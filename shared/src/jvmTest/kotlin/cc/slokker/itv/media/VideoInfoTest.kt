package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O SPS de vídeos a sério (feitos com o ffmpeg em resources/ts, com o que o ffprobe diz deles): o tamanho que se vê,
 * a cadência, a profundidade e a cor, que decidem se a TV muda para HDR.
 */
class VideoInfoTest {
    private fun info(name: String, hevc: Boolean): VideoInfo {
        val bytes = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()
        val reader = NalReader(hevc)
        var sets: List<ByteArray> = emptyList()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.kind != TrackKind.VIDEO || sets.isNotEmpty()) return
                sets = reader.unit(sample.data).parameterSets
            }
        })
        d.feed(bytes, 0, bytes.size)
        d.flush()
        // O SPS: tipo 7 no H.264, 33 no HEVC.
        val sps = sets.first { if (hevc) ((it[0].toInt() shr 1) and 0x3F) == 33 else (it[0].toInt() and 0x1F) == 7 }
        return (if (hevc) VideoInfo.hevc(sps) else VideoInfo.h264(sps))!!
    }

    @Test
    fun hevcHdr10() {
        val v = info("hevc_hdr10.ts", hevc = true)
        assertEquals(listOf(1920, 1080, 10), listOf(v.width, v.height, v.bitDepth))
        assertEquals(25f, v.fps)
        assertEquals(listOf(9, 16, 9), listOf(v.primaries, v.transfer, v.matrix))
        assertTrue(v.isHdr10)
        assertFalse(v.fullRange)
    }

    @Test
    fun h264HlgCroppedTo1080() {
        val v = info("h264_hlg.ts", hevc = false)
        // Codificado em 1088 linhas (68 macroblocos), com 8 recortadas.
        assertEquals(listOf(1920, 1080, 8), listOf(v.width, v.height, v.bitDepth))
        assertEquals(50f, v.fps)
        assertEquals(listOf(9, 18, 9), listOf(v.primaries, v.transfer, v.matrix))
        assertTrue(v.isHlg)
    }

    @Test
    fun h264InterlacedSdr() {
        val v = info("h264_576i.ts", hevc = false)
        assertEquals(listOf(720, 576), listOf(v.width, v.height))
        assertEquals(25f, v.fps)
        assertEquals(listOf(5, 1, 5), listOf(v.primaries, v.transfer, v.matrix))
        assertFalse(v.isHdr)
    }
}
