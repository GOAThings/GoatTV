package cc.slokker.itv.data.m3u

import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.LimitedReader
import cc.slokker.itv.data.SafeInput
import cc.slokker.itv.data.SourceException
import cc.slokker.itv.data.boundedLines
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
            SafeInput.outOfMemoryAsIo {
                val result = LimitedReader(res.body.charStream(), SafeInput.MAX_M3U_BYTES).use { M3uParser.parse(it.boundedLines()) }
                PlaylistContent.build(
                    playlist = playlist,
                    categories = result.categories,
                    channels = result.channels,
                    guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: result.epgUrl,
                )
            }
        }
    }
}
