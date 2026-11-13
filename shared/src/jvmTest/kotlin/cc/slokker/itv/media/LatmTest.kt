package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O mesmo AAC em ADTS e em LOAS/LATM (o ffmpeg copiou o áudio para os dois, resources/ts/h264_aac_same.ts e
 * h264_aac_latm.ts): os blocos tirados do LATM têm de ser os mesmos, byte a byte, e o formato também.
 */
class LatmTest {
    private fun audio(name: String): Pair<List<AudioFrame>, AudioFormat?> {
        val bytes = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()
        var reader: AudioFrameReader? = null
        val frames = ArrayList<AudioFrame>()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {
                reader = AudioFrameReader(tracks.first { it.kind == TrackKind.AUDIO }.codec)
            }

            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.kind != TrackKind.AUDIO) return
                frames += reader!!.frames(sample.data, sample.pts, sample.discontinuity)
            }
        })
        // Aos bocados, como chega da rede.
        var at = 0
        while (at < bytes.size) {
            val n = minOf(1_317, bytes.size - at)
            d.feed(bytes, at, n)
            at += n
        }
        d.flush()
        return frames to reader!!.format
    }

    @Test
    fun latmGivesTheSameFramesAsAdts() {
        val (adts, adtsFormat) = audio("h264_aac_same.ts")
        val (latm, latmFormat) = audio("h264_aac_latm.ts")
        assertTrue(adts.size > 80, "blocos ADTS: ${adts.size}")
        assertEquals(adts.size, latm.size, "blocos")
        for (k in adts.indices) assertContentEquals(adts[k].data, latm[k].data, "bloco $k")
        assertEquals(adts.map { it.pts - adts[0].pts }, latm.map { it.pts - latm[0].pts }, "tempos")
        assertEquals(Codec.AAC_LATM, latmFormat!!.codec)
        assertEquals(adtsFormat!!.sampleRate to adtsFormat.channels, latmFormat.sampleRate to latmFormat.channels)
        // O AudioSpecificConfig: o mesmo tipo, frequência e canais (o do LATM traz mais 3 bits do GASpecificConfig).
        assertContentEquals(adtsFormat.config, latmFormat.config.copyOf(2))
    }
}
