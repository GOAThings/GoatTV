package cc.slokker.itv.data

/**
 * As variantes do mesmo canal numa lista ("RTP 1 HD", "RTP 1 SD", "RTP 1 FHD"…): o mesmo nome, país e atraso (+1)
 * depois de tirar a qualidade (ChannelMatch.parse). Com "Juntar variantes" ligado (Prefs.mergeVariants), cada canal
 * fica uma linha só: a de melhor qualidade (a primeira da lista, em empate), e as outras passam a failovers dela, da
 * melhor para a pior. Igual na Apple TV (GoatCore ChannelVariants), com a tabela channel_match.json ("variants").
 */
object ChannelVariants {
    /** Os canais da lista (chave e nome), pela ordem dela → a melhor variante de cada canal → as outras, pela ordem. */
    fun of(channels: List<Pair<String, String>>): Map<String, List<String>> {
        class Item(val key: String, val quality: Int, val index: Int)
        val groups = LinkedHashMap<String, MutableList<Item>>()
        channels.forEachIndexed { i, (key, name) ->
            val p = ChannelMatch.parse(name)
            if (p.base.length < 2) return@forEachIndexed
            groups.getOrPut("${p.base}|${p.country.orEmpty()}|${p.shift}") { mutableListOf() } += Item(key, p.quality, i)
        }
        val out = LinkedHashMap<String, List<String>>()
        for (items in groups.values) {
            val unique = items.distinctBy { it.key }
            if (unique.size < 2) continue
            val sorted = unique.sortedWith(compareByDescending<Item> { it.quality }.thenBy { it.index })
            out[sorted.first().key] = sorted.drop(1).map { it.key }
        }
        return out
    }
}
