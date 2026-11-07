package cc.slokker.itv.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.ui.glass.GlassChipButton
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.capsuleBar
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.theme.GoatPalette
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.ItvShapes

private val NetOk = Color(0xFF4CC38A)

/** A cor do veredicto: verde se aguenta, âmbar se há um problema, neutro enquanto mede. */
internal fun NetVerdict.tone(): Color = when (this) {
    NetVerdict.GOOD -> NetOk
    NetVerdict.SLOW, NetVerdict.GAPS, NetVerdict.SLOW_REPLY -> GoatPalette.Warning
    else -> ItvColors.TextDim
}

internal fun Strings.netTitle(st: NetState): String = when (st.verdict) {
    NetVerdict.MEASURING -> netMeasuring
    NetVerdict.GOOD -> netGood
    NetVerdict.SLOW -> if (st.link.wifi) netSlowWifi else netSlow
    NetVerdict.GAPS -> netGaps
    NetVerdict.SLOW_REPLY -> netSlowReply
    NetVerdict.UNSUPPORTED -> netVlc
}

internal fun Strings.netText(st: NetState): String = when (st.verdict) {
    NetVerdict.MEASURING -> netMeasuringSub
    NetVerdict.GOOD -> netGoodSub((st.bufferMs / 1_000).toInt())
    NetVerdict.SLOW -> netSlowSub(mbps(st.needsKbps ?: 0), mbps(st.gettingKbps ?: 0))
    NetVerdict.GAPS -> netGapsSub
    NetVerdict.SLOW_REPLY -> netSlowReplySub("%.1f".format((st.replyMs ?: 0) / 1_000f))
    NetVerdict.UNSUPPORTED -> netVlcSub
}

/** O veredicto numa palavra (valor da linha "Ligação" e cápsula do gráfico). */
internal fun Strings.netShort(verdict: NetVerdict): String = when (verdict) {
    NetVerdict.MEASURING -> netShortMeasuring
    NetVerdict.GOOD -> netShortGood
    NetVerdict.SLOW -> netShortSlow
    NetVerdict.GAPS -> netShortGaps
    NetVerdict.SLOW_REPLY -> netShortReply
    NetVerdict.UNSUPPORTED -> "—"
}

internal fun Strings.netLink(link: NetLink): String = when {
    link.cable -> netCable
    link.wifi && link.band != null && link.signal != null -> netWifi(link.band, link.signal)
    link.wifi -> netWifiPlain
    else -> netUnknown
}

internal fun NetLink.icon(): ImageVector = if (cable) ItvIcons.Cable else ItvIcons.Wifi

/**
 * O veredicto no topo da página Ligação: o que se passa numa frase e, quando chega pouco, uma barra com o que
 * chega contra o que o canal precisa (o traço branco).
 */
@Composable
internal fun NetVerdictCard(st: NetState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tone = st.verdict.tone()
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(tone.copy(alpha = 0.20f), tone.copy(alpha = 0.06f))))
            .border(1.dp, tone.copy(alpha = 0.35f), shape)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (st.verdict) {
                    NetVerdict.SLOW -> st.link.icon()
                    NetVerdict.GOOD -> ItvIcons.Check
                    NetVerdict.UNSUPPORTED -> ItvIcons.Info
                    else -> ItvIcons.Warning
                },
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = tone,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.netTitle(st), style = MaterialTheme.typography.titleSmall)
            Text(s.netText(st), style = MaterialTheme.typography.bodySmall, color = ItvColors.TextDim, modifier = Modifier.padding(top = 4.dp))
            val getting = st.gettingKbps
            val needs = st.needsKbps
            if (st.verdict == NetVerdict.SLOW && getting != null && needs != null) {
                // A escala vai até um terço acima do que o canal precisa: o traço fica a 3/4.
                val max = needs * 4f / 3f
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)))
                    Box(
                        Modifier.align(Alignment.CenterStart).fillMaxWidth((getting / max).coerceIn(0.02f, 1f)).height(8.dp)
                            .clip(CircleShape).background(tone),
                    )
                    Box(Modifier.offset(x = maxWidth * 0.75f - 1.dp).width(2.dp).fillMaxHeight().background(Color.White))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(s.netBarGetting(mbps(getting)), style = MaterialTheme.typography.labelSmall, color = ItvColors.TextDim, modifier = Modifier.weight(1f))
                    Text(s.netBarNeeds(mbps(needs)), style = MaterialTheme.typography.labelSmall, color = ItvColors.TextDim)
                    Spacer(Modifier.fillMaxWidth(0.22f))
                }
            }
        }
    }
}

