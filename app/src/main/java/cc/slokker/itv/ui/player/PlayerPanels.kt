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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawOutline
import cc.slokker.itv.ui.theme.GoatPalette
import cc.slokker.itv.ui.glass.panelListState
import cc.slokker.itv.ui.glass.slidingFocus
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import cc.slokker.itv.data.kind
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.LocalChannelLogos
import cc.slokker.itv.ui.common.rememberNow
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
import cc.slokker.itv.ui.theme.tabular
import cc.slokker.itv.ui.common.focusWithRetry
import cc.slokker.itv.data.at
import cc.slokker.itv.data.Prefs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow

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
    onRecordings: () -> Unit,
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
    // Estilo Sombra: as listas ficam sem painel por cima da sombra do ecrã (ver PlayerScreen), com o texto sombreado.
    val shadow = prefs.panelStyle != Prefs.PanelStyles.GLASS
    ShadowText(shadow) {
    val shape = ItvShapes.Panel
    val panel = if (shadow) Modifier else Modifier.glass(shape)
    // Os grupos abrem num painel próprio, ao lado dos canais, que ficam atrás esbatidos. Os dois ficam montados:
    // abrir é só animar largura e opacidade (lidas no desenho), sem reconstruir as listas.
    val toGroups = animateFloatAsState(
        targetValue = if (mode == Panel.Groups) 1f else 0f,
        animationSpec = tween(PANEL_ANIM_MS, easing = FastOutSlowInEasing),
        label = "panel",
    )
    val content = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp)
    val groupsWidth = (prefs.panelWidth * 0.85f).dp
    // Com os grupos abertos, a lista de canais (escondida) acompanha o grupo com foco (→ abre-o já pronto).
    var preview by remember(group) { mutableStateOf(group) }
    val shownGroup = if (mode == Panel.Groups) preview else group
    Row(Modifier.padding(start = 32.dp, top = 27.dp, bottom = 27.dp)) {
    Box(
        Modifier
            .fillMaxHeight()
            .layout { measurable, constraints ->
                // Largura a crescer com a animação; o conteúdo é sempre medido à largura toda (não recompõe).
                val full = groupsWidth.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = full, maxWidth = full))
                val w = (full * toGroups.value).toInt()
                layout(w, placeable.height) { if (w > 0) placeable.placeRelative(w - full, 0) }
            }
            .padding(end = 12.dp)
            .then(panel)
            .clip(shape)
            .focusProperties { canFocus = mode == Panel.Groups },
    ) {
        Box(content.graphicsLayer { alpha = toGroups.value }) {
            GroupList(group, vm, onSelectGroup, { preview = it }, onSettings, onRecordings, playlistMenu, onPlaylistMenu, active = mode == Panel.Groups)
        }
    }
    Box(
        Modifier
            .width(prefs.panelWidth.dp)
            .fillMaxHeight()
            // Com os grupos abertos, os canais saem (só os grupos no ecrã).
            .graphicsLayer { alpha = 1f - toGroups.value }
            .then(panel)
            // Recortado pela borda do vidro: o conteúdo a deslizar nunca passa para fora do painel,
            // e as margens ficam por dentro para o brilho do foco ter espaço.
            .clip(shape)
            .focusProperties { canFocus = mode == Panel.Channels },
    ) {
        Box(content) {
            ChannelList(shownGroup, playing, vm, onPlay, onOpenGroups, onClose, onOpenEpg, active = mode == Panel.Channels && epgChannel == null)
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
}

/** O fundo da linha com foco no estilo Sombra. */
private val ShadowFocus = Color.White.copy(alpha = 0.22f)

/** No estilo Sombra o texto fica por cima do vídeo: uma sombra leve por baixo das letras mantém-no legível. */
@Composable
private fun ShadowText(on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    val t = MaterialTheme.typography
    val typography = remember(t) {
        val s = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1.5f), 4f)
        t.copy(
            titleLarge = t.titleLarge.copy(shadow = s),
            titleSmall = t.titleSmall.copy(shadow = s),
            bodySmall = t.bodySmall.copy(shadow = s),
            labelMedium = t.labelMedium.copy(shadow = s),
        )
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = typography, content = content)
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

private fun List<EpgEntry>.currentTitle(): String? = current()?.title

