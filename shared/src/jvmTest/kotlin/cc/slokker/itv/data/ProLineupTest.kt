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
        assertEquals(ProLineup.MORE_FROM, more.single().number)
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
    fun everyTableIsClean() {
        for (lineup in ProLineup.LINEUPS) {
            assertEquals(lineup.slots.size, lineup.slots.map { it.number }.toSet().size, "${lineup.id}: números repetidos")
        }
    }
}
