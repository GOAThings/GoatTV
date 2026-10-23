package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.SectionLabel
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors

/**
 * EPG de um canal, ao lado da lista de canais (→ na lista): os programas por ordem, com o atual em foco.
 * OK: a dar agora vê o canal; já passou vê em catch-up (se houver); ainda vai dar marca um lembrete.
 * ← ou Voltar regressa à lista.
 */
@Composable
internal fun ChannelEpgPanel(
    channel: Channel,
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onCatchup: (Channel, EpgEntry) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val version by vm.epgVersion.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val now = rememberNow()
    val entries = remember(channel, version) { vm.guideEntries(channel) }
    // Sem guia completo deste canal: pede o EPG curto (Xtream).
    LaunchedEffect(channel.key) { if (entries.isEmpty()) vm.epgFor(channel) }
    val shown = remember(entries) {
        val from = System.currentTimeMillis() - PAST_SHOWN_MS
        entries.filter { it.endMs > from }
    }
    val current = remember(shown) { shown.indexOfFirst { it.endMs > System.currentTimeMillis() }.coerceAtLeast(0) }
    val state = remember(channel) { LazyListState((current - 1).coerceAtLeast(0)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(channel, shown.isNotEmpty()) { focusWithRetry(focus) }

    fun act(e: EpgEntry) {
        val t = System.currentTimeMillis()
        when {
            e.startMs <= t && e.endMs > t -> onPlay(channel)
            e.startMs > t -> vm.toggleReminder(channel, e)
            vm.canCatchup(channel, e) -> onCatchup(channel, e)
            else -> vm.showToast(s.noCatchup)
        }
    }

    val shape = ItvShapes.Panel
    Column(
        Modifier
            .padding(start = 12.dp, top = 27.dp, bottom = 27.dp)
            .width((prefs.panelWidth + 60).dp)
            .fillMaxHeight()
            .glass(shape)
            .clip(shape)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (prefs.showLogos) {
                ChannelLogo(channel, 40.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.tvGuide, color = ItvColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (shown.isEmpty()) {
            Text(s.noGuideInfo, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(10.dp))
            // Algo com foco, para as teclas continuarem a funcionar aqui.
            GlassRow(onClick = { onPlay(channel) }, modifier = Modifier.focusRequester(focus), title = s.watch)
            return@Column
        }
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(shown, key = { _, e -> e.startMs }) { i, e ->
                // Separador quando muda o dia (Hoje, Amanhã…).
                if (i == 0 || dayLabel(s, shown[i - 1].startMs) != dayLabel(s, e.startMs)) {
                    SectionLabel(dayLabel(s, e.startMs))
                }
                val live = e.startMs <= now && e.endMs > now
                val icon = when {
                    e.startMs > now && reminders.isNotEmpty() && vm.hasReminder(channel, e) -> ItvIcons.Bell
                    e.endMs <= now && vm.canCatchup(channel, e) -> ItvIcons.History
                    else -> null
                }
                GlassRow(
                    onClick = { act(e) },
                    modifier = if (i == current) Modifier.focusRequester(focus) else Modifier,
                    selected = live,
                    leading = {
                        Text(formatTime(e.startMs), Modifier.width(56.dp), color = ItvColors.TextDim, style = MaterialTheme.typography.labelLarge)
                    },
                    trailing = icon?.let { ic -> { Icon(ic, contentDescription = null, modifier = Modifier.size(16.dp)) } },
                    title = e.title,
                    subtitle = if (live) s.onNow(formatTime(e.endMs)) else null,
                )
            }
        }
    }
}

/** Quanto do que já passou se mostra (para o catch-up). */
private const val PAST_SHOWN_MS = 6 * 3600_000L
