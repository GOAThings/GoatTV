package cc.slokker.itv.data

import cc.slokker.itv.data.stalker.StalkerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerTest {
    private val mac = "00:1A:79:00:00:01"

    @Test
    fun `endpoints follow the portal address`() {
        assertEquals(listOf("http://p/stalker_portal/server/load.php"), StalkerSource.endpoints("http://p/stalker_portal/c/"))
        assertEquals(listOf("http://p/x/portal.php"), StalkerSource.endpoints("http://p/x/portal.php"))
        assertEquals("http://p:8080/portal.php", StalkerSource.endpoints("http://p:8080/c/").first())
    }

    @Test
    fun `cookies differ by portal kind`() {
        val modern = StalkerSource.cookie(mac, "T", true, "get_genres", "UTC")
        assertTrue(modern.startsWith("mac=00%3A1A%3A79")); assertTrue(modern.endsWith("; token=T"))
        assertFalse(StalkerSource.cookie(mac, "T", true, "get_profile", "UTC").contains("token"))
        val other = StalkerSource.cookie(mac, "T", false, "get_profile", "UTC")
        assertTrue(other.startsWith("mac=$mac")); assertTrue(other.endsWith("; token=T"))
    }

    @Test
    fun `commands become urls on the portal when needed`() {
        assertEquals("http://p:88/ch/1_", StalkerSource.commandUrl("ffmpeg http://localhost/ch/1_", "http://p:88/portal.php"))
        assertEquals("http://p/play/live.php?a=1", StalkerSource.commandUrl("/play/live.php?a=1", "http://p/portal.php"))
        assertEquals("http://cdn/x.m3u8?t=1", StalkerSource.commandUrl("auto http://cdn/x.m3u8?t=1", "http://p"))
        assertTrue(StalkerSource.needsLink("ffmpeg http://localhost/ch/1_"))
        assertFalse(StalkerSource.needsLink("ffmpeg http://h/play/live.php?id=1"))
        assertEquals(StreamFormat.HLS, StalkerSource.candidate("http://cdn/x.m3u8?t=1").format)
        assertEquals(StreamFormat.TS, StalkerSource.candidate("http://cdn/x").format)
    }

    @Test
    fun `identity is derived from the mac unless given`() {
        val p = Playlist(id = "s", type = PlaylistType.STALKER, name = "S", server = "http://p", username = mac)
        val id = StalkerSource.Identity.of(p)
        assertEquals(13, id.serial.length); assertEquals(64, id.deviceId.length); assertEquals(id.deviceId, id.deviceId2)
        assertEquals("ABC", StalkerSource.Identity.of(p.copy(serial = "ABC")).serial)
    }

    @Test
    fun `form normalizes the mac and needs a valid one`() {
        assertEquals(mac, PlaylistForm.normalizeMac("00-1a-79-00-00-01"))
        assertEquals(mac, PlaylistForm.normalizeMac("001a79000001"))
        assertNull(PlaylistForm.normalizeMac("00:1A"))
    }

    @Test
    fun `generated macs are valid and only fill an empty field`() {
        val generated = PlaylistForm.generateMac()
        assertEquals(generated, PlaylistForm.normalizeMac(generated))
        assertEquals("00:1A:79", generated.take(8))
        val stalker = PlaylistForm().withType(PlaylistType.STALKER, mac)
        assertEquals(mac, stalker.username)
        assertEquals("", stalker.withType(PlaylistType.M3U, mac).username)
        assertEquals("other", PlaylistForm(username = "other").withType(PlaylistType.STALKER, mac).username)
    }

    @Test
    fun `portals without genres group channels by their separators`() {
        fun ch(name: String) = Channel(0, name, 0, name, null, "")
        assertEquals("FIFA WC", StalkerSource.sectionTitle("✦●✦ FIFA WC ✦●✦"))
        assertEquals("UK", StalkerSource.sectionTitle("=== UK ==="))
        assertNull(StalkerSource.sectionTitle("PPV| PSF 01 [EVENT ONLY]"))
        assertNull(StalkerSource.sectionTitle("(( HD ))"))
        val (grouped, sections) = StalkerSource.groupBySections(listOf(ch("Info"), ch("== A =="), ch("One"), ch("== B =="), ch("Two")))
        assertEquals(listOf("A", "B"), sections.map { it.id })
        assertEquals(listOf("" to "Info", "A" to "One", "B" to "Two"), grouped.map { it.categoryId to it.name })
        assertEquals(listOf(0, 1, 2), grouped.map { it.uid })
        assertEquals(2, StalkerSource.groupBySections(listOf(ch("== A =="), ch("One"))).first.size)
    }

    @Test
    fun `reports hide macs`() {
        assertEquals("a *** b", BugReport.redact("a $mac b", emptyList()))
        assertEquals("mac=***&x=1", BugReport.redact("mac=00%3A1A%3A79%3A00%3A00%3A01&x=1", emptyList()))
    }


    @Test
    fun `a token with characters a header cannot carry is not usable`() {
        assertTrue(SafeInput.isHeaderSafe("abc-123_XYZ"))
        assertFalse(SafeInput.isHeaderSafe("abc\ndef"))
        assertFalse(SafeInput.isHeaderSafe("tokén"))
        // O que se aceita tem mesmo de passar no OkHttp.
        okhttp3.Request.Builder().url("http://example.invalid/").header("Authorization", "Bearer abc-123_XYZ").build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `okhttp refuses what the token check refuses`() {
        okhttp3.Request.Builder().url("http://example.invalid/").header("Authorization", "Bearer abc\ndef").build()
    }
}
