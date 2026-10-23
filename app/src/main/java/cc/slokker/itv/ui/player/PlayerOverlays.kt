package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.ErrorPanel
import cc.slokker.itv.ui.common.KeyHint
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * Barra "a ver agora" em vidro, em baixo: canal, programa atual com progresso, a seguir e hora.
 * [actions] são os botões por baixo (pesquisa, guia, favorito…), que recebem o programa atual.
 */
@Composable
internal fun NowPlayingBar(
    channel: Channel,
    data: PlaylistContent,
    vm: AppViewModel,
    badges: List<String> = emptyList(),
    actions: (@Composable RowScope.(current: EpgEntry?) -> Unit)? = null,
) {
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val isFavorite = channel.key in favorites[data.playlist.id].orEmpty()

    Column(
        Modifier
            .padding(start = 48.dp, end = 48.dp, bottom = 27.dp)
            .fillMaxWidth()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Só o essencial: o canal com os dados da stream por baixo, e o que está a dar e a seguir.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(0.8f)) {
                Text(
                    if (isFavorite) "${channel.name}  ★" else channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badges.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach { QualityBadge(it) }
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            NowNext(epg, now, Modifier.weight(1.2f), compact = true)
        }
        if (actions != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                actions(epg.firstOrNull { it.startMs <= now && it.endMs > now })
            }
        }
    }
}

@Composable
private fun QualityBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = ItvColors.TextDim,
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Cartão compacto ao mudar de canal: logo, número e grupo, programa atual e progresso. */
@Composable
internal fun ZapCard(channel: Channel, vm: AppViewModel) {
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val current = epg.firstOrNull { it.startMs <= now && it.endMs > now }

    Row(
        Modifier
            .padding(start = 48.dp, bottom = 48.dp)
            .width(420.dp)
            .glass(ItvShapes.Card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefs.showLogos) {
            ChannelLogo(channel, 56.dp)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                current?.title ?: channel.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(current.startMs), style = MaterialTheme.typography.labelSmall, color = ItvColors.TextDim)
                    ProgressBar(current.progress(now), Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(formatTime(current.endMs), style = MaterialTheme.typography.labelSmall, color = ItvColors.TextDim)
                }
            }
        }
    }
}

/** Cápsula "A sintonizar…" com um ponto a pulsar. */
@Composable
internal fun TuningPill(text: String, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "tuning").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        modifier
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .glass(ItvShapes.Control)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(pulse)
                .clip(CircleShape)
                .background(LocalAccent.current.start),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
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
