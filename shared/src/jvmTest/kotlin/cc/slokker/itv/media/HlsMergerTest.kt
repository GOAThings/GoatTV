package cc.slokker.itv.media

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Um HLS com o áudio à parte: o vídeo em segmentos TS só com vídeo e o áudio em segmentos "empacotados" (ID3 com o
 * tempo + blocos ADTS), feitos a partir do mesmo `h264_aac.ts`. Juntos pelo [HlsMerger], o TS que sai tem de ter as
 * mesmas imagens e o mesmo áudio, com os mesmos tempos.
 */
class HlsMergerTest {
    private class Collect : TsListener {
        val tracks = ArrayList<TsTrack>()
        val samples = LinkedHashMap<Codec, MutableList<TsSample>>()
        val order = ArrayList<Long>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks.clear()
            this.tracks += tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples.getOrPut(track.codec) { ArrayList() } += sample
            order += if (sample.dts >= 0) sample.dts else sample.pts
        }
    }

    private fun original(): Collect {
        val ts = javaClass.getResourceAsStream("/mp4/h264_aac.ts")!!.readBytes()
        return Collect().also { c -> TsDemuxer(c).apply { feed(ts, 0, ts.size); flush() } }
    }

    /** Segmentos de ~2 s de um lado: os do vídeo em TS (um muxer só, como um servidor), os do áudio empacotados. */
    private fun videoSegments(video: List<TsSample>, track: TsTrack): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var current = ByteArrayOutputStream()
        val muxer = TsMuxer { current.write(it) }
        muxer.onTracks(listOf(track))
        var start = video.first().dts.takeIf { it >= 0 } ?: video.first().pts
        for (s in video) {
            val t = if (s.dts >= 0) s.dts else s.pts
            if (s.randomAccess && t - start >= 180_000 && current.size() > 0) {
                out += current.toByteArray()
                current = ByteArrayOutputStream()
                start = t
            }
            muxer.onSample(track, s)
        }
        out += current.toByteArray()
        return out
    }

    private fun id3(pts: Long): ByteArray {
        val owner = "com.apple.streaming.transportStreamTimestamp".encodeToByteArray()
        val body = owner + byteArrayOf(0) + ByteArray(8) { (pts shr (56 - 8 * it)).toByte() }
        val frame = "PRIV".encodeToByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
        return "ID3".encodeToByteArray() + byteArrayOf(4, 0, 0) + syncsafe(frame.size) + frame
    }

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())

    private fun audioSegments(audio: List<TsSample>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < audio.size) {
            val first = audio[i].pts
            val seg = ByteArrayOutputStream()
            seg.write(id3(first))
            while (i < audio.size && audio[i].pts - first < 180_000) seg.write(audio[i++].data)
            out += seg.toByteArray()
        }
        return out
    }

    @Test
    fun videoAndPackedAudioBecomeOneStream() {
        val o = original()
        val video = o.samples.getValue(Codec.H264)
        val audio = o.samples.getValue(Codec.AAC_ADTS)
        val v = videoSegments(video, o.tracks.first { it.codec == Codec.H264 })
        val a = audioSegments(audio)
        val ts = ByteArrayOutputStream()
        val merger = HlsMerger { ts.write(it) }
        // Como o leitor faz: um segmento de cada lado, à vez.
        for (n in 0 until maxOf(v.size, a.size)) {
            v.getOrNull(n)?.let(merger::video)
            a.getOrNull(n)?.let(merger::audio)
        }
        merger.flush()
        val merged = Collect()
        ts.toByteArray().let { TsDemuxer(merged).apply { feed(it, 0, it.size); flush() } }
        assertEquals(setOf(Codec.H264, Codec.AAC_ADTS), merged.tracks.map { it.codec }.toSet())
        val gotVideo = merged.samples.getValue(Codec.H264)
        assertEquals(video.map { it.pts }, gotVideo.map { it.pts })
        assertTrue(video.zip(gotVideo).all { (x, y) -> x.data.contentEquals(y.data) })
        // O áudio: os mesmos bytes (agora um bloco por PES) e o mesmo tempo no primeiro.
        val gotAudio = merged.samples.getValue(Codec.AAC_ADTS)
        assertEquals(audio.first().pts, gotAudio.first().pts)
        assertTrue(audio.flatMap { it.data.toList() }.toByteArray().contentEquals(gotAudio.flatMap { it.data.toList() }.toByteArray()))
        // Sai intercalado: nunca recua mais de um segmento.
        val back = merged.order.zipWithNext().maxOf { (x, y) -> x - y }
        assertTrue(back < 180_000, "recuou $back")
    }

    @Test
    fun packedAudioWithoutTimestampContinuesFromTheLastSegment() {
        val o = original()
        val audio = o.samples.getValue(Codec.AAC_ADTS)
        val c = Collect()
        val d = PackedAudioDemuxer(c)
        val half = audio.size / 2
        d.feed(id3(audio.first().pts) + audio.take(half).flatMap { it.data.toList() }.toByteArray())
        d.feed(audio.drop(half).flatMap { it.data.toList() }.toByteArray())
        val got = c.samples.getValue(Codec.AAC_ADTS)
        // O tempo do primeiro bloco do 2.º segmento é o do PES original que começa aí, contado pelas amostras (os PTS
        // do ficheiro não batem à amostra: margem de 1 ms).
        val firstOfSecond = got.first { it.pts >= audio[half].pts - 90 }
        assertTrue(kotlin.math.abs(firstOfSecond.pts - audio[half].pts) <= 90, "${firstOfSecond.pts} vs ${audio[half].pts}")
    }
}
