@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import cc.slokker.itv.media.HlsPlaylist
import cc.slokker.itv.media.HlsSegment
import java.io.IOException
import java.io.InputStream

/** Um stream que este motor não toca (o leitor passa a outro). */
class UnsupportedStream(why: String) : IOException(why)

/**
 * Um canal HLS lido como um só MPEG-TS: a playlist (a principal escolhe a qualidade mais alta) e os segmentos, um
 * atrás do outro, pela mesma ligação do resto ([source], uma de cada vez). Num direto começa três segmentos antes do
 * fim e vai relendo a playlist. Os segmentos fMP4 e os cifrados não: [UnsupportedStream].
 */
class HlsInputStream(private val source: DataSource.Factory, private val url: String) : InputStream() {
    private var mediaUrl: String? = null
    private var nextSequence = Long.MIN_VALUE
    private val pending = ArrayDeque<HlsSegment>()
    private var current: InputStream? = null
    private var ended = false
    private var targetMs = 6_000L
    private var lastReload = 0L

    private fun fetch(u: String): String =
        DataSourceInputStream(source.createDataSource(), DataSpec(Uri.parse(u))).use { it.readBytes().decodeToString() }

    private fun reload() {
        var base = mediaUrl ?: url
        var playlist = HlsPlaylist.parse(fetch(base), base)
        if (playlist.isMaster) {
            // A qualidade mais alta (é uma TV); as outras ficam para quando houver adaptação.
            val variant = playlist.variants.maxBy { it.bandwidth }
            base = variant.uri
            playlist = HlsPlaylist.parse(fetch(base), base)
        }
        mediaUrl = base
        playlist.unsupported?.let { throw UnsupportedStream("HLS: $it") }
        targetMs = (playlist.targetDuration * 1000).toLong().coerceIn(1_000, 20_000)
        ended = playlist.ended
        val segments = playlist.segments
        if (nextSequence == Long.MIN_VALUE && segments.isNotEmpty()) {
            nextSequence = segments[if (ended) 0 else (segments.size - 3).coerceAtLeast(0)].sequence
        }
        for (s in segments) if (s.sequence >= nextSequence && pending.none { it.sequence == s.sequence }) pending.addLast(s)
        lastReload = SystemClock.elapsedRealtime()
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            current?.let { stream ->
                val n = stream.read(b, off, len)
                if (n >= 0) return n
                stream.close()
                current = null
            }
            if (pending.isEmpty()) {
                if (ended && nextSequence != Long.MIN_VALUE) return -1
                // Um direto: a playlist volta a ler-se a meio da duração de um segmento.
                val wait = targetMs / 2 - (SystemClock.elapsedRealtime() - lastReload)
                if (wait > 0 && nextSequence != Long.MIN_VALUE) Thread.sleep(wait)
                reload()
                continue
            }
            val segment = pending.removeFirst()
            nextSequence = segment.sequence + 1
            current = DataSourceInputStream(source.createDataSource(), DataSpec(Uri.parse(segment.uri))).also { it.open() }
        }
    }

    override fun close() {
        runCatching { current?.close() }
        current = null
    }
}
