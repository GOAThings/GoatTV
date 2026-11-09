package cc.slokker.itv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgrammesTest {
    private val min = 60_000L
    private val news = EpgEntry("Telejornal", 20 * 60 * min, 21 * 60 * min)
    private val film = EpgEntry("Filme", 21 * 60 * min, 23 * 60 * min)
    // Um buraco no guia entre as 23:00 e as 23:30.
    private val late = EpgEntry("Última hora", (23 * 60 + 30) * min, 24 * 60 * min)
    private val day = listOf(news, film, late)

    @Test
    fun `the programme on at a time counts the start and not the end`() {
        assertSame(news, day.at(news.startMs))
        assertSame(news, day.at(news.endMs - 1))
        assertSame(film, day.at(news.endMs))
        assertTrue(news.isOn(news.startMs))
        assertFalse(news.isOn(news.endMs))
    }

    @Test
    fun `no programme in a gap or outside the guide`() {
        assertNull(day.at(film.endMs))
        assertNull(day.at(news.startMs - 1))
        assertNull(day.at(late.endMs))
        assertNull(emptyList<EpgEntry>().at(news.startMs))
    }

    @Test
    fun `next is the first that starts when the current one ends`() {
        assertSame(film, day.after(news, news.startMs + min))
        assertSame(late, day.after(film, film.startMs))
        assertNull(day.after(late, late.startMs))
    }

    @Test
    fun `without a current programme next starts from the time asked`() {
        // No buraco: o seguinte é o que começa depois de agora.
        assertSame(late, day.after(null, film.endMs + min))
        // Antes do guia começar: o primeiro.
        assertSame(news, day.after(null, 0L))
    }

    @Test
    fun `minutes left round up and still say one in the last minute`() {
        assertEquals(60, news.minutesLeft(news.startMs + 1))
        assertEquals(61, news.minutesLeft(news.startMs))
        assertEquals(1, news.minutesLeft(news.endMs - 1))
        assertEquals(1, minutesLeft(10 * min, 10 * min - 59_999))
        assertEquals(16, minutesLeft(15 * min, 0))
    }

    @Test
    fun `the guide wins and the short epg is the fallback without repeats`() {
        val shortEpg = listOf(news, news.copy(endMs = news.endMs + min), film)
        assertSame(day, programmes(day, shortEpg))
        assertEquals(listOf(news, film), programmes(emptyList(), shortEpg))
        assertEquals(emptyList<EpgEntry>(), programmes(emptyList(), emptyList()))
    }
}
