package cc.slokker.itv.ui.settings

import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.data.ChannelMatch
import cc.slokker.itv.ui.player.NetVerdictCard
import cc.slokker.itv.ui.player.BackupChannelBody
import cc.slokker.itv.ui.player.BackupAddBody
import cc.slokker.itv.ui.player.rememberBackupPanel
import cc.slokker.itv.ui.player.BackupChannelsPage
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.kind
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.PinPad
import cc.slokker.itv.ui.common.onBackKey
import cc.slokker.itv.ui.glass.AuroraBackground
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.theme.ItvColors

/** Definições em ecrã próprio (quando não há player, ex.: a lista não carregou). */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    AuroraBackground {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(460.dp)
                .fillMaxHeight()
                .glass(ItvShapes.Panel)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            SettingsContent(vm, player = null, playing = null, onClose = vm::closeSettings, startAtNewPlaylist = vm.settingsStartAtNewPlaylist)
        }
    }
}

/** Páginas das definições. */
internal sealed interface Route {
    fun title(s: Strings): String

    data object Root : Route { override fun title(s: Strings) = s.settings }
    data object Current : Route { override fun title(s: Strings) = s.currentChannel }
    data object Audio : Route { override fun title(s: Strings) = s.audio }
    data object Subtitles : Route { override fun title(s: Strings) = s.subtitles }
    data object StreamInfo : Route { override fun title(s: Strings) = s.streamInfo }
    data object Connection : Route { override fun title(s: Strings) = s.connection }
    data object General : Route { override fun title(s: Strings) = s.general }
    data object Playback : Route { override fun title(s: Strings) = s.playback }
    data object Licenses : Route { override fun title(s: Strings) = s.licenses }
    data object GroupsManage : Route { override fun title(s: Strings) = s.manageGroups }
    data object GroupsOrder : Route { override fun title(s: Strings) = s.reorderGroups }
    /** Canais da lista em uso: primeiro escolhe-se o grupo. */
    data object ChannelGroups : Route { override fun title(s: Strings) = s.manageChannels }
    class ChannelsManage(val groupId: String, private val name: String) : Route { override fun title(s: Strings) = name }
    class ChannelsOrder(val groupId: String, private val name: String) : Route { override fun title(s: Strings) = name }
    data object Zapping : Route { override fun title(s: Strings) = s.zapping }
    data object Appearance : Route { override fun title(s: Strings) = s.appearance }
    data object Guide : Route { override fun title(s: Strings) = s.guide }
    data object ParentalGate : Route { override fun title(s: Strings) = s.parental }
    data object LogoutGate : Route { override fun title(s: Strings) = s.logoutThisTv }
    data object SettingsGate : Route { override fun title(s: Strings) = s.settings }
    data object Parental : Route { override fun title(s: Strings) = s.parental }
    data object NewPin : Route { override fun title(s: Strings) = s.newPin }
    data object LockedGroups : Route { override fun title(s: Strings) = s.lockedGroups }
    data object Lists : Route { override fun title(s: Strings) = s.playlists }
    class PlaylistDetail(val id: String, private val name: String) : Route { override fun title(s: Strings) = name }
    /** Os canais da lista [playlistId] na reserva: cada um, o da reserva e o estado (ver BackupChannelsPage). */
    class BackupChannels(val playlistId: String) : Route {
        override fun title(s: Strings) = s.backupChannels

        /** O filtro e a pesquisa ficam na entrada (sobrevivem a abrir um canal ou o teclado e voltar). */
        val filter = mutableStateOf(ChannelMatch.Filter.ALL)
        val query = mutableStateOf("")
    }
    /** Escolher o canal da reserva de [channel] (da lista [playlistId]), como no painel do player. */
    class BackupChannelPick(val playlistId: String, val channel: Channel) : Route { override fun title(s: Strings) = channel.name }
    /** "Juntar um canal" aos failovers de [channel] (ver BackupAddBody). */
    class BackupChannelAdd(val playlistId: String, val channel: Channel) : Route { override fun title(s: Strings) = s.backupAddChannel }
    /**
     * Escolher o guia de um canal (só com conta): pesquisa pelo nome → país → canal de guia.
     * Com [logo], escolhe-se da mesma maneira o logótipo do canal (o do canal de guia), sem mexer no guia.
     */
    class EpgPick(val channel: Channel, val logo: Boolean = false) : Route {
        /** O nome a procurar (começa pelo do canal; "Procurar outro nome" muda-o). */
        val query = mutableStateOf(channel.name)
        override fun title(s: Strings) = if (logo) s.channelLogo else s.epgGuideChannel
    }

