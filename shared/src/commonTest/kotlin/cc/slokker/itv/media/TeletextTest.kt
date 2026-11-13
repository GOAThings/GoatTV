package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeletextTest {
    /** Um PES de teletexto DVB com as [lines] (cada uma: magazine, pacote, 40 bytes), como um emissor o manda. */
    private fun pes(vararg lines: Triple<Int, Int, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        out += 0x10
        for ((mag, packet, data) in lines) {
            out += 0x03
            out += 44
            out += 0xE0.toByte() // field parity e line offset
            out += 0xE4.toByte() // framing code (0x27 ao contrário)
            val a = (mag and 7) or ((packet and 1) shl 3)
            val b = packet shr 1
            out += TeletextDecoder.reverse(TeletextDecoder.hammingEncode(a))
            out += TeletextDecoder.reverse(TeletextDecoder.hammingEncode(b))
            for (x in data) out += TeletextDecoder.reverse(x)
        }
        return out.toByteArray()
    }

    /** O cabeçalho da página [page] (ex. 0x888), com C4 (apagar) e a variante nacional [national] (C12–C14). */
    private fun header(page: Int, erase: Boolean = true, national: Int = 0): Triple<Int, Int, ByteArray> {
        val d = ByteArray(40) { 0x20 }
        d[0] = TeletextDecoder.hammingEncode(page and 0xF)
        d[1] = TeletextDecoder.hammingEncode((page shr 4) and 0xF)
        for (i in 2..7) d[i] = TeletextDecoder.hammingEncode(0)
        d[3] = TeletextDecoder.hammingEncode(if (erase) 8 else 0)
        d[5] = TeletextDecoder.hammingEncode(8) // C6: legendas
        // C12 no bit 1, C13 no 2, C14 no 3 (a tabela lê-os como C12 C13 C14).
        val c = ((national shr 2) and 1 shl 1) or ((national shr 1) and 1 shl 2) or ((national and 1) shl 3)
        d[7] = TeletextDecoder.hammingEncode(c)
        return Triple((page shr 8) and 7, 0, d)
    }

    /** Uma linha de texto com paridade ímpar, dentro de uma caixa (como as legendas vêm). */
    private fun row(mag: Int, n: Int, text: String): Triple<Int, Int, ByteArray> {
        val d = ByteArray(40) { 0x20 }
        val content = byteArrayOf(0x0B, 0x0B) + text.encodeToByteArray() + byteArrayOf(0x0A, 0x0A)
        content.copyInto(d, 4)
        for (i in d.indices) {
            val v = d[i].toInt() and 0x7F
            d[i] = (if (v.countOneBits() % 2 == 0) v or 0x80 else v).toByte()
        }
        return Triple(mag, n, d)
    }

    @Test
    fun pagesFromTheDescriptor() {
        val pages = TeletextPage.of("por".encodeToByteArray() + byteArrayOf(0x10, 0x88.toByte()) + "eng".encodeToByteArray() + byteArrayOf(0x2C, 0x01.toByte()))
        assertEquals(listOf(TeletextPage("por", 2, 0x888), TeletextPage("eng", 5, 0x401)), pages)
    }

    @Test
    fun aPageShowsWhenTheNextHeaderCloses_itAndClears() {
        val d = TeletextDecoder(0x888)
        // A página chega; ainda não se mostra (pode faltar linhas).
        assertNull(d.feed(pes(header(0x888), row(0, 20, "Bom dia"), row(0, 22, "a todos"))))
        // O cabeçalho seguinte da mesma magazine (página de enchimento 0xFF) fecha-a.
        assertEquals(listOf("Bom dia", "a todos"), d.feed(pes(header(0x8FF))))
        // A mesma página outra vez: nada muda.
        assertNull(d.feed(pes(header(0x888), row(0, 20, "Bom dia"), row(0, 22, "a todos"), header(0x8FF))))
        // A página vazia limpa.
        assertEquals(emptyList(), d.feed(pes(header(0x888), header(0x8FF))))
    }

    @Test
    fun otherPagesAndMagazinesAreIgnored() {
        val d = TeletextDecoder(0x888)
        assertNull(d.feed(pes(header(0x100), row(1, 20, "Noticias"), header(0x1FF))))
        assertNull(d.feed(pes(header(0x801), row(0, 20, "Outra"), header(0x8FF))))
    }

    @Test
    fun nationalCharacters() {
        val d = TeletextDecoder(0x888)
        // Português/espanhol (5): 0x7C = ñ, 0x40 = ¡, 0x5B = á.
        d.feed(pes(header(0x888, national = 5), row(0, 22, "Ma|ana @Hola! [gua")))
        assertEquals(listOf("Mañana ¡Hola! água"), d.feed(pes(header(0x8FF))))
        val de = TeletextDecoder(0x777)
        de.feed(pes(header(0x777, national = 1), row(7, 21, "Gr}~e")))
        assertEquals(listOf("Grüße"), de.feed(pes(header(0x7FF))))
    }

    @Test
    fun hammingCorrectsOneBit() {
        for (v in 0 until 16) {
            val code = TeletextDecoder.hammingEncode(v).toInt() and 0xFF
            assertEquals(v, TeletextDecoder.hamming(code.toByte()))
            for (bit in 0 until 8) assertEquals(v, TeletextDecoder.hamming((code xor (1 shl bit)).toByte()))
        }
    }
}
