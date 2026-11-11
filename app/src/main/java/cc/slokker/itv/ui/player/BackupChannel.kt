package cc.slokker.itv.ui.player

import androidx.compose.foundation.background
import cc.slokker.itv.ui.glass.GlassChipButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.ChannelMatch
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.focusWithRetry
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.delay

/**
 * O painel "Canal de reserva" do canal a dar (na TV e no telemóvel): o canal que a lista de reserva dá quando este
 * não abre. O automático (o do ChannelMatch) ou "sem correspondência"; os mais parecidos; a pesquisa em toda a
 * reserva; e "nenhum" (este canal não muda de lista). Escolher grava (ganha ao automático); "Automático" apaga a
 * escolha. "Experimentar agora" dá já o escolhido, com a ligação à lista principal fechada antes.
 */
@Stable
internal class BackupPanelState(private val vm: AppViewModel, val channel: Channel, private val mainId: String? = null) {
    var choices by mutableStateOf<AppViewModel.BackupChoices?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    /** A escolha à mão (chave da reserva, [ChannelMatch.OFF] ou null = automático). */
    var choice by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Channel>>(emptyList())
        private set

    suspend fun load() {
        loading = true
        choices = vm.backupChoices(channel, mainId)
        choice = choices?.choice
        loading = false
    }

    fun search() {
        results = choices?.let { vm.searchBackup(it, query) }.orEmpty()
    }

    /** Modo manual: o automático é só uma sugestão (e sem escolha não muda de lista). */
    val manual: Boolean get() = choices?.manual == true

    /** "Nenhum" está escolhido: à mão, ou (no modo manual) por não haver escolha. */
    val none: Boolean get() = choice == ChannelMatch.OFF || manual && choice == null

    /** O canal a experimentar: o escolhido, ou o automático; null com "nenhum" ou sem canal. */
    val effective: Channel?
        get() {
            val c = choices ?: return null
            return when (val chosen = choice) {
                null -> if (c.manual) null else c.autoChannel
                ChannelMatch.OFF -> null
                else -> c.content.byKey[chosen]
            }
        }

    fun pick(value: String?) {
        val main = choices?.mainId ?: return
        choice = value
        vm.setBackupChoice(main, channel.key, value)
    }

    /** "Automático: SIC HD", "Sem correspondência" ou "Em dúvida…". */
    fun autoLabel(s: Strings): String = when (val r = choices?.auto) {
        is ChannelMatch.Result.Match -> s.backupChannelAutoIs(choices?.autoChannel?.name.orEmpty())
        is ChannelMatch.Result.Unsure -> s.backupChannelUnsure
        else -> s.backupChannelNoMatch
    }
}

@Composable
internal fun rememberBackupPanel(vm: AppViewModel, channel: Channel, mainId: String? = null): BackupPanelState {
    val state = remember(channel.key, mainId) { BackupPanelState(vm, channel, mainId) }
    LaunchedEffect(state) { state.load() }
    // Procura enquanto se escreve, com uma pequena pausa (como a pesquisa de canais).
    LaunchedEffect(state, state.query, state.choices) {
        delay(250)
        state.search()
    }
    return state
}

/** O painel na TV, encostado à esquerda como os outros. Voltar fecha-o (ver TvBack). */
@Composable
internal fun BackupChannelPanel(vm: AppViewModel, channel: Channel, width: Dp, onTry: (Channel) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp)
            .width(width)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(s.backupChannel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        BackupChannelBody(vm, channel, null, onTry)
    }
}

/**
 * O conteúdo do painel (também nas Definições → lista → Canais de reserva, aí sem "Experimentar agora": [onTry] null).
 * [mainId]: a lista principal (null = a em uso).
 */
