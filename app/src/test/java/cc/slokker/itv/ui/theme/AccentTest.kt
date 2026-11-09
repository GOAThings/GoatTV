package cc.slokker.itv.ui.theme

import cc.slokker.itv.data.Prefs
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentTest {
    /** Uma cor nova nas Definições sem o id em Prefs voltava sozinha ao padrão (Prefs.from não a aceitava). */
    @Test
    fun `every accent the settings offer is one prefs accepts, in the same order`() {
        assertEquals(Prefs.Accents.IDS, Accent.ALL.map { it.id })
        for (id in Prefs.Accents.IDS) {
            assertEquals(id, Prefs.from(JsonObject().apply { addProperty(Prefs.Key.ACCENT, id) }).accent)
        }
        assertEquals(Accent.ALL.first(), Accent.byId("nope"))
    }
}
