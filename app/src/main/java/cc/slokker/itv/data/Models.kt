package cc.slokker.itv.data

import cc.slokker.itv.ui.i18n.I18n
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PlaylistType { XTREAM, M3U }

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
)

/** Dados de uma lista a criar/editar na TV (só sem conta: com conta, as listas gerem-se no site). */
data class PlaylistForm(
    val type: PlaylistType = PlaylistType.XTREAM,
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
) {
    /** Mensagem do primeiro campo em falta, ou null se estiver pronto a gravar. */
    fun problem(): String? = when {
        type == PlaylistType.XTREAM && server.isBlank() -> I18n.s.missingServer
        type == PlaylistType.XTREAM && username.isBlank() -> I18n.s.missingUsername
        type == PlaylistType.XTREAM && password.isBlank() -> I18n.s.missingPassword
        type == PlaylistType.M3U && url.isBlank() -> I18n.s.missingUrl
        else -> null
    }

    /** A lista a gravar. Sem nome fica o do servidor; sem "http://" no endereço, junta-se. */
    fun toPlaylist(id: String): Playlist {
        val xtream = type == PlaylistType.XTREAM
        val server = withScheme(server).trimEnd('/')
        val url = withScheme(url)
        val fallbackName = (if (xtream) server else url).toHttpUrlOrNull()?.host ?: if (xtream) "Xtream" else "M3U"
        return Playlist(
            id = id,
            type = type,
            name = name.trim().ifEmpty { fallbackName },
            server = server.takeIf { xtream },
            username = username.trim().takeIf { xtream },
            password = password.takeIf { xtream },
            url = url.takeIf { !xtream },
            epgUrl = withScheme(epgUrl).takeIf { it.isNotEmpty() },
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
        )

        private fun withScheme(address: String): String {
            val a = address.trim()
            return if (a.isEmpty() || a.contains("://")) a else "http://$a"
        }
    }
}

data class Category(val id: String, val name: String)

/**
 * Um canal dentro de uma lista.
 *
 * [uid] é a posição na lista (única, usada como chave nas listas da UI).
 * [key] é o `channelKey` sincronizado com a conta (favoritos, último canal).
 */
data class Channel(
    val uid: Int,
    val key: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val streamId: String? = null,
    val url: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Id do canal no guia XMLTV (`tvg-id` / `epg_channel_id`). */
    val epgId: String? = null,
    /** Dias de catch-up que o servidor guarda deste canal (0 = sem catch-up). */
    val archiveDays: Int = 0,
)

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
    val categories: List<Category>,
    val channels: List<Channel>,
    val account: XtreamAccount? = null,
    /** Guia XMLTV: o da conta, ou o do cabeçalho da lista M3U (`x-tvg-url`). */
    val guideUrl: String? = null,
    /** Quando os canais foram descarregados (para o intervalo de atualização). */
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val byCategory: Map<String, List<Channel>> = channels.groupBy { it.categoryId }
    val byKey: Map<String, Channel> = channels.reversed().associateBy { it.key }
    private val categoryNames = categories.associate { it.id to it.name }

    fun categoryName(id: String): String = when (id) {
        SpecialCategory.FAVORITES -> I18n.s.favorites
        SpecialCategory.ALL -> I18n.s.all
        SpecialCategory.RECENT -> I18n.s.recents
        OTHERS -> I18n.s.others
        else -> categoryNames[id] ?: id
    }

    companion object {
        const val OTHERS = "Outros"

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
) {
    val durationMs: Long get() = endMs - startMs
}

object StreamFormat {
    const val TS = "ts"
    const val HLS = "m3u8"
}
