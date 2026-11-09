package cc.slokker.itv.ui.player.engine

import cc.slokker.itv.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

/** O atraso ao mudar de canal: uma tecla abre já; a passar canais, só onde se parar; ou sempre um tempo fixo. */
class ZapPacerTest {
    private val auto = Prefs.ZapDelay.AUTO

    @Test
    fun umaTeclaAbreJa() {
        val p = ZapPacer()
        assertEquals(0L, p.delayFor(10_000, auto))
        // Muito depois: outra vez logo.
        assertEquals(0L, p.delayFor(12_000, auto))
    }

    @Test
    fun aPassarCanaisSoAbreQuandoSePara() {
        val p = ZapPacer()
        assertEquals(0L, p.delayFor(0, auto))
        // Cada tecla a menos de meio segundo da anterior espera; a espera recomeça a cada uma (quem chama cancela).
        assertEquals(ZapPacer.SETTLE_MS, p.delayFor(200, auto))
        assertEquals(ZapPacer.SETTLE_MS, p.delayFor(450, auto))
        assertEquals(ZapPacer.SETTLE_MS, p.delayFor(900, auto))
        // Parou um bocado: a seguinte volta a abrir já.
        assertEquals(0L, p.delayFor(900 + ZapPacer.FLIP_WINDOW_MS, auto))
    }

    @Test
    fun desligadoNuncaEspera() {
        val p = ZapPacer()
        listOf(0L, 50L, 100L).forEach { assertEquals(0L, p.delayFor(it, Prefs.ZapDelay.OFF)) }
    }

    @Test
    fun tempoFixoEsperaSempre() {
        val p = ZapPacer()
        assertEquals(1_000L, p.delayFor(0, 1_000))
        assertEquals(2_000L, p.delayFor(60_000, 2_000))
    }
}
