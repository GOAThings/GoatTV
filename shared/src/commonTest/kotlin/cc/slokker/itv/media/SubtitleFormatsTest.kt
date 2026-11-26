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

    @Test
    fun nestedSidx() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT20S"><Period><AdaptationSet contentType="video"><Representation id="v" bandwidth="1"><BaseURL>v.mp4</BaseURL><SegmentBase indexRange="1000-1055" timescale="1000"/></Representation></AdaptationSet>
            <AdaptationSet contentType="text" mimeType="application/mp4" codecs="stpp" lang="hi"><Representation id="s" bandwidth="1"><BaseURL>s.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        fun sidx(earliest: Long, firstOffset: Long, refs: List<Triple<Boolean, Long, Long>>): ByteArray {
            var body = full(0, 0) + u32(1) + u32(1000) + u32(earliest) + u32(firstOffset) + byteArrayOf(0, 0, 0, refs.size.toByte())
            for ((child, size, dur) in refs) body += u32((if (child) 0x80000000L else 0L) or size) + u32(dur) + u32(0x90000000L)
            return box("sidx", body)
        }
        // O de topo (56 bytes): dois filhos, cada um com um sidx de 56 bytes e dois pedaços de 100 bytes (256).
        val top = sidx(0, 0, listOf(Triple(true, 256, 10_000), Triple(true, 256, 10_000)))
        assertEquals(56, top.size)
        var p = HlsPlaylist.parseAny(mpd, "https://x/a.mpd#goatdash=v0", 0)
        p = DashManifest.withIndex(p, top)
        val first = p.dashIndex!!
        assertEquals(1056L, first.range.offset)
        p = DashManifest.withIndex(p, sidx(0, 0, listOf(Triple(false, 100, 5_000), Triple(false, 100, 5_000))))
        assertEquals(1056L + 256, p.dashIndex!!.range.offset)
        p = DashManifest.withIndex(p, sidx(10_000, 0, listOf(Triple(false, 100, 5_000), Triple(false, 100, 5_000))))
        assertEquals(null, p.dashIndex)
        assertEquals(listOf(0L, 5000L, 10_000L, 15_000L), p.segments.map { it.sequence })
        assertEquals(listOf(1112L, 1212L, 1368L, 1468L), p.segments.map { it.range.offset })
        // As legendas stpp num MP4 (sem segmentos): um ficheiro só.
        val master = HlsPlaylist.parseAny(mpd, "https://x/a.mpd", 0)
        assertEquals(listOf("hi"), master.variants[0].subtitles.map { it.language })
    }
}
