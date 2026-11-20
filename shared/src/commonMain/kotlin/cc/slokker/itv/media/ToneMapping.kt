package cc.slokker.itv.media

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * O HDR numa TV que só mostra SDR: sem conversão, o HDR10 (PQ) e o HLG aparecem lavados (cinzentos, sem cor). Quando o
 * descodificador não converte (Android 12 ou mais antigo, como a SHIELD, ou um que recuse o pedido), a GPU faz o que
 * ele faria (ver VideoGpuStage):
 *
 * 1. a curva do sinal ([pqEotf] ou [hlgEotf], esta com o OOTF do BT.2100 para uma TV de [HLG_PEAK] nits) → luz linear
 *    em nits, nas cores do BT.2020;
 * 2. o EETF do BT.2390 (o "tone-mapping=bt.2390" do mpv/libplacebo, ver [bt2390]) na luminância, no domínio do PQ:
 *    leva o pico do vídeo ([SOURCE_PEAK], os 1000 nits habituais quando a emissão não diz) ao pico do SDR
 *    ([TARGET_PEAK], o branco de referência do BT.2408), e as cores escalam pela mesma razão (sem mudar o tom);
 * 3. BT.2020 → BT.709 ([BT2020_TO_BT709], calculada das primárias) e corta-se o que fica fora;
 * 4. a curva da TV SDR ([bt1886Inverse]: o inverso do gama 2,4 do BT.1886, o que a TV desfaz).
 *
 * As contas estão aqui em Kotlin (com testes, em double) e o GLSL ([glsl]) é gerado com as mesmas constantes.
 */
object ToneMapping {
    const val NONE = 0
    const val PQ = 1
    const val HLG = 2

    /** Os códigos do H.273 (VideoInfo.transfer). */
    const val TRANSFER_PQ = 16
    const val TRANSFER_HLG = 18

    /** O pico do PQ (o 1,0 do sinal). */
    const val PQ_MAX_NITS = 10_000.0
    /** O pico do vídeo HDR10 quando não se sabe (o MaxCLL não vem no SPS): o dos masters habituais. */
    const val SOURCE_PEAK = 1000.0
    /** A TV de referência do HLG (BT.2100: gama do sistema 1,2 a 1000 nits). */
    const val HLG_PEAK = 1000.0
    const val HLG_GAMMA = 1.2
    /** O branco do SDR, em nits do HDR (BT.2408: o branco de referência, 203 nits = 100 % no SDR). */
    const val TARGET_PEAK = 203.0

    // PQ (SMPTE ST 2084).
    const val PQ_M1 = 2610.0 / 16384.0
    const val PQ_M2 = 2523.0 / 4096.0 * 128.0
    const val PQ_C1 = 3424.0 / 4096.0
    const val PQ_C2 = 2413.0 / 4096.0 * 32.0
    const val PQ_C3 = 2392.0 / 4096.0 * 32.0

    // HLG (ARIB STD-B67 / BT.2100).
    const val HLG_A = 0.17883277
    const val HLG_B = 0.28466892
    const val HLG_C = 0.55991073

    /** A luminância no BT.2020 (a linha Y da matriz RGB → XYZ). */
    val BT2020_LUMA = doubleArrayOf(0.2627, 0.6780, 0.0593)

    /**
     * O que fazer a este vídeo: [PQ] ou [HLG] quando o vídeo de curva [transfer] (H.273) tem de sair em SDR
     * ([needsSdr], o HdrType.needsSdr) e o descodificador não o converteu ([decoderConverted]). [force] (testes,
     * `debug.goattv.tonemap`): sempre, e num vídeo SDR trata-se como PQ (vê-se escuro, mas vê-se o caminho).
     */
    fun mode(transfer: Int, needsSdr: Boolean, decoderConverted: Boolean, force: Boolean = false): Int {
        val curve = when (transfer) {
            TRANSFER_PQ -> PQ
            TRANSFER_HLG -> HLG
            else -> NONE
        }
        if (force) return if (curve == NONE) PQ else curve
        if (curve == NONE || !needsSdr || decoderConverted) return NONE
        return curve
    }

    fun name(mode: Int): String = when (mode) {
        PQ -> "pq"
        HLG -> "hlg"
        else -> "none"
    }

