package cc.slokker.itv.ui.mobile

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberEpg
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.dayLabel
import cc.slokker.itv.ui.theme.GoatPalette
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * O guia do telemóvel em grelha, como o da TV: os canais à esquerda, os programas a deslizar de lado (com o dedo),
 * a hora atual numa linha vermelha e os dias por cima. Tocar num programa a dar abre o canal; num que já passou,
 * o catch-up (se houver); num que vem aí, marca um lembrete. Manter o dedo abre o guia do canal.
 */
@Composable
internal fun GuideGrid(vm: AppViewModel, channels: List<Channel>, groupId: String, current: Channel, onEpg: (Channel) -> Unit) {
    val s = LocalStrings.current
    val density = LocalDensity.current
    val now = rememberNow()
    val played = LocalOnPlayed.current
    val scope = rememberCoroutineScope()
    val origin = remember { startOfHalfHour(System.currentTimeMillis() - PAST_MS) }
    val end = origin + PAST_MS + FUTURE_MS
    val pxPerMs = with(density) { SLOT_WIDTH.toPx() } / HALF_HOUR
    val column = with(density) { CHANNEL_COLUMN.toPx() }
    var width by remember { mutableIntStateOf(0) }
    val maxOffset = ((end - origin) * pxPerMs - (width - column)).coerceAtLeast(0f)
    // Quantos píxeis do início da grelha já ficaram para trás (à esquerda).
    var offset by remember { mutableFloatStateOf((System.currentTimeMillis() - HALF_HOUR / 2 - origin) * pxPerMs) }
    val jobs = remember { arrayOfNulls<Job>(1) }
    fun moveTo(target: Float) {
        jobs[0]?.cancel()
        jobs[0] = scope.launch { animate(offset, target.coerceIn(0f, maxOffset)) { v, _ -> offset = v } }
    }
    val drag = rememberDraggableState { d -> offset = (offset - d).coerceIn(0f, maxOffset) }
    // O deslocamento só se lê no desenho (e no dia escolhido): deslizar não recompõe as linhas.
    val currentOffset = remember { { offset } }

    Column(Modifier.fillMaxSize()) {
        // Os dias: Hoje (volta à hora atual), Amanhã, e os seguintes ao fim da tarde.
        val today = startOfDay(now)
        val leftDay by remember(today) {
            derivedStateOf {
                val t = origin + (offset / pxPerMs).toLong() + HALF_HOUR
                (0..6).firstOrNull { t >= dayStart(today, it) && t < dayStart(today, it + 1) }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            items(7) { i ->
                val day = dayStart(today, i)
                val on = leftDay == i
                Text(
                    when (i) {
                        0 -> s.today
                        1 -> s.tomorrow
                        else -> dayLabel(s, day + 12 * 3600_000L)
                    },
                    color = if (on) Color.Black else ItvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(if (on) Color.White else DayFill)
                        .clickable { moveTo(((if (i == 0) System.currentTimeMillis() - HALF_HOUR / 2 else day + EVENING_MS) - origin) * pxPerMs) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { width = it.width }
                .draggable(
                    drag, Orientation.Horizontal,
                    onDragStarted = { jobs[0]?.cancel() },
                    onDragStopped = { velocity ->
                        jobs[0] = scope.launch {
                            animateDecay(offset, -velocity, FloatExponentialDecaySpec()) { v, _ -> offset = v.coerceIn(0f, maxOffset) }
                        }
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize()) {
                TimeRuler(origin, pxPerMs, column, now, currentOffset)
                LazyColumn(contentPadding = PaddingValues(bottom = LocalBarSpace.current)) {
                    items(channels, key = { it.uid }) { channel ->
                        GridRow(vm, channel, channel.key == current.key, origin, pxPerMs, now, currentOffset, onLong = { onEpg(channel) }) { entry ->
                            val t = System.currentTimeMillis()
                            when {
                                entry == null || (entry.startMs <= t && entry.endMs > t) -> {
                                    vm.play(channel, groupId)
                                    played()
                                }
                                entry.startMs > t -> vm.toggleReminder(channel, entry)
                                vm.canCatchup(channel, entry) -> {
                                    vm.playCatchup(channel, entry)
                                    played()
                                }
                                else -> onEpg(channel)
                            }
                        }
                    }
                }
            }
            // A hora atual: uma linha vermelha por cima das linhas.
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val x = column + (now - origin) * pxPerMs - currentOffset()
                    if (x >= column) drawRect(GoatPalette.Live.copy(alpha = 0.9f), Offset(x - 1.dp.toPx(), RULER_HEIGHT.toPx()), Size(2.dp.toPx(), size.height))
                },
            )
        }
    }
}

/** As horas por cima da grelha, de meia em meia hora, com a hora atual numa etiqueta vermelha. */
@Composable
private fun TimeRuler(origin: Long, pxPerMs: Float, column: Float, now: Long, offset: () -> Float) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val label = TextStyle(color = ItvColors.TextDim, fontSize = 12.sp)
    val pill = TextStyle(color = Color(0xFF0B0B0B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Canvas(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        val nowX = column + (now - origin) * pxPerMs - offset()
        val first = origin + ((offset() / pxPerMs).toLong() / HALF_HOUR) * HALF_HOUR
        clipRect(left = column) {
            var t = first
            while (true) {
                val x = column + (t - origin) * pxPerMs - offset()
                if (x > size.width) break
                // A hora debaixo da etiqueta vermelha não se mostra.
                if (kotlin.math.abs(x + 22.dp.toPx() - nowX) > 46.dp.toPx()) {
                    val layout = measurer.measure(formatTime(t), label)
                    drawText(layout, topLeft = Offset(x + 4.dp.toPx(), (size.height - layout.size.height) / 2))
                }
                t += HALF_HOUR
            }
            if (nowX >= column) {
                val layout = measurer.measure(formatTime(now), pill)
                val w = layout.size.width + 12.dp.toPx()
                val h = layout.size.height + 4.dp.toPx()
                val left = nowX - w / 2
                val top = (size.height - h) / 2
                drawRoundRect(GoatPalette.Live, Offset(left, top), Size(w, h), CornerRadius(6.dp.toPx()))
                drawText(layout, topLeft = Offset(left + 6.dp.toPx(), top + 2.dp.toPx()))
            }
        }
    }
}

/** Uma linha: o logótipo do canal e os programas dele desenhados num Canvas (só se redesenha ao deslizar). */
@Composable
private fun GridRow(
    vm: AppViewModel,
    channel: Channel,
    playing: Boolean,
    origin: Long,
    pxPerMs: Float,
    now: Long,
    offset: () -> Float,
    onLong: () -> Unit,
    onTap: (EpgEntry?) -> Unit,
) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val entries = remember(epg, channel.key) { vm.guideEntries(channel).ifEmpty { epg.distinctBy { it.startMs } } }
    val reminded = remember(reminders, entries) { entries.filter { vm.hasReminder(channel, it) }.map { it.startMs }.toSet() }
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val title = TextStyle(color = ItvColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val pastTitle = title.copy(color = ItvColors.TextFaint)
    val time = TextStyle(color = ItvColors.TextFaint, fontSize = 11.sp)
    val noGuide = s.noGuide
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Box(Modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (playing) accent.start.copy(alpha = 0.18f) else DayFill),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { ChannelLogo(channel, 30.dp) }
        }
        Canvas(
            Modifier.fillMaxSize().clipToBounds().pointerInput(entries) {
                detectTapGestures(
                    onTap = { pos ->
                        val t = origin + ((pos.x + offset()) / pxPerMs).toLong()
                        onTap(entries.firstOrNull { it.startMs <= t && it.endMs > t })
                    },
                    onLongPress = { onLong() },
                )
            },
        ) {
            val shift = offset()
            val gap = CELL_GAP.toPx()
            val pad = 8.dp.toPx()
            val radius = CornerRadius(CELL_RADIUS.toPx())
            val top = 4.dp.toPx()
            val h = size.height - top * 2
            if (entries.isEmpty()) {
                drawRoundRect(Color.White.copy(alpha = 0.03f), Offset(0f, top), Size(size.width, h), radius)
                drawText(measurer.measure(noGuide, pastTitle), topLeft = Offset(pad, top + pad))
                return@Canvas
            }
            for (e in entries) {
                val left = (e.startMs - origin) * pxPerMs - shift
                val right = (e.endMs - origin) * pxPerMs - shift - gap
                if (right < 0f || left > size.width || right - left < 4.dp.toPx()) continue
                val live = e.startMs <= now && e.endMs > now
                val past = e.endMs <= now
                drawRoundRect(
                    when {
                        live -> Color.White.copy(alpha = 0.1f)
                        past -> Color.White.copy(alpha = 0.025f)
                        else -> Color.White.copy(alpha = 0.06f)
                    },
                    Offset(left, top), Size(right - left, h), radius,
                )
                clipRect(left, top, right, top + h) {
                    // O título fica à vista mesmo com o programa a começar fora do ecrã (como no guia da TV).
                    val x = maxOf(left, 0f) + pad
                    val maxWidth = (right - left - pad * 2).toInt().coerceAtLeast(1)
                    val titleLayout = measurer.measure(e.title, if (past) pastTitle else title, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxWidth))
                    drawText(titleLayout, topLeft = Offset(x, top + 7.dp.toPx()))
                    if (live) {
                        val barW = right - left - pad * 2
                        val y = top + h - 7.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = 0.15f), Offset(left + pad, y), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                        drawRoundRect(accent.brush, Offset(left + pad, y), Size(barW * e.progress(now), 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                    } else {
                        drawText(measurer.measure(formatTime(e.startMs), time), topLeft = Offset(x, top + 7.dp.toPx() + titleLayout.size.height))
                    }
                    // Lembrete marcado: um ponto da cor de destaque no canto.
                    if (e.startMs in reminded) drawCircle(accent.start, 3.5.dp.toPx(), Offset(right - 9.dp.toPx(), top + 9.dp.toPx()))
                }
            }
        }
    }
}

private fun startOfHalfHour(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    c.set(Calendar.MINUTE, if (c.get(Calendar.MINUTE) < 30) 0 else 30)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun startOfDay(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Meia-noite do dia [days] dias depois de [today] (pelo calendário: as mudanças de hora não a desviam). */
private fun dayStart(today: Long, days: Int): Long =
    Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

private const val HALF_HOUR = 30 * 60_000L
/** Para trás (o catch-up) e para a frente (uma semana de guia). */
private const val PAST_MS = 12 * 3600_000L
private const val FUTURE_MS = 7 * 24 * 3600_000L
/** Os outros dias abrem às 18:00 (o que se costuma procurar no guia). */
private const val EVENING_MS = 18 * 3600_000L
private val SLOT_WIDTH = 120.dp
private val CHANNEL_COLUMN = 76.dp
private val ROW_HEIGHT = 58.dp
private val RULER_HEIGHT = 30.dp
private val CELL_GAP = 3.dp
private val CELL_RADIUS = 8.dp
private val DayFill = Color.White.copy(alpha = 0.07f)
