package cc.slokker.itv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun `compares versions number by number`() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("1.0", "0.99.1"))
        assertTrue(Updater.isNewer("0.2.1", "0.2"))
        assertFalse(Updater.isNewer("0.2.0", "0.2.0"))
        assertFalse(Updater.isNewer("0.1.9", "0.2.0"))
        assertFalse(Updater.isNewer("", "0.1.0"))
    }
}
