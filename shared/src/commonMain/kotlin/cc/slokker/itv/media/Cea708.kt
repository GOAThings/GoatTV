package cc.slokker.itv.media

/**
 * Um descodificador CEA-708 (as legendas digitais dos canais americanos) só de texto, do serviço 1 (o das legendas
 * na língua do programa): junta os pacotes DTVCC, lê os blocos do serviço e segue as janelas (definir, mostrar,
 * esconder, limpar, apagar), o cursor e as linhas que sobem. As cores, os tipos de letra e as posições exatas ficam
 * de fora: sai o texto das janelas visíveis, de cima para baixo. [feed] devolve-o quando muda (vazio para limpar).
 */
class Cea708Decoder {
    private class Window {
        var visible = false
        var anchorVertical = 0
        var rowCount = 1
        var penRow = 0
        var penCol = 0
        val rows = ArrayList<StringBuilder>()

        fun ensureRows() {
            while (rows.size < rowCount) rows += StringBuilder()
            while (rows.size > rowCount) rows.removeAt(0)
            penRow = penRow.coerceIn(0, rowCount - 1)
        }

        fun clear() {
            rows.forEach { it.setLength(0) }
            penRow = 0
            penCol = 0
        }

        fun put(c: Char) {
            ensureRows()
            val row = rows[penRow]
            while (row.length < penCol) row.append(' ')
            if (penCol < row.length) row[penCol] = c else row.append(c)
            penCol++
        }

        fun carriageReturn() {
            ensureRows()
            penCol = 0
            if (penRow + 1 < rowCount) {
                penRow++
            } else {
                // Na última linha: tudo sobe uma (o roll-up).
                rows.removeAt(0)
                rows += StringBuilder()
            }
        }

        fun backspace() {
            ensureRows()
            if (penCol > 0) {
                penCol--
                val row = rows[penRow]
                if (penCol < row.length) row.deleteAt(penCol)
            }
        }
    }

    private val windows = arrayOfNulls<Window>(8)
    private var current = 0
    private var packet = ByteArray(0)
    private var packetSize = -1
    private var shown: List<String> = emptyList()

    /** As entradas DTVCC de uma imagem ([cc_type, b1, b2], só os tipos 2 e 3), pela ordem de apresentação. */
    fun feed(triples: ByteArray): List<String>? {
        var changed = false
        var i = 0
        while (i + 3 <= triples.size) {
            val type = triples[i].toInt()
            val b1 = triples[i + 1]
            val b2 = triples[i + 2]
            i += 3
            if (type == 3) {
                // Um pacote novo: o tamanho no cabeçalho (0 = 128 bytes, senão o código × 2).
                if (packetSize > 0) changed = process() || changed
                val code = b1.toInt() and 0x3F
                packetSize = (if (code == 0) 128 else code * 2) - 1
                packet = byteArrayOf(b2)
            } else if (type == 2 && packetSize > 0) {
                packet += byteArrayOf(b1, b2)
            } else {
                continue
            }
            if (packetSize > 0 && packet.size >= packetSize) {
                changed = process() || changed
            }
        }
        if (!changed) return null
        val text = visibleText()
        if (text == shown) return null
        shown = text
        return text
    }

    /** O pacote que se juntou: os blocos de serviço; só o 1 interessa. */
    private fun process(): Boolean {
        val d = packet.copyOf(minOf(packet.size, packetSize))
        packetSize = -1
        packet = ByteArray(0)
        var i = 0
        var changed = false
        while (i < d.size) {
            val header = d[i].toInt() and 0xFF
            var service = header shr 5
            val size = header and 0x1F
            i++
            if (service == 7 && i < d.size) service = d[i++].toInt() and 0x3F
            if (service == 0 || size == 0) break
            val end = minOf(i + size, d.size)
            if (service == 1) changed = block(d, i, end) || changed
            i = end
        }
        return changed
    }

    private fun window(): Window? = windows[current]

