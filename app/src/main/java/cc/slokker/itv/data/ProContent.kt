package cc.slokker.itv.data

import cc.slokker.itv.playback.SourceHistory
import cc.slokker.itv.playback.SourceReliability
import cc.slokker.itv.playback.SourceStats
import cc.slokker.itv.ui.i18n.I18n

/**
 * Modo TV Pro: as listas de onde vêm os canais ([members], como se mostram) e as fontes de cada canal da lista única,
 * da melhor para a pior (ver ProLineup). A primeira que existe é a que dá; as outras são os failovers.
 */
class ProSet(
    val members: Map<String, PlaylistContent>,
    private val sources: Map<String, List<ProLineup.Ref>>,
    private val store: ProHealthStore? = null,
    private val history: SourceHistoryStore? = null,
) {
    /** Sem [store] (testes): as que falharam só contam enquanto este ProSet existir. */
    private val health = store?.health ?: ProHealth()

    // Com o nome do canal: a versão dele ("ᴴᴰ") também conta (ver ProHealth.variantOf).
    private fun down(playlistId: String, channel: Channel) = health.isDown(playlistId, channel.key, System.currentTimeMillis(), channel.name)

    /** Uma fonte do canal: a referência da tabela (a qualidade), a lista e o canal dela. */
    private class Found(val ref: ProLineup.Ref, val member: PlaylistContent, val channel: Channel)

    /**
     * As fontes do canal [key] que ainda existem, da melhor para a pior. As que falharam há pouco (ProHealth) vão para o
     * fim, e em cada grupo as claramente más segundo o historial (SourceHistory) também; entre fontes da mesma
     * qualidade, a mais fiável primeiro. Sem historial, é a ordem da tabela.
     */
    private fun ordered(key: String): List<Found> {
        val found = sources[key].orEmpty().mapNotNull { r ->
            members[r.playlistId]?.let { m -> m.byKey[r.key]?.let { Found(r, m, it) } }
        }
        val now = System.currentTimeMillis()
        fun sort(list: List<Found>) = SourceReliability.order(list, history?.history, now, { it.ref.quality }, { SourceHistory.key(it.ref.playlistId, it.ref.key) })
        val (down, up) = found.partition { down(it.member.playlist.id, it.channel) }
        return sort(up) + sort(down)
    }

    /** As fontes do canal [key] que ainda existem, como (lista, canal dela), da melhor para a pior (as que falharam no fim). */
    fun resolve(key: String): List<Pair<PlaylistContent, Channel>> = ordered(key).map { it.member to it.channel }

    /** O que se sabe da fiabilidade da fonte [channel] de [member] esta semana (ver SourceHistory); null sem nada. */
    fun stats(member: PlaylistContent, channel: Channel): SourceStats? = history?.history?.stats(SourceHistory.key(member.playlist.id, channel.key), System.currentTimeMillis())

    /** A fonte [channel] de [member] falhou (cai sempre, ou não abre): durante uns dias as outras vêm antes (ProHealth). */
    fun demote(member: PlaylistContent, channel: Channel) {
        health.markDown(member.playlist.id, channel.key, System.currentTimeMillis(), channel.name)
        store?.save()
    }

    /** A pessoa escolheu a fonte [channel] de [member] no painel: volta ao lugar dela. */
    fun promote(member: PlaylistContent, channel: Channel) {
        if (health.markUp(member.playlist.id, channel.key, channel.name)) store?.save()
    }

    /** A fonte [channel] de [member] falhou há pouco. */
    fun isDown(member: PlaylistContent, channel: Channel): Boolean = down(member.playlist.id, channel)

    /** A qualidade (pelo nome, ChannelMatch.Quality) da fonte que vai dar o canal [key] (a primeira de [resolve]), ou null. */
    fun quality(key: String): Int? = ordered(key).firstOrNull()?.ref?.quality

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
    fun build(lineupId: String, members: List<PlaylistContent>, store: ProHealthStore? = null, history: SourceHistoryStore? = null): PlaylistContent {
        val lineup = ProLineup.lineup(lineupId)
        val country = lineup?.country ?: lineupId
        val sources = members.map { m ->
            ProLineup.Source(m.playlist.id, (m.channels + m.hidden).map { ProLineup.Input(it.key, it.name, m.categoryName(it.categoryId)) })
        }
        val result = ProLineup.build(lineup, country, sources)
        // Os nomes que faltam à tabela (só nomes de canais, sem links), para os acrescentar.
        ProLineup.nearMisses(lineup, result).takeIf { it.isNotEmpty() }?.let { android.util.Log.i("GoatTV", "Pro: em Mais canais, parecidos com a tabela: " + it.take(60).joinToString(" | ")) }
        val guide = members.firstOrNull { it.guideUrl != null }
        val set = ProSet(members.associateBy { it.playlist.id }, result.channels.associate { it.id to it.sources }, store, history)
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
