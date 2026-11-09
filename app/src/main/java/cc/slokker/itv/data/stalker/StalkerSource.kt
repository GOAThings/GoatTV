package cc.slokker.itv.data.stalker

import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.LimitedReader
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.SafeInput
import cc.slokker.itv.data.SourceException
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.i18n.I18n
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Cliente de portais Stalker / MAC, só para TV em direto e só para listas sem conta: os pedidos saem deste aparelho,
 * com o MAC autorizado. Apresenta-se como uma MAG250. Os links dos canais pedem-se ao abrir, porque podem expirar.
 */
class StalkerSource(http: OkHttpClient) {

    private val http = http.newBuilder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    private class Session(val endpoint: String, var token: String, val identity: Identity)

    private val sessions = HashMap<String, Session>()
    private val mutex = Mutex()

    suspend fun load(playlist: Playlist): PlaylistContent = withContext(Dispatchers.IO) {
        mutex.withLock { sessions.remove(playlist.id) }
        val genres = api(playlist, "itv", "get_genres").asListOrEmpty().mapNotNull { it.asObjectOrNull() }
        val categories = genres.mapNotNull { g ->
            val id = g.text("id")?.takeIf { it != "*" } ?: return@mapNotNull null
            Category(id, g.text("title") ?: id)
        }
        val raw = LinkedHashMap<String, JsonObject>()
        api(playlist, "itv", "get_all_channels", listOf("force_ch_link_check" to "", "include_censored" to "1"))
            .listOfData().forEach { c -> c.text("id")?.let { raw[it] = c } }
        // Os géneros adultos muitas vezes só dão os canais página a página.
        for (genre in genres.filter { it.text("censored") == "1" }) {
            val id = genre.text("id") ?: continue
            var page = 1
            while (page <= MAX_PAGES) {
                val js = api(playlist, "itv", "get_ordered_list", listOf("genre" to id, "force_ch_link_check" to "", "fav" to "0", "sortby" to "number", "hd" to "0", "p" to page.toString()))
                val list = js.listOfData()
                list.forEach { c -> c.text("id")?.let { raw.putIfAbsent(it, c) } }
                val total = js.asObjectOrNull()?.text("total_items")?.toIntOrNull() ?: 0
                val perPage = js.asObjectOrNull()?.text("max_page_items")?.toIntOrNull()?.takeIf { it > 0 } ?: list.size
                if (list.isEmpty() || page * perPage >= total) break
                page++
            }
        }
        val endpoint = mutex.withLock { sessions[playlist.id]?.endpoint }.orEmpty()
        val channels = raw.values.mapIndexedNotNull { i, c -> parseChannel(c, i, endpoint) }
        val (grouped, sections) = if (categories.isEmpty()) groupBySections(channels) else channels to emptyList()
        PlaylistContent.build(
            playlist = playlist,
            categories = categories + sections,
            channels = grouped,
            guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() },
        )
    }

    /** O link de [channel] neste momento. Sem resposta do portal fica o do comando: o player mostra o erro e volta a tentar. */
    suspend fun candidates(playlist: Playlist, channel: Channel): List<StreamCandidate> = withContext(Dispatchers.IO) {
        val cmd = channel.url.orEmpty()
        val endpoint = runCatchingIo { session(playlist).endpoint } ?: return@withContext listOf(candidate(commandUrl(cmd, playlist.server.orEmpty())))
        val link = if (needsLink(cmd)) {
            runCatchingIo {
                val js = api(playlist, "itv", "create_link", listOf("cmd" to cmd, "series" to "", "forced_storage" to "undefined", "disable_ad" to "0", "download" to "0"))
                js.asObjectOrNull()?.text("cmd")
            }
        } else {
            null
        }
        listOf(candidate(commandUrl(link ?: cmd, endpoint)))
    }

    /**
     * A gravação do programa [startMs]–[endMs] de [channel] (visto às [wantedMs]), no arquivo do portal: o portal não
     * faz URLs com horas, dá o link da gravação de um programa do seu guia (`type=tv_archive&action=create_link`, com
     * o comando `auto /media/<id do programa>.mpg`). O id procura-se na tabela do dia (`get_simple_data_table`).
     * Devolve onde a gravação começa e o link, ou null (sem gravação, ou o portal não respondeu).
     */
    suspend fun archive(playlist: Playlist, channel: Channel, wantedMs: Long, startMs: Long, endMs: Long): Recording? = withContext(Dispatchers.IO) {
        val id = channel.streamId ?: return@withContext null
        val programme = runCatchingIo { recordingAt(playlist, id, wantedMs, startMs, endMs) } ?: return@withContext null
        val js = runCatchingIo {
            api(playlist, "tv_archive", "create_link", listOf("cmd" to "auto /media/${programme.id}.mpg", "series" to "", "forced_storage" to "", "disable_ad" to "0", "download" to "0"))
        }?.asObjectOrNull() ?: return@withContext null
        // "nothing_to_play" (ou outro erro) e um comando vazio: não há gravação.
        if (js.text("error") != null) return@withContext null
        val cmd = js.text("cmd") ?: return@withContext null
        val endpoint = runCatchingIo { session(playlist).endpoint }.orEmpty()
        val url = commandUrl(cmd, endpoint)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        Recording(programme.startMs, candidate(url))
    }

    /** Onde a gravação de um programa começa e como abri-la. */
    class Recording(val startMs: Long, val candidate: StreamCandidate)

    private class Programme(val id: String, val startMs: Long, val stopMs: Long)

    /**
     * O programa da tabela do portal que corresponde ao programa [startMs]–[endMs] do guia, visto às [wantedMs] (o dia
     * vem no fuso deste aparelho, que vai na cookie), ou null. Os que o portal marca como não gravados
     * (`mark_archive` 0) não contam. Perto da meia-noite, o programa pode estar na tabela do dia anterior.
     */
    private suspend fun recordingAt(playlist: Playlist, channelId: String, wantedMs: Long, startMs: Long, endMs: Long): Programme? {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for (dayMs in listOf(wantedMs, wantedMs - 86_400_000L)) {
            val date = format.format(java.util.Date(dayMs))
            val rows = ArrayList<Programme>()
            var page = 1
            while (page <= MAX_EPG_PAGES) {
                val js = api(playlist, "epg", "get_simple_data_table", listOf("ch_id" to channelId, "date" to date, "p" to page.toString()))
                val list = js.listOfData()
                list.mapNotNullTo(rows, ::recorded)
                val total = js.asObjectOrNull()?.text("total_items")?.toIntOrNull() ?: 0
                val perPage = js.asObjectOrNull()?.text("max_page_items")?.toIntOrNull()?.takeIf { it > 0 } ?: list.size
                if (list.isEmpty() || page * perPage >= total) break
                page++
            }
            matching(rows, wantedMs, startMs, endMs)?.let { return it }
        }
        return null
    }

    suspend fun shortEpg(playlist: Playlist, channel: Channel, size: Int = 4): List<EpgEntry> = withContext(Dispatchers.IO) {
        val id = channel.streamId ?: return@withContext emptyList()
        api(playlist, "itv", "get_short_epg", listOf("ch_id" to id, "size" to size.toString())).asListOrEmpty().mapNotNull { e ->
            val o = e.asObjectOrNull() ?: return@mapNotNull null
            val start = o.text("start_timestamp")?.toLongOrNull()?.let(SafeInput::epochMs) ?: return@mapNotNull null
            val stop = o.text("stop_timestamp")?.toLongOrNull()?.let(SafeInput::epochMs) ?: return@mapNotNull null
            EpgEntry(o.text("name").orEmpty(), start, stop)
        }
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null // um cabeçalho que o OkHttp recusa (ex.: um MAC com acentos): é como o portal não responder.
    }

    /** Um pedido com a sessão; se o token tiver caducado, entra de novo uma vez. */
    private suspend fun api(playlist: Playlist, type: String, action: String, params: List<Pair<String, String>> = emptyList()): JsonElement {
        val s = session(playlist)
        return try {
            js(call(s.endpoint, s.identity, s.token, type, action, params))
        } catch (e: ExpiredToken) {
            mutex.withLock { sessions.remove(playlist.id) }
            val again = session(playlist)
            try {
                js(call(again.endpoint, again.identity, again.token, type, action, params))
            } catch (e: ExpiredToken) {
                throw badLogin()
            }
        }
    }

    private suspend fun session(playlist: Playlist): Session = mutex.withLock {
        sessions[playlist.id]?.let { return@withLock it }
        val server = playlist.server?.trim().orEmpty()
        if (server.toHttpUrlOrNull() == null) throw SourceException(SourceException.Kind.BAD_URL, I18n.s.m3uBadUrl)
        val identity = Identity.of(playlist)
        var failure: IOException? = null
        for (endpoint in endpoints(server)) {
            try {
                val hello = js(call(endpoint, identity, "", "stb", "handshake", listOf("token" to ""))).asObjectOrNull()
                val token = hello?.text("token")?.takeIf { it.isNotEmpty() && SafeInput.isHeaderSafe(it) } ?: throw badLogin()
                val profile = js(call(endpoint, identity, token, "stb", "get_profile", profileParams(identity, hello.text("random").orEmpty()))).asObjectOrNull()
                if (profile == null || profile.text("status") == "1") throw badLogin()
                val finalToken = profile.text("token")?.takeIf { it.isNotEmpty() } ?: token
                if (!SafeInput.isHeaderSafe(finalToken)) throw badLogin()
                return@withLock Session(endpoint, finalToken, identity).also { sessions[playlist.id] = it }
            } catch (e: SourceException) {
                if (e.kind == SourceException.Kind.BAD_LOGIN) throw e
                failure = e
            } catch (e: ExpiredToken) {
                throw badLogin()
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: SourceException(SourceException.Kind.BAD_RESPONSE, I18n.s.m3uBadUrl)
    }

    private fun call(endpoint: String, identity: Identity, token: String, type: String, action: String, params: List<Pair<String, String>>): String {
        val url = endpoint.toHttpUrlOrNull()!!.newBuilder().apply {
            addQueryParameter("type", type)
            addQueryParameter("action", action)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()
        val request = Request.Builder().url(url)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", referer(endpoint))
            .header("Cookie", cookie(identity.mac, token, isStalkerPortal(endpoint), action, TimeZone.getDefault().id))
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { res ->
            val body = SafeInput.outOfMemoryAsIo { LimitedReader(res.body.charStream(), SafeInput.MAX_PLAYLIST_BYTES).use { it.readText() } }
            if (res.code == 401 || body.contains("Authorization failed", ignoreCase = true)) throw ExpiredToken()
            if (!res.isSuccessful) throw SourceException(SourceException.Kind.HTTP, I18n.s.m3uHttp(res.code), httpCode = res.code)
            return body
        }
    }

    private class ExpiredToken : IOException()

    private fun badLogin() = SourceException(SourceException.Kind.BAD_LOGIN, I18n.s.stalkerBadLogin)

    private fun js(body: String): JsonElement {
        val root = try {
            SafeInput.outOfMemoryAsIo { JsonParser.parseString(body).asObjectOrNull() }
        } catch (e: RuntimeException) {
            null
        }
        return root?.get("js") ?: throw SourceException(SourceException.Kind.BAD_RESPONSE, I18n.s.stalkerBadLogin)
    }

    /** O que identifica a "box" no portal: o que a pessoa escreveu ou, sem isso, o calculado a partir do MAC. */
    data class Identity(val mac: String, val serial: String, val deviceId: String, val deviceId2: String, val signature: String) {
        companion object {
            fun of(p: Playlist): Identity {
                val mac = p.username.orEmpty().trim().uppercase()
                val serial = p.serial?.trim()?.takeIf { it.isNotEmpty() } ?: hash("MD5", mac).take(13)
                val deviceId = p.deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: hash("SHA-256", mac)
                return Identity(
                    mac = mac,
                    serial = serial,
                    deviceId = deviceId,
                    deviceId2 = p.deviceId2?.trim()?.takeIf { it.isNotEmpty() } ?: deviceId,
                    signature = p.signature?.trim()?.takeIf { it.isNotEmpty() } ?: hash("SHA-256", serial + mac),
                )
            }

            fun hash(algorithm: String, text: String): String =
                MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02X".format(it) }
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val MAX_PAGES = 200

        /** A tabela de um dia vem às páginas (de 10, nos portais Ministra): um dia cheio cabe em 30. */
        private const val MAX_EPG_PAGES = 30

        /**
         * Dias de arquivo de um canal: o portal marca-o (`tv_archive`, `enable_tv_archive` ou `archive` a 1) e diz
         * `tv_archive_duration`, que nuns portais vem em horas (o Ministra traz 168, uma semana) e noutros em dias.
         * Como o QiTV e o E2iPlayer: mais de 31, ou um múltiplo de 24, são horas; marcado sem duração, uma semana.
         */
        fun archiveDays(c: JsonObject): Int {
            if (listOf("tv_archive", "enable_tv_archive", "archive").none { c.text(it) == "1" || c.text(it).equals("true", ignoreCase = true) }) return 0
            val value = c.text("tv_archive_duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return 7
            val days = if (value > 31 || value % 24 == 0.0) kotlin.math.ceil(value / 24) else kotlin.math.ceil(value)
            return days.toInt().coerceIn(1, 31)
        }

        /** Uma linha da tabela do portal com gravação (sem `mark_archive` 0) e id, ou null. */
        private fun recorded(row: JsonObject): Programme? {
            val start = row.text("start_timestamp")?.toLongOrNull()?.let(SafeInput::epochMs) ?: return null
            val stop = row.text("stop_timestamp")?.toLongOrNull()?.takeIf { it > 0 }?.let(SafeInput::epochMs) ?: return null
            if (stop <= start || row.text("mark_archive") == "0") return null
            val id = row.text("id") ?: row.text("real_id") ?: return null
            return Programme(id, start, stop)
        }

        /** Até quanto o início de um programa pode variar entre o guia XMLTV e a tabela do portal. */
        private const val DRIFT_MS = 5 * 60_000L

        /**
         * O programa do portal para o do guia ([startMs]–[endMs]) às [wantedMs]: o que dá a essa hora ou começa até
         * [DRIFT_MS] depois, o que mais se sobrepõe ao do guia. Assim, se o guia diz 20:00 e o portal 20:01, abrir o
         * programa desde o início dá a gravação dele e não o último minuto do anterior.
         */
        private fun matching(rows: List<Programme>, wantedMs: Long, startMs: Long, endMs: Long): Programme? =
            rows.filter { (it.startMs <= wantedMs && wantedMs < it.stopMs) || (it.startMs > wantedMs && it.startMs <= wantedMs + DRIFT_MS) }
                .maxWithOrNull(compareBy<Programme> { minOf(it.stopMs, endMs) - maxOf(it.startMs, startMs) }.thenBy { it.startMs <= wantedMs })

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
            append("mac=").append(if (stalkerPortal) URLEncoder.encode(mac, "UTF-8") else mac)
            append("; stb_lang=en; timezone=").append(timezone)
            if (token.isNotEmpty() && (!stalkerPortal || action != "get_profile")) append("; token=").append(token)
        }

        private fun referer(endpoint: String): String {
            val base = endpoint.substringBefore("/server/load.php").let { if (it == endpoint) it.substringBeforeLast('/') else it }
            return "$base/c/"
        }

        private fun profileParams(id: Identity, random: String): List<Pair<String, String>> = listOf(
            "hd" to "1", "ver" to "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.1.0; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566",
            "num_banks" to "2", "sn" to id.serial, "stb_type" to "MAG250", "image_version" to "218", "video_out" to "hdmi",
            "device_id" to id.deviceId, "device_id2" to id.deviceId2, "signature" to id.signature, "auth_second_step" to "1",
            "hw_version" to "1.7-BD-" + Identity.hash("MD5", id.mac).take(2), "hw_version_2" to Identity.hash("MD5", (id.serial + id.mac).lowercase()).lowercase(),
            "client_type" to "STB", "metrics" to """{"mac":"${id.mac}","sn":"${id.serial}","model":"MAG250","type":"STB","uid":"","random":"$random"}""", "not_valid_token" to "0", "api_signature" to "263", "prehash" to "0",
            "timestamp" to (System.currentTimeMillis() / 1000).toString(), "random" to random,
        )

        /** O nome dentro de um separador (`✦●✦ DESPORTO ✦●✦`, `=== UK ===`), ou null se for um canal normal. */
        fun sectionTitle(name: String): String? {
            val title = name.trim { !it.isLetterOrDigit() }
            val start = name.indexOf(title)
            val edges = start >= 2 && name.length - start - title.length >= 2
            return title.takeIf { it.isNotEmpty() && edges && (name.take(start) + name.drop(start + title.length)).none { c -> c in "()[]{}" } }
        }

        /** Portais sem géneros: os separadores da lista passam a grupos e deixam de contar como canais. */
        fun groupBySections(channels: List<Channel>): Pair<List<Channel>, List<Category>> {
            if (channels.count { sectionTitle(it.name) != null } < 2) return channels to emptyList()
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
            return grouped.mapIndexed { i, c -> c.copy(uid = i) } to sections.values.filter { it.id in used }
        }

        /** Só os comandos `/ch/` são pedidos ao portal; os outros já são o link. */
        fun needsLink(cmd: String) = cmd.contains("/ch/")

        /** O URL dentro de um comando (`ffmpeg http://…`). Sem host, ou com localhost, aponta para o próprio portal. */
        fun commandUrl(cmd: String, portal: String): String {
            val url = cmd.trim().substringAfterLast(' ')
            val origin = portal.toHttpUrlOrNull()?.let { u -> "${u.scheme}://${u.host}" + if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else "" }.orEmpty()
            val parsed = url.toHttpUrlOrNull()
            return when {
                parsed == null -> origin + "/" + url.trimStart('/')
                parsed.host == "localhost" || parsed.host == "127.0.0.1" -> origin + url.substringAfter("://").let { r -> r.indexOf('/').let { if (it >= 0) r.substring(it) else "" } }
                else -> url
            }
        }

        fun candidate(url: String): StreamCandidate {
            val hls = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
            return StreamCandidate(url, if (hls) StreamFormat.HLS else StreamFormat.TS)
        }

        private fun parseChannel(c: JsonObject, index: Int, endpoint: String): Channel? {
            val id = c.text("id") ?: return null
            val cmd = c.text("cmd") ?: c.getAsJsonArrayOrNull("cmds")?.firstOrNull()?.asObjectOrNull()?.text("url") ?: return null
            return Channel(
                uid = index,
                key = id,
                num = c.text("number")?.toDoubleOrNull()?.toInt() ?: (index + 1),
                name = c.text("name") ?: I18n.s.channelName(id),
                logo = c.text("logo")?.takeIf { it.contains("://") || it.startsWith("/") }?.let { if (it.startsWith("/")) commandUrl(it, endpoint) else it },
                categoryId = c.text("tv_genre_id").orEmpty(),
                streamId = id,
                url = cmd,
                userAgent = USER_AGENT,
                epgId = c.text("xmltv_id"),
                archiveDays = archiveDays(c),
            )
        }

        private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
        private fun JsonElement.asListOrEmpty(): List<JsonElement> = if (isJsonArray) asJsonArray.toList() else emptyList()
        private fun JsonObject.getAsJsonArrayOrNull(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.text(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

        /** Os portais dão a lista diretamente ou em `{data: […]}`. */
        private fun JsonElement.listOfData(): List<JsonObject> {
            val list = asObjectOrNull()?.getAsJsonArrayOrNull("data")?.toList() ?: asListOrEmpty()
            return list.mapNotNull { it.asObjectOrNull() }
        }
    }
}
