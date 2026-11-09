package cc.slokker.itv.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TestStringsTest {
    @Test
    fun `test strings are stable and distinct`() {
        val s = TestStrings.install()
        assertEquals(s.epgRefreshed, s.epgRefreshed)
        assertNotEquals(s.epgRefreshed, s.epgRefreshFailed)
        assertEquals(s.saveFailed(500), s.saveFailed(500))
    }
}
