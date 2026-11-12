package cc.slokker.itv.data

/**
 * Os canais que o GoatRemote vê (GET /channels da porta dos telemóveis). O estado é tirado na thread principal
 * (ver `AppViewModel.phoneChannelList`, que os tira com o ChannelCatalog) e o JSON é montado fora dela, pelo
 * [PhoneList] partilhado com a Apple.
 */
internal class PhoneChannelList(
    val playlist: Playlist,
    /** Os grupos pela ordem do ecrã, cada um com os seus canais pela ordem do ecrã. */
    val groups: List<Pair<Category, List<Channel>>>,
    val prefs: Prefs,
    /** Os favoritos desta lista (channelKeys). */
    val favorites: Set<String>,
    /** O canal a ver (channelKey), ou null. */
    val current: String?,
    /** Os logótipos que substituem os da lista (channelKey → URL), como no ecrã. */
    val logos: Map<String, String>,
    /** O canal de cada channelKey (o primeiro da lista com essa chave): é esse que o /play e o /logo usam. */
    val byKey: Map<String, Channel>,
) {
    /** O JSON do /channels, com no máximo [maxBytes]: se não couber tudo, os canais cortam-se e vai `"truncated": true`. */
    fun json(maxBytes: Int = MAX_BYTES): ByteArray {
        val firstUid = HashMap<String, Int>()
        for ((_, channels) in groups) for (ch in channels) byKey[ch.key]?.let { firstUid[ch.key] = it.uid }
        return PhoneList.json(
            playlist.id, playlist.name, groups.map { PhoneGroup(it.first, it.second) }, PhoneRules(prefs), favorites,
            current?.let { byKey[it] }, logos, firstUid, maxBytes,
        ).encodeToByteArray()
    }

    companion object {
        const val MAX_BYTES = PhoneList.MAX_BYTES

        /** O JSON do /now (ver [PhoneList.nowJson]). */
        fun nowJson(current: String?, now: EpgEntry?, next: EpgEntry?): ByteArray = PhoneList.nowJson(current, now, next).encodeToByteArray()

        /** O telemóvel pode ver e abrir [channel]: nem escondido (ele ou o grupo) nem bloqueado (ver ChannelCatalog). */
        fun allowed(prefs: Prefs, playlistId: String, channel: Channel): Boolean = PhoneRules(prefs).allowed(playlistId, channel)

        fun logoUrl(channel: Channel, logos: Map<String, String>): String? = PhoneList.logoUrl(channel, logos)

        fun decodeKey(encoded: String): String? = PhoneList.decodeKey(encoded)
    }
}
