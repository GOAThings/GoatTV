package cc.slokker.itv.data.stalker

import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.InputRules
import cc.slokker.itv.data.Md5
import cc.slokker.itv.data.Sha1
import cc.slokker.itv.data.Sha256
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.StreamFormat

/** O que identifica a "box" no portal: o que a pessoa escreveu ou, sem isso, o calculado a partir do MAC. */
data class StalkerIdentity(val mac: String, val serial: String, val deviceId: String, val deviceId2: String, val signature: String)

/** Um programa da tabela do portal com gravação. */
data class StalkerProgramme(val id: String, val startMs: Long, val stopMs: Long)

/**
 * As regras dos portais Stalker / MAC, as mesmas no Android e na Apple: os pedidos e as sessões são de cada
 * plataforma (que se apresenta como uma MAG250); o que se pede, a cookie e o que se faz com as respostas é daqui.
 * As respostas chegam como os campos de cada objeto em texto (`id`, `cmd`, `number`…), os servidores misturam números
 * e textos.
 */
object StalkerRules {
    const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    const val MAX_PAGES = 200

    /** A tabela de um dia vem às páginas (de 10, nos portais Ministra): um dia cheio cabe em 30. */
    const val MAX_EPG_PAGES = 30

    /** Até quanto o início de um programa pode variar entre o guia XMLTV e a tabela do portal. */
    const val DRIFT_MS = 5 * 60_000L

    /** Os campos dos canais que [channel] lê ([CMDS_URL] é o `url` do primeiro de `cmds`). */
    val CHANNEL_KEYS = setOf("id", "cmd", "number", "name", "logo", "tv_genre_id", "xmltv_id", "tv_archive", "enable_tv_archive", "archive", "tv_archive_duration")
    const val CMDS_URL = "cmds.0.url"

