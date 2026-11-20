package cc.slokker.itv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendToTvTest {
    private fun entry(key: String, name: String) = ChannelMatch.Entry(key, name, null)

    @Test
    fun bodyRoundTrip() {
        val body = SendToTv.body("p1", "k\"1", "SIC \"HD\" \\ ção", "pt", "Pixel 9")
        val r = assertNotNull(SendToTv.parse(body))
        assertEquals("p1", r.playlist)
        assertEquals("k\"1", r.channel)
        assertEquals("SIC \"HD\" \\ ção", r.name)
        assertEquals("pt", r.pro)
        assertEquals("Pixel 9", r.from)
    }

    @Test
    fun bodyCarriesOnlyIdsAndNames() {
        val body = SendToTv.body("p1", "k1", "SIC", "", "")
        assertEquals("""{"playlist":"p1","channel":"k1","name":"SIC","pro":"","from":""}""", body)
    }

    @Test
    fun parseRejectsWhatIsNotARequest() {
        assertNull(SendToTv.parse(""))
        assertNull(SendToTv.parse("[]"))
        assertNull(SendToTv.parse("\"x\""))
        assertNull(SendToTv.parse("{\"playlist\":\"p\"}"))
        assertNull(SendToTv.parse("{\"channel\":1,\"name\":true}"))
        assertNull(SendToTv.parse("{\"channel\":\"k\""))
        assertNull(SendToTv.parse("{\"channel\":\"" + "k".repeat(SendToTv.MAX_BODY_BYTES) + "\"}"))
    }

    @Test
    fun parseToleratesMissingAndUnknownFields() {
        val r = assertNotNull(SendToTv.parse("""{"name":"RTP 1","extra":{"a":[1,2]}}"""))
        assertEquals("", r.playlist)
        assertEquals("", r.channel)
        assertEquals("RTP 1", r.name)
        assertEquals("", r.from)
    }

    @Test
    fun textFromTheNetworkIsCleaned() {
        val r = assertNotNull(SendToTv.parse("""{"channel":"k","name":"A\nB","from":"  ${"x".repeat(100)}\u0007  "}"""))
        assertEquals("AB", r.name)
        assertEquals(40, r.from.length)
        assertEquals("ab", SendToTv.clean(" a\u0000b\u007f "))
    }

    @Test
    fun hello() {
        val h = assertNotNull(SendToTv.parseHello(SendToTv.hello("Sala", "0.19.1", "cc.slokker.itv")))
        assertEquals("Sala", h.name)
        assertEquals("0.19.1", h.version)
        assertEquals("cc.slokker.itv", h.pkg)
        assertNull(SendToTv.parseHello("""{"app":"Other","name":"x"}"""))
        assertNull(SendToTv.parseHello("not json"))
        // Um pacote que não é um pacote não chega ao endereço do GoatLauncher.
        assertEquals("", assertNotNull(SendToTv.parseHello("""{"app":"GoatTV","package":"a/../b"}""")).pkg)
    }

    @Test
    fun route() {
        val r = SendRequest("p1", "k1", "SIC", "", "")
        assertEquals(SendToTv.ROUTE_HERE, SendToTv.route(r, "p1", listOf("p1", "p2"), false))
        assertEquals(SendToTv.ROUTE_SWITCH, SendToTv.route(r, "p2", listOf("p1", "p2"), false))
        assertEquals(SendToTv.ROUTE_NAME, SendToTv.route(r, "p2", listOf("p2"), false))
        // Modo TV Pro na TV: nunca sai da lista única.
        assertEquals(SendToTv.ROUTE_NAME, SendToTv.route(r, "pro:pt", listOf("p1", "p2"), true))
        // A mesma tabela nos dois: a chave é a mesma.
        val pro = SendRequest("pro:pt", "sic", "SIC", "pt", "")
        assertEquals(SendToTv.ROUTE_HERE, SendToTv.route(pro, "pro:pt", listOf("p1"), true))
        assertEquals(SendToTv.ROUTE_NAME, SendToTv.route(pro, "p1", listOf("p1"), false))
        assertEquals(SendToTv.ROUTE_NAME, SendToTv.route(SendRequest("", "", "SIC", "", ""), "p1", listOf("p1"), false))
    }

    @Test
    fun byName() {
        val pool = listOf(entry("a", "PT: RTP 1 HD"), entry("b", "SIC FHD"), entry("c", "SIC"), entry("d", "TVI +1"), entry("e", "TVI"))
        assertEquals("c", SendToTv.byName("SIC", pool))
        assertEquals("c", SendToTv.byName(" sic ", pool))
        assertEquals("b", SendToTv.byName("SIC FHD", pool))
        assertEquals("a", SendToTv.byName("RTP 1", pool))
        assertEquals("e", SendToTv.byName("TVI HD", pool))
        assertEquals("d", SendToTv.byName("TVI +1 HD", pool))
        assertNull(SendToTv.byName("CNN", pool))
        assertNull(SendToTv.byName("  ", pool))
        assertNull(SendToTv.byName("SIC", emptyList()))
    }

    @Test
    fun byNameDoesNotGuessBetweenCountries() {
        val pool = listOf(entry("a", "PT: Eurosport 1"), entry("b", "ES: Eurosport 1"))
        assertNull(SendToTv.byName("Eurosport 1", pool))
        assertEquals("b", SendToTv.byName("ES: Eurosport 1 HD", pool))
    }

    @Test
    fun statusAndOutcome() {
        assertEquals(409, SendToTv.status(inFront = false, ready = false, found = false))
        assertEquals(503, SendToTv.status(inFront = true, ready = false, found = true))
        assertEquals(404, SendToTv.status(inFront = true, ready = true, found = false))
        assertEquals(200, SendToTv.status(inFront = true, ready = true, found = true))
        assertEquals("409 Conflict", SendToTv.statusLine(409))
        assertEquals("400 Bad Request", SendToTv.statusLine(400))
        assertTrue(SendToTv.retry(409))
        assertTrue(SendToTv.retry(503))
        assertFalse(SendToTv.retry(404))
        assertFalse(SendToTv.retry(0))
        assertEquals(SendToTv.PLAYING, SendToTv.outcome(200))
        assertEquals(SendToTv.MISSING, SendToTv.outcome(404))
        assertEquals(SendToTv.CLOSED, SendToTv.outcome(409))
        assertEquals(SendToTv.FAILED, SendToTv.outcome(503))
        assertEquals(SendToTv.FAILED, SendToTv.outcome(0))
        assertEquals(SendToTv.FAILED, SendToTv.outcome(403))
    }

    @Test
    fun step() {
        assertEquals(SendToTv.STEP_DONE, SendToTv.step(200, 0, launched = false, canLaunch = true))
        assertEquals(SendToTv.STEP_DONE, SendToTv.step(404, 0, launched = false, canLaunch = true))
        assertEquals(SendToTv.STEP_DONE, SendToTv.step(0, 0, launched = false, canLaunch = true))
        // Atrás de outra app: pede-se ao GoatLauncher uma vez, depois espera-se que venha para a frente.
        assertEquals(SendToTv.STEP_LAUNCH, SendToTv.step(409, 0, launched = false, canLaunch = true))
        assertEquals(SendToTv.STEP_WAIT, SendToTv.step(409, 1, launched = true, canLaunch = true))
        // Sem GoatLauncher (Apple TV): acaba logo.
        assertEquals(SendToTv.STEP_DONE, SendToTv.step(409, 0, launched = false, canLaunch = false))
        assertEquals(SendToTv.STEP_WAIT, SendToTv.step(503, 0, launched = false, canLaunch = false))
        assertEquals(SendToTv.STEP_DONE, SendToTv.step(503, SendToTv.RETRIES, launched = false, canLaunch = false))
    }

    @Test
    fun opened() {
        assertEquals("SIC HD", SendToTv.parseOpened(SendToTv.opened("SIC HD")))
        assertEquals("", SendToTv.parseOpened(SendToTv.opened("")))
        assertEquals("", SendToTv.parseOpened("not json"))
        assertEquals("/open/cc.slokker.itv", SendToTv.launcherPath("cc.slokker.itv"))
    }

    @Test
    fun lastTvComesFirst() {
        assertEquals(listOf(2, 1, 0), SendToTv.order(listOf("Sala", "Quarto", "shield"), "shield"))
        assertEquals(listOf(1, 0, 2), SendToTv.order(listOf("Sala", "Quarto", "shield"), null))
    }

    @Test
    fun contentLength() {
        fun length(vararg lines: String) = LanRequest.parse((listOf("POST /open HTTP/1.1", "Host: 10.0.0.2:6471") + lines).joinToString("\r\n"))?.contentLength
        assertEquals(42, length("Content-Length: 42"))
        assertEquals(0, length("content-length: 0"))
        assertEquals(-1, length())
        assertEquals(-1, length("Content-Length: -1"))
        assertEquals(-1, length("Content-Length: 1e3"))
        assertEquals(-1, length("Content-Length: 12345678901"))
        assertEquals(-1, length("Content-Length: 5", "Content-Length: 6"))
    }
}
