package cc.slokker.itv.data

/**
 * O país (ou a região) de um grupo da lista, pelo nome: `PT | Desporto`, `|UK| Movies`, `[FR] Cinéma`, `🇩🇪 Sport`,
 * `PORTUGAL`, `Deutschland HD`, `LATINO`, `ARABIC`, `EX-YU`… Serve o filtro de países das Definições: só os grupos
 * dos países escolhidos ficam à vista (os novos que o fornecedor juntar também), e os sem país são uma escolha à parte.
 *
 * Os códigos são o ISO 3166-1 em minúsculas, mais as regiões: [LATAM], [AFRICA], [ASIA], [CARIBBEAN] (os códigos M.49, que as duas
 * plataformas já sabem dizer na língua da pessoa), [ARAB] e [EXYU].
 */
object GroupCountries {
    /** América Latina ("LATINO", "LATAM", "LA |"). */
    const val LATAM = "419"
    const val AFRICA = "002"
    const val ASIA = "142"
    const val CARIBBEAN = "029"
    /** Canais árabes ("AR |", "ARABIC", "عربي"): é uma língua, não um país (o "AR" das listas raramente é a Argentina). */
    const val ARAB = "arab"
    /** Ex-Jugoslávia ("EX-YU", "BALKAN"). */
    const val EXYU = "exyu"

    /** Na escolha guardada: os grupos sem país. */
    const val NONE = "none"

    /** O país de [name], ou null se não se percebe. */
    fun of(name: String): String? {
        flag(name)?.let { return it }
        val words = words(fold(name))
        if (words.isEmpty()) return null
        // Um país por extenso ganha à etiqueta: os fornecedores usam códigos seus ("SR| SERBIA", "BH| BOSNIA",
        // "TN| TAIWAN"), e "AR| MOROCCO" ou "ASIA| PAKISTAN" dizem mais que a região.
        countryName(words)?.let { return it }
        // A etiqueta à frente, até ao primeiro separador ("PT | …", "[UK] …", "FR: …"): aí valem também os códigos de
        // 3 letras e os que se confundem com palavras.
        leadingTagWords(name)?.let { tag -> for (w in tag) code(w, inTag = true)?.let { return it } }
        // Regiões e gentílicos em qualquer sítio ("LATINO", "PORTUGUESE MOVIES", "EX-YU").
        keyword(words)?.let { return it }
        // Um código solto à frente, em maiúsculas: "PT SPORT", "UK MOVIES".
        val first = name.trimStart { !it.isLetterOrDigit() }.takeWhile { it.isLetterOrDigit() }
        if (first.length == 2 && first == first.uppercase() && first.lowercase() !in BARE_WORDS) code(words[0], inTag = true)?.let { return it }
        // Uma etiqueta no fim: "Sport (PT)", "Movies [DE]".
        trailingTag(name)?.let { t -> words(fold(t)).singleOrNull()?.let { w -> code(w, inTag = true)?.let { return it } } }
        return null
    }

    /**
     * Os grupos de [groups] (id → nome) que o filtro [allowed] esconde: os de países fora dele, e os sem país se não
     * tiver [NONE]. Sem filtro (null ou vazio), nenhum.
     */
    fun hidden(groups: List<Category>, allowed: Collection<String>?): Set<String> {
        if (allowed.isNullOrEmpty()) return emptySet()
        val keep = allowed.toSet()
        return groups.filter { (of(it.name) ?: NONE) !in keep }.mapTo(HashSet()) { it.id }
    }

