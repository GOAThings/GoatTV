package cc.slokker.itv.media

/**
 * Uma página de legendas do teletexto anunciada no PMT (descritor 0x56/0x46): a língua, o tipo (2 = legendas, 5 =
 * legendas para surdos) e o número da página como se escreve (0x888 = 888: magazine 8, página 0x88).
 */
data class TeletextPage(val language: String?, val type: Int, val page: Int) {
    val isSubtitle: Boolean get() = type == 2 || type == 5

    companion object {
        /** As páginas do [TsTrack.config] de uma faixa [Codec.TELETEXT] (5 bytes cada, como vêm no descritor). */
        fun of(config: ByteArray?): List<TeletextPage> {
            val c = config ?: return emptyList()
            return (0 until c.size / 5).map { i ->
                val at = i * 5
                val language = CharArray(3) { (c[at + it].toInt() and 0xFF).toChar() }.concatToString().lowercase()
                    .takeIf { it.all { ch -> ch in 'a'..'z' } }
                val magazine = (c[at + 3].toInt() and 7).let { if (it == 0) 8 else it }
                TeletextPage(language, (c[at + 3].toInt() shr 3) and 0x1F, magazine * 0x100 + (c[at + 4].toInt() and 0xFF))
            }
        }
    }
}

/**
 * Lê as legendas de uma página do teletexto (EBU, dentro de PES DVB: ETSI EN 300 472 e EN 300 706). Cada PES traz
 * linhas de 42 bytes com os bits ao contrário; o cabeçalho (linha 0) abre a página e as linhas 1–24 trazem o texto.
 * A página fica pronta quando chega o cabeçalho seguinte da mesma magazine (ou de qualquer uma, em modo série).
 * [feed] devolve o texto a mostrar quando muda: as linhas que se veem (o que está nas caixas, ou a linha toda), e
 * uma lista vazia para limpar.
 */
class TeletextDecoder(private val page: Int) {
    private val magazine = (page shr 8) and 7
    private val rows = arrayOfNulls<ByteArray>(25)
    private var collecting = false
    private var charset = 0
    private var shown: List<String> = emptyList()

    /** Um PES (data_identifier e unidades): o texto novo, se a página mudou; null se não mudou. */
    fun feed(pes: ByteArray): List<String>? {
        if (pes.isEmpty() || (pes[0].toInt() and 0xFF) !in 0x10..0x1F) return null
        var result: List<String>? = null
        var i = 1
        while (i + 2 <= pes.size) {
            val id = pes[i].toInt() and 0xFF
            val length = pes[i + 1].toInt() and 0xFF
            val unit = i + 2
            i = unit + length
            if (i > pes.size) break
            // 0x02: teletexto normal; 0x03: legendas. O resto (WSS, VPS, enchimento) não interessa.
            if ((id != 0x02 && id != 0x03) || length != 44) continue
            line(pes, unit + 2)?.let { result = it }
        }
        return result
    }

    /** Uma linha de 42 bytes a partir de [at] (a seguir ao field/line e ao framing code). */
    private fun line(d: ByteArray, at: Int): List<String>? {
        val a = hamming(reverse(d[at]))
        val b = hamming(reverse(d[at + 1]))
        if (a < 0 || b < 0) return null
        val mag = a and 7
        val packet = (a shr 3) or (b shl 1)
        val data = ByteArray(40) { reverse(d[at + 2 + it]) }
        if (packet == 0) {
            val units = hamming(data[0])
            val tens = hamming(data[1])
            val c4 = hamming(data[3])
            val c7 = hamming(data[6])
            val c11 = hamming(data[7])
            if (units < 0 || tens < 0 || c4 < 0 || c7 < 0 || c11 < 0) return null
            val serial = (c11 and 1) != 0
            // O cabeçalho de outra página da mesma magazine (ou de qualquer uma, em série) fecha a que se lia.
            var done: List<String>? = null
            if (collecting && (serial || mag == magazine)) {
                collecting = false
                done = publish()
            }
            if (mag == magazine && ((tens shl 4) or units) == (page and 0xFF)) {
                collecting = true
                // C12–C14: a variante nacional dos caracteres latinos (C12 é o bit mais alto da tabela).
                charset = ((c11 shr 1) and 1) * 4 + ((c11 shr 2) and 1) * 2 + ((c11 shr 3) and 1)
                // C4 (apagar) ou uma página nova: as linhas de antes saem.
                if ((c4 and 8) != 0) rows.fill(null)
            }
            return done
        }
        if (collecting && mag == magazine && packet in 1..24) rows[packet] = data
        return null
    }

