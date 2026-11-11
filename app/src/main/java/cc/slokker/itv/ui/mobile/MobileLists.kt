package cc.slokker.itv.ui.mobile

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberEpg
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.common.dayLabel
import cc.slokker.itv.data.at
import cc.slokker.itv.data.isOn

/**
 * Procurar: antes de escrever, as pesquisas recentes, o que está a dar nos canais vistos há pouco e os grupos;
 * com texto, os resultados separados em canais, a dar agora (Ver), mais tarde (lembrete) e já passou (catch-up).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneSearch(vm: AppViewModel, current: Channel, onEpg: (Channel) -> Unit, onGroup: (String) -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val played = LocalOnPlayed.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(FILTER_ALL) }
    var recentQueries by remember { mutableStateOf(loadRecentSearches(context)) }
    fun rememberQuery(q: String) {
        recentQueries = saveRecentSearch(context, q.trim())
    }
    val typed = query.trim().length >= 2
    val rows = rememberRowState(vm)
    Column(Modifier.fillMaxSize()) {
        Text(s.search, color = ItvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp))
        Field(s.searchHint, query, Modifier.padding(horizontal = 12.dp)) { query = it }
        if (typed) {
            Row(Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FILTER_ALL to s.all, FILTER_CHANNELS to s.searchChannels, FILTER_PROGRAMMES to s.searchProgrammes).forEach { (id, label) ->
                    Pill(label, on = filter == id) { filter = id }
                }
            }
        }
        val now = rememberNow()
        val hits = remember(query) { if (typed) vm.search(query, withPast = true) else emptyList() }
        LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = LocalBarSpace.current)) {
            if (!typed) {
                if (recentQueries.isNotEmpty()) {
                    caption(s.searchRecent)
                    item {
                        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recentQueries.forEach { q ->
                                Row(
                                    Modifier.clip(CircleShape).background(PhoneColors.Card).clickable { query = q }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(ItvIcons.History, null, tint = ItvColors.TextFaint, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(q, color = ItvColors.Text, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                val recents = vm.channelsOf(SpecialCategory.RECENT).take(3)
                if (recents.isNotEmpty()) {
                    caption(s.searchNow)
                    items(recents, key = { "r" + it.uid }) { ch ->
                        ChannelCard(vm, ch, false, rows, onClick = { vm.play(ch, vm.groupFor(ch)); played() }, onLong = { onEpg(ch) })
                    }
                }
                caption(s.groups)
                item {
                    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.guideGroups().filter { it.id != SpecialCategory.RECENT }.forEach { g ->
                            Text(
                                g.name, color = ItvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(PhoneShapes.List).background(PhoneColors.Card).clickable { onGroup(g.id) }.padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                return@LazyColumn
            }
            if (hits.isEmpty()) {
                item { Note(s.searchNoResults) }
                return@LazyColumn
            }
            val channels = hits.filter { it.programme == null }
            val live = hits.filter { h -> h.programme?.isOn(now) == true }
            val later = hits.filter { h -> h.programme?.let { it.startMs > now } == true }
            val past = hits.filter { h -> h.programme?.let { it.endMs <= now } == true }
            if (filter != FILTER_PROGRAMMES && channels.isNotEmpty()) {
                caption(s.searchChannels)
                items(channels, key = { "c" + it.channel.uid }) { h ->
                    ChannelCard(vm, h.channel, h.channel.key == current.key, rows, onClick = { rememberQuery(query); vm.play(h.channel, vm.groupFor(h.channel)); played() }, onLong = { onEpg(h.channel) })
                }
            }
            if (filter != FILTER_CHANNELS) {
                if (live.isNotEmpty()) {
                    caption(s.searchNow)
                    items(live, key = { "n" + it.channel.uid + it.programme!!.startMs }) { h ->
                        val e = h.programme!!
                        ResultRow(h.channel, e.title, "${h.channel.name} · ${formatTime(e.startMs)} – ${formatTime(e.endMs)}", e.progress(now), rows.showLogos, action = s.watch, onLong = { onEpg(h.channel) }) {
                            rememberQuery(query)
                            vm.play(h.channel, vm.groupFor(h.channel))
                            played()
                        }
                    }
                }
                if (later.isNotEmpty()) {
                    caption(s.searchLater)
                    items(later, key = { "l" + it.channel.uid + it.programme!!.startMs }) { h ->
                        val e = h.programme!!
                        ResultRow(h.channel, e.title, "${dayLabel(s, e.startMs)} ${formatTime(e.startMs)} · ${h.channel.name}", null, rows.showLogos, bell = rows.reminded(h.channel, e), onLong = { onEpg(h.channel) }) {
                            rememberQuery(query)
                            vm.toggleReminder(h.channel, e)
                        }
                    }
                }
                if (past.isNotEmpty()) {
                    caption(s.searchPast)
                    items(past, key = { "p" + it.channel.uid + it.programme!!.startMs }) { h ->
                        val e = h.programme!!
                        ResultRow(h.channel, e.title, "${dayLabel(s, e.startMs)} ${formatTime(e.startMs)} · ${h.channel.name}", null, rows.showLogos, action = s.watchAgain, onLong = { onEpg(h.channel) }) {
                            rememberQuery(query)
                            vm.playCatchup(h.channel, e)
                            played()
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.caption(text: String) {
    item {
        Text(
            text.uppercase(), color = ItvColors.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else ItvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (on) Color.White else PhoneColors.Card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Um programa encontrado: logótipo, título, quando e onde, e o que o toque faz (Ver, lembrete, rever). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(channel: Channel, title: String, sub: String, progress: Float?, showLogo: Boolean, action: String? = null, bell: Boolean? = null, onLong: () -> Unit, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(PhoneShapes.List).combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLogo) {
            Box(Modifier.size(56.dp, 38.dp), contentAlignment = Alignment.Center) { ChannelLogo(channel, 38.dp) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) {
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(PhoneColors.ProgressTrack)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(PhoneColors.ProgressDim))
                }
            }
        }
        if (action != null) Text(action, color = accent.start, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
        if (bell != null) Icon(ItvIcons.Bell, null, tint = if (bell) accent.start else ItvColors.TextFaint, modifier = Modifier.padding(start = 10.dp).size(20.dp))
    }
}

/**
 * Favoritos em cartões (logótipo, o que está a dar e quanto já passou), com "Ordenar" para mudar a ordem com ↑ ↓.
 * Sem favoritos (ou no fim), uma nota a dizer como juntar mais.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneFavorites(vm: AppViewModel, current: Channel, onEpg: (Channel) -> Unit) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val played = LocalOnPlayed.current
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val channels = remember(favorites, content, prefs.sort) { vm.channelsOf(SpecialCategory.FAVORITES) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(s.favorites, color = ItvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(s.groupChannels(channels.size), color = ItvColors.TextFaint, fontSize = 13.sp)
            }
            if (channels.size > 1) {
                Text(
                    if (reordering) s.done else s.favoritesReorder, color = accent.start, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { reordering = !reordering }.padding(6.dp),
                )
            }
        }
        if (reordering) {
            LazyColumn(contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = LocalBarSpace.current)) {
                items(channels, key = { it.uid }) { ch ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (prefs.showLogos) {
                            Box(Modifier.size(56.dp, 38.dp), contentAlignment = Alignment.Center) { ChannelLogo(ch, 38.dp) }
                            Spacer(Modifier.width(14.dp))
                        }
                        Text(ch.name, color = ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        listOf(-1 to -90f, 1 to 90f).forEach { (delta, angle) ->
                            Icon(
                                ItvIcons.Chevron, null, tint = ItvColors.TextDim,
                                modifier = Modifier.size(44.dp).clip(CircleShape).clickable { vm.moveFavorite(ch, delta) }.padding(10.dp).rotate(angle),
                            )
                        }
                    }
                }
            }
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = LocalBarSpace.current),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(channels, key = { it.uid }) { ch ->
                FavoriteCard(vm, ch, playing = ch.key == current.key, showLogo = prefs.showLogos, onLong = { onEpg(ch) }) {
                    vm.play(ch, SpecialCategory.FAVORITES)
                    played()
                }
            }
            item(span = { GridItemSpan(2) }) {
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ItvIcons.StarOutline, null, tint = ItvColors.TextDim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s.favoritesHint, color = ItvColors.TextFaint, fontSize = 14.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(vm: AppViewModel, channel: Channel, playing: Boolean, showLogo: Boolean, onLong: () -> Unit, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val entry: EpgEntry? = epg.at(now)
    Column(Modifier.clip(PhoneShapes.Tile).background(PhoneColors.Tile).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box(
            Modifier.fillMaxWidth().height(92.dp).background(
                Brush.radialGradient(listOf(accent.start.copy(alpha = if (playing) 0.35f else 0.16f), Color.Transparent), center = Offset(80f, 40f), radius = 380f),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { ChannelLogo(channel, 38.dp, initials = !showLogo) }
            Icon(ItvIcons.Star, null, tint = PhoneColors.FavoriteGold, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(16.dp))
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Text(channel.name, color = if (playing) accent.start else ItvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry?.title ?: " ", color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(PhoneColors.ProgressTrack)) {
                if (entry != null) Box(Modifier.fillMaxWidth(entry.progress(now)).fillMaxHeight().background(accent.brush))
            }
        }
    }
}

/** As últimas pesquisas (só neste aparelho), a mais recente primeiro. */
private fun loadRecentSearches(context: Context): List<String> =
    context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE).getString(SEARCH_KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

private fun saveRecentSearch(context: Context, query: String): List<String> {
    val list = (listOf(query) + loadRecentSearches(context).filterNot { it.equals(query, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
    context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE).edit().putString(SEARCH_KEY, list.joinToString("\n")).apply()
    return list
}

private const val FILTER_ALL = 0
private const val FILTER_CHANNELS = 1
private const val FILTER_PROGRAMMES = 2
private const val SEARCH_PREFS = "mobile_search"
private const val SEARCH_KEY = "recent"
private const val MAX_RECENT_SEARCHES = 6
