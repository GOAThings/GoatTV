package cc.slokker.itv.data.m3u

import cc.slokker.itv.data.StreamDrm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uStreamPropsTest {
    private fun parse(text: String) = M3uParser.parse(M3uParser.lines(text.trimIndent())).channels

    private val kid = "0123456789abcdef0123456789abcdef"
    private val key = "fedcba9876543210fedcba9876543210"

    @Test
    fun clearKeyBeforeExtinf() {
        val c = parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=${kid.uppercase()}:$key
            #EXTINF:-1 tvg-id="a" group-title="News",Star News
            https://cdn.example.com/live/index.mpd
            #EXTINF:-1,Next
            http://x/2.ts
            """,
        )
        assertEquals(StreamDrm(StreamDrm.CLEARKEY, keys = mapOf(kid to key)), c[0].drm)
        assertEquals("https://cdn.example.com/live/index.mpd", c[0].url)
        // As linhas de um canal não passam para o seguinte.
        assertNull(c[1].drm)
    }

    @Test
    fun keysWithoutTypeAndSeveralPairs() {
        val k2 = "11111111111111111111111111111111"
        val c = parse(
            """
            #EXTINF:-1,A
            #KODIPROP:inputstream.adaptive.license_key=$kid:$key,$k2:$k2
            http://x/a.mpd
            """,
        )
        assertEquals(mapOf(kid to key, k2 to k2), c[0].drm?.keys)
        assertEquals(StreamDrm.CLEARKEY, c[0].drm?.system)
    }

    @Test
    fun jsonKeysInBase64Url() {
        // kid 0123…ef e key fedc…10 em base64url sem "=".
        val c = parse(
            """
            #EXTINF:-1,A
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key={"keys":[{"kty":"oct","kid":"ASNFZ4mrze8BI0VniavN7w","k":"_ty6mHZUMhD-3LqYdlQyEA"}],"type":"temporary"}
            http://x/a.mpd
            """,
        )
        assertEquals(mapOf(kid to key), c[0].drm?.keys)
    }

    @Test
    fun widevineServerWithHeaders() {
        val c = parse(
            """
            #EXTINF:-1,A
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example.com/wv?id=1|Content-Type=application%2Foctet-stream&X-Token=abc
            http://x/a.mpd
            """,
        )
        assertEquals(
            StreamDrm(StreamDrm.WIDEVINE, licenseUrl = "https://lic.example.com/wv?id=1", licenseHeaders = mapOf("Content-Type" to "application/octet-stream", "X-Token" to "abc")),
            c[0].drm,
        )
    }

    @Test
    fun clearKeyLicenseServer() {
        val c = parse(
            """
            #EXTINF:-1,A
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=https://keys.example.com/ck
            http://x/a.mpd
            """,
        )
        assertEquals(StreamDrm(StreamDrm.CLEARKEY, licenseUrl = "https://keys.example.com/ck"), c[0].drm)
    }

    @Test
    fun modernAndLegacyDrmProps() {
        val c = parse(
            """
            #EXTINF:-1,A
            #KODIPROP:inputstream.adaptive.drm={"org.w3.clearkey":{"license":{"keyids":{"$kid":"$key"}}}}
            http://x/a.mpd
            #EXTINF:-1,B
            #KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|$kid:$key
            http://x/b.mpd
            """,
        )
        assertEquals(mapOf(kid to key), c[0].drm?.keys)
        assertEquals(mapOf(kid to key), c[1].drm?.keys)
    }

    @Test
    fun headersFromExthttpPipeAndVlcopt() {
        val c = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",A
            #EXTHTTP:{"cookie":"__hdnea__=st=1~exp=2~hmac=ab","User-Agent":"plaYtv/7.1.3"}
            https://jio.example.com/bpk-tv/A/index.mpd|Referer=https%3A%2F%2Fsite.example.com%2F&Origin=https://site.example.com
            #EXTVLCOPT:http-user-agent=Agent B
            #EXTINF:-1,B
            http://x/b.m3u8
            """,
        )
        assertEquals("https://jio.example.com/bpk-tv/A/index.mpd", c[0].url)
        assertEquals("plaYtv/7.1.3", c[0].userAgent)
        assertEquals("https://site.example.com/", c[0].referrer)
        assertEquals(mapOf("Cookie" to "__hdnea__=st=1~exp=2~hmac=ab", "Origin" to "https://site.example.com"), c[0].headers)
        assertNull(c[0].drm)
        // Um EXTVLCOPT antes do EXTINF conta para esse canal.
        assertEquals("Agent B", c[1].userAgent)
        assertNull(c[1].headers)
    }

    @Test
    fun keyStaysTheSameWithPipe() {
        // O channelKey (favoritos) continua a ser o do URL inteiro, como antes.
        val url = "http://x/a.m3u8|User-Agent=Foo"
        val c = parse("#EXTINF:-1,A\n$url")
        assertEquals(M3uParser.urlKey(url), c[0].key)
        assertEquals("http://x/a.m3u8", c[0].url)
        assertEquals("Foo", c[0].userAgent)
    }

    @Test
    fun pipeThatIsNotHeadersStays() {
        val c = parse("#EXTINF:-1,A\nhttp://x/a.m3u8?a=1|2")
        assertEquals("http://x/a.m3u8?a=1|2", c[0].url)
    }

    @Test
    fun licenseRequestAndResponse() {
        assertEquals("""{"kids":["ASNFZ4mrze8BI0VniavN7w"],"type":"temporary"}""", M3uStreamProps.licenseRequest(listOf(kid)))
        assertEquals(mapOf(kid to key), M3uStreamProps.licenseKeys("""{"keys":[{"kty":"oct","kid":"ASNFZ4mrze8BI0VniavN7w","k":"_ty6mHZUMhD-3LqYdlQyEA"}]}"""))
    }
}
