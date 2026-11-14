package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.request.ImageRequest
import coil3.SingletonImageLoader
import cc.slokker.itv.ui.common.LocalChannelLogos
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import coil3.toBitmap
import coil3.compose.AsyncImage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.ErrorPanel
import cc.slokker.itv.ui.common.KeyHint
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import cc.slokker.itv.data.EpgEntry
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.NowNext
import cc.slokker.itv.ui.common.ProgressBar
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberEpg
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.tabular
import cc.slokker.itv.data.at
import cc.slokker.itv.data.after
import cc.slokker.itv.data.minutesLeft

/**
 * Barra "a ver agora" em baixo, sem cartão de vidro: o vídeo escurece em baixo e por cima ficam o logótipo, o programa
 * (horas, quanto falta e os dados da stream), o seguinte, a barra de progresso larga e os botões.
 * [actions] são os botões por baixo (pesquisa, guia, favorito…), que recebem o programa atual.
 */
@Composable
internal fun NowPlayingBar(
    channel: Channel,
    data: PlaylistContent,
    vm: AppViewModel,
    badges: List<String> = emptyList(),
    /** A barra de progresso recebe o foco e OK chama isto (pausar o direto); null = só para ver. */
    onBarClick: (() -> Unit)? = null,
    barFocus: FocusRequester? = null,
    actions: (@Composable RowScope.(current: EpgEntry?) -> Unit)? = null,
) {
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val isFavorite = channel.key in favorites[data.playlist.id].orEmpty()
    val current = epg.at(now)
    val next = epg.after(current, now)
    val s = LocalStrings.current

    Column(
        Modifier
            .fillMaxWidth()
            .background(HudScrim)
            .padding(start = 48.dp, end = 48.dp, top = 150.dp, bottom = 27.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (prefs.showLogos) {
                HudLogo(channel, Modifier.size(width = 132.dp, height = 76.dp))
                Spacer(Modifier.width(20.dp))
            }
            Column(Modifier.weight(1f)) {
                ChannelLine(channel, isFavorite, prefs.showNumbers)
                Text(
                    current?.title ?: channel.name,
                    style = MaterialTheme.typography.headlineSmall.copy(shadow = TextShadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current != null) {
                        // A barra larga por baixo já mostra o progresso: aqui só as horas.
                        ProgrammeTimes(current, now, Modifier.padding(end = 14.dp), bar = false)
                    } else if (epg.isEmpty()) {
                        Text(s.noGuide, color = ItvColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 14.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { badges.forEach { QualityBadge(it) } }
                }
            }
            if (next != null) {
                Spacer(Modifier.width(24.dp))
                Column(Modifier.widthIn(max = 300.dp), horizontalAlignment = Alignment.End) {
                    Text(s.next(formatTime(next.startMs)), color = ItvColors.TextDim, style = MaterialTheme.typography.labelSmall)
                    Text(next.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        WideProgress(current?.progress(now), onBarClick, barFocus)
        if (actions != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                actions(current)
            }
        }
    }
}

/** Número e nome do canal (com ★ se for favorito), pequenos, por cima do programa. Sem [showNumber] (Definições →
 * Números dos canais desligado), só o nome. */
@Composable
internal fun ChannelLine(channel: Channel, isFavorite: Boolean, showNumber: Boolean, style: TextStyle = MaterialTheme.typography.labelLarge) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showNumber) {
            Text(channel.num.toString(), color = ItvColors.TextDim, style = style.copy(shadow = TextShadow).tabular)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (isFavorite) "${channel.name}  ★" else channel.name,
            style = style.copy(shadow = TextShadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "14:15 — 15:45", uma barra curta ([bar]) e quanto falta. */
@Composable
internal fun ProgrammeTimes(current: EpgEntry, now: Long, modifier: Modifier = Modifier, bar: Boolean = true) {
    val style = MaterialTheme.typography.bodySmall.copy(shadow = TextShadow).tabular
    val left = current.minutesLeft(now)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("${formatTime(current.startMs)} — ${formatTime(current.endMs)}", style = style)
        if (bar) ProgressBar(current.progress(now), Modifier.padding(horizontal = 12.dp).width(56.dp)) else Text("  ·  ", style = style)
        Text(LocalStrings.current.minutesLeft(left), style = style)
    }
}

/**
 * A barra de progresso larga do HUD, com um ponto no sítio onde o programa vai. Sem guia fica só o traço. Com [onClick]
 * recebe o foco (o ponto cresce e ganha um aro) e OK pausa; ← → não fazem nada aqui (não mudam de canal).
 */
@Composable
private fun WideProgress(progress: Float?, onClick: (() -> Unit)?, focus: FocusRequester?) {
    val brush = LocalAccent.current.brush
    var focused by remember { mutableStateOf(false) }
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .onKeyEvent { e ->
                            when (e.key.nativeKeyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    if (e.type == KeyEventType.KeyUp) onClick()
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable()
                } else {
                    Modifier
                },
            )
            .drawBehind {
                val h = (if (focused) 6.dp else 4.dp).toPx()
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2)
                drawRoundRect(Color.White.copy(alpha = if (focused) 0.32f else 0.22f), Offset(0f, y), Size(size.width, h), r)
                val p = progress ?: if (focused) 0f else return@drawBehind
                val x = size.width * p.coerceIn(0f, 1f)
                if (progress != null) drawRoundRect(brush, Offset(0f, y), Size(x, h), r)
                val c = Offset(x, size.height / 2)
                if (focused) {
                    drawCircle(Color.White.copy(alpha = 0.3f), radius = 12.dp.toPx(), center = c)
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = c)
                } else {
                    drawCircle(Color.White.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = c)
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = c)
                }
            },
    )
}

/**
 * O logótipo sem placa, a ocupar a caixa toda (os largos ficam largos), com uma sombra suave com a forma dele para se
 * separar do vídeo. Os escuros sem fundo próprio levam antes um brilho claro (uma sombra escura não os salvava).
 * Sem logo (ou se falhar), as iniciais.
 */
@Composable
internal fun HudLogo(channel: Channel, modifier: Modifier) {
    val url = LocalChannelLogos.current[channel.key] ?: channel.logo
    var failed by remember(url) { mutableStateOf(url == null) }
    var look by remember(url) { mutableStateOf(url?.let { LogoLooks[it] }) }
    val context = LocalContext.current
    // Mede-se e faz-se a sombra numa cópia pequena e à parte (sem bitmaps de hardware), uma vez por logótipo.
    LaunchedEffect(url) {
        if (url == null || LogoLooks.containsKey(url)) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(LOGO_SAMPLE).allowHardware(false).build()
        val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image ?: return@LaunchedEffect
        val made = withContext(Dispatchers.Default) { runCatching { logoLook(image.toBitmap()) }.getOrNull() } ?: return@LaunchedEffect
        LogoLooks[url] = made
        look = made
    }
    Box(
        modifier.drawBehind {
            val l = look ?: return@drawBehind
            if (failed) return@drawBehind
            // Onde o ContentScale.Fit põe o logótipo dentro do padding; a sombra (maior, com o desfoque) à volta.
            val scale = minOf((size.width - 20.dp.toPx()) / l.width, (size.height - 16.dp.toPx()) / l.height)
            val left = (size.width - l.width * scale) / 2 + l.dx * scale
            val top = (size.height - l.height * scale) / 2 + l.dy * scale + if (l.dark) 0f else 1.dp.toPx()
            drawImage(
                l.shadow,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize((l.shadow.width * scale).roundToInt(), (l.shadow.height * scale).roundToInt()),
                colorFilter = ColorFilter.tint(if (l.dark) LogoGlow else LogoShadow),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (failed || url == null) {
            Text(
                channel.name.take(2).uppercase(),
                style = MaterialTheme.typography.headlineSmall.copy(shadow = TextShadow),
                color = ItvColors.TextDim,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** A sombra de um logótipo (só o alfa, desfocado) e onde fica em relação a ele, no tamanho da amostra. */
private class LogoLook(val shadow: ImageBitmap, val dx: Int, val dy: Int, val width: Int, val height: Int, val dark: Boolean)

/** Já medidos: url do logótipo → sombra. */
private val LogoLooks = ConcurrentHashMap<String, LogoLook>()

private fun logoLook(source: Bitmap): LogoLook {
    val offset = IntArray(2)
    val blur = Paint().apply { maskFilter = BlurMaskFilter(LOGO_BLUR, BlurMaskFilter.Blur.NORMAL) }
    val shadow = source.extractAlpha(blur, offset)
    return LogoLook(shadow.asImageBitmap(), offset[0], offset[1], source.width, source.height, isDarkLogo(source))
}

/**
 * Escuro e sem fundo próprio: o brilho médio (percebido) só das partes não transparentes, numa cópia pequena.
 * Com fundo próprio (quase tudo opaco) vê-se sempre.
 */
private fun isDarkLogo(source: Bitmap): Boolean {
    val small = Bitmap.createScaledBitmap(source, 32, 32, true)
    val pixels = IntArray(32 * 32)
    small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
    var opaque = 0
    var light = 0.0
    for (c in pixels) {
        if (AndroidColor.alpha(c) < 128) continue
        opaque++
        val r = AndroidColor.red(c) / 255.0
        val g = AndroidColor.green(c) / 255.0
        val b = AndroidColor.blue(c) / 255.0
        light += sqrt(0.299 * r * r + 0.587 * g * g + 0.114 * b * b)
    }
    if (opaque == 0 || opaque > pixels.size * 0.85) return false
    return light / opaque < DARK_LOGO
}

private const val DARK_LOGO = 0.38
private const val LOGO_SAMPLE = 96
private const val LOGO_BLUR = 3.5f
private val LogoShadow = Color.Black.copy(alpha = 0.7f)
private val LogoGlow = Color.White.copy(alpha = 0.55f)

@Composable
private fun QualityBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = ItvColors.Text.copy(alpha = 0.85f),
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Ao mudar de canal, no mesmo estilo do HUD mas sem botões nem cartão: o canto de baixo escurece um pouco
 * e o texto leva sombra, para se ler em cima de imagens claras.
 */
@Composable
internal fun ZapCard(channel: Channel, vm: AppViewModel) {
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val current = epg.at(now)

    // O ecrã todo, com o canto de baixo à esquerda escurecido: uma elipse que desvanece até meio do ecrã (antes era
    // desenhada só à volta do cartão e acabava de repente).
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val corner = Offset(0f, size.height)
                val ry = size.height * 0.8f
                scale(scaleX = size.width * 0.7f / ry, scaleY = 1f, pivot = corner) {
                    drawCircle(ZapCorner(corner, ry), radius = ry, center = corner)
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
    Row(
        Modifier
            .padding(start = 48.dp, bottom = 48.dp, top = 48.dp, end = 48.dp)
            .widthIn(max = 640.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefs.showLogos) {
            HudLogo(channel, Modifier.size(width = 108.dp, height = 62.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column {
            ChannelLine(channel, isFavorite = false, showNumber = prefs.showNumbers, style = MaterialTheme.typography.labelMedium)
            Text(
                current?.title ?: channel.name,
                style = MaterialTheme.typography.titleLarge.copy(shadow = TextShadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            if (current != null) ProgrammeTimes(current, now)
        }
    }
    }
}

@Suppress("FunctionName")
private fun ZapCorner(center: Offset, radius: Float) = Brush.radialGradient(
    0f to Color.Black.copy(alpha = 0.75f),
    0.35f to Color.Black.copy(alpha = 0.5f),
    0.7f to Color.Black.copy(alpha = 0.15f),
    1f to Color.Transparent,
    center = center,
    radius = radius,
)

/** Sombra do texto do HUD e do zapping (sem cartão por trás). */
// Curta e escura: contorna as letras (lê-se por cima de qualquer imagem) sem parecer uma mancha.
internal val TextShadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 4f)

/** O escurecido do HUD: transparente em cima, quase preto junto aos botões. */
private val HudScrim = Brush.verticalGradient(
    // Começa devagar, para não se ver onde acaba.
    0f to Color.Transparent,
    0.15f to Color.Black.copy(alpha = 0.08f),
    0.35f to Color.Black.copy(alpha = 0.38f),
    0.55f to Color.Black.copy(alpha = 0.66f),
    0.75f to Color.Black.copy(alpha = 0.82f),
    1f to Color.Black.copy(alpha = 0.9f),
)


/**
 * No canto: a sintonizar um canal, a voltar a ligar depois de ele cair ([reconnecting]), ou a abrir um failover dele
 * ([failover]: "RTP 1 HD (BackupTV) · 1 de 2"). A ligar, o anel roda; à espera da próxima tentativa ([nextTryAt],
 * elapsedRealtime), enche-se durante [waitMs] com os segundos no meio.
 */
@Composable
internal fun TuningPill(
    channel: String,
    reconnecting: Boolean,
    attempt: Int,
    nextTryAt: Long,
    waitMs: Long,
    modifier: Modifier = Modifier,
    failover: String? = null,
) {
    val s = LocalStrings.current
    val leftMs = rememberMsLeft(nextTryAt)
    Row(
        modifier
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .glass(CircleShape)
            .padding(start = 7.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountdownRing(leftMs, waitMs, Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (reconnecting) s.reconnecting else if (failover != null) s.backupConnecting else s.tuning, style = MaterialTheme.typography.labelLarge)
            Text(
                if (attempt > 0) s.reconnectAttempt(channel, attempt) else failover ?: channel,
                style = MaterialTheme.typography.bodySmall.tabular,
                color = ItvColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

/** Quanto falta até [at] (elapsedRealtime), a cada frame; 0 quando já passou ou [at] é 0. */
@Composable
internal fun rememberMsLeft(at: Long): Long {
    val left by produceState(0L, at) {
        while (at > 0) {
            value = (at - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (value == 0L) break
            withFrameMillis { }
        }
        value = 0L
    }
    return left
}

/** À espera ([leftMs] de [waitMs]): o anel enche-se com os segundos no meio. A tentar (0): o anel roda. */
@Composable
internal fun CountdownRing(leftMs: Long, waitMs: Long, modifier: Modifier = Modifier, color: Color = Color.White) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (leftMs > 0 && waitMs > 0) {
            ProgressRing(1f - leftMs.toFloat() / waitMs, Modifier.fillMaxSize(), color = color)
            Text(
                ((leftMs + 999) / 1_000).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold).tabular,
                color = Color.White,
            )
        } else {
            SpinnerRing(Modifier.fillMaxSize(), color)
        }
    }
}

/** Anel que se enche até [fraction] (0..1), a começar em cima. */
@Composable
internal fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, stroke: Dp = 2.5.dp, color: Color = Color.White) {
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Anel a rodar: a ligar, sem se saber quanto falta. */
@Composable
private fun SpinnerRing(modifier: Modifier = Modifier, color: Color = Color.White) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "turn",
    )
    Canvas(modifier) {
        val w = 2.5.dp.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        drawArc(color, turn - 90f, 100f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Erro de reprodução ao centro: o que foi, porquê, como resolver e as teclas que ajudam. */
@Composable
internal fun ErrorCard(info: ErrorInfo, status: String?, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    ErrorPanel(info, modifier, status) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KeyHint("OK", s.retry)
            KeyHint("↑ ↓", s.changeChannel)
            KeyHint("←", s.channelListHint)
        }
    }
}
