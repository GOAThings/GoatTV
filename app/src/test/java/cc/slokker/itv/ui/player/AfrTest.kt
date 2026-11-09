package cc.slokker.itv.ui.player

import cc.slokker.itv.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToLong

/** A cadência medida pelas imagens (o TS não a diz) e quando o AFR muda o modo da TV. */
class AfrTest {
    /** [n] imagens a [fps], com os tempos em 90 kHz como no TS (arredondados a µs, como o Media3 os dá). */
    private fun frames(fps: Double, n: Int = 40, start: Long = 10_000_000L): List<Long> {
        val ticks = 90_000.0 / fps
        return (0 until n).map { start + ((it * ticks).roundToLong() * 1_000_000.0 / 90_000).roundToLong() }
    }

    private fun measure(times: List<Long>): Float? {
        val e = FpsEstimator()
        var last: Float? = null
        times.forEach { t -> e.add(t)?.let { last = it } }
        return last
    }

    @Test
    fun asCadenciasDeTv() {
        for ((fps, expected) in listOf(25.0 to 25f, 50.0 to 50f, 30_000 / 1001.0 to 29.97f, 60_000 / 1001.0 to 59.94f, 24_000 / 1001.0 to 23.976f, 24.0 to 24f, 30.0 to 30f, 60.0 to 60f)) {
            assertEquals("$fps", expected, measure(frames(fps)))
        }
    }

    @Test
    fun precisaDeImagensQueCheguem() {
        assertNull(measure(frames(25.0, n = FpsEstimator.FRAMES)))
        assertEquals(25f, measure(frames(25.0, n = FpsEstimator.FRAMES + 1)))
    }

    @Test
    fun imagensLargadasESaltosNaoEnganam() {
        // Uma imagem largada a cada 7 (o intervalo dobra) continua a dar 25.
        val dropped = frames(25.0, n = 60).filterIndexed { i, _ -> i % 7 != 3 }
        assertEquals(25f, measure(dropped))
        // Três imagens largadas seguidas (120 ms sem nenhuma) também não.
        val gap = frames(25.0, n = 60).filterIndexed { i, _ -> i !in 20..22 }
        assertEquals(25f, measure(gap))
        // O canal anterior a 50 e depois um salto para outro a 25: conta só o novo.
        val zap = frames(50.0, n = 10) + frames(25.0, n = 30, start = 900_000_000L)
        assertEquals(25f, measure(zap))
    }

    @Test
    fun entrelacadoVaiPara50Hz() {
        // 1080i50 / 576i: 25 imagens com os dois campos, ou 50 se o descodificador os separar.
        val modes = listOf(1 to 60f, 2 to 59.94f, 3 to 50f, 4 to 24f)
        assertEquals(3, FrameRateMatcher.bestMode(modes, measure(frames(25.0))!!))
        assertEquals(3, FrameRateMatcher.bestMode(modes, measure(frames(50.0))!!))
        assertEquals(2, FrameRateMatcher.bestMode(modes, measure(frames(30_000 / 1001.0))!!))
    }

    @Test
    fun foraDasHabituaisFicaComoE() {
        assertEquals(15f, FpsEstimator.snap(15f))
        assertEquals(25f, FpsEstimator.snap(25.1f))
    }

    @Test
    fun desligadoNuncaMuda() {
        val s = AfrSession()
        assertNull(s.onFps(Prefs.AfrMode.OFF, 25f))
    }

    @Test
    fun aoComecarSoNoPrimeiroCanal() {
        val s = AfrSession()
        assertEquals(25f, s.onFps(Prefs.AfrMode.START, 25f))
        // A mesma medida outra vez, ou outro canal: fica como está.
        assertNull(s.onFps(Prefs.AfrMode.START, 25f))
        s.newChannel()
        assertNull(s.onFps(Prefs.AfrMode.START, 29.97f))
        // Saiu-se do player (a TV voltou ao modo normal): ao voltar, muda outra vez.
        s.reset()
        assertEquals(29.97f, s.onFps(Prefs.AfrMode.START, 29.97f))
    }

    @Test
    fun sempreEmCadaCanal() {
        val s = AfrSession()
        assertEquals(25f, s.onFps(Prefs.AfrMode.ALWAYS, 25f))
        assertNull(s.onFps(Prefs.AfrMode.ALWAYS, 25f))
        s.newChannel()
        assertEquals(59.94f, s.onFps(Prefs.AfrMode.ALWAYS, 59.94f))
    }
}
