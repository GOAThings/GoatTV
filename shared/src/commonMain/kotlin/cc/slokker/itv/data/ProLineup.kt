package cc.slokker.itv.data

/**
 * O "Modo TV Pro": uma lista só, arrumada como a box de um operador do país escolhido (números e grupos da [Lineup]),
 * feita com os canais de todas as listas da pessoa. Cada canal fica com as suas fontes, da melhor para a pior: a
 * qualidade (FHD antes de HD antes de SD), depois a lista (pela ordem das listas) e depois a ordem na lista. A primeira
 * é a que dá; as outras são os failovers. As listas em si deixam de aparecer.
 *
 * Um canal da lista conta para um canal da tabela quando o nome normalizado (ChannelMatch.parse: sem país, qualidade,
 * acentos nem espaços) é um dos nomes dele, sem `+1`, e não é de outro país (nem pelo nome, nem pelo grupo). Os canais
 * do país que a tabela não tem vão para "Mais canais" ([MORE]), juntos da mesma maneira pelo nome.
 */
object ProLineup {
    /**
     * Um canal da tabela do operador: o número, o nome a mostrar, o grupo e os outros nomes por que aparece nas listas.
     * [foreign]: um canal estrangeiro que o operador também tem (Sky News, TV5Monde): conta mesmo no grupo de outro país.
     */
    data class Slot(val number: Int, val name: String, val group: String, val aliases: List<String> = emptyList(), val foreign: Boolean = false)

    /** A tabela de um país ([id]: "pt", arrumada como a do [operator]): os grupos pela ordem e os canais. */
    data class Lineup(val id: String, val country: String, val operator: String, val groups: List<String>, val slots: List<Slot>)

    /** Um canal de uma lista, com o nome do grupo dele (para saber o país quando o nome não diz). */
    data class Input(val key: String, val name: String, val group: String?)

    /** As listas da pessoa, pela ordem de preferência. */
    data class Source(val playlistId: String, val channels: List<Input>)

    /** Uma fonte de um canal: a lista, a chave do canal nela e a qualidade pelo nome (ChannelMatch.Quality). */
    data class Ref(val playlistId: String, val key: String, val quality: Int)

    /**
     * Um canal da lista Pro: [id] fica igual entre arranques e entre operadores (favoritos, recentes), [sources] da
     * melhor para a pior.
     */
    data class Channel(val id: String, val number: Int, val name: String, val group: String, val sources: List<Ref>)

    data class Result(val groups: List<String>, val channels: List<Channel>)

    /** O grupo dos canais do país que não estão na tabela. */
    const val MORE = "more"

    /**
     * Os "Mais canais" começam aqui, ou na centena a seguir ao número mais alto da tabela (como os canais acima dos
     * principais numa box): nunca têm o número de um canal da tabela.
     */
    const val MORE_FROM = 200

    /** Onde começam os "Mais canais" de [lineup]. */
    fun moreFrom(lineup: Lineup?): Int {
        val top = lineup?.slots?.maxOfOrNull { it.number } ?: return MORE_FROM
        return maxOf(MORE_FROM, (top / 100 + 1) * 100)
    }

    /** As tabelas que se podem escolher, pela ordem do menu. */
    val LINEUPS: List<Lineup> get() = listOf(
        ProLineups.PT, ProLineupsES.ES, ProLineupsFR.FR, ProLineupsIT.IT, ProLineupsDE.DE, ProLineupsGB.GB, ProLineupsNL.NL,
        ProLineupsBR.BR, ProLineupsUS.US,
    )

    /** O id da lista Pro da tabela [lineupId] (não é uma lista da pessoa: não se guarda em cache nem vai para a conta). */
    fun playlistId(lineupId: String) = "$PREFIX$lineupId"

    fun isPro(playlistId: String) = playlistId.startsWith(PREFIX)

    /** A tabela da lista Pro [playlistId]. */
    fun lineupIdOf(playlistId: String) = playlistId.removePrefix(PREFIX)

