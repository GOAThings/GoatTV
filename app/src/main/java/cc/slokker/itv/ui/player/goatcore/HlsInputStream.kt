@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import cc.slokker.itv.media.HlsPlaylist
import cc.slokker.itv.media.HlsRange
import cc.slokker.itv.media.HlsSegment
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Um stream que este motor não toca (o leitor passa a outro). */
class UnsupportedStream(why: String) : IOException(why)

/**
 * Um canal HLS lido como um só stream: a playlist (a principal escolhe a qualidade mais alta) e os segmentos, um
 * atrás do outro, pela mesma ligação do resto ([source], uma de cada vez). Num direto começa três segmentos antes do
 * fim e vai relendo a playlist. Nos fMP4, o segmento de inicialização vai à frente sempre que muda (fica um MP4
 * fragmentado seguido). Os segmentos cifrados com AES-128 decifram-se aqui; as outras cifras (SAMPLE-AES…) não:
 * [UnsupportedStream].
 */
class HlsInputStream(
    private val source: DataSource.Factory,
    private val url: String,
    /** Num VOD (catch-up em HLS), o ponto onde começar: o segmento que o tem. */
    private val startMs: Long = 0,
    /** Num VOD aberto a meio: onde começa (ms) o segmento por onde se começou. */
    private val onStart: (Long) -> Unit = {},
) : InputStream() {
    private var mediaUrl: String? = null
    private var nextSequence = Long.MIN_VALUE
    private val pending = ArrayDeque<HlsSegment>()
    private var current: InputStream? = null
    private var ended = false
    private var targetMs = 6_000L
    private var lastReload = 0L
    // A última chave lida (a mesma serve muitos segmentos seguidos).
    private var keyUri: String? = null
    private var keyBytes: ByteArray? = null
    // O segmento de inicialização que já foi (o próximo que for diferente vai antes do seu segmento).
    private var sentMap: HlsRange? = null

    private fun fetchBytes(u: String): ByteArray =
        DataSourceInputStream(source.createDataSource(), DataSpec(Uri.parse(u))).use { it.readBytes() }

    private fun open(r: HlsRange): InputStream {
        val spec = DataSpec.Builder().setUri(Uri.parse(r.uri)).setPosition(r.offset).setLength(if (r.length < 0) C.LENGTH_UNSET.toLong() else r.length).build()
        return DataSourceInputStream(source.createDataSource(), spec).also { it.open() }
    }

    private fun fetch(u: String): String = fetchBytes(u).decodeToString()

    private fun keyFor(uri: String): ByteArray {
        if (uri != keyUri) {
            val bytes = fetchBytes(uri)
            if (bytes.size != 16) throw UnsupportedStream("HLS: AES-128 key of ${bytes.size} bytes")
            keyUri = uri
            keyBytes = bytes
        }
        return keyBytes!!
    }

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
            var first = if (ended) 0 else (segments.size - 3).coerceAtLeast(0)
            if (ended && startMs > 0) {
                // O segmento onde fica o ponto pedido (pela soma das durações).
                var at = 0L
                for ((i, s) in segments.withIndex()) {
                    val d = (s.duration * 1000).toLong()
                    if (at + d > startMs || i == segments.lastIndex) {
                        first = i
                        break
                    }
                    at += d
                }
                onStart(at)
            }
            nextSequence = segments[first].sequence
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
            val segment = pending.first()
            // O moov antes dos segmentos que o usam (lido inteiro: é pequeno, e a ligação é uma de cada vez).
            segment.map?.takeIf { !it.sameAs(sentMap) }?.let { map ->
                sentMap = map
                current = open(map).use { it.readBytes() }.inputStream()
                continue
            }
            pending.removeFirst()
            nextSequence = segment.sequence + 1
            // A chave lê-se antes de abrir o segmento: uma ligação de cada vez.
            val cipher = segment.key?.let { key ->
                Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFor(key.uri), "AES"), IvParameterSpec(key.iv))
                }
            }
            val raw = open(segment.range)
            current = cipher?.let { CipherInputStream(raw, it) } ?: raw
        }
    }

    override fun close() {
        runCatching { current?.close() }
        current = null
    }
}
