package cc.slokker.itv.data

import android.content.ContextWrapper
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.epg.AccountGuide
import cc.slokker.itv.data.epg.XmltvGuide
import cc.slokker.itv.data.m3u.M3uParser
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O que fica gravado e o que se partilha com o site e a Apple TV, tal como é hoje. Os ficheiros são o Gson das classes
 * (o Gson ignora a nulidade do Kotlin): mudar o nome de um campo perde os dados de quem já tem a app, sem erro nenhum.
 * Se um destes testes falhar, o formato mudou: é preciso ler o antigo também, não mudar o teste.
 */
class PersistedFormatTest {

    private val dir: File = Files.createTempDirectory("goattv-formats").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val gson = Gson() // o do AppContainer

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // ---- LocalStore: local/config.json (a única cópia das listas de quem não tem conta) ----

    @Test
    fun `local config reads today's file and writes it back the same`() = runBlocking {
        val fixture = fixture("local_config.json")
        File(dir, "local").mkdirs()
        File(dir, "local/config.json").writeText(fixture)
        val store = LocalStore(context, gson)
        val config = store.load()

        val playlists = config.playlists.orEmpty()
        assertEquals(listOf("local1a2b3c4d5e6f7a8b", "local2", "local3"), playlists.map { it.id })
        assertEquals(listOf("xtream", "m3u", "stalker"), playlists.map { it.type })
        assertEquals("http://xtream.example.com/epg.xml", playlists[0].epgUrl)
        assertEquals("pass1", playlists[0].password)
        assertEquals("http://m3u.example.com/list.m3u", playlists[1].url)
        // Os campos Stalker ficam em camelCase no ficheiro (não têm @SerializedName).
        with(playlists[2]) {
            assertEquals("00:1A:79:12:34:56", username)
            assertEquals(listOf("SN123", "DEV1", "DEV2", "SIG"), listOf(serial, deviceId, deviceId2, signature))
        }
        assertNull(playlists[0].indexHash)
        val settings = config.settings!!.asJsonObject
        assertEquals("mint", Prefs.from(settings).accent)
        assertEquals("local2", settings.get("last_playlist").asString)
        assertEquals(listOf("101", "102"), config.favorites!!.asJsonObject.getAsJsonArray("local1a2b3c4d5e6f7a8b").map { it.asString })

        store.save(config)
        assertJsonEquals(fixture, File(dir, "local/config.json").readText())
    }

    @Test
    fun `local config that can't be read is kept aside, not overwritten`() = runBlocking {
        File(dir, "local").mkdirs()
        val broken = "{\"playlists\": [{\"id\": \"local1\", \"type\": \"m3u\"" // cortado a meio
        File(dir, "local/config.json").writeText(broken)
        val store = LocalStore(context, gson)

        val config = store.load()
        assertTrue(config.playlists.orEmpty().isEmpty())
        store.save(config.copy(settings = JsonObject().apply { addProperty("accent", "gold") }))

        val aside = File(dir, "local").listFiles()!!.filter { it.name.startsWith("config.json.bad") }
        assertEquals(1, aside.size)
        assertEquals(broken, aside.single().readText())
        assertEquals("gold", JsonParser.parseString(File(dir, "local/config.json").readText()).asJsonObject.getAsJsonObject("settings").get("accent").asString)
    }

    @Test
    fun `local config that does not exist starts empty`() = runBlocking {
        val config = LocalStore(context, gson).load()
        assertTrue(config.playlists.orEmpty().isEmpty())
        assertNull(config.settings)
        assertTrue(File(dir, "local").listFiles().orEmpty().isEmpty())
    }

    // ---- CacheStore: cache/*.json.gz ----

    @Test
    fun `cached account config reads today's file and writes it back the same`() = runBlocking {
        val fixture = fixture("account_config.json")
        writeGz("config.json.gz", fixture)
        val cache = CacheStore(context, gson)
        val config = cache.loadConfig()!!
        assertEquals("user123", config.user?.id)
        assertEquals("Sala", config.device?.name)
        assertEquals(7L, config.settingsRev)
        assertEquals("ea9f8cc27edc2e37f03ba70062966b66b503be16", config.playlists!![0].indexHash)
        assertNull(config.playlists[1].indexHash)
        assertEquals("http://m3u.example.com/epg.xml", config.playlists[1].epgUrl)
        assertEquals(setOf("pl1:9"), Prefs.from(config.settings!!.asJsonObject).lockedGroups)

        cache.saveConfig(config)
        assertJsonEquals(fixture, readGz("config.json.gz"))
    }

