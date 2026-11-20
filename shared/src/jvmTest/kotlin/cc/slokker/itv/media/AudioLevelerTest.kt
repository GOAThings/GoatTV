package cc.slokker.itv.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioLevelerTest {
    private val rate = 48_000

    /** [seconds] de um seno estéreo de [amplitude] a [hz], em blocos de 1024 amostras como os de um descodificador. */
    private fun run(leveler: AudioLeveler, amplitude: Double, seconds: Double, hz: Double = 997.0, start: Int = 0, out: ((FloatArray) -> Unit)? = null): Int {
        val frames = (seconds * rate).toInt()
        val block = FloatArray(1024 * 2)
        var t = start
        var done = 0
        while (done < frames) {
            val n = minOf(1024, frames - done)
            for (i in 0 until n) {
                val v = (amplitude * sin(2 * PI * hz * (t + i) / rate)).toFloat()
                block[2 * i] = v
                block[2 * i + 1] = v
            }
            leveler.process(block, 0, n)
            out?.invoke(block.copyOf(n * 2))
            t += n
            done += n
        }
        return t
    }

    private fun rmsDb(samples: FloatArray): Double = 10 * log10(samples.sumOf { it.toDouble() * it } / samples.size + 1e-20)

    @Test
    fun `a 997 Hz sine at -20 dBFS in stereo reads -20 LUFS`() {
        val l = AudioLeveler(rate, 2, night = false)
        run(l, 0.1, 4.0)
        // Um seno de pico 1 num canal dá −3,01 LUFS (BS.1770); nos dois, 0 LUFS; com pico 0,1, −20 LUFS.
        assertEquals(-20.0, l.measuredLufs.toDouble(), 0.3)
    }

    @Test
    fun `a loud channel comes down to the target`() {
        val l = AudioLeveler(rate, 2, night = false)
        run(l, 0.7, 30.0)
        val after = AudioLeveler(rate, 2, night = false)
        // A saída dos últimos segundos, medida por outro (que não mexe: só a medida conta).
        val tail = mutableListOf<FloatArray>()
        run(l, 0.7, 5.0, start = 30 * rate) { tail += it }
        tail.forEach { after.process(it.copyOf(), 0, it.size / 2) }
        assertEquals(AudioLeveler.TARGET_LUFS, after.measuredLufs.toDouble(), 1.0)
        assertTrue(l.gainDb < -5f, "ganho ${l.gainDb}")
    }

    @Test
    fun `a quiet channel goes up at most 12 dB and never clips`() {
        val l = AudioLeveler(rate, 2, night = false)
        var peak = 0f
        run(l, 0.01, 40.0) { b -> b.forEach { peak = maxOf(peak, abs(it)) } }
        assertEquals(AudioLeveler.MAX_BOOST_DB, l.gainDb, 0.3f)
        assertTrue(peak <= AudioLeveler.CEILING.toFloat() + 1e-4f)
    }

    @Test
    fun `the limiter keeps a boosted full-scale signal under the ceiling`() {
        val l = AudioLeveler(rate, 2, night = false, startGainDb = 12f)
        var peak = 0f
        run(l, 1.0, 2.0) { b -> b.forEach { peak = maxOf(peak, abs(it)) } }
        assertTrue(peak <= AudioLeveler.CEILING.toFloat() + 1e-4f, "pico $peak")
        val night = AudioLeveler(rate, 2, night = true, startGainDb = 12f)
        peak = 0f
        run(night, 1.0, 2.0) { b -> b.forEach { peak = maxOf(peak, abs(it)) } }
        assertTrue(peak <= AudioLeveler.CEILING.toFloat() + 1e-4f, "pico $peak")
    }

    @Test
    fun `silence does not raise the gain`() {
        val l = AudioLeveler(rate, 2, night = false, startGainDb = -6f)
        run(l, 0.0, 10.0)
        assertEquals(-6f, l.gainDb)
        // Depois de som, uma pausa longa também não mexe no ganho.
        run(l, 0.1, 10.0)
        val before = l.gainDb
        run(l, 0.0001, 10.0)
        assertEquals(before, l.gainDb, 0.01f)
    }

    @Test
    fun `a remembered gain applies from the first sample`() {
        val l = AudioLeveler(rate, 2, night = false, startGainDb = -10f)
        val out = mutableListOf<FloatArray>()
        run(l, 0.5, 0.05) { out += it }
        val peak = out.maxOf { b -> b.maxOf { abs(it) } }
        assertEquals(0.5 * 0.316, peak.toDouble(), 0.01)
    }

    @Test
    fun `night mode narrows the gap between quiet and loud parts`() {
        fun gap(night: Boolean): Double {
            val l = AudioLeveler(rate, 2, night)
            var t = 0
            // Diálogo baixo e explosões, alternados, até o nível assentar.
            repeat(6) {
                t = run(l, 0.02, 3.0, start = t)
                t = run(l, 0.6, 1.0, start = t)
            }
            val quiet = mutableListOf<FloatArray>()
            val loud = mutableListOf<FloatArray>()
            t = run(l, 0.02, 3.0, start = t) { quiet += it }
            run(l, 0.6, 1.0, start = t) { loud += it }
            // O fim de cada parte, já com o compressor assente.
            return rmsDb(loud.takeLast(20).flatMap { it.asList() }.toFloatArray()) - rmsDb(quiet.takeLast(20).flatMap { it.asList() }.toFloatArray())
        }
        val level = gap(false)
        val night = gap(true)
        assertTrue(night < level - 6, "modo noite $night dB, nivelar $level dB")
    }

    @Test
    fun `16-bit PCM round trip`() {
        val l = AudioLeveler(rate, 2, night = false)
        val pcm = ByteArray(4096)
        for (i in 0 until 1024) {
            val v = (8000 * sin(2 * PI * 440 * i / rate)).toInt()
            pcm[4 * i] = v.toByte(); pcm[4 * i + 1] = (v shr 8).toByte()
            pcm[4 * i + 2] = v.toByte(); pcm[4 * i + 3] = (v shr 8).toByte()
        }
        val copy = pcm.copyOf()
        l.processPcm16(pcm, 0, pcm.size)
        // Os primeiros 400 ms ficam sem ganho: igual à entrada (a menos do arredondamento).
        for (i in 0 until 2048) {
            val a = ((copy[2 * i].toInt() and 0xFF) or (copy[2 * i + 1].toInt() shl 8)).toShort()
            val b = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
            assertTrue(abs(a - b) <= 1, "$i: $a → $b")
        }
    }

    @Test
    fun `5_1 ignores the LFE in the measurement`() {
        val l = AudioLeveler(rate, 6, night = false)
        val block = FloatArray(1024 * 6)
        repeat(200) { k ->
            for (i in 0 until 1024) block[i * 6 + 3] = (0.9 * sin(2 * PI * 50 * (k * 1024 + i) / rate)).toFloat()
            l.process(block, 0, 1024)
        }
        assertTrue(l.measuredLufs.isNaN(), "${l.measuredLufs}")
        assertEquals(0f, l.gainDb)
    }
}
