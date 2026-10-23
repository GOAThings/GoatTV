package cc.slokker.itv.data.m3u

import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class M3uSource(private val http: OkHttpClient) {

    suspend fun load(playlist: Playlist): PlaylistContent = withContext(Dispatchers.IO) {
        val url = playlist.url?.trim().orEmpty()
        if (url.isEmpty()) throw SourceException(SourceException.Kind.NO_URL, I18n.s.m3uNoUrl)
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw SourceException(SourceException.Kind.BAD_URL, I18n.s.m3uBadUrl)
        }
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw SourceException(SourceException.Kind.HTTP, I18n.s.m3uHttp(res.code), httpCode = res.code)
            val result = res.body.charStream().buffered().useLines { M3uParser.parse(it) }
            PlaylistContent.build(
                playlist = playlist,
                categories = result.categories,
                channels = result.channels,
                guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: result.epgUrl,
            )
        }
    }
}
