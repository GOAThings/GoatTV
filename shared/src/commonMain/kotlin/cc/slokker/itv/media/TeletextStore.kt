package cc.slokker.itv.media

import cc.slokker.itv.media.TeletextDecoder.Companion.hamming
import cc.slokker.itv.media.TeletextDecoder.Companion.hamming24
import cc.slokker.itv.media.TeletextDecoder.Companion.reverse

/**
 * O teletexto todo do canal (EN 300 706, nível 1.5), para o ver como numa TV: guarda as páginas (e as subpáginas) da
 * faixa de teletexto do canal à medida que passam, e faz o ecrã de uma delas (40×25 células, ver [TeletextScreen]).
 *
 * Cada página fica guardada quando chega o cabeçalho seguinte da mesma magazine (ou de qualquer uma, em modo série);
 * até lá vai-se juntando à parte, para nunca se ver meia página. A memória tem limite: [MAX_ENTRIES] páginas e
 * subpáginas com o teletexto aberto ([viewing]), [PRECACHE] com ele fechado (as mais recentes, e sempre a 100, que é
 * por onde se abre). Um canal novo é um TeletextStore novo (ou [clear]).
 *
 * Os números das páginas na API são os que se escrevem (100…899). Não é seguro entre threads: quem usa põe um lock à
 * volta (a rede escreve, o ecrã lê).
 */
class TeletextStore {
    /** Uma página ou subpágina: as linhas como vieram (0 = cabeçalho, 1–24), o alfabeto, os extras e as ligações. */
    private class Entry(val page: Int, val subcode: Int) {
        val rows = arrayOfNulls<ByteArray>(25)
        var national = 0
        var group = -1
        var flags = 0
        /** X/26: linha × 40 + coluna → modo × 256 + código (o G2, ou uma letra do G0 com acento). */
        val extra = HashMap<Int, Int>()
        /** X/27/0: as 6 ligações (vermelho, verde, amarelo, azul, …, índice) em página hexadecimal; -1 sem ela. */
        var links: IntArray? = null
        var order = 0L

        fun takeFrom(o: Entry) {
            o.rows.copyInto(rows)
            group = o.group
            extra.putAll(o.extra)
            links = o.links
        }
    }

    private val entries = LinkedHashMap<Int, Entry>()
    /** A página que cada magazine está a mandar (0 = a magazine 8), ainda por fechar. */
    private val pending = arrayOfNulls<Entry>(8)
    private val activeRow = IntArray(8)
    private val magazineGroup = IntArray(8) { -1 }
    private var defaultGroup = 0
    /** O último cabeçalho que se pode mostrar (o nome do canal, a data e o relógio), a correr enquanto se espera. */
    private var header: ByteArray? = null
    private var counter = 0L

    /** O PID da faixa de teletexto que se lê (ver [tracks]); -1 sem nenhuma. */
    var pid = -1
        private set

    /** O PMT anuncia páginas que não são só de legendas (o índice, informação, programação). */
    var announced = false
        private set

    /** Já chegou pelo menos uma página que não é de legendas. */
    var hasPages = false
        private set

    /** O teletexto pode ver-se: o canal anuncia-o, ou já chegaram páginas dele. */
    val available: Boolean get() = pid >= 0 && (announced || hasPages)

    /** O teletexto está aberto: guarda até [MAX_ENTRIES]; fechado, só a pré-cache ([PRECACHE]). */
    var viewing = false
        set(value) {
            field = value
            trim()
        }

    /** Quantas páginas e subpáginas estão guardadas. */
    val size: Int get() = entries.size

    /** As faixas do canal (do PMT): escolhe a do teletexto. Devolve o PID dela, ou -1. Outra faixa limpa tudo. */
    fun tracks(list: List<TsTrack>): Int {
        val teletext = list.filter { it.codec == Codec.TELETEXT }
        val pages = teletext.associateWith { TeletextPage.of(it.config) }
        // A que tem páginas que não são de legendas; senão, a primeira (as páginas chegam e dizem-no).
        val track = teletext.firstOrNull { t -> pages.getValue(t).any { !it.isSubtitle } } ?: teletext.firstOrNull()
        val newPid = track?.pid ?: -1
        if (newPid != pid) {
            clear()
            pid = newPid
        }
        announced = track != null && pages.getValue(track).any { !it.isSubtitle }
        val language = track?.let { t -> pages.getValue(t).firstOrNull { !it.isSubtitle }?.language ?: t.language }
        defaultGroup = TeletextDecoder.languageGroup(language)
        return pid
    }

