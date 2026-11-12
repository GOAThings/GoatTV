package cc.slokker.itv.ui.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.data.recording.RecordingInfo
import cc.slokker.itv.data.recording.RecordingState
import cc.slokker.itv.data.recording.ScheduledRecording
import cc.slokker.itv.recording.RecordingAlarm
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.recordingLine
import cc.slokker.itv.ui.player.scheduledLine
import cc.slokker.itv.ui.player.sizeLabel
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.delay

/**
 * As gravações no telemóvel (do ecrã inicial, ao lado da pesquisa): as que estão a gravar, as marcadas e as
 * gravadas, com o espaço livre. Tocar vê; o ✕ desmarca ou pára, o caixote apaga (com um segundo toque para
 * confirmar). Sem alarmes exatos, uma nota diz que, com a app fechada, podem começar tarde.
 */
@Composable
internal fun RecordingsPage(vm: AppViewModel, onBack: () -> Unit, onPlay: (RecordingInfo) -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val list by vm.recordingList.collectAsStateWithLifecycle()
    val schedule by vm.recordingSchedule.collectAsStateWithLifecycle()
    val active by vm.activeRecordings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    val activeIds = active.map { it.info.id }.toSet()
    val free = remember(list) { vm.recordingFreeBytes() }
    LaunchedEffect(confirm) {
        if (confirm == null) return@LaunchedEffect
        delay(4_000)
        confirm = null
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(s.recordings, "TV", onBack)
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBarSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text(s.recordingsFree(sizeLabel(free)), color = ItvColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
            }
            if (schedule.recordings.isNotEmpty() && !RecordingAlarm.canBeExact(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    Section(null) {
                        MenuRow(s.recordingExactAlarm, s.recordingAllow, ItvIcons.Clock) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                            }
                        }
                    }
                }
            }
            if (list.isEmpty() && schedule.recordings.isEmpty()) item { Note(s.recordingsEmpty) }
            if (schedule.recordings.isNotEmpty()) {
                item {
                    Section(s.recordingsScheduled) {
                        schedule.recordings.forEach { r -> ScheduledRow(vm, r, confirm == "s:" + r.id) { confirm = "s:" + r.id } }
                    }
                }
            }
            if (list.isNotEmpty()) {
                item {
                    Section(s.recordingsRecorded) {
                        list.forEach { info ->
                            val key = "r:" + info.id
                            RecordingRow(info, running = info.id in activeIds, confirming = confirm == key, onPlay = { onPlay(info) }) {
                                when {
                                    info.id in activeIds -> vm.stopRecording(info.id)
                                    confirm == key -> {
                                        confirm = null
                                        vm.deleteRecording(info.id)
                                    }
                                    else -> confirm = key
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledRow(vm: AppViewModel, r: ScheduledRecording, confirming: Boolean, onAsk: () -> Unit) {
    val s = LocalStrings.current
    RowLine(
        icon = if (r.seriesId != null) ItvIcons.RecordSeries else ItvIcons.Record,
        iconTint = PhoneColors.LiveRed,
        title = r.title,
        sub = if (confirming) s.pressAgainToConfirm else scheduledLine(s, r),
        action = ItvIcons.Close,
        onClick = {},
        onAction = {
            if (!confirming) return@RowLine onAsk()
            r.seriesId?.let(vm::cancelSeries) ?: vm.cancelScheduled(r.id)
        },
    )
}

@Composable
private fun RecordingRow(info: RecordingInfo, running: Boolean, confirming: Boolean, onPlay: () -> Unit, onAction: () -> Unit) {
    val s = LocalStrings.current
    RowLine(
        icon = if (running) ItvIcons.Record else ItvIcons.PlayCircle,
        iconTint = if (running) PhoneColors.LiveRed else ItvColors.TextDim,
        title = info.title,
        sub = if (confirming) s.pressAgainToConfirm else recordingLine(s, info) + if (!running) " · ${sizeLabel(info.bytes)}" else "",
        action = if (running) ItvIcons.Stop else ItvIcons.Delete,
        onClick = { if (info.state != RecordingState.FAILED) onPlay() },
        onAction = onAction,
    )
}

@Composable
private fun RowLine(icon: ImageVector, iconTint: Color, title: String, sub: String, action: ImageVector, onClick: () -> Unit, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ControlButton(action, onClick = onAction)
    }
}

/**
 * Um aviso no alto do ecrã com duas escolhas (como o do lembrete): mudar de canal com uma gravação a decorrer, ou
 * uma marcada noutro canal quase a começar.
 */
@Composable
internal fun PhonePromptCard(overline: String, title: String, text: String, primary: String, secondary: String, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Column(Modifier.fillMaxWidth().clip(PhoneShapes.Card).background(PhoneColors.Reminder).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(PhoneColors.LiveRed))
                Spacer(Modifier.width(8.dp))
                Text(overline, color = ItvColors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, color = ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(text, color = ItvColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    primary, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Color.White).clickable(onClick = onPrimary).padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    secondary, color = accent.start, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clip(CircleShape).background(PhoneColors.Card).clickable(onClick = onSecondary).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
