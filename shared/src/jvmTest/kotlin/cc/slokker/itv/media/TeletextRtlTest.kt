package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * As legendas do teletexto em hebraico e árabe: as linhas vêm pela ordem em que se veem (células da esquerda para a
 * direita) e saem pela ordem lógica, com as letras árabes de base (o sistema liga-as e mostra-as da direita para a
 * esquerda). Os bytes são feitos como o emissor os manda (EN 300 706), com os códigos das tabelas.
 */
class TeletextRtlTest {
    private val page = 0x888

    /** Uma linha de 42 bytes (MRAG e 40 de dados) numa unidade de legendas (0x03) de um PES. */
    private fun unit(packet: Int, data: ByteArray): ByteArray {
        val mag = (page shr 8) and 7
        val line = byteArrayOf(
            TeletextDecoder.hammingEncode(mag or ((packet and 1) shl 3)),
            TeletextDecoder.hammingEncode(packet shr 1),
        ) + data
        return byteArrayOf(0x03, 44, 0x00, 0xE4.toByte()) + line.map { TeletextDecoder.reverse(it) }.toByteArray()
    }

    private fun pes(vararg units: ByteArray): ByteArray = units.fold(byteArrayOf(0x10)) { acc, u -> acc + u }

    /** O cabeçalho da página (ou de [pageUnits] outra), com a variante nacional [option] (C12–C14) e C4 (apagar). */
    private fun header(option: Int, pageUnits: Int = page and 0xFF): ByteArray {
        val d = ByteArray(40) { 0x20 }
        d[0] = TeletextDecoder.hammingEncode(pageUnits and 0xF)
        d[1] = TeletextDecoder.hammingEncode(pageUnits shr 4)
        d[2] = TeletextDecoder.hammingEncode(0)
        d[3] = TeletextDecoder.hammingEncode(8)
        d[4] = TeletextDecoder.hammingEncode(0)
        d[5] = TeletextDecoder.hammingEncode(0)
        d[6] = TeletextDecoder.hammingEncode(0)
        // C12 no bit 1, C13 no 2, C14 no 3 (o decoder lê C12 como o bit mais alto da variante).
        val c12 = (option shr 2) and 1
        val c13 = (option shr 1) and 1
        val c14 = option and 1
        d[7] = TeletextDecoder.hammingEncode((c12 shl 1) or (c13 shl 2) or (c14 shl 3))
        return unit(0, d)
    }

    /** Uma linha de texto com os códigos [codes] (7 bits) a partir da coluna [column], numa caixa. */
    private fun row(n: Int, codes: List<Int>, column: Int = 10): ByteArray {
        val d = ByteArray(40) { 0x20 }
        d[column - 2] = 0x0B
        d[column - 1] = 0x0B
        codes.forEachIndexed { i, c -> d[column + i] = c.toByte() }
        d[column + codes.size] = 0x0A
        d[column + codes.size + 1] = 0x0A
        return unit(n, d)
    }

    /** X/26/0: o primeiro tripleto põe a linha ativa, os seguintes os caracteres do G2 nas colunas. */
    private fun enhancements(row: Int, g2: Map<Int, Int>): ByteArray {
        val triplets = mutableListOf((40 + row) or (0x04 shl 6))
        for ((column, code) in g2) triplets += column or (0x0F shl 6) or (code shl 11)
        while (triplets.size < 13) triplets += 63 or (0x1F shl 6)
        val d = ByteArray(40)
        d[0] = TeletextDecoder.hammingEncode(0)
        triplets.forEachIndexed { k, t -> TeletextDecoder.hamming24Encode(t).copyInto(d, 1 + k * 3) }
        return unit(26, d)
    }

    /** X/28/0 com o código [code] de 7 bits (tabela 32) para o G0 e o G2. */
    private fun x28(code: Int): ByteArray {
        val d = ByteArray(40)
        d[0] = TeletextDecoder.hammingEncode(0)
        TeletextDecoder.hamming24Encode(code shl 7).copyInto(d, 1)
        for (k in 1 until 13) TeletextDecoder.hamming24Encode(0).copyInto(d, 1 + k * 3)
        return unit(28, d)
    }

