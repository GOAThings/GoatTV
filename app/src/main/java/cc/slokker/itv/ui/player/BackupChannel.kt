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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.LocalContentColor
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
 * O painel "Canal de reserva" do canal a dar (na TV e no telemóvel): os failovers dele, pela ordem em que se tentam
 * quando não abre (canais da reserva ou da própria lista). Sem escolha, é o automático (o do ChannelMatch). Pode-se
 * juntar (os parecidos e a pesquisa nas duas listas), tirar e mudar a ordem; qualquer mudança grava a lista (ganha ao
 * automático). "Automático" apaga a escolha e "Nenhum" deixa-a vazia (este canal não muda de lista). "Experimentar
 * agora" dá já os failovers, pela ordem, com a ligação à lista principal fechada antes.
 */
@Stable
internal class BackupPanelState(private val vm: AppViewModel, val channel: Channel, val mainId: String? = null) {
    var choices by mutableStateOf<AppViewModel.BackupChoices?>(null)
        private set
    var loading by mutableStateOf(true)
        private set

    /** Os failovers escolhidos à mão, pela ordem (vazia = "nenhum"; null = automático). */
    var choice by mutableStateOf<List<String>?>(null)
        private set
    var query by mutableStateOf("")
    /** Os canais encontrados, com true nos da própria lista. */
    var results by mutableStateOf<List<Pair<Channel, Boolean>>>(emptyList())
        private set

    suspend fun load() {
        loading = true
        choices = vm.backupChoices(channel, mainId)
        choice = choices?.choice
        loading = false
    }

    /** Os failovers gravados (mudados noutra página ou noutro sítio). */
    fun sync() {
        val c = choices ?: return
        choice = vm.backupChoice(c.mainId, channel.key)
    }

    fun search() {
        results = choices?.let { vm.searchBackup(it, query) }.orEmpty().filter { (c, fromMain) -> !fromMain || c.key != channel.key }
    }

    /** Modo manual: o automático é só uma sugestão (e sem escolha não muda de lista). */
    val manual: Boolean get() = choices?.manual == true

    /** "Nenhum" está escolhido: à mão, ou (no modo manual) por não haver escolha. */
    val none: Boolean get() = failovers.isEmpty()

    /** Os failovers que se tentam, pela ordem: os escolhidos que ainda existem, ou o automático. */
    val failovers: List<String>
        get() {
            val c = choices ?: return emptyList()
            val chosen = choice?.filter { c.channelOf(it) != null }
            // Uma escolha cujos canais já não existem conta como sem escolha (como na lista "Canais de reserva").
            if (choice?.isEmpty() == true || !chosen.isNullOrEmpty()) return chosen.orEmpty()
            return c.variants + if (manual) emptyList() else listOfNotNull(c.autoChannel?.key)
        }

    /** Os failovers ainda são os do automático (nada escolhido à mão). */
    val automatic: Boolean get() = choice == null

    private fun save(list: List<String>?) {
        val main = choices?.mainId ?: return
        choice = list
        vm.setBackupChoice(main, channel.key, list)
    }

    /** Junta [failover] no fim (ou tira-o, se já lá estiver). */
    fun toggle(failover: String) {
        val list = failovers
        save(if (failover in list) list - failover else list + failover)
    }

    fun remove(failover: String) = save(failovers - failover)

    /** Move [failover] [delta] lugares (−1 = para cima). */
    fun move(failover: String, delta: Int) {
        val list = failovers.toMutableList()
        val from = list.indexOf(failover)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    /** Volta ao automático. */
    fun reset() = save(null)

    /** "Nenhum": este canal não muda de lista. */
    fun clear() = save(emptyList())

    /** "Automático: SIC HD", "Sem correspondência" ou "Em dúvida…". */
    fun autoLabel(s: Strings): String = when (choices?.auto) {
        is ChannelMatch.Result.Match -> s.backupChannelAutoIs(choices?.autoChannel?.name.orEmpty())
        is ChannelMatch.Result.Unsure -> s.backupChannelUnsure
        else -> s.backupChannelNoMatch
    }

    /** A etiqueta de um failover: "Esta lista" ou o nome da reserva. */
    fun source(failover: String, s: Strings): String =
        if (ChannelMatch.mainKeyOf(failover) != null) s.backupThisPlaylist else choices?.backup?.name.orEmpty()

    /** Os canais sugeridos para juntar: o do automático, os parecidos na reserva e na própria lista (true). */
    val suggested: List<Pair<Channel, Boolean>>
        get() {
            val c = choices ?: return emptyList()
            val backup = (listOfNotNull(c.autoChannel) + c.similar).distinctBy { it.key }.map { it to false }
            return backup.take(5) + c.similarMain.take(3).map { it to true } + backup.drop(5)
        }
}

@Composable
internal fun rememberBackupPanel(vm: AppViewModel, channel: Channel, mainId: String? = null): BackupPanelState {
    val state = remember(channel.key, mainId) { BackupPanelState(vm, channel, mainId) }
    LaunchedEffect(state) { state.load() }
    // Mudado noutra página (Juntar um canal, nas Definições) ou noutro sítio: a lista acompanha.
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    LaunchedEffect(state, prefs.backupChannels) { state.sync() }
    // Procura enquanto se escreve, com uma pequena pausa (como a pesquisa de canais).
    LaunchedEffect(state, state.query, state.choices) {
        delay(250)
        state.search()
    }
    return state
}

/** Em que página está o painel da TV: os failovers, ou "Juntar um canal" (Voltar recua de uma para a outra). */
@Stable
internal class BackupPanelNav {
    var adding by mutableStateOf(false)

