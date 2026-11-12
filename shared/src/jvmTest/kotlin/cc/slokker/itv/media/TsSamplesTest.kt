package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streams a sério (feitos com o ffmpeg, 2 s cada, em `resources/ts`): o leitor tem de dar as faixas e as unidades que o
 * ffmpeg pôs lá, em todos os formatos que os canais usam, não só no H.264 com AAC.
 */
class TsSamplesTest {
    private class Collect : TsListener {
        var tracks: List<TsTrack> = emptyList()
        val samples = HashMap<Int, MutableList<TsSample>>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks = tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples.getOrPut(track.pid) { ArrayList() } += sample
        }
    }

    private fun read(name: String): Collect {
        val bytes = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()
        val c = Collect()
        val d = TsDemuxer(c)
        // Aos bocados de tamanhos estranhos, como chega da rede.
        var at = 0
        var step = 1
        while (at < bytes.size) {
            val n = minOf(step, bytes.size - at)
            d.feed(bytes, at, n)
            at += n
            step = step * 7 % 3001 + 1
        }
        d.flush()
        return c
    }

    private fun check(name: String, vararg expected: Pair<Codec, String?>) {
        val c = read(name)
        assertEquals(expected.toList(), c.tracks.map { it.codec to it.language }, name)
        for (t in c.tracks) {
            val list = c.samples[t.pid].orEmpty()
            assertTrue(list.isNotEmpty(), "$name ${t.codec}: sem unidades")
            assertTrue(list.none { it.discontinuity }, "$name ${t.codec}: descontinuidade")
            assertTrue(list.all { it.pts >= 0 }, "$name ${t.codec}: sem PTS")
            if (t.kind == TrackKind.VIDEO) {
                // 2 s a 25 imagens: 50 unidades, a primeira marcada como ponto de entrada.
                assertEquals(50, list.size, "$name vídeo")
                assertTrue(list.first().randomAccess, "$name: a primeira imagem é um ponto de entrada")
                // Com imagens B, o DTS fica atrás do PTS.
                assertTrue(list.all { it.dts <= it.pts }, "$name: DTS depois do PTS")
            }
        }
    }

    @Test
    fun h264WithAac() = check("h264_aac.ts", Codec.H264 to null, Codec.AAC_ADTS to "por")

    @Test
    fun hevcWithAc3() = check("hevc_ac3.ts", Codec.HEVC to null, Codec.AC3 to "eng")

    @Test
    fun mpeg2WithMp2() = check("mpeg2_mp2.ts", Codec.MPEG2_VIDEO to null, Codec.MPEG_AUDIO to "spa")

    @Test
    fun h264WithEac3() = check("h264_eac3.ts", Codec.H264 to null, Codec.EAC3 to null)

    @Test
    fun dvbStyleAc3AndEac3() = check("dvb_ac3_eac3.ts", Codec.H264 to null, Codec.AC3 to "por", Codec.EAC3 to "eng")
}
