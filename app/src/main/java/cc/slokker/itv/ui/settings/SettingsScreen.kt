package cc.slokker.itv.ui.settings
import android.net.Uri
import java.util.Locale
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.theme.outlineBrush
import cc.slokker.itv.ui.ReportState
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.player.channelsLabel
import cc.slokker.itv.ui.player.codecName
import cc.slokker.itv.ui.player.NetVerdict
import cc.slokker.itv.ui.player.NetVerdictCard
import cc.slokker.itv.ui.player.icon
import cc.slokker.itv.ui.player.mbps
import cc.slokker.itv.ui.player.netLink
import cc.slokker.itv.ui.player.netShort
import cc.slokker.itv.ui.player.engine.videoFormat
import cc.slokker.itv.ui.theme.GoatPalette
import cc.slokker.itv.ui.player.textLabel
import cc.slokker.itv.ui.player.audioLabel
import cc.slokker.itv.ui.player.trackOptions
import cc.slokker.itv.ui.player.rememberStreamState
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import cc.slokker.itv.ui.common.LocalChannelLogos
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.api.EpgResult
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.kind
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.RefreshPolicy
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.PinPad
import cc.slokker.itv.ui.common.onBackKey
import cc.slokker.itv.ui.glass.AuroraBackground
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.maskEmail
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.player.HdrType
import cc.slokker.itv.ui.player.dynamicRangeLabel
import cc.slokker.itv.ui.theme.Accent
import cc.slokker.itv.ui.theme.ItvColors
import kotlin.math.abs
import kotlinx.coroutines.launch

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

