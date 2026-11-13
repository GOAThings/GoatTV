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
import cc.slokker.itv.media.TsTrack
import cc.slokker.itv.ui.player.goatcore.GoatCorePlayer
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
) : SimpleBasePlayer(Looper.getMainLooper()) {
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

    /** A stream a tocar: [source] é a ligação (a do ExoPlayer, com o portão); começa com [prepare]. */
    fun setStream(url: String, source: DataSource.Factory, hls: Boolean) {
        stopCore()
        this.source = source
        this.uri = Uri.parse(url)
        this.hls = hls
        mediaItem = MediaItem.fromUri(url)
        chosenAudio = -1
        chosenText = -1
        cues = androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO
        tsTracks = emptyList()
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
        val player = GoatCorePlayer(surface, softwareVideo = softwareVideo, capture = capture, previous = closing, passthrough = passthrough, listener = object : GoatCorePlayer.Listener {
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

            override fun onCues(atUs: Long, cues: List<androidx.media3.common.text.Cue>, durationUs: Long) = showCues(g, atUs, cues, durationUs)
        })
        core = player
        player.setVolume(effectiveVolume())
        player.setPaused(!playWhenReady)
        // HLS: os segmentos TS um atrás do outro (ver HlsInputStream); o resto é o stream direto.
        player.open {
            if (hls) cc.slokker.itv.ui.player.goatcore.HlsInputStream(factory, uri.toString())
            else DataSourceInputStream(factory.createDataSource(), DataSpec(uri)).also { it.open() }
        }
        playbackState = Player.STATE_BUFFERING
    }

    private var cues = androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO

    /** Mostra [list] quando o relógio do motor chegar a [atUs] (Long.MIN_VALUE = já), e tira-as ao fim de [durationUs]. */
    private fun showCues(g: Int, atUs: Long, list: List<androidx.media3.common.text.Cue>, durationUs: Long) {
        val now = core?.nowUs ?: Long.MIN_VALUE
        val delayMs = if (atUs == Long.MIN_VALUE || now == Long.MIN_VALUE) 0 else ((atUs - now) / 1000).coerceIn(0, 10_000)
        val group = androidx.media3.common.text.CueGroup(list, atUs.coerceAtLeast(0))
        main.postDelayed({
            if (g != generation) return@postDelayed
            cues = group
            invalidateState()
            if (list.isNotEmpty() && durationUs > 0 && durationUs != C.TIME_UNSET) {
                main.postDelayed({
                    if (g == generation && cues === group) {
                        cues = androidx.media3.common.text.CueGroup.EMPTY_TIME_ZERO
                        invalidateState()
                    }
                }, durationUs / 1000)
            }
        }, delayMs)
    }

    private fun post(g: Int, change: () -> Unit) {
        main.post {
            if (g != generation) return@post
            change()
            invalidateState()
        }
    }

    private fun fail(e: PlaybackException) {
        stopCore()
        error = e
        playbackState = Player.STATE_IDLE
        invalidateState()
    }

    private fun stopCore() {
        generation++
        val c = core ?: return
        core = null
        lastPositionMs = position(c)
        // O stop espera pelas threads do motor: fora do thread principal. O seguinte espera por ele.
        closing = Thread({ c.stop() }, "goatcore-stop").also { it.start() }
    }

    private fun position(c: GoatCorePlayer): Long {
        val p = c.positionMs
        if (p < 0) return lastPositionMs
        if (startPositionMs < 0) startPositionMs = p
        return p - startPositionMs
    }

    /**
     * As faixas do canal como [Tracks] do Media3: o menu de áudio e a escolha pela língua preferida (TrackPick) são os
     * mesmos dos outros motores. As de áudio sem descodificador no aparelho vêm como não suportadas.
     */
    private fun tracksOf(list: List<TsTrack>): Tracks {
        val playing = chosenAudio.takeIf { pid -> list.any { it.pid == pid } } ?: core?.selectedAudio ?: -1
        val groups = list.filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO || it.codec == Codec.DVB_SUBTITLE }.map { t ->
            val id = "goatcore:${t.pid}"
            val format = Format.Builder()
                .setId(id)
                .setSampleMimeType(mimeOf(t.codec))
                .setLanguage(t.language)
                .setLabel(t.language?.let { cc.slokker.itv.data.Languages.code(it) }?.let { java.util.Locale.forLanguageTag(it).displayLanguage })
                .build()
            val supported = t.kind != TrackKind.AUDIO || GoatCorePlayer.playable(t.codec, passthrough)
            val selected = t.kind == TrackKind.VIDEO || t.pid == playing || (t.kind == TrackKind.SUBTITLE && t.pid == chosenText)
            Tracks.Group(TrackGroup(id, format), false, intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(selected))
        }
        return Tracks(groups)
    }

    private fun mimeOf(codec: Codec): String = when (codec) {
        Codec.H264 -> MimeTypes.VIDEO_H264
        Codec.HEVC -> MimeTypes.VIDEO_H265
        Codec.MPEG2_VIDEO -> MimeTypes.VIDEO_MPEG2
        Codec.AAC_ADTS, Codec.AAC_LATM -> MimeTypes.AUDIO_AAC
        Codec.AC3 -> MimeTypes.AUDIO_AC3
        Codec.EAC3 -> MimeTypes.AUDIO_E_AC3
        Codec.MPEG_AUDIO -> MimeTypes.AUDIO_MPEG_L2
        Codec.DVB_SUBTITLE -> MimeTypes.APPLICATION_DVBSUBS
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
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlayerError(error)
            .setVideoSize(videoSize)
            .setTrackSelectionParameters(params)
            .setVolume(volume)
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
}
