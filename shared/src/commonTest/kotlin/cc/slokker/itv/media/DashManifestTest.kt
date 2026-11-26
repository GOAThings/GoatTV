package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashManifestTest {
    private val url = "https://cdn.example.com/live/ch1/manifest.mpd?token=abc"

    private val vod = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- a VOD -->
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static" mediaPresentationDuration="PT10.5S" minBufferTime="PT2S">
          <Period id="p0">
            <AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">
              <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="01234567-89ab-cdef-0123-456789abcdef"/>
              <SegmentTemplate timescale="1000" duration="4000" startNumber="1" initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number%05d${'$'}.m4s"/>
              <Representation id="v720" bandwidth="2500000" width="1280" height="720" codecs="avc1.64001f" frameRate="25"/>
              <Representation id="v1080" bandwidth="5000000" width="1920" height="1080" codecs="avc1.640028" frameRate="50/2"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="hi">
              <Role schemeIdUri="urn:mpeg:dash:role:2011" value="main"/>
              <SegmentTemplate timescale="48000" duration="192000" initialization="a/${'$'}Bandwidth${'$'}/init.mp4" media="a/${'$'}Bandwidth${'$'}/${'$'}Number${'$'}.m4s"/>
              <Representation id="a64" bandwidth="64000" codecs="mp4a.40.2"><AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/></Representation>
              <Representation id="a128" bandwidth="128000" codecs="mp4a.40.2"/>
            </AdaptationSet>
            <AdaptationSet contentType="audio" lang="en"><SegmentTemplate timescale="1000" duration="4000" initialization="en/init.mp4" media="en/${'$'}Number${'$'}.m4s"/><Representation id="en" bandwidth="96000"/></AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun masterOfAVod() {
        assertTrue(DashManifest.looksLike(vod.encodeToByteArray().copyOf(64)))
        assertTrue(DashManifest.isManifest(vod))
        val m = HlsPlaylist.parseAny(vod, url, 0)
        assertNull(m.unsupported)
        assertEquals(2, m.variants.size)
        val v = m.variants[1]
        assertEquals("$url#goatdash=v1", v.uri)
        assertEquals(5_000_000, v.bandwidth)
        assertEquals(1080, v.height)
        assertEquals(25.0, v.frameRate)
        assertEquals(listOf("hi", "en"), v.audio.map { it.language })
        assertEquals("$url#goatdash=a0", v.audio[0].uri)
        assertEquals("https://cdn.example.com/live/ch1/manifest.mpd?token=abc", HlsPlaylist.fetchUrl(v.uri))
    }

    @Test
    fun segmentsOfAVod() {
        val v = HlsPlaylist.parseAny(vod, "$url#goatdash=v1", 0)
        assertNull(v.unsupported)
        assertTrue(v.ended)
        // 10.5 s em segmentos de 4 s: 3 segmentos.
        assertEquals(3, v.segments.size)
        assertEquals("https://cdn.example.com/live/ch1/v1080/seg-00001.m4s", v.segments[0].uri)
        assertEquals("https://cdn.example.com/live/ch1/v1080/init.mp4", v.segments[0].map?.uri)
        assertEquals(listOf(0L, 4000L, 8000L), v.segments.map { it.sequence })
        assertEquals(v.segments[1].sequence, v.segments[0].next)
        // O áudio: a melhor qualidade do conjunto.
        val a = HlsPlaylist.parseAny(vod, "$url#goatdash=a0", 0)
        assertEquals("https://cdn.example.com/live/ch1/a/128000/1.m4s", a.segments[0].uri)
        assertEquals(4.0, a.segments[0].duration)
    }

    @Test
    fun liveTimelineWithTime() {
        val mpd = """
            <MPD type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" minimumUpdatePeriod="PT4S" timeShiftBufferDepth="PT30S">
              <Period id="1" start="PT0S">
                <BaseURL>https://edge.example.com/bpk-tv/Star/</BaseURL>
                <AdaptationSet mimeType="video/mp4" maxHeight="1080">
                  <SegmentTemplate timescale="90000" presentationTimeOffset="0" initialization="dash/Star-${'$'}RepresentationID${'$'}.dash" media="dash/Star-${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.dash">
                    <SegmentTimeline><S t="900000" d="540000" r="2"/><S d="360000"/></SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="video=1500000" bandwidth="1500000" width="1280" height="720" codecs="avc1.4D401F"/>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4" lang="hin">
                  <SegmentTemplate timescale="48000" initialization="dash/Star-${'$'}RepresentationID${'$'}.dash" media="dash/Star-${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.dash">
                    <SegmentTimeline><S t="480000" d="288000" r="3"/></SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="audio_hin=96000" bandwidth="96000" codecs="mp4a.40.2"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val v = HlsPlaylist.parseAny(mpd, "$url#goatdash=v0", 40_000)
        assertFalse(v.ended)
        assertEquals(4, v.segments.size)
        assertEquals("https://edge.example.com/bpk-tv/Star/dash/Star-video=1500000-900000.dash", v.segments[0].uri)
        assertEquals("https://edge.example.com/bpk-tv/Star/dash/Star-video=1500000.dash", v.segments[0].map?.uri)
        // Os números são o tempo em ms: 10 s, 16 s, 22 s, 28 s; o seguinte de cada um é o início do outro.
        assertEquals(listOf(10_000L, 16_000L, 22_000L, 28_000L), v.segments.map { it.sequence })
        assertEquals(32_000L, v.segments.last().next)
        assertEquals(6.0, v.targetDuration)
        val a = HlsPlaylist.parseAny(mpd, "$url#goatdash=a0", 40_000)
        assertEquals(listOf(10_000L, 16_000L, 22_000L, 28_000L), a.segments.map { it.sequence })
    }

    @Test
    fun liveNumberByTheClock() {
        val mpd = """
            <MPD type="dynamic" availabilityStartTime="2024-01-01T00:00:00Z" timeShiftBufferDepth="PT20S">
              <Period start="PT0S">
                <AdaptationSet contentType="video">
                  <SegmentTemplate timescale="1" duration="2" startNumber="100" initialization="init-${'$'}RepresentationID${'$'}.mp4" media="${'$'}RepresentationID${'$'}_${'$'}Number${'$'}.m4s"/>
                  <Representation id="hd" bandwidth="3000000" height="720"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val ast = DashManifest.dateTime("2024-01-01T00:00:00Z")!!
        // 61 s depois do início: os segmentos de 2 s já acabados vão até ao 30.º (58–60 s).
        val p = HlsPlaylist.parseAny(mpd, "https://x/live.mpd#goatdash=v0", ast + 61_000)
        assertEquals("https://x/hd_129.m4s", p.segments.last().uri)
        assertEquals(58_000L, p.segments.last().sequence)
        // A janela de 20 s.
        assertTrue(p.segments.first().sequence >= 61_000 - 20_000 - 3_000)
    }

    @Test
    fun multiPeriodVod() {
        val mpd = """
            <MPD type="static" mediaPresentationDuration="PT20S">
              <Period id="a" duration="PT8S"><AdaptationSet contentType="video"><SegmentTemplate timescale="1" duration="4" initialization="a/init.mp4" media="a/${'$'}Number${'$'}.m4s"/><Representation id="v" bandwidth="1"/></AdaptationSet></Period>
              <Period id="b"><AdaptationSet contentType="video"><SegmentTemplate timescale="1" duration="4" initialization="b/init.mp4" media="b/${'$'}Number${'$'}.m4s"/><Representation id="v" bandwidth="1"/></AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val p = HlsPlaylist.parseAny(mpd, "https://x/vod.mpd#goatdash=v0", 0)
        assertEquals(listOf("a/1", "a/2", "b/1", "b/2", "b/3"), p.segments.map { it.uri.removePrefix("https://x/").removeSuffix(".m4s") })
        assertEquals(listOf(0L, 4000L, 8000L, 12000L, 16000L), p.segments.map { it.sequence })
        assertTrue(p.segments[2].discontinuity)
        assertEquals("https://x/b/init.mp4", p.segments[2].map?.uri)
    }

    @Test
    fun segmentBaseIsNotYet() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT5S"><Period><AdaptationSet contentType="video"><Representation id="v" bandwidth="1"><BaseURL>v.mp4</BaseURL><SegmentBase indexRange="800-1000"/></Representation></AdaptationSet></Period></MPD>"""
        assertEquals("DASH SegmentBase", HlsPlaylist.parseAny(mpd, "https://x/a.mpd#goatdash=v0", 0).unsupported)
    }

    @Test
    fun hlsStillParsesAsHls() {
        val p = HlsPlaylist.parseAny("#EXTM3U\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST", "http://h/x.m3u8", 0)
        assertEquals(1, p.segments.size)
        assertEquals(1L, p.segments[0].next)
    }

    @Test
    fun templates() {
        assertEquals("v/0042_x_\$_1500", DashManifest.expand("v/\$Number%04d\$_x_\$\$_\$Bandwidth\$", "id", 42, 0, 1500))
        assertEquals(5_025_500L, DashManifest.durationMs("PT1H23M45.5S"))
        assertEquals(90_000L, DashManifest.durationMs("PT1M30S"))
    }
}
