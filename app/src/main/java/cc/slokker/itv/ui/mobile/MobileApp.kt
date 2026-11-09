package cc.slokker.itv.ui.mobile

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.media3.common.C
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.MainActivity
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.ui.player.HudLogo
import cc.slokker.itv.data.RefreshPolicy
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.kind
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
import cc.slokker.itv.ui.player.TuningPill
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.i18n.maskEmail
import cc.slokker.itv.ui.theme.Accent
import cc.slokker.itv.ui.player.VideoView
import cc.slokker.itv.ui.player.Timeshift
import cc.slokker.itv.ui.player.VlcPlayer
import cc.slokker.itv.ui.player.behindLabel
import cc.slokker.itv.ui.player.rememberStreamState
import cc.slokker.itv.ui.player.trackOptions
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import cc.slokker.itv.data.api.EpgResult
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.ui.common.LogoImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.ui.theme.tabular
import androidx.tv.material3.LocalTextStyle
import cc.slokker.itv.ui.player.engine.titleCard
import cc.slokker.itv.ui.player.engine.status

private val Card = Color.White.copy(alpha = 0.08f)
private val CardShape = RoundedCornerShape(18.dp)
private val ListShape = RoundedCornerShape(12.dp)
private const val SELECTED_ALPHA = 0.13f
private enum class Tab { Tv, Guide, Favorites, Search, Menu }