    /** O que sai quando a página fecha (com o cabeçalho de outra página da mesma magazine). */
    private fun decode(decoder: TeletextDecoder, vararg units: ByteArray): List<String>? {
        decoder.feed(pes(*units))
        return decoder.feed(pes(header(0, pageUnits = 0x99)))
    }

    // "مرحبا" como se vê, da esquerda para a direita: alef final, beh medial, hah inicial, reh, meem inicial.
    private val marhabaVisual = listOf(0x46, 0x42, 0x4D, 0x51, 0x65)
    private val marhaba = "مرحبا"

    @Test
    fun arabicRowComesOutInLogicalOrderWithBaseLetters() {
        val decoder = TeletextDecoder(page, "ara")
        assertEquals(listOf(marhaba), decode(decoder, header(option = 7), row(22, marhabaVisual)))
    }

    @Test
    fun arabicNumbersAndPunctuationStayReadable() {
        // "مرحبا 2024؟" no ecrã: o ؟ à esquerda, o número da esquerda para a direita, a palavra à direita.
        val visual = listOf(0x3F, 0x32, 0x30, 0x32, 0x34, 0x20) + marhabaVisual
        val decoder = TeletextDecoder(page, "ara")
        assertEquals(listOf("$marhaba 2024؟"), decode(decoder, header(option = 7), row(22, visual)))
    }

    @Test
    fun arabicParenthesesAndLamAlef() {
        // "(لا)": no ecrã o 0x29 à esquerda e o 0x28 à direita (abre-se do lado direito); lam-alef numa célula só.
        val decoder = TeletextDecoder(page, null)
        assertEquals(listOf("(لا)"), decode(decoder, header(option = 7), x28(0x47), row(22, listOf(0x29, 0x7E, 0x28))))
    }

    @Test
    fun arabicG2FromX26AddsLatinAndHamza() {
        // "مرحبا BBC" no ecrã: BBC à esquerda (letras latinas do G2 por X/26, por cima de espaços), a palavra à direita.
        val decoder = TeletextDecoder(page, null)
        val visual = listOf(0x20, 0x20, 0x20, 0x20) + marhabaVisual
        val units = arrayOf(
            header(option = 7), x28(0x57), row(22, visual),
            enhancements(22, mapOf(10 to 0x42, 11 to 0x42, 12 to 0x43)),
        )
        assertEquals(listOf("$marhaba BBC"), decode(decoder, *units))
        // A hamza do G2 (alef com hamza em cima, 0x23) numa célula: "أ".
        val again = TeletextDecoder(page, "ara")
        assertEquals(listOf("أ"), decode(again, header(option = 7), row(22, listOf(0x20)), enhancements(22, mapOf(10 to 0x23))))
    }

    @Test
    fun hebrewRowComesOutInLogicalOrder() {
        // "שלום" no ecrã: final mem, vav, lamed, shin (0x60 + a letra desde o alef).
        val decoder = TeletextDecoder(page, "heb")
        assertEquals(listOf("שלום"), decode(decoder, header(option = 5), row(22, listOf(0x6D, 0x65, 0x6C, 0x79))))
    }

    @Test
    fun latinFirstInRtlRowGetsTheMark() {
        // "ABC שלום" numa linha da direita para a esquerda: no ecrã a palavra hebraica à esquerda e ABC à direita.
        assertEquals("‏ABC שלום", TeletextDecoder.logical("םולש ABC"))
    }

    @Test
    fun latinRowsAreUntouched() {
        val decoder = TeletextDecoder(page, "eng")
        val text = "Hello (world) 12"
        assertEquals(listOf(text), decode(decoder, header(option = 0), row(22, text.map { it.code })))
        assertEquals(text, TeletextDecoder.logical(text))
    }
}
