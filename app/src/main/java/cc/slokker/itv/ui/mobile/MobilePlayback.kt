package cc.slokker.itv.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.player.Timeshift
import cc.slokker.itv.ui.player.VideoView
import cc.slokker.itv.ui.player.engine.AppPlaybackHost
import cc.slokker.itv.ui.player.engine.ChannelPlayback
import cc.slokker.itv.ui.player.engine.FREEZE_HEIGHT
import cc.slokker.itv.ui.player.engine.FREEZE_WIDTH
import cc.slokker.itv.ui.player.engine.PlaybackFailure
import cc.slokker.itv.ui.player.engine.PlaybackPolicy
import cc.slokker.itv.ui.player.engine.PlaybackRequest
import cc.slokker.itv.ui.player.engine.aspectOrNull
import cc.slokker.itv.ui.player.engine.rememberChannelPlayback
import cc.slokker.itv.ui.player.engine.resizeModeFor

/** O que a interface do telemóvel precisa de saber do leitor: o [ChannelPlayback] (o mesmo da TV) e a imagem. */
@Stable
internal class MobilePlayback(val core: ChannelPlayback, viewState: MutableState<VideoView?>) {
    var view by viewState
    val player: Player get() = checkNotNull(core.player)
    val timeshift: Timeshift get() = checkNotNull(core.timeshift)
    val ready: Boolean get() = core.ready
    val failure: PlaybackFailure? get() = core.failure
    val playing: Boolean get() = core.playing
    val buffering: Boolean get() = core.buffering

    /** Última imagem do canal anterior, até o novo mostrar o primeiro frame (sem ecrã preto). */
    val freeze get() = core.freeze

    /** Catch-up: instante real do início do vídeo pedido ao servidor (0 em direto). */
    val archiveStart: Long get() = core.archiveStartMs

    /** A tentativa de voltar a ligar (0 = não caiu) e quando é a próxima (elapsedRealtime; 0 = a tentar agora). */
    val reconnects: Int get() = core.reconnects
    val nextTryAt: Long get() = core.nextTryAt
    val retryDelayMs: Long get() = core.retryDelayMs
}

/**
 * O leitor do telemóvel: o mesmo [ChannelPlayback] da TV (ts ⇄ m3u8, erro explicado e 3 tentativas, voltar a
 * ligar, catch-up que continua onde ia, VLC se o ExoPlayer não descodificar), sem o comando nem os painéis.
 */
@Composable
internal fun rememberMobilePlayback(vm: AppViewModel, channel: Channel, data: PlaylistContent, prefs: Prefs, streamFormat: String?, active: Boolean): MobilePlayback {
    val latestPrefs by rememberUpdatedState(prefs)
    val latestData by rememberUpdatedState(data)
    val latestActive by rememberUpdatedState(active)
    val host = remember { AppPlaybackHost(vm, { latestPrefs }, { latestData }) }
    val viewState = remember { mutableStateOf<VideoView?>(null) }
    // Uma gravação usa a ligação deste canal: o ExoPlayer lê o anel dela (ver o efeito mais abaixo).
    val recordingRing by vm.recordingRing.collectAsStateWithLifecycle()
    val ringRecording = recordingRing?.let { it.playlistId == data.playlist.id && it.channelKey == channel.key } == true
    val core = rememberChannelPlayback(PlaybackPolicy.PHONE, host, prefs, channel.key, forceExo = ringRecording) {
        if (latestPrefs.freezeFrame) viewState.value?.capture(FREEZE_WIDTH, FREEZE_HEIGHT)?.asImageBitmap() else null
    }
    val playback = remember(core) { MobilePlayback(core, viewState) }
    val player = playback.player

    val catchup by vm.catchup.collectAsStateWithLifecycle()
    LaunchedEffect(channel.key, data.playlist, player, catchup, active) {
        // Nas Definições o canal fecha-se (e liberta a ligação); ao sair, volta a dar.
        vm.onPlayerVisible(active)
        if (!active) {
            core.stop()
            core.freeze = null
            return@LaunchedEffect
        }
        core.tune(PlaybackRequest.of(data, channel, catchup, streamFormat, vm.stalkerLinks, latestPrefs.catchupOffsetMin))
    }

    LaunchedEffect(playback.view, prefs.aspect) {
        playback.view?.setResizeMode(resizeModeFor(prefs.aspect))
    }

    LaunchedEffect(player, playback.view) {
        val view = playback.view ?: return@LaunchedEffect
        player.setVideoSurfaceView(view.surfaceView)
        player.videoSize.aspectOrNull()?.let { view.setAspectRatio(it) }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // Legendas do ExoPlayer: como na TV, desenha-as a SubtitleView por cima do vídeo (o VLC desenha as suas).
            override fun onCues(cueGroup: CueGroup) {
                playback.view?.subtitleView?.setCues(cueGroup.cues)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                size.aspectOrNull()?.let { playback.view?.setAspectRatio(it) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Em segundo plano pára o stream e liberta a ligação Xtream. Ao recriar o player, o canal volta a abrir só pelo
    // efeito acima (uma vez).
    // Uma gravação passou a usar a ligação deste canal e o player abriu-o sem o anel: recomeça pelo anel dela.
    LaunchedEffect(ringRecording, player, catchup, active) {
        if (!ringRecording || catchup != null || !active) return@LaunchedEffect
        val url = vm.recordingRingUrl(channel) ?: return@LaunchedEffect
        if (core.timeshift?.feed?.url != url && core.request?.live == true) core.retry()
    }

    LifecycleStartEffect(core) {
        if (latestActive) {
            core.onStart()
            vm.onPlayerVisible(true)
        }
        onStopOrDispose {
            vm.onPlayerVisible(false)
            core.stop()
            vm.flushLastChannel()
        }
    }
    return playback
}
