package cc.slokker.itv.data

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupCountriesTest {
    private fun of(name: String) = GroupCountries.of(name)

    @Test
    fun prefixes() {
        assertEquals("pt", of("PT | Desporto"))
        assertEquals("pt", of("PT| SPORT TV"))
        assertEquals("gb", of("|UK| Movies"))
        assertEquals("fr", of("[FR] Cinéma"))
        assertEquals("de", of("DE: Sport"))
        assertEquals("es", of("ES - Deportes"))
        assertEquals("pt", of("POR | Notícias"))
        assertEquals("de", of("GER ▎ Bundesliga"))
        assertEquals("us", of("USA | NBA"))
        assertEquals("br", of("BR • Globo"))
    }

    @Test
    fun bareCodes() {
        assertEquals("pt", of("PT SPORTS"))
        assertEquals("gb", of("UK ENTERTAINMENT"))
        assertEquals(null, of("IT Crowd"))
        assertEquals(null, of("NF Originals"))
        assertEquals(null, of("BT Sport"))
        assertEquals(null, of("Pt sports"))
    }

    @Test
    fun namesAndFlags() {
        assertEquals("pt", of("PORTUGAL"))
        assertEquals("pt", of("Portugal Desporto"))
        assertEquals("de", of("Deutschland HD"))
        assertEquals("es", of("Canales de España"))
        assertEquals("gb", of("United Kingdom Sports"))
        assertEquals("sa", of("Saudi Arabia"))
        assertEquals("za", of("South Africa"))
        assertEquals("pt", of("🇵🇹 Desporto"))
        assertEquals("gb", of("🇬🇧 News"))
        assertEquals("pt", of("Sport (PT)"))
        assertEquals("ru", of("Россия"))
        // O nome ganha ao código do fornecedor.
        assertEquals("rs", of("SR| SERBIA ᴴᴰ"))
        assertEquals("tw", of("TN| TAIWAN HD/4K"))
        assertEquals("ma", of("AR| MOROCCO ▶ المغرب"))
        assertEquals("ba", of("BH| BOSNIA"))
        assertEquals("pk", of("ASIA| PAKISTAN"))
    }

    @Test
    fun regions() {
        assertEquals(GroupCountries.ARAB, of("AR | MBC"))
        assertEquals(GroupCountries.ARAB, of("ARABIC NEWS"))
        assertEquals(GroupCountries.LATAM, of("LATINO | Deportes"))
        assertEquals(GroupCountries.EXYU, of("EX-YU | Sport"))
        assertEquals(GroupCountries.AFRICA, of("AFRICA"))
        assertEquals(GroupCountries.AFRICA, of("AFR| DSTV VIP"))
        assertEquals(GroupCountries.CARIBBEAN, of("CARIBBEAN"))
        assertEquals("ar", of("ARG | Futbol"))
        assertEquals("pt", of("PORTUGUESE MOVIES"))
    }

    @Test
    fun noCountry() {
        assertEquals(null, of("Sports"))
        assertEquals(null, of("4K UHD"))
        assertEquals(null, of("VOD | Movies"))
        assertEquals(null, of("Kids"))
        assertEquals(null, of("NEWS"))
        assertEquals(null, of("HD | Sport"))
    }

    @Test
    fun hiddenAndCount() {
        val groups = listOf(Category("1", "PT | Sport"), Category("2", "UK | News"), Category("3", "Kids"), Category("4", "PT | News"))
        assertEquals(setOf("2", "3"), GroupCountries.hidden(groups, listOf("pt")))
        assertEquals(setOf("2"), GroupCountries.hidden(groups, listOf("pt", GroupCountries.NONE)))
        assertEquals(emptySet(), GroupCountries.hidden(groups, emptyList()))
        assertEquals(listOf("pt" to 2, "gb" to 1, GroupCountries.NONE to 1), GroupCountries.count(groups))
    }
}
