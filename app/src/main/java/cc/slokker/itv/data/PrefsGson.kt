package cc.slokker.itv.data

import cc.slokker.itv.ui.i18n.Strings
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// As definições vivem num JsonObject do Gson no Android (ProfileStore, a cache local); as regras são as do núcleo
// partilhado (Prefs), que lê e escreve o JSON da kotlinx. Estas pontes passam de um para o outro pelo texto.

private fun JsonObject.shared() = Json.parseToJsonElement(toString()).jsonObject

/** As definições em [s], com as línguas da app Android. */
fun Prefs.Companion.from(s: JsonObject): Prefs = from(s.shared(), Prefs.Engine.ALL, Strings::normalize)

/** Escreve [value] na chave [key] de [settings] (ver Prefs.put), ou tira-a com null. */
fun Prefs.Companion.put(settings: JsonObject, key: String, value: Any?) {
    if (value == null) settings.remove(key) else settings.add(key, JsonParser.parseString(json(value).toString()))
}

/** Tira de [settings] o que é só da lista [playlistId] (ver Prefs.removePlaylist). Devolve true se mudou alguma coisa. */
fun Prefs.Companion.removePlaylist(settings: JsonObject, playlistId: String): Boolean {
    val changed = removePlaylist(settings.shared(), playlistId) ?: return false
    val fresh = JsonParser.parseString(changed.toString()).asJsonObject
    settings.keySet().toList().forEach(settings::remove)
    fresh.entrySet().forEach { (k, v) -> settings.add(k, v) }
    return true
}
