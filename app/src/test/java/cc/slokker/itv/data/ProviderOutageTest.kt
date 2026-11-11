package cc.slokker.itv.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderOutageTest {
    private var now = 1_000L
    private val outage = ProviderOutage { now }

    @Test
    fun ficaEmBaixoCincoMinutos() {
        outage.markDown("a")
        assertTrue(outage.isDown("a"))
        assertFalse(outage.isDown("b"))
        now += ProviderOutage.WINDOW_MS - 1
        assertTrue(outage.isDown("a"))
        now += 1
        assertFalse(outage.isDown("a"))
    }

    @Test
    fun umCanalQueDaApagaAMarca() {
        outage.markDown("a")
        outage.clear("a")
        assertFalse(outage.isDown("a"))
    }

    @Test
    fun soOsErrosDaContaOuDoServidor() {
        listOf(401, 429, 456, 458, 500, 502, 503, 513).forEach { assertTrue("$it", ProviderOutage.providerWide(it)) }
        listOf(null, 200, 302, 403, 404, 410).forEach { assertFalse("$it", ProviderOutage.providerWide(it)) }
    }
}
