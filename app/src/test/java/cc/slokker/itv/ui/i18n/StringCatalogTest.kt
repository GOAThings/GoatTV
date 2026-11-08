package cc.slokker.itv.ui.i18n

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Os textos das apps, que nada verifica ao compilar: as pastas `res/values*` do Android, a tabela que as apps da
 * Apple leem (`tvos/GoatTV/Resources/android_strings.json`, gerada por `tvos/scripts/import-android-strings.py`) e
 * as chaves que o Swift pede pelo nome (`r("…")`, `plural("…")`). Uma chave em falta aparece em inglês no Android
 * e com o próprio nome ("cancel") no iPhone e na Apple TV.
 */
class StringCatalogTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "tvos").isDirectory && File(it, "app/src/main/res").isDirectory }
        ?: error("Pasta do projeto não encontrada a partir de ${System.getProperty("user.dir")}")
    private val res = File(root, "app/src/main/res")
    private val base = catalog("values")

    /** Pastas `values-*` com strings_ui.xml (as 15 línguas e o `values-pt`, que é o mesmo pt-PT). */
    private val localeFolders = res.listFiles()!!.filter { it.name.startsWith("values-") && File(it, FILE).exists() }.map { it.name }.sorted()

    @Test
    fun `every locale has every key and no other`() {
        val problems = localeFolders.mapNotNull { folder ->
            val c = catalog(folder)
            val known = KNOWN_MISSING[folder].orEmpty()
            val missing = base.keys - c.keys - known
            val extra = c.keys - base.keys
            val fixed = known - (base.keys - c.keys)
            if (missing.isEmpty() && extra.isEmpty() && fixed.isEmpty()) null
            else "$folder: em falta ${missing.sorted()}" + (if (extra.isNotEmpty()) ", a mais ${extra.sorted()}" else "") +
                (if (fixed.isNotEmpty()) ", já traduzidas (tirar de KNOWN_MISSING) ${fixed.sorted()}" else "")
        }
        if (problems.isNotEmpty()) fail("Textos por traduzir:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `placeholders match the english text`() {
        val problems = mutableListOf<String>()
        for (folder in localeFolders) {
            val c = catalog(folder)
            for ((key, text) in c.strings) {
                val expected = base.strings[key] ?: continue
                if (placeholders(text) != placeholders(expected)) {
                    problems += "$folder/$key: ${placeholders(text)} em vez de ${placeholders(expected)}"
                }
            }
            // Plurais: cada forma da língua tem os valores do "other" inglês.
            for ((key, items) in c.plurals) {
                val expected = base.plurals[key]?.get("other")?.let(::placeholders) ?: continue
                items.forEach { (quantity, text) ->
                    if (placeholders(text) != expected) problems += "$folder/$key[$quantity]: ${placeholders(text)} em vez de $expected"
                }
            }
        }
        if (problems.isNotEmpty()) fail("Valores trocados:\n" + problems.joinToString("\n"))
    }

    /** O pt-PT da Apple vem de values-pt-rPT; o Android também usa values-pt. Têm de ser iguais. */
    @Test
    fun `both european portuguese folders are the same`() {
        val pt = catalog("values-pt")
        val ptPT = catalog("values-pt-rPT")
        val differ = (pt.strings.keys + ptPT.strings.keys - KNOWN_MISSING["values-pt-rPT"].orEmpty()).filter { pt.strings[it] != ptPT.strings[it] }.sorted()
        assertEquals("values-pt e values-pt-rPT diferem em", emptyList<String>(), differ)
        assertEquals(pt.plurals, ptPT.plurals)
    }

    @Test
    fun `every key the apple apps ask for exists`() {
        val swift = File(root, "tvos").walkTopDown().filter { it.isFile && it.extension == "swift" }.toList()
        assertTrue("Sem ficheiros Swift em tvos/", swift.isNotEmpty())
        val asked = mutableSetOf<String>()
        val askedPlurals = mutableSetOf<String>()
        for (file in swift) {
            val text = file.readText()
            SWIFT_R.findAll(text).forEach { asked += it.groupValues[1] }
            SWIFT_PLURAL.findAll(text).forEach { askedPlurals += it.groupValues[1] }
            // Chaves num `var key: String { switch self { case .x: "chave" … } }`, pedidas com r(option.key).
            SWIFT_KEY_SWITCH.findAll(text).forEach { block -> SWIFT_CASE_KEY.findAll(block.value).forEach { asked += it.groupValues[1] } }
        }
        assertTrue("Nenhum r(\"…\") encontrado no Swift: a expressão deixou de os apanhar?", asked.size > 50)
        val missing = (asked - base.strings.keys).sorted() + (askedPlurals - base.plurals.keys).sorted().map { "plural $it" }
        assertEquals("Chaves pedidas pelo Swift que não existem em values/strings_ui.xml", emptyList<String>(), missing)
    }

    /** A tabela da Apple é a que o script gera: sem isto, um texto novo ou corrigido no Android não chega lá. */
    @Test
    fun `apple string table matches what the import script produces`() {
        val expected = appleTable()
        val json = JsonParser.parseString(File(root, "tvos/GoatTV/Resources/android_strings.json").readText()).asJsonObject
        val actual = json.entrySet().associate { (lang, table) -> lang to table.asJsonObject.entrySet().associate { (k, v) -> k to v.asString } }
        assertEquals("Línguas", expected.keys.sorted(), actual.keys.sorted())
        val differ = expected.flatMap { (lang, table) ->
            val other = actual[lang].orEmpty()
            (table.keys + other.keys).filter { table[it] != other[it] }.sorted().map { "$lang/$it" }
        }
        assertEquals(
            "android_strings.json desatualizado; correr tvos/scripts/import-android-strings.py app/src/main/res",
            emptyList<String>(),
            differ.take(20),
        )
    }

    @Test
    fun `the language lists agree`() {
        val app = Strings.LANGUAGES.map { it.first }
        val localesConfig = LOCALE.findAll(File(res, "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals("locales_config.xml", app, localesConfig)
        // As pastas: values é o inglês; values-pt é o mesmo pt-PT (ver acima).
        val folders = (listOf("values") + localeFolders - "values-pt").map(::folderLanguage).sorted()
        assertEquals("pastas values-*", app.sorted(), folders)
        val script = File(root, "tvos/scripts/import-android-strings.py").readText()
        val scriptLangs = SCRIPT_LANG.findAll(script).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("LANGS do script", app.sorted(), scriptLangs.values.sorted())
        scriptLangs.forEach { (folder, lang) -> assertEquals("LANGS do script: $folder", folderLanguage(folder), lang) }
        val swift = File(root, "tvos/GoatTV/UI/Strings.swift").readText()
        val block = swift.substringAfter("static let languages").substringBefore("\n    ]")
        assertEquals("Strings.languages da Apple", app, SWIFT_LANGUAGE.findAll(block).map { it.groupValues[1] }.toList())
    }

    // ---- Leitura dos ficheiros (as mesmas expressões do import-android-strings.py) ----

    private class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>) {
        val keys: Set<String> get() = strings.keys + plurals.keys
    }

    private fun catalog(folder: String): Catalog {
        val xml = File(res, "$folder/$FILE").readText()
        val strings = STRING.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
        val plurals = PLURALS.findAll(xml).associate { m ->
            m.groupValues[1] to ITEM.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        }
        return Catalog(strings, plurals)
    }

    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    private fun folderLanguage(folder: String): String = when (folder) {
        "values" -> "en"
        "values-in" -> "id" // o Android ainda usa o código antigo do indonésio
        else -> folder.removePrefix("values-").replace("-r", "-")
    }

    /** O que o import-android-strings.py escreve: língua → chave → texto (plurais como `chave_quantidade`). */
    private fun appleTable(): Map<String, Map<String, String>> {
        val script = File(root, "tvos/scripts/import-android-strings.py").readText()
        return SCRIPT_LANG.findAll(script).associate { m ->
            val c = catalog(m.groupValues[1])
            val table = c.strings.mapValues { clean(it.value) }.toMutableMap()
            c.plurals.forEach { (key, items) -> items.forEach { (q, text) -> table[key + "_" + q] = clean(text) } }
            m.groupValues[2] to table
        }
    }

    /** O `clean` do script: tira as aspas e os escapes do Android e passa %1$s / %d a %1$@ / %@. */
    private fun clean(raw: String): String {
        var t = raw.trim()
        if (t.startsWith('"') && t.endsWith('"')) t = t.substring(1, (t.length - 1).coerceAtLeast(1))
        t = t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
        t = Regex("%(\\d+)\\$[sd]").replace(t) { "%${it.groupValues[1]}\$@" }
        return Regex("%[sd]").replace(t, "%@")
    }

    private companion object {
        /** O que já faltava quando este teste entrou (traduz-se a seguir). Esta lista só pode encolher. */
        val UNTRANSLATED = setOf(
            "connect_account_body", "connect_account_title", "copied", "copy_code", "invalid_mac", "mac_address",
            "missing_portal", "portal_url", "sign_in_to_approve", "stalker_bad_login", "stalker_mac_hint", "stalker_sub",
            "welcome_local_phone", "welcome_local_phone_sub",
        )
        val KNOWN_MISSING: Map<String, Set<String>> =
            listOf("ar", "de", "es", "fr", "hi", "in", "it", "ja", "ko", "ru", "tr", "zh-rCN").associate { "values-$it" to UNTRANSLATED } +
                mapOf(
                    "values-pt-rBR" to setOf(
                        "invalid_mac", "mac_address", "missing_portal", "portal_url", "stalker_bad_login", "stalker_mac_hint",
                        "stalker_sub", "welcome_local_phone", "welcome_local_phone_sub",
                    ),
                    "values-pt-rPT" to setOf("welcome_local_phone", "welcome_local_phone_sub"),
                )

        const val FILE = "strings_ui.xml"
        val STRING = Regex("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val PLURALS = Regex("<plurals name=\"([a-z0-9_]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("<item quantity=\"([a-z]+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")
        val LOCALE = Regex("<locale android:name=\"([^\"]+)\"")
        val SCRIPT_LANG = Regex("\"(values[a-zA-Z-]*)\": \"([^\"]+)\"")
        val SWIFT_R = Regex("\\br\\(\"([a-z0-9_]+)\"")
        val SWIFT_PLURAL = Regex("\\bplural\\(\"([a-z0-9_]+)\"")
        val SWIFT_KEY_SWITCH = Regex("var key: String \\{.*?\\n    \\}", RegexOption.DOT_MATCHES_ALL)
        val SWIFT_CASE_KEY = Regex("case \\.[A-Za-z]+: \"([a-z0-9_]+)\"")
        val SWIFT_LANGUAGE = Regex("\\(\"([A-Za-z-]+)\", \"")
    }
}
