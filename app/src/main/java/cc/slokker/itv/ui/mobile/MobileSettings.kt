package cc.slokker.itv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.MainActivity
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.kind
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.ReportState
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.maskEmail
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.launch
import cc.slokker.itv.ui.settings.PhonePage
import cc.slokker.itv.ui.settings.PhoneSection
import cc.slokker.itv.ui.settings.Setting
import cc.slokker.itv.ui.settings.SettingValues
import cc.slokker.itv.ui.settings.SettingsCatalog
import cc.slokker.itv.ui.settings.saveSetting

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
    // A lista aberta (usar, recarregar, editar, remover) e a que se está a editar.
    var opened by remember { mutableStateOf<Playlist?>(null) }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmPinOff by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var managingGroups by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    val format by vm.streamFormat.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    if (adding) return PlaylistFormPage(vm) { adding = false }
    editing?.let { p -> return PlaylistFormPage(vm, p) { editing = null; opened = null } }
    opened?.let { p -> return PlaylistPage(vm, p, onEdit = { editing = p }) { opened = null } }
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
    val values = SettingValues(prefs, format)
    fun label(setting: Setting.Choice) = setting.label(s, values, phone = true)
    val pipSupported = (LocalContext.current as? MainActivity)?.pipSupported == true
    page?.let { BackHandler { page = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBarSpace.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (page) {
            null -> {
                Text(s.settings, color = ItvColors.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp))
                // A lista em uso em destaque, como o canal atual nas Definições da TV; as outras por baixo.
                content?.let { c ->
                    val type = c.playlist.type.kind.label
                    PlaylistCard(c.playlist.name, "$type · ${s.groupChannels(c.channels.size)}") { opened = c.playlist }
                }
                // Como na TV: cada lista abre-se (usar, recarregar, editar, remover) e há sempre "Adicionar lista".
                val others = playlists.filter { it.id != content?.playlist?.id }
                Section(s.playlists) {
                    others.forEach { p -> MenuRow(p.name, p.type.kind.label, ItvIcons.Playlist, chevron = true) { opened = p } }
                    MenuRow(s.addPlaylist, null, ItvIcons.Playlist, chevron = true) { adding = true }
                }
                Section(s.sectionWatching) {
                    MenuRow(s.playback, label(SettingsCatalog.STREAM_FORMAT).substringBefore(" ") + ", " + label(SettingsCatalog.BUFFER).substringBefore(" ("), ItvIcons.PlayCircle, chevron = true) { page = MenuPage.Playback }
                    MenuRow(s.manageChannels, label(SettingsCatalog.PHONE_STARTUP), ItvIcons.SwapVert, chevron = true) { page = MenuPage.Channels }
                    MenuRow(s.guide, label(SettingsCatalog.EPG_REFRESH), ItvIcons.Calendar, chevron = true) { page = MenuPage.Guide }
                }
                Section(s.sectionInterface) {
                    MenuRow(s.settingsAppearance, label(SettingsCatalog.LANGUAGE) + ", " + label(SettingsCatalog.ACCENT), ItvIcons.Palette, chevron = true) { page = MenuPage.Appearance }
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
                    // Carregar outra vez para confirmar (como na TV).
                    if (!local) MenuRow(if (confirmLogout) s.pressAgainToConfirm else s.logout, email?.let(::maskEmail), ItvIcons.Account) {
                        when {
                            !confirmLogout -> confirmLogout = true
                            vm.logoutNeedsPin() -> { confirmLogout = false; logoutGate = true }
                            else -> vm.logoutThisTv()
                        }
                    }
                    // Sem conta: ligar a conta passa as definições deste telemóvel para lá e sincroniza tudo.
                    else MenuRow(s.connectAccount, null, ItvIcons.Account, chevron = true, onClick = vm::connectAccount)
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
                CatalogSections(PhonePage.PLAYBACK, vm, values, open) { setting ->
                    when (setting) {
                        // Só nos telemóveis com janela pequena.
                        SettingsCatalog.PIP -> {
                            if (pipSupported) ToggleRow(s.pictureInPicture, prefs.pipEnabled) { vm.setPref(Prefs.Key.PIP_ENABLED, it) }
                            true
                        }
                        SettingsCatalog.USER_AGENT -> {
                            MenuRow(s.userAgent, prefs.userAgent.ifBlank { s.userAgentDefault }, chevron = true) { editingAgent = true }
                            true
                        }
                        else -> false
                    }
                }
            }
            MenuPage.Channels -> {
                PageHeader(s.manageChannels, s.settings) { page = null }
                CatalogSections(PhonePage.CHANNELS, vm, values, open) { setting ->
                    when (setting) {
                        SettingsCatalog.LOGO_SOURCE -> {
                            if (vm.epgPickerAvailable) MenuRow(s.logoSource, null, chevron = true) { pickingCountry = true }
                            true
                        }
                        SettingsCatalog.VISIBLE_GROUPS -> {
                            MenuRow(s.visibleGroups, null, chevron = true) { managingGroups = true }
                            true
                        }
                        else -> false
                    }
                }
            }
            MenuPage.Guide -> {
                PageHeader(s.guide, s.settings) { page = null }
                // Depois das definições, no mesmo cartão: o guia de um país, repor as escolhas e atualizar.
                CatalogSections(PhonePage.GUIDE, vm, values, open, after = { section ->
                    if (section == PhoneSection.MAIN) {
                        if (vm.epgPickerAvailable) MenuRow(s.epgCountryGuide, null, chevron = true) { pickingCountry = false }
                        if (vm.epgPickerAvailable && epgMappings.isNotEmpty()) {
                            MenuRow(if (confirmingEpgReset) s.pressAgainToConfirm else s.epgResetAll, null) {
                                if (confirmingEpgReset) vm.resetEpgMappings()
                                confirmingEpgReset = !confirmingEpgReset
                            }
                        }
                        MenuRow(s.refreshEpg, null, onClick = vm::refreshEpg)
                    }
                }) { false }
            }
            MenuPage.Appearance -> {
                PageHeader(s.settingsAppearance, s.settings) { page = null }
                CatalogSections(PhonePage.APPEARANCE, vm, values, open) { false }
            }
            MenuPage.Parental -> {
                PageHeader(s.parental, s.settings) { page = null }
                Section(null) {
                    if (prefs.pinHash == null) {
                        MenuRow(s.setPin, null) { choosingPin = true }
                    } else {
                        ToggleRow(s.protectSettings, prefs.settingsPin) { vm.setPref(Prefs.Key.SETTINGS_PIN, it) }
                        MenuRow(s.changePin, null) { choosingPin = true }
                        MenuRow(if (confirmPinOff) s.pressAgainToConfirm else s.parentalOff, null) {
                            if (confirmPinOff) {
                                vm.setPin(null)
                                vm.showToast(s.parentalOffToast)
                            }
                            confirmPinOff = !confirmPinOff
                        }
                        vm.orderedGroups(includeHidden = true).forEach { g ->
                            ToggleRow("🔒 " + g.name, vm.isGroupLockedSetting(g.id)) { vm.toggleGroupLock(g.id) }
                        }
                    }
                }
            }
        }
    }
}

