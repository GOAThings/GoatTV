package cc.slokker.itv.ui.mobile

import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.Reminder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowStateTest {
    private val rtp = Channel(uid = 1, key = "rtp", num = 1, name = "RTP 1", logo = null, categoryId = "pt")
    private val sic = Channel(uid = 2, key = "sic", num = 2, name = "SIC", logo = null, categoryId = "pt")
    private val news = EpgEntry("Telejornal", 1_000L, 2_000L)

    @Test
    fun `favourites and reminders count only for the open playlist`() {
        val rows = RowState.of(
            Prefs(),
            favorites = mapOf("p1" to listOf("rtp"), "p2" to listOf("sic")),
            reminders = listOf(Reminder("p1", "rtp", "RTP 1", "Telejornal", 1_000L), Reminder("p2", "sic", "SIC", "Jornal", 1_000L)),
            playlistId = "p1",
        )
        assertTrue(rows.isFavorite(rtp))
        assertFalse(rows.isFavorite(sic))
        assertTrue(rows.reminded(rtp, news))
        assertFalse(rows.reminded(sic, news))
        // Outro programa do mesmo canal.
        assertFalse(rows.reminded(rtp, news.copy(startMs = 2_000L)))
    }

    @Test
    fun `without an open playlist nothing is marked`() {
        val rows = RowState.of(Prefs(), mapOf("p1" to listOf("rtp")), listOf(Reminder("p1", "rtp", "RTP 1", "T", 1_000L)), null)
        assertFalse(rows.isFavorite(rtp))
        assertFalse(rows.reminded(rtp, news))
    }

    @Test
    fun `list settings come from the prefs`() {
        val rows = RowState.of(Prefs(showLogos = false, showNumbers = true, epgInList = false), emptyMap(), emptyList(), "p1")
        assertFalse(rows.showLogos)
        assertTrue(rows.showNumbers)
        assertFalse(rows.epgInList)
    }
}
