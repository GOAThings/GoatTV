package cc.slokker.itv.data

import java.text.Normalizer
import java.util.Locale

/**
 * O "mesmo canal" noutra lista (a lista de reserva, quando o fornecedor principal não responde). As mesmas regras na
 * Apple (GoatCore ChannelMatch.swift), conferidas pelos mesmos casos (app/src/test/resources/channel_match.json).
 *
 * 1. O id do guia (`tvg-id` / `epg_channel_id`), quando os dois têm e são iguais (sem contar maiúsculas).
 * 2. O nome normalizado ([parse]): sem o país/grupo à frente (`PT|`, `PT:`, `[PT]`, `|PT|`…), sem a qualidade (HD,
 *    FHD, UHD, 4K, ᴴᴰ…), sem acentos, pontuação, espaços nem maiúsculas. O `+1` fica: SIC e SIC +1 são canais diferentes.
 * 3. Vários candidatos: o do mesmo país e depois o de melhor qualidade. Se continuar a haver dúvida (países diferentes,
 *    ou nomes diferentes com o mesmo id do guia), não se escolhe: nunca se põe a dar um canal errado.
 */
object ChannelMatch {
    /** Um nome partido: a [base] que se compara, o país (código de 2 letras), a qualidade ([Quality]) e o `+N`. */
    data class Parsed(val base: String, val country: String?, val quality: Int, val shift: Int)

    /** Um canal da lista de reserva (ou o que se procura). */
    data class Entry(val key: String, val name: String, val epgId: String?)

    sealed interface Result {
        /** O canal [index] da lista de reserva. */
        data class Match(val index: Int) : Result

        /** Há [count] candidatos e nada que os desempate: não se muda de lista. */
        data class Unsure(val count: Int) : Result

        data object Missing : Result
    }

    /** Escolha à mão canal a canal (para um ecrã futuro): a chave do canal na reserva, ou [OFF] para nunca mudar. */
    const val OFF = "off"

    object Quality {
        const val SD = 1
        const val NONE = 2
        const val HD = 3
        const val FHD = 4
        const val UHD = 5
        const val Q8K = 6
    }

    private val QUALITY = mapOf(
        "sd" to Quality.SD, "480p" to Quality.SD, "576p" to Quality.SD,
        "hd" to Quality.HD, "720p" to Quality.HD, "hq" to Quality.HD,
        "fhd" to Quality.FHD, "1080p" to Quality.FHD, "1080i" to Quality.FHD, "fullhd" to Quality.FHD,
        "uhd" to Quality.UHD, "4k" to Quality.UHD, "2160p" to Quality.UHD,
        "8k" to Quality.Q8K,
    )

    /** Marcas técnicas que se tiram sem contar para a qualidade. */
    private val TECH = setOf("hevc", "h265", "h264", "x265", "x264", "hdr", "hdr10", "sdr", "50fps", "60fps", "25fps", "30fps", "fps", "vip", "raw")

    /** Palavras de 2 letras que não são países (ficam de fora do país, mas tiram-se na mesma do prefixo). */
    private val NOT_COUNTRY = setOf("tv", "hd", "sd", "fm", "am", "hq", "lq")

    private val COUNTRY_ALIASES = mapOf(
        "uk" to "gb", "gbr" to "gb", "eng" to "gb", "por" to "pt", "prt" to "pt", "portugal" to "pt",
        "bra" to "br", "brasil" to "br", "brazil" to "br", "esp" to "es", "espana" to "es", "spain" to "es",
        "fra" to "fr", "france" to "fr", "ger" to "de", "deu" to "de", "germany" to "de", "ita" to "it", "italia" to "it",
        "italy" to "it", "usa" to "us", "ned" to "nl", "nld" to "nl", "bel" to "be", "pol" to "pl", "rou" to "ro",
        "tur" to "tr", "arg" to "ar", "mex" to "mx", "can" to "ca", "irl" to "ie", "che" to "ch", "aut" to "at",
    )

