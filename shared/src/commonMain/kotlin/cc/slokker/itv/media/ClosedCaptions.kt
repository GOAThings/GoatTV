package cc.slokker.itv.media

/**
 * As legendas fechadas que vêm dentro do vídeo (ATSC A/53: SEI "GA94" no H.264 e no HEVC, comuns nos canais
 * americanos e canadianos). [field1] dá os pares CEA-608 do campo 1 (CC1/CC2); [ccData] dá tudo, 608 e 708, para o
 * [CaptionDecoder].
 */
object ClosedCaptions {
    /**
     * Todo o cc_data de uma imagem (Annex B): três bytes por entrada válida, [cc_type, b1, b2] (0/1 = 608 campo 1/2,
     * 2/3 = DTVCC do CEA-708, 3 a abrir um pacote). [mpeg2]: o vídeo é MPEG-2 (user_data em vez de SEI).
     */
    fun ccData(data: ByteArray, hevc: Boolean, mpeg2: Boolean): ByteArray = if (mpeg2) {
        mpeg2UserData(data) { b, from, to -> triples(b, from, to) }
    } else {
        var out = ByteArray(0)
        for (n in NalReader.split(data)) {
            val type = if (hevc) (data[n[0]].toInt() shr 1) and 0x3F else data[n[0]].toInt() and 0x1F
            if ((hevc && type == 39) || (!hevc && type == 6)) out += sei(data, n[0] + if (hevc) 2 else 1, n[1], ::triples)
        }
        out
    }

    /** As entradas válidas de um cc_data() (a partir do byte com o cc_count), em três bytes cada. */
    private fun triples(b: ByteArray, from: Int, to: Int): ByteArray {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (to - from < 2) return ByteArray(0)
        val count = u(from) and 0x1F
        val out = ArrayList<Byte>()
        var i = from + 2
        repeat(count) {
            if (i + 3 > to) return@repeat
            val flags = u(i)
            if ((flags and 0x04) != 0) {
                out += (flags and 0x03).toByte()
                out += b[i + 1]
                out += b[i + 2]
            }
            i += 3
        }
        return out.toByteArray()
    }

    /** Os pares 608 do campo 1 numa imagem (Annex B, como vem no PES): [b1, b2, b1, b2…], já sem a paridade. */
    fun field1(annexB: ByteArray, hevc: Boolean): ByteArray {
        var out = ByteArray(0)
        for (n in NalReader.split(annexB)) {
            val type = if (hevc) (annexB[n[0]].toInt() shr 1) and 0x3F else annexB[n[0]].toInt() and 0x1F
            if ((hevc && type == 39) || (!hevc && type == 6)) out += sei(annexB, n[0] + if (hevc) 2 else 1, n[1], ::ccData)
        }
        return out
    }

    /** O mesmo no MPEG-2 (o cabo americano): nos user_data (0x000001B2) da imagem, "GA94" e cc_data (0x03). */
    fun field1Mpeg2(d: ByteArray): ByteArray = mpeg2UserData(d, ::ccData)

    private fun mpeg2UserData(d: ByteArray, read: (ByteArray, Int, Int) -> ByteArray): ByteArray {
        var out = ByteArray(0)
        var i = 0
        while (i + 9 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1 && (d[i + 3].toInt() and 0xFF) == 0xB2 &&
                d[i + 4].toInt() == 'G'.code && d[i + 5].toInt() == 'A'.code && d[i + 6].toInt() == '9'.code && d[i + 7].toInt() == '4'.code &&
                d[i + 8].toInt() == 0x03
            ) {
                // Acaba no código de início seguinte.
                var end = i + 9
                while (end + 2 < d.size && !(d[end].toInt() == 0 && d[end + 1].toInt() == 0 && d[end + 2].toInt() == 1)) end++
                out += read(d, i + 9, if (end + 2 < d.size) end else d.size)
                i = end
            } else {
                i++
            }
        }
        return out
    }

    private fun sei(d: ByteArray, from: Int, to: Int, read: (ByteArray, Int, Int) -> ByteArray): ByteArray {
        val b = SpsBits.rbsp(d, from, to)
        var out = ByteArray(0)
        var i = 0
        while (i + 2 <= b.size && (b[i].toInt() and 0xFF) != 0x80) {
            var type = 0
            while (i < b.size && (b[i].toInt() and 0xFF) == 0xFF) { type += 255; i++ }
            if (i >= b.size) break
            type += b[i++].toInt() and 0xFF
            var size = 0
            while (i < b.size && (b[i].toInt() and 0xFF) == 0xFF) { size += 255; i++ }
            if (i >= b.size) break
            size += b[i++].toInt() and 0xFF
            if (i + size > b.size) break
            if (type == 4) out += t35(b, i, i + size, read)
            i += size
        }
        return out
    }

    /** user_data_registered_itu_t_t35: os EUA (0xB5), o ATSC (0x0031), "GA94", cc_data (0x03). */
    private fun t35(b: ByteArray, from: Int, to: Int, read: (ByteArray, Int, Int) -> ByteArray): ByteArray {
        if (to - from < 10) return ByteArray(0)
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (u(from) != 0xB5 || u(from + 1) != 0x00 || u(from + 2) != 0x31) return ByteArray(0)
        if (u(from + 3) != 'G'.code || u(from + 4) != 'A'.code || u(from + 5) != '9'.code || u(from + 6) != '4'.code || u(from + 7) != 0x03) return ByteArray(0)
        return read(b, from + 8, to)
    }

    /** cc_data() a partir do byte com o cc_count: os pares do campo 1. */
    private fun ccData(b: ByteArray, from: Int, to: Int): ByteArray {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (to - from < 2) return ByteArray(0)
        val count = u(from) and 0x1F
        val out = ArrayList<Byte>()
        var i = from + 2
        repeat(count) {
            if (i + 3 > to) return@repeat
            val flags = u(i)
            // cc_valid e cc_type 0 (campo 1).
            if ((flags and 0x04) != 0 && (flags and 0x03) == 0) {
                out += (u(i + 1) and 0x7F).toByte()
                out += (u(i + 2) and 0x7F).toByte()
            }
            i += 3
        }
        return out.toByteArray()
    }
}

