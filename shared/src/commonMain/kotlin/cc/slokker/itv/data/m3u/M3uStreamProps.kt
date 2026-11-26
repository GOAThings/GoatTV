package cc.slokker.itv.data.m3u

import cc.slokker.itv.data.StreamDrm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * O que uma lista M3U diz de como abrir um canal, além do URL, nas formas que o TiviMate e o Kodi leem:
 * - `#EXTVLCOPT:http-user-agent=…` / `http-referrer=…`;
 * - `#EXTHTTP:{"cookie":"…","User-Agent":"…"}` (um objeto JSON com os cabeçalhos);
 * - `URL|User-Agent=…&Referer=…&Cookie=…` (os cabeçalhos no fim do URL, com os valores em %-encoding);
 * - `#KODIPROP:inputstream.adaptive.*`: o DRM (`license_type`, `license_key`, `drm_legacy`, `drm`) e os cabeçalhos
 *   (`stream_headers`, `manifest_headers`).
 * As linhas de um canal são as que vêm entre o URL do anterior e o seu (antes ou depois do `#EXTINF`).
 */
class M3uStreamProps {
    private var userAgent: String? = null
    private var referrer: String? = null
    private val headers = LinkedHashMap<String, String>()
    private val kodi = HashMap<String, String>()

    /** Uma linha `#…` da lista: true se era uma destas (as outras ficam para o [M3uParser]). */
    fun line(line: String): Boolean {
        when {
            line.startsWith("#EXTVLCOPT:") -> {
                val opt = line.substringAfter(':').trim()
                val value = opt.substringAfter('=', "").trim()
                when (opt.substringBefore('=').trim().lowercase()) {
                    "http-user-agent" -> userAgent = value.ifEmpty { null }
                    "http-referrer", "http-referer" -> referrer = value.ifEmpty { null }
                    "http-origin" -> if (value.isNotEmpty()) headers["Origin"] = value
                    "http-cookie" -> if (value.isNotEmpty()) headers["Cookie"] = value
                }
            }
            line.startsWith("#EXTHTTP:") -> jsonHeaders(line.substringAfter(':')).forEach { (k, v) -> header(k, v) }
            line.startsWith("#KODIPROP:") -> {
                val prop = line.substringAfter(':')
                val key = prop.substringBefore('=').trim().lowercase()
                if (key.isNotEmpty() && '=' in prop) kodi[key] = prop.substringAfter('=').trim()
            }
            else -> return false
        }
        return true
    }

    /** O canal de [rawUrl] (a linha do URL, talvez com `|cabeçalhos`): o que se juntou até aqui. Depois recomeça. */
    fun take(rawUrl: String): Stream {
        val (url, piped) = splitPipe(rawUrl)
        piped.forEach { (k, v) -> header(k, v) }
        for (k in listOf("inputstream.adaptive.manifest_headers", "inputstream.adaptive.stream_headers")) {
            kodi[k]?.let { query(it) }?.forEach { (h, v) -> header(h, v) }
        }
        val stream = Stream(url, userAgent, referrer, headers.toMap().ifEmpty { null }, drm())
        userAgent = null
        referrer = null
        headers.clear()
        kodi.clear()
        return stream
    }

    class Stream(val url: String, val userAgent: String?, val referrer: String?, val headers: Map<String, String>?, val drm: StreamDrm?)

    private fun header(name: String, value: String) {
        val v = value.trim().removeSurrounding("\"")
        if (v.isEmpty()) return
        when (name.trim().lowercase()) {
            "user-agent" -> userAgent = v
            "referer", "referrer" -> referrer = v
            "" -> {}
            else -> headers[canonical(name.trim())] = v
        }
    }

    private fun drm(): StreamDrm? {
        // A forma nova do Kodi 21+: {"org.w3.clearkey":{"license":{"keyids":{…}}}} ou {"com.widevine.alpha":{"license":{"server_url":…}}}.
        kodi["inputstream.adaptive.drm"]?.let { modernDrm(it) }?.let { return it }
        // drm_legacy: "sistema|chave-ou-servidor|cabeçalhos".
        kodi["inputstream.adaptive.drm_legacy"]?.let { legacy ->
            val parts = legacy.split('|')
            system(parts[0])?.let { return drmOf(it, parts.getOrNull(1).orEmpty(), parts.getOrNull(2)) }
        }
        val type = kodi["inputstream.adaptive.license_type"]?.let { system(it) }
        val key = kodi["inputstream.adaptive.license_key"]?.trim().orEmpty()
        // Sem o tipo, umas chaves "kid:key" são ClearKey (é o que as listas querem dizer).
        val system = type ?: if (key.isNotEmpty() && !key.startsWith("http", ignoreCase = true)) StreamDrm.CLEARKEY else return null
        return drmOf(system, key, null)
    }

    /** O DRM de [system] com [key] (as chaves, o JSON delas, ou o servidor com `|cabeçalhos` à moda do Kodi). */
    private fun drmOf(system: String, key: String, headerPart: String?): StreamDrm {
        if (key.startsWith("http://", ignoreCase = true) || key.startsWith("https://", ignoreCase = true)) {
            val parts = key.split('|')
            val hdrs = (headerPart ?: parts.getOrNull(1))?.let { query(it) }?.toMap()?.ifEmpty { null }
            return StreamDrm(system, licenseUrl = parts[0].trim(), licenseHeaders = hdrs)
        }
        val keys = if (system == StreamDrm.CLEARKEY) parseKeys(key) else null
        return StreamDrm(system, keys = keys?.ifEmpty { null })
    }

