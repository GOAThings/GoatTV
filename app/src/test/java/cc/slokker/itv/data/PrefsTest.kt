package cc.slokker.itv.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {

    private fun prefs(json: String) = Prefs.from(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `missing or invalid values fall back to defaults`() {
        val p = prefs("""{"buffer":"enorme","decoder":42,"info_seconds":"x","show_logos":false,"stream_format":"ts"}""")
        assertEquals(Prefs.Buffer.NORMAL, p.buffer)
        assertEquals(Prefs.Decoder.HARDWARE, p.decoder)
        assertEquals(Prefs().infoSeconds, p.infoSeconds)
        assertFalse(p.showLogos)
    }

    @Test
    fun `numbers are clamped`() {
        val p = prefs("""{"epg_offset_min":99999,"panel_timeout":-5}""")
        assertEquals(12 * 60, p.epgOffsetMin)
        assertEquals(0, p.panelTimeout)
    }

    @Test
    fun `glass opacity goes from 0 to 100`() {
        assertEquals(0, prefs("""{"glass_opacity":0}""").glassOpacity)
        assertEquals(100, prefs("""{"glass_opacity":150}""").glassOpacity)
        assertEquals(0, prefs("""{"glass_opacity":-10}""").glassOpacity)
    }

    @Test
    fun `hidden channels and channel order are read per playlist`() {
        val p = prefs("""{"hidden_channels":["p1:1001"],"channel_order":{"p1:12":["1003","1001"]}}""")
        assertTrue(p.isChannelHidden("p1", "1001"))
        assertFalse(p.isChannelHidden("p2", "1001"))
        assertEquals(listOf("1003", "1001"), p.channelOrder["p1:12"])
    }

    @Test
    fun `hdr mode defaults to auto`() {
        assertEquals(Prefs.Hdr.AUTO, prefs("""{"hdr":"x"}""").hdr)
        assertEquals(Prefs.Hdr.SDR, prefs("""{"hdr":"sdr"}""").hdr)
    }

    @Test
    fun `groups are only locked when a pin exists`() {
        val locked = """"locked_groups":["p1:adultos"]"""
        assertFalse(prefs("{$locked}").isLocked("p1", "adultos"))
        val p = prefs("""{"parental_pin_sha256":"${Prefs.hashPin("1234")}",$locked}""")
        assertTrue(p.isLocked("p1", "adultos"))
        assertFalse(p.isLocked("p2", "adultos"))
    }

    @Test
    fun `pin hash is stable and not the pin`() {
        assertEquals(Prefs.hashPin("0000"), Prefs.hashPin("0000"))
        assertNotEquals(Prefs.hashPin("0000"), Prefs.hashPin("0001"))
        assertEquals(64, Prefs.hashPin("1234").length)
    }
}
