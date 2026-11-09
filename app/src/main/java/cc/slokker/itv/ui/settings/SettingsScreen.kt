package cc.slokker.itv.ui.settings

import android.net.Uri
import java.util.Locale
import cc.slokker.itv.ui.ReportState
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.player.channelsLabel
import cc.slokker.itv.ui.player.codecName
import cc.slokker.itv.ui.player.NetVerdict
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
import androidx.compose.ui.graphics.vector.ImageVector
import cc.slokker.itv.ui.common.LocalChannelLogos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
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
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.maskEmail
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.player.HdrType
import cc.slokker.itv.ui.player.dynamicRangeLabel
import kotlinx.coroutines.launch

/** Nome da origem dos logótipos: a lista, o guia de cada canal, ou o país (pelo código). */
private fun logoSourceLabel(source: String, s: Strings): String = when (source) {
    Prefs.LogoSource.PLAYLIST -> s.logoFromPlaylist
    Prefs.LogoSource.GUIDE -> s.logoFromGuide
    else -> Locale("", source.uppercase()).displayCountry.takeIf { it.isNotBlank() && !it.equals(source, ignoreCase = true) } ?: source.uppercase()
}

/** O que as páginas usam para navegar (ver SettingsContent) e para as escolhas de uma lista de opções. */
internal class PageNav(
    private val vm: AppViewModel,
    val push: (Route, String) -> Unit,
    val pop: () -> Unit,
    /** Recua uma página, mas nunca fecha as Definições. */
    val popNested: () -> Unit,
    val replace: (Route) -> Unit,
    /** Volta à página "Canal atual"; aberto pelo HUD, fecha. */
    val popToCurrent: () -> Unit,
) {
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

    /** A linha de uma escolha do catálogo: o valor à direita; carregar abre as opções (com amostras de cor, se as tem). */
    fun choice(setting: Setting.Choice, values: SettingValues, s: Strings, subtitle: String? = setting.subtitle?.invoke(s)): Item {
        val title = setting.title(s)
        val options = setting.options(s)
        val selected = setting.read(values)
        return Item.Nav(setting.key, title, options.firstOrNull { it.first == selected }?.second, subtitle) {
            push(
                Route.Pick(title, options, selected, swatches = setting.swatches?.invoke(values.prefs).orEmpty()) { v ->
                    vm.saveSetting(setting, v)
                    pop()
                },
                setting.key,
            )
        }
    }

    /** A linha normal de uma definição do catálogo (as [Setting.Custom] não têm: desenha-as a página). */
    fun item(setting: Setting, values: SettingValues, s: Strings): Item? = when (setting) {
        is Setting.Choice -> choice(setting, values, s)
        is Setting.Toggle -> Item.Toggle(setting.key, setting.title(s), setting.read(values.prefs), setting.subtitle?.invoke(s)) { vm.setPref(setting.key, it) }
        is Setting.Custom -> null
    }

    /**
     * As linhas da página [page] do catálogo, secção a secção (com o título por cima). [custom] desenha as linhas que
     * a página faz à sua maneira; null fica com a normal.
     */
    fun catalogItems(page: TvPage, values: SettingValues, s: Strings, custom: (Setting) -> Item? = { null }): List<Item> = buildList {
        for ((section, settings) in SettingsCatalog.tv(page)) {
            val title = section.title
            if (title != null && section.headerKey != null) add(Item.Header(section.headerKey, title(s)))
            settings.forEach { setting -> (custom(setting) ?: item(setting, values, s))?.let(::add) }
        }
    }
}

/**
 * As linhas da página [route]. Cada zona das Definições tem a sua função: lê só os dados de que precisa e guarda o
 * seu próprio estado (ex.: o "carregar outra vez para confirmar"), para uma página não mexer noutra.
 */
