package cc.slokker.itv.ui.player

import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import cc.slokker.itv.media.TeletextScreen
import cc.slokker.itv.media.TeletextStore
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/** O fundo transparente ou opaco do teletexto: fica como estava da última vez (enquanto a app está aberta). */
private var lastTransparent = false

/**
 * O teletexto a ver (o mesmo na TV e no telemóvel): a página, a subpágina, o número a meio de escrever, o fundo e o
 * revelar. O ecrã refaz-se a cada [POLL_MS] a partir do [TeletextStore] do motor (as páginas chegam a rodar).
 */
@Stable
internal class TeletextController {
    var page by mutableIntStateOf(START_PAGE)
        private set
    /** A subpágina escolhida (-1 = a última que chegou, a rodar como numa TV). */
    var subpage by mutableIntStateOf(-1)
        private set
    var typed by mutableStateOf("")
        private set
    var transparent by mutableStateOf(lastTransparent)
        private set
    var reveal by mutableStateOf(false)
        private set
    var screen by mutableStateOf<TeletextScreen?>(null)
        private set
    /** Pisca: o texto com FLASH vê-se nesta fase. */
    var flashOn by mutableStateOf(true)
        private set
    var store: TeletextStore? = null

    /** Na TV: o teclado no ecrã (o comando da SHIELD não tem números nem cores) e a tecla com o cursor. */
    var pad by mutableStateOf(false)
    var padIndex by mutableIntStateOf(0)

    private fun label() = if (typed.isEmpty()) "" else typed.padEnd(3, '-')

    /** Um algarismo do número da página: ao terceiro, vai para ela (a primeira só pode ser 1–8). */
    fun digit(d: Int) {
        if (typed.isEmpty() && d !in 1..8) return
        typed += d
        if (typed.length == 3) go(typed.toInt()) else refresh()
    }

    fun go(target: Int) {
        page = TeletextStore.wrap(target)
        subpage = -1
        typed = ""
        refresh()
    }

    /** ↑/↓: a página seguinte ou a anterior das que já chegaram. */
    fun step(direction: Int) {
        val s = store
        go(if (s != null) synchronized(s) { s.nextPage(page, direction) } else TeletextStore.wrap(page + direction))
    }

    /** ←/→: a subpágina ao lado (à volta). */
    fun sub(direction: Int) {
        val shown = screen ?: return
        if (shown.subpages <= 1) return
        subpage = ((if (subpage >= 0) subpage else shown.subpage.coerceAtLeast(0)) + direction + shown.subpages) % shown.subpages
        refresh()
    }

    /** As teclas de cor: a ligação do FastText da página (sem ela, nada). */
    fun colour(index: Int) {
        screen?.link(index)?.takeIf { it in 100..899 }?.let(::go)
    }

    fun toggleBackground() {
        transparent = !transparent
        lastTransparent = transparent
    }

    fun toggleReveal() {
        reveal = !reveal
    }

    fun refresh() {
        val s = store
        screen = if (s == null) null else synchronized(s) {
            s.viewing = true
            s.screen(page, subpage, label())
        }
    }

    internal fun tick(now: Long) {
        flashOn = (now / FLASH_MS) % 3 != 2L
    }

    /** A tecla [index] do teclado no ecrã (ver [PAD]). */
    fun press(index: Int) {
        when (val k = PAD[index]) {
            in 0..9 -> digit(k)
            PAD_RED, PAD_GREEN, PAD_YELLOW, PAD_BLUE -> colour(k - PAD_RED)
            PAD_BACKGROUND -> toggleBackground()
            PAD_REVEAL -> toggleReveal()
        }
    }

