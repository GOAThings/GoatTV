package cc.slokker.itv.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** As regras das tentativas, as mesmas no Android e na Apple. */
class PlaybackRulesTest {
    private fun step(
        live: Boolean = true, started: Boolean = false, wasPlaying: Boolean = false, onBackup: Boolean = false,
        hasNextFormat: Boolean = false, failoverAllowed: Boolean = false, reconnect: Boolean = true, noAnswer: Boolean = false,
    ) = PlaybackRules.nextStep(AttemptFailure(live, started, wasPlaying, onBackup, hasNextFormat, failoverAllowed, reconnect, noAnswer))

    @Test
    fun semRespostaVaiJaAosFailovers() {
        // Mesmo com outro formato por tentar, e também a voltar a ligar depois de cair.
        assertEquals(NextStep.FAILOVER, step(noAnswer = true, hasNextFormat = true, failoverAllowed = true))
        assertEquals(NextStep.FAILOVER, step(noAnswer = true, wasPlaying = true, failoverAllowed = true))
        // Num failover: o seguinte.
        assertEquals(NextStep.NEXT_FAILOVER, step(noAnswer = true, onBackup = true, hasNextFormat = true))
        // Sem failovers: como sempre, o outro formato.
        assertEquals(NextStep.NEXT_FORMAT, step(noAnswer = true, hasNextFormat = true))
    }

    @Test
    fun antesDeDarTentaOOutroFormato() {
        assertEquals(NextStep.NEXT_FORMAT, step(hasNextFormat = true, failoverAllowed = true))
        // Já a voltar a ligar (deu antes), a tentativa desta volta ainda não deu: o outro formato também.
        assertEquals(NextStep.NEXT_FORMAT, step(wasPlaying = true, hasNextFormat = true))
        // A dar, uma quebra não passa ao outro formato: volta a ligar.
        assertEquals(NextStep.RECONNECT, step(started = true, wasPlaying = true, hasNextFormat = true))
    }

    @Test
    fun depoisDosFormatosOsFailoversEDepoisOErro() {
        assertEquals(NextStep.FAILOVER, step(failoverAllowed = true))
        assertEquals(NextStep.GIVE_UP, step())
        assertEquals(NextStep.NEXT_FAILOVER, step(onBackup = true))
        // O catch-up não tem failovers.
        assertEquals(NextStep.GIVE_UP, step(live = false, failoverAllowed = true))
        // Caiu a meio sem "voltar a ligar": os failovers, senão o erro.
        assertEquals(NextStep.FAILOVER, step(started = true, wasPlaying = true, reconnect = false, failoverAllowed = true))
        assertEquals(NextStep.GIVE_UP, step(started = true, wasPlaying = true, reconnect = false))
    }

    @Test
    fun tempoParaComecarADar() {
        assertEquals(8_000L, PlaybackRules.openTimeoutMs(largeBuffer = false, slowEngine = false))
        assertEquals(10_000L, PlaybackRules.openTimeoutMs(largeBuffer = true, slowEngine = false))
        assertEquals(10_000L, PlaybackRules.openTimeoutMs(largeBuffer = false, slowEngine = true))
        assertEquals(12_000L, PlaybackRules.openTimeoutMs(largeBuffer = true, slowEngine = true))
    }

    @Test
    fun aSegundaParagemEmPoucoTempoVaiAosFailovers() {
        val c = StallCounter()
        assertFalse(c.stalled(0, failoverAllowed = true))
        assertTrue(c.stalled(60_000, failoverAllowed = true))
        // Recomeça a contar.
        assertFalse(c.stalled(70_000, failoverAllowed = true))
        // Fora da janela não conta.
        assertFalse(c.stalled(70_000 + PlaybackRules.STALL_WINDOW_MS, failoverAllowed = true))
        // Sem failovers: volta sempre a ligar, e as paragens continuam a contar.
        val d = StallCounter()
        assertFalse(d.stalled(0, failoverAllowed = false))
        assertFalse(d.stalled(1_000, failoverAllowed = false))
        assertEquals(2, d.count)
    }

    @Test
    fun esperasAoAbrir() {
        assertEquals(listOf(250L, 500L, 1_000L, 1_500L), (1..5).mapNotNull { OpenRetry.delayMs(503, it) })
        assertEquals(listOf(2_000L, 4_000L), (1..3).mapNotNull { OpenRetry.delayMs(429, it) })
        assertEquals(listOf(250L), (1..2).mapNotNull { OpenRetry.delayMs(404, it) })
        assertEquals(listOf(0L, 500L, 1_000L), (1..4).mapNotNull { OpenRetry.delayMs(null, it) })
        assertNull(OpenRetry.delayMs(null, 0))
        assertTrue(OpenRetry.isRefusal(429))
        assertFalse(OpenRetry.isBusy(429))
    }

    @Test
    fun atrasoAoMudarDeCanal() {
        assertEquals(0L, PlaybackRules.zapDelayMs(-1, PlaybackRules.ZAP_AUTO))
        assertEquals(PlaybackRules.ZAP_SETTLE_MS, PlaybackRules.zapDelayMs(200, PlaybackRules.ZAP_AUTO))
        assertEquals(0L, PlaybackRules.zapDelayMs(PlaybackRules.ZAP_FLIP_WINDOW_MS, PlaybackRules.ZAP_AUTO))
        assertEquals(0L, PlaybackRules.zapDelayMs(100, PlaybackRules.ZAP_OFF))
        assertEquals(1_000L, PlaybackRules.zapDelayMs(-1, 1_000))
    }
}
