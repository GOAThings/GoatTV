package cc.slokker.itv.data

import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.data.stalker.StalkerRules

enum class PlaylistType {
    XTREAM, M3U, STALKER;

    /** Como no JSON das listas e no núcleo partilhado ("xtream", "m3u", "stalker"). */
    val key: String get() = name.lowercase()
}

data class Playlist(
    val id: String,
    val type: PlaylistType,
    val name: String,
    // Xtream
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    // M3U
    val url: String? = null,
    val epgUrl: String? = null,
    // Stalker (só sem conta): o portal fica em [server] e o MAC em [username]; sem estes, calculam-se a partir do MAC.
    val serial: String? = null,
    val deviceId: String? = null,
    val deviceId2: String? = null,
    val signature: String? = null,
) {
    /**
     * Tudo o que identifica a conta do fornecedor (endereços, credenciais, MAC e ids da box), para tirar dos relatórios.
     * Só o id e o nome ficam de fora: um campo novo entra aqui (o PlaylistKindTest confere).
     */
    fun secrets(): List<String> = listOfNotNull(server, username, password, url, epgUrl, serial, deviceId, deviceId2, signature)
}

/** Dados de uma lista a criar/editar na TV (nesta TV sem conta, ou na conta). */
data class PlaylistForm(
    val type: PlaylistType = PlaylistType.XTREAM,
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
    val serial: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = "",
) {
    /** Muda o tipo: o Stalker começa com o MAC deste aparelho, e ao sair dele esse MAC não fica como utilizador. */
    fun withType(next: PlaylistType, deviceMac: String): PlaylistForm =
        copy(type = next, username = PlaylistRules.usernameFor(next.key, username, deviceMac))

    /** Mensagem do primeiro campo em falta, ou null se estiver pronto a gravar. */
    fun problem(): String? = when (PlaylistRules.problem(type.key, server, username, password, url)) {
        PlaylistProblem.SERVER -> I18n.s.missingServer
        PlaylistProblem.USERNAME -> I18n.s.missingUsername
        PlaylistProblem.PASSWORD -> I18n.s.missingPassword
        PlaylistProblem.URL -> I18n.s.missingUrl
        PlaylistProblem.PORTAL -> I18n.s.missingPortal
        PlaylistProblem.MAC -> I18n.s.invalidMac
        null -> null
    }

    /** A lista a gravar (ver [PlaylistRules.fields]). */
    fun toPlaylist(id: String): Playlist {
        val f = PlaylistRules.fields(type.key, name, server, username, password, url, epgUrl, serial, deviceId, deviceId2, signature)
        return Playlist(id, type, f.name, f.server, f.username, f.password, f.url, f.epgUrl, f.serial, f.deviceId, f.deviceId2, f.signature)
    }

    companion object {
        fun from(p: Playlist) = PlaylistForm(
            type = p.type,
            name = p.name,
            server = p.server.orEmpty(),
            username = p.username.orEmpty(),
            password = p.password.orEmpty(),
            url = p.url.orEmpty(),
            epgUrl = p.epgUrl.orEmpty(),
            serial = p.serial.orEmpty(),
            deviceId = p.deviceId.orEmpty(),
            deviceId2 = p.deviceId2.orEmpty(),
            signature = p.signature.orEmpty(),
        )

        /** Um MAC novo com o prefixo das MAG, que os portais esperam: as apps não têm acesso ao MAC verdadeiro. */
        fun generateMac(): String = StalkerRules.generateMac { java.security.SecureRandom().nextInt(it) }

        /** O MAC como `AA:BB:CC:DD:EE:FF` (aceita traços ou sem separadores), ou null se não for um MAC. */
        fun normalizeMac(mac: String): String? = StalkerRules.normalizeMac(mac)
    }
}


data class XtreamAccount(
    val maxConnections: Int?,
    val status: String?,
    /** `allowed_output_formats` (ex.: só "ts"); vazio = não indicado. */
    val allowedFormats: Set<String> = emptySet(),
    /** Fuso horário do servidor (ex.: "Europe/Lisbon"): os pedidos de catch-up usam a hora dele. */
    val timezone: String? = null,
)

