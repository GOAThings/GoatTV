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
 *
 * O alfabeto vem do grupo (X/28 da página, M/29 da magazine, ou o da [language] do PMT quando o canal não diz) e da
 * variante nacional do cabeçalho (C12–C14): é assim que se leem canais russos, gregos, polacos, turcos, hebraicos,
 * árabes. O hebraico e o árabe vêm pela ordem em que se veem (da esquerda para a direita, célula a célula): cada linha
 * sai na ordem lógica, para o texto do sistema a mostrar da direita para a esquerda (e ligar as letras árabes).
 */
class TeletextDecoder(private val page: Int, language: String? = null) {
    private val magazine = (page shr 8) and 7
    private val rows = arrayOfNulls<ByteArray>(25)
    private var collecting = false
    private var charset = 0
    private val defaultGroup = languageGroup(language)
    private var pageGroup = -1
    private var magazineGroup = -1
    private var table: CharArray = G0.getValue(0)
    private var g2: CharArray? = null
    private var shown: List<String> = emptyList()

    /** Os caracteres do G2 postos por X/26 (linha × 40 + coluna → código de 7 bits). */
    private val enhancements = HashMap<Int, Int>()

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
                pageGroup = -1
                enhancements.clear()
                // C4 (apagar) ou uma página nova: as linhas de antes saem.
                if ((c4 and 8) != 0) rows.fill(null)
            }
            return done
        }
        if (collecting && mag == magazine && packet in 1..24) rows[packet] = data
        if (collecting && mag == magazine && packet == 26) enhance(data)
        // X/28/0 e X/28/4 (a página) ou M/29/0 e M/29/4 (a magazine toda): o alfabeto, no 1.º tripleto (bits 8–14).
        if (mag == magazine && (packet == 28 && collecting || packet == 29)) {
            val designation = hamming(data[0])
            val triplet = hamming24(data, 1)
            if ((designation == 0 || designation == 4) && triplet >= 0) {
                val group = ((triplet shr 7) and 0x7F) shr 3
                if (packet == 28) pageGroup = group else magazineGroup = group
            }
        }
        return null
    }

    /**
     * X/26 (EN 300 706, 12.3): 13 tripletos de endereço, modo e dados. Só se guardam os caracteres do G2 (modo 0x0F),
     * na linha ativa (posta pelos tripletos de linha, modos 0x01 e 0x04).
     */
    private var activeRow = 0

    private fun enhance(data: ByteArray) {
        if (hamming(data[0]) == 0) activeRow = 0
        for (k in 0 until 13) {
            val t = hamming24(data, 1 + k * 3)
            if (t < 0) continue
            val address = t and 0x3F
            val mode = (t shr 6) and 0x1F
            val value = (t shr 11) and 0x7F
            if (address >= 40) {
                if (mode == 0x1F) return
                if (mode == 0x01 || mode == 0x04) activeRow = if (address == 40) 24 else address - 40
            } else if (mode == 0x0F && activeRow in 1..24 && value >= 0x20) {
                enhancements[activeRow * 40 + address] = value
            }
        }
    }

    /** O alfabeto da página: o grupo com a variante do cabeçalho; se essa combinação não existir, o código do grupo. */
    private fun chooseTable(): CharArray {
        val group = currentGroup()
        return G0[group * 8 + charset] ?: G0[group * 8] ?: G0[charset] ?: G0.getValue(0)
    }

    private fun currentGroup() = if (pageGroup >= 0) pageGroup else if (magazineGroup >= 0) magazineGroup else defaultGroup

    private fun publish(): List<String>? {
        table = chooseTable()
        // O G2 árabe é o dos grupos do árabe e do hebraico (tabela 32: códigos 0x40–0x47 e 0x50–0x57).
        g2 = ARABIC_G2.takeIf { currentGroup() == 8 || currentGroup() == 10 }
        val text = (1..24).mapNotNull { r -> rows[r]?.let { rowText(r, it) } }.filter { it.isNotBlank() }
        rows.fill(null)
        enhancements.clear()
        if (text == shown) return null
        shown = text
        return text
    }

    /** O texto da linha [r]: só o que está nas caixas (start box 0x0B … end box 0x0A), se as houver. */
    private fun rowText(r: Int, row: ByteArray): String {
        val chars = row.map { (it.toInt() and 0x7F) }
        val boxed = chars.contains(0x0B)
        val out = StringBuilder()
        var inBox = !boxed
        val g2 = g2
        for ((column, c) in chars.withIndex()) {
            val extra = if (g2 != null) enhancements[r * 40 + column] else null
            when {
                c == 0x0B -> inBox = true
                c == 0x0A -> if (boxed) inBox = false
                !inBox -> {}
                extra != null && g2 != null -> out.append(g2[extra - 0x20])
                c < 0x20 -> out.append(' ')
                else -> out.append(table[c - 0x20])
            }
        }
        return logical(out.toString().replace(Regex(" {2,}"), " ").trim())
    }

    companion object {
        /** As posições do G0 latino que mudam com a língua (EN 300 706, tabela 36). */
        private val NATIONAL_POSITIONS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)

        private const val ASCII = " !\"#\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~ "

        /** O G0 latino com as 13 posições da variante. */
        private fun latin(national: String): CharArray {
            val t = ASCII.toCharArray()
            NATIONAL_POSITIONS.forEachIndexed { i, at -> t[at - 0x20] = national[i] }
            return t
        }

        /** Um G0 de 0x20 a 0x7F: o ASCII até 0x3F (com [low] por cima, posição → letra) e [high] de 0x40 a 0x7F. */
        private fun table(high: String, low: Map<Int, Char> = emptyMap()): CharArray {
            val t = (ASCII.substring(0, 0x20) + high).toCharArray()
            for ((at, c) in low) t[at - 0x20] = c
            return t
        }

        private val ENGLISH = latin("£\$@←½→↑#―¼‖¾÷")
        private val GERMAN = latin("#\$§ÄÖÜ^_°äöüß")
        private val SWEDISH = latin("#¤ÉÄÖÅÜ_éäöåü")
        private val ITALIAN = latin("£\$é°ç→↑#ùàòèì")
        private val FRENCH = latin("éïàëêùî#èâôûç")
        private val SPANISH = latin("ç\$¡áéíóú¿üñèà")
        private val CZECH = latin("#ůčťžýířéáěúš")
        private val POLISH = latin("#ńąŻŚŁćóężśłź")
        private val TURKISH = latin("₺ğİŞÖÇÜĞışöçü")
        private val SERBIAN = latin("#ËČĆŽĐŠëčćžđš")
        private val ROMANIAN = latin("#¤ŢÂŞĂÎıţâşăî")
        private val ESTONIAN = latin("#õŠÄÖŽÜÕšäöžü")
        private val LITHUANIAN = latin("#\$ŠėęŽčūšąųžį")

        private val CYRILLIC_1 = table("ЧАБЦДЕФГХИЈКЛМНОПЌРСТУВЃЉЊЗЋЖЂШЏчабцдефгхијклмнопќрстувѓљњзћжђшџ")
        private val CYRILLIC_2 = table("ЮАБЦДЕФГХИЙКЛМНОПЯРСТУЖВЬЪЗШЭЩЧЫюабцдефгхийклмнопярстужвьъзшэщчы", mapOf(0x26 to 'ы'))
        private val CYRILLIC_3 = table("ЮАБЦДЕФГХИЙКЛМНОПЯРСТУЖВЬЇЗШЄЩЧІюабцдефгхийклмнопярстужвьїзшєщчі", mapOf(0x26 to 'ї'))
        private val GREEK = table(
            CharArray(0x40) { if (it == 0x12) 'ʹ' else if (it == 0x3F) ' ' else (0x0390 + it).toChar() }.concatToString(),
            mapOf(0x3C to '«', 0x3E to '»'),
        )
        private val HEBREW = latin("£\$@←½→↑#―₪‖¾÷").also { t ->
            for (c in 0x60..0x7A) t[c - 0x20] = (0x05D0 + c - 0x60).toChar()
        }

        /**
         * O G0 árabe (EN 300 706; as mesmas posições do zvbi e do tuxtxt/Kodi), como se vê: as letras nas formas que a
         * tabela distingue (isolada, inicial, média, final; Unicode FE70–FEFF), os parênteses e o < > virados (a linha
         * está pela ordem visual).
         */
        private val ARABIC = chars(
            0x0020, 0x0021, 0x0022, 0x00A3, 0x0024, 0x066A, 0xFEF0, 0xFEF2, 0x0029, 0x0028, 0x002A, 0x002B, 0x060C, 0x002D, 0x002E, 0x002F,
            0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037, 0x0038, 0x0039, 0x003A, 0x061B, 0x003E, 0x003D, 0x003C, 0x061F,
            0xFE94, 0x0621, 0xFE92, 0x0628, 0xFE98, 0x062A, 0xFE8E, 0xFE8D, 0xFE91, 0xFE93, 0xFE97, 0xFE9B, 0xFE9F, 0xFEA3, 0xFEA7, 0xFEA9,
            0x0630, 0xFEAD, 0xFEAF, 0xFEB3, 0xFEB7, 0xFEBB, 0xFEBF, 0xFEC1, 0xFEC5, 0xFECB, 0xFECF, 0xFE9C, 0xFEA0, 0xFEA4, 0xFEA8, 0x0023,
            0x0640, 0xFED3, 0xFED7, 0xFEDB, 0xFEDF, 0xFEE3, 0xFEE7, 0xFEEB, 0xFEED, 0xFEEF, 0xFEF3, 0xFE99, 0xFE9D, 0xFEA1, 0xFEA5, 0xFEF4,
            0xFEF0, 0xFECC, 0xFED0, 0xFED4, 0xFED1, 0xFED8, 0xFED5, 0xFED9, 0xFEE0, 0xFEDD, 0xFEE4, 0xFEE1, 0xFEE8, 0xFEE5, 0xFEFB, 0x0020,
        )

        /**
         * O G2 árabe, o dos canais árabes e hebraicos (por X/26): mais letras e formas (hamza, as letras
         * persas), os algarismos árabes e as letras latinas.
         */
        internal val ARABIC_G2 = chars(
            0x0020, 0x0639, 0xFEC9, 0xFE83, 0xFE85, 0xFE87, 0xFE8B, 0xFE89, 0xFB7C, 0xFB7D, 0xFB7A, 0xFB58, 0xFB59, 0xFB56, 0xFB6D, 0xFB8E,
            0x0660, 0x0661, 0x0662, 0x0663, 0x0664, 0x0665, 0x0666, 0x0667, 0x0668, 0x0669, 0xFECE, 0xFECD, 0xFEFC, 0xFEEC, 0xFEEA, 0xFEE9,
            0x00E0, 0x0041, 0x0042, 0x0043, 0x0044, 0x0045, 0x0046, 0x0047, 0x0048, 0x0049, 0x004A, 0x004B, 0x004C, 0x004D, 0x004E, 0x004F,
            0x0050, 0x0051, 0x0052, 0x0053, 0x0054, 0x0055, 0x0056, 0x0057, 0x0058, 0x0059, 0x005A, 0x00EB, 0x00EA, 0x00F9, 0x00EE, 0xFECA,
            0x00E9, 0x0061, 0x0062, 0x0063, 0x0064, 0x0065, 0x0066, 0x0067, 0x0068, 0x0069, 0x006A, 0x006B, 0x006C, 0x006D, 0x006E, 0x006F,
            0x0070, 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076, 0x0077, 0x0078, 0x0079, 0x007A, 0x00E2, 0x00F4, 0x00FB, 0x00E7, 0x0020,
        )

        private fun chars(vararg codes: Int) = CharArray(codes.size) { codes[it].toChar() }

        /**
         * As formas de apresentação (FE80–FEFC, e as das letras persas do G2) na letra de base, para o texto do sistema
         * as voltar a ligar pela posição na palavra (lam-alef: as duas letras).
         */
        private val FORMS: Map<Char, String> = HashMap<Char, String>().also { m ->
            // Pela ordem do Unicode, quantas formas tem cada letra a partir de FE80.
            val letters = listOf(
                "\u0621" to 1, "\u0622" to 2, "\u0623" to 2, "\u0624" to 2, "\u0625" to 2, "\u0626" to 4, "\u0627" to 2,
                "\u0628" to 4, "\u0629" to 2, "\u062A" to 4, "\u062B" to 4, "\u062C" to 4, "\u062D" to 4, "\u062E" to 4,
                "\u062F" to 2, "\u0630" to 2, "\u0631" to 2, "\u0632" to 2, "\u0633" to 4, "\u0634" to 4, "\u0635" to 4,
                "\u0636" to 4, "\u0637" to 4, "\u0638" to 4, "\u0639" to 4, "\u063A" to 4, "\u0641" to 4, "\u0642" to 4,
                "\u0643" to 4, "\u0644" to 4, "\u0645" to 4, "\u0646" to 4, "\u0647" to 4, "\u0648" to 2, "\u0649" to 2,
                "\u064A" to 4, "\u0644\u0622" to 2, "\u0644\u0623" to 2, "\u0644\u0625" to 2, "\u0644\u0627" to 2,
            )
            var code = 0xFE80
            for ((base, count) in letters) repeat(count) { m[(code++).toChar()] = base }
            // As persas do G2: peh (FB56–FB59), tcheh (FB7A–FB7D), veh (FB6A–FB6D), keheh (FB8E–FB91).
            for ((start, base) in listOf(0xFB56 to "\u067E", 0xFB7A to "\u0686", 0xFB6A to "\u06A4", 0xFB8E to "\u06A9")) {
                for (k in 0 until 4) m[(start + k).toChar()] = base
            }
        }

        /** Uma letra hebraica ou árabe (os algarismos árabes, como os outros, vão da esquerda para a direita). */
        private fun isRtl(c: Char) = c.isLetter() && (c in '\u0590'..'\u08FF' || c in '\uFB1D'..'\uFDFF' || c in '\uFE70'..'\uFEFF')

        private val MIRRORED = mapOf('(' to ')', ')' to '(', '<' to '>', '>' to '<', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '«' to '»', '»' to '«')

        /**
         * Uma linha do teletexto (ordem visual, da esquerda para a direita) na ordem lógica, quando tem hebraico ou
         * árabe: tudo ao contrário, menos os pedaços da esquerda para a direita (números, palavras latinas), que voltam
         * a ficar como estavam; os parênteses fora deles trocam (o sistema volta a virá-los no texto da direita para a
         * esquerda). As formas árabes ficam na letra de base, e uma linha que comece por latim leva a marca RLM para
         * se mostrar da direita para a esquerda. Sem letras dessas, fica como está.
         */
        internal fun logical(visual: String): String {
            if (visual.none(::isRtl)) return visual
            val out = visual.reversed().toCharArray()
            val ltr = BooleanArray(out.size)
            var i = 0
            while (i < out.size) {
                if (isRtl(out[i]) || !out[i].isLetterOrDigit()) {
                    i++
                    continue
                }
                // Um pedaço da esquerda para a direita: de uma letra ou algarismo ao último antes de uma letra RTL.
                var end = i
                var j = i
                while (j < out.size && !isRtl(out[j])) {
                    if (out[j].isLetterOrDigit()) end = j
                    j++
                }
                out.reverse(i, end + 1)
                for (k in i..end) ltr[k] = true
                i = end + 1
            }
            val text = StringBuilder()
            for (k in out.indices) {
                val c = out[k]
                text.append(if (ltr[k]) c.toString() else MIRRORED[c]?.toString() ?: FORMS[c] ?: c.toString())
            }
            val firstStrong = text.firstOrNull { it.isLetter() }
            return if (firstStrong != null && !isRtl(firstStrong)) "\u200F$text" else text.toString()
        }

        /** Os alfabetos pelo código de 7 bits da tabela 32 (grupo × 8 + variante). */
        internal val G0: Map<Int, CharArray> = mapOf(
            0 to ENGLISH, 1 to GERMAN, 2 to SWEDISH, 3 to ITALIAN, 4 to FRENCH, 5 to SPANISH, 6 to CZECH,
            8 to POLISH, 9 to GERMAN, 10 to SWEDISH, 11 to ITALIAN, 12 to FRENCH, 14 to CZECH,
            16 to ENGLISH, 17 to GERMAN, 18 to SWEDISH, 19 to ITALIAN, 20 to FRENCH, 21 to SPANISH, 22 to TURKISH,
            29 to SERBIAN, 31 to ROMANIAN,
            32 to CYRILLIC_1, 33 to GERMAN, 34 to ESTONIAN, 35 to LITHUANIAN, 36 to CYRILLIC_2, 37 to CYRILLIC_3, 38 to CZECH,
            54 to TURKISH, 55 to GREEK,
            64 to ENGLISH, 68 to FRENCH, 71 to ARABIC,
            85 to HEBREW, 87 to ARABIC,
        )

        /** O grupo da tabela 32 a usar quando o canal não manda X/28 nem M/29: o da língua da página no PMT. */
        internal fun languageGroup(language: String?): Int = when (language) {
            "pol" -> 1
            "tur" -> 2
            "slv", "hrv", "scr", "ron", "rum", "bos" -> 3
            "rus", "bul", "ukr", "srp", "scc", "mkd", "mac", "bel", "est", "lav", "lit" -> 4
            "ell", "gre" -> 6
            "heb" -> 10
            "ara" -> 8
            else -> 0
        }

        /**
         * Hamming 24/18 (3 bytes a partir de [at]): os 18 bits de dados, com um bit errado corrigido; -1 se houver mais.
         * Os bits contam-se de 1 (o menos significativo do 1.º byte) a 24; os de proteção estão em 1, 2, 4, 8, 16 e 24.
         */
        internal fun hamming24(d: ByteArray, at: Int): Int {
            var word = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8) or ((d[at + 2].toInt() and 0xFF) shl 16)
            var syndrome = 0
            for (k in 0 until 5) {
                var parity = 0
                for (bit in 1..23) if ((bit shr k) and 1 == 1) parity = parity xor ((word shr (bit - 1)) and 1)
                if (parity == 0) syndrome = syndrome or (1 shl k)
            }
            val overallOk = word.countOneBits() % 2 == 1
            if (syndrome != 0) {
                // Um bit errado mexe na paridade total; dois não, e esses não se corrigem.
                if (overallOk || syndrome > 23) return -1
                word = word xor (1 shl (syndrome - 1))
            } else if (!overallOk) {
                word = word xor (1 shl 23)
            }
            return ((word shr 2) and 1) or (((word shr 4) and 7) shl 1) or (((word shr 8) and 0x7F) shl 4) or (((word shr 16) and 0x7F) shl 11)
        }

        /** Para os testes: [v] (18 bits) em Hamming 24/18, com paridade ímpar em cada teste. */
        internal fun hamming24Encode(v: Int): ByteArray {
            var word = ((v and 1) shl 2) or (((v shr 1) and 7) shl 4) or (((v shr 4) and 0x7F) shl 8) or (((v shr 11) and 0x7F) shl 16)
            for (k in 0 until 5) {
                var parity = 0
                for (bit in 1..23) if ((bit shr k) and 1 == 1) parity = parity xor ((word shr (bit - 1)) and 1)
                if (parity == 0) word = word or (1 shl ((1 shl k) - 1))
            }
            if (word.countOneBits() % 2 == 0) word = word or (1 shl 23)
            return byteArrayOf(word.toByte(), (word shr 8).toByte(), (word shr 16).toByte())
        }

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
