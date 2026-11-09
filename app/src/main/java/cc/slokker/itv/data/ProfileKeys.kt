package cc.slokker.itv.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * As chaves do `settings` da conta que não são preferências ([Prefs.Key]): o estado que a app guarda lá. Com as de
 * [Prefs.Key] são todas as chaves que esta app escreve, e o site e a Apple TV leem as mesmas: não mudam de nome.
 * Só o ProfileStore as lê e escreve.
 */
object ProfileKeys {
    /** A lista e o canal onde se saiu (para abrir aí, nesta ou noutra TV). */
    const val LAST_PLAYLIST = "last_playlist"
    const val LAST_CHANNEL = "last_channel"

    /** Quando o último canal mudou (ms): entre a conta e a cópia desta TV, ganha o mais recente. */
    const val LAST_CHANNEL_AT = "last_channel_at"

    /** O formato que funcionou nas listas Xtream ([StreamFormat.TS]/[StreamFormat.HLS]; sem a chave = Auto). */
    const val STREAM_FORMAT = "stream_format"

    /** Últimos canais vistos: playlistId → channelKeys, o mais recente primeiro. */
    const val RECENT_CHANNELS = "recent_channels"

    /** O texto em [key], ou null se faltar, estiver vazio ou não for texto. */
    fun JsonObject.string(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString.takeIf { it.isNotEmpty() } else null
    }

    /** Um mapa playlistId → channelKeys (favoritos, recentes), sem repetidos; o que não tiver essa forma fica de fora. */
    fun parseKeyLists(json: JsonElement?): Map<String, List<String>> {
        if (json == null || !json.isJsonObject) return emptyMap()
        return json.asJsonObject.entrySet().associate { (playlistId, value) ->
            playlistId to if (value.isJsonArray) {
                value.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.distinct()
            } else {
                emptyList()
            }
        }
    }
}
