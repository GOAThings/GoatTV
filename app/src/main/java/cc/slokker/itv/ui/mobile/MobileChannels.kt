package cc.slokker.itv.ui.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Channel
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
import cc.slokker.itv.ui.theme.tabular
import androidx.tv.material3.LocalTextStyle
import cc.slokker.itv.data.at

@Composable
internal fun ChannelsTab(vm: AppViewModel, current: Channel, guide: Boolean = false, onEpg: (Channel) -> Unit) {
    val selected by vm.selectedCategory.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val groups = remember(content, favorites) { vm.guideGroups() }
    var group by rememberSaveable { mutableStateOf(selected) }
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val locked = remember(content, unlocked) { vm.orderedGroups(includeHidden = false).filter { vm.isGroupLocked(it.id) } }
    var pinFor by remember { mutableStateOf<String?>(null) }
    // O grupo pode não estar à vista (ex.: "Todos" escondido): fica o do canal a dar, ou o primeiro.
    val shown = groups.firstOrNull { it.id == group }?.id
        ?: groups.firstOrNull { it.id == current.categoryId }?.id
        ?: groups.firstOrNull()?.id
        ?: SpecialCategory.ALL
    val channels = remember(content, favorites, shown) { vm.channelsOf(shown) }
    Column(Modifier.fillMaxSize()) {
        // O grupo em uso fica sempre à vista, mesmo com muitos grupos antes dele.
        val pills = rememberLazyListState()
        LaunchedEffect(shown, groups) { groups.indexOfFirst { it.id == shown }.takeIf { it >= 0 }?.let { pills.animateScrollToItem(it) } }
        val accent = LocalAccent.current.start
        LazyRow(
            state = pills,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        ) {
            items(groups, key = { it.id }) { g ->
                val on = g.id == shown
                Text(
                    g.name,
                    color = if (on) accent else ItvColors.TextFaint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(PhoneShapes.List)
                        .background(if (on) accent.copy(alpha = PhoneColors.SELECTED_ALPHA) else Color.Transparent)
                        .clickable { group = g.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(locked, key = { it.id }) { g ->
                Text(
                    "🔒 ${g.name}", color = ItvColors.TextFaint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.clip(PhoneShapes.List).clickable { pinFor = g.id }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (guide) GuideList(vm, channels, shown, current, onEpg) else ChannelList(vm, channels, shown, current, onEpg)
    }
    pinFor?.let { id ->
        PinDialog(LocalStrings.current.enterPin, onClose = { pinFor = null }, rejected = vm::pinRejected) { pin ->
            vm.unlockGroup(id, pin).also { if (it) { group = id; pinFor = null } }
        }
    }
}

@Composable
internal fun ChannelList(vm: AppViewModel, channels: List<Channel>, groupId: String, current: Channel, onEpg: (Channel) -> Unit) {
    val s = LocalStrings.current
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(s.noChannelsInGroup, color = ItvColors.TextFaint, fontSize = 15.sp) }
        return
    }
    val list = rememberLazyListState()
    val played = LocalOnPlayed.current
    LazyColumn(state = list, contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = LocalBarSpace.current)) {
        items(channels, key = { it.uid }) { channel ->
            ChannelCard(vm, channel, channel.key == current.key, onClick = { vm.play(channel, groupId); played() }, onLong = { onEpg(channel) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelCard(vm: AppViewModel, channel: Channel, playing: Boolean, found: EpgEntry? = null, onClick: () -> Unit, onLong: () -> Unit) {
    val accent = LocalAccent.current.start
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val now = rememberNow()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val programme = found ?: epg.at(now)?.takeIf { prefs.epgInList }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PhoneShapes.List)
            .background(if (playing) accent.copy(alpha = PhoneColors.SELECTED_ALPHA) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefs.showLogos) {
            Box(Modifier.size(56.dp, 38.dp), contentAlignment = Alignment.Center) { ChannelLogo(channel, 38.dp) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, color = if (playing) accent else ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (prefs.showNumbers) {
                    Spacer(Modifier.width(8.dp))
                    Text("${channel.num}", color = ItvColors.TextFaint.copy(alpha = 0.36f), fontSize = 13.sp)
                }
            }
            if (programme != null) {
                Text(if (found == null) programme.title else "${formatTime(programme.startMs)}  ${programme.title}", color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (programme.startMs <= now) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(PhoneColors.ProgressTrack)) {
                        Box(Modifier.fillMaxWidth(programme.progress(now)).fillMaxHeight().background(if (playing) accent else PhoneColors.ProgressDim))
                    }
                }
            }
        }
    }
}

/** O guia de um canal (manter o dedo no cartão). */
@Composable
internal fun EpgSheet(vm: AppViewModel, channel: Channel, onPick: (logo: Boolean) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val played = LocalOnPlayed.current
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val now = rememberNow()
    val entries = remember(epg, channel.key) { vm.programmes(channel, epg) }
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    // Abre no programa que está a dar; o passado fica acima (para o catch-up).
    LaunchedEffect(entries) { entries.indexOfFirst { it.endMs > now }.takeIf { it > 0 }?.let { list.scrollToItem(it) } }
    Box(Modifier.fillMaxSize().background(PhoneColors.Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.75f)
                .clip(PhoneShapes.Sheet).background(PhoneColors.Sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ControlButton(if (vm.isFavorite(channel)) ItvIcons.Star else ItvIcons.StarOutline) { vm.toggleFavorite(channel) }
                ControlButton(ItvIcons.Close, onClick = onClose)
            }
            if (vm.epgPickerAvailable) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to s.epgGuideChannel, true to s.channelLogo).forEach { (logo, title) ->
                        Box(
                            Modifier.weight(1f).clip(PhoneShapes.List).background(PhoneColors.Card).clickable { onPick(logo) }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(title, color = ItvColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            if (entries.isEmpty()) Text(s.noGuide, color = ItvColors.TextFaint, fontSize = 15.sp)
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.startMs }) { e ->
                    reminders.size
                    val catchup = vm.canCatchup(channel, e)
                    val future = e.startMs > now
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = catchup || future) {
                            if (catchup) {
                                vm.playCatchup(channel, e)
                                played()
                                onClose()
                            } else {
                                vm.toggleReminder(channel, e)
                            }
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(formatTime(e.startMs), color = ItvColors.TextFaint, fontSize = 14.sp, modifier = Modifier.width(60.dp), style = LocalTextStyle.current.tabular)
                        Text(e.title, color = if (e.endMs <= now) ItvColors.TextFaint else if (future) ItvColors.TextDim else ItvColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        when {
                            catchup -> Icon(ItvIcons.Replay, s.watchCatchup, tint = ItvColors.TextDim, modifier = Modifier.size(18.dp))
                            future && vm.hasReminder(channel, e) -> Icon(ItvIcons.Bell, s.removeReminder, tint = LocalAccent.current.start, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
