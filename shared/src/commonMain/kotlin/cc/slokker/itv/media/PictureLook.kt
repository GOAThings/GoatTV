package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs

/**
 * Os ajustes da imagem (Definições → Imagem): o brilho, o contraste, a saturação e a nitidez, com as mesmas contas no
 * Android (um shader GLES sobre RGB, ver VideoGpuStage) e na Apple (um compute shader de Metal sobre Y e CbCr, ver
 * InterlacedVideo). Aqui ficam só os números; cada plataforma desenha.
 *
 * Cada definição vai de −5 a +5 (a nitidez de 0 a 5), 0 = a imagem como vem:
 * - o contraste multiplica à volta do cinzento médio ([gain]: 0,70 a 1,30);
 * - o brilho soma ([lift]: −0,15 a +0,15 de uma escala de 0 a 1);
 * - a saturação afasta ou aproxima cada cor do seu cinzento ([saturation]: 0,50 a 1,50);
 * - a nitidez junta à imagem a diferença para a média dos quatro vizinhos ([sharpen]: 0 a 0,75).
 *
 * Em RGB: `cor' = gain · S · cor + bias`, com S a matriz da saturação ([rgbMatrix] já com o ganho). Em YCbCr é o mesmo
 * (a conversão é linear): `Y' = gain · Y + lumaBias`, `C' = (C − ½) · chromaGain + ½`.
 *
 * O HDR e os 10 bits não passam por aqui ([applies]): o caminho deles (a TV em HDR, os 10 bits até ao ecrã) é direto.
 */
class PictureLook(brightness: Int = 0, contrast: Int = 0, saturation: Int = 0, sharpness: Int = 0) {
    val brightness: Int = brightness.coerceIn(-Prefs.Picture.MAX, Prefs.Picture.MAX)
    val contrast: Int = contrast.coerceIn(-Prefs.Picture.MAX, Prefs.Picture.MAX)
    val saturationStep: Int = saturation.coerceIn(-Prefs.Picture.MAX, Prefs.Picture.MAX)
    val sharpness: Int = sharpness.coerceIn(0, Prefs.Picture.MAX)

    /** Tudo no 0: a imagem vai como vem (sem passar pela GPU). */
    val neutral: Boolean get() = brightness == 0 && contrast == 0 && saturationStep == 0 && sharpness == 0

    /** O contraste: quanto se multiplica à volta do cinzento médio. */
    val gain: Float get() = 1f + contrast * CONTRAST_STEP

    /** O brilho: quanto se soma (numa escala de 0 a 1). */
    val lift: Float get() = brightness * BRIGHTNESS_STEP

    /** A saturação: 0 = a preto e branco, 1 = como vem. */
    val saturation: Float get() = 1f + saturationStep * SATURATION_STEP

    /** A nitidez: quanto da diferença para os vizinhos se junta (0 = nada). */
    val sharpen: Float get() = sharpness * SHARPEN_STEP

    /** O que se soma a cada cor em RGB (ou ao Y de gama inteira), depois do ganho: o contraste roda no ½ e o brilho soma. */
    val bias: Float get() = 0.5f * (1f - gain) + lift

    /**
     * A matriz 3×3 que se aplica ao RGB (por colunas, como o GLES e o Metal as querem): a saturação (BT.709) já com o
     * ganho do contraste. Falta só somar [bias].
     */
    fun rgbMatrix(): FloatArray {
        val s = saturation
        val g = gain
        val m = FloatArray(9)
        for (column in 0 until 3) {
            for (row in 0 until 3) {
                val grey = LUMA[column] * (1f - s)
                m[column * 3 + row] = g * (grey + if (row == column) s else 0f)
            }
        }
        return m
    }

    /** O que se soma ao Y (0 a 1 da gama do formato): na gama limitada (16–235) o preto está nos 16 e a escala é de 219. */
    fun lumaBias(fullRange: Boolean): Float =
        if (fullRange) bias else LIMITED_BLACK * (1f - gain) + bias * LIMITED_SCALE

    /** Quanto se multiplica a cor (Cb e Cr, à volta do ½): o contraste e a saturação. */
    val chromaGain: Float get() = gain * saturation

    /**
     * Este vídeo passa pelos ajustes: só com algum fora do 0, em 8 bits e SDR, e até [maxHeight] linhas (0 = sem
     * limite; na Apple, [APPLE_MAX_HEIGHT]).
     */
    fun applies(bitDepth: Int, hdr: Boolean, height: Int = 0, maxHeight: Int = 0): Boolean =
        !neutral && !hdr && bitDepth <= 8 && (maxHeight <= 0 || height <= maxHeight)

    override fun equals(other: Any?): Boolean =
        other is PictureLook && other.brightness == brightness && other.contrast == contrast &&
            other.saturationStep == saturationStep && other.sharpness == sharpness

    override fun hashCode(): Int = ((brightness * 31 + contrast) * 31 + saturationStep) * 31 + sharpness

    override fun toString(): String = "brightness=$brightness contrast=$contrast saturation=$saturationStep sharpness=$sharpness"

    companion object {
        const val CONTRAST_STEP = 0.06f
        const val BRIGHTNESS_STEP = 0.03f
        const val SATURATION_STEP = 0.1f
        const val SHARPEN_STEP = 0.15f

        /** O peso de cada cor no cinzento (BT.709). */
        val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

        /** A gama limitada do vídeo: o preto nos 16 e o branco nos 235 (de 255). */
        const val LIMITED_BLACK = 16f / 255f
        const val LIMITED_SCALE = 219f / 255f

        /**
         * Na Apple os ajustes obrigam a descodificar na app (VideoToolbox) e a passar cada imagem pelo Metal antes da
         * camada: até 1080 linhas (o Apple TV HD aguenta); o 4K fica como vem.
         */
        const val APPLE_MAX_HEIGHT = 1088

        /** Os ajustes das definições [prefs]. */
        fun of(prefs: Prefs): PictureLook =
            PictureLook(prefs.pictureBrightness, prefs.pictureContrast, prefs.pictureSaturation, prefs.pictureSharpness)

        /**
         * A conta inteira num píxel RGB (0 a 1), como os shaders a fazem: para os testes e para quem não tem GPU.
         * [around]: a média dos quatro vizinhos (para a nitidez); null = sem vizinhos.
         */
        fun apply(look: PictureLook, rgb: FloatArray, around: FloatArray? = null): FloatArray {
            val k = look.sharpen
            val c = FloatArray(3) { i -> if (around == null) rgb[i] else (rgb[i] + k * (rgb[i] - around[i])).coerceIn(0f, 1f) }
            val m = look.rgbMatrix()
            return FloatArray(3) { row -> (m[row] * c[0] + m[3 + row] * c[1] + m[6 + row] * c[2] + look.bias).coerceIn(0f, 1f) }
        }
    }
}
