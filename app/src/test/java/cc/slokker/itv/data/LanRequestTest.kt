package cc.slokker.itv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** As mesmas regras (e os mesmos casos) da porta 6470 do GoatLauncher. */
class LanRequestTest {
    private fun request(vararg lines: String) =
        LanRequest.read((lines.joinToString("\r\n") + "\r\n\r\n").byteInputStream())

    @Test
    fun acceptsWhatGoatRemoteSends() {
        val android = request("POST /play/k%201 HTTP/1.1", "User-Agent: Dalvik/2.1.0", "Host: 192.168.1.20:6471", "Connection: Keep-Alive", "Content-Length: 0")
        assertTrue(android!!.allowed)
        assertEquals("POST", android.method)
        assertEquals("/play/k%201", android.path)
        assertTrue(request("GET /channels HTTP/1.1", "Host: 192.168.1.20", "Accept: */*")!!.allowed)
        assertTrue(request("GET /channels HTTP/1.1", "host: [fe80::1]:6471")!!.allowed)
    }

    @Test
    fun refusesOriginAndForeignHost() {
        assertFalse(request("POST /play/1 HTTP/1.1", "Host: 192.168.1.20:6471", "Origin: https://site.example")!!.allowed)
        assertFalse(request("POST /play/1 HTTP/1.1", "Host: 192.168.1.20:6471", "ORIGIN: null")!!.allowed)
        assertFalse(request("GET /channels HTTP/1.1", "Host: site.example:6471")!!.allowed)
        assertFalse(request("GET /channels HTTP/1.1")!!.allowed)
        assertFalse(request("GET /channels HTTP/1.1", "Host: 192.168.1.20", "Host: site.example")!!.allowed)
    }

    @Test
    fun hostMustBeAnIpLiteral() {
        for (host in listOf(
            "10.0.0.1", "10.0.0.1:6471", "255.255.255.255:65535",
            "[::1]", "[::]", "[fe80::1]:6471", "[fd12:3456::abcd]", "[fe80::1%25wlan0]:6471", "[fe80::1%wlan0]",
            "[1:2:3:4:5:6:7:8]", "[::ffff:192.168.1.20]:6471", "[1:2:3:4:5:6:1.2.3.4]",
        )) assertTrue(host, LanRequest.isIpHost(host))
        for (host in listOf(
            "", "tv", "tv.local", "tv.local:6471", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.4.site.example", "+1.2.3.4",
            "1.2.3.4:", "1.2.3.4:x", "1.2.3.4:65536", "::1", "[", "[]", "[::1", "[::1]x", "[::1]:", "[site.example]",
            "[1:2:3]", "[1:2:3:4:5:6:7:8:9]", "[:::]", "[1::2::3]", "[12345::]", "[g::]", "[fe80::1%]", "[1.2.3.4]",
        )) assertFalse(host, LanRequest.isIpHost(host))
        for (host in listOf("shield", "shield.local", "SHIELD.lan:6471", "tv.home.arpa", "tv.internal", "box.home")) {
            assertTrue(host, LanRequest.isHomeName(host))
        }
        for (host in listOf("example.com", "evil.local.example.com", "a:b:c", "", "shield:port", "tv_1", "tv.local:99999")) {
            assertFalse(host, LanRequest.isHomeName(host))
        }
        assertTrue(request("GET /channels HTTP/1.1", "Host: shield.local:6471")!!.allowed)
    }

    @Test
    fun readsAtMostTheLimit() {
        var read = 0
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                read++
                return 'a'.code
            }
        }
        assertEquals(null, LanRequest.read(endless))
        assertEquals(LanRequest.MAX_HEAD_BYTES, read)
    }

    @Test
    fun incompleteOrMalformedRequestsAreNull() {
        assertEquals(null, LanRequest.read("GET /channels HTTP/1.1\r\nHost: 10.0.0.1\r\n".byteInputStream()))
        assertEquals(null, LanRequest.read("".byteInputStream()))
        assertEquals(null, LanRequest.read("\r\n".byteInputStream()))
        assertEquals(null, LanRequest.read("GET\r\n\r\n".byteInputStream()))
    }
}
