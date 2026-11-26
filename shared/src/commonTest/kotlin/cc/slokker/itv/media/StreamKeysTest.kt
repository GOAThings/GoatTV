package cc.slokker.itv.media

import cc.slokker.itv.data.StreamDrm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamKeysTest {
    private val kid = "0123456789abcdef0123456789abcdef"
    private val key = "fedcba9876543210fedcba9876543210"

    @Test
    fun keysFromTheChannelAndHeaders() {
        val url = "https://test.example.com/keys-${kid.take(6)}/manifest.mpd"
        StreamKeys.remember(url, StreamDrm(StreamDrm.CLEARKEY, keys = mapOf(kid to key)), mapOf("Cookie" to "a=b"))
        val drm = StreamKeys.drmFor("$url${DashManifest.MARK}v1")
        assertEquals(StreamDrm.CLEARKEY, drm?.system)
        assertEquals(key, StreamKeys.key(kid, drm)?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        // A lista só deu uma chave: serve para um KID que não disse.
        assertEquals(16, StreamKeys.key("ffffffffffffffffffffffffffffff00", drm)?.size)
        assertEquals(mapOf("Cookie" to "a=b"), StreamKeys.headersFor(url))
        assertTrue(StreamKeys.headersFor("https://other.example.com/x.mpd").isEmpty())
    }

    @Test
    fun licenceServerAnswer() {
        val other = "11112222333344445555666677778888"
        val drm = StreamDrm(StreamDrm.CLEARKEY, licenseUrl = "https://lic.example.com/ck")
        assertEquals("https://lic.example.com/ck", StreamKeys.licenseUrl(drm))
        assertNull(StreamKeys.licenseUrl(StreamDrm(StreamDrm.WIDEVINE, licenseUrl = "https://wv.example.com")))
        assertNull(StreamKeys.key(other, drm))
        // 11112222… e 0123… em base64url.
        val n = StreamKeys.licenseResponse("""{"keys":[{"kty":"oct","kid":"EREiIjMzRERVVWZmd3eIiA","k":"ASNFZ4mrze8BI0VniavN7w"}],"type":"temporary"}""")
        assertEquals(1, n)
        assertEquals(16, StreamKeys.key(other, drm)?.size)
        assertEquals("Widevine DRM", StreamKeys.why(StreamDrm(StreamDrm.WIDEVINE)))
    }
}
