package cc.slokker.itv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** As línguas como os canais as marcam: códigos, nomes e etiquetas. */
class LanguagesTest {

    private fun assertCode(expected: String?, vararg raw: String?) = raw.forEach { assertEquals("«$it»", expected, Languages.code(it)) }

    @Test
    fun `iso 639-1 and both 639-2 forms give the same code`() {
        assertCode("pt", "pt", "por", "POR", "Por", "pt-PT", "pt_BR", "pt-br")
        assertCode("de", "de", "deu", "ger", "GER")
        assertCode("fr", "fr", "fra", "fre")
        assertCode("zh", "zh", "zho", "chi", "zh-Hans", "cmn")
        assertCode("nl", "nl", "nld", "dut")
        assertCode("el", "el", "ell", "gre")
        assertCode("cs", "ces", "cze")
        assertCode("ro", "ron", "rum")
    }

    @Test
    fun `old java codes and norwegian variants are folded`() {
        assertCode("he", "he", "iw", "heb")
        assertCode("id", "id", "in", "ind")
        assertCode("no", "no", "nb", "nn", "nor", "nob", "nno")
    }

    @Test
    fun `codes that say no language give null`() {
        assertCode(null, null, "", "  ", "und", "UND", "mul", "zxx", "mis", "qaa", "qad", "qtz", "xx")
    }

    @Test
    fun `names in english, in the language itself and in the app languages`() {
        assertCode("pt", "Portuguese", "Português", "portugues", "PORTUGUÊS", "Portugués", "Portugais", "Portugiesisch")
        assertCode("en", "English", "Inglês", "Anglais", "Englisch")
        assertCode("es", "Spanish", "Español", "Castellano")
        assertCode("ru", "Russian", "Русский")
        assertCode("ar", "Arabic", "العربية")
        assertCode("ja", "Japanese", "日本語")
    }

    @Test
    fun `labels with a language inside`() {
        assertCode("pt", "Faixa 1 - [Portuguese]", "Track 2 [por]", "POR AC3", "Audio (Português)", "por 5.1")
        assertCode("en", "Track 1 - [English]", "ENG Dolby Digital", "English (Original)")
        assertCode("de", "Deutsch Stereo")
        assertCode("pt", "Brazilian Portuguese")
    }

    @Test
    fun `labels without a language give null`() {
        assertCode(null, "Audio 1", "Track 2", "Stereo", "Dolby Digital 5.1", "Original", "AAC", "Main", "Commentary", "HD")
    }

    @Test
    fun `every choice has a name in every app language`() {
        assertEquals(Languages.CHOICES.distinct(), Languages.CHOICES)
        for (code in Languages.CHOICES) {
            // O código das Definições lê-se de volta como ele próprio (o que se grava é o que se compara).
            assertEquals(code, Languages.code(code))
            for (app in listOf("en", "pt", "ja", "ar")) {
                val name = Languages.displayName(code, Locale.forLanguageTag(app))
                assertTrue("$code em $app: $name", name.isNotBlank() && name != code)
            }
        }
        assertEquals("Português", Languages.displayName("pt", Locale.forLanguageTag("pt-PT")))
        assertEquals("Inglês", Languages.displayName("en", Locale.forLanguageTag("pt-PT")))
    }
}