    @Test
    fun `cached playlist reads today's file and writes it back the same`() = runBlocking {
        val fixture = fixture("playlist.json")
        // O nome do ficheiro só leva as letras e os números do id.
        writeGz("playlist_p1.json.gz", fixture)
        val cache = CacheStore(context, gson)
        val playlist = Playlist("p-1", PlaylistType.XTREAM, "Fornecedor", server = "http://xtream.example.com", username = "u", password = "p")
        val content = cache.loadPlaylist(playlist)!!
        assertEquals(listOf(Category("1", "Notícias"), Category("Outros", "Outros")), content.categories)
        assertEquals(
            Channel(0, "101", 1, "RTP 1", "http://logos.example.com/rtp1.png", "1", streamId = "101", epgId = "rtp1.pt", archiveDays = 3),
            content.channels[0],
        )
        assertEquals(Channel(1, "102", 2, "Canal ç", null, "Outros", streamId = "102"), content.channels[1])
        assertEquals(XtreamAccount(1, "Active", setOf("ts", "m3u8"), "Europe/Lisbon"), content.account)
        assertEquals("http://xtream.example.com/xmltv.php?username=u&password=p", content.guideUrl)
        assertEquals(1761300000000L, content.fetchedAt)
        // Outros dados da lista (outra palavra-passe): a cache não serve.
        assertNull(cache.loadPlaylist(playlist.copy(password = "other")))

        cache.savePlaylist(content)
        assertJsonEquals(fixture, readGz("playlist_p1.json.gz"))
    }

    /** Os campos do catch-up das listas M3U (fase 6) são novos e opcionais: a lista de antes lê-se igual (acima). */
    @Test
    fun `cached m3u playlist keeps the catch-up fields`() = runBlocking {
        val fixture = fixture("playlist_m3u.json")
        writeGz("playlist_m1.json.gz", fixture)
        val cache = CacheStore(context, gson)
        val playlist = Playlist("m-1", PlaylistType.M3U, "Lista", url = "http://m3u.example.com/list.m3u")
        val content = cache.loadPlaylist(playlist)!!
        assertEquals(listOf(null, "default", "xc"), content.channels.map { it.catchupType })
        assertEquals(listOf(0, 7, 3), content.channels.map { it.archiveDays })
        assertEquals("http://archive.example.com/sic.ts?utc={utc}&lutc={lutc}", content.channels[1].catchupSource)
        assertNull(content.channels[2].catchupSource)
        cache.savePlaylist(content)
        assertJsonEquals(fixture, readGz("playlist_m1.json.gz"))
    }

    @Test
    fun `cached playlist from before fetchedAt counts as old`() = runBlocking {
        val old = JsonParser.parseString(fixture("playlist.json")).asJsonObject.apply { remove("fetchedAt") }
        writeGz("playlist_p1.json.gz", old.toString())
        val playlist = Playlist("p-1", PlaylistType.XTREAM, "Fornecedor", server = "http://xtream.example.com", username = "u", password = "p")
        assertEquals(0L, CacheStore(context, gson).loadPlaylist(playlist)!!.fetchedAt)
    }

    @Test
    fun `cached guides and reminders read today's files and write them back the same`() = runBlocking {
        val cache = CacheStore(context, gson)
        val url = "http://xtream.example.com/xmltv.php?username=u&password=p"

        val guide = fixture("guide.json")
        writeGz("epg_p1.json.gz", guide)
        val xmltv: XmltvGuide = cache.loadGuide("p-1", url)!!
        assertEquals(listOf(EpgEntry("Telejornal", 1761300000000L, 1761303600000L)), xmltv.programmes["rtp1.pt"])
        assertEquals("rtp1.pt", xmltv.names["rtp1"])
        assertNull(cache.loadGuide("p-1", "http://other.example.com/epg.xml"))
        cache.saveGuide("p-1", xmltv)
        assertJsonEquals(guide, readGz("epg_p1.json.gz"))

        val account = fixture("account_guide.json")
        writeGz("epg_account_p1.json.gz", account)
        val accountGuide: AccountGuide = cache.loadAccountGuide("p-1")!!
        assertEquals("\"v5\"", accountGuide.etag)
        assertEquals(listOf(EpgEntry("Telejornal", 1761300000000L, 1761303600000L)), accountGuide.programmes["101"])
        cache.saveAccountGuide(accountGuide)
        assertJsonEquals(account, readGz("epg_account_p1.json.gz"))

        val reminders = fixture("reminders.json")
        writeGz("reminders.json.gz", reminders)
        val loaded = cache.loadReminders()
        assertEquals(listOf(Reminder("p-1", "101", "RTP 1", "Telejornal", 1761300000000L)), loaded)
        cache.saveReminders(loaded)
        assertJsonEquals(reminders, readGz("reminders.json.gz"))
    }

