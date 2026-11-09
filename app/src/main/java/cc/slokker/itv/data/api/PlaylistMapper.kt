package cc.slokker.itv.data.api

import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistKind
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.kind
import com.google.gson.JsonObject

// Uma lista da conta (ou do LocalStore, que usa o mesmo formato) ↔ Playlist. É o único sítio que sabe os campos
// do JSON: um campo novo em Playlist junta-se aqui (e em PlaylistDto), não no ViewModel.

fun PlaylistDto.toPlaylist(): Playlist? {
    val id = id ?: return null
    val kind = PlaylistKind.fromWire(type) ?: return null
    return Playlist(
        id = id,
        type = kind.type,
        name = name?.takeIf { it.isNotBlank() } ?: kind.label,
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
    type = type.kind.wireName,
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

/**
 * O corpo de uma lista para a conta (os campos do outro tipo vão vazios, como no site). Só para os tipos que a conta
 * guarda: um Stalker ia como M3U e perdia o portal e o MAC.
 */
fun Playlist.accountBody(): JsonObject = JsonObject().apply {
    require(type.kind.supportsAccount) { "${type.kind.wireName} não vai para a conta" }
    val xtream = type == PlaylistType.XTREAM
    addProperty("type", type.kind.wireName)
    addProperty("name", name)
    addProperty("server", if (xtream) server.orEmpty() else "")
    addProperty("username", if (xtream) username.orEmpty() else "")
    addProperty("password", if (xtream) password.orEmpty() else "")
    addProperty("url", if (xtream) "" else url.orEmpty())
    addProperty("epg_url", epgUrl.orEmpty())
}
