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

    /** As imagens pelo [NalReader]: quantas são imagens-chave e se a primeira traz os conjuntos de parâmetros. */
    private fun video(name: String, hevc: Boolean, keyframes: Int) {
        val c = read(name)
        val track = c.tracks.first { it.kind == TrackKind.VIDEO }
        val reader = NalReader(hevc)
        val units = c.samples.getValue(track.pid).map { reader.unit(it.data) }
        assertEquals(keyframes, units.count { it.keyframe }, name)
        assertTrue(units.first().keyframe, name)
        assertEquals(if (hevc) 3 else 2, units.first().parameterSets.size, name)
        assertTrue(units.drop(1).filter { !it.keyframe }.all { it.parameterSets.isEmpty() }, name)
        // AVCC: os tamanhos de 4 bytes percorrem a imagem toda.
        for (u in units) {
            var at = 0
            while (at < u.data.size) at += 4 + ((u.data[at].toInt() and 0xFF) shl 24 or ((u.data[at + 1].toInt() and 0xFF) shl 16) or ((u.data[at + 2].toInt() and 0xFF) shl 8) or (u.data[at + 3].toInt() and 0xFF))
            assertEquals(u.data.size, at, name)
        }
    }

    /** O áudio pelo [AudioFrameReader]: os blocos que o ffprobe conta, a frequência, os canais e o tempo a avançar. */
    private fun audio(name: String, pid: Int, frames: Int, rate: Int, channels: Int) {
        val c = read(name)
        val track = c.tracks.first { it.pid == pid }
        val reader = AudioFrameReader(track.codec)
        val all = c.samples.getValue(pid).flatMap { reader.frames(it.data, it.pts, it.discontinuity) }
        assertEquals(frames, all.size, name)
        assertEquals(rate, reader.format!!.sampleRate, name)
        assertEquals(channels, reader.format!!.channels, name)
        assertTrue(all.zipWithNext().all { (a, b) -> b.pts > a.pts }, "$name: o tempo do áudio não avança")
        val step = all[1].pts - all[0].pts
        assertEquals(all[0].samples * 90_000L / rate, step, name)
    }

    @Test
    fun h264Units() = video("h264_aac.ts", hevc = false, keyframes = 2)

    /** Open GOP: só a primeira é IDR, as outras imagens I também são pontos de entrada (como em muitos canais de TV). */
    @Test
    fun h264OpenGopIFrames() = video("h264_opengop.ts", hevc = false, keyframes = 6)

    @Test
    fun hevcUnits() = video("hevc_ac3.ts", hevc = true, keyframes = 2)

    @Test
    fun aacFrames() = audio("h264_aac.ts", firstAudio("h264_aac.ts"), 95, 48000, 2)

    @Test
    fun ac3SurroundFrames() = audio("hevc_ac3.ts", firstAudio("hevc_ac3.ts"), 63, 48000, 6)

    @Test
    fun eac3Frames() = audio("h264_eac3.ts", firstAudio("h264_eac3.ts"), 63, 48000, 2)

    @Test
    fun mp2Frames() = audio("mpeg2_mp2.ts", firstAudio("mpeg2_mp2.ts"), 84, 48000, 1)

    @Test
    fun dvbMonoAc3AndEac3() {
        val tracks = read("dvb_ac3_eac3.ts").tracks.filter { it.kind == TrackKind.AUDIO }
        audio("dvb_ac3_eac3.ts", tracks[0].pid, 63, 48000, 1)
        audio("dvb_ac3_eac3.ts", tracks[1].pid, 63, 48000, 1)
    }

    private fun firstAudio(name: String) = read(name).tracks.first { it.kind == TrackKind.AUDIO }.pid

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