    /** Esquece as páginas (outro canal). */
    fun clear() {
        entries.clear()
        pending.fill(null)
        magazineGroup.fill(-1)
        header = null
        hasPages = false
    }

    /** Um PES da faixa [fromPid] (data_identifier e unidades, como no [TeletextDecoder]); os de outra faixa ignoram-se. */
    fun feed(fromPid: Int, pes: ByteArray) {
        if (fromPid != pid || pes.isEmpty() || (pes[0].toInt() and 0xFF) !in 0x10..0x1F) return
        var i = 1
        while (i + 2 <= pes.size) {
            val id = pes[i].toInt() and 0xFF
            val length = pes[i + 1].toInt() and 0xFF
            val unit = i + 2
            i = unit + length
            if (i > pes.size) break
            if ((id != 0x02 && id != 0x03) || length != 44) continue
            line(pes, unit + 2)
        }
    }

    private fun line(d: ByteArray, at: Int) {
        val a = hamming(reverse(d[at]))
        val b = hamming(reverse(d[at + 1]))
        if (a < 0 || b < 0) return
        val mag = a and 7
        val packet = (a shr 3) or (b shl 1)
        val data = ByteArray(40) { reverse(d[at + 2 + it]) }
        when (packet) {
            0 -> header(mag, data)
            in 1..24 -> pending[mag]?.rows?.set(packet, data)
            26 -> pending[mag]?.let { enhance(mag, it, data) }
            27 -> pending[mag]?.let { e -> if (hamming(data[0]) == 0) e.links = IntArray(6) { link(mag, data, 1 + it * 6) } }
            28, 29 -> {
                // X/28/0 e /4 (a página) ou M/29/0 e /4 (a magazine): o alfabeto, no 1.º tripleto.
                val designation = hamming(data[0])
                val triplet = hamming24(data, 1)
                if ((designation == 0 || designation == 4) && triplet >= 0) {
                    val group = ((triplet shr 7) and 0x7F) shr 3
                    if (packet == 28) pending[mag]?.group = group else magazineGroup[mag] = group
                }
            }
        }
    }

    private fun header(mag: Int, data: ByteArray) {
        val n = IntArray(8) { hamming(data[it]) }
        if (n.any { it < 0 }) {
            commit(mag)
            return
        }
        val units = n[0]
        val tens = n[1]
        val s2 = n[3]
        val s4 = n[5]
        val c7 = n[6]
        val c11 = n[7]
        // Em série, qualquer cabeçalho fecha a página que se lia; em paralelo, só o da mesma magazine.
        if ((c11 and 1) != 0) for (m in 0 until 8) commit(m) else commit(mag)
        val subtitle = (s4 and 8) != 0
        val newsflash = (s4 and 4) != 0
        val suppress = (c7 and 1) != 0
        // O cabeçalho a correr (também o das páginas de enchimento, que trazem o relógio).
        if (!subtitle && !newsflash && !suppress) header = data
        if (tens > 9 || units > 9) return
        val page = (if (mag == 0) 8 else mag) * 0x100 + tens * 16 + units
        val subcode = n[2] or ((s2 and 7) shl 4) or (n[4] shl 8) or ((s4 and 3) shl 12)
        val entry = Entry(page, subcode)
        // Sem C4 (apagar), o que não vier outra vez fica como estava.
        if ((s2 and 8) == 0) entries[key(page, subcode)]?.let(entry::takeFrom)
        entry.national = ((c11 shr 1) and 1) * 4 + ((c11 shr 2) and 1) * 2 + ((c11 shr 3) and 1)
        entry.flags = (if (subtitle) SUBTITLE else 0) or (if (newsflash) NEWSFLASH else 0)
        entry.rows[0] = data
        pending[mag] = entry
        activeRow[mag] = 0
    }

    /** A página da magazine [mag] fica guardada (no fim da ordem: as mais antigas saem primeiro). */
    private fun commit(mag: Int) {
        val e = pending[mag] ?: return
        pending[mag] = null
        val key = key(e.page, e.subcode)
        val isNew = entries.remove(key) == null
        e.order = counter++
        entries[key] = e
        if ((e.flags and SUBTITLE) == 0) hasPages = true
        // Uma página com subpáginas a mais (o código pode ser uma hora): sai a mais antiga dela.
        if (isNew && entries.values.count { it.page == e.page } > MAX_SUBPAGES) {
            entries.values.firstOrNull { it.page == e.page }?.let { entries.remove(key(it.page, it.subcode)) }
        }
        trim()
    }

