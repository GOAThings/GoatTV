package cc.slokker.itv.data.api

import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistType
import com.google.gson.JsonObject

// Uma lista da conta (ou do LocalStore, que usa o mesmo formato) ↔ Playlist. É o único sítio que sabe os campos
// do JSON: um campo novo em Playlist junta-se aqui (e em PlaylistDto), não no ViewModel.

fun PlaylistDto.toPlaylist(): Playlist? {
    val id = id ?: return null
    val type = when (type?.lowercase()) {
        "xtream" -> PlaylistType.XTREAM
        "m3u" -> PlaylistType.M3U
        "stalker" -> PlaylistType.STALKER
        else -> return null
    }
    return Playlist(
        id = id,
        type = type,
        name = name?.takeIf { it.isNotBlank() } ?: when (type) {
            PlaylistType.XTREAM -> "Xtream"
            PlaylistType.M3U -> "M3U"
            PlaylistType.STALKER -> "Stalker"
        },
        // A conta manda sempre os 8 campos ("" nos que não são do tipo): vazio = em falta, para a lista
        // não parecer diferente da guardada em cache (e não recarregar sem razão).
        server = server?.takeIf { it.isNotBlank() },
        username = username?.takeIf { it.isNotBlank() },
        password = password?.takeIf { it.isNotEmpty() },
        url = url?.takeIf { it.isNotBlank() },
        epgUrl = epgUrl?.takeIf { it.isNotBlank() },
        serial = serial,
        deviceId = deviceId,
        deviceId2 = deviceId2,
        signature = signature,
    )
}

fun Playlist.toDto() = PlaylistDto(
    id = id,
    type = type.name.lowercase(),
    name = name,
    server = server,
    username = username,
    password = password,
    url = url,
    epgUrl = epgUrl,
    serial = serial,
    deviceId = deviceId,
    deviceId2 = deviceId2,
    signature = signature,
)

/** O corpo de uma lista para a conta (os campos do outro tipo vão vazios, como no site). */
fun Playlist.accountBody() = JsonObject().apply {
    val xtream = type == PlaylistType.XTREAM
    addProperty("type", if (xtream) "xtream" else "m3u")
    addProperty("name", name)
    addProperty("server", if (xtream) server.orEmpty() else "")
    addProperty("username", if (xtream) username.orEmpty() else "")
    addProperty("password", if (xtream) password.orEmpty() else "")
    addProperty("url", if (xtream) "" else url.orEmpty())
    addProperty("epg_url", epgUrl.orEmpty())
}