    @Test
    fun `removing a playlist deletes its channels and all its guides`() = runBlocking {
        val names = { id: String -> listOf("playlist_$id.json.gz", "epg_$id.json.gz", "epg_${id}extra.json.gz", "epg_account_$id.json.gz") }
        (names("p1") + names("p2")).forEach { writeGz(it, "{}") }
        CacheStore(context, gson).removePlaylist("p-1")
        assertEquals(names("p2").sorted(), File(dir, "cache").list()!!.sorted())
    }

    // ---- settings da conta (iguais no site e na Apple TV) ----

    /** Cada campo de [Prefs]: a chave JSON e um valor diferente do padrão, na forma que o AppViewModel grava. */
    private val samples: Map<String, Pair<String, Any>> = mapOf(
        "engine" to (Prefs.Key.ENGINE to Prefs.Engine.VLC),
        "buffer" to (Prefs.Key.BUFFER to Prefs.Buffer.LARGE),
        "decoder" to (Prefs.Key.DECODER to Prefs.Decoder.SOFTWARE),
        "deinterlace" to (Prefs.Key.DEINTERLACE to Prefs.Deinterlace.OFF),
        "audioDelayMs" to (Prefs.Key.AUDIO_DELAY to 150),
        "aspect" to (Prefs.Key.ASPECT to Prefs.Aspect.ZOOM),
        "freezeFrame" to (Prefs.Key.FREEZE_FRAME to false),
        "reconnectSeconds" to (Prefs.Key.RECONNECT_SECONDS to 10),
        "afr" to (Prefs.Key.AFR to true),
        "afrMode" to (Prefs.Key.AFR_MODE to Prefs.AfrMode.START),
        "afrFallback" to (Prefs.Key.AFR_FALLBACK to Prefs.AfrFallback.HZ50),
        "audioPassthrough" to (Prefs.Key.AUDIO_PASSTHROUGH to false),
        "surroundDefault" to (Prefs.Key.SURROUND_DEFAULT to true),
        "pipEnabled" to (Prefs.Key.PIP_ENABLED to true),
        "audioDecoder" to (Prefs.Key.AUDIO_DECODER to Prefs.Decoder.SOFTWARE),
        "hdr" to (Prefs.Key.HDR to Prefs.Hdr.SDR),
        "netGraph" to (Prefs.Key.NET_GRAPH to true),
        "netWarnings" to (Prefs.Key.NET_WARNINGS to false),
        "timeshift" to (Prefs.Key.TIMESHIFT to false),
        "protectPaused" to (Prefs.Key.PROTECT_PAUSED to false),
        "userAgent" to (Prefs.Key.USER_AGENT to "VLC/3.0"),
        "autoStart" to (Prefs.Key.AUTO_START to true),
        "exitConfirm" to (Prefs.Key.EXIT_CONFIRM to false),
        "accent" to (Prefs.Key.ACCENT to "sunset"),
        "glassOpacity" to (Prefs.Key.GLASS_OPACITY to 35),
        "showFavoritesGroup" to (Prefs.Key.SHOW_FAVORITES_GROUP to false),
        "showAllGroup" to (Prefs.Key.SHOW_ALL_GROUP to false),
        "showRecentGroup" to (Prefs.Key.SHOW_RECENT_GROUP to false),
        "hiddenGroups" to (Prefs.Key.HIDDEN_GROUPS to listOf("p1:7", "p1:Outros")),
        "groupOrder" to (Prefs.Key.GROUP_ORDER to mapOf("p1" to listOf("3", "1"))),
        "hiddenChannels" to (Prefs.Key.HIDDEN_CHANNELS to listOf("p1:101")),
        "channelOrder" to (Prefs.Key.CHANNEL_ORDER to mapOf("p1:1" to listOf("102", "101"))),
        "upDown" to (Prefs.Key.UP_DOWN to Prefs.UpDown.LIST),
        "reverseZap" to (Prefs.Key.REVERSE_ZAP to true),
        "zapDelayMs" to (Prefs.Key.ZAP_DELAY to 1_000),
        "infoSeconds" to (Prefs.Key.INFO_SECONDS to 8),
        "startup" to (Prefs.Key.STARTUP to Prefs.Startup.LIST),
        "phoneStartup" to (Prefs.Key.PHONE_STARTUP to Prefs.PhoneStartup.LAST),
        "backAction" to (Prefs.Key.BACK_ACTION to Prefs.BackAction.EXIT),
        "showNumbers" to (Prefs.Key.SHOW_NUMBERS to false),
        "showLogos" to (Prefs.Key.SHOW_LOGOS to false),
        "logoSource" to (Prefs.Key.LOGO_SOURCE to "pt"),
        "channelLogos" to (Prefs.Key.CHANNEL_LOGOS to mapOf("p1:101" to "https://logos.example.com/rtp1.png", "p1:102" to "")),
        "extraEpg" to (Prefs.Key.EXTRA_EPG to mapOf("p1" to "https://epg.example.com/extra.xml")),
        "outlineColor" to (Prefs.Key.OUTLINE_COLOR to "pink"),
        "panelTimeout" to (Prefs.Key.PANEL_TIMEOUT to 30),
        "sort" to (Prefs.Key.SORT to Prefs.Sort.NAME),
        "uiScale" to (Prefs.Key.UI_SCALE to 110),
        "panelWidth" to (Prefs.Key.PANEL_WIDTH to 400),
        "textSize" to (Prefs.Key.TEXT_SIZE to Prefs.TextSize.LARGE),
        "language" to (Prefs.Key.LANGUAGE to "pt-BR"),
        "playlistRefreshHours" to (Prefs.Key.PLAYLIST_REFRESH to 24),
        "epgRefreshHours" to (Prefs.Key.EPG_REFRESH to 6),
        "epgOffsetMin" to (Prefs.Key.EPG_OFFSET to -90),
        "catchupOffsetMin" to (Prefs.Key.CATCHUP_OFFSET to -60),
        "audioLanguage" to (Prefs.Key.AUDIO_LANGUAGE to "en"),
        "subtitleLanguage" to (Prefs.Key.SUBTITLE_LANGUAGE to "pt"),
        "epgInList" to (Prefs.Key.EPG_IN_LIST to false),
        "recordPaddingBeforeMin" to (Prefs.Key.RECORD_PADDING_BEFORE to 5),
        "recordPaddingAfterMin" to (Prefs.Key.RECORD_PADDING_AFTER to 10),
        "pinHash" to (Prefs.Key.PIN_HASH to Prefs.hashPin("1234")),
        "lockedGroups" to (Prefs.Key.LOCKED_GROUPS to listOf("p1:9")),
        "settingsPin" to (Prefs.Key.SETTINGS_PIN to true),
    )

