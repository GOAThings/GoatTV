package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TsClockTest {
    @Test
    fun wrapsAt33Bits() {
        val c = TsClock()
        val end = TsClock.WRAP - 3000
        assertEquals(end, c.unwrap(end))
        assertEquals(TsClock.WRAP + 600, c.unwrap(600))
        assertFalse(c.jumped)
        // Uma imagem B de antes da volta, já depois dela.
        assertEquals(end + 1500, c.unwrap(end + 1500))
        assertEquals(TsClock.WRAP + 4200, c.unwrap(4200))
    }

    @Test
    fun bigJumpsAreReported() {
        val c = TsClock()
        c.unwrap(1_000_000)
        c.unwrap(1_003_600)
        assertFalse(c.jumped)
        c.unwrap(90_000_000)
        assertTrue(c.jumped)
        assertEquals(-1, c.unwrap(-1))
    }
}
