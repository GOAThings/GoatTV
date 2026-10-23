package cc.slokker.itv.ui.settings

import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.player.channelsLabel
import cc.slokker.itv.ui.player.codecName
import cc.slokker.itv.ui.player.textLabel
import cc.slokker.itv.ui.player.audioLabel
import cc.slokker.itv.ui.player.trackOptions
import cc.slokker.itv.ui.player.rememberStreamState
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.RefreshPolicy
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.PinPad
import cc.slokker.itv.ui.common.onBackKey
import cc.slokker.itv.ui.glass.AuroraBackground
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.SectionLabel
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.glass.rowText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.maskEmail
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.player.focusWithRetry
import cc.slokker.itv.ui.player.HdrType
import cc.slokker.itv.ui.player.dynamicRangeLabel
import cc.slokker.itv.ui.theme.Accent
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
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
private sealed interface Route {
    fun title(s: Strings): String

    data object Root : Route { override fun title(s: Strings) = s.settings }
    data object Current : Route { override fun title(s: Strings) = s.currentChannel }
    data object Audio : Route { override fun title(s: Strings) = s.audio }
    data object Subtitles : Route { override fun title(s: Strings) = s.subtitles }
    data object StreamInfo : Route { override fun title(s: Strings) = s.streamInfo }
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
    data object SettingsGate : Route { override fun title(s: Strings) = s.settings }
    data object Parental : Route { override fun title(s: Strings) = s.parental }
    data object NewPin : Route { override fun title(s: Strings) = s.newPin }
    data object LockedGroups : Route { override fun title(s: Strings) = s.lockedGroups }
    data object Lists : Route { override fun title(s: Strings) = s.playlists }
    class PlaylistDetail(val id: String, private val name: String) : Route { override fun title(s: Strings) = name }
    /** Escolher o guia de um canal (só com conta): pesquisa pelo nome → país → canal de guia. */
    class EpgPick(val channel: Channel) : Route {
        /** O nome a procurar (começa pelo do canal; "Procurar outro nome" muda-o). */
        val query = mutableStateOf(channel.name)
        override fun title(s: Strings) = s.epgGuideChannel
    }

