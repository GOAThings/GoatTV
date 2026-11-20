package cc.slokker.itv.media

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Nivelar o volume entre canais (Definições → Nivelar volume), no PCM que a app produz (AAC, MP2, AC-3 pelo FFmpeg;
 * em passthrough o som vai em bruto e não se lhe mexe). Igual no Android e na Apple.
 *
 * Mede a loudness de curto prazo da entrada (uma aproximação da ITU-R BS.1770: filtro K, blocos de 100 ms, média de
 * ~3 s, sem os silêncios) e leva-a devagar até [TARGET_LUFS], com o reforço limitado a [MAX_BOOST_DB]. No modo noite
 * ([night]) um compressor a seguir sobe o que é baixo (diálogo) e baixa o que é alto (explosões). No fim, um limitador
 * de pico: nada passa de [CEILING] (−1 dBFS), por isso o reforço nunca satura.
 *
 * Feito para correr em cada bloco de som: só somas e multiplicações por amostra; os logaritmos e as exponenciais são
 * de 16 em 16 amostras (compressor) ou de 100 em 100 ms (ganho). Uma instância por faixa (guarda o estado dos filtros).
 */
class AudioLeveler(
    sampleRate: Int,
    /** Intercalados, pela ordem do Android/FFmpeg (5.1: FL FR FC LFE BL BR). */
    private val channels: Int,
    /** Modo noite: nivelar e comprimir a dinâmica. */
    private val night: Boolean,
    /**
     * O ganho (dB) com que este canal ficou da última vez (ver [gainDb]), para o zap não rebentar antes de se adaptar;
     * NaN = desconhecido (começa sem ganho e adapta-se mais depressa nos primeiros segundos).
     */
    startGainDb: Float = Float.NaN,
) {
    private val rate = if (sampleRate > 0) sampleRate else 48_000
    private val blockFrames = (rate / 10).coerceAtLeast(1)

    // O filtro K (BS.1770): uma prateleira nos agudos e um passa-altos, os coeficientes para esta frequência.
    private val s1b0: Double
    private val s1b1: Double
    private val s1b2: Double
    private val s1a1: Double
    private val s1a2: Double
    private val s2a1: Double
    private val s2a2: Double

    init {
        var k = tan(PI * 1681.974450955533 / rate)
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(0.4996667741545416)
        var q = 0.7071752369554196
        var a0 = 1 + k / q + k * k
        s1b0 = (vh + vb * k / q + k * k) / a0
        s1b1 = 2 * (k * k - vh) / a0
        s1b2 = (vh - vb * k / q + k * k) / a0
        s1a1 = 2 * (k * k - 1) / a0
        s1a2 = (1 - k / q + k * k) / a0
        k = tan(PI * 38.13547087602444 / rate)
        q = 0.5003270373238773
        a0 = 1 + k / q + k * k
        s2a1 = 2 * (k * k - 1) / a0
        s2a2 = (1 - k / q + k * k) / a0
    }

    /** O peso de cada canal na medida: o LFE não conta, os de trás valem 1,41 (BS.1770). */
    private val weights = DoubleArray(channels.coerceAtLeast(0)) { c ->
        when {
            channels >= 6 && c == 3 -> 0.0
            channels >= 6 && c >= 4 -> 1.41
            else -> 1.0
        }
    }

    // O estado dos dois filtros de cada canal (forma direta II transposta).
    private val z1 = DoubleArray(weights.size)
    private val z2 = DoubleArray(weights.size)
    private val w1 = DoubleArray(weights.size)
    private val w2 = DoubleArray(weights.size)

    private var blockPower = 0.0
    private var blockCount = 0
    /** A potência média de curto prazo da entrada (NaN até haver som), e de quantos blocos. */
    private var measured = Double.NaN
    private var measuredBlocks = 0

    /** Veio de [startGainDb]: adapta-se ao ritmo normal desde o início. */
    private val remembered = !startGainDb.isNaN()

    /** O ganho do nivelamento, em dB (o que guardar para este canal quando ele fechar). */
    var gainDb: Float = if (remembered) startGainDb.coerceIn(-MAX_CUT_DB, MAX_BOOST_DB) else 0f
        private set

    /** A loudness de curto prazo medida na entrada (LUFS); NaN antes de haver som. */
    val measuredLufs: Float get() = if (measured.isNaN()) Float.NaN else lufs(measured).toFloat()

    // O ganho aplicado, em rampa de bloco a bloco (sem degraus que se ouçam).
    private var gain = dbToLinear(gainDb)
    private var gainTarget = gain
    private var gainStep = 0.0

    // O compressor (modo noite), para os dois lados do limiar: o alto desce, o baixo sobe (até [NIGHT_MAX_UP_DB], e
    // nada no ruído de fundo). A média dos quadrados com ataque e libertação, e o ganho em rampa de 16 amostras.
    private val compAttack = 1 - exp(-1.0 / (0.010 * rate))
    private val compRelease = 1 - exp(-1.0 / (0.250 * rate))
    private var env = 0.0
    private var comp = 1.0
    private var compStep = 0.0
    private var compCount = 0

    // O limitador: baixa logo (na própria amostra) e volta em ~50 ms.
    private val limRelease = 1 - exp(-1.0 / (0.050 * rate))
    private var lim = 1.0

    private var scratch = FloatArray(0)

    /** Nivela [frames] amostras intercaladas de [samples] a partir de [offset] (−1…1), no próprio array. */
    fun process(samples: FloatArray, offset: Int, frames: Int) {
        val ch = weights.size
        if (ch == 0) return
        for (f in 0 until frames) {
            val base = offset + f * ch
            var power = 0.0
            var peak = 0.0
            var square = 0.0
            for (c in 0 until ch) {
                val x = samples[base + c].toDouble()
                val y1 = s1b0 * x + z1[c]
                z1[c] = s1b1 * x - s1a1 * y1 + z2[c]
                z2[c] = s1b2 * x - s1a2 * y1
                val y2 = y1 + w1[c]
                w1[c] = -2 * y1 - s2a1 * y2 + w2[c]
                w2[c] = y1 - s2a2 * y2
                power += weights[c] * y2 * y2
                val a = if (x < 0) -x else x
                if (a > peak) peak = a
                square += x * x
            }
            blockPower += power
            var g = gain
            if (night) {
                val ms = square / ch * g * g
                env += (if (ms > env) compAttack else compRelease) * (ms - env)
                if (compCount == 0) {
                    val level = 10 * log10(env + 1e-12)
                    val change = (NIGHT_THRESHOLD_DB - level) * (1 - 1 / NIGHT_RATIO)
                    val db = if (change < 0) change else minOf(change, NIGHT_MAX_UP_DB, (level - NIGHT_FLOOR_DB).coerceAtLeast(0.0))
                    compStep = (dbToLinear(db.toFloat()) - comp) / COMP_FRAMES
                }
                compCount = (compCount + 1) % COMP_FRAMES
                comp += compStep
                g *= comp
            }
            lim += (1 - lim) * limRelease
            val out = peak * g
            if (out * lim > CEILING) lim = CEILING / out
            g *= lim
            if (g != 1.0) {
                val gf = g.toFloat()
                for (c in 0 until ch) samples[base + c] *= gf
            }
            gain += gainStep
            if (++blockCount == blockFrames) endBlock()
        }
    }

    /** O mesmo em PCM de 16 bits little-endian ([length] bytes de [pcm] a partir de [offset]), no próprio array. */
    fun processPcm16(pcm: ByteArray, offset: Int, length: Int) {
        val ch = weights.size
        if (ch == 0) return
        val frames = length / 2 / ch
        val n = frames * ch
        if (scratch.size < n) scratch = FloatArray(n)
        val s = scratch
        var o = offset
        for (i in 0 until n) {
            s[i] = ((pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)).toShort() / 32768f
            o += 2
        }
        process(s, 0, frames)
        o = offset
        for (i in 0 until n) {
            val v = (s[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
            pcm[o] = v.toByte()
            pcm[o + 1] = (v shr 8).toByte()
            o += 2
        }
    }

    /** O fim de um bloco de 100 ms: a medida e o ganho seguinte (em rampa até ao fim do próximo bloco). */
    private fun endBlock() {
        val p = blockPower / blockFrames
        blockPower = 0.0
        blockCount = 0
        val loud = lufs(p)
        // Os silêncios e as pausas (muito abaixo do que se mede) não contam: nem a medida nem o ganho mexem.
        val counts = loud > ABSOLUTE_GATE_LUFS && (measured.isNaN() || loud > lufs(measured) - RELATIVE_GATE_LU)
        if (counts) {
            measuredBlocks++
            // Até ter a janela cheia, a média de todos (uma medida certa depressa); depois a média de ~3 s.
            val a = if (measuredBlocks < WINDOW_BLOCKS) 1.0 / measuredBlocks else WINDOW_ALPHA
            measured = if (measured.isNaN()) p else measured + a * (p - measured)
        }
        if (counts && measuredBlocks >= MIN_BLOCKS) {
            val want = (TARGET_LUFS - lufs(measured)).toFloat().coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
            val alpha = when {
                !remembered && measuredBlocks < WINDOW_BLOCKS -> FAST_ALPHA
                want < gainDb -> DOWN_ALPHA
                else -> UP_ALPHA
            }
            gainDb += (want - gainDb) * alpha.toFloat()
        }
        gain = gainTarget
        gainTarget = dbToLinear(gainDb)
        gainStep = (gainTarget - gain) / blockFrames
    }

    companion object {
        /**
         * A loudness alvo. Os canais em EBU R128 dão −23 e os ATSC −24; muitos IPTV (música, restreams) andam pelos
         * −10. −20 sobe pouco os primeiros e baixa os outros sem deixar tudo baixinho.
         */
        const val TARGET_LUFS = -20.0
        const val MAX_BOOST_DB = 12f
        const val MAX_CUT_DB = 20f
        /** −1 dBFS. */
        const val CEILING = 0.8912509381337456

        private const val ABSOLUTE_GATE_LUFS = -60.0
        private const val RELATIVE_GATE_LU = 20.0
        /** 3 s de blocos de 100 ms (a janela de curto prazo da BS.1770). */
        private const val WINDOW_BLOCKS = 30
        /** 400 ms (a janela momentânea) antes de mexer no ganho. */
        private const val MIN_BLOCKS = 4
        private val WINDOW_ALPHA = 1 - exp(-0.1 / 3.0)
        /** Constantes de tempo do ganho: um canal novo em 1 s, a baixar em 3 s, a subir em 8 s. */
        private val FAST_ALPHA = 1 - exp(-0.1 / 1.0)
        private val DOWN_ALPHA = 1 - exp(-0.1 / 3.0)
        private val UP_ALPHA = 1 - exp(-0.1 / 8.0)

        private const val NIGHT_THRESHOLD_DB = -30.0
        private const val NIGHT_RATIO = 3.0
        private const val NIGHT_MAX_UP_DB = 12.0
        /** Abaixo disto é ruído de fundo: não se sobe. */
        private const val NIGHT_FLOOR_DB = -70.0
        private const val COMP_FRAMES = 16

        private fun lufs(power: Double) = -0.691 + 10 * log10(power + 1e-20)

        private fun dbToLinear(db: Float): Double = 10.0.pow(db / 20.0)
    }
}
