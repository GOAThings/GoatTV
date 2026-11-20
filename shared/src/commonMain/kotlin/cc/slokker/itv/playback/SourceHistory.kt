package cc.slokker.itv.playback

import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Quão fiável tem sido cada fonte (a lista e o canal nela) neste aparelho: as aberturas, as que falharam, as quedas
// e o tempo a ver, por dia, nos últimos [SourceHistory.WINDOW_DAYS] dias. Só neste aparelho (não vai para a conta); o
// mesmo no Android e na Apple. Serve para escolher a fonte a abrir primeiro ([SourceReliability.order]) e para dizer
// à pessoa o que se passou ("3 quedas em 2 h esta semana").

/** O que a [SourceHistory] soma de uma fonte na janela: sem pesos, tal como aconteceu. */
class SourceStats(
    /** As vezes que abriu e deu. */
    val opens: Int,
    /** As vezes que não abriu (sem contar as recusas da conta toda, que não são desta fonte). */
    val failedOpens: Int,
    /** As quedas depois de dar (a ligação caiu, ou o direto ficou preso). */
    val drops: Int,
    /** O tempo a ver, em segundos. */
    val watchedSec: Long,
    /** Quando falhou a última vez (ms); 0 = nunca na janela. */
    val lastFailMs: Long,
)

/**
 * O historial por fonte ("lista|canal", a mesma chave do [cc.slokker.itv.data.ProHealth]), em baldes por dia
 * ([WINDOW_DAYS] dias, para não crescer), das [MAX] fontes usadas há menos tempo.
 *
 * O texto guardado ([encode]/[decode]) é uma linha por fonte, da mais antiga para a mais recente:
 * `<visto ms>\t<falhou ms>\t<dia>:<aberturas>:<falhas>:<quedas>:<segundos>,…\t<lista>|<canal>`.
 */
class SourceHistory(entries: Map<String, Entry> = emptyMap()) {
    /** Um dia de uma fonte ([day] = dias desde 1970, em UTC). */
    class Day(val day: Int, val opens: Int, val failed: Int, val drops: Int, val watchSec: Long)

    class Entry(val lastSeen: Long, val lastFail: Long, val days: List<Day>)

    // Cópia nova a cada mudança: quem lê (a UI, o player) nunca vê o mapa a meio de uma escrita.
    @Volatile
    private var map: Map<String, Entry> = LinkedHashMap(entries)

    /** A fonte abriu ([ok]) ou não abriu. Devolve true se mudou alguma coisa (há que gravar). */
    fun recordOpen(key: String, ok: Boolean, nowMs: Long): Boolean =
        update(key, nowMs, failed = !ok) { d -> if (ok) Day(d.day, d.opens + 1, d.failed, d.drops, d.watchSec) else Day(d.day, d.opens, d.failed + 1, d.drops, d.watchSec) }

    /** A ligação da fonte caiu depois de dar. */
    fun recordDrop(key: String, nowMs: Long): Boolean =
        update(key, nowMs, failed = false) { d -> Day(d.day, d.opens, d.failed, d.drops + 1, d.watchSec) }

    /** A pessoa viu [seconds] s da fonte. */
    fun recordWatched(key: String, seconds: Long, nowMs: Long): Boolean {
        if (seconds <= 0) return false
        val s = min(seconds, MAX_WATCH_SEC)
        return update(key, nowMs, failed = false) { d -> Day(d.day, d.opens, d.failed, d.drops, d.watchSec + s) }
    }

    /** O que a fonte somou nos últimos [WINDOW_DAYS] dias; null se nada. */
    fun stats(key: String, nowMs: Long): SourceStats? {
        val entry = map[key] ?: return null
        val today = dayOf(nowMs)
        var opens = 0
        var failed = 0
        var drops = 0
        var watch = 0L
        var any = false
        for (d in entry.days) {
            if (today - d.day !in 0 until WINDOW_DAYS) continue
            any = true
            opens += d.opens
            failed += d.failed
            drops += d.drops
            watch += d.watchSec
        }
        if (!any) return null
        return SourceStats(opens, failed, drops, watch, entry.lastFail.takeIf { nowMs - it in 0 until WINDOW_DAYS * DAY_MS } ?: 0L)
    }