    private const val PREFIX = "pro:"

    /** A tabela [id] ("pt"), se existir. */
    fun lineup(id: String): Lineup? = LINEUPS.firstOrNull { it.id == id.lowercase() }

    fun build(lineup: Lineup?, country: String, sources: List<Source>): Result {
        val cc = country.lowercase()
        // O nome normalizado de cada nome da tabela → o canal da tabela.
        val slotOf = HashMap<String, Slot>()
        lineup?.slots?.forEach { slot -> (listOf(slot.name) + slot.aliases).forEach { n -> slotOf.putIfAbsent(ChannelMatch.parse(n).base, slot) } }

        class Hit(val ref: Ref, val explicit: Boolean, val order: Int)
        val bySlot = HashMap<Int, MutableList<Hit>>()
        val more = LinkedHashMap<String, MutableList<Hit>>()
        val moreName = HashMap<String, String>()
        var order = 0
        for (source in sources) {
            for (c in source.channels) {
                order++
                val p = ChannelMatch.parse(c.name)
                if (p.base.length < 2) continue
                val group = c.group?.let { GroupCountries.of(it) }
                // De outro país (pelo nome ou pelo grupo): não é este canal, é a versão de lá.
                val slot = if (p.shift == 0) slotOf[p.base] else null
                if (slot?.foreign != true && ((p.country != null && p.country != cc) || (group != null && group != cc))) continue
                val explicit = p.country == cc || group == cc
                val ref = Ref(source.playlistId, c.key, p.quality)
                when {
                    slot != null -> bySlot.getOrPut(slot.number) { mutableListOf() } += Hit(ref, explicit, order)
                    // Os "Mais canais" só com os que são mesmo do país (um grupo "Desporto" sem país pode ser de qualquer lado).
                    explicit -> {
                        val id = if (p.shift == 0) p.base else "${p.base}+${p.shift}"
                        more.getOrPut(id) { mutableListOf() } += Hit(ref, true, order)
                        moreName.putIfAbsent(id, cleanName(c.name))
                    }
                }
            }
        }
        val best = compareByDescending<Hit> { it.ref.quality }.thenByDescending { it.explicit }.thenBy { it.order }
        val channels = mutableListOf<Channel>()
        lineup?.slots?.sortedBy { it.number }?.forEach { slot ->
            val hits = bySlot[slot.number] ?: return@forEach
            channels += Channel("pro:$cc:${ChannelMatch.parse(slot.name).base}", slot.number, slot.name, slot.group, hits.sortedWith(best).map { it.ref }.distinct())
        }
        var n = moreFrom(lineup)
        more.entries.sortedBy { moreName[it.key]?.lowercase() }.forEach { (id, hits) ->
            channels += Channel("pro:$cc:more:$id", n++, moreName.getValue(id), MORE, hits.sortedWith(best).map { it.ref }.distinct())
        }
        val used = channels.map { it.group }.toSet()
        val groups = (lineup?.groups.orEmpty() + MORE).filter { it in used }
        return Result(groups, channels)
    }

    /** O nome sem o país à frente nem a qualidade ("PT| SIC Radical FHD" → "SIC Radical"), para os "Mais canais". */
    internal fun cleanName(name: String): String {
        var s = name.trim()
        // O prefixo do país ou do grupo: "PT|", "PT:", "[PT]".
        Regex("^\\s*(\\[[^\\]]{1,12}\\]|\\([^)]{1,12}\\)|[^|:▎┃│¦»\\s]{1,6}\\s*[|:▎┃│¦»])\\s*").find(s)?.let { s = s.substring(it.range.last + 1) }
        s = s.replace(Regex("(?i)\\s*[\\[(]?\\b(4k|uhd|fhd|full ?hd|hd|sd|hevc|h\\.?26[45]|1080[pi]|720p|576p|480p)\\b[\\])]?"), "")
        return s.trim().ifEmpty { name.trim() }
    }
}
