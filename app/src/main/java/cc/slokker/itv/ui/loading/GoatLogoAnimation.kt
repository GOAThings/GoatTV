package cc.slokker.itv.ui.loading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.R
import cc.slokker.itv.ui.theme.ItvColors
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * O logo GOAT TV animado, feito com as camadas do logo original (1376×768 px, ver res/drawable-nodpi/goattv_*):
 * o fundo, as letras G-O-A-T e o T e o V grandes, cada uma com o seu brilho. As letras são "luz" sobre o fundo
 * (o logo menos o fundo) e desenham-se a somar (BlendMode.Plus), como no original. As máscaras *_core são só a
 * forma do T e do V, para a faixa de luz passar por cima das letras e não do brilho.
 */

private const val FRAME_W = 1376f
private const val FRAME_H = 768f

/** Parte da largura do ecrã ocupada pelo logo original: num ecrã de 1080p fica quase ao tamanho real. */
private const val FRAME_FRACTION = 0.72f

/** Onde acaba o TV (no logo original): a linha de progresso e o texto ficam por baixo. */
private const val TV_BOTTOM = 543f

/** A abertura acaba aqui (em segundos): o logo fica parado, igual ao do ecrã de carregamento. */
private const val INTRO_END = 2.2f

private class Layer(val image: ImageBitmap, val x: Float, val y: Float)

private class LogoImages(
    val background: ImageBitmap,
    val goat: List<Layer>,
    val tvT: Layer,
    val tvV: Layer,
    val coreT: Layer,
    val coreV: Layer,
)

@Composable
private fun rememberLogoImages(): LogoImages {
    val bg = ImageBitmap.imageResource(R.drawable.goattv_bg)
    val g = ImageBitmap.imageResource(R.drawable.goattv_g)
    val o = ImageBitmap.imageResource(R.drawable.goattv_o)
    val a = ImageBitmap.imageResource(R.drawable.goattv_a)
    val t = ImageBitmap.imageResource(R.drawable.goattv_t)
    val tvT = ImageBitmap.imageResource(R.drawable.goattv_tv_t)
    val tvV = ImageBitmap.imageResource(R.drawable.goattv_tv_v)
    val coreT = ImageBitmap.imageResource(R.drawable.goattv_tv_t_core)
    val coreV = ImageBitmap.imageResource(R.drawable.goattv_tv_v_core)
    // Posições no logo original (impressas pelo script que fez as camadas).
    return remember(bg) {
        LogoImages(
            background = bg,
            goat = listOf(Layer(g, 424f, 179f), Layer(o, 551f, 179f), Layer(a, 678f, 179f), Layer(t, 805f, 179f)),
            tvT = Layer(tvT, 423f, 304f),
            tvV = Layer(tvV, 664f, 304f),
            coreT = Layer(coreT, 423f, 304f),
            coreV = Layer(coreV, 664f, 304f),
        )
    }
}

/**
 * Segundos de animação, atualizados a cada frame (só o desenho os lê). Cada frame conta no máximo [MAX_STEP]:
 * no arranque a app engasga (canais, guia) e, com o tempo real, a abertura saltava meio caminho.
 */
@Composable
private fun rememberClock(): State<Float> = produceState(0f) {
    var last = withFrameNanos { it }
    while (true) {
        withFrameNanos { now ->
            value += min((now - last) / 1_000_000_000f, MAX_STEP)
            last = now
        }
    }
}

private const val MAX_STEP = 0.05f

/**
 * A abertura da app: as letras do GOAT abrem a partir do centro, o T e o V sobem e acendem, passa uma luz pelo TV.
 * Acaba com o logo parado no mesmo sítio do [GoatLoading] e chama [onDone] (quem a mostra faz o fade de saída).
 */
@Composable
fun GoatIntro(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val images = rememberLogoImages()
    val clock = rememberClock()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        snapshotFlow { clock.value }.first { it >= INTRO_END }
        done()
    }
    Canvas(modifier.fillMaxSize()) { drawLogo(images, clock.value, intro = true) }
}