    /** Canais de guia de um país: os da pesquisa ([matches]), ou todos (null). */
    class EpgCountry(val channel: Channel, val country: String, private val name: String, val matches: List<EpgGuideChannel>?) : Route {
        override fun title(s: Strings) = name
    }

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
    class TextEdit(private val label: String, val initial: String, val kind: TextKind, val onDone: (String) -> Unit) : Route {
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

private enum class TextKind { PLAIN, URL, PASSWORD }

/** Uma linha de definições. [key] serve para devolver o foco à linha certa ao voltar atrás. */
private sealed class Item(val key: String) {
    class Nav(key: String, val title: String, val value: String? = null, val subtitle: String? = null, val onClick: () -> Unit) : Item(key)
    class Toggle(key: String, val title: String, val checked: Boolean, val subtitle: String? = null, val onToggle: (Boolean) -> Unit) : Item(key)
    class Check(key: String, val title: String, val checked: Boolean, val subtitle: String? = null, val swatch: Brush? = null, val onClick: () -> Unit) : Item(key)
    class Action(key: String, val title: String, val subtitle: String? = null, val onClick: () -> Unit) : Item(key)
    class Info(key: String, val title: String, val value: String) : Item(key)
    class Header(key: String, val text: String) : Item(key)
}

private class Entry(val route: Route, var returnKey: String? = null)

/**
 * Definições com navegação em páginas. Voltar recua uma página; na primeira chama [onClose].
 * [player] e [playing] só existem dentro do player (página "Canal atual").
 * [startAtNewPlaylist]: abre já no formulário da primeira lista (sem conta).
 * [startAtEpgFor]: abre já na escolha do guia desse canal (botão do HUD); ao escolher, fecha.
 */
@Composable
fun SettingsContent(
    vm: AppViewModel,
    player: Player?,
    playing: Channel?,
    onClose: () -> Unit,
    startAtNewPlaylist: Boolean = false,
    startAtEpgFor: Channel? = null,
) {
    // Com "Proteger Definições", pede o PIN antes de mostrar qualquer página.
    var stack by remember {
        val first = when {
            startAtNewPlaylist -> Route.NewPlaylistType
            vm.settingsNeedPin() -> Route.SettingsGate
            startAtEpgFor != null -> Route.EpgPick(startAtEpgFor)
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
        Column(Modifier.focusProperties { canFocus = !leaving }) {
            // A página de uma lista mostra o nome atual (pode ter mudado aqui, no site ou noutra TV).
            val playlistsNow by vm.playlists.collectAsStateWithLifecycle()
            val title = (entry.route as? Route.PlaylistDetail)?.let { r -> playlistsNow.firstOrNull { it.id == r.id }?.name }
                ?: entry.route.title(LocalStrings.current)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 4.dp),
            )
            when (entry.route) {
                Route.ParentalGate -> PinPad(
                    title = LocalStrings.current.enterPin,
                    message = null,
                    onDone = { pin -> if (vm.checkPin(pin)) replace(Route.Parental) else vm.showToast(I18n.s.wrongPin) },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    enabled = !leaving,
                )
                Route.SettingsGate -> PinPad(
                    title = LocalStrings.current.enterPin,
                    message = null,
                    onDone = { pin -> if (vm.unlockSettings(pin)) replace(Route.Root) else vm.showToast(I18n.s.wrongPin) },
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
                    // Páginas que carregam da rede: quando chegam os itens, a lista recomeça com o foco no primeiro.
                    key(items.any { it.key == LOADING_KEY }) {
                        ItemList(items = items, focusKey = entry.returnKey, enabled = !leaving)
                    }
                }
            }
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
    var confirmDelete by remember { mutableStateOf(false) }
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
    fun updateItem(key: String): Item? = when (val u = updateState) {
        is UpdateState.Available -> Item.Action(key, s.updateTo(u.release.version), u.release.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('-', '*', ' ')) { vm.installUpdate() }
        is UpdateState.Downloading -> Item.Action(key, s.updateTo(u.release.version), s.downloading((u.progress * 100).toInt())) {}
        is UpdateState.Installing -> Item.Action(key, s.updateTo(u.release.version), s.installing) {}
        // Falhou: o motivo por extenso; carregar procura outra vez e volta a oferecer a atualização.
        is UpdateState.Failed -> Item.Action(key, s.updateFailedTitle, u.message) { vm.checkForUpdate(manual = true) }
        else -> null
    }

    return when (route) {
        Route.Root -> buildList {
            updateItem("update")?.let { add(it) }
            if (player != null && playing != null) {
                add(Item.Nav("current", s.currentChannel, subtitle = playing.name) { push(Route.Current, "current") })
            }
            add(Item.Nav("general", s.general, subtitle = s.generalSummary) { push(Route.General, "general") })
            add(Item.Nav("playback", s.playback, subtitle = s.playbackSummary) { push(Route.Playback, "playback") })
            add(Item.Nav("zapping", s.zapping, subtitle = s.zappingSummary) { push(Route.Zapping, "zapping") })
            add(Item.Nav("appearance", s.appearance, subtitle = s.appearanceSummary) { push(Route.Appearance, "appearance") })
            add(Item.Nav("guide", s.guide, subtitle = s.guideSummary) { push(Route.Guide, "guide") })
            add(
                Item.Nav("parental", s.parental, subtitle = if (prefs.pinHash != null) s.on else s.off) {
                    push(if (prefs.pinHash != null) Route.ParentalGate else Route.Parental, "parental")
                },
            )
            add(Item.Nav("lists", s.playlists, subtitle = content?.playlist?.name) { push(Route.Lists, "lists") })
            add(Item.Nav("account", s.account, subtitle = if (local) s.noAccount else email?.let(::maskEmail)) { push(Route.Account, "account") })
        }

        Route.Current -> buildList {
            val ch = playing ?: return@buildList
            val isFav = ch.key in favorites[content?.playlist?.id].orEmpty()
            add(Item.Toggle("fav", s.favorite, isFav, ch.name) { vm.toggleFavorite(ch) })
            add(Item.Nav("audio", s.audio, tracks.audioLabel()) { push(Route.Audio, "audio") })
            add(Item.Nav("subs", s.subtitles, tracks.textLabel(player)) { push(Route.Subtitles, "subs") })
            add(pref(Prefs.Key.ASPECT, s.aspect, aspectOptions, prefs.aspect))
            add(Item.Nav("info", s.streamInfo) { push(Route.StreamInfo, "info") })
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
        }

        is Route.EpgPick -> {
            val ch = route.channel
            val query by route.query
            val mapping = epgMappings[ch.key]
            val suggestions by produceState<AppViewModel.EpgResult<List<EpgGuideChannel>>?>(null, ch.key) { value = vm.epgSuggest(ch) }
            val search by produceState<AppViewModel.EpgResult<List<EpgCountryResult>>?>(null, query) {
                value = null
                value = vm.epgSearch(query)
            }
            fun choose(country: String, guide: EpgGuideChannel) {
                scope.launch { if (vm.setEpgMapping(ch, country, guide)) popToCurrent() }
            }
            buildList {
                // O guia da lista é o normal; escolhê-lo desfaz a troca.
                add(
                    Item.Check("playlist", s.epgFromPlaylist, mapping == null, subtitle = mapping?.let { s.epgUsingNow(guideLabel(it.guideName ?: it.channelId, it.country)) }) {
                        if (mapping != null) scope.launch { if (vm.removeEpgMapping(ch)) popToCurrent() }
                    },
                )
                if (suggestions == null || search == null) {
                    add(Item.Info(LOADING_KEY, s.loadingShort, ""))
                    return@buildList
                }
                (suggestions as? AppViewModel.EpgResult.Ok)?.value?.filter { it.id != null && it.country != null }?.takeIf { it.isNotEmpty() }?.let { list ->
                    add(Item.Header("hs", s.epgSuggestions))
                    list.forEach { g ->
                        add(
                            Item.Check(
                                "s:${g.country}:${g.id}", g.name ?: g.id.orEmpty(),
                                mapping?.country == g.country && mapping?.channelId == g.id,
                                subtitle = listOfNotNull(g.countryName, g.now).joinToString(" · "),
                            ) { choose(g.country!!, g) },
                        )
                    }
                }
                add(Item.Header("hc", s.epgCountriesFor(query)))
                when (val r = search) {
                    is AppViewModel.EpgResult.Error -> add(Item.Info("err", r.message, ""))
                    is AppViewModel.EpgResult.Ok -> {
                        val countries = r.value.filter { it.country != null && !it.channels.isNullOrEmpty() }
                        if (countries.isEmpty()) add(Item.Info("none", s.epgNoResults(query), ""))
                        countries.forEach { c ->
                            val code = c.country!!
                            val name = c.countryName ?: code.uppercase()
                            add(Item.Nav("c:$code", name, value = c.channels!!.size.toString()) {
                                push(Route.EpgCountry(ch, code, name, c.channels), "c:$code")
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

        Route.EpgCountries -> {
            val countries by produceState<AppViewModel.EpgResult<List<EpgCountryInfo>>?>(null) { value = vm.epgCountries() }
            when (val r = countries) {
                null -> listOf(Item.Info(LOADING_KEY, s.loadingShort, ""))
                is AppViewModel.EpgResult.Error -> listOf(Item.Info("err", r.message, ""))
                is AppViewModel.EpgResult.Ok -> r.value
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
            val mapping = epgMappings[ch.key]
            val all by produceState<AppViewModel.EpgResult<EpgCountryResult>?>(null, route.country) {
                if (route.matches == null) value = vm.epgCountryChannels(route.country)
            }
            buildList {
                val channels = route.matches ?: when (val r = all) {
                    null -> {
                        add(Item.Info(LOADING_KEY, s.loadingShort, ""))
                        return@buildList
                    }
                    is AppViewModel.EpgResult.Error -> {
                        add(Item.Info("err", r.message, ""))
                        return@buildList
                    }
                    is AppViewModel.EpgResult.Ok -> r.value.channels.orEmpty()
                }
                channels.filter { it.id != null }.forEach { g ->
                    add(
                        Item.Check("g:${g.id}", g.name ?: g.id!!, mapping?.country == route.country && mapping?.channelId == g.id, subtitle = g.now) {
                            scope.launch { if (vm.setEpgMapping(ch, route.country, g)) popToCurrent() }
                        },
                    )
                }
                if (route.matches != null) {
                    add(Item.Nav("all", s.epgAllCountry(route.title(s))) {
                        push(Route.EpgCountry(ch, route.country, route.title(s), null), "all")
                    })
                }
                // Atalho: o mesmo país para todos os canais da lista com o mesmo nome.
                add(Item.Action("whole", s.epgCountryApply(route.title(s)), s.epgCountryGuideSub) {
                    vm.applyEpgCountry(route.country, route.title(s))
                    popToCurrent()
                })
            }
        }

        Route.Audio -> trackItems(tracks, C.TRACK_TYPE_AUDIO, player, allowOff = false)
        Route.Subtitles -> trackItems(tracks, C.TRACK_TYPE_TEXT, player, allowOff = true)
        Route.StreamInfo -> streamInfo(player, format)

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
            pref(Prefs.Key.LANGUAGE, s.language, listOf("auto" to s.languageAuto) + Strings.LANGUAGES, prefs.language),
            pref(
                Prefs.Key.UI_SCALE, s.uiScale,
                (Prefs.UI_SCALE_MIN..Prefs.UI_SCALE_MAX step 5).map { it to s.percent(it) },
                prefs.uiScale,
            ),
            Item.Toggle(Prefs.Key.SHOW_NUMBERS, s.channelNumbers, prefs.showNumbers) { vm.setPref(Prefs.Key.SHOW_NUMBERS, it) },
            Item.Toggle(Prefs.Key.SHOW_LOGOS, s.channelLogos, prefs.showLogos) { vm.setPref(Prefs.Key.SHOW_LOGOS, it) },
            pref(
                Prefs.Key.SORT, s.sortChannels,
                listOf(Prefs.Sort.NUMBER to s.sortNumber, Prefs.Sort.NAME to s.sortName),
                prefs.sort,
            ),
            pref(
                Prefs.Key.PANEL_WIDTH, s.panelWidth,
                listOf(300 to s.widthNarrow, 340 to s.widthNormal, 400 to s.widthWide, 460 to s.widthExtraWide),
                prefs.panelWidth,
            ),
            pref(
                Prefs.Key.TEXT_SIZE, s.textSize,
                listOf(Prefs.TextSize.SMALL to s.sizeSmall, Prefs.TextSize.NORMAL to s.sizeNormal, Prefs.TextSize.LARGE to s.sizeLarge),
                prefs.textSize,
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
                add(Item.Toggle("g:" + g.id, g.name, "${data.playlist.id}:${g.id}" in prefs.lockedGroups) { vm.toggleGroupLock(g.id) })
            }
        }

        Route.GroupsManage -> buildList {
            add(Item.Action("reorder", s.reorderGroups, s.reorderGroupsSub) { push(Route.GroupsOrder, "reorder") })
            // Sempre à vista: se desaparecesse ao carregar, o foco ficava sem sítio (visto na SHIELD).
            add(Item.Action("resetorder", s.resetOrder, s.resetOrderSub) { vm.resetGroupOrder() })
            add(Item.Header("h", s.visibleGroups))
            // Os dois grupos especiais valem para todas as listas.
            add(Item.Toggle(Prefs.Key.SHOW_FAVORITES_GROUP, s.favoritesStar, prefs.showFavoritesGroup) { vm.setPref(Prefs.Key.SHOW_FAVORITES_GROUP, it) })
            add(Item.Toggle(Prefs.Key.SHOW_ALL_GROUP, s.all, prefs.showAllGroup) { vm.setPref(Prefs.Key.SHOW_ALL_GROUP, it) })
            add(Item.Toggle(Prefs.Key.SHOW_RECENT_GROUP, s.recents, prefs.showRecentGroup) { vm.setPref(Prefs.Key.SHOW_RECENT_GROUP, it) })
            val data = content ?: return@buildList
            vm.orderedGroups(includeHidden = true).forEach { g ->
                val name = if (g.id == PlaylistContent.OTHERS) s.others else g.name
                add(Item.Toggle("g:" + g.id, name, !prefs.isHidden(data.playlist.id, g.id)) { visible -> vm.setGroupHidden(g.id, !visible) })
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
                add(Item.Toggle("c:" + ch.key, ch.name, !prefs.isChannelHidden(data.playlist.id, ch.key)) { visible -> vm.setChannelHidden(ch, !visible) })
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
            // Com conta: trocar o guia da lista inteira pelo de um país (canal a canal é em Canal atual → Guia).
            if (isCurrent && vm.epgPickerAvailable) {
                add(Item.Header("hepg", s.guide))
                add(Item.Nav("epgcountry", s.epgCountryGuide, subtitle = s.epgCountryGuideSub) { push(Route.EpgCountries, "epgcountry") })
                if (epgMappings.isNotEmpty()) {
                    add(
                        Item.Action("epgreset", if (confirmDelete) s.pressAgainToConfirm else s.epgResetAll, s.epgResetAllSub) {
                            if (confirmDelete) {
                                vm.resetEpgMappings()
                                confirmDelete = false
                            } else {
                                confirmDelete = true
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
        Route.NewPlaylistType -> listOf(
            Item.Action("xtream", "Xtream Codes", s.xtreamSub) {
                replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm(type = PlaylistType.XTREAM))))
            },
            Item.Action("m3u", s.m3uPlaylist, s.m3uSub) {
                replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm(type = PlaylistType.M3U))))
            },
        )

        is Route.PlaylistEdit -> buildList {
            val form = route.form.value
            fun set(f: (PlaylistForm) -> PlaylistForm) { route.form.value = f(route.form.value) }
            fun text(key: String, title: String, value: String, kind: TextKind, optional: Boolean = false, update: (PlaylistForm, String) -> PlaylistForm) =
                Item.Nav(
                    key, title,
                    value = when {
                        value.isEmpty() -> if (optional) s.optional else "—"
                        kind == TextKind.PASSWORD -> "•".repeat(value.length.coerceAtMost(8))
                        else -> value
                    },
                ) { push(Route.TextEdit(title, value, kind) { v -> set { update(it, v) } }, key) }

            val typeOptions = listOf<Pair<Any?, String>>(PlaylistType.XTREAM to "Xtream Codes", PlaylistType.M3U to s.m3uPlaylist)
            if (route.id != null && !local) {
                // Na conta, o tipo de uma lista não muda (para outro tipo cria-se uma lista nova).
                add(Item.Info("type", s.type, typeOptions.first { it.first == form.type }.second))
            } else {
                add(pick("type", s.type, typeOptions, form.type) { t -> set { it.copy(type = t as PlaylistType) } })
            }
            add(text("name", s.name, form.name, TextKind.PLAIN, optional = true) { f, v -> f.copy(name = v) })
            if (form.type == PlaylistType.XTREAM) {
                add(text("server", s.server, form.server, TextKind.URL) { f, v -> f.copy(server = v) })
                add(text("user", s.username, form.username, TextKind.PLAIN) { f, v -> f.copy(username = v) })
                add(text("pass", s.password, form.password, TextKind.PASSWORD) { f, v -> f.copy(password = v) })
            } else {
                add(text("url", s.playlistUrl, form.url, TextKind.URL) { f, v -> f.copy(url = v) })
            }
            add(text("epg", s.epgUrl, form.epgUrl, TextKind.URL, optional = true) { f, v -> f.copy(epgUrl = v) })
            add(Item.Action("save", s.save, form.problem() ?: if (local) s.playlistOnlyHere else s.playlistAccountHint) {
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
                    if (confirmLogout) vm.logoutThisTv() else confirmLogout = true
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

        Route.ParentalGate, Route.SettingsGate, Route.NewPin, Route.GroupsOrder, is Route.ChannelsOrder, is Route.TextEdit -> emptyList()
    }
}

@Composable
private fun ItemList(items: List<Item>, focusKey: String?, enabled: Boolean) {
    val focusable = items.filter { it !is Item.Header }
    val target = focusable.firstOrNull { it.key == focusKey }
        ?: focusable.firstOrNull { it is Item.Check && it.checked }
        ?: focusable.firstOrNull()
    val focus = remember { FocusRequester() }
    val start = remember { items.indexOf(target).coerceAtLeast(0) }
    val state = remember { LazyListState((start - 3).coerceAtLeast(0)) }
    LaunchedEffect(Unit) { if (target != null) focusWithRetry(focus) }

    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(items, key = { it.key }) { item ->
            val mod = if (item === target) Modifier.focusRequester(focus) else Modifier
            val dim = LocalContentColor.current.copy(alpha = 0.82f)
            when (item) {
                is Item.Header -> SectionLabel(item.text)
                is Item.Nav -> GlassRow(
                    enabled = enabled,
                    onClick = item.onClick,
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    trailing = item.value?.let { value ->
                        { TrailingValue(value, LocalContentColor.current.copy(alpha = 0.82f)) }
                    },
                    modifier = mod,
                )
                is Item.Toggle -> GlassRow(
                    enabled = enabled,
                    onClick = { item.onToggle(!item.checked) },
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    trailing = { Switch(item.checked) },
                    modifier = mod,
                )
                is Item.Check -> GlassRow(
                    enabled = enabled,
                    onClick = item.onClick,
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    leading = item.swatch?.let { swatch ->
                        {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(swatch))
                            Spacer(Modifier.width(12.dp))
                        }
                    },
                    trailing = if (item.checked) {
                        { Text("✓", color = LocalAccent.current.start) }
                    } else {
                        null
                    },
                    modifier = mod,
                )
                is Item.Action -> GlassRow(enabled = enabled, onClick = item.onClick, title = item.title, subtitle = item.subtitle, subtitleLines = 1, modifier = mod)
                is Item.Info -> GlassRow(
                    enabled = enabled,
                    onClick = {},
                    title = item.title,
                    trailing = { TrailingValue(item.value, dim) },
                    modifier = mod,
                )
            }
        }
    }
}

/** Reordenar grupos (ver [OrderPage]). */
@Composable
private fun GroupsOrderPage(vm: AppViewModel, enabled: Boolean) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val groups = remember(prefs.groupOrder, prefs.hiddenGroups) {
        vm.orderedGroups(includeHidden = true).map { it.id to if (it.id == PlaylistContent.OTHERS) s.others else it.name }
    }
    OrderPage(groups, enabled) { id, delta -> vm.moveGroup(id, delta) }
}

/** Reordenar os canais de um grupo (ver [OrderPage]). */
@Composable
private fun ChannelsOrderPage(vm: AppViewModel, groupId: String, enabled: Boolean) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val channels = remember(prefs.channelOrder, prefs.hiddenChannels) {
        vm.orderedChannels(groupId, includeHidden = true).map { it.key to it.name }
    }
    OrderPage(channels, enabled) { key, delta -> vm.moveChannel(groupId, key, delta) }
}

/** Reordenar uma lista de (id, nome): OK pega/larga um item; com ele "na mão", ↑/↓ movem-no. */
@Composable
private fun OrderPage(groups: List<Pair<String, String>>, enabled: Boolean, onMove: (id: String, delta: Int) -> Unit) {
    val s = LocalStrings.current
    var moving by remember { mutableStateOf<String?>(null) }
    val state = remember { LazyListState() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(first) }
    // O grupo em movimento fica sempre à vista.
    LaunchedEffect(groups, moving) {
        val index = groups.indexOfFirst { it.first == moving }
        val visible = state.layoutInfo.visibleItemsInfo
        if (index >= 0 && visible.isNotEmpty() && (index <= visible.first().index || index >= visible.last().index)) {
            state.scrollToItem((index - 3).coerceAtLeast(0))
        }
    }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(groups, key = { it.first }) { (id, name) ->
            val isMoving = moving == id
            GlassRow(
                enabled = enabled,
                onClick = { moving = if (isMoving) null else id },
                selected = isMoving,
                title = name,
                trailing = { Text(if (isMoving) s.moving else "↕", style = MaterialTheme.typography.bodySmall, color = if (isMoving) LocalAccent.current.start else LocalContentColor.current.copy(alpha = 0.6f)) },
                modifier = Modifier
                    .then(if (id == groups.firstOrNull()?.first) Modifier.focusRequester(first) else Modifier)
                    .onPreviewKeyEvent { e ->
                        if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key.nativeKeyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> { onMove(id, -1); true }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { onMove(id, 1); true }
                            else -> false
                        }
                    },
            )
        }
    }
}

/** Abre o ecrã do sistema para "Mostrar por cima de outras apps", se a box o tiver. */
private fun requestOverlayPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun typeLabel(type: PlaylistType) = if (type == PlaylistType.XTREAM) "Xtream" else "M3U"

/** Linha que está a carregar da rede (ver o `key` em SettingsContent). */
private const val LOADING_KEY = "loading"

/** "SIC · PT": o canal de guia e o país. */
private fun guideLabel(name: String?, country: String?) = listOfNotNull(name, country?.uppercase()).joinToString(" · ")

/** Campo de texto com o teclado da TV; OK no teclado (ou "Guardar") confirma, Voltar cancela. */
@Composable
private fun TextEditPage(route: Route.TextEdit, enabled: Boolean, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(route.initial, selection = TextRange(route.initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusWithRetry(focus)
        keyboard?.show()
    }
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(color = ItvColors.Text),
            cursorBrush = SolidColor(LocalAccent.current.start),
            visualTransformation = if (route.kind == TextKind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (route.kind) {
                    TextKind.URL -> KeyboardType.Uri
                    TextKind.PASSWORD -> KeyboardType.Password
                    TextKind.PLAIN -> KeyboardType.Text
                },
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .clip(ItvShapes.Control)
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
        Text(
            LocalStrings.current.typeHint,
            style = MaterialTheme.typography.bodySmall,
            color = ItvColors.TextDim,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp),
        )
        GlassRow(onClick = { onDone(value.text) }, title = LocalStrings.current.save, enabled = enabled)
    }
}

/** Valor à direita de uma linha: no máximo metade da largura, numa linha que anda com o foco. */
@Composable
private fun TrailingValue(value: String, color: Color) {
    Text(
        value,
        Modifier.widthIn(max = 180.dp).rowText(),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Interruptor com as cores da marca. */
@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(50))
            // Desligado: a cor do texto da linha, translúcida (escura na linha com foco, que é branca).
            .then(if (on) Modifier.background(LocalAccent.current.brush) else Modifier.background(LocalContentColor.current.copy(alpha = 0.25f)))
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

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
