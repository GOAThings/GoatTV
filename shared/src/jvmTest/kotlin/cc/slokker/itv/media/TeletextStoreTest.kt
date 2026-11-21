package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O teletexto todo (ver TeletextStore): pacotes feitos como o emissor os manda (EN 300 706, dentro de PES DVB). */
class TeletextStoreTest {
    private val pid = 0x45

    private fun h(v: Int) = TeletextDecoder.hammingEncode(v)

    /** Um PES com as linhas [lines] (magazine, pacote, 40 bytes). */
    private fun pes(vararg lines: Triple<Int, Int, ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        out += 0x10
        for ((mag, packet, data) in lines) {
            out += 0x02
            out += 44
            out += 0xE0.toByte()
            out += 0xE4.toByte()
            out += TeletextDecoder.reverse(h((mag and 7) or ((packet and 1) shl 3)))
            out += TeletextDecoder.reverse(h(packet shr 1))
            for (x in data) out += TeletextDecoder.reverse(x)
        }
        return out.toByteArray()
    }

    /** Paridade ímpar em cada byte (como as linhas de texto vêm). */
    private fun odd(d: ByteArray): ByteArray {
        for (i in d.indices) {
            val v = d[i].toInt() and 0x7F
            d[i] = (if (v.countOneBits() % 2 == 0) v or 0x80 else v).toByte()
        }
        return d
    }

    /** O cabeçalho da página [page] (0x100…), subpágina [sub], com o texto [text] nas colunas 8–39. */
    private fun header(
        page: Int,
        sub: Int = 0,
        erase: Boolean = true,
        national: Int = 0,
        serial: Boolean = false,
        subtitle: Boolean = false,
        text: String = "GOAT TV    21 NOV 12:34:56",
    ): Triple<Int, Int, ByteArray> {
        val d = ByteArray(40) { 0x20 }
        d[0] = h(page and 0xF)
        d[1] = h((page shr 4) and 0xF)
        d[2] = h(sub and 0xF)
        d[3] = h(((sub shr 4) and 7) or (if (erase) 8 else 0))
        d[4] = h((sub shr 8) and 0xF)
        d[5] = h(((sub shr 12) and 3) or (if (subtitle) 8 else 0))
        d[6] = h(0)
        val c = (if (serial) 1 else 0) or (((national shr 2) and 1) shl 1) or (((national shr 1) and 1) shl 2) or ((national and 1) shl 3)
        d[7] = h(c)
        odd(text.padEnd(32).take(32).encodeToByteArray()).copyInto(d, 8)
        return Triple((page shr 8) and 7, 0, d)
    }

    private fun row(page: Int, n: Int, vararg content: Any): Triple<Int, Int, ByteArray> {
        val d = ByteArray(40) { 0x20 }
        var at = 0
        for (c in content) when (c) {
            is String -> for (ch in c) d[at++] = ch.code.toByte()
            is Int -> d[at++] = c.toByte()
        }
        return Triple((page shr 8) and 7, n, odd(d))
    }

    private fun store(): TeletextStore {
        val s = TeletextStore()
        val config = "por".encodeToByteArray() + byteArrayOf(0x09, 0x00)
        assertEquals(pid, s.tracks(listOf(TsTrack(pid, 6, Codec.TELETEXT, TrackKind.SUBTITLE, "por", config))))
        return s
    }

    private fun TeletextStore.feed(vararg lines: Triple<Int, Int, ByteArray>) = feed(pid, pes(*lines))

    @Test
    fun aPageIsKeptWhenTheNextHeaderOfItsMagazineArrives() {
        val s = store()
        assertTrue(s.announced)
        s.feed(header(0x100), row(0x100, 1, "Indice"))
        // Ainda a meio: não se mostra.
        assertFalse(s.screen(100, -1, "").found)
        s.feed(header(0x101))
        val screen = s.screen(100, -1, "")
        assertTrue(screen.found)
        assertEquals("Indice", screen.rowText(1).trim())
        // O cabeçalho: o número e o do canal com o relógio.
        assertEquals(" P100   GOAT TV    21 NOV 12:34:56", screen.rowText(0).trimEnd())
        assertTrue(s.hasPages)
        assertTrue(s.available)
    }

