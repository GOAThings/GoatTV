package cc.slokker.itv.ui.mobile

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.slokker.itv.data.SportsGame
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.TeamBadge
import cc.slokker.itv.ui.player.sportGradient
import cc.slokker.itv.ui.player.whenText
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sports Hub no telemóvel (o mesmo da TV, em coluna): os desportos, o jogo em destaque com onde ver, e os jogos a
 * decorrer e os próximos. Tocar num jogo abre-o no melhor canal; tocar num canal de "Onde ver" abre esse.
 */
@Composable
internal fun SportsPage(vm: AppViewModel, onBack: () -> Unit, onPlayed: () -> Unit) {
    val s = LocalStrings.current
    val accent = LocalAccent.current.start
    val content by vm.content.collectAsStateWithLifecycle()
    val epgVersion by vm.epgVersion.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val tick = rememberNow()
    val now = remember(tick) { vm.sportsNow() }
    var hub by remember { mutableStateOf(vm.sportsLast()) }
    LaunchedEffect(content, epgVersion, prefs.hiddenGroups, prefs.hiddenChannels, now / 60_000) {
        hub = withContext(Dispatchers.Default) { vm.sportsHub() }
    }
    var sport by remember { mutableStateOf<String?>(null) }
    var featuredId by remember { mutableStateOf<String?>(null) }
    val games = hub?.games.orEmpty().filter { sport == null || it.sport == sport }
    val featured = games.firstOrNull { it.id == featuredId } ?: games.firstOrNull()

    Column(Modifier.fillMaxSize()) {
        PageHeader(s.sportsHub, "TV", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = LocalBarSpace.current + 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { HubChip(s.sportsAll, sport == null, accent) { sport = null; featuredId = null } }
                    items(hub?.sports.orEmpty()) { sp -> HubChip(s.sportName(sp), sport == sp, accent) { sport = sp; featuredId = null } }
                }
            }
            val result = hub
            when {
                result == null -> item { HubNote(s.sportsLoading) }
                featured == null -> item { HubNote(s.sportsEmpty) }
                else -> {
                    item(key = "featured") {
                        FeaturedCard(featured, now) {
                            vm.playGame(featured)
                            onPlayed()
                        }
                    }
                    if (featured.channels.size > 1) {
                        item { HubLabel(s.sportsWhere) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(featured.channels, key = { it.key }) { c ->
                                    val ch = content?.byKey?.get(c.key) ?: return@items
                                    Row(
                                        Modifier.clip(RoundedCornerShape(12.dp)).background(Raised).clickable {
                                            vm.playGame(featured, from = c.key)
                                            onPlayed()
                                        }.padding(horizontal = 10.dp, vertical = 8.dp).width(170.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ChannelLogo(ch, 26.dp)
                                        Column(Modifier.weight(1f)) {
                                            Text(ch.name, color = ItvColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            c.country?.let { Text(s.countryName(it), color = ItvColors.TextFaint, fontSize = 11.sp, maxLines = 1) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val live = games.filter { it.isLive(now) }
                    val next = games.filter { !it.isLive(now) }
                    if (live.isNotEmpty()) {
                        item { HubLabel("${s.sportsLive} · ${live.size}", LiveText) }
                        items(live, key = { "l:" + it.id }) { g -> GameRow(g, now, selected = g.id == featured.id) { featuredId = g.id } }
                    }
                    if (next.isNotEmpty()) {
                        item { HubLabel(s.sportsNext) }
                        items(next, key = { "n:" + it.id }) { g -> GameRow(g, now, selected = g.id == featured.id) { featuredId = g.id } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(game: SportsGame, now: Long, onWatch: () -> Unit) {
    val s = LocalStrings.current
    val live = game.isLive(now)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(sportGradient(game.sport))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val badge = if (live) s.sportsLive.uppercase() else whenText(game.startMs, now, s).uppercase()
            Text(
                badge, Modifier.clip(RoundedCornerShape(5.dp)).background(if (live) LiveRed else Color.White.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            )
            Text(
                listOfNotNull(s.sportName(game.sport), game.league.takeIf { it.isNotEmpty() }).joinToString(" · "),
                color = TextSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (game.home.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Team(game.home, Modifier.weight(1f))
                Text("–", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Team(game.away, Modifier.weight(1f))
            }
        } else {
            Text(game.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (live) Text(s.sportsStarted(formatTime(game.startMs)), color = TextSoft, fontSize = 12.sp)
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(Color.White).clickable(onClick = onWatch),
            contentAlignment = Alignment.Center,
        ) {
            Text(s.sportsWatchOn(game.channels.first().name), color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun Team(name: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamBadge(name, 52)
        Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GameRow(game: SportsGame, now: Long, selected: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val live = game.isLive(now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Color.White.copy(alpha = 0.14f) else Raised).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (game.home.isNotEmpty()) {
            Box(Modifier.width(56.dp)) {
                TeamBadge(game.home, 32)
                Box(Modifier.padding(start = 22.dp)) { TeamBadge(game.away, 32) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(game.title, color = ItvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(s.sportName(game.sport), game.channels.first().name).joinToString(" · "),
                color = ItvColors.TextFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (live) s.sportsLive else whenText(game.startMs, now, s),
            color = if (live) LiveText else NextText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HubChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(CircleShape).background(if (selected) Color.White else PhoneColors.Tile).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) Color.Black else ItvColors.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
    )
}

@Composable
private fun HubLabel(text: String, color: Color = ItvColors.TextFaint) {
    Text(text.uppercase(), Modifier.padding(top = 4.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp)
}

@Composable
private fun HubNote(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 40.dp), color = ItvColors.TextDim, fontSize = 15.sp, textAlign = TextAlign.Center)
}

private val Raised = Color(0xFF1A1E2A)
private val TextSoft = Color(0xFFC9D1DD)
private val LiveRed = Color(0xFFEF4444)
private val LiveText = Color(0xFFFCA5A5)
private val NextText = Color(0xFF7DD3FC)