class PlaylistContent(
    val playlist: Playlist,
    categories: List<Category>,
    val channels: List<Channel>,
    val account: XtreamAccount? = null,
    /** Guia XMLTV: o da conta, ou o do cabeçalho da lista M3U (`x-tvg-url`). */
    val guideUrl: String? = null,
    /** Quando os canais foram descarregados (para o intervalo de atualização). */
    val fetchedAt: Long = System.currentTimeMillis(),
    /** Com "Juntar variantes" (ver ContentView): as variantes que não se mostram, só abertas como failover. */
    val hidden: List<Channel> = emptyList(),
    /** A melhor variante de cada canal → as outras (chaves em [hidden]), da melhor para a pior. */
    val variants: Map<String, List<String>> = emptyMap(),
) {
    /** Sem ids repetidos nem os dos grupos especiais: as listas do ecrã usam o id como chave única. Vale também para as cópias em cache. */
    val categories: List<Category> = categories.filter { it.id !in SpecialCategory.RESERVED }.distinctBy { it.id }
    val byCategory: Map<String, List<Channel>> = channels.groupBy { it.categoryId }
    /** Também as variantes escondidas ([hidden]): um favorito, o último canal ou um failover que seja uma delas abre-a. */
    val byKey: Map<String, Channel> = (channels + hidden).reversed().associateBy { it.key }
    private val categoryNames = categories.associate { it.id to it.name }

    /** Os grupos pela ordem escolhida ([order]: ids); os novos ficam no fim, pela ordem da lista. */
    fun orderedCategories(order: List<String>): List<Category> {
        val position = order.withIndex().associate { (i, id) -> id to i }
        // O sortedBy é estável: os que têm a mesma posição ficam pela ordem da lista.
        return categories.sortedBy { position[it.id] ?: order.size }
    }

    fun categoryName(id: String): String = when (id) {
        SpecialCategory.FAVORITES -> I18n.s.favorites
        SpecialCategory.ALL -> I18n.s.all
        SpecialCategory.RECENT -> I18n.s.recents
        OTHERS -> I18n.s.others
        else -> categoryNames[id] ?: id
    }

    companion object {
        const val OTHERS = OTHERS_GROUP

        /** Agrupa os canais pelas categorias conhecidas; o que sobra vai para "Outros". */
        fun build(
            playlist: Playlist,
            categories: List<Category>,
            channels: List<Channel>,
            account: XtreamAccount? = null,
            guideUrl: String? = playlist.epgUrl,
        ): PlaylistContent {
            val known = categories.map { it.id }.toSet()
            val fixed = channels.map { if (it.categoryId in known) it else it.copy(categoryId = OTHERS) }
            val used = fixed.map { it.categoryId }.toSet()
            val cats = categories.filter { it.id in used }.toMutableList()
            if (OTHERS in used && cats.none { it.id == OTHERS }) cats += Category(OTHERS, OTHERS)
            return PlaylistContent(playlist, cats, fixed, account, guideUrl?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

object SpecialCategory {
    const val FAVORITES = "__fav__"
    const val ALL = "__all__"
    const val RECENT = "__recent__"
    val RESERVED = setOf(FAVORITES, ALL, RECENT)
}

/** Lembrete de um programa que ainda vai começar (fica só nesta TV). */
data class Reminder(
    val playlistId: String,
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
)

/** Um programa que já passou (ou o atual desde o início), a ver em catch-up a partir de [positionMs]. */
data class Catchup(
    val channel: Channel,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    /** Onde o vídeo começa, em ms desde [startMs] (avançar/recuar = pedir de novo daí). */
    val positionMs: Long = 0L,
    /** Uma gravação no disco (`file://…`), em vez do arquivo do servidor: não usa a ligação ao fornecedor. */
    val recording: String? = null,
) {
    val durationMs: Long get() = endMs - startMs

    /**
     * Onde vai o vídeo, em ms desde [startMs]: o servidor abriu-o em [archiveStartMs] (ao minuto, ver
     * catchupCandidates) e o player já vai em [playerMs] desde aí.
     */
    fun positionAt(archiveStartMs: Long, playerMs: Long): Long = archiveStartMs - startMs + playerMs

    companion object {
        /** O servidor precisa de uns minutos para ter um programa em arquivo. */
        const val MIN_AGE_MS = CatchupRules.MIN_AGE_MS
    }
}
