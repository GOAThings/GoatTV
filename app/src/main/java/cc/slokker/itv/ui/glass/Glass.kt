package cc.slokker.itv.ui.glass

import cc.slokker.itv.ui.theme.ControlRadius
import cc.slokker.itv.ui.theme.ItvShapes
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.theme.TvSafePadding
import kotlin.math.roundToInt

/**
 * O que está por trás do vidro: uma miniatura desfocada do vídeo e onde o vídeo está no ecrã.
 * O Android só tem blur de fundo nativo a partir do 12; assim funciona em qualquer box (Android 7+).
 */
@Stable
class GlassBackdrop {
    var image by mutableStateOf<ImageBitmap?>(null)
    var videoBounds by mutableStateOf(Rect.Zero)

    /** Opacidade do véu escuro sobre o fundo desfocado (definição "Opacidade do vidro"). */
    var opacity by mutableFloatStateOf(DEFAULT_GLASS_OPACITY)
}

const val DEFAULT_GLASS_OPACITY = 0.6f

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

private val GlassTint = Color(0xFF0A0D16)

/** Cores mais vivas por trás do vidro ("vibrancy"). */
private val Vibrancy = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.7f) })

/**
 * Vidro líquido: fundo desfocado, véu escuro, reflexo de luz no topo e contorno fino.
 * Sem fundo disponível (antes do primeiro frame, ou fora do player) fica um vidro fumado.
 */
fun Modifier.glass(
    shape: Shape = ItvShapes.Panel,
    tint: Color? = null,
    fallbackTint: Color = Color(0xCC111522),
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val highlight = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.3f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent,
            )
            val rim = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f)),
            )
            val stroke = Stroke(width = 1.2.dp.toPx())
            onDrawBehind {
                clipPath(path) {
                    val image = backdrop?.image
                    val video = backdrop?.videoBounds ?: Rect.Zero
                    if (image != null && !video.isEmpty) {
                        drawImage(
                            image = image,
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset((video.left - origin.x).roundToInt(), (video.top - origin.y).roundToInt()),
                            dstSize = IntSize(video.width.roundToInt(), video.height.roundToInt()),
                            filterQuality = FilterQuality.Low,
                            colorFilter = Vibrancy,
                        )
                        drawRect(tint ?: GlassTint.copy(alpha = backdrop.opacity))
                    } else {
                        drawRect(fallbackTint)
                    }
                    drawRect(highlight)
                }
                drawOutline(outline, rim, style = stroke)
            }
        }
        // O conteúdo vai para uma camada própria: quando o fundo desfocado muda (várias vezes por segundo)
        // só o vidro é redesenhado, não as linhas, o texto e os logos que estão por cima.
        .graphicsLayer()
}

/** Fundo dos ecrãs sem vídeo: manchas de cor desfocadas a mexer devagar. O conteúdo fica dentro das margens de TV. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "t",
    )
    Box(modifier.fillMaxSize().background(ItvColors.Background)) {
        Canvas(Modifier.fillMaxSize()) {
            fun blob(color: Color, x: Float, y: Float, r: Float) = drawCircle(
                brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(x * size.width, y * size.height), radius = r * size.width),
                radius = r * size.width,
                center = Offset(x * size.width, y * size.height),
            )
            blob(ItvColors.Sky.copy(alpha = 0.30f), 0.15f + 0.1f * t, 0.2f, 0.55f)
            blob(ItvColors.Indigo.copy(alpha = 0.32f), 0.85f - 0.1f * t, 0.85f, 0.6f)
            blob(Color(0xFFC084FC).copy(alpha = 0.16f), 0.6f, 0.1f + 0.2f * t, 0.4f)
        }
        Box(Modifier.fillMaxSize().padding(TvSafePadding), content = content)
    }
}

/** Botão em cápsula de vidro; com foco fica claro, como no tvOS. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = ItvShapes.Control
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = ItvColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(text, Modifier.padding(horizontal = 28.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
    }
}

/** A [GlassRow] onde se está tem o foco (para o texto à direita também andar, ver [rowText]). */
val LocalRowFocused = compositionLocalOf { false }

/**
 * Texto numa só linha: cortado com "…", e a andar para o lado quando a linha tem o foco
 * (nomes compridos de canais, grupos e definições lêem-se inteiros sem ocupar mais espaço).
 */
@Composable
fun Modifier.rowText(): Modifier =
    // Numa camada própria: o texto a andar só redesenha a si mesmo, não o painel de vidro inteiro.
    if (LocalRowFocused.current) graphicsLayer().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = MARQUEE_DELAY_MS) else this

private const val MARQUEE_DELAY_MS = 900

/** O contorno da linha com foco em [GlassRow] com outlineFocus (o traço é cortado a meio pela forma da linha). */
private val OUTLINE_WIDTH = 2.dp
private const val OUTLINE_VEIL = 0.12f

