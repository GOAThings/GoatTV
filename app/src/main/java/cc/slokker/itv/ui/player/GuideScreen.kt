package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.ui.input.key.nativeKeyCode
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.KeyHint
import cc.slokker.itv.ui.common.ProgressBar
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import java.util.Calendar

/** Onde fica a pré-visualização do canal enquanto o guia está aberto (o vídeo encolhe para aqui). */
internal val GUIDE_PREVIEW_WIDTH = 256.dp
internal val GUIDE_PREVIEW_HEIGHT = 144.dp
internal val GUIDE_PREVIEW_TOP = 20.dp
internal val GUIDE_PREVIEW_END = 32.dp

private val CHANNEL_COLUMN = 210.dp
private val ROW_HEIGHT = 46.dp
private const val HALF_HOUR = 30 * 60_000L
/** Largura visível da grelha: 2 horas. */
private const val WINDOW_MS = 4 * HALF_HOUR
private const val PAST_LIMIT_MS = 24 * 3600_000L
private const val FUTURE_LIMIT_MS = 36 * 3600_000L
private const val PAGE_ROWS = 6

/** Um bloco da grelha: um programa, ou um intervalo sem informação. */
private data class Slot(val entry: EpgEntry?, val start: Long, val end: Long)

/**
 * Guia de TV em grelha: canais nas linhas, horas nas colunas, o canal a dar no canto.
 * O foco é gerido à mão (linha + hora), como nos guias das boxes: andar para os lados segue os programas,
 * para cima/baixo mantém a hora. ↑ na primeira linha passa para o grupo (← → muda de grupo).
 * OK: em direto vê o canal; já passou e há arquivo vê em catch-up; ainda vai dar marca um lembrete.
 */