/** Ecrã de carregamento: o logo com a luz a passar de vez em quando, uma linha de progresso e [message] por baixo. */
@Composable
fun GoatLoading(message: String, modifier: Modifier = Modifier) {
    val images = rememberLogoImages()
    val clock = rememberClock()
    BoxWithConstraints(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawLogo(images, clock.value, intro = false) }
        if (message.isNotEmpty()) {
            val scale = constraints.maxWidth * FRAME_FRACTION / FRAME_W
            val top = with(LocalDensity.current) { ((constraints.maxHeight - FRAME_H * scale) / 2 + (TV_BOTTOM + 70f) * scale).toDp() + 28.dp }
            Text(
                message,
                Modifier.align(Alignment.TopCenter).padding(top = top, start = 48.dp, end = 48.dp),
                color = ItvColors.TextDim,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

private fun clamp01(v: Float) = min(1f, max(0f, v))
private fun easeOut(t: Float): Float = clamp01(t).let { 1 - (1 - it) * (1 - it) * (1 - it) }
private fun easeInOut(t: Float): Float = clamp01(t).let { it * it * (3 - 2 * it) }

private fun DrawScope.drawLogo(img: LogoImages, t: Float, intro: Boolean) {
    // Fundo: o do logo, a encher o ecrã (na abertura, acende a partir do preto).
    drawRect(Color.Black)
    val bgScale = max(size.width / img.background.width, size.height / img.background.height)
    val bgSize = IntSize((img.background.width * bgScale).roundToInt(), (img.background.height * bgScale).roundToInt())
    drawImage(
        img.background,
        dstOffset = IntOffset(((size.width - bgSize.width) / 2).roundToInt(), ((size.height - bgSize.height) / 2).roundToInt()),
        dstSize = bgSize,
        alpha = if (intro) easeInOut(t / 0.35f) else 1f,
        filterQuality = FilterQuality.High,
    )

    val scale = size.width * FRAME_FRACTION / FRAME_W
    val ox = (size.width - FRAME_W * scale) / 2
    val oy = (size.height - FRAME_H * scale) / 2

    // A soma de várias passagens dá o pico de brilho (k > 1) ao acender.
    fun layer(l: Layer, dx: Float = 0f, dy: Float = 0f, k: Float = 1f) {
        var left = k
        while (left > 0.001f) {
            drawImage(
                l.image,
                dstOffset = IntOffset((ox + (l.x + dx) * scale).roundToInt(), (oy + (l.y + dy) * scale).roundToInt()),
                dstSize = IntSize((l.image.width * scale).roundToInt(), (l.image.height * scale).roundToInt()),
                alpha = min(left, 1f),
                blendMode = BlendMode.Plus,
                filterQuality = FilterQuality.High,
            )
            left -= 1f
        }
    }

    // G-O-A-T: na abertura, abrem a partir do centro, uma de cada vez.
    val centre = FRAME_W / 2
    img.goat.forEachIndexed { i, l ->
        if (intro) {
            val e = easeOut((t - 0.25f - i * 0.07f) / 0.7f)
            val letterCentre = l.x + l.image.width / 2f
            val x = centre + (letterCentre - centre) * (0.6f + 0.4f * e) - l.image.width / 2f
            layer(l, dx = x - l.x, k = e)
        } else {
            layer(l)
        }
    }
    // T e V: sobem e acendem, um a seguir ao outro, com um pico de brilho.
    listOf(img.tvT, img.tvV).forEachIndexed { j, l ->
        if (intro) {
            val start = 0.55f + j * 0.15f
            val e = easeOut((t - start) / 0.6f)
            val glow = 1f + 0.35f * sin(PI.toFloat() * clamp01((t - start) / 0.9f))
            layer(l, dy = 30f * (1 - e), k = e * glow)
        } else {
            layer(l)
        }
    }

    if (intro) {
        if (t in 1.25f..1.95f) drawShine(img, scale, ox, oy, (t - 1.25f) / 0.7f, 0.8f)
    } else {
        val p = (t % 1.8f) / 1.8f
        if (p < 0.6f) drawShine(img, scale, ox, oy, p / 0.6f, 0.65f)
        drawProgress(scale, ox, oy, t)
    }
}

/** Uma faixa de luz inclinada que atravessa o T e o V ([p] de 0 a 1), só por cima das letras. */
private fun DrawScope.drawShine(img: LogoImages, scale: Float, ox: Float, oy: Float, p: Float, strength: Float) {
    val left = ox + img.coreT.x * scale
    val right = ox + (img.coreV.x + img.coreV.image.width) * scale
    val top = oy + img.coreT.y * scale
    val bottom = oy + (img.coreT.y + max(img.coreT.image.height, img.coreV.image.height)) * scale
    val span = right - left
    val centre = left - 0.2f * span + p * 1.4f * span
    val half = span * 0.12f
    // Linhas de igual luz inclinadas: x + 0,35·y constante (como no mockup).
    val dir = Offset(1f, 0.35f) / 1.1225f
    val light = Color(0.85f, 0.95f, 1f)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(left, top, right, bottom), Paint().apply { blendMode = BlendMode.Plus })
        for (core in listOf(img.coreT, img.coreV)) {
            drawImage(
                core.image,
                dstOffset = IntOffset((ox + core.x * scale).roundToInt(), (oy + core.y * scale).roundToInt()),
                dstSize = IntSize((core.image.width * scale).roundToInt(), (core.image.height * scale).roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        drawRect(
            Brush.linearGradient(
                0f to light.copy(alpha = 0f),
                0.3f to light.copy(alpha = strength * 0.3f),
                0.5f to light.copy(alpha = strength),
                0.7f to light.copy(alpha = strength * 0.3f),
                1f to light.copy(alpha = 0f),
                start = Offset(centre - half, top),
                end = Offset(centre - half, top) + dir * (2 * half),
            ),
            topLeft = Offset(left, top),
            size = Size(span, bottom - top),
            blendMode = BlendMode.SrcIn,
        )
        canvas.restore()
    }
}

/**
 * Linha de progresso fina por baixo do TV: uma luz que anda sempre para a direita, a velocidade constante, e ao
 * chegar ao fim volta a entrar pelo início (desenha-se também a cópia que está a entrar). As pontas desvanecem.
 */
private fun DrawScope.drawProgress(scale: Float, ox: Float, oy: Float, t: Float) {
    val wordLeft = 468f
    val wordRight = 907f
    val x0 = ox + (wordLeft + (wordRight - wordLeft) * 0.2f) * scale
    val x1 = ox + (wordRight - (wordRight - wordLeft) * 0.2f) * scale
    val y = oy + (TV_BOTTOM + 70f) * scale
    val h = max(2f, 4f * scale)
    val span = x1 - x0
    val bar = Rect(x0, y, x1, y + h)
    val light = Color(0.45f, 0.8f, 1f)
    val w = span * 0.3f
    val a = x0 + span * ((t % PROGRESS_LOOP) / PROGRESS_LOOP)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bar, Paint().apply { blendMode = BlendMode.Plus })
        drawRect(Color.White.copy(alpha = 0.10f), bar.topLeft, bar.size)
        for (centre in listOf(a, a - span)) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = centre - w, endX = centre + w),
                topLeft = bar.topLeft,
                size = bar.size,
            )
        }
        // As pontas desvanecem: a luz entra e sai sem se ver o corte.
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent, startX = x0, endX = x1),
            topLeft = bar.topLeft,
            size = bar.size,
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}

/** Quanto tempo a luz da linha de progresso leva a ir de uma ponta à outra. */
private const val PROGRESS_LOOP = 1.6f
