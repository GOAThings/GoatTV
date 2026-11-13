package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Cea708Test {
    /** Um pacote DTVCC com um bloco do serviço 1 ([commands]), em entradas [cc_type, b1, b2] como vêm no cc_data. */
    private fun packet(vararg commands: Int): ByteArray {
        val block = byteArrayOf(((1 shl 5) or commands.size).toByte()) + ByteArray(commands.size) { commands[it].toByte() }
        // O tamanho do pacote conta-se em pares (com o cabeçalho): completa-se com zeros.
        val body = if ((block.size + 1) % 2 == 0) block else block + byteArrayOf(0)
        val code = (body.size + 1) / 2
        val bytes = byteArrayOf(code.toByte()) + body
        val out = ArrayList<Byte>()
        out += 3; out += bytes[0]; out += bytes[1]
        var i = 2
        while (i < bytes.size) {
            out += 2; out += bytes[i]; out += if (i + 1 < bytes.size) bytes[i + 1] else 0
            i += 2
        }
        return out.toByteArray()
    }

    private fun text(s: String) = s.map { it.code }.toIntArray()

    /** DF0: visível, âncora 70, 2 linhas, 32 colunas. */
    private val define = intArrayOf(0x98, 0x20, 70, 0, 0x01, 31, 0)

    @Test
    fun aWindowWithTwoLinesShowsAndClears() {
        val d = Cea708Decoder()
        assertEquals(listOf("HELLO", "WORLD"), d.feed(packet(*define, *text("HELLO"), 0x0D, *text("WORLD"))))
        // Uma terceira linha numa janela de duas: sobe.
        assertEquals(listOf("WORLD", "AGAIN"), d.feed(packet(0x0D, *text("AGAIN"))))
        // CLW da janela 0.
        assertEquals(emptyList(), d.feed(packet(0x88, 0x01)))
        assertNull(d.feed(packet(0x88, 0x01)))
    }

    @Test
    fun hiddenUntilDisplayed() {
        val d = Cea708Decoder()
        // DF1 escondida (pop-on): monta-se e só aparece com o DSW.
        assertNull(d.feed(packet(0x99, 0x00, 70, 0, 0x00, 31, 0, *text("Caf"), 0xE9)))
        assertEquals(listOf("Café"), d.feed(packet(0x89, 0x02)))
    }

    @Test
    fun the608WinsWhenItHasTextOtherwiseThe708() {
        val c = CaptionDecoder()
        // Só 708 (o 608 vem como enchimento).
        val padding = byteArrayOf(0, 0x80.toByte(), 0x80.toByte())
        assertEquals(listOf("HI"), c.feed(padding + packet(*define, *text("HI"))))
        assertEquals(true, c.found)
        // Um 608 com texto (RCL, PAC, "AB", EOC) passa à frente.
        fun pair(a: Int, b: Int) = byteArrayOf(0, a.toByte(), b.toByte())
        val pop = pair(0x14, 0x20) + pair(0x14, 0x70) + pair('A'.code, 'B'.code) + pair(0x14, 0x2F)
        assertEquals(listOf("AB"), c.feed(pop))
    }
}
