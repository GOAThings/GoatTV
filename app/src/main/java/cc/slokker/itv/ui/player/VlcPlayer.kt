@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * O VLC (libVLC) com a interface [Player] do Media3, para o resto do player não precisar de saber qual dos dois está
 * a tocar (Definições → Reprodução → Player). Só o que a app usa: uma stream de cada vez ([setStream]), play/pausa/
 * parar, posição (catch-up), tamanho do vídeo, faixas de áudio e de legendas, silenciar (segundo canal) e erros.
 * O VLC desenha as legendas na própria imagem, por isso não há `onCues`.
 */
internal class VlcPlayer(
    context: Context,
    /** Áudio Dolby/DTS em bruto para o recetor ou a TV. */
    passthrough: Boolean,
    /** Vídeo sempre por software (Definições → Descodificador). */
    private val softwareVideo: Boolean,
    /** Quanto o VLC guarda antes de começar e depois de uma paragem (o "Buffer" das definições). */
    private val networkCachingMs: Int,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    // O LibVLC junta as suas opções a esta lista: tem de poder ser alterada (com buildList fechava a app).
    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf("--http-reconnect", "--audio-time-stretch").apply { if (passthrough) add("--spdif") },
    )
    private val vlc = MediaPlayer(libVlc)
    private val main = Handler(Looper.getMainLooper())

    private var url: String? = null
    private var userAgent: String? = null
    private var referrer: String? = null
    private var mediaItem: MediaItem? = null

    private var playbackState = Player.STATE_IDLE
    private var playWhenReady = false
    private var error: PlaybackException? = null
    private var videoSize = VideoSize.UNKNOWN
    private var tracks = Tracks.EMPTY
    private var seekable = false
    private var lengthMs = 0L
    private var volume = 1f
    private var params = TrackSelectionParameters.DEFAULT
    private var firstFrame = false
    private var surface: SurfaceView? = null

    /**
     * Depois de [release] o VLC já não existe: chamá-lo fecha a app ("can't get VLCObject instance"). Ao trocar de
     * player nas definições, a app ainda pede a posição ou o estado ao antigo, e há eventos do VLC a caminho.
     */
    private var released = false
    private var lastTimeMs = 0L

    /** O VLC precisa de saber o tamanho da janela para desenhar a imagem com a proporção certa. */
    private val surfaceLayout = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (v.width > 0 && v.height > 0) vlc.vlcVout.setWindowSize(v.width, v.height)
    }

    private val videoLayout = IVLCVout.OnNewVideoLayoutListener { _, width, height, visibleWidth, visibleHeight, sarNum, sarDen ->
        val w = if (visibleWidth > 0) visibleWidth else width
        val h = if (visibleHeight > 0) visibleHeight else height
        if (w > 0 && h > 0) {
            videoSize = VideoSize(w, h, if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f)
            invalidateState()
        }
    }

    init {
        // Os eventos chegam do VLC; o estado do Player só muda no thread principal.
        vlc.setEventListener { e ->
            val type = e.type
            val buffering = if (type == MediaPlayer.Event.Buffering) e.buffering else 0f
            val voutCount = if (type == MediaPlayer.Event.Vout) e.voutCount else 0
            main.post { onEvent(type, buffering, voutCount) }
        }
    }

    /** A stream a tocar (o equivalente ao `setMediaSource` do ExoPlayer); começa com [prepare]. */
    fun setStream(url: String, userAgent: String?, referrer: String?) {
        this.url = url
        this.userAgent = userAgent
        this.referrer = referrer
        mediaItem = MediaItem.fromUri(url)
        if (playbackState != Player.STATE_IDLE) vlc.stop()
        playbackState = Player.STATE_IDLE
        error = null
        tracks = Tracks.EMPTY
        videoSize = VideoSize.UNKNOWN
        invalidateState()
    }

    private fun onEvent(type: Int, buffering: Float, voutCount: Int) {
        if (released) return
        when (type) {
            MediaPlayer.Event.Opening -> playbackState = Player.STATE_BUFFERING
            MediaPlayer.Event.Buffering -> if (playbackState != Player.STATE_IDLE) {
                playbackState = if (buffering >= 100f) Player.STATE_READY else Player.STATE_BUFFERING
            }
            MediaPlayer.Event.Playing -> {
                playbackState = Player.STATE_READY
                refreshTracks()
            }
            MediaPlayer.Event.EndReached -> playbackState = Player.STATE_ENDED
            MediaPlayer.Event.Stopped -> if (playbackState != Player.STATE_ENDED) playbackState = Player.STATE_IDLE
            MediaPlayer.Event.EncounteredError -> {
                error = PlaybackException("VLC: não foi possível abrir a stream", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                playbackState = Player.STATE_IDLE
            }
            MediaPlayer.Event.Vout -> if (voutCount > 0) {
                firstFrame = true
                refreshTracks()
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.SeekableChanged -> seekable = vlc.isSeekable
            MediaPlayer.Event.LengthChanged -> lengthMs = vlc.length
            else -> return
        }
        invalidateState()
    }

    /** As faixas do VLC como [Tracks] do Media3 (o menu de áudio e legendas é o mesmo dos dois players). */
    private fun refreshTracks() {
        if (released) return
        val groups = mutableListOf<Tracks.Group>()
        vlc.currentVideoTrack?.let { v ->
            val format = Format.Builder()
                .setId("vlc:video")
                .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
                .setWidth(v.width)
                .setHeight(v.height)
                .apply { if (v.frameRateDen > 0) setFrameRate(v.frameRateNum.toFloat() / v.frameRateDen) }
                .build()
            groups += Tracks.Group(TrackGroup("vlc:video", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
        }
        fun add(kind: String, mime: String, list: Array<MediaPlayer.TrackDescription>?, selected: Int) {
            list.orEmpty().filter { it.id >= 0 }.forEach { t ->
                val id = "vlc:$kind:${t.id}"
                val format = Format.Builder().setId(id).setLabel(t.name).setSampleMimeType(mime).build()
                groups += Tracks.Group(TrackGroup(id, format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(t.id == selected))
            }
        }
        add("audio", MimeTypes.AUDIO_UNKNOWN, vlc.audioTracks, vlc.audioTrack)
        add("text", MimeTypes.TEXT_UNKNOWN, vlc.spuTracks, vlc.spuTrack)
        tracks = Tracks(groups)
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SET_VIDEO_SURFACE,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
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
        val item = mediaItem
        if (item == null) {
            builder.setPlaybackState(Player.STATE_IDLE)
        } else {
            builder
                .setPlaylist(
                    listOf(
                        MediaItemData.Builder("vlc")
                            .setMediaItem(item)
                            .setTracks(tracks)
                            .setIsSeekable(seekable)
                            .setIsDynamic(true)
                            .setDurationUs(if (lengthMs > 0) lengthMs * 1000 else C.TIME_UNSET)
                            .build(),
                    ),
                )
                .setCurrentMediaItemIndex(0)
                .setPlaybackState(playbackState)
                .setContentPositionMs { if (released) lastTimeMs else vlc.time.coerceAtLeast(0).also { lastTimeMs = it } }
        }
        if (firstFrame) {
            builder.setNewlyRenderedFirstFrame(true)
            firstFrame = false
        }
        return builder.build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val url = url?.takeIf { !released } ?: return Futures.immediateVoidFuture()
        error = null
        playbackState = Player.STATE_BUFFERING
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(!softwareVideo, false)
            addOption(":network-caching=$networkCachingMs")
            userAgent?.let { addOption(":http-user-agent=$it") }
            referrer?.let { addOption(":http-referrer=$it") }
        }
        vlc.media = media
        media.release()
        if (playWhenReady) vlc.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playbackState != Player.STATE_IDLE && !released) {
            if (playWhenReady) vlc.play() else vlc.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!released) vlc.stop()
        playbackState = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        detachSurface()
        lastTimeMs = vlc.time.coerceAtLeast(0)
        released = true
        main.removeCallbacksAndMessages(null)
        vlc.setEventListener(null)
        vlc.release()
        libVlc.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (videoOutput is SurfaceView && videoOutput != surface && !released) {
            detachSurface()
            surface = videoOutput
            vlc.vlcVout.setVideoView(videoOutput)
            vlc.vlcVout.attachViews(videoLayout)
            videoOutput.addOnLayoutChangeListener(surfaceLayout)
            if (videoOutput.width > 0 && videoOutput.height > 0) vlc.vlcVout.setWindowSize(videoOutput.width, videoOutput.height)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        detachSurface()
        return Futures.immediateVoidFuture()
    }

    private fun detachSurface() {
        val view = surface ?: return
        if (released) {
            surface = null
            return
        }
        view.removeOnLayoutChangeListener(surfaceLayout)
        vlc.vlcVout.detachViews()
        surface = null
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (released) return Futures.immediateVoidFuture()
        if (positionMs == C.TIME_UNSET) {
            // "Voltar ao direto": num live, recomeçar a stream é o que leva ao ponto atual.
            if (playbackState != Player.STATE_IDLE) {
                vlc.stop()
                handlePrepare()
            }
        } else if (seekable) {
            vlc.time = positionMs
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        this.volume = volume
        applyVolume()
        return Futures.immediateVoidFuture()
    }

    /** Escolher faixas de áudio e legendas, e desligar o áudio (só um dos dois canais tem som). */
    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        params = trackSelectionParameters
        if (released) return Futures.immediateVoidFuture()
        applyVolume()
        if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) vlc.spuTrack = -1
        params.overrides.values.forEach { override ->
            val id = override.mediaTrackGroup.id
            val vlcId = id.substringAfterLast(':').toIntOrNull() ?: return@forEach
            when {
                id.startsWith("vlc:audio:") -> vlc.audioTrack = vlcId
                id.startsWith("vlc:text:") && C.TRACK_TYPE_TEXT !in params.disabledTrackTypes -> vlc.spuTrack = vlcId
            }
        }
        refreshTracks()
        return Futures.immediateVoidFuture()
    }

    private fun applyVolume() {
        if (released) return
        val muted = C.TRACK_TYPE_AUDIO in params.disabledTrackTypes
        vlc.volume = if (muted) 0 else (volume * 100).toInt().coerceIn(0, 100)
    }
}
