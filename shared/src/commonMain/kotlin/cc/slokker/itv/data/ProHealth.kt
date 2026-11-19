package cc.slokker.itv.data

import kotlin.concurrent.Volatile

/**
 * Modo TV Pro: as fontes que falharam ("lista|canal" → quando), guardadas na TV (não vão para a conta) para a próxima
 * vez já começar pela que dá, em vez de esperar que a má caia outra vez. Uma fonte que falhou não sai: passa para o fim
 * da fila durante [TTL_MS] (o fornecedor pode tê-la arranjado entretanto), ou até a pessoa a escolher no painel das
 * fontes.
 *
 * O texto guardado ([encode]/[decode]) é uma linha por fonte: `<ms>\t<lista>|<canal>`.
 */
class ProHealth(entries: Map<String, Long> = emptyMap()) {
    // Cópia nova a cada mudança: quem lê (a UI, o player) nunca vê o mapa a meio de uma escrita.
    @Volatile
    private var failed: Map<String, Long> = LinkedHashMap(entries)

    /** A fonte [channelKey] de [playlistId] está em baixo (falhou há menos de [TTL_MS]). */
    fun isDown(playlistId: String, channelKey: String, nowMs: Long): Boolean =
        failed[key(playlistId, channelKey)]?.let { nowMs - it in 0 until TTL_MS } ?: false

    /** A fonte falhou agora. */
    fun markDown(playlistId: String, channelKey: String, nowMs: Long) {
        val k = key(playlistId, channelKey)
        val next = LinkedHashMap(failed)
        next.remove(k)
        next[k] = nowMs
        // Não cresce sem fim: as mais antigas saem primeiro.
        while (next.size > MAX) next.remove(next.keys.first())
        failed = next
    }

    /** A pessoa escolheu a fonte no painel: deixa de estar em baixo. Devolve true se estava. */
    fun markUp(playlistId: String, channelKey: String): Boolean {
        val k = key(playlistId, channelKey)
        if (k !in failed) return false
        failed = LinkedHashMap(failed).apply { remove(k) }
        return true
    }

    /** O texto a guardar, sem as que já passaram do prazo. */
    fun encode(nowMs: Long): String =
        failed.entries.filter { nowMs - it.value in 0 until TTL_MS }.joinToString("\n") { "${it.value}\t${it.key}" }

    private fun key(playlistId: String, channelKey: String) = "$playlistId|$channelKey"

    companion object {
        /** Quanto tempo uma fonte que falhou fica para o fim: 3 dias. */
        const val TTL_MS = 3L * 24 * 60 * 60 * 1000

        private const val MAX = 500

        fun decode(text: String?, nowMs: Long): ProHealth {
            val entries = LinkedHashMap<String, Long>()
            text.orEmpty().lineSequence().forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val at = line.substring(0, tab).toLongOrNull() ?: return@forEach
                val key = line.substring(tab + 1)
                if ('|' in key && nowMs - at in 0 until TTL_MS) entries[key] = at
            }
            return ProHealth(entries)
        }
    }
}
