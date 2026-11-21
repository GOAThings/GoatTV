@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.media.AutoCrop
import cc.slokker.itv.media.Codec
import cc.slokker.itv.media.CropRect
import cc.slokker.itv.media.TrackKind
import cc.slokker.itv.media.TeletextPage
import cc.slokker.itv.media.VideoInfo
import cc.slokker.itv.media.AudioLabelInput
import cc.slokker.itv.media.AudioRole
import cc.slokker.itv.media.AudioTrackLabel
import cc.slokker.itv.media.TsTrack
import cc.slokker.itv.ui.player.goatcore.GoatCorePlayer
import cc.slokker.itv.ui.player.goatcore.TsSeek
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException

/**
 * O motor próprio ([GoatCorePlayer], experimental) com a interface [Player] do Media3, como o [VlcPlayer]: o resto do
 * leitor (tentativas, failovers, reconexão) não precisa de saber qual está a tocar. A ligação ao fornecedor é a mesma
 * fonte HTTP do ExoPlayer (com o portão de uma ligação de cada vez), por isso os erros HTTP chegam como chegam do
 * ExoPlayer. Um formato que este motor ou o aparelho não toca é um erro de descodificação: o leitor passa ao VLC.
 */
internal class GoatCoreMediaPlayer(
    /** Vídeo por software (ver [GoatCorePlayer]). */
    private val softwareVideo: Boolean,
    /** Só no debug (ver DebugEngine.capture). */
    private val capture: java.io.File? = null,
    /** AC-3/E-AC-3 em bruto para a TV ou o recetor (ver [GoatCorePlayer.passthrough]). */
    private val passthrough: Boolean = false,
    /** O HDR: a box decide se a TV aceita o do canal (Definições → HDR; ver HdrType.needsSdr). */
    private val needsSdr: (Format) -> Boolean = { false },
    /** Os canais em UDP/RTP (ver [cc.slokker.itv.ui.player.goatcore.UdpTsDataSource]). */
    val udp: DataSource.Factory? = null,
    /** Nivelar o volume (Prefs.AudioLevel; ver [GoatCorePlayer.audioLevel]). */
    private val audioLevel: String = Prefs.AudioLevel.OFF,
    /** Definições → Desentrelaçar (Prefs.Deinterlace; ver [GoatCorePlayer.deinterlace]). */
    private val deinterlace: String = cc.slokker.itv.data.Prefs.Deinterlace.OFF,
    /** O atraso do som à mão e o acerto automático da saída (ver [GoatCorePlayer.audioDelayMs] e audioDelayAuto). */
    private val audioDelayMs: Int = 0,
    private val audioDelayAuto: Boolean = true,
    /** Vozes mais claras, e se a saída de som só leva estéreo (ver [GoatCorePlayer.audioVoices] e stereoOutput). */
    private val audioVoices: Boolean = false,
    private val stereoOutput: () -> Boolean = { true },
    /** Aumentar a imagem (Prefs.Upscale; ver [GoatCorePlayer.upscale]): "off" fora da imagem principal. */
    private val upscale: String = Prefs.Upscale.OFF,
    /** Cortar as barras pretas (Prefs.autoCrop; ver [GoatCorePlayer.autoCrop]): só na imagem principal. */
    private val autoCrop: Boolean = false,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    /**
     * Definições → Imagem (ver [GoatCorePlayer.picture]): muda-se com o canal a dar. Passar de tudo no 0 para algum
     * ajuste (ou voltar) só vale ao abrir o canal (quem cria o player recria-o, ver PlayerBuild.picture).
     */
    @Volatile var picture: cc.slokker.itv.media.PictureLook = cc.slokker.itv.media.PictureLook()

    /**
     * A proporção deste canal é Ajustar (Prefs.Aspect.FIT; quem mostra diz qual é, ver rememberChannelAspect): só aí
     * se cortam as barras pretas. Nos outros modos a imagem já enche o ecrã.
     */
    var fitMode: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            applyCrop()
            invalidateState()
        }

    /** O tamanho do vídeo como o motor o dá (sem o corte das barras pretas, ver [applyCrop]). */
    private var baseVideoSize = VideoSize.UNKNOWN

    /** Cortar as barras pretas: a parte do quadro com imagem, a última que o motor disse. */
    private var activeArea = CropRect.FULL

    /** A vista que leva a imagem (a VideoView, à volta da do vídeo e da que lhe dá a proporção): o "ecrã" do corte. */
    private var cropBox: android.view.View? = null
    private val cropBoxLayout = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        if (r - l != or - ol || b - t != ob - ot) {
            applyCrop()
            invalidateState()
        }
    }

    /**
     * O corte das barras pretas: o que mostrar do quadro para a parte com imagem encher a vista (AutoCrop.window), para
     * o motor (a GPU desenha só isso) e para a vista (a proporção do vídeo passa a ser a desse rectângulo: a proporção
     * do píxel leva a diferença, e a largura e a altura ficam as do vídeo, para quem as mostra).
     */
    private fun applyCrop() {
        val base = baseVideoSize
        val par = base.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        val box = cropBox
        val screen = if (box != null && box.width > 0 && box.height > 0) box.width.toFloat() / box.height else 16f / 9f
        val window = if (autoCrop && fitMode && base.width > 0 && base.height > 0) {
            AutoCrop.window(activeArea, base.width * par / base.height, screen)
        } else {
            CropRect.FULL
        }
        core?.crop = window
        videoSize = if (window.isFull) base else VideoSize(base.width, base.height, par * window.width / window.height)
    }

    /** O que o SPS do canal diz (tamanho, cadência, cor): vai na faixa de vídeo (selo HDR, AFR, a TV em HDR). */
    private var videoInfo: VideoInfo? = null

    /** O vídeo traz legendas fechadas (CEA-608): há a faixa "CC1" no menu. */
    private var captionsFound = false

    private val main = Handler(Looper.getMainLooper())

    /** O player do canal principal: o HLS dele tem o menu da qualidade (ver HlsQualityControl); o segundo canal não. */
    var primary = false

    private var source: DataSource.Factory? = null
    /** O canal, para o ganho do nivelamento (ver [setStream]). */
    private var levelKey = ""
    private var uri: Uri? = null
    private var hls = false
    private var mediaItem: MediaItem? = null
    private var core: GoatCorePlayer? = null

    // Para "Testar este canal": o que se viu das ligações, as estatísticas do motor e a hora da primeira imagem.
    internal var connection: cc.slokker.itv.ui.player.engine.ConnectionProbe? = null
    internal val coreStats: GoatCorePlayer.Stats? get() = core?.stats
    internal val isHls: Boolean get() = hls
    internal val streamInfo: VideoInfo? get() = videoInfo
    /** Os números do motor agora, para "Informação do stream"; null sem motor a dar. */
    internal fun coreSnapshot(): cc.slokker.itv.ui.player.goatcore.GoatCoreSnapshot? = core?.snapshot()

    /**
     * Os contadores do motor agora, para o FreezeWatch (ver ChannelPlayback): as imagens mostradas e o tempo da última,
     * o som, os bytes e as amostras. A playlist do HLS em direto só no canal principal (é o leitor dele que a mede).
     * null sem motor a dar.
     */
    internal fun freezeSample(): cc.slokker.itv.ui.player.engine.FreezeSample? {
        val c = core ?: return null
        val s = c.stats
        return cc.slokker.itv.ui.player.engine.FreezeSample(
            videoFrames = s.framesRendered.toLong(),
            videoPtsMs = c.shownPtsMs,
            audioFrames = s.audioFrames.toLong(),
            bytes = s.bytesRead,
            samples = s.samples,
            hasVideo = c.hasVideo && !audioOnly,
            hasAudio = c.selectedAudio >= 0,
            concealing = c.concealing,
            paused = !playWhenReady,
            playlistStuckMs = if (primary) cc.slokker.itv.playback.HlsLiveStall.stuckMs(android.os.SystemClock.elapsedRealtime()) else -1L,
            playlistTargetMs = cc.slokker.itv.playback.HlsLiveStall.targetMs(),
        )
    }
    @Volatile internal var firstFrameMs = -1L
    private var generation = 0

    /** O motor anterior a fechar (ver [GoatCorePlayer.previous]). */
    private var closing: Thread? = null

    private var playbackState = Player.STATE_IDLE
    private var playWhenReady = false
    private var error: PlaybackException? = null
    private var videoSize = VideoSize.UNKNOWN
    private var tracks = Tracks.EMPTY
    private var volume = 1f
    private var params = TrackSelectionParameters.DEFAULT
    private var firstFrame = false
    private var surfaceView: SurfaceView? = null

    /** Em vez da [surfaceView]: uma TextureView (as imagens pequenas, que levam cantos arredondados) e a sua Surface. */
    private var textureView: TextureView? = null
    private var textureSurface: Surface? = null
    private var pendingStart = false
    private var lastPositionMs = 0L
    private var tsTracks: List<TsTrack> = emptyList()

    /** A faixa de áudio pedida (o PID); -1 = a que o motor escolheu. */
    private var chosenAudio = -1

    /** As legendas escolhidas (o PID); -1 = desligadas. */
    private var chosenText = -1
    private var startPositionMs = -1L

    /**
     * O endereço que já se viu ser só de som (uma rádio: ver RadioRules). Nele o motor não precisa da imagem: continua
     * sem ela (o telemóvel em segundo plano, o ecrã bloqueado) e volta a abrir sem ela (a reconexão em segundo plano).
     */
    private var audioOnlyUrl: String? = null

    /** O canal a tocar é só de som. */
    val audioOnly: Boolean get() = audioOnlyUrl != null && audioOnlyUrl == uri?.toString()

    /** Uma superfície que ninguém mostra, para abrir uma rádio sem a imagem (o motor pede uma; só o vídeo a usa). */
    private var blank: Pair<SurfaceTexture, Surface>? = null

    private fun blankSurface(): Surface = (blank ?: SurfaceTexture(0).let { it to Surface(it) }.also { blank = it }).second

    /** O formato do vídeo a tocar (o do SPS: tamanho, cadência e cor), como o ExoPlayer.videoFormat. */
    val videoFormat: Format?
        get() = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)

    /** A velocidade pedida (fica para o motor seguinte, ao mudar de ponto ou de canal). */
    private var speed = 1f

    /**
     * O buffer automático (Prefs.Buffer.AUTO, só no direto): o endereço do canal no fornecedor (a memória do nível é
     * por fornecedor), ou null com um dos buffers fixos. Fica ao mudar de ponto no anel; quem abre o canal põe-no.
     */
    var adaptiveUrl: String? = null

    /** A ver o direto (não em pausa nem atrás dele no anel): só aí o buffer automático aprende e acelera. */
    var atLive: () -> Boolean = { true }

    /** O que está à frente diz se a ligação acompanha (não num HLS, que chega aos segmentos; ver AdaptiveBuffer.onPlaying). */
    var adaptiveDips = true

    /** O buffer automático do motor a dar (ver [adapt]); novo em cada motor. */
    private var adaptive: cc.slokker.itv.playback.AdaptiveBuffer? = null

    /** Parado depois de uma paragem à espera de ter o alvo do [adaptive] à frente (desde quando, elapsedRealtime). */
    private var holding = false
    private var holdingSince = 0L

    /** No quarto de segundo anterior estava a sair imagem: só então uma imagem que falta é uma paragem (não ao sair da pausa). */
    private var wasOutputting = false

    /** A velocidade a mais para gastar o que está à frente (1 = nenhuma; ver [adapt]). */
    private var drain = 1f

    /** Onde começar dentro do ficheiro (as gravações e o catch-up do Stalker; ver [TsSeek]); 0 = do início. */
    private var startMs = 0L

    /** Com [startMs]: o PTS do início do ficheiro, para a posição contar desde lá (como no ExoPlayer). */
    @Volatile private var firstPts = -1L

    /** Num HLS (VOD) aberto a meio: onde começa o segmento por onde se começou (ms). */
    @Volatile private var offsetMs = 0L

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (pendingStart) start()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        // Sem superfície o descodificador não tem onde desenhar: pára, e o leitor volta a abrir quando voltar.
        // Uma rádio não desenha nada: continua.
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (audioOnly) return
            stopCore()
            if (playbackState != Player.STATE_IDLE) pendingStart = true
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            textureSurface = Surface(texture)
            if (pendingStart) start()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (!audioOnly) {
                stopCore()
                if (playbackState != Player.STATE_IDLE) pendingStart = true
            }
            releaseTextureSurface()
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private fun releaseTextureSurface() {
        textureSurface?.release()
        textureSurface = null
    }

    /**
     * A superfície do vídeo fica sempre do tamanho da vista (1080p nas boxes 4K, cuja interface é 1080p): o aumento
     * da imagem vai até aí, e o sistema estica para o ecrã. Chegou a pedir-se o ecrã a sério (3840×2160), mas na SHIELD
     * (2026-11-20) nem o "Nítido" aguentou um canal 1080i para 4K; se a box mais forte não aguenta, nenhuma aguenta.
     */
    private val surfaceLayout = android.view.View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> fitSurface(v as SurfaceView) }

    private fun fitSurface(v: SurfaceView) {
        if (fixedSize[0] == 0 && fixedSize[1] == 0) return
        fixedSize[0] = 0
        fixedSize[1] = 0
        v.holder.setSizeFromLayout()
    }

    private val fixedSize = IntArray(2)

    /** O tamanho da superfície do vídeo agora (ver [GoatCorePlayer.surfaceSize]). */
    private fun videoSurfaceSize(): IntArray? {
        surfaceView?.holder?.surfaceFrame?.takeIf { it.width() > 0 && it.height() > 0 }?.let { return intArrayOf(it.width(), it.height()) }
        return textureView?.takeIf { it.width > 0 && it.height > 0 }?.let { intArrayOf(it.width, it.height) }
    }

    /** Larga a imagem atual (SurfaceView ou TextureView). */
    private fun detachOutput() {
        surfaceView?.holder?.removeCallback(surfaceCallback)
        surfaceView?.let { v ->
            if (upscale != Prefs.Upscale.OFF) {
                v.removeOnLayoutChangeListener(surfaceLayout)
                v.holder.setSizeFromLayout()
                fixedSize.fill(0)
            }
        }
        surfaceView = null
        cropBox?.removeOnLayoutChangeListener(cropBoxLayout)
        cropBox = null
        textureView?.let { if (it.surfaceTextureListener === textureListener) it.surfaceTextureListener = null }
        textureView = null
        releaseTextureSurface()
    }

    /**
     * A stream a tocar: [source] é a ligação (a do ExoPlayer, com o portão); começa com [prepare]. [startMs]: num TS
     * gravado, o ponto onde começar. [levelKey]: o canal para o nivelamento do volume (o anel da pausa tem outro
     * endereço; sem ele, um salto no anel fica com o canal que já estava).
     */
    fun setStream(url: String, source: DataSource.Factory, hls: Boolean, startMs: Long = 0L, levelKey: String? = null) {
        stopCore()
        this.source = source
        this.uri = Uri.parse(url)
        this.levelKey = levelKey ?: if (url.startsWith("timeshift:")) this.levelKey else url
        this.hls = hls
        this.startMs = startMs
        firstPts = -1L
        firstFrameMs = -1L
        offsetMs = 0L
        mediaItem = MediaItem.fromUri(url)
        chosenAudio = -1
        chosenText = -1
        cues = androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO
        tsTracks = emptyList()
        captionsFound = false
        playbackState = Player.STATE_IDLE
        error = null
        tracks = Tracks.EMPTY
        videoSize = VideoSize.UNKNOWN
        baseVideoSize = VideoSize.UNKNOWN
        activeArea = CropRect.FULL
        invalidateState()
    }

    private fun start() {
        val surface = (textureSurface ?: surfaceView?.holder?.surface)?.takeIf { it.isValid } ?: if (audioOnly) blankSurface() else null
        if (surface == null) {
            pendingStart = true
            return
        }
        pendingStart = false
        val factory = source ?: return
        val uri = uri ?: return
        val g = ++generation
        startPositionMs = -1
        // Um motor novo mede as barras de novo.
        activeArea = CropRect.FULL
        applyCrop()
        val player = GoatCorePlayer(surface, softwareVideo = softwareVideo, capture = capture, previous = closing, passthrough = passthrough,
            audioLevel = audioLevel, levelKey = levelKey,
            audioDelayMs = audioDelayMs, audioDelayAuto = audioDelayAuto, audioVoices = audioVoices, stereoOutput = stereoOutput,
            deinterlace = deinterlace, picture = { picture }, upscale = upscale, surfaceSize = ::videoSurfaceSize, autoCrop = autoCrop,
            needsSdr = { info -> needsSdr(videoFormat(info).build()) }, listener = object : GoatCorePlayer.Listener {
            override fun onFirstFrame(ms: Long) = post(g) {
                firstFrameMs = ms
                firstFrame = true
                playbackState = Player.STATE_READY
            }

            override fun onTracks(tracks: List<TsTrack>) = post(g) {
                tsTracks = tracks
                val url = uri?.toString()
                if (cc.slokker.itv.playback.RadioRules.audioOnlyTracks(tracks)) audioOnlyUrl = url else if (audioOnlyUrl == url) audioOnlyUrl = null
                this@GoatCoreMediaPlayer.tracks = tracksOf(tracks)
            }

            override fun onError(why: String, unsupported: Boolean, cause: Throwable?) = post(g) {
                val code = when {
                    // Pediu-se como HLS e não é: o outro formato (ts ⇄ m3u8) no GoatCore, não o VLC.
                    cause is cc.slokker.itv.ui.player.goatcore.NotHlsPlaylist -> PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                    unsupported -> PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                    cause is HttpDataSource.InvalidResponseCodeException -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    cause is IOException -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    else -> PlaybackException.ERROR_CODE_UNSPECIFIED
                }
                fail(PlaybackException("GoatCore: $why", cause, code))
            }

            override fun onEnded() = post(g) {
                stopCore()
                playbackState = Player.STATE_ENDED
            }

            override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) = post(g) {
                baseVideoSize = VideoSize(width, height, pixelRatio)
                applyCrop()
            }

            override fun onActiveArea(area: CropRect) = post(g) {
                activeArea = area
                applyCrop()
            }

            override fun onCaptionsFound() = post(g) {
                captionsFound = true
                this@GoatCoreMediaPlayer.tracks = tracksOf(tsTracks)
            }

            override fun onVideoInfo(info: VideoInfo) = post(g) {
                videoInfo = info
                this@GoatCoreMediaPlayer.tracks = tracksOf(tsTracks)
            }

            override fun onCues(atUs: Long, cues: List<androidx.media3.common.text.Cue>, durationUs: Long) = showCues(g, atUs, cues, durationUs)
        })
        core = player
        adaptive = adaptiveUrl?.let { url ->
            val key = cc.slokker.itv.playback.AdaptiveBuffer.providerKey(url)
            cc.slokker.itv.playback.AdaptiveBuffer(adaptiveMemory.startLevel(key)).also { adaptiveKey = key }
        }
        holding = false
        wasOutputting = false
        drain = 1f
        main.removeCallbacks(starvation)
        main.postDelayed(starvation, 250)
        player.setVolume(effectiveVolume())
        player.setSpeed(speed)
        player.setPaused(!playWhenReady)
        // HLS: os segmentos TS um atrás do outro (ver HlsInputStream); o resto é o stream direto.
        val from = startMs
        player.open {
            if (hls) return@open cc.slokker.itv.ui.player.goatcore.HlsInputStream(factory, uri.toString(), from, onStart = { offsetMs = it }, primary = primary)
            // Num ficheiro com ponto de partida: o byte onde ele fica (sem dar, do início).
            val plan = if (from > 0) runCatching { TsSeek.plan(factory, uri, from) }.onFailure { android.util.Log.w("GoatCore", "seek: ${it.message}") }.getOrNull() else null
            plan?.let { firstPts = it.firstPts }
            DataSourceInputStream(factory.createDataSource(), DataSpec.Builder().setUri(uri).setPosition(plan?.position ?: 0).build()).also { it.open() }
        }
        playbackState = Player.STATE_BUFFERING
    }

    private var cues = androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO

    /**
     * De quarto em quarto de segundo, a dar: sem imagem há mais de [STARVED_MS], o estado passa a "a carregar" (como o
     * ExoPlayer quando o buffer esvazia), e o leitor conta a paragem e volta a ligar se não recuperar (ChannelPlayback).
     */
    private val starvation = object : Runnable {
        override fun run() {
            val c = core ?: return
            // Antes da primeira imagem fica "a carregar": um canal que nunca deu não pode parecer que deu (senão uma
            // falha ao abrir era tratada como uma queda e voltava a ligar, em vez de ir ao failover).
            if (c.started && playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)) {
                val next = if (c.starvedMs > STARVED_MS) Player.STATE_BUFFERING else Player.STATE_READY
                if (next != playbackState && (next == Player.STATE_BUFFERING || c.starvedMs < STARVED_MS)) {
                    playbackState = next
                    invalidateState()
                }
            }
            adapt(c)
            main.postDelayed(this, 250)
        }
    }

    private var adaptiveKey = ""

    /**
     * O buffer automático, de quarto em quarto de segundo (ver AdaptiveBuffer): numa paragem a meio, o motor fica em
     * pausa até ter o alvo à frente (no máximo [HOLD_MAX_MS]), em vez de dar aos soluços; a dar, aprende com o que
     * está à frente e, depois de descer de nível, gasta o que está a mais a tocar um pouco mais depressa (o som PCM
     * mantém o tom; em passthrough não se acelera). O motor mostra a primeira imagem logo, como sempre.
     */
    private fun adapt(c: GoatCorePlayer) {
        val a = adaptive ?: return
        if (!c.started || !playWhenReady || !c.hasVideo) {
            wasOutputting = false
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val lead = c.bufferedMs
        if (holding) {
            if (lead >= a.targetMs() || now - holdingSince > HOLD_MAX_MS) {
                holding = false
                wasOutputting = false
                c.setPaused(false)
            }
            return
        }
        val live = atLive()
        val outputting = c.starvedMs <= STARVED_MS
        val stalled = wasOutputting && !outputting
        wasOutputting = outputting
        if (stalled) {
            if (live && a.onStall(now)) adaptiveMemory.remember(adaptiveKey, a.level)
            setDrain(c, 1f)
            holding = true
            holdingSince = now
            c.setPaused(true)
            return
        }
        var want = 1f
        if (live && speed == 1f) {
            if (a.onPlaying(now, lead, dips = adaptiveDips)) adaptiveMemory.remember(adaptiveKey, a.level)
            want = a.rate(lead, canDrain = !c.bitstreaming)
        }
        setDrain(c, want)
    }

    private fun setDrain(c: GoatCorePlayer, rate: Float) {
        if (rate == drain) return
        drain = rate
        c.setSpeed(speed * rate)
    }

    /** Legendas à espera da hora delas no relógio do motor: o motor lê adiantado (até ~20 s) e chegam antes. */
    private class PendingCues(val atUs: Long, val group: androidx.media3.common.text.CueGroup, val durationUs: Long)

    private val pendingCues = ArrayDeque<PendingCues>()

    /** Quando tirar as legendas no ecrã (relógio do motor), ou Long.MAX_VALUE se ficam até às seguintes. */
    private var cuesEndUs = Long.MAX_VALUE

    /** Mostra [list] quando o relógio do motor chegar a [atUs] (Long.MIN_VALUE = já), e tira-as ao fim de [durationUs]. */
    private fun showCues(g: Int, atUs: Long, list: List<androidx.media3.common.text.Cue>, durationUs: Long) {
        val group = androidx.media3.common.text.CueGroup(list, atUs.coerceAtLeast(0))
        main.post {
            if (g != generation) return@post
            if (atUs == Long.MIN_VALUE) {
                pendingCues.clear()
                setCues(group, durationUs, Long.MIN_VALUE)
            } else {
                pendingCues.addLast(PendingCues(atUs, group, durationUs))
            }
            main.removeCallbacks(cueTicker)
            cueTicker.run()
        }
    }

    private fun setCues(group: androidx.media3.common.text.CueGroup, durationUs: Long, atUs: Long) {
        cues = group
        cuesEndUs = if (group.cues.isNotEmpty() && durationUs > 0 && durationUs != C.TIME_UNSET && atUs != Long.MIN_VALUE) atUs + durationUs else Long.MAX_VALUE
        invalidateState()
    }

    /** Põe e tira as legendas pelo relógio do motor (em pausa, ele pára e elas também). */
    private val cueTicker = object : Runnable {
        override fun run() {
            val now = core?.nowUs ?: return
            if (now == Long.MIN_VALUE) {
                if (pendingCues.isNotEmpty()) main.postDelayed(this, 100)
                return
            }
            while (pendingCues.isNotEmpty() && pendingCues.first().atUs <= now) {
                val p = pendingCues.removeFirst()
                setCues(p.group, p.durationUs, p.atUs)
            }
            if (now >= cuesEndUs) setCues(androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO, 0, Long.MIN_VALUE)
            val next = minOf(pendingCues.firstOrNull()?.atUs ?: Long.MAX_VALUE, cuesEndUs)
            if (next != Long.MAX_VALUE) main.postDelayed(this, ((next - now) / 1000).coerceIn(10, 250))
        }
    }

    private fun post(g: Int, change: () -> Unit) {
        main.post {
            if (g != generation) return@post
            change()
            invalidateState()
        }
    }

    /** Um endereço que o motor não lê (RTMP, RTSP, UDP…): falha já como formato não suportado, e o VLC fica com ele. */
    fun unsupported(why: String) {
        stopCore()
        fail(PlaybackException("GoatCore: $why", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }

    private fun fail(e: PlaybackException) {
        stopCore()
        error = e
        playbackState = Player.STATE_IDLE
        invalidateState()
    }

    private fun stopCore() {
        generation++
        main.removeCallbacks(starvation)
        main.removeCallbacks(cueTicker)
        pendingCues.clear()
        cuesEndUs = Long.MAX_VALUE
        val c = core ?: return
        core = null
        lastPositionMs = position(c)
        // O stop espera pelas threads do motor: fora do thread principal. O seguinte espera por ele.
        closing = Thread({ c.stop() }, "goatcore-stop").also { it.start() }
    }

    private fun position(c: GoatCorePlayer): Long {
        val p = c.positionMs
        if (p < 0) return lastPositionMs
        firstPts.takeIf { it >= 0 }?.let { return TsSeek.positionMs(p, it) }
        if (startPositionMs < 0) startPositionMs = p
        return offsetMs + p - startPositionMs
    }

    /**
     * As faixas do canal como [Tracks] do Media3: o menu de áudio e a escolha pela língua preferida (TrackPick) são os
     * mesmos dos outros motores. As de áudio sem descodificador no aparelho vêm como não suportadas.
     */
    private fun tracksOf(list: List<TsTrack>): Tracks {
        val playing = chosenAudio.takeIf { pid -> list.any { it.pid == pid } } ?: core?.selectedAudio ?: -1
        val strings = cc.slokker.itv.ui.i18n.I18n.s
        fun label(language: String?) = language?.let { cc.slokker.itv.data.Languages.code(it) }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(strings.locale).replaceFirstChar { c -> c.titlecase(strings.locale) } }
        // As faixas de áudio com nome claro: língua · papel · canais (o formato só se duas ficassem iguais), ver AudioTrackLabel.
        val audioTracks = list.filter { it.kind == TrackKind.AUDIO }
        fun channelsOf(t: TsTrack) = core?.channelsOf(t.pid)?.takeIf { it > 0 } ?: t.channels
        val audioLabels = AudioTrackLabel.labels(
            audioTracks.map { t -> AudioLabelInput(label(t.language ?: t.name).orEmpty(), t.role, channelsOf(t), codecName(t.codec)) },
            strings.audioLabelWords,
        ).withIndex().associate { (i, l) -> audioTracks[i].pid to l }
        val groups = list.filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO || it.codec == Codec.DVB_SUBTITLE || it.codec == Codec.WEBVTT }.map { t ->
            val id = "goatcore:${t.pid}"
            val info = videoInfo.takeIf { t.kind == TrackKind.VIDEO }
            val format = (info?.let { videoFormat(it) } ?: Format.Builder())
                .setId(id)
                .setSampleMimeType(mimeOf(t.codec))
                .setLanguage(t.language)
                .setLabel(if (t.kind == TrackKind.AUDIO) audioLabels[t.pid] else label(t.language))
                .apply {
                    if (t.kind == TrackKind.AUDIO) {
                        channelsOf(t).takeIf { it > 0 }?.let { setChannelCount(it) }
                        setRoleFlags(roleFlagsOf(t.role))
                    }
                }
                .build()
            val supported = t.kind != TrackKind.AUDIO || GoatCorePlayer.playable(t.codec, passthrough)
            val selected = t.kind == TrackKind.VIDEO || t.pid == playing || (t.kind == TrackKind.SUBTITLE && t.pid == chosenText)
            Tracks.Group(TrackGroup(id, format), false, intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(selected))
        }
        // O teletexto: cada página de legendas é uma faixa (a chave junta o PID e a página, ver GoatCorePlayer.teletextKey).
        val teletext = list.filter { it.codec == Codec.TELETEXT }.flatMap { t ->
            TeletextPage.of(t.config).filter { it.isSubtitle }.map { page ->
                val key = GoatCorePlayer.teletextKey(t.pid, page.page)
                val id = "goatcore:$key"
                val language = page.language ?: t.language
                val format = Format.Builder()
                    .setId(id)
                    .setSampleMimeType(TELETEXT_MIME)
                    .setLanguage(language)
                    .setLabel(listOfNotNull(label(language), "${page.page.toString(16)}").joinToString(" · "))
                    .setRoleFlags(if (page.type == 5) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
                    .build()
                Tracks.Group(TrackGroup(id, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(key == chosenText))
            }
        }
        // As legendas fechadas que vêm dentro do vídeo (os canais americanos), quando as há.
        val captions = if (!captionsFound) emptyList() else {
            val key = GoatCorePlayer.CAPTIONS
            val id = "goatcore:$key"
            val format = Format.Builder().setId(id).setSampleMimeType(MimeTypes.APPLICATION_CEA608).setLabel("CC1").build()
            listOf(Tracks.Group(TrackGroup(id, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(key == chosenText)))
        }
        return Tracks(groups + teletext + captions)
    }

    /** Os role flags do Media3 para o papel de uma faixa de áudio (o inverso está em trackInfo, ver AudioRole.detail). */
    private fun roleFlagsOf(role: Int): Int = when (role) {
        AudioRole.DESCRIPTION -> C.ROLE_FLAG_DESCRIBES_VIDEO
        AudioRole.HEARING_IMPAIRED -> C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY
        AudioRole.CLEAN_EFFECTS -> C.ROLE_FLAG_SUPPLEMENTARY
        AudioRole.ORIGINAL -> C.ROLE_FLAG_MAIN
        else -> 0
    }

    private fun codecName(codec: Codec): String = when (codec) {
        Codec.AAC_ADTS, Codec.AAC_LATM -> "AAC"
        Codec.AC3 -> "AC-3"
        Codec.EAC3 -> "E-AC-3"
        Codec.MPEG_AUDIO -> "MP2"
        else -> ""
    }

    /** Um Format com o que o SPS diz (para o selo HDR, o AFR e a decisão de converter para SDR). */
    private fun videoFormat(info: VideoInfo): Format.Builder = Format.Builder()
        .setWidth(info.width)
        .setHeight(info.height)
        .apply { if (info.fps > 0) setFrameRate(info.fps) }
        .setColorInfo(
            androidx.media3.common.ColorInfo.Builder()
                .setColorSpace(androidx.media3.common.ColorInfo.isoColorPrimariesToColorSpace(info.primaries))
                .setColorTransfer(androidx.media3.common.ColorInfo.isoTransferCharacteristicsToColorTransfer(info.transfer))
                .setColorRange(if (info.fullRange) C.COLOR_RANGE_FULL else C.COLOR_RANGE_LIMITED)
                .setLumaBitdepth(info.bitDepth)
                .setChromaBitdepth(info.bitDepth)
                .build(),
        )

    private fun mimeOf(codec: Codec): String = when (codec) {
        Codec.H264 -> MimeTypes.VIDEO_H264
        Codec.HEVC -> MimeTypes.VIDEO_H265
        Codec.MPEG2_VIDEO -> MimeTypes.VIDEO_MPEG2
        Codec.AAC_ADTS, Codec.AAC_LATM -> MimeTypes.AUDIO_AAC
        Codec.AC3 -> MimeTypes.AUDIO_AC3
        Codec.EAC3 -> MimeTypes.AUDIO_E_AC3
        Codec.MPEG_AUDIO -> MimeTypes.AUDIO_MPEG_L2
        Codec.DVB_SUBTITLE -> MimeTypes.APPLICATION_DVBSUBS
        Codec.WEBVTT -> MimeTypes.TEXT_VTT
        else -> MimeTypes.VIDEO_MP2T
    }

    private fun effectiveVolume(): Float = if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes) 0f else volume

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_TEXT,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_SPEED_AND_PITCH,
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlayerError(error)
            .setVideoSize(videoSize)
            .setTrackSelectionParameters(params)
            .setVolume(volume)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setCurrentCues(cues)
        val item = mediaItem
        if (item == null) {
            builder.setPlaybackState(Player.STATE_IDLE)
        } else {
            builder
                .setPlaylist(
                    listOf(
                        MediaItemData.Builder("goatcore")
                            .setMediaItem(item)
                            .setTracks(tracks)
                            .setIsSeekable(false)
                            .setIsDynamic(true)
                            .setDurationUs(C.TIME_UNSET)
                            .build(),
                    ),
                )
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(playbackState)
                .setContentPositionMs { core?.let(::position) ?: lastPositionMs }
                .setTotalBufferedDurationMs { core?.bufferedMs ?: 0L }
        }
        if (firstFrame) {
            builder.setNewlyRenderedFirstFrame(true)
            firstFrame = false
        }
        return builder.build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (source == null) return Futures.immediateVoidFuture()
        error = null
        stopCore()
        start()
        if (error == null) playbackState = Player.STATE_BUFFERING
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        // À espera do alvo do buffer automático: continua parado até lá (ver [adapt]).
        core?.setPaused(!playWhenReady || holding)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopCore()
        pendingStart = false
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        stopCore()
        pendingStart = false
        detachOutput()
        blank?.let { (texture, surface) ->
            surface.release()
            texture.release()
        }
        blank = null
        main.removeCallbacksAndMessages(null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (videoOutput != surfaceView && videoOutput != textureView && (videoOutput is SurfaceView || videoOutput is TextureView)) {
            // Parar antes de largar a Surface antiga (o descodificador ainda desenha nela). Uma rádio não desenha:
            // continua a dar, sem recomeçar.
            if (core != null && !audioOnly) {
                stopCore()
                pendingStart = true
            }
            detachOutput()
            if (videoOutput is SurfaceView) {
                surfaceView = videoOutput
                videoOutput.holder.addCallback(surfaceCallback)
                if (upscale != Prefs.Upscale.OFF) {
                    videoOutput.addOnLayoutChangeListener(surfaceLayout)
                    fitSurface(videoOutput)
                }
            } else if (videoOutput is TextureView) {
                textureView = videoOutput
                videoOutput.surfaceTextureListener = textureListener
                videoOutput.surfaceTexture?.let { textureSurface = Surface(it) }
            }
            if (autoCrop) {
                // A VideoView: a vista do vídeo está dentro da que lhe dá a proporção, e essa dentro dela.
                cropBox = ((videoOutput as android.view.View).parent as? android.view.View)?.parent as? android.view.View
                cropBox?.addOnLayoutChangeListener(cropBoxLayout)
            }
            // O ecrã trocou de vista (acontece logo ao abrir): o descodificador da superfície antiga ficava parado (os do
            // Android só descodificam para uma superfície que alguém mostra), por isso recomeça-se na nova.
            if (pendingStart) start()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput == surfaceView || videoOutput == textureView) {
            // Parar antes de largar a Surface da TextureView (o descodificador ainda desenha nela). Uma rádio continua.
            if (core != null && !audioOnly) {
                stopCore()
                pendingStart = true
            }
            detachOutput()
        }
        return Futures.immediateVoidFuture()
    }

    /** "Voltar ao direto": recomeçar a stream é o que leva ao ponto atual. */
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs == C.TIME_UNSET && playbackState != Player.STATE_IDLE) {
            stopCore()
            start()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        drain = 1f
        core?.setSpeed(speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        core?.setVolume(effectiveVolume())
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        params = trackSelectionParameters
        core?.setVolume(effectiveVolume())
        // A faixa de áudio escolhida (no menu, ou pela língua das Definições).
        val pid = params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.mediaTrackGroup?.id
            ?.removePrefix("goatcore:")?.toIntOrNull()
        if (pid != null && pid != chosenAudio) {
            chosenAudio = pid
            core?.selectAudio(pid)
            tracks = tracksOf(tsTracks)
        }
        // As legendas: a escolhida (no menu, ou pela língua), ou nenhumas com o tipo desligado.
        val textOff = C.TRACK_TYPE_TEXT in params.disabledTrackTypes
        val text = if (textOff) -1 else params.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_TEXT }?.mediaTrackGroup?.id
            ?.removePrefix("goatcore:")?.toIntOrNull() ?: chosenText
        if (text != chosenText) {
            chosenText = text
            core?.selectText(text)
            tracks = tracksOf(tsTracks)
        }
        return Futures.immediateVoidFuture()
    }

    companion object {
        /** Sem imagem há mais do que isto, a dar, é uma paragem à espera de dados. */
        private const val STARVED_MS = 700L

        /** Parado à espera do alvo do buffer automático no máximo isto; depois dá com o que houver. */
        private const val HOLD_MAX_MS = 8_000L

        /** O nível do buffer automático aprendido por fornecedor (só na thread principal, como os players). */
        private val adaptiveMemory = cc.slokker.itv.playback.AdaptiveBufferMemory()

        /** O Media3 não tem tipo para o teletexto: "text/…" basta para ser uma faixa de legendas. */
        const val TELETEXT_MIME = "text/x-dvb-teletext"
    }
}
