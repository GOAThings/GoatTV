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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.IPTV_USER_AGENT
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.streamCandidates
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.ChannelLogo
import cc.slokker.itv.ui.common.explainPlaylistError
import cc.slokker.itv.ui.glass.GlassRow
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Como se mostram os dois canais. */
internal enum class DualLayout { PIP, SPLIT }

/** Liga/desliga o som de um player (sem som não chega a abrir o áudio: dá para ter passthrough num e PCM no outro). */
internal fun setAudible(player: Player, audible: Boolean) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audible)
        .build()
}

/** O segundo player e o estado dele (a sintonizar / erro), para as etiquetas no ecrã. */
@Stable
internal class SecondPlayback(val player: Player) {
    var ready by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

/**
 * Cria o segundo player enquanto houver segundo canal, e liga-o ao canal escolhido (com as mesmas tentativas
 * ts ⇄ m3u8 do principal e a voltar a ligar sozinho se falhar). Sem passthrough: o som dele é sempre PCM.
 */
@Composable
internal fun rememberSecondPlayback(
    vm: AppViewModel,
    second: AppViewModel.SecondView?,
    prefs: Prefs,
    streamFormat: String?,
    onVideoSize: (Float) -> Unit,
): SecondPlayback? {
    val context = LocalContext.current
    val active = second != null
    val playback = remember(active, prefs.engine, prefs.buffer, prefs.decoder, prefs.audioDecoder) {
        if (active) SecondPlayback(buildPlayer(context, prefs.copy(audioPassthrough = false))) else null
    }
    DisposableEffect(playback) { onDispose { playback?.player?.release() } }
    val latestOnVideoSize by rememberUpdatedState(onVideoSize)
    val scope = rememberCoroutineScope()

    DisposableEffect(playback, second?.channel?.key, second?.content?.playlist) {
        val p = playback
        val sv = second
        if (p == null || sv == null || vm.testMode) return@DisposableEffect onDispose { }
        val candidates = streamCandidates(sv.content.playlist, sv.channel, streamFormat, sv.content.account?.allowedFormats.orEmpty())
        var attempt = 0
        var retryJob: Job? = null
        fun start() {
            val candidate = candidates.getOrNull(attempt) ?: return
            p.ready = false
            p.player.loadStream(candidate, sv.channel, nearLive = prefs.buffer == Prefs.Buffer.LIVE, userAgent = prefs.userAgent.ifBlank { IPTV_USER_AGENT })
            p.player.prepare()
            p.player.play()
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    p.ready = true
                    p.failed = false
                }
                // O servidor fechou a ligação do segundo canal (o direto não "acaba"): volta a ligar.
                if (state == Player.STATE_ENDED && p.ready) {
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(prefs.reconnectSeconds.coerceAtLeast(3) * 1_000L)
                        start()
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    latestOnVideoSize(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        p.player.seekToDefaultPosition()
                        p.player.prepare()
                    }
                    !p.ready && attempt + 1 < candidates.size -> {
                        attempt++
                        start()
                    }
                    else -> {
                        p.failed = true
                        retryJob?.cancel()
                        retryJob = scope.launch {
                            delay(prefs.reconnectSeconds.coerceAtLeast(3) * 1_000L)
                            attempt = 0
                            start()
                        }
                    }
                }
            }
        }
        p.player.addListener(listener)
        p.failed = false
        start()
        onDispose {
            retryJob?.cancel()
            p.player.removeListener(listener)
        }
    }
    return playback
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
    second: AppViewModel.SecondView,
    mainChannel: Channel,
    playback: SecondPlayback?,
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
    val secondStatus = when {
        playback?.failed == true -> s.noSignal
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
