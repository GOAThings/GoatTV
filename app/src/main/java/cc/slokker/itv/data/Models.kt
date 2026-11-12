package cc.slokker.itv.data

import cc.slokker.itv.ui.i18n.I18n
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PlaylistType { XTREAM, M3U, STALKER }

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
    fun withType(next: PlaylistType, deviceMac: String): PlaylistForm = copy(
        type = next,
        username = when {
            next == PlaylistType.STALKER && username.isBlank() -> deviceMac
            next != PlaylistType.STALKER && username == deviceMac -> ""
            else -> username
        },
    )

    /** Mensagem do primeiro campo em falta, ou null se estiver pronto a gravar. */
    fun problem(): String? = when {
        type == PlaylistType.XTREAM && server.isBlank() -> I18n.s.missingServer
        type == PlaylistType.XTREAM && username.isBlank() -> I18n.s.missingUsername
        type == PlaylistType.XTREAM && password.isBlank() -> I18n.s.missingPassword
        type == PlaylistType.M3U && url.isBlank() -> I18n.s.missingUrl
        type == PlaylistType.STALKER && server.isBlank() -> I18n.s.missingPortal
        type == PlaylistType.STALKER && normalizeMac(username) == null -> I18n.s.invalidMac
        else -> null
    }

    /** A lista a gravar. Sem nome fica o do servidor; sem "http://" no endereço, junta-se. */
    fun toPlaylist(id: String): Playlist {
        val stalker = type == PlaylistType.STALKER
        val xtream = type == PlaylistType.XTREAM || stalker
        val server = withScheme(server).trimEnd('/')
        val url = withScheme(url)
        val fallbackName = (if (xtream) server else url).toHttpUrlOrNull()?.host ?: if (stalker) "Stalker" else if (xtream) "Xtream" else "M3U"
        return Playlist(
            id = id,
            type = type,
            name = name.trim().ifEmpty { fallbackName },
            server = server.takeIf { xtream },
            username = if (stalker) normalizeMac(username) else username.trim().takeIf { xtream },
            password = password.takeIf { type == PlaylistType.XTREAM },
            url = url.takeIf { !xtream },
            epgUrl = withScheme(epgUrl).takeIf { it.isNotEmpty() },
            serial = serial.trim().takeIf { stalker && it.isNotEmpty() },
            deviceId = deviceId.trim().takeIf { stalker && it.isNotEmpty() },
            deviceId2 = deviceId2.trim().takeIf { stalker && it.isNotEmpty() },
            signature = signature.trim().takeIf { stalker && it.isNotEmpty() },
        )
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
        fun generateMac(): String =
            "00:1A:79:" + List(3) { "%02X".format(java.security.SecureRandom().nextInt(256)) }.joinToString(":")

        /** O MAC como `AA:BB:CC:DD:EE:FF` (aceita traços ou sem separadores), ou null se não for um MAC. */
        fun normalizeMac(mac: String): String? {
            val hex = mac.trim().replace(":", "").replace("-", "").uppercase()
            if (hex.length != 12 || !hex.all { it in "0123456789ABCDEF" }) return null
            return hex.chunked(2).joinToString(":")
        }

        private fun withScheme(address: String): String {
            val a = address.trim()
            return if (a.isEmpty() || a.contains("://")) a else "http://$a"
        }
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

data class EpgEntry(val title: String, val startMs: Long, val endMs: Long) {
    fun progress(now: Long): Float =
        if (endMs <= startMs) 0f else ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
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
