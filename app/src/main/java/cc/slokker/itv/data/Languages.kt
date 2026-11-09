package cc.slokker.itv.data

import java.text.Normalizer
import java.util.Locale
import java.util.MissingResourceException

/**
 * As línguas das faixas de áudio e de legendas. Os canais marcam-nas de muitas maneiras: ISO 639-1 ("pt"), 639-2 nas
 * duas formas ("por", "ger"/"deu"), com região ("pt-BR", "pt_PT"), em maiúsculas, pelo nome em inglês ou na própria
 * língua ("Portuguese", "Português") ou dentro de uma etiqueta ("Faixa 1 - [Portuguese]", "POR AC3"). [code] reduz
 * tudo ao código ISO 639-1 (o das Definições); os códigos que não dizem a língua ("und", "mul", "qaa"…) dão null.
 */
object Languages {

    /** As línguas a escolher nas Definições (ISO 639-1); o ecrã ordena-as pelo nome na língua da app. */
    val CHOICES = listOf(
        "ar", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hr", "hu",
        "hy", "id", "it", "ja", "ka", "kk", "ko", "ku", "lt", "lv", "mk", "ml", "ms", "nl", "no", "pa", "pl", "pt", "ro",
        "ru", "sk", "sl", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh",
    )

    /** O nome de [code] em [locale], com maiúscula (como nas faixas do player). */
    fun displayName(code: String, locale: Locale): String =
        Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }

    /**
     * O código ISO 639-1 da língua de [raw] (um código, um nome ou uma etiqueta com um deles), ou null se não disser
     * a língua. Os códigos antigos do Java ("iw", "in", "ji") e as variantes do norueguês dão o mesmo que os atuais.
     */
    fun code(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        tag(text)?.let { return it.takeIf { c -> c != UNKNOWN } }
        names[fold(text)]?.let { return it }
        // Uma etiqueta: primeiro o que está entre parênteses retos ou curvos ("Faixa 1 - [Portuguese]"), depois as palavras.
        for (m in BRACKETS.findAll(text)) code(m.groupValues[1])?.let { return it }
        val words = WORD.findAll(text).map { it.value }.toList()
        for ((i, w) in words.withIndex()) {
            if (w.length >= 4) names[fold(w)]?.let { return it }
            // Nomes de duas palavras ("Scottish Gaelic", "Norwegian Bokmål").
            if (i + 1 < words.size) names[fold(w + " " + words[i + 1])]?.let { return it }
            // Os códigos de três letras numa etiqueta ("POR 5.1"), menos os que também são palavras comuns.
            if (w.length == 3 && fold(w) !in WORDS) iso3[fold(w)]?.let { return it }
        }
        return null
    }

    /** [raw] diz a língua [wanted] (um código ISO 639-1)? */
    fun matches(raw: String?, wanted: String): Boolean = code(raw) == wanted

    /** Um código ("pt", "por", "pt-BR"): o ISO 639-1, [UNKNOWN] se for um que não diz a língua, null se não for um código. */
    private fun tag(text: String): String? {
        if (!TAG.matches(text)) return null
        val primary = text.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        if (primary in UNDETERMINED || (primary.length == 3 && primary in "qaa".."qtz")) return UNKNOWN
        return when (primary.length) {
            2 -> canonical(primary).takeIf { it in iso2 }
            else -> iso3[primary]
        }
    }

    private const val UNKNOWN = ""

    /** Sem língua: indeterminada, várias, sem fala, outras; "qaa"–"qtz" são de uso local (o DVB põe "qaa" na versão original). */
    private val UNDETERMINED = setOf("und", "mul", "zxx", "mis", "unk", "nar", "xx")

    /** Códigos de três letras que numa etiqueta costumam ser palavras, não línguas. */
    private val WORDS = setOf("may", "sun", "her", "mon", "son", "new", "tel", "nor", "art", "aac", "pcm", "dts", "mp2", "mp3")

    private val TAG = Regex("""[A-Za-z]{2,3}([-_][A-Za-z0-9]{1,8})*""")
    private val BRACKETS = Regex("""[\[(]([^\])]+)[\])]""")
    private val WORD = Regex("""\p{L}+""")

    private fun canonical(code: String): String = when (code) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        "nb", "nn" -> "no"
        else -> code
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "").trim()

    private val MARKS = Regex("""\p{Mn}+""")

    private val iso2: Set<String> by lazy { Locale.getISOLanguages().map(::canonical).toSet() + setOf("he", "id", "yi") }

    /** ISO 639-2: a forma "T" (do Java), a "B" (bibliográfica, que o DVB e muitas listas usam) e outras que se veem nos canais. */
    private val iso3: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (code in Locale.getISOLanguages()) {
            val three = try {
                Locale(code).isO3Language
            } catch (e: MissingResourceException) {
                continue
            }
            if (three.isNotEmpty()) map[three] = canonical(code)
        }
        map += mapOf(
            "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl",
            "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
            "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
            "scc" to "sr", "scr" to "hr", "nob" to "no", "nno" to "no", "cmn" to "zh", "esp" to "es", "jap" to "ja",
            "pob" to "pt", "ptb" to "pt", "chs" to "zh", "cht" to "zh",
        )
        map
    }

    /** Os nomes das línguas em inglês, na própria língua e nas línguas da app (sem acentos nem maiúsculas). */
    private val names: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        val shown = listOf("en", "es", "pt", "fr", "de", "it", "ru", "tr", "id", "hi", "ar", "zh", "ja", "ko").map(Locale::forLanguageTag)
        for (code in Locale.getISOLanguages()) {
            val language = Locale(code)
            val c = canonical(code)
            for (where in listOf(Locale.ENGLISH, language) + shown) {
                val name = fold(language.getDisplayLanguage(where))
                // Sem nome conhecido o Java devolve o próprio código: esses já os trata o [tag].
                if (name.length >= 3 && name != code) map.putIfAbsent(name, c)
            }
        }
        // Nomes que se veem nas listas e não são os do Java.
        map += mapOf("portugues" to "pt", "castellano" to "es", "farsi" to "fa", "mandarin" to "zh", "cantonese" to "zh", "brazilian" to "pt")
        map
    }
}
