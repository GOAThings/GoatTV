package cc.slokker.itv.ui.common

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.SourceException
import cc.slokker.itv.ui.i18n.Strings
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Explica porque é que a lista [name] não carregou. */
fun explainPlaylistError(s: Strings, name: String, e: Throwable): ErrorInfo {
    val cause = generateSequence(e) { it.cause }.toList()
    fun <T : Throwable> find(type: Class<T>) = cause.firstOrNull { type.isInstance(it) }
    val source = e as? SourceException
    return when {
        source != null -> when (source.kind) {
            SourceException.Kind.BAD_LOGIN -> s.errPlaylistBadLogin(name)
            SourceException.Kind.INACTIVE -> s.errPlaylistInactive(name, source.status.orEmpty())
            SourceException.Kind.HTTP -> s.errPlaylistHttp(name, source.httpCode ?: 0)
            SourceException.Kind.BAD_RESPONSE -> s.errPlaylistBadResponse(name)
            SourceException.Kind.NO_URL, SourceException.Kind.BAD_URL -> s.errPlaylistBadUrl(name)
        }
        find(UnknownHostException::class.java) != null -> s.errPlaylistUnknownHost(name)
        find(SSLException::class.java) != null -> s.errPlaylistSsl(name)
        find(SocketTimeoutException::class.java) != null || find(ConnectException::class.java) != null -> s.errPlaylistUnreachable(name)
        else -> s.errPlaylistOther(name)
    }.withDetail(s, e)
}

/**
 * Explica um erro do player. [connections] (em uso, máximo) vem do servidor Xtream quando o erro é de rede,
 * para distinguir "conta ocupada" de "canal em baixo".
 */
fun explainPlaybackError(
    s: Strings,
    error: PlaybackException,
    data: PlaylistContent,
    connections: Pair<Int, Int>?,
    prefs: Prefs,
    format: String?,
): ErrorInfo {
    val isNetwork = error.errorCode in 2000..2999
    val isXtream = data.playlist.type == PlaylistType.XTREAM
    val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    val limit = connections?.second ?: data.account?.maxConnections
    val mime = (error.cause as? MediaCodecRenderer.DecoderInitializationException)?.mimeType
    val info = when {
        isAccountBusy(data, connections) -> s.errAccountBusy(limit, sure = true)
        httpCode == 404 -> s.errChannelGone
        httpCode == 429 -> s.errChannelRateLimited
        httpCode != null && httpCode >= 500 -> s.errProviderServer(httpCode)
        // A conta está livre (ou não é Xtream): o problema é deste canal.
        httpCode != null && httpCode in 400..499 && (!isXtream || connections != null) -> s.errChannelRefused(httpCode)
        isXtream && isNetwork -> s.errAccountBusy(limit, sure = false)
        isNetwork -> s.errChannelUnreachable
        error.errorCode in 3000..3999 -> s.errStreamFormat(format)
        error.errorCode in 5000..5999 || mime?.startsWith("audio/") == true ->
            s.errAudio(codecName(mime), prefs.audioPassthrough, prefs.audioDecoder == Prefs.Decoder.SOFTWARE)
        error.errorCode in 4000..4999 -> s.errVideoDecoder(codecName(mime), prefs.decoder == Prefs.Decoder.SOFTWARE)
        else -> s.errCantPlay
    }
    val code = listOfNotNull(error.errorCodeName, httpCode?.let { "HTTP $it" }).joinToString(" · ")
    return info.copy(detail = s.technical(code))
}

/** O servidor Xtream diz que todas as ligações da conta estão em uso. */
fun isAccountBusy(data: PlaylistContent, connections: Pair<Int, Int>?): Boolean =
    data.playlist.type == PlaylistType.XTREAM && connections != null && connections.first >= connections.second

private fun ErrorInfo.withDetail(s: Strings, e: Throwable): ErrorInfo =
    if (detail != null) this else copy(detail = s.technical(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")))

/** "video/hevc" → "HEVC (H.265)", para quem lê o erro. */
private fun codecName(mime: String?): String? = when (mime) {
    null -> null
    "video/hevc" -> "HEVC (H.265)"
    "video/avc" -> "H.264"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/mpeg2" -> "MPEG-2"
    "audio/ac3" -> "Dolby Digital"
    "audio/eac3" -> "Dolby Digital Plus"
    "audio/eac3-joc" -> "Dolby Atmos"
    "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
    "audio/mp4a-latm" -> "AAC"
    "audio/mpeg", "audio/mpeg-L2" -> "MPEG"
    else -> mime.substringAfter('/').uppercase()
}