    /** As letras pequenas por cima (ᴴᴰ, ᵁᴴᴰ, ⁴ᴷ…) que os fornecedores colam ao nome. */
    private val SUPERSCRIPT = mapOf(
        'ᴬ' to 'A', 'ᴮ' to 'B', 'ᶜ' to 'C', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᶠ' to 'F', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I',
        'ᴶ' to 'J', 'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ˢ' to 'S',
        'ᵀ' to 'T', 'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W', 'ˣ' to 'X', 'ʰ' to 'H', 'ᵈ' to 'D', 'ᵏ' to 'K', 'ˡ' to 'L',
        'ᵖ' to 'P', 'ᵗ' to 'T', 'ᵘ' to 'U', 'ᵛ' to 'V', 'ᵐ' to 'M', 'ⁿ' to 'N', 'ᵒ' to 'O', 'ᵉ' to 'E', 'ᵃ' to 'A',
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
    )

    /** O que separa o prefixo do nome: `PT|`, `PT:`, `PT ▎`… */
    private const val PREFIX_SEPARATORS = "|:▎┃│¦»"

    private const val TAG_MAX = 12

    // ---- O nome ---------------------------------------------------------------------------------

    fun parse(name: String): Parsed {
        var s = fold(spaceSuperscripts(name)).replace("h.265", "h265").replace("h.264", "h264")
        var country: String? = null
        var quality = 0
        fun take(tag: String): Boolean {
            val words = words(tag)
            if (words.isEmpty()) return false
            var known = true
            for (w in words) {
                when {
                    w in QUALITY -> quality = maxOf(quality, QUALITY.getValue(w))
                    w in TECH -> Unit
                    countryOf(w) != null -> if (country == null) country = countryOf(w)
                    else -> known = false
                }
            }
            return known
        }
        // À frente: até 3 etiquetas ("[PT] VIP| RTP 1"); um grupo desconhecido também sai, mas sem país.
        repeat(3) {
            val t = s.trimStart()
            val cut = leadingTag(t) ?: return@repeat
            take(cut.first)
            s = cut.second
        }
        // No fim: "(PT)", "[FHD]"; só se sair tudo o que lá está for país ou qualidade (senão é parte do nome).
        repeat(3) {
            val t = s.trimEnd()
            val cut = trailingTag(t) ?: return@repeat
            if (!isKnownTag(cut.first)) return@repeat
            take(cut.first)
            s = cut.second
        }
        val shifted = shift(s)
        s = shifted.second
        val base = StringBuilder()
        for (w in words(s)) {
            when {
                w in QUALITY -> quality = maxOf(quality, QUALITY.getValue(w))
                w in TECH -> Unit
                else -> base.append(w)
            }
        }
        return Parsed(base.toString(), country, if (quality == 0) Quality.NONE else quality, shifted.first)
    }

    private fun isKnownTag(tag: String): Boolean {
        val w = words(tag)
        return w.isNotEmpty() && w.all { it in QUALITY || it in TECH || countryOf(it) != null }
    }

    private fun countryOf(word: String): String? {
        COUNTRY_ALIASES[word]?.let { return it }
        if (word.length == 2 && word.all { it in 'a'..'z' } && word !in NOT_COUNTRY) return word
        return null
    }

    /** "ᴴᴰ" colado ao nome passa a ser uma palavra à parte ("SICᴴᴰ" → "SIC HD"). */
    private fun spaceSuperscripts(name: String): String {
        val out = StringBuilder()
        var inRun = false
        for (c in name) {
            val mapped = SUPERSCRIPT[c]
            if (mapped != null) {
                if (!inRun) out.append(' ')
                out.append(mapped)
                inRun = true
            } else {
                if (inRun) out.append(' ')
                out.append(c)
                inRun = false
            }
        }
        return out.toString()
    }

    /** Sem acentos (e com as formas de compatibilidade: ｜ → |), em minúsculas. */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase(Locale.ROOT)

