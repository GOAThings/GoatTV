package cc.slokker.itv.playback

import cc.slokker.itv.data.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveBufferTest {
    private val minute = 60_000L

    /** [b] a dar de [from] até [to] (de 250 em 250 ms) com [leadMs] à frente. */
    private fun play(b: AdaptiveBuffer, from: Long, to: Long, leadMs: Long, dips: Boolean = true) {
        var t = from
        while (t <= to) {
            b.onPlaying(t, leadMs, dips)
            t += 250
        }
    }

    @Test fun startsAtNormalForAnUnknownProvider() {
        val b = AdaptiveBuffer()
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        assertEquals(2_000L, b.targetMs())
    }

    @Test fun eachStallGrowsOneStepUpToLarge() {
        val b = AdaptiveBuffer(AdaptiveBuffer.LIVE)
        assertEquals(500L, b.targetMs())
        assertTrue(b.onStall(1_000))
        assertEquals(AdaptiveBuffer.SMALL, b.level)
        assertTrue(b.onStall(2_000))
        assertTrue(b.onStall(3_000))
        assertEquals(AdaptiveBuffer.LARGE, b.level)
        assertEquals(4_000L, b.targetMs())
        // Nunca acima do Grande.
        assertFalse(b.onStall(4_000))
        assertEquals(AdaptiveBuffer.LARGE, b.level)
    }

    @Test fun levelsAreClamped() {
        assertEquals(AdaptiveBuffer.LARGE, AdaptiveBuffer(9).level)
        assertEquals(AdaptiveBuffer.LIVE, AdaptiveBuffer(-3).level)
        assertEquals(4_000L, AdaptiveBuffer.targetMs(7))
        assertEquals(500L, AdaptiveBuffer.targetMs(-1))
    }

    @Test fun tenStableMinutesShrinkOneStepAndThenAnother() {
        val b = AdaptiveBuffer(AdaptiveBuffer.LARGE)
        play(b, 0, 10 * minute - 250, 4_000)
        assertEquals(AdaptiveBuffer.LARGE, b.level)
        assertTrue(b.onPlaying(10 * minute, 4_000, true))
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        // A descida seguinte só depois de outros 10 minutos.
        play(b, 10 * minute + 250, 20 * minute - 250, 2_000)
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        b.onPlaying(20 * minute, 2_000, true)
        assertEquals(AdaptiveBuffer.SMALL, b.level)
        play(b, 20 * minute + 250, 40 * minute, 1_000)
        // Nunca abaixo do Ao vivo.
        play(b, 40 * minute, 60 * minute, 500)
        assertEquals(AdaptiveBuffer.LIVE, b.level)
    }

    @Test fun aStallRestartsTheStableClock() {
        val b = AdaptiveBuffer(AdaptiveBuffer.SMALL)
        play(b, 0, 9 * minute, 1_000)
        b.onStall(9 * minute)
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        play(b, 9 * minute, 18 * minute, 2_000)
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        play(b, 18 * minute, 19 * minute + 250, 2_000)
        assertEquals(AdaptiveBuffer.SMALL, b.level)
    }

    @Test fun aFallingLeadGrowsBeforeItStalls() {
        val b = AdaptiveBuffer(AdaptiveBuffer.NORMAL)
        play(b, 0, 5_000, 3_000)
        // A ligação deixa de acompanhar: o que está à frente cai até abaixo de metade do alvo (1 s).
        var t = 5_000L
        var lead = 3_000L
        var grewAt = -1L
        while (lead > 0 && grewAt < 0) {
            t += 250
            lead -= 100
            if (b.onPlaying(t, lead, true)) grewAt = t
        }
        assertEquals(AdaptiveBuffer.LARGE, b.level)
        assertTrue(lead < 1_000, "subiu com $lead ms à frente")
    }

    @Test fun dipsAreRateLimitedAndIgnoredWhenNotTrusted() {
        val b = AdaptiveBuffer(AdaptiveBuffer.LIVE)
        b.onPlaying(0, 2_000, true)
        assertTrue(b.onPlaying(250, 100, true))
        assertEquals(AdaptiveBuffer.SMALL, b.level)
        // Outra quebra logo a seguir não conta (no máximo uma a cada 30 s).
        b.onPlaying(500, 2_500, true)
        assertFalse(b.onPlaying(750, 100, true))
        assertTrue(b.onPlaying(30_250, 2_500, true).not())
        assertTrue(b.onPlaying(30_500, 100, true))
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        // Num HLS (dips = false) o que está à frente sobe e desce aos segmentos: só as paragens contam.
        val hls = AdaptiveBuffer(AdaptiveBuffer.NORMAL)
        repeat(7) { i ->
            hls.onPlaying(i * 40_000L, 6_000, false)
            hls.onPlaying(i * 40_000L + 20_000, 200, false)
        }
        assertEquals(AdaptiveBuffer.NORMAL, hls.level)
    }

    @Test fun aSteadyLeadIsNotADip() {
        val b = AdaptiveBuffer(AdaptiveBuffer.NORMAL)
        // Um servidor sem rajada: pouco à frente, mas sempre o mesmo.
        play(b, 0, 5 * minute, 300)
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
    }

    @Test fun afterShrinkingItDrainsTheExtraLeadSlowlyThenStops() {
        val b = AdaptiveBuffer(AdaptiveBuffer.LARGE)
        assertEquals(1f, b.rate(4_000, canDrain = true))
        play(b, 0, 10 * minute, 4_000)
        assertEquals(AdaptiveBuffer.NORMAL, b.level)
        // 4 s à frente, alvo 2 s: acelera 2 %, nunca mais.
        assertEquals(AdaptiveBuffer.DRAIN_RATE, b.rate(4_000, canDrain = true))
        assertTrue(AdaptiveBuffer.DRAIN_RATE <= 1.03f)
        assertEquals(AdaptiveBuffer.DRAIN_RATE, b.rate(2_600, canDrain = true))
        // Chegou ao alvo (mais a folga): volta a 1 e não torna a acelerar.
        assertEquals(1f, b.rate(2_500, canDrain = true))
        assertEquals(1f, b.rate(4_000, canDrain = true))
    }

    @Test fun withoutDrainingTheExtraLeadStaysForTheNextZap() {
        val b = AdaptiveBuffer(AdaptiveBuffer.NORMAL)
        play(b, 0, 10 * minute, 3_000)
        assertEquals(AdaptiveBuffer.SMALL, b.level)
        // O som em bruto para o recetor não acelera: fica a 1.
        assertEquals(1f, b.rate(3_000, canDrain = false))
        // Uma paragem acaba com a descida.
        b.onStall(10 * minute + 1_000)
        assertEquals(1f, b.rate(3_000, canDrain = true))
    }

    @Test fun neverDrainsWithoutAShrink() {
        val b = AdaptiveBuffer(AdaptiveBuffer.LIVE)
        // A rajada do arranque (8 s à frente) fica: só uma descida de nível acelera.
        play(b, 0, 5 * minute, 8_000)
        assertEquals(1f, b.rate(8_000, canDrain = true))
    }

    @Test fun staticLevelsFollowTheSetting() {
        assertEquals(AdaptiveBuffer.LIVE, AdaptiveBuffer.levelOf(Prefs.Buffer.LIVE))
        assertEquals(AdaptiveBuffer.SMALL, AdaptiveBuffer.levelOf(Prefs.Buffer.SMALL))
        assertEquals(AdaptiveBuffer.NORMAL, AdaptiveBuffer.levelOf(Prefs.Buffer.NORMAL))
        assertEquals(AdaptiveBuffer.LARGE, AdaptiveBuffer.levelOf(Prefs.Buffer.LARGE))
        assertEquals(AdaptiveBuffer.NORMAL, AdaptiveBuffer.levelOf(Prefs.Buffer.AUTO))
    }

    @Test fun providerKeyIsTheHostAndPort() {
        assertEquals("tv.example.com:8080", AdaptiveBuffer.providerKey("http://TV.Example.com:8080/live/u/p/1.ts"))
        assertEquals("tv.example.com", AdaptiveBuffer.providerKey("https://user:pass@tv.example.com?x=1"))
        assertEquals("", AdaptiveBuffer.providerKey("timeshift:abc"))
        assertEquals("", AdaptiveBuffer.providerKey("/sdcard/rec.ts"))
    }

    @Test fun memoryRemembersPerProvider() {
        val m = AdaptiveBufferMemory()
        assertEquals(AdaptiveBuffer.START_LEVEL, m.startLevel("a"))
        m.remember("a", AdaptiveBuffer.LARGE)
        m.remember("b", AdaptiveBuffer.LIVE)
        assertEquals(AdaptiveBuffer.LARGE, m.startLevel("a"))
        assertEquals(AdaptiveBuffer.LIVE, m.startLevel("b"))
        // O canal seguinte do fornecedor que falhou começa no Grande.
        val next = AdaptiveBuffer(m.startLevel("a"))
        assertEquals(4_000L, next.targetMs())
        // Valores fora da escala ficam nos limites; sem fornecedor não se guarda.
        m.remember("c", 12)
        assertEquals(AdaptiveBuffer.LARGE, m.startLevel("c"))
        m.remember("", AdaptiveBuffer.LIVE)
        assertEquals(AdaptiveBuffer.START_LEVEL, m.startLevel(""))
    }

    @Test fun memoryKeepsTheMostRecentProviders() {
        val m = AdaptiveBufferMemory()
        for (i in 0 until AdaptiveBufferMemory.MAX) m.remember("p$i", AdaptiveBuffer.LARGE)
        // Usar o p0 outra vez torna-o o mais recente: sai o p1.
        m.remember("p0", AdaptiveBuffer.LARGE)
        m.remember("new", AdaptiveBuffer.LIVE)
        assertEquals(AdaptiveBuffer.LARGE, m.startLevel("p0"))
        assertEquals(AdaptiveBuffer.START_LEVEL, m.startLevel("p1"))
        assertEquals(AdaptiveBuffer.LIVE, m.startLevel("new"))
    }
}
