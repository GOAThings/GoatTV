package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Legendas CEA-608 dentro do H.264 (resources/ts/h264_cea608.ts, SEI GA94 com um par por imagem a 30 fps): o que o
 * ecrã mostra e quando. Os tempos e os textos são os que o descodificador do ffmpeg (ccaption_dec) dá do mesmo ficheiro.
 */
class ClosedCaptionsTest {
    @Test
    fun popOnClearAndRollUp() {
        val bytes = javaClass.getResourceAsStream("/ts/h264_cea608.ts")!!.readBytes()
        val decoder = Cea608Decoder()
        val shown = ArrayList<Pair<Long, List<String>>>()
        var first = -1L
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.kind != TrackKind.VIDEO) return
                if (first < 0) first = sample.pts
                decoder.feed(ClosedCaptions.field1(sample.data, hevc = false))?.let { shown += (sample.pts - first) * 1000 / 90_000 to it }
            }
        })
        d.feed(bytes, 0, bytes.size)
        d.flush()
        // Os tempos (ms, ±2 do arredondamento): o pop-on aos 0,733 e 2,6 s, o apagar aos 4,0 s e o roll-up a aparecer
        // aos 4,8 s, quando chega a primeira letra (o roll-up escreve-se no ecrã).
        val expected = listOf(733L, 2_600L, 4_000L, 4_800L)
        for ((k, ms) in expected.withIndex()) assertEquals(true, kotlin.math.abs(shown[k].first - ms) <= 2, "aparecimento $k: ${shown[k].first} ms")
        assertEquals(listOf("HELLO WORLD"), shown[0].second)
        assertEquals(listOf("CAFé♪", "SECOND LINE"), shown[1].second)
        assertEquals(emptyList(), shown[2].second)
        assertEquals(listOf("ROLL ONE", "ROLL TWO"), shown.last().second)
    }

    /** O mesmo vídeo em MPEG-2 (o ffmpeg passou as legendas para os user_data da imagem): o mesmo texto. */
    @Test
    fun mpeg2UserData() {
        val bytes = javaClass.getResourceAsStream("/ts/mpeg2_cea608.ts")!!.readBytes()
        val decoder = Cea608Decoder()
        val shown = ArrayList<List<String>>()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.kind != TrackKind.VIDEO) return
                decoder.feed(ClosedCaptions.field1Mpeg2(sample.data))?.let { shown += it }
            }
        })
        d.feed(bytes, 0, bytes.size)
        d.flush()
        assertEquals(listOf("HELLO WORLD"), shown[0])
        assertEquals(listOf("CAFé♪", "SECOND LINE"), shown[1])
        assertEquals(listOf("ROLL ONE", "ROLL TWO"), shown.last())
    }
}