/** Transição do destaque de foco nas linhas. */
private const val FOCUS_FADE_MS = 150

/**
 * Linha de lista (canais, grupos, definições). Com foco: plataforma clara.
 * [selected] marca o item atual (ex.: o canal a ver) com um fundo subtil.
 * O título fica sempre numa linha (anda para o lado com o foco); o [subtitle] tem até [subtitleLines]
 * linhas, e com 1 também anda.
 * [outlineFocus]: com foco, só um contorno branco (e um véu leve) em vez da placa branca; o texto fica claro.
 * Na lista de canais, assim os logótipos brancos continuam a ver-se sem placa por trás.
 */
@Composable
fun GlassRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    subtitleLines: Int = 2,
    enabled: Boolean = true,
    outlineFocus: Boolean = false,
    title: String,
) {
    var focused by remember { mutableStateOf(false) }
    // Como o menu do GoatLauncher: a linha com foco fica branca com texto escuro; a selecionada
    // (ex.: o canal a ver) fica com um fundo claro translúcido.
    // O tv-material só anima o tamanho: a placa branca aparece aqui com uma transição curta, para o
    // destaque não saltar de linha em linha enquanto a lista desliza. Pinta-se só no desenho (sem
    // recompor a linha a cada frame) e o texto troca de cor uma vez, a meio.
    val fade = animateFloatAsState(if (focused && enabled) 1f else 0f, tween(FOCUS_FADE_MS), label = "row")
    val dark by remember(outlineFocus) { derivedStateOf { !outlineFocus && fade.value > 0.5f } }
    val rest = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent
    val content = if (dark) Color.Black else ItvColors.Text
    val shape = ItvShapes.Row
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = rest,
            contentColor = content,
            disabledContainerColor = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent,
            disabledContentColor = ItvColors.Text.copy(alpha = 0.35f),
            focusedContainerColor = rest,
            focusedContentColor = content,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        CompositionLocalProvider(LocalRowFocused provides focused) {
        Row(
            Modifier
                .drawBehind {
                    val a = fade.value.takeIf { it > 0f } ?: return@drawBehind
                    if (outlineFocus) {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        drawOutline(outline, Color.White.copy(alpha = OUTLINE_VEIL * a))
                        drawOutline(outline, Color.White.copy(alpha = a), style = Stroke(OUTLINE_WIDTH.toPx() * 2))
                    } else {
                        drawRect(Color.White.copy(alpha = a))
                    }
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.rowText(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        if (subtitleLines == 1) Modifier.rowText() else Modifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.82f),
                        maxLines = subtitleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        }
    }
}

/** Botão redondo com um ícone (ex.: definições no cabeçalho dos grupos). */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    // Redondo, translúcido; com foco fica branco com o ícone escuro (como o menu em cápsula).
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(40.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = ItvColors.Text,
            disabledContainerColor = Color.White.copy(alpha = 0.05f),
            disabledContentColor = ItvColors.Text.copy(alpha = 0.35f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Barra de botões em cápsula (como o menu do GoatLauncher): um fundo claro translúcido, sem contorno,
 * com os [GlassChipButton] lá dentro.
 */
fun Modifier.capsuleBar(): Modifier = background(Color.White.copy(alpha = 0.14f), CircleShape).padding(5.dp)

/**
 * Botão em cápsula com ícone e texto, para uma [capsuleBar] (ex.: o HUD do player).
 * Sem foco é só texto; com foco fica uma cápsula branca com texto escuro.
 */
@Composable
fun GlassChipButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.85f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}


/** Título de secção pequeno, em maiúsculas. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = ItvColors.TextFaint,
    )
}

/**
 * Desfocagem rápida (3 passagens de box blur ≈ gaussiano) de [src] para [dst], do mesmo tamanho.
 * [a] e [b] são arrays de trabalho com width×height posições, reutilizados entre frames (sem lixo para o GC).
 */
fun blurInto(src: Bitmap, dst: Bitmap, a: IntArray, b: IntArray, radius: Int = 3) {
    val w = src.width
    val h = src.height
    src.getPixels(a, 0, w, 0, 0, w, h)
    repeat(3) {
        boxPass(a, b, w, h, radius, horizontal = true)
        boxPass(b, a, w, h, radius, horizontal = false)
    }
    dst.setPixels(a, 0, w, 0, 0, w, h)
}

private fun boxPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val window = 2 * r + 1
    for (line in 0 until lines) {
        fun idx(i: Int): Int {
            val c = i.coerceIn(0, len - 1)
            return if (horizontal) line * w + c else c * w + line
        }
        var sr = 0
        var sg = 0
        var sb = 0
        for (i in -r..r) {
            val p = input[idx(i)]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
        }
        for (i in 0 until len) {
            output[idx(i)] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
            val add = input[idx(i + r + 1)]
            val sub = input[idx(i - r)]
            sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            sb += (add and 0xFF) - (sub and 0xFF)
        }
    }
}
