package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O mesmo conteúdo em fMP4 e em TS (o ffmpeg copiou as faixas para os dois, em `resources/mp4`): o [Mp4Demuxer] tem
 * de dar ao motor o mesmo que o [TsDemuxer] dá — as mesmas faixas, as mesmas imagens com os mesmos tempos e os
 * mesmos blocos de áudio.
 */
class Mp4SamplesTest {
    private class Collect : TsListener {
        var tracks: List<TsTrack> = emptyList()
        val samples = HashMap<Int, MutableList<TsSample>>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks = tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples.getOrPut(track.pid) { ArrayList() } += sample
        }

        fun of(kind: TrackKind) = samples.getValue(tracks.first { it.kind == kind }.pid)
    }

    private fun read(name: String): Pair<Collect, StreamDemuxer> {
        val bytes = javaClass.getResourceAsStream("/mp4/$name")!!.readBytes()
        val c = Collect()
        val d = StreamDemuxer(c)
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
        return c to d
    }

    private fun compare(mp4Name: String, tsName: String, hevc: Boolean) {
        val (mp4, demuxer) = read(mp4Name)
        val (ts, _) = read(tsName)
        assertTrue(demuxer.isMp4, mp4Name)
        assertNull(demuxer.unsupported, mp4Name)
        assertEquals(ts.tracks.map { it.codec to it.kind }, mp4.tracks.map { it.codec to it.kind }, mp4Name)

        // Vídeo: as mesmas imagens, pela mesma ordem, com os mesmos tempos (o TS começa mais tarde: compara-se o avanço).
        val mv = mp4.of(TrackKind.VIDEO)
        val tv = ts.of(TrackKind.VIDEO)
        assertEquals(tv.size, mv.size, "$mp4Name: imagens")
        assertEquals(tv.map { it.pts - tv[0].dts }, mv.map { it.pts - mv[0].dts }, "$mp4Name: PTS")
        assertEquals(tv.map { it.dts - tv[0].dts }, mv.map { it.dts - mv[0].dts }, "$mp4Name: DTS")
        assertEquals(tv.map { it.randomAccess }, mv.map { it.randomAccess }, "$mp4Name: imagens-chave")
        val reader = NalReader(hevc)
        val units = mv.map { reader.unit(it.data) }
        assertTrue(units.first().keyframe, mp4Name)
        assertEquals(if (hevc) 3 else 2, units.first().parameterSets.size, mp4Name)

        // Áudio: os mesmos blocos (o TS junta vários num PES; o MP4 dá um a um) e o mesmo formato.
        val track = mp4.tracks.first { it.kind == TrackKind.AUDIO }
        fun frames(c: Collect): Pair<List<AudioFrame>, AudioFormat?> {
            val r = AudioFrameReader(track.codec)
            val out = c.of(TrackKind.AUDIO).flatMap { r.frames(it.data, it.pts, false) }
            return out to r.format
        }
        val (ma, mf) = frames(mp4)
        val (ta, tf) = frames(ts)
        assertEquals(ta.size, ma.size, "$mp4Name: blocos de áudio")
        assertEquals(tf, mf, "$mp4Name: formato do áudio")
        // O passo entre blocos. O TS arredonda o PTS de cada PES (±15 a 90 kHz) e o MP4 conta exato: até 1 ms de
        // diferença. Do segundo bloco em diante: no fMP4 o ffmpeg dá ao primeiro bloco AAC a duração do priming.
        val drift = (2 until ta.size).maxOf { i -> kotlin.math.abs((ta[i].pts - ta[1].pts) - (ma[i].pts - ma[1].pts)) }
        assertTrue(drift <= 90, "$mp4Name: tempos do áudio afastam-se $drift")
        assertTrue(ta.zip(ma).all { (a, b) -> a.data.size == b.data.size }, "$mp4Name: tamanho dos blocos")
    }

    @Test
    fun h264WithAac() = compare("h264_aac.mp4", "h264_aac.ts", hevc = false)

    @Test
    fun hevcWithEac3() = compare("hevc_eac3.mp4", "hevc_eac3.ts", hevc = true)

    /** Como o HLS dá um fMP4: o init.mp4 e os segmentos (cada um com o seu styp) um atrás do outro. */
    @Test
    fun hlsSegmentsInARow() = compare("h264_aac_hls.mp4", "h264_aac.ts", hevc = false)

    @Test
    fun languageFromMdhd() {
        val (c, _) = read("h264_aac.mp4")
        assertEquals("por", c.tracks.first { it.kind == TrackKind.AUDIO }.language)
    }

    @Test
    fun timesAt90kHzWithoutOverflow() {
        assertEquals(90_000L, Mp4Demuxer.ticks(48_000, 48_000))
        // Um tfdt de 10 MHz com décadas de tempo não passa do Long; fica nos 33 bits como um PTS.
        val big = 10_000_000L * 3_000_000_000L
        assertTrue(Mp4Demuxer.ticks(big, 10_000_000L) in 0..0x1FFFFFFFFL)
    }

    @Test
    fun heAacGoesAsItsCore() {
        // AAC LC 48 kHz estéreo: 0x11 0x90.
        assertEquals(listOf(1, 3, 2), Mp4Demuxer.audioSpecificConfig(byteArrayOf(0x11, 0x90.toByte()))!!.toList())
        // HE-AAC explícito (objeto 5, 24 kHz, estéreo, extensão a 48 kHz, núcleo AAC LC): o ADTS leva o núcleo.
        assertEquals(listOf(1, 6, 2), Mp4Demuxer.audioSpecificConfig(byteArrayOf(0x2B, 0x11, 0x88.toByte(), 0x00))!!.toList())
    }
}
