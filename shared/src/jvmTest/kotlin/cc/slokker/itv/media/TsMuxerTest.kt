package cc.slokker.itv.media

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Um fMP4 passado a TS pelo [TsRemuxer] tem de dar, lido pelo [TsDemuxer], o mesmo que o [Mp4Demuxer] dá lido
 * direto: as mesmas faixas, as mesmas amostras com os mesmos tempos.
 */
class TsMuxerTest {
    private class Collect : TsListener {
        var tracks: List<TsTrack> = emptyList()
        val samples = LinkedHashMap<Codec, MutableList<TsSample>>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks = tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples.getOrPut(track.codec) { ArrayList() } += sample
        }
    }

    private fun feedInPieces(bytes: ByteArray, feed: (ByteArray, Int, Int) -> Unit) {
        var at = 0
        var step = 1
        while (at < bytes.size) {
            val n = minOf(step, bytes.size - at)
            feed(bytes, at, n)
            at += n
            step = step * 7 % 3001 + 1
        }
    }

    private fun remux(bytes: ByteArray): ByteArray {
        val ts = ByteArrayOutputStream()
        val r = TsRemuxer { ts.write(it) }
        feedInPieces(bytes) { d, o, n -> r.feed(d, o, n) }
        r.flush()
        assertTrue(r.remuxing)
        return ts.toByteArray()
    }

    private fun check(name: String) {
        val mp4 = javaClass.getResourceAsStream("/mp4/$name")!!.readBytes()
        val direct = Collect()
        Mp4Demuxer(direct).apply { feed(mp4, 0, mp4.size); flush() }
        val ts = remux(mp4)
        assertEquals(0, ts.size % 188)
        val viaTs = Collect()
        TsDemuxer(viaTs).apply { feed(ts, 0, ts.size); flush() }
        assertEquals(direct.tracks.map { it.codec to it.language }, viaTs.tracks.map { it.codec to it.language })
        for ((codec, expected) in direct.samples) {
            val got = viaTs.samples.getValue(codec)
            assertEquals(expected.size, got.size, "$name $codec")
            expected.zip(got).forEachIndexed { i, (e, g) ->
                assertEquals(e.pts, g.pts, "$name $codec #$i pts")
                assertEquals(if (e.dts == e.pts) -1 else e.dts, if (g.dts == g.pts) -1 else g.dts, "$name $codec #$i dts")
                assertTrue(e.data.contentEquals(g.data), "$name $codec #$i data")
            }
            // O vídeo começa numa imagem-chave marcada como tal.
            if (codec == Codec.H264 || codec == Codec.HEVC) assertTrue(got.first().randomAccess)
        }
    }

    @Test
    fun h264AndAac() = check("h264_aac.mp4")

    @Test
    fun hevcAndEac3() = check("hevc_eac3.mp4")

    @Test
    fun hlsSegmentsWithTheInitAgain() = check("h264_aac_hls.mp4")

    @Test
    fun aTransportStreamPassesUntouched() {
        val bytes = javaClass.getResourceAsStream("/mp4/h264_aac.ts")!!.readBytes()
        val out = ByteArrayOutputStream()
        val r = TsRemuxer { out.write(it) }
        feedInPieces(bytes) { d, o, n -> r.feed(d, o, n) }
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }

    @Test
    fun theClockIsInTheTablesAndReadersCanStartAtAnyKeyframe() {
        val mp4 = javaClass.getResourceAsStream("/mp4/h264_aac.mp4")!!.readBytes()
        val ts = remux(mp4)
        // A meio do stream: o PAT e o PMT estão lá antes da imagem-chave seguinte.
        val c = Collect()
        val half = ts.size / 2 / 188 * 188
        TsDemuxer(c).apply { feed(ts, half, ts.size - half); flush() }
        assertTrue(c.samples.getValue(Codec.H264).isNotEmpty())
        assertTrue(c.samples.getValue(Codec.AAC_ADTS).isNotEmpty())
    }
}
