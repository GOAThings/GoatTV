package cc.slokker.itv.data

import cc.slokker.itv.data.RefreshTracker.Slot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshTrackerTest {
    private var clock = 100 * 3600_000L
    private val tracker = RefreshTracker { clock }
    private val hour = 3600_000L

    @Test
    fun `every open of the app checks once, failures wait before trying again`() {
        val fetchedAt = clock - hour
        assertTrue(tracker.isDue(Slot.GUIDE, "p1", hours = 0, fetchedAt = fetchedAt))
        tracker.attempted(Slot.GUIDE, "p1")
        assertFalse(tracker.isDue(Slot.GUIDE, "p1", hours = 0, fetchedAt = fetchedAt))
        // Voltar à app volta a contar; mas a última tentativa foi agora mesmo.
        tracker.clearChecked()
        assertTrue(tracker.isDue(Slot.GUIDE, "p1", hours = 0, fetchedAt = fetchedAt))
        tracker.attempted(Slot.GUIDE, "p1")
        tracker.clearChecked()
        tracker.attempted(Slot.GUIDE, "p1")
        assertFalse(tracker.isDue(Slot.GUIDE, "p1", hours = 0, fetchedAt = fetchedAt))
    }

    @Test
    fun `an interval waits for the hours, and then for the retry gap after a failed try`() {
        val fetchedAt = clock - 5 * hour
        assertFalse(tracker.isDue(Slot.PLAYLIST, "p1", hours = 6, fetchedAt = fetchedAt))
        clock += hour
        assertTrue(tracker.isDue(Slot.PLAYLIST, "p1", hours = 6, fetchedAt = fetchedAt))
        tracker.attempted(Slot.PLAYLIST, "p1")
        clock += RefreshPolicy.RETRY_MS - 1
        assertFalse(tracker.isDue(Slot.PLAYLIST, "p1", hours = 6, fetchedAt = fetchedAt))
        clock += 1
        assertTrue(tracker.isDue(Slot.PLAYLIST, "p1", hours = 6, fetchedAt = fetchedAt))
    }

    @Test
    fun `slots and playlists are separate, and a fresh download is not a failed try`() {
        tracker.attempted(Slot.GUIDE, "p1")
        assertTrue(tracker.isChecked(Slot.GUIDE, "p1"))
        assertFalse(tracker.isChecked(Slot.EXTRA_GUIDE, "p1"))
        assertFalse(tracker.isChecked(Slot.GUIDE, "p2"))
        tracker.markChecked(Slot.PLAYLIST, "p1")
        assertFalse(tracker.isDue(Slot.PLAYLIST, "p1", hours = 0, fetchedAt = clock))
        assertTrue(tracker.isDue(Slot.PLAYLIST, "p1", hours = 1, fetchedAt = clock - 2 * hour))
        tracker.clear()
        assertFalse(tracker.isChecked(Slot.GUIDE, "p1"))
        // Nunca descarregado conta sempre como em atraso.
        assertTrue(tracker.isDue(Slot.EXTRA_GUIDE, "p1", hours = 24, fetchedAt = 0L))
    }
}
