package cc.slokker.itv.ui.state

import cc.slokker.itv.data.AccountProfile
import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.LocalProfile
import cc.slokker.itv.data.LocalStore
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileKeys
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.TestModeProfile
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.PlaylistDto
import cc.slokker.itv.ui.state.FakeBackend.Companion.json
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ProfileStoreTest {
    private val files = TempFiles()
    private val gson = Gson()
    private val backend = FakeBackend()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val localStore = LocalStore(files.context, gson)
    private val cache = CacheStore(files.context, gson)
    private var local = false
    private var testMode = false
    private var generation = 0
    private val failures = mutableListOf<Int?>()
    private val languages = mutableListOf<String>()

    private val store = ProfileStore(
        scope, gson,
        repository = {
            when {
                local -> LocalProfile(localStore)
                testMode -> TestModeProfile(backend, cache)
                else -> AccountProfile(backend, cache)
            }
        },
        generation = { generation },
        onSaveFailed = { failures += it },
        onLanguage = { languages += it },
    )

    @After
    fun cleanUp() {
        files.delete()
    }

    /** Espera pelo que ficou a correr (gravações em disco, PATCH em série). */
    private fun settle() = runBlocking {
        while (job.children.any { it.isActive }) job.children.forEach { it.join() }
    }

    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("formats/$name")!!.readText()

    private fun config(settings: String, favorites: String = "{}", rev: Long? = 3, playlists: List<PlaylistDto> = listOf(xtreamDto)) =
        ConfigResponse(user = null, device = null, playlists = playlists, favorites = json(favorites), settings = json(settings), settingsRev = rev)

    private val xtreamDto = PlaylistDto("acc1", "xtream", "Casa", "http://x.example.com:8080", "user1", "pass1", "", "")

    @Test
    fun `the keys the app keeps in settings read from today's file`() {
        local = true
        val fixture = gson.fromJson(fixture("local_config.json"), ConfigResponse::class.java)
        store.applyConfig(fixture)
        assertEquals("local2", store.lastPlaylistId)
        assertEquals("rtp1.pt", store.lastChannelKey)
        assertEquals(1761300000000L, store.lastChannelAt)
        assertEquals(StreamFormat.TS, store.streamFormat.value)
        assertEquals(mapOf("local2" to listOf("rtp1.pt")), store.recents.value)
        assertEquals(listOf("101", "102"), store.favorites.value["local1a2b3c4d5e6f7a8b"])
        assertEquals("mint", store.prefs.value.accent)
        assertEquals(listOf("local1a2b3c4d5e6f7a8b", "local2", "local3"), store.playlists.value.map { it.id })
        assertEquals(listOf("auto"), languages)
    }

    @Test
    fun `last channel and recents are written in the same shape`() {
        store.applyConfig(config("{}"))
        store.recordLast("acc1", "101", at = 5L, watched = true, maxRecents = 2)
        store.recordLast("acc1", "102", at = null, watched = true, maxRecents = 2)
        store.recordLast("acc1", "103", at = 9L, watched = true, maxRecents = 2)
        store.recordLast("acc1", "104", at = null, watched = false, maxRecents = 2)
        settle()
        val sent = backend.profileBodies.map { it.getAsJsonObject("settings_merge") }
        val last = sent.last()
        // Só a diferença: a lista já tinha ido no primeiro.
        assertEquals("acc1", sent.first().get(ProfileKeys.LAST_PLAYLIST).asString)
        assertNull(last.get(ProfileKeys.LAST_PLAYLIST))
        assertEquals("103", last.get(ProfileKeys.LAST_CHANNEL).asString)
        assertEquals(9L, last.get(ProfileKeys.LAST_CHANNEL_AT).asLong)
        assertEquals(json("""{"acc1": ["103", "102"]}"""), last.get(ProfileKeys.RECENT_CHANNELS))
        // O último não contou como visto e não mudou o último canal: não gravou nada.
        assertEquals(3, backend.profileBodies.size)
        assertEquals(listOf("103", "102"), store.recents.value["acc1"])
    }

    @Test
    fun `only the difference goes to the server, with the revision when an array changes`() {
        store.applyConfig(config("""{"accent": "mint", "hidden_groups": ["acc1:1"]}""", rev = 7))
        store.setPref(Prefs.Key.ACCENT, "gold")
        settle()
        assertEquals(json("""{"settings_merge": {"accent": "gold"}}"""), backend.profileBodies[0])
        store.setPref(Prefs.Key.HIDDEN_GROUPS, listOf("acc1:1", "acc1:2"))
        settle()
        assertEquals(json("""{"settings_merge": {"hidden_groups": ["acc1:1", "acc1:2"]}, "settings_rev": 7}"""), backend.profileBodies[1])
        assertEquals(setOf("acc1:1", "acc1:2"), store.prefs.value.hiddenGroups)
    }

    @Test
    fun `a 409 puts the changes made here over the server's and tries again`() {
        store.applyConfig(config("""{"hidden_groups": ["acc1:1"]}""", rev = 7))
        var attempt = 0
        backend.onUpdateProfile = {
            attempt++
            if (attempt == 1) {
                // Entretanto, no site, mudaram a cor e esconderam outro grupo.
                FakeBackend.error(409, """{"settings": {"hidden_groups": ["acc1:1", "acc1:5"], "accent": "violet"}, "settings_rev": 9}""")
            } else {
                Response.success(json("""{"settings": {"hidden_groups": ["acc1:1", "acc1:5", "acc1:2"], "accent": "violet"}, "settings_rev": 10}"""))
            }
        }
        store.setPref(Prefs.Key.HIDDEN_GROUPS, listOf("acc1:1", "acc1:2"))
        settle()
        assertEquals(2, backend.profileBodies.size)
        val retry = backend.profileBodies[1]
        assertEquals(9L, retry.get("settings_rev").asLong)
        assertEquals(json("""{"hidden_groups": ["acc1:1", "acc1:5", "acc1:2"]}"""), retry.getAsJsonObject("settings_merge"))
        assertEquals("violet", store.prefs.value.accent)
        assertEquals(setOf("acc1:1", "acc1:5", "acc1:2"), store.prefs.value.hiddenGroups)
        assertEquals(emptyList<Int?>(), failures)
    }

    @Test
    fun `a reply from before the session changed is ignored`() {
        store.applyConfig(config("{}"))
        backend.onUpdateProfile = {
            generation++
            Response.success(json("""{"settings": {"accent": "violet"}}"""))
        }
        store.setPref(Prefs.Key.ACCENT, "gold")
        settle()
        assertEquals("gold", store.prefs.value.accent)
    }

    @Test
    fun `failed saves are reported, a 401 is not`() {
        store.applyConfig(config("{}"))
        backend.onUpdateProfile = { FakeBackend.error(500, "{}") }
        store.setPref(Prefs.Key.ACCENT, "gold")
        settle()
        backend.onUpdateProfile = { FakeBackend.error(401, "{}") }
        store.setPref(Prefs.Key.ACCENT, "mint")
        settle()
        backend.onUpdateProfile = { throw java.io.IOException("offline") }
        store.toggleFavorite("acc1", "101")
        settle()
        assertEquals(listOf<Int?>(500, null), failures)
    }

    @Test
    fun `without an account nothing goes to the server and the playlists stay on this tv`() {
        local = true
        store.applyConfig(ConfigResponse(null, null, emptyList(), null, null))
        store.upsertPlaylist(Playlist("local1", PlaylistType.M3U, "Lista", url = "http://m/list.m3u"))
        settle()
        assertTrue(store.toggleFavorite("local1", "a"))
        settle()
        store.setLastPlaylist("local1")
        settle()
        assertEquals(emptyList<String>(), backend.calls)
        val saved = runBlocking { localStore.load() }
        assertEquals(listOf("local1"), saved.playlists.orEmpty().map { it.id })
        assertEquals(listOf("m3u"), saved.playlists.orEmpty().map { it.type })
        assertEquals("local1", saved.settings!!.asJsonObject.get(ProfileKeys.LAST_PLAYLIST).asString)
        assertEquals(json("""{"local1": ["a"]}"""), saved.favorites)
    }

    @Test
    fun `with an account the disk copy is the config cache, with the account's playlists`() {
        store.applyConfig(config("{}"))
        store.setStreamFormat(StreamFormat.HLS)
        settle()
        val cached = runBlocking { cache.loadConfig() }!!
        assertEquals(StreamFormat.HLS, cached.settings!!.asJsonObject.get(ProfileKeys.STREAM_FORMAT).asString)
        assertEquals(listOf(xtreamDto), cached.playlists)
        store.setStreamFormat(null)
        assertNull(store.snapshot()!!.settings!!.asJsonObject.get(ProfileKeys.STREAM_FORMAT))
    }

    @Test
    fun `test mode writes nothing to the account`() {
        testMode = true
        store.applyConfig(config("{}"))
        store.setPref(Prefs.Key.ACCENT, "gold")
        store.toggleFavorite("acc1", "101")
        settle()
        assertEquals(emptyList<String>(), backend.calls)
        assertEquals("gold", store.prefs.value.accent)
    }

    @Test
    fun `removing a playlist from this tv takes its favourites, recents and settings`() {
        local = true
        store.applyConfig(
            ConfigResponse(
                null, null, listOf(PlaylistDto("l1", "m3u", "A", null, null, null, "u1", null), PlaylistDto("l2", "m3u", "B", null, null, null, "u2", null)),
                json("""{"l1": ["a"], "l2": ["b"]}"""),
                json("""{"hidden_groups": ["l1:g", "l2:g"], "recent_channels": {"l1": ["a"], "l2": ["b"]}, "group_order": {"l1": ["g"]}}"""),
            ),
        )
        val remaining = store.removeLocalPlaylist("l1")
        assertEquals(listOf("l2"), remaining.map { it.id })
        assertEquals(mapOf("l2" to listOf("b")), store.favorites.value)
        assertEquals(mapOf("l2" to listOf("b")), store.recents.value)
        assertEquals(setOf("l2:g"), store.prefs.value.hiddenGroups)
        assertEquals(emptyMap<String, List<String>>(), store.prefs.value.groupOrder)
    }

    @Test
    fun `connecting an account moves this tv's settings and favourites to the same playlists`() {
        val localConfig = ConfigResponse(
            null, null,
            listOf(PlaylistDto("local9", "xtream", "Casa", "http://X.example.com:8080/", "user1", "pass1", null, null)),
            json("""{"local9": ["101", "102"], "localGone": ["7"]}"""),
            json("""{"accent": "sunset", "group_order": {"local9": ["3", "1"]}, "hidden_groups": ["local9:7"]}"""),
        )
        // A conta ainda não tem definições nem favoritos.
        store.applyConfig(config("{}"))
        assertTrue(store.adoptLocalProfile(localConfig))
        settle()
        assertEquals("sunset", store.prefs.value.accent)
        assertEquals(mapOf("acc1" to listOf("3", "1")), store.prefs.value.groupOrder)
        assertEquals(setOf("acc1:7"), store.prefs.value.hiddenGroups)
        assertEquals(mapOf("acc1" to listOf("101", "102")), store.favorites.value)
        assertTrue(backend.profileBodies.any { it.has("favorites") })
        // Já com definições na conta, as desta TV não passam por cima.
        val other = ProfileStore(scope, gson, { AccountProfile(backend, cache) }, { 0 }, {}, {})
        other.applyConfig(config("""{"accent": "gold"}""", favorites = """{"acc1": ["5"]}"""))
        assertFalse(other.adoptLocalProfile(localConfig))
        assertEquals("gold", other.prefs.value.accent)
        assertEquals(listOf("5"), other.favorites.value["acc1"])
    }

    @Test
    fun `a sync keeps the settings when the server sends none, and tells when the locks changed`() {
        store.applyConfig(config("""{"accent": "gold"}"""))
        assertFalse(store.applyConfig(config("{}").copy(settings = null), keepSettingsIfMissing = true))
        assertEquals("gold", store.prefs.value.accent)
        assertTrue(store.applyConfig(config("""{"parental_pin_sha256": "${Prefs.hashPin("1")}", "locked_groups": ["acc1:3"]}""")))
        assertFalse(store.applyConfig(config("""{"parental_pin_sha256": "${Prefs.hashPin("1")}", "locked_groups": ["acc1:3"], "accent": "mint"}""")))
        // Uma config completa sem settings começa do zero.
        store.applyConfig(config("{}").copy(settings = null))
        assertEquals(Prefs(), store.prefs.value)
    }

    @Test
    fun `reset forgets the profile but keeps the prefs until asked`() {
        store.applyConfig(config("""{"accent": "gold", "stream_format": "m3u8"}""", favorites = """{"acc1": ["1"]}"""))
        store.reset()
        assertEquals(emptyList<Playlist>(), store.playlists.value)
        assertEquals(emptyMap<String, List<String>>(), store.favorites.value)
        assertNull(store.streamFormat.value)
        assertNull(store.lastConfig)
        assertEquals("gold", store.prefs.value.accent)
        store.resetPrefs()
        assertEquals(Prefs(), store.prefs.value)
    }
}
