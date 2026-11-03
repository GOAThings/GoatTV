package cc.slokker.itv.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.RefreshPolicy
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.ReportState
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.Logo
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberEpg
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.i18n.maskEmail
import cc.slokker.itv.ui.theme.Accent
import cc.slokker.itv.ui.player.VideoView
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.ui.common.LogoImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Card = Color.White.copy(alpha = 0.08f)
private val CardShape = RoundedCornerShape(18.dp)
private enum class Tab { Tv, Favorites, Search, Menu }

/** Primeira vez no telemóvel: conta GOAT ou listas só neste aparelho. */
@Composable
fun MobileWelcome(onAccount: () -> Unit, onLocal: () -> Unit) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Logo(size = 28)
        Text(s.welcomeTitle, color = ItvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(s.welcomeSub, color = ItvColors.TextDim, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        ChoiceCard(s.welcomeAccount, s.welcomeAccountSub, s.recommended, onAccount)
        ChoiceCard(s.welcomeLocalPhone, s.welcomeLocalPhoneSub, null, onLocal)
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, badge: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Card).clickable(onClick = onClick).padding(18.dp)) {
        if (badge != null) {
            Text(
                badge.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(LocalAccent.current.brush, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(title, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = ItvColors.TextDim, fontSize = 14.sp)
    }
}

/** O ecrã principal do telemóvel: abre logo a dar o último canal, com a lista por baixo. */
@Composable
fun MobileMain(vm: AppViewModel) {
    val channel by vm.playing.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val streamFormat by vm.streamFormat.collectAsStateWithLifecycle()
    val current = channel ?: return
    val data = content ?: return
    var tab by rememberSaveable { mutableStateOf(Tab.Tv) }
    val settings = tab == Tab.Menu
    val playback = rememberMobilePlayback(vm, current, data, prefs, streamFormat, active = !settings)
    val landscape = !settings && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = LocalContext.current as? Activity
    // Trocar de lista nas Definições abre outro canal: volta à TV para o ver.
    var shownKey by remember { mutableStateOf(current.key) }
    if (shownKey != current.key) {
        shownKey = current.key
        if (settings) tab = Tab.Tv
    }
    var epgFor by remember { mutableStateOf<Channel?>(null) }
    var epgPick by remember { mutableStateOf<Pair<Channel, Boolean>?>(null) }

    FullscreenBars(landscape)
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    BackHandler(landscape || epgFor != null || tab != Tab.Tv) {
        when {
            landscape -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            epgFor != null -> epgFor = null
            else -> tab = Tab.Tv
        }
    }

    Column(Modifier.fillMaxSize().background(ItvColors.Background)) {
        if (settings) {
            Spacer(Modifier.statusBarsPadding())
        } else {
            Box(
                if (landscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f),
            ) {
                VideoArea(vm, current, playback, landscape, onFullscreen = {
                    activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                })
            }
        }
        if (!landscape) {
            Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                when (tab) {
                    Tab.Tv -> ChannelsTab(vm, current) { epgFor = it }
                    Tab.Favorites -> ChannelList(vm, vm.channelsOf(SpecialCategory.FAVORITES), SpecialCategory.FAVORITES, current) { epgFor = it }
                    Tab.Search -> SearchTab(vm, current) { epgFor = it }
                    Tab.Menu -> MobileMenu(vm)
                }
                // A barra flutua por cima da lista, com o vidro do resto da GoatTV.
                BottomBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
                epgFor?.let { channel ->
                    EpgSheet(vm, channel, onPick = { logo -> epgPick = channel to logo }) { epgFor = null }
                }
                epgPick?.let { (channel, logo) ->
                    EpgPickPage(vm, channel, logo) {
                        epgPick = null
                        epgFor = null
                    }
                }
            }
        }
    }
    var reminder by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (reminder == null) reminder = vm.dueReminder(System.currentTimeMillis())
            delay(15_000)
        }
    }
    reminder?.let { r ->
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(CardShape).background(Color(0xFF20232E)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = ItvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.channelName, color = ItvColors.TextDim, fontSize = 13.sp)
                }
                ControlButton(ItvIcons.Play) { vm.consumeReminder(r, watch = true); reminder = null }
                ControlButton(ItvIcons.Close) { vm.consumeReminder(r, watch = false); reminder = null }
            }
        }
    }
}