    private fun trim() {
        val limit = if (viewing) MAX_ENTRIES else PRECACHE
        while (entries.size > limit) {
            val it = entries.entries.iterator()
            var removed = false
            // A página 100 (onde o teletexto abre) fica, enquanto houver outras para tirar.
            while (it.hasNext()) {
                if (it.next().value.page != START) {
                    it.remove()
                    removed = true
                    break
                }
            }
            if (!removed) entries.remove(entries.keys.first())
        }
    }

    /** X/26: só os caracteres (G2 e letras com acento) das linhas 1–24; o resto do nível 2.5 não é para aqui. */
    private fun enhance(mag: Int, e: Entry, data: ByteArray) {
        if (hamming(data[0]) == 0) {
            activeRow[mag] = 0
            e.extra.clear()
        }
        for (k in 0 until 13) {
            val t = hamming24(data, 1 + k * 3)
            if (t < 0) continue
            val address = t and 0x3F
            val mode = (t shr 6) and 0x1F
            val value = (t shr 11) and 0x7F
            if (address >= 40) {
                if (mode == 0x1F) return
                if (mode == 0x01 || mode == 0x04) activeRow[mag] = if (address == 40) 24 else address - 40
            } else if ((mode == 0x0F || mode >= 0x10) && activeRow[mag] in 1..24 && value >= 0x20) {
                e.extra[activeRow[mag] * 40 + address] = (mode shl 8) or value
            }
        }
    }

    /** Uma ligação do X/27/0 (6 bytes em Hamming 8/4 a partir de [at]): a página hexadecimal, ou -1. */
    private fun link(mag: Int, data: ByteArray, at: Int): Int {
        val n = IntArray(6) { hamming(data[at + it]) }
        if (n.any { it < 0 } || n[0] > 9 || n[1] > 9) return -1
        // M1 no S2 e M2, M3 no S4: a magazine relativa à da página.
        val relative = ((n[3] shr 3) and 1) or (((n[5] shr 2) and 3) shl 1)
        val m = mag xor relative
        return (if (m == 0) 8 else m) * 0x100 + n[1] * 16 + n[0]
    }

    private fun subpagesOf(page: Int): List<Entry> = entries.values.filter { it.page == page }.sortedBy { it.subcode }

    /** Quantas subpáginas da página [page] (100…899) já chegaram (0 = nenhuma: ainda não chegou). */
    fun subpageCount(page: Int): Int = entries.values.count { it.page == hexOf(page) }

    /** A página já chegou. */
    fun has(page: Int): Boolean = entries.values.any { it.page == hexOf(page) }

    /**
     * A página a seguir a [page] na direção [step] (+1/−1) entre as que já chegaram, à volta de 899 para 100; sem
     * nenhuma, a seguinte pelo número.
     */
    fun nextPage(page: Int, step: Int): Int {
        val pages = entries.values.map { decimalOf(it.page) }.distinct().sorted()
        if (pages.isEmpty()) return wrap(page + step)
        return if (step >= 0) pages.firstOrNull { it > page } ?: pages.first() else pages.lastOrNull { it < page } ?: pages.last()
    }

    /**
     * O ecrã da página [page] (100…899): a subpágina [subpage] (pela ordem dos códigos; -1 = a última que chegou, como
     * numa TV a rodar). [label] vai no início do cabeçalho (o número a escrever, "1--"; vazio = o da página).
     */
    fun screen(page: Int, subpage: Int, label: String): TeletextScreen {
        val hex = hexOf(page)
        val subs = subpagesOf(hex)
        val shown = subs.getOrNull(subpage) ?: subs.maxByOrNull { it.order }
        val boxed = shown != null && (shown.flags and (SUBTITLE or NEWSFLASH)) != 0
        val group = shown?.group?.takeIf { it >= 0 } ?: magazineGroup[(hex shr 8) and 7].takeIf { it >= 0 } ?: defaultGroup
        val table = TeletextDecoder.alphabet(group, shown?.national ?: 0)
        val g2 = if (group == 8 || group == 10) TeletextDecoder.ARABIC_G2 else TeletextDecoder.LATIN_G2
        val out = TeletextScreen(page, shown != null, if (shown != null) subs.indexOf(shown) else -1, subs.size, boxed, shown?.subcode ?: -1)
        // O cabeçalho: o número nas 8 primeiras colunas, e o do canal a correr (com o relógio) nas outras.
        val head = ByteArray(40) { 0x20 }
        (header ?: shown?.rows?.get(0))?.copyInto(head, 8, 8, 40)
        val number = " P" + label.ifEmpty { page.toString() }
        for ((i, ch) in number.take(8).withIndex()) head[i] = ch.code.toByte()
        out.layout(0, head, table, g2, null)
        if (shown != null) {
            var r = 1
            while (r <= 24) {
                val row = shown.rows[r]
                val double = row != null && out.layout(r, row, table, g2, shown.extra)
                // A linha a seguir a uma de altura dupla não se mostra: leva a metade de baixo dela.
                if (double && r < 23) {
                    out.lowerHalf(r)
                    r += 2
                } else {
                    r++
                }
            }
            shown.links?.let { l -> for (k in 0 until 4) out.links[k] = l[k].takeIf { it >= 0 }?.let(::decimalOf) ?: -1 }
        }
        if (boxed) out.hideOutsideBoxes()
        return out
    }