    @Test
    fun otherMagazinesDoNotCloseAPageInParallelMode_butDoInSerialMode() {
        val s = store()
        s.feed(header(0x100), row(0x100, 1, "Um"), header(0x200), row(0x200, 2, "Dois"))
        assertFalse(s.has(100))
        // As linhas da magazine 1 continuam a ir para a 100, mesmo depois do cabeçalho da 200.
        s.feed(row(0x100, 3, "Tres"), header(0x1FF))
        assertEquals("Tres", s.screen(100, -1, "").rowText(3).trim())
        val serial = store()
        serial.feed(header(0x300, serial = true), row(0x300, 1, "Serie"), header(0x400, serial = true))
        assertTrue(serial.has(300))
    }

    @Test
    fun aPageThatIsNotYetHereWaits() {
        val s = store()
        s.feed(header(0x100), header(0x1FF))
        val screen = s.screen(777, -1, "7--")
        assertFalse(screen.found)
        assertEquals(" P7--   GOAT TV", screen.rowText(0).take(15))
    }

    @Test
    fun subpagesAreKeptApartAndTheLatestShowsByDefault() {
        val s = store()
        s.feed(header(0x150, sub = 1), row(0x150, 1, "Primeira"), header(0x150, sub = 2), row(0x150, 1, "Segunda"), header(0x1FF))
        assertEquals(2, s.subpageCount(150))
        assertEquals("Segunda", s.screen(150, -1, "").rowText(1).trim())
        val first = s.screen(150, 0, "")
        assertEquals("Primeira", first.rowText(1).trim())
        assertEquals(0, first.subpage)
        assertEquals(2, first.subpages)
    }

    @Test
    fun withoutEraseTheRowsNotSentAgainStay() {
        val s = store()
        s.feed(header(0x100), row(0x100, 1, "Fica"), row(0x100, 2, "Velha"), header(0x1FF))
        s.feed(header(0x100, erase = false), row(0x100, 2, "Nova"), header(0x1FF))
        val screen = s.screen(100, -1, "")
        assertEquals("Fica", screen.rowText(1).trim())
        assertEquals("Nova", screen.rowText(2).trim())
        s.feed(header(0x100, erase = true), row(0x100, 2, "So"), header(0x1FF))
        assertEquals("", s.screen(100, -1, "").rowText(1).trim())
    }

    @Test
    fun portugueseNationalCharacters() {
        val s = store()
        // Português/espanhol (variante 5): [ á, \ é, ] í, { ü… ; 0x7C ñ, 0x7D è, 0x7E à, 0x60 ¿, 0x23 ç.
        s.feed(header(0x100, national = 5), row(0x100, 1, "Informa#~o"), header(0x1FF))
        assertEquals("Informaçào", s.screen(100, -1, "").rowText(1).trim())
    }

    @Test
    fun coloursAndBackgrounds() {
        val s = store()
        // Amarelo, texto, fundo novo (amarelo), vermelho, texto, fundo preto.
        s.feed(header(0x100), row(0x100, 1, 0x03, "A", 0x1D, 0x01, "B", 0x1C, "C"), header(0x1FF))
        val p = s.screen(100, -1, "")
        assertEquals(7, p.foreground(1, 0))
        assertEquals(3, p.foreground(1, 1))
        assertEquals(0, p.background(1, 1))
        // O "fundo novo" vale já na célula dele.
        assertEquals(3, p.background(1, 2))
        assertEquals(1, p.foreground(1, 4))
        assertEquals(3, p.background(1, 4))
        assertEquals(0, p.background(1, 5))
    }

