package cc.slokker.itv.ui.settings

import android.net.Uri
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.glass.fadeEdges
import cc.slokker.itv.ui.player.icon
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import cc.slokker.itv.ui.theme.ControlRadius
import cc.slokker.itv.ui.glass.panelListState
import cc.slokker.itv.ui.glass.slidingFocus
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.kind
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.LogoImage
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.glass.SectionLabel
import cc.slokker.itv.ui.glass.rowText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.focusWithRetry
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.theme.tabular

/** Uma linha de definições. [key] serve para devolver o foco à linha certa ao voltar atrás. */
internal sealed class Item(val key: String) {
    /** Abre outra página ou escolha. [icon]: ícone à esquerda (página principal); [logo]: logótipo do canal à esquerda. */
    class Nav(
        key: String,
        val title: String,
        val value: String? = null,
        val subtitle: String? = null,
        val icon: ImageVector? = null,
        val logo: String? = null,
        val showLogo: Boolean = false,
        val onClick: () -> Unit,
    ) : Item(key)
    /** Sim/não. Com [eye], um olho (visível) ou um olho riscado e esbatido (escondido) em vez do interruptor. */
    class Toggle(
        key: String,
        val title: String,
        val checked: Boolean,
        val subtitle: String? = null,
        val eye: Boolean = false,
        val onToggle: (Boolean) -> Unit,
    ) : Item(key)
    class Check(
        key: String,
        val title: String,
        val checked: Boolean,
        val subtitle: String? = null,
        val swatch: Brush? = null,
        /** Logótipo à esquerda (URL; null mostra as iniciais do título). Só com [showLogo]. */
        val logo: String? = null,
        val showLogo: Boolean = false,
        val onClick: () -> Unit,
    ) : Item(key)
    class Action(key: String, val title: String, val subtitle: String? = null, val icon: ImageVector? = null, val onClick: () -> Unit) : Item(key)
    /** Só para ler. [icon]: ícone à esquerda; [valueColor]: o valor noutra cor (ex.: âmbar quando há um problema). */
    class Info(
        key: String,
        val title: String,
        val value: String,
        val subtitle: String? = null,
        val icon: ImageVector? = null,
        val valueColor: Color? = null,
    ) : Item(key)
    class Header(key: String, val text: String) : Item(key)
}

@Composable
internal fun ItemList(items: List<Item>, focusKey: String?, enabled: Boolean) {
    val focusable = items.filter { it !is Item.Header }
    val target = focusable.firstOrNull { it.key == focusKey }
        ?: focusable.firstOrNull { it is Item.Check && it.checked }
        ?: focusable.firstOrNull()
    val focus = remember { FocusRequester() }
    val start = remember { items.indexOf(target).coerceAtLeast(0) }
    val state = remember { panelListState((start - 3).coerceAtLeast(0)) }
    LaunchedEffect(Unit) { if (target != null) focusWithRetry(focus) }
    // Como na lista de canais: um só contorno que desliza até à linha com foco (lido só no desenho).
    var focusedKey by remember { mutableStateOf<String?>(null) }

    // As linhas de cada secção (entre títulos) ficam num bloco: cantos redondos só no início e no fim, e um traço
    // fino entre elas.
    val edges = remember(items) {
        items.mapIndexed { i, it ->
            if (it is Item.Header) null else (i == 0 || items[i - 1] is Item.Header) to (i == items.lastIndex || items[i + 1] is Item.Header)
        }
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.slidingFocus(state) { focusedKey }.fadeEdges(state, top = false),
    ) {
        itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
            val (first, last) = edges[index] ?: (false to false)
            val inset = when {
                item is Item.Nav && item.showLogo -> 66.dp
                item is Item.Nav && item.icon != null || item is Item.Action && item.icon != null || item is Item.Info && item.icon != null -> 62.dp
                item is Item.Check && item.showLogo -> 58.dp
                else -> 12.dp
            }
            val mod = (if (item === target) Modifier.focusRequester(focus) else Modifier)
                .onFocusChanged { if (it.isFocused) focusedKey = item.key }
                .cardSegment(first, last, inset)
            val dim = LocalContentColor.current.copy(alpha = 0.82f)
            when (item) {
                is Item.Header -> SectionLabel(item.text, if (index > 0) Modifier.padding(top = 8.dp) else Modifier)
                is Item.Nav -> GlassRow(
                    enabled = enabled,
                    outlineFocus = true,
                    slidingFocus = true,
                    onClick = item.onClick,
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    leading = when {
                        item.showLogo -> {
                            {
                                LogoImage(item.logo, item.title, 40.dp)
                                Spacer(Modifier.width(14.dp))
                            }
                        }
                        item.icon != null -> { { IconTile(item.icon) } }
                        else -> null
                    },
                    trailing = {
                        item.value?.let { TrailingValue(it, LocalContentColor.current.copy(alpha = 0.82f)) }
                        Icon(ItvIcons.Chevron, contentDescription = null, modifier = Modifier.size(22.dp), tint = LocalContentColor.current.copy(alpha = 0.5f))
                    },
                    modifier = mod,
                )
                is Item.Toggle -> GlassRow(
                    enabled = enabled,
                    outlineFocus = true,
                    slidingFocus = true,
                    onClick = { item.onToggle(!item.checked) },
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    trailing = { if (item.eye) Eye(item.checked) else Switch(item.checked) },
                    modifier = mod,
                )
                is Item.Check -> GlassRow(
                    enabled = enabled,
                    outlineFocus = true,
                    slidingFocus = true,
                    onClick = item.onClick,
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    leading = when {
                        item.showLogo -> {
                            {
                                LogoImage(item.logo, item.title, 32.dp)
                                Spacer(Modifier.width(14.dp))
                            }
                        }
                        item.swatch != null -> {
                            {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(item.swatch))
                                Spacer(Modifier.width(12.dp))
                            }
                        }
                        else -> null
                    },
                    trailing = if (item.checked) {
                        { Text("✓", color = LocalAccent.current.start) }
                    } else {
                        null
                    },
                    modifier = mod,
                )
                is Item.Action -> GlassRow(
                    enabled = enabled,
                    outlineFocus = true,
                    slidingFocus = true,
                    onClick = item.onClick,
                    title = item.title,
                    subtitle = item.subtitle,
                    subtitleLines = 1,
                    leading = item.icon?.let { icon -> { IconTile(icon, brand = true) } },
                    modifier = mod,
                )
                is Item.Info -> GlassRow(
                    enabled = enabled,
                    outlineFocus = true,
                    slidingFocus = true,
                    onClick = {},
                    title = item.title,
                    subtitle = item.subtitle,
                    leading = item.icon?.let { icon -> { IconTile(icon) } },
                    trailing = { if (item.value.isNotEmpty()) TrailingValue(item.value, item.valueColor ?: dim) },
                    modifier = mod,
                )
            }
        }
    }
}

