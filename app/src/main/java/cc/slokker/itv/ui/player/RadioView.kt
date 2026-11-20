package cc.slokker.itv.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlin.math.PI
import kotlin.math.sin

/**
 * O ecrã de um canal só de som (uma rádio), no lugar da imagem preta: o logótipo grande, o nome, o programa a dar (se
 * o guia o tiver) e umas barras de equalizador animadas (paradas em pausa). [compact]: o telemóvel ao alto e as
 * imagens pequenas (menos espaço).
 */
@Composable
internal fun RadioView(channel: Channel, programme: String?, playing: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    val accent = LocalAccent.current
    val logo: Dp = if (compact) 72.dp else 168.dp
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(accent.start.copy(alpha = 0.22f).compositeOver(ItvColors.Background), ItvColors.Background)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(16.dp)) {
            ChannelLogo(channel, logo)
            Spacer(Modifier.height(if (compact) 10.dp else 24.dp))
            Text(
                channel.name, color = ItvColors.Text, fontSize = if (compact) 16.sp else 30.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(
                programme ?: LocalStrings.current.radioLabel, color = ItvColors.TextDim, fontSize = if (compact) 13.sp else 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(if (compact) 10.dp else 28.dp))
            EqualizerBars(playing, accent.start, accent.end, Modifier.width(if (compact) 56.dp else 112.dp).height(if (compact) 22.dp else 44.dp))
        }
    }
}

/** Barras que sobem e descem (barato: uma só animação, cada barra com a sua fase); em pausa ficam baixas. */
@Composable
private fun EqualizerBars(playing: Boolean, start: Color, end: Color, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "radio")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        val bars = BAR_SPEEDS.size
        val gap = size.width / (bars * 3f)
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val t = (phase * BAR_SPEEDS[i] + i * 0.37f) * 2 * PI
            val level = if (playing) 0.25f + 0.75f * ((sin(t) + 1) / 2).toFloat() else 0.15f
            val h = size.height * level
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(end, start), startY = size.height - h, endY = size.height),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3),
            )
        }
    }
}

/** As voltas de cada barra por ciclo (inteiras, para o ciclo fechar sem saltos). */
private val BAR_SPEEDS = intArrayOf(2, 3, 1, 4, 2)
