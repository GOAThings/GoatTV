package cc.slokker.itv.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** "Testar este canal / esta lista": as regras do veredicto, os números por extenso e o que nunca se mostra. */
class StreamProbeTest {
    private fun input(
        status: Int = 200, firstByte: Long = 400, reconnects: Int = 0, dropped: Int = 0, rendered: Int = 250,
        cannotDecode: String = "", showing: Boolean = true,
    ) = ChannelProbeInput(
        host = "h.example", httpStatus = status, redirects = 0, firstByteMs = firstByte, firstPictureMs = 900, container = "TS",
        videoCodec = "H.264", width = 1920, height = 1080, fps = 25f, interlaced = true, hdr = false, audio = "AAC 2.0",
        measuredKbps = 4200, windowMs = 10_000, droppedFrames = dropped, renderedFrames = rendered, reconnects = reconnects,
        cannotDecode = cannotDecode, showing = showing,
    )

    @Test
    fun okWhenEverythingIsFine() = assertEquals(ProbeVerdict.OK, StreamProbe.verdict(input()).verdict)

    @Test
    fun refusedBeatsEverything() {
        val v = StreamProbe.verdict(input(status = 403, reconnects = 3, cannotDecode = "HEVC"))
        assertEquals(ProbeVerdict.REFUSED, v.verdict)
        assertEquals("403", v.arg)
        assertEquals(ProbeVerdict.REFUSED, StreamProbe.verdict(input(status = 503)).verdict)
    }

    @Test
    fun deviceCannotDecode() {
        val v = StreamProbe.verdict(input(cannotDecode = "HEVC", showing = false))
        assertEquals(ProbeVerdict.CANNOT_DECODE, v.verdict)
        assertEquals("HEVC", v.arg)
    }

    @Test
    fun dropsFromReconnectsOrLateFrames() {
        assertEquals(ProbeVerdict.DROPS, StreamProbe.verdict(input(reconnects = 1)).verdict)
        assertEquals(ProbeVerdict.DROPS, StreamProbe.verdict(input(dropped = 30, rendered = 220)).verdict)
        // 2 % de imagens tarde não é uma stream a falhar.
        assertEquals(ProbeVerdict.OK, StreamProbe.verdict(input(dropped = 5, rendered = 245)).verdict)
        // Um motor que não conta (-1) nunca acusa quedas.
        assertEquals(ProbeVerdict.OK, StreamProbe.verdict(input(dropped = -1, rendered = -1)).verdict)
    }

    @Test
    fun slowFirstByte() {
        val v = StreamProbe.verdict(input(firstByte = 4_260))
        assertEquals(ProbeVerdict.SLOW, v.verdict)
        assertEquals("4.3", v.arg)
        assertEquals(ProbeVerdict.OK, StreamProbe.verdict(input(firstByte = 3_000)).verdict)
    }

    @Test
    fun noPictureYet() = assertEquals(ProbeVerdict.NO_PICTURE, StreamProbe.verdict(input(showing = false)).verdict)

    @Test
    fun listVerdicts() {
        assertEquals(ListVerdict.OK, StreamProbe.listVerdict(200, 1_200, 800))
        assertEquals(ListVerdict.REFUSED, StreamProbe.listVerdict(401, 100, 0))
        assertEquals(ListVerdict.UNREACHABLE, StreamProbe.listVerdict(-1, 8_000, 0))
        assertEquals(ListVerdict.EMPTY, StreamProbe.listVerdict(200, 500, 0))
        assertEquals(ListVerdict.SLOW, StreamProbe.listVerdict(200, 20_000, 10))
    }

    @Test
    fun numbersReadWell() {
        assertEquals("0.4", StreamProbe.seconds(400))
        assertEquals("12.0", StreamProbe.seconds(11_960))
        assertEquals("4.2 Mbit/s", StreamProbe.bitrate(4200))
        assertEquals("800 kbit/s", StreamProbe.bitrate(800))
        assertEquals("—", StreamProbe.bitrate(-1))
        assertEquals("4.5 MB", StreamProbe.size(4_480_000))
        assertEquals("512 KB", StreamProbe.size(512_000))
    }

    @Test
    fun formatLine() {
        assertEquals("TS · H.264 · 1920×1080i · 25 fps · interlaced", StreamProbe.format(input(), "interlaced", "HDR"))
    }

    @Test
    fun hostOnly() {
        assertEquals("cdn.example.com", StreamProbe.host("http://user:pass@cdn.example.com:8080/live/user/pass/1.ts?token=abc"))
        assertEquals("a.b", StreamProbe.host("a.b/x"))
        assertEquals("[::1]", StreamProbe.host("http://[::1]:80/x"))
    }

    @Test
    fun scrubRemovesAddresses() {
        val out = StreamProbe.scrub("GET http://cdn.example.com/live/joe/secret/9.ts?t=1 failed; retry https://x.y/z")
        assertEquals("GET cdn.example.com failed; retry x.y", out)
        assertFalse(out.contains("secret"))
    }

    @Test
    fun tokenCounterAcrossChunks() {
        val c = TokenCounter("<programme")
        c.feed("<programme a><progr")
        c.feed("amme b></programme><programme")
        c.feed(" c>")
        assertEquals(3, c.count)
        val m = TokenCounter("#EXTINF")
        "#EXTM3U\n#EXTINF:-1,A\nu\n#EXTINF:-1,B\nu\n".chunked(3).forEach(m::feed)
        assertEquals(2, m.count)
    }
}