    /** Voltar: de "Juntar um canal" volta aos failovers (true); nos failovers, quem chama fecha o painel (false). */
    fun back(): Boolean {
        if (!adding) return false
        adding = false
        return true
    }
}

/** O painel na TV, encostado à esquerda como os outros. Voltar recua (ver [BackupPanelNav]) e depois fecha-o. */
@Composable
internal fun BackupChannelPanel(vm: AppViewModel, channel: Channel, width: Dp, nav: BackupPanelNav, onTry: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val state = rememberBackupPanel(vm, channel)
    Column(
        modifier
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp)
            .width(width)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(if (nav.adding) s.backupAddChannel else s.backupChannel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        if (nav.adding) {
            BackupAddBody(vm, state) { nav.adding = false }
        } else {
            BackupChannelBody(vm, state, onTry = onTry, onAdd = { nav.adding = true })
        }
    }
}

/**
 * Os failovers de um canal (também nas Definições → lista → Canais de reserva, aí sem "Experimentar agora": [onTry]
 * null): a lista numerada, com a etiqueta da lista de cada um; OK pega/larga um (com ele "na mão", ↑/↓ movem-no, como
 * em "Reordenar grupos"), OK longo tira-o. Por baixo, "Juntar um canal" ([onAdd]), "Experimentar agora" e "Voltar ao
 * automático" (só com uma escolha à mão).
 */
@Composable
internal fun BackupChannelBody(vm: AppViewModel, state: BackupPanelState, onTry: ((List<String>) -> Unit)?, onAdd: () -> Unit) {
    val s = LocalStrings.current
    val channel = state.channel
    var moving by remember { mutableStateOf<String?>(null) }
    // O foco começa no primeiro failover (ou em "Juntar um canal"). Tirar um failover tira a linha com foco: o foco passa
    // ao que fica no lugar dele (ou ao de cima, ou a "Juntar um canal").
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    val addFocus = remember { FocusRequester() }
    var refocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refocus) {
        val target = refocus ?: return@LaunchedEffect
        focusWithRetry(if (target == ADD) addFocus else rowFocus.getOrPut(target) { FocusRequester() })
        refocus = null
    }
    LaunchedEffect(state.loading) { if (!state.loading) refocus = state.failovers.firstOrNull() ?: ADD }
    val choices = state.choices
    Column {
        when {
            state.loading -> Text(s.backupChecking, color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))
            choices == null -> Text(s.backupUnavailable((state.mainId ?: vm.content.value?.playlist?.id)?.let(vm::backupOf)?.name.orEmpty()), color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                val failovers = state.failovers
                item(key = "intro") {
                    Text(
                        if (failovers.isEmpty()) s.backupNoneNote(channel.name) else s.backupFailoversIntro(channel.name),
                        color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(failovers, key = { _, f -> "f:$f" }) { i, f ->
                    val c = choices.channelOf(f) ?: return@itemsIndexed
                    val isMoving = moving == f
                    GlassRow(
                        onClick = { moving = if (isMoving) null else f },
                        onLongClick = {
                            moving = null
                            val rest = failovers - f
                            refocus = rest.getOrNull(i) ?: rest.getOrNull(i - 1) ?: ADD
                            state.remove(f)
                        },
                        selected = isMoving,
                        modifier = Modifier.focusRequester(rowFocus.getOrPut(f) { FocusRequester() }).onPreviewKeyEvent { e ->
                            if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionUp -> { state.move(f, -1); true }
                                Key.DirectionDown -> { state.move(f, 1); true }
                                else -> false
                            }
                        },
                        leading = { Text("${i + 1}", color = LocalContentColor.current.copy(alpha = 0.6f), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(24.dp)) },
                        trailing = {
                            if (isMoving) Text(s.moving, style = MaterialTheme.typography.bodySmall, color = LocalAccent.current.start)
                            else SourceTag(state.source(f, s))
                        },
                        title = c.name,
                    )
                }
                item(key = "add") {
                    GlassRow(
                        onClick = onAdd,
                        modifier = Modifier.focusRequester(addFocus),
                        leading = { Icon(ItvIcons.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) },
                        title = s.backupAddChannel,
                    )
                }
                item(key = "line") { Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f))) }
                if (onTry != null && failovers.isNotEmpty()) {
                    item(key = "try") {
                        GlassRow(
                            onClick = { onTry(failovers) },
                            leading = { Icon(ItvIcons.Play, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) },
                            title = s.backupChannelTry,
                        )
                    }
                }
                if (!state.automatic) {
                    item(key = "auto") {
                        GlassRow(
                            onClick = { state.reset(); refocus = ADD },
                            leading = { Icon(ItvIcons.Replay, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) },
                            title = s.backupResetAuto,
                            subtitle = state.autoLabel(s),
                        )
                    }
                }
                if (failovers.isNotEmpty()) {
                    item(key = "hint") { Text(s.backupFailoversHint, color = ItvColors.TextFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 10.dp)) }
                }
            }
        }
    }
}