    /** Os países de [groups], com quantos grupos cada um, pela ordem de mais grupos; os sem país em [NONE], no fim. */
    fun count(groups: List<Category>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (g in groups) {
            val c = of(g.name) ?: NONE
            counts[c] = (counts[c] ?: 0) + 1
        }
        val none = counts.remove(NONE)
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value } + listOfNotNull(none?.let { NONE to it })
    }

    // ---- Por dentro ------------------------------------------------------------------------------------------------

    /** Os códigos que, soltos à frente sem separador, são mais vezes palavras ("IT Crowd", "MY …", "NO …"). */
    private val BARE_WORDS = setOf("it", "in", "is", "to", "by", "me", "my", "so", "do", "go", "be", "at", "no", "on", "as", "am", "an", "or", "us")

    /** Códigos ISO que nas listas são outra coisa: NF = Netflix, BT = BT Sport, TV/HD/SD/FM… */
    private val NOT_COUNTRY = setOf("tv", "hd", "sd", "fm", "am", "hq", "lq", "nf", "bt", "en")

    private val ALIASES = mapOf(
        "uk" to "gb", "eng" to "gb", "ger" to "de", "ned" to "nl", "hol" to "nl", "por" to "pt", "esp" to "es",
        "spa" to "es", "ita" to "it", "fra" to "fr", "usa" to "us", "sui" to "ch", "den" to "dk", "gre" to "gr",
        "ar" to ARAB, "la" to LATAM, "afr" to AFRICA, "lat" to LATAM, "car" to CARIBBEAN, "yu" to EXYU, "exyu" to EXYU, "alb" to "al", "bul" to "bg", "cro" to "hr",
        "srb" to "rs", "rom" to "ro", "slo" to "si", "bra" to "br", "arg" to "ar", "mex" to "mx",
        "irl" to "ie", "kor" to "kr", "jpn" to "jp", "chn" to "cn", "ind" to "in", "pak" to "pk",
    )

    /** Palavras de região e gentílicos comuns ("PORTUGUESE MOVIES", "Deutsch", "Latino"). */
    private val KEYWORDS = mapOf(
        "latino" to LATAM, "latina" to LATAM, "latinos" to LATAM, "latam" to LATAM, "latin" to LATAM,
        "arab" to ARAB, "arabic" to ARAB, "arabe" to ARAB, "arabes" to ARAB, "عربي" to ARAB, "العربية" to ARAB,
        "balkan" to EXYU, "balkans" to EXYU, "exyu" to EXYU,
        "africa" to AFRICA, "african" to AFRICA, "afrique" to AFRICA, "africain" to AFRICA,
        "asia" to ASIA, "asian" to ASIA, "asie" to ASIA, "caribbean" to CARIBBEAN, "caribe" to CARIBBEAN, "caraibes" to CARIBBEAN,
        "portuguese" to "pt", "portugues" to "pt", "portugueses" to "pt", "brasileiro" to "br", "brazilian" to "br",
        "french" to "fr", "francais" to "fr", "german" to "de", "deutsch" to "de", "italian" to "it", "italiano" to "it",
        "spanish" to "es", "espanol" to "es", "turkish" to "tr", "turk" to "tr", "turkce" to "tr", "polish" to "pl",
        "polski" to "pl", "dutch" to "nl", "nederlands" to "nl", "greek" to "gr", "albanian" to "al", "shqip" to "al",
        "romanian" to "ro", "romanesc" to "ro", "hungarian" to "hu", "magyar" to "hu", "czech" to "cz", "swedish" to "se",
        "svenska" to "se", "danish" to "dk", "norwegian" to "no", "finnish" to "fi", "russian" to "ru", "ukrainian" to "ua",
        "persian" to "ir", "iranian" to "ir", "indian" to "in", "pakistani" to "pk", "filipino" to "ph", "pinoy" to "ph",
        "irish" to "ie", "scottish" to "gb", "british" to "gb", "american" to "us", "canadian" to "ca", "mexican" to "mx",
        "bosnia" to "ba", "england" to "gb", "scotland" to "gb", "wales" to "gb", "holland" to "nl", "chinese" to "cn", "japanese" to "jp", "korean" to "kr", "vietnamese" to "vn", "thai" to "th", "kurdish" to "iq",
    )

    private val iso2: Set<String> by lazy { (0 until CountryTables.ISO.length / 5).mapTo(HashSet()) { CountryTables.ISO.substring(it * 5, it * 5 + 2) } }

    private val iso3: Map<String, String> by lazy {
        (0 until CountryTables.ISO.length / 5).mapNotNull { i ->
            val three = CountryTables.ISO.substring(i * 5 + 2, i * 5 + 5)
            if (three.isBlank()) null else three to CountryTables.ISO.substring(i * 5, i * 5 + 2)
        }.toMap()
    }

    /** Nome dobrado (palavras separadas por um espaço) → código; e o maior número de palavras de um nome. */
    private val byName: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (line in CountryTables.NAMES.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq != 2) continue
            val code = line.substring(0, 2)
            for (n in line.substring(3).split('|')) if (n.isNotEmpty()) map.putIfAbsent(n, code)
        }
        map
    }

    private val longestName: Int by lazy { byName.keys.maxOf { it.count { c -> c == ' ' } + 1 } }

    /** O código de uma palavra: ISO, aliás ou região; os de 3 letras e "AR"/"LA" só dentro de uma etiqueta. */
    private fun code(w: String, inTag: Boolean): String? {
        KEYWORDS[w]?.let { return it }
        if (w in NOT_COUNTRY) return null
        if (inTag) ALIASES[w]?.let { return it }
        if (w.length == 2 && w in iso2) return if (inTag || w !in ALIASES) w else null
        if (inTag && w.length == 3) iso3[w]?.let { return it }
        return null
    }

    /** O primeiro nome de país em [words] (o mais comprido em cada posição: "south africa" antes de "africa"). */
    private fun countryName(words: List<String>): String? {
        for (i in words.indices) {
            for (n in minOf(longestName, words.size - i) downTo 1) {
                val key = if (n == 1) words[i] else words.subList(i, i + n).joinToString(" ")
                byName[key]?.let { return it }
            }
            // Os gentílicos e nomes curtos ("PORTUGUESE", "BOSNIA") também são um país, não uma região.
            KEYWORDS[words[i]]?.takeIf { it.length == 2 }?.let { return it }
        }
        return null
    }

    /** A primeira palavra de região ou gentílico em [words]. */
    private fun keyword(words: List<String>): String? {
        for (i in words.indices) {
            KEYWORDS[words[i]]?.let { return it }
            // "EX-YU" e "EX YU" são duas palavras.
            if (words[i] == "ex" && words.getOrNull(i + 1) == "yu") return EXYU
        }
        return null
    }

    /** A bandeira em emoji (dois indicadores regionais): 🇵🇹 → "pt". */
    private fun flag(s: String): String? {
        var i = 0
        var first = -1
        while (i < s.length) {
            val cp: Int
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                cp = ((s[i].code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                cp = s[i].code
                i++
            }
            if (cp in 0x1F1E6..0x1F1FF) {
                if (first < 0) {
                    first = cp
                } else {
                    val code = "${'a' + (first - 0x1F1E6)}${'a' + (cp - 0x1F1E6)}"
                    if (code == "uk") return "gb"
                    if (code in iso2) return code
                    first = -1
                }
            } else {
                first = -1
            }
        }
        return null
    }

    /** As palavras antes do primeiro separador, se houver um e forem até 3: `PT | …`, `[UK] …`, `|FR| …`, `DE: …`. */
    private fun leadingTagWords(name: String): List<String>? {
        val s = name.trim()
        val open = s.firstOrNull() ?: return null
        val body = if (open == '[' || open == '(' || open == '|' || open == '{') {
            val close = when (open) { '[' -> ']'; '(' -> ')'; '{' -> '}'; else -> '|' }
            val end = s.indexOf(close, 1)
            if (end < 0) return null
            s.substring(1, end)
        } else {
            val sep = s.indexOfFirst { it in SEPARATORS }
            val dash = s.indexOf(" - ")
            val cut = listOf(sep, dash).filter { it > 0 }.minOrNull() ?: return null
            s.substring(0, cut)
        }
        val w = words(fold(body))
        return w.takeIf { it.isNotEmpty() && it.size <= 3 }
    }

    /** O que está entre parênteses ou parênteses retos no fim. */
    private fun trailingTag(name: String): String? {
        val s = name.trimEnd()
        val close = s.lastOrNull() ?: return null
        val open = when (close) { ')' -> '('; ']' -> '['; else -> return null }
        val start = s.lastIndexOf(open)
        return if (start > 0) s.substring(start + 1, s.length - 1) else null
    }

    private const val SEPARATORS = "|:▎┃│¦»•"

    /** Sem acentos (e com as formas de compatibilidade), em minúsculas. */
    private fun fold(s: String): String = nfkd(s.lowercase()).filter { it.category != CharCategory.NON_SPACING_MARK }

    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (c.isLetterOrDigit()) {
                cur.append(c)
            } else if (cur.isNotEmpty()) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
