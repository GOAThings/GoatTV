package cc.slokker.itv.data

/**
 * Quem vai ver a lista: o ecrã desta sessão, onde um grupo desbloqueado com o PIN conta como aberto, ou o que fica
 * fora dela e sem PIN (o ecrã inicial do Android TV, o telemóvel), onde vale o bloqueio tal como está nas definições.
 */
enum class Audience {
    SESSION,

    /**
     * Também não olha para o PIN: um grupo marcado continua de fora mesmo sem PIN definido (de propósito, ver
     * PhoneChannelListTest.lockedStaysOutEvenWithoutPinCheck).
     */
    PERSISTENT,
}

/** Os nomes dos grupos especiais, na língua da app. */
class GroupNames(val favorites: String, val recents: String, val all: String, val others: String)

/**
 * Que canais e grupos da lista aberta se veem, e por que ordem: a regra de visibilidade (bloqueado, escondido) e as
 * ordens escolhidas, num só sítio. Não guarda nada: é um retrato de [content] com as definições, os favoritos, os
 * recentes e os grupos desbloqueados nesta sessão ("playlistId:groupId") desse momento.
 */
class ChannelCatalog(
    val content: PlaylistContent?,
    private val prefs: Prefs,
    private val favorites: Map<String, List<String>>,
    private val recents: Map<String, List<String>>,
    private val unlocked: Set<String>,
) {
    private val playlistId: String? = content?.playlist?.id

    fun channelsOf(categoryId: String, audience: Audience = Audience.SESSION): List<Channel> {
        val content = content ?: return emptyList()
        val visible = { ch: Channel -> isVisible(ch, audience) }
        val list = when (categoryId) {
            // Os canais de grupos bloqueados não aparecem em "Todos" nem nos favoritos; os escondidos em lado nenhum.
            SpecialCategory.ALL -> content.channels.filter(visible)
            SpecialCategory.FAVORITES -> favorites[content.playlist.id].orEmpty().mapNotNull { content.byKey[it] }.filter(visible)
            // Pela ordem em que foram vistos (não se ordenam).
            SpecialCategory.RECENT -> return recents[content.playlist.id].orEmpty().mapNotNull { content.byKey[it] }.filter(visible)
            // Um grupo com ordem escolhida fica por essa ordem (mesmo com "Ordenar por nome").
            else -> {
                val ordered = orderedChannels(categoryId, includeHidden = false)
                if (hasChannelOrder(categoryId)) return ordered
                ordered
            }
        }
        return if (prefs.sort == Prefs.Sort.NAME) list.sortedBy { it.name.lowercase() } else list
    }

    /**
     * O canal da lista aberta pode aparecer e abrir-se aqui: nem o grupo bloqueado (e por desbloquear nesta sessão),
     * nem o grupo ou o canal escondidos. A mesma regra em "Todos", favoritos, recentes, pesquisa, números, segundo
     * canal, ecrã inicial e telemóvel (estes dois com [Audience.PERSISTENT]).
     */
    fun isVisible(channel: Channel, audience: Audience = Audience.SESSION): Boolean {
        val playlistId = playlistId ?: return true
        return isVisible(prefs, unlocked, playlistId, channel, audience)
    }

    fun isChannelHidden(channel: Channel): Boolean {
        val playlistId = playlistId ?: return false
        return prefs.isChannelHidden(playlistId, channel.key)
    }

    fun channelOrderKey(groupId: String): String? = playlistId?.let { "$it:$groupId" }

    fun hasChannelOrder(groupId: String): Boolean = channelOrderKey(groupId)?.let { it in prefs.channelOrder } == true

    /** Canais do grupo pela ordem escolhida (os novos ficam no fim, pela ordem da lista). */
    fun orderedChannels(groupId: String, includeHidden: Boolean): List<Channel> {
        val content = content ?: return emptyList()
        val list = content.byCategory[groupId].orEmpty()
        val order = channelOrderKey(groupId)?.let { prefs.channelOrder[it] }.orEmpty()
        val ordered = if (order.isEmpty()) {
            list
        } else {
            val position = order.withIndex().associate { (i, key) -> key to i }
            list.withIndex().sortedBy { (i, ch) -> position[ch.key] ?: (order.size + i) }.map { it.value }
        }
        return if (includeHidden) ordered else ordered.filterNot { isChannelHidden(it) }
    }

    fun isGroupHidden(groupId: String): Boolean {
        val playlistId = playlistId ?: return false
        return prefs.isHidden(playlistId, groupId)
    }

    /** Grupos da lista atual pela ordem escolhida (os novos ficam no fim, pela ordem da lista). */
    fun orderedGroups(includeHidden: Boolean): List<Category> {
        val content = content ?: return emptyList()
        return content.orderedCategories(prefs.groupOrder[content.playlist.id].orEmpty())
            .filter { includeHidden || !isGroupHidden(it.id) }
    }

    /** Grupos que o guia percorre (← →): os especiais visíveis e os normais sem os escondidos nem bloqueados. */
    fun guideGroups(names: GroupNames): List<Category> = listOfNotNull(
        Category(SpecialCategory.FAVORITES, names.favorites).takeIf { prefs.showFavoritesGroup && channelsOf(SpecialCategory.FAVORITES).isNotEmpty() },
        Category(SpecialCategory.RECENT, names.recents).takeIf { prefs.showRecentGroup && channelsOf(SpecialCategory.RECENT).isNotEmpty() },
        Category(SpecialCategory.ALL, names.all),
    ) + orderedGroups(includeHidden = false)
        .filterNot { isGroupLocked(it.id) }
        .map { if (it.id == PlaylistContent.OTHERS) it.copy(name = names.others) else it }

    /** O grupo está marcado como bloqueado, mesmo que já tenha sido desbloqueado com o PIN nesta sessão. */
    fun isGroupLockedSetting(groupId: String): Boolean = isGroupLocked(groupId, Audience.PERSISTENT)

    /** Bloqueado pelo controlo parental e ainda não desbloqueado nesta sessão (ou, para [Audience.PERSISTENT], marcado). */
    fun isGroupLocked(groupId: String, audience: Audience = Audience.SESSION): Boolean {
        val playlistId = playlistId ?: return false
        return isLocked(prefs, unlocked, playlistId, groupId, audience)
    }

    /** Canal com o número [n] (teclas numéricas do comando). Os bloqueados e os escondidos (canal ou grupo) não contam. */
    fun channelByNumber(n: Int): Channel? = content?.channels?.firstOrNull { it.num == n && isVisible(it) }

    fun isFavorite(channel: Channel): Boolean {
        val playlistId = playlistId ?: return false
        return channel.key in favorites[playlistId].orEmpty()
    }

    companion object {
        /** A regra de bloqueio, para quem não tem um catálogo à mão (o JSON do telemóvel). */
        fun isLocked(prefs: Prefs, unlocked: Set<String>, playlistId: String, groupId: String, audience: Audience): Boolean = when (audience) {
            Audience.SESSION -> prefs.isLocked(playlistId, groupId) && "$playlistId:$groupId" !in unlocked
            Audience.PERSISTENT -> "$playlistId:$groupId" in prefs.lockedGroups
        }

        /** A regra de visibilidade (ver o [isVisible] do catálogo). */
        fun isVisible(prefs: Prefs, unlocked: Set<String>, playlistId: String, channel: Channel, audience: Audience): Boolean =
            !isLocked(prefs, unlocked, playlistId, channel.categoryId, audience) &&
                !prefs.isHidden(playlistId, channel.categoryId) && !prefs.isChannelHidden(playlistId, channel.key)
    }
}