    /**
     * A nota da fonte, 0 a 100 ([SourceReliability.NEUTRAL] sem historial): desce com as quedas por hora a ver e com a
     * razão de aberturas falhadas. Os dias mais recentes pesam mais ([DECAY] por dia).
     */
    fun score(key: String, nowMs: Long): Int {
        val entry = map[key] ?: return SourceReliability.NEUTRAL
        val today = dayOf(nowMs)
        var opens = 0.0
        var failed = 0.0
        var drops = 0.0
        var watch = 0.0
        var any = false
        for (d in entry.days) {
            val age = today - d.day
            if (age !in 0 until WINDOW_DAYS) continue
            any = true
            val w = WEIGHTS[age]
            opens += d.opens * w
            failed += d.failed * w
            drops += d.drops * w
            watch += d.watchSec * w
        }
        if (!any) return SourceReliability.NEUTRAL
        // Pouco visto não faz uma queda valer mais do que 4 por hora.
        val hours = max(watch / 3600.0, MIN_HOURS)
        val dropPenalty = min(MAX_DROP_PENALTY, drops / hours * DROP_PENALTY_PER_HOUR)
        val attempts = opens + failed
        // Uma só falha não chega para a condenar: o peso cresce até duas tentativas.
        val failPenalty = if (failed > 0 && attempts > 0) failed / attempts * FAIL_PENALTY * min(1.0, attempts / 2.0) else 0.0
        return (SourceReliability.NEUTRAL - dropPenalty - failPenalty).roundToInt().coerceIn(0, SourceReliability.NEUTRAL)
    }

    /** A fonte tem uma nota claramente má ([SourceReliability.BAD_SCORE]): vai para trás das boas. */
    fun isBad(key: String, nowMs: Long): Boolean = score(key, nowMs) < SourceReliability.BAD_SCORE

    /** O texto a guardar, sem os dias que já passaram da janela. */
    fun encode(nowMs: Long): String {
        val today = dayOf(nowMs)
        return map.entries.mapNotNull { (k, e) ->
            val days = e.days.filter { today - it.day in 0 until WINDOW_DAYS }
            if (days.isEmpty()) null
            else "${e.lastSeen}\t${e.lastFail}\t${days.joinToString(",") { "${it.day}:${it.opens}:${it.failed}:${it.drops}:${it.watchSec}" }}\t$k"
        }.joinToString("\n")
    }

    private fun update(key: String, nowMs: Long, failed: Boolean, change: (Day) -> Day): Boolean {
        // Uma chave com separadores não cabe no texto guardado (não há listas assim; fica sem historial).
        if ('|' !in key || key.any { it == '\t' || it == '\n' || it == '\r' }) return false
        val today = dayOf(nowMs)
        val current = map
        val old = current[key]
        // (Um relógio mudado para trás não apaga os dias que ficam à frente.)
        val days = (old?.days.orEmpty()).filter { today - it.day < WINDOW_DAYS }.toMutableList()
        val at = days.indexOfFirst { it.day == today }
        if (at >= 0) days[at] = change(days[at]) else days += change(Day(today, 0, 0, 0, 0L))
        val next = LinkedHashMap(current)
        next.remove(key)
        next[key] = Entry(nowMs, if (failed) nowMs else old?.lastFail ?: 0L, days)
        // Não cresce sem fim: as usadas há mais tempo saem primeiro.
        while (next.size > MAX) next.remove(next.keys.first())
        map = next
        return true
    }