    /** A identidade a partir do MAC (o utilizador da lista) e do que a pessoa escreveu (vazio = calcular). */
    fun identity(username: String?, serial: String?, deviceId: String?, deviceId2: String?, signature: String?): StalkerIdentity {
        fun given(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        val mac = username.orEmpty().trim().uppercase()
        val sn = given(serial) ?: Md5.hexUpper(mac).take(13)
        val id = given(deviceId) ?: sha256Upper(mac)
        return StalkerIdentity(mac, sn, id, given(deviceId2) ?: id, given(signature) ?: sha256Upper(sn + mac))
    }

    private fun sha256Upper(text: String) = Sha1.hex(Sha256.digest(text.encodeToByteArray())).uppercase()

    /** Um MAC novo com o prefixo das MAG, que os portais esperam: as apps não têm acesso ao MAC verdadeiro. */
    fun generateMac(random: (Int) -> Int): String =
        "00:1A:79:" + List(3) { random(256).toString(16).uppercase().padStart(2, '0') }.joinToString(":")

    /** O MAC como `AA:BB:CC:DD:EE:FF` (aceita traços ou sem separadores), ou null se não for um MAC. */
    fun normalizeMac(mac: String): String? {
        val hex = mac.trim().replace(":", "").replace("-", "").uppercase()
        if (hex.length != 12 || !hex.all { it in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    /** Onde o portal pode responder, pela ordem a tentar. */
    fun endpoints(server: String): List<String> {
        val s = server.trim().trimEnd('/').removeSuffix("/c")
        if (s.substringBefore('?').endsWith(".php")) return listOf(s)
        val i = s.indexOf("/stalker_portal")
        if (i >= 0) return listOf(s.substring(0, i) + "/stalker_portal/server/load.php")
        return listOf("$s/portal.php", "$s/server/load.php", "$s/stalker_portal/server/load.php")
    }

    fun isStalkerPortal(endpoint: String) = endpoint.contains("/stalker_portal/")

    /** Nos `/stalker_portal/`, MAC codificado e token na cookie (menos no `get_profile`); nos outros, MAC tal como está e token sempre. */
    fun cookie(mac: String, token: String, stalkerPortal: Boolean, action: String, timezone: String): String = buildString {
        append("mac=").append(if (stalkerPortal) formEncode(mac) else mac)
        append("; stb_lang=en; timezone=").append(timezone)
        if (token.isNotEmpty() && (!stalkerPortal || action != "get_profile")) append("; token=").append(token)
    }

    fun referer(endpoint: String): String {
        val base = endpoint.substringBefore("/server/load.php").let { if (it == endpoint) it.substringBeforeLast('/') else it }
        return "$base/c/"
    }

    /** Os parâmetros do `get_profile`, com o [random] do handshake e a hora ([nowSeconds]). */
    fun profileParams(id: StalkerIdentity, random: String, nowSeconds: Long): List<List<String>> = listOf(
        "hd" to "1", "ver" to "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.1.0; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566",
        "num_banks" to "2", "sn" to id.serial, "stb_type" to "MAG250", "image_version" to "218", "video_out" to "hdmi",
        "device_id" to id.deviceId, "device_id2" to id.deviceId2, "signature" to id.signature, "auth_second_step" to "1",
        "hw_version" to "1.7-BD-" + Md5.hexUpper(id.mac).take(2), "hw_version_2" to Md5.hexUpper((id.serial + id.mac).lowercase()).lowercase(),
        "client_type" to "STB", "metrics" to """{"mac":"${id.mac}","sn":"${id.serial}","model":"MAG250","type":"STB","uid":"","random":"$random"}""", "not_valid_token" to "0", "api_signature" to "263", "prehash" to "0",
        "timestamp" to nowSeconds.toString(), "random" to random,
    ).map { listOf(it.first, it.second) }

    /** O nome dentro de um separador (`✦●✦ DESPORTO ✦●✦`, `=== UK ===`), ou null se for um canal normal. */
    fun sectionTitle(name: String): String? {
        val title = name.trim { !it.isLetterOrDigit() }
        val start = name.indexOf(title)
        val edges = start >= 2 && name.length - start - title.length >= 2
        return title.takeIf { it.isNotEmpty() && edges && (name.take(start) + name.drop(start + title.length)).none { c -> c in "()[]{}" } }
    }

    /** Portais sem géneros: os separadores da lista passam a grupos e deixam de contar como canais. */
    fun groupBySections(channels: List<Channel>): StalkerSections {
        if (channels.count { sectionTitle(it.name) != null } < 2) return StalkerSections(channels, emptyList())
        val sections = LinkedHashMap<String, Category>()
        var current = ""
        val grouped = channels.mapNotNull { c ->
            val title = sectionTitle(c.name)
            if (title != null) {
                current = title
                sections.getOrPut(title) { Category(title, title) }
                null
            } else {
                c.copy(categoryId = current)
            }
        }
        val used = grouped.map { it.categoryId }.toSet()
        return StalkerSections(grouped.mapIndexed { i, c -> c.copy(uid = i) }, sections.values.filter { it.id in used })
    }

    /** Só os comandos `/ch/` são pedidos ao portal; os outros já são o link. */
    fun needsLink(cmd: String) = cmd.contains("/ch/")

    /** O URL dentro de um comando (`ffmpeg http://…`). Sem host, ou com localhost, aponta para o próprio portal. */
    fun commandUrl(cmd: String, portal: String): String {
        val url = cmd.trim().substringAfterLast(' ')
        val origin = origin(portal)
        if (!url.contains("://")) return origin + "/" + url.trimStart('/')
        val rest = url.substringAfter("://")
        val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@').substringBefore(':')
        if (host != "localhost" && host != "127.0.0.1") return url
        val path = rest.indexOf('/')
        return origin + if (path >= 0) rest.substring(path) else ""
    }

    /** `esquema://servidor[:porta]` de [url] (sem a porta habitual do esquema), ou "" se não for um endereço. */
    private fun origin(url: String): String {
        val scheme = url.substringBefore("://", "").lowercase().takeIf { it.isNotEmpty() } ?: return ""
        val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return ""
        val port = authority.substringAfter(':', "").toIntOrNull()
        val default = when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
        return "$scheme://$host" + if (port != null && port != default) ":$port" else ""
    }

    fun candidate(url: String): StreamCandidate {
        val hls = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
        return StreamCandidate(url, if (hls) StreamFormat.HLS else StreamFormat.TS)
    }

    /**
     * Dias de arquivo de um canal: o portal marca-o (`tv_archive`, `enable_tv_archive` ou `archive` a 1) e diz
     * `tv_archive_duration`, que nuns portais vem em horas (o Ministra traz 168, uma semana) e noutros em dias.
     * Como o QiTV e o E2iPlayer: mais de 31, ou um múltiplo de 24, são horas; marcado sem duração, uma semana.
     */
    fun archiveDays(c: Map<String, String>): Int {
        if (listOf("tv_archive", "enable_tv_archive", "archive").none { text(c, it).let { t -> t == "1" || t.equals("true", ignoreCase = true) } }) return 0
        val value = text(c, "tv_archive_duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return 7
        val days = if (value > 31 || value % 24 == 0.0) kotlin.math.ceil(value / 24) else kotlin.math.ceil(value)
        return days.coerceIn(1.0, 31.0).toInt()
    }

    /**
     * O canal de um objeto do portal ([c], os [CHANNEL_KEYS] e o [CMDS_URL]), na posição [uid], ou null sem id ou sem
     * comando. [endpoint] completa os logótipos com caminho; [channelName] dá o nome aos que vêm sem ele.
     */
    fun channel(c: Map<String, String>, uid: Int, endpoint: String, channelName: (String) -> String): Channel? {
        val id = text(c, "id") ?: return null
        val cmd = text(c, "cmd") ?: text(c, CMDS_URL) ?: return null
        return Channel(
            uid = uid,
            key = id,
            num = text(c, "number")?.let(InputRules::int) ?: (uid + 1),
            name = text(c, "name") ?: channelName(id),
            logo = text(c, "logo")?.takeIf { it.contains("://") || it.startsWith("/") }?.let { if (it.startsWith("/")) commandUrl(it, endpoint) else it },
            categoryId = text(c, "tv_genre_id").orEmpty(),
            streamId = id,
            url = cmd,
            userAgent = USER_AGENT,
            epgId = text(c, "xmltv_id"),
            archiveDays = archiveDays(c),
        )
    }

    /** Uma linha da tabela do portal com gravação (sem `mark_archive` 0) e id, ou null. */
    fun recorded(row: Map<String, String>): StalkerProgramme? {
        val start = text(row, "start_timestamp")?.toLongOrNull()?.let(InputRules::epochMs) ?: return null
        val stop = text(row, "stop_timestamp")?.toLongOrNull()?.takeIf { it > 0 }?.let(InputRules::epochMs) ?: return null
        if (stop <= start || text(row, "mark_archive") == "0") return null
        val id = text(row, "id") ?: text(row, "real_id") ?: return null
        return StalkerProgramme(id, start, stop)
    }

    /**
     * O programa do portal para o do guia ([startMs]–[endMs]) às [wantedMs]: o que dá a essa hora ou começa até
     * [DRIFT_MS] depois, o que mais se sobrepõe ao do guia. Assim, se o guia diz 20:00 e o portal 20:01, abrir o
     * programa desde o início dá a gravação dele e não o último minuto do anterior.
     */
    fun matching(rows: List<StalkerProgramme>, wantedMs: Long, startMs: Long, endMs: Long): StalkerProgramme? =
        rows.filter { (it.startMs <= wantedMs && wantedMs < it.stopMs) || (it.startMs > wantedMs && it.startMs <= wantedMs + DRIFT_MS) }
            .maxWithOrNull(compareBy<StalkerProgramme> { minOf(it.stopMs, endMs) - maxOf(it.startMs, startMs) }.thenBy { it.startMs <= wantedMs })

    /** Um campo em texto, sem espaços à volta, ou null se vier vazio. */
    private fun text(c: Map<String, String>, key: String): String? = c[key]?.trim()?.takeIf { it.isNotEmpty() }

    /** Como o URLEncoder do Java (`application/x-www-form-urlencoded`, UTF-8). */
    private fun formEncode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_" -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/** Os canais já com os grupos dos separadores, e esses grupos. */
class StalkerSections(val channels: List<Channel>, val categories: List<Category>)
