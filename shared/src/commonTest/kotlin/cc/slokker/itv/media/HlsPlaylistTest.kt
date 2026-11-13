package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsPlaylistTest {
    @Test
    fun master() {
        val p = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            /hd/index.m3u8?token=a
            """.trimIndent(),
            "http://h.example:8080/live/ch/master.m3u8?x=1",
        )
        assertTrue(p.isMaster)
        assertEquals(listOf("http://h.example:8080/live/ch/low/index.m3u8", "http://h.example:8080/hd/index.m3u8?token=a"), p.variants.map { it.uri })
        assertEquals(listOf(360, 1080), p.variants.map { it.height })
        assertEquals("avc1.4d401e,mp4a.40.2", p.variants[0].codecs)
        assertEquals(5_000_000L, p.variants[1].bandwidth)
    }

    @Test
    fun liveMedia() {
        val p = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:1200
            #EXTINF:4.000,
            seg1200.ts
            #EXTINF:3.96,
            seg1201.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:4,
            https://cdn.example/other/seg1202.ts
            """.trimIndent(),
            "http://h.example/live/ch/index.m3u8",
        )
        assertFalse(p.isMaster)
        assertFalse(p.ended)
        assertNull(p.unsupported)
        assertEquals(4.0, p.targetDuration)
        assertEquals(listOf(1200L, 1201L, 1202L), p.segments.map { it.sequence })
        assertEquals(listOf(false, false, true), p.segments.map { it.discontinuity })
        assertEquals("http://h.example/live/ch/seg1200.ts", p.segments[0].uri)
        assertEquals("https://cdn.example/other/seg1202.ts", p.segments[2].uri)
    }

    @Test
    fun unsupportedKinds() {
        val fmp4 = HlsPlaylist.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST", "http://h/x.m3u8")
        assertEquals("fMP4 segments", fmp4.unsupported)
        assertTrue(fmp4.ended)
        val sample = HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4,\na.ts", "http://h/x.m3u8")
        assertEquals("encrypted (SAMPLE-AES)", sample.unsupported)
        assertNull(HlsPlaylist.parse("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts", "http://h/x.m3u8").unsupported)
    }

    @Test
    fun aes128Keys() {
        val p = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:258
            #EXT-X-KEY:METHOD=AES-128,URI="/keys/1.key"
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="k2",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:4,
            b.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            c.ts
            """.trimIndent(),
            "http://h.example/live/ch/index.m3u8",
        )
        assertNull(p.unsupported)
        val (a, b, c) = p.segments
        assertEquals("http://h.example/keys/1.key", a.key?.uri)
        // Sem IV: a sequência (258 = 0x0102) em big-endian.
        assertEquals(List(14) { 0 } + listOf(1, 2), a.key!!.iv.map { it.toInt() })
        assertEquals("http://h.example/live/ch/k2", b.key?.uri)
        assertEquals((0..15).toList(), b.key!!.iv.map { it.toInt() })
        assertNull(c.key)
    }
}