    private fun modernDrm(text: String): StreamDrm? {
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        for ((name, value) in obj) {
            val system = system(name) ?: continue
            val license = (value as? JsonObject)?.get("license") as? JsonObject
            val keyIds = license?.get("keyids") as? JsonObject
            if (keyIds != null) {
                val keys = LinkedHashMap<String, String>()
                for ((kid, k) in keyIds) {
                    val kh = hexKey(kid) ?: continue
                    val vh = (k as? JsonPrimitive)?.contentOrNull?.let { hexKey(it) } ?: continue
                    keys[kh] = vh
                }
                return StreamDrm(system, keys = keys.ifEmpty { null })
            }
            val server = (license?.get("server_url") as? JsonPrimitive)?.contentOrNull
            val hdrs = (license?.get("req_headers") as? JsonPrimitive)?.contentOrNull?.let { query(it) }?.toMap()?.ifEmpty { null }
            return StreamDrm(system, licenseUrl = server, licenseHeaders = hdrs)
        }
        return null
    }

    companion object {
        /** O sistema de DRM por um dos nomes que as listas usam; null se não for um destes. */
        fun system(name: String): String? = when (name.trim().lowercase()) {
            "clearkey", "org.w3.clearkey", "clear-key" -> StreamDrm.CLEARKEY
            "widevine", "com.widevine.alpha" -> StreamDrm.WIDEVINE
            "playready", "com.microsoft.playready", "com.microsoft.playready.recommendation" -> StreamDrm.PLAYREADY
            else -> null
        }

        /**
         * As chaves ClearKey de uma lista: `kid:key` (uma ou várias, separadas por vírgulas ou espaços), em
         * hexadecimal ou base64url, ou o JSON das licenças (`{"keys":[{"kty":"oct","kid":"…","k":"…"}]}`).
         * KID → chave, em hexadecimal minúsculo.
         */
        fun parseKeys(text: String): Map<String, String> {
            val t = text.trim()
            if (t.startsWith("{")) return licenseKeys(t)
            val out = LinkedHashMap<String, String>()
            for (pair in t.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val kid = hexKey(pair.substringBefore(':')) ?: continue
                val key = hexKey(pair.substringAfter(':', "")) ?: continue
                out[kid] = key
            }
            return out
        }

        /** A resposta de um servidor de licenças ClearKey (ou o mesmo JSON na lista): KID → chave, em hexadecimal. */
        fun licenseKeys(json: String): Map<String, String> {
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (e in (obj["keys"] as? JsonArray).orEmpty()) {
                val o = e as? JsonObject ?: continue
                val kid = (o["kid"] as? JsonPrimitive)?.contentOrNull?.let { hexKey(it) } ?: continue
                val k = (o["k"] as? JsonPrimitive)?.contentOrNull?.let { hexKey(it) } ?: continue
                out[kid] = k
            }
            return out
        }

        /** O pedido a um servidor de licenças ClearKey (W3C EME) pelas [kids] (em hexadecimal). */
        fun licenseRequest(kids: Collection<String>): String =
            kids.joinToString(",", "{\"kids\":[", "],\"type\":\"temporary\"}") { "\"" + base64Url(hex(it)) + "\"" }

        /** 16 bytes em hexadecimal minúsculo, de 32 hexadecimais (com ou sem hífenes de UUID) ou de base64/base64url. */
        @OptIn(ExperimentalEncodingApi::class)
        fun hexKey(text: String): String? {
            val t = text.trim().removeSurrounding("\"")
            val h = t.replace("-", "")
            if (h.length == 32 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return h.lowercase()
            val b64 = t.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
            val bytes = runCatching { Base64.decode(b64) }.getOrNull() ?: return null
            return if (bytes.size == 16) bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } else null
        }

        fun hex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        @OptIn(ExperimentalEncodingApi::class)
        private fun base64Url(b: ByteArray): String = Base64.UrlSafe.encode(b).trimEnd('=')

        /** `URL|cabeçalhos`: o URL e os cabeçalhos (só se o resto parece `Nome=valor`; um `|` dentro do URL fica). */
        fun splitPipe(raw: String): Pair<String, List<Pair<String, String>>> {
            val bar = raw.indexOf('|')
            if (bar < 0) return raw to emptyList()
            val rest = raw.substring(bar + 1)
            val pairs = query(rest)
            if (pairs.isEmpty()) return raw to emptyList()
            return raw.substring(0, bar).trim() to pairs
        }

        /** `Nome=valor&Nome2=valor2` (valores em %-encoding), como o Kodi os escreve. */
        fun query(text: String): List<Pair<String, String>> =
            text.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = part.substring(0, eq).trim()
                if (name.isEmpty() || name.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return@mapNotNull null
                name to percentDecode(part.substring(eq + 1).trim())
            }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 <= s.lastIndex) {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out += v.toByte(); i += 3; continue }
                }
                c.toString().encodeToByteArray().forEach { out += it }
                i++
            }
            return out.toByteArray().decodeToString()
        }

        /** Os cabeçalhos de um `#EXTHTTP` (um objeto JSON; só os valores em texto). */
        private fun jsonHeaders(text: String): List<Pair<String, String>> {
            val obj = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull() ?: return emptyList()
            return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
        }

        /** "cookie" → "Cookie", "x-forwarded-for" → "X-Forwarded-For" (os servidores não ligam, mas os logs leem-se melhor). */
        private fun canonical(name: String): String =
            name.split('-').joinToString("-") { p -> p.lowercase().replaceFirstChar { it.uppercaseChar() } }
    }
}
