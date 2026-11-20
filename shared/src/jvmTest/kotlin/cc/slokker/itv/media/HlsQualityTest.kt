package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsQualityTest {
    private fun v(bandwidth: Long, height: Int, codecs: String? = null, fps: Double = 0.0, uri: String = "v$bandwidth") =
        HlsVariant(uri, bandwidth, height, codecs, frameRate = fps)

    // A ordem de uma playlist real: não vem ordenada.
    private val ladder = HlsQuality.ordered(
        listOf(v(6_200_000, 1080, fps = 50.0), v(800_000, 360), v(3_000_000, 720), v(1_500_000, 540), v(12_000_000, 2160)),
    )

    private fun plan(preference: String, pinned: Int = -1) = HlsQuality.plan(ladder, preference, pinned).let { Triple(it.start, it.lowest, it.highest) }

    @Test
    fun `ordered goes from the lowest to the highest bitrate`() {
        assertEquals(listOf(360, 540, 720, 1080, 2160), ladder.map { it.height })
    }

    @Test
    fun `audio-only variants leave when there is video`() {
        val list = HlsQuality.ordered(listOf(v(64_000, 0, "mp4a.40.2"), v(2_000_000, 720, "avc1.64001f,mp4a.40.2"), v(900_000, 0, "avc1.4d401e")))
        assertEquals(listOf(900_000L, 2_000_000L), list.map { it.bandwidth })
        // Só som em todas: ficam (o leitor tem de ler alguma coisa).
        assertEquals(1, HlsQuality.ordered(listOf(v(64_000, 0, "mp4a.40.2"))).size)
        assertTrue(HlsQuality.audioOnly(v(64_000, 0, "mp4a.40.2, ec-3")))
        assertFalse(HlsQuality.audioOnly(v(64_000, 0, null)))
        assertFalse(HlsQuality.audioOnly(v(64_000, 0, "hvc1.2.4.L150,mp4a.40.2")))
    }

    @Test
    fun `each preference maps to a start and a range`() {
        assertEquals(Triple(4, 0, 4), plan(Prefs.HlsQuality.AUTO))
        assertEquals(Triple(4, 4, 4), plan(Prefs.HlsQuality.BEST))
        assertEquals(Triple(0, 0, 0), plan(Prefs.HlsQuality.LOWEST))
        assertEquals(Triple(2, 0, 2), plan(Prefs.HlsQuality.DATA_SAVER))
        // Um valor desconhecido é o automático.
        assertEquals(Triple(4, 0, 4), plan("x"))
    }

    @Test
    fun `a manual choice wins over the preference until it is cleared`() {
        assertEquals(Triple(1, 1, 1), plan(Prefs.HlsQuality.BEST, pinned = 1))
        assertEquals(Triple(3, 3, 3), plan(Prefs.HlsQuality.DATA_SAVER, pinned = 3))
        // Fora da lista (outro stream, com menos): conta a definição.
        assertEquals(Triple(0, 0, 0), plan(Prefs.HlsQuality.LOWEST, pinned = 9))
        assertTrue(HlsQuality.plan(ladder, Prefs.HlsQuality.AUTO, 2).fixed)
        assertFalse(HlsQuality.plan(ladder, Prefs.HlsQuality.AUTO, -1).fixed)
    }

    @Test
    fun `data saver without heights caps by bitrate`() {
        val list = HlsQuality.ordered(listOf(v(1_000_000, 0), v(2_800_000, 0), v(5_000_000, 0)))
        assertEquals(1, HlsQuality.plan(list, Prefs.HlsQuality.DATA_SAVER, -1).start)
        // Nada cabe: a mais baixa.
        val big = HlsQuality.ordered(listOf(v(8_000_000, 1080), v(5_000_000, 900)))
        assertEquals(0, HlsQuality.plan(big, Prefs.HlsQuality.DATA_SAVER, -1).start)
    }

    @Test
    fun `an empty playlist has a plan that does not break`() {
        val p = HlsQuality.plan(emptyList(), Prefs.HlsQuality.BEST, -1)
        assertEquals(0, p.start)
    }

    @Test
    fun `labels show height, high frame rates and bitrate`() {
        assertEquals("1080p50 · 6.2 Mb/s", HlsQuality.label(v(6_200_000, 1080, fps = 50.0), 0))
        assertEquals("720p · 3.0 Mb/s", HlsQuality.label(v(3_000_000, 720, fps = 25.0), 0))
        assertEquals("360p · 800 kb/s", HlsQuality.label(v(800_000, 360), 0))
        assertEquals("1080p60 · 7.9 Mb/s", HlsQuality.label(v(7_949_000, 1080, fps = 59.94), 0))
        assertEquals("2.5 Mb/s", HlsQuality.label(v(2_500_000, 0), 0))
        assertEquals("3", HlsQuality.label(v(0, 0), 2))
    }

    @Test
    fun `the menu goes from the highest down and skips repeated qualities`() {
        assertEquals(listOf(4, 3, 2, 1, 0), HlsQuality.menu(ladder).map { it.index })
        assertEquals("2160p · 12.0 Mb/s", HlsQuality.menu(ladder).first().label)
        // A mesma qualidade em dois servidores (reserva): uma linha só.
        val backup = HlsQuality.ordered(listOf(v(3_000_000, 720, uri = "a"), v(3_000_000, 720, uri = "b"), v(800_000, 360)))
        assertEquals(listOf("720p · 3.0 Mb/s", "360p · 800 kb/s"), HlsQuality.menu(backup).map { it.label })
        // Uma qualidade só: sem menu.
        assertTrue(HlsQuality.menu(HlsQuality.ordered(listOf(v(3_000_000, 720)))).isEmpty())
        assertTrue(HlsQuality.menu(HlsQuality.ordered(listOf(v(3_000_000, 720, uri = "a"), v(3_000_000, 720, uri = "b")))).isEmpty())
    }

    @Test
    fun `frame rate is read from the master playlist`() {
        val p = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=6200000,RESOLUTION=1920x1080,FRAME-RATE=50.000
            hd.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            sd.m3u8
            """.trimIndent(),
            "http://h/x/master.m3u8",
        )
        assertEquals(listOf(50.0, 0.0), p.variants.map { it.frameRate })
    }
}
