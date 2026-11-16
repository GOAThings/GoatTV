package cc.slokker.itv.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cc.slokker.itv.data.SportsChannel
import cc.slokker.itv.data.SportsGame
import cc.slokker.itv.data.SportsHub
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.ProgressBar
import cc.slokker.itv.ui.common.focusWithRetry
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Sports Hub na TV (fase 1, só com o guia): o jogo em destaque com os canais onde dá, os jogos a decorrer e os
 * próximos, e os canais de desporto. Painéis opacos por cima do vídeo (que continua a tocar por trás).
 * OK num jogo abre-o no melhor canal; se falhar, o player passa aos seguintes (AppViewModel.sportsFallback).
 */
@Composable
fun SportsHubTv(vm: AppViewModel, onPlayed: () -> Unit) {
    val s = LocalStrings.current
    val content by vm.content.collectAsStateWithLifecycle()
    val epgVersion by vm.epgVersion.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val tick = rememberNow()
    val now = remember(tick) { vm.sportsNow() }
    var hub by remember { mutableStateOf(vm.sportsLast()) }
    // Volta a procurar com guia novo, outra lista, canais escondidos, e a cada minuto (os jogos passam a "ao vivo").
    LaunchedEffect(content, epgVersion, prefs.hiddenGroups, prefs.hiddenChannels, now / 60_000) {
        hub = withContext(Dispatchers.Default) { vm.sportsHub() }
    }
    var sport by remember { mutableStateOf<String?>(null) }
    var featuredId by remember { mutableStateOf<String?>(null) }
    val games = hub?.games.orEmpty().filter { sport == null || it.sport == sport }
    val featured = games.firstOrNull { it.id == featuredId } ?: games.firstOrNull()
    val watchFocus = remember { FocusRequester() }
    val whereFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(hub != null, featured != null) {
        if (hub == null || focusedOnce) return@LaunchedEffect
        focusedOnce = true
        focusWithRetry(if (featured != null) watchFocus else chipFocus)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HubBackground)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Barra de cima: o nome, os desportos que há agora, e as horas.
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(PanelShape).background(HubPanel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(s.sportsHub.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, modifier = Modifier.padding(end = 8.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                item(key = "all") {
                    Chip(s.sportsAll, selected = sport == null, modifier = Modifier.focusRequester(chipFocus)) { sport = null; featuredId = null }
                }
                items(hub?.sports.orEmpty(), key = { it }) { sp ->
                    Chip(s.sportName(sp), selected = sport == sp) { sport = sp; featuredId = null }
                }
            }
            Text(formatTime(now), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        val result = hub
        when {
            result == null -> Message(s.sportsLoading, Modifier.weight(1f))
            featured == null -> Message(s.sportsEmpty, Modifier.weight(1f))
            else -> {
                Row(Modifier.fillMaxWidth().height(222.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Featured(featured, now, s, watchFocus, Modifier.weight(1f).fillMaxHeight(), onWatch = {
                        vm.playGame(featured)
                        onPlayed()
                    }, onOthers = { runCatching { whereFocus.requestFocus() } })
                    WherePanel(featured, vm, s, whereFocus, Modifier.width(250.dp).fillMaxHeight()) { ch ->
                        vm.playGame(featured, from = ch.key)
                        onPlayed()
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).clip(PanelShape).background(HubPanel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val live = games.count { it.isLive(now) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (live > 0) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                            Text("${s.sportsLive.uppercase()} · $live", Modifier.padding(start = 6.dp, end = 18.dp), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = LiveText)
                        }
                        Text(s.sportsNext.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Dim)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                        items(games.take(MAX_CARDS), key = { it.id }) { g ->
                            GameCard(g, now, s, onFocus = { featuredId = g.id }) {
                                vm.playGame(g)
                                onPlayed()
                            }
                        }
                    }
                    Text(s.sportsChannels.uppercase(), Modifier.padding(top = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Dim)
                    val byKey = content?.byKey.orEmpty()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                        items(result.channels.take(MAX_CHANNELS), key = { it.key }) { c ->
                            val ch = byKey[c.key] ?: return@items
                            HubSurface(onClick = {
                                vm.play(ch, ch.categoryId)
                                onPlayed()
                            }, modifier = Modifier.width(118.dp).height(58.dp)) {
                                Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    ChannelLogo(ch, 26.dp)
                                    Text(ch.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
private fun Featured(
    game: SportsGame,
    now: Long,
    s: Strings,
    watchFocus: FocusRequester,
    modifier: Modifier,
    onWatch: () -> Unit,
    onOthers: () -> Unit,
) {
    val live = game.isLive(now)
    Column(
        modifier.clip(PanelShape).background(Brush.linearGradient(sportGradient(game.sport))).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val badge = if (live) s.sportsLive.uppercase() else whenText(game.startMs, now, s).uppercase()
            Text(
                badge, Modifier.clip(RoundedCornerShape(5.dp)).background(if (live) LiveRed else Color.White.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            )
            val meta = listOfNotNull(s.sportName(game.sport), game.league.takeIf { it.isNotEmpty() }, if (live) s.sportsStarted(formatTime(game.startMs)) else null)
            Text(meta.joinToString(" · "), fontSize = 12.sp, color = TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (game.home.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Team(game.home, Modifier.weight(1f))
                Text("–", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                Team(game.away, Modifier.weight(1f))
            }
        } else {
            Text(game.name, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live && game.endMs > game.startMs) {
                ProgressBar(((now - game.startMs).toFloat() / (game.endMs - game.startMs)).coerceIn(0f, 1f), Modifier.fillMaxWidth(0.5f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(s.sportsWatchOn(game.channels.first().name), primary = true, modifier = Modifier.focusRequester(watchFocus), onClick = onWatch)
                if (game.channels.size > 1) Pill(s.sportsOtherChannels(game.channels.size - 1), primary = false, onClick = onOthers)
            }
        }
    }
}

@Composable
private fun Team(name: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TeamBadge(name, 54)
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** O círculo com as iniciais (o guia não traz logótipos das equipas). */
@Composable
internal fun TeamBadge(name: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(SportsHub.COLORS[SportsHub.colorIndex(name)])),
        contentAlignment = Alignment.Center,
    ) {
        Text(SportsHub.initials(name), fontSize = (size * 0.3f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

@Composable
private fun WherePanel(game: SportsGame, vm: AppViewModel, s: Strings, focus: FocusRequester, modifier: Modifier, onPick: (SportsChannel) -> Unit) {
    val content by vm.content.collectAsStateWithLifecycle()
    Column(modifier.clip(PanelShape).background(HubPanel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.sportsWhere.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Dim)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(game.channels, key = { it.key }) { c ->
                val ch = content?.byKey?.get(c.key) ?: return@items
                HubSurface(
                    onClick = { onPick(c) },
                    modifier = Modifier.fillMaxWidth().let { if (c == game.channels.first()) it.focusRequester(focus) else it },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChannelLogo(ch, 24.dp)
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            c.country?.let { Text(s.countryName(it), fontSize = 10.sp, color = LocalContentColor.current.copy(alpha = 0.7f), maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: SportsGame, now: Long, s: Strings, onFocus: () -> Unit, onClick: () -> Unit) {
    val live = game.isLive(now)
    HubSurface(onClick = onClick, modifier = Modifier.width(176.dp).height(84.dp).onFocusChanged { if (it.isFocused) onFocus() }) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val label = if (live) "${s.sportName(game.sport)} · ${s.sportsLive}" else "${whenText(game.startMs, now, s)} · ${s.sportName(game.sport)}"
            Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = if (live) LiveText else NextText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(game.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(game.channels.first().name, fontSize = 10.sp, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "22:15", "Amanhã 15:00" ou o dia (até 36 h à frente). */
internal fun whenText(start: Long, now: Long, s: Strings): String {
    val a = Calendar.getInstance().apply { timeInMillis = now }
    val b = Calendar.getInstance().apply { timeInMillis = start }
    val days = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 366 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR)
    return if (days <= 0) s.sportsToday(formatTime(start)) else s.sportsTomorrow(formatTime(start))
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().clip(PanelShape).background(HubPanel).padding(40.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, color = TextSoft, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(30.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.3f) else Raised,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.16f),
            contentColor = if (primary) Color.Black else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Um cartão do Sports Hub: escuro em repouso, branco com texto escuro com foco (como as linhas da app). */
@Composable
private fun HubSurface(onClick: () -> Unit, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp), content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Raised,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) { content() }
}

internal fun sportGradient(sport: String): List<Color> = when (sport) {
    SportsHub.FOOTBALL -> listOf(Color(0xFF0F3D24), Color(0xFF14532D), Color(0xFF0B1220))
    SportsHub.BASKETBALL -> listOf(Color(0xFF5A240C), Color(0xFF7C2D12), Color(0xFF0B1220))
    SportsHub.TENNIS -> listOf(Color(0xFF3F4A0C), Color(0xFF4D5E10), Color(0xFF0B1220))
    SportsHub.MOTOR -> listOf(Color(0xFF4A0D0D), Color(0xFF7F1D1D), Color(0xFF0B1220))
    SportsHub.HOCKEY, SportsHub.AMFOOTBALL, SportsHub.BASEBALL -> listOf(Color(0xFF0C2A4A), Color(0xFF1E3A8A), Color(0xFF0B1220))
    SportsHub.FIGHT -> listOf(Color(0xFF3B0A2A), Color(0xFF701A4A), Color(0xFF0B1220))
    else -> listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF0B1220))
}

private const val MAX_CARDS = 40
private const val MAX_CHANNELS = 40
private val PanelShape = RoundedCornerShape(16.dp)
private val HubBackground = Color(0xFF07090E)
private val HubPanel = Color(0xFF121722)
private val Raised = Color(0xFF1A202C)
private val Dim = Color(0xFF9AA4B5)
private val TextSoft = Color(0xFFC9D1DD)
private val LiveRed = Color(0xFFEF4444)
private val LiveText = Color(0xFFFCA5A5)
private val NextText = Color(0xFF7DD3FC)
