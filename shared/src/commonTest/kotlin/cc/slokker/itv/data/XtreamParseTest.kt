package cc.slokker.itv.data

import cc.slokker.itv.data.xtream.XtreamParse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XtreamParseTest {
    private fun streams(json: String) = XtreamParse.streamsText(json) { "Canal $it" }

    @Test
    fun streamsWithMixedTypes() {
        val list = streams(
            """[{"num":3,"name":" RTP 1 ","stream_id":101,"stream_icon":"http://l/1.png","category_id":"7","epg_channel_id":" rtp1.pt ","tv_archive":1,"tv_archive_duration":"3","direct_source":{"a":[1,"]"]}},
               {"stream_id":"","name":"sem id"}, 42, {"stream_id":"102","name":"","stream_icon":"  ","num":null,"category_id":null}]""",
        )
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("101", a.key)
        assertEquals(3, a.num)
        assertEquals("RTP 1", a.name)
        assertEquals("http://l/1.png", a.logo)
        assertEquals("rtp1.pt", a.epgId)
        assertEquals(3, a.archiveDays)
        val b = list[1]
        assertEquals("Canal 102", b.name)
        assertNull(b.logo)
        assertEquals("", b.categoryId)
        // A posição na lista (o uid) conta também os que ficaram de fora.
        assertEquals(4, b.num)
        assertEquals(3, b.uid)
    }

    @Test
    fun providerNumbersOutOfRange() {
        for (num in listOf("\"1e300\"", "\"nan\"", "\"inf\"", "1e300", "\"9223372036854775807\"")) {
            assertEquals(1, streams("""[{"stream_id":"1","num":$num}]""").first().num, num)
        }
        assertEquals(0, streams("""[{"stream_id":"1","tv_archive_duration":"1e19"}]""").first().archiveDays)
    }

    @Test
    fun emptyAndBad() {
        for (empty in listOf("null", "{}", "[]", " [ ] ")) assertTrue(streams(empty).isEmpty(), empty)
        for (bad in listOf("", "<html>", "{\"user_info\":{}}", "[{\"stream_id\":\"1\"", "[{\"a\" 1}]")) {
            assertFailsWith<BadJson>(bad) { streams(bad) }
        }
    }

    @Test
    fun escapes() {
        val list = streams("""[{"stream_id":"1","name":"A\"B\\Cç\n"}]""")
        assertEquals("A\"B\\Cç", list.first().name)
    }

    @Test
    fun categories() {
        val list = XtreamParse.categoriesText("""[{"category_id":5,"category_name":" Desporto "},{"category_id":"6"},{"category_name":"x"}]""")
        assertEquals(listOf(Category("5", "Desporto"), Category("6", "6")), list)
    }

    @Test
    fun login() {
        val info = XtreamParse.login(
            """{"user_info":{"auth":1,"status":"Active","active_cons":"2","max_connections":3,"allowed_output_formats":["M3U8","ts",""]},"server_info":{"timezone":"Europe/Lisbon"}}""",
        )!!
        assertEquals("1", info.auth)
        assertEquals(2, info.activeConnections)
        assertEquals(3, info.maxConnections)
        assertEquals(setOf("m3u8", "ts"), info.allowedFormats)
        assertEquals("Europe/Lisbon", info.timezone)
        assertEquals(setOf("ts", "rtmp"), XtreamParse.login("""{"user_info":{"allowed_output_formats":"ts, RTMP"}}""")!!.allowedFormats)
        assertNull(XtreamParse.login("<html>"))
        assertNull(XtreamParse.login("""{"server_info":{}}"""))
    }

    @Test
    fun shortEpg() {
        val list = XtreamParse.shortEpg(
            """{"epg_listings":[{"title":"Tm90w61jaWFz","start_timestamp":"20","stop_timestamp":"30"},{"title":"","start_timestamp":"10","stop_timestamp":"20"},{"start_timestamp":"9223372036854775807","stop_timestamp":"1"}]}""",
        )
        assertEquals(listOf(EpgEntry("", 10_000, 20_000), EpgEntry("Notícias", 20_000, 30_000)), list)
        assertEquals("Ola", InputRules.base64Text("T2xh"))
        assertNull(InputRules.base64Text("!!!"))
    }
}
