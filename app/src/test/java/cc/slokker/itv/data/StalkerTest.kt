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
    fun `reports hide macs`() {
        assertEquals("a *** b", BugReport.redact("a $mac b", emptyList()))
        assertEquals("mac=***&x=1", BugReport.redact("mac=00%3A1A%3A79%3A00%3A00%3A01&x=1", emptyList()))
    }
}
