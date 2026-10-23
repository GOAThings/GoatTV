package cc.slokker.itv.data

import cc.slokker.itv.ui.player.FrameRateMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatcherTest {

    // Modos típicos de uma TV 4K: (modeId, refresh).
    private val modes = listOf(1 to 60f, 2 to 59.94f, 3 to 50f, 4 to 30f, 5 to 25f, 6 to 24f, 7 to 23.976f)

    @Test
    fun `25 and 50 fps use 50 Hz, not 25 Hz`() {
        assertEquals(3, FrameRateMatcher.bestMode(modes, 25f))
        assertEquals(3, FrameRateMatcher.bestMode(modes, 50f))
    }

    @Test
    fun `ntsc rates use 59_94 Hz`() {
        assertEquals(2, FrameRateMatcher.bestMode(modes, 29.97f))
        assertEquals(2, FrameRateMatcher.bestMode(modes, 59.94f))
    }

    @Test
    fun `film uses the low 24 Hz modes when there is no 48 Hz`() {
        assertEquals(7, FrameRateMatcher.bestMode(modes, 23.976f))
        assertEquals(6, FrameRateMatcher.bestMode(modes, 24f))
    }

    @Test
    fun `no matching mode leaves the tv alone`() {
        assertNull(FrameRateMatcher.bestMode(listOf(1 to 60f), 25f))
    }
}
