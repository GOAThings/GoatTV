@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import cc.slokker.itv.media.Codec
import cc.slokker.itv.media.TrackKind
import cc.slokker.itv.media.TeletextPage
import cc.slokker.itv.media.VideoInfo
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
) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** O que o SPS do canal diz (tamanho, cadência, cor): vai na faixa de vídeo (selo HDR, AFR, a TV em HDR). */
    private var videoInfo: VideoInfo? = null

    /** O vídeo traz legendas fechadas (CEA-608): há a faixa "CC1" no menu. */
    private var captionsFound = false

    private val main = Handler(Looper.getMainLooper())

    private var source: DataSource.Factory? = null
    private var uri: Uri? = null
    private var hls = false
    private var mediaItem: MediaItem? = null
    private var core: GoatCorePlayer? = null
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
    private var pendingStart = false
    private var lastPositionMs = 0L
    private var tsTracks: List<TsTrack> = emptyList()

    /** A faixa de áudio pedida (o PID); -1 = a que o motor escolheu. */
    private var chosenAudio = -1

    /** As legendas escolhidas (o PID); -1 = desligadas. */
    private var chosenText = -1
    private var startPositionMs = -1L

    /** O formato do vídeo a tocar (o do SPS: tamanho, cadência e cor), como o ExoPlayer.videoFormat. */
    val videoFormat: Format?
        get() = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0)

    /** A velocidade pedida (fica para o motor seguinte, ao mudar de ponto ou de canal). */
    private var speed = 1f

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
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            stopCore()
            if (playbackState != Player.STATE_IDLE) pendingStart = true
        }
    }

    /**
     * A stream a tocar: [source] é a ligação (a do ExoPlayer, com o portão); começa com [prepare]. [startMs]: num TS
     * gravado, o ponto onde começar.
     */
    fun setStream(url: String, source: DataSource.Factory, hls: Boolean, startMs: Long = 0L) {
        stopCore()
        this.source = source
        this.uri = Uri.parse(url)
        this.hls = hls
        this.startMs = startMs
        firstPts = -1L
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
        invalidateState()
    }

    private fun start() {
        val holder = surfaceView?.holder
        val surface = holder?.surface?.takeIf { it.isValid }
        if (surface == null) {
            pendingStart = true
            return
        }
        pendingStart = false
        val factory = source ?: return
        val uri = uri ?: return
        val g = ++generation
        startPositionMs = -1
        val player = GoatCorePlayer(surface, softwareVideo = softwareVideo, capture = capture, previous = closing, passthrough = passthrough,
            needsSdr = { info -> needsSdr(videoFormat(info).build()) }, listener = object : GoatCorePlayer.Listener {
            override fun onFirstFrame(ms: Long) = post(g) {
                firstFrame = true
                playbackState = Player.STATE_READY
            }

            override fun onTracks(tracks: List<TsTrack>) = post(g) {
                tsTracks = tracks
                this@GoatCoreMediaPlayer.tracks = tracksOf(tracks)
            }

            override fun onError(why: String, unsupported: Boolean, cause: Throwable?) = post(g) {
                val code = when {
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

            override fun onVideoSize(width: Int, height: Int) = post(g) { videoSize = VideoSize(width, height) }

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
        main.removeCallbacks(starvation)
        main.postDelayed(starvation, 250)
        player.setVolume(effectiveVolume())
        player.setSpeed(speed)
        player.setPaused(!playWhenReady)
        // HLS: os segmentos TS um atrás do outro (ver HlsInputStream); o resto é o stream direto.
        val from = startMs
        player.open {
            if (hls) return@open cc.slokker.itv.ui.player.goatcore.HlsInputStream(factory, uri.toString(), from) { offsetMs = it }
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
            if (playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)) {
                val next = if (c.starvedMs > STARVED_MS) Player.STATE_BUFFERING else Player.STATE_READY
                if (next != playbackState && (next == Player.STATE_BUFFERING || c.starvedMs < STARVED_MS)) {
                    playbackState = next
                    invalidateState()
                }
            }
            main.postDelayed(this, 250)
        }
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
        fun label(language: String?) = language?.let { cc.slokker.itv.data.Languages.code(it) }?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
        val groups = list.filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO || it.codec == Codec.DVB_SUBTITLE || it.codec == Codec.WEBVTT }.map { t ->
            val id = "goatcore:${t.pid}"
            val info = videoInfo.takeIf { t.kind == TrackKind.VIDEO }
            val format = (info?.let { videoFormat(it) } ?: Format.Builder())
                .setId(id)
                .setSampleMimeType(mimeOf(t.codec))
                .setLanguage(t.language)
                .setLabel(label(t.language))
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
        core?.setPaused(!playWhenReady)
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
        surfaceView?.holder?.removeCallback(surfaceCallback)
        surfaceView = null
        main.removeCallbacksAndMessages(null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (videoOutput is SurfaceView && videoOutput != surfaceView) {
            surfaceView?.holder?.removeCallback(surfaceCallback)
            surfaceView = videoOutput
            videoOutput.holder.addCallback(surfaceCallback)
            // O ecrã trocou de vista (acontece logo ao abrir): o descodificador da superfície antiga fica parado (os do
            // Android só descodificam para uma superfície que alguém mostra), por isso recomeça-se na nova.
            if (core != null) {
                stopCore()
                pendingStart = true
            }
            if (pendingStart) start()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput == surfaceView) {
            surfaceView?.holder?.removeCallback(surfaceCallback)
            surfaceView = null
            if (core != null) {
                stopCore()
                pendingStart = true
            }
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

    private companion object {
        /** Sem imagem há mais do que isto, a dar, é uma paragem à espera de dados. */
        const val STARVED_MS = 700L

        /** O Media3 não tem tipo para o teletexto: "text/…" basta para ser uma faixa de legendas. */
        const val TELETEXT_MIME = "text/x-dvb-teletext"
    }
}
