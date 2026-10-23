@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * SurfaceView centrada e com a proporção do vídeo. Permite copiar o frame atual
 * (PixelCopy, Android 7+) para o vidro desfocado e para manter a imagem ao mudar de canal.
 */
class VideoView(context: Context, overlay: Boolean = false) : FrameLayout(context) {

    /** Com [overlay], a imagem fica por cima da do outro VideoView (o segundo canal em imagem na imagem). */
    val surfaceView = SurfaceView(context).apply { if (overlay) setZOrderMediaOverlay(true) }

    /** Legendas (DVB, teletexto, CEA-608), por cima do vídeo e com a mesma proporção. */
    val subtitleView = SubtitleView(context)

    private val frame = AspectRatioFrameLayout(context).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val copyThread = HandlerThread("itv-pixelcopy").apply { start() }
    private val handler = Handler(copyThread.looper)
    private val location = IntArray(2)

    /** Chamado sempre que a área do vídeo muda (coordenadas da janela). */
    var onBoundsChanged: ((Rect) -> Unit)? = null

    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        surfaceView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.getLocationInWindow(location)
            onBoundsChanged?.invoke(
                Rect(location[0].toFloat(), location[1].toFloat(), (location[0] + v.width).toFloat(), (location[1] + v.height).toFloat()),
            )
        }
    }

    fun setAspectRatio(ratio: Float) = frame.setAspectRatio(ratio)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        copyThread.quitSafely()
    }

    /** Uma das constantes `AspectRatioFrameLayout.RESIZE_MODE_*`. */
    fun setResizeMode(mode: Int) {
        frame.resizeMode = mode
    }

    /**
     * Copia o frame atual reduzido a [width]×[height] (para [into], se for dado, evitando alocações);
     * null se não houver imagem ou se demorar demasiado.
     */
    suspend fun capture(width: Int, height: Int, into: Bitmap? = null): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        if (surfaceView.width == 0 || !surfaceView.holder.surface.isValid) return null
        return withTimeoutOrNull(250) {
            suspendCancellableCoroutine { cont ->
                val bitmap = into?.takeIf { it.width == width && it.height == height } ?: createBitmap(width, height)
                try {
                    PixelCopy.request(surfaceView, bitmap, { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
                    }, handler)
                } catch (e: IllegalArgumentException) {
                    cont.resume(null)
                }
            }
        }
    }
}
