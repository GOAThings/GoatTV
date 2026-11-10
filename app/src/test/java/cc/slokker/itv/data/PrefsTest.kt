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
    fun `an engine only the Apple apps know falls back to the default`() {
        // "aether" (o motor experimental do Apple TV e do iPhone) chega pela conta: aqui é o normal.
        assertEquals(Prefs.Engine.EXO, prefs("""{"engine":"aether"}""").engine)
        assertEquals(Prefs.Engine.VLC, prefs("""{"engine":"vlc"}""").engine)
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

    private fun attempts(failures: Int = 0, at: Long = 0L, saved: MutableList<Pair<Int, Long>> = ArrayList()) =
        PinAttempts(failures, at) { f, t -> saved += f to t }

    @Test
    fun `wrong PINs bring a growing wait and the right one is refused during it`() {
        val a = attempts()
        var now = 1_000_000L
        repeat(2) { assertFalse(a.check(now, false)); assertEquals(0L, a.waitMs(now)) }
        assertFalse(a.check(now, false))
        assertEquals(30_000L, a.waitMs(now))
        assertFalse("o PIN certo também espera", a.check(now + 1_000, true))
        assertEquals(29_000L, a.waitMs(now + 1_000))
        now += 30_000
        assertFalse(a.check(now, false))
        assertEquals(60_000L, a.waitMs(now))
        now += 60_000
        assertTrue(a.check(now, true))
        assertEquals(0L, a.waitMs(now))
        assertFalse("volta ao início depois de acertar", a.check(now, false))
        assertEquals(0L, a.waitMs(now))
    }

    @Test
    fun `the wait stops growing at one hour`() {
        assertEquals(0L, PinAttempts.delayFor(2))
        assertEquals(30_000L, PinAttempts.delayFor(3))
        assertEquals(3_600_000L, PinAttempts.delayFor(30))
        assertEquals(3_600_000L, PinAttempts.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun `attempts carry across prompts and restarts`() {
        val saved = ArrayList<Pair<Int, Long>>()
        val first = attempts(saved = saved)
        repeat(3) { first.check(5_000L, false) }
        val (failures, at) = saved.last()
        assertEquals(3, failures)
        val reopened = attempts(failures, at)
        assertEquals(10_000L, reopened.waitMs(25_000L))
        assertFalse(reopened.check(25_000L, true))
    }

    @Test
    fun `a clock moved back cannot lock the PIN forever`() {
        val a = attempts(failures = 3, at = 10_000_000L)
        assertEquals(30_000L, a.waitMs(1_000L))
        assertEquals(0L, a.waitMs(31_000L))
        assertTrue(a.check(31_000L, true))
    }

    private fun zapChannel(uid: Int, group: String) = Channel(uid, "k$uid", uid, "c$uid", null, group)

    @Test
    fun `zapping skips what is locked at that moment`() {
        val list = listOf(zapChannel(0, "a"), zapChannel(1, "locked"), zapChannel(2, "a"), zapChannel(3, "locked"))
        val locked: (Channel) -> Boolean = { it.categoryId == "locked" }
        assertEquals(2, nextAllowed(list, 0, 1, locked)?.uid)
        assertEquals(2, nextAllowed(list, 0, -1, locked)?.uid)
        assertEquals(0, nextAllowed(list, 2, 1, locked)?.uid)
        assertEquals(null, nextAllowed(list.filter(locked), 0, 1, locked))
        assertEquals(1, nextAllowed(list, 0, 1) { false }?.uid)
    }

    @Test
    fun `removing a playlist drops only its own prefs`() {
        val settings = JsonParser.parseString(
            """
            {"hidden_groups": ["p1:7", "p2:7"], "hidden_channels": ["p1:101", "p10:101"], "locked_groups": ["p1:9"],
             "group_order": {"p1": ["3"], "p2": ["1"]}, "extra_epg": {"p1": "https://e.example.com/x.xml"},
             "channel_order": {"p1:1": ["a"], "p2:1": ["b"]}, "channel_logos": {"p1:101": "", "p2:101": "https://l/x.png"},
             "accent": "mint", "unknown": {"p1": 1}}
            """,
        ).asJsonObject
        assertTrue(Prefs.removePlaylist(settings, "p1"))
        assertEquals(
            JsonParser.parseString(
                """
                {"hidden_groups": ["p2:7"], "hidden_channels": ["p10:101"], "locked_groups": [],
                 "group_order": {"p2": ["1"]}, "extra_epg": {}, "channel_order": {"p2:1": ["b"]},
                 "channel_logos": {"p2:101": "https://l/x.png"}, "accent": "mint", "unknown": {"p1": 1}}
                """,
            ),
            settings,
        )
        assertFalse(Prefs.removePlaylist(settings, "p1"))
    }

    @Test
    fun `afr mode keeps reading the old on-off key`() {
        // Gravado antes de haver modos: ligado era "sempre".
        assertEquals(Prefs.AfrMode.ALWAYS, prefs("""{"afr":true}""").afrMode)
        assertEquals(Prefs.AfrMode.OFF, prefs("""{"afr":false}""").afrMode)
        assertEquals(Prefs.AfrMode.OFF, prefs("""{}""").afrMode)
        // A chave antiga a false desliga sempre (a Apple TV e as versões antigas só mexem nela).
        assertEquals(Prefs.AfrMode.OFF, prefs("""{"afr":false,"afr_mode":"start"}""").afrMode)
        assertEquals(Prefs.AfrMode.START, prefs("""{"afr":true,"afr_mode":"start"}""").afrMode)
        assertEquals(Prefs.AfrMode.ALWAYS, prefs("""{"afr":true,"afr_mode":"x"}""").afrMode)
    }

    @Test
    fun `afr mode writes both keys`() {
        for (mode in Prefs.AfrMode.ALL) {
            val settings = JsonParser.parseString("""{"afr":true,"afr_mode":"always"}""").asJsonObject
            Prefs.afrModeValues(mode).forEach { (k, v) -> Prefs.put(settings, k, v) }
            val p = Prefs.from(settings)
            assertEquals(mode, p.afrMode)
            assertEquals(mode != Prefs.AfrMode.OFF, p.afr)
        }
        // Desligado não deixa o modo gravado.
        val off = JsonParser.parseString("""{"afr":true,"afr_mode":"start"}""").asJsonObject
        Prefs.afrModeValues(Prefs.AfrMode.OFF).forEach { (k, v) -> Prefs.put(off, k, v) }
        assertEquals("""{"afr":false}""", off.toString())
    }

    @Test
    fun `zap delay and afr fallback accept only their options`() {
        assertEquals(Prefs.ZapDelay.AUTO, Prefs().zapDelayMs)
        assertEquals(1_000, prefs("""{"zap_delay_ms":1000}""").zapDelayMs)
        assertEquals(Prefs.ZapDelay.AUTO, prefs("""{"zap_delay_ms":1234}""").zapDelayMs)
        assertEquals(Prefs.AfrFallback.HZ50, prefs("""{"afr_fallback":"50"}""").afrFallback)
        assertEquals(Prefs.AfrFallback.NONE, prefs("""{"afr_fallback":"48"}""").afrFallback)
        assertEquals(59.94f, Prefs.AfrFallback.fps(Prefs.AfrFallback.HZ5994))
    }
}