    /**
     * As teclas do comando com o teletexto aberto na TV: números, ↑/↓ páginas, ←/→ subpáginas, as quatro cores, OK
     * abre o teclado no ecrã (onde as setas andam pelas teclas) e Voltar fecha o teclado ou o teletexto. Tudo fica aqui.
     */
    fun onTvKey(e: KeyEvent, onClose: () -> Unit): Boolean {
        val code = e.key.nativeKeyCode
        if (code == AndroidKeyEvent.KEYCODE_BACK) {
            if (e.type == KeyEventType.KeyUp) {
                when {
                    pad -> pad = false
                    typed.isNotEmpty() -> {
                        typed = ""
                        refresh()
                    }
                    else -> onClose()
                }
            }
            return true
        }
        if (e.type != KeyEventType.KeyDown) return true
        val digit = when (code) {
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> code - AndroidKeyEvent.KEYCODE_0
            in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> code - AndroidKeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) {
            digit(digit)
            return true
        }
        when (code) {
            AndroidKeyEvent.KEYCODE_PROG_RED -> colour(0)
            AndroidKeyEvent.KEYCODE_PROG_GREEN -> colour(1)
            AndroidKeyEvent.KEYCODE_PROG_YELLOW -> colour(2)
            AndroidKeyEvent.KEYCODE_PROG_BLUE -> colour(3)
            AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> step(1)
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> step(-1)
            AndroidKeyEvent.KEYCODE_DPAD_UP -> if (pad) padIndex = (padIndex - PAD_COLUMNS).coerceAtLeast(padIndex % PAD_COLUMNS) else step(1)
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (pad) padIndex = (padIndex + PAD_COLUMNS).takeIf { it < PAD.size } ?: padIndex else step(-1)
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (pad) padIndex = if (padIndex % PAD_COLUMNS > 0) padIndex - 1 else padIndex else sub(-1)
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (pad) padIndex = if (padIndex % PAD_COLUMNS < PAD_COLUMNS - 1) padIndex + 1 else padIndex else sub(1)
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (e.nativeKeyEvent.repeatCount == 0) {
                    if (pad) press(padIndex) else pad = true
                }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_INFO -> toggleBackground()
        }
        return true
    }

    companion object {
        const val START_PAGE = 100
        private const val FLASH_MS = 333L
        const val POLL_MS = 250L
        const val PAD_COLUMNS = 4
        const val PAD_RED = 10
        const val PAD_GREEN = 11
        const val PAD_YELLOW = 12
        const val PAD_BLUE = 13
        const val PAD_BACKGROUND = 14
        const val PAD_REVEAL = 15

        /** O teclado no ecrã, 4×4: os algarismos, as cores à direita, o fundo e o revelar ao lado do 0. */
        val PAD = intArrayOf(1, 2, 3, PAD_RED, 4, 5, 6, PAD_GREEN, 7, 8, 9, PAD_YELLOW, PAD_BACKGROUND, 0, PAD_REVEAL, PAD_BLUE)
    }
}

/** O teletexto do motor a dar ([player]); null sem GoatCore (o VLC de recurso não o lê). */
internal fun teletextOf(player: Player?): TeletextStore? = (player as? GoatCoreMediaPlayer)?.teletextPages

/** O canal a dar tem teletexto (para mostrar a entrada no menu); vê-se de segundo a segundo. */
@Composable
internal fun rememberTeletextAvailable(player: Player?): Boolean {
    val available by produceState(false, player) {
        while (true) {
            value = teletextOf(player)?.let { synchronized(it) { it.available } } == true
            delay(1_000)
        }
    }
    return available
}

/**
 * Um [TeletextController] ligado ao teletexto de [player] enquanto [active] (aberto): o canal muda, o teletexto é o do
 * canal novo. Fechado, o motor volta à pré-cache.
 */
@Composable
internal fun rememberTeletext(player: Player?, active: Boolean = true): TeletextController {
    val controller = remember { TeletextController() }
    LaunchedEffect(player, active) {
        if (!active) {
            controller.store?.let { synchronized(it) { it.viewing = false } }
            controller.store = null
            controller.pad = false
            return@LaunchedEffect
        }
        while (true) {
            val store = teletextOf(player)
            if (store !== controller.store) {
                controller.store?.let { synchronized(it) { it.viewing = false } }
                controller.store = store
            }
            controller.tick(System.currentTimeMillis())
            controller.refresh()
            delay(TeletextController.POLL_MS)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.store?.let { synchronized(it) { it.viewing = false } } }
    }
    return controller
}

/** As 8 cores do teletexto (preto, vermelho, verde, amarelo, azul, magenta, ciano, branco). */
private val PALETTE = intArrayOf(
    0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
    0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
)

/**
 * A página numa grelha de 40×25 células, em 4:3, o maior possível dentro do espaço (ao centro). No fundo
 * transparente (e nas legendas e notícias, que só mostram as caixas) o preto não se pinta e o texto leva sombra.
 */