    /** O sinal PQ [e] (0..1) → nits (0..10000). */
    fun pqEotf(e: Double): Double {
        val p = max(e, 0.0).pow(1.0 / PQ_M2)
        return (max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p)).pow(1.0 / PQ_M1) * PQ_MAX_NITS
    }

    /** Nits → o sinal PQ (o inverso de [pqEotf]). */
    fun pqInverse(nits: Double): Double {
        val y = (max(nits, 0.0) / PQ_MAX_NITS).pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y)).pow(PQ_M2)
    }

    /** O sinal HLG [e] (0..1) → a luz da cena (0..1): o inverso do OETF. */
    fun hlgInverseOetf(e: Double): Double {
        val v = max(e, 0.0)
        return if (v <= 0.5) v * v / 3.0 else (exp((v - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    /** O sinal HLG → a luz da cena (o OETF), para os testes. */
    fun hlgOetf(scene: Double): Double =
        if (scene <= 1.0 / 12.0) kotlin.math.sqrt(3.0 * scene) else HLG_A * ln(12.0 * scene - HLG_B) + HLG_C

    /**
     * O HLG em nits numa TV de [HLG_PEAK]: a luz da cena de cada cor ([r], [g], [b], já pelo [hlgInverseOetf]) vezes
     * a luminância dela elevada a gama − 1 (o OOTF do BT.2100).
     */
    fun hlgOotf(r: Double, g: Double, b: Double): DoubleArray {
        val y = BT2020_LUMA[0] * r + BT2020_LUMA[1] * g + BT2020_LUMA[2] * b
        val k = HLG_PEAK * max(y, 0.0).pow(HLG_GAMMA - 1.0)
        return doubleArrayOf(k * r, k * g, k * b)
    }

    /** Um cinzento HLG [e] em nits (o sinal todo igual nas três cores). */
    fun hlgEotf(e: Double): Double = hlgOotf(hlgInverseOetf(e), hlgInverseOetf(e), hlgInverseOetf(e))[0]

    /**
     * O EETF do BT.2390 (sem levantar o preto): a luminância [nits] de um vídeo com pico [sourcePeak] para uma TV com
     * pico [targetPeak]. Até ao joelho fica igual; daí ao pico do vídeo, uma curva de Hermite que chega ao da TV.
     */
    fun bt2390(nits: Double, sourcePeak: Double = SOURCE_PEAK, targetPeak: Double = TARGET_PEAK): Double {
        val srcPq = pqInverse(sourcePeak)
        val maxLum = pqInverse(targetPeak) / srcPq
        val e = min(pqInverse(nits) / srcPq, 1.0)
        val ks = 1.5 * maxLum - 0.5
        val out = if (e < ks) {
            e
        } else {
            val t = (e - ks) / (1.0 - ks)
            val t2 = t * t
            val t3 = t2 * t
            (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum
        }
        return pqEotf(out * srcPq)
    }

    /** Linear (0..1) → o sinal para a TV SDR (o inverso do BT.1886, gama 2,4). */
    fun bt1886Inverse(linear: Double): Double = min(max(linear, 0.0), 1.0).pow(1.0 / 2.4)

    /**
     * O sinal [rgb] (0..1, BT.2020, curva [mode]) → o sinal SDR BT.709 (0..1): o que o shader faz, píxel a píxel.
     */
    fun toSdr(rgb: DoubleArray, mode: Int): DoubleArray {
        val lin = if (mode == HLG) {
            hlgOotf(hlgInverseOetf(rgb[0]), hlgInverseOetf(rgb[1]), hlgInverseOetf(rgb[2]))
        } else {
            doubleArrayOf(pqEotf(rgb[0]), pqEotf(rgb[1]), pqEotf(rgb[2]))
        }
        val peak = if (mode == HLG) HLG_PEAK else SOURCE_PEAK
        val y = BT2020_LUMA[0] * lin[0] + BT2020_LUMA[1] * lin[1] + BT2020_LUMA[2] * lin[2]
        val scale = if (y > 0.0) bt2390(y, peak, TARGET_PEAK) / y / TARGET_PEAK else 0.0
        val m = BT2020_TO_BT709
        return DoubleArray(3) { i ->
            val v = (m[i * 3] * lin[0] + m[i * 3 + 1] * lin[1] + m[i * 3 + 2] * lin[2]) * scale
            bt1886Inverse(v)
        }
    }

    /** RGB linear BT.2020 → RGB linear BT.709 (por linhas), calculada das primárias e do branco D65. */
    val BT2020_TO_BT709: DoubleArray by lazy {
        val d65 = doubleArrayOf(0.3127, 0.3290)
        val to709 = invert3(rgbToXyz(doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060), d65))
        multiply3(to709, rgbToXyz(doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046), d65))
    }

    /** A matriz RGB → XYZ (por linhas) das primárias [xy] (xr, yr, xg, yg, xb, yb) com o branco [white]. */
    internal fun rgbToXyz(xy: DoubleArray, white: DoubleArray): DoubleArray {
        fun col(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val r = col(xy[0], xy[1])
        val g = col(xy[2], xy[3])
        val b = col(xy[4], xy[5])
        val p = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val w = col(white[0], white[1])
        val inv = invert3(p)
        val s = DoubleArray(3) { i -> inv[i * 3] * w[0] + inv[i * 3 + 1] * w[1] + inv[i * 3 + 2] * w[2] }
        return DoubleArray(9) { i -> p[i] * s[i % 3] }
    }

    internal fun multiply3(a: DoubleArray, b: DoubleArray): DoubleArray =
        DoubleArray(9) { i -> (0 until 3).sumOf { k -> a[(i / 3) * 3 + k] * b[k * 3 + i % 3] } }

    internal fun invert3(m: DoubleArray): DoubleArray {
        val (a, b, c, d, e) = m
        val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }

    /** Um número para o GLSL (sempre com o ponto). */
    internal fun f(v: Double): String {
        val s = v.toString()
        return if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
    }

    /**
     * As funções GLSL (ES 1.0, highp) que fazem o [toSdr]: `vec3 toneMap(vec3 rgb)` para a curva [mode]. Os pow com
     * a base presa a ≥ 0 (pow de negativos não está definido no GLSL).
     */
    fun glsl(mode: Int): String = buildString {
        val m = BT2020_TO_BT709
        val peak = if (mode == HLG) HLG_PEAK else SOURCE_PEAK
        val srcPq = pqInverse(peak)
        append("const highp float PQ_M1 = ${f(PQ_M1)};\n")
        append("const highp float PQ_M2 = ${f(PQ_M2)};\n")
        append("const highp float PQ_C1 = ${f(PQ_C1)};\n")
        append("const highp float PQ_C2 = ${f(PQ_C2)};\n")
        append("const highp float PQ_C3 = ${f(PQ_C3)};\n")
        append("const highp float SRC_PQ = ${f(srcPq)};\n")
        append("const highp float MAX_LUM = ${f(pqInverse(TARGET_PEAK) / srcPq)};\n")
        append("const highp vec3 LUMA = vec3(${f(BT2020_LUMA[0])}, ${f(BT2020_LUMA[1])}, ${f(BT2020_LUMA[2])});\n")
        // O GLSL lê as matrizes por colunas: a transposta da nossa (por linhas).
        append("const highp mat3 GAMUT = mat3(")
        append((0 until 9).joinToString(", ") { k -> f(m[(k % 3) * 3 + k / 3]) })
        append(");\n")
        // Em unidades de 10000 nits (o 1,0 do PQ), para não passar do alcance dos floats.
        append("highp vec3 pqEotf(highp vec3 e) {\n")
        append("  highp vec3 p = pow(max(e, 0.0), vec3(1.0 / PQ_M2));\n")
        append("  return pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), vec3(1.0 / PQ_M1));\n}\n")
        append("highp float pqEotf1(highp float e) {\n")
        append("  highp float p = pow(max(e, 0.0), 1.0 / PQ_M2);\n")
        append("  return pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), 1.0 / PQ_M1);\n}\n")
        append("highp float pqInverse(highp float y) {\n")
        append("  highp float v = pow(max(y, 0.0), PQ_M1);\n")
        append("  return pow((PQ_C1 + PQ_C2 * v) / (1.0 + PQ_C3 * v), PQ_M2);\n}\n")
        append("highp float bt2390(highp float y) {\n")
        append("  highp float e = min(pqInverse(y) / SRC_PQ, 1.0);\n")
        append("  highp float ks = 1.5 * MAX_LUM - 0.5;\n")
        append("  if (e >= ks) {\n")
        append("    highp float t = (e - ks) / (1.0 - ks);\n")
        append("    highp float t2 = t * t;\n")
        append("    highp float t3 = t2 * t;\n")
        append("    e = (2.0 * t3 - 3.0 * t2 + 1.0) * ks + (t3 - 2.0 * t2 + t) * (1.0 - ks) + (-2.0 * t3 + 3.0 * t2) * MAX_LUM;\n")
        append("  }\n")
        append("  return pqEotf1(e * SRC_PQ);\n}\n")
        if (mode == HLG) {
            append("highp vec3 hlgInverseOetf(highp vec3 e) {\n")
            append("  highp vec3 v = max(e, 0.0);\n")
            append("  highp vec3 lo = v * v / 3.0;\n")
            append("  highp vec3 hi = (exp((v - ${f(HLG_C)}) / ${f(HLG_A)}) + ${f(HLG_B)}) / 12.0;\n")
            append("  return mix(lo, hi, step(0.5, v));\n}\n")
        }
        append("highp vec3 toneMap(highp vec3 rgb) {\n")
        if (mode == HLG) {
            append("  highp vec3 s = hlgInverseOetf(rgb);\n")
            append("  highp vec3 lin = s * (${f(HLG_PEAK / PQ_MAX_NITS)} * pow(max(dot(LUMA, s), 0.0), ${f(HLG_GAMMA - 1.0)}));\n")
        } else {
            append("  highp vec3 lin = pqEotf(rgb);\n")
        }
        append("  highp float y = dot(LUMA, lin);\n")
        append("  highp float k = y > 0.0 ? bt2390(y) / y * ${f(PQ_MAX_NITS / TARGET_PEAK)} : 0.0;\n")
        append("  highp vec3 sdr = clamp(GAMUT * lin * k, 0.0, 1.0);\n")
        append("  return pow(sdr, vec3(${f(1.0 / 2.4)}));\n}\n")
    }
}
