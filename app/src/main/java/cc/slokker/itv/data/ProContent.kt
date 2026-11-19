package cc.slokker.itv.data

import cc.slokker.itv.ui.i18n.I18n

/**
 * Modo TV Pro: as listas de onde vêm os canais ([members], como se mostram) e as fontes de cada canal da lista única,
 * da melhor para a pior (ver ProLineup). A primeira que existe é a que dá; as outras são os failovers.
 */
class ProSet(val members: Map<String, PlaylistContent>, private val sources: Map<String, List<ProLineup.Ref>>) {
    /** As fontes que falharam nesta sessão ("lista|canal"): passam para o fim da fila (ver [demote]). */
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** As fontes do canal [key] que ainda existem, como (lista, canal dela), da melhor para a pior (as que falharam no fim). */
    fun resolve(key: String): List<Pair<PlaylistContent, Channel>> = sources[key].orEmpty().mapNotNull { r ->
        members[r.playlistId]?.let { m -> m.byKey[r.key]?.let { m to it } }
    }.sortedBy { (m, c) -> "${m.playlist.id}|${c.key}" in failed }

    /** A fonte [channel] de [member] falhou (cai sempre, ou não abre): até a lista se refazer, as outras vêm antes. */
    fun demote(member: PlaylistContent, channel: Channel) {
        failed += "${member.playlist.id}|${channel.key}"
    }

    /** A qualidade (pelo nome) da melhor fonte do canal [key] (ChannelMatch.Quality), ou null. */
    fun quality(key: String): Int? = sources[key]?.firstOrNull()?.quality

    /** Quantas fontes tem o canal [key]. */
    fun count(key: String): Int = sources[key]?.size ?: 0
}

/** A lista única do Modo TV Pro, feita com as listas da pessoa (ver ProLineup). */
object ProContent {
    /** A "lista" que se abre no Modo TV Pro com a tabela [lineupId]: só o id e o nome; não tem fornecedor. */
    fun playlist(lineupId: String) = Playlist(ProLineup.playlistId(lineupId), PlaylistType.M3U, lineupName(lineupId))

    /** O nome do país da tabela ("Portugal"). */
    fun lineupName(lineupId: String): String = I18n.s.countryName(ProLineup.lineup(lineupId)?.country ?: lineupId)

    /**
     * Os canais de [members] (pela ordem de preferência) arrumados pela tabela [lineupId]. Cada canal fica com o
     * nome e o número da tabela, e com o logótipo e o guia da melhor fonte que os tenha (o guia, de preferência, da
     * lista cujo guia se usa: o da primeira que tem um).
     */
    fun build(lineupId: String, members: List<PlaylistContent>): PlaylistContent {
        val lineup = ProLineup.lineup(lineupId)
        val country = lineup?.country ?: lineupId
        val sources = members.map { m ->
            ProLineup.Source(m.playlist.id, (m.channels + m.hidden).map { ProLineup.Input(it.key, it.name, m.categoryName(it.categoryId)) })
        }
        val result = ProLineup.build(lineup, country, sources)
        val guide = members.firstOrNull { it.guideUrl != null }
        val set = ProSet(members.associateBy { it.playlist.id }, result.channels.associate { it.id to it.sources })
        val channels = result.channels.mapIndexedNotNull { i, pc ->
            val found = set.resolve(pc.id)
            val first = found.firstOrNull()?.second ?: return@mapIndexedNotNull null
            val epgId = found.firstOrNull { (m, c) -> m === guide && !c.epgId.isNullOrBlank() }?.second?.epgId
                ?: found.firstNotNullOfOrNull { (_, c) -> c.epgId?.takeIf { it.isNotBlank() } }
            Channel(
                uid = i,
                key = pc.id,
                num = pc.number,
                name = pc.name,
                logo = found.firstNotNullOfOrNull { (_, c) -> c.logo?.takeIf { it.isNotBlank() } },
                categoryId = pc.group,
                epgId = epgId,
                archiveDays = first.archiveDays,
            )
        }
        val categories = result.groups.map { Category(it, groupName(it)) }
        return PlaylistContent(playlist(lineupId), categories, channels, guideUrl = guide?.guideUrl, pro = set)
    }

    /** O canal [source] (de uma lista da pessoa) a dar no lugar de [shown] (o da lista Pro): o ecrã continua a mostrar este. */
    fun asShown(source: Channel, shown: Channel): Channel =
        source.copy(key = shown.key, num = shown.num, name = shown.name, logo = shown.logo ?: source.logo, categoryId = shown.categoryId)

    private fun groupName(id: String): String = I18n.s.let { s ->
        when (id) {
            "general" -> s.proGeneral
            "news" -> s.proNews
            "entertainment" -> s.proEntertainment
            "movies" -> s.proMovies
            "sports" -> s.proSports
            "kids" -> s.proKids
            "docs" -> s.proDocs
            "music" -> s.proMusic
            "international" -> s.proInternational
            else -> s.proMore
        }
    }
}
