package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.PinPad
import cc.slokker.itv.ui.glass.GlassIconButton
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.SectionLabel
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.settings.SettingsContent
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Painel de vidro encostado à esquerda; canais e grupos trocam no mesmo sítio. */
@Composable
internal fun SidePanel(
    mode: Panel,
    group: String,
    playing: Channel,
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenGroups: () -> Unit,
    onSelectGroup: (String) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    playlistMenu: Boolean,
    onPlaylistMenu: (Boolean) -> Unit,
    epgChannel: Channel?,
    onOpenEpg: (Channel) -> Unit,
    onEpgPlay: (Channel) -> Unit,
    onEpgCatchup: (Channel, EpgEntry) -> Unit,
    onCloseEpg: () -> Unit,
) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    PanelScale(prefs.textScale) {
    val shape = ItvShapes.Panel
    Row {
    Box(
        Modifier
            .padding(start = 32.dp, top = 27.dp, bottom = 27.dp)
            .width(prefs.panelWidth.dp)
            .fillMaxHeight()
            .glass(shape)
            // Recortado pela borda do vidro: o conteúdo a deslizar nunca passa para fora do painel,
            // e as margens ficam por dentro para o brilho do foco ter espaço.
            .clip(shape),
    ) {
        // As duas listas ficam montadas: trocar é só deslizar e fazer fade na GPU, sem reconstruir nada.
        // Lido só na fase de desenho (graphicsLayer): a animação não recompõe as listas.
        val toGroups = animateFloatAsState(
            targetValue = if (mode == Panel.Groups) 1f else 0f,
            animationSpec = tween(PANEL_ANIM_MS, easing = FastOutSlowInEasing),
            label = "panel",
        )
        val content = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp)
        // Grupos entram pela esquerda (estão "antes" dos canais); canais pela direita.
        PanelLayer(visible = { 1f - toGroups.value }, shift = { toGroups.value }, active = mode == Panel.Channels, modifier = content) {
            ChannelList(group, playing, vm, onPlay, onOpenGroups, onClose, onOpenEpg, active = mode == Panel.Channels && epgChannel == null)
        }
        PanelLayer(visible = { toGroups.value }, shift = { toGroups.value - 1f }, active = mode == Panel.Groups, modifier = content) {
            GroupList(group, vm, onSelectGroup, onSettings, playlistMenu, onPlaylistMenu, active = mode == Panel.Groups)
        }
    }
    // → na lista de canais: o EPG do canal com foco, ao lado.
    AnimatedVisibility(
        visible = mode == Panel.Channels && epgChannel != null,
        enter = fadeIn(tween(PANEL_ANIM_MS)) + slideInHorizontally(tween(PANEL_ANIM_MS)) { -it / 6 },
        exit = fadeOut(tween(PANEL_ANIM_MS / 2)),
    ) {
        // Guarda o último canal para a animação de saída não ficar vazia.
        var shown by remember { mutableStateOf(epgChannel) }
        if (epgChannel != null) shown = epgChannel
        shown?.let { ch -> ChannelEpgPanel(ch, vm, onEpgPlay, onEpgCatchup, onCloseEpg) }
    }
    }
    }
}

/**
 * Uma das listas do painel. [visible] 0..1 é a opacidade; [shift] desloca-a em frações de um quarto da largura.
 * A transparência aplica-se traço a traço (sem buffer à parte) e a camada invisível não recebe foco.
 */
@Composable
private fun PanelLayer(visible: () -> Float, shift: () -> Float, active: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .graphicsLayer {
                alpha = visible()
                translationX = shift() * size.width / 4f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .focusProperties { canFocus = active },
    ) {
        content()
    }
}

private const val PANEL_ANIM_MS = 260
private val LIST_LOGO_SIZE = 34.dp

private fun List<EpgEntry>.currentTitle(): String? {
    val now = System.currentTimeMillis()
    return firstOrNull { it.startMs <= now && it.endMs > now }?.title
}

/** Aplica o "Tamanho do texto" das definições só dentro dos painéis. */
@Composable
private fun PanelScale(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale), content = content)
}

