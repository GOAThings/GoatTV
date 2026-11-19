package cc.slokker.itv.data

import cc.slokker.itv.data.ChannelMatch.Quality
import cc.slokker.itv.data.ProLineup.Input
import cc.slokker.itv.data.ProLineup.Ref
import cc.slokker.itv.data.ProLineup.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProLineupTest {
    private fun pt(vararg sources: Source) = ProLineup.build(ProLineup.lineup("pt"), "pt", sources.toList())

    private fun list(id: String, vararg channels: Pair<String, String?>) =
        Source(id, channels.mapIndexed { i, (name, group) -> Input("$id-$i", name, group) })

    @Test
    fun bestQualityFirstAcrossLists() {
        val r = pt(
            list("a", "PT| RTP 1 HD" to "PT | Generalistas", "PT| RTP 1 SD" to "PT | Generalistas", "PT| RTP 1 FHD" to "PT | Generalistas"),
            list("b", "RTP1 FHD" to "PORTUGAL", "RTP1 4K" to "PORTUGAL"),
        )
        val rtp1 = r.channels.single { it.number == 1 }
        assertEquals("RTP1", rtp1.name)
        assertEquals(
            listOf(Ref("b", "b-1", Quality.UHD), Ref("a", "a-2", Quality.FHD), Ref("b", "b-0", Quality.FHD), Ref("a", "a-0", Quality.HD), Ref("a", "a-1", Quality.SD)),
            rtp1.sources,
        )
    }

    @Test
    fun aliasesAndNumbers() {
        val r = pt(list("a", "PT: TVI24 HD" to null, "PT: Eleven Sports 1" to null, "SPORT.TV 1 FHD" to "PT Desporto"))
        assertEquals(listOf(7, 20, 135), r.channels.map { it.number })
        assertEquals(listOf("CNN Portugal", "Sport TV 1", "DAZN 1"), r.channels.map { it.name })
        assertEquals(listOf(ProLineups.NEWS, ProLineups.SPORTS), r.groups)
    }

    @Test
    fun otherCountriesAndTimeshiftStayOut() {
        val r = pt(
            list(
                "a",
                "BR: SIC" to "BR | Canais", "SIC" to "UK | Entertainment", "ES| Eurosport 1" to null, "SIC +1" to "PT | Generalistas",
                "SIC HD" to "Generalistas",
            ),
        )
        assertEquals(listOf(Ref("a", "a-4", Quality.HD)), r.channels.single { it.number == 3 }.sources)
        assertNull(r.channels.firstOrNull { it.number == 130 })
        // O SIC +1 é do país mas não é o SIC: vai para os "Mais canais".
        assertEquals(listOf("SIC +1"), r.channels.filter { it.group == ProLineup.MORE }.map { it.name })
    }

    @Test
    fun moreChannelsAreOnlyTheCountrysAndMerged() {
        val r = pt(
            list("a", "PT| Canal Teste HD" to "PT | Outros", "Random Movies" to "Filmes"),
            list("b", "PT| Canal Teste SD" to "PT | Outros"),
        )
        val more = r.channels.filter { it.group == ProLineup.MORE }
        assertEquals(listOf("Canal Teste"), more.map { it.name })
        // Depois de todos os da tabela (a de Portugal vai até aos 300 e tal): nenhum número repetido.
        assertEquals(400, more.single().number)
        assertTrue(ProLineups.PT.slots.all { it.number < more.single().number })
        assertEquals(listOf(Ref("a", "a-0", Quality.HD), Ref("b", "b-0", Quality.SD)), more.single().sources)
        assertEquals(listOf(ProLineup.MORE), r.groups)
    }

    @Test
    fun idsAreStable() {
        val r = pt(list("a", "PT| RTP 2" to null, "PT| Canal Q" to null))
        assertEquals(listOf("pro:pt:rtp2", "pro:pt:canalq"), r.channels.map { it.id })
    }

    @Test
    fun tableHasNoClashingNames() {
        val seen = HashMap<String, Int>()
        val pt = ProLineup.lineup("pt")!!
        assertEquals(pt.slots.size, pt.slots.map { it.number }.toSet().size, "números repetidos")
        for (slot in pt.slots) for (n in listOf(slot.name) + slot.aliases) {
            val base = ChannelMatch.parse(n).base
            val other = seen.put(base, slot.number)
            assertTrue(other == null || other == slot.number, "\"$n\" ($base) é de ${slot.number} e de $other")
        }
    }

    @Test
    fun noTableMeansOnlyMore() {
        val r = ProLineup.build(ProLineup.lineup("xx"), "es", listOf(list("a", "ES| La 1 HD" to null, "ES| La 1 FHD" to null)))
        assertEquals(listOf("La 1"), r.channels.map { it.name })
        assertEquals(2, r.channels.single().sources.size)
    }

    @Test
    fun cleanNames() {
        assertEquals("SIC Radical", ProLineup.cleanName("PT| SIC Radical FHD"))
        assertEquals("Canal Teste", ProLineup.cleanName("[PT] Canal Teste (HD)"))
        assertEquals("A Bola TV", ProLineup.cleanName("A Bola TV"))
    }

    @Test
    fun foreignChannelsTheOperatorCarriesCountFromAnyGroup() {
        val r = pt(list("a", "UK| Sky News HD" to "UK | News", "RTP3 HD" to "PT | Info", "US| ESPN" to "US | Sports"))
        assertEquals(listOf(6 to "RTP Notícias", 205 to "Sky News"), r.channels.map { it.number to it.name })
    }

    @Test
    fun numbersFollowTheNosThemes() {
        val r = pt(list("a", "PT| SIC Radical" to null, "PT| Sport TV 1" to null, "PT| Mezzo" to null, "PT| V+ TVI" to null))
        assertEquals(listOf("pro:pt:sicradical" to 9, "pro:pt:sporttv1" to 20, "pro:pt:vtvi" to 59, "pro:pt:mezzo" to 157), r.channels.map { it.id to it.number })
    }

    @Test
    fun theCountryCodeAtTheEndIsTheCountrysVersion() {
        // Como os fornecedores portugueses escrevem: "CNN PT" é a CNN Portugal, "SIC PT" é a SIC; "CNN" num grupo
        // português e "CNN International" são a internacional.
        val r = pt(
            list(
                "a",
                "CNN PT ᴴᴰ" to "PT | Informação", "SIC PT" to "PT | Generalistas", "Eurosport PT" to "PT | Desporto",
                "CNN International ᴴᴰ" to "PT | Informação", "SIC Notícias" to "PT | Informação",
            ),
        )
        assertEquals(
            listOf(3 to "SIC", 5 to "SIC Notícias", 7 to "CNN Portugal", 130 to "Eurosport 1", 204 to "CNN"),
            r.channels.map { it.number to it.name },
        )
        assertTrue(r.channels.none { it.group == ProLineup.MORE })
    }

    @Test
    fun nearMissesShowNamesTheTableLacks() {
        val r = pt(list("a", "PT| CNN Prime" to null, "PT| Canal Teste" to null, "PT| SIC" to null))
        assertEquals(listOf("CNN Prime ~ CNN"), ProLineup.nearMisses(ProLineup.lineup("pt"), r))
    }

    @Test
    fun foreignNewsIsInternationalNotNews() {
        val r = pt(list("a", "PT| CNN Portugal" to null, "CNN International" to "PT | Info", "BBC World News" to "UK | News"))
        assertEquals(
            listOf("CNN Portugal" to ProLineups.NEWS, "CNN" to ProLineups.INTERNATIONAL, "BBC World News" to ProLineups.INTERNATIONAL),
            r.channels.map { it.name to it.group },
        )
    }

    @Test
    fun everyTableFindsItsOwnChannels() {
        // Cada canal de cada tabela, como vem numa lista do país ("ES| La 1 HD" no grupo "ES | Generalistas"), vai
        // para o lugar dele.
        for (lineup in ProLineup.LINEUPS) {
            val tag = lineup.country.uppercase().let { if (it == "GB") "UK" else it }
            val named = lineup.slots.filter { ChannelMatch.parse(it.name).base.length >= 2 }
            val input = named.mapIndexed { i, slot -> ProLineup.Input("k$i", "$tag| ${slot.name} HD", "$tag | TV") }
            val r = ProLineup.build(lineup, lineup.country, listOf(ProLineup.Source("a", input)))
            val got = r.channels.filter { it.group != ProLineup.MORE }.map { it.number }.toSet()
            val lost = named.filter { it.number !in got }.map { it.name }
            assertTrue(lost.isEmpty(), "${lineup.id}: não encontrou $lost")
        }
    }

    @Test
    fun everyTableIsClean() {
        for (lineup in ProLineup.LINEUPS) {
            assertEquals(lineup.slots.size, lineup.slots.map { it.number }.toSet().size, "${lineup.id}: números repetidos")
            // Dois canais da tabela com o mesmo nome (já normalizado): o segundo nunca recebia fontes.
            val owner = HashMap<String, String>()
            for (slot in lineup.slots) {
                // Os nomes de uma letra ("E!") não contam (ver ProLineup.build): tem de haver outro.
                val names = (listOf(slot.name) + slot.aliases).filter { ChannelMatch.parse(it).base.length >= 2 }
                assertTrue(names.isNotEmpty(), "${lineup.id}: \"${slot.name}\" fica sem nome")
                for (n in names) {
                    val other = owner.putIfAbsent(ChannelMatch.parse(n).base, slot.name)
                    assertTrue(other == null || other == slot.name, "${lineup.id}: \"$n\" é de \"$other\" e de \"${slot.name}\"")
                }
            }
        }
    }
}