/** Chamado depois de abrir um canal a partir de uma lista: no ecrã inicial, passa a mostrar o vídeo. */
internal val LocalOnPlayed = staticCompositionLocalOf<() -> Unit> { {} }

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
private fun VideoArea(
    vm: AppViewModel, channel: Channel, playback: MobilePlayback, fullscreen: Boolean, pip: Boolean, onPip: (() -> Unit)?,
    onGuide: () -> Unit, onChannels: () -> Unit, onOption: (PlayerOption) -> Unit, onFullscreen: () -> Unit,
) {
    val s = LocalStrings.current
    val view = LocalView.current
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableStateOf(0) }
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var seekDelta by remember { mutableStateOf(0L) }
    var seekBase by remember { mutableStateOf(0L) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableStateOf(0) }
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val liveEntry = { now: Long -> vm.guideEntries(channel).ifEmpty { epg }.firstOrNull { it.startMs <= now && it.endMs > now } }
    val ts = playback.timeshift
    // Em direto, as posições contam-se desde o início do programa a dar (ou desde agora, sem guia).
    fun liveBase(now: Long) = liveEntry(now)?.startMs ?: now
    /** Em direto: quanto [target] fica atrás do direto, se for a pausa do direto (no disco) a lá chegar; senão null (catch-up). */
    fun tsBehind(target: Long): Long? {
        if (vm.catchup.value != null || !ts.available) return null
        val now = System.currentTimeMillis()
        val behind = now - liveBase(now) - target
        val entry = liveEntry(now)
        // Mais atrás do que o disco guarda: o catch-up do programa, se houver.
        return if (behind <= ts.windowMs || entry == null || !vm.canCatchup(channel, entry)) behind.coerceIn(0L, ts.windowMs) else null
    }
    // No VLC, o tempo deixa de bater certo depois de saltar dentro do stream: reabre-se nessa posição.
    fun seeksInStream(cu: Catchup, target: Long) =
        target >= cu.positionAt(playback.archiveStart, 0) && playback.player !is VlcPlayer && playback.player.isCurrentMediaItemSeekable
    // Onde um salto vai mesmo parar: os últimos minutos ainda não estão no arquivo do servidor, e o servidor só abre
    // em minutos certos (a avançar vai para o seguinte, senão ficava no mesmo sítio; a recuar, para o início desse minuto).
    fun landing(target: Long, position: Long): Long {
        val cu = vm.catchup.value
        if (cu == null) {
            val wanted = target.coerceAtMost(position - Catchup.MIN_AGE_MS - SEEK_MS).coerceAtLeast(0)
            val start = liveEntry(System.currentTimeMillis())?.startMs ?: return wanted
            return (wanted - (start + wanted) % 60_000).coerceAtLeast(0)
        }
        val wanted = target.coerceAtLeast(0)
        if (seeksInStream(cu, wanted)) return wanted
        val extra = (cu.startMs + wanted) % 60_000
        return (if (wanted > position) wanted + (60_000 - extra) % 60_000 else wanted - extra).coerceAtLeast(0)
    }
    fun goTo(target: Long, position: Long) {
        val cu = vm.catchup.value
        tsBehind(target)?.let {
            ts.seekBehind(playback.player, it)
            return
        }
        if (cu == null) {
            val entry = liveEntry(System.currentTimeMillis())
            if (entry != null && target < position - 5_000) vm.playCatchup(channel, entry, landing(target, position))
            return
        }
        if (seeksInStream(cu, target)) {
            playback.player.seekTo(target - cu.positionAt(playback.archiveStart, 0))
        } else {
            vm.seekCatchup(landing(target, position))
        }
    }
    // Vários toques seguidos somam-se, e só depois se pede a posição nova: cada pedido reabre o stream.
    fun seek(delta: Long, position: Long) {
        if (seekDelta == 0L) seekBase = position
        seekDelta += delta
        touched++
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(SEEK_DEBOUNCE_MS)
            val target = seekBase + seekDelta
            seekDelta = 0
            goTo(target, seekBase)
        }
    }
    LaunchedEffect(controls, touched, channel.key, scrub) {
        if (controls && scrub == null) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(controls) {
        while (controls) {
            delay(1_000)
            tick++
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
            update = {
                playback.view = it
                // Como na TV: o ecrã não se apaga enquanto o canal dá (ou carrega).
                it.keepScreenOn = playback.playing || playback.buffering
            },
            modifier = Modifier.fillMaxSize(),
        )
        playback.freeze?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        when {
            // Não arrancou: porquê (como na TV) e a próxima tentativa; o Play tenta outra vez.
            playback.failure != null -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val failure = playback.failure!!
                Text(failure.titleCard(s).title, color = ItvColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center)
                failure.status(s)?.let { Text(it, color = ItvColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
            }
            controls && !pip -> {}
            playback.freeze != null -> Spinner(Modifier.align(Alignment.Center))
            // A abrir: que está a carregar, e o quê (a voltar a ligar diz-o a pílula, mais abaixo).
            !playback.ready && playback.reconnects == 0 -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner()
                Text(s.loadingPlaylist(channel.name), color = ItvColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        // Caiu e está a voltar a ligar: ao alto a pílula no topo; em ecrã inteiro a cápsula da TV.
        if (playback.reconnects > 0 && !pip) {
            if (fullscreen) {
                TuningPill(
                    channel = channel.name,
                    reconnecting = true,
                    attempt = playback.reconnects,
                    nextTryAt = playback.nextTryAt,
                    waitMs = playback.retryDelayMs,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
                ReconnectIsland(channel.name, playback.nextTryAt, playback.retryDelayMs, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
        if (controls && !pip) {
            // O vídeo escurece um pouco, para os botões se verem mesmo num vídeo claro.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
            // Em ecrã inteiro, em cima: sair dele, a hora e a janela pequena.
            if (fullscreen) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(ItvIcons.FullscreenExit, Modifier.size(40.dp).clip(CircleShape).background(HudButton), onClick = onFullscreen)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(System.currentTimeMillis()), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp), style = LocalTextStyle.current.tabular)
                    onPip?.let { ControlButton(ItvIcons.Pip, Modifier.size(40.dp).clip(CircleShape).background(HudButton), onClick = it) }
                }
            }
            Box(Modifier.align(if (fullscreen) Alignment.Center else BiasAlignment(0f, -0.3f))) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Em direto, recua-se para o catch-up do programa a dar; à frente do direto não há nada.
                    val cu = catchup
                    val live = if (cu == null) liveEntry(System.currentTimeMillis()) else null
                    val canSeek = cu != null || (cu == null && ts.available) || (live != null && vm.canCatchup(channel, live))
                    fun position(): Long {
                        if (cu != null) return cu.positionAt(playback.archiveStart, playback.player.currentPosition)
                        val now = System.currentTimeMillis()
                        return now - (if (ts.available) ts.behindMs else 0L) - liveBase(now)
                    }
                    if (canSeek) SeekButton(forward = false) { seek(-SEEK_MS, position()) }
                    // A abrir, a pausa não faz nada: fica o indicador no lugar dela, sem mexer nos outros botões.
                    if (playback.failure == null && (!playback.ready || playback.freeze != null)) {
                        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                            Spinner()
                        }
                    } else {
                        ControlButton(if (playback.playing) ItvIcons.Pause else ItvIcons.Play, Modifier.size(60.dp).clip(CircleShape).background(HudButton)) {
                            when {
                                // Não arrancou: tenta outra vez desde o primeiro formato (o OK da TV).
                                playback.failure != null -> playback.core.retry()
                                playback.playing -> playback.player.pause()
                                // Pausa do direto: continua de onde parou (o disco continuou a gravar).
                                cu == null -> ts.resume(playback.player)
                                else -> playback.player.play()
                            }
                            touched++
                        }
                    }
                    if (canSeek) SeekButton(forward = true, enabled = cu != null || ts.shifted) { seek(SEEK_MS, position()) }
                }
                // Por cima dos botões, sem os mexer.
                if (seekDelta != 0L) {
                    val target = seekBase + seekDelta
                    val goesLive = catchup?.let { target >= (System.currentTimeMillis() - it.startMs - Catchup.MIN_AGE_MS).coerceAtMost(it.durationMs) } == true
                    val behind = tsBehind(target)
                    Text(
                        when {
                            behind != null -> if (behind <= Timeshift.LIVE_SNAP_MS) s.playerGoLive else behindLabel(behind)
                            goesLive -> s.playerGoLive
                            else -> "→ ${durationText(landing(target, seekBase))}"
                        },
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            // Em baixo, como o HUD da TV: logótipo, canal, programa, horas, o seguinte e a barra larga.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ItvColors.Background.copy(alpha = 0.6f), ItvColors.Background.copy(alpha = 0.92f))))
                    .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) else Modifier)
                    .padding(start = if (fullscreen) 24.dp else 12.dp, end = if (fullscreen) 24.dp else 8.dp, top = if (fullscreen) 60.dp else 34.dp, bottom = if (fullscreen) 14.dp else 2.dp),
            ) {
                tick
                val now = System.currentTimeMillis()
                val cu = catchup
                val entry = if (cu == null) liveEntry(now) else null
                val programme = cu?.let { EpgEntry(it.title, it.startMs, it.endMs) } ?: entry
                val next = if (cu == null) vm.guideEntries(channel).ifEmpty { epg }.firstOrNull { it.startMs >= (entry?.endMs ?: now) } else null
                Row(verticalAlignment = Alignment.Bottom) {
                    HudLogo(channel, Modifier.size(if (fullscreen) 92.dp else 56.dp, if (fullscreen) 52.dp else 32.dp))
                    Spacer(Modifier.width(if (fullscreen) 16.dp else 10.dp))
                    Column(Modifier.weight(1f)) {
                        if (fullscreen) {
                            Text(
                                "${channel.num}  ${channel.name}" + if (vm.isFavorite(channel)) "  ★" else "",
                                color = ItvColors.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            programme?.title ?: channel.name, color = Color.White, fontSize = if (fullscreen) 22.sp else 16.sp, fontWeight = FontWeight.ExtraBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (programme != null) {
                            val left = if (cu == null) " · " + s.minutesLeft(((programme.endMs - now) / 60_000 + 1).toInt()) else ""
                            Text("${formatTime(programme.startMs)} — ${formatTime(programme.endMs)}$left", color = ItvColors.TextDim, fontSize = if (fullscreen) 13.sp else 12.sp, maxLines = 1)
                        }
                    }
                    if (fullscreen && next != null) {
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.widthIn(max = 220.dp), horizontalAlignment = Alignment.End) {
                            Text(s.next(formatTime(next.startMs)), color = ItvColors.TextDim, fontSize = 11.sp)
                            Text(next.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!fullscreen) ControlButton(ItvIcons.Fullscreen, Modifier.size(38.dp).clip(CircleShape).background(HudButton), onClick = onFullscreen)
                }
                // Onde vai e quanto dura: o catch-up, ou o programa a dar. Em direto, só se pode arrastar se houver catch-up.
                val duration = cu?.durationMs ?: entry?.let { it.endMs - it.startMs }
                if (duration != null && duration > 0) {
                    val position = when {
                        cu != null && playback.ready -> cu.positionAt(playback.archiveStart, playback.player.currentPosition)
                        cu != null -> cu.positionMs
                        // Na pausa do direto, o ponto fica onde está o que se vê.
                        else -> now - entry!!.startMs - (if (ts.available) ts.behindMs else 0L)
                    }
                    val fraction = scrub ?: (position.toFloat() / duration).coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // No catch-up conta onde vai; em direto, as horas já estão por cima.
                        if (cu != null) Text(durationText((fraction * duration).toLong()), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp), style = LocalTextStyle.current.tabular)
                        if (cu == null && ts.shifted && scrub == null) {
                            Text(behindLabel(ts.behindMs), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp), style = LocalTextStyle.current.tabular)
                        }
                        PlayerBar(
                            fraction, seekable = cu != null || ts.available || vm.canCatchup(channel, entry!!),
                            onChange = { scrub = it; touched++ },
                            onEnd = {
                                val target = ((scrub ?: fraction) * duration).toLong()
                                scrub = null
                                touched++
                                seekJob?.cancel()
                                seekDelta = 0
                                goTo(target, position)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (cu != null) Text(durationText(duration), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp), style = LocalTextStyle.current.tabular)
                    }
                }
                if (fullscreen) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Num ecrã estreito (ou com textos compridos) as pílulas deslizam; os botões da direita ficam.
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiveButton(catchup == null && !ts.shifted, behind = behindLabel(ts.behindMs).takeIf { catchup == null }) { if (catchup != null) vm.exitCatchup() else ts.goLive(playback.player) }
                            HudPill(ItvIcons.Calendar, s.tabGuide, onClick = onGuide)
                            HudPill(if (vm.isFavorite(channel)) ItvIcons.Star else ItvIcons.StarOutline, s.favorite) {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                vm.toggleFavorite(channel)
                                touched++
                            }
                            HudPill(ItvIcons.Channels, s.searchChannels, onClick = onChannels)
                        }
                        // O que estava no "mais": cada um no seu botão.
                        PlayerOption.entries.forEach { option ->
                            ControlButton(option.icon, Modifier.size(40.dp).clip(CircleShape).background(HudButton)) { onOption(option); touched++ }
                        }
                    }
                }
            }
        }
    }
}

