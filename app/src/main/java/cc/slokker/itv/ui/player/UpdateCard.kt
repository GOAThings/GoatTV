package cc.slokker.itv.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.R
import cc.slokker.itv.data.AppRelease
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.data.Versions
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.theme.tabular

/**
 * No canto do vídeo: há uma versão nova. Mostra até três novidades das notas da versão e instala-se mantendo OK
 * carregado ([hold], 0..1), sem tirar o foco a nada; desaparece sozinho ([hidesIn] segundos). A descarregar ou a
 * instalar ([state]), o rodapé passa a dizer isso.
 */
@Composable
internal fun UpdateCard(release: AppRelease, state: UpdateState, hold: Float, hidesIn: Int, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .width(340.dp)
            .glass(ItvShapes.Panel)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // O primeiro plano do ícone adaptativo já traz o fundo; o logótipo ocupa o centro.
            Image(
                painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .graphicsLayer { scaleX = 1.45f; scaleY = 1.45f },
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    s.updateReadyLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold),
                    color = ItvColors.TextDim,
                )
                Text(s.updateWhatsNew(release.version), style = MaterialTheme.typography.titleMedium.tabular)
            }
        }
        val highlights = releaseHighlights(release.notes)
        if (highlights.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                highlights.forEach { line ->
                    Row {
                        Box(
                            Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(LocalAccent.current.start),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = ItvColors.Text.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is UpdateState.Downloading -> {
                    ProgressRing(state.progress, Modifier.size(24.dp), color = LocalAccent.current.start)
                    Spacer(Modifier.width(10.dp))
                    Text(s.downloading((state.progress * 100).toInt()), style = MaterialTheme.typography.labelMedium.tabular)
                }
                is UpdateState.Installing -> Text(s.installing, style = MaterialTheme.typography.labelMedium)
                else -> {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(hold, Modifier.fillMaxSize())
                        Text("OK", style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(s.updateHoldOk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(s.updateHidesIn(hidesIn), style = MaterialTheme.typography.bodySmall.tabular, color = ItvColors.TextDim)
                }
            }
        }
    }
}

/**
 * As novidades das notas da versão (as do GitHub, em Markdown): os primeiros pontos de lista, sem negrito. Notas sem
 * lista dão as primeiras linhas que não são títulos ("Android TV:").
 */
internal fun releaseHighlights(notes: String, max: Int = 3): List<String> = Versions.highlights(notes, max)