/** A lista em uso, no topo das Definições. */
@Composable
private fun PlaylistCard(name: String, detail: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(PhoneShapes.Card)
            .background(Brush.linearGradient(listOf(PhoneColors.HeroTop, PhoneColors.PlaylistCardBottom))).clickable(onClick = onClick).padding(16.dp),
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
        Icon(ItvIcons.Chevron, null, tint = ItvColors.TextDim, modifier = Modifier.size(20.dp))
    }
}

/** Uma lista: usá-la, recarregar os canais, editar e remover (como Listas → a lista, na TV). */
@Composable
private fun PlaylistPage(vm: AppViewModel, playlist: Playlist, onEdit: () -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val content by vm.content.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val isCurrent = playlist.id == content?.playlist?.id
    BackHandler(onBack = onClose)
    SubPage(playlist.name, onClose) {
        if (!isCurrent) MenuRow(s.usePlaylist, null) { vm.switchPlaylist(playlist); onClose() }
        MenuRow(s.reloadChannels, null) {
            if (isCurrent) vm.reloadPlaylist() else vm.switchPlaylist(playlist)
            onClose()
        }
        MenuRow(s.edit, null, chevron = true, onClick = onEdit)
        MenuRow(
            if (confirmDelete) s.removePlaylistConfirm else s.removePlaylist,
            if (confirmDelete || local) null else s.removePlaylistAccountSub,
        ) {
            if (!confirmDelete) {
                confirmDelete = true
            } else {
                confirmDelete = false
                scope.launch {
                    val error = vm.deletePlaylist(playlist)
                    vm.showToast(error ?: s.playlistRemoved)
                    if (error == null) onClose()
                }
            }
        }
    }
}

