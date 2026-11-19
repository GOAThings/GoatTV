package cc.slokker.itv.data

import kotlin.concurrent.Volatile

/**
 * Modo TV Pro: as fontes que falharam ("lista|canal" → quando), guardadas na TV (não vão para a conta) para a próxima
 * vez já começar pela que dá, em vez de esperar que a má caia outra vez. Uma fonte que falhou não sai: passa para o fim
 * da fila durante [TTL_MS] (o fornecedor pode tê-la arranjado entretanto), ou até a pessoa a escolher no painel das
 * fontes.
 *
 * Muitas vezes a falha é de uma versão inteira da lista (todos os "RTP 1 ᴴᴰ", "SIC ᴴᴰ"… de um fornecedor caem):
 * quando [VARIANT_FAILS] canais diferentes da mesma versão ([variantOf]: o que o nome tem além do canal) falharam na
 * mesma lista, todos os dessa versão passam para o fim, sem ter de falhar um a um.
 *
 * O texto guardado ([encode]/[decode]) é uma linha por fonte: `<ms>\t<lista>|<canal>`; as versões são
 * `<lista>|~<versão>` (em baixo) e `<lista>|~<versão>~<canal>` (um canal dela que falhou).
 */
class ProHealth(entries: Map<String, Long> = emptyMap()) {
    // Cópia nova a cada mudança: quem lê (a UI, o player) nunca vê o mapa a meio de uma escrita.
    @Volatile
    private var failed: Map<String, Long> = LinkedHashMap(entries)

    /**
     * A fonte [channelKey] de [playlistId] está em baixo (falhou há menos de [TTL_MS]), ou a versão dela
     * ([channelName], ver [variantOf]) falhou nessa lista.
     */
    fun isDown(playlistId: String, channelKey: String, nowMs: Long, channelName: String? = null): Boolean {
        val map = failed
        fun recent(k: String) = map[k]?.let { nowMs - it in 0 until TTL_MS } ?: false
        if (recent(key(playlistId, channelKey))) return true
        val v = channelName?.let(::variantOf).orEmpty()
        return v.isNotEmpty() && recent(variantKey(playlistId, v))
    }

    /** A fonte falhou agora (com [channelName], conta também para a versão dela). */
    fun markDown(playlistId: String, channelKey: String, nowMs: Long, channelName: String? = null) {
        val next = LinkedHashMap(failed)
        fun put(k: String) {
            next.remove(k)
            next[k] = nowMs
        }
        put(key(playlistId, channelKey))
        val v = channelName?.let(::variantOf).orEmpty()
        if (v.isNotEmpty()) {
            val prefix = variantKey(playlistId, v) + "~"
            put(prefix + channelKey)
            val fails = next.count { (k, at) -> k.startsWith(prefix) && nowMs - at in 0 until TTL_MS }
            if (fails >= VARIANT_FAILS) put(variantKey(playlistId, v))
        }
        // Não cresce sem fim: as mais antigas saem primeiro.
        while (next.size > MAX) next.remove(next.keys.first())
        failed = next
    }

    /**
     * A pessoa escolheu a fonte no painel: deixa de estar em baixo, e a versão dela também (a pessoa diz que dá).
     * Devolve true se mudou alguma coisa.
     */
    fun markUp(playlistId: String, channelKey: String, channelName: String? = null): Boolean {
        val v = channelName?.let(::variantOf).orEmpty()
        val keys = listOf(key(playlistId, channelKey)) +
            if (v.isNotEmpty()) failed.keys.filter { it == variantKey(playlistId, v) || it.startsWith(variantKey(playlistId, v) + "~") } else emptyList()
        if (keys.none { it in failed }) return false
        failed = LinkedHashMap(failed).apply { keys.forEach { remove(it) } }
        return true
    }

    /** O texto a guardar, sem as que já passaram do prazo. */
    fun encode(nowMs: Long): String =
        failed.entries.filter { nowMs - it.value in 0 until TTL_MS }.joinToString("\n") { "${it.value}\t${it.key}" }

    private fun key(playlistId: String, channelKey: String) = "$playlistId|$channelKey"

    private fun variantKey(playlistId: String, variant: String) = "$playlistId|~$variant"

    companion object {
        /** Quanto tempo uma fonte que falhou fica para o fim: 3 dias. */
        const val TTL_MS = 3L * 24 * 60 * 60 * 1000

        private const val MAX = 500

        /** Quantos canais diferentes de uma versão têm de falhar na mesma lista para a versão toda ir para o fim. */
        const val VARIANT_FAILS = 2

        /**
         * A versão de um canal numa lista: o que o nome tem além do canal, como está escrito ("SIC ᴴᴰ" → "ᴴᴰ",
         * "PT| RTP 1 FHD" → "FHD"); vazia se o nome é só o canal (aí não se junta nada: pode ser qualquer um).
         */
        fun variantOf(name: String): String =
            name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && ChannelMatch.parse(it).base.isEmpty() }.joinToString(" ")

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
