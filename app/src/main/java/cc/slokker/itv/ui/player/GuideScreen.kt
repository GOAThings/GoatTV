package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.ui.input.key.nativeKeyCode
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import cc.slokker.itv.ui.theme.GoatPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import java.util.Calendar
import cc.slokker.itv.ui.theme.tabular
import cc.slokker.itv.ui.common.focusWithRetry
import cc.slokker.itv.ui.common.dayLabel

/** Onde fica a pré-visualização do canal enquanto o guia está aberto (o vídeo encolhe para aqui). */
/** A informação do programa escolhido, entre os grupos e a grelha. */
private val HERO_HEIGHT = 118.dp
/** Altura do meio dessa informação (margem de cima + grupos + metade). */
private val HERO_CENTER = 20.dp + 40.dp + HERO_HEIGHT / 2

private val CHANNEL_COLUMN = 210.dp
private val ROW_HEIGHT = 44.dp
/** Espaço entre linhas (metade em cima, metade em baixo) e entre programas. */
private val ROW_GAP = 2.dp
private val CELL_GAP = 3.dp
private const val HALF_HOUR = 30 * 60_000L
/** Largura visível da grelha: 2 horas. */
private const val WINDOW_MS = 4 * HALF_HOUR
private const val PAST_LIMIT_MS = 24 * 3600_000L
private const val FUTURE_LIMIT_MS = 36 * 3600_000L
private const val PAGE_ROWS = 6
private const val HELD_HERO_DELAY_MS = 150L

/** Linhas já compostas fora do ecrã (as que vão entrar ao descer ou subir não se fazem no frame da tecla). */
@OptIn(ExperimentalFoundationApi::class)
private val GuideListCache = LazyLayoutCacheWindow(ahead = ROW_HEIGHT * 4, behind = ROW_HEIGHT * 3)

@OptIn(ExperimentalFoundationApi::class)
private fun guideListState(first: Int) = LazyListState(GuideListCache, first)

/**
 * O scroll das barras laterais (na TV, a linha com foco fica a 30% da altura e a lista desliza por baixo), mas com a
 * linha escolhida certa na 3.ª posição: a 30% ficava uma tira da linha de cima cortada por baixo da régua.
 */
@OptIn(ExperimentalFoundationApi::class)
private val GuideScrollSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - size * 2
}

/** Um bloco da grelha: um programa, ou um intervalo sem informação. */
private data class Slot(val entry: EpgEntry?, val start: Long, val end: Long)