    companion object {
        /** As páginas e subpáginas guardadas com o teletexto aberto (umas 1,3 KB cada). */
        const val MAX_ENTRIES = 1000
        /** As que ficam com ele fechado: as mais recentes, para a página 100 e as perto dela aparecerem logo. */
        const val PRECACHE = 200
        /** As subpáginas de uma página (a norma vai até 79). */
        const val MAX_SUBPAGES = 80
        private const val START = 0x100
        private const val SUBTITLE = 1
        private const val NEWSFLASH = 2

        private fun key(page: Int, subcode: Int) = (page shl 16) or subcode

        /** 100…899 na página como o teletexto a manda (0x100…0x899). */
        internal fun hexOf(page: Int): Int = (page / 100) * 0x100 + ((page / 10) % 10) * 16 + page % 10

        internal fun decimalOf(hex: Int): Int = ((hex shr 8) and 0xF) * 100 + ((hex shr 4) and 0xF) * 10 + (hex and 0xF)

        /** [page] dentro de 100…899, à volta. */
        fun wrap(page: Int): Int = if (page > 899) 100 else if (page < 100) 899 else page
    }
}

/**
 * Uma página do teletexto pronta a desenhar: 25 linhas de 40 células, com o texto (ou o mosaico) de cada uma, as cores
 * (0–7: preto, vermelho, verde, amarelo, azul, magenta, ciano, branco) e os atributos ([FLASH], [CONCEAL]…). As
 * células contam-se por linha × 40 + coluna. API plana para o Swift.
 */
