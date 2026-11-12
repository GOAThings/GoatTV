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
    val order = if (preferred == StreamFormat.HLS) {
        listOf(StreamFormat.HLS, StreamFormat.TS)
    } else {
        listOf(StreamFormat.TS, StreamFormat.HLS)
    }

    // O link pede-se ao portal ao abrir o canal (StalkerSource.candidates).
    if (playlist.type.kind.linkAtPlayTime) return emptyList()

    if (playlist.type == PlaylistType.XTREAM) {
        val id = channel.streamId ?: return emptyList()
        // Se o servidor diz que formatos permite, não vale a pena tentar os outros.
        return order.filter { it in allowedFormats }.ifEmpty { order }.map { StreamCandidate(XtreamSource.streamUrl(playlist, id, it), it) }
    }

    val url = channel.url ?: return emptyList()
    val path = url.substringBefore('?').substringBefore('#')
    return when {
        path.endsWith(".m3u8", ignoreCase = true) -> listOf(
            StreamCandidate(url, StreamFormat.HLS),
            StreamCandidate(swapExtension(url, path, ".m3u8", ".ts"), StreamFormat.TS),
        )
        path.endsWith(".ts", ignoreCase = true) -> listOf(
            StreamCandidate(url, StreamFormat.TS),
            StreamCandidate(swapExtension(url, path, ".ts", ".m3u8"), StreamFormat.HLS),
        )
        // Sem extensão: o mesmo URL, primeiro como HLS (o mais comum em M3U) a não ser que se prefira TS.
        preferred == StreamFormat.TS -> listOf(StreamCandidate(url, StreamFormat.TS), StreamCandidate(url, StreamFormat.HLS))
        else -> listOf(StreamCandidate(url, StreamFormat.HLS), StreamCandidate(url, StreamFormat.TS))
    }
}

private fun swapExtension(url: String, path: String, from: String, to: String): String =
    path.dropLast(from.length) + to + url.substring(path.length)

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
            val start = wanted - Math.floorMod(wanted, 60_000L)
            val minutes = ((catchup.endMs - start + 59_999) / 60_000).toInt().coerceAtLeast(1)
            val urls = XtreamSource.catchupUrls(playlist, id, start + shift, minutes, timezone)
            CatchupStart(start, urls.map { StreamCandidate(it, StreamFormat.TS) })
        }
        PlaylistType.M3U -> {
            if (catchup.channel.catchupType == null) return null
            val start = wanted - Math.floorMod(wanted, M3uCatchup.precisionMs(catchup.channel))
            val candidates = M3uCatchup.candidates(catchup.channel, (start + shift) / 1000, (catchup.endMs + shift) / 1000, nowMs / 1000)
            CatchupStart(start, candidates).takeIf { candidates.isNotEmpty() }
        }
        PlaylistType.STALKER -> null
    }
}

class CatchupStart(val startMs: Long, val candidates: List<StreamCandidate>)