    @Test
    fun mosaicsContiguousSeparatedAndHeld() {
        val s = store()
        // Mosaico branco: 0x7F (todos os blocos), 0x21 (só o de cima à esquerda), 0x41 ("A" passa como letra).
        // Depois separado, hold, e um atributo que mostra o último mosaico.
        s.feed(header(0x100), row(0x100, 1, 0x17, 0x7F, 0x21, 0x41, 0x1A, 0x35, 0x1E, 0x12), header(0x1FF))
        val p = s.screen(100, -1, "")
        assertEquals(-1, p.mosaic(1, 0))
        assertEquals(0x3F, p.mosaic(1, 1))
        assertEquals(1, p.mosaic(1, 2))
        assertEquals("A", p.text(1, 3))
        assertEquals(-1, p.mosaic(1, 3))
        // 0x35: blocos 0, 2, 4 (a coluna da esquerda), separados.
        assertEquals(0x15, p.mosaic(1, 5))
        assertTrue((p.attributes(1, 5) and TeletextScreen.SEPARATED) != 0)
        assertFalse((p.attributes(1, 1) and TeletextScreen.SEPARATED) != 0)
        // Com o hold, o 0x12 (mosaico verde) mostra o último mosaico em vez de um espaço.
        assertEquals(0x15, p.mosaic(1, 7))
    }

    @Test
    fun doubleHeightTakesTheRowBelow() {
        val s = store()
        s.feed(header(0x100), row(0x100, 3, 0x0D, "Grande"), row(0x100, 4, "Escondida"), row(0x100, 5, "Normal"), header(0x1FF))
        val p = s.screen(100, -1, "")
        assertTrue((p.attributes(3, 1) and TeletextScreen.DOUBLE_TOP) != 0)
        assertEquals("G", p.text(4, 1))
        assertTrue((p.attributes(4, 1) and TeletextScreen.DOUBLE_BOTTOM) != 0)
        assertEquals("Grande", p.rowText(4).trim())
        assertEquals("Normal", p.rowText(5).trim())
    }

    @Test
    fun flashConcealAndBoxes() {
        val s = store()
        s.feed(header(0x100), row(0x100, 1, 0x08, "P", 0x09, "Q", 0x18, "R", 0x07, "S"), header(0x1FF))
        val p = s.screen(100, -1, "")
        assertTrue((p.attributes(1, 1) and TeletextScreen.FLASH) != 0)
        assertFalse((p.attributes(1, 3) and TeletextScreen.FLASH) != 0)
        assertTrue((p.attributes(1, 5) and TeletextScreen.CONCEAL) != 0)
        // Uma cor de texto acaba com o esconder.
        assertFalse((p.attributes(1, 7) and TeletextScreen.CONCEAL) != 0)
        // Uma página de legendas: só as caixas se veem.
        s.feed(header(0x888, subtitle = true), row(0x888, 22, "fora", 0x0B, 0x0B, "dentro", 0x0A, 0x0A), header(0x8FF))
        val sub = s.screen(888, -1, "")
        assertTrue(sub.boxed)
        assertTrue((sub.attributes(22, 0) and TeletextScreen.HIDDEN) != 0)
        assertFalse((sub.attributes(22, 6) and TeletextScreen.HIDDEN) != 0)
        assertTrue((sub.attributes(0, 10) and TeletextScreen.HIDDEN) != 0)
    }

    @Test
    fun fastTextLinksForTheColourKeys() {
        val s = store()
        val d = ByteArray(40) { h(0) }
        // Vermelho 102, verde 200 (magazine relativa: 1 xor 3 = 2), amarelo 300, azul sem ligação (FF).
        fun link(k: Int, units: Int, tens: Int, relative: Int) {
            val at = 1 + k * 6
            d[at] = h(units)
            d[at + 1] = h(tens)
            d[at + 2] = h(0xF)
            d[at + 3] = h(7 or ((relative and 1) shl 3))
            d[at + 4] = h(0xF)
            d[at + 5] = h(3 or (((relative shr 1) and 3) shl 2))
        }
        link(0, 2, 0, 0)
        link(1, 0, 0, 3)
        link(2, 0, 0, 2)
        link(3, 0xF, 0xF, 0)
        s.feed(header(0x100), Triple(1, 27, d), header(0x1FF))
        val p = s.screen(100, -1, "")
        assertEquals(listOf(102, 200, 300, -1), (0 until 4).map { p.link(it) })
    }

