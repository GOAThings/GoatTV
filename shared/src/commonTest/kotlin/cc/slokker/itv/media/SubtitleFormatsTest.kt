package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleFormatsTest {
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return u32(8L + body.size) + type.encodeToByteArray() + body
    }
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun full(version: Int, flags: Int) = byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte())

    @Test
    fun ttmlFile() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25" ttp:tickRate="10000000">
              <body><div>
                <p begin="00:00:01.500" end="00:00:03.000">Hello<br/>world &amp; all</p>
                <p begin="00:00:04:12" dur="2s"><span tts:color="yellow">Frames</span></p>
                <p begin="50000000t" end="60000000t">Ticks</p>
                <p><span begin="10s" end="11s">Span</span> <span begin="11s" end="12.5s">times</span></p>
              </div></body>
            </tt>"""
        assertTrue(Ttml.looksLike(xml))
        val cues = Ttml.parse(xml)
        assertEquals(listOf("Hello\nworld & all", "Frames", "Ticks", "Span times"), cues.map { it.text })
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        // 4 s + 12 imagens a 25/s = 4480 ms, mais 2 s.
        assertEquals(4480L, cues[1].startMs)
        assertEquals(6480L, cues[1].endMs)
        assertEquals(5000L, cues[2].startMs)
        assertEquals(10_000L to 12_500L, cues[3].startMs to cues[3].endMs)
    }

    /** Um MP4 de legendas: o init com [format] (escala 1000) e um segmento com [samples] (dados, início, duração). */
    private fun mp4(format: String, samples: List<Triple<ByteArray, Long, Long>>): ByteArray {
        val stsd = box("stsd", full(0, 0), u32(1), box(format, ByteArray(8)))
        val init = box("moov", box("trak", box("mdia", box("mdhd", full(0, 0), u32(0), u32(0), u32(1000), u32(0), ByteArray(4)), box("minf", box("stbl", stsd)))))
        fun moof(off: Int) = box(
            "moof",
            box(
                "traf",
                box("tfhd", full(0, 0x20000), u32(1)),
                box("tfdt", full(0, 0), u32(samples.first().second)),
                box("trun", full(0, 0x301), u32(samples.size.toLong()), u32(off.toLong()), samples.fold(ByteArray(0)) { a, s -> a + u32(s.third) + u32(s.first.size.toLong()) }),
            ),
        )
        val size = moof(0).size
        return init + moof(size + 8) + box("mdat", samples.fold(ByteArray(0)) { a, s -> a + s.first })
    }

    @Test
    fun webVttInMp4() {
        val cue = box("vttc", box("payl", "<i>Olá</i> mundo".encodeToByteArray()))
        val empty = box("vtte")
        val bytes = mp4("wvtt", listOf(Triple(cue, 20_000L, 2_000L), Triple(empty, 22_000L, 1_000L)))
        assertTrue(Mp4Text.looksLike(bytes))
        val cues = Mp4Text().cues(bytes)
        assertEquals(1, cues.size)
        assertEquals("Olá mundo", cues[0].text)
        assertEquals(20_000L to 22_000L, cues[0].startMs to cues[0].endMs)
    }

    @Test
    fun ttmlInMp4RelativeAndAbsolute() {
        val rel = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:00:00.500" end="00:00:01.500">Relative</p></div></body></tt>"""
        val abs = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01:02.000" end="00:01:03.000">Absolute</p></div></body></tt>"""
        val r = Mp4Text().cues(mp4("stpp", listOf(Triple(rel.encodeToByteArray(), 60_000L, 2_000L))))
        assertEquals(60_500L, r.single().startMs)
        val a = Mp4Text().cues(mp4("stpp", listOf(Triple(abs.encodeToByteArray(), 62_000L, 2_000L))))
        assertEquals(62_000L, a.single().startMs)
        // Pelo demuxer das legendas: o PTS a 90 kHz.
        val samples = ArrayList<TsSample>()
        WebVttDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) { samples += sample }
        }, "eng").feed(mp4("stpp", listOf(Triple(abs.encodeToByteArray(), 62_000L, 2_000L))))
        assertEquals(62_000L * 90, samples.single().pts)
        assertEquals("Absolute", WebVtt.text(samples.single().data))
    }

}