@Composable
internal fun TeletextCanvas(c: TeletextController, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val text = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE } }
    val block = remember { Paint().apply { style = Paint.Style.FILL } }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val screen = c.screen
        Canvas(Modifier.fillMaxSize()) {
            if (screen != null) drawTeletext(screen, c.transparent || screen.boxed, c.reveal, c.flashOn, text, block)
        }
        if (screen == null || !screen.found) {
            val width = min(maxWidth.value, maxHeight.value * 4f / 3f)
            Text(
                s.teletextWaiting(c.page),
                color = Color.White,
                fontSize = (width / 36f).coerceIn(11f, 28f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

private fun DrawScope.drawTeletext(screen: TeletextScreen, clear: Boolean, reveal: Boolean, flashOn: Boolean, text: Paint, block: Paint) {
    val gridW = min(size.width, size.height * 4f / 3f)
    val gridH = gridW * 3f / 4f
    val ox = (size.width - gridW) / 2f
    val oy = (size.height - gridH) / 2f
    val cw = gridW / TeletextScreen.COLUMNS
    val ch = gridH / TeletextScreen.ROWS
    if (!clear) drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(ox, oy), size = androidx.compose.ui.geometry.Size(gridW, gridH))
    text.textSize = ch * 0.92f
    text.textScaleX = 1f
    text.textScaleX = (cw / text.measureText("M")).coerceAtMost(1.6f)
    if (clear) text.setShadowLayer(ch * 0.12f, 0f, 0f, android.graphics.Color.BLACK) else text.clearShadowLayer()
    drawIntoCanvas { canvas ->
        val n = canvas.nativeCanvas
        for (r in 0 until TeletextScreen.ROWS) for (col in 0 until TeletextScreen.COLUMNS) {
            val attrs = screen.attributes(r, col)
            if ((attrs and TeletextScreen.HIDDEN) != 0) continue
            val x = ox + col * cw
            val y = oy + r * ch
            val back = screen.background(r, col)
            if (!(back == 0 && clear)) {
                block.color = PALETTE[back]
                n.drawRect(x, y, x + cw + 0.5f, y + ch + 0.5f, block)
            }
            if ((attrs and TeletextScreen.FLASH) != 0 && !flashOn) continue
            if ((attrs and TeletextScreen.CONCEAL) != 0 && !reveal) continue
            val mosaic = screen.mosaic(r, col)
            val glyph = screen.text(r, col)
            if (mosaic <= 0 && glyph.isBlank()) continue
            n.save()
            n.clipRect(x, y, x + cw, y + ch)
            // Altura dupla: desenha-se esticado a partir da linha de cima, e cada linha mostra a sua metade.
            val top = when {
                (attrs and TeletextScreen.DOUBLE_TOP) != 0 -> y.also { n.scale(1f, 2f, x, y) }
                (attrs and TeletextScreen.DOUBLE_BOTTOM) != 0 -> (y - ch).also { n.scale(1f, 2f, x, y - ch) }
                else -> y
            }
            val fore = PALETTE[screen.foreground(r, col)]
            if (mosaic > 0) {
                block.color = fore
                val separated = (attrs and TeletextScreen.SEPARATED) != 0
                val bw = cw / 2f
                val bh = ch / 3f
                val gap = if (separated) min(bw, bh) * 0.2f else 0f
                for (bit in 0 until 6) {
                    if ((mosaic shr bit) and 1 == 0) continue
                    val bx = x + (bit % 2) * bw
                    val by = top + (bit / 2) * bh
                    n.drawRect(bx + gap, by, bx + bw + (if (separated) 0f else 0.5f), by + bh - gap + (if (separated) 0f else 0.5f), block)
                }
            } else {
                text.color = fore
                n.drawText(glyph, x, top + ch * 0.8f, text)
            }
            n.restore()
        }
    }
}

/** As cores das teclas do FastText. */
private val KEY_COLOURS = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF1E88E5))

/**
 * O teletexto na TV, por cima do vídeo (as teclas chegam por [TeletextController.onTvKey], do ecrã do player): a
 * página ao centro, e ao lado a subpágina, as ligações das cores e, com OK, o teclado no ecrã.
 */
