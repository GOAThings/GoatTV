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
import java.util.TimeZone

/**
 * Cliente de portais Stalker / MAC, só para TV em direto e só para listas sem conta: os pedidos saem deste aparelho,
 * com o MAC autorizado. Apresenta-se como uma MAG250. Os links dos canais pedem-se ao abrir, porque podem expirar.
 */
class StalkerSource(http: OkHttpClient) {

    private val http = http.newBuilder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    private class Session(val endpoint: String, var token: String, val identity: StalkerIdentity)

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
        val channels = ArrayList<Channel>()
        for (c in raw.values) StalkerRules.channel(c.fields(StalkerRules.CHANNEL_KEYS), channels.size, endpoint) { I18n.s.channelName(it) }?.let { channels += it }
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

    /**
     * O programa da tabela do portal que corresponde ao programa [startMs]–[endMs] do guia, visto às [wantedMs] (o dia
     * vem no fuso deste aparelho, que vai na cookie), ou null. Os que o portal marca como não gravados
     * (`mark_archive` 0) não contam. Perto da meia-noite, o programa pode estar na tabela do dia anterior.
     */
    private suspend fun recordingAt(playlist: Playlist, channelId: String, wantedMs: Long, startMs: Long, endMs: Long): StalkerProgramme? {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for (dayMs in listOf(wantedMs, wantedMs - 86_400_000L)) {
            val date = format.format(java.util.Date(dayMs))
            val rows = ArrayList<StalkerProgramme>()
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
            StalkerRules.matching(rows, wantedMs, startMs, endMs)?.let { return it }
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

    private fun call(endpoint: String, identity: StalkerIdentity, token: String, type: String, action: String, params: List<Pair<String, String>>): String {
        val url = endpoint.toHttpUrlOrNull()!!.newBuilder().apply {
            addQueryParameter("type", type)
            addQueryParameter("action", action)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()
        val request = Request.Builder().url(url)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", StalkerRules.referer(endpoint))
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

    /** O que identifica a "box" no portal (ver [StalkerRules.identity]). */
    object Identity {
        fun of(p: Playlist): StalkerIdentity = StalkerRules.identity(p.username, p.serial, p.deviceId, p.deviceId2, p.signature)
    }

    companion object {
        const val USER_AGENT = StalkerRules.USER_AGENT
        private const val MAX_PAGES = StalkerRules.MAX_PAGES
        private const val MAX_EPG_PAGES = StalkerRules.MAX_EPG_PAGES

        fun endpoints(server: String): List<String> = StalkerRules.endpoints(server)

        fun isStalkerPortal(endpoint: String) = StalkerRules.isStalkerPortal(endpoint)

        fun cookie(mac: String, token: String, stalkerPortal: Boolean, action: String, timezone: String): String =
            StalkerRules.cookie(mac, token, stalkerPortal, action, timezone)

        private fun profileParams(id: StalkerIdentity, random: String): List<Pair<String, String>> =
            StalkerRules.profileParams(id, random, System.currentTimeMillis() / 1000).map { it[0] to it[1] }

        fun sectionTitle(name: String): String? = StalkerRules.sectionTitle(name)

        fun groupBySections(channels: List<Channel>): Pair<List<Channel>, List<Category>> =
            StalkerRules.groupBySections(channels).let { it.channels to it.categories }

        fun needsLink(cmd: String) = StalkerRules.needsLink(cmd)

        fun commandUrl(cmd: String, portal: String): String = StalkerRules.commandUrl(cmd, portal)

        fun candidate(url: String): StreamCandidate = StalkerRules.candidate(url)

        fun archiveDays(c: JsonObject): Int = StalkerRules.archiveDays(c.fields(ARCHIVE_KEYS))

        private val ARCHIVE_KEYS = setOf("tv_archive", "enable_tv_archive", "archive", "tv_archive_duration")
        private val PROGRAMME_KEYS = setOf("start_timestamp", "stop_timestamp", "mark_archive", "id", "real_id")

        /** Os campos [keys] de um objeto do portal, em texto (os números também), e o `url` do primeiro de `cmds`. */
        private fun JsonObject.fields(keys: Set<String>): Map<String, String> {
            val out = HashMap<String, String>()
            for (k in keys) get(k)?.takeIf { it.isJsonPrimitive }?.asString?.let { out[k] = it }
            getAsJsonArrayOrNull("cmds")?.firstOrNull()?.asObjectOrNull()?.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                ?.let { out[StalkerRules.CMDS_URL] = it }
            return out
        }

        private fun recorded(row: JsonObject): StalkerProgramme? = StalkerRules.recorded(row.fields(PROGRAMME_KEYS))

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
