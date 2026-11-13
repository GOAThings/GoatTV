@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import cc.slokker.itv.media.HlsMerger
import cc.slokker.itv.media.HlsPlaylist
import cc.slokker.itv.media.HlsRange
import cc.slokker.itv.media.HlsSegment
import cc.slokker.itv.media.HlsVariant
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Um stream que este motor não toca (o leitor passa a outro). */
class UnsupportedStream(why: String) : IOException(why)

/**
 * Um canal HLS lido como um só stream: a playlist (a principal escolhe a qualidade mais alta) e os segmentos, um
 * atrás do outro, pela mesma ligação do resto ([source], uma de cada vez). Num direto começa três segmentos antes do
 * fim e vai relendo a playlist. Nos fMP4, o segmento de inicialização vai à frente sempre que muda (fica um MP4
 * fragmentado seguido). Os segmentos cifrados com AES-128 decifram-se aqui; as outras cifras (SAMPLE-AES…) não:
 * [UnsupportedStream]. Com o áudio numa playlist à parte, lê-se um segmento de cada, à vez, e sai um TS só com os dois
 * ([HlsMerger]).
 */
class HlsInputStream(
    private val source: DataSource.Factory,
    private val url: String,
    /** Num VOD (catch-up em HLS), o ponto onde começar: o segmento que o tem. */
    private val startMs: Long = 0,
    /** Num VOD aberto a meio: onde começa (ms) o segmento por onde se começou. */
    private val onStart: (Long) -> Unit = {},
) : InputStream() {
    private var current: InputStream? = null
    // A última chave lida (a mesma serve muitos segmentos seguidos).
    private var keyUri: String? = null
    private var keyBytes: ByteArray? = null

    // As qualidades da playlist principal (da mais baixa à mais alta) e a que se lê; ver [measured].
    private var variants: List<HlsVariant> = emptyList()
    private var level = -1
    private var slow = 0
    private var fast = 0

    private val video = Lane(url, main = true)
    /** As faixas de áudio à parte (EXT-X-MEDIA com URI, uma por língua) e quem as junta ao vídeo; vazio quando vem dentro dos segmentos. */
    private var audios: List<Lane> = emptyList()
    private var merger: HlsMerger? = null
    private val merged = ByteArrayOutputStream()

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

    /**
     * Uma playlist de segmentos lida em sequência: a do vídeo ([main], que pode ser a principal e muda de qualidade)
     * ou a do áudio à parte. [seconds]: quanto já se leu (é por aí que se escolhe o lado a ler a seguir).
     */
    private inner class Lane(var mediaUrl: String, val main: Boolean, val language: String? = null) {
        var nextSequence = Long.MIN_VALUE
        val pending = ArrayDeque<HlsSegment>()
        var ended = false
        var targetMs = 6_000L
        var lastReload = 0L
        // O segmento de inicialização que já foi (o próximo que for diferente vai antes do seu segmento).
        var sentMap: HlsRange? = null
        var seconds = 0.0
        /** Mudou-se de qualidade: se a nova não continuar a numeração, volta-se ao ponto do direto. */
        var resync = false
        /** Num direto: quanto antes do fim se começou (ms), para o áudio começar no mesmo ponto que o vídeo. */
        var liveOffsetMs = -1L

        fun reload() {
            var base = mediaUrl
            var playlist = HlsPlaylist.parse(fetch(base), base)
            if (playlist.isMaster) {
                playlist.unsupported?.let { throw UnsupportedStream("HLS: $it") }
                // Começa na qualidade mais alta (é uma TV); desce e sobe com a rede (ver [measured]).
                variants = playlist.variants.sortedBy { it.bandwidth }
                level = variants.lastIndex
                base = variants[level].uri
                // O áudio à parte: as línguas todas (até [MAX_AUDIO]), para o menu do áudio as ter como num TS.
                variants[level].audio.take(MAX_AUDIO).takeIf { it.isNotEmpty() }?.let { list ->
                    audios = list.map { Lane(it.uri, main = false, language = it.code) }
                    merger = HlsMerger { merged.write(it) }
                }
                playlist = HlsPlaylist.parse(fetch(base), base)
            }
            mediaUrl = base
            playlist.unsupported?.let { throw UnsupportedStream("HLS: $it") }
            targetMs = (playlist.targetDuration * 1000).toLong().coerceIn(1_000, 20_000)
            ended = playlist.ended
            val segments = playlist.segments
            if (nextSequence == Long.MIN_VALUE && segments.isNotEmpty()) {
                nextSequence = segments[firstSegment(segments)].sequence
            }
            if (resync) {
                resync = false
                if (!ended && segments.isNotEmpty() && segments.none { it.sequence == nextSequence }) {
                    nextSequence = segments[(segments.size - 3).coerceAtLeast(0)].sequence
                }
            }
            for (s in segments) if (s.sequence >= nextSequence && pending.none { it.sequence == s.sequence }) pending.addLast(s)
            lastReload = SystemClock.elapsedRealtime()
        }

        /** Por onde começar: num VOD o do ponto pedido; num direto três antes do fim (o áudio, à mesma distância). */
        private fun firstSegment(segments: List<HlsSegment>): Int {
            if (ended) {
                if (startMs <= 0) return 0
                // O segmento onde fica o ponto pedido (pela soma das durações).
                var at = 0L
                for ((i, s) in segments.withIndex()) {
                    val d = (s.duration * 1000).toLong()
                    if (at + d > startMs || i == segments.lastIndex) {
                        if (main) onStart(at)
                        return i
                    }
                    at += d
                }
                return 0
            }
            if (main) {
                val first = (segments.size - 3).coerceAtLeast(0)
                liveOffsetMs = segments.drop(first).sumOf { (it.duration * 1000).toLong() }
                return first
            }
            val offset = video.liveOffsetMs.takeIf { it >= 0 } ?: return (segments.size - 3).coerceAtLeast(0)
            var fromEnd = 0L
            for (i in segments.indices.reversed()) {
                fromEnd += (segments[i].duration * 1000).toLong()
                if (fromEnd >= offset) return i
            }
            return 0
        }

        /** Um VOD lido até ao fim. */
        val done: Boolean get() = ended && pending.isEmpty() && nextSequence != Long.MIN_VALUE

        /** O segmento seguinte (com o init segment à frente, se mudou), decifrado; null no fim de um VOD. */
        fun next(): ByteArray? {
            while (true) {
                if (pending.isEmpty()) {
                    if (ended && nextSequence != Long.MIN_VALUE) return null
                    // Um direto: a playlist volta a ler-se a meio da duração de um segmento.
                    val wait = targetMs / 2 - (SystemClock.elapsedRealtime() - lastReload)
                    if (wait > 0 && nextSequence != Long.MIN_VALUE) Thread.sleep(wait)
                    reload()
                    continue
                }
                val segment = pending.removeFirst()
                nextSequence = segment.sequence + 1
                // O moov antes dos segmentos que o usam (lido inteiro: é pequeno, e a ligação é uma de cada vez).
                val map = segment.map?.takeIf { !it.sameAs(sentMap) }?.let { m ->
                    sentMap = m
                    open(m).use { it.readBytes() }
                }
                // A chave lê-se antes de abrir o segmento: uma ligação de cada vez.
                val cipher = segment.key?.let { key ->
                    Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        init(Cipher.DECRYPT_MODE, SecretKeySpec(keyFor(key.uri), "AES"), IvParameterSpec(key.iv))
                    }
                }
                // O segmento inteiro de uma vez: o tempo que demora é o da rede (lido aos poucos contava a espera do motor).
                val started = SystemClock.elapsedRealtime()
                val raw = open(segment.range).use { it.readBytes() }
                if (main) measured(raw.size, SystemClock.elapsedRealtime() - started, segment.duration)
                seconds += segment.duration
                val data = cipher?.doFinal(raw) ?: raw
                return if (map != null) map + data else data
            }
        }
    }

    /**
     * Um segmento de [seconds] chegou em [ms]: a rede não chega (demorou mais de 80 % do que dura, duas vezes, ou mais
     * do que dura uma vez) → a qualidade que caiba; folga grande quatro vezes seguidas → uma acima.
     */
    private fun measured(bytes: Int, ms: Long, seconds: Double) {
        if (variants.size < 2 || seconds <= 0) return
        val ratio = ms / (seconds * 1000)
        val bps = bytes * 8 * 1000.0 / ms.coerceAtLeast(1)
        if (ratio > 1.0 || (ratio > 0.8 && ++slow >= 2)) {
            val fits = variants.indexOfLast { it.bandwidth <= bps * 0.7 }.coerceAtLeast(0)
            if (fits < level) switchTo(fits) else slow = 0
            return
        }
        if (ratio <= 0.8) slow = 0
        if (level < variants.lastIndex && ratio < 0.3 && bps > variants[level + 1].bandwidth * 1.5) {
            if (++fast >= 4) switchTo(level + 1)
        } else {
            fast = 0
        }
    }

    private fun switchTo(next: Int) {
        Log.i("GoatCore", "HLS ${variants[level].bandwidth / 1000} → ${variants[next].bandwidth / 1000} kbit/s")
        level = next
        video.mediaUrl = variants[next].uri
        slow = 0
        fast = 0
        video.pending.clear()
        video.resync = true
        video.lastReload = 0
    }

    /** O pedaço seguinte do stream: um segmento (ou, com o áudio à parte, o que os dois já juntaram); null no fim. */
    private fun nextChunk(): ByteArray? {
        val m = merger
        if (m == null || audios.isEmpty()) {
            val data = video.next() ?: return null
            // A playlist principal pode ter dito agora que o áudio vem à parte: este já vai pelo junta-dois.
            merger?.let { mm ->
                mm.video(data)
                return takeMerged() ?: nextChunk()
            }
            return data
        }
        while (true) {
            // O lado que vai mais atrás lê o segmento seguinte (o vídeo primeiro, num empate); um que acabou deixa os
            // outros irem até ao fim.
            val lane = (listOf(video) + audios).filter { !it.done }.minByOrNull { it.seconds }
            if (lane == null) {
                m.flush()
                return takeMerged()
            }
            val data = lane.next()
            if (data != null) {
                if (lane === video) m.video(data) else m.audio(data, audios.indexOf(lane), lane.language)
            }
            m.unsupported?.let { throw UnsupportedStream(it) }
            takeMerged()?.let { return it }
        }
    }

    private fun takeMerged(): ByteArray? {
        if (merged.size() == 0) return null
        return merged.toByteArray().also { merged.reset() }
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
            current = (nextChunk() ?: return -1).inputStream()
        }
    }

    override fun close() {
        runCatching { current?.close() }
        current = null
    }

    private companion object {
        /** Mais línguas à parte seriam mais pedidos por segmento, na única ligação. */
        const val MAX_AUDIO = 4
    }
}
