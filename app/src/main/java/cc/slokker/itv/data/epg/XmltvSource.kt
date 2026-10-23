package cc.slokker.itv.data.epg

import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Guia XMLTV de uma lista, já reduzido aos canais dela e a uma janela à volta de agora.
 * [programmes]: id do canal no XMLTV (minúsculas) → programas por ordem.
 * [names]: nome normalizado → id, para canais sem `tvg-id` (ou com um que o guia não tem).
 */
class XmltvGuide(
    val url: String,
    val fetchedAt: Long,
    val programmes: Map<String, List<EpgEntry>>,
    val names: Map<String, String>,
) {
    fun entriesFor(channel: Channel): List<EpgEntry> {
        channel.epgId?.lowercase()?.let { id -> programmes[id]?.let { return it } }
        val id = names[normalizeName(channel.name)] ?: return emptyList()
        return programmes[id].orEmpty()
    }

    companion object {
        private val PREFIX = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
        private val QUALITY = Regex("""\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p?|720p?)\b""")
        private val NON_ALNUM = Regex("""[^a-z0-9]""")
        private val ACCENTS = Regex("""\p{Mn}+""")

        /** "PT: RTP 1 HD" → "rtp1": o mesmo canal escrito de formas diferentes na lista e no guia. */
        fun normalizeName(name: String): String {
            val plain = ACCENTS.replace(Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD), "")
            return NON_ALNUM.replace(QUALITY.replace(PREFIX.replace(plain.trim(), ""), ""), "")
        }

        /**
         * Lê o XMLTV guardando só os programas dos [channels] entre [fromMs] e [toMs].
         * Os ids comparam-se em minúsculas (as listas nem sempre respeitam maiúsculas).
         */
        fun build(url: String, channels: List<Channel>, fromMs: Long, toMs: Long, fetchedAt: Long, parse: (XmltvParser) -> Unit): XmltvGuide {
            val wantedIds = channels.mapNotNullTo(HashSet()) { it.epgId?.lowercase() }
            // Só os canais sem programas pelo id precisam de casar pelo nome; decide-se no fim.
            val channelNames = channels.mapTo(HashSet()) { normalizeName(it.name) }.apply { remove("") }
            val names = HashMap<String, String>()
            val nameIds = HashSet<String>()
            val programmes = HashMap<String, ArrayList<EpgEntry>>()
            val titles = HashMap<String, String>() // os títulos repetem-se muito: uma só String por título

            val parser = XmltvParser(
                onChannel = { id, displayNames ->
                    val lower = id.lowercase()
                    for (n in displayNames) {
                        val key = normalizeName(n)
                        if (key in channelNames && key !in names) {
                            names[key] = lower
                            nameIds += lower
                        }
                    }
                },
                wantsProgramme = { ch, start, end ->
                    end > fromMs && start < toMs && ch.lowercase().let { it in wantedIds || it in nameIds }
                },
                onProgramme = { ch, start, end, title ->
                    programmes.getOrPut(ch.lowercase()) { ArrayList() } += EpgEntry(titles.getOrPut(title) { title }, start, end)
                },
            )
            parse(parser)
            programmes.values.forEach { list -> list.sortBy { it.startMs } }
            return XmltvGuide(url, fetchedAt, programmes, names.filterValues { it in programmes })
        }
    }
}

/** Descarrega e lê guias XMLTV (`.xml` ou `.xml.gz`). */
class XmltvSource(private val http: OkHttpClient) {

    suspend fun load(url: String, channels: List<Channel>): XmltvGuide = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Bad guide URL")
        }
        val context = coroutineContext
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Guide HTTP ${res.code}")
            val source = res.body.source()
            // Muitos guias vêm em .gz sem Content-Encoding: vê-se pelos dois primeiros bytes.
            val gzip = source.peek().let { it.request(2) && it.readByte() == 0x1f.toByte() && it.readByte() == 0x8b.toByte() }
            val stream = if (gzip) GZIPInputStream(source.inputStream(), 64 * 1024) else source.inputStream()
            val now = System.currentTimeMillis()
            XmltvGuide.build(url, channels, now - WINDOW_BEFORE_MS, now + WINDOW_AFTER_MS, now) { parser ->
                val reader = CancellableReader(stream.reader(Charsets.UTF_8)) { context.ensureActive() }
                parser.parse(reader.buffered(64 * 1024))
            }
        }
    }

    /** Leitor que verifica de vez em quando se o pedido foi cancelado (o parse de um guia grande demora). */
    private class CancellableReader(private val inner: java.io.Reader, private val check: () -> Unit) : java.io.Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            check()
            return inner.read(cbuf, off, len)
        }

        override fun close() = inner.close()
    }

    private companion object {
        /** 24 h para trás: o guia mostra o que já passou (e dá para ver em catch-up). */
        const val WINDOW_BEFORE_MS = 24 * 3600_000L
        const val WINDOW_AFTER_MS = 36 * 3600_000L
    }
}
