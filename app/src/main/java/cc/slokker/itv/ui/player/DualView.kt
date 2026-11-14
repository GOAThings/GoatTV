package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Icon
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.theme.LocalAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.state.PlaybackState
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.explainPlaylistError
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import cc.slokker.itv.ui.player.engine.ChannelPlayback
import cc.slokker.itv.ui.player.engine.PlaybackPolicy
import cc.slokker.itv.ui.player.engine.PlaybackRequest
import cc.slokker.itv.ui.player.engine.SecondPlaybackHost
import cc.slokker.itv.ui.player.engine.aspectOrNull
import cc.slokker.itv.ui.player.engine.rememberChannelPlayback
import cc.slokker.itv.ui.common.focusWithRetry

/** Como se mostram os canais: um grande e os outros pequenos por cima, ou o ecrã dividido entre todos. */
internal enum class DualLayout { PIP, SPLIT }

/** Um lugar no ecrã, em frações dele (0–1): canto, largura e altura. */
internal data class TileRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Onde fica cada lugar com [count] canais (2–4). O lugar 0 é o grande na imagem na imagem (os outros ficam em coluna
 * no canto de cima à direita); no dividido, 2 canais ficam lado a lado e 3 ou 4 numa grelha 2×2 (com 3, o terceiro ao
 * centro em baixo), todos 16:9, com margem à volta e um espaço entre eles (para os cantos arredondados).
 */
internal fun tileRects(count: Int, layout: DualLayout): List<TileRect> {
    if (count <= 1) return listOf(TileRect(0f, 0f, 1f, 1f))
    return when (layout) {
        DualLayout.PIP -> listOf(TileRect(0f, 0f, 1f, 1f)) + (1 until count).map { k ->
            TileRect(1f - PIP_FRACTION - PIP_MARGIN_X, PIP_MARGIN_Y + (k - 1) * (PIP_FRACTION + PIP_GAP), PIP_FRACTION, PIP_FRACTION)
        }
        DualLayout.SPLIT -> {
            // A mesma fração na largura e na altura: 16:9 num ecrã 16:9. O espaço na vertical é o mesmo em píxeis.
            val w = (1f - 2 * SPLIT_MARGIN - SPLIT_GAP) / 2
            val gapY = SPLIT_GAP * 16 / 9
            val left = SPLIT_MARGIN
            val right = SPLIT_MARGIN + w + SPLIT_GAP
            val top = (1f - 2 * w - gapY) / 2
            val bottom = top + w + gapY
            when (count) {
                2 -> listOf(TileRect(left, (1f - w) / 2, w, w), TileRect(right, (1f - w) / 2, w, w))
                3 -> listOf(TileRect(left, top, w, w), TileRect(right, top, w, w), TileRect((1f - w) / 2, bottom, w, w))
                else -> listOf(TileRect(left, top, w, w), TileRect(right, top, w, w), TileRect(left, bottom, w, w), TileRect(right, bottom, w, w))
            }
        }
    }
}

/** No dividido: a margem às bordas do ecrã e o espaço entre canais, em frações da largura. */
private const val SPLIT_MARGIN = 0.015f
private const val SPLIT_GAP = 0.008f

/** O arredondado dos canais no dividido. */
internal val SplitCorner = 15.dp

/** A imagem pequena: um quarto da largura (e da altura: fica 16:9 num ecrã 16:9), afastada das margens. */
private const val PIP_FRACTION = 0.25f
private const val PIP_MARGIN_X = 0.017f
private const val PIP_MARGIN_Y = 0.03f
private const val PIP_GAP = 0.015f

/** Liga/desliga o som de um player (sem som não chega a abrir o áudio: dá para ter passthrough num e PCM no outro). */
internal fun setAudible(player: Player, audible: Boolean) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
        .build()
}

/**
 * Um canal extra (o do lugar [slot]): o mesmo [ChannelPlayback] do principal, com a política dos extra (sem pausa do
 * direto nem VLC, áudio PCM, a voltar a tentar sozinho de 3 em 3 s, com failovers). Só há player enquanto houver canal
 * nesse lugar (null sem ele).
 */
@Composable
internal fun rememberSecondPlayback(
    vm: AppViewModel,
    slot: Int,
    second: PlaybackState.SecondView?,
    prefs: Prefs,
    streamFormat: String?,
    onVideoSize: (Float) -> Unit,
): ChannelPlayback? {
    val latestPrefs by rememberUpdatedState(prefs)
    val host = remember { SecondPlaybackHost(vm, slot) { latestPrefs } }
    val playback = rememberChannelPlayback(PlaybackPolicy.SECOND, host, prefs, channelKey = null, enabled = second != null)
    val player = playback.player
    val latestOnVideoSize by rememberUpdatedState(onVideoSize)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSize.aspectOrNull()?.let { latestOnVideoSize(it) }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    LaunchedEffect(player, second?.channel?.key, second?.content?.playlist) {
        val sv = second ?: return@LaunchedEffect
        if (player == null) return@LaunchedEffect
        playback.tune(PlaybackRequest.of(sv.content, sv.channel, null, streamFormat, stalker = null))
    }
    return playback.takeIf { player != null }
}

/** Passos da escolha do segundo canal: lista → grupo → canal. Voltar recua um passo. */
@Stable
internal class SecondPickerState {
    var content by mutableStateOf<PlaylistContent?>(null)
    var group by mutableStateOf<Category?>(null)
    var loading by mutableStateOf(false)

    /** Recua um passo; false se já está no primeiro (aí Voltar fecha). */
    fun back(): Boolean = when {
        group != null -> {
            group = null
            true
        }
        content != null -> {
            content = null
            true
        }
        else -> false
    }