/**
 * Guia de TV em grelha por cima do vídeo (que continua em ecrã inteiro, escurecido por trás): os grupos em cima,
 * o programa escolhido como no HUD, e canais nas linhas, horas nas colunas.
 * O foco é gerido à mão (linha + hora), como nos guias das boxes: andar para os lados segue os programas,
 * para cima/baixo mantém a hora. ↑ na primeira linha passa para o grupo (← → muda de grupo).
 * OK: em direto vê o canal; já passou e há arquivo vê em catch-up; ainda vai dar marca um lembrete.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val listState = remember { guideListState((row - 2).coerceAtLeast(0)) }
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

    // O programa em cima segue a escolha um frame depois (com a tecla segurada, só quando se para): o frame da tecla
    // fica só com a grelha e a placa começa a deslizar a tempo.
    val heroTarget = if (selectedChannel != null && selectedSlot != null) selectedChannel to selectedSlot else null
    var hero by remember { mutableStateOf(heroTarget) }
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(heroTarget) {
        delay(if (held) HELD_HERO_DELAY_MS else 16)
        hero = heroTarget
    }
    val tabs = remember(groups, data) { groups.map { it.id to it.name.ifBlank { data.categoryName(it.id) } } }

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

    // A lista anda como a das barras laterais: a linha escolhida pede para ser vista (mais abaixo, em cada linha) e
    // fica na 3.ª posição, com a lista a deslizar (GuideScrollSpec). Um salto de página para fora da vista vai lá direto.
    LaunchedEffect(row, channels) {
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == row }) listState.scrollToItem((row - 3).coerceAtLeast(0))
    }

    Box(
        modifier
            .fillMaxSize()
            .background(GuideScrim)
            .drawBehind {
                // O lado do logótipo e do programa, escurecido como o canto do zapping (o vídeo aí é claro).
                val center = Offset(0f, HERO_CENTER.toPx())
                val ry = HERO_HEIGHT.toPx() * 1.5f
                scale(scaleX = size.width * 0.55f / ry, scaleY = 1f, pivot = center) {
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color.Black.copy(alpha = 0.6f),
                            0.45f to Color.Black.copy(alpha = 0.35f),
                            0.75f to Color.Black.copy(alpha = 0.1f),
                            1f to Color.Transparent,
                            center = center,
                            radius = ry,
                        ),
                        radius = ry,
                        center = center,
                    )
                }
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    held = false
                    return@onKeyEvent false
                }
                held = e.nativeKeyEvent.repeatCount > 0
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
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupTabs(tabs, group, onHeader, Modifier.weight(1f))
                Text(formatTime(now), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, shadow = TextShadow).tabular)
            }
            Box(Modifier.fillMaxWidth().height(HERO_HEIGHT).padding(start = 20.dp, bottom = 10.dp), contentAlignment = Alignment.BottomStart) {
                val shown = hero
                if (shown != null) {
                    GuideHero(vm, s, shown.first, shown.second, now, onHeader, prefs.showLogos)
                } else {
                    Text(s.noChannelsInGroup, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyLarge)
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val timelineWidth = maxWidth - CHANNEL_COLUMN
                val dpPerMs = timelineWidth.value / WINDOW_MS
                fun x(t: Long): Dp = ((t - windowStart) * dpPerMs).dp
                // Linha da hora atual (a etiqueta com a hora fica na régua).
                if (now in windowStart until windowStart + WINDOW_MS) {
                    Box(
                        Modifier
                            .offset(x = CHANNEL_COLUMN + x(now) - 1.dp, y = RULER_HEIGHT)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(GoatPalette.Live.copy(alpha = 0.9f)),
                    )
                }
                // A placa branca do programa escolhido desliza de bloco em bloco (de linha em linha também).
                val density = LocalDensity.current
                val plateTarget: () -> Rect? = target@{
                    val slot = selectedSlot
                    if (onHeader || slot == null) return@target null
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == row } ?: return@target null
                    with(density) {
                        val start = maxOf(slot.start, windowStart)
                        val end = minOf(slot.end, windowStart + WINDOW_MS)
                        val left = CHANNEL_COLUMN.toPx() + x(start).toPx()
                        val right = CHANNEL_COLUMN.toPx() + x(end).toPx() - CELL_GAP.toPx()
                        val top = RULER_HEIGHT.toPx() + item.offset + ROW_GAP.toPx()
                        Rect(left, top, right, top + item.size - ROW_GAP.toPx() * 2)
                    }
                }
                // A placa e a grelha em camadas próprias: a placa mexe-se em cada frame da animação sem obrigar a
                // redesenhar os textos das linhas todas.
                Spacer(Modifier.matchParentSize().graphicsLayer().guidePlate(plateTarget))
                Column(Modifier.graphicsLayer()) {
                    TimeRuler(windowStart, timelineWidth, now, s)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides GuideScrollSpec) {
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
                                val bring = remember { BringIntoViewRequester() }
                                if (index == row && !onHeader) LaunchedEffect(Unit) { bring.bringIntoView() }
                                GuideRow(
                                    modifier = Modifier.bringIntoViewRequester(bring),
                                    vm = vm,
                                    channel = ch,
                                    entries = entries,
                                    selectedSlot = if (index == row && !onHeader) selectedSlot else null,
                                    isPlaying = ch.key == playing.key,
                                    windowStart = windowStart,
                                    timelineWidth = timelineWidth,
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
}

/** Escurecido do guia: o vídeo vê-se em cima (por trás dos grupos e do programa) e quase nada por trás da grelha. */
private val GuideScrim = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.55f),
    0.12f to Color.Black.copy(alpha = 0.15f),
    0.24f to Color.Black.copy(alpha = 0.3f),
    0.38f to Color.Black.copy(alpha = 0.8f),
    0.52f to Color.Black.copy(alpha = 0.9f),
    1f to Color.Black.copy(alpha = 0.95f),
)