private fun List<EpgEntry>.current(): EpgEntry? {
    return at(System.currentTimeMillis())
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

    val shadow = prefs.panelStyle != Prefs.PanelStyles.GLASS
    // Seletor branco: cada linha pinta a sua placa branca com o texto escuro (não há destaque a deslizar).
    val white = prefs.panelStyle == Prefs.PanelStyles.SHADOW_WHITE
    val startIndex = remember(channels) { channels.indexOfFirst { it.uid == playing.uid }.coerceAtLeast(0) }
    val state = remember(channels) { panelListState((startIndex - 3).coerceAtLeast(0)) }
    val initialFocus = remember { FocusRequester() }
    // O canal com foco é um estado lido só dentro das linhas: mudar o foco recompõe as duas linhas
    // envolvidas, não a lista toda (o scroll fica fluido).
    var focusedUid by remember { mutableIntStateOf(-1) }
    // O programa de cada linha vem do guia já em memória; volta a ler-se quando chega guia novo e de minuto a minuto.
    val epgVersion by vm.epgVersion.collectAsStateWithLifecycle()
    val minute = rememberNow() / 60_000
    val byUid = remember(channels) { channels.associateBy { it.uid } }
    val indexByUid = remember(channels) { channels.withIndex().associate { (i, ch) -> ch.uid to i } }
    val scope = rememberCoroutineScope()

    // Logos das linhas à volta da vista pedidos antes de aparecerem (mesmo tamanho → cache de memória).
    val context = LocalContext.current
    val logoPx = with(LocalDensity.current) { LIST_LOGO_SIZE.roundToPx() }
    // O logótipo que a linha vai mesmo mostrar: o do guia/país/escolhido, se houver, senão o da lista.
    val logoOverrides = LocalChannelLogos.current
    LaunchedEffect(channels, prefs.showLogos, logoOverrides) {
        if (!prefs.showLogos) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val requested = HashSet<String>()
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { first ->
            val from = (first - 10).coerceAtLeast(0)
            val to = (first + 25).coerceAtMost(channels.lastIndex)
            for (i in from..to) {
                val url = logoOverrides[channels[i].key] ?: channels[i].logo ?: continue
                if (requested.add(url)) loader.enqueue(ImageRequest.Builder(context).data(url).size(logoPx).build())
            }
        }
    }
    LaunchedEffect(channels, prefs.epgInList) {
        if (!prefs.epgInList) return@LaunchedEffect
        // Canais sem guia em memória (ex.: Xtream sem XMLTV): pede-se o EPG curto do que fica com foco; quando
        // chega, o epgVersion muda e a linha mostra-o.
        snapshotFlow { focusedUid }.collectLatest { uid ->
            val ch = byUid[uid] ?: return@collectLatest
            if (vm.cachedEpg(ch).currentTitle() != null) return@collectLatest
            delay(250) // não pedir EPG de cada canal por onde o foco passa a correr
            vm.epgFor(ch)
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
            // No estilo Sombra o cabeçalho é só o nome do grupo.
            subtitle = if (shadow) null else LocalStrings.current.channelsCount(channels.size, data.playlist.name),
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
            modifier = if (white) Modifier else Modifier.slidingFocus(state, fill = ShadowFocus.takeIf { shadow }) { focusedUid },
        ) {
            items(channels, key = { it.uid }) { ch ->
                val isPlaying = ch.uid == playing.uid
                val programme = if (prefs.epgInList) remember(ch.key, epgVersion, minute) { vm.cachedEpg(ch).current() } else null
                GlassRow(
                    onClick = { onPlay(ch) },
                    onLongClick = { vm.toggleFavorite(ch) },
                    selected = isPlaying && !white,
                    // Só contorno no foco: os logótipos (muitos brancos sobre transparente) não precisam de placa.
                    outlineFocus = !white,
                    slidingFocus = !white,
                    growOnFocus = false,
                    title = ch.name,
                    // Todas as linhas com a mesma altura: o programa numa linha (anda para o lado na que tem foco),
                    // ou "Sem informação" sem guia. Assim mudar o foco não mexe no layout da lista.
                    subtitle = when {
                        programme != null -> programme.title
                        isPlaying -> LocalStrings.current.watchingNow
                        prefs.epgInList -> LocalStrings.current.noGuideInfo
                        else -> null
                    },
                    subtitleLines = 1,
                    // Barra do programa (o espaço fica reservado sem guia, para as linhas terem todas a mesma altura).
                    progress = programme?.let { e -> ((minute * 60_000 - e.startMs).toFloat() / (e.endMs - e.startMs)).coerceIn(0f, 1f) },
                    reserveProgress = prefs.epgInList,
                    leading = if (prefs.showNumbers || prefs.showLogos) {
                        {
                            if (prefs.showNumbers) {
                                Text(
                                    ch.num.toString(),
                                    style = MaterialTheme.typography.labelMedium.tabular,
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
                            if (ch.key in favoriteKeys) Text("★", color = GoatPalette.Warning)
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
    onFocusGroup: (String) -> Unit,
    onSettings: () -> Unit,
    onRecordings: () -> Unit,
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
    // Só depois de o foco parar um instante: a correr pelos grupos não se reconstrói a lista de canais a cada tecla.
    LaunchedEffect(Unit) {
        snapshotFlow { focusedGroup }.collectLatest { id ->
            if (id == null) return@collectLatest
            delay(GROUP_PREVIEW_MS)
            onFocusGroup(id)
        }
    }
    // Estilo Sombra: o foco é o mesmo fundo claro dos canais e o grupo atual não leva fundo (pareceria um segundo foco).
    val soft = prefs.panelStyle == Prefs.PanelStyles.SHADOW
    val selectedIndex = groups.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val state = remember { panelListState((selectedIndex - 3).coerceAtLeast(0)) }
    val pillFocus = remember { FocusRequester() }
    // ↑ ↓ andam de grupo em grupo por aqui (como na lista de canais), não pela procura de foco do Compose, que
    // à volta dos botões do cabeçalho às vezes deixava o foco perdido fora da lista.
    val rowFocus = remember(groups.size) { List(groups.size) { FocusRequester() } }
    val scope = rememberCoroutineScope()
    fun focusRow(index: Int) {
        if (runCatching { rowFocus[index].requestFocus() }.getOrDefault(false)) return
        scope.launch {
            state.scrollToItem((index - 3).coerceAtLeast(0))
            focusWithRetry(rowFocus[index])
        }
    }

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
            if (e.type != KeyEventType.KeyDown || playlistMenu) return@onPreviewKeyEvent false
            val group = focusedGroup
            val code = e.key.nativeKeyCode
            // Nos botões do cabeçalho (⇄, ⚙): ↓ volta ao primeiro grupo; as outras setas só mudam o foco.
            if (group == null) {
                if (code != AndroidKeyEvent.KEYCODE_DPAD_DOWN || groups.isEmpty()) return@onPreviewKeyEvent false
                focusRow(0)
                return@onPreviewKeyEvent true
            }
            when (code) {
                // ↑ no primeiro grupo sobe para os botões do cabeçalho; ↓ no último fica.
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    val index = groups.indexOfFirst { it.id == group }
                    if (index < 0) return@onPreviewKeyEvent false
                    val up = code == AndroidKeyEvent.KEYCODE_DPAD_UP
                    if (up && index == 0) return@onPreviewKeyEvent false
                    val to = if (up) index - 1 else index + 1
                    if (to <= groups.lastIndex) focusRow(to)
                    true
                }
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
            // As gravações, ao lado das Definições (como o ⇄ das listas).
            GlassIconButton(
                ItvIcons.Recordings,
                contentDescription = s.recordings,
                onClick = onRecordings,
                enabled = !playlistMenu,
                modifier = Modifier.onFocusChanged { if (it.isFocused) focusedGroup = null },
            )
            Spacer(Modifier.width(8.dp))
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
            modifier = Modifier.slidingFocus(state, plate = !soft, fill = ShadowFocus.takeIf { soft }) { focusedGroup?.let { "g:$it" } },
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
                    selected = g.id == selected && !soft,
                    enabled = !playlistMenu,
                    slidingFocus = true,
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
                        .focusRequester(rowFocus[index])
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
                subtitle = p.type.kind.label,
                trailing = if (p.id == current) { { Text("✓", color = LocalAccent.current.start) } } else null,
                modifier = if (p.id == current) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

/** Cabeçalho do painel; à direita, uma dica ([hint]) ou uma ação ([action]). */
@Composable
private fun PanelHeader(title: String, subtitle: String?, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ItvColors.TextDim, maxLines = 1)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = ItvColors.TextFaint)
        action?.invoke()
    }
}

private const val GROUP_PREVIEW_MS = 400L


/** Ponto "em direto" do canal a ver. */
@Composable
private fun LiveDot() {
    Box(
        Modifier
            .padding(start = 8.dp)
            .width(8.dp)
            .height(8.dp)
            .glass(RoundedCornerShape(50), tint = GoatPalette.Live, fallbackTint = GoatPalette.Live),
    )
}

/** Painel de definições à direita, por cima do vídeo. */
@Composable
internal fun SettingsSheet(
    vm: AppViewModel,
    player: Player,
    playing: Channel,
    onClose: () -> Unit,
    startAtEpg: Boolean = false,
    startAtConnection: Boolean = false,
    onManualTrack: (Int) -> Unit = {},
) {
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
        SettingsContent(vm, player, playing, onClose, startAtEpgFor = playing.takeIf { startAtEpg }, startAtConnection = startAtConnection, onManualTrack = onManualTrack)
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