    fun reset() {
        content = null
        group = null
        loading = false
    }

    /** Carrega a lista escolhida e passa aos grupos. */
    fun open(vm: AppViewModel, scope: CoroutineScope, p: Playlist) {
        loading = true
        scope.launch {
            try {
                content = vm.loadForSecond(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showToast(explainPlaylistError(I18n.s, p.name, e).title)
            } finally {
                loading = false
            }
        }
    }
}

/** Escolha do segundo canal, encostada à esquerda como a pesquisa. */
@Composable
internal fun SecondChannelPicker(
    vm: AppViewModel,
    state: SecondPickerState,
    /** O lugar cujo canal se vai mudar (larga a sua ligação), ou null para um canal novo. */
    replacing: Int?,
    width: Dp,
    onPick: (PlaylistContent, Channel, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val main by vm.content.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val content = state.content
    val group = state.group
    // Cada passo começa no primeiro item.
    LaunchedEffect(content, group, state.loading) { if (!state.loading) focusWithRetry(first) }

    Column(
        modifier
            .padding(start = 24.dp, top = 24.dp, bottom = 24.dp)
            .width(width)
            .fillMaxHeight()
            .glass(ItvShapes.Panel)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            group?.name ?: content?.playlist?.name ?: s.secondChannel,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            when {
                group != null -> s.secondPickChannel
                content != null -> s.secondPickGroup
                else -> s.secondPickPlaylist
            },
            color = ItvColors.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
        )
        when {
            state.loading -> Text(s.loadingShort, color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))

            content == null -> {
                val allowed = playlists.filter { vm.secondAllowed(it, replacing) }
                if (allowed.isEmpty()) {
                    Text(s.secondNeedsOther, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    // As listas permitidas primeiro (a principal, se tiver 1 ligação, fica no fim e desativada).
                    val ordered = allowed + (playlists - allowed.toSet())
                    itemsIndexed(ordered, key = { _, p -> p.id }) { i, p ->
                        val ok = p in allowed
                        GlassRow(
                            onClick = { state.open(vm, scope, p) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            enabled = ok,
                            title = p.name,
                            subtitle = when {
                                !ok -> s.secondOneConnection
                                p.id == main?.playlist?.id -> s.currentPlaylist
                                else -> null
                            },
                        )
                    }
                }
            }

            group == null -> {
                val groups = remember(content) { vm.groupsForSecond(content) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(groups, key = { _, g -> g.id }) { i, g ->
                        GlassRow(
                            onClick = { state.group = g },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            title = g.name,
                        )
                    }
                }
            }

            else -> {
                val channels = remember(content, group) { vm.channelsForSecond(content, group.id) }
                if (channels.isEmpty()) {
                    Text(s.noChannelsInGroup, color = ItvColors.TextDim, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(channels, key = { _, ch -> ch.uid }) { i, ch ->
                        GlassRow(
                            onClick = { onPick(content, ch, group.id) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            leading = if (prefs.showLogos) {
                                { ChannelLogo(ch, 34.dp); Spacer(Modifier.width(12.dp)) }
                            } else {
                                null
                            },
                            title = if (prefs.showNumbers) "${ch.num}  ${ch.name}" else ch.name,
                        )
                    }
                }
            }
        }
    }
}

/** Um canal no ecrã, para a decoração: o nome, se é o que se ouve, e o estado (null = a dar). */
internal class TileInfo(val name: String, val audible: Boolean, val status: String?)

/** O estado de um canal extra para a etiqueta: sem sinal (não arrancou, ou caiu e ainda não voltou), a sintonizar, ou null. */
internal fun extraStatus(playback: ChannelPlayback?, s: cc.slokker.itv.ui.i18n.Strings): String? = when {
    playback != null && !playback.ready && (playback.failure != null || playback.reconnects > 0) -> s.noSignal
    playback?.ready != true -> s.tuning
    else -> null
}

/**
 * Por cima das imagens: contorno no canal com foco (o que se ouve) e uma etiqueta com o nome, o som e o estado.
 * [modifiers]: os de cada lugar (os mesmos das imagens, filhos da mesma Box); [tiles] pela ordem dos lugares. Na
 * imagem na imagem, o canal em ecrã inteiro não leva etiqueta (para esse há a informação normal).
 */
@Composable
internal fun TileDecorations(tiles: List<TileInfo>, modifiers: List<Modifier>, split: Boolean) {
    val accent = LocalAccent.current
    tiles.forEachIndexed { position, tile ->
        val small = !split && position > 0
        val shape = RoundedCornerShape(if (split) SplitCorner else 4.dp)
        val border = when {
            tile.audible && (split || small) -> Modifier.border(3.dp, accent.brush, shape)
            small -> Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            else -> Modifier
        }
        Box(modifiers[position].then(border)) {
            if (split || small) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(ItvShapes.Control)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (tile.audible) {
                        Icon(ItvIcons.Audio, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        listOfNotNull(tile.name, tile.status).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * No dividido: pinta de preto tudo à volta dos canais, menos os cantos arredondados deles. O vídeo é uma SurfaceView
 * (por trás da janela), por isso um clip não o corta; o que se desenha por cima tapa-lhe os cantos.
 */
@Composable
internal fun SplitCornerMask(rects: List<TileRect>) {
    Canvas(Modifier.fillMaxSize()) {
        val r = SplitCorner.toPx()
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            rects.forEach { t ->
                addRoundRect(RoundRect(t.x * size.width, t.y * size.height, (t.x + t.w) * size.width, (t.y + t.h) * size.height, CornerRadius(r)))
            }
        }
        drawPath(path, Color.Black)
    }
}