    @Test
    fun `every pref survives a save and a reload`() {
        val fields = Prefs::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals("Campo de Prefs sem exemplo neste teste (junta-o a samples)", fields.sorted(), samples.keys.sorted())
        val keys = Prefs.Key::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }.toSet()
        assertEquals("Chaves de Prefs.Key sem campo", keys.sorted(), samples.values.map { it.first }.sorted())

        val defaults = Prefs()
        val settings = JsonObject()
        for ((field, sample) in samples) {
            val (key, value) = sample
            val alone = JsonObject().also { Prefs.put(it, key, value) }
            val read = fieldOf(Prefs.from(alone), field)
            assertEquals("$field ($key)", asStored(value, read), read)
            assertTrue("$field: o exemplo tem de ser diferente do padrão", fieldOf(defaults, field) != asStored(value, read))
            Prefs.put(settings, key, value)
        }
        // Todos juntos (e a voltar ao padrão com null).
        val all = Prefs.from(settings)
        samples.forEach { (field, sample) -> fieldOf(all, field).let { assertEquals(field, asStored(sample.second, it), it) } }
        samples.values.forEach { (key, _) -> Prefs.put(settings, key, null) }
        assertEquals(defaults, Prefs.from(settings))
    }

    /** As formas dos valores no JSON, que o site e a Apple TV leem: listas, mapas de listas e mapas de texto. */
    @Test
    fun `pref values keep their json shape`() {
        val settings = JsonObject()
        samples.values.forEach { (key, value) -> Prefs.put(settings, key, value) }
        assertJsonEquals(
            """
            {"hidden_groups": ["p1:7", "p1:Outros"], "group_order": {"p1": ["3", "1"]}, "channel_order": {"p1:1": ["102", "101"]},
             "channel_logos": {"p1:101": "https://logos.example.com/rtp1.png", "p1:102": ""},
             "extra_epg": {"p1": "https://epg.example.com/extra.xml"}, "reconnect_seconds": 10, "afr": true,
             "parental_pin_sha256": "${Prefs.hashPin("1234")}", "epg_offset_min": -90, "language": "pt-BR"}
            """,
            JsonObject().apply {
                listOf("hidden_groups", "group_order", "channel_order", "channel_logos", "extra_epg", "reconnect_seconds", "afr", "parental_pin_sha256", "epg_offset_min", "language")
                    .forEach { add(it, settings.get(it)) }
            }.toString(),
        )
        // O sal do PIN não pode mudar: os PINs já gravados nas contas deixavam de entrar.
        assertEquals(sha256("i-tv:1234"), Prefs.hashPin("1234"))
    }

    // ---- Chaves partilhadas com o site e a Apple TV ----

    /** Os mesmos valores do CoreTests.m3uKeys da Apple TV. */
    @Test
    fun `m3u channel key from the url is the same as on apple tv`() {
        assertEquals("u1f19d54b353a", M3uParser.urlKey("http://example.com/live/1.ts"))
        assertEquals("udf750051f35d", M3uParser.urlKey("http://a.b/c?x=1"))
        // O grupo dos canais sem grupo: o id vai para hidden_groups/group_order da conta e para o site.
        assertEquals("Outros", PlaylistContent.OTHERS)
    }

    /** O hash do índice é o SHA-1 deste texto (uma linha por grupo e por canal), igual na Apple TV. */
    @Test
    fun `channel index hash comes from the same canonical text as on apple tv`() {
        val content = PlaylistContent(
            Playlist("p1", PlaylistType.M3U, "Demo", url = "https://example.com/list.m3u"),
            listOf(Category("1", "News"), Category("Outros", "Outros")),
            listOf(
                Channel(0, "101", 1, "RTP 1", "https://x/rtp1.png", "1"),
                Channel(1, "u0a1b2c", 2, "Canal ç", "logo.png", "Outros"),
            ),
        )
        val canonical = "g\t1\tNews\n" + "g\tOutros\tOutros\n" +
            "c\t101\tRTP 1\t1\t1\thttps://x/rtp1.png\n" + "c\tu0a1b2c\tCanal ç\tOutros\t2\t\n"
        val sha1 = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(sha1, ChannelIndex.of(content)!!.hash)
        assertEquals("ea9f8cc27edc2e37f03ba70062966b66b503be16", sha1)
    }

    // ---- Ajudas ----

    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("formats/$name")!!.readText()

    private fun writeGz(name: String, text: String) {
        File(dir, "cache").mkdirs()
        GZIPOutputStream(File(dir, "cache/$name").outputStream()).bufferedWriter().use { it.write(text) }
    }

    private fun readGz(name: String): String {
        val file = File(dir, "cache/$name")
        assertNotNull("Falta cache/$name", file.takeIf { it.exists() })
        return GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
    }

    /** Iguais como JSON (a ordem das chaves não conta). */
    private fun assertJsonEquals(expected: String, actual: String) {
        val e: JsonElement = JsonParser.parseString(expected)
        val a: JsonElement = JsonParser.parseString(actual)
        assertEquals(e, a)
    }

    /** O exemplo como o [Prefs] o guarda: o AppViewModel grava os conjuntos como listas. */
    private fun asStored(value: Any, field: Any?): Any = if (field is Set<*> && value is List<*>) value.toSet() else value

    private fun fieldOf(prefs: Prefs, name: String): Any? =
        Prefs::class.java.getDeclaredField(name).apply { isAccessible = true }.get(prefs)

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
