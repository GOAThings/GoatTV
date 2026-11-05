package cc.slokker.itv.data

import cc.slokker.itv.data.api.ServerError
import cc.slokker.itv.data.api.epgErrorText
import cc.slokker.itv.data.api.parseServerError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Os erros do servidor da conta (estilo PocketBase) e o texto que se mostra nos do guia. */
class ServerErrorTest {
    private fun text(status: Int, body: String?) = epgErrorText(status, parseServerError(body), "TOO_MANY", "FAILED")

    @Test
    fun le() {
        assertEquals(
            ServerError("mapping_limit", "At most 5 channels can use the same guide channel."),
            parseServerError("""{"status":400,"code":"mapping_limit","message":"At most 5 channels can use the same guide channel.","data":{}}"""),
        )
        assertEquals(ServerError("new_country_limit", null), parseServerError("""{"data":{"code":"new_country_limit"},"message":"  "}"""))
        assertNull(parseServerError(null))
        assertNull(parseServerError(""))
        assertNull(parseServerError("<html>502</html>"))
        assertNull(parseServerError("[1,2]"))
    }

    @Test
    fun limitesMostramAFraseDoServidor() {
        assertEquals("Too many.", text(400, """{"code":"mapping_limit","message":"Too many."}"""))
        assertEquals("Wait a bit.", text(429, """{"code":"new_country_limit","message":"Wait a bit."}"""))
    }

    @Test
    fun limitesSemFrase() {
        assertEquals("TOO_MANY", text(429, """{"code":"new_country_limit"}"""))
        assertEquals("FAILED", text(400, """{"code":"mapping_limit"}"""))
    }

    @Test
    fun outrosErrosFicamComoEstavam() {
        assertEquals("FAILED", text(500, """{"code":"other","message":"Something went wrong."}"""))
        assertEquals("FAILED", text(429, null))
        assertEquals("FAILED", text(404, "not json"))
    }
}
