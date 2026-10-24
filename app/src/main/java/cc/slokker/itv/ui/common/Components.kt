package cc.slokker.itv.ui.common

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.width
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.i18n.LocalStrings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.theme.ItvColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logo da app, como no banner: "TV" grande no gradiente da marca e "GOAT" por cima, mais pequeno,
 * com as letras espalhadas do início do T ao fim do V. [size] é a escala (≈ tamanho do texto de antes).
 */
@Composable
fun Logo(modifier: Modifier = Modifier, size: Int = 32) {
    val tvStyle = TextStyle(brush = ItvColors.Accent, fontSize = (size * 1.6f).sp, fontWeight = FontWeight.Bold)
    val goatStyle = TextStyle(color = Color.White, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.SemiBold)
    Layout(
        modifier = modifier,
        content = {
            Text("TV", style = tvStyle)
            "GOAT".forEach { Text(it.toString(), style = goatStyle) }
        },
    ) { measurables, _ ->
        val tv = measurables[0].measure(Constraints())
        val letters = measurables.drop(1).map { it.measure(Constraints()) }
        // O texto tem folga em cima (a altura da linha): o "GOAT" encosta um pouco ao "TV".
        val overlap = (size * 0.32f).dp.roundToPx()
        val goatHeight = letters.maxOf { it.height }
        val space = (tv.width - letters.sumOf { it.width }) / (letters.size - 1)
        layout(tv.width, goatHeight + tv.height - overlap) {
            var x = 0
            letters.forEach {
                it.place(x, 0)
                x += it.width + space
            }
            tv.place(0, goatHeight - overlap)
        }
    }
}

/** Logo do canal, ou as iniciais quando não há logo / falha a carregar. */
@Composable
fun ChannelLogo(channel: Channel, size: Dp, modifier: Modifier = Modifier) {
    val logo = LocalGuideLogos.current[channel.key] ?: channel.logo
    // As iniciais só aparecem sem logo ou se falhar: muitos logos têm fundo transparente.
    var showInitials by remember(logo) { mutableStateOf(logo == null) }
    // Numa linha com foco (fundo branco, texto escuro) os logos brancos desapareciam: ganham uma placa escura,
    // desenhada por fora do tamanho para não mexer no alinhamento.
    val onLight = !showInitials && LocalContentColor.current.luminance() < 0.5f
    // Só o logo; o fundo arredondado fica para as iniciais (quando não há logo).
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (onLight) {
                    Modifier.drawBehind {
                        val pad = 4.dp.toPx()
                        drawRoundRect(
                            color = LOGO_PLATE,
                            topLeft = Offset(-pad, -pad),
                            size = Size(this.size.width + pad * 2, this.size.height + pad * 2),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .then(if (showInitials) Modifier.clip(RoundedCornerShape(size / 7)).background(Color.White.copy(alpha = 0.10f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (showInitials) {
            Text(
                text = channel.name.take(2).uppercase(),
                color = ItvColors.TextDim,
                fontSize = (size.value / 3).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { showInitials = true },
                modifier = Modifier.size(size),
            )
        }
    }
}

private val LOGO_PLATE = Color(0xFF1A1D24)

/**
 * Logótipos do guia por channelKey (Definições → Aspeto → Logótipos do guia), para a lista aberta;
 * vazio com a opção desligada.
 */
val LocalGuideLogos = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** Relógio que atualiza a cada 15 s (barras de progresso do EPG). */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** EPG curto do canal: devolve logo o que está em cache e depois pede ao servidor. */
@Composable
fun rememberEpg(channel: Channel?, cached: (Channel) -> List<EpgEntry>, load: suspend (Channel) -> List<EpgEntry>): List<EpgEntry> {
    val state = produceState(channel?.let(cached).orEmpty(), channel) {
        if (channel == null) return@produceState
        value = cached(channel)
        delay(250) // não pedir EPG de cada canal por onde o foco passa a correr
        value = load(channel)
    }
    return state.value
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.ROOT)

fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

/** "Agora" com barra de progresso e "A seguir". */
@Composable
fun NowNext(epg: List<EpgEntry>, now: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    val current = epg.firstOrNull { it.startMs <= now && it.endMs > now }
    val next = epg.firstOrNull { it.startMs >= (current?.endMs ?: now) }
    Column(modifier) {
        if (current == null && next == null) {
            Text(LocalStrings.current.noGuide, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        // Compacto (HUD): uma linha por programa.
        if (compact) {
            if (current != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(LocalStrings.current.now, color = LocalAccent.current.start, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(10.dp))
                    Text(current.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text("${formatTime(current.startMs)} – ${formatTime(current.endMs)}", color = ItvColors.TextDim, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(6.dp))
                ProgressBar(current.progress(now), Modifier.fillMaxWidth())
            }
            if (next != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(LocalStrings.current.next(formatTime(next.startMs)), color = ItvColors.TextDim, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(10.dp))
                    Text(next.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            return@Column
        }
        if (current != null) {
            Text(LocalStrings.current.now, color = LocalAccent.current.start, style = MaterialTheme.typography.labelSmall)
            Text(
                current.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(current.startMs), color = ItvColors.TextDim, style = MaterialTheme.typography.labelSmall)
                ProgressBar(current.progress(now), Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(formatTime(current.endMs), color = ItvColors.TextDim, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (next != null) {
            Spacer(Modifier.height(12.dp))
            Text(LocalStrings.current.next(formatTime(next.startMs)), color = ItvColors.TextDim, style = MaterialTheme.typography.labelSmall)
            Text(next.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(LocalAccent.current.brush),
        )
    }
}

/** Aviso curto no topo do ecrã, em vidro, que desaparece sozinho. */
@Composable
fun Toast(message: String?, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(3_500)
        onDismiss(message)
    }
    Box(modifier.fillMaxWidth().padding(top = 27.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .glass(ItvShapes.Card)
                .padding(horizontal = 28.dp, vertical = 14.dp),
        )
    }
}
