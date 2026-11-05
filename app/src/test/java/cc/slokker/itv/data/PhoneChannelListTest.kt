package cc.slokker.itv.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneChannelListTest {
    private val playlist = Playlist("p1", PlaylistType.M3U, "A minha lista", url = "https://example.com/list.m3u")
    private val news = Category("news", "Notícias")
    private val sport = Category("sport", "Desporto")
    private val adult = Category("adult", "Adultos")
    private val kids = Category("kids", "Infantis")

    private val channels = listOf(
        Channel(0, "n1", 1, "RTP 1", "https://x/rtp1.png", "news"),
        Channel(1, "n2", 2, "SIC <Notícias>", null, "news"),
        Channel(2, "s1", 3, "Sport TV", "logo.png", "sport"),
        Channel(3, "a1", 4, "Privado", "https://x/a1.png", "adult"),
        Channel(4, "k1", 5, "Panda", "https://x/panda.png", "kids"),
        Channel(5, "k2", 6, "Canal escondido", null, "kids"),
        // A mesma chave noutro grupo: conta só o primeiro canal com ela.
        Channel(6, "n1", 7, "RTP 1 (cópia)", null, "kids"),
    )
    private val content = PlaylistContent.build(playlist, listOf(news, sport, adult, kids), channels)

    private val prefs = Prefs(
        hiddenGroups = setOf("p1:sport"),
        hiddenChannels = setOf("p1:k2"),
        pinHash = "0".repeat(64),
        lockedGroups = setOf("p1:adult"),
    )

    /** Os grupos como o ecrã os dá (aqui também os escondidos e bloqueados: a lista tira-os na mesma). */
    private fun list(
        prefs: Prefs = this.prefs,
        current: String? = "k1",
        order: List<Category> = listOf(kids, news, sport, adult),
        logos: Map<String, String> = emptyMap(),
    ) = PhoneChannelList(
        playlist,
        order.map { it to content.byCategory[it.id].orEmpty() },
        prefs,
        favorites = setOf("n2", "a1"),
        current = current,
        logos = logos,
        byKey = content.byKey,
    )

    private fun parse(bytes: ByteArray): JsonObject = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject

    @Test
    fun hiddenAndLockedGroupsAreLeftOut() {
        val json = parse(list().json())
        assertEquals("p1", json.getAsJsonObject("playlist").get("id").asString)
        assertEquals("A minha lista", json.getAsJsonObject("playlist").get("name").asString)
        assertEquals("k1", json.get("current").asString)
        // Pela ordem do ecrã, sem o escondido nem o bloqueado.
        assertEquals(listOf("kids", "news"), json.getAsJsonArray("groups").map { it.asJsonObject.get("id").asString })
        assertEquals("Infantis", json.getAsJsonArray("groups")[0].asJsonObject.get("name").asString)
        val items = json.getAsJsonArray("channels").map { it.asJsonObject }
        // Sem o canal escondido nem a cópia da chave n1 (aparece uma vez, no seu grupo).
        assertEquals(listOf("k1", "n1", "n2"), items.map { it.get("key").asString })
        assertEquals(listOf("kids", "news", "news"), items.map { it.get("group").asString })
        assertEquals(listOf(5, 1, 2), items.map { it.get("num").asInt })
        assertEquals("SIC <Notícias>", items[2].get("name").asString)
        assertEquals(listOf(true, true, false), items.map { it.get("logo").asBoolean })
        assertEquals(listOf(false, false, true), items.map { it.get("favorite").asBoolean })
        assertFalse(json.has("truncated"))
    }

    @Test
    fun lockedStaysOutEvenWithoutPinCheck() {
        // O bloqueio conta tal como está nas definições: nem um canal do grupo nem o canal a ver.
        val json = parse(list(current = "a1", prefs = prefs.copy(hiddenGroups = emptySet())).json())
        assertEquals(listOf("kids", "news", "sport"), json.getAsJsonArray("groups").map { it.asJsonObject.get("id").asString })
        assertTrue(json.get("current").isJsonNull)
        assertFalse(json.getAsJsonArray("channels").any { it.asJsonObject.get("key").asString == "a1" })
        assertFalse(PhoneChannelList.allowed(prefs, "p1", channels[3]))
        assertFalse(PhoneChannelList.allowed(prefs, "p1", channels[2]))
        assertFalse(PhoneChannelList.allowed(prefs, "p1", channels[5]))
        assertTrue(PhoneChannelList.allowed(prefs, "p1", channels[4]))
    }

    @Test
    fun noCurrentChannel() {
        assertTrue(parse(list(current = null).json()).get("current").isJsonNull)
        assertTrue(parse(list(current = "gone").json()).get("current").isJsonNull)
    }

    @Test
    fun logosFollowTheScreen() {
        // Um logótipo escolhido (http) substitui o da lista; um caminho relativo não se consegue carregar.
        assertEquals("https://y/sport.png", PhoneChannelList.logoUrl(channels[2], mapOf("s1" to "https://y/sport.png")))
        assertNull(PhoneChannelList.logoUrl(channels[2], emptyMap()))
        assertNull(PhoneChannelList.logoUrl(channels[1], emptyMap()))
        assertNull(PhoneChannelList.logoUrl(channels[0], mapOf("n1" to "file:///sdcard/x.png")))
        val json = parse(list(logos = mapOf("n2" to "http://y/sic.png")).json())
        assertTrue(json.getAsJsonArray("channels")[2].asJsonObject.get("logo").asBoolean)
    }

    @Test
    fun cutAtTheCap() {
        val full = list().json()
        val cut = list().json(maxBytes = full.size - 10)
        assertTrue(cut.size <= full.size - 10)
        val json = parse(cut)
        assertTrue(json.get("truncated").asBoolean)
        assertEquals(2, json.getAsJsonArray("channels").size())
        // Nem um canal cabe: a lista vai vazia, mas continua a ser JSON.
        val empty = parse(list().json(maxBytes = 10))
        assertEquals(0, empty.getAsJsonArray("channels").size())
        assertTrue(empty.get("truncated").asBoolean)
    }

    @Test
    fun keysArePercentDecoded() {
        assertEquals("k1", PhoneChannelList.decodeKey("k1"))
        assertEquals("a b/c+d", PhoneChannelList.decodeKey("a%20b%2Fc+d"))
        assertEquals("canal ç", PhoneChannelList.decodeKey("canal%20%C3%A7"))
        // Bytes UTF-8 sem codificar (o pedido é lido byte a byte).
        assertEquals("ç", PhoneChannelList.decodeKey("Ã§"))
        for (bad in listOf("", "%", "%2", "%zz", "%+1", "%C3", "%FF", "ć")) assertNull(bad, PhoneChannelList.decodeKey(bad))
    }
}
