package cc.slokker.itv.ui.player

import cc.slokker.itv.ui.theme.ItvShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.launch
import cc.slokker.itv.ui.player.engine.ChannelPlayback
import cc.slokker.itv.ui.player.engine.PlaybackPolicy
import cc.slokker.itv.ui.player.engine.PlaybackRequest
import cc.slokker.itv.ui.player.engine.SecondPlaybackHost
import cc.slokker.itv.ui.player.engine.aspectOrNull
import cc.slokker.itv.ui.player.engine.rememberChannelPlayback

/** Como se mostram os dois canais. */
internal enum class DualLayout { PIP, SPLIT }

/** Liga/desliga o som de um player (sem som não chega a abrir o áudio: dá para ter passthrough num e PCM no outro). */
internal fun setAudible(player: Player, audible: Boolean) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
        .build()
}

/**
 * O segundo canal: o mesmo [ChannelPlayback] do principal, com a política do segundo (sem pausa do direto nem
 * VLC, áudio PCM, a voltar a tentar sozinho de 3 em 3 s). Só há player enquanto houver segundo canal (null sem ele).
 */
@Composable
internal fun rememberSecondPlayback(
    vm: AppViewModel,
    second: PlaybackState.SecondView?,
    prefs: Prefs,
    streamFormat: String?,
    onVideoSize: (Float) -> Unit,
): ChannelPlayback? {
    val latestPrefs by rememberUpdatedState(prefs)
    val host = remember { SecondPlaybackHost(vm) { latestPrefs } }
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
        playback.tune(PlaybackRequest.of(sv.content, sv.channel, null, streamFormat, stalkerLinks = null))
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
}

/** Escolha do segundo canal, encostada à esquerda como a pesquisa. */
@Composable
internal fun SecondChannelPicker(
    vm: AppViewModel,
    state: SecondPickerState,
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

    fun open(p: Playlist) {
        state.loading = true
        scope.launch {
            try {
                state.content = vm.loadForSecond(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.showToast(explainPlaylistError(I18n.s, p.name, e).title)
            } finally {
                state.loading = false
            }
        }
    }

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
                val allowed = playlists.filter { vm.secondAllowed(it) }
                if (allowed.isEmpty()) {
                    Text(s.secondNeedsOther, color = ItvColors.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    // As listas permitidas primeiro (a principal, se tiver 1 ligação, fica no fim e desativada).
                    val ordered = allowed + (playlists - allowed.toSet())
                    itemsIndexed(ordered, key = { _, p -> p.id }) { i, p ->
                        val ok = p in allowed
                        GlassRow(
                            onClick = { open(p) },
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

/**
 * Por cima das duas imagens: contorno no canal com foco e uma etiqueta com o nome, o som e o estado.
 * Os [bigModifier]/[smallModifier] são os mesmos das imagens (filhos da mesma Box), para ficarem certinhos.
 */
@Composable
internal fun DualDecorations(
    second: PlaybackState.SecondView,
    mainChannel: Channel,
    playback: ChannelPlayback?,
    swapped: Boolean,
    focusSecond: Boolean,
    split: Boolean,
    bigModifier: Modifier,
    smallModifier: Modifier,
) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val secondModifier = if (swapped) bigModifier else smallModifier
    val mainModifier = if (swapped) smallModifier else bigModifier
    // Sem sinal: não arrancou, ou caiu e ainda não voltou; a sintonizar: a abrir.
    val secondStatus = when {
        playback != null && !playback.ready && (playback.failure != null || playback.reconnects > 0) -> s.noSignal
        playback?.ready != true -> s.tuning
        else -> null
    }

    @Composable
    fun Decoration(modifier: Modifier, name: String, audible: Boolean, small: Boolean, status: String?, showLabel: Boolean) {
        val border = when {
            audible && (split || small) -> Modifier.border(3.dp, accent.brush, RoundedCornerShape(4.dp))
            small -> Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            else -> Modifier
        }
        Box(modifier.then(border)) {
            if (showLabel) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(ItvShapes.Control)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (audible) {
                        Icon(ItvIcons.Audio, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        listOfNotNull(name, status).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // A etiqueta do canal em ecrã inteiro (imagem na imagem) não aparece: para esse há a informação normal.
    Decoration(mainModifier, mainChannel.name, audible = !focusSecond, small = !split && swapped, status = null, showLabel = split || swapped)
    Decoration(secondModifier, second.channel.name, audible = focusSecond, small = !split && !swapped, status = secondStatus, showLabel = split || !swapped)
}