    /** Canais de guia de um país: os da pesquisa ([matches]), ou todos (null). [logo] como em [EpgPick]. */
    class EpgCountry(
        val channel: Channel,
        val country: String,
        private val name: String,
        val matches: List<EpgGuideChannel>?,
        val logo: Boolean = false,
    ) : Route {
        override fun title(s: Strings) = name
    }

    /** De onde vêm os logótipos de todos os canais: a lista, o guia de cada canal, ou um país. */
    data object LogoSource : Route { override fun title(s: Strings) = s.logoSource }

    /** A lista inteira com o guia de um país (só com conta, lista em uso). */
    data object EpgCountries : Route { override fun title(s: Strings) = s.epgCountryGuide }

    /** Lista nova, primeiro passo: Xtream Codes ou lista M3U (só sem conta). */
    data object NewPlaylistType : Route { override fun title(s: Strings) = s.newPlaylist }
    /** Criar ([id] null) ou editar uma lista desta TV (só sem conta). */
    class PlaylistEdit(val id: String?, val form: MutableState<PlaylistForm>) : Route {
        /** A gravar na conta (para não gravar duas vezes com dois OK seguidos). */
        val saving = mutableStateOf(false)

        override fun title(s: Strings) = if (id == null) s.newPlaylist else s.editPlaylist
    }

    /** Escrever um texto com o teclado da TV. */
    class TextEdit(
        private val label: String,
        val initial: String,
        val kind: TextKind,
        /** O texto do botão (por omissão, Gravar). */
        val action: String? = null,
        val onDone: (String) -> Unit,
    ) : Route {
        override fun title(s: Strings) = label
    }
    data object Account : Route { override fun title(s: Strings) = s.account }

    /** Escolha de um valor de uma lista (com ✓ no atual); [swatches] mostra uma amostra de cor por opção. */
    class Pick(
        private val label: String,
        val options: List<Pair<Any?, String>>,
        val selected: Any?,
        val swatches: Map<Any?, Brush> = emptyMap(),
        val onPick: (Any?) -> Unit,
    ) : Route {
        override fun title(s: Strings) = label
    }
}

internal enum class TextKind { PLAIN, URL, PASSWORD }

private class Entry(val route: Route, var returnKey: String? = null)

/**
 * Definições com navegação em páginas. Voltar recua uma página; na primeira chama [onClose].
 * [player] e [playing] só existem dentro do player (página "Canal atual").
 * [startAtNewPlaylist]: abre já no formulário da primeira lista (sem conta).
 * [startAtEpgFor]: abre já na escolha do guia desse canal (botão do HUD); ao escolher, fecha.
 * [startAtConnection]: abre já na página Ligação (botão "Detalhes" do aviso de paragens).
 */
