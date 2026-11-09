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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxHeight
import cc.slokker.itv.ui.player.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
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
import java.util.Calendar
import cc.slokker.itv.data.at
import cc.slokker.itv.data.minutesLeft

/**
 * O ecrã inicial do telemóvel (a app já não abre a dar o último canal): os canais vistos há pouco em cartões
 * "Continuar" a deslizar, e os grupos numa grelha. Tocar num cartão abre o canal; num grupo, a lista dele.
 */
@Composable
internal fun MobileHome(
    vm: AppViewModel,
    data: PlaylistContent,
    last: Channel,
    onPlay: (Channel) -> Unit,
    onGroup: (String) -> Unit,
    onLockedGroup: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val s = LocalStrings.current
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recentKeys by vm.recents.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    // O último canal primeiro (mesmo que ainda não tenha entrado nos Recentes), depois os outros.
    val recents = remember(data, recentKeys, last) {
        (listOf(last) + vm.channelsOf(SpecialCategory.RECENT)).distinctBy { it.key }
            .filterNot { vm.isGroupLocked(it.categoryId) }
            .take(HOME_RECENTS)
    }
    val groups = remember(data, favorites, unlocked) { vm.guideGroups().filter { it.id != SpecialCategory.RECENT } }
    val locked = remember(data, unlocked) { vm.orderedGroups(includeHidden = false).filter { vm.isGroupLocked(it.id) } }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBarSpace.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(s.greeting(hour), color = ItvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(data.playlist.name, color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(TileFill).clickable(onClick = onSearch),
                    contentAlignment = Alignment.Center,
                ) { Icon(ItvIcons.Search, s.search, tint = ItvColors.TextDim, modifier = Modifier.size(20.dp)) }
            }
        }
        item {
            val pager = rememberPagerState { recents.size }
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(start = 18.dp, end = if (recents.size > 1) 42.dp else 18.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.padding(top = 14.dp),
            ) { page ->
                ContinueCard(vm, recents[page], first = page == 0) { onPlay(recents[page]) }
            }
            if (recents.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(recents.size) { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier.padding(horizontal = 3.dp).size(width = if (on) 18.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape).background(if (on) ItvColors.Text else ItvColors.TextFaint.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
        }
        item {
            Text(
                s.groups, color = ItvColors.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp),
            )
        }
        val tiles = groups.map { it.id to it.name.ifBlank { data.categoryName(it.id) } }.map { (id, name) -> GroupTileInfo(id, name, false) } +
            locked.map { GroupTileInfo(it.id, it.name, true) }
        items(tiles.chunked(2)) { pair ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { g ->
                    GroupTile(vm, g, Modifier.weight(1f)) { if (g.locked) onLockedGroup(g.id) else onGroup(g.id) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private class GroupTileInfo(val id: String, val name: String, val locked: Boolean)

/** Um canal visto há pouco: logótipo, o que está a dar, quanto falta e "Ver". */
@Composable
private fun ContinueCard(vm: AppViewModel, channel: Channel, first: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val current = epg.at(now)
    Box(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(HeroShape)
            .background(Brush.linearGradient(listOf(Color(0xFF1D2236), Color(0xFF0E1018))))
            .drawBehind {
                // Um brilho da cor de destaque no canto, para o cartão não ser um bloco liso.
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.start.copy(alpha = 0.28f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.2f),
                        radius = size.width * 0.55f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.78f, size.height * 0.2f),
                )
            }
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Box(
            Modifier.size(width = 84.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) { ChannelLogo(channel, 44.dp) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            Text(
                (if (first) "${s.homeContinue} · ${channel.name}" else channel.name).uppercase(),
                color = ItvColors.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                current?.title ?: channel.name, color = ItvColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(Color.White).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ItvIcons.Play, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.watch, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (current != null) {
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                            Box(Modifier.fillMaxWidth(current.progress(now)).height(3.dp).background(accent.brush))
                        }
                        val left = current.minutesLeft(now)
                        Text(
                            "${formatTime(current.startMs)} – ${formatTime(current.endMs)} · ${s.minutesLeft(left)}",
                            color = ItvColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Um grupo: até três logótipos dos canais dele, o nome e quantos canais tem. Os bloqueados pedem o PIN. */
@Composable
private fun GroupTile(vm: AppViewModel, group: GroupTileInfo, modifier: Modifier, onClick: () -> Unit) {
    val s = LocalStrings.current
    val channels = remember(group.id, group.locked) { if (group.locked) emptyList() else vm.channelsOf(group.id) }
    Column(modifier.clip(TileShape).background(TileFill).clickable(onClick = onClick).padding(14.dp)) {
        Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            channels.take(3).forEach { ch ->
                Box(Modifier.size(width = 34.dp, height = 24.dp).clip(RoundedCornerShape(6.dp)).background(TileFill), contentAlignment = Alignment.Center) {
                    ChannelLogo(ch, 20.dp)
                }
            }
        }
        Text(
            if (group.locked) "🔒 ${group.name}" else group.name,
            color = if (group.locked) ItvColors.TextFaint else ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
        )
        Text(if (group.locked) " " else s.groupChannels(channels.size), color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * O canal a dar fora do ecrã do canal: uma barra por cima dos separadores com o vídeo pequeno, o programa, pausa e
 * fechar. Tocar nela volta ao canal em grande.
 */
@Composable
internal fun MiniPlayer(vm: AppViewModel, channel: Channel, playback: MobilePlayback, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val current = remember(epg, now, channel.key) { vm.programmes(channel, epg).at(now) }
    Box(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(16.dp, MiniShape)
            .clip(MiniShape)
            .background(Color(0xFF1A1E2A))
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 84.dp, height = 47.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                AndroidView(
                    factory = { VideoView(it).also { v -> playback.view = v } },
                    update = { playback.view = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = ItvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = current?.let { "${it.title} · ${s.minutesLeft(it.minutesLeft(now))}" }
                if (sub != null) Text(sub, color = ItvColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                if (playback.playing) ItvIcons.Pause else ItvIcons.Play, null, tint = ItvColors.Text,
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .clickable { if (playback.playing) playback.player.pause() else playback.player.play() }
                    .padding(10.dp),
            )
            Icon(ItvIcons.Close, null, tint = ItvColors.TextDim, modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose).padding(11.dp))
        }
        if (current != null) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(current.progress(now)).fillMaxHeight().background(accent.brush))
            }
        }
    }
}

private val MiniShape = RoundedCornerShape(16.dp)
private val HeroShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(16.dp)
private val TileFill = Color.White.copy(alpha = 0.06f)
private const val HOME_RECENTS = 6
