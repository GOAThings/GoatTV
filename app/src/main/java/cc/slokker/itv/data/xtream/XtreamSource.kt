package cc.slokker.itv.data.xtream

import cc.slokker.itv.ui.i18n.I18n
import android.util.Base64
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.SourceException
import cc.slokker.itv.data.XtreamAccount
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.lang.reflect.Type

/**
 * Cliente Xtream Codes, só para TV em direto.
 * Os campos vêm como String porque os servidores misturam números e strings
 * (`"stream_id": "123"` vs `123`); o Gson converte números em String sem problemas.
 */
class XtreamSource(private val http: OkHttpClient, private val gson: Gson) {

    suspend fun load(playlist: Playlist): PlaylistContent = coroutineScope {
        val login = async { runCatching { get<XtreamLogin>(apiUrl(playlist), XtreamLogin::class.java) }.getOrNull() }
        val categories = async {
            get<List<XtreamCategory>?>(apiUrl(playlist, "get_live_categories"), CATEGORY_LIST).orEmpty()
        }
        val streams = async {
            get<List<XtreamStream>?>(apiUrl(playlist, "get_live_streams"), STREAM_LIST).orEmpty()
        }

        val loginResult = login.await()
        val info = loginResult?.userInfo
        if (info?.auth == "0") throw SourceException(SourceException.Kind.BAD_LOGIN, I18n.s.xtreamBadLogin)
        val status = info?.status
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw SourceException(SourceException.Kind.INACTIVE, I18n.s.xtreamInactive(status), status = status)
        }

        val cats = categories.await().mapNotNull { c ->
            val id = c.categoryId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Category(id, c.categoryName?.trim().orEmpty().ifEmpty { id })
        }
        val channels = streams.await().mapIndexedNotNull { i, s ->
            val id = s.streamId?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            Channel(
                uid = i,
                key = id,
                num = s.num?.toDoubleOrNull()?.toInt() ?: (i + 1),
                name = s.name?.trim().orEmpty().ifEmpty { I18n.s.channelName(id) },
                logo = s.streamIcon?.takeIf { it.isNotBlank() },
                categoryId = s.categoryId.orEmpty(),
                streamId = id,
                epgId = s.epgChannelId?.trim()?.takeIf { it.isNotEmpty() },
                archiveDays = if (s.tvArchive?.trim() == "1") s.tvArchiveDuration?.trim()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 1 else 0,
            )
        }
        PlaylistContent.build(
            playlist = playlist,
            categories = cats,
            channels = channels,
            account = info?.let {
                XtreamAccount(it.maxConnections?.toIntOrNull(), it.status, it.allowedFormats(), loginResult?.serverInfo?.timezone?.takeIf { tz -> tz.isNotBlank() })
            },
            // Sem guia próprio, o guia completo do fornecedor (o mesmo XMLTV que os outros players usam).
            guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: xmltvUrl(playlist),
        )
    }

    /** Ligações ativas e máximo da conta, ou null se o servidor não responder. */
    suspend fun connections(playlist: Playlist): Pair<Int, Int>? {
        val info = runCatching { get<XtreamLogin?>(apiUrl(playlist), XtreamLogin::class.java) }.getOrNull()?.userInfo
        val active = info?.activeCons?.toIntOrNull() ?: return null
        val max = info.maxConnections?.toIntOrNull() ?: return null
        return active to max
    }

    suspend fun shortEpg(playlist: Playlist, streamId: String, limit: Int = 4): List<EpgEntry> {
        val url = apiUrl(playlist, "get_short_epg").newBuilder()
            .addQueryParameter("stream_id", streamId)
            .addQueryParameter("limit", limit.toString())
            .build()
        val listings = get<XtreamShortEpg?>(url, XtreamShortEpg::class.java)?.epgListings.orEmpty()
        return listings.mapNotNull { l ->
            val start = l.startTimestamp?.toLongOrNull() ?: return@mapNotNull null
            val stop = l.stopTimestamp?.toLongOrNull() ?: return@mapNotNull null
            EpgEntry(decodeBase64(l.title.orEmpty()), start * 1000, stop * 1000)
        }.sortedBy { it.startMs }
    }

    private fun apiUrl(playlist: Playlist, action: String? = null): HttpUrl {
        val builder = baseUrl(playlist).newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", playlist.username.orEmpty())
            .addQueryParameter("password", playlist.password.orEmpty())
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build()
    }

    private suspend fun <T> get(url: HttpUrl, type: Type): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw SourceException(SourceException.Kind.HTTP, I18n.s.xtreamHttp(res.code), httpCode = res.code)
            try {
                gson.fromJson<T>(res.body.charStream(), type)
            } catch (e: JsonParseException) {
                throw SourceException(SourceException.Kind.BAD_RESPONSE, I18n.s.xtreamBadResponse)
            }
        }
    }

    companion object {
        private val CATEGORY_LIST = object : TypeToken<List<XtreamCategory>>() {}.type
        private val STREAM_LIST = object : TypeToken<List<XtreamStream>>() {}.type

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
            val zone = timezone?.let { java.util.TimeZone.getTimeZone(it) } ?: java.util.TimeZone.getDefault()
            val start = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(startMs))
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

        private fun decodeBase64(s: String): String =
            try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (e: IllegalArgumentException) {
                s
            }
    }
}

class XtreamLogin(
    @SerializedName("user_info") val userInfo: XtreamUserInfo?,
    @SerializedName("server_info") val serverInfo: XtreamServerInfo?,
)

class XtreamServerInfo(val timezone: String?)

class XtreamUserInfo(
    val auth: String?,
    val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("active_cons") val activeCons: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    // JsonElement: alguns servidores mandam lista, outros string.
    @SerializedName("allowed_output_formats") val allowedOutputFormats: JsonElement?,
) {
    fun allowedFormats(): Set<String> {
        val e = allowedOutputFormats ?: return emptySet()
        val values = when {
            e.isJsonArray -> e.asJsonArray.mapNotNull { v -> v.takeIf { it.isJsonPrimitive }?.asString }
            e.isJsonPrimitive -> e.asString.split(',')
            else -> emptyList()
        }
        return values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }
}

class XtreamCategory(
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("category_name") val categoryName: String?,
)

class XtreamStream(
    val num: String?,
    val name: String?,
    @SerializedName("stream_id") val streamId: String?,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    // String: uns servidores mandam 1, outros "1".
    @SerializedName("tv_archive") val tvArchive: String?,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: String?,
)

class XtreamShortEpg(@SerializedName("epg_listings") val epgListings: List<XtreamEpgListing>?)

class XtreamEpgListing(
    val title: String?,
    @SerializedName("start_timestamp") val startTimestamp: String?,
    @SerializedName("stop_timestamp") val stopTimestamp: String?,
)
