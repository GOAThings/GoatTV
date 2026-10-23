package cc.slokker.itv.ui.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto frame rate (AFR): pede à TV o modo de imagem cuja frequência é múltipla da cadência do canal
 * (25 fps → 50 Hz, 23.976 → 23.976/47.95 Hz, 29.97 → 59.94 Hz), na mesma resolução.
 * Em TV europeia (25/50 fps) evita os soluços de mostrar 50 fps num ecrã a 60 Hz.
 * Num canal HDR prefere os modos em que a TV aceita esse HDR (Android 14+, onde cada modo o diz).
 */
class FrameRateMatcher(private val activity: Activity) {

    private var applied = false

    /** Escolhe e pede o melhor modo para [fps] (e [hdr]); não faz nada se o modo atual já servir. */
    fun match(fps: Float, hdr: HdrType? = null) {
        if (fps <= 0f) return
        val display = display() ?: return
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val candidates = if (hdr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sameSize.filter { hdr.displayType in it.supportedHdrTypes }.ifEmpty { sameSize }
        } else {
            sameSize
        }
        val best = bestMode(candidates.map { it.modeId to it.refreshRate }, fps) ?: return
        if (best == current.modeId && !applied) return
        val window = activity.window
        window.attributes = window.attributes.apply { preferredDisplayModeId = best }
        applied = true
    }

    /** Devolve a TV ao modo que o sistema escolher (ao sair do player ou da app). */
    fun restore() {
        if (!applied) return
        val window = activity.window
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        applied = false
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay

    companion object {
        /**
         * Entre (modeId, refresh) escolhe um de frequência múltipla da cadência. Prefere frequências ≥ 47 Hz
         * (25 Hz pisca em muitos ecrãs), depois o erro mais pequeno (23,976 Hz antes de 24 Hz), depois a mais baixa.
         */
        fun bestMode(modes: List<Pair<Int, Float>>, fps: Float): Int? {
            fun error(refresh: Float): Float? {
                val k = (refresh / fps).roundToInt()
                val diff = abs(refresh - k * fps) / k
                return if (k >= 1 && diff < 0.05f) diff else null
            }
            return modes
                .mapNotNull { (id, refresh) -> error(refresh)?.let { Triple(id, refresh, it) } }
                .minWithOrNull(compareBy<Triple<Int, Float, Float>>({ it.second < 47f }, { it.third }, { it.second }))
                ?.first
        }
    }
}