/** Nome da origem dos logótipos: a lista, o guia de cada canal, ou o país (pelo código). */
private fun logoSourceLabel(source: String, s: Strings): String = when (source) {
    Prefs.LogoSource.PLAYLIST -> s.logoFromPlaylist
    Prefs.LogoSource.GUIDE -> s.logoFromGuide
    else -> Locale("", source.uppercase()).displayCountry.takeIf { it.isNotBlank() && !it.equals(source, ignoreCase = true) } ?: source.uppercase()
}

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
) {
    // Com "Proteger Definições", pede o PIN antes de mostrar qualquer página.
    var stack by remember {
        val first = when {
            startAtNewPlaylist -> Route.NewPlaylistType
            vm.settingsNeedPin() -> Route.SettingsGate
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
                is Route.ChannelsOrder -> ChannelsOrderPage(vm, entry.route.groupId, enabled = !leaving)
                is Route.TextEdit -> TextEditPage(entry.route, enabled = !leaving) { text ->
                    entry.route.onDone(text)
                    pop()
                }
                else -> {
                    val items = settingItems(entry.route, vm, player, playing, ::push, ::pop, ::popNested, ::replace, ::popToCurrent)
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

@Composable
private fun settingItems(
    route: Route,
    vm: AppViewModel,
    player: Player?,
    playing: Channel?,
    push: (Route, String) -> Unit,
    pop: () -> Unit,
    popNested: () -> Unit,
    replace: (Route) -> Unit,
    popToCurrent: () -> Unit,
): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val format by vm.streamFormat.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val email by vm.email.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val epgMappings by vm.epgMappings.collectAsStateWithLifecycle()
    val tracks = rememberStreamState(player).tracks
    val s = LocalStrings.current
    val context = LocalContext.current
    // Volta a verificar sempre que a página é mostrada (a pessoa pode ter ido dar a permissão).
    val canDrawOverlays = remember(route) { Settings.canDrawOverlays(context) }
    var confirmLogout by remember { mutableStateOf(false) }
    // Um "carregar outra vez para confirmar" por ação e por página: armar um não pode confirmar o outro.
    var confirmEpgReset by remember(route) { mutableStateOf(false) }
    var confirmDelete by remember(route) { mutableStateOf(false) }
    val displayHdr = remember { HdrType.supportedByDisplay(context) }
    val scope = rememberCoroutineScope()

    fun pick(key: String, title: String, options: List<Pair<Any?, String>>, selected: Any?, onPick: (Any?) -> Unit) =
        Item.Nav(key, title, options.firstOrNull { it.first == selected }?.second) {
            push(
                Route.Pick(title, options, selected) { v ->
                    onPick(v)
                    pop()
                },
                key,
            )
        }

    fun pref(key: String, title: String, options: List<Pair<Any?, String>>, selected: Any?) =
        pick(key, title, options, selected) { vm.setPref(key, it) }

    val aspectOptions = listOf<Pair<Any?, String>>(
        Prefs.Aspect.FIT to s.aspectFit,
        Prefs.Aspect.ZOOM to s.aspectZoom,
        Prefs.Aspect.FILL to s.aspectFill,
    )

    // Atualização da app: título e estado (a descarregar, a instalar…).
    val updateState by vm.update.collectAsStateWithLifecycle()
    val channelLogos = LocalChannelLogos.current
    val reportState by vm.report.collectAsStateWithLifecycle()
    fun updateItem(key: String, icon: ImageVector? = null): Item? = when (val u = updateState) {
        is UpdateState.Available -> Item.Action(key, s.updateTo(u.release.version), u.release.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('-', '*', ' '), icon) { vm.installUpdate() }
        is UpdateState.Downloading -> Item.Action(key, s.updateTo(u.release.version), s.downloading((u.progress * 100).toInt()), icon) {}
        is UpdateState.Installing -> Item.Action(key, s.updateTo(u.release.version), s.installing, icon) {}
        // Falhou: o motivo por extenso; carregar procura outra vez e volta a oferecer a atualização.
        is UpdateState.Failed -> Item.Action(key, s.updateFailedTitle, u.message, icon) { vm.checkForUpdate(manual = true) }
        else -> null
    }

    return when (route) {
        Route.Root -> buildList {
            // No topo, sem secção: o canal a ver e a atualização (se houver). Depois as páginas, por secções.
            if (player != null && playing != null) {
                add(
                    Item.Nav("current", playing.name, subtitle = s.currentChannel, logo = channelLogos[playing.key] ?: playing.logo, showLogo = true) {
                        push(Route.Current, "current")
                    },
                )
            }
            updateItem("update", ItvIcons.Update)?.let { add(it) }
            add(Item.Header("hwatch", s.sectionWatching))
            add(Item.Nav("playback", s.playback, subtitle = s.playbackSummary, icon = ItvIcons.PlayCircle) { push(Route.Playback, "playback") })
            add(Item.Nav("zapping", s.zapping, subtitle = s.zappingSummary, icon = ItvIcons.SwapVert) { push(Route.Zapping, "zapping") })
            add(Item.Nav("guide", s.guide, subtitle = s.guideSummary, icon = ItvIcons.Calendar) { push(Route.Guide, "guide") })
            add(Item.Header("hui", s.sectionInterface))
            add(Item.Nav("appearance", s.appearance, subtitle = s.appearanceSummary, icon = ItvIcons.Palette) { push(Route.Appearance, "appearance") })
            add(Item.Nav("general", s.general, subtitle = s.generalSummary, icon = ItvIcons.Tune) { push(Route.General, "general") })
            add(Item.Header("hlib", s.sectionLibrary))
            add(Item.Nav("lists", s.playlists, subtitle = content?.playlist?.name, icon = ItvIcons.Playlist) { push(Route.Lists, "lists") })
            add(
                Item.Nav("parental", s.parental, subtitle = if (prefs.pinHash != null) s.on else s.off, icon = ItvIcons.Lock) {
                    push(if (prefs.pinHash != null) Route.ParentalGate else Route.Parental, "parental")
                },
            )
            add(Item.Nav("account", s.account, subtitle = if (local) s.noAccount else email?.let(::maskEmail), icon = ItvIcons.Account) { push(Route.Account, "account") })
        }

        Route.Current -> buildList {
            val ch = playing ?: return@buildList
            val isFav = ch.key in favorites[content?.playlist?.id].orEmpty()
            add(Item.Toggle("fav", s.favorite, isFav, ch.name) { vm.toggleFavorite(ch) })
            add(Item.Nav("audio", s.audio, tracks.audioLabel()) { push(Route.Audio, "audio") })
            add(Item.Nav("subs", s.subtitles, tracks.textLabel(player)) { push(Route.Subtitles, "subs") })
            add(pref(Prefs.Key.ASPECT, s.aspect, aspectOptions, prefs.aspect))
            add(Item.Nav("info", s.streamInfo) { push(Route.StreamInfo, "info") })
            val net by vm.net.state.collectAsStateWithLifecycle()
            add(Item.Nav("net", s.connection, s.netShort(net.verdict)) { push(Route.Connection, "net") })
            // Por defeito o guia é o da lista; com conta, pode trocar-se o deste canal por um do IPTV-EPG.org.
            if (vm.epgPickerAvailable) {
                val mapping = epgMappings[ch.key]
                add(
                    Item.Nav("epg", s.epgGuideChannel, value = mapping?.let { guideLabel(it.guideName ?: it.channelId, it.country) } ?: s.epgFromPlaylist) {
                        push(Route.EpgPick(ch), "epg")
                    },
                )
            } else {
                add(Item.Action("epg", s.epgGuideChannel, s.epgNeedsAccount) { vm.showToast(s.epgNeedsAccount) })
            }
            // O logótipo escolhe-se como o guia: por defeito segue a origem geral (Aspeto → Origem dos logótipos).
            if (vm.epgPickerAvailable) {
                val choice = vm.channelLogoChoice(ch)
                val value = when {
                    choice == null -> s.logoAutomatic
                    choice.isEmpty() -> s.logoFromPlaylist
                    else -> s.logoChosen
                }
                add(Item.Nav("logo", s.channelLogo, value = value) { push(Route.EpgPick(ch, logo = true), "logo") })
            }
        }

        is Route.EpgPick -> {
            val ch = route.channel
            val logo = route.logo
            val query by route.query
            val mapping = epgMappings[ch.key]
            val logoChoice = vm.channelLogoChoice(ch)
            val suggestions by produceState<EpgResult<List<EpgGuideChannel>>?>(null, ch.key) { value = vm.epgSuggest(ch) }
            val search by produceState<EpgResult<List<EpgCountryResult>>?>(null, query) {
                value = null
                value = vm.epgSearch(query)
            }
            fun choose(country: String, guide: EpgGuideChannel) {
                if (logo) {
                    vm.setChannelLogo(ch, guide.icon)
                    popToCurrent()
                } else {
                    scope.launch { if (vm.setEpgMapping(ch, country, guide)) popToCurrent() }
                }
            }
            // Para o logótipo só contam os canais de guia que o têm.
            fun usable(g: EpgGuideChannel) = !logo || g.icon != null
            fun chosen(country: String?, g: EpgGuideChannel) =
                if (logo) logoChoice == g.icon else mapping?.country == country && mapping?.channelId == g.id
            buildList {
                if (logo) {
                    // Automático: o que diz Aspeto → Origem dos logótipos; o da lista fixa-o neste canal.
                    add(
                        Item.Check("auto", s.logoAutomatic, logoChoice == null, subtitle = logoSourceLabel(prefs.logoSource, s)) {
                            vm.setChannelLogo(ch, null)
                            popToCurrent()
                        },
                    )
                    add(
                        Item.Check("playlist", s.logoFromPlaylist, logoChoice == "", logo = ch.logo, showLogo = true) {
                            vm.setChannelLogo(ch, "")
                            popToCurrent()
                        },
                    )
                } else {
                    // O guia da lista é o normal; escolhê-lo desfaz a troca.
                    add(
                        Item.Check("playlist", s.epgFromPlaylist, mapping == null, subtitle = mapping?.let { s.epgUsingNow(guideLabel(it.guideName ?: it.channelId, it.country)) }) {
                            if (mapping != null) scope.launch { if (vm.removeEpgMapping(ch)) popToCurrent() }
                        },
                    )
                }
                if (suggestions == null || search == null) {
                    add(Item.Info(LOADING_KEY, s.loadingShort, ""))
                    return@buildList
                }
                (suggestions as? EpgResult.Ok)?.value?.filter { it.id != null && it.country != null && usable(it) }?.takeIf { it.isNotEmpty() }?.let { list ->
                    add(Item.Header("hs", s.epgSuggestions))
                    list.forEach { g ->
                        add(
                            Item.Check(
                                "s:${g.country}:${g.id}", g.name ?: g.id.orEmpty(),
                                chosen(g.country, g),
                                subtitle = listOfNotNull(g.countryName, g.now).joinToString(" · "),
                                logo = g.icon,
                                showLogo = logo,
                            ) { choose(g.country!!, g) },
                        )
                    }
                }
                add(Item.Header("hc", s.epgCountriesFor(query)))
                when (val r = search) {
                    is EpgResult.Error -> add(Item.Info("err", r.message, ""))
                    is EpgResult.Ok -> {
                        val countries = r.value
                            .map { it.copy(channels = it.channels?.filter(::usable)) }
                            .filter { it.country != null && !it.channels.isNullOrEmpty() }
                        if (countries.isEmpty()) add(Item.Info("none", s.epgNoResults(query), ""))
                        countries.forEach { c ->
                            val code = c.country!!
                            val name = c.countryName ?: code.uppercase()
                            add(Item.Nav("c:$code", name, value = c.channels!!.size.toString()) {
                                push(Route.EpgCountry(ch, code, name, c.channels, logo), "c:$code")
                            })
                        }
                    }
                    null -> Unit
                }
                add(Item.Action("other", s.epgSearchOther) {
                    push(Route.TextEdit(s.epgSearchOther, query, TextKind.PLAIN) { v -> if (v.isNotBlank()) route.query.value = v.trim() }, "other")
                })
            }
        }

        Route.LogoSource -> {
            val countries by produceState<EpgResult<List<EpgCountryInfo>>?>(null) {
                if (vm.epgPickerAvailable) value = vm.epgCountries()
            }
            fun choose(source: String?) {
                vm.setPref(Prefs.Key.LOGO_SOURCE, source)
                popNested()
            }
            buildList {
                add(Item.Check(Prefs.LogoSource.PLAYLIST, s.logoFromPlaylist, prefs.logoSource == Prefs.LogoSource.PLAYLIST) { choose(null) })
                // Os outros vêm do guia da GoatTV (IPTV-EPG.org), que precisa de conta.
                if (!vm.epgPickerAvailable) {
                    add(Item.Action("account", s.logoByCountry, s.epgNeedsAccount) { vm.showToast(s.epgNeedsAccount) })
                    return@buildList
                }
                add(
                    Item.Check(Prefs.LogoSource.GUIDE, s.logoFromGuide, prefs.logoSource == Prefs.LogoSource.GUIDE, s.logoFromGuideSub) {
                        choose(Prefs.LogoSource.GUIDE)
                    },
                )
                add(Item.Header("hc", s.logoByCountry))
                when (val r = countries) {
                    null -> add(Item.Info(LOADING_KEY, s.loadingShort, ""))
                    is EpgResult.Error -> add(Item.Info("err", r.message, ""))
                    is EpgResult.Ok -> r.value
                        .filter { it.code != null && (it.channels ?: 0) > 0 }
                        .sortedBy { it.name ?: it.code }
                        .forEach { c ->
                            add(Item.Check("k:${c.code}", c.name ?: c.code!!.uppercase(), prefs.logoSource == c.code) { choose(c.code) })
                        }
                }
            }
        }

        Route.EpgCountries -> {
            val countries by produceState<EpgResult<List<EpgCountryInfo>>?>(null) { value = vm.epgCountries() }
            when (val r = countries) {
                null -> listOf(Item.Info(LOADING_KEY, s.loadingShort, ""))
                is EpgResult.Error -> listOf(Item.Info("err", r.message, ""))
                is EpgResult.Ok -> r.value
                    .filter { it.code != null && (it.channels ?: 0) > 0 }
                    .sortedBy { it.name ?: it.code }
                    .map { c ->
                        val name = c.name ?: c.code!!.uppercase()
                        Item.Action("k:${c.code}", name, (c.channels ?: 0).toString()) {
                            vm.applyEpgCountry(c.code!!, name)
                            popNested()
                        }
                    }
            }
        }

        is Route.EpgCountry -> {
            val ch = route.channel
            val logo = route.logo
            val mapping = epgMappings[ch.key]
            val logoChoice = vm.channelLogoChoice(ch)
            val all by produceState<EpgResult<EpgCountryResult>?>(null, route.country) {
                if (route.matches == null) value = vm.epgCountryChannels(route.country)
            }
            buildList {
                val channels = route.matches ?: when (val r = all) {
                    null -> {
                        add(Item.Info(LOADING_KEY, s.loadingShort, ""))
                        return@buildList
                    }
                    is EpgResult.Error -> {
                        add(Item.Info("err", r.message, ""))
                        return@buildList
                    }
                    is EpgResult.Ok -> r.value.channels.orEmpty()
                }
                channels.filter { it.id != null && (!logo || it.icon != null) }.forEach { g ->
                    if (logo) {
                        add(
                            Item.Check("g:${g.id}", g.name ?: g.id!!, logoChoice == g.icon, subtitle = g.now, logo = g.icon, showLogo = true) {
                                vm.setChannelLogo(ch, g.icon)
                                popToCurrent()
                            },
                        )
                    } else {
                        add(
                            Item.Check("g:${g.id}", g.name ?: g.id!!, mapping?.country == route.country && mapping?.channelId == g.id, subtitle = g.now) {
                                scope.launch { if (vm.setEpgMapping(ch, route.country, g)) popToCurrent() }
                            },
                        )
                    }
                }
                if (route.matches != null) {
                    add(Item.Nav("all", s.epgAllCountry(route.title(s))) {
                        push(Route.EpgCountry(ch, route.country, route.title(s), null, logo), "all")
                    })
                }
                // Atalho: o mesmo país para todos os canais da lista com o mesmo nome.
                if (logo) {
                    add(Item.Action("whole", s.logoCountryApply(route.title(s)), s.logoCountryApplySub) {
                        vm.setPref(Prefs.Key.LOGO_SOURCE, route.country)
                        popToCurrent()
                    })
                } else {
                    add(Item.Action("whole", s.epgCountryApply(route.title(s)), s.epgCountryGuideSub) {
                        vm.applyEpgCountry(route.country, route.title(s))
                        popToCurrent()
                    })
                }
            }
        }

        Route.Audio -> trackItems(tracks, C.TRACK_TYPE_AUDIO, player, allowOff = false)
        Route.Subtitles -> trackItems(tracks, C.TRACK_TYPE_TEXT, player, allowOff = true)
        Route.StreamInfo -> streamInfo(player, format)

        Route.Connection -> buildList {
            val net by vm.net.state.collectAsStateWithLifecycle()
            val problem = net.verdict == NetVerdict.SLOW || net.verdict == NetVerdict.GAPS || net.verdict == NetVerdict.SLOW_REPLY
            val warn = GoatPalette.Warning
            if (net.verdict != NetVerdict.UNSUPPORTED) {
                add(Item.Header("hnow", s.netRightNow))
                add(
                    Item.Info(
                        "speed", s.netSpeed, net.gettingKbps?.let { s.netMbit(mbps(it)) } ?: "—", s.netSpeedSub, ItvIcons.Download,
                        warn.takeIf { net.verdict == NetVerdict.SLOW },
                    ),
                )
                val video = player?.videoFormat()
                val videoInfo = video?.let { f ->
                    listOfNotNull(
                        f.height.takeIf { it > 0 }?.let { "${it}p" },
                        codecName(f.sampleMimeType)?.substringBefore(" ("),
                        f.frameRate.takeIf { it > 0 }?.let { "%.0f fps".format(it) },
                    ).joinToString(" · ").ifEmpty { null }
                }
                add(Item.Info("needs", s.netNeeds, net.needsKbps?.let { s.netMbit(mbps(it)) } ?: "—", videoInfo, ItvIcons.PlayCircle))
                add(Item.Info("ahead", s.netAhead, "${net.bufferMs / 1_000} s", s.netAheadSub, ItvIcons.Buffer))
                add(
                    Item.Info(
                        "reply", s.netReply, net.replyMs?.let { "$it ms" } ?: "—", s.netReplySub, ItvIcons.Clock,
                        warn.takeIf { net.verdict == NetVerdict.SLOW_REPLY },
                    ),
                )
                add(Item.Info("stops", s.netStops, "${net.stops}", s.netStopsSub, ItvIcons.PauseCircle, warn.takeIf { net.stops > 0 }))
            }
            add(Item.Header("htv", s.netThisTv))
            add(Item.Info("link", s.netNetwork, s.netLink(net.link), icon = net.link.icon()))
            if (problem) {
                add(Item.Header("htry", s.netTry))
                if (net.link.wifi) add(Item.Info("cable", s.netUseCable, "", s.netUseCableSub, ItvIcons.Cable))
                val buffers = listOf<Pair<Any?, String>>(
                    Prefs.Buffer.LIVE to s.bufferLive,
                    Prefs.Buffer.SMALL to s.bufferSmall,
                    Prefs.Buffer.NORMAL to s.bufferNormal,
                    Prefs.Buffer.LARGE to s.bufferLarge,
                )
                add(
                    Item.Nav(Prefs.Key.BUFFER, s.buffer, buffers.firstOrNull { it.first == prefs.buffer }?.second, s.netBufferSub, ItvIcons.Buffer) {
                        push(Route.Pick(s.buffer, buffers, prefs.buffer) { v -> vm.setPref(Prefs.Key.BUFFER, v); pop() }, Prefs.Key.BUFFER)
                    },
                )
                val formats = listOf<Pair<Any?, String>>(null to s.auto, StreamFormat.TS to "TS (MPEG-TS)", StreamFormat.HLS to "HLS (m3u8)")
                // Só nas listas Xtream o servidor dá os dois formatos de cada canal.
                if (content?.playlist?.type == PlaylistType.XTREAM) add(
                    Item.Nav("format", s.streamFormat, formats.firstOrNull { it.first == format }?.second, s.netFormatSub, ItvIcons.SwapPlaylist) {
                        push(Route.Pick(s.streamFormat, formats, format) { v -> vm.setStreamFormat(v as String?); pop() }, "format")
                    },
                )
            }
            add(Item.Header("hopt", s.netOptions))
            add(Item.Toggle(Prefs.Key.NET_GRAPH, s.netGraph, prefs.netGraph, s.netGraphSub) { vm.setPref(Prefs.Key.NET_GRAPH, it) })
            add(Item.Toggle(Prefs.Key.NET_WARNINGS, s.netWarnings, prefs.netWarnings, s.netWarningsSub) { vm.setPref(Prefs.Key.NET_WARNINGS, it) })
        }

        Route.Playback -> listOf(
            pick(
                "format", s.streamFormat,
                listOf(null to s.auto, StreamFormat.TS to "TS (MPEG-TS)", StreamFormat.HLS to "HLS (m3u8)"),
                format,
            ) { vm.setStreamFormat(it as String?) },
            pref(
                Prefs.Key.BUFFER, s.buffer,
                listOf(
                    Prefs.Buffer.LIVE to s.bufferLive,
                    Prefs.Buffer.SMALL to s.bufferSmall,
                    Prefs.Buffer.NORMAL to s.bufferNormal,
                    Prefs.Buffer.LARGE to s.bufferLarge,
                ),
                prefs.buffer,
            ),
            pref(
                Prefs.Key.ENGINE, s.engine,
                listOf(Prefs.Engine.EXO to s.engineExo, Prefs.Engine.VLC to s.engineVlc),
                prefs.engine,
            ),
            pref(
                Prefs.Key.DECODER, s.decoder,
                listOf(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw),
                prefs.decoder,
            ),
            pref(Prefs.Key.ASPECT, s.aspect, aspectOptions, prefs.aspect),
            Item.Toggle(Prefs.Key.FREEZE_FRAME, s.freezeFrame, prefs.freezeFrame, s.freezeFrameSub) {
                vm.setPref(Prefs.Key.FREEZE_FRAME, it)
            },
            Item.Toggle(Prefs.Key.TIMESHIFT, s.timeshift, prefs.timeshift, s.timeshiftSub) {
                vm.setPref(Prefs.Key.TIMESHIFT, it)
            },
            pref(
                Prefs.Key.RECONNECT_SECONDS, s.reconnect,
                listOf(0 to s.no) + listOf(2, 5, 10, 30).map { it to s.afterSeconds(it) },
                prefs.reconnectSeconds,
            ),
            Item.Toggle(Prefs.Key.AFR, s.afr, prefs.afr, s.afrSub) { vm.setPref(Prefs.Key.AFR, it) },
            run {
                val options = listOf<Pair<Any?, String>>(Prefs.Hdr.AUTO to s.hdrAuto, Prefs.Hdr.SDR to s.hdrSdr)
                Item.Nav(
                    Prefs.Key.HDR, s.hdr,
                    value = options.firstOrNull { it.first == prefs.hdr }?.second,
                    subtitle = if (displayHdr.isEmpty()) s.hdrTvNone else s.hdrTvSupports(displayHdr.joinToString(", ") { it.label }),
                ) {
                    push(
                        Route.Pick(s.hdr, options, prefs.hdr) { v ->
                            vm.setPref(Prefs.Key.HDR, v)
                            pop()
                        },
                        Prefs.Key.HDR,
                    )
                }
            },
            Item.Header("audio", s.audioSection),
            Item.Toggle(Prefs.Key.AUDIO_PASSTHROUGH, s.passthrough, prefs.audioPassthrough, s.passthroughSub) {
                vm.setPref(Prefs.Key.AUDIO_PASSTHROUGH, it)
            },
            Item.Toggle(Prefs.Key.SURROUND_DEFAULT, s.surroundDefault, prefs.surroundDefault, s.surroundDefaultSub) {
                vm.setPref(Prefs.Key.SURROUND_DEFAULT, it)
            },
            pref(
                Prefs.Key.AUDIO_DECODER, s.audioDecoder,
                listOf(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw),
                prefs.audioDecoder,
            ),
        )

        Route.General -> listOf(
            Item.Toggle(
                Prefs.Key.AUTO_START, s.autoStart, prefs.autoStart,
                if (prefs.autoStart && !canDrawOverlays) s.overlayNeeded else s.autoStartSub,
            ) { on ->
                vm.setPref(Prefs.Key.AUTO_START, on)
                // O Android só deixa abrir a app no arranque com "Mostrar por cima de outras apps".
                if (on && !canDrawOverlays) requestOverlayPermission(context)
            },
            Item.Toggle(Prefs.Key.EXIT_CONFIRM, s.exitConfirm, prefs.exitConfirm) { vm.setPref(Prefs.Key.EXIT_CONFIRM, it) },
            Item.Nav(Prefs.Key.USER_AGENT, s.userAgent, value = prefs.userAgent.ifBlank { s.userAgentDefault }, subtitle = s.userAgentSub) {
                push(
                    Route.TextEdit(s.userAgent, prefs.userAgent, TextKind.PLAIN) { v -> vm.setPref(Prefs.Key.USER_AGENT, v.trim().ifEmpty { null }) },
                    Prefs.Key.USER_AGENT,
                )
            },
        )

        Route.Zapping -> listOf(
            pref(
                Prefs.Key.UP_DOWN, s.upDownKeys,
                listOf(Prefs.UpDown.ZAP to s.upDownZap, Prefs.UpDown.LIST to s.upDownList),
                prefs.upDown,
            ),
            Item.Toggle(Prefs.Key.REVERSE_ZAP, s.reverseUpDown, prefs.reverseZap, s.reverseUpDownSub) {
                vm.setPref(Prefs.Key.REVERSE_ZAP, it)
            },
            pref(
                Prefs.Key.INFO_SECONDS, s.infoOnZap,
                listOf(0 to s.dontShow) + listOf(3, 4, 6, 10).map { it to s.seconds(it) },
                prefs.infoSeconds,
            ),
            pref(
                Prefs.Key.STARTUP, s.onStartup,
                listOf(Prefs.Startup.LAST to s.startupLast, Prefs.Startup.LIST to s.startupList),
                prefs.startup,
            ),
            pref(
                Prefs.Key.BACK_ACTION, s.backKey,
                listOf(Prefs.BackAction.GUIDE to s.backOpensGuide, Prefs.BackAction.EXIT to s.backExits),
                prefs.backAction,
            ),
        )

        Route.Appearance -> listOf(
            Item.Header("hlang", s.sectionLanguageText),
            pref(Prefs.Key.LANGUAGE, s.language, listOf("auto" to s.languageAuto) + Strings.LANGUAGES, prefs.language),
            pref(
                Prefs.Key.UI_SCALE, s.uiScale,
                (Prefs.UI_SCALE_MIN..Prefs.UI_SCALE_MAX step 5).map { it to s.percent(it) },
                prefs.uiScale,
            ),
            pref(
                Prefs.Key.TEXT_SIZE, s.textSize,
                listOf(Prefs.TextSize.SMALL to s.sizeSmall, Prefs.TextSize.NORMAL to s.sizeNormal, Prefs.TextSize.LARGE to s.sizeLarge),
                prefs.textSize,
            ),
            Item.Header("hlist", s.sectionChannelList),
            Item.Toggle(Prefs.Key.SHOW_NUMBERS, s.channelNumbers, prefs.showNumbers) { vm.setPref(Prefs.Key.SHOW_NUMBERS, it) },
            Item.Toggle(Prefs.Key.SHOW_LOGOS, s.channelLogos, prefs.showLogos) { vm.setPref(Prefs.Key.SHOW_LOGOS, it) },
            Item.Nav(Prefs.Key.LOGO_SOURCE, s.logoSource, value = logoSourceLabel(prefs.logoSource, s)) { push(Route.LogoSource, Prefs.Key.LOGO_SOURCE) },
            pref(
                Prefs.Key.SORT, s.sortChannels,
                listOf(Prefs.Sort.NUMBER to s.sortNumber, Prefs.Sort.NAME to s.sortName),
                prefs.sort,
            ),
            Item.Header("hpanels", s.sectionPanelsColour),
            pref(
                Prefs.Key.PANEL_WIDTH, s.panelWidth,
                listOf(300 to s.widthNarrow, 340 to s.widthNormal, 400 to s.widthWide, 460 to s.widthExtraWide),
                prefs.panelWidth,
            ),
            Item.Nav(Prefs.Key.ACCENT, s.accent, value = s.accentName(prefs.accent)) {
                push(
                    Route.Pick(
                        s.accent,
                        Accent.ALL.map { it.id to s.accentName(it.id) },
                        prefs.accent,
                        swatches = Accent.ALL.associate { it.id to it.brush },
                    ) { v ->
                        vm.setPref(Prefs.Key.ACCENT, v)
                        pop()
                    },
                    Prefs.Key.ACCENT,
                )
            },
            Item.Nav(Prefs.Key.OUTLINE_COLOR, s.outlineColor, value = s.outlineColorName(prefs.outlineColor)) {
                val accent = Accent.byId(prefs.accent)
                push(
                    Route.Pick(
                        s.outlineColor,
                        Prefs.OutlineColors.IDS.map { it to s.outlineColorName(it) },
                        prefs.outlineColor,
                        swatches = Prefs.OutlineColors.IDS.associate { id: String -> (id as Any?) to outlineBrush(id, accent) },
                    ) { v ->
                        vm.setPref(Prefs.Key.OUTLINE_COLOR, v)
                        pop()
                    },
                    Prefs.Key.OUTLINE_COLOR,
                )
            },
            pref(
                Prefs.Key.GLASS_OPACITY, s.glassOpacity,
                (0..100 step 10).map { it to s.percent(it) },
                prefs.glassOpacity,
            ),
            pref(
                Prefs.Key.PANEL_TIMEOUT, s.autoClosePanels,
                listOf(10, 15, 30).map { it to s.afterSeconds(it) } + listOf(0 to s.never),
                prefs.panelTimeout,
            ),
        )

        Route.Guide -> listOf(
            pref(Prefs.Key.EPG_REFRESH, s.epgRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.epgRefreshHours),
            pref(Prefs.Key.EPG_OFFSET, s.timeOffset, (-180..180 step 30).map { it to offsetLabel(it) }, prefs.epgOffsetMin),
            Item.Toggle(Prefs.Key.EPG_IN_LIST, s.nowInList, prefs.epgInList, s.nowInListSub) {
                vm.setPref(Prefs.Key.EPG_IN_LIST, it)
            },
        )

        Route.Parental -> buildList {
            if (prefs.pinHash == null) {
                add(Item.Action("newpin", s.setPin, s.setPinSub) { push(Route.NewPin, "newpin") })
            } else {
                val data = content
                val locked = if (data == null) 0 else prefs.lockedGroups.count { it.startsWith(data.playlist.id + ":") }
                add(Item.Nav("locked", s.lockedGroups, locked.toString()) { push(Route.LockedGroups, "locked") })
                add(Item.Toggle(Prefs.Key.SETTINGS_PIN, s.protectSettings, prefs.settingsPin, s.protectSettingsSub) { vm.setPref(Prefs.Key.SETTINGS_PIN, it) })
                add(Item.Action("newpin", s.changePin) { push(Route.NewPin, "newpin") })
                add(
                    Item.Action("nopin", s.parentalOff, s.parentalOffSub) {
                        vm.setPin(null)
                        vm.showToast(s.parentalOffToast)
                        pop()
                    },
                )
            }
        }

        Route.LockedGroups -> buildList {
            val data = content ?: return@buildList
            add(Item.Header("h", s.lockedGroupsHeader))
            data.categories.forEach { g ->
                // "Outros" é o id do grupo dos canais sem grupo: o nome mostra-se na língua da app (como em Grupos).
                val name = if (g.id == PlaylistContent.OTHERS) s.others else g.name
                add(Item.Toggle("g:" + g.id, name, "${data.playlist.id}:${g.id}" in prefs.lockedGroups) { vm.toggleGroupLock(g.id) })
            }
        }

        Route.GroupsManage -> buildList {
            add(Item.Action("reorder", s.reorderGroups, s.reorderGroupsSub) { push(Route.GroupsOrder, "reorder") })
            // Sempre à vista: se desaparecesse ao carregar, o foco ficava sem sítio (visto na SHIELD).
            add(Item.Action("resetorder", s.resetOrder, s.resetOrderSub) { vm.resetGroupOrder() })
            add(Item.Header("h", s.visibleGroups))
            // Os dois grupos especiais valem para todas as listas.
            add(Item.Toggle(Prefs.Key.SHOW_FAVORITES_GROUP, s.favoritesStar, prefs.showFavoritesGroup, eye = true) { vm.setPref(Prefs.Key.SHOW_FAVORITES_GROUP, it) })
            add(Item.Toggle(Prefs.Key.SHOW_ALL_GROUP, s.all, prefs.showAllGroup, eye = true) { vm.setPref(Prefs.Key.SHOW_ALL_GROUP, it) })
            add(Item.Toggle(Prefs.Key.SHOW_RECENT_GROUP, s.recents, prefs.showRecentGroup, eye = true) { vm.setPref(Prefs.Key.SHOW_RECENT_GROUP, it) })
            val data = content ?: return@buildList
            vm.orderedGroups(includeHidden = true).forEach { g ->
                val name = if (g.id == PlaylistContent.OTHERS) s.others else g.name
                add(Item.Toggle("g:" + g.id, name, !prefs.isHidden(data.playlist.id, g.id), eye = true) { visible -> vm.setGroupHidden(g.id, !visible) })
            }
        }

        Route.ChannelGroups -> buildList {
            val data = content ?: return@buildList
            vm.orderedGroups(includeHidden = true).forEach { g ->
                val name = if (g.id == PlaylistContent.OTHERS) s.others else g.name
                val all = data.byCategory[g.id].orEmpty()
                val visible = all.count { !prefs.isChannelHidden(data.playlist.id, it.key) }
                add(Item.Nav("g:" + g.id, name, value = if (visible == all.size) all.size.toString() else "$visible / ${all.size}") {
                    push(Route.ChannelsManage(g.id, name), "g:" + g.id)
                })
            }
        }

        is Route.ChannelsManage -> buildList {
            val data = content ?: return@buildList
            add(Item.Action("reorder", s.reorderChannels, s.reorderChannelsSub) { push(Route.ChannelsOrder(route.groupId, route.title(s)), "reorder") })
            // Sempre à vista: se desaparecesse ao carregar, o foco ficava sem sítio (visto na SHIELD).
            add(Item.Action("resetorder", s.resetOrder, s.resetOrderSub) { vm.resetChannelOrder(route.groupId) })
            add(Item.Header("h", s.visibleChannels))
            vm.orderedChannels(route.groupId, includeHidden = true).forEach { ch ->
                add(Item.Toggle("c:" + ch.key, ch.name, !prefs.isChannelHidden(data.playlist.id, ch.key), eye = true) { visible -> vm.setChannelHidden(ch, !visible) })
            }
        }

        Route.Lists -> buildList {
            playlists.forEach { p ->
                add(
                    Item.Nav(
                        "p:" + p.id, p.name,
                        value = if (p.id == content?.playlist?.id) s.inUse else null,
                        subtitle = typeLabel(p.type),
                    ) { push(Route.PlaylistDetail(p.id, p.name), "p:" + p.id) },
                )
            }
            add(Item.Action("add", s.addPlaylist, s.addPlaylistSub) { push(Route.NewPlaylistType, "add") })
            // Com conta também se gerem aqui, mas escreve-se melhor no telemóvel ou no computador.
            if (!local) add(Item.Info("manage", s.playlistsSiteTip, ""))
            add(Item.Action("epg", s.refreshEpg, s.refreshEpgSub) { vm.refreshEpg() })
            add(Item.Header("hr", s.updates))
            add(pref(Prefs.Key.PLAYLIST_REFRESH, s.playlistRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.playlistRefreshHours))
            add(pref(Prefs.Key.EPG_REFRESH, s.epgRefresh, RefreshPolicy.OPTIONS.map { it to s.refreshLabel(it) }, prefs.epgRefreshHours))
        }

        is Route.PlaylistDetail -> buildList {
            val p = playlists.firstOrNull { it.id == route.id } ?: return@buildList
            val isCurrent = p.id == content?.playlist?.id
            if (!isCurrent) add(Item.Action("use", s.usePlaylist) { vm.switchPlaylist(p) })
            add(Item.Action("reload", s.reloadChannels, s.reloadChannelsSub) {
                if (isCurrent) vm.reloadPlaylist() else vm.switchPlaylist(p)
            })
            // Organizar: esconder e reordenar grupos e canais (precisa dos canais, por isso só na lista em uso).
            add(Item.Header("horg", s.organize))
            if (isCurrent) {
                val hidden = prefs.hiddenGroups.count { it.startsWith(p.id + ":") }
                add(Item.Nav("groups", s.manageGroups, subtitle = s.manageGroupsSub(hidden)) { push(Route.GroupsManage, "groups") })
                add(Item.Nav("channels", s.manageChannels, subtitle = s.manageChannelsSub) { push(Route.ChannelGroups, "channels") })
            } else {
                add(Item.Info("orgfirst", s.organizeUseFirst, ""))
            }
            // Guia: um XMLTV extra (qualquer lista, com ou sem conta); com conta, também o de um país (canal a canal
            // é em Canal atual → Guia).
            add(Item.Header("hepg", s.guide))
            val extraUrl = prefs.extraEpg[p.id]
            add(
                Item.Nav("epgextra", s.epgExtra, value = extraUrl?.let { Uri.parse(it).host } ?: s.epgExtraNone, subtitle = s.epgExtraSub) {
                    push(Route.TextEdit(s.epgExtra, extraUrl.orEmpty(), TextKind.URL) { v -> vm.setExtraEpg(p.id, v) }, "epgextra")
                },
            )
            if (extraUrl != null) add(Item.Action("epgextraoff", s.epgExtraRemove) { vm.setExtraEpg(p.id, null) })
            if (isCurrent && vm.epgPickerAvailable) {
                add(Item.Nav("epgcountry", s.epgCountryGuide, subtitle = s.epgCountryGuideSub) { push(Route.EpgCountries, "epgcountry") })
                if (epgMappings.isNotEmpty()) {
                    add(
                        Item.Action("epgreset", if (confirmEpgReset) s.pressAgainToConfirm else s.epgResetAll, s.epgResetAllSub) {
                            if (confirmEpgReset) {
                                vm.resetEpgMappings()
                                confirmEpgReset = false
                            } else {
                                confirmEpgReset = true
                            }
                        },
                    )
                }
            }
            add(Item.Nav("edit", s.edit) { push(Route.PlaylistEdit(p.id, mutableStateOf(PlaylistForm.from(p))), "edit") })
            add(
                Item.Action(
                    "delete",
                    if (confirmDelete) s.removePlaylistConfirm else s.removePlaylist,
                    if (local) s.removePlaylistSub else s.removePlaylistAccountSub,
                ) {
                    if (!confirmDelete) {
                        confirmDelete = true
                    } else {
                        confirmDelete = false
                        scope.launch {
                            val error = vm.deletePlaylist(p)
                            vm.showToast(error ?: s.playlistRemoved)
                            if (error == null) popNested()
                        }
                    }
                },
            )
        }

        // O formulário substitui esta página: ao gravar (ou Voltar) regressa-se às Listas.
        Route.NewPlaylistType -> listOfNotNull(
            Item.Action("xtream", "Xtream Codes", s.xtreamSub) {
                replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm(type = PlaylistType.XTREAM))))
            },
            Item.Action("m3u", s.m3uPlaylist, s.m3uSub) {
                replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm(type = PlaylistType.M3U))))
            },
            Item.Action("stalker", "Stalker / MAC", s.stalkerSub) {
                replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm().withType(PlaylistType.STALKER, vm.deviceMac))))
            }.takeIf { vm.canAddStalker },
        )

        is Route.PlaylistEdit -> buildList {
            val form = route.form.value
            fun set(f: (PlaylistForm) -> PlaylistForm) { route.form.value = f(route.form.value) }
            fun text(key: String, title: String, value: String, kind: TextKind, optional: Boolean = false, subtitle: String? = null, update: (PlaylistForm, String) -> PlaylistForm) =
                Item.Nav(
                    key, title,
                    value = when {
                        value.isEmpty() -> if (optional) s.optional else "—"
                        kind == TextKind.PASSWORD -> "•".repeat(value.length.coerceAtMost(8))
                        else -> value
                    },
                    subtitle = subtitle,
                ) { push(Route.TextEdit(title, value, kind) { v -> set { update(it, v) } }, key) }

            val typeOptions = listOf<Pair<Any?, String>>(PlaylistType.XTREAM to "Xtream Codes", PlaylistType.M3U to s.m3uPlaylist) +
                if (vm.canAddStalker || form.type == PlaylistType.STALKER) listOf<Pair<Any?, String>>(PlaylistType.STALKER to "Stalker / MAC") else emptyList()
            if (route.id != null && !local) {
                // Na conta, o tipo de uma lista não muda (para outro tipo cria-se uma lista nova).
                add(Item.Info("type", s.type, typeOptions.first { it.first == form.type }.second))
            } else {
                add(pick("type", s.type, typeOptions, form.type) { t -> set { it.withType(t as PlaylistType, vm.deviceMac) } })
            }
            add(text("name", s.name, form.name, TextKind.PLAIN, optional = true) { f, v -> f.copy(name = v) })
            if (form.type == PlaylistType.XTREAM) {
                add(text("server", s.server, form.server, TextKind.URL) { f, v -> f.copy(server = v) })
                add(text("user", s.username, form.username, TextKind.PLAIN) { f, v -> f.copy(username = v) })
                add(text("pass", s.password, form.password, TextKind.PASSWORD) { f, v -> f.copy(password = v) })
            } else if (form.type == PlaylistType.STALKER) {
                add(text("server", s.portalUrl, form.server, TextKind.URL) { f, v -> f.copy(server = v) })
                add(text("mac", s.macAddress, form.username, TextKind.PLAIN, subtitle = s.stalkerMacHint) { f, v -> f.copy(username = v) })
                add(text("serial", "Serial", form.serial, TextKind.PLAIN, optional = true) { f, v -> f.copy(serial = v) })
                add(text("device", "Device ID", form.deviceId, TextKind.PLAIN, optional = true) { f, v -> f.copy(deviceId = v) })
                add(text("device2", "Device ID 2", form.deviceId2, TextKind.PLAIN, optional = true) { f, v -> f.copy(deviceId2 = v) })
                add(text("signature", "Signature", form.signature, TextKind.PLAIN, optional = true) { f, v -> f.copy(signature = v) })
            } else {
                add(text("url", s.playlistUrl, form.url, TextKind.URL) { f, v -> f.copy(url = v) })
            }
            add(text("epg", s.epgUrl, form.epgUrl, TextKind.URL, optional = true) { f, v -> f.copy(epgUrl = v) })
            add(Item.Action("save", s.save, form.problem() ?: if (vm.epgPickerAvailable) s.playlistAccountHint else s.playlistOnlyHere) {
                if (route.saving.value) return@Action
                route.saving.value = true
                scope.launch {
                    val error = try {
                        vm.savePlaylist(route.id, route.form.value)
                    } finally {
                        route.saving.value = false
                    }
                    if (error != null) {
                        vm.showToast(error)
                    } else {
                        vm.showToast(s.playlistSaved)
                        popNested()
                    }
                }
            })
        }

        Route.Account -> listOfNotNull(
            Item.Info("email", s.account, if (local) s.noAccount else email?.let(::maskEmail) ?: "—"),
            Item.Info("device", s.thisTv, deviceName ?: "—").takeIf { !local },
            // Sem conta: ligar a conta passa as definições desta TV para lá e sincroniza tudo entre dispositivos.
            if (local) {
                Item.Action("connect", s.connectAccount, s.connectAccountSub) { vm.connectAccount() }
            } else {
                Item.Action("logout", if (confirmLogout) s.pressAgainToConfirm else s.logoutThisTv) {
                    when {
                        !confirmLogout -> confirmLogout = true
                        vm.logoutNeedsPin() -> push(Route.LogoutGate, "logout")
                        else -> vm.logoutThisTv()
                    }
                }
            },
            Item.Info("version", s.version, BuildConfig.VERSION_NAME),
            Item.Nav("licenses", s.licenses, value = "GPL-3.0") { push(Route.Licenses, "licenses") },
            // A mesma chave em todos os estados: a linha muda de texto sem perder o foco.
            updateItem("checkupdate") ?: Item.Action(
                "checkupdate",
                s.checkUpdates,
                when (val u = updateState) {
                    UpdateState.Checking -> s.checking
                    UpdateState.UpToDate -> s.upToDate
                    is UpdateState.Failed -> s.updateFailed(u.message)
                    else -> null
                },
            ) { vm.checkForUpdate(manual = true) },
            // O que a pessoa escreve vai com o registo desta app para a zona admin do site.
            Item.Action(
                "report",
                s.sendReport,
                when (val r = reportState) {
                    ReportState.Idle -> s.sendReportSub
                    ReportState.Sending -> s.reportSending
                    is ReportState.Sent -> s.reportSent(r.id)
                    is ReportState.Failed -> r.message
                },
            ) {
                if (reportState != ReportState.Sending) {
                    push(Route.TextEdit(s.reportWhat, "", TextKind.PLAIN, action = s.reportSend) { vm.sendReport(it) }, "report")
                }
            },
        )

        // A app é GPL-3; cada componente com a sua licença (os textos estão no repositório do código).
        Route.Licenses -> listOf(
            Item.Info("source", s.sourceCode, "github.com/GOAThings/GoatTV"),
            Item.Info("goattv", "GoatTV", "GPL-3.0"),
            Item.Info("media3", "AndroidX, Media3 (ExoPlayer)", "Apache-2.0"),
            Item.Info("ffmpeg", "FFmpeg, nextlib", "GPL-3.0 / LGPL-2.1"),
            Item.Info("vlc", "libVLC", "LGPL-2.1"),
            Item.Info("net", "OkHttp, Retrofit, Gson", "Apache-2.0"),
            Item.Info("coil", "Coil, ZXing", "Apache-2.0"),
            Item.Info("font", "Adwaita Sans", "OFL-1.1"),
        )

        is Route.Pick -> route.options.mapIndexed { i, (value, label) ->
            Item.Check("o:$i", label, value == route.selected, swatch = route.swatches[value]) { route.onPick(value) }
        }

        Route.ParentalGate, Route.LogoutGate, Route.SettingsGate, Route.NewPin, Route.GroupsOrder, is Route.ChannelsOrder, is Route.TextEdit -> emptyList()
    }
}

