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

    @Test
    fun variantOfIsWhatTheNameHasBesidesTheChannel() {
        assertEquals("ᴴᴰ", ProHealth.variantOf("SIC ᴴᴰ"))
        assertEquals("FHD", ProHealth.variantOf("RTP 1 FHD"))
        assertEquals("", ProHealth.variantOf("RTP 1"))
    }

    @Test
    fun twoFailuresOfAVersionSendTheWholeVersionDown() {
        val h = ProHealth()
        h.markDown("p1", "sic", 1_000, "SIC ᴴᴰ")
        // Uma só: os outros "ᴴᴰ" ainda vêm à frente.
        assertFalse(h.isDown("p1", "rtp1", 1_500, "RTP 1 ᴴᴰ"))
        h.markDown("p1", "tvi", 2_000, "TVI ᴴᴰ")
        assertTrue(h.isDown("p1", "rtp1", 2_500, "RTP 1 ᴴᴰ"))
        // As outras versões da lista e a mesma versão noutra lista ficam como estavam.
        assertFalse(h.isDown("p1", "rtp1-plain", 2_500, "RTP 1"))
        assertFalse(h.isDown("p1", "rtp1-fhd", 2_500, "RTP 1 FHD"))
        assertFalse(h.isDown("p2", "rtp1", 2_500, "RTP 1 ᴴᴰ"))
        // Guarda-se, e a pessoa escolher um "ᴴᴰ" no painel devolve a versão toda.
        val back = ProHealth.decode(h.encode(3_000), 3_000)
        assertTrue(back.isDown("p1", "rtp1", 3_500, "RTP 1 ᴴᴰ"))
        assertTrue(back.markUp("p1", "rtp1", "RTP 1 ᴴᴰ"))
        assertFalse(back.isDown("p1", "rtp1", 3_500, "RTP 1 ᴴᴰ"))
        // Os que falharam eles próprios continuam no fim; os outros "ᴴᴰ" voltam.
        assertTrue(back.isDown("p1", "sic", 3_500, "SIC ᴴᴰ"))
        assertFalse(back.isDown("p1", "rtp2", 3_500, "RTP 2 ᴴᴰ"))
    }

    @Test
    fun plainNamesNeverGroup() {
        val h = ProHealth()
        h.markDown("p1", "a", 1_000, "RTP 1")
        h.markDown("p1", "b", 1_000, "SIC")
        assertFalse(h.isDown("p1", "c", 1_500, "TVI"))
    }
}
