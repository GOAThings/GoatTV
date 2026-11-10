package cc.slokker.itv.data

import cc.slokker.itv.data.epg.GuideWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quantos dias de guia se guardam (Definições → Guia). */
class GuideWindowTest {
    private val hour = 3600_000L

    @Test
    fun `the default keeps a day back and a day and a half ahead`() {
        val w = GuideWindow.of(Prefs())
        assertEquals(GuideWindow.DEFAULT, w)
        assertTrue(w.keeps(-23 * hour, -22 * hour, now = 0))
        assertFalse(w.keeps(-30 * hour, -25 * hour, now = 0))
        assertFalse(w.keeps(37 * hour, 38 * hour, now = 0))
    }

    @Test
    fun `a week ahead keeps next week and bad values fall back`() {
        val p = Prefs.from(com.google.gson.JsonParser.parseString("""{"epg_future_hours":168,"epg_past_hours":5}""").asJsonObject)
        assertEquals(24, p.epgPastHours)
        assertTrue(GuideWindow.of(p).keeps(160 * hour, 161 * hour, now = 0))
    }
}
