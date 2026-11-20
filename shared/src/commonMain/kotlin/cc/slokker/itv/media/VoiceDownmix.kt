package cc.slokker.itv.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vozes mais claras (Definições → Vozes mais claras), no PCM que a app produz e quando a saída é estéreo. Igual no
 * Android e na Apple.
 *
 * Som com canal central (3.0, 5.0, 5.1, 7.1): a mistura para estéreo faz-se aqui, em vez de a deixar ao sistema.
 * A da ITU (BS.775) põe o centro (os diálogos) 3 dB abaixo dos da frente, e numa TV sem recetor as vozes ficam
 * baixas; aqui o centro fica [CENTER_BOOST_DB] acima disso, os de trás como na ITU e o LFE fora (os altifalantes de
 * uma TV não o reproduzem, só gastava margem). O total desce 3 dB para raramente passar de 1.
 *
 * Som estéreo: um reforço suave ([MID_BOOST_DB]) das frequências da fala (1 a 4 kHz) só no que é comum aos dois
 * canais (o "meio", onde estão as vozes); o que é só de um lado (o ambiente, a largura da música) fica como estava.
 *
 * No fim um limitador de pico em [AudioLeveler.CEILING], a não ser que o nivelamento venha a seguir (ele tem o seu:
 * ver [process]). Por amostra são umas somas e multiplicações (e um filtro no estéreo). Uma instância por faixa.
 */
class VoiceDownmix(sampleRate: Int, private val channels: Int) {
    private val rate = if (sampleRate > 0) sampleRate else 48_000

    /** Os canais que saem: 2 quando mistura, senão os que entram. */
    val outChannels: Int = if (downmixes(channels)) 2 else channels

    // O filtro de pico do meio (RBJ): centro em 2 kHz, largo o bastante para apanhar de 1 a 4 kHz.
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val a = 10.0.pow(MID_BOOST_DB / 40)
        val w = 2 * PI * MID_HZ / rate
        val alpha = sin(w) / (2 * MID_Q)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0
        b1 = -2 * cos(w) / a0
        b2 = (1 - alpha * a) / a0
        a1 = -2 * cos(w) / a0
        a2 = (1 - alpha / a) / a0
    }

    // O limitador (como o do AudioLeveler): baixa na própria amostra e volta em ~50 ms.
    private val limRelease = 1 - exp(-1.0 / (0.050 * rate))
    private var lim = 1.0

    private var scratch = FloatArray(0)

    /**
     * Trata [frames] amostras intercaladas de [samples] a partir de [offset] (−1…1), no próprio array: o resultado
     * fica no mesmo sítio com [outChannels] canais. [limit]: com o limitador (false quando o AudioLeveler vem a
     * seguir e limita ele: o que sai daqui pode então passar de 1).
     */
    fun process(samples: FloatArray, offset: Int, frames: Int, limit: Boolean) {
        val ch = channels
        if (!applies(ch)) return
        val back = when (ch) { 5 -> 3; 6, 8 -> 4; else -> -1 }
        var o = offset
        for (f in 0 until frames) {
            val base = offset + f * ch
            var l = samples[base].toDouble()
            var r = samples[base + 1].toDouble()
            if (ch == 2) {
                val m = (l + r) * 0.5
                val y = b0 * m + z1
                z1 = b1 * m - a1 * y + z2
                z2 = b2 * m - a2 * y
                val d = y - m
                l += d
                r += d
            } else {
                val c = samples[base + 2] * CENTER
                l = l * FRONT + c
                r = r * FRONT + c
                if (back >= 0) {
                    l += samples[base + back] * SURROUND
                    r += samples[base + back + 1] * SURROUND
                    if (ch == 8) {
                        l += samples[base + 6] * SURROUND
                        r += samples[base + 7] * SURROUND
                    }
                }
            }
            if (limit) {
                lim += (1 - lim) * limRelease
                val al = if (l < 0) -l else l
                val ar = if (r < 0) -r else r
                val peak = if (al > ar) al else ar
                if (peak * lim > AudioLeveler.CEILING) lim = AudioLeveler.CEILING / peak
                l *= lim
                r *= lim
            }
            // Escreve-se atrás de onde se lê (2 canais por cada [ch] ≥ 2): nunca por cima do que falta ler.
            samples[o] = l.toFloat()
            samples[o + 1] = r.toFloat()
            o += 2
        }
    }

    /**
     * O mesmo em PCM de 16 bits little-endian ([length] bytes de [pcm] a partir de [offset]), no próprio array, e a
     * seguir o nivelamento [leveler] (feito para [outChannels] canais; null = sem ele), tudo numa só passagem pelas
     * amostras. Devolve os bytes que ficaram (menos do que [length] quando mistura para estéreo).
     */
    fun processPcm16(pcm: ByteArray, offset: Int, length: Int, leveler: AudioLeveler?): Int {
        val ch = channels
        if (!applies(ch)) {
            leveler?.processPcm16(pcm, offset, length)
            return length
        }
        val frames = length / 2 / ch
        val n = frames * ch
        if (scratch.size < n) scratch = FloatArray(n)
        val s = scratch
        var o = offset
        for (i in 0 until n) {
            s[i] = ((pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)).toShort() / 32768f
            o += 2
        }
        process(s, 0, frames, limit = leveler == null)
        leveler?.process(s, 0, frames)
        val out = frames * 2
        o = offset
        for (i in 0 until out) {
            val v = (s[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
            pcm[o] = v.toByte()
            pcm[o + 1] = (v shr 8).toByte()
            o += 2
        }
        return out * 2
    }

    companion object {
        /** Quanto o centro fica acima do que a mistura da ITU lhe dá (dB), em relação aos canais da frente. */
        const val CENTER_BOOST_DB = 5.0
        /** O reforço do meio no som estéreo (dB, no centro da banda). */
        const val MID_BOOST_DB = 4.0
        private const val MID_HZ = 2_000.0
        private const val MID_Q = 0.7

        // A mistura da ITU (frente 1, centro e trás 0,707) com tudo 3 dB abaixo e o centro [CENTER_BOOST_DB] acima.
        private const val FRONT = 0.7071067811865476f
        private const val SURROUND = 0.5f
        private val CENTER = (0.5 * 10.0.pow(CENTER_BOOST_DB / 20)).toFloat()

        /**
         * Com estes canais há central e mistura-se para estéreo, pela ordem do Android/FFmpeg: 3 (FL FR FC),
         * 5 (FL FR FC BL BR), 6 (FL FR FC LFE BL BR), 8 (FL FR FC LFE BL BR SL SR). Com 4 ou 7 não se sabe onde
         * está o centro (ou se o há): fica para o sistema.
         */
        fun downmixes(channels: Int): Boolean = channels == 3 || channels == 5 || channels == 6 || channels == 8

        /** Há alguma coisa a fazer com estes canais (misturar, ou reforçar o meio do estéreo). */
        fun applies(channels: Int): Boolean = channels == 2 || downmixes(channels)
    }
}