/** Um botão com texto por cima do vídeo (ecrã inteiro). */
@Composable
private fun HudPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(HudButton).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private val HudButton = Color.White.copy(alpha = 0.14f)

/** O canal está a abrir. */
@Composable
private fun Spinner(modifier: Modifier = Modifier) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn",
    )
    Canvas(modifier.size(28.dp)) {
        val stroke = 2.dp.toPx()
        drawArc(Color.White, turn, 270f, false, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** O botão do direto: em direto só avisa; em catch-up ou na pausa do direto, volta ([behind]: quanto vai atrás). */
@Composable
private fun LiveButton(live: Boolean, behind: String? = null, onClick: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.clip(CircleShape).background(if (live) Color.Transparent else Color.White.copy(alpha = 0.16f)).clickable(enabled = !live, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Color(0xFFE53935) else Color.White.copy(alpha = 0.6f)))
        Spacer(Modifier.width(6.dp))
        Text(if (live) s.playerLive else s.playerGoLive, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (!live && behind != null) {
            Text("  $behind", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = LocalTextStyle.current.tabular)
        }
    }
}

/** A barra de progresso do leitor, para arrastar. */
@Composable
private fun PlayerBar(progress: Float, seekable: Boolean, onChange: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.brush
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier.height(30.dp).then(
            if (!seekable) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val pointer = awaitPointerEvent().changes.first()
                        pointer.consume()
                        if (!pointer.pressed) break
                        change((pointer.position.x / size.width).coerceIn(0f, 1f))
                    }
                    end()
                }
            },
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
        Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(CircleShape).background(accent))
        // O ponto onde vai, como na barra larga da TV.
        Box(Modifier.fillMaxWidth(progress), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.offset(x = 7.dp).size(14.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}

private fun durationText(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Recuar ou avançar 30 segundos, ao lado do botão de pausa. */
@Composable
private fun SeekButton(forward: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(ItvIcons.Replay, null, tint = tint, modifier = Modifier.size(34.dp).graphicsLayer { if (forward) scaleX = -1f })
        Text("30", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** O que se escolhe no canal a dar, cada um no seu botão. */
private enum class PlayerOption(val icon: ImageVector) { AUDIO(ItvIcons.Audio), SUBTITLES(ItvIcons.Subtitles), SLEEP(ItvIcons.Sleep) }

@Composable
private fun PlayerOption.label(): String {
    val s = LocalStrings.current
    return when (this) {
        PlayerOption.AUDIO -> s.audio
        PlayerOption.SUBTITLES -> s.subtitles
        PlayerOption.SLEEP -> s.sleepTimer
    }
}

/** O áudio, as legendas ou o temporizador do canal a dar. */
@Composable
private fun PlayerOptionsSheet(vm: AppViewModel, playback: MobilePlayback, option: PlayerOption, onClose: () -> Unit) {
    val s = LocalStrings.current
    val tracks = rememberStreamState(playback.player).tracks
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Color(0xFF14161E))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (option) {
                PlayerOption.AUDIO, PlayerOption.SUBTITLES -> {
                    val text = option == PlayerOption.SUBTITLES
                    Section(option.label()) {
                        val options = trackOptions(tracks, if (text) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO, playback.player, text)
                        if (options.isEmpty()) Note(s.noTracks)
                        options.forEach { o ->
                            MenuRow(o.label, null, checked = o.selected) {
                                o.select()
                                onClose()
                            }
                        }
                    }
                }
                PlayerOption.SLEEP -> Section(sleepAt?.let { s.sleepRemaining(((it - System.currentTimeMillis()) / 60_000 + 1).toInt()) } ?: s.sleepTimer) {
                    MenuRow(s.subtitlesOff, null, checked = sleepAt == null) {
                        vm.setSleepTimer(null)
                        onClose()
                    }
                    listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                        MenuRow("$minutes min", null) {
                            vm.setSleepTimer(minutes)
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, modifier: Modifier = Modifier.size(44.dp), onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
}

/** Por baixo do vídeo, numa fila que desliza: direto (ou voltar a ele), guia, favorito, áudio, legendas, temporizador e a janela pequena. */
@Composable
private fun PlayerActions(vm: AppViewModel, channel: Channel, playback: MobilePlayback, onGuide: () -> Unit, onPip: (() -> Unit)?, onOption: (PlayerOption) -> Unit) {
    val s = LocalStrings.current
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Atrás do direto (catch-up ou a pausa do direto): o botão volta a ele.
        val ts = playback.timeshift
        LiveButton(catchup == null && !ts.shifted, behind = behindLabel(ts.behindMs).takeIf { catchup == null }) { if (catchup != null) vm.exitCatchup() else ts.goLive(playback.player) }
        ActionPill(ItvIcons.Calendar, s.tabGuide, onClick = onGuide)
        favorites.size
        val favorite = vm.isFavorite(channel)
        ActionPill(if (favorite) ItvIcons.Star else ItvIcons.StarOutline, s.favorite, tint = if (favorite) FavoriteGold else ItvColors.Text) { vm.toggleFavorite(channel) }
        PlayerOption.entries.forEach { option ->
            // Com o temporizador ligado, o botão diz quanto falta.
            val label = if (option == PlayerOption.SLEEP && sleepAt != null) "${((sleepAt!! - System.currentTimeMillis()) / 60_000 + 1)} min" else option.label()
            ActionPill(option.icon, label, tint = if (option == PlayerOption.SLEEP && sleepAt != null) LocalAccent.current.start else ItvColors.Text) { onOption(option) }
        }
        onPip?.let { ActionPill(ItvIcons.Pip, "PiP", onClick = it) }
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, tint: Color = ItvColors.Text, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Card).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = ItvColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** O programa seguinte do canal a dar, com o lembrete (o que está a dar já se vê no vídeo e na lista). */
@Composable
private fun NextUp(vm: AppViewModel, channel: Channel) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val entries = remember(epg, channel.key) { vm.guideEntries(channel).ifEmpty { epg } }
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val current = entries.firstOrNull { it.startMs <= now && it.endMs > now }
    val next = entries.firstOrNull { it.startMs >= (current?.endMs ?: now) } ?: return
    reminders.size
    val reminded = vm.hasReminder(channel, next)
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp).fillMaxWidth().clip(ListShape).background(Card)
            .clickable { vm.toggleReminder(channel, next) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.next(formatTime(next.startMs)), color = ItvColors.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(next.title, color = ItvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(ItvIcons.Bell, s.setReminder, tint = if (reminded) accent.start else ItvColors.TextFaint, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}

private val FavoriteGold = Color(0xFFF5C04A)

@Composable
private fun ChannelsTab(vm: AppViewModel, current: Channel, guide: Boolean = false, onEpg: (Channel) -> Unit) {
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
                        .clip(ListShape)
                        .background(if (on) accent.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
                        .clickable { group = g.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(locked, key = { it.id }) { g ->
                Text(
                    "🔒 ${g.name}", color = ItvColors.TextFaint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.clip(ListShape).clickable { pinFor = g.id }.padding(horizontal = 12.dp, vertical = 8.dp),
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
private fun ChannelList(vm: AppViewModel, channels: List<Channel>, groupId: String, current: Channel, onEpg: (Channel) -> Unit) {
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
    val programme = found ?: epg.firstOrNull { it.startMs <= now && it.endMs > now }?.takeIf { prefs.epgInList }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ListShape)
            .background(if (playing) accent.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
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
                    Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                        Box(Modifier.fillMaxWidth(programme.progress(now)).fillMaxHeight().background(if (playing) accent else Color.White.copy(alpha = 0.34f)))
                    }
                }
            }
        }
    }
}

/** O guia de um canal (manter o dedo no cartão). */
@Composable
private fun EpgSheet(vm: AppViewModel, channel: Channel, onPick: (logo: Boolean) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val played = LocalOnPlayed.current
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val now = rememberNow()
    val entries = remember(epg, channel.key) { vm.guideEntries(channel).ifEmpty { epg.distinctBy { it.startMs } } }
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

@Composable
internal fun Note(text: String) = Text(text, color = ItvColors.TextFaint, fontSize = 15.sp, modifier = Modifier.padding(16.dp))

@Composable
internal fun Field(hint: String, value: String, modifier: Modifier = Modifier, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
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

/** As páginas das Definições. */
private enum class MenuPage { Playback, Channels, Guide, Appearance, Parental }

/** Definições do telemóvel: um ecrã curto com as categorias, e uma página para cada. */
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
        return PinDialog(s.enterPin, onClose = null, rejected = vm::pinRejected) { pin -> vm.unlockSettings(pin).also { if (it) unlockedMenu = true } }
    }
    var page by remember { mutableStateOf<MenuPage?>(null) }
    var choosingPin by remember { mutableStateOf(false) }
    // Com PIN, a página do controlo parental e o terminar sessão pedem-no sempre (como na TV).
    var parentalGate by remember { mutableStateOf(false) }
    var logoutGate by remember { mutableStateOf(false) }
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
    if (parentalGate) {
        return PinDialog(s.enterPin, onClose = { parentalGate = false }, rejected = vm::pinRejected) { pin ->
            vm.checkPin(pin).also { if (it) { parentalGate = false; page = MenuPage.Parental } }
        }
    }
    if (logoutGate) {
        return PinDialog(s.enterPin, onClose = { logoutGate = false }, rejected = vm::pinRejected) { pin ->
            vm.checkPin(pin).also { if (it) { logoutGate = false; vm.logoutThisTv() } }
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
    val formats = listOf(null to s.auto, StreamFormat.TS to "TS (MPEG-TS)", StreamFormat.HLS to "HLS (m3u8)")
    val buffers = listOf(Prefs.Buffer.LIVE to s.bufferLive, Prefs.Buffer.SMALL to s.bufferSmall, Prefs.Buffer.NORMAL to s.bufferNormal, Prefs.Buffer.LARGE to s.bufferLarge)
    val startups = listOf(Prefs.PhoneStartup.HOME to s.homeScreen, Prefs.PhoneStartup.LAST to s.startupLast)
    val languages = listOf("auto" to s.languageAuto) + Strings.LANGUAGES
    val accents = Accent.ALL.map { it.id to s.accentName(it.id) }
    fun label(options: List<Pair<Any?, String>>, value: Any?) = options.firstOrNull { it.first == value }?.second.orEmpty()
    page?.let { BackHandler { page = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBarSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (page) {
            null -> {
                Text(s.settings, color = ItvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))
                // A lista em uso em destaque, como o canal atual nas Definições da TV; as outras por baixo.
                content?.let { c ->
                    val type = c.playlist.type.kind.label
                    PlaylistCard(c.playlist.name, "$type · ${s.groupChannels(c.channels.size)}")
                }
                val others = playlists.filter { it.id != content?.playlist?.id }
                if (others.isNotEmpty() || local) {
                    Section(s.playlists) {
                        others.forEach { p -> MenuRow(p.name, null, ItvIcons.Playlist) { vm.switchPlaylist(p) } }
                        if (local) MenuRow(s.addPlaylist, null, ItvIcons.Playlist, chevron = true) { adding = true }
                    }
                }
                Section(s.sectionWatching) {
                    MenuRow(s.playback, label(formats, format).substringBefore(" ") + ", " + label(buffers, prefs.buffer).substringBefore(" ("), ItvIcons.PlayCircle, chevron = true) { page = MenuPage.Playback }
                    MenuRow(s.manageChannels, label(startups, prefs.phoneStartup), ItvIcons.SwapVert, chevron = true) { page = MenuPage.Channels }
                    MenuRow(s.guide, s.refreshLabel(prefs.epgRefreshHours), ItvIcons.Calendar, chevron = true) { page = MenuPage.Guide }
                }
                Section(s.sectionInterface) {
                    MenuRow(s.settingsAppearance, label(languages, prefs.language) + ", " + label(accents, prefs.accent), ItvIcons.Palette, chevron = true) { page = MenuPage.Appearance }
                }
                Section(s.sectionLibrary) {
                    MenuRow(s.parental, if (prefs.pinHash == null) s.settingOff else s.settingOn, ItvIcons.Lock, chevron = true) {
                        if (prefs.pinHash != null) parentalGate = true else page = MenuPage.Parental
                    }
                    MenuRow(s.sendReport, when (val r = report) { ReportState.Sending -> "…"; is ReportState.Sent -> r.id; else -> null }, ItvIcons.Report, chevron = true) { reporting = !reporting }
                    if (reporting) {
                        Field(s.sendReportSub, message, Modifier.padding(horizontal = 12.dp)) { message = it }
                        MenuRow(s.sendReport, "→") { vm.sendReport(message); reporting = false; message = "" }
                    }
                    if (!local) MenuRow(s.logout, email?.let(::maskEmail), ItvIcons.Account) { if (vm.logoutNeedsPin()) logoutGate = true else vm.logoutThisTv() }
                    when (val u = update) {
                        is UpdateState.Available -> MenuRow(u.release.version, null, ItvIcons.Update, onClick = vm::installUpdate)
                        is UpdateState.Downloading -> MenuRow(u.release.version, "${(u.progress * 100).toInt()}%", ItvIcons.Update) { }
                        is UpdateState.Installing -> MenuRow(s.installing, null, ItvIcons.Update) { }
                        UpdateState.UpToDate -> MenuRow(s.upToDate, BuildConfig.VERSION_NAME, ItvIcons.Update) { vm.checkForUpdate(true) }
                        else -> MenuRow(s.checkUpdates, BuildConfig.VERSION_NAME, ItvIcons.Update) { vm.checkForUpdate(true) }
                    }
                }
                Text("GoatTV ${BuildConfig.VERSION_NAME}", color = ItvColors.TextFaint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center)
            }
            MenuPage.Playback -> {
                PageHeader(s.playback, s.settings) { page = null }
                Section(s.settingsPictureSound) {
                    prefRow(Prefs.Key.ASPECT, s.aspect, listOf(Prefs.Aspect.FIT to s.aspectFit, Prefs.Aspect.ZOOM to s.aspectZoom, Prefs.Aspect.FILL to s.aspectFill), prefs.aspect)
                    if ((LocalContext.current as? MainActivity)?.pipSupported == true) {
                        ToggleRow(s.pictureInPicture, prefs.pipEnabled) { vm.setPref(Prefs.Key.PIP_ENABLED, it) }
                    }
                }
                Section(s.settingsConnection) {
                    prefRow(Prefs.Key.BUFFER, s.buffer, buffers, prefs.buffer)
                    prefRow(Prefs.Key.RECONNECT_SECONDS, s.reconnect, listOf(0 to s.no) + listOf(2, 5, 10, 30).map { it to s.afterSeconds(it) }, prefs.reconnectSeconds)
                    ToggleRow(s.timeshift, prefs.timeshift) { vm.setPref(Prefs.Key.TIMESHIFT, it) }
                }
                Section(s.settingsAdvanced) {
                    PickRow(s.streamFormat, formats, format, open) { vm.setStreamFormat(it as String?) }
                    prefRow(Prefs.Key.ENGINE, s.engine, listOf(Prefs.Engine.EXO to s.engineExo, Prefs.Engine.VLC to s.engineVlc), prefs.engine)
                    prefRow(Prefs.Key.DECODER, s.decoder, listOf(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw), prefs.decoder)
                    prefRow(Prefs.Key.AUDIO_DECODER, s.audioDecoder, listOf(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw), prefs.audioDecoder)
                    MenuRow(s.userAgent, prefs.userAgent.ifBlank { s.userAgentDefault }, chevron = true) { editingAgent = true }
                }
            }
            MenuPage.Channels -> {
                PageHeader(s.manageChannels, s.settings) { page = null }
                Section(null) {
                    prefRow(Prefs.Key.PHONE_STARTUP, s.onStartup, startups, prefs.phoneStartup)
                    prefRow(Prefs.Key.SORT, s.sortChannels, listOf(Prefs.Sort.NUMBER to s.sortNumber, Prefs.Sort.NAME to s.sortName), prefs.sort)
                    ToggleRow(s.channelNumbers, prefs.showNumbers) { vm.setPref(Prefs.Key.SHOW_NUMBERS, it) }
                    ToggleRow(s.channelLogos, prefs.showLogos) { vm.setPref(Prefs.Key.SHOW_LOGOS, it) }
                    ToggleRow(s.nowInList, prefs.epgInList) { vm.setPref(Prefs.Key.EPG_IN_LIST, it) }
                    if (vm.epgPickerAvailable) MenuRow(s.logoSource, null, chevron = true) { pickingCountry = true }
                    MenuRow(s.visibleGroups, null, chevron = true) { managingGroups = true }
                }
            }
            MenuPage.Guide -> {
                PageHeader(s.guide, s.settings) { page = null }
                Section(null) {
                    prefRow(Prefs.Key.EPG_REFRESH, s.epgRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.epgRefreshHours)
                    prefRow(Prefs.Key.PLAYLIST_REFRESH, s.playlistRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.playlistRefreshHours)
                    prefRow(Prefs.Key.EPG_OFFSET, s.timeOffset, (-180..180 step 30).map { m -> m to (if (m == 0) "0" else "%+d min".format(m)) }, prefs.epgOffsetMin)
                    if (vm.epgPickerAvailable) MenuRow(s.epgCountryGuide, null, chevron = true) { pickingCountry = false }
                    if (vm.epgPickerAvailable && epgMappings.isNotEmpty()) {
                        MenuRow(if (confirmingEpgReset) s.pressAgainToConfirm else s.epgResetAll, null) {
                            if (confirmingEpgReset) vm.resetEpgMappings()
                            confirmingEpgReset = !confirmingEpgReset
                        }
                    }
                    MenuRow(s.refreshEpg, null, onClick = vm::refreshEpg)
                }
            }
            MenuPage.Appearance -> {
                PageHeader(s.settingsAppearance, s.settings) { page = null }
                Section(null) {
                    prefRow(Prefs.Key.LANGUAGE, s.language, languages, prefs.language)
                    prefRow(Prefs.Key.ACCENT, s.accent, accents, prefs.accent)
                }
            }
            MenuPage.Parental -> {
                PageHeader(s.parental, s.settings) { page = null }
                Section(null) {
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
            }
        }
    }
}

/** Quatro dígitos com o teclado do telefone. [check] devolve true quando o PIN serve; [rejected] diz porque não serviu. */
@Composable
internal fun PinDialog(title: String, onClose: (() -> Unit)?, rejected: () -> String = { "" }, check: (String) -> Boolean) {
    val s = LocalStrings.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).clip(CardShape).background(Color(0xFF14161E)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Field(s.pinHint, pin, secret = true, keyboard = KeyboardType.NumberPassword) { v ->
                pin = v.filter(Char::isDigit).take(4)
                wrong = null
                if (pin.length == 4 && !check(pin)) {
                    wrong = rejected().ifEmpty { s.wrongPin }
                    pin = ""
                }
            }
            wrong?.let { Text(it, color = ItvColors.Error, fontSize = 14.sp) }
            if (onClose != null) ControlButton(ItvIcons.Close, onClick = onClose)
        }
    }
}

private val RowLine = Color.White.copy(alpha = 0.08f)
private val SectionFill = Color.White.copy(alpha = 0.05f)
private val SectionShape = RoundedCornerShape(16.dp)

/** A lista em uso, no topo das Definições. */
@Composable
private fun PlaylistCard(name: String, detail: String) {
    val accent = LocalAccent.current
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1D2236), Color(0xFF12151D)))).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accent.brush), contentAlignment = Alignment.Center) {
            Icon(ItvIcons.Playlist, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = ItvColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = ItvColors.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Section(title: String?, rows: @Composable () -> Unit) {
    Column {
        if (title != null) {
            Text(title.uppercase(), color = ItvColors.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
        }
        // Num cartão, como nas Definições da TV. A linha por baixo da última fila fica de fora.
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(SectionShape).background(SectionFill).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height - 0.5.dp.roundToPx()) { placeable.place(0, 0) }
            },
        ) { rows() }
    }
}

/** O topo de uma página: voltar atrás e o título. */
@Composable
private fun PageHeader(title: String, back: String, onBack: () -> Unit) {
    val accent = LocalAccent.current.start
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.align(Alignment.CenterStart).clip(ListShape).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ItvIcons.Back, null, tint = accent, modifier = Modifier.size(24.dp))
            Text(back, color = accent, fontSize = 16.sp)
        }
        Text(title, color = ItvColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun MenuRow(title: String, value: String?, icon: ImageVector? = null, chevron: Boolean = false, checked: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current.start
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = ItvColors.TextDim, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(14.dp))
            }
            Text(title, color = ItvColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) {
                Spacer(Modifier.width(8.dp))
                Text(value, color = ItvColors.TextFaint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            }
            if (checked) Icon(ItvIcons.Check, null, tint = accent, modifier = Modifier.size(22.dp))
            if (chevron) Icon(ItvIcons.Chevron, null, tint = ItvColors.TextFaint.copy(alpha = 0.42f), modifier = Modifier.size(22.dp))
        }
        Box(Modifier.padding(start = if (icon != null) 64.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(RowLine))
    }
}

/** O valor numa fila: as explicações longas ficam para a lista de opções. */
private fun shortLabel(label: String) = if (label.length > 24) label.substringBefore(" (") else label

/** Uma escolha aberta numa página própria, com as opções todas à vista. */
private class Picking(val title: String, val options: List<Pair<Any?, String>>, val selected: Any?, val onPick: (Any?) -> Unit)

@Composable
private fun PickRow(title: String, options: List<Pair<Any?, String>>, value: Any?, open: (Picking) -> Unit, onPick: (Any?) -> Unit) {
    MenuRow(title, options.firstOrNull { it.first == value }?.second?.let(::shortLabel), chevron = true) { open(Picking(title, options, value, onPick)) }
}

@Composable
private fun PickPage(picking: Picking, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    SubPage(picking.title, onClose) {
        picking.options.forEach { (value, label) ->
            MenuRow(label, null, checked = value == picking.selected) {
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
        Field(s.userAgentSub, text, Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { text = it }
        MenuRow(s.save, null) {
            onSave(text)
            onClose()
        }
    }
}

@Composable
private fun SubPage(title: String, onClose: () -> Unit, rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBarSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(title, LocalStrings.current.settings, onClose)
        Section(null, rows)
    }
}

@Composable
private fun ToggleRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().clickable { onChange(!on) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = ItvColors.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp, 26.dp).clip(CircleShape).then(if (on) Modifier.background(accent.brush) else Modifier.background(Color.White.copy(alpha = 0.18f))).padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) { Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White)) }
        }
        Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(RowLine))
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
private val MINI_SPACE = 70.dp

