package cc.slokker.itv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProHealthTest {
    @Test
    fun downUntilTheDeadlineAndSurvivesSaving() {
        val h = ProHealth()
        h.markDown("p1", "13824", 1_000)
        assertTrue(h.isDown("p1", "13824", 2_000))
        assertFalse(h.isDown("p1", "14103", 2_000))
        assertFalse(h.isDown("p2", "13824", 2_000))
        val back = ProHealth.decode(h.encode(2_000), 2_000)
        assertTrue(back.isDown("p1", "13824", 3_000))
        assertFalse(back.isDown("p1", "13824", 1_000 + ProHealth.TTL_MS))
        assertEquals("", ProHealth.decode(h.encode(2_000), 1_000 + ProHealth.TTL_MS).encode(1_000 + ProHealth.TTL_MS))
    }

    @Test
    fun pickedInThePanelIsUpAgain() {
        val h = ProHealth()
        h.markDown("p1", "a|b", 10)
        assertTrue(h.isDown("p1", "a|b", 20))
        assertTrue(h.markUp("p1", "a|b"))
        assertFalse(h.isDown("p1", "a|b", 20))
        assertFalse(h.markUp("p1", "a|b"))
    }

    @Test
    fun junkLinesAreSkipped() {
        val h = ProHealth.decode("x\tp|c\n\n5\tnokey\n7\tp|c", 10)
        assertTrue(h.isDown("p", "c", 10))
        assertEquals("7\tp|c", h.encode(10))
    }
}
