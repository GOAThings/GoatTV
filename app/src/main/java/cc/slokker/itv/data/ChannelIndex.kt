package cc.slokker.itv.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Os grupos e canais de uma lista, para o site da conta os poder organizar (o servidor não descarrega as listas:
 * os fornecedores bloqueiam IPs de datacenter). A TV envia-o depois de carregar a lista, só quando o [hash] é
 * diferente do `index_hash` que a conta tem (ver PUT /api/goattv/playlists/{pid}/index).
 */
class ChannelIndex private constructor(val hash: String, private val groups: String, private val channels: String) {

    /** O corpo do pedido, em gzip. */
    fun gzipBody(generatedAt: Long = System.currentTimeMillis() / 1000): ByteArray {
        val json = """{"hash":"$hash","generated_at":$generatedAt,"groups":$groups,"channels":$channels}"""
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray()) } }.toByteArray()
    }

    companion object {
        /** Os limites do servidor. */
        private const val MAX_CHANNELS = 100_000
        private const val MAX_GROUPS = 10_000

        /** O índice de [content] pela ordem do fornecedor; null se passar os limites do servidor. */
        fun of(content: PlaylistContent): ChannelIndex? {
            if (content.channels.size > MAX_CHANNELS || content.categories.size > MAX_GROUPS) return null
            // O hash vem de um texto simples (uma linha por grupo e por canal), igual ao da Apple TV: as duas apps
            // escrevem o JSON de maneira diferente, e hashes diferentes fariam as TVs substituir o índice umas das outras.
            val canonical = StringBuilder()
            val groups = JsonArray()
            for (c in content.categories) {
                groups.add(JsonObject().apply {
                    addProperty("id", c.id)
                    addProperty("name", c.name)
                })
                canonical.append("g\t").append(c.id).append('\t').append(c.name).append('\n')
            }
            val channels = JsonArray()
            for (ch in content.channels) {
                val logo = ch.logo?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                channels.add(JsonObject().apply {
                    addProperty("key", ch.key)
                    addProperty("name", ch.name)
                    addProperty("group", ch.categoryId)
                    addProperty("num", ch.num)
                    logo?.let { addProperty("logo", it) }
                })
                canonical.append("c\t").append(ch.key).append('\t').append(ch.name).append('\t').append(ch.categoryId)
                    .append('\t').append(ch.num).append('\t').append(logo.orEmpty()).append('\n')
            }
            val hash = MessageDigest.getInstance("SHA-1").digest(canonical.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            return ChannelIndex(hash, groups.toString(), channels.toString())
        }
    }
}