/** Ícone de uma página, num quadrado arredondado; [brand] no gradiente da marca (ex.: a atualização). */
@Composable
private fun IconTile(icon: ImageVector, brand: Boolean = false) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (brand) Modifier.background(LocalAccent.current.brush) else Modifier.background(Color.White.copy(alpha = 0.08f))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (brand) Color.White else ItvColors.Text.copy(alpha = 0.85f))
    }
    Spacer(Modifier.width(14.dp))
}

/** O pedaço de bloco por trás de uma linha: fundo leve, cantos redondos em [first]/[last] e um traço por cima, a partir de [inset]. */
private fun Modifier.cardSegment(first: Boolean, last: Boolean, inset: Dp) = drawBehind {
    val r = ControlRadius.toPx()
    val top = if (first) CornerRadius(r) else CornerRadius.Zero
    val bottom = if (last) CornerRadius(r) else CornerRadius.Zero
    drawPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), top, top, bottom, bottom)) }, CardFill)
    if (!first) {
        val x = inset.toPx()
        drawRect(CardDivider, topLeft = Offset(x, 0f), size = Size(size.width - x - 12.dp.toPx(), 1.dp.toPx()))
    }
}

private val CardFill = Color.White.copy(alpha = 0.04f)
private val CardDivider = Color.White.copy(alpha = 0.07f)

/** Reordenar grupos (ver [OrderPage]). */
@Composable
internal fun GroupsOrderPage(vm: AppViewModel, enabled: Boolean) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val groups = remember(prefs.groupOrder, prefs.hiddenGroups) {
        vm.orderedGroups(includeHidden = true).map { it.id to if (it.id == PlaylistContent.OTHERS) s.others else it.name }
    }
    OrderPage(groups, enabled) { id, delta -> vm.moveGroup(id, delta) }
}

/** Reordenar os canais de um grupo (ver [OrderPage]). */
@Composable
internal fun ChannelsOrderPage(vm: AppViewModel, groupId: String, enabled: Boolean) {
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
    val state = remember { panelListState(0) }
    val first = remember { FocusRequester() }
    var focusedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focusWithRetry(first) }
    // O grupo em movimento fica sempre à vista.
    LaunchedEffect(groups, moving) {
        val index = groups.indexOfFirst { it.first == moving }
        val visible = state.layoutInfo.visibleItemsInfo
        if (index >= 0 && visible.isNotEmpty() && (index <= visible.first().index || index >= visible.last().index)) {
            state.scrollToItem((index - 3).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = state,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier.slidingFocus(state) { focusedId },
    ) {
        items(groups, key = { it.first }) { (id, name) ->
            val isMoving = moving == id
            GlassRow(
                enabled = enabled,
                onClick = { moving = if (isMoving) null else id },
                selected = isMoving,
                outlineFocus = true,
                slidingFocus = true,
                title = name,
                trailing = { Text(if (isMoving) s.moving else "↕", style = MaterialTheme.typography.bodySmall, color = if (isMoving) LocalAccent.current.start else LocalContentColor.current.copy(alpha = 0.6f)) },
                modifier = Modifier
                    .then(if (id == groups.firstOrNull()?.first) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused) focusedId = id }
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

/** Campo de texto com o teclado da TV; OK no teclado (ou "Guardar") confirma, Voltar cancela. */
@Composable
internal fun TextEditPage(route: Route.TextEdit, enabled: Boolean, onDone: (String) -> Unit) {
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
        GlassRow(onClick = { onDone(value.text) }, title = route.action ?: LocalStrings.current.save, enabled = enabled)
    }
}

/** Valor à direita de uma linha: no máximo metade da largura, numa linha que anda com o foco. */
@Composable
private fun TrailingValue(value: String, color: Color) {
    Text(
        value,
        Modifier.widthIn(max = 180.dp).rowText(),
        style = MaterialTheme.typography.bodySmall.tabular,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Visível (olho) ou escondido (olho riscado, esbatido): nos grupos e canais a mostrar na lista. */
@Composable
private fun Eye(visible: Boolean) {
    Icon(
        if (visible) ItvIcons.Visible else ItvIcons.Hidden,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = LocalContentColor.current.copy(alpha = if (visible) 1f else 0.4f),
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