/**
 * Um descodificador CEA-608 do canal CC1 (o das legendas; o CC2 quase não se usa): os modos pop-on (montadas fora do
 * ecrã e mostradas de uma vez), roll-up (2–4 linhas que sobem) e paint-on (escritas no ecrã), com as posições das
 * linhas e os caracteres especiais. [feed] recebe os pares pela ordem em que se mostram (não a de descodificação) e
 * devolve o que está no ecrã quando muda: as linhas de cima para baixo, vazio para limpar.
 */
class Cea608Decoder {
    private enum class Mode { POP_ON, ROLL_UP, PAINT_ON }

    private class Screen {
        val rows = Array(15) { StringBuilder() }
        fun clear() = rows.forEach { it.setLength(0) }
        fun lines(): List<String> = rows.map { it.toString().trimEnd() }.filter { it.isNotBlank() }.map { it.trim() }
        fun copy(): Screen = Screen().also { s -> rows.indices.forEach { s.rows[it].append(rows[it]) } }
    }

    private var mode = Mode.POP_ON
    private var displayed = Screen()
    private var hidden = Screen()
    private var row = 14
    private var column = 0
    private var rollUpRows = 2
    /** O canal dos códigos que chegam (os dois bytes de controlo dizem se são do CC1 ou do CC2). */
    private var channel = 1
    private var lastControl = -1
    private var shown: List<String> = emptyList()

    /** Os pares (b1, b2) já sem a paridade; o ecrã novo, ou null se não mudou. */
    fun feed(pairs: ByteArray): List<String>? {
        var i = 0
        while (i + 1 < pairs.size) {
            pair(pairs[i].toInt() and 0x7F, pairs[i + 1].toInt() and 0x7F)
            i += 2
        }
        val now = displayed.lines()
        if (now == shown) return null
        shown = now
        return now
    }

    private val target: Screen get() = if (mode == Mode.POP_ON) hidden else displayed

    private fun pair(b1: Int, b2: Int) {
        if (b1 == 0 && b2 == 0) return
        if (b1 in 0x10..0x1F) {
            // Os códigos de controlo vêm repetidos (para resistir a erros): o segundo igual não conta.
            val code = (b1 shl 8) or b2
            if (code == lastControl) {
                lastControl = -1
                return
            }
            lastControl = code
            channel = if ((b1 and 0x08) != 0) 2 else 1
            if (channel != 1) return
            control(b1 and 0x17, b2)
            return
        }
        lastControl = -1
        if (channel != 1) return
        if (b1 >= 0x20) put(BASIC[b1 - 0x20])
        if (b2 >= 0x20) put(BASIC[b2 - 0x20])
    }

