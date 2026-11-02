package cc.slokker.itv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.catchupCandidates
import cc.slokker.itv.data.streamCandidates
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.IPTV_USER_AGENT
import cc.slokker.itv.ui.player.VideoView
import cc.slokker.itv.ui.player.VlcPlayer
import cc.slokker.itv.ui.player.buildPlayer
import cc.slokker.itv.ui.player.cannotDecode
import cc.slokker.itv.ui.player.loadStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** O que a interface do telemóvel precisa de saber do leitor. */
@Stable
internal class MobilePlayback(val player: Player) {
    var ready by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var view by mutableStateOf<VideoView?>(null)
    /** Catch-up: instante real do início do vídeo pedido ao servidor (0 em direto). */
    var archiveStart = 0L
}

/**
 * O leitor do telemóvel: as mesmas tentativas que o da TV (ts ⇄ m3u8, voltar a ligar, VLC se o ExoPlayer não
 * descodificar), sem o comando nem os painéis.
 */
@Composable
internal fun rememberMobilePlayback(vm: AppViewModel, channel: Channel, data: PlaylistContent, prefs: Prefs, streamFormat: String?, active: Boolean): MobilePlayback {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var vlcFor by remember { mutableStateOf<String?>(null) }
    val engine = if (vlcFor == channel.key) Prefs.Engine.VLC else prefs.engine
    val playback = remember(engine, prefs.buffer, prefs.decoder, prefs.audioDecoder) {
        MobilePlayback(buildPlayer(context, prefs.copy(engine = engine)))
    }
    val player = playback.player
    val candidates = remember { arrayOf(emptyList<StreamCandidate>()) }
    val attempt = remember { intArrayOf(0) }
    val retry = remember { arrayOfNulls<Job>(1) }
    val latestChannel by rememberUpdatedState(channel)
    val latestPrefs by rememberUpdatedState(prefs)
    val latestActive by rememberUpdatedState(active)

    fun start(index: Int) {
        if (vm.noVideo) return
        val candidate = candidates[0].getOrNull(index) ?: return
        attempt[0] = index
        playback.ready = false
        playback.failed = false
        player.loadStream(
            candidate, latestChannel,
            nearLive = latestPrefs.buffer == Prefs.Buffer.LIVE && vm.catchup.value == null,
            userAgent = latestPrefs.userAgent.ifBlank { IPTV_USER_AGENT },
        )
        player.prepare()
        player.play()
    }

    val catchup by vm.catchup.collectAsStateWithLifecycle()
    LaunchedEffect(channel.key, data.playlist, player, catchup, active) {
        retry[0]?.cancel()
        // Nas Definições o canal fecha-se (e liberta a ligação); ao sair, volta a dar.
        if (!active) {
            player.stop()
            return@LaunchedEffect
        }
        val archive = catchup?.let { catchupCandidates(data.playlist, it, data.account?.timezone) }
        playback.archiveStart = archive?.startMs ?: 0L
        candidates[0] = archive?.candidates ?: streamCandidates(data.playlist, channel, streamFormat, data.account?.allowedFormats.orEmpty())
        start(0)
    }

    LaunchedEffect(player, playback.view) {
        val view = playback.view ?: return@LaunchedEffect
        player.setVideoSurfaceView(view.surfaceView)
        player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { view.setAspectRatio(it.width * it.pixelWidthHeightRatio / it.height) }
    }

    DisposableEffect(player) {
        fun reconnect() {
            retry[0]?.cancel()
            retry[0] = scope.launch {
                delay(latestPrefs.reconnectSeconds.coerceAtLeast(1) * 1_000L)
                start(attempt[0])
            }
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    playback.ready = true
                    playback.failed = false
                }
                // Um canal em direto não acaba: o servidor fechou a ligação.
                if (state == Player.STATE_ENDED) {
                    if (vm.catchup.value != null) vm.exitCatchup() else reconnect()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playback.playing = isPlaying
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) playback.view?.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (player !is VlcPlayer && tracks.cannotDecode()) vlcFor = latestChannel.key
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    player !is VlcPlayer && error.errorCode in 4000..4999 -> vlcFor = latestChannel.key
                    !playback.ready && attempt[0] + 1 < candidates[0].size -> start(attempt[0] + 1)
                    else -> {
                        playback.failed = !playback.ready
                        if (latestPrefs.reconnectSeconds > 0) reconnect()
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            retry[0]?.cancel()
            player.release()
        }
    }

    // Em segundo plano pára o stream e liberta a ligação Xtream.
    LifecycleStartEffect(player) {
        if (latestActive && player.playbackState == Player.STATE_IDLE && candidates[0].isNotEmpty()) start(attempt[0])
        onStopOrDispose {
            player.stop()
            vm.flushLastChannel()
        }
    }
    return playback
}
