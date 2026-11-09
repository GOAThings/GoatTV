package cc.slokker.itv.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberEpg
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.data.at
import cc.slokker.itv.data.after
import cc.slokker.itv.data.isOn
import cc.slokker.itv.data.minutesLeft

/**
 * O guia do telemóvel: o que dá em cada canal à hora escolhida (Agora, ou de meia em meia hora para trás, para o
 * catch-up, e para a frente), com quanto falta e o que vem a seguir.
 */
@Composable
internal fun GuideList(vm: AppViewModel, channels: List<Channel>, groupId: String, current: Channel, onEpg: (Channel) -> Unit) {
    val s = LocalStrings.current
    val now = rememberNow()
    val played = LocalOnPlayed.current
    var slot by rememberSaveable { mutableStateOf<Long?>(null) }
    val first = now - now % HALF_HOUR + HALF_HOUR
    val rows = rememberRowState(vm)
    Column(Modifier.fillMaxSize()) {
        // Para trás, para o catch-up; "Agora" começa perto do meio do ecrã.
        LazyRow(state = rememberLazyListState(PAST_SLOTS - 3), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
            items(PAST_SLOTS) { i ->
                val time = first + (i - PAST_SLOTS) * HALF_HOUR
                SlotChip(formatTime(time), slot == time) { slot = time }
            }
            item { SlotChip(s.guideNow, slot == null) { slot = null } }
            items(FUTURE_SLOTS) { i ->
                val time = first + i * HALF_HOUR
                SlotChip(formatTime(time), slot == time) { slot = time }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
        if (channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(s.noChannelsInGroup, color = ItvColors.TextFaint, fontSize = 15.sp) }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = LocalBarSpace.current)) {
                items(channels, key = { it.uid }) { channel ->
                    GuideRow(vm, channel, slot, now, channel.key == current.key && slot == null, rows, onPlay = { vm.play(channel, groupId); played() }, onLong = { onEpg(channel) })
                }
            }
        }
    }
}

@Composable
private fun SlotChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else ItvColors.TextFaint, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
        modifier = Modifier.clip(PhoneShapes.List).background(if (on) Color.White else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Um canal no guia: o programa à hora escolhida (com quanto falta, se está a dar) e o seguinte, com o sino do
 * lembrete. Agora, tocar abre o canal; no passado, o catch-up; a outra hora, o guia dele.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(vm: AppViewModel, channel: Channel, slot: Long?, now: Long, playing: Boolean, rows: RowState, onPlay: () -> Unit, onLong: () -> Unit) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val entries = remember(epg, channel.key) { vm.programmes(channel, epg) }
    val at = slot ?: now
    val entry = entries.at(at)
    val next = entries.after(entry, at)
    val live = entry != null && entry.isOn(now)
    val catchup = slot != null && entry != null && entry.endMs <= now && vm.canCatchup(channel, entry)
    val played = LocalOnPlayed.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PhoneShapes.List)
            .background(if (playing) accent.start.copy(alpha = PhoneColors.SELECTED_ALPHA) else Color.Transparent)
            .combinedClickable(onClick = {
                when {
                    slot == null -> onPlay()
                    catchup -> { vm.playCatchup(channel, entry!!); played() }
                    else -> onLong()
                }
            }, onLongClick = onLong)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rows.showLogos) {
            Box(Modifier.size(56.dp, 38.dp), contentAlignment = Alignment.Center) { ChannelLogo(channel, 38.dp) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry?.title ?: channel.name, color = if (playing) accent.start else ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val times = entry?.let { e ->
                "${formatTime(e.startMs)} – ${formatTime(e.endMs)}" + if (live) " · ${s.minutesLeft(e.minutesLeft(now))}" else ""
            }
            Text(times ?: s.noGuide, color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (live) {
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(PhoneColors.ProgressTrack)) {
                    Box(Modifier.fillMaxWidth(entry!!.progress(now)).fillMaxHeight().background(if (playing) accent.brush else SolidColor(PhoneColors.ProgressDim)))
                }
            }
            if (next != null) {
                val reminded = rows.reminded(channel, next)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.next(formatTime(next.startMs)), color = ItvColors.TextFaint, fontSize = 12.5.sp, maxLines = 1)
                    Spacer(Modifier.width(7.dp))
                    Text(next.title, color = ItvColors.TextDim, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // O lembrete do programa seguinte, sem abrir nada.
                    Icon(
                        ItvIcons.Bell, s.setReminder, tint = if (reminded) accent.start else ItvColors.TextFaint.copy(alpha = 0.6f),
                        modifier = Modifier.size(30.dp).clip(CircleShape).clickable { vm.toggleReminder(channel, next) }.padding(6.dp),
                    )
                }
            }
        }
        if (catchup) {
            Spacer(Modifier.width(8.dp))
            Icon(ItvIcons.Replay, s.watchCatchup, tint = ItvColors.TextFaint, modifier = Modifier.size(20.dp))
        }
    }
}

private const val HALF_HOUR = 1_800_000L
/** Meias horas para trás (o catch-up) e para a frente. */
private const val PAST_SLOTS = 36
private const val FUTURE_SLOTS = 12