@Composable
internal fun settingItems(
    route: Route,
    vm: AppViewModel,
    player: Player?,
    playing: Channel?,
    onManualTrack: (Int) -> Unit,
    push: (Route, String) -> Unit,
    pop: () -> Unit,
    popNested: () -> Unit,
    replace: (Route) -> Unit,
    popToCurrent: () -> Unit,
): List<Item> {
    val nav = PageNav(vm, push, pop, popNested, replace, popToCurrent)
    return when (route) {
        Route.Root -> rootItems(vm, player, playing, nav)
        Route.Current -> currentItems(vm, player, playing, nav)
        is Route.EpgPick -> epgPickItems(route, vm, nav)
        Route.LogoSource -> logoSourceItems(vm, nav)
        Route.EpgCountries -> epgCountriesItems(vm, nav)
        is Route.EpgCountry -> epgCountryItems(route, vm, nav)
        Route.Audio -> trackItems(rememberStreamState(player).tracks, C.TRACK_TYPE_AUDIO, player, allowOff = false, onManualTrack)
        Route.Subtitles -> trackItems(rememberStreamState(player).tracks, C.TRACK_TYPE_TEXT, player, allowOff = true, onManualTrack)
        Route.StreamInfo -> {
            val format by vm.streamFormat.collectAsStateWithLifecycle()
            streamInfo(player, format)
        }
        Route.Connection -> connectionItems(vm, player, nav)
        Route.Playback, Route.General, Route.Zapping, Route.Appearance, Route.Guide -> prefPageItems(route, vm, nav)
        Route.Parental, Route.LockedGroups -> parentalItems(route, vm, nav)
        Route.GroupsManage, Route.ChannelGroups, is Route.ChannelsManage -> organizeItems(route, vm, nav)
        Route.Lists, is Route.PlaylistDetail, Route.NewPlaylistType, is Route.PlaylistEdit -> playlistItems(route, vm, nav)
        Route.Account, Route.Licenses -> accountItems(route, vm, nav)
        is Route.Pick -> route.options.mapIndexed { i, (value, label) ->
            Item.Check("o:$i", label, value == route.selected, swatch = route.swatches[value]) { route.onPick(value) }
        }
        Route.ParentalGate, Route.LogoutGate, Route.SettingsGate, Route.NewPin, Route.GroupsOrder, is Route.ChannelsOrder, is Route.TextEdit -> emptyList()
    }
}

/** A atualização da app: título e estado (a descarregar, a instalar…); null quando não há nada a mostrar. */
private fun updateItem(updateState: UpdateState, vm: AppViewModel, s: Strings, key: String, icon: ImageVector? = null): Item? = when (val u = updateState) {
    is UpdateState.Available -> Item.Action(key, s.updateTo(u.release.version), u.release.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart('-', '*', ' '), icon) { vm.installUpdate() }
    is UpdateState.Downloading -> Item.Action(key, s.updateTo(u.release.version), s.downloading((u.progress * 100).toInt()), icon) {}
    is UpdateState.Installing -> Item.Action(key, s.updateTo(u.release.version), s.installing, icon) {}
    // Falhou: o motivo por extenso; carregar procura outra vez e volta a oferecer a atualização.
    is UpdateState.Failed -> Item.Action(key, s.updateFailedTitle, u.message, icon) { vm.checkForUpdate(manual = true) }
    else -> null
}


// ---- Página principal e canal atual --------------------------------------------------------