    companion object {
        /** Quantos dias conta o historial. */
        const val WINDOW_DAYS = 7

        /** Quantas fontes se guardam. */
        const val MAX = 1000

        private const val DAY_MS = 86_400_000L
        private const val MAX_WATCH_SEC = 86_400L

        /** Quanto vale cada dia, do de hoje para trás. */
        private const val DECAY = 0.85
        private val WEIGHTS = DoubleArray(WINDOW_DAYS).also { w ->
            var x = 1.0
            for (i in w.indices) {
                w[i] = x
                x *= DECAY
            }
        }

        private const val MIN_HOURS = 0.25
        private const val DROP_PENALTY_PER_HOUR = 8.0
        private const val MAX_DROP_PENALTY = 60.0
        private const val FAIL_PENALTY = 60.0

        /** A chave de uma fonte: a lista e o canal nela (como no ProHealth). */
        fun key(playlistId: String, channelKey: String): String = "$playlistId|$channelKey"

        private fun dayOf(nowMs: Long): Int = (nowMs / DAY_MS).toInt()

        fun decode(text: String?, nowMs: Long): SourceHistory {
            val today = dayOf(nowMs)
            val entries = LinkedHashMap<String, Entry>()
            text.orEmpty().lineSequence().forEach { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4 || '|' !in parts[3]) return@forEach
                val seen = parts[0].toLongOrNull() ?: return@forEach
                val fail = parts[1].toLongOrNull() ?: return@forEach
                val days = parts[2].split(',').mapNotNull { d ->
                    val f = d.split(':')
                    if (f.size != 5) return@mapNotNull null
                    val day = f[0].toIntOrNull() ?: return@mapNotNull null
                    val opens = f[1].toIntOrNull() ?: return@mapNotNull null
                    val failed = f[2].toIntOrNull() ?: return@mapNotNull null
                    val drops = f[3].toIntOrNull() ?: return@mapNotNull null
                    val watch = f[4].toLongOrNull() ?: return@mapNotNull null
                    if (today - day !in 0 until WINDOW_DAYS || opens < 0 || failed < 0 || drops < 0 || watch < 0) null else Day(day, opens, failed, drops, watch)
                }
                if (days.isNotEmpty()) entries[parts[3]] = Entry(seen, fail, days)
            }
            while (entries.size > MAX) entries.remove(entries.keys.first())
            return SourceHistory(entries)
        }
    }
}

/** A ordem das fontes segundo o [SourceHistory] (o que se abre primeiro), e como dizer o tempo a ver. */
object SourceReliability {
    /** A nota de uma fonte sem historial (e a máxima). */
    const val NEUTRAL = 100

    /** Abaixo disto a fonte é claramente má: vai para trás das boas (nunca fica de fora). */
    const val BAD_SCORE = 50

    /** Notas a menos de [BAND] pontos uma da outra contam como iguais (não se troca a ordem por 97 contra 95). */
    private const val BAND = 10

    /**
     * [items] com as fontes más ([SourceHistory.isBad]) no fim, e, entre as boas (e entre as más), as mais fiáveis
     * primeiro, mas só entre vizinhas do mesmo [tier] (a qualidade, por exemplo): nunca passa uma melhor para trás de
     * uma pior. [key] é a chave da fonte ([SourceHistory.key]), ou null se não tem (conta como sem historial). Sem
     * historial, devolve a mesma ordem.
     */
    fun <T> order(items: List<T>, history: SourceHistory?, nowMs: Long, tier: (T) -> Int, key: (T) -> String?): List<T> {
        if (history == null || items.size < 2) return items
        val scores = items.map { item -> key(item)?.let { history.score(it, nowMs) } ?: NEUTRAL }
        return orderIndices(scores, items.map(tier)).map { items[it] }
    }

    /** O mesmo sobre as notas e as qualidades de cada fonte: as posições de [scores] pela ordem nova (a Apple usa esta). */
    fun orderIndices(scores: List<Int>, tiers: List<Int>): List<Int> {
        fun regroup(ids: List<Int>): List<Int> {
            val out = ArrayList<Int>(ids.size)
            var i = 0
            while (i < ids.size) {
                var j = i + 1
                while (j < ids.size && tiers[ids[j]] == tiers[ids[i]]) j++
                out += byScore(ids.subList(i, j), scores)
                i = j
            }
            return out
        }
        val good = scores.indices.filter { scores[it] >= BAD_SCORE }
        val bad = scores.indices.filter { scores[it] < BAD_SCORE }
        return regroup(good) + regroup(bad)
    }

    /** [ids] por nota, da maior para a menor, mas só passa uma à frente de outra se for [BAND] pontos melhor (estável). */
    private fun byScore(ids: List<Int>, scores: List<Int>): List<Int> {
        val out = ids.toMutableList()
        for (i in 1 until out.size) {
            var j = i
            while (j > 0 && scores[out[j]] - scores[out[j - 1]] >= BAND) {
                val x = out[j]
                out[j] = out[j - 1]
                out[j - 1] = x
                j--
            }
        }
        return out
    }

    /** O tempo a ver como se diz ("35 min", "2 h", "2 h 30 min"). */
    fun watchedLabel(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours == 0L -> "${max(1L, minutes)} min"
            minutes < 5 -> "$hours h"
            else -> "$hours h $minutes min"
        }
    }

    /** Com tão pouco visto, sem falhas nem quedas, ainda não se diz que é estável. */
    const val STABLE_MIN_SEC = 600L
}
