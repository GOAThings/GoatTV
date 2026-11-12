package cc.slokker.itv.data

import cc.slokker.itv.data.m3u.M3uCatchup
import cc.slokker.itv.data.xtream.XtreamSource

/**
 * Lista ordenada de tentativas para [channel].
 * [preferred] é o `settings.stream_format` da conta (null = Auto).
 * [allowedFormats] é o `allowed_output_formats` da conta Xtream (vazio = não indicado).
 */
fun streamCandidates(
    playlist: Playlist,
    channel: Channel,
    preferred: String?,
    allowedFormats: Set<String> = emptySet(),
): List<StreamCandidate> {
    // O link pede-se ao portal ao abrir o canal (StalkerSource.candidates).
    if (playlist.type.kind.linkAtPlayTime) return emptyList()

    if (playlist.type == PlaylistType.XTREAM) {
        val id = channel.streamId ?: return emptyList()
        // Se o servidor diz que formatos permite, não vale a pena tentar os outros (StreamRules, igual na Apple).
        return StreamRules.xtreamFormats(preferred, allowedFormats).map { StreamCandidate(XtreamSource.streamUrl(playlist, id, it), it) }
    }

    return StreamRules.m3uCandidates(channel.url ?: return emptyList(), preferred)
}

/**
 * Tentativas para ver [catchup] (Xtream e M3U; o Stalker pede o link ao portal, ver StalkerSource.archive). O Xtream
 * trabalha ao minuto (e o M3U conforme o tipo, ver [M3uCatchup.precisionMs]): pede-se a partir do minuto (ou do
 * segundo) da posição e devolve-se esse início em [CatchupStart.startMs], para o player saber onde está.
 * [correctionMin] (Definições → Guia) soma-se só à hora pedida ao servidor: o vídeo que vem é o do programa.
 */
fun catchupCandidates(
    playlist: Playlist,
    catchup: Catchup,
    timezone: String?,
    correctionMin: Int = 0,
    nowMs: Long = System.currentTimeMillis(),
): CatchupStart? {
    if (!playlist.type.kind.supportsCatchup) return null
    val wanted = catchup.startMs + catchup.positionMs
    val shift = correctionMin * 60_000L
    return when (playlist.type) {
        PlaylistType.XTREAM -> {
            val id = catchup.channel.streamId ?: return null
            val start = CatchupRules.floorTo(wanted, 60_000L)
            val minutes = CatchupRules.xtreamMinutes(start, catchup.endMs)
            val urls = XtreamSource.catchupUrls(playlist, id, start + shift, minutes, timezone)
            CatchupStart(start, urls.map { StreamCandidate(it, StreamFormat.TS) })
        }
        PlaylistType.M3U -> {
            if (catchup.channel.catchupType == null) return null
            val start = CatchupRules.floorTo(wanted, M3uCatchup.precisionMs(catchup.channel))
            val candidates = M3uCatchup.candidates(catchup.channel, (start + shift) / 1000, (catchup.endMs + shift) / 1000, nowMs / 1000)
            CatchupStart(start, candidates).takeIf { candidates.isNotEmpty() }
        }
        PlaylistType.STALKER -> null
    }
}

class CatchupStart(val startMs: Long, val candidates: List<StreamCandidate>)
