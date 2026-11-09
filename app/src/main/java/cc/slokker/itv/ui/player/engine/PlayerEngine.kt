@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.engine

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.ui.player.NetMonitor
import cc.slokker.itv.ui.player.Timeshift
import cc.slokker.itv.ui.player.VlcPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** O [StreamEngine] de um player do Media3 (o ExoPlayer ou o [VlcPlayer]): passa os eventos dele ao [playback]. */
internal class PlayerEngine(
    override val player: Player,
    private val playback: ChannelPlayback,
    private val timeshift: Timeshift?,
    private val net: NetMonitor?,
    private val scope: CoroutineScope,
) : StreamEngine {
    private val gate = playback.gate
    private var pendingOpen: Job? = null

    /** Parado por [holdForZap], com os descodificadores guardados até o canal seguinte estar a dar. */
    private var holding = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // O canal seguinte já usa os descodificadores: deixa de os guardar quando estiver parado.
            if (playbackState == Player.STATE_READY) keepCodecs(false)
            playback.onState(this@PlayerEngine, playbackState)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = playback.onPlayingChanged(this@PlayerEngine, isPlaying)
        override fun onRenderedFirstFrame() = playback.onFirstFrame(this@PlayerEngine)
        override fun onPlayerError(error: PlaybackException) = playback.onError(this@PlayerEngine, error)
        override fun onTracksChanged(tracks: Tracks) {
            if (!isVlc && tracks.cannotDecode()) playback.onUndecodable(this@PlayerEngine)
        }
    }

    init {
        player.addListener(listener)
    }

    override val isVlc: Boolean get() = player is VlcPlayer
    override val supportsRing: Boolean get() = player is ExoPlayer
    override val idle: Boolean get() = player.playbackState == Player.STATE_IDLE
    override val positionMs: Long get() = player.currentPosition

    override fun open(candidate: StreamCandidate, channel: Channel, options: OpenOptions) {
        pendingOpen?.cancel()
        // Uma geração nova: as ligações da tentativa anterior que ainda tentem abrir são recusadas.
        gate.next()
        val load = {
            player.loadStream(
                candidate, channel,
                nearLive = options.nearLive,
                userAgent = options.userAgent,
                transfer = net,
                timeshift = timeshift.takeIf { options.ring },
                reconnectMs = options.reconnectMs,
                gate = gate,
            )
            player.prepare()
            player.play()
        }
        // O VLC liga-se por conta própria: se o ExoPlayer de antes (ao passar para o VLC) ainda tem a ligação aberta,
        // espera-se que feche. O ExoPlayer espera sozinho, ao abrir cada ligação (StreamGate).
        if (isVlc && gate.openCount > 0) {
            player.stop()
            pendingOpen = scope.launch {
                withContext(Dispatchers.IO) { gate.awaitIdle() }
                load()
            }
        } else {
            load()
        }
    }

    override fun restartAtLive() {
        player.seekToDefaultPosition()
        player.prepare()
    }

    override fun stop() {
        pendingOpen?.cancel()
        // Em segundo plano os descodificadores ficam livres para as outras apps.
        keepCodecs(false)
        player.stop()
    }

    override fun holdForZap() {
        pendingOpen?.cancel()
        keepCodecs(true)
        player.stop()
    }

    /** O ExoPlayer parado guarda os descodificadores ("foreground mode") só entre canais, a passar canais. */
    private fun keepCodecs(keep: Boolean) {
        val exo = player as? ExoPlayer ?: return
        if (holding == keep) return
        holding = keep
        exo.setForegroundMode(keep)
    }

    override fun release() {
        pendingOpen?.cancel()
        player.removeListener(listener)
        player.release()
    }

    override fun ringContinues(): Boolean = timeshift?.handleEnded(player) == true

    override fun tick(programmeStart: (Long) -> Long?) {
        timeshift?.update(player, programmeStart)
    }

    override fun sampleNet() {
        net?.sample(player, timeshift?.recordedMs)
    }
}

/**
 * O [ChannelPlayback] de um ecrã, com o player criado conforme as definições (recriado quando muda uma das do
 * [PlayerBuild], ou quando o canal [channelKey] passa para o VLC). [enabled] false: sem player (segundo canal
 * fechado). O canal abre-se com [ChannelPlayback.tune], num efeito com o [ChannelPlayback.player] nas chaves.
 */
@Composable
internal fun rememberChannelPlayback(
    policy: PlaybackPolicy,
    host: PlaybackHost,
    prefs: Prefs,
    channelKey: String?,
    enabled: Boolean = true,
    net: NetMonitor? = null,
    captureFreeze: suspend () -> ImageBitmap? = { null },
): ChannelPlayback {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestCapture by rememberUpdatedState(captureFreeze)
    val playback = remember {
        ChannelPlayback(
            policy, scope, host,
            timeshift = if (policy.timeshift) Timeshift(context) else null,
            net = net.takeIf { policy.diagnostics },
        ).also { it.freezeCapture = { latestCapture() } }
    }
    val build = PlayerBuild.of(prefs, playback.engineFor(channelKey, prefs.engine), policy.passthrough)
    // Criado (e ligado ao controlador) já aqui, para o ecrã ter o player na mesma composição.
    val engine = remember(build, enabled) {
        if (!enabled) return@remember null
        PlayerEngine(buildPlayer(context, build.applyTo(prefs)), playback, playback.timeshift, net.takeIf { policy.diagnostics }, scope)
            .also(playback::attach)
    }
    DisposableEffect(engine) { onDispose { engine?.let(playback::detach) } }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}