class TeletextScreen internal constructor(
    /** A página (100…899). */
    val page: Int,
    /** A página já chegou; senão só o cabeçalho tem alguma coisa ("à espera da página"). */
    val found: Boolean,
    /** A subpágina que se mostra (0…[subpages]−1), -1 sem página. */
    val subpage: Int,
    val subpages: Int,
    /** Legendas ou notícia de última hora: só o que está nas caixas se vê, por cima do vídeo. */
    val boxed: Boolean,
    /** O código da subpágina (como vem no cabeçalho), -1 sem página. */
    val subcode: Int,
) {
    private val texts = Array(CELLS) { " " }
    private val mosaics = IntArray(CELLS) { -1 }
    private val fg = IntArray(CELLS) { 7 }
    private val bg = IntArray(CELLS)
    private val attrs = IntArray(CELLS)
    internal val links = IntArray(4) { -1 }

    /** O texto da célula (um carácter, às vezes com um acento a seguir); vazio num mosaico. */
    fun text(row: Int, column: Int): String = texts[row * 40 + column]

    /** Os 6 blocos do mosaico (bit 0 em cima à esquerda, 1 em cima à direita, … 5 em baixo à direita); -1 = texto. */
    fun mosaic(row: Int, column: Int): Int = mosaics[row * 40 + column]
    fun foreground(row: Int, column: Int): Int = fg[row * 40 + column]
    fun background(row: Int, column: Int): Int = bg[row * 40 + column]
    fun attributes(row: Int, column: Int): Int = attrs[row * 40 + column]

    /** A página da tecla de cor [index] (0 vermelho, 1 verde, 2 amarelo, 3 azul) pelo FastText, ou -1. */
    fun link(index: Int): Int = links.getOrElse(index) { -1 }

    /** O texto de uma linha toda (para os testes e o registo), sem os mosaicos. */
    fun rowText(row: Int): String = (0 until 40).joinToString("") { c -> if (mosaics[row * 40 + c] >= 0) "#" else texts[row * 40 + c] }

    /**
     * Os atributos de espaçamento de uma linha (EN 300 706, 12.2): as cores do texto e do mosaico, o fundo, piscar,
     * esconder, caixas, altura dupla, os mosaicos juntos ou separados e o "hold". Devolve true se houver altura dupla.
     */
    internal fun layout(r: Int, row: ByteArray, table: CharArray, g2: CharArray, extra: Map<Int, Int>?): Boolean {
        var fore = 7
        var back = 0
        var graphics = false
        var separated = false
        var hold = false
        var held = 0
        var heldSeparated = false
        var flash = false
        var conceal = false
        var box = false
        var double = false
        var anyDouble = false
        for (col in 0 until 40) {
            val c = row[col].toInt() and 0x7F
            val at = r * 40 + col
            // Os que valem já nesta célula ("set-at").
            when (c) {
                0x09 -> flash = false
                0x0C -> {
                    if (double) held = 0
                    double = false
                }
                0x18 -> conceal = true
                0x19 -> separated = false
                0x1A -> separated = true
                0x1C -> back = 0
                0x1D -> back = fore
                0x1E -> hold = true
            }
            var text = " "
            var mosaic = -1
            var sep = separated
            if (c < 0x20) {
                // Um atributo ocupa a célula: um espaço, ou o último mosaico com o "hold".
                if (hold && graphics && held != 0) {
                    mosaic = held
                    sep = heldSeparated
                }
            } else if (graphics && (c < 0x40 || c >= 0x60)) {
                mosaic = (c and 0x1F) or ((c and 0x40) shr 1)
                held = mosaic
                heldSeparated = separated
            } else {
                val e = extra?.get(at)
                text = when {
                    e == null -> table[c - 0x20].toString()
                    (e shr 8) == 0x0F -> g2[(e and 0x7F) - 0x20].toString()
                    // Uma letra do G0 com acento (modo 0x10 sem acento: o "@" de 0x2A, como diz a norma).
                    (e shr 8) == 0x10 -> if ((e and 0x7F) == 0x2A) "@" else table[(e and 0x7F) - 0x20].toString()
                    else -> table[(e and 0x7F) - 0x20].toString() + TeletextDecoder.DIACRITICS[(e shr 8) and 0xF]
                }
            }
            texts[at] = if (mosaic >= 0) "" else text
            mosaics[at] = mosaic
            fg[at] = fore
            bg[at] = back
            attrs[at] = (if (flash) FLASH else 0) or (if (conceal) CONCEAL else 0) or (if (box) BOXED else 0) or
                (if (double && r in 1..22) DOUBLE_TOP else 0) or (if (sep && mosaic >= 0) SEPARATED else 0)
            // Os que valem a partir da célula seguinte ("set-after").
            when (c) {
                in 0x00..0x07 -> {
                    fore = c
                    if (graphics) held = 0
                    graphics = false
                    conceal = false
                }
                in 0x10..0x17 -> {
                    fore = c - 0x10
                    graphics = true
                    conceal = false
                }
                0x08 -> flash = true
                0x0A -> box = false
                0x0B -> box = true
                0x0D, 0x0F -> {
                    if (!double) held = 0
                    double = true
                    if (r in 1..22) anyDouble = true
                }
                0x1F -> hold = false
            }
        }
        return anyDouble
    }

    /** A linha a seguir à [r] (de altura dupla) passa a ter a metade de baixo dela; as outras células ficam com o fundo. */
    internal fun lowerHalf(r: Int) {
        for (col in 0 until 40) {
            val top = r * 40 + col
            val at = top + 40
            val double = (attrs[top] and DOUBLE_TOP) != 0
            texts[at] = if (double) texts[top] else " "
            mosaics[at] = if (double) mosaics[top] else -1
            fg[at] = fg[top]
            bg[at] = bg[top]
            attrs[at] = if (double) (attrs[top] and DOUBLE_TOP.inv()) or DOUBLE_BOTTOM else attrs[top] and (BOXED or CONCEAL)
        }
    }

    /** Legendas e notícias: o que está fora das caixas não se vê (e o cabeçalho também não). */
    internal fun hideOutsideBoxes() {
        for (i in 0 until CELLS) if (i < 40 || (attrs[i] and BOXED) == 0) attrs[i] = attrs[i] or HIDDEN
    }

    companion object {
        const val ROWS = 25
        const val COLUMNS = 40
        private const val CELLS = ROWS * COLUMNS

        /** Pisca (o texto aparece e desaparece). */
        const val FLASH = 1
        /** Escondido até se pedir para revelar (as respostas dos jogos). */
        const val CONCEAL = 2
        /** Dentro de uma caixa (nas legendas e notícias, só isto se vê). */
        const val BOXED = 4
        /** A metade de cima de um carácter de altura dupla (a de baixo está na linha seguinte). */
        const val DOUBLE_TOP = 8
        const val DOUBLE_BOTTOM = 16
        /** Mosaico separado (os blocos com uma margem à volta). */
        const val SEPARATED = 32
        /** Não se desenha (nem o fundo): o vídeo vê-se por trás. */
        const val HIDDEN = 64
    }
}
