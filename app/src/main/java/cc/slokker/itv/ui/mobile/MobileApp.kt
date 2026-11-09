package cc.slokker.itv.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.ui.layout.layout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.MainActivity
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.delay

private enum class Tab { Tv, Guide, Favorites, Search, Menu }

/** Chamado depois de abrir um canal a partir de uma lista: no ecrã inicial, passa a mostrar o vídeo. */
internal val LocalOnPlayed = staticCompositionLocalOf<() -> Unit> { {} }

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
    // Ao abrir, o ecrã inicial (recentes e grupos) sem nada a dar; o vídeo só aparece quando se escolhe um canal.
    var home by rememberSaveable { mutableStateOf(prefs.phoneStartup == Prefs.PhoneStartup.HOME) }
    var stopped by rememberSaveable { mutableStateOf(prefs.phoneStartup == Prefs.PhoneStartup.HOME) }
    var homeGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var homePinFor by remember { mutableStateOf<String?>(null) }
    // A dar: em grande no separador TV, ou no mini-leitor por cima dos separadores (no início, no guia...).
    val video = !settings && !stopped
    val big = video && tab == Tab.Tv && !home
    val playback = rememberMobilePlayback(vm, current, data, prefs, streamFormat, active = video)
    val activity = LocalContext.current as? Activity
    val main = activity as? MainActivity
    val pip = main?.inPip == true
    val landscape = video && (pip || (big && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE))
    val mini = video && !big && !landscape
    val pipAction = if (prefs.pipEnabled && main?.pipSupported == true) main::enterPip else null
    // Nas Definições o canal está fechado: não há nada para levar para a janela pequena.
    val pipOnLeave = prefs.pipEnabled && video
    DisposableEffect(main, pipOnLeave) {
        main?.setPipOnLeave(pipOnLeave)
        onDispose { main?.setPipOnLeave(false) }
    }
    // Trocar de lista nas Definições abre outro canal: volta à TV para o ver.
    var shownKey by remember { mutableStateOf(current.key) }
    if (shownKey != current.key) {
        shownKey = current.key
        if (settings) tab = Tab.Tv
    }
    var epgFor by remember { mutableStateOf<Channel?>(null) }
    var epgPick by remember { mutableStateOf<Pair<Channel, Boolean>?>(null) }
    // Áudio, legendas ou temporizador, cada um no seu botão.
    var options by remember { mutableStateOf<PlayerOption?>(null) }
    val showingOptions = options != null
    val view = LocalView.current
    var hapticKey by remember { mutableStateOf(current.key) }
    LaunchedEffect(current.key) {
        if (hapticKey != current.key) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        hapticKey = current.key
    }
    // Temporizador: aviso no último minuto; ao acabar, para e fecha a app.
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val strings = LocalStrings.current
    LaunchedEffect(sleepAt) {
        val at = sleepAt ?: return@LaunchedEffect
        var warned = false
        while (true) {
            val left = at - System.currentTimeMillis()
            if (left <= 0) {
                playback.player.stop()
                vm.setSleepTimer(null, announce = false)
                activity?.finish()
                break
            }
            if (left <= 60_000 && !warned) {
                warned = true
                vm.showToast(strings.sleepSoon(((left + 999) / 1000).toInt()))
            }
            delay(if (left <= 61_000) 1_000 else minOf(left - 60_000, 30_000))
        }
    }

    FullscreenBars(landscape)
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    BackHandler(landscape || showingOptions || epgFor != null || tab != Tab.Tv || homeGroup != null || !home) {
        when {
            showingOptions -> options = null
            landscape -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            epgFor != null -> epgFor = null
            tab != Tab.Tv -> tab = Tab.Tv
            homeGroup != null -> homeGroup = null
            // Do canal volta-se ao ecrã inicial (o canal continua no mini-leitor).
            else -> home = true
        }
    }
    // Quem abre um canal (listas, guia, procura, catch-up) sai do ecrã inicial.
    val played: () -> Unit = {
        stopped = false
        home = false
        homeGroup = null
        tab = Tab.Tv
    }

    CompositionLocalProvider(LocalOnPlayed provides played, LocalBarSpace provides if (mini) BAR_SPACE + MINI_SPACE else BAR_SPACE) {
        Column(Modifier.fillMaxSize().background(ItvColors.Background)) {
            if (!big && !landscape) {
                Spacer(Modifier.statusBarsPadding())
            } else {
                Box(
                    if (landscape) Modifier.fillMaxSize() else Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f),
                ) {
                    // O guia e a lista de canais estão por baixo do vídeo: em ecrã inteiro, volta-se ao alto para os ver.
                    VideoArea(
                        vm, current, playback, landscape, pip, onPip = pipAction,
                        onGuide = {
                            if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            epgFor = current
                        },
                        onChannels = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT },
                        onOption = { options = it },
                        onFullscreen = {
                            activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        },
                    )
                }
            }
            if (!landscape) {
                Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                    when (tab) {
                        Tab.Tv -> when {
                            !home -> Column(Modifier.fillMaxSize()) {
                                PlayerActions(vm, current, playback, onGuide = { epgFor = current }, onPip = pipAction, onOption = { options = it })
                                NextUp(vm, current)
                                ChannelsTab(vm, current) { epgFor = it }
                            }
                            homeGroup != null -> homeGroup?.let { id ->
                                Column(Modifier.fillMaxSize()) {
                                    PageHeader(vm.guideGroups().firstOrNull { it.id == id }?.name?.ifBlank { null } ?: data.categoryName(id), "TV") { homeGroup = null }
                                    ChannelList(vm, vm.channelsOf(id), id, current) { epgFor = it }
                                }
                            }
                            else -> MobileHome(
                                vm, data, current,
                                onPlay = { ch ->
                                    vm.play(ch, if (ch.key == current.key) vm.selectedCategory.value else vm.groupFor(ch))
                                    played()
                                },
                                onGroup = { homeGroup = it },
                                onLockedGroup = { homePinFor = it },
                                onSearch = { tab = Tab.Search },
                            )
                        }
                        Tab.Guide -> ChannelsTab(vm, current, guide = true) { epgFor = it }
                        Tab.Favorites -> PhoneFavorites(vm, current) { epgFor = it }
                        Tab.Search -> PhoneSearch(vm, current, onEpg = { epgFor = it }) { id ->
                            tab = Tab.Tv
                            home = true
                            homeGroup = id
                        }
                        Tab.Menu -> MobileMenu(vm)
                    }
                    // A barra fica colada ao fundo do ecrã, por cima da lista (com o mini-leitor por cima dela, se há um
                    // canal a dar fora do ecrã do canal). Tocar em "TV" já na TV volta ao início.
                    Column(Modifier.align(Alignment.BottomCenter)) {
                        if (mini) {
                            MiniPlayer(
                                vm, current, playback,
                                onOpen = {
                                    tab = Tab.Tv
                                    home = false
                                },
                                onClose = { stopped = true },
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            )
                        }
                        BottomBar(tab) {
                            if (it == Tab.Tv && tab == Tab.Tv) {
                                home = true
                                homeGroup = null
                            }
                            tab = it
                        }
                    }
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
    }
    options?.let { if (!pip) PlayerOptionsSheet(vm, playback, it) { options = null } }
    homePinFor?.let { id ->
        PinDialog(LocalStrings.current.enterPin, onClose = { homePinFor = null }, rejected = vm::pinRejected) { pin ->
            vm.unlockGroup(id, pin).also { if (it) { homeGroup = id; homePinFor = null } }
        }
    }
    var reminder by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (reminder == null) reminder = vm.dueReminder(System.currentTimeMillis())
            delay(15_000)
        }
    }
    if (!pip) reminder?.let { r ->
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clip(CardShape).background(Color(0xFF20232E)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, color = ItvColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.channelName, color = ItvColors.TextDim, fontSize = 13.sp)
                }
                ControlButton(ItvIcons.Play) { vm.consumeReminder(r, watch = true); reminder = null; played() }
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
private fun BottomBar(tab: Tab, modifier: Modifier = Modifier, onTab: (Tab) -> Unit) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth().background(Color(0xFF0C0E13)).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.1f)))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp)) {
            BarItem("TV", ItvIcons.Guide, tab == Tab.Tv) { onTab(Tab.Tv) }
            BarItem(s.tabGuide, ItvIcons.Calendar, tab == Tab.Guide) { onTab(Tab.Guide) }
            BarItem(s.favorites, ItvIcons.Star, tab == Tab.Favorites) { onTab(Tab.Favorites) }
            BarItem(s.search, ItvIcons.Search, tab == Tab.Search) { onTab(Tab.Search) }
            BarItem(s.settings, ItvIcons.Settings, tab == Tab.Menu) { onTab(Tab.Menu) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BarItem(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val tint = if (on) LocalAccent.current.start else ItvColors.TextFaint
    Column(
        Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val BAR_SPACE = 92.dp
private val MINI_SPACE = 70.dp

/** O espaço por baixo das listas: a barra dos separadores e, quando há, o mini-leitor. */
internal val LocalBarSpace = compositionLocalOf { BAR_SPACE }