    /** As palavras: o que é letra ou algarismo, separado pelo resto. */
    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (Character.isLetterOrDigit(c)) {
                cur.append(c)
            } else if (cur.isNotEmpty()) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** A etiqueta à frente de [s] e o resto: `[pt] …`, `(pt) …`, `|pt| …`, `pt: …`, `pt| …`, `pt - …`. */
    private fun leadingTag(s: String): Pair<String, String>? {
        if (s.isEmpty()) return null
        val open = s[0]
        val close = when (open) {
            '[' -> ']'
            '(' -> ')'
            '|' -> '|'
            else -> null
        }
        if (close != null) {
            val end = s.indexOf(close, 1)
            if (end < 0 || end - 1 > TAG_MAX) return null
            val rest = s.substring(end + 1).trimStart().let { r -> if (r.isNotEmpty() && r[0] in PREFIX_SEPARATORS) r.substring(1) else r }
            return s.substring(1, end).trim() to rest
        }
        val sep = s.indexOfFirst { it in PREFIX_SEPARATORS }
        if (sep in 1..TAG_MAX + 1) {
            val tag = s.substring(0, sep).trim()
            if (tag.isNotEmpty() && tag.split(' ').size <= 2 && tag.all { it == ' ' || Character.isLetterOrDigit(it) }) {
                val rest = s.substring(sep + 1).let { r -> if (r.isNotEmpty() && r[0] in PREFIX_SEPARATORS) r.substring(1) else r }
                // "Hollywood: FHD": o que vem depois é só a qualidade, por isso o nome é o que está antes.
                if (words(rest).any { it !in QUALITY && it !in TECH }) return tag to rest
            }
        }
        // "pt - rtp 1": só um país de 2–3 letras antes do traço (um traço a meio do nome fica).
        val dash = s.indexOf(" - ")
        if (dash in 2..3) {
            val tag = s.substring(0, dash)
            if (tag.all { it in 'a'..'z' } && countryOf(tag) != null) return tag to s.substring(dash + 3)
        }
        return null
    }

    /** A etiqueta entre parênteses no fim de [s] e o que fica antes. */
    private fun trailingTag(s: String): Pair<String, String>? {
        if (s.isEmpty()) return null
        val open = when (s.last()) {
            ']' -> '['
            ')' -> '('
            else -> return null
        }
        val start = s.lastIndexOf(open, s.length - 2)
        if (start < 0 || s.length - start - 2 > TAG_MAX) return null
        return s.substring(start + 1, s.length - 1).trim() to s.substring(0, start)
    }

    /**
     * O `+N` (o mesmo canal N horas mais tarde) e o nome sem ele. Só quando o `+` está colado ao número ("SIC+1",
     * "SIC (+1)") ou tem um espaço antes ("SIC + 1"): "Canal+ 1" não é um +1.
     */
    private fun shift(s: String): Pair<Int, String> {
        var i = s.indexOf('+')
        while (i >= 0) {
            var j = i + 1
            val spaceAfter = j < s.length && s[j] == ' '
            if (spaceAfter) j++
            var k = j
            while (k < s.length && k - j < 3 && s[k] in '0'..'9') k++
            val digits = k - j
            val spaceBefore = i > 0 && s[i - 1] == ' '
            val endsNumber = k >= s.length || s[k] !in '0'..'9'
            if (digits in 1..2 && endsNumber && (!spaceAfter || spaceBefore)) {
                // "+1h": o "h" (de horas) vai com ele.
                if (k < s.length && s[k] == 'h' && (k + 1 >= s.length || !Character.isLetterOrDigit(s[k + 1]))) k++
                return s.substring(j, j + digits).toInt() to (s.substring(0, i) + " " + s.substring(k))
            }
            i = s.indexOf('+', i + 1)
        }
        return 0 to s
    }