/** O espaço por baixo das listas: a barra dos separadores e, quando há, o mini-leitor. */
internal val LocalBarSpace = compositionLocalOf { BAR_SPACE }
private const val CONTROLS_MS = 4_000L
private const val SEEK_MS = 30_000L
private const val SEEK_DEBOUNCE_MS = 700L
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
        onAddPlaylist?.let { MenuRow(s.addPlaylist, null, onClick = it) }
        MenuRow(s.retry, null, onClick = onRetry)
        onSettings?.let { MenuRow(s.settings, null, onClick = it) }
        onLogout?.let { MenuRow(s.logout, null, onClick = it) }
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
        val all by produceState<EpgResult<EpgCountryResult>?>(null, code, showAll) { if (showAll) value = vm.epgCountryChannels(code) }
        val channels = if (showAll) (all as? EpgResult.Ok)?.value?.channels.orEmpty() else open.channels.orEmpty()
        PickerScaffold(name, { country = null; showAll = false }) {
            if (showAll && all == null) item { PickerRow(s.loadingShort) {} }
            (all as? EpgResult.Error)?.takeIf { showAll }?.let { e -> item { PickerRow(e.message) {} } }
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

    val suggestions by produceState<EpgResult<List<EpgGuideChannel>>?>(null, channel.key) { value = vm.epgSuggest(channel) }
    val search by produceState<EpgResult<List<EpgCountryResult>>?>(null, query) {
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
        val suggested = (suggestions as? EpgResult.Ok)?.value.orEmpty().filter { usable(it) && it.country != null }
        if (suggested.isNotEmpty()) {
            item { PickerHeader(s.epgSuggestions) }
            items(suggested, key = { "s:${it.country}:${it.id}" }) { g ->
                PickerRow(g.name ?: g.id!!, listOfNotNull(g.countryName, g.now).joinToString(" · "), g.icon, showLogo = logo, checked = chosen(g.country, g)) { choose(g.country!!, g) }
            }
        }
        item { PickerHeader(s.epgCountriesFor(query)) }
        when (val r = search) {
            null -> item { PickerRow(s.loadingShort) {} }
            is EpgResult.Error -> item { PickerRow(r.message) {} }
            is EpgResult.Ok -> {
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
    val countries by produceState<EpgResult<List<EpgCountryInfo>>?>(null) { value = vm.epgCountries() }
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
            is EpgResult.Error -> item { PickerRow(r.message) {} }
            is EpgResult.Ok -> items(
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