@Composable
internal fun TvTeletext(c: TeletextController) {
    val s = LocalStrings.current
    val screen = c.screen
    Box(Modifier.fillMaxSize().background(if (c.transparent || screen?.boxed == true) Color.Transparent else Color.Black)) {
        TeletextCanvas(c, Modifier.fillMaxSize().padding(vertical = 16.dp))
        Column(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).width(150.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (screen != null && screen.subpages > 1) Chip("${s.teletextSubpage} ${screen.subpage + 1}/${screen.subpages}")
            if (screen != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in 0 until 4) {
                        val link = screen.link(k)
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(KEY_COLOURS[k].copy(alpha = if (link > 0) 0.9f else 0.25f))
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                        ) {
                            Text(if (link > 0) link.toString() else "   ", color = if (k == 2) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (c.pad) TvPad(c) else Chip(s.teletextHintTv)
        }
    }
}

@Composable
private fun Chip(label: String) {
    Text(
        label,
        color = Color.White,
        fontSize = 11.sp,
        textAlign = TextAlign.End,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** O teclado no ecrã da TV: 4×4 teclas, a do cursor com uma orla (as setas andam por elas, OK carrega). */
@Composable
private fun TvPad(c: TeletextController) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until TeletextController.PAD.size / TeletextController.PAD_COLUMNS) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (column in 0 until TeletextController.PAD_COLUMNS) {
                    val index = row * TeletextController.PAD_COLUMNS + column
                    PadKey(TeletextController.PAD[index], c, focused = index == c.padIndex, size = 30)
                }
            }
        }
    }
}

/** Uma tecla do teclado (TV e telemóvel): um algarismo, uma cor, o fundo (◐) ou o revelar (?). */
@Composable
private fun PadKey(key: Int, c: TeletextController, focused: Boolean, size: Int, onClick: (() -> Unit)? = null) {
    val colour = when (key) {
        TeletextController.PAD_RED, TeletextController.PAD_GREEN, TeletextController.PAD_YELLOW, TeletextController.PAD_BLUE ->
            KEY_COLOURS[key - TeletextController.PAD_RED]
        TeletextController.PAD_BACKGROUND -> if (c.transparent) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)
        TeletextController.PAD_REVEAL -> if (c.reveal) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val label = when (key) {
        in 0..9 -> key.toString()
        TeletextController.PAD_BACKGROUND -> "◐"
        TeletextController.PAD_REVEAL -> "?"
        else -> ""
    }
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(colour)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * O teletexto no telemóvel, por cima de tudo: o número da página a escrever, a página (deslizar para o lado muda de
 * página), as setas das páginas e das subpáginas, as quatro cores, o fundo e o revelar.
 */
@Composable
internal fun PhoneTeletext(player: Player?, onClose: () -> Unit) {
    val s = LocalStrings.current
    val c = rememberTeletext(player)
    var field by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(if (c.transparent) Color.Black.copy(alpha = 0.45f) else Color.Black)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(ItvIcons.Close, onClose)
            Text(s.teletext, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
            Text(s.teletextPage, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    val digits = v.filter(Char::isDigit).take(3)
                    field = digits
                    if (digits.length == 3) {
                        digits.toInt().takeIf { it in 100..899 }?.let(c::go)
                        field = ""
                    }
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    field.toIntOrNull()?.takeIf { it in 100..899 }?.let(c::go)
                    field = ""
                }),
                decorationBox = { inner ->
                    Box(Modifier.width(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.12f)).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        if (field.isEmpty()) Text(c.page.toString(), color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        inner()
                    }
                },
            )
        }
        var drag by remember { mutableStateOf(0f) }
        TeletextCanvas(
            c,
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp).pointerInput(c) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        // Para a esquerda: a página seguinte (como virar a folha).
                        if (abs(drag) > 80f) c.step(if (drag < 0) 1 else -1)
                        drag = 0f
                    },
                    onHorizontalDrag = { _, amount -> drag += amount },
                )
            },
        )
        val screen = c.screen
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(ItvIcons.Back) { c.step(-1) }
            Text("${c.page}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
            RoundButton(ItvIcons.Chevron) { c.step(1) }
            Box(Modifier.weight(1f))
            if (screen != null && screen.subpages > 1) {
                Text("‹", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { c.sub(-1) }.padding(horizontal = 10.dp))
                Text("${screen.subpage + 1}/${screen.subpages}", color = Color.White, fontSize = 14.sp)
                Text("›", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { c.sub(1) }.padding(horizontal = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (k in 0 until 4) {
                val link = screen?.link(k) ?: -1
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(KEY_COLOURS[k].copy(alpha = if (link > 0) 1f else 0.3f))
                        .clickable(enabled = link > 0) { c.colour(k) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (link > 0) link.toString() else "", color = if (k == 2) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            PadKey(TeletextController.PAD_BACKGROUND, c, focused = false, size = 40) { c.toggleBackground() }
            PadKey(TeletextController.PAD_REVEAL, c, focused = false, size = 40) { c.toggleReveal() }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
