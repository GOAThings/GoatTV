package cc.slokker.itv.data

import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.PlaylistDto
import cc.slokker.itv.ui.state.FakeBackend
import cc.slokker.itv.ui.state.TempFiles
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryTest {
    private val files = TempFiles()
    private val gson = Gson()
    private val backend = FakeBackend()
    private val cache = CacheStore(files.context, gson)
    private val localStore = LocalStore(files.context, gson)

    private val fromServer = PlaylistDto("acc1", "xtream", "Casa", "http://x", "u", "p", "", "")
    private val base = ConfigResponse(null, null, listOf(fromServer), null, null, settingsRev = 4)
    private val edited = listOf(Playlist("local1", PlaylistType.M3U, "Lista", url = "http://m/list.m3u"))
    private val settings = FakeBackend.json("""{"accent": "gold"}""")

    @After
    fun cleanUp() {
        files.delete()
    }

    @Test
    fun `each mode says what it allows`() {
        val local = LocalProfile(localStore)
        val account = AccountProfile(backend, cache)
        val test = TestModeProfile(backend, cache)
        assertEquals(listOf(false, true, true), listOf(local, account, test).map { it.isAccount })
        assertEquals(listOf(false, true, false), listOf(local, account, test).map { it.syncs })
        assertEquals(listOf(false, true, true), listOf(local, account, test).map { it.supportsEpgMapping })
        assertEquals(listOf(true, false, false), listOf(local, account, test).map { it.supportsStalker })
        assertEquals(listOf(false, true, false), listOf(local, account, test).map { it.uploadsIndex })
        assertEquals(listOf(null, null, "Test mode"), listOf(local, account, test).map { it.refusal })
    }

    @Test
    fun `without an account the disk copy carries this tv's playlists`() = runBlocking {
        val local = LocalProfile(localStore)
        val config = local.withProfile(base, settings, JsonObject(), edited)
        assertEquals(listOf("local1"), config.playlists.orEmpty().map { it.id })
        assertEquals(4L, config.settingsRev)
        local.save(config)
        assertEquals("gold", localStore.load().settings!!.asJsonObject.get("accent").asString)
        assertNull(cache.loadConfig())
    }

    @Test
    fun `with an account the disk copy keeps the account's playlists, in the config cache`() = runBlocking {
        val account = AccountProfile(backend, cache)
        val config = account.withProfile(base, settings, JsonObject(), edited)
        assertEquals(listOf(fromServer), config.playlists)
        account.save(config)
        assertEquals(listOf(fromServer), cache.loadConfig()!!.playlists)
        assertTrue(localStore.load().playlists.isNullOrEmpty())
        account.patch(settings)
        assertEquals(listOf("updateProfile"), backend.calls)
    }

    @Test
    fun `test mode never writes to the account`() = runBlocking {
        val test = TestModeProfile(backend, cache)
        for (write in listOf<suspend () -> Unit>(
            { test.patch(settings) },
            { test.createPlaylist(settings) },
            { test.updatePlaylist("acc1", settings) },
            { test.deletePlaylist("acc1") },
        )) {
            val refused = runCatching { write() }.exceptionOrNull()
            assertEquals("Test mode", refused?.message)
        }
        assertFalse(backend.calls.any())
    }
}
