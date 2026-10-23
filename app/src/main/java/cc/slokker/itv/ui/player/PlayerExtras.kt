package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.KeyHint
import cc.slokker.itv.ui.common.ProgressBar
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Pesquisa de canais e programas, encostada à esquerda (como os painéis). */
@Composable
internal fun SearchPanel(vm: AppViewModel, width: Dp, onPick: (AppViewModel.SearchHit) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<AppViewModel.SearchHit>>(emptyList()) }
    val fieldFocus = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val now = rememberNow()

    LaunchedEffect(Unit) {
        focusWithRetry(fieldFocus)
        keyboard?.show()
    }
    // Pesquisa enquanto se escreve, com uma pequena pausa (não procura a cada letra).
    LaunchedEffect(query) {
        delay(250)
        hits = withContext(Dispatchers.Default) { vm.search(query) }
    }

    Column(
        modifier
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp)
            .width(width)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(s.search, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(color = ItvColors.Text),
            cursorBrush = SolidColor(LocalAccent.current.start),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                if (hits.isNotEmpty()) runCatching { firstResult.requestFocus() }
            }),
            decorationBox = { field ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ItvIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = ItvColors.TextDim)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(s.searchHint, color = ItvColors.TextFaint, style = MaterialTheme.typography.titleSmall)
                        field()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .clip(ItvShapes.Control)
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(10.dp))
        when {
            query.trim().length < 2 -> Text(s.searchMinChars, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            hits.isEmpty() -> Text(s.searchNoResults, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(hits, key = { h -> h.channel.uid.toString() + ":" + (h.programme?.startMs ?: 0) }) { hit ->
                val p = hit.programme
                val first = hit == hits.first()
                GlassRow(
                    onClick = { onPick(hit) },
                    modifier = if (first) Modifier.focusRequester(firstResult) else Modifier,
                    leading = if (prefs.showLogos) {
                        { ChannelLogo(hit.channel, 34.dp); Spacer(Modifier.width(12.dp)) }
                    } else {
                        null
                    },
                    trailing = if (p != null && vm.hasReminder(hit.channel, p)) {
                        { Icon(ItvIcons.Bell, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                    title = p?.title ?: hit.channel.name,
                    subtitle = when {
                        p == null -> vm.content.value?.categoryName(hit.channel.categoryId)
                        p.startMs <= now -> s.nowOn(hit.channel.name)
                        else -> "${formatTime(p.startMs)} · ${hit.channel.name}"
                    },
                )
            }
        }
    }
}

/** Número a ser escrito no comando, com o canal que lhe corresponde. */
@Composable
internal fun NumberEntryBadge(number: String, channel: Channel?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .widthIn(min = 160.dp)
            .glass(ItvShapes.Card)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(number, style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold))
        Text(
            channel?.name ?: "—",
            color = ItvColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

/** Cápsula "CATCH-UP" no canto, sempre visível enquanto não se está em direto. */
@Composable
internal fun CatchupPill(paused: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .glass(ItvShapes.Control)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (paused) ItvIcons.Pause else ItvIcons.History, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(LocalStrings.current.catchupLabel, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Barra do catch-up: programa, posição e as teclas (← → recuar/avançar, OK pausa, Voltar direto). */
@Composable
internal fun CatchupBar(catchup: Catchup, positionMs: Long, pendingSeekMs: Long?, paused: Boolean, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val shown = pendingSeekMs ?: positionMs
    Column(
        modifier
            .padding(start = 48.dp, end = 48.dp, bottom = 27.dp)
            .fillMaxWidth()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(ItvShapes.Small)
                    .background(LocalAccent.current.brush)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(s.catchupLabel, color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(catchup.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${catchup.channel.name} · ${formatTime(catchup.startMs)} – ${formatTime(catchup.endMs)}",
                    color = ItvColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (paused) Icon(ItvIcons.Pause, contentDescription = null, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(shown), style = MaterialTheme.typography.labelLarge, color = if (pendingSeekMs != null) LocalAccent.current.start else ItvColors.Text)
            ProgressBar((shown.toFloat() / catchup.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.weight(1f).padding(horizontal = 14.dp))
            Text(formatDuration(catchup.durationMs), style = MaterialTheme.typography.labelLarge, color = ItvColors.TextDim)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyHint("← →", s.seekMinute)
            KeyHint("OK", if (paused) s.resume else s.pause)
            KeyHint(s.backKeyName, s.backToLive)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Escolha do temporizador para desligar. */
@Composable
internal fun SleepPicker(current: Long?, onPick: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(focus) }
    val now = rememberNow()
    Column(
        modifier
            .width(360.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(s.sleepTimer, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        current?.let {
            Text(s.sleepRemaining(((it - now) / 60_000 + 1).toInt()), color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        }
        val options = listOf<Int?>(null, 15, 30, 45, 60, 90, 120)
        options.forEachIndexed { i, minutes ->
            GlassRow(
                onClick = { onPick(minutes) },
                modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                title = minutes?.let { s.minutes(it) } ?: s.off,
            )
        }
    }
}

/** Escolha da faixa de áudio ou das legendas (no HUD). */
@Composable
internal fun TrackPicker(title: String, options: List<TrackOption>, onPicked: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(focus) }
    val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        modifier
            .width(420.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        if (options.isEmpty()) {
            Text(s.noTracks, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            // Sem faixas, o foco fica num botão para fechar.
            GlassRow(onClick = onPicked, modifier = Modifier.focusRequester(focus), title = s.close)
        }
        options.forEachIndexed { i, o ->
            GlassRow(
                onClick = {
                    o.select()
                    onPicked()
                },
                modifier = if (i == focusIndex) Modifier.focusRequester(focus) else Modifier,
                title = o.label,
                subtitle = o.details?.takeIf { it.isNotEmpty() },
                selected = o.selected,
                trailing = if (o.selected) {
                    { Text("✓", style = MaterialTheme.typography.titleMedium) }
                } else {
                    null
                },
            )
        }
    }
}

/** Aviso 1 minuto antes de o temporizador parar a app. */
@Composable
internal fun SleepWarning(secondsLeft: Int, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .widthIn(max = 620.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 32.dp, vertical = 26.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(ItvIcons.Sleep, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text(s.sleepSoon(secondsLeft), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyHint("OK", s.keepWatching)
        }
    }
}

/** Um programa com lembrete vai começar. */
@Composable
internal fun ReminderCard(reminder: Reminder, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val pulse by rememberInfiniteTransition(label = "bell").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        modifier
            .padding(48.dp)
            .widthIn(max = 520.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 28.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).alpha(pulse).clip(CircleShape).background(LocalAccent.current.brush),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ItvIcons.Bell, contentDescription = null, modifier = Modifier.size(22.dp), tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(s.startingNow, color = ItvColors.TextDim, style = MaterialTheme.typography.labelLarge)
                Text(reminder.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(reminder.startMs)} · ${reminder.channelName}", color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyHint("OK", s.watch)
            KeyHint(s.backKeyName, s.dismiss)
        }
    }
}
