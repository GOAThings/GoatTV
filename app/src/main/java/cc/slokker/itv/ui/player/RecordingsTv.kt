package cc.slokker.itv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.recording.RecordingInfo
import cc.slokker.itv.data.recording.RecordingState
import cc.slokker.itv.data.recording.ScheduledRecording
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.KeyHint
import cc.slokker.itv.ui.common.dayLabel
import cc.slokker.itv.ui.common.focusWithRetry
import cc.slokker.itv.ui.common.formatDuration
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.SectionLabel
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.theme.GoatPalette
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.ItvShapes
import kotlinx.coroutines.delay

/** A cápsula "REC" no canto enquanto há uma gravação (com o título do que grava). */
@Composable
internal fun RecPill(title: String, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .widthIn(max = 420.dp)
            .glass(ItvShapes.Control)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(GoatPalette.Live))
        Spacer(Modifier.width(8.dp))
        Text(s.recBadge, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = ItvColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Mudar de canal com uma gravação a decorrer: OK pára a gravação e muda, Voltar fica a gravar (neste canal). É a
 * mesma forma do lembrete ([ReminderCard]): o fornecedor só deixa uma ligação, e o cartão diz isso.
 */
@Composable
internal fun RecordingSwitchCard(prompt: AppViewModel.SwitchPrompt, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    PromptCard(
        icon = { Box(Modifier.size(14.dp).clip(CircleShape).background(GoatPalette.Live)) },
        overline = s.recBadge,
        title = s.recordingSwitchTitle(prompt.title),
        text = s.recordingSwitchText,
        ok = s.recordingStopAndSwitch,
        back = s.recordingKeep,
        modifier = modifier,
    )
}

/**
 * Uma marcada noutro canal quase a começar: OK muda para esse canal (e grava), Voltar não grava. Sem resposta até à
 * hora, muda-se ([secondsLeft] conta até lá).
 */
@Composable
internal fun RecordingPromptCard(recording: ScheduledRecording, secondsLeft: Int, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    PromptCard(
        icon = { Icon(ItvIcons.Record, contentDescription = null, modifier = Modifier.size(22.dp), tint = GoatPalette.Live) },
        overline = s.recordingSoon(recording.channelName),
        title = recording.title,
        text = "${formatTime(recording.startMs)} · ${s.recordingSwitchingIn(secondsLeft.coerceAtLeast(0))}",
        ok = s.recordingSwitchTo(recording.channelName),
        back = s.recordingSkip,
        modifier = modifier,
    )
}

@Composable
private fun PromptCard(icon: @Composable () -> Unit, overline: String, title: String, text: String, ok: String, back: String, modifier: Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .padding(48.dp)
            .widthIn(max = 560.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 28.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(ItvColors.TextFaint.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(overline, color = ItvColors.TextDim, style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyHint("OK", ok)
            KeyHint(s.backKeyName, back)
        }
    }
}

/** "12,3 GB", "850 MB". */
internal fun sizeLabel(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${(bytes / 1_000_000L).coerceAtLeast(0)} MB"

/** A linha de baixo de uma gravação: canal, dia e hora, duração e, se for o caso, como acabou. */
internal fun recordingLine(s: Strings, info: RecordingInfo): String {
    val state = when {
        info.recording -> s.recBadge
        info.state == RecordingState.FAILED -> s.recordingStateFailed
        info.state == RecordingState.STOPPED -> s.recordingStateStopped
        else -> null
    }
    return listOfNotNull(info.channelName, "${dayLabel(s, info.fileStartMs)} ${formatTime(info.fileStartMs)}", formatDuration(info.durationMs), state).joinToString(" · ")
}

/** A linha de baixo de uma marcada: canal, dia e hora (e "série"). */
internal fun scheduledLine(s: Strings, r: ScheduledRecording): String =
    listOfNotNull(r.channelName, "${dayLabel(s, r.startMs)} ${formatTime(r.startMs)} – ${formatTime(r.endMs)}", s.recordSeries.takeIf { r.seriesId != null }).joinToString(" · ")

/**
 * As gravações na TV: um painel de vidro à esquerda (como a pesquisa) com as que estão a gravar, as marcadas e as
 * gravadas, e o espaço livre. OK vê uma gravação; manter OK (ou Menu) pára, apaga ou desmarca, com uma segunda vez para
 * confirmar (como os outros "carregue outra vez" das Definições).
 */
@Composable
internal fun RecordingsPanel(vm: AppViewModel, width: Dp, onPlay: (RecordingInfo) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val list by vm.recordingList.collectAsStateWithLifecycle()
    val schedule by vm.recordingSchedule.collectAsStateWithLifecycle()
    val active by vm.activeRecordings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    var free by remember { mutableStateOf(vm.recordingFreeBytes()) }
    LaunchedEffect(list) { free = vm.recordingFreeBytes() }
    val first = remember { FocusRequester() }
    val activeIds = active.map { it.info.id }.toSet()
    val recorded = list
    val scheduled = schedule.recordings
    LaunchedEffect(recorded.isEmpty() && scheduled.isEmpty()) { focusWithRetry(first) }

    fun longPress(key: String, action: () -> Unit) {
        if (confirm == key) {
            confirm = null
            action()
        } else {
            confirm = key
        }
    }

    Column(
        modifier
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp)
            .width(width)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text(s.recordings, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text(s.recordingsFree(sizeLabel(free)), color = ItvColors.TextDim, style = MaterialTheme.typography.labelMedium)
        }
        if (recorded.isEmpty() && scheduled.isEmpty()) {
            Text(s.recordingsEmpty, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            // Algo com foco, para o Voltar e as teclas continuarem a funcionar aqui.
            GlassRow(onClick = {}, title = s.recordings, modifier = Modifier.focusRequester(first))
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (scheduled.isNotEmpty()) item(key = "h:s") { SectionLabel(s.recordingsScheduled) }
            items(scheduled, key = { "s:" + it.id }) { r ->
                val key = "s:" + r.id
                val cancel = { if (r.seriesId != null) vm.cancelSeries(r.seriesId) else vm.cancelScheduled(r.id) }
                GlassRow(
                    onClick = { confirm = null },
                    onLongClick = { longPress(key, cancel) },
                    leading = { Icon(if (r.seriesId != null) ItvIcons.RecordSeries else ItvIcons.Record, contentDescription = null, modifier = Modifier.padding(end = 10.dp).size(16.dp), tint = GoatPalette.Live) },
                    title = r.title,
                    subtitle = if (confirm == key) s.pressAgainToConfirm else scheduledLine(s, r),
                    trailing = { Text(if (r.seriesId != null) s.cancelSeries else s.cancelRecording, color = LocalContentColor.current.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.then(if (r == scheduled.first()) Modifier.focusRequester(first) else Modifier).menuKey { longPress(key, cancel) },
                )
            }
            if (recorded.isNotEmpty()) item(key = "h:r") { SectionLabel(s.recordingsRecorded) }
            items(recorded, key = { "r:" + it.id }) { info ->
                val key = "r:" + info.id
                val running = info.id in activeIds
                GlassRow(
                    onClick = {
                        confirm = null
                        if (info.state != RecordingState.FAILED) onPlay(info)
                    },
                    onLongClick = { longPress(key) { if (running) vm.stopRecording(info.id) else vm.deleteRecording(info.id) } },
                    leading = if (running) {
                        { Box(Modifier.padding(end = 10.dp).size(10.dp).clip(CircleShape).background(GoatPalette.Live)) }
                    } else {
                        null
                    },
                    title = info.title,
                    subtitle = if (confirm == key) s.pressAgainToConfirm else recordingLine(s, info),
                    trailing = { Text(if (running) s.stopRecording else sizeLabel(info.bytes), color = LocalContentColor.current.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier
                        .then(if (scheduled.isEmpty() && info == recorded.first()) Modifier.focusRequester(first) else Modifier)
                        .menuKey { longPress(key) { if (running) vm.stopRecording(info.id) else vm.deleteRecording(info.id) } },
                )
            }
        }
    }
    // A confirmação sai sozinha se não se carregar outra vez.
    LaunchedEffect(confirm) {
        if (confirm == null) return@LaunchedEffect
        delay(CONFIRM_MS)
        confirm = null
    }
}

/** A tecla Menu (≡) do comando faz o mesmo que manter OK. */
private fun Modifier.menuKey(action: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    val menu = e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_MENU
    if (menu && e.type == KeyEventType.KeyUp) action()
    menu
}

private const val CONFIRM_MS = 4_000L
