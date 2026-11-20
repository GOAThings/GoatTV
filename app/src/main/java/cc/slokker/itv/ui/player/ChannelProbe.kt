@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import cc.slokker.itv.playback.ChannelProbeInput
import cc.slokker.itv.playback.ProbeVerdict
import cc.slokker.itv.playback.ProbeVerdictLine
import cc.slokker.itv.playback.StreamProbe
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.player.engine.isDecoderError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** O estado do painel "Testar este canal". [second]: quantos segundos já se observaram. */
data class ChannelProbeState(
    val running: Boolean = false,
    val second: Int = 0,
    val result: ChannelProbeInput? = null,
    /** O motor a tocar não deixa medir (o VLC de reserva, o ExoPlayer). */
    val unsupported: Boolean = false,
)

/**
 * "Testar este canal": observa a reprodução que já está a dar durante [StreamProbe.WINDOW_MS] e lê os números (as
 * ligações do portão, as estatísticas do motor, os bytes que chegaram). Não abre nenhuma ligação: os fornecedores
 * só deixam uma. Igual na TV e no telemóvel.
 */
class ChannelProbe(private val scope: CoroutineScope, private val net: NetMonitor) {
    private val _state = MutableStateFlow(ChannelProbeState())
    val state: StateFlow<ChannelProbeState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(player: Player?) {
        if (_state.value.running) return
        val core = player as? GoatCoreMediaPlayer
        if (core == null) {
            _state.value = ChannelProbeState(unsupported = true)
            return
        }
        job = scope.launch {
            _state.value = ChannelProbeState(running = true)
            val stats0 = core.coreStats
            val rendered0 = stats0?.framesRendered ?: 0
            val late0 = stats0?.framesLate ?: 0
            val bytes0 = net.totalBytes
            val stops0 = net.stopsTotal
            val conn = core.connection
            val opens0 = conn?.opens ?: 0
            val failures0 = conn?.failures ?: 0
            val t0 = SystemClock.elapsedRealtime()
            for (second in 1..(StreamProbe.WINDOW_MS / 1000).toInt()) {
                delay(1_000)
                _state.value = ChannelProbeState(running = true, second = second)
            }
            val window = SystemClock.elapsedRealtime() - t0
            val stats1 = core.coreStats
            // O motor foi refeito a meio (o canal voltou a ligar): as contas dele recomeçam.
            val restarted = stats0 == null || stats1 !== stats0
            val stops = (net.stopsTotal - stops0).coerceAtLeast(0)
            val failures = (conn?.failures ?: 0) - failures0
            // Num TS, cada abertura a mais é uma ligação que caiu; num HLS cada segmento é uma abertura: só contam as falhadas.
            val reopened = if (core.isHls) 0 else ((conn?.opens ?: 0) - opens0).coerceAtLeast(0)
            val reconnects = maxOf(stops, failures + reopened) + if (restarted && stats0 != null) 1 else 0
            val dropped = if (stats1 == null) -1 else if (restarted) stats1.framesLate else stats1.framesLate - late0
            val rendered = if (stats1 == null) -1 else if (restarted) stats1.framesRendered else stats1.framesRendered - rendered0
            _state.value = ChannelProbeState(result = snapshot(core, conn, window, bytes0, reconnects, dropped, rendered))
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = ChannelProbeState()
    }

    private fun snapshot(
        core: GoatCoreMediaPlayer, conn: cc.slokker.itv.ui.player.engine.ConnectionProbe?, window: Long, bytes0: Long,
        reconnects: Int, dropped: Int, rendered: Int,
    ): ChannelProbeInput {
        val video = core.videoFormat
        val info = core.streamInfo
        val audio = core.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }.joinToString(", ") {
            val f = it.getTrackFormat(0)
            listOfNotNull(codecName(f.sampleMimeType)?.substringBefore(" ("), channelsLabel(f.channelCount)).joinToString(" ")
        }
        val failed = core.playerError?.errorCode?.let(::isDecoderError) == true
        val videoName = codecName(video?.sampleMimeType)?.substringBefore(" (").orEmpty()
        val bytes = net.totalBytes - bytes0
        val status = conn?.failureStatus?.takeIf { it >= 400 } ?: conn?.status ?: -1
        return ChannelProbeInput(
            host = conn?.host.orEmpty(),
            httpStatus = status,
            redirects = if (conn == null || conn.status < 0) -1 else if (conn.redirected) 1 else 0,
            firstByteMs = conn?.firstByteMs ?: -1L,
            firstPictureMs = core.firstFrameMs,
            container = if (core.isHls) "HLS" else "TS",
            videoCodec = videoName,
            width = video?.width?.takeIf { it > 0 } ?: 0,
            height = video?.height?.takeIf { it > 0 } ?: 0,
            fps = video?.frameRate?.takeIf { it > 0 } ?: 0f,
            interlaced = info?.interlaced == true,
            hdr = info?.isHdr == true,
            audio = audio,
            measuredKbps = if (window > 0 && bytes > 0) (bytes * 8 / window).toInt() else -1,
            windowMs = window,
            droppedFrames = dropped,
            renderedFrames = rendered,
            reconnects = reconnects,
            cannotDecode = if (failed) videoName.ifEmpty { "video" } else "",
            showing = core.firstFrameMs >= 0 && core.playbackState == Player.STATE_READY,
        )
    }
}