@Composable
private fun rootItems(vm: AppViewModel, player: Player?, playing: Channel?, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val email by vm.email.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val updateState by vm.update.collectAsStateWithLifecycle()
    val channelLogos = LocalChannelLogos.current
    val s = LocalStrings.current
    val push = nav.push
    return buildList {
        // No topo, sem secção: o canal a ver e a atualização (se houver). Depois as páginas, por secções.
        if (player != null && playing != null) {
            add(
                Item.Nav("current", playing.name, subtitle = s.currentChannel, logo = channelLogos[playing.key] ?: playing.logo, showLogo = true) {
                    push(Route.Current, "current")
                },
            )
        }
        updateItem(updateState, vm, s, "update", ItvIcons.Update)?.let { add(it) }
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
}

@Composable
private fun currentItems(vm: AppViewModel, player: Player?, playing: Channel?, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val epgMappings by vm.epgMappings.collectAsStateWithLifecycle()
    val tracks = rememberStreamState(player).tracks
    val s = LocalStrings.current
    val push = nav.push
    return buildList {
        val ch = playing ?: return@buildList
        val isFav = ch.key in favorites[content?.playlist?.id].orEmpty()
        add(Item.Toggle("fav", s.favorite, isFav, ch.name) { vm.toggleFavorite(ch) })
        add(Item.Nav("audio", s.audio, tracks.audioLabel()) { push(Route.Audio, "audio") })
        add(Item.Nav("subs", s.subtitles, tracks.textLabel(player)) { push(Route.Subtitles, "subs") })
        add(nav.choice(SettingsCatalog.ASPECT, SettingValues(prefs, null), s))
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
}

// ---- Guia e logótipo de um canal, origem dos logótipos, guia de um país -------------------------

@Composable
private fun epgPickItems(route: Route.EpgPick, vm: AppViewModel, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val epgMappings by vm.epgMappings.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val popToCurrent = nav.popToCurrent
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
    return buildList {
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
                        nav.push(Route.EpgCountry(ch, code, name, c.channels, logo), "c:$code")
                    })
                }
            }
            null -> Unit
        }
        add(Item.Action("other", s.epgSearchOther) {
            nav.push(Route.TextEdit(s.epgSearchOther, query, TextKind.PLAIN) { v -> if (v.isNotBlank()) route.query.value = v.trim() }, "other")
        })
    }
}

