package cc.slokker.itv.data

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** As formas de abrir um canal (o fallback ts ⇄ m3u8) e do catch-up: as mesmas regras no Android e na Apple. */
object StreamRules {
    /** As formas, pela ordem: a preferida da conta ([preferred], null = automático: ts primeiro) e a outra. */
    fun order(preferred: String?): List<String> =
        if (preferred == StreamFormat.HLS) listOf(StreamFormat.HLS, StreamFormat.TS) else listOf(StreamFormat.TS, StreamFormat.HLS)

    /** Xtream: só as formas que o servidor diz que permite ([allowed], vazio = não diz), senão as duas. */
    fun xtreamFormats(preferred: String?, allowed: Set<String>): List<String> =
        order(preferred).let { o -> o.filter { it in allowed }.ifEmpty { o } }

    /**
     * M3U: o URL do canal e o mesmo com a outra extensão (.m3u8 ⇄ .ts); sem extensão, o mesmo URL primeiro como HLS
     * (o mais comum em M3U), a não ser que se prefira TS.
     */
    fun m3uCandidates(url: String, preferred: String?): List<StreamCandidate> {
        val path = url.substringBefore('?').substringBefore('#')
        fun swap(from: String, to: String) = path.dropLast(from.length) + to + url.substring(path.length)
        return when {
            path.endsWith(".m3u8", ignoreCase = true) ->
                listOf(StreamCandidate(url, StreamFormat.HLS), StreamCandidate(swap(".m3u8", ".ts"), StreamFormat.TS))
            path.endsWith(".ts", ignoreCase = true) ->
                listOf(StreamCandidate(url, StreamFormat.TS), StreamCandidate(swap(".ts", ".m3u8"), StreamFormat.HLS))
            preferred == StreamFormat.TS -> listOf(StreamCandidate(url, StreamFormat.TS), StreamCandidate(url, StreamFormat.HLS))
            else -> listOf(StreamCandidate(url, StreamFormat.HLS), StreamCandidate(url, StreamFormat.TS))
        }
    }
}

/** O catch-up: quando um programa ainda está no arquivo e como se pede ao servidor. */
object CatchupRules {
    /** O servidor precisa de uns minutos para ter um programa em arquivo. */
    const val MIN_AGE_MS = 2 * 60_000L

    /** Mais do que isto não é um arquivo a sério (um número estragado na lista). */
    const val MAX_ARCHIVE_DAYS = 3650

    /**
     * O programa que começou em [startMs] ainda está no arquivo (agora [nowMs])? Num canal com [archiveDays]; numa
     * lista M3U ([m3u]), só com o tipo de catch-up ([catchupType]: as listas guardadas antes de existir não sabem
     * pedi-lo); e pelo menos [MIN_AGE_MS] depois de começar.
     */
    fun available(m3u: Boolean, archiveDays: Int, catchupType: String?, startMs: Long, nowMs: Long): Boolean {
        if (archiveDays <= 0 || (m3u && catchupType == null)) return false
        // O valor pode vir de uma cópia em cache, de antes de ser limitado ao ler a lista.
        return startMs < nowMs - MIN_AGE_MS && startMs > nowMs - minOf(archiveDays, MAX_ARCHIVE_DAYS) * 86_400_000L
    }

    /** Xtream: os dias de arquivo de um canal (`tv_archive` "1" e `tv_archive_duration`); 0 = sem catch-up. */
    fun xtreamArchiveDays(tvArchive: String?, duration: String?): Int {
        if (tvArchive?.trim() != "1") return 0
        return duration?.trim()?.toDoubleOrNull()?.toInt()?.coerceIn(1, MAX_ARCHIVE_DAYS) ?: 1
    }

    /** [value] arredondado para baixo a múltiplos de [unit] (também antes de 1970). */
    fun floorTo(value: Long, unit: Long): Long = value - value.mod(unit)

    /** Xtream: quantos minutos pedir de [startMs] (já ao minuto) até ao fim do programa [endMs] (pelo menos 1). */
    fun xtreamMinutes(startMs: Long, endMs: Long): Int = ((endMs - startMs + 59_999) / 60_000).toInt().coerceAtLeast(1)

    /** Xtream: o início como o servidor o quer ("2026-11-12:21-05"), na hora do fuso dele ([timezone]; sem ele, deste aparelho). */
    fun xtreamStart(startMs: Long, timezone: String?): String {
        val zone = timezone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()
        val t = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year.toString().padStart(4, '0')}-${two(t.month.number)}-${two(t.day)}:${two(t.hour)}-${two(t.minute)}"
    }
}