    /** O id do guia que serve para comparar (sem os vazios e os "none" de algumas listas). */
    fun epgKey(id: String?): String? =
        id?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.length >= 2 && it !in setOf("none", "null", "n/a", "0") }

    // ---- A procura --------------------------------------------------------------------------------

    /** Os canais de uma lista de reserva, já partidos e indexados (para procurar muitos sem voltar a partir). */
    class Matcher(val pool: List<Entry>) {
        private val parsed = pool.map { parse(it.name) }
        private val byEpg = HashMap<String, MutableList<Int>>()
        private val byBase = HashMap<String, MutableList<Int>>()
        private val byKey = HashMap<String, Int>()

        init {
            pool.forEachIndexed { i, e ->
                epgKey(e.epgId)?.let { byEpg.getOrPut(it) { ArrayList() } += i }
                parsed[i].base.takeIf { it.isNotEmpty() }?.let { byBase.getOrPut(it) { ArrayList() } += i }
                byKey.putIfAbsent(e.key, i)
            }
        }

        /** O canal [name] (id do guia [epgId]) nesta lista; [override]: a escolha à mão ([OFF] ou uma chave daqui). */
        fun find(name: String, epgId: String?, override: String? = null): Result {
            if (override == OFF) return Result.Missing
            override?.let { key -> byKey[key]?.let { return Result.Match(it) } }
            val t = parse(name)
            if (t.base.isEmpty()) return Result.Missing
            fun compatible(i: Int): Boolean {
                val c = parsed[i]
                return c.shift == t.shift && (t.country == null || c.country == null || c.country == t.country)
            }
            val byName = byBase[t.base].orEmpty().filter(::compatible)
            val named = choose(t, byName)
            val epg = epgKey(epgId)?.let { byEpg[it] }.orEmpty().filter(::compatible)
            if (epg.isNotEmpty()) {
                val sameName = epg.filter { parsed[it].base == t.base }
                if (sameName.isNotEmpty()) return choose(t, sameName)
                // O id do guia aponta para um canal com outro nome: só vale se não houver nenhum com o mesmo nome
                // (um id errado num dos fornecedores não pode ganhar ao nome).
                if (byName.isEmpty()) return choose(t, epg)
                return Result.Unsure(byName.size + epg.size)
            }
            return named
        }

        /**
         * Os canais daqui mais parecidos com [name] (para escolher à mão), do mais para o menos parecido: até [limit],
         * com uma parecença de pelo menos [MIN_SCORE] (0–100 pelo nome, mais o id do guia e o país; menos um `+N` ou
         * um país diferentes). Empates: a melhor qualidade, depois a ordem da lista.
         */
        fun ranked(name: String, epgId: String?, limit: Int = 8): List<Int> {
            val t = parse(name)
            if (t.base.isEmpty()) return emptyList()
            val epg = epgKey(epgId)
            val scored = ArrayList<Pair<Int, Int>>()
            for (i in pool.indices) {
                val s = score(t, epg, i)
                if (s >= MIN_SCORE) scored += i to s
            }
            return scored.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { parsed[it.first].quality }.thenBy { it.first })
                .take(limit).map { it.first }
        }

        /** A parecença do canal [i] com [t] (ver [ranked]); só contas inteiras, iguais nas duas plataformas. */
        fun score(t: Parsed, epg: String?, i: Int): Int {
            val c = parsed[i]
            if (c.base.isEmpty()) return 0
            val longest = maxOf(t.base.length, c.base.length)
            var s = 100 - 100 * levenshtein(t.base, c.base) / longest
            // Um contém o outro ("sporttv1" em "sporttv1extra"): mais parecido do que as letras dizem.
            if (s < 60 && (c.base.contains(t.base) || t.base.contains(c.base))) s = 60
            if (epg != null && epgKey(pool[i].epgId) == epg) s += 50
            if (c.shift != t.shift) s -= 30
            if (t.country != null && c.country != null) s += if (c.country == t.country) 5 else -20
            return s
        }

        /** Entre candidatos: o mesmo país, depois a melhor qualidade; países ou nomes diferentes que sobrem = dúvida. */
        private fun choose(t: Parsed, candidates: List<Int>): Result {
            if (candidates.isEmpty()) return Result.Missing
            var set = candidates
            if (t.country != null) set.filter { parsed[it].country == t.country }.takeIf { it.isNotEmpty() }?.let { set = it }
            if (set.mapNotNull { parsed[it].country }.distinct().size > 1) return Result.Unsure(set.size)
            if (set.map { parsed[it].base }.distinct().size > 1) return Result.Unsure(set.size)
            val best = set.maxOf { parsed[it].quality }
            return Result.Match(set.first { parsed[it].quality == best })
        }
    }

    const val MIN_SCORE = 40

    /** Quantas letras é preciso mudar para ir de [a] a [b]. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /** Quantos canais de uma lista têm o seu na reserva, quantos ficam em dúvida e quantos faltam (com os nomes). */
    data class Coverage(
        val total: Int,
        val matched: Int,
        val unsure: Int,
        val missing: Int,
        val unsureNames: List<String>,
        val missingNames: List<String>,
    )

    /** A cobertura da reserva [matcher] para os canais [main] (nome e id do guia); [overrides]: chave → escolha à mão. */
    fun coverage(main: List<Entry>, matcher: Matcher, overrides: Map<String, String> = emptyMap()): Coverage {
        var matched = 0
        val unsure = ArrayList<String>()
        val missing = ArrayList<String>()
        for (c in main) {
            when (matcher.find(c.name, c.epgId, overrides[c.key])) {
                is Result.Match -> matched++
                is Result.Unsure -> unsure += c.name
                Result.Missing -> missing += c.name
            }
        }
        return Coverage(main.size, matched, unsure.size, missing.size, unsure, missing)
    }

    fun entry(c: Channel) = Entry(c.key, c.name, c.epgId)
}