/** Abre o ecrã do sistema para "Mostrar por cima de outras apps", se a box o tiver. */
private fun requestOverlayPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun typeLabel(type: PlaylistType) = type.kind.label

/** Linha que está a carregar da rede (ver o `key` em SettingsContent). */
private const val LOADING_KEY = "loading"

/** "SIC · PT": o canal de guia e o país. */
private fun guideLabel(name: String?, country: String?) = listOfNotNull(name, country?.uppercase()).joinToString(" · ")

// ---- Faixas de áudio e legendas, informação do stream ---------------------------------------

private fun trackItems(tracks: Tracks, type: Int, player: Player?, allowOff: Boolean): List<Item> {
    val options = trackOptions(tracks, type, player, allowOff)
    if (options.isEmpty()) return listOf(Item.Info("none", I18n.s.noTracks, ""))
    return options.map { o -> Item.Check(o.key, o.label, o.selected, o.details?.takeIf { it.isNotEmpty() }) { o.select() } }
}

private fun streamInfo(player: Player?, format: String?): List<Item> {
    if (player == null) return emptyList()
    val video = player.videoSize
    val tracks = player.currentTracks
    fun selected(type: Int): Format? = tracks.groups.filter { it.type == type }.firstNotNullOfOrNull { g ->
        (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) }
    }
    val v = selected(C.TRACK_TYPE_VIDEO)
    val a = selected(C.TRACK_TYPE_AUDIO)
    return listOfNotNull(
        Item.Info("res", I18n.s.resolution, if (video.width > 0) "${video.width}×${video.height}" else "—"),
        v?.let { Item.Info("range", I18n.s.dynamicRange, dynamicRangeLabel(it)) },
        v?.let {
            Item.Info("vcodec", I18n.s.video, listOfNotNull(codecName(it.sampleMimeType), it.frameRate.takeIf { r -> r > 0 }?.let { r -> "%.0f fps".format(r) }).joinToString(" · "))
        },
        a?.let {
            Item.Info("acodec", I18n.s.audio, listOfNotNull(codecName(it.sampleMimeType), channelsLabel(it.channelCount), it.sampleRate.takeIf { r -> r > 0 }?.let { r -> "${r / 1000} kHz" }).joinToString(" · "))
        },
        Item.Info("buffer", I18n.s.buffer, "${player.totalBufferedDuration / 1000} s"),
        Item.Info("fmt", I18n.s.preferredFormat, when (format) { StreamFormat.TS -> "TS"; StreamFormat.HLS -> "HLS"; else -> I18n.s.auto }),
    )
}

private fun offsetLabel(min: Int): String =
    if (min == 0) I18n.s.noOffset else (if (min > 0) "+" else "−") + "${abs(min) / 60} h" + if (abs(min) % 60 != 0) " 30" else ""