/** O gráfico num canto do vídeo (Ligação → Gráfico no vídeo): lê o estado sozinho, sem recompor o player. */
@Composable
internal fun NetGraph(monitor: NetMonitor, channel: String, modifier: Modifier = Modifier) {
    val st by monitor.state.collectAsStateWithLifecycle()
    NetGraphCard(st, channel, modifier)
}

@Composable
private fun NetGraphCard(st: NetState, channel: String, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tone = st.verdict.tone()
    Column(
        modifier
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .width(340.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(
                s.netShort(st.verdict),
                style = MaterialTheme.typography.labelSmall,
                color = tone,
                modifier = Modifier.clip(CircleShape).background(tone.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(s.netLastMinute, style = MaterialTheme.typography.labelSmall, color = ItvColors.TextFaint, modifier = Modifier.padding(top = 10.dp))
        val history = st.history
        val needs = st.needsKbps?.div(1_000f)
        val line = GoatPalette.Link
        Canvas(Modifier.fillMaxWidth().height(80.dp).padding(top = 6.dp)) {
            if (history.size < 2) return@Canvas
            val top = maxOf(history.max(), needs ?: 0f, 1f) * 1.2f
            fun y(v: Float) = size.height - v / top * size.height
            val step = size.width / (HISTORY_POINTS - 1)
            val startX = size.width - step * (history.size - 1)
            val path = Path().apply {
                history.forEachIndexed { i, v -> if (i == 0) moveTo(startX, y(v)) else lineTo(startX + i * step, y(v)) }
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(startX, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.40f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (needs != null) {
                drawLine(
                    GoatPalette.Warning,
                    start = androidx.compose.ui.geometry.Offset(0f, y(needs)),
                    end = androidx.compose.ui.geometry.Offset(size.width, y(needs)),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GraphStat(s.netGetting, st.gettingKbps?.let { s.netMbit(mbps(it)) } ?: "—", if (st.verdict == NetVerdict.SLOW) tone else ItvColors.Text, Modifier.weight(1f))
            GraphStat(s.buffer, "${st.bufferMs / 1_000} s", ItvColors.Text, Modifier.weight(1f))
            GraphStat(s.netStops, "${st.stops}", if (st.stops > 0) GoatPalette.Warning else ItvColors.Text, Modifier.weight(1f))
        }
        Text(s.netLink(st.link), style = MaterialTheme.typography.labelSmall, color = ItvColors.TextFaint, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun GraphStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ItvColors.TextFaint, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
    }
}

/** Um botão do aviso; [onClick] já fecha o aviso. */
internal class NetHintAction(val icon: ImageVector, val text: String, val onClick: () -> Unit)

/**
 * O aviso "este canal está sempre a parar": a causa, com os números, e o que experimentar.
 * Aparece sozinho (uma vez por canal) quando o canal parou várias vezes em 10 minutos.
 */
@Composable
internal fun NetHintCard(monitor: NetMonitor, actions: List<NetHintAction>, focus: FocusRequester, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val st by monitor.state.collectAsStateWithLifecycle()
    Row(
        modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .widthIn(max = 760.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(GoatPalette.Warning.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ItvIcons.Warning, contentDescription = null, modifier = Modifier.size(24.dp), tint = GoatPalette.Warning)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(s.netHintTitle, style = MaterialTheme.typography.titleMedium)
            val text = if (st.verdict == NetVerdict.SLOW && st.gettingKbps != null && st.needsKbps != null) {
                s.netHintSlow(st.stops, mbps(st.gettingKbps!!), mbps(st.needsKbps!!))
            } else {
                s.netHintGaps(st.stops)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = ItvColors.TextDim, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.capsuleBar().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions.forEachIndexed { i, a ->
                    GlassChipButton(a.icon, a.text, a.onClick, if (i == 0) Modifier.focusRequester(focus) else Modifier)
                }
            }
        }
    }
}

private const val HISTORY_POINTS = 60
