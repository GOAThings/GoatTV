package cc.slokker.itv.data

import cc.slokker.itv.data.stalker.StalkerRules

/** O primeiro campo em falta num formulário de lista. */
enum class PlaylistProblem { SERVER, USERNAME, PASSWORD, URL, PORTAL, MAC }

/** Os campos de uma lista a gravar, já limpos (null = o tipo não usa o campo, ou ficou vazio). */
class PlaylistFields(
    val name: String,
    val server: String?,
    val username: String?,
    val password: String?,
    val url: String?,
    val epgUrl: String?,
    val serial: String?,
    val deviceId: String?,
    val deviceId2: String?,
    val signature: String?,
)

/**
 * As regras do formulário de uma lista (criar ou editar na TV), as mesmas no Android e na Apple. O tipo vai em texto:
 * "xtream", "m3u" ou "stalker" (como no JSON das listas).
 */
object PlaylistRules {
    /** O utilizador ao mudar para o tipo [next]: o Stalker começa com o MAC deste aparelho, e ao sair dele esse MAC não fica. */
    fun usernameFor(next: String, username: String, deviceMac: String): String = when {
        next == STALKER && username.isBlank() -> deviceMac
        next != STALKER && username == deviceMac -> ""
        else -> username
    }

    /** O primeiro campo em falta, ou null se estiver pronta a gravar. */
    fun problem(type: String, server: String, username: String, password: String, url: String): PlaylistProblem? = when {
        type == XTREAM && server.isBlank() -> PlaylistProblem.SERVER
        type == XTREAM && username.isBlank() -> PlaylistProblem.USERNAME
        type == XTREAM && password.isEmpty() -> PlaylistProblem.PASSWORD
        type == M3U && url.isBlank() -> PlaylistProblem.URL
        type == STALKER && server.isBlank() -> PlaylistProblem.PORTAL
        type == STALKER && StalkerRules.normalizeMac(username) == null -> PlaylistProblem.MAC
        else -> null
    }

    /** A lista a gravar. Sem nome fica o do servidor; sem "http://" no endereço, junta-se. */
    fun fields(
        type: String, name: String, server: String, username: String, password: String, url: String, epgUrl: String,
        serial: String, deviceId: String, deviceId2: String, signature: String,
    ): PlaylistFields {
        val stalker = type == STALKER
        val xtream = type == XTREAM || stalker
        val cleanServer = withScheme(server).trimEnd('/')
        val cleanUrl = withScheme(url)
        val fallback = host(if (xtream) cleanServer else cleanUrl) ?: if (stalker) "Stalker" else if (xtream) "Xtream" else "M3U"
        fun extra(s: String) = s.trim().takeIf { stalker && it.isNotEmpty() }
        return PlaylistFields(
            name = name.trim().ifEmpty { fallback },
            server = cleanServer.takeIf { xtream },
            username = if (stalker) StalkerRules.normalizeMac(username) else username.trim().takeIf { xtream },
            password = password.takeIf { type == XTREAM },
            url = cleanUrl.takeIf { !xtream },
            epgUrl = withScheme(epgUrl).takeIf { it.isNotEmpty() },
            serial = extra(serial),
            deviceId = extra(deviceId),
            deviceId2 = extra(deviceId2),
            signature = extra(signature),
        )
    }

    fun withScheme(address: String): String {
        val a = address.trim()
        return if (a.isEmpty() || "://" in a) a else "http://$a"
    }

    /** O servidor de um endereço http(s), ou null. */
    fun host(url: String): String? {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.takeIf { it.isNotEmpty() && it != "[]" && it.none { c -> c.isWhitespace() } }?.lowercase()
    }

    private const val XTREAM = "xtream"
    private const val M3U = "m3u"
    private const val STALKER = "stalker"
}