private val RULER_HEIGHT = 30.dp

/**
 * A placa do bloco escolhido, a deslizar até [target], por trás dos blocos (o texto do escolhido já é escuro). Em
 * "diferença" por cima obrigava a desenhar a grelha toda numa camada à parte em cada frame: na SHIELD, com o vídeo
 * em ecrã inteiro por trás, isso fazia saltar frames.
 */
@Composable
private fun Modifier.guidePlate(target: () -> Rect?): Modifier {
    val current by rememberUpdatedState(target)
    val left = remember { Animatable(0f) }
    val top = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val bottom = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { current() }.collect { r ->
            if (r == null) {
                shown = false
                return@collect
            }
            if (!shown) {
                // Aparece já no sítio (ao abrir, ou ao voltar dos grupos).
                left.snapTo(r.left); top.snapTo(r.top); right.snapTo(r.right); bottom.snapTo(r.bottom)
                shown = true
            } else {
                launch { left.animateTo(r.left, PlateSpring) }
                launch { top.animateTo(r.top, PlateSpring) }
                launch { right.animateTo(r.right, PlateSpring) }
                launch { bottom.animateTo(r.bottom, PlateSpring) }
            }
        }
    }
    return drawBehind {
        if (!shown) return@drawBehind
        drawRoundRect(
            Color.White,
            Offset(left.value, top.value),
            Size(right.value - left.value, bottom.value - top.value),
            CornerRadius(CELL_RADIUS.toPx()),
        )
    }
}

private val PlateSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val CELL_RADIUS = 7.dp