    private fun control(b1: Int, b2: Int) {
        when {
            // Preâmbulo de endereço (PAC): a linha e a coluna (múltiplos de 4) onde se escreve.
            b2 in 0x40..0x7F && b1 in 0x10..0x17 -> {
                val base = PAC_ROWS[((b1 and 0x07) shl 1) or ((b2 shr 5) and 1)]
                if (mode == Mode.ROLL_UP) {
                    // Em roll-up a linha base pode mudar: o texto vai com ela.
                    if (base != row) moveRollUp(base)
                } else {
                    row = base
                }
                column = if ((b2 and 0x10) != 0) ((b2 shr 1) and 0x07) * 4 else 0
                pad()
            }
            // Mid-row (cor, itálico): um espaço.
            b1 == 0x11 && b2 in 0x20..0x2F -> put(' ')
            // Caracteres especiais (®, °, ½, ¿, ™, ¢, £, ♪, à, espaço, è, â, ê, î, ô, û).
            b1 == 0x11 && b2 in 0x30..0x3F -> put(SPECIAL[b2 - 0x30])
            // Caracteres estendidos: substituem o caráter de antes (que vem como aproximação).
            (b1 == 0x12 || b1 == 0x13) && b2 in 0x20..0x3F -> {
                backspace()
                put((if (b1 == 0x12) EXTENDED_12 else EXTENDED_13)[b2 - 0x20])
            }
            // Códigos de modo e de ecrã.
            (b1 == 0x14 || b1 == 0x15) && b2 in 0x20..0x2F -> misc(b2)
            // Tabulação: 1 a 3 colunas.
            b1 == 0x17 && b2 in 0x21..0x23 -> repeat(b2 - 0x20) { put(' ') }
        }
    }

    private fun misc(b2: Int) {
        when (b2) {
            0x20 -> { // RCL: pop-on
                mode = Mode.POP_ON
            }
            0x21 -> backspace() // BS
            0x24 -> target.rows[row].setLength(minOf(target.rows[row].length, column)) // DER
            0x25, 0x26, 0x27 -> { // RU2, RU3, RU4
                if (mode != Mode.ROLL_UP) {
                    displayed.clear()
                    hidden.clear()
                }
                mode = Mode.ROLL_UP
                rollUpRows = b2 - 0x23
                row = 14
                column = 0
            }
            0x29 -> { // RDC: paint-on
                mode = Mode.PAINT_ON
            }
            0x2C -> displayed.clear() // EDM
            0x2D -> carriageReturn() // CR
            0x2E -> hidden.clear() // ENM
            0x2F -> { // EOC: troca o que está montado com o que está no ecrã
                val d = displayed
                displayed = hidden
                hidden = d
                mode = Mode.POP_ON
            }
        }
    }

    private fun carriageReturn() {
        if (mode != Mode.ROLL_UP) {
            row = minOf(14, row + 1)
            column = 0
            return
        }
        // As linhas sobem; só ficam as [rollUpRows] de cima da base.
        val top = maxOf(0, row - rollUpRows + 1)
        for (r in 0 until 15) {
            when {
                r < top || r > row -> displayed.rows[r].setLength(0)
                r < row -> {
                    displayed.rows[r].setLength(0)
                    displayed.rows[r].append(displayed.rows[r + 1])
                }
            }
        }
        displayed.rows[row].setLength(0)
        column = 0
    }

    private fun moveRollUp(base: Int) {
        val copy = displayed.copy()
        displayed.clear()
        for (k in 0 until rollUpRows) {
            val from = row - k
            val to = base - k
            if (from in 0..14 && to in 0..14) displayed.rows[to].append(copy.rows[from])
        }
        row = base
    }

    private fun pad() {
        val line = target.rows[row]
        while (line.length < column) line.append(' ')
        line.setLength(column)
    }

    private fun put(c: Char) {
        val line = target.rows[row]
        while (line.length < column) line.append(' ')
        if (column < line.length) line[column] = c else line.append(c)
        if (column < 31) column++
    }

    private fun backspace() {
        if (column > 0) {
            column--
            val line = target.rows[row]
            if (column < line.length) line.setLength(column)
        }
    }

    private companion object {
        /** A linha de cada PAC (pelo primeiro byte e o bit 5 do segundo). */
        val PAC_ROWS = intArrayOf(10, 10, 0, 1, 2, 3, 11, 12, 13, 14, 4, 5, 6, 7, 8, 9)

        /** O conjunto básico do 608 (0x20–0x7F), com as letras acentuadas onde o ASCII tem outras. */
        val BASIC: CharArray = (0x20..0x7F).map { c ->
            when (c) {
                0x2A -> 'á'
                0x5C -> 'é'
                0x5E -> 'í'
                0x5F -> 'ó'
                0x60 -> 'ú'
                0x7B -> 'ç'
                0x7C -> '÷'
                0x7D -> 'Ñ'
                0x7E -> 'ñ'
                0x7F -> '█'
                else -> c.toChar()
            }
        }.toCharArray()

        val SPECIAL = "®°½¿™¢£♪à èâêîôû".toCharArray()
        val EXTENDED_12 = "ÁÉÓÚÜü‘¡*'—©℠•“”ÀÂÇÈÊËëÎÏïÔÙùÛ«»".toCharArray()
        val EXTENDED_13 = "ÃãÍÌìÒòÕõ{}\\^_|~ÄäÖöß¥¤│ÅåØø┌┐└┘".toCharArray()
    }
}
