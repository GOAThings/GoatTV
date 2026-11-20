package cc.slokker.itv.media

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O som solto das rádios (MP3 por HTTP) e o áudio empacotado de um HLS só de som, a chegar aos bocados. */
class RawAudioTest {
    private class Collect : TsListener {
        val tracks = ArrayList<TsTrack>()
        val samples = ArrayList<TsSample>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks.clear()
            this.tracks += tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples += sample
        }
    }

    /** Um bloco MP3 (MPEG-1 camada III, 128 kb/s, 44,1 kHz, estéreo): 417 bytes, 1152 amostras. */
    private fun mp3Frame(): ByteArray = ByteArray(417).also {
        it[0] = 0xFF.toByte()
        it[1] = 0xFB.toByte()
        it[2] = 0x90.toByte()
        it[3] = 0x44
    }

    /** A etiqueta ID3 do HLS com o tempo do primeiro bloco (o PRIV da Apple, 90 kHz). */
    private fun id3(pts: Long): ByteArray {
        val owner = "com.apple.streaming.transportStreamTimestamp".encodeToByteArray() + 0
        val stamp = ByteArray(8) { i -> (pts shr (56 - 8 * i)).toByte() }
        val body = owner + stamp
        fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
        val frame = "PRIV".encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame
    }

    private fun feed(stream: ByteArray, chunk: Int): Collect {
        val c = Collect()
        val d = StreamDemuxer(c)
        var at = 0
        while (at < stream.size) {
            val n = minOf(chunk, stream.size - at)
            d.feed(stream, at, n)
            at += n
        }
        d.flush()
        return c
    }

    @Test
    fun icecastMp3InOddChunks() {
        val out = ByteArrayOutputStream()
        repeat(20) { out.write(mp3Frame()) }
        val c = feed(out.toByteArray(), 1000)
        assertEquals(listOf(Codec.MPEG_AUDIO), c.tracks.map { it.codec })
        assertEquals(TrackKind.AUDIO, c.tracks.single().kind)
        assertEquals(20, c.samples.size)
        assertTrue(c.samples.all { it.data.size == 417 })
        // O tempo pelas amostras, desde 0.
        assertEquals(0L, c.samples.first().pts)
        assertEquals(19 * 1152L * 90_000 / 44_100, c.samples.last().pts)
        // O formato diz que é MP3 (camada III), não o MP2 da TV.
        val reader = AudioFrameReader(Codec.MPEG_AUDIO)
        reader.frames(c.samples.first().data, 0, false)
        assertEquals(3, reader.format?.mpegLayer)
        assertEquals(44_100, reader.format?.sampleRate)
    }

    @Test
    fun packedHlsSegmentsBackToBack() {
        val out = ByteArrayOutputStream()
        val step = 5 * 1152L * 90_000 / 44_100
        for (seg in 0 until 3) {
            out.write(id3(90_000 + seg * step))
            repeat(5) { out.write(mp3Frame()) }
        }
        val c = feed(out.toByteArray(), 333)
        assertEquals(15, c.samples.size)
        assertEquals(90_000L, c.samples[0].pts)
        // Cada segmento recomeça no tempo da sua etiqueta.
        assertEquals(90_000L + step, c.samples[5].pts)
        assertEquals(90_000L + 2 * step, c.samples[10].pts)
    }

    @Test
    fun tsStillGoesToTheTsDemuxer() {
        val ts = javaClass.getResourceAsStream("/mp4/h264_aac.ts")!!.readBytes()
        val c = feed(ts, 4096)
        assertTrue(c.tracks.any { it.kind == TrackKind.VIDEO })
    }
}