/** Em paisagem o vídeo ocupa o ecrã todo: escondem-se as barras do sistema. */
@Composable
private fun FullscreenBars(fullscreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val bars = WindowInsetsControllerCompat(window, view)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

@Composable
private fun VideoArea(vm: AppViewModel, channel: Channel, playback: MobilePlayback, fullscreen: Boolean, onFullscreen: () -> Unit) {
    val s = LocalStrings.current
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableStateOf(0) }
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    LaunchedEffect(controls, touched, channel.key) {
        if (controls) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controls = !controls; touched++ } }
            // Deslizar na vertical muda de canal.
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (kotlin.math.abs(drag) > ZAP_DRAG_PX) vm.zap(if (drag < 0) 1 else -1) },
                ) { _, amount -> drag += amount }
            },
    ) {
        AndroidView(
            factory = { VideoView(it).also { v -> playback.view = v } },
            update = { playback.view = it },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            playback.failed -> Text(s.cantOpenChannel, color = ItvColors.Text, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            !playback.ready -> Text(channel.name, color = ItvColors.TextDim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (controls) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(channel.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ControlButton(if (vm.isFavorite(channel)) ItvIcons.Star else ItvIcons.StarOutline) { vm.toggleFavorite(channel); touched++ }
            }
            ControlButton(if (playback.playing) ItvIcons.Pause else ItvIcons.Play, Modifier.align(Alignment.Center).size(56.dp)) {
                if (playback.playing) playback.player.pause() else playback.player.play()
                touched++
            }
            catchup?.let { cu ->
                Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun position() = playback.archiveStart - cu.startMs + playback.player.currentPosition
                    SmallAction("−5") { vm.seekCatchup(position() - SEEK_MS); touched++ }
                    SmallAction("+5") { vm.seekCatchup(position() + SEEK_MS); touched++ }
                    SmallAction(s.backToLive) { vm.exitCatchup() }
                }
            }
            ControlButton(ItvIcons.Fullscreen, Modifier.align(Alignment.BottomEnd).padding(6.dp), onFullscreen)
            if (fullscreen && catchup == null) Text(formatTime(System.currentTimeMillis()), color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ControlButton(icon: ImageVector, modifier: Modifier = Modifier.size(44.dp), onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun ChannelsTab(vm: AppViewModel, current: Channel, onEpg: (Channel) -> Unit) {
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
        LazyRow(
            state = pills,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(groups, key = { it.id }) { g ->
                val on = g.id == shown
                Text(
                    g.name,
                    color = if (on) Color.Black else ItvColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (on) Color.White else Card)
                        .clickable { group = g.id }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            items(locked, key = { it.id }) { g ->
                Text(
                    "🔒 ${g.name}", color = ItvColors.TextDim, fontSize = 14.sp, maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(Card).clickable { pinFor = g.id }.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        ChannelList(vm, channels, shown, current, onEpg)
    }
    pinFor?.let { id ->
        PinDialog(LocalStrings.current.enterPin, onClose = { pinFor = null }) { pin ->
            vm.unlockGroup(id, pin).also { if (it) { group = id; pinFor = null } }
        }
    }
}

@Composable
private fun ChannelList(vm: AppViewModel, channels: List<Channel>, groupId: String, current: Channel, onEpg: (Channel) -> Unit) {
    val s = LocalStrings.current
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(s.noChannelsInGroup, color = ItvColors.TextFaint, fontSize = 15.sp) }
        return
    }
    val list = rememberLazyListState()
    LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = BAR_SPACE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(channels, key = { it.uid }) { channel ->
            ChannelCard(vm, channel, channel.key == current.key, onClick = { vm.play(channel, groupId) }, onLong = { onEpg(channel) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCard(vm: AppViewModel, channel: Channel, playing: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val accent = LocalAccent.current
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val now = rememberNow()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val programme = epg.firstOrNull { it.startMs <= now && it.endMs > now }?.takeIf { prefs.epgInList }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (playing) Color.White.copy(alpha = 0.16f) else Card)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefs.showLogos) {
            Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) { ChannelLogo(channel, 48.dp) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(if (prefs.showNumbers) "${channel.num}  ${channel.name}" else channel.name, color = ItvColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (programme != null) {
                Text(programme.title, color = ItvColors.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(programme.progress(now)).fillMaxHeight().background(accent.brush))
                }
            }
        }
    }
}

/** O guia de um canal (manter o dedo no cartão). */
@Composable
private fun EpgSheet(vm: AppViewModel, channel: Channel, onPick: (logo: Boolean) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val now = rememberNow()
    val entries = remember(epg, channel.key) { vm.guideEntries(channel).ifEmpty { epg } }
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    // Abre no programa que está a dar; o passado fica acima (para o catch-up).
    LaunchedEffect(entries) { entries.indexOfFirst { it.endMs > now }.takeIf { it > 0 }?.let { list.scrollToItem(it) } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.75f)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Color(0xFF14161E))
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
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Card).clickable { onPick(logo) }.padding(vertical = 10.dp),
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
                                onClose()
                            } else {
                                vm.toggleReminder(channel, e)
                            }
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(formatTime(e.startMs), color = ItvColors.TextFaint, fontSize = 14.sp, modifier = Modifier.width(60.dp))
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

@Composable
private fun SearchTab(vm: AppViewModel, current: Channel, onEpg: (Channel) -> Unit) {
    val s = LocalStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query) { vm.search(query) }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Field(s.searchHint, query, Modifier.padding(horizontal = 12.dp)) { query = it }
        Spacer(Modifier.height(10.dp))
        when {
            query.trim().length < 2 -> Note(s.searchMinChars)
            hits.isEmpty() -> Note(s.searchNoResults)
            else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = BAR_SPACE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits) { hit ->
                    ChannelCard(vm, hit.channel, hit.channel.key == current.key, onClick = { vm.play(hit.channel, vm.groupFor(hit.channel)) }, onLong = { onEpg(hit.channel) })
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, color = ItvColors.TextFaint, fontSize = 15.sp, modifier = Modifier.padding(16.dp))

@Composable
private fun Field(hint: String, value: String, modifier: Modifier = Modifier, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp)) {
        if (value.isEmpty()) Text(hint, color = ItvColors.TextFaint, fontSize = 16.sp)
        BasicTextField(
            value, onChange,
            singleLine = true,
            textStyle = TextStyle(color = ItvColors.Text, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BottomBar(tab: Tab, modifier: Modifier = Modifier, onTab: (Tab) -> Unit) {
    val s = LocalStrings.current
    Row(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .glass(CircleShape)
            .padding(5.dp),
    ) {
        BarItem("TV", ItvIcons.Guide, tab == Tab.Tv) { onTab(Tab.Tv) }
        BarItem(s.favorites, ItvIcons.Star, tab == Tab.Favorites) { onTab(Tab.Favorites) }
        BarItem(s.search, ItvIcons.Search, tab == Tab.Search) { onTab(Tab.Search) }
        BarItem(s.settings, ItvIcons.Settings, tab == Tab.Menu) { onTab(Tab.Menu) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val tint = if (on) LocalAccent.current.start else ItvColors.TextDim
    Column(
        Modifier.weight(1f).clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Definições do telemóvel: o essencial, em lista agrupada. */
@Composable
fun MobileMenu(vm: AppViewModel) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val email by vm.email.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val needPin = vm.settingsNeedPin()
    var unlockedMenu by remember { mutableStateOf(!needPin) }
    if (!unlockedMenu) {
        return PinDialog(s.enterPin, onClose = null) { pin -> vm.unlockSettings(pin).also { if (it) unlockedMenu = true } }
    }
    var choosingPin by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var managingGroups by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val format by vm.streamFormat.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    if (adding) return PlaylistFormPage(vm) { adding = false }
    if (choosingPin) {
        return PinDialog(s.choosePin, onClose = { choosingPin = false }) { pin ->
            vm.setPin(pin)
            choosingPin = false
            true
        }
    }
    picking?.let { p -> return PickPage(p) { picking = null } }
    if (managingGroups) return GroupsPage(vm) { managingGroups = false }
    var pickingCountry by remember { mutableStateOf<Boolean?>(null) }
    var confirmingEpgReset by remember { mutableStateOf(false) }
    val epgMappings by vm.epgMappings.collectAsStateWithLifecycle()
    pickingCountry?.let { logo -> return EpgCountriesPage(vm, logo) { pickingCountry = null } }
    if (editingAgent) {
        return UserAgentPage(prefs.userAgent, onSave = { vm.setPref(Prefs.Key.USER_AGENT, it.trim().ifEmpty { null }) }) { editingAgent = false }
    }
    val open: (Picking) -> Unit = { picking = it }
    @Composable
    fun prefRow(key: String, title: String, options: List<Pair<Any?, String>>, value: Any?) = PickRow(title, options, value, open) { vm.setPref(key, it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = BAR_SPACE), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section(s.playlists) {
            playlists.forEach { p ->
                MenuRow(p.name, if (p.id == content?.playlist?.id) "✓" else null) { vm.switchPlaylist(p) }
            }
            if (local) MenuRow(s.addPlaylist, null) { adding = true }
        }
        Section(s.playback) {
            PickRow(s.streamFormat, listOf(null to s.auto, StreamFormat.TS to "TS (MPEG-TS)", StreamFormat.HLS to "HLS (m3u8)"), format, open) { vm.setStreamFormat(it as String?) }
            prefRow(Prefs.Key.BUFFER, s.buffer, listOf(Prefs.Buffer.LIVE to s.bufferLive, Prefs.Buffer.SMALL to s.bufferSmall, Prefs.Buffer.NORMAL to s.bufferNormal, Prefs.Buffer.LARGE to s.bufferLarge), prefs.buffer)
            prefRow(Prefs.Key.ENGINE, s.engine, listOf(Prefs.Engine.EXO to s.engineExo, Prefs.Engine.VLC to s.engineVlc), prefs.engine)
            prefRow(Prefs.Key.DECODER, s.decoder, listOf(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw), prefs.decoder)
            prefRow(Prefs.Key.AUDIO_DECODER, s.audioDecoder, listOf(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw), prefs.audioDecoder)
            prefRow(Prefs.Key.ASPECT, s.aspect, listOf(Prefs.Aspect.FIT to s.aspectFit, Prefs.Aspect.ZOOM to s.aspectZoom, Prefs.Aspect.FILL to s.aspectFill), prefs.aspect)
            prefRow(Prefs.Key.RECONNECT_SECONDS, s.reconnect, listOf(0 to s.no) + listOf(2, 5, 10, 30).map { it to s.afterSeconds(it) }, prefs.reconnectSeconds)
            MenuRow(s.userAgent, prefs.userAgent.ifBlank { s.userAgentDefault }) { editingAgent = true }
        }
        Section(s.manageChannels) {
            prefRow(Prefs.Key.STARTUP, s.onStartup, listOf(Prefs.Startup.LAST to s.startupLast, Prefs.Startup.LIST to s.startupList), prefs.startup)
            prefRow(Prefs.Key.SORT, s.sortChannels, listOf(Prefs.Sort.NUMBER to s.sortNumber, Prefs.Sort.NAME to s.sortName), prefs.sort)
            ToggleRow(s.channelNumbers, prefs.showNumbers) { vm.setPref(Prefs.Key.SHOW_NUMBERS, it) }
            ToggleRow(s.channelLogos, prefs.showLogos) { vm.setPref(Prefs.Key.SHOW_LOGOS, it) }
            ToggleRow(s.nowInList, prefs.epgInList) { vm.setPref(Prefs.Key.EPG_IN_LIST, it) }
            if (vm.epgPickerAvailable) MenuRow(s.logoSource, null) { pickingCountry = true }
            MenuRow(s.visibleGroups, null) { managingGroups = true }
        }
        Section(s.settings) {
            prefRow(Prefs.Key.LANGUAGE, s.language, listOf("auto" to s.languageAuto) + Strings.LANGUAGES, prefs.language)
            prefRow(Prefs.Key.ACCENT, s.accent, Accent.ALL.map { it.id to s.accentName(it.id) }, prefs.accent)
        }
        Section(s.guide) {
            prefRow(Prefs.Key.EPG_REFRESH, s.epgRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.epgRefreshHours)
            prefRow(Prefs.Key.PLAYLIST_REFRESH, s.playlistRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.playlistRefreshHours)
            prefRow(Prefs.Key.EPG_OFFSET, s.timeOffset, (-180..180 step 30).map { m -> m to (if (m == 0) "0" else "%+d min".format(m)) }, prefs.epgOffsetMin)
            if (vm.epgPickerAvailable) MenuRow(s.epgCountryGuide, null) { pickingCountry = false }
            if (vm.epgPickerAvailable && epgMappings.isNotEmpty()) {
                MenuRow(if (confirmingEpgReset) s.pressAgainToConfirm else s.epgResetAll, null) {
                    if (confirmingEpgReset) vm.resetEpgMappings()
                    confirmingEpgReset = !confirmingEpgReset
                }
            }
            MenuRow(s.refreshEpg, null, vm::refreshEpg)
        }
        Section(s.parental) {
            if (prefs.pinHash == null) {
                MenuRow(s.setPin, null) { choosingPin = true }
            } else {
                ToggleRow(s.protectSettings, prefs.settingsPin) { vm.setPref(Prefs.Key.SETTINGS_PIN, it) }
                MenuRow(s.changePin, null) { choosingPin = true }
                MenuRow(s.parentalOff, null) { vm.setPin(null) }
                vm.orderedGroups(includeHidden = true).forEach { g ->
                    ToggleRow("🔒 " + g.name, vm.isGroupLockedSetting(g.id)) { vm.toggleGroupLock(g.id) }
                }
            }
        }
        Section(s.updates) {
            when (val u = update) {
                is UpdateState.Available -> MenuRow(u.release.version, null, vm::installUpdate)
                is UpdateState.Downloading -> MenuRow(u.release.version, "${(u.progress * 100).toInt()}%") { }
                is UpdateState.Installing -> MenuRow(s.installing, null) { }
                UpdateState.UpToDate -> MenuRow(s.upToDate, BuildConfig.VERSION_NAME) { vm.checkForUpdate(true) }
                else -> MenuRow(s.checkUpdates, BuildConfig.VERSION_NAME) { vm.checkForUpdate(true) }
            }
        }
        Section(s.account) {
            email?.let { MenuRow(maskEmail(it), null) { } }
            MenuRow(s.sendReport, when (val r = report) { ReportState.Sending -> "…"; is ReportState.Sent -> r.id; else -> null }) { reporting = !reporting }
            if (reporting) {
                Field(s.sendReportSub, message, Modifier.padding(horizontal = 12.dp)) { message = it }
                MenuRow(s.sendReport, "→") { vm.sendReport(message); reporting = false; message = "" }
            }
            if (!local) MenuRow(s.logout, null, vm::logoutThisTv)
        }
    }
}

/** Quatro dígitos com o teclado do telefone. [check] devolve true quando o PIN serve. */
@Composable
private fun PinDialog(title: String, onClose: (() -> Unit)?, check: (String) -> Boolean) {
    val s = LocalStrings.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(CardShape).background(Color(0xFF14161E)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Field(s.pinHint, pin, secret = true, keyboard = KeyboardType.NumberPassword) { v ->
                pin = v.filter(Char::isDigit).take(4)
                wrong = false
                if (pin.length == 4 && !check(pin)) {
                    wrong = true
                    pin = ""
                }
            }
            if (wrong) Text(s.wrongPin, color = ItvColors.Error, fontSize = 14.sp)
            if (onClose != null) ControlButton(ItvIcons.Close, onClick = onClose)
        }
    }
}

@Composable
private fun Section(title: String, rows: @Composable () -> Unit) {
    Column {
        Text(title, color = ItvColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Card)) { rows() }
    }
}

@Composable
private fun MenuRow(title: String, value: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ItvColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = ItvColors.TextDim, fontSize = 15.sp)
    }
}

/** Uma escolha aberta numa página própria, com as opções todas à vista. */
private class Picking(val title: String, val options: List<Pair<Any?, String>>, val selected: Any?, val onPick: (Any?) -> Unit)

@Composable
private fun PickRow(title: String, options: List<Pair<Any?, String>>, value: Any?, open: (Picking) -> Unit, onPick: (Any?) -> Unit) {
    MenuRow(title, options.firstOrNull { it.first == value }?.second) { open(Picking(title, options, value, onPick)) }
}

@Composable
private fun PickPage(picking: Picking, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    SubPage(picking.title, onClose) {
        picking.options.forEach { (value, label) ->
            MenuRow(label, if (value == picking.selected) "✓" else null) {
                picking.onPick(value)
                onClose()
            }
        }
    }
}

@Composable
private fun GroupsPage(vm: AppViewModel, onClose: () -> Unit) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    SubPage(s.visibleGroups, onClose) {
        ToggleRow(s.favoritesStar, prefs.showFavoritesGroup) { vm.setPref(Prefs.Key.SHOW_FAVORITES_GROUP, it) }
        ToggleRow(s.all, prefs.showAllGroup) { vm.setPref(Prefs.Key.SHOW_ALL_GROUP, it) }
        ToggleRow(s.recents, prefs.showRecentGroup) { vm.setPref(Prefs.Key.SHOW_RECENT_GROUP, it) }
        val id = content?.playlist?.id
        if (id != null) {
            vm.orderedGroups(includeHidden = true).forEach { g ->
                ToggleRow(g.name, !prefs.isHidden(id, g.id)) { visible -> vm.setGroupHidden(g.id, !visible) }
            }
        }
    }
}

@Composable
private fun UserAgentPage(initial: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    BackHandler(onBack = onClose)
    SubPage(s.userAgent, onClose) {
        Field(s.userAgentSub, text, Modifier.padding(12.dp)) { text = it }
        MenuRow(s.save, null) {
            onSave(text)
            onClose()
        }
    }
}

@Composable
private fun SubPage(title: String, onClose: () -> Unit, rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = BAR_SPACE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton(ItvIcons.Close, onClick = onClose)
            Text(title, color = ItvColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Card)) { rows() }
    }
}

@Composable
private fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = ItvColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(44.dp, 26.dp).clip(CircleShape).then(if (on) Modifier.background(accent.brush) else Modifier.background(Color.White.copy(alpha = 0.18f))).padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
    }
}

/** Nova lista neste aparelho (sem conta). */
@Composable
fun PlaylistFormPage(vm: AppViewModel, onDone: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(PlaylistForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDone)
    Column(
        Modifier.fillMaxSize().background(ItvColors.Background).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(s.addPlaylist, color = ItvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(PlaylistType.XTREAM to "Xtream", PlaylistType.M3U to "M3U") + if (vm.canAddStalker) listOf(PlaylistType.STALKER to "Stalker") else emptyList()).forEach { (type, label) ->
                val on = form.type == type
                Text(
                    label, color = if (on) Color.Black else ItvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(if (on) Color.White else Card).clickable { form = form.withType(type, vm.deviceMac) }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        Field(s.name, form.name) { form = form.copy(name = it) }
        if (form.type == PlaylistType.XTREAM) {
            Field(s.server, form.server, keyboard = KeyboardType.Uri) { form = form.copy(server = it) }
            Field(s.username, form.username) { form = form.copy(username = it) }
            Field(s.password, form.password, secret = true, keyboard = KeyboardType.Password) { form = form.copy(password = it) }
        } else if (form.type == PlaylistType.STALKER) {
            Field(s.portalUrl, form.server, keyboard = KeyboardType.Uri) { form = form.copy(server = it) }
            Field(s.macAddress, form.username) { form = form.copy(username = it) }
            Text(s.stalkerMacHint, color = ItvColors.TextDim, fontSize = 13.sp)
            Field("Serial", form.serial) { form = form.copy(serial = it) }
            Field("Device ID", form.deviceId) { form = form.copy(deviceId = it) }
            Field("Device ID 2", form.deviceId2) { form = form.copy(deviceId2 = it) }
            Field("Signature", form.signature) { form = form.copy(signature = it) }
        } else {
            Field(s.playlistUrl, form.url, keyboard = KeyboardType.Uri) { form = form.copy(url = it) }
        }
        Field(s.epgUrl, form.epgUrl, keyboard = KeyboardType.Uri) { form = form.copy(epgUrl = it) }
        error?.let { Text(it, color = ItvColors.Error, fontSize = 14.sp) }
        Text(
            s.save, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CardShape).background(LocalAccent.current.brush).clickable {
                val problem = form.problem()
                if (problem != null) {
                    error = problem
                } else {
                    scope.launch {
                        error = vm.savePlaylist(null, form)
                        if (error == null) onDone()
                    }
                }
            }.padding(horizontal = 28.dp, vertical = 13.dp),
        )
    }
}

private val BAR_SPACE = 92.dp
private const val CONTROLS_MS = 4_000L
private const val SEEK_MS = 5 * 60_000L
private const val ZAP_DRAG_PX = 120f

/** Erro ao carregar uma lista, com botões de toque. */
@Composable
fun MobileLoadError(error: ErrorInfo, onRetry: () -> Unit, onSettings: (() -> Unit)?, onLogout: (() -> Unit)?, onAddPlaylist: (() -> Unit)?) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Logo(size = 26)
        Spacer(Modifier.height(8.dp))
        Text(error.title, color = ItvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        error.cause?.let { Text(it, color = ItvColors.TextDim, fontSize = 15.sp) }
        error.fixes.forEach { Text("• $it", color = ItvColors.TextDim, fontSize = 14.sp) }
        error.detail?.let { Text(it, color = ItvColors.TextFaint, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        onAddPlaylist?.let { MenuRow(s.addPlaylist, null, it) }
        MenuRow(s.retry, null, onRetry)
        onSettings?.let { MenuRow(s.settings, null, it) }
        onLogout?.let { MenuRow(s.logout, null, it) }
    }
}

/** Com conta: o guia ou o logótipo de um canal (sugestões, pesquisa e países, como na TV). */
@Composable
private fun EpgPickPage(vm: AppViewModel, channel: Channel, logo: Boolean, onClose: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val mappings by vm.epgMappings.collectAsStateWithLifecycle()
    val mapping = mappings[channel.key]
    val choice = vm.channelLogoChoice(channel)
    var query by remember { mutableStateOf(channel.name) }
    var country by remember { mutableStateOf<EpgCountryResult?>(null) }
    var showAll by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (country != null) { country = null; showAll = false } else onClose() })

    fun usable(g: EpgGuideChannel) = g.id != null && (!logo || g.icon != null)
    fun chosen(code: String?, g: EpgGuideChannel) = if (logo) choice == g.icon else mapping?.country == code && mapping?.channelId == g.id
    fun choose(code: String, g: EpgGuideChannel) {
        if (logo) {
            vm.setChannelLogo(channel, g.icon)
            onClose()
        } else {
            scope.launch { if (vm.setEpgMapping(channel, code, g)) onClose() }
        }
    }

    val open = country
    if (open != null) {
        val code = open.country.orEmpty()
        val name = open.countryName ?: code.uppercase()
        val all by produceState<AppViewModel.EpgResult<EpgCountryResult>?>(null, code, showAll) { if (showAll) value = vm.epgCountryChannels(code) }
        val channels = if (showAll) (all as? AppViewModel.EpgResult.Ok)?.value?.channels.orEmpty() else open.channels.orEmpty()
        PickerScaffold(name, { country = null; showAll = false }) {
            if (showAll && all == null) item { PickerRow(s.loadingShort) {} }
            (all as? AppViewModel.EpgResult.Error)?.takeIf { showAll }?.let { e -> item { PickerRow(e.message) {} } }
            items(channels.filter(::usable), key = { it.id!! }) { g ->
                PickerRow(g.name ?: g.id!!, g.now, if (logo) g.icon else null, showLogo = logo, checked = chosen(code, g)) { choose(code, g) }
            }
            if (!showAll) item { PickerRow(s.epgAllCountry(name)) { showAll = true } }
            item {
                if (logo) {
                    PickerRow(s.logoCountryApply(name), s.logoCountryApplySub) {
                        vm.setPref(Prefs.Key.LOGO_SOURCE, code)
                        onClose()
                    }
                } else {
                    PickerRow(s.epgCountryApply(name), s.epgCountryGuideSub) {
                        vm.applyEpgCountry(code, name)
                        onClose()
                    }
                }
            }
        }
        return
    }

    val suggestions by produceState<AppViewModel.EpgResult<List<EpgGuideChannel>>?>(null, channel.key) { value = vm.epgSuggest(channel) }
    val search by produceState<AppViewModel.EpgResult<List<EpgCountryResult>>?>(null, query) {
        value = null
        delay(400)
        value = vm.epgSearch(query)
    }
    PickerScaffold(if (logo) s.channelLogo else s.epgGuideChannel, onClose, header = {
        Field(s.epgSearchOther, query, Modifier.padding(bottom = 8.dp)) { query = it }
    }) {
        if (logo) {
            item {
                PickerRow(s.logoAutomatic, checked = choice == null) {
                    vm.setChannelLogo(channel, null)
                    onClose()
                }
            }
            item {
                PickerRow(s.logoFromPlaylist, logo = channel.logo, showLogo = true, checked = choice == "") {
                    vm.setChannelLogo(channel, "")
                    onClose()
                }
            }
        } else {
            item {
                PickerRow(s.epgFromPlaylist, checked = mapping == null) {
                    if (mapping == null) onClose() else scope.launch { if (vm.removeEpgMapping(channel)) onClose() }
                }
            }
        }
        val suggested = (suggestions as? AppViewModel.EpgResult.Ok)?.value.orEmpty().filter { usable(it) && it.country != null }
        if (suggested.isNotEmpty()) {
            item { PickerHeader(s.epgSuggestions) }
            items(suggested, key = { "s:${it.country}:${it.id}" }) { g ->
                PickerRow(g.name ?: g.id!!, listOfNotNull(g.countryName, g.now).joinToString(" · "), g.icon, showLogo = logo, checked = chosen(g.country, g)) { choose(g.country!!, g) }
            }
        }
        item { PickerHeader(s.epgCountriesFor(query)) }
        when (val r = search) {
            null -> item { PickerRow(s.loadingShort) {} }
            is AppViewModel.EpgResult.Error -> item { PickerRow(r.message) {} }
            is AppViewModel.EpgResult.Ok -> {
                val countries = r.value.map { it.copy(channels = it.channels?.filter(::usable)) }.filter { it.country != null && !it.channels.isNullOrEmpty() }
                if (countries.isEmpty()) item { PickerRow(s.epgNoResults(query)) {} }
                items(countries, key = { "c:${it.country}" }) { c ->
                    PickerRow(c.countryName ?: c.country!!.uppercase(), c.channels!!.size.toString()) { country = c }
                }
            }
        }
    }
}

/** A lista inteira: o guia de um país, ou a origem dos logótipos. */
@Composable
private fun EpgCountriesPage(vm: AppViewModel, logo: Boolean, onClose: () -> Unit) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val countries by produceState<AppViewModel.EpgResult<List<EpgCountryInfo>>?>(null) { value = vm.epgCountries() }
    BackHandler(onBack = onClose)
    fun source(value: String?) {
        vm.setPref(Prefs.Key.LOGO_SOURCE, value)
        onClose()
    }
    PickerScaffold(if (logo) s.logoSource else s.epgCountryGuide, onClose) {
        if (logo) {
            item { PickerRow(s.logoFromPlaylist, checked = prefs.logoSource == Prefs.LogoSource.PLAYLIST) { source(null) } }
            item { PickerRow(s.logoFromGuide, s.logoFromGuideSub, checked = prefs.logoSource == Prefs.LogoSource.GUIDE) { source(Prefs.LogoSource.GUIDE) } }
        }
        when (val r = countries) {
            null -> item { PickerRow(s.loadingShort) {} }
            is AppViewModel.EpgResult.Error -> item { PickerRow(r.message) {} }
            is AppViewModel.EpgResult.Ok -> items(
                r.value.filter { it.code != null && (it.channels ?: 0) > 0 }.sortedBy { it.name ?: it.code },
                key = { it.code!! },
            ) { c ->
                val name = c.name ?: c.code!!.uppercase()
                PickerRow(name, if (logo) null else c.channels.toString(), checked = logo && prefs.logoSource == c.code) {
                    if (logo) {
                        source(c.code)
                    } else {
                        vm.applyEpgCountry(c.code!!, name)
                        onClose()
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerScaffold(title: String, onClose: () -> Unit, header: @Composable () -> Unit = {}, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(ItvColors.Background).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ControlButton(ItvIcons.Close, onClick = onClose)
        }
        header()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    }
}

@Composable
private fun PickerHeader(title: String) {
    Text(title, color = ItvColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun PickerRow(title: String, subtitle: String? = null, logo: String? = null, showLogo: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showLogo) {
            LogoImage(logo, title, 40.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = ItvColors.Text, fontSize = 16.sp)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = ItvColors.TextFaint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (checked) Text("✓", color = LocalAccent.current.start, fontSize = 18.sp)
    }
}