@Composable
private fun logoSourceItems(vm: AppViewModel, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val countries by produceState<EpgResult<List<EpgCountryInfo>>?>(null) {
        if (vm.epgPickerAvailable) value = vm.epgCountries()
    }
    fun choose(source: String?) {
        vm.setPref(Prefs.Key.LOGO_SOURCE, source)
        nav.popNested()
    }
    return buildList {
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

@Composable
private fun epgCountriesItems(vm: AppViewModel, nav: PageNav): List<Item> {
    val s = LocalStrings.current
    val countries by produceState<EpgResult<List<EpgCountryInfo>>?>(null) { value = vm.epgCountries() }
    return when (val r = countries) {
        null -> listOf(Item.Info(LOADING_KEY, s.loadingShort, ""))
        is EpgResult.Error -> listOf(Item.Info("err", r.message, ""))
        is EpgResult.Ok -> r.value
            .filter { it.code != null && (it.channels ?: 0) > 0 }
            .sortedBy { it.name ?: it.code }
            .map { c ->
                val name = c.name ?: c.code!!.uppercase()
                Item.Action("k:${c.code}", name, (c.channels ?: 0).toString()) {
                    vm.applyEpgCountry(c.code!!, name)
                    nav.popNested()
                }
            }
    }
}

@Composable
private fun epgCountryItems(route: Route.EpgCountry, vm: AppViewModel, nav: PageNav): List<Item> {
    val epgMappings by vm.epgMappings.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val popToCurrent = nav.popToCurrent
    val ch = route.channel
    val logo = route.logo
    val mapping = epgMappings[ch.key]
    val logoChoice = vm.channelLogoChoice(ch)
    val all by produceState<EpgResult<EpgCountryResult>?>(null, route.country) {
        if (route.matches == null) value = vm.epgCountryChannels(route.country)
    }
    return buildList {
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
                nav.push(Route.EpgCountry(ch, route.country, route.title(s), null, logo), "all")
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

// ---- Ligação ---------------------------------------------------------------------------------

@Composable
private fun connectionItems(vm: AppViewModel, player: Player?, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val format by vm.streamFormat.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val push = nav.push
    val pop = nav.pop
    return buildList {
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
            val buffers = SettingsCatalog.BUFFER.options(s)
            add(
                Item.Nav(Prefs.Key.BUFFER, s.buffer, buffers.firstOrNull { it.first == prefs.buffer }?.second, s.netBufferSub, ItvIcons.Buffer) {
                    push(Route.Pick(s.buffer, buffers, prefs.buffer) { v -> vm.setPref(Prefs.Key.BUFFER, v); pop() }, Prefs.Key.BUFFER)
                },
            )
            val formats = SettingsCatalog.STREAM_FORMAT.options(s)
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
}

// ---- Páginas só de definições: reprodução, geral, mudança de canal, aspeto, guia ------------------

@Composable
private fun prefPageItems(route: Route, vm: AppViewModel, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val format by vm.streamFormat.collectAsStateWithLifecycle()
    val values = SettingValues(prefs, format)
    val s = LocalStrings.current
    val context = LocalContext.current
    val push = nav.push
    return when (route) {
        Route.Playback -> {
            val displayHdr = remember { HdrType.supportedByDisplay(context) }
            nav.catalogItems(TvPage.PLAYBACK, values, s) { setting ->
                when (setting) {
                    SettingsCatalog.HDR -> nav.choice(
                        SettingsCatalog.HDR, values, s,
                        subtitle = if (displayHdr.isEmpty()) s.hdrTvNone else s.hdrTvSupports(displayHdr.joinToString(", ") { it.label }),
                    )
                    else -> null
                }
            }
        }

        Route.General -> {
            // Volta a verificar sempre que a página é mostrada (a pessoa pode ter ido dar a permissão).
            val canDrawOverlays = remember(route) { Settings.canDrawOverlays(context) }
            nav.catalogItems(TvPage.GENERAL, values, s) { setting ->
                when (setting) {
                    SettingsCatalog.AUTO_START -> Item.Toggle(
                        Prefs.Key.AUTO_START, s.autoStart, prefs.autoStart,
                        if (prefs.autoStart && !canDrawOverlays) s.overlayNeeded else s.autoStartSub,
                    ) { on ->
                        vm.setPref(Prefs.Key.AUTO_START, on)
                        // O Android só deixa abrir a app no arranque com "Mostrar por cima de outras apps".
                        if (on && !canDrawOverlays) requestOverlayPermission(context)
                    }
                    SettingsCatalog.USER_AGENT -> Item.Nav(Prefs.Key.USER_AGENT, s.userAgent, value = prefs.userAgent.ifBlank { s.userAgentDefault }, subtitle = s.userAgentSub) {
                        push(
                            Route.TextEdit(s.userAgent, prefs.userAgent, TextKind.PLAIN) { v -> vm.setPref(Prefs.Key.USER_AGENT, v.trim().ifEmpty { null }) },
                            Prefs.Key.USER_AGENT,
                        )
                    }
                    else -> null
                }
            }
        }

        Route.Zapping -> nav.catalogItems(TvPage.ZAPPING, values, s)

        Route.Appearance -> nav.catalogItems(TvPage.APPEARANCE, values, s) { setting ->
            when (setting) {
                SettingsCatalog.LOGO_SOURCE -> Item.Nav(Prefs.Key.LOGO_SOURCE, s.logoSource, value = logoSourceLabel(prefs.logoSource, s)) {
                    push(Route.LogoSource, Prefs.Key.LOGO_SOURCE)
                }
                else -> null
            }
        }

        Route.Guide -> nav.catalogItems(TvPage.GUIDE, values, s)

        else -> emptyList()
    }
}

// ---- Controlo parental -------------------------------------------------------------------------

@Composable
private fun parentalItems(route: Route, vm: AppViewModel, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val push = nav.push
    return when (route) {
        Route.Parental -> buildList {
            if (prefs.pinHash == null) {
                add(Item.Action("newpin", s.setPin, s.setPinSub) { push(Route.NewPin, "newpin") })
            } else {
                val data = content
                val locked = if (data == null) 0 else prefs.lockedGroupCount(data.playlist.id)
                add(Item.Nav("locked", s.lockedGroups, locked.toString()) { push(Route.LockedGroups, "locked") })
                add(Item.Toggle(Prefs.Key.SETTINGS_PIN, s.protectSettings, prefs.settingsPin, s.protectSettingsSub) { vm.setPref(Prefs.Key.SETTINGS_PIN, it) })
                add(Item.Action("newpin", s.changePin) { push(Route.NewPin, "newpin") })
                add(
                    Item.Action("nopin", s.parentalOff, s.parentalOffSub) {
                        vm.setPin(null)
                        vm.showToast(s.parentalOffToast)
                        nav.pop()
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
                add(Item.Toggle("g:" + g.id, name, prefs.isGroupMarkedLocked(data.playlist.id, g.id)) { vm.toggleGroupLock(g.id) })
            }
        }

        else -> emptyList()
    }
}

// ---- Organizar: grupos e canais da lista em uso -------------------------------------------------

@Composable
private fun organizeItems(route: Route, vm: AppViewModel, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val push = nav.push
    return when (route) {
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

        else -> emptyList()
    }
}

// ---- Listas: as listas, uma lista, criar e editar ----------------------------------------------

@Composable
private fun playlistItems(route: Route, vm: AppViewModel, nav: PageNav): List<Item> {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val epgMappings by vm.epgMappings.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val push = nav.push
    // Um "carregar outra vez para confirmar" por ação e por página: armar um não pode confirmar o outro.
    var confirmEpgReset by remember(route) { mutableStateOf(false) }
    var confirmDelete by remember(route) { mutableStateOf(false) }
    return when (route) {
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
            add(nav.choice(SettingsCatalog.PLAYLIST_REFRESH, SettingValues(prefs, null), s))
            add(nav.choice(SettingsCatalog.EPG_REFRESH, SettingValues(prefs, null), s))
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
                val hidden = prefs.hiddenGroupCount(p.id)
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
                            if (error == null) nav.popNested()
                        }
                    }
                },
            )
        }

        // O formulário substitui esta página: ao gravar (ou Voltar) regressa-se às Listas.
        Route.NewPlaylistType -> listOfNotNull(
            Item.Action("xtream", "Xtream Codes", s.xtreamSub) {
                nav.replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm(type = PlaylistType.XTREAM))))
            },
            Item.Action("m3u", s.m3uPlaylist, s.m3uSub) {
                nav.replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm(type = PlaylistType.M3U))))
            },
            Item.Action("stalker", "Stalker / MAC", s.stalkerSub) {
                nav.replace(Route.PlaylistEdit(null, mutableStateOf(PlaylistForm().withType(PlaylistType.STALKER, vm.deviceMac))))
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
                add(nav.pick("type", s.type, typeOptions, form.type) { t -> set { it.withType(t as PlaylistType, vm.deviceMac) } })
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
                        nav.popNested()
                    }
                }
            })
        }

        else -> emptyList()
    }
}

// ---- Conta e licenças --------------------------------------------------------------------------

@Composable
private fun accountItems(route: Route, vm: AppViewModel, nav: PageNav): List<Item> {
    val email by vm.email.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val updateState by vm.update.collectAsStateWithLifecycle()
    val reportState by vm.report.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val push = nav.push
    var confirmLogout by remember { mutableStateOf(false) }
    return when (route) {
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
            updateItem(updateState, vm, s, "checkupdate") ?: Item.Action(
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

        else -> emptyList()
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
internal const val LOADING_KEY = "loading"

/** "SIC · PT": o canal de guia e o país. */
private fun guideLabel(name: String?, country: String?) = listOfNotNull(name, country?.uppercase()).joinToString(" · ")

// ---- Faixas de áudio e legendas, informação do stream ---------------------------------------

private fun trackItems(tracks: Tracks, type: Int, player: Player?, allowOff: Boolean, onManual: (Int) -> Unit): List<Item> {
    val options = trackOptions(tracks, type, player, allowOff, onManual)
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

