package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** Os códigos de pixels de 2 e 8 bits, as paletas por omissão e o mapa 2→4 (EN 300 743, 7.2.5). */
class DvbSubtitleCodesTest {
    private class Writer {
        val bits = ArrayList<Int>()
        fun put(v: Int, n: Int) = apply { for (i in n - 1 downTo 0) bits += (v shr i) and 1 }
        fun bytes(): ByteArray {
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { a, k -> (a shl 1) or bits[i * 8 + k] }.toByte() }
        }
    }

    private fun seg(type: Int, body: ByteArray) =
        byteArrayOf(0x0F, type.toByte(), 0, 1, (body.size shr 8).toByte(), body.size.toByte()) + body

    /** Uma página com uma região [w]×[h] de profundidade [depth] (1 = 2 bits, 2 = 4, 3 = 8) e um objeto com [line] em todas as linhas. */
    private fun displaySet(w: Int, h: Int, depth: Int, line: ByteArray): ByteArray {
        val page = seg(0x10, byteArrayOf(0, ((2 shl 2) or 3).toByte(), 0, 0xFF.toByte(), 0, 10, 0, 20))
        val region = seg(
            0x11,
            byteArrayOf(0, 0x07, 0, w.toByte(), 0, h.toByte(), ((depth shl 5) or (depth shl 2) or 3).toByte(), 0, 0, 0x03, 0, 1, 0, 0, 0xF0.toByte(), 0),
        )
        val field = line + byteArrayOf(0xF0.toByte())
        val obj = seg(0x13, byteArrayOf(0, 1, 0x01, 0, field.size.toByte(), 0, 0) + field)
        return page + region + obj + seg(0x80, ByteArray(0))
    }

    private fun DvbSubtitleImage.argb(x: Int): List<Int> = (0 until 4).map { rgba[x * 4 + it].toInt() and 0xFF }

    @Test
    fun twoBitCodesWithRunsInAFourBitRegion() {
        // 1 pixel de 1, corrida de 5 de 2 (switch_1 = 1: 3 bits + 3), 2 transparentes (00 0 0 01), 1 de 3, fim.
        val line = byteArrayOf(0x10) + Writer()
            .put(1, 2)
            .put(0, 2).put(1, 1).put(2, 3).put(2, 2)
            .put(0, 2).put(0, 1).put(0, 1).put(1, 2)
            .put(3, 2)
            .put(0, 2).put(0, 1).put(0, 1).put(0, 2)
            .bytes()
        val page = DvbSubtitleDecoder(1, 1).decode(displaySet(9, 2, 2, line))!!
        val image = page.images.single()
        assertEquals(listOf(10, 20, 9, 2), listOf(image.x, image.y, image.width, image.height))
        // O mapa 2→4 por omissão: 1 → 7 (branco), 2 → 8 (preto, opaco), 3 → 15 (cinzento 127).
        assertEquals(listOf(255, 255, 255, 255), image.argb(0))
        for (x in 1..5) assertEquals(listOf(0, 0, 0, 255), image.argb(x), "x=$x")
        assertEquals(listOf(0, 0, 0, 0), image.argb(6))
        assertEquals(listOf(0, 0, 0, 0), image.argb(7))
        assertEquals(listOf(127, 127, 127, 255), image.argb(8))
    }

    @Test
    fun eightBitCodesWithRuns() {
        // 2 pixels de 0x10 (um a um), corrida de 4 de 0x20 (1 + 7 bits + código), 3 transparentes (0 + 7 bits), fim.
        val line = byteArrayOf(0x12) + Writer()
            .put(0x10, 8).put(0x10, 8)
            .put(0, 8).put(1, 1).put(4, 7).put(0x20, 8)
            .put(0, 8).put(0, 1).put(3, 7)
            .put(0, 8).put(0, 1).put(0, 7)
            .bytes()
        val image = DvbSubtitleDecoder(1, 1).decode(displaySet(9, 2, 3, line))!!.images.single()
        // A paleta de 8 bits por omissão: 0x10 = (170, 0, 0); 0x20 = (0, 170, 0); 0 = transparente.
        assertEquals(listOf(170, 0, 0, 255), image.argb(0))
        assertEquals(listOf(170, 0, 0, 255), image.argb(1))
        for (x in 2..5) assertEquals(listOf(0, 170, 0, 255), image.argb(x), "x=$x")
        for (x in 6..8) assertEquals(listOf(0, 0, 0, 0), image.argb(x), "x=$x")
    }

    @Test
    fun otherPagesAreIgnored() {
        val line = byteArrayOf(0x12) + Writer().put(0x10, 8).put(0, 8).put(0, 1).put(0, 7).bytes()
        assertEquals(null, DvbSubtitleDecoder(2, 2).decode(displaySet(1, 2, 3, line)))
    }
}
