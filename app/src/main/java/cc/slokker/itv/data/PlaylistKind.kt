package cc.slokker.itv.data

/**
 * O que cada tipo de lista sabe e pode fazer. Um tipo novo junta-se aqui (e à fonte em AppContainer), em vez de
 * se procurar cada `when (type)` pela app fora: o resto pergunta à [PlaylistType.kind] pela capacidade.
 */
class PlaylistKind private constructor(
    val type: PlaylistType,
    /** O tipo no JSON da conta e do LocalStore. Não muda: o site e a Apple TV leem-no. */
    val wireName: String,
    /** O tipo nos ecrãs, e o nome de uma lista sem nome. */
    val label: String,
    /** Pode ficar na conta GOAT (o site não guarda portais Stalker: expõe-nos como M3U). */
    val supportsAccount: Boolean,
    /** O servidor guarda os programas para ver depois (Xtream com `tv_archive`). */
    val supportsCatchup: Boolean,
    /** Há EPG curto pedido canal a canal (para os canais sem guia XMLTV). */
    val shortEpg: Boolean,
    /** O link do canal pede-se ao abrir (caduca), em vez de vir na lista. */
    val linkAtPlayTime: Boolean,
    /** Uma ligação de cada vez, sempre (um MAC é uma box): nunca dá segundo canal. */
    val singleConnection: Boolean,
    /** O servidor diz quantas ligações o plano tem e quantas estão em uso. */
    val knowsConnectionLimit: Boolean,
    /** O formato que funcionou (ts/m3u8) fica na conta, para os canais seguintes começarem por ele. */
    val remembersFormat: Boolean,
    private val sameSourceRule: (Playlist, Playlist) -> Boolean,
) {
    /** A mesma lista do fornecedor (para passar favoritos de uma lista desta TV para a da conta). */
    fun sameSource(a: Playlist, b: Playlist): Boolean = a.type == type && b.type == type && sameSourceRule(a, b)

    companion object {
        val XTREAM = PlaylistKind(
            PlaylistType.XTREAM, "xtream", "Xtream",
            supportsAccount = true, supportsCatchup = true, shortEpg = true, linkAtPlayTime = false,
            singleConnection = false, knowsConnectionLimit = true, remembersFormat = true,
        ) { a, b ->
            a.server?.trim()?.trimEnd('/')?.lowercase() == b.server?.trim()?.trimEnd('/')?.lowercase() &&
                a.username?.trim() == b.username?.trim()
        }

        val M3U = PlaylistKind(
            PlaylistType.M3U, "m3u", "M3U",
            supportsAccount = true, supportsCatchup = false, shortEpg = false, linkAtPlayTime = false,
            singleConnection = false, knowsConnectionLimit = false, remembersFormat = false,
        ) { a, b -> a.url?.trim() == b.url?.trim() }

        val STALKER = PlaylistKind(
            PlaylistType.STALKER, "stalker", "Stalker",
            supportsAccount = false, supportsCatchup = false, shortEpg = true, linkAtPlayTime = true,
            singleConnection = true, knowsConnectionLimit = false, remembersFormat = false,
        ) { a, b -> a.server?.trim()?.lowercase() == b.server?.trim()?.lowercase() && a.username == b.username }

        val ALL = listOf(XTREAM, M3U, STALKER)

        /** O tipo de [wireName] (sem distinguir maiúsculas), ou null se esta versão não o conhece. */
        fun fromWire(wireName: String?): PlaylistKind? = ALL.firstOrNull { it.wireName == wireName?.lowercase() }
    }
}

/** A mesma lista do fornecedor que [other] (mesmo tipo e mesma origem, ver [PlaylistKind.sameSource]). */
fun Playlist.sameSourceAs(other: Playlist): Boolean = type == other.type && type.kind.sameSource(this, other)

/**
 * As duas listas gastam as ligações da mesma conta do fornecedor: a mesma lista, ou a mesma origem num tipo que conta
 * as ligações. Com a mesma regra de [sameSourceAs] (antes, "http://x/" e "http://x" contavam como contas diferentes).
 */
fun Playlist.sharesConnectionsWith(other: Playlist): Boolean =
    id == other.id || (type.kind.knowsConnectionLimit && sameSourceAs(other))

/** Um `when` exaustivo: um tipo novo não compila sem o seu [PlaylistKind]. */
val PlaylistType.kind: PlaylistKind
    get() = when (this) {
        PlaylistType.XTREAM -> PlaylistKind.XTREAM
        PlaylistType.M3U -> PlaylistKind.M3U
        PlaylistType.STALKER -> PlaylistKind.STALKER
    }
