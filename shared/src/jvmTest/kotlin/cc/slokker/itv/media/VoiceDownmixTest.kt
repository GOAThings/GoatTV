package cc.slokker.itv.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceDownmixTest {
    private val rate = 48_000

    /** [frames] amostras de [channels] canais: cada canal um seno de [amplitudes] a [hz] (0 = calado). */
    private fun tone(channels: Int, amplitudes: Map<Int, Double>, frames: Int, hz: Double = 997.0): FloatArray {
        val s = FloatArray(frames * channels)
        for (f in 0 until frames) for ((c, a) in amplitudes) s[f * channels + c] = (a * sin(2 * PI * hz * f / rate)).toFloat()
        return s
    }

    /** O RMS (dB) do canal [c] de [s] ([channels] canais), sem o primeiro décimo de segundo (o filtro a assentar). */
    private fun rmsDb(s: FloatArray, channels: Int, c: Int, frames: Int): Double {
        var sum = 0.0
        val from = rate / 10
        for (f in from until frames) sum += s[f * channels + c].toDouble() * s[f * channels + c]
        return 10 * log10(sum / (frames - from) + 1e-20)
    }

    private fun peak(s: FloatArray, n: Int): Float = (0 until n).maxOf { abs(s[it]) }

    @Test
    fun `which layouts are downmixed`() {
        for (ch in listOf(3, 5, 6, 8)) {
            assertTrue(VoiceDownmix.downmixes(ch))
            assertEquals(2, VoiceDownmix(rate, ch).outChannels)
        }
        for (ch in listOf(1, 2, 4, 7)) {
            assertFalse(VoiceDownmix.downmixes(ch))
            assertEquals(ch, VoiceDownmix(rate, ch).outChannels)
        }
        assertTrue(VoiceDownmix.applies(2))
        assertFalse(VoiceDownmix.applies(1))
        assertFalse(VoiceDownmix.applies(4))
    }

    @Test
    fun `the centre of 5_1 goes to both sides 5 dB above its ITU share`() {
        val frames = rate
        // Só o centro, depois só a frente esquerda, à mesma amplitude: a diferença é o peso de cada um.
        val centre = tone(6, mapOf(2 to 0.2), frames)
        VoiceDownmix(rate, 6).process(centre, 0, frames, limit = true)
        val front = tone(6, mapOf(0 to 0.2), frames)
        VoiceDownmix(rate, 6).process(front, 0, frames, limit = true)
        val c = rmsDb(centre, 2, 0, frames)
        assertEquals(c, rmsDb(centre, 2, 1, frames), 0.01)
        val f = rmsDb(front, 2, 0, frames)
        // ITU: o centro 3 dB abaixo da frente; aqui 5 dB acima disso.
        assertEquals(-3.01 + VoiceDownmix.CENTER_BOOST_DB, c - f, 0.05)
        // A frente esquerda não passa para a direita.
        assertTrue(rmsDb(front, 2, 1, frames) < -150)
    }

    @Test
    fun `the LFE is dropped and the surrounds keep their side`() {
        val frames = rate / 2
        val lfe = tone(6, mapOf(3 to 0.9), frames, hz = 60.0)
        VoiceDownmix(rate, 6).process(lfe, 0, frames, limit = true)
        assertEquals(0f, peak(lfe, frames * 2))
        val back = tone(6, mapOf(5 to 0.4), frames)
        VoiceDownmix(rate, 6).process(back, 0, frames, limit = true)
        assertTrue(rmsDb(back, 2, 0, frames) < -150)
        val front = tone(6, mapOf(1 to 0.4), frames)
        VoiceDownmix(rate, 6).process(front, 0, frames, limit = true)
        // Trás 3 dB abaixo da frente, como na ITU.
        assertEquals(-3.01, rmsDb(back, 2, 1, frames) - rmsDb(front, 2, 1, frames), 0.05)
    }

    @Test
    fun `3_0 5_0 and 7_1 use the right channels`() {
        val frames = rate / 2
        val c3 = tone(3, mapOf(2 to 0.2), frames)
        VoiceDownmix(rate, 3).process(c3, 0, frames, limit = true)
        val c6 = tone(6, mapOf(2 to 0.2), frames)
        VoiceDownmix(rate, 6).process(c6, 0, frames, limit = true)
        assertEquals(rmsDb(c6, 2, 0, frames), rmsDb(c3, 2, 0, frames), 0.01)
        // 5.0: o canal 3 é o de trás esquerdo (não um LFE).
        val b5 = tone(5, mapOf(3 to 0.4), frames)
        VoiceDownmix(rate, 5).process(b5, 0, frames, limit = true)
        val b6 = tone(6, mapOf(4 to 0.4), frames)
        VoiceDownmix(rate, 6).process(b6, 0, frames, limit = true)
        assertEquals(rmsDb(b6, 2, 0, frames), rmsDb(b5, 2, 0, frames), 0.01)
        assertTrue(rmsDb(b5, 2, 1, frames) < -150)
        // 7.1: os de lado (6 e 7) entram como os de trás; o LFE (3) não.
        val s8 = tone(8, mapOf(7 to 0.4, 3 to 0.9), frames)
        VoiceDownmix(rate, 8).process(s8, 0, frames, limit = true)
        val r6 = tone(6, mapOf(5 to 0.4), frames)
        VoiceDownmix(rate, 6).process(r6, 0, frames, limit = true)
        assertEquals(rmsDb(r6, 2, 1, frames), rmsDb(s8, 2, 1, frames), 0.01)
        assertTrue(rmsDb(s8, 2, 0, frames) < -150)
    }

    @Test
    fun `full scale on every channel never passes the ceiling`() {
        val frames = rate
        for (ch in listOf(2, 3, 5, 6, 8)) {
            val s = tone(ch, (0 until ch).associateWith { 1.0 }, frames, hz = if (ch == 2) 2_000.0 else 997.0)
            VoiceDownmix(rate, ch).process(s, 0, frames, limit = true)
            assertTrue(peak(s, frames * 2) <= AudioLeveler.CEILING.toFloat() + 1e-4f, "$ch ch: ${peak(s, frames * 2)}")
        }
        // Sem o limitador (o nivelamento vem a seguir) a mistura passa de 1, e o nivelamento segura-a.
        val s = tone(6, (0 until 6).associateWith { 1.0 }, frames)
        VoiceDownmix(rate, 6).process(s, 0, frames, limit = false)
        assertTrue(peak(s, frames * 2) > 1.5f)
        AudioLeveler(rate, 2, night = false).process(s, 0, frames)
        assertTrue(peak(s, frames * 2) <= AudioLeveler.CEILING.toFloat() + 1e-4f)
    }

    @Test
    fun `stereo gets the speech band of the mid raised and the sides untouched`() {
        val frames = rate
        fun gain(hz: Double, right: Double): Double {
            val s = FloatArray(frames * 2)
            for (f in 0 until frames) {
                val v = (0.1 * sin(2 * PI * hz * f / rate)).toFloat()
                s[2 * f] = v
                s[2 * f + 1] = (v * right).toFloat()
            }
            val before = rmsDb(s, 2, 0, frames)
            VoiceDownmix(rate, 2).process(s, 0, frames, limit = true)
            return rmsDb(s, 2, 0, frames) - before
        }
        // O que é igual nos dois canais (as vozes): +4 dB a 2 kHz, quase nada nos graves e nos agudos altos.
        assertEquals(VoiceDownmix.MID_BOOST_DB, gain(2_000.0, 1.0), 0.1)
        assertTrue(gain(1_000.0, 1.0) in 1.0..3.5)
        assertTrue(gain(4_000.0, 1.0) in 1.0..3.5)
        assertTrue(abs(gain(100.0, 1.0)) < 0.2)
        assertTrue(abs(gain(15_000.0, 1.0)) < 0.5)
        // O que está em oposição (só "lados"): não tem meio, fica como estava.
        assertEquals(0.0, gain(2_000.0, -1.0), 0.001)
    }

    @Test
    fun `16-bit PCM is downmixed in place and levelled in the same pass`() {
        val frames = 4_800
        fun pcm(): ByteArray {
            val s = tone(6, mapOf(0 to 0.1, 2 to 0.1), frames)
            val b = ByteArray(8 + frames * 12)
            for (i in s.indices) {
                val v = (s[i] * 32768f).toInt()
                b[8 + 2 * i] = v.toByte()
                b[8 + 2 * i + 1] = (v shr 8).toByte()
            }
            return b
        }
        fun rms(b: ByteArray, bytes: Int): Double {
            var sum = 0.0
            for (i in 0 until bytes / 2) {
                val v = ((b[8 + 2 * i].toInt() and 0xFF) or (b[8 + 2 * i + 1].toInt() shl 8)).toShort() / 32768.0
                sum += v * v
            }
            return sqrt(sum / (bytes / 2))
        }
        val plain = pcm()
        val n = VoiceDownmix(rate, 6).processPcm16(plain, 8, frames * 12, null)
        assertEquals(frames * 4, n)
        // Os bytes antes do início não se tocam.
        assertTrue(plain.take(8).all { it == 0.toByte() })
        // Esquerda: frente (0,707) + centro (0,889) em fase; direita: só o centro.
        val expected = 0.1 / sqrt(2.0) * sqrt(((0.7071 + 0.8891) * (0.7071 + 0.8891) + 0.8891 * 0.8891) / 2)
        assertEquals(expected, rms(plain, n), 0.002)
        // Com o nivelamento a começar em +6 dB: o mesmo, 6 dB acima.
        val levelled = pcm()
        val m = VoiceDownmix(rate, 6).processPcm16(levelled, 8, frames * 12, AudioLeveler(rate, 2, night = false, startGainDb = 6f))
        assertEquals(n, m)
        assertEquals(6.0, 20 * log10(rms(levelled, m) / rms(plain, n)), 0.3)
        // Mono (ou 4 canais): nada a fazer, só o nivelamento.
        val mono = ByteArray(200) { 5 }
        assertEquals(200, VoiceDownmix(rate, 1).processPcm16(mono, 0, 200, null))
        assertTrue(mono.all { it == 5.toByte() })
    }
}
