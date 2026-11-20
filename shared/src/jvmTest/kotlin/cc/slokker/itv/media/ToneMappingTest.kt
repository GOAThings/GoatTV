package cc.slokker.itv.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** As contas do HDR → SDR (ver [ToneMapping]), com os valores conhecidos do PQ, do HLG e do BT.2408. */
class ToneMappingTest {
    private fun near(expected: Double, actual: Double, tolerance: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")

    @Test
    fun pqKnownValues() {
        near(0.0, ToneMapping.pqEotf(0.0), 1e-9, "black")
        near(10_000.0, ToneMapping.pqEotf(1.0), 1e-6, "peak")
        // ST 2084 / BT.2408: 100 nits ≈ 0,5081, 203 nits ≈ 0,5807, 1000 nits ≈ 0,7518.
        near(0.5081, ToneMapping.pqInverse(100.0), 1e-4, "100 nits")
        near(0.5807, ToneMapping.pqInverse(203.0), 1e-4, "203 nits")
        near(0.7518, ToneMapping.pqInverse(1000.0), 1e-4, "1000 nits")
        near(92.25, ToneMapping.pqEotf(0.5), 0.05, "signal 0.5")
        for (n in listOf(0.01, 1.0, 50.0, 203.0, 1000.0, 4000.0)) near(n, ToneMapping.pqEotf(ToneMapping.pqInverse(n)), n * 1e-9, "round trip $n")
    }

    @Test
    fun hlgKnownValues() {
        near(1.0 / 12.0, ToneMapping.hlgInverseOetf(0.5), 1e-12, "knee")
        near(1.0, ToneMapping.hlgInverseOetf(1.0), 1e-5, "peak")
        near(0.0, ToneMapping.hlgInverseOetf(0.0), 1e-12, "black")
        for (s in listOf(0.001, 0.05, 1.0 / 12.0, 0.3, 0.9)) near(s, ToneMapping.hlgInverseOetf(ToneMapping.hlgOetf(s)), 1e-9, "round trip $s")
        // BT.2408: o branco de referência do HLG (75 % do sinal) dá 203 nits numa TV de 1000 nits.
        near(203.0, ToneMapping.hlgEotf(0.75), 1.0, "reference white")
        near(1000.0, ToneMapping.hlgEotf(1.0), 0.1, "peak")
    }

    @Test
    fun bt2390KeepsTheDarkAndReachesThePeak() {
        near(ToneMapping.TARGET_PEAK, ToneMapping.bt2390(1000.0), 0.01, "peak to target")
        // Abaixo do joelho fica como vem.
        near(10.0, ToneMapping.bt2390(10.0), 1e-9, "dark")
        near(50.0, ToneMapping.bt2390(50.0), 1e-9, "mid")
        // Sobe sempre, sem passar o pico da TV.
        var last = 0.0
        for (n in 1..1000) {
            val v = ToneMapping.bt2390(n.toDouble())
            assertTrue(v >= last - 1e-9 && v <= ToneMapping.TARGET_PEAK + 1e-6, "monotonic at $n: $v")
            last = v
        }
        near(ToneMapping.TARGET_PEAK, ToneMapping.bt2390(4000.0), 0.01, "above the source peak")
    }

    @Test
    fun gamutMatrixIsTheBt2087One() {
        val m = ToneMapping.BT2020_TO_BT709
        // BT.2087 (a inversa da conversão BT.709 → BT.2020).
        val expected = doubleArrayOf(1.6605, -0.5876, -0.0728, -0.1246, 1.1329, -0.0083, -0.0182, -0.1006, 1.1187)
        for (i in 0 until 9) near(expected[i], m[i], 5e-4, "m[$i]")
        // O branco fica branco.
        for (r in 0 until 3) near(1.0, m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2], 1e-9, "row $r")
    }

    @Test
    fun referenceWhiteBecomesSdrWhite() {
        val pqWhite = ToneMapping.pqInverse(203.0)
        val sdr = ToneMapping.toSdr(doubleArrayOf(pqWhite, pqWhite, pqWhite), ToneMapping.PQ)
        // 203 nits está acima do joelho para um pico de 1000: fica um pouco abaixo do branco, mas cinzento (sem cor).
        for (c in sdr) assertTrue(c in 0.85..1.0, "pq white $c")
        near(sdr[0], sdr[1], 1e-9)
        near(sdr[1], sdr[2], 1e-9)
        val hlg = ToneMapping.toSdr(doubleArrayOf(0.75, 0.75, 0.75), ToneMapping.HLG)
        for (c in hlg) assertTrue(c in 0.85..1.0, "hlg white $c")
        // O preto fica preto, o pico fica no branco.
        for (c in ToneMapping.toSdr(doubleArrayOf(0.0, 0.0, 0.0), ToneMapping.PQ)) near(0.0, c, 1e-9)
        val peak = ToneMapping.pqInverse(1000.0)
        for (c in ToneMapping.toSdr(doubleArrayOf(peak, peak, peak), ToneMapping.PQ)) near(1.0, c, 1e-4)
        // Um cinzento escuro (10 nits) sai como no SDR: (10/203)^(1/2,4).
        val dark = ToneMapping.pqInverse(10.0)
        near(Math.pow(10.0 / 203.0, 1 / 2.4), ToneMapping.toSdr(doubleArrayOf(dark, dark, dark), ToneMapping.PQ)[0], 1e-6)
    }

    @Test
    fun decision() {
        assertEquals(ToneMapping.PQ, ToneMapping.mode(16, needsSdr = true, decoderConverted = false))
        assertEquals(ToneMapping.HLG, ToneMapping.mode(18, needsSdr = true, decoderConverted = false))
        assertEquals(ToneMapping.NONE, ToneMapping.mode(16, needsSdr = true, decoderConverted = true))
        assertEquals(ToneMapping.NONE, ToneMapping.mode(16, needsSdr = false, decoderConverted = false))
        assertEquals(ToneMapping.NONE, ToneMapping.mode(1, needsSdr = true, decoderConverted = false))
        assertEquals(ToneMapping.PQ, ToneMapping.mode(1, needsSdr = false, decoderConverted = false, force = true))
        assertEquals(ToneMapping.HLG, ToneMapping.mode(18, needsSdr = false, decoderConverted = true, force = true))
    }

    @Test
    fun glslUsesTheSameConstants() {
        for (mode in listOf(ToneMapping.PQ, ToneMapping.HLG)) {
            val g = ToneMapping.glsl(mode)
            assertTrue("vec3 toneMap(highp vec3 rgb)" in g)
            assertTrue("PQ_M1 = ${ToneMapping.PQ_M1}" in g)
            assertTrue("PQ_C3 = ${ToneMapping.PQ_C3}" in g)
            // A matriz vai por colunas: a primeira coluna é a primeira de cada linha.
            val m = ToneMapping.BT2020_TO_BT709
            assertTrue("mat3(${m[0]}, ${m[3]}, ${m[6]}, ${m[1]}" in g, g)
            assertEquals(g.count { it == '{' }, g.count { it == '}' })
            assertEquals(g.count { it == '(' }, g.count { it == ')' })
        }
        assertTrue("hlgInverseOetf" in ToneMapping.glsl(ToneMapping.HLG))
        assertTrue("hlgInverseOetf" !in ToneMapping.glsl(ToneMapping.PQ))
    }
}
