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
class ChannelIndex private constructor(
    val hash: String,
    private val categories: List<Category>,
    private val channels: List<Channel>,
) {

    /**
     * O corpo do pedido (JSON; o Android manda-o em gzip). [generatedAt]: segundos Unix. Só se monta aqui: quase
     * sempre o hash é igual ao da conta e não se manda nada, e numa lista de dezenas de milhares de canais montá-lo
     * em cada arranque levava segundos (e o GC parava a imagem).
     */
    fun body(generatedAt: Long): String {
        val groups = categories.map { c -> JsonObject(mapOf("id" to JsonPrimitive(c.id), "name" to JsonPrimitive(c.name))) }
        val items = channels.map { ch ->
            JsonObject(
                buildMap {
                    put("key", JsonPrimitive(ch.key))
                    put("name", JsonPrimitive(ch.name))
                    put("group", JsonPrimitive(ch.categoryId))
                    put("num", JsonPrimitive(ch.num))
                    logoOf(ch)?.let { put("logo", JsonPrimitive(it)) }
                    epgOf(ch)?.let { put("epg", JsonPrimitive(it)) }
                },
            )
        }
        return """{"hash":"$hash","generated_at":$generatedAt,"groups":${JsonArray(groups)},"channels":${JsonArray(items)}}"""
    }

    companion object {
        /** Os limites do servidor. */
        private const val MAX_CHANNELS = 100_000
        private const val MAX_GROUPS = 10_000

        private fun logoOf(ch: Channel) = ch.logo?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        /** O id do guia: o site usa-o para emparelhar os canais com os da lista de reserva (ver ChannelMatch). */
        private fun epgOf(ch: Channel) = ch.epgId?.trim()?.takeIf { it.isNotEmpty() }

        /** O índice dos grupos [categories] e canais [channels] pela ordem do fornecedor; null se passar os limites do servidor. */
        fun of(categories: List<Category>, channels: List<Channel>): ChannelIndex? {
            if (channels.size > MAX_CHANNELS || categories.size > MAX_GROUPS) return null
            // O hash vem de um texto simples (uma linha por grupo e por canal), não do JSON.
            val canonical = StringBuilder()
            for (c in categories) canonical.append("g\t").append(c.id).append('\t').append(c.name).append('\n')
            for (ch in channels) {
                canonical.append("c\t").append(ch.key).append('\t').append(ch.name).append('\t').append(ch.categoryId)
                    .append('\t').append(ch.num).append('\t').append(logoOf(ch).orEmpty()).append('\t').append(epgOf(ch).orEmpty()).append('\n')
            }
            val hash = Sha1.hex(Sha1.digest(canonical.toString().encodeToByteArray()))
            return ChannelIndex(hash, categories, channels)
        }
    }
}