    private fun publish(): List<String>? {
        val text = (1..24).mapNotNull { rows[it]?.let(::rowText) }.filter { it.isNotBlank() }
        rows.fill(null)
        if (text == shown) return null
        shown = text
        return text
    }

    /** O texto de uma linha: só o que está nas caixas (start box 0x0B … end box 0x0A), se as houver. */
    private fun rowText(row: ByteArray): String {
        val chars = row.map { (it.toInt() and 0x7F) }
        val boxed = chars.contains(0x0B)
        val out = StringBuilder()
        var inBox = !boxed
        for (c in chars) {
            when {
                c == 0x0B -> inBox = true
                c == 0x0A -> if (boxed) inBox = false
                !inBox -> {}
                c < 0x20 || c == 0x7F -> out.append(' ')
                else -> out.append(char(c))
            }
        }
        return out.toString().replace(Regex(" {2,}"), " ").trim()
    }

    private fun char(c: Int): Char {
        val at = NATIONAL_POSITIONS.indexOf(c)
        if (at >= 0) NATIONAL.getOrNull(charset)?.let { return it[at] }
        return c.toChar()
    }

    companion object {
        /** As posições do G0 latino que mudam com a língua (EN 300 706, tabela 36). */
        private val NATIONAL_POSITIONS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)

        /** As variantes pelo C12–C14 (tabela 32, grupo da Europa Ocidental). */
        private val NATIONAL = listOf(
            "£\$@←½→↑#—¼‖¾÷", // inglês
            "#\$§ÄÖÜ^_°äöüß", // alemão
            "#¤ÉÄÖÅÜ_éäöåü", // sueco, finlandês, húngaro
            "£\$é°ç→↑#ùàòèì", // italiano
            "éïàëêùî#èâôûç", // francês
            "ç\$¡áéíóú¿üñèà", // português, espanhol
            "#ůčťžýířéáěúš", // checo, eslovaco
        )

        private val HAMMING = IntArray(256) { -1 }.also { t ->
            // Hamming 8/4: os dados nos bits 1, 3, 5 e 7; os de proteção nos pares. Um bit errado corrige-se.
            for (v in 0 until 16) {
                val d1 = v and 1
                val d2 = (v shr 1) and 1
                val d3 = (v shr 2) and 1
                val d4 = (v shr 3) and 1
                val p1 = 1 xor d1 xor d3 xor d4
                val p2 = 1 xor d1 xor d2 xor d4
                val p3 = 1 xor d1 xor d2 xor d3
                val p4 = 1 xor p1 xor d1 xor p2 xor d2 xor p3 xor d3 xor d4
                val code = p1 or (d1 shl 1) or (p2 shl 2) or (d2 shl 3) or (p3 shl 4) or (d3 shl 5) or (p4 shl 6) or (d4 shl 7)
                t[code] = v
                for (bit in 0 until 8) if (t[code xor (1 shl bit)] == -1) t[code xor (1 shl bit)] = v
            }
        }

        internal fun hamming(b: Byte): Int = HAMMING[b.toInt() and 0xFF]

        /** O byte com os bits pela ordem ao contrário (o teletexto manda o bit menos significativo primeiro). */
        internal fun reverse(b: Byte): Byte {
            var v = b.toInt() and 0xFF
            var r = 0
            repeat(8) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            return r.toByte()
        }

        /** Para os testes e para quem precise de escrever: [v] (0–15) em Hamming 8/4. */
        internal fun hammingEncode(v: Int): Byte = (0 until 256).first { HAMMING[it] == v && isValidCode(it) }.toByte()

        private fun isValidCode(code: Int): Boolean {
            val v = HAMMING[code]
            if (v < 0) return false
            // O código certo (sem correção): todos os bits batem com os calculados.
            val d1 = v and 1
            val d2 = (v shr 1) and 1
            val d3 = (v shr 2) and 1
            val d4 = (v shr 3) and 1
            val p1 = 1 xor d1 xor d3 xor d4
            val p2 = 1 xor d1 xor d2 xor d4
            val p3 = 1 xor d1 xor d2 xor d3
            val p4 = 1 xor p1 xor d1 xor p2 xor d2 xor p3 xor d3 xor d4
            return code == (p1 or (d1 shl 1) or (p2 shl 2) or (d2 shl 3) or (p3 shl 4) or (d3 shl 5) or (p4 shl 6) or (d4 shl 7))
        }
    }
}
