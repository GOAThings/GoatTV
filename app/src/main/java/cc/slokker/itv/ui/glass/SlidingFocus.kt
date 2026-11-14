package cc.slokker.itv.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.theme.LocalOutlineBrush
import cc.slokker.itv.ui.theme.LocalPanelStyle
import cc.slokker.itv.data.Prefs
import kotlinx.coroutines.launch

/**
 * Estado das listas dos painéis, com as linhas de baixo e de cima preparadas antes de aparecerem: sem isto, cada
 * linha nova era composta no próprio frame em que entrava, e o scroll com o comando dava soluços na Android TV.
 */
@OptIn(ExperimentalFoundationApi::class)
fun panelListState(first: Int) = LazyListState(PanelListCache, first)

/**
 * Um só destaque por baixo da lista, que desliza até à linha com foco (como na Apple TV) em vez de cada linha acender
 * e apagar o seu. [focusedKey] é a chave (do `items`) da linha com foco; só se mostra com o foco dentro da lista.
 * Com [fill], o destaque é só um fundo dessa cor por baixo da linha, sem contorno (o estilo Sombra dos painéis).
 */
@Composable
fun Modifier.slidingFocus(state: LazyListState, plate: Boolean = false, fill: Color? = null, focusedKey: () -> Any?): Modifier {
    val top = remember { Animatable(0f) }
    val height = remember { Animatable(0f) }
    var focused by remember { mutableStateOf(false) }
    val brush = LocalOutlineBrush.current
    // Estilo Sombra suave: as listas que não escolhem (ex.: Definições) também levam o fundo claro em vez do contorno.
    val fill = fill ?: ShadowFocusFill.takeIf { !plate && LocalPanelStyle.current == Prefs.PanelStyles.SHADOW }
    LaunchedEffect(state) {
        var shown = false
        snapshotFlow {
            val key = focusedKey()
            val info = state.layoutInfo
            // O offset conta a partir do fim da margem de cima (contentPadding); no desenho é preciso somá-la.
            info.visibleItemsInfo.firstOrNull { it.key == key }?.let { (it.offset - info.viewportStartOffset).toFloat() to it.size.toFloat() }
        }.collect { target ->
            val (t, h) = target ?: return@collect
            if (!shown) {
                // Primeira vez: aparece já no sítio, sem vir de cima.
                top.snapTo(t)
                height.snapTo(h)
                shown = true
            } else {
                launch { top.animateTo(t, HighlightSpring) }
                launch { height.animateTo(h, HighlightSpring) }
            }
        }
    }
    if (plate) {
        // [plate]: a placa branca de sempre, mas a deslizar. Pinta-se por cima em "diferença" numa camada à parte:
        // o texto branco fica escuro onde a placa está (mesmo a meio do caminho) e o resto fica branco.
        return onFocusChanged { focused = it.hasFocus }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (!focused || height.value <= 0f) return@drawWithContent
                val outline = ItvShapes.Row.createOutline(Size(size.width, height.value), layoutDirection, this)
                translate(top = top.value) { drawOutline(outline, Color.White, blendMode = BlendMode.Difference) }
            }
    }
    if (fill != null) {
        return onFocusChanged { focused = it.hasFocus }
            .clipToBounds()
            .drawWithContent {
                if (focused && height.value > 0f) {
                    val outline = ItvShapes.Row.createOutline(Size(size.width, height.value), layoutDirection, this)
                    translate(top = top.value) { drawOutline(outline, fill) }
                }
                drawContent()
            }
    }
    return onFocusChanged { focused = it.hasFocus }
        .clipToBounds()
        .drawWithContent { if (focused) drawSlidingFocus(top.value, height.value, brush) else drawContent() }
}

/** O destaque que desliza: rápido o bastante para acompanhar o comando carregado, sem ressaltar. */
private val HighlightSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * A lista desvanece nas pontas quando há mais para ver (em cima só com [top]): uma linha a meio de sair não fica
 * cortada a direito por baixo do título.
 */
fun Modifier.fadeEdges(state: LazyListState, top: Boolean = true) =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val h = 40.dp.toPx()
        if (top && state.canScrollBackward) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = h),
                size = Size(size.width, h),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
                topLeft = Offset(0f, size.height - h),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@OptIn(ExperimentalFoundationApi::class)
private val PanelListCache = LazyLayoutCacheWindow(ahead = 360.dp, behind = 160.dp)
