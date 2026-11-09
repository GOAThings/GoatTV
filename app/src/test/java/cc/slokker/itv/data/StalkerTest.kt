package cc.slokker.itv.data

import cc.slokker.itv.data.stalker.StalkerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class StalkerTest {
    private val mac = "00:1A:79:00:00:01"

    @Test
    fun `endpoints follow the portal address`() {
        assertEquals(listOf("http://p/stalker_portal/server/load.php"), StalkerSource.endpoints("http://p/stalker_portal/c/"))
        assertEquals(listOf("http://p/x/portal.php"), StalkerSource.endpoints("http://p/x/portal.php"))
        assertEquals("http://p:8080/portal.php", StalkerSource.endpoints("http://p:8080/c/").first())
    }

    @Test
    fun `cookies differ by portal kind`() {
        val modern = StalkerSource.cookie(mac, "T", true, "get_genres", "UTC")
        assertTrue(modern.startsWith("mac=00%3A1A%3A79")); assertTrue(modern.endsWith("; token=T"))
        assertFalse(StalkerSource.cookie(mac, "T", true, "get_profile", "UTC").contains("token"))
        val other = StalkerSource.cookie(mac, "T", false, "get_profile", "UTC")
        assertTrue(other.startsWith("mac=$mac")); assertTrue(other.endsWith("; token=T"))
    }

    @Test
    fun `commands become urls on the portal when needed`() {
        assertEquals("http://p:88/ch/1_", StalkerSource.commandUrl("ffmpeg http://localhost/ch/1_", "http://p:88/portal.php"))
        assertEquals("http://p/play/live.php?a=1", StalkerSource.commandUrl("/play/live.php?a=1", "http://p/portal.php"))
        assertEquals("http://cdn/x.m3u8?t=1", StalkerSource.commandUrl("auto http://cdn/x.m3u8?t=1", "http://p"))
        assertTrue(StalkerSource.needsLink("ffmpeg http://localhost/ch/1_"))
        assertFalse(StalkerSource.needsLink("ffmpeg http://h/play/live.php?id=1"))
        assertEquals(StreamFormat.HLS, StalkerSource.candidate("http://cdn/x.m3u8?t=1").format)
        assertEquals(StreamFormat.TS, StalkerSource.candidate("http://cdn/x").format)
    }

    @Test
    fun `identity is derived from the mac unless given`() {
        val p = Playlist(id = "s", type = PlaylistType.STALKER, name = "S", server = "http://p", username = mac)
        val id = StalkerSource.Identity.of(p)
        assertEquals(13, id.serial.length); assertEquals(64, id.deviceId.length); assertEquals(id.deviceId, id.deviceId2)
        assertEquals("ABC", StalkerSource.Identity.of(p.copy(serial = "ABC")).serial)
    }

    @Test
    fun `form normalizes the mac and needs a valid one`() {
        assertEquals(mac, PlaylistForm.normalizeMac("00-1a-79-00-00-01"))
        assertEquals(mac, PlaylistForm.normalizeMac("001a79000001"))
        assertNull(PlaylistForm.normalizeMac("00:1A"))
    }

    @Test
    fun `generated macs are valid and only fill an empty field`() {
        val generated = PlaylistForm.generateMac()
        assertEquals(generated, PlaylistForm.normalizeMac(generated))
        assertEquals("00:1A:79", generated.take(8))
        val stalker = PlaylistForm().withType(PlaylistType.STALKER, mac)
        assertEquals(mac, stalker.username)
        assertEquals("", stalker.withType(PlaylistType.M3U, mac).username)
        assertEquals("other", PlaylistForm(username = "other").withType(PlaylistType.STALKER, mac).username)
    }

    @Test
    fun `portals without genres group channels by their separators`() {
        fun ch(name: String) = Channel(0, name, 0, name, null, "")
        assertEquals("FIFA WC", StalkerSource.sectionTitle("✦●✦ FIFA WC ✦●✦"))
        assertEquals("UK", StalkerSource.sectionTitle("=== UK ==="))
        assertNull(StalkerSource.sectionTitle("PPV| PSF 01 [EVENT ONLY]"))
        assertNull(StalkerSource.sectionTitle("(( HD ))"))
        val (grouped, sections) = StalkerSource.groupBySections(listOf(ch("Info"), ch("== A =="), ch("One"), ch("== B =="), ch("Two")))
        assertEquals(listOf("A", "B"), sections.map { it.id })
        assertEquals(listOf("" to "Info", "A" to "One", "B" to "Two"), grouped.map { it.categoryId to it.name })
        assertEquals(listOf(0, 1, 2), grouped.map { it.uid })
        assertEquals(2, StalkerSource.groupBySections(listOf(ch("== A =="), ch("One"))).first.size)
    }

    @Test
    fun `reports hide macs`() {
        assertEquals("a *** b", BugReport.redact("a $mac b", emptyList()))
        assertEquals("mac=***&x=1", BugReport.redact("mac=00%3A1A%3A79%3A00%3A00%3A01&x=1", emptyList()))
    }


    @Test
    fun `a token with characters a header cannot carry is not usable`() {
        assertTrue(SafeInput.isHeaderSafe("abc-123_XYZ"))
        assertFalse(SafeInput.isHeaderSafe("abc\ndef"))
        assertFalse(SafeInput.isHeaderSafe("tokén"))
        // O que se aceita tem mesmo de passar no OkHttp.
        okhttp3.Request.Builder().url("http://example.invalid/").header("Authorization", "Bearer abc-123_XYZ").build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `okhttp refuses what the token check refuses`() {
        okhttp3.Request.Builder().url("http://example.invalid/").header("Authorization", "Bearer abc\ndef").build()
    }

    // ---- Arquivo (catch-up) ----

    private fun channelJson(vararg fields: Pair<String, Any>) = com.google.gson.JsonObject().apply {
        fields.forEach { (k, v) -> if (v is Number) addProperty(k, v) else addProperty(k, v.toString()) }
    }

    @Test
    fun `archive days read hours or days as portals send them`() {
        assertEquals(0, StalkerSource.archiveDays(channelJson("tv_archive_duration" to 168)))
        assertEquals(0, StalkerSource.archiveDays(channelJson("tv_archive" to "0", "tv_archive_duration" to 168)))
        assertEquals(7, StalkerSource.archiveDays(channelJson("tv_archive" to 1, "tv_archive_duration" to 168)))
        assertEquals(7, StalkerSource.archiveDays(channelJson("enable_tv_archive" to "1", "tv_archive_duration" to "168")))
        assertEquals(1, StalkerSource.archiveDays(channelJson("archive" to "1", "tv_archive_duration" to "24")))
        assertEquals(3, StalkerSource.archiveDays(channelJson("tv_archive" to "1", "tv_archive_duration" to "3")))
        assertEquals(3, StalkerSource.archiveDays(channelJson("tv_archive" to "1", "tv_archive_duration" to "72")))
        assertEquals(2, StalkerSource.archiveDays(channelJson("tv_archive" to "1", "tv_archive_duration" to "36")))
        // Marcado sem duração: uma semana (o padrão do Ministra).
        assertEquals(7, StalkerSource.archiveDays(channelJson("tv_archive" to "1")))
    }

    /** Um portal de mentira (no OkHttp, sem rede): responde pelo `action` e regista os pedidos. */
    private class FakePortal(private val answers: (okhttp3.HttpUrl) -> String) : okhttp3.Interceptor {
        val asked = mutableListOf<okhttp3.HttpUrl>()
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val url = chain.request().url
            asked += url
            val body = when (url.queryParameter("action")) {
                "handshake" -> """{"js":{"token":"T1","random":"r"}}"""
                "get_profile" -> """{"js":{"id":"1","status":0}}"""
                else -> answers(url)
            }
            return okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private val portal = Playlist(id = "s1", type = PlaylistType.STALKER, name = "P", server = "http://portal.example.com/stalker_portal/c/", username = mac)
    private val archived = Channel(uid = 0, key = "42", num = 1, name = "C", logo = null, categoryId = "g", streamId = "42", url = "ffrt http://localhost/ch/42", archiveDays = 7)

    @Test
    fun `archive finds the programme on the day's table and asks its recording`() = kotlinx.coroutines.runBlocking {
        val t0 = 1_794_160_800L // 18:00 UTC
        val fake = FakePortal { url ->
            when (url.queryParameter("action")) {
                "get_simple_data_table" -> if (url.queryParameter("p") == "1") {
                    """{"js":{"total_items":3,"max_page_items":2,"data":[
                        {"id":"900","start_timestamp":${t0 - 3600},"stop_timestamp":$t0,"name":"Antes","mark_archive":1},
                        {"id":"901","start_timestamp":$t0,"stop_timestamp":${t0 + 1800},"name":"Não gravado","mark_archive":0}]}}"""
                } else {
                    """{"js":{"total_items":3,"max_page_items":2,"data":[
                        {"id":"902","start_timestamp":${t0 + 1800},"stop_timestamp":${t0 + 5400},"name":"Este","mark_archive":"1"}]}}"""
                }
                "create_link" -> """{"js":{"id":"902","cmd":"ffrt http://localhost/stalker_portal/storage/rec.php?id=902&t=abc"}}"""
                else -> """{"js":[]}"""
            }
        }
        val source = StalkerSource(okhttp3.OkHttpClient.Builder().addInterceptor(fake).build())
        val wanted = (t0 + 2_000) * 1000
        val rec = source.archive(portal, archived, wanted, (t0 + 1800) * 1000, (t0 + 5400) * 1000)!!
        assertEquals((t0 + 1800) * 1000, rec.startMs)
        // Um link com localhost aponta para o próprio portal.
        assertEquals("http://portal.example.com/stalker_portal/storage/rec.php?id=902&t=abc", rec.candidate.url)
        val link = fake.asked.single { it.queryParameter("action") == "create_link" }
        assertEquals("tv_archive", link.queryParameter("type"))
        assertEquals("auto /media/902.mpg", link.queryParameter("cmd"))
        val table = fake.asked.filter { it.queryParameter("action") == "get_simple_data_table" }
        assertEquals(listOf("1", "2"), table.map { it.queryParameter("p") })
        assertTrue(table.all { it.queryParameter("ch_id") == "42" && it.queryParameter("type") == "epg" })
        // Não gravado: não há gravação.
        assertNull(source.archive(portal, archived, (t0 + 60) * 1000, t0 * 1000, (t0 + 1800) * 1000))
    }

    @Test
    fun `archive without a recording is null`() = kotlinx.coroutines.runBlocking {
        val t0 = 1_794_160_800L
        val fake = FakePortal { url ->
            when (url.queryParameter("action")) {
                "get_simple_data_table" -> """{"js":{"total_items":1,"max_page_items":10,"data":[{"id":"7","start_timestamp":$t0,"stop_timestamp":${t0 + 3600},"mark_archive":1}]}}"""
                "create_link" -> """{"js":{"error":"nothing_to_play","cmd":""}}"""
                else -> """{"js":[]}"""
            }
        }
        val source = StalkerSource(okhttp3.OkHttpClient.Builder().addInterceptor(fake).build())
        assertNull(source.archive(portal, archived, (t0 + 60) * 1000, t0 * 1000, (t0 + 3600) * 1000))
    }

    @Test
    fun `archive picks the portal's programme when its times drift from the guide`() = kotlinx.coroutines.runBlocking {
        val t0 = 1_794_160_800L
        val fake = FakePortal { url ->
            when (url.queryParameter("action")) {
                // O portal diz que o segundo programa começa um minuto depois do que diz o guia.
                "get_simple_data_table" -> """{"js":{"total_items":2,"max_page_items":10,"data":[
                    {"id":"1","start_timestamp":$t0,"stop_timestamp":${t0 + 1860},"mark_archive":1},
                    {"id":"2","start_timestamp":${t0 + 1860},"stop_timestamp":${t0 + 5400},"mark_archive":1}]}}"""
                "create_link" -> """{"js":{"cmd":"ffrt http://rec.example.com/""" + url.queryParameter("cmd")!!.substringAfter("/media/") + """"}}"""
                else -> """{"js":[]}"""
            }
        }
        val source = StalkerSource(okhttp3.OkHttpClient.Builder().addInterceptor(fake).build())
        // Do início (o guia diz t0 + 1800): a gravação do segundo, não o último minuto do primeiro.
        val fromStart = source.archive(portal, archived, (t0 + 1800) * 1000, (t0 + 1800) * 1000, (t0 + 5400) * 1000)!!
        assertEquals("http://rec.example.com/2.mpg", fromStart.candidate.url)
        assertEquals((t0 + 1860) * 1000, fromStart.startMs)
        // Num ponto a meio, o programa do portal a essa hora.
        val middle = source.archive(portal, archived, (t0 + 3000) * 1000, (t0 + 1800) * 1000, (t0 + 5400) * 1000)!!
        assertEquals("http://rec.example.com/2.mpg", middle.candidate.url)
    }
}
