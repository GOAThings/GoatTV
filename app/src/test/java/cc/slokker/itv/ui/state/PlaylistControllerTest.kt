package cc.slokker.itv.ui.state

import cc.slokker.itv.data.AccountProfile
import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.LocalProfile
import cc.slokker.itv.data.LocalStore
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileRepository
import cc.slokker.itv.data.RefreshTracker
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.PlaylistDto
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistControllerTest {
    private val files = TempFiles()
    private val gson = Gson()
    private val backend = FakeBackend()
    private val cache = CacheStore(files.context, gson)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private var repository: ProfileRepository = AccountProfile(backend, cache)
    private val profile = ProfileStore(scope, gson, { repository }, { 0 }, {}, {})
    private val tracker = RefreshTracker()
    private val prefs = MutableStateFlow(Prefs())
    private val toasts = mutableListOf<String>()
    private val refreshed = mutableListOf<Pair<Int, Boolean>>()
    private lateinit var s: cc.slokker.itv.ui.i18n.Strings

    private val playlist = Playlist("p1", PlaylistType.M3U, "Lista", url = "http://m/list.m3u")
    private var version = 0
    private var failWith: Exception? = null
    private val loads = mutableListOf<String>()

    /** O "fornecedor": cada carga dá uma versão nova dos canais (o número do primeiro canal diz qual). */
    private suspend fun load(p: Playlist): PlaylistContent {
        loads += p.id
        failWith?.let { throw it }
        version++
        return PlaylistContent.build(p, listOf(Category("g", "G")), listOf(Channel(0, "a", version, "A", null, "g")), guideUrl = if (version > 2) "http://m/epg.xml" else null)
    }

    private val lists = PlaylistController(scope, ::load, cache, backend, profile, { repository }, tracker, prefs, { toasts += it }) { fresh, guideChanged ->
        refreshed += fresh.channels.first().num to guideChanged
    }

    @Before
    fun setUp() {
        s = TestStrings.install()
        profile.applyConfig(ConfigResponse(null, null, listOf(PlaylistDto("p1", "m3u", "Lista", null, null, null, "http://m/list.m3u", null)), null, null))
    }

    @After
    fun cleanUp() {
        job.cancel()
        files.delete()
    }

    private fun settle() = runBlocking {
        withTimeout(10_000) { while (job.children.any { it.isActive }) delay(5) }
    }

    @Test
    fun `a download is cached and counts as checked`() = runBlocking {
        assertNull(lists.cached(playlist))
        val loaded = lists.download(playlist)
        assertEquals(1, lists.cached(playlist)?.channels?.first()?.num)
        assertTrue(lists.hasCached(playlist))
        assertTrue(tracker.isChecked(RefreshTracker.Slot.PLAYLIST, "p1"))
        lists.show(loaded)
        assertSame(loaded, lists.content.value)
    }

    @Test
    fun `the open playlist refreshes in the background when due, once per opening of the app`() {
        val first = runBlocking { lists.download(playlist) }
        lists.show(first)
        settle()
        // Acabou de se descarregar: com "sempre que abrir a app" não volta a pedir.
        lists.maybeRefresh(first)
        settle()
        assertEquals(1, loads.size)
        tracker.clearChecked()
        lists.maybeRefresh(first)
        settle()
        assertEquals(2, loads.size)
        assertEquals(2, lists.content.value?.channels?.first()?.num)
        assertEquals(listOf(2 to false), refreshed)
        // Os canais novos também ficam na cache.
        assertEquals(2, runBlocking { lists.cached(playlist) }?.channels?.first()?.num)
    }

    @Test
    fun `a manual reload tells how it went, and a playlist that changed meanwhile is left alone`() {
        lists.show(runBlocking { lists.download(playlist) })
        lists.refreshInBackground(playlist, notify = true)
        settle()
        assertEquals(listOf(s.playlistUpdated), toasts)
        failWith = java.io.IOException("offline")
        lists.refreshInBackground(playlist, notify = true)
        settle()
        assertEquals(s.playlistLoadFailed("Lista", "offline"), toasts.last())
        failWith = null
        // A lista aberta passou a ser outra (ou foi editada): os canais que chegam já não servem.
        lists.show(PlaylistContent.build(playlist.copy(url = "http://m/other.m3u"), emptyList(), listOf(Channel(0, "z", 9, "Z", null, "g"))))
        lists.refreshInBackground(playlist)
        settle()
        assertEquals(9, lists.content.value?.channels?.first()?.num)
        assertEquals(listOf(2 to false), refreshed)
    }

    @Test
    fun `the channel index goes to the account once per change, never without an account`() {
        profile.rememberIndexHashes(ConfigResponse(null, null, listOf(PlaylistDto("p1", "m3u", "Lista", null, null, null, "u", null, indexHash = null)), null, null))
        val loaded = runBlocking { lists.download(playlist) }
        lists.show(loaded)
        settle()
        lists.show(loaded)
        settle()
        assertEquals(listOf("putPlaylistIndex:p1"), backend.calls)
        repository = LocalProfile(LocalStore(files.context, gson))
        lists.show(runBlocking { lists.download(playlist) })
        settle()
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `second channel playlists are kept until closed or forgotten`() = runBlocking {
        val other = Playlist("p2", PlaylistType.M3U, "Outra", url = "http://o/list.m3u")
        val a = lists.loadForSecond(other)
        assertSame(a, lists.loadForSecond(other))
        lists.forget("p2")
        lists.loadForSecond(other) // agora da cache em disco, sem rede
        assertEquals(listOf("p2"), loads)
        lists.close()
        assertNull(lists.content.value)
        // A lista aberta serve também para o segundo canal.
        val open = lists.download(playlist)
        lists.show(open)
        assertSame(open, lists.loadForSecond(playlist))
    }
}