@Composable
internal fun BackupChannelBody(vm: AppViewModel, channel: Channel, mainId: String?, onTry: ((Channel) -> Unit)?) {
    val s = LocalStrings.current
    val state = rememberBackupPanel(vm, channel, mainId)
    val firstFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.loading) { if (!state.loading) focusWithRetry(firstFocus) }
    val choices = state.choices
    Column {
        Text(
            listOfNotNull(channel.name, choices?.backup?.name).joinToString(" → "),
            color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        )
        when {
            state.loading -> Text(s.backupChecking, color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))
            choices == null -> Text(s.backupUnavailable((mainId ?: vm.content.value?.playlist?.id)?.let(vm::backupOf)?.name.orEmpty()), color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                val effective = state.effective
                if (onTry != null) {
                    item(key = "try") {
                        GlassRow(
                            onClick = { effective?.let(onTry) },
                            enabled = effective != null,
                            modifier = Modifier.focusRequester(firstFocus),
                            leading = { Icon(ItvIcons.Play, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) },
                            title = s.backupChannelTry,
                            subtitle = effective?.name,
                        )
                    }
                }
                // Sem "Experimentar agora", o foco começa na primeira escolha.
                val first = if (onTry == null) Modifier.focusRequester(firstFocus) else Modifier
                item(key = "auto") {
                    val auto = choices.autoChannel
                    when {
                        // Manual: o automático é uma sugestão, que se escolhe com um toque.
                        state.manual && auto != null -> GlassRow(
                            onClick = { state.pick(auto.key) }, modifier = first, selected = state.choice == auto.key, trailing = check(state.choice == auto.key),
                            title = s.backupSuggestion(auto.name), subtitle = choices.content.categoryName(auto.categoryId),
                        )
                        state.manual -> GlassRow(onClick = {}, modifier = first, title = state.autoLabel(s))
                        else -> GlassRow(
                            onClick = { state.pick(null) }, modifier = first, selected = state.choice == null, trailing = check(state.choice == null),
                            title = s.backupChannelAuto, subtitle = state.autoLabel(s),
                        )
                    }
                }
                item(key = "off") {
                    GlassRow(onClick = { state.pick(ChannelMatch.OFF) }, selected = state.none, trailing = check(state.none), title = s.backupChannelOff)
                }
                // Um canal escolhido à mão que não está entre os parecidos continua à vista.
                val chosen = choices.content.byKey[state.choice ?: ""]
                val similar = (listOfNotNull(chosen) + choices.similar).distinctBy { it.key }
                if (similar.isNotEmpty()) item(key = "hs") { Header(s.backupChannelSimilar) }
                items(similar, key = { "s:" + it.key }) { c -> ChoiceRow(state, c, choices, s) }
                item(key = "search") {
                    BasicTextField(
                        value = state.query,
                        onValueChange = { state.query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleSmall.copy(color = ItvColors.Text),
                        cursorBrush = SolidColor(LocalAccent.current.start),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        decorationBox = { field ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(ItvIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = ItvColors.TextDim)
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f)) {
                                    if (state.query.isEmpty()) Text(s.backupChannelSearch(choices.backup.name), color = ItvColors.TextFaint, style = MaterialTheme.typography.titleSmall)
                                    field()
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .leavesWithArrows()
                            .fillMaxWidth()
                            .clip(ItvShapes.Control)
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
                if (state.results.isNotEmpty()) item(key = "hr") { Header(s.backupChannelResults) }
                items(state.results, key = { "r:" + it.key }) { c -> ChoiceRow(state, c, choices, s) }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, color = ItvColors.TextDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(state: BackupPanelState, c: Channel, choices: AppViewModel.BackupChoices, s: Strings) {
    val picked = state.choice == c.key
    GlassRow(
        onClick = { state.pick(c.key) },
        selected = picked,
        trailing = check(picked),
        title = c.name,
        subtitle = listOfNotNull(choices.content.categoryName(c.categoryId), s.backupChannelAuto.takeIf { c.key == choices.autoChannel?.key }).joinToString(" · "),
    )
}

private fun check(on: Boolean): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
    if (on) ({ Icon(ItvIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null

/** O que diz o estado de um canal na lista "Canais de reserva". */
internal fun ChannelMatch.State.label(s: Strings): String = when (this) {
    ChannelMatch.State.AUTO -> s.backupChannelAuto
    ChannelMatch.State.CHOSEN -> s.backupStateChosen
    ChannelMatch.State.OFF -> s.backupStateNone
    ChannelMatch.State.UNSURE -> s.backupStateUnsure
    ChannelMatch.State.MISSING -> s.backupStateMissing
    ChannelMatch.State.NOT_SET -> s.backupStateNotSet
}

internal fun ChannelMatch.Filter.label(s: Strings): String = when (this) {
    ChannelMatch.Filter.ALL -> s.all
    ChannelMatch.Filter.MATCHED -> s.backupFilterMatched
    ChannelMatch.Filter.UNSURE -> s.backupStateUnsure
    ChannelMatch.Filter.MISSING -> s.backupChannelNoMatch
    ChannelMatch.Filter.CHOSEN -> s.backupStateChosen
}

/** Uma linha da lista "Canais de reserva": "RTP 1 → RTP 1 FHD" e o estado (no manual sem escolha, a sugestão). */
internal fun backupRowText(s: Strings, review: AppViewModel.BackupReview, row: ChannelMatch.ReviewRow): Pair<String, String> {
    val main = review.main.channels[row.main].name
    val backup = row.backup?.let { review.backup.channels.getOrNull(it)?.name }
    val title = if (backup != null) "$main → $backup" else main
    val suggestion = row.suggestion?.let { review.backup.channels.getOrNull(it)?.name }?.let(s::backupSuggestion)
    return title to listOfNotNull(row.state.label(s), suggestion).joinToString(" · ")
}

/**
 * A lista "Canais de reserva" de uma lista (Definições na TV): cada canal em direto da lista principal, o da reserva e o
 * estado, com filtros e a pesquisa pelo nome. Feita uma vez (a mesma conta da cobertura); OK abre a escolha ([onOpen]).
 */
@Composable
internal fun BackupChannelsPage(
    vm: AppViewModel,
    playlistId: String,
    enabled: Boolean,
    filterState: MutableState<ChannelMatch.Filter>,
    query: String,
    onSearch: () -> Unit,
    onOpen: (Channel) -> Unit,
) {
    val s = LocalStrings.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val coverage by vm.backupCoverage.collectAsStateWithLifecycle()
    LaunchedEffect(playlistId, prefs.backupChannels, prefs.backupMode) { vm.checkBackupCoverage(playlistId) }
    val review = (coverage[playlistId] as? AppViewModel.BackupCoverage.Ready)?.review
    var filter by filterState
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(review != null) { if (review != null) focusWithRetry(firstFocus) }
    if (review == null) {
        Text(s.backupChecking, color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))
        return
    }
    val counts = remember(review) { ChannelMatch.Filter.entries.associateWith { f -> review.rows.count { with(ChannelMatch) { it.passes(f) } } } }
    val shown = remember(review, filter, query) {
        review.rows.filter { with(ChannelMatch) { it.passes(filter) } && vm.backupReviewMatches(review.main.channels[it.main].name, query) }
    }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ChannelMatch.Filter.entries.forEach { f ->
                GlassChipButton(
                    if (f == filter) ItvIcons.Check else ItvIcons.Channels, "${f.label(s)} (${counts[f]})", { filter = f },
                    // O foco começa (e volta) no filtro escolhido, não sempre no primeiro.
                    if (f == filter) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        // Uma linha que abre o teclado numa página à parte (como a pesquisa do guia): um campo aqui abria o teclado
        // ao receber o foco e as setas ficavam presas nele, sem chegar aos canais.
        GlassRow(
            onClick = onSearch,
            enabled = enabled,
            leading = { Icon(ItvIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = ItvColors.TextDim); Spacer(Modifier.width(12.dp)) },
            title = query.ifEmpty { s.backupChannelSearch(review.main.playlist.name) },
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(shown, key = { it.main }) { row ->
                val (title, subtitle) = backupRowText(s, review, row)
                GlassRow(onClick = { onOpen(review.main.channels[row.main]) }, enabled = enabled, title = title, subtitle = subtitle)
            }
        }
    }
}

/**
 * As setas para cima e para baixo saem do campo de pesquisa (para os filtros ou para a lista): no Android TV o campo
 * ficava com elas e não se chegava aos canais com o comando. O teclado fecha-se ao sair.
 */
@Composable
private fun Modifier.leavesWithArrows(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val direction = when (e.key) {
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionUp -> FocusDirection.Up
            else -> return@onPreviewKeyEvent false
        }
        keyboard?.hide()
        focus.moveFocus(direction)
    }
}
