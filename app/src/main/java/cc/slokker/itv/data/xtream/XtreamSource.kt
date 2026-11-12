package cc.slokker.itv.data.xtream

import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.data.BadJson
import cc.slokker.itv.data.CatchupRules
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.LimitedReader
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.SafeInput
import cc.slokker.itv.data.SourceException
import cc.slokker.itv.data.XtreamAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Reader

/**
 * Cliente Xtream Codes, só para TV em direto. As respostas leem-se com o [XtreamParse] partilhado com a Apple.
 */
class XtreamSource(private val http: OkHttpClient) {

    suspend fun load(playlist: Playlist): PlaylistContent = coroutineScope {
        val login = async { runCatching { text(apiUrl(playlist)) }.getOrNull()?.let(XtreamParse::login) }
        val categories = async { rows(apiUrl(playlist, "get_live_categories")) { XtreamParse.categories(it) } }
        val streams = async { rows(apiUrl(playlist, "get_live_streams")) { XtreamParse.streams(it) { id -> I18n.s.channelName(id) } } }

        val info = login.await()
        if (info?.auth == "0") throw SourceException(SourceException.Kind.BAD_LOGIN, I18n.s.xtreamBadLogin)
        val status = info?.status
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw SourceException(SourceException.Kind.INACTIVE, I18n.s.xtreamInactive(status), status = status)
        }

        val cats = categories.await()
        val channels = streams.await()
        PlaylistContent.build(
            playlist = playlist,
            categories = cats,
            channels = channels,
            account = info?.let {
                XtreamAccount(it.maxConnections, it.status, it.allowedFormats, it.timezone)
            },
            // Sem guia próprio, o guia completo do fornecedor (o mesmo XMLTV que os outros players usam).
            guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: xmltvUrl(playlist),
        )
    }

    /** Ligações ativas e máximo da conta, ou null se o servidor não responder. */
    suspend fun connections(playlist: Playlist): Pair<Int, Int>? {
        val info = runCatching { text(apiUrl(playlist)) }.getOrNull()?.let(XtreamParse::login)
        val active = info?.activeConnections ?: return null
        val max = info.maxConnections ?: return null
        return active to max
    }

    suspend fun shortEpg(playlist: Playlist, streamId: String, limit: Int = 4): List<EpgEntry> {
        val url = apiUrl(playlist, "get_short_epg").newBuilder()
            .addQueryParameter("stream_id", streamId)
            .addQueryParameter("limit", limit.toString())
            .build()
        return XtreamParse.shortEpg(text(url))
    }

    private fun apiUrl(playlist: Playlist, action: String? = null): HttpUrl {
        val builder = baseUrl(playlist).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", playlist.username.orEmpty())
            .addQueryParameter("password", playlist.password.orEmpty())
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build()
    }

    /** O corpo de [url] todo (respostas pequenas: a conta, o guia curto). */
    private suspend fun text(url: HttpUrl): String = body(url) { it.readText() }

    /** Uma lista grande, lida aos poucos por [parse]. */
    private suspend fun <T> rows(url: HttpUrl, parse: ((CharArray) -> Int) -> T): T = body(url) { reader ->
        try {
            parse { buf -> reader.read(buf) }
        } catch (e: BadJson) {
            throw SourceException(SourceException.Kind.BAD_RESPONSE, I18n.s.xtreamBadResponse)
        }
    }

    private suspend fun <T> body(url: HttpUrl, read: (Reader) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw SourceException(SourceException.Kind.HTTP, I18n.s.xtreamHttp(res.code), httpCode = res.code)
            SafeInput.outOfMemoryAsIo { LimitedReader(res.body.charStream(), SafeInput.MAX_PLAYLIST_BYTES).use(read) }
        }
    }

    companion object {
        fun baseUrl(playlist: Playlist): HttpUrl {
            val raw = playlist.server?.trim().orEmpty()
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
            return withScheme.toHttpUrlOrNull() ?: throw SourceException(SourceException.Kind.BAD_URL, I18n.s.xtreamBadServer)
        }

        fun streamUrl(playlist: Playlist, streamId: String, ext: String): String =
            baseUrl(playlist).newBuilder()
                .addPathSegment("live")
                .addPathSegment(playlist.username.orEmpty())
                .addPathSegment(playlist.password.orEmpty())
                .addPathSegment("$streamId.$ext")
                .build()
                .toString()

        /** Guia XMLTV completo da conta (`xmltv.php`). */
        fun xmltvUrl(playlist: Playlist): String =
            baseUrl(playlist).newBuilder()
                .addPathSegment("xmltv.php")
                .addQueryParameter("username", playlist.username.orEmpty())
                .addQueryParameter("password", playlist.password.orEmpty())
                .build()
                .toString()

        /**
         * Catch-up de [streamId] a partir de [startMs], durante [minutes] minutos. A hora vai no fuso do servidor.
         * Há duas formas na API Xtream; os servidores aceitam uma ou as duas, por isso o player tenta ambas.
         */
        fun catchupUrls(playlist: Playlist, streamId: String, startMs: Long, minutes: Int, timezone: String?): List<String> {
            val start = CatchupRules.xtreamStart(startMs, timezone)
            val user = playlist.username.orEmpty()
            val pass = playlist.password.orEmpty()
            val path = baseUrl(playlist).newBuilder()
                .addPathSegment("timeshift").addPathSegment(user).addPathSegment(pass)
                .addPathSegment(minutes.toString()).addPathSegment(start).addPathSegment("$streamId.ts")
                .build().toString()
            val php = baseUrl(playlist).newBuilder()
                .addPathSegment("streaming").addPathSegment("timeshift.php")
                .addQueryParameter("username", user).addQueryParameter("password", pass)
                .addQueryParameter("stream", streamId).addQueryParameter("start", start)
                .addQueryParameter("duration", minutes.toString())
                .build().toString()
            return listOf(path, php)
        }
    }
}

