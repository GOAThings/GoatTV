package cc.slokker.itv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Os passos da velocidade no catch-up e atrás do direto. */
class PlaybackSpeedTest {
    @Test
    fun `up and down stop at the ends, the phone button goes round`() {
        assertEquals(1.25f, PlaybackSpeed.faster(1f))
        assertEquals(2f, PlaybackSpeed.faster(2f))
        assertEquals(1f, PlaybackSpeed.slower(1.25f))
        assertEquals(1f, PlaybackSpeed.slower(1f))
        assertEquals(1.5f, PlaybackSpeed.next(1.25f))
        assertEquals(1f, PlaybackSpeed.next(2f))
    }
}
