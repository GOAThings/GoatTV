@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.createBitmap
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.playback.SubtitleLook
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * SurfaceView centrada e com a proporção do vídeo. Permite copiar o frame atual
 * (PixelCopy, Android 7+) para o vidro desfocado, para manter a imagem ao mudar de canal e para a guardar
 * ("Guardar imagem", ver PictureSaver).
 * Com [rounded] usa uma TextureView: desenha-se com o resto da janela, por isso dá para lhe arredondar os cantos
 * ([cornerRadius]) por cima de outro vídeo (as imagens pequenas da imagem na imagem).
 */
class VideoView(context: Context, overlay: Boolean = false, rounded: Boolean = false) : FrameLayout(context) {

    /** Com [overlay], a imagem fica por cima da do outro VideoView (o segundo canal em imagem na imagem). */
    val surfaceView: SurfaceView? = if (rounded) null else SurfaceView(context).apply { if (overlay) setZOrderMediaOverlay(true) }

    private val textureView: TextureView? = if (rounded) TextureView(context) else null

    /** A vista onde o vídeo se desenha. */
    private val video: View = surfaceView ?: textureView!!

    /** Liga [player] a esta imagem. */
    fun attach(player: Player) {
        surfaceView?.let { player.setVideoSurfaceView(it) } ?: player.setVideoTextureView(textureView)
    }

    /** Legendas (DVB, teletexto, CEA-608), por cima do vídeo e com a mesma proporção. Mostram-se com [showCues]. */
    val subtitleView = SubtitleView(context)

    /** O aspeto escolhido nas definições (Prefs.subtitleSize/Style/Position; ver SubtitleLook). */
    private var look = Triple(Prefs.SubtitleSize.NORMAL, Prefs.SubtitleStyle.WHITE_BOX, Prefs.SubtitlePosition.NORMAL)

    /** As últimas legendas, como chegaram (para as redesenhar quando o aspeto muda). */
    private var lastCues: List<Cue> = emptyList()

    /**
     * Muda o aspeto das legendas: o tamanho e o sítio valem para todas, a cor e o fundo só para as de texto. No padrão
     * fica o de sempre do SubtitleView (branco numa caixa preta, 5,33 % da altura, 8 % acima do fundo).
     */
    fun setSubtitleLook(size: String, style: String, position: String) {
        val next = Triple(size, style, position)
        if (next == look) return
        look = next
        val color = SubtitleLook.textColor(style).toInt()
        subtitleView.setStyle(
            if (SubtitleLook.boxed(style)) {
                CaptionStyleCompat(color, Color.BLACK, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.WHITE, null)
            } else {
                CaptionStyleCompat(color, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null)
            },
        )
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SubtitleLook.scale(size))
        subtitleView.setBottomPaddingFraction(SubtitleLook.gap(position))
        showCues(lastCues)
    }

    /** Mostra [cues] com o aspeto escolhido: as imagens (DVB) crescem e sobem aqui, o texto pelo estilo do SubtitleView. */
    fun showCues(cues: List<Cue>) {
        lastCues = cues
        val (size, _, position) = look
        subtitleView.setCues(cues.map { if (it.bitmap != null) placeBitmap(it, size, position) else it })
    }

    private fun placeBitmap(cue: Cue, size: String, position: String): Cue {
        // O DvbParser dá tudo em fração do ecrã do emissor, a partir do canto de cima à esquerda; sem isso, fica como vem.
        if (cue.position == Cue.DIMEN_UNSET || cue.line == Cue.DIMEN_UNSET || cue.size == Cue.DIMEN_UNSET || cue.bitmapHeight == Cue.DIMEN_UNSET) return cue
        if (cue.lineType != Cue.LINE_TYPE_FRACTION || cue.positionAnchor != Cue.ANCHOR_TYPE_START || cue.lineAnchor != Cue.ANCHOR_TYPE_START) return cue
        val r = SubtitleLook.place(cue.position, cue.line, cue.size, cue.bitmapHeight, size, position)
        return cue.buildUpon().setPosition(r.x).setLine(r.y, Cue.LINE_TYPE_FRACTION).setSize(r.width).setBitmapHeight(r.height).build()
    }

    private val frame = AspectRatioFrameLayout(context).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        video.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.getLocationInWindow(location)
            onBoundsChanged?.invoke(
                Rect(location[0].toFloat(), location[1].toFloat(), (location[0] + v.width).toFloat(), (location[1] + v.height).toFloat()),
            )
        }
    }

    fun setAspectRatio(ratio: Float) = frame.setAspectRatio(ratio)

    /** Cantos arredondados (px; 0 = retos): corta a própria imagem, também por cima de outro vídeo. */
    var cornerRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            clipToOutline = value > 0f
            outlineProvider = if (value > 0f) {
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, field)
                }
            } else {
                ViewOutlineProvider.BACKGROUND
            }
            invalidateOutline()
        }

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
    suspend fun capture(width: Int, height: Int, into: Bitmap? = null, timeoutMs: Long = 250): Bitmap? {
        textureView?.let { tv -> return if (tv.isAvailable) tv.getBitmap(into?.takeIf { it.width == width && it.height == height } ?: createBitmap(width, height)) else null }
        val surfaceView = surfaceView ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        if (surfaceView.width == 0 || !surfaceView.holder.surface.isValid) return null
        return withTimeoutOrNull(timeoutMs) {
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