/** Os grupos em separadores; com foco (↑ na primeira linha) o atual fica branco e ← → mudam. */
@Composable
private fun GroupTabs(groups: List<Pair<String, String>>, selected: String, focused: Boolean, modifier: Modifier) {
    val state = rememberLazyListState()
    val index = groups.indexOfFirst { it.first == selected }
    LaunchedEffect(index) { if (index >= 0) state.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    LazyRow(state = state, modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(groups, key = { it.first }) { (id, name) ->
            val on = id == selected
            Text(
                name,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    on && focused -> Color.Black
                    on -> ItvColors.Text
                    else -> ItvColors.TextDim
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            on && focused -> Color.White
                            on -> Color.White.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** O programa escolhido, como no HUD: logótipo, canal, título, horas (e o que falta, se está a dar) e o que o OK faz. */
@Composable
private fun GuideHero(vm: AppViewModel, s: Strings, channel: Channel, slot: Slot, now: Long, onHeader: Boolean, showLogos: Boolean) {
    val e = slot.entry
    val live = e == null || (e.startMs <= now && e.endMs > now)
    Row(verticalAlignment = Alignment.Bottom) {
        if (showLogos) {
            HudLogo(channel, Modifier.size(width = 104.dp, height = 60.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column {
            ChannelLine(channel, isFavorite = false, style = MaterialTheme.typography.labelMedium)
            Text(
                e?.title ?: s.noGuideInfo,
                style = MaterialTheme.typography.headlineSmall.copy(shadow = TextShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            if (e != null && live) {
                ProgrammeTimes(e, now)
            } else if (e != null) {
                Text(
                    "${dayLabel(s, e.startMs)} ${formatTime(e.startMs)} — ${formatTime(e.endMs)}  ·  ${s.minutes(((e.endMs - e.startMs) / 60_000).toInt())}",
                    style = MaterialTheme.typography.bodySmall.copy(shadow = TextShadow),
                    color = ItvColors.TextDim,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
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
    }
}

@Composable
private fun TimeRuler(windowStart: Long, timelineWidth: Dp, now: Long, s: Strings) {
    Row(Modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        Box(Modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text(dayLabel(s, windowStart), style = MaterialTheme.typography.labelLarge, color = ItvColors.TextDim)
        }
        Box(Modifier.width(timelineWidth).fillMaxHeight()) {
            val step = timelineWidth / 4
            val nowX = timelineWidth * ((now - windowStart).toFloat() / WINDOW_MS)
            val showNow = now in windowStart until windowStart + WINDOW_MS
            for (i in 0 until 4) {
                // A hora que fica por baixo da etiqueta vermelha não se mostra.
                if (showNow && (step * i + 24.dp - nowX).value.let { kotlin.math.abs(it) } < 60f) continue
                Text(
                    formatTime(windowStart + i * HALF_HOUR),
                    style = MaterialTheme.typography.labelLarge.tabular,
                    color = ItvColors.TextDim,
                    modifier = Modifier.offset(x = step * i + 6.dp).align(Alignment.CenterStart),
                )
            }
            // A hora atual, numa etiqueta por cima da linha vermelha.
            if (now in windowStart until windowStart + WINDOW_MS) {
                val x = timelineWidth * ((now - windowStart).toFloat() / WINDOW_MS)
                Text(
                    formatTime(now),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold).tabular,
                    color = Color(0xFF0B0B0B),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            layout(p.width, p.height) { p.place(x.roundToPx() - p.width / 2, 0) }
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(GoatPalette.Live)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
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
    now: Long,
    showNumbers: Boolean,
    showLogos: Boolean,
    /** Só para voltar a ver os ícones quando os lembretes mudam. */
    reminders: Int,
    modifier: Modifier = Modifier,
) {
    val rowSelected = selectedSlot != null
    Row(modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = ROW_GAP)) {
        Row(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(CELL_RADIUS))
                .background(if (rowSelected) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showNumbers) {
                Text(channel.num.toString(), Modifier.width(34.dp), color = ItvColors.TextDim, style = MaterialTheme.typography.labelMedium.tabular)
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
                modifier = Modifier.weight(1f, fill = false),
            )
            // O canal a dar: o ponto de "em direto".
            if (isPlaying) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(GoatPalette.Live))
            }
        }
        GuideTimeline(vm, channel, entries, selectedSlot, windowStart, now, reminders, Modifier.width(timelineWidth).fillMaxHeight())
    }
}

/** Um bloco da linha do tempo: um programa, ou um espaço sem informação. */
private class Cell(
    val title: String,
    /** A hora de início a mostrar por baixo do título (null se o programa começou antes da grelha). */
    val startMs: Long?,
    val start: Long,
    val end: Long,
    val past: Boolean,
    val live: Boolean,
    val selected: Boolean,
    val icon: Int,
    /** O programa inteiro (para a barra do que está a dar). */
    val entryStart: Long? = null,
    val entryEnd: Long = 0,
)

private const val ICON_NONE = 0
private const val ICON_BELL = 1
private const val ICON_HISTORY = 2

/**
 * Os programas de um canal na janela visível, desenhados num só Canvas (o texto medido com cache). Antes era
 * uma Row com duas linhas de texto por programa: ao andar no guia, compor e medir dezenas de nós por linha
 * fazia saltar frames na SHIELD.
 */
@Composable
private fun GuideTimeline(
    vm: AppViewModel,
    channel: Channel,
    entries: List<EpgEntry>,
    selectedSlot: Slot?,
    windowStart: Long,
    now: Long,
    reminders: Int,
    modifier: Modifier,
) {
    val noInfo = LocalStrings.current.noGuideInfo
    val windowEnd = windowStart + WINDOW_MS
    val cells = remember(entries, selectedSlot, windowStart, now / 60_000, reminders) {
        buildList {
            val visible = entries.filter { it.endMs > windowStart && it.startMs < windowEnd }
            if (visible.isEmpty() && selectedSlot?.entry == null) {
                add(Cell(noInfo, null, windowStart, windowEnd, past = false, live = false, selected = false, icon = ICON_NONE))
            }
            for (e in visible) {
                val past = e.endMs <= now
                val icon = when {
                    e.startMs > now && vm.hasReminder(channel, e) -> ICON_BELL
                    past && vm.canCatchup(channel, e) -> ICON_HISTORY
                    else -> ICON_NONE
                }
                add(
                    Cell(
                        e.title, e.startMs.takeIf { it >= windowStart }, maxOf(e.startMs, windowStart), minOf(e.endMs, windowEnd),
                        past, live = e.startMs <= now && e.endMs > now, selected = selectedSlot?.entry == e, icon = icon,
                        entryStart = e.startMs, entryEnd = e.endMs,
                    ),
                )
            }
            // Bloco vazio escolhido (sem informação nessa hora).
            if (selectedSlot != null && selectedSlot.entry == null) {
                add(Cell(noInfo, null, maxOf(selectedSlot.start, windowStart), minOf(selectedSlot.end, windowEnd), past = false, live = false, selected = true, icon = ICON_NONE))
            }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE)
    val title = MaterialTheme.typography.bodyMedium
    val titleSelected = title.copy(fontWeight = FontWeight.SemiBold)
    val time = MaterialTheme.typography.labelSmall.tabular
    val bell = rememberVectorPainter(ItvIcons.Bell)
    val history = rememberVectorPainter(ItvIcons.History)
    val accent = LocalAccent.current.brush
    Canvas(modifier) {
        val pxPerMs = size.width / WINDOW_MS
        val gap = CELL_GAP.toPx()
        val pad = 8.dp.toPx()
        val radius = CornerRadius(CELL_RADIUS.toPx())
        val iconSize = 14.dp.toPx()
        for (c in cells) {
            val left = (c.start - windowStart) * pxPerMs
            val width = (c.end - c.start) * pxPerMs - gap
            if (width < 4.dp.toPx()) continue
            // O escolhido não tem fundo aqui: é a placa branca que desliza por trás da grelha.
            if (!c.selected) drawRoundRect(
                when {
                    c.live -> Color.White.copy(alpha = 0.1f)
                    c.past -> Color.White.copy(alpha = 0.025f)
                    else -> Color.White.copy(alpha = 0.06f)
                },
                Offset(left, 0f),
                Size(width, size.height),
                radius,
            )
            // O que está a dar: quanto já passou, numa barra fina em baixo.
            if (c.live && !c.selected && c.entryStart != null) {
                val barY = size.height - 6.dp.toPx()
                val barW = width - pad * 2
                val p = ((now - c.entryStart).toFloat() / (c.entryEnd - c.entryStart)).coerceIn(0f, 1f)
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(left + pad, barY), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                drawRoundRect(accent, Offset(left + pad, barY), Size(barW * p, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            }
            val ink = when {
                c.selected -> Color.Black
                c.past -> ItvColors.TextFaint
                else -> ItvColors.Text
            }
            var x = left + pad
            val painter = when (c.icon) {
                ICON_BELL -> bell
                ICON_HISTORY -> history
                else -> null
            }
            if (painter != null && width > 40.dp.toPx()) {
                translate(x, (size.height - iconSize) / 2) {
                    with(painter) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(if (c.selected) Color.Black else ItvColors.TextDim)) }
                }
                x += iconSize + 6.dp.toPx()
            }
            val maxWidth = (left + width - pad - x).toInt()
            if (maxWidth <= 0) continue
            val titleLayout = measurer.measure(
                c.title,
                (if (c.selected) titleSelected else title).copy(color = ink),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxWidth),
            )
            // No que está a dar a barra já diz onde vai: a hora de início sairia por cima dela.
            val timeLayout = if (c.startMs != null && !c.live && width > 90.dp.toPx()) {
                measurer.measure(
                    formatTime(c.startMs),
                    time.copy(
                        color = when {
                            c.selected -> Color.Black.copy(alpha = 0.6f)
                            c.past -> ItvColors.TextFaint.copy(alpha = 0.45f)
                            else -> ItvColors.TextFaint
                        },
                    ),
                    maxLines = 1,
                    constraints = Constraints(maxWidth = maxWidth),
                )
            } else {
                null
            }
            val textHeight = titleLayout.size.height + (timeLayout?.size?.height ?: 0)
            val top = (size.height - textHeight) / 2
            drawText(titleLayout, topLeft = Offset(x, top))
            if (timeLayout != null) drawText(timeLayout, topLeft = Offset(x, top + titleLayout.size.height))
        }
    }
}

/** Textos medidos guardados (títulos e horas das linhas à vista e das que acabaram de sair). */
private const val TEXT_CACHE = 256

private fun floorHalfHour(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    c.set(Calendar.MINUTE, if (c.get(Calendar.MINUTE) < 30) 0 else 30)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
