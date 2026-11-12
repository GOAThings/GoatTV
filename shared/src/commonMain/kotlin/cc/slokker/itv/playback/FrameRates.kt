package cc.slokker.itv.playback

import kotlin.math.abs

/** As cadências de vídeo (o AFR): aproximar uma medida das habituais. Igual no Android e na Apple. */
object FrameRates {
    /** As cadências de TV e de cinema. */
    val STANDARD: List<Double> = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 47.952, 48.0, 50.0, 59.94, 60.0, 100.0, 119.88, 120.0)

    /** As inteiras que uma contagem não separa da fracionária da TV (0,1 % de diferença). */
    private val FRACTIONAL = mapOf(24.0 to 23.976, 30.0 to 29.97, 48.0 to 47.952, 60.0 to 59.94, 120.0 to 119.88)

    /** A cadência habitual mais perto de [fps] (até 1 % de diferença); fora disso, [fps] como está. */
    fun snap(fps: Double): Double {
        val best = STANDARD.minByOrNull { abs(it - fps) / it } ?: return fps
        return if (abs(best - fps) / best <= 0.01) best else fps
    }

    /**
     * Uma medida por contagem (imagens num intervalo) não separa 29,97 de 30: entre as duas fica a da TV (29,97,
     * 59,94, 23,976), que é o que as emissões usam. Até [tolerance] de diferença; fora disso, -1.
     */
    fun snapCounted(fps: Double, tolerance: Double): Double {
        val near = STANDARD.filter { abs(it - fps) / it <= tolerance }
        val best = near.minByOrNull { abs(it - fps) } ?: return -1.0
        val twin = FRACTIONAL[best]
        return if (twin != null && twin in near) twin else best
    }
}

/**
 * Quando o AFR muda o modo da TV (`afr_mode`): uma vez por canal, quando a cadência dele fica conhecida; "ao começar"
 * só no primeiro canal do player (o modo fica nos seguintes, sem o ecrã apagar 1–3 s a cada zap) até [reset] (ao
 * sair do player, quando a TV volta ao modo normal).
 */
class AfrSession {
    private var matched = false
    private var channelDone = false

    /** Outro canal: ainda não se decidiu nada para ele. */
    fun newChannel() {
        channelDone = false
    }

    /** A TV voltou ao modo normal (saiu-se do player, ou o AFR foi desligado). */
    fun reset() {
        matched = false
        channelDone = false
    }

    /** Já se sabe a cadência [fps] do canal: devolve a que se pede à TV, ou -1 para não mexer. */
    fun onFps(mode: String, fps: Double): Double {
        if (mode == MODE_OFF || channelDone || fps <= 0) return -1.0
        channelDone = true
        if (mode == MODE_START && matched) return -1.0
        matched = true
        return fps
    }

    companion object {
        /** Os valores de `afr_mode` (Prefs.AfrMode). */
        const val MODE_OFF = "off"
        const val MODE_START = "start"
    }
}
