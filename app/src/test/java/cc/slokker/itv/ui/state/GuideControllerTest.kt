package cc.slokker.itv.ui.state

import cc.slokker.itv.data.AccountProfile
import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.at
import cc.slokker.itv.data.EpgRepository
import cc.slokker.itv.data.LocalProfile
import cc.slokker.itv.data.LocalStore
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileRepository
import cc.slokker.itv.data.RefreshTracker
import cc.slokker.itv.data.TestModeProfile
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.api.EpgMapping
import cc.slokker.itv.data.epg.AccountGuideSource
import cc.slokker.itv.data.epg.XmltvGuide
import cc.slokker.itv.data.epg.XmltvSource
import cc.slokker.itv.data.stalker.StalkerSource
import cc.slokker.itv.data.xtream.XtreamSource
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class GuideControllerTest {
    private val files = TempFiles()
    private val gson = Gson()
    private val backend = FakeBackend()
    private val cache = CacheStore(files.context, gson)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val http = OkHttpClient()
    private val epg = EpgRepository(XtreamSource(http, gson), StalkerSource(http), XmltvSource(http), AccountGuideSource(backend), cache)

    private val playlist = Playlist("p1", PlaylistType.M3U, "Lista", url = "http://m/list.m3u", epgUrl = "http://m/epg.xml")
    private val rtp = Channel(0, "rtp", 1, "RTP 1", null, "news", epgId = "RTP1.pt")
    private val sic = Channel(1, "sic", 2, "SIC HD", null, "news")
    private val content = PlaylistContent.build(playlist, listOf(Category("news", "Notícias")), listOf(rtp, sic))
    private val open = MutableStateFlow<PlaylistContent?>(null)
    private val prefs = MutableStateFlow(Prefs())
    private var repository: ProfileRepository = AccountProfile(backend, cache)
    private val toasts = mutableListOf<String>()
    private val tracker = RefreshTracker()
    private lateinit var s: cc.slokker.itv.ui.i18n.Strings

    private val guide by lazy { GuideController(scope, epg, backend, open, prefs, { repository }, tracker, { toasts += it }) }

    private val now = System.currentTimeMillis()
    private val telejornal = EpgEntry("Telejornal", now - 600_000L, now + 1_200_000L)

    @Before
    fun setUp() {
        s = TestStrings.install()
        guide.mappings
        alwaysRunning = job.children.count { it.isActive }
    }

    @After
    fun cleanUp() {
        job.cancel()
        files.delete()
    }

    /** Os coroutines que ficam sempre a correr (os logótipos e o país dos logótipos): só se espera pelos outros. */
    private var alwaysRunning = 0

    private fun settle() = runBlocking {
        withTimeout(10_000) { while (job.children.count { it.isActive } > alwaysRunning) delay(5) }
    }

    /** Um guia XMLTV já em disco (acabado de descarregar: não volta à rede). */
    private fun storedGuide() = runBlocking {
        cache.saveGuide("p1", XmltvGuide("http://m/epg.xml", now, mapOf("rtp1.pt" to listOf(telejornal)), emptyMap()))
    }

    @Test
    fun `programmes come with the offset from the settings`() {
        storedGuide()
        open.value = content
        guide.open(content)
        settle()
        assertEquals(listOf(telejornal), guide.guideEntries(rtp))
        prefs.value = Prefs(epgOffsetMin = 60)
        val shifted = guide.guideEntries(rtp).single()
        assertEquals(telejornal.startMs + 3_600_000L, shifted.startMs)
        assertEquals(telejornal.endMs + 3_600_000L, shifted.endMs)
        assertEquals(shifted, guide.guideEntries(rtp).at(shifted.startMs + 1))
        assertEquals(emptyList<EpgEntry>(), guide.guideEntries(sic))
        // Uma lista acabada de abrir não volta a descarregar o guia (ainda está no intervalo).
        assertTrue(tracker.isChecked(RefreshTracker.Slot.GUIDE, "p1").not())
    }

    @Test
    fun `the account mappings load once per playlist and again after the app comes back`() {
        backend.onEpgMappings = { mapOf("rtp" to EpgMapping("pt", "RTP1.pt", "RTP 1", "RTP 1", icon = "http://logo/rtp.png")) }
        open.value = content
        guide.openAccount(content)
        guide.openAccount(content)
        settle()
        assertEquals(listOf("epgMappings:p1"), backend.calls.filter { it.startsWith("epgMappings") })
        assertEquals(setOf("rtp"), guide.mappings.value.keys)
        guide.forgetMappings()
        guide.openAccount(content)
        settle()
        assertEquals(2, backend.calls.count { it.startsWith("epgMappings") })
    }

    @Test
    fun `no mappings yet (404) counts as none, no network tries again later`() {
        open.value = content
        backend.onEpgMappings = { throw java.io.IOException("offline") }
        guide.openAccount(content)
        settle()
        backend.onEpgMappings = { throw HttpException(Response.error<Any>(404, "".toResponseBody())) }
        guide.openAccount(content)
        settle()
        guide.openAccount(content)
        settle()
        assertEquals(2, backend.calls.count { it.startsWith("epgMappings") })
        assertEquals(emptyMap<String, EpgMapping>(), guide.mappings.value)
    }

    @Test
    fun `without an account there is no account guide`() {
        repository = LocalProfile(LocalStore(files.context, gson))
        open.value = content
        guide.openAccount(content)
        settle()
        assertFalse(guide.pickerAvailable)
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test
    fun `guide logos follow the mappings, and a channel's own choice wins`() {
        backend.onEpgMappings = { mapOf("rtp" to EpgMapping("pt", "RTP1.pt", "RTP 1", "RTP 1", icon = "http://logo/rtp.png")) }
        open.value = content
        guide.openAccount(content)
        settle()
        assertEquals(emptyMap<String, String>(), guide.channelLogos.value)
        prefs.value = Prefs(logoSource = Prefs.LogoSource.GUIDE)
        assertEquals(mapOf("rtp" to "http://logo/rtp.png"), guide.channelLogos.value)
        prefs.value = Prefs(logoSource = Prefs.LogoSource.GUIDE, channelLogos = mapOf("p1:rtp" to "", "p1:sic" to "http://mine/sic.png"))
        assertEquals(mapOf("sic" to "http://mine/sic.png"), guide.channelLogos.value)
    }

    @Test
    fun `mapping a channel saves it and reopens the account guide, test mode refuses`() = runBlocking {
        open.value = content
        val pick = EpgGuideChannel("SIC.pt", "SIC", null, "Jornal", "pt", "Portugal", null)
        assertTrue(guide.setEpgMapping(sic, "pt", pick))
        assertEquals("SIC", guide.mappings.value["sic"]?.guideName)
        assertEquals(s.epgMapped("SIC HD", "SIC"), toasts.last())
        assertTrue(guide.removeEpgMapping(sic))
        assertFalse("sic" in guide.mappings.value)

        repository = TestModeProfile(backend, cache)
        val before = backend.calls.size
        assertFalse(guide.setEpgMapping(sic, "pt", pick))
        assertEquals("Test mode", toasts.last())
        guide.applyEpgCountry("pt", "Portugal")
        assertEquals(before, backend.calls.size)
    }

    @Test
    fun `refresh tells when there is nothing to fetch, and waits for the playlist guide when there is`() {
        guide.refresh()
        assertEquals(listOf(s.epgRefreshed), toasts)
        toasts.clear()
        val noGuide = PlaylistContent.build(playlist.copy(epgUrl = null), listOf(Category("news", "Notícias")), listOf(rtp))
        open.value = noGuide
        guide.refresh()
        assertEquals(listOf(s.epgRefreshed), toasts)
        toasts.clear()
        // Com guia extra, o aviso espera por ele (falha: sem rede neste teste).
        prefs.value = Prefs(extraEpg = mapOf("p1" to "http://127.0.0.1:9/extra.xml"))
        guide.refresh()
        settle()
        assertEquals(listOf(s.epgRefreshing, s.epgRefreshFailed), toasts)
        assertTrue(tracker.isChecked(RefreshTracker.Slot.EXTRA_GUIDE, "p1"))
    }

    @Test
    fun `channels match a guide by tvg-id first, then by the normalized name`() {
        val guideChannels = listOf(
            EpgGuideChannel("rtp1.pt", "RTP Um", null, null, null, null, null),
            EpgGuideChannel("sic.pt", "SIC", null, null, null, null, null),
            EpgGuideChannel("sic2.pt", "SIC", null, null, null, null, null),
        )
        val pairs = GuideController.matchGuide(listOf(rtp, sic, sic.copy(uid = 5)), guideChannels)
        assertEquals(listOf("rtp" to "rtp1.pt", "sic" to "sic.pt"), pairs.map { (ch, g) -> ch.key to g.id })
    }
}
