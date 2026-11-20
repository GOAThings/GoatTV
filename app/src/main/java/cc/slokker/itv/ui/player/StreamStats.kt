@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.playback.RollingBitrate
import cc.slokker.itv.playback.StreamStats
import cc.slokker.itv.playback.StreamStatsInput
import cc.slokker.itv.playback.StreamStatsRow
import cc.slokker.itv.playback.StreamStatsWords
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.player.engine.ChannelPlayback
import cc.slokker.itv.ui.player.goatcore.HlsQualityControl
import kotlinx.coroutines.delay

/**
 * "Informação do stream" (Canal atual na TV, opções do player no telemóvel): lê os números do canal a dar (o motor, as
 * faixas, a qualidade do HLS, a reprodução) e mede o débito a rolar. As contas e as unidades são as do núcleo
 * partilhado (StreamStats), iguais na Apple. Só lê: não abre nada.
 */
internal class StreamStatsMeter {
    private val bitrate = RollingBitrate()

    /** Os números agora; chamar de segundo a segundo (o débito precisa das medições anteriores). */
    fun input(player: Player?, playback: ChannelPlayback?, playlists: List<Playlist>, hls: HlsQualityControl.Now?): StreamStatsInput {
        val core = player as? GoatCoreMediaPlayer
        val snap = core?.coreSnapshot()
        if (snap != null) bitrate.add(SystemClock.elapsedRealtime(), snap.bytesRead, snap.mediaMs) else bitrate.clear()
        val tracks = player?.currentTracks
        val v = tracks?.selectedFormat(C.TRACK_TYPE_VIDEO)
        val a = tracks?.selectedFormat(C.TRACK_TYPE_AUDIO)
        val info = snap?.video
        val size = player?.videoSize
        val audio = snap?.audio
        // Sem o motor (o VLC de reserva): o que as faixas do Media3 dizem.
        fun readable(mime: String?) = codecName(mime)?.substringBefore(" (").orEmpty()
        val videoCodec = snap?.videoCodec?.takeIf { it.isNotEmpty() } ?: readable(v?.sampleMimeType)
        val container = when {
            core != null -> if (core.isHls) "HLS" else "TS"
            else -> when (playback?.currentCandidate?.format) {
                StreamFormat.HLS -> "HLS"
                StreamFormat.TS -> "TS"
                else -> ""
            }
        }
        val variant = hls?.takeIf { it.variants.size > 1 }
        val request = playback?.request
        val listName = request?.source?.substringBefore('|')?.let { id -> playlists.firstOrNull { it.id == id }?.name }
        val transfer = info?.transfer ?: when (HdrType.of(v)) {
            HdrType.HDR10 -> 16
            HdrType.HLG -> 18
            else -> -1
        }
        return StreamStatsInput(
            container = container,
            hlsVariant = variant?.label.orEmpty(),
            hlsPinned = (variant?.pinned ?: -1) >= 0,
            videoCodec = videoCodec,
            videoProfile = info?.profile ?: -1,
            videoLevel = info?.level ?: -1,
            bitDepth = info?.bitDepth ?: -1,
            width = info?.width ?: size?.width?.takeIf { it > 0 } ?: v?.width?.takeIf { it > 0 } ?: 0,
            height = info?.height ?: size?.height?.takeIf { it > 0 } ?: v?.height?.takeIf { it > 0 } ?: 0,
            fps = info?.fps?.takeIf { it > 0 } ?: v?.frameRate?.takeIf { it > 0 } ?: 0f,
            interlaced = if (snap == null || snap.videoCodec.isEmpty()) -1 else if (snap.interlaced) 1 else 0,
            deinterlacing = snap?.deinterlacing == true,
            upscaling = snap?.upscaling == true,
            toneMapping = snap?.toneMapping == true,
            hdrTransfer = transfer,
            audioCodec = audio?.codec?.name ?: readable(a?.sampleMimeType),
            audioChannels = audio?.channels ?: a?.channelCount?.takeIf { it > 0 } ?: 0,
            audioSampleRate = audio?.sampleRate ?: a?.sampleRate?.takeIf { it > 0 } ?: 0,
            audioTrack = tracks?.audioLabel().orEmpty(),
            bitrateKbps = bitrate.kbps(),
            bufferMs = snap?.bufferedMs ?: if (player is ExoPlayer) player.totalBufferedDuration else -1L,
            droppedFrames = snap?.framesLate ?: -1,
            renderedFrames = snap?.framesRendered ?: -1,
            decodeErrors = snap?.decoderErrors ?: -1,
            concealedFrames = snap?.concealedFrames ?: -1,
            reconnects = playback?.sessionReconnects ?: 0,
            engine = when (player) {
                is GoatCoreMediaPlayer -> "GoatCore"
                is VlcPlayer -> "VLC"
                is ExoPlayer -> "ExoPlayer"
                else -> ""
            },
            fallback = player is VlcPlayer,
            source = request?.let { listOfNotNull(it.channel.name, listName).joinToString(" · ") }.orEmpty(),
            failover = playback?.onBackup != null,
        )
    }
}

internal fun Strings.statsWords() = StreamStatsWords(
    progressive = statsProgressive, interlaced = statsInterlaced, deinterlacing = statsDeinterlacing, upscaling = statsUpscaling,
    toneMapping = statsToneMapping, nothing = none, automatic = auto, fallback = statsFallback, failover = statsFailover,
)

/** O título de uma linha ([StreamStatsRow.key] é o nome da string). */
internal fun Strings.statsTitle(key: String): String = when (key) {
    StreamStats.KEY_ENGINE -> statsEngine
    StreamStats.KEY_SOURCE -> statsSource
    StreamStats.KEY_FORMAT -> probeFormat
    StreamStats.KEY_VIDEO -> video
    StreamStats.KEY_RESOLUTION -> resolution
    StreamStats.KEY_FRAME_RATE -> statsFrameRate
    StreamStats.KEY_SCAN -> statsScan
    StreamStats.KEY_RANGE -> dynamicRange
    StreamStats.KEY_PROCESSING -> statsProcessing
    StreamStats.KEY_AUDIO -> audio
    StreamStats.KEY_AUDIO_TRACK -> statsAudioTrack
    StreamStats.KEY_BITRATE -> probeBitrate
    StreamStats.KEY_BUFFER -> buffer
    StreamStats.KEY_DROPPED -> probeDropped
    StreamStats.KEY_DECODE_ERRORS -> statsDecodeErrors
    StreamStats.KEY_CONCEALED -> statsConcealed
    StreamStats.KEY_RECONNECTS -> probeReconnects
    else -> key
}

/** As linhas do painel, refeitas de segundo a segundo enquanto ele está aberto. */
@Composable
internal fun rememberStreamStats(vm: AppViewModel, player: Player?, s: Strings): List<StreamStatsRow> {
    fun now() = StreamStats.rows(vm.streamStats.input(player, vm.mainPlayback, vm.playlists.value, HlsQualityControl.now.value), s.statsWords())
    // As primeiras já ao abrir (a página tem logo as linhas para o foco), depois de segundo a segundo.
    val first = remember(player, s) { now() }
    val rows by produceState(first, player, s) {
        while (true) {
            delay(1_000)
            value = now()
        }
    }
    return rows
}
