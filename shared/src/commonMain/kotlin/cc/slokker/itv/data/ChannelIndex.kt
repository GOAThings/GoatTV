package cc.slokker.itv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Os grupos e canais de uma lista, para o site da conta os poder organizar (o servidor não descarrega as listas:
 * os fornecedores bloqueiam IPs de datacenter). A TV envia-o depois de carregar a lista, só quando o [hash] é
 * diferente do `index_hash` que a conta tem (ver PUT /api/goattv/playlists/{pid}/index). Igual no Android e na Apple:
 * o mesmo hash para a mesma lista, para as TVs não estarem sempre a substituir o índice umas das outras.
 */
class ChannelIndex private constructor(val hash: String, private val groups: String, private val channels: String) {

    /** O corpo do pedido (JSON; o Android manda-o em gzip). [generatedAt]: segundos Unix. */
    fun body(generatedAt: Long): String = """{"hash":"$hash","generated_at":$generatedAt,"groups":$groups,"channels":$channels}"""

    companion object {
        /** Os limites do servidor. */
        private const val MAX_CHANNELS = 100_000
        private const val MAX_GROUPS = 10_000

        /** O índice dos grupos [categories] e canais [channels] pela ordem do fornecedor; null se passar os limites do servidor. */
        fun of(categories: List<Category>, channels: List<Channel>): ChannelIndex? {
            if (channels.size > MAX_CHANNELS || categories.size > MAX_GROUPS) return null
            // O hash vem de um texto simples (uma linha por grupo e por canal), não do JSON.
            val canonical = StringBuilder()
            val groups = categories.map { c ->
                canonical.append("g\t").append(c.id).append('\t').append(c.name).append('\n')
                JsonObject(mapOf("id" to JsonPrimitive(c.id), "name" to JsonPrimitive(c.name)))
            }
            val items = channels.map { ch ->
                val logo = ch.logo?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                // O id do guia: o site usa-o para emparelhar os canais com os da lista de reserva (ver ChannelMatch).
                val epg = ch.epgId?.trim()?.takeIf { it.isNotEmpty() }
                canonical.append("c\t").append(ch.key).append('\t').append(ch.name).append('\t').append(ch.categoryId)
                    .append('\t').append(ch.num).append('\t').append(logo.orEmpty()).append('\t').append(epg.orEmpty()).append('\n')
                JsonObject(
                    buildMap {
                        put("key", JsonPrimitive(ch.key))
                        put("name", JsonPrimitive(ch.name))
                        put("group", JsonPrimitive(ch.categoryId))
                        put("num", JsonPrimitive(ch.num))
                        logo?.let { put("logo", JsonPrimitive(it)) }
                        epg?.let { put("epg", JsonPrimitive(it)) }
                    },
                )
            }
            val hash = Sha1.hex(Sha1.digest(canonical.toString().encodeToByteArray()))
            return ChannelIndex(hash, JsonArray(groups).toString(), JsonArray(items).toString())
        }
    }
}
