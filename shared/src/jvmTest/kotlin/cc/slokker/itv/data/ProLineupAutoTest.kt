package cc.slokker.itv.data

import cc.slokker.itv.data.ChannelMatch.Quality
import cc.slokker.itv.data.ProLineup.Input
import cc.slokker.itv.data.ProLineup.Ref
import cc.slokker.itv.data.ProLineup.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Os países sem tabela: a ordem da primeira lista, os temas pelos grupos. */
class ProLineupAutoTest {
    private fun pl(vararg sources: Source) = ProLineup.build(ProLineup.lineup("pl"), "pl", sources.toList())

    private fun list(id: String, vararg channels: Pair<String, String?>) =
        Source(id, channels.mapIndexed { i, (name, group) -> Input("$id-$i", name, group) })

    @Test
    fun followsTheFirstListsOrder() {
        val r = pl(
            list(
                "a",
                "PL| TVP 1 HD" to "PL | Ogólne", "PL| TVP 2 HD" to "PL | Ogólne", "PL| Polsat HD" to "PL | Ogólne",
                "PL| TVN 24" to "PL | Informacje", "PL| Polsat Sport 1" to "PL | Sport", "UK| BBC One" to "UK | General",
            ),
            list("b", "PL| Polsat FHD" to "POLSKA", "PL| Canal+ Film" to "POLSKA", "PL| TVP 1 SD" to "POLSKA"),
        )
        assertEquals(listOf("TVP 1", "TVP 2", "Polsat", "TVN 24", "Polsat Sport 1", "Canal+ Film"), r.channels.map { it.name })
        assertEquals((1..6).toList(), r.channels.map { it.number })
        val polsat = r.channels.single { it.name == "Polsat" }
        assertEquals(listOf(Ref("b", "b-0", Quality.FHD), Ref("a", "a-2", Quality.HD)), polsat.sources)
        assertEquals("pro:pl:polsat", polsat.id)
    }

    @Test
    fun themesFromGroupsThenNames() {
        val r = pl(
            list(
                "a",
                "PL| TVP 1" to "PL | Ogólne", "PL| TVN 24" to "PL | Informacje", "PL| Eleven Sports 1" to "PL | Sport",
                "PL| Canal+ Film" to "POLSKA", "PL| MiniMini+" to "PL | Dzieci", "PL| Discovery" to "POLSKA", "PL| Kanał X" to "POLSKA",
            ),
        )
        assertEquals(
            listOf(ProLineups.GENERAL, ProLineups.NEWS, ProLineups.SPORTS, ProLineups.MOVIES, ProLineups.KIDS, ProLineups.DOCS, ProLineup.MORE),
            r.channels.map { it.group },
        )
        assertEquals(listOf(ProLineups.GENERAL, ProLineups.NEWS, ProLineups.MOVIES, ProLineups.SPORTS, ProLineups.KIDS, ProLineups.DOCS, ProLineup.MORE), r.groups)
    }

    @Test
    fun onlyTheCountrysChannels() {
        val r = pl(list("a", "Sport 1" to "Sports", "DE| Sport1" to "PL | Sport", "PL| TVP Sport" to null))
        assertEquals(listOf("TVP Sport"), r.channels.map { it.name })
    }

    @Test
    fun countriesWithoutTableFromGroups() {
        val groups = listOf("PL | Sport", "PL | Filmy", "RO| Sport", "PT | Desporto", "LATINO", "VOD", "PL | Dzieci")
        assertEquals(listOf("pl", "ro"), ProLineup.countriesIn(groups))
        assertTrue(ProLineup.isLineupId("pl"))
        assertTrue(ProLineup.isLineupId("pt"))
        assertFalse(ProLineup.isLineupId("419"))
        assertNull(ProLineup.lineup("pl"))
    }
}