    private fun block(d: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        var changed = false
        fun u(k: Int) = if (k < to) d[k].toInt() and 0xFF else 0
        while (i < to) {
            val c = u(i)
            i++
            when (c) {
                in 0x20..0x7F -> { window()?.put(if (c == 0x7F) '♪' else c.toChar()); changed = true }
                in 0xA0..0xFF -> { window()?.put(c.toChar()); changed = true }
                0x08 -> { window()?.backspace(); changed = true }
                0x0C -> { window()?.clear(); changed = true }
                0x0D -> { window()?.carriageReturn(); changed = true }
                0x0E -> window()?.let { w -> w.ensureRows(); w.rows[w.penRow].setLength(0); w.penCol = 0; changed = true }
                0x10 -> {
                    // EXT1: os conjuntos alargados (G2 tem uns caracteres; o resto salta-se pelo tamanho).
                    val e = u(i)
                    i++
                    when (e) {
                        in 0x00..0x07 -> {}
                        in 0x08..0x0F -> i += 1
                        in 0x10..0x17 -> i += 2
                        in 0x18..0x1F -> i += 3
                        in 0x20..0x7F -> { window()?.put(G2[e] ?: ' '); changed = true }
                        in 0x80..0x87 -> i += 4
                        in 0x88..0x8F -> i += 5
                        in 0x90..0x9F -> i += 1 + (u(i) and 0x3F)
                        else -> {}
                    }
                }
                in 0x11..0x17 -> i += 1
                in 0x18..0x1F -> i += 2
                in 0x80..0x87 -> current = c - 0x80
                0x88 -> { each(u(i)) { it.clear() }; i++; changed = true }
                0x89 -> { each(u(i)) { it.visible = true }; i++; changed = true }
                0x8A -> { each(u(i)) { it.visible = false }; i++; changed = true }
                0x8B -> { each(u(i)) { it.visible = !it.visible }; i++; changed = true }
                0x8C -> {
                    val map = u(i)
                    i++
                    for (n in 0 until 8) if ((map shr n) and 1 == 1) windows[n] = null
                    changed = true
                }
                0x8D -> i += 1
                0x8F -> { windows.fill(null); changed = true }
                0x90 -> i += 2
                0x91 -> i += 3
                0x92 -> {
                    window()?.let { w ->
                        w.penRow = (u(i) and 0x0F).coerceAtMost(w.rowCount - 1)
                        w.penCol = u(i + 1) and 0x3F
                    }
                    i += 2
                }
                0x97 -> i += 4
                in 0x98..0x9F -> {
                    val id = c - 0x98
                    val w = windows[id] ?: Window().also { windows[id] = it }
                    w.visible = (u(i) and 0x20) != 0
                    w.anchorVertical = u(i + 1) and 0x7F
                    w.rowCount = (u(i + 3) and 0x0F) + 1
                    w.ensureRows()
                    current = id
                    i += 6
                    changed = true
                }
                else -> {}
            }
        }
        return changed
    }

    private inline fun each(map: Int, action: (Window) -> Unit) {
        for (n in 0 until 8) if ((map shr n) and 1 == 1) windows[n]?.let(action)
    }

    private fun visibleText(): List<String> =
        windows.filterNotNull().filter { it.visible }.sortedBy { it.anchorVertical }
            .flatMap { w -> w.rows.map { it.toString().trim() }.filter { it.isNotEmpty() } }

    private companion object {
        val G2 = mapOf(
            0x20 to ' ', 0x21 to ' ', 0x25 to '…', 0x2A to 'Š', 0x2C to 'Œ', 0x30 to '█', 0x31 to '‘', 0x32 to '’',
            0x33 to '“', 0x34 to '”', 0x35 to '•', 0x39 to '™', 0x3A to 'š', 0x3C to 'œ', 0x3D to '℠', 0x3F to 'Ÿ',
            0x76 to '⅛', 0x77 to '⅜', 0x78 to '⅝', 0x79 to '⅞', 0x7A to '│', 0x7B to '┐', 0x7C to '└', 0x7D to '─',
            0x7E to '┘', 0x7F to '┌',
        )
    }
}

/**
 * As legendas fechadas de um canal, 608 ou 708: recebe o cc_data de cada imagem ([ClosedCaptions.ccData]) pela ordem
 * de apresentação e mostra o CC1 do 608 enquanto o canal o mandar com texto (é o que todos os aparelhos leem), senão
 * o serviço 1 do 708 (os canais que já só mandam esse). [feed] devolve o texto quando muda.
 */
class CaptionDecoder {
    private val c608 = Cea608Decoder()
    private val c708 = Cea708Decoder()
    private var pictures = 0L
    private var last608 = -1L
    private var lines608: List<String> = emptyList()
    private var lines708: List<String> = emptyList()
    private var using608 = true

    /** Já houve texto de legendas (é a partir daí que a faixa "CC" aparece). */
    var found = false
        private set

    fun feed(triples: ByteArray): List<String>? {
        pictures++
        val pairs = ArrayList<Byte>()
        val dtvcc = ArrayList<Byte>()
        var i = 0
        while (i + 3 <= triples.size) {
            when (triples[i].toInt()) {
                0 -> {
                    val b1 = triples[i + 1].toInt() and 0x7F
                    val b2 = triples[i + 2].toInt() and 0x7F
                    pairs += b1.toByte()
                    pairs += b2.toByte()
                    if (b1 != 0 || b2 != 0) last608 = pictures
                }
                2, 3 -> for (k in 0..2) dtvcc += triples[i + k]
            }
            i += 3
        }
        if (pairs.isNotEmpty()) c608.feed(pairs.toByteArray())?.let { lines608 = it; if (it.isNotEmpty()) found = true }
        if (dtvcc.isNotEmpty()) c708.feed(dtvcc.toByteArray())?.let { lines708 = it; if (it.isNotEmpty()) found = true }
        // O 608 com texto nos últimos ~20 s (600 imagens) ganha; sem ele, o 708.
        val use608 = last608 >= 0 && pictures - last608 < 600
        val switched = use608 != using608
        using608 = use608
        val lines = if (use608) lines608 else lines708
        return if (switched || lines !== lastReturned) lines.also { lastReturned = it } else null
    }

    private var lastReturned: List<String>? = null
}