@Composable
private fun ChannelList(
    group: String,
    playing: Channel,
    vm: AppViewModel,
    onPlay: (Channel) -> Unit,
    onOpenGroups: () -> Unit,
    onClose: () -> Unit,
    onOpenEpg: (Channel) -> Unit,
    active: Boolean,
) {
    val content by vm.content.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val data = content ?: return
    val channels = remember(data, group, favorites, prefs.sort, prefs.hiddenChannels, prefs.channelOrder) { vm.channelsOf(group) }
    val favoriteKeys = remember(favorites, data) { favorites[data.playlist.id].orEmpty().toSet() }

    val startIndex = remember(channels) { channels.indexOfFirst { it.uid == playing.uid }.coerceAtLeast(0) }
    val state = remember(channels) { LazyListState((startIndex - 3).coerceAtLeast(0)) }
    val initialFocus = remember { FocusRequester() }
    // O canal com foco e o seu programa são estados lidos só dentro das linhas: mudar o foco
    // recompõe as duas linhas envolvidas, não a lista toda (o scroll fica fluido).
    var focusedUid by remember { mutableIntStateOf(-1) }
    val nowTitle = remember { mutableStateOf<String?>(null) }
    val byUid = remember(channels) { channels.associateBy { it.uid } }
    val indexByUid = remember(channels) { channels.withIndex().associate { (i, ch) -> ch.uid to i } }
    val scope = rememberCoroutineScope()

    // Logos das linhas à volta da vista pedidos antes de aparecerem (mesmo tamanho → cache de memória).
    val context = LocalContext.current
    val logoPx = with(LocalDensity.current) { LIST_LOGO_SIZE.roundToPx() }
    LaunchedEffect(channels, prefs.showLogos) {
        if (!prefs.showLogos) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val requested = HashSet<String>()
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { first ->
            val from = (first - 10).coerceAtLeast(0)
            val to = (first + 25).coerceAtMost(channels.lastIndex)
            for (i in from..to) {
                val url = channels[i].logo ?: continue
                if (requested.add(url)) loader.enqueue(ImageRequest.Builder(context).data(url).size(logoPx).build())
            }
        }
    }
    LaunchedEffect(channels, prefs.epgInList) {
        if (!prefs.epgInList) return@LaunchedEffect
        snapshotFlow { focusedUid }.collectLatest { uid ->
            val ch = byUid[uid] ?: return@collectLatest
            nowTitle.value = vm.cachedEpg(ch).currentTitle()
            delay(250) // não pedir EPG de cada canal por onde o foco passa a correr
            nowTitle.value = vm.epgFor(ch).currentTitle()
        }
    }

    // Ao ficar visível, o foco vai para o canal onde estava (se for desta lista), senão o que está a tocar,
    // senão o primeiro; e a lista mostra-o, se ele estiver fora da vista.
    val targetFocus = remember { FocusRequester() }
    var targetUid by remember { mutableIntStateOf(-1) }
    LaunchedEffect(active, channels) {
        if (!active) return@LaunchedEffect
        if (channels.isEmpty()) {
            focusWithRetry(initialFocus)
            return@LaunchedEffect
        }
        val uid = when {
            focusedUid in byUid -> focusedUid
            playing.uid in byUid -> playing.uid
            else -> channels.first().uid
        }
        targetUid = uid
        val index = channels.indexOfFirst { it.uid == uid }
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem((index - 3).coerceAtLeast(0))
        focusWithRetry(targetFocus)
    }

    Column(
        Modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (val code = e.key.nativeKeyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onOpenGroups(); true }
                // → abre o EPG do canal com foco (sem canal, fecha o painel como antes).
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    byUid[focusedUid]?.let(onOpenEpg) ?: onClose()
                    true
                }
                // Dá a volta como um relógio: ↑ no primeiro vai para o último, ↓ no último para o primeiro.
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    val index = indexByUid[focusedUid] ?: return@onPreviewKeyEvent false
                    val wrapTo = when {
                        channels.size < 2 -> null
                        code == AndroidKeyEvent.KEYCODE_DPAD_UP && index == 0 -> channels.lastIndex
                        code == AndroidKeyEvent.KEYCODE_DPAD_DOWN && index == channels.lastIndex -> 0
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    targetUid = channels[wrapTo].uid
                    scope.launch {
                        state.scrollToItem((wrapTo - 3).coerceAtLeast(0))
                        focusWithRetry(targetFocus)
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        PanelHeader(
            title = data.categoryName(group),
            subtitle = LocalStrings.current.channelsCount(channels.size, data.playlist.name),
            hint = LocalStrings.current.groupsHint,
        )
        if (channels.isEmpty()) {
            Text(
                if (group == SpecialCategory.FAVORITES) LocalStrings.current.noFavoritesYet else LocalStrings.current.noChannelsInGroup,
                color = ItvColors.TextDim,
                modifier = Modifier.padding(14.dp),
            )
            GlassRow(onClick = onOpenGroups, title = LocalStrings.current.seeGroups, modifier = Modifier.focusRequester(initialFocus))
            return@Column
        }
        LazyColumn(
            state = state,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(channels, key = { it.uid }) { ch ->
                val isPlaying = ch.uid == playing.uid
                val isFocused by remember { derivedStateOf { focusedUid == ch.uid } }
                GlassRow(
                    onClick = { onPlay(ch) },
                    onLongClick = { vm.toggleFavorite(ch) },
                    selected = isPlaying,
                    // Só contorno no foco: os logótipos (muitos brancos sobre transparente) não precisam de placa.
                    outlineFocus = true,
                    title = ch.name,
                    subtitle = when {
                        isFocused && nowTitle.value != null -> nowTitle.value
                        isPlaying -> LocalStrings.current.watchingNow
                        else -> null
                    },
                    leading = if (prefs.showNumbers || prefs.showLogos) {
                        {
                            if (prefs.showNumbers) {
                                Text(
                                    ch.num.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalContentColor.current.copy(alpha = 0.75f),
                                    modifier = Modifier.width(32.dp),
                                )
                            }
                            if (prefs.showLogos) ChannelLogo(ch, LIST_LOGO_SIZE)
                            Spacer(Modifier.width(10.dp))
                        }
                    } else {
                        null
                    },
                    trailing = if (ch.key in favoriteKeys || isPlaying) {
                        {
                            if (ch.key in favoriteKeys) Text("★", color = if (isPlaying) LocalAccent.current.start else LocalContentColor.current)
                            if (isPlaying) LiveDot()
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .then(if (ch.uid == targetUid) Modifier.focusRequester(targetFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedUid = ch.uid }
                        .onPreviewKeyEvent { e ->
                            // Botão Menu = favorito (alternativa a manter OK).
                            val isMenu = e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_MENU
                            if (isMenu && e.type == KeyEventType.KeyUp) vm.toggleFavorite(ch)
                            isMenu
                        },
                )
            }
        }
    }
}

@Composable
private fun GroupList(
    selected: String,
    vm: AppViewModel,
    onSelectGroup: (String) -> Unit,
    onSettings: () -> Unit,
    playlistMenu: Boolean,
    onPlaylistMenu: (Boolean) -> Unit,
    active: Boolean,
) {
    val content by vm.content.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val data = content ?: return
    val s = LocalStrings.current
    val recentCount = remember(data, recents, prefs.hiddenGroups, prefs.hiddenChannels, prefs.lockedGroups, unlocked) { vm.channelsOf(SpecialCategory.RECENT).size }
    // Grupos pela ordem escolhida e sem os escondidos (Definições → Aspeto → Grupos).
    val groups = remember(data, s, prefs.groupOrder, prefs.hiddenGroups, prefs.showFavoritesGroup, prefs.showAllGroup, prefs.showRecentGroup, recentCount > 0) {
        listOfNotNull(
            Category(SpecialCategory.FAVORITES, s.favoritesStar).takeIf { prefs.showFavoritesGroup },
            Category(SpecialCategory.RECENT, s.recents).takeIf { prefs.showRecentGroup && recentCount > 0 },
            Category(SpecialCategory.ALL, s.all).takeIf { prefs.showAllGroup },
        ) + vm.orderedGroups(includeHidden = false).map { if (it.id == PlaylistContent.OTHERS) it.copy(name = s.others) else it }
    }
    val favoriteCount = remember(data, favorites, prefs.hiddenGroups, prefs.hiddenChannels, prefs.lockedGroups) { vm.channelsOf(SpecialCategory.FAVORITES).size }
    // "Todos" sem os grupos escondidos ou bloqueados.
    val allCount = remember(data, prefs.hiddenGroups, prefs.hiddenChannels, prefs.lockedGroups, unlocked) { vm.channelsOf(SpecialCategory.ALL).size }
    val initialFocus = remember { FocusRequester() }
    var focusedGroup by remember { mutableStateOf<String?>(null) }
    val selectedIndex = groups.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val state = remember { LazyListState((selectedIndex - 3).coerceAtLeast(0)) }
    val pillFocus = remember { FocusRequester() }

    // Ao ficar visível: foco no grupo da lista de canais de onde se veio (trazido para a vista se preciso).
    LaunchedEffect(active, selectedIndex) {
        if (!active) return@LaunchedEffect
        if (state.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            state.scrollToItem((selectedIndex - 3).coerceAtLeast(0))
        }
        focusWithRetry(initialFocus)
    }
    // Ao fechar o menu de playlists, o foco volta ao botão.
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(playlistMenu) {
        if (menuWasOpen && !playlistMenu) focusWithRetry(pillFocus)
        menuWasOpen = playlistMenu
    }

    Box {
    Column(
        Modifier.onPreviewKeyEvent { e ->
            // Nos botões do cabeçalho (⇄, ⚙) as setas só mudam o foco.
            val group = focusedGroup
            if (e.type != KeyEventType.KeyDown || playlistMenu || group == null) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    onSelectGroup(group)
                    true
                }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        },
    ) {
        PanelHeader(title = s.groups, subtitle = data.playlist.name) {
            if (playlists.size > 1) {
                GlassIconButton(
                    ItvIcons.SwapPlaylist,
                    contentDescription = s.playlists,
                    onClick = { onPlaylistMenu(true) },
                    enabled = !playlistMenu,
                    modifier = Modifier
                        .focusRequester(pillFocus)
                        .onFocusChanged { if (it.isFocused) focusedGroup = null },
                )
                Spacer(Modifier.width(8.dp))
            }
            GlassIconButton(
                ItvIcons.Settings,
                contentDescription = s.settings,
                onClick = onSettings,
                enabled = !playlistMenu,
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedGroup = null },
            )
        }
        LazyColumn(
            state = state,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(groups, key = { _, g -> "g:" + g.id }) { index, g ->
                val count = when (g.id) {
                    SpecialCategory.FAVORITES -> favoriteCount
                    SpecialCategory.RECENT -> recentCount
                    SpecialCategory.ALL -> allCount
                    else -> data.byCategory[g.id]?.count { !prefs.isChannelHidden(data.playlist.id, it.key) } ?: 0
                }
                val locked = prefs.isLocked(data.playlist.id, g.id)
                GlassRow(
                    onClick = { onSelectGroup(g.id) },
                    selected = g.id == selected,
                    enabled = !playlistMenu,
                    title = g.name,
                    trailing = {
                        if (locked) {
                            Text(
                                if ("${data.playlist.id}:${g.id}" in unlocked) "🔓 " else "🔒 ",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
                    },
                    modifier = Modifier
                        .then(if (index == selectedIndex) Modifier.focusRequester(initialFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedGroup = g.id },
                )
            }
        }
    }
        if (playlistMenu) {
            PlaylistMenu(
                playlists = playlists,
                current = data.playlist.id,
                onPick = { p ->
                    onPlaylistMenu(false)
                    vm.switchPlaylist(p)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 4.dp),
            )
        }
    }
}

/** Menu de playlists por baixo do botão ⇄ do cabeçalho. */
@Composable
private fun PlaylistMenu(playlists: List<Playlist>, current: String, onPick: (Playlist) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(focus) }
    Column(
        modifier
            .width(240.dp)
            .glass(ItvShapes.Card, tint = Color(0xD90A0D16), fallbackTint = Color(0xF0111522))
            .padding(6.dp),
    ) {
        playlists.forEach { p ->
            GlassRow(
                onClick = { onPick(p) },
                selected = p.id == current,
                title = p.name,
                subtitle = if (p.type == PlaylistType.XTREAM) "Xtream" else "M3U",
                trailing = if (p.id == current) { { Text("✓", color = LocalAccent.current.start) } } else null,
                modifier = if (p.id == current) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

/** Cabeçalho do painel; à direita, uma dica ([hint]) ou uma ação ([action]). */
@Composable
private fun PanelHeader(title: String, subtitle: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ItvColors.TextDim, maxLines = 1)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = ItvColors.TextFaint)
        action?.invoke()
    }
}

/** Ponto "em direto" do canal a ver. */
@Composable
private fun LiveDot() {
    Box(
        Modifier
            .padding(start = 8.dp)
            .width(8.dp)
            .height(8.dp)
            .glass(RoundedCornerShape(50), tint = LocalAccent.current.start, fallbackTint = LocalAccent.current.start),
    )
}

/** Painel de definições à direita, por cima do vídeo. */
@Composable
internal fun SettingsSheet(vm: AppViewModel, player: Player, playing: Channel, onClose: () -> Unit, startAtEpg: Boolean = false) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    PanelScale(prefs.textScale) {
    Box(
        Modifier
            .padding(end = 32.dp, top = 27.dp, bottom = 27.dp)
            .width((prefs.panelWidth + 40).dp)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        SettingsContent(vm, player, playing, onClose, startAtEpgFor = playing.takeIf { startAtEpg })
    }
    }
}

/** Pede o PIN para abrir um grupo bloqueado. */
@Composable
internal fun PinDialog(title: String, error: String?, onDone: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(420.dp)
            .glass(ItvShapes.Panel)
            .padding(24.dp),
    ) {
        PinPad(title = title, message = error, onDone = onDone, modifier = Modifier.fillMaxWidth())
    }
}

/** Pede foco durante alguns frames, até o elemento existir (listas e animações ainda a compor). */
internal suspend fun focusWithRetry(requester: FocusRequester) {
    repeat(10) {
        withFrameNanos { }
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return
    }
}
