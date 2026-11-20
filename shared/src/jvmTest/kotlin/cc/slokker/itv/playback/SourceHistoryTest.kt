package cc.slokker.itv.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceHistoryTest {
    private val day = 86_400_000L
    private val t0 = 100 * day + 3_600_000L
    private val a = SourceHistory.key("p1", "100")
    private val b = SourceHistory.key("p2", "200")

    @Test
    fun noHistoryIsNeutralAndKeepsTheOrder() {
        val h = SourceHistory()
        assertEquals(SourceReliability.NEUTRAL, h.score(a, t0))
        assertFalse(h.isBad(a, t0))
        assertNull(h.stats(a, t0))
        val items = listOf("x", "y", "z")
        assertEquals(items, SourceReliability.order(items, h, t0, { 1 }, { null }))
        assertEquals(items, SourceReliability.order(items, null, t0, { 1 }, { it }))
        assertEquals(items, SourceReliability.order(items, h, t0, { 1 }, { SourceHistory.key("p", it) }))
    }

    @Test
    fun countsAndSurvivesSaving() {
        val h = SourceHistory()
        assertTrue(h.recordOpen(a, true, t0))
        assertTrue(h.recordOpen(a, false, t0 + 10))
        assertTrue(h.recordDrop(a, t0 + 20))
        assertTrue(h.recordDrop(a, t0 + 30))
        assertTrue(h.recordWatched(a, 7_200, t0 + 40))
        val s = h.stats(a, t0 + 50)!!
        assertEquals(1, s.opens)
        assertEquals(1, s.failedOpens)
        assertEquals(2, s.drops)
        assertEquals(7_200L, s.watchedSec)
        assertEquals(t0 + 10, s.lastFailMs)
        val back = SourceHistory.decode(h.encode(t0 + 50), t0 + 50)
        val r = back.stats(a, t0 + 50)!!
        assertEquals(2, r.drops)
        assertEquals(7_200L, r.watchedSec)
        assertEquals(h.score(a, t0 + 50), back.score(a, t0 + 50))
    }

    @Test
    fun oldDaysLeaveTheWindow() {
        val h = SourceHistory()
        h.recordDrop(a, t0)
        h.recordDrop(a, t0 + 6 * day)
        assertEquals(2, h.stats(a, t0 + 6 * day)!!.drops)
        // No dia 7 o primeiro já saiu.
        assertEquals(1, h.stats(a, t0 + 7 * day)!!.drops)
        assertNull(h.stats(a, t0 + 20 * day))
        assertEquals("", SourceHistory.decode(h.encode(t0 + 6 * day), t0 + 20 * day).encode(t0 + 20 * day))
        // Ao gravar, o dia velho não vai.
        assertEquals(1, h.encode(t0 + 7 * day).substringAfter('\t').substringAfter('\t').substringBefore('\t').split(',').size)
    }

    @Test
    fun manyDropsPerHourIsBad() {
        val h = SourceHistory()
        h.recordWatched(a, 3_600, t0)
        h.recordDrop(a, t0)
        assertTrue(h.score(a, t0) > 85)
        repeat(7) { h.recordDrop(a, t0) }
        assertTrue(h.isBad(a, t0), "8 drops in 1 h: ${h.score(a, t0)}")
        // Muito visto, as mesmas quedas já não pesam tanto.
        h.recordWatched(b, 40 * 3_600, t0)
        repeat(8) { h.recordDrop(b, t0) }
        assertFalse(h.isBad(b, t0))
    }

    @Test
    fun failedOpensCount() {
        val h = SourceHistory()
        h.recordOpen(a, false, t0)
        assertFalse(h.isBad(a, t0), "one failure alone")
        h.recordOpen(a, false, t0)
        assertTrue(h.isBad(a, t0))
        h.recordOpen(b, true, t0)
        h.recordOpen(b, true, t0)
        h.recordOpen(b, false, t0)
        assertFalse(h.isBad(b, t0))
        assertTrue(h.score(b, t0) < SourceReliability.NEUTRAL)
    }

    @Test
    fun recentDaysWeighMore() {
        val now = t0 + 5 * day
        // As mesmas quedas e o mesmo tempo a ver: antigas (a fonte já melhorou) ou de hoje.
        val old = SourceHistory()
        old.recordWatched(a, 3_600, t0)
        repeat(4) { old.recordDrop(a, t0) }
        old.recordWatched(a, 3 * 3_600, now)
        val fresh = SourceHistory()
        fresh.recordWatched(a, 3 * 3_600, t0)
        fresh.recordWatched(a, 3_600, now)
        repeat(4) { fresh.recordDrop(a, now) }
        assertTrue(old.score(a, now) > fresh.score(a, now), "${old.score(a, now)} vs ${fresh.score(a, now)}")
    }

    @Test
    fun badGoesToTheBackNeverOut() {
        val h = SourceHistory()
        h.recordOpen(a, false, t0)
        h.recordOpen(a, false, t0)
        val out = SourceReliability.order(listOf("1", "2", "3"), h, t0, { 1 }, { if (it == "1") a else null })
        assertEquals(listOf("2", "3", "1"), out)
    }

    @Test
    fun equalTierPrefersTheReliableButNeverCrossesTiers() {
        val h = SourceHistory()
        // "2" tem uma queda por hora (nota ~92, a mesma faixa), "3" tem 3 por hora (~76).
        h.recordWatched(SourceHistory.key("p", "3"), 3_600, t0)
        repeat(3) { h.recordDrop(SourceHistory.key("p", "3"), t0) }
        val tiers = mapOf("1" to 3, "2" to 2, "3" to 2, "4" to 2)
        val out = SourceReliability.order(listOf("1", "3", "2", "4"), h, t0, { tiers.getValue(it) }, { SourceHistory.key("p", it) })
        // O 3 (pior) passa para trás do 2 e do 4 (iguais em qualidade); o 1 fica à frente, de outra qualidade.
        assertEquals(listOf("1", "2", "4", "3"), out)
        val crossing = SourceReliability.order(listOf("1", "3"), h, t0, { tiers.getValue(it) }, { SourceHistory.key("p", it) })
        assertEquals(listOf("1", "3"), crossing)
    }

    @Test
    fun smallDifferencesDoNotReorder() {
        val h = SourceHistory()
        h.recordWatched(SourceHistory.key("p", "1"), 3_600, t0)
        h.recordDrop(SourceHistory.key("p", "1"), t0)
        val out = SourceReliability.order(listOf("1", "2"), h, t0, { 1 }, { SourceHistory.key("p", it) })
        assertEquals(listOf("1", "2"), out)
    }

    @Test
    fun capsTheNumberOfSources() {
        val h = SourceHistory()
        for (i in 0 until SourceHistory.MAX + 5) h.recordDrop("p|$i", t0 + i)
        assertNull(h.stats("p|0", t0 + SourceHistory.MAX))
        assertEquals(1, h.stats("p|${SourceHistory.MAX + 4}", t0 + SourceHistory.MAX)!!.drops)
        assertEquals(SourceHistory.MAX, SourceHistory.decode(h.encode(t0 + 2000), t0 + 2000).encode(t0 + 2000).lines().size)
    }

    @Test
    fun badKeysAndGarbageAreIgnored() {
        val h = SourceHistory()
        assertFalse(h.recordDrop("no-separator", t0))
        assertFalse(h.recordDrop("p|a\tb", t0))
        assertFalse(h.recordWatched(a, 0, t0))
        assertEquals("", SourceHistory.decode("lixo\n1\t2\t3\n1\t2\tx:y\tp|a", t0).encode(t0))
        assertEquals("", SourceHistory.decode(null, t0).encode(t0))
    }

    @Test
    fun watchedLabels() {
        assertEquals("1 min", SourceReliability.watchedLabel(20))
        assertEquals("35 min", SourceReliability.watchedLabel(35 * 60))
        assertEquals("2 h", SourceReliability.watchedLabel(2 * 3600 + 120))
        assertEquals("2 h 30 min", SourceReliability.watchedLabel(2 * 3600 + 1800))
    }
}