    @Test
    fun x26PutsG2CharactersAndAccents() {
        val s = store()
        val d = ByteArray(40) { h(0) }
        fun triplet(k: Int, address: Int, mode: Int, value: Int) =
            TeletextDecoder.hamming24Encode(address or (mode shl 6) or (value shl 11)).copyInto(d, 1 + k * 3)
        // Linha 2 (endereço 42, modo 0x04), coluna 0: "e" com acento agudo (modo 0x12); coluna 1: o ½ do G2 (0x3D).
        triplet(0, 42, 0x04, 0)
        triplet(1, 0, 0x12, 'e'.code)
        triplet(2, 1, 0x0F, 0x3D)
        triplet(3, 63, 0x1F, 0)
        for (k in 4 until 13) triplet(k, 63, 0x1F, 0)
        s.feed(header(0x100), Triple(1, 26, d), row(0x100, 2, "xx"), header(0x1FF))
        val p = s.screen(100, -1, "")
        assertEquals("é", p.text(2, 0))
        assertEquals("½", p.text(2, 1))
    }

    @Test
    fun navigationStepsThroughThePagesThatArrived() {
        val s = store()
        s.feed(header(0x100), header(0x105), header(0x1FF), header(0x300), header(0x3FF))
        assertEquals(105, s.nextPage(100, 1))
        assertEquals(300, s.nextPage(105, 1))
        assertEquals(100, s.nextPage(300, 1))
        assertEquals(300, s.nextPage(100, -1))
        assertEquals(101, TeletextStore().nextPage(100, 1))
        assertEquals(899, TeletextStore.wrap(99))
    }

    @Test
    fun theStoreIsBoundedAndKeepsPage100() {
        val s = store()
        s.feed(header(0x100), row(0x100, 1, "Inicio"))
        // Muitas páginas (com subpáginas) a seguir, com o teletexto fechado: fica só a pré-cache.
        for (sub in 0 until 3) for (p in 0x101..0x199) {
            if ((p and 0xF) > 9 || ((p shr 4) and 0xF) > 9) continue
            s.feed(header(p, sub = sub))
        }
        s.feed(header(0x1FF))
        assertEquals(TeletextStore.PRECACHE, s.size)
        assertTrue(s.has(100))
        s.viewing = true
        for (m in 2..7) for (sub in 0 until 3) for (p in 0..0x99) {
            if ((p and 0xF) > 9) continue
            s.feed(header(m * 0x100 + p, sub = sub))
        }
        s.feed(header(0x7FF))
        assertEquals(TeletextStore.MAX_ENTRIES, s.size)
        assertTrue(s.has(100))
        // Outro canal: tudo limpo.
        s.tracks(emptyList())
        assertEquals(0, s.size)
        assertFalse(s.available)
    }

    @Test
    fun onlySubtitlePagesAnnouncedIsNotTeletextUntilPagesArrive() {
        val s = TeletextStore()
        val config = "por".encodeToByteArray() + byteArrayOf(0x10, 0x88.toByte())
        s.tracks(listOf(TsTrack(pid, 6, Codec.TELETEXT, TrackKind.SUBTITLE, "por", config)))
        assertFalse(s.available)
        s.feed(header(0x888, subtitle = true), header(0x8FF))
        assertFalse(s.available)
        s.feed(header(0x100), header(0x1FF))
        assertTrue(s.available)
    }
}
