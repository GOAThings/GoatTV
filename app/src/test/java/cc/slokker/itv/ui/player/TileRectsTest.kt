package cc.slokker.itv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os lugares dos canais no ecrã: 2, 3 ou 4, em imagem na imagem ou dividido. */
class TileRectsTest {
    private fun TileRect.overlaps(o: TileRect) = x < o.x + o.w - 1e-4f && o.x < x + w - 1e-4f && y < o.y + o.h - 1e-4f && o.y < y + h - 1e-4f

    @Test
    fun splitNeverOverlapsAndStaysOnScreen() {
        for (n in 2..4) {
            val r = tileRects(n, DualLayout.SPLIT)
            assertEquals(n, r.size)
            for (a in r.indices) for (b in r.indices) if (a < b) assertTrue("$n: $a/$b", !r[a].overlaps(r[b]))
            r.forEach { assertTrue(it.x >= 0 && it.y >= 0 && it.x + it.w <= 1.0001f && it.y + it.h <= 1.0001f) }
        }
    }

    @Test
    fun pipHasOneFullScreenAndSmallOnesThatDoNotOverlap() {
        for (n in 2..4) {
            val r = tileRects(n, DualLayout.PIP)
            assertEquals(TileRect(0f, 0f, 1f, 1f), r[0])
            val small = r.drop(1)
            for (a in small.indices) for (b in small.indices) if (a < b) assertTrue(!small[a].overlaps(small[b]))
            small.forEach { assertTrue(it.y + it.h <= 1f) }
        }
    }
}
