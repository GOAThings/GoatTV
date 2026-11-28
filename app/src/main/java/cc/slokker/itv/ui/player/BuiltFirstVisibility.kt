package cc.slokker.itv.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Como o AnimatedVisibility (aparecer com fade e a deslizar [slide] da largura; 0 = só o fade), mas o conteúdo
 * constrói-se primeiro, invisível, e a entrada só começa dois frames depois. A lista de canais leva na SHIELD
 * 60–200 ms a construir num só frame: com o AnimatedVisibility a animação começava nesse frame e o relógio dela saltava
 * para o fim (o painel aparecia de repente em vez de deslizar).
 */
@Composable
internal fun BuiltFirstVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    slide: Float,
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    // 0 = fora (invisível e deslocado), 1 = no sítio.
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) {
            if (!mounted) {
                progress.snapTo(0f)
                mounted = true
                // O frame em que o painel se compõe e mede (o pesado) passa com ele invisível.
                withFrameNanos { }
                withFrameNanos { }
            }
            progress.animateTo(1f, tween(ENTER_MS, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, tween(EXIT_MS, easing = FastOutLinearInEasing))
            mounted = false
        }
    }
    if (mounted) {
        Box(
            modifier.graphicsLayer {
                alpha = progress.value
                if (slide != 0f) translationX = (1f - progress.value) * size.width * slide
            },
        ) { content() }
    }
}

/** A entrada (fade e deslize juntos) e a saída dos painéis. */
private const val ENTER_MS = 240
private const val EXIT_MS = 180