private const val ADD = "+add"

/**
 * "Juntar um canal": a pesquisa nas duas listas e os sugeridos (o do automático e os parecidos, nas duas). OK junta o
 * canal no fim dos failovers e volta a eles ([onDone]); os que já lá estão ficam esbatidos.
 */
@Composable
internal fun BackupAddBody(vm: AppViewModel, state: BackupPanelState, onDone: () -> Unit) {
    val s = LocalStrings.current
    val keyboard = LocalSoftwareKeyboardController.current
    val choices = state.choices ?: return
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(firstFocus) }
    val added = state.failovers.toSet()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
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
                            if (state.query.isEmpty()) Text(s.backupChannelSearchAll, color = ItvColors.TextFaint, style = MaterialTheme.typography.titleSmall)
                            field()
                        }
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 6.dp)
                    .leavesWithArrows()
                    .fillMaxWidth()
                    .clip(ItvShapes.Control)
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
        val list = if (state.query.isBlank()) state.suggested else state.results
        item(key = "h") { Header(if (state.query.isBlank()) s.backupSuggested else s.backupChannelResults) }
        itemsIndexed(list, key = { _, (c, fromMain) -> (if (fromMain) "m:" else "b:") + c.key }) { i, (c, fromMain) ->
            val f = choices.failoverOf(c, fromMain)
            val already = f in added
            GlassRow(
                onClick = {
                    if (!already) state.toggle(f)
                    onDone()
                },
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                title = c.name,
                subtitle = s.backupAlreadyAdded.takeIf { already },
                trailing = { SourceTag(state.source(f, s), dim = already) },
            )
        }
    }
}

/** A etiqueta pequena com a lista de um canal ("Backup", "Esta lista"). */
@Composable
private fun SourceTag(text: String, dim: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current.copy(alpha = if (dim) 0.4f else 0.75f),
        maxLines = 1,
        modifier = Modifier.clip(ItvShapes.Control).background(LocalContentColor.current.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Header(text: String) {
    Text(text, color = ItvColors.TextDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
}

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

/**
 * Uma linha da lista "Canais de reserva": "RTP 1 → RTP 1 FHD" (com "+2" se houver mais failovers) e o estado (no
 * manual sem escolha, a sugestão).
 */
internal fun backupRowText(s: Strings, review: AppViewModel.BackupReview, row: ChannelMatch.ReviewRow): Pair<String, String> {
    val main = review.main.channels[row.main].name
    val chosen = row.failovers.mapNotNull { f -> ChannelMatch.mainKeyOf(f).let { if (it != null) review.main.byKey[it] else review.backup.byKey[f] }?.name }
    val backup = chosen.firstOrNull() ?: row.backup?.let { review.backup.channels.getOrNull(it)?.name }
    val more = if (chosen.size > 1) " +${chosen.size - 1}" else ""
    val title = if (backup != null) "$main → $backup$more" else main
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
