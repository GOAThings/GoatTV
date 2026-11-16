package cc.slokker.itv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsHubTest {
    private fun teams(title: String) = SportsHub.parse(title)?.let { it.home to it.away }

    @Test
    fun titles() {
        assertEquals("Benfica" to "Porto", teams("Benfica - Porto"))
        assertEquals("Brest" to "Lyon", teams("Nogomet – Francuska liga: Brest – Lyon"))
        assertEquals("Senegal" to "Sudan", teams("Senegal vs Sudan - Africa Cup of Nations ( AFCON ) Qualification 2026/2027- MD3"))
        assertEquals("Florida Crocs" to "Portland Paddlers", teams("MLTT - Week 5: Florida Crocs vs. Portland Paddlers"))
        assertEquals("FC Barcelona" to "Racing Santander", teams("Piłka nożna: Liga hiszpańska - mecz: FC Barcelona - Racing Santander"))
        assertEquals("Portugal" to "Dinamarca", teams("UEFA Nations League - Portugal x Dinamarca"))
        assertEquals("Romania" to "Poland", teams("LIVE: Nogomet: UEFA Liga nacija (M): Romania - Poland"))
        assertEquals("Brighton" to "Brentford", teams("Nogomet (M): Brighton - Brentford, Premier liga 2026/2027"))
        assertEquals("Shakhtar" to "Sporting", teams("SHAKHTAR - SPORTING"))
        assertEquals("Celtics" to "Lakers", teams("Lakers @ Celtics"))
        assertEquals("Енисей" to "Уралмаш", teams("Баскетбол. Единая лига ВТБ. Енисей (Красноярск) - Уралмаш (Екатеринбург)"))
    }

    @Test
    fun notGames() {
        assertNull(teams("Jujutsu Kaisen S1 - E10"))
        assertNull(teams("LaLiga HL 26/27: Barcelona v R Madrid"))
        assertNull(teams("Golf - pasja i sport"))
        assertNull(teams("Últimas Notícias - Últimas Notícias - Direto"))
        assertNull(teams("Premier League 360 - Big Sam's Bolton"))
        assertNull(teams("Copa del mundo de esquí alpino F - Levi"))
        assertNull(teams("Ridebanespringning: World Cup - Verona"))
        assertNull(teams("BSL (26-27) 07.Hafta Karşıyaka - Beşiktaş / Bant"))
        assertNull(teams("Nogomet: Liga narodov: Studio po tekmi Slovenija - Švica"))
        assertNull(teams("ATP World Tour 250 - Estocolmo 2026 - Meia-Final"))
        assertNull(SportsHub.parse("On Board F1"))
    }

    @Test
    fun sports() {
        assertEquals(SportsHub.BASKETBALL, SportsHub.parse("Košarka - Euroleague: Panathinaikos - Barcelona")?.sport)
        assertEquals(SportsHub.HOCKEY, SportsHub.parse("NHL: Columbus Blue Jackets - Philadelphia Flyers")?.sport)
        assertEquals(SportsHub.FIGHT, SportsHub.parse("UFC 334: Gane vs. Hokit")?.sport)
        assertEquals(SportsHub.MOTOR, SportsHub.parse("Mundial F1 - GP de Japón")?.sport)
        assertEquals(SportsHub.FOOTBALL, SportsHub.parse("Netherlands vs Greece - UEFA Nations League 2026/2027 - MD5")?.sport)
    }

    private val now = 1_794_000_000_000L
    private fun e(title: String, startMin: Int, lenMin: Int = 110) = EpgEntry(title, now + startMin * 60_000L, now + (startMin + lenMin) * 60_000L)

    @Test
    fun groupsAndRanks() {
        val sources = listOf(
            SportsSource("a", "UK: Sky Sports 1 HD", "UK | SPORTS", listOf(e("Benfica v Porto - Liga Portugal", -30))),
            SportsSource("b", "PT: Sport TV 1 FHD", "PT | DESPORTO", listOf(e("Liga Portugal: SL Benfica - FC Porto", -29), e("Benfica - Porto", 300))),
            SportsSource("c", "ES: DAZN 1", "ES | DEPORTES", listOf(e("Valladolid - Castellón", 60))),
        )
        val r = SportsHub.build(sources, now, "pt", "pt", SportsMemory())
        assertEquals(2, r.games.size)
        val live = r.games[0]
        assertTrue(live.isLive(now))
        assertEquals(listOf("b", "a"), live.channels.map { it.key })
        assertEquals(0, live.channels[0].rank)
        // A de Espanha joga mais tarde; a repetição das 5 h do Benfica – Porto não conta.
        assertEquals("Valladolid – Castellón", r.games[1].title)
        assertEquals(listOf(SportsHub.FOOTBALL), r.sports)
        assertEquals("BEN", SportsHub.initials("SL Benfica"))
        assertEquals("RM", SportsHub.initials("Real Madrid"))
    }
}