@Composable
fun SettingsContent(
    vm: AppViewModel,
    player: Player?,
    playing: Channel?,
    onClose: () -> Unit,
    startAtNewPlaylist: Boolean = false,
    startAtEpgFor: Channel? = null,
    startAtConnection: Boolean = false,
    /** Uma faixa escolhida à mão (Canal atual → Áudio/Legendas): ganha à língua preferida até mudar de canal. */
    onManualTrack: (Int) -> Unit = {},
) {
    // Com "Proteger Definições", pede o PIN antes de mostrar qualquer página.
    var stack by remember {
        val first = when {
            // O PIN das Definições vem primeiro, também para adicionar uma lista a partir do player.
            vm.settingsNeedPin() -> Route.SettingsGate
            startAtNewPlaylist -> Route.NewPlaylistType
            startAtEpgFor != null -> Route.EpgPick(startAtEpgFor)
            startAtConnection -> Route.Connection
            else -> Route.Root
        }
        mutableStateOf(listOf(Entry(first)))
    }

    fun push(route: Route, fromKey: String) {
        stack.last().returnKey = fromKey
        stack = stack + Entry(route)
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1) else onClose()
    }

    fun replace(route: Route) {
        stack = stack.dropLast(1) + Entry(route)
    }

    /** Volta à página "Canal atual" (depois de escolher o guia do canal); aberto pelo HUD, fecha. */
    fun popToCurrent() {
        val i = stack.indexOfLast { it.route == Route.Current }
        if (i >= 0) stack = stack.take(i + 1) else onClose()
    }

    /** Recua uma página, mas nunca fecha as Definições (quem fecha é o ViewModel, ex.: ao abrir a lista nova). */
    fun popNested() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler { pop() }

    AnimatedContent(
        targetState = stack,
        modifier = Modifier.onBackKey {
            pop()
            true
        },
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        contentKey = { stack -> stack.size.toString() + stack.last().route.javaClass.name },
        label = "settings",
    ) { s ->
        val entry = s.last()
        // A página que está a sair não pode ficar com o foco (senão ele salta ao terminar a animação).
        val leaving = transition.targetState == EnterExitState.PostExit
        Column(Modifier.fillMaxSize().focusProperties { canFocus = !leaving }) {
            // A página de uma lista mostra o nome atual (pode ter mudado aqui, no site ou noutra TV).
            val playlistsNow by vm.playlists.collectAsStateWithLifecycle()
            val title = (entry.route as? Route.PlaylistDetail)?.let { r -> playlistsNow.firstOrNull { it.id == r.id }?.name }
                ?: entry.route.title(LocalStrings.current)
            // Nas subpáginas, de onde se veio ("‹ Definições") por cima do título.
            val parent = s.getOrNull(s.lastIndex - 1)?.route
            if (parent != null) {
                Row(Modifier.padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ItvIcons.Back, contentDescription = null, modifier = Modifier.size(18.dp), tint = ItvColors.TextDim)
                    Text(parent.title(LocalStrings.current), style = MaterialTheme.typography.labelMedium, color = ItvColors.TextDim)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp, top = if (parent != null) 0.dp else 2.dp, bottom = 6.dp),
            )
            Box(Modifier.weight(1f)) {
            when (entry.route) {
                Route.ParentalGate -> PinPad(
                    title = LocalStrings.current.enterPin,
                    message = null,
                    onDone = { pin -> if (vm.checkPin(pin)) replace(Route.Parental) else vm.showToast(vm.pinRejected()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    enabled = !leaving,
                )
                Route.SettingsGate -> PinPad(
                    title = LocalStrings.current.enterPin,
                    message = null,
                    onDone = { pin -> if (vm.unlockSettings(pin)) replace(Route.Root) else vm.showToast(vm.pinRejected()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    enabled = !leaving,
                )
                Route.LogoutGate -> PinPad(
                    title = LocalStrings.current.enterPin,
                    message = null,
                    onDone = { pin -> if (vm.checkPin(pin)) vm.logoutThisTv() else vm.showToast(vm.pinRejected()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    enabled = !leaving,
                )
                Route.NewPin -> PinPad(
                    title = LocalStrings.current.choosePin,
                    message = null,
                    onDone = { pin ->
                        vm.setPin(pin)
                        vm.showToast(I18n.s.pinSet)
                        pop()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    enabled = !leaving,
                )
                Route.GroupsOrder -> GroupsOrderPage(vm, enabled = !leaving)
                is Route.BackupChannels -> BackupChannelsPage(
                    vm, entry.route.playlistId, enabled = !leaving, filterState = entry.route.filter, query = entry.route.query.value,
                    onSearch = {
                        val query = entry.route.query
                        push(Route.TextEdit(I18n.s.search, query.value, TextKind.PLAIN) { v -> query.value = v.trim() }, "bcsearch")
                    },
                ) { channel ->
                    push(Route.BackupChannelPick(entry.route.playlistId, channel), "bc:" + channel.key)
                }
                is Route.BackupChannelPick -> BackupChannelBody(vm, rememberBackupPanel(vm, entry.route.channel, entry.route.playlistId), onTry = null) {
                    push(Route.BackupChannelAdd(entry.route.playlistId, entry.route.channel), "bcadd")
                }
                is Route.BackupChannelAdd -> BackupAddBody(vm, rememberBackupPanel(vm, entry.route.channel, entry.route.playlistId)) { pop() }
                is Route.ChannelsOrder -> ChannelsOrderPage(vm, entry.route.groupId, enabled = !leaving)
                is Route.TextEdit -> TextEditPage(entry.route, enabled = !leaving) { text ->
                    entry.route.onDone(text)
                    pop()
                }
                else -> {
                    val items = settingItems(entry.route, vm, player, playing, onManualTrack, ::push, ::pop, ::popNested, ::replace, ::popToCurrent)
                    Column {
                        // Ligação: o veredicto fica fixo por cima da lista (dentro dela, a lista deslizava até à
                        // primeira linha com foco e cortava-o).
                        if (entry.route == Route.Connection) {
                            val net by vm.net.state.collectAsStateWithLifecycle()
                            NetVerdictCard(net, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp))
                        }
                        // Páginas que carregam da rede: quando chegam os itens, a lista recomeça com o foco no primeiro.
                        key(items.any { it.key == LOADING_KEY }) {
                            ItemList(items = items, focusKey = entry.returnKey, enabled = !leaving)
                        }
                    }
                }
            }
            }
            Text(
                "GoatTV ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = ItvColors.TextFaint,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            )
        }
    }
}
