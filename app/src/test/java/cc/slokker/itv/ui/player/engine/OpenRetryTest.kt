package cc.slokker.itv.ui.player.engine

import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** As esperas entre tentativas de abrir uma stream que ainda não deu nada. */
class OpenRetryTest {
    /** Uma resposta HTTP [code] (sem Uri: no PC o Android não existe, por isso sem passar pelo construtor). */
    private fun http(code: Int): HttpDataSource.InvalidResponseCodeException {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val e = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, HttpDataSource.InvalidResponseCodeException::class.java) as HttpDataSource.InvalidResponseCodeException
        HttpDataSource.InvalidResponseCodeException::class.java.getField("responseCode").apply { isAccessible = true }.setInt(e, code)
        return e
    }

    private fun ladder(error: Throwable?) = generateSequence(1) { it + 1 }.map { OpenRetry.delayMs(error, it) }.takeWhile { it != null }.toList()

    @Test
    fun ocupadoTentaDepressaEVariasVezes() {
        for (code in listOf(403, 406, 429, 458, 503, 509)) {
            assertEquals("$code", listOf(150L, 200L, 250L, 250L, 300L, 300L, 400L, 500L, 600L), ladder(http(code)))
            assertTrue(OpenRetry.isBusy(http(code)))
        }
    }

    @Test
    fun naoExisteSoMaisUmaVez() {
        assertEquals(listOf(250L), ladder(http(404)))
        assertEquals(listOf(250L), ladder(http(410)))
        assertFalse(OpenRetry.isBusy(http(404)))
    }

    @Test
    fun osOutrosErrosComoAntesMasMaisCurtos() {
        assertEquals(listOf(0L, 500L, 1_000L), ladder(IOException("rede")))
        assertEquals(listOf(0L, 500L, 1_000L), ladder(http(500)))
        assertEquals(listOf(0L, 500L, 1_000L), ladder(null))
    }

    @Test
    fun oCodigoVemDasCausas() {
        val wrapped = IOException("anel", RuntimeException("player", http(503)))
        assertEquals(503, OpenRetry.httpCode(wrapped))
        assertTrue(OpenRetry.isBusy(wrapped))
        assertNull(OpenRetry.httpCode(IOException("sem código")))
    }
}