/**
 * As linhas da página [page] do catálogo, cada secção no seu cartão. [custom] desenha as linhas que esta página faz
 * à sua maneira (devolve true quando a desenhou, ou a escondeu); [after] junta outras no fim do cartão de uma secção.
 */
@Composable
private fun CatalogSections(
    page: PhonePage,
    vm: AppViewModel,
    values: SettingValues,
    open: (Picking) -> Unit,
    after: @Composable (PhoneSection) -> Unit = {},
    custom: @Composable (Setting) -> Boolean,
) {
    val s = LocalStrings.current
    SettingsCatalog.phone(page).forEach { (section, settings) ->
        Section(section.title?.invoke(s)) {
            settings.forEach { setting ->
                if (!custom(setting)) {
                    when (setting) {
                        is Setting.Choice -> PickRow(setting.title(s), setting.phoneOptions(s), setting.read(values), open) { vm.saveSetting(setting, it) }
                        is Setting.Toggle -> ToggleRow(setting.title(s), setting.read(values.prefs)) { vm.setPref(setting.key, it) }
                        is Setting.Custom -> Unit
                    }
                }
            }
            after(section)
        }
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

/** Nova lista ([editing] null) ou editar uma: neste aparelho (sem conta) ou na conta. */
@Composable
fun PlaylistFormPage(vm: AppViewModel, editing: Playlist? = null, onDone: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val local by vm.local.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf(editing?.let(PlaylistForm::from) ?: PlaylistForm()) }
    // Na conta, o tipo de uma lista não muda (como na TV: para outro tipo cria-se uma lista nova).
    val typeLocked = editing != null && !local
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDone)
    Column(
        Modifier.fillMaxSize().background(ItvColors.Background).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (editing != null) s.edit else s.addPlaylist, color = ItvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val types = listOf(PlaylistType.XTREAM to "Xtream", PlaylistType.M3U to "M3U") +
                if (vm.canAddStalker || form.type == PlaylistType.STALKER) listOf(PlaylistType.STALKER to "Stalker") else emptyList()
            types.filter { !typeLocked || it.first == form.type }.forEach { (type, label) ->
                val on = form.type == type
                Text(
                    label, color = if (on) Color.Black else ItvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(if (on) Color.White else PhoneColors.Card).clickable(enabled = !typeLocked) { form = form.withType(type, vm.deviceMac) }.padding(horizontal = 18.dp, vertical = 9.dp),
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
            modifier = Modifier.clip(PhoneShapes.Card).background(LocalAccent.current.brush).clickable {
                val problem = form.problem()
                if (problem != null) {
                    error = problem
                } else {
                    scope.launch {
                        error = vm.savePlaylist(editing?.id, form)
                        if (error == null) onDone()
                    }
                }
            }.padding(horizontal = 28.dp, vertical = 13.dp),
        )
    }
}
