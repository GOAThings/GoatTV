package cc.slokker.itv.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Os canais que o GoatRemote vê (GET /channels da porta dos telemóveis). O estado é tirado na thread principal
 * (ver `AppViewModel.phoneChannelList`) e o JSON é montado fora dela.
 *
 * Nunca aparecem grupos ou canais escondidos, nem grupos bloqueados pelo controlo parental tal como está nas
 * definições, mesmo que tenham sido desbloqueados com o PIN nesta sessão: o telemóvel não pede PIN.
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
        val id = playlist.id
        val seen = HashSet<String>()
        val shown = groups
            .filter { (group, _) -> !prefs.isHidden(id, group.id) && !isLockedSetting(prefs, id, group.id) }
            .map { (group, channels) ->
                // Cada canal uma só vez, no seu grupo, e só o que o /play abriria com essa chave.
                group to channels.filter { ch ->
                    ch.categoryId == group.id && allowed(prefs, id, ch) && byKey[ch.key]?.uid == ch.uid && seen.add(ch.key)
                }
            }
            .filter { it.second.isNotEmpty() }

        val head = JsonObject().apply {
            add("playlist", JsonObject().apply { addProperty("id", id); addProperty("name", playlist.name) })
            addProperty("current", current?.let { byKey[it] }?.takeIf { allowed(prefs, id, it) }?.key)
            add("groups", JsonArray().apply {
                shown.forEach { (group, _) -> add(JsonObject().apply { addProperty("id", group.id); addProperty("name", group.name) }) }
            })
        }
        // O objeto fica aberto para os canais entrarem um a um, a contar os bytes.
        val start = GSON.toJson(head).dropLast(1) + ",\"channels\":["
        val out = ByteArrayOutputStream()
        out.write(start.toByteArray())
        val budget = maxBytes - out.size() - TRUNCATED_END.length
        var used = 0
        var count = 0
        var truncated = false
        loop@ for ((group, channels) in shown) {
            for (ch in channels) {
                val item = JsonObject().apply {
                    addProperty("key", ch.key)
                    addProperty("num", ch.num)
                    addProperty("name", ch.name)
                    addProperty("group", group.id)
                    addProperty("logo", logoUrl(ch, logos) != null)
                    addProperty("favorite", ch.key in favorites)
                }
                val bytes = GSON.toJson(item).toByteArray()
                val need = bytes.size + if (count > 0) 1 else 0
                if (used + need > budget) {
                    truncated = true
                    break@loop
                }
                if (count > 0) out.write(','.code)
                out.write(bytes)
                used += need
                count++
            }
        }
        out.write((if (truncated) TRUNCATED_END else END).toByteArray())
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024

        /**
         * O JSON do /now: o canal a ver e, com guia, o programa e o seguinte. É pequeno, para o telemóvel perguntar a
         * cada poucos segundos sem pedir a lista inteira.
         */
        fun nowJson(current: String?, now: EpgEntry?, next: EpgEntry?): ByteArray {
            fun programme(e: EpgEntry?) = e?.let {
                JsonObject().apply {
                    addProperty("title", it.title)
                    addProperty("start", it.startMs)
                    addProperty("end", it.endMs)
                }
            }
            val json = JsonObject().apply {
                addProperty("current", current)
                add("now", programme(now))
                add("next", programme(next))
            }
            return GSON.toJson(json).toByteArray()
        }
        private const val END = "]}"
        private const val TRUNCATED_END = "],\"truncated\":true}"

        // Com os null ("current" sem canal) e sem trocar < > & por <… (é JSON para uma app, não para HTML).
        private val GSON = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

        /** Bloqueado nas definições (o mesmo que `AppViewModel.isGroupLockedSetting`), desbloqueado ou não com o PIN. */
        fun isLockedSetting(prefs: Prefs, playlistId: String, groupId: String) = "$playlistId:$groupId" in prefs.lockedGroups

        /** O telemóvel pode ver e abrir [channel]: nem escondido (ele ou o grupo) nem bloqueado. */
        fun allowed(prefs: Prefs, playlistId: String, channel: Channel): Boolean =
            !prefs.isHidden(playlistId, channel.categoryId) && !prefs.isChannelHidden(playlistId, channel.key) &&
                !isLockedSetting(prefs, playlistId, channel.categoryId)

        /** O logótipo do canal tal como o ecrã o mostra (o escolhido, ou o da lista), se for um endereço http(s). */
        fun logoUrl(channel: Channel, logos: Map<String, String>): String? =
            (logos[channel.key] ?: channel.logo)?.trim()?.takeIf { it.toHttpUrlOrNull() != null }

        /**
         * A chave de um caminho (`/logo/<chave>`), que o telemóvel manda em percent-encoding. O pedido foi lido byte a
         * byte (cada byte um carácter), por isso os bytes de fora do ASCII também contam. null se estiver mal escrita.
         */
        fun decodeKey(encoded: String): String? {
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < encoded.length) {
                val char = encoded[i]
                if (char == '%') {
                    val hex = encoded.substring(i + 1, minOf(i + 3, encoded.length))
                    if (hex.length != 2 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    bytes.write(hex.toInt(16))
                    i += 3
                } else {
                    if (char.code > 0xFF) return null
                    bytes.write(char.code)
                    i++
                }
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString().takeIf { it.isNotEmpty() }
            } catch (e: CharacterCodingException) {
                null
            }
        }
    }
}