@Composable
internal fun TvGuide(
    vm: AppViewModel,
    playing: Channel,
    startGroup: String,
    onPlay: (Channel, String) -> Unit,
    onCatchup: (Channel, EpgEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val version by vm.epgVersion.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val data = content ?: return
    val now = rememberNow()

    val groups = remember(data, prefs) { vm.guideGroups() }
    var group by remember { mutableStateOf(groups.firstOrNull { it.id == startGroup }?.id ?: groups.firstOrNull()?.id ?: SpecialCategory.ALL) }
    val channels = remember(data, group, prefs.sort, prefs.hiddenGroups, prefs.hiddenChannels, prefs.channelOrder) { vm.channelsOf(group) }
    var row by remember(channels) { mutableIntStateOf(channels.indexOfFirst { it.key == playing.key }.coerceAtLeast(0)) }
    val opened = remember { System.currentTimeMillis() }
    var windowStart by remember { mutableLongStateOf(floorHalfHour(opened)) }
    var focusTime by remember { mutableLongStateOf(opened) }
    var onHeader by remember { mutableStateOf(false) }
    val minTime = floorHalfHour(opened - PAST_LIMIT_MS)
    val maxTime = opened + FUTURE_LIMIT_MS
    val listState = rememberLazyListState((row - 2).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(focus) }

    fun entriesOf(ch: Channel) = vm.guideEntries(ch)

    /** O bloco na hora [t] da linha: o programa, ou o intervalo vazio até à meia hora seguinte. */
    fun slotAt(entries: List<EpgEntry>, t: Long): Slot {
        entries.firstOrNull { it.startMs <= t && it.endMs > t }?.let { return Slot(it, it.startMs, it.endMs) }
        val half = floorHalfHour(t)
        val prevEnd = entries.lastOrNull { it.endMs <= t }?.endMs ?: Long.MIN_VALUE
        val nextStart = entries.firstOrNull { it.startMs > t }?.startMs ?: Long.MAX_VALUE
        return Slot(null, maxOf(prevEnd, half), minOf(nextStart, half + HALF_HOUR))
    }

    val selectedChannel = channels.getOrNull(row)
    val selectedSlot = selectedChannel?.let { ch -> remember(ch, version, focusTime) { slotAt(entriesOf(ch), focusTime) } }

    fun moveTo(slot: Slot) {
        focusTime = maxOf(slot.start, minTime)
        while (focusTime < windowStart) windowStart -= HALF_HOUR
        while (focusTime >= windowStart + WINDOW_MS) windowStart += HALF_HOUR
        // Ficar com o início do bloco visível, mas sem saltar a grelha para trás de mais.
        focusTime = maxOf(focusTime, windowStart)
    }

    fun act(ch: Channel, slot: Slot) {
        val e = slot.entry
        val t = System.currentTimeMillis()
        when {
            e == null || (e.startMs <= t && e.endMs > t) -> onPlay(ch, group)
            e.startMs > t -> vm.toggleReminder(ch, e)
            vm.canCatchup(ch, e) -> onCatchup(ch, e)
            else -> vm.showToast(s.noCatchup)
        }
    }

    // A linha escolhida fica sempre à vista (com duas linhas de contexto por cima).
    LaunchedEffect(row, channels) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || row <= visible.first().index || row >= visible.last().index) {
            listState.animateScrollToItem((row - 2).coerceAtLeast(0))
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(ItvColors.Background)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ch = channels.getOrNull(row)
                val slot = selectedSlot
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (!onHeader) { if (row == 0) onHeader = true else row-- }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (onHeader) onHeader = false else row = (row + 1).coerceAtMost(channels.lastIndex.coerceAtLeast(0))
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val forward = e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
                        if (onHeader) {
                            val i = groups.indexOfFirst { it.id == group }
                            if (groups.isNotEmpty()) {
                                group = groups[(i + if (forward) 1 else -1).mod(groups.size)].id
                                row = 0
                            }
                        } else if (ch != null && slot != null) {
                            val entries = entriesOf(ch)
                            if (forward) {
                                if (slot.end < maxTime) moveTo(slotAt(entries, slot.end))
                            } else {
                                // Um programa que começa antes da grelha: primeiro mostra-se o início dele.
                                if (slot.start < windowStart && windowStart > minTime) {
                                    windowStart -= HALF_HOUR
                                    focusTime = maxOf(slot.start, windowStart)
                                } else if (slot.start - 1 >= minTime) {
                                    moveTo(slotAt(entries, slot.start - 1))
                                }
                            }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (!onHeader && ch != null && slot != null) act(ch, slot)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> {
                        row = (row - PAGE_ROWS).coerceAtLeast(0); true
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> {
                        row = (row + PAGE_ROWS).coerceAtMost(channels.lastIndex.coerceAtLeast(0)); true
                    }
                    // Volta à hora atual.
                    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_0 -> {
                        val t = System.currentTimeMillis()
                        windowStart = floorHalfHour(t)
                        focusTime = t
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = GUIDE_PREVIEW_END, vertical = GUIDE_PREVIEW_TOP),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(GUIDE_PREVIEW_HEIGHT)) {
                Column(Modifier.weight(1f).padding(end = 24.dp)) {
                    GroupSwitcher(groups.firstOrNull { it.id == group }?.name ?: data.categoryName(group), onHeader)
                    Spacer(Modifier.height(12.dp))
                    if (selectedChannel != null && selectedSlot != null) {
                        SlotDetail(vm, s, selectedChannel, selectedSlot, now, onHeader)
                    } else {
                        Text(s.noChannelsInGroup, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                // Aqui por baixo vê-se o canal a dar (o vídeo do player, encolhido).
                Spacer(Modifier.width(GUIDE_PREVIEW_WIDTH))
            }
            Spacer(Modifier.height(14.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val timelineWidth = maxWidth - CHANNEL_COLUMN
                val dpPerMs = timelineWidth.value / WINDOW_MS
                fun x(t: Long): Dp = ((t - windowStart) * dpPerMs).dp
                // Linha da hora atual.
                if (now in windowStart until windowStart + WINDOW_MS) {
                    Box(
                        Modifier
                            .offset(x = CHANNEL_COLUMN + x(now) - 1.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(LocalAccent.current.brush),
                    )
                }
                Column {
                    TimeRuler(windowStart, timelineWidth, s)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(channels, key = { _, ch -> ch.uid }) { index, ch ->
                            val entries = remember(ch, version) { entriesOf(ch) }
                            // Canais sem guia completo: pede o EPG curto quando a linha aparece.
                            LaunchedEffect(ch.key) {
                                if (entries.isEmpty()) {
                                    kotlinx.coroutines.delay(300)
                                    vm.epgFor(ch)
                                }
                            }
                            GuideRow(
                                vm = vm,
                                channel = ch,
                                entries = entries,
                                selectedSlot = if (index == row && !onHeader) selectedSlot else null,
                                isPlaying = ch.key == playing.key,
                                windowStart = windowStart,
                                timelineWidth = timelineWidth,
                                x = ::x,
                                now = now,
                                showNumbers = prefs.showNumbers,
                                showLogos = prefs.showLogos,
                                reminders = reminders.size,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSwitcher(name: String, focused: Boolean) {
    Row(
        Modifier
            .clip(ItvShapes.Control)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", style = MaterialTheme.typography.titleMedium, color = if (focused) Color.Black else ItvColors.TextFaint)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, color = if (focused) Color.Black else ItvColors.Text)
        Spacer(Modifier.width(12.dp))
        Text("›", style = MaterialTheme.typography.titleMedium, color = if (focused) Color.Black else ItvColors.TextFaint)
    }
}

/** Detalhe do bloco escolhido, em cima: título, horas, estado e o que o OK faz. */
@Composable
private fun SlotDetail(vm: AppViewModel, s: Strings, channel: Channel, slot: Slot, now: Long, onHeader: Boolean) {
    val e = slot.entry
    Text(
        e?.title ?: s.noGuideInfo,
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        listOfNotNull(
            e?.let { "${dayLabel(s, it.startMs)} ${formatTime(it.startMs)} – ${formatTime(it.endMs)}" },
            e?.let { s.minutes(((it.endMs - it.startMs) / 60_000).toInt()) },
            channel.name,
        ).joinToString("  ·  "),
        color = ItvColors.TextDim,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(10.dp))
    val live = e == null || (e.startMs <= now && e.endMs > now)
    if (e != null && live) {
        ProgressBar(e.progress(now), Modifier.width(220.dp))
        Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            onHeader -> KeyHint("← →", s.changeGroup)
            live -> KeyHint("OK", s.watch)
            e!!.startMs > now -> KeyHint("OK", if (vm.hasReminder(channel, e)) s.removeReminder else s.setReminder)
            vm.canCatchup(channel, e) -> KeyHint("OK", s.watchCatchup)
            else -> Text(s.noCatchup, color = ItvColors.TextFaint, style = MaterialTheme.typography.bodyMedium)
        }
        if (!onHeader) KeyHint("0", s.backToNow)
    }
}

@Composable
private fun TimeRuler(windowStart: Long, timelineWidth: Dp, s: Strings) {
    Row(Modifier.fillMaxWidth().height(26.dp)) {
        Box(Modifier.width(CHANNEL_COLUMN).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Text(dayLabel(s, windowStart), style = MaterialTheme.typography.labelLarge, color = ItvColors.TextDim)
        }
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            val step = timelineWidth / 4
            for (i in 0 until 4) {
                Text(
                    formatTime(windowStart + i * HALF_HOUR),
                    style = MaterialTheme.typography.labelLarge,
                    color = ItvColors.TextDim,
                    modifier = Modifier.offset(x = step * i + 6.dp).align(Alignment.CenterStart),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel,
    channel: Channel,
    entries: List<EpgEntry>,
    selectedSlot: Slot?,
    isPlaying: Boolean,
    windowStart: Long,
    timelineWidth: Dp,
    x: (Long) -> Dp,
    now: Long,
    showNumbers: Boolean,
    showLogos: Boolean,
    @Suppress("UNUSED_PARAMETER") reminders: Int, // só para recompor quando os lembretes mudam
) {
    val windowEnd = windowStart + WINDOW_MS
    val rowSelected = selectedSlot != null
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .padding(end = 6.dp)
                .clip(ItvShapes.Control)
                .background(
                    when {
                        rowSelected -> Color.White.copy(alpha = 0.14f)
                        isPlaying -> Color.White.copy(alpha = 0.07f)
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumbers) {
                Text(channel.num.toString(), Modifier.width(34.dp), color = ItvColors.TextDim, style = MaterialTheme.typography.labelMedium)
            }
            if (showLogos) {
                ChannelLogo(channel, 28.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (rowSelected) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            val visible = entries.filter { it.endMs > windowStart && it.startMs < windowEnd }
            if (visible.isEmpty() && selectedSlot?.entry == null) {
                GuideCell(LocalStrings.current.noGuideInfo, null, 0.dp, timelineWidth, past = false, live = false, selected = false, icon = null)
            }
            for (e in visible) {
                val start = maxOf(e.startMs, windowStart)
                val end = minOf(e.endMs, windowEnd)
                val selected = selectedSlot?.entry == e
                val live = e.startMs <= now && e.endMs > now
                val past = e.endMs <= now
                val icon = when {
                    e.startMs > now && vm.hasReminder(channel, e) -> ItvIcons.Bell
                    past && vm.canCatchup(channel, e) -> ItvIcons.History
                    else -> null
                }
                GuideCell(e.title, e.startMs.takeIf { it >= windowStart }, x(start), x(end) - x(start), past, live, selected, icon)
            }
            // Bloco vazio escolhido (sem informação nessa hora).
            if (selectedSlot != null && selectedSlot.entry == null) {
                val start = maxOf(selectedSlot.start, windowStart)
                val end = minOf(selectedSlot.end, windowEnd)
                GuideCell(LocalStrings.current.noGuideInfo, null, x(start), x(end) - x(start), past = false, live = false, selected = true, icon = null)
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    startMs: Long?,
    offset: Dp,
    width: Dp,
    past: Boolean,
    live: Boolean,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
) {
    if (width < 4.dp) return
    val shape = ItvShapes.Small
    Row(
        Modifier
            .offset(x = offset)
            .width(width - 3.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    live -> Color.White.copy(alpha = 0.13f)
                    past -> Color.White.copy(alpha = 0.04f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && width > 40.dp) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (selected) Color.Black else ItvColors.TextDim)
            Spacer(Modifier.width(6.dp))
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = when {
                    selected -> Color.Black
                    past -> ItvColors.TextDim
                    else -> ItvColors.Text
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (startMs != null && width > 90.dp) {
                Text(formatTime(startMs), style = MaterialTheme.typography.labelSmall, color = if (selected) Color.Black.copy(alpha = 0.6f) else ItvColors.TextFaint, maxLines = 1)
            }
        }
    }
}

private fun floorHalfHour(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    c.set(Calendar.MINUTE, if (c.get(Calendar.MINUTE) < 30) 0 else 30)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** "Hoje", "Ontem", "Amanhã" ou o dia da semana. */
internal fun dayLabel(s: Strings, t: Long): String {
    // Dias desde 1970 na hora local (dá certo também na passagem de ano).
    fun dayOf(ms: Long) = Math.floorDiv(ms + java.util.TimeZone.getDefault().getOffset(ms), 86_400_000L)
    val today = dayOf(System.currentTimeMillis())
    return when ((dayOf(t) - today).toInt()) {
        0 -> s.today
        -1 -> s.yesterday
        1 -> s.tomorrow
        else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(s.locale, "EEEd"), s.locale).format(java.util.Date(t))
    }
}
