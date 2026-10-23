package cc.slokker.itv.ui.common

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent

/**
 * Um erro explicado: o que aconteceu ([title]), a causa provável ([cause]),
 * os passos para resolver ([fixes]) e o detalhe técnico ([detail]), para quem precisar de ajuda.
 */
data class ErrorInfo(
    val title: String,
    val cause: String? = null,
    val fixes: List<String> = emptyList(),
    val detail: String? = null,
)

/**
 * Cartão de erro comum a toda a app (arranque, listas, player).
 * [status] é uma linha de estado viva (ex.: "Nova tentativa em 5 s"); [footer] são botões ou atalhos.
 */
@Composable
fun ErrorPanel(
    info: ErrorInfo,
    modifier: Modifier = Modifier,
    status: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val s = LocalStrings.current
    Column(
        modifier
            .widthIn(max = 780.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 40.dp, vertical = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ErrorBadge()
            Spacer(Modifier.width(20.dp))
            Column {
                Text(info.title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                info.cause?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (info.fixes.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(s.howToFix.uppercase(), color = ItvColors.TextFaint, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                info.fixes.forEachIndexed { i, fix -> FixStep(i + 1, fix) }
            }
        }
        status?.let {
            Spacer(Modifier.height(20.dp))
            StatusLine(it)
        }
        footer?.let {
            Spacer(Modifier.height(28.dp))
            it()
        }
        info.detail?.let {
            Spacer(Modifier.height(20.dp))
            Text(it, color = ItvColors.TextFaint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Atalho do comando: tecla num chip + o que faz. */
@Composable
fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .border(1.dp, Color.White.copy(alpha = 0.22f), ItvShapes.Small)
                .background(Color.White.copy(alpha = 0.06f), ItvShapes.Small)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(key, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorBadge() {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(52.dp)
            .background(accent.start.copy(alpha = 0.12f), CircleShape)
            .border(2.dp, accent.brush, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("!", style = TextStyle(brush = accent.brush, fontSize = 28.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun FixStep(n: Int, text: String) {
    Row {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(26.dp)
                .background(Color.White.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StatusLine(text: String) {
    val pulse by rememberInfiniteTransition(label = "status").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).alpha(pulse).background(LocalAccent.current.brush, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}
