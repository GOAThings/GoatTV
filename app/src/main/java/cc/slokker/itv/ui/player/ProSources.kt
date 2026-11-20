package cc.slokker.itv.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.ChannelMatch
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.focusWithRetry
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors

/**
 * Modo TV Pro: as fontes do canal [channel] (o mesmo canal nas várias listas), da melhor para a pior. É a ordem em que
 * se tentam quando uma falha; OK numa abre-a já (e as seguintes ficam como failovers dela).
 */
@Composable
internal fun ProSourcesPanel(vm: AppViewModel, channel: Channel, width: Dp, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val content by vm.content.collectAsStateWithLifecycle()
    val sources = remember(content, channel.key) { vm.proSources(channel) }
    val first = remember { FocusRequester() }
    LaunchedEffect(sources.isNotEmpty()) { if (sources.isNotEmpty()) focusWithRetry(first) }
    Column(
        modifier
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp)
            .width(width)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 26.dp, vertical = 30.dp),
    ) {
        Text("${channel.name} · ${s.proSources}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp))
        Text(s.proSourcesIntro, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(sources, key = { _, src -> "s:${src.index}" }) { i, src ->
                GlassRow(
                    onClick = { onPick(src.index) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    leading = { Text("${i + 1}", color = LocalContentColor.current.copy(alpha = 0.6f), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(24.dp)) },
                    title = listOfNotNull(qualityLabel(src.quality), src.playlistName).joinToString(" · "),
                    subtitle = listOfNotNull(src.channelName, s.sourceHistory(src.history)).joinToString("\n"),
                    trailing = { if (src.down) Text(s.proSourceDown, style = MaterialTheme.typography.bodySmall, color = DownRed) },
                )
            }
        }
    }
}

private val DownRed = Color(0xFFFF6B6B)

/** A qualidade pelo nome ("FHD"), ou null se o nome não diz. */
private fun qualityLabel(q: Int): String? = when (q) {
    ChannelMatch.Quality.SD -> "SD"
    ChannelMatch.Quality.HD -> "HD"
    ChannelMatch.Quality.FHD -> "FHD"
    ChannelMatch.Quality.UHD -> "4K"
    ChannelMatch.Quality.Q8K -> "8K"
    else -> null
}
