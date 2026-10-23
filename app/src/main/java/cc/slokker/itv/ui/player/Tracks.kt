package cc.slokker.itv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.i18n.I18n
import java.util.Locale

/** Faixas e tamanho do vídeo do stream atual, atualizados quando mudam. */
internal class StreamState(val tracks: Tracks, val videoSize: VideoSize)

@Composable
internal fun rememberStreamState(player: Player?): StreamState {
    var state by remember(player) { mutableStateOf(StreamState(player?.currentTracks ?: Tracks.EMPTY, player?.videoSize ?: VideoSize.UNKNOWN)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                state = StreamState(t, state.videoSize)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                state = StreamState(state.tracks, size)
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    return state
}

/** Uma faixa de áudio/legendas a escolher (ou "Desligadas"). */
internal class TrackOption(val key: String, val label: String, val details: String?, val selected: Boolean, val select: () -> Unit)

/** As faixas de [type] (áudio ou legendas) para escolher; [allowOff] junta "Desligadas" (legendas). */
internal fun trackOptions(tracks: Tracks, type: Int, player: Player?, allowOff: Boolean): List<TrackOption> {
    val groups = tracks.groups.filter { it.type == type }
    if (player == null || groups.isEmpty()) return emptyList()
    val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(type)
    return buildList {
        if (allowOff) {
            add(
                TrackOption("off", I18n.s.subtitlesOff, null, disabled || groups.none { it.isSelected }) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
                },
            )
        }
        var n = 0
        groups.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val index = n++
                add(
                    TrackOption("t:$index", trackLabel(f, index), trackDetails(f), !disabled && g.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build()
                    },
                )
            }
        }
    }
}

internal fun Tracks.audioLabel(): String? {
    groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
        for (i in 0 until g.length) if (g.isTrackSelected(i)) return trackLabel(g.getTrackFormat(i), i)
    }
    return null
}

internal fun Tracks.textLabel(player: Player?): String {
    if (groups.none { it.type == C.TRACK_TYPE_TEXT }) return I18n.s.none
    if (player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true) return I18n.s.subtitlesOff
    groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
        for (i in 0 until g.length) if (g.isTrackSelected(i)) return trackLabel(g.getTrackFormat(i), i)
    }
    return I18n.s.subtitlesOff
}

internal fun Tracks.selectedFormat(type: Int): Format? = groups.filter { it.type == type }.firstNotNullOfOrNull { g ->
    (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) }
}

internal fun trackLabel(f: Format, index: Int): String =
    f.label?.takeIf { it.isNotBlank() }
        ?: f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(I18n.s.locale).replaceFirstChar { it.titlecase(I18n.s.locale) } }
        ?: I18n.s.track(index + 1)

internal fun trackDetails(f: Format): String =
    listOfNotNull(codecName(f.sampleMimeType), channelsLabel(f.channelCount)).joinToString(" · ")

internal fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "H.265 (HEVC)"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.AUDIO_AAC, "audio/mp4a-latm" -> "AAC"
    MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
    null -> null
    else -> mime.substringAfter('/').uppercase()
}

internal fun channelsLabel(n: Int): String? = when {
    n <= 0 -> null
    n == 1 -> "Mono"
    n == 2 -> I18n.s.stereo
    n == 6 -> "5.1"
    n == 8 -> "7.1"
    else -> I18n.s.channelsAudio(n)
}

/** Etiquetas de qualidade para o HUD: "1080p", "50 fps", "HDR10", "H.264", "AAC · Estéreo", "TS". */
internal fun qualityBadges(stream: StreamState, container: String?): List<String> {
    val v = stream.tracks.selectedFormat(C.TRACK_TYPE_VIDEO)
    val a = stream.tracks.selectedFormat(C.TRACK_TYPE_AUDIO)
    val height = stream.videoSize.height.takeIf { it > 0 } ?: v?.height?.takeIf { it > 0 }
    return listOfNotNull(
        height?.let {
            when {
                it >= 2000 -> "4K"
                it >= 1400 -> "1440p"
                it >= 1000 -> "1080p"
                it >= 700 -> "720p"
                else -> "${it}p"
            }
        },
        v?.frameRate?.takeIf { it > 0f }?.let { r ->
            if (r % 1f == 0f || r > 100f) "%.0f fps".format(Locale.ROOT, r) else "%.2f fps".format(Locale.ROOT, r).replace(".00", "")
        },
        HdrType.of(v)?.label,
        // O Dolby Vision já aparece como HDR; o codec repetia-o.
        v?.takeIf { it.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION }?.let { codecName(it.sampleMimeType)?.substringBefore(" (") },
        a?.let { listOfNotNull(codecName(it.sampleMimeType)?.substringBefore(" ("), channelsLabel(it.channelCount)).joinToString(" ") },
        when (container) {
            StreamFormat.TS -> "TS"
            StreamFormat.HLS -> "HLS"
            else -> null
        },
    )
}
