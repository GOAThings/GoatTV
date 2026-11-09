package cc.slokker.itv.ui.state

import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Reminder
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemindersTest {
    private val files = TempFiles()
    private val cache = CacheStore(files.context, Gson())
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private var playlistId: String? = "p1"
    private val toasts = mutableListOf<String>()
    private val reminders = Reminders(scope, cache, { playlistId }, { toasts += it })
    private lateinit var s: cc.slokker.itv.ui.i18n.Strings

    private val now = 10_000_000L
    private val rtp = Channel(0, "rtp", 1, "RTP 1", null, "news")
    private val news = EpgEntry("Telejornal", now + 30 * 60_000L, now + 90 * 60_000L)

    @Before
    fun setUp() {
        s = TestStrings.install()
    }

    @After
    fun cleanUp() {
        job.cancel()
        files.delete()
    }

    private fun settle() = runBlocking { withTimeout(10_000) { while (job.children.any { it.isActive }) delay(5) } }

    @Test
    fun `a reminder is set, shown a minute before, and kept on disk`() {
        reminders.toggle(rtp, news)
        settle()
        assertTrue(reminders.has(rtp, news))
        assertEquals(listOf(s.reminderSet("Telejornal")), toasts)
        assertNull(reminders.due(now))
        val due = reminders.due(news.startMs - 60_000L)
        assertEquals(Reminder("p1", "rtp", "RTP 1", "Telejornal", news.startMs), due)
        // Noutra lista não aparece.
        playlistId = "p2"
        assertFalse(reminders.has(rtp, news))
        assertNull(reminders.due(news.startMs))
        playlistId = "p1"
        val reread = Reminders(scope, cache, { "p1" }, {})
        runBlocking { reread.load(now) }
        assertEquals(listOf(due), reread.reminders.value)
        // Passados mais de 10 min do início, já não se lê.
        runBlocking { reread.load(news.startMs + Reminders.GRACE_MS) }
        assertEquals(emptyList<Reminder>(), reread.reminders.value)
    }

    @Test
    fun `toggling again removes it, and consuming takes it off the list`() {
        reminders.toggle(rtp, news)
        reminders.toggle(rtp, news)
        assertFalse(reminders.has(rtp, news))
        assertEquals(s.reminderRemoved, toasts.last())
        reminders.toggle(rtp, news)
        reminders.consume(reminders.due(news.startMs)!!)
        settle()
        assertEquals(emptyList<Reminder>(), reminders.reminders.value)
        assertEquals(emptyList<Reminder>(), runBlocking { cache.loadReminders() })
    }
}
