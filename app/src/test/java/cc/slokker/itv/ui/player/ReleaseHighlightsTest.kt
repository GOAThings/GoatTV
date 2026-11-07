package cc.slokker.itv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** As novidades que o cartão de versão nova tira das notas do GitHub. */
class ReleaseHighlightsTest {

    @Test
    fun bulletsWithoutHeadingsOrBold() {
        val notes = "Android TV:\n- New **Connection** page.\n- Fixed-width digits.\n\nHome screen:\n- Schedule.\n- Fourth."
        assertEquals(listOf("New Connection page.", "Fixed-width digits.", "Schedule."), releaseHighlights(notes))
    }

    @Test
    fun plainLinesWhenThereIsNoList() {
        assertEquals(listOf("Version 0.9.21"), releaseHighlights("Version 0.9.21"))
        assertEquals(emptyList<String>(), releaseHighlights("  \n"))
    }
}