/** A frase do veredicto na língua da app. */
internal fun Strings.probeVerdictText(line: ProbeVerdictLine): String = when (line.verdict) {
    ProbeVerdict.OK -> probeVOk
    ProbeVerdict.SLOW -> probeVSlow(line.arg)
    ProbeVerdict.REFUSED -> probeVRefused(line.arg.toIntOrNull() ?: 0)
    ProbeVerdict.DROPS -> probeVDrops
    ProbeVerdict.CANNOT_DECODE -> probeVDecode(line.arg)
    ProbeVerdict.NO_PICTURE -> probeVNopicture
}

/** As linhas do painel (título e valor), sem endereços: só o nome do servidor. */
internal fun Strings.probeRows(i: ChannelProbeInput): List<Pair<String, String>> {
    val answer = if (i.httpStatus < 0) "—" else probeHttp(i.httpStatus) + (if (i.redirects > 0) " · $probeRedirected" else "")
    val host = i.host.takeIf { it.isNotEmpty() }
    return listOfNotNull(
        (probeAnswer to listOfNotNull(answer, host).joinToString(" · ")),
        probeFirstByte to (if (i.firstByteMs >= 0) probeSeconds(StreamProbe.seconds(i.firstByteMs)) else "—"),
        probeFirstPicture to (if (i.firstPictureMs >= 0) probeSeconds(StreamProbe.seconds(i.firstPictureMs)) else "—"),
        probeFormat to StreamProbe.format(i, probeInterlaced, probeHdr),
        i.audio.takeIf { it.isNotEmpty() }?.let { probeAudio to it },
        probeBitrate to StreamProbe.bitrate(i.measuredKbps),
        probeDropped to (if (i.droppedFrames < 0) "—" else "${i.droppedFrames}"),
        probeReconnects to "${i.reconnects}",
    )
}

/** A frase de uma linha do painel da lista ("OK", "Lenta", "Recusou (HTTP 403)"…). */
internal fun Strings.probeListVerdict(row: cc.slokker.itv.data.ListProbeRow): String = when (row.verdict) {
    cc.slokker.itv.playback.ListVerdict.OK -> probeLvOk
    cc.slokker.itv.playback.ListVerdict.SLOW -> probeLvSlow
    cc.slokker.itv.playback.ListVerdict.REFUSED -> probeLvRefused(row.status)
    cc.slokker.itv.playback.ListVerdict.UNREACHABLE -> probeLvUnreachable
    cc.slokker.itv.playback.ListVerdict.EMPTY -> probeLvEmpty
}
