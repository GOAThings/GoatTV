package cc.slokker.itv.ui.mobile

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.media3.common.C
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.ui.player.HudLogo
import cc.slokker.itv.ui.player.BackupPanelState
import cc.slokker.itv.ui.player.rememberBackupPanel
import cc.slokker.itv.data.ChannelMatch
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.data.Channel
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.common.formatTime
import cc.slokker.itv.ui.common.rememberEpg
import cc.slokker.itv.ui.common.rememberNow
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.player.TuningPill
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.VideoView
import cc.slokker.itv.ui.player.PlaybackSpeed
import cc.slokker.itv.ui.player.Timeshift
import cc.slokker.itv.ui.player.VlcPlayer
import cc.slokker.itv.ui.player.behindLabel
import cc.slokker.itv.ui.player.rememberStreamState
import cc.slokker.itv.ui.player.trackOptions
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.ui.theme.tabular
import androidx.tv.material3.LocalTextStyle
import cc.slokker.itv.ui.player.engine.titleCard
import cc.slokker.itv.ui.player.engine.status
import cc.slokker.itv.ui.common.formatDuration
import cc.slokker.itv.data.at
import cc.slokker.itv.data.after
import cc.slokker.itv.data.minutesLeft
import cc.slokker.itv.ui.player.engine.ZapTrace

@Composable
internal fun VideoArea(
    vm: AppViewModel, channel: Channel, playback: MobilePlayback, fullscreen: Boolean, pip: Boolean, onPip: (() -> Unit)?,
    onGuide: () -> Unit, onChannels: () -> Unit, onOption: (PlayerOption) -> Unit, onFullscreen: () -> Unit,
) {
    val s = LocalStrings.current
    val view = LocalView.current
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableStateOf(0) }
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val activeRecordings by vm.activeRecordings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var seekDelta by remember { mutableStateOf(0L) }
    var seekBase by remember { mutableStateOf(0L) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableStateOf(0) }
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val liveEntry = { now: Long -> vm.programmes(channel, epg).at(now) }
    val ts = playback.timeshift
    // Em direto, as posições contam-se desde o início do programa a dar (ou desde agora, sem guia).
    fun liveBase(now: Long) = liveEntry(now)?.startMs ?: now
    /** Em direto: quanto [target] fica atrás do direto, se for a pausa do direto (no disco) a lá chegar; senão null (catch-up). */
    fun tsBehind(target: Long): Long? {
        if (vm.catchup.value != null || !ts.available) return null
        val now = System.currentTimeMillis()
        val behind = now - liveBase(now) - target
        val entry = liveEntry(now)
        // Mais atrás do que o disco guarda: o catch-up do programa, se houver.
        return if (behind <= ts.windowMs || entry == null || !vm.canCatchup(channel, entry)) behind.coerceIn(0L, ts.windowMs) else null
    }
    // No VLC, o tempo deixa de bater certo depois de saltar dentro do stream: reabre-se nessa posição.
    fun seeksInStream(cu: Catchup, target: Long) =
        target >= cu.positionAt(playback.archiveStart, 0) && playback.player !is VlcPlayer && playback.player.isCurrentMediaItemSeekable
    // Onde um salto vai mesmo parar: os últimos minutos ainda não estão no arquivo do servidor, e o servidor só abre
    // em minutos certos (a avançar vai para o seguinte, senão ficava no mesmo sítio; a recuar, para o início desse minuto).
    fun landing(target: Long, position: Long): Long {
        val cu = vm.catchup.value
        if (cu == null) {
            val wanted = target.coerceAtMost(position - Catchup.MIN_AGE_MS - SEEK_MS).coerceAtLeast(0)
            val start = liveEntry(System.currentTimeMillis())?.startMs ?: return wanted
            return (wanted - (start + wanted) % 60_000).coerceAtLeast(0)
        }
        val wanted = target.coerceAtLeast(0)
        if (seeksInStream(cu, wanted)) return wanted
        val extra = (cu.startMs + wanted) % 60_000
        return (if (wanted > position) wanted + (60_000 - extra) % 60_000 else wanted - extra).coerceAtLeast(0)
    }
    fun goTo(target: Long, position: Long) {
        val cu = vm.catchup.value
        tsBehind(target)?.let {
            ts.seekBehind(playback.player, it)
            return
        }
        if (cu == null) {
            val entry = liveEntry(System.currentTimeMillis())
            if (entry != null && target < position - 5_000) vm.playCatchup(channel, entry, landing(target, position))
            return
        }
        if (seeksInStream(cu, target)) {
            playback.player.seekTo(target - cu.positionAt(playback.archiveStart, 0))
        } else {
            vm.seekCatchup(landing(target, position))
        }
    }
    // Vários toques seguidos somam-se, e só depois se pede a posição nova: cada pedido reabre o stream.
    fun seek(delta: Long, position: Long) {
        if (seekDelta == 0L) seekBase = position
        seekDelta += delta
        touched++
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(SEEK_DEBOUNCE_MS)
            val target = seekBase + seekDelta
            seekDelta = 0
            goTo(target, seekBase)
        }
    }
    LaunchedEffect(controls, touched, channel.key, scrub) {
        if (controls && scrub == null) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(controls) {
        while (controls) {
            delay(1_000)
            tick++
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { controls = !controls; touched++ } }
            // Deslizar na vertical muda de canal.
            .pointerInput(Unit) {
                var drag = 0f
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (kotlin.math.abs(drag) > ZAP_DRAG_PX) {
                            ZapTrace.key(what = "swipe")
                            vm.zap(if (drag < 0) 1 else -1)
                        }
                    },
                ) { _, amount -> drag += amount }
            },
    ) {
        AndroidView(
            factory = { VideoView(it).also { v -> playback.view = v } },
            update = {
                playback.view = it
                // Como na TV: o ecrã não se apaga enquanto o canal dá (ou carrega).
                it.keepScreenOn = playback.playing || playback.buffering
            },
            modifier = Modifier.fillMaxSize(),
        )
        playback.freeze?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        when {
            // Não arrancou: porquê (como na TV) e a próxima tentativa; o Play tenta outra vez.
            playback.failure != null -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val failure = playback.failure!!
                Text(failure.titleCard(s).title, color = ItvColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center)
                failure.status(s)?.let { Text(it, color = ItvColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
            }
            controls && !pip -> {}
            playback.freeze != null -> Spinner(Modifier.align(Alignment.Center))
            // A abrir: que está a carregar, e o quê (a voltar a ligar diz-o a pílula, mais abaixo).
            !playback.ready && playback.reconnects == 0 -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Spinner()
                Text(s.loadingPlaylist(channel.name), color = ItvColors.TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        // A gravar: um ponto vermelho com "REC" no canto (também por cima dos botões).
        if (activeRecordings.isNotEmpty() && !pip) {
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(PhoneColors.LiveRed))
                Spacer(Modifier.width(6.dp))
                Text(s.recBadge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        // Caiu e está a voltar a ligar: ao alto a pílula no topo; em ecrã inteiro a cápsula da TV.
        if (playback.reconnects > 0 && !pip) {
            if (fullscreen) {
                TuningPill(
                    channel = channel.name,
                    reconnecting = true,
                    attempt = playback.reconnects,
                    nextTryAt = playback.nextTryAt,
                    waitMs = playback.retryDelayMs,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
                ReconnectIsland(channel.name, playback.nextTryAt, playback.retryDelayMs, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
        if (controls && !pip) {
            // O vídeo escurece um pouco, para os botões se verem mesmo num vídeo claro.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
            // Em ecrã inteiro, em cima: sair dele, a hora e a janela pequena.
            if (fullscreen) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlButton(ItvIcons.FullscreenExit, Modifier.size(40.dp).clip(CircleShape).background(PhoneColors.HudButton), onClick = onFullscreen)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(System.currentTimeMillis()), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp), style = LocalTextStyle.current.tabular)
                    onPip?.let { ControlButton(ItvIcons.Pip, Modifier.size(40.dp).clip(CircleShape).background(PhoneColors.HudButton), onClick = it) }
                }
            }
            Box(Modifier.align(if (fullscreen) Alignment.Center else BiasAlignment(0f, -0.3f))) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Em direto, recua-se para o catch-up do programa a dar; à frente do direto não há nada.
                    val cu = catchup
                    val live = if (cu == null) liveEntry(System.currentTimeMillis()) else null
                    val canSeek = cu != null || (cu == null && ts.available) || (live != null && vm.canCatchup(channel, live))
                    fun position(): Long {
                        if (cu != null) return cu.positionAt(playback.archiveStart, playback.player.currentPosition)
                        val now = System.currentTimeMillis()
                        return now - (if (ts.available) ts.behindMs else 0L) - liveBase(now)
                    }
                    if (canSeek) SeekButton(forward = false) { seek(-SEEK_MS, position()) }
                    // A abrir, a pausa não faz nada: fica o indicador no lugar dela, sem mexer nos outros botões.
                    if (playback.failure == null && (!playback.ready || playback.freeze != null)) {
                        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                            Spinner()
                        }
                    } else {
                        ControlButton(if (playback.playing) ItvIcons.Pause else ItvIcons.Play, Modifier.size(60.dp).clip(CircleShape).background(PhoneColors.HudButton)) {
                            when {
                                // Não arrancou: tenta outra vez desde o primeiro formato (o OK da TV).
                                playback.failure != null -> playback.core.retry()
                                playback.playing -> playback.player.pause()
                                // Pausa do direto: continua de onde parou (o disco continuou a gravar).
                                cu == null -> ts.resume(playback.player)
                                else -> playback.player.play()
                            }
                            touched++
                        }
                    }
                    if (canSeek) SeekButton(forward = true, enabled = cu != null || ts.shifted) { seek(SEEK_MS, position()) }
                }
                // Por cima dos botões, sem os mexer.
                if (seekDelta != 0L) {
                    val target = seekBase + seekDelta
                    val goesLive = catchup?.let { target >= (System.currentTimeMillis() - it.startMs - Catchup.MIN_AGE_MS).coerceAtMost(it.durationMs) } == true
                    val behind = tsBehind(target)
                    Text(
                        when {
                            behind != null -> if (behind <= Timeshift.LIVE_SNAP_MS) s.playerGoLive else behindLabel(behind)
                            goesLive -> s.playerGoLive
                            else -> "→ ${formatDuration(landing(target, seekBase))}"
                        },
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
            // Em baixo, como o HUD da TV: logótipo, canal, programa, horas, o seguinte e a barra larga.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ItvColors.Background.copy(alpha = 0.6f), ItvColors.Background.copy(alpha = 0.92f))))
                    .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) else Modifier)
                    .padding(start = if (fullscreen) 24.dp else 12.dp, end = if (fullscreen) 24.dp else 8.dp, top = if (fullscreen) 60.dp else 34.dp, bottom = if (fullscreen) 14.dp else 2.dp),
            ) {
                tick
                val now = System.currentTimeMillis()
                val cu = catchup
                val entry = if (cu == null) liveEntry(now) else null
                val programme = cu?.let { EpgEntry(it.title, it.startMs, it.endMs) } ?: entry
                val next = if (cu == null) vm.programmes(channel, epg).after(entry, now) else null
                Row(verticalAlignment = Alignment.Bottom) {
                    HudLogo(channel, Modifier.size(if (fullscreen) 92.dp else 56.dp, if (fullscreen) 52.dp else 32.dp))
                    Spacer(Modifier.width(if (fullscreen) 16.dp else 10.dp))
                    Column(Modifier.weight(1f)) {
                        if (fullscreen) {
                            Text(
                                "${channel.num}  ${channel.name}" + if (vm.isFavorite(channel)) "  ★" else "",
                                color = ItvColors.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            programme?.title ?: channel.name, color = Color.White, fontSize = if (fullscreen) 22.sp else 16.sp, fontWeight = FontWeight.ExtraBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (programme != null) {
                            val left = if (cu == null) " · " + s.minutesLeft(programme.minutesLeft(now)) else ""
                            Text("${formatTime(programme.startMs)} — ${formatTime(programme.endMs)}$left", color = ItvColors.TextDim, fontSize = if (fullscreen) 13.sp else 12.sp, maxLines = 1)
                        }
                    }
                    if (fullscreen && next != null) {
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.widthIn(max = 220.dp), horizontalAlignment = Alignment.End) {
                            Text(s.next(formatTime(next.startMs)), color = ItvColors.TextDim, fontSize = 11.sp)
                            Text(next.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!fullscreen) ControlButton(ItvIcons.Fullscreen, Modifier.size(38.dp).clip(CircleShape).background(PhoneColors.HudButton), onClick = onFullscreen)
                }
                // Onde vai e quanto dura: o catch-up, ou o programa a dar. Em direto, só se pode arrastar se houver catch-up.
                val duration = cu?.durationMs ?: entry?.let { it.endMs - it.startMs }
                if (duration != null && duration > 0) {
                    val position = when {
                        cu != null && playback.ready -> cu.positionAt(playback.archiveStart, playback.player.currentPosition)
                        cu != null -> cu.positionMs
                        // Na pausa do direto, o ponto fica onde está o que se vê.
                        else -> now - entry!!.startMs - (if (ts.available) ts.behindMs else 0L)
                    }
                    val fraction = scrub ?: (position.toFloat() / duration).coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // No catch-up conta onde vai; em direto, as horas já estão por cima.
                        if (cu != null) Text(formatDuration((fraction * duration).toLong()), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp), style = LocalTextStyle.current.tabular)
                        if (cu == null && ts.shifted && scrub == null) {
                            Text(behindLabel(ts.behindMs), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp), style = LocalTextStyle.current.tabular)
                        }
                        PlayerBar(
                            fraction, seekable = cu != null || ts.available || vm.canCatchup(channel, entry!!),
                            onChange = { scrub = it; touched++ },
                            onEnd = {
                                val target = ((scrub ?: fraction) * duration).toLong()
                                scrub = null
                                touched++
                                seekJob?.cancel()
                                seekDelta = 0
                                goTo(target, position)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (cu != null) Text(formatDuration(duration), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp), style = LocalTextStyle.current.tabular)
                    }
                }
                if (fullscreen) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Num ecrã estreito (ou com textos compridos) as pílulas deslizam; os botões da direita ficam.
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiveButton(catchup == null && !ts.shifted, behind = behindLabel(ts.behindMs).takeIf { catchup == null }) { if (catchup != null) vm.exitCatchup() else ts.goLive(playback.player) }
                            // Catch-up ou atrás do direto: a velocidade (1× → 1,25× → 1,5× → 2×).
                            if (catchup != null || ts.shifted) HudPill(ItvIcons.Replay, "${s.playbackSpeed} ${PlaybackSpeed.label(playback.speed, s)}") { playback.speed = PlaybackSpeed.next(playback.speed); touched++ }
                            HudPill(ItvIcons.Calendar, s.tabGuide, onClick = onGuide)
                            HudPill(if (vm.isFavorite(channel)) ItvIcons.Star else ItvIcons.StarOutline, s.favorite) {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                vm.toggleFavorite(channel)
                                touched++
                            }
                            HudPill(ItvIcons.Channels, s.searchChannels, onClick = onChannels)
                            val recording = activeRecordings.any { vm.recordingOf(channel)?.info?.id == it.info.id }
                            if (recording) {
                                HudPill(ItvIcons.Stop, s.stopRecording) { vm.stopRecording(channel); touched++ }
                            } else if (catchup == null && ts.available) {
                                HudPill(ItvIcons.Record, s.record) { vm.recordNow(channel, ts.feed); touched++ }
                            }
                        }
                        // O que estava no "mais": cada um no seu botão.
                        // O canal de reserva só no direto, numa lista com reserva.
        val backup = catchup == null && vm.content.collectAsStateWithLifecycle().value?.playlist?.id?.let(vm::backupOf) != null
        PlayerOption.entries.filter { it != PlayerOption.BACKUP || backup }.forEach { option ->
                            ControlButton(option.icon, Modifier.size(40.dp).clip(CircleShape).background(PhoneColors.HudButton)) { onOption(option); touched++ }
                        }
                    }
                }
            }
        }
    }
}

/** Um botão com texto por cima do vídeo (ecrã inteiro). */
@Composable
private fun HudPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(PhoneColors.HudButton).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}


/** O botão do direto: em direto só avisa; em catch-up ou na pausa do direto, volta ([behind]: quanto vai atrás). */
@Composable
private fun LiveButton(live: Boolean, behind: String? = null, onClick: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.clip(CircleShape).background(if (live) Color.Transparent else Color.White.copy(alpha = 0.16f)).clickable(enabled = !live, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) PhoneColors.LiveRed else Color.White.copy(alpha = 0.6f)))
        Spacer(Modifier.width(6.dp))
        Text(if (live) s.playerLive else s.playerGoLive, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (!live && behind != null) {
            Text("  $behind", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = LocalTextStyle.current.tabular)
        }
    }
}

/** A barra de progresso do leitor, para arrastar. */
@Composable
private fun PlayerBar(progress: Float, seekable: Boolean, onChange: (Float) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.brush
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier.height(30.dp).then(
            if (!seekable) Modifier else Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val pointer = awaitPointerEvent().changes.first()
                        pointer.consume()
                        if (!pointer.pressed) break
                        change((pointer.position.x / size.width).coerceIn(0f, 1f))
                    }
                    end()
                }
            },
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
        Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(CircleShape).background(accent))
        // O ponto onde vai, como na barra larga da TV.
        Box(Modifier.fillMaxWidth(progress), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.offset(x = 7.dp).size(14.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}


/** Recuar ou avançar 30 segundos, ao lado do botão de pausa. */
@Composable
private fun SeekButton(forward: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(ItvIcons.Replay, null, tint = tint, modifier = Modifier.size(34.dp).graphicsLayer { if (forward) scaleX = -1f })
        Text("30", color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** O que se escolhe no canal a dar, cada um no seu botão. */
internal enum class PlayerOption(val icon: ImageVector) { AUDIO(ItvIcons.Audio), SUBTITLES(ItvIcons.Subtitles), BACKUP(ItvIcons.SwapPlaylist), SLEEP(ItvIcons.Sleep) }

@Composable
private fun PlayerOption.label(): String {
    val s = LocalStrings.current
    return when (this) {
        PlayerOption.AUDIO -> s.audio
        PlayerOption.SUBTITLES -> s.subtitles
        PlayerOption.BACKUP -> s.backupChannel
        PlayerOption.SLEEP -> s.sleepTimer
    }
}

/** O áudio, as legendas ou o temporizador do canal a dar. */
@Composable
internal fun PlayerOptionsSheet(vm: AppViewModel, playback: MobilePlayback, option: PlayerOption, onClose: () -> Unit) {
    val s = LocalStrings.current
    val tracks = rememberStreamState(playback.player).tracks
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(PhoneColors.Scrim).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 560.dp)
                .clip(PhoneShapes.Sheet).background(PhoneColors.Sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (option) {
                PlayerOption.AUDIO, PlayerOption.SUBTITLES -> {
                    val text = option == PlayerOption.SUBTITLES
                    Section(option.label()) {
                        val options = trackOptions(tracks, if (text) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO, playback.player, text, playback.core::onManualTrack)
                        if (options.isEmpty()) Note(s.noTracks)
                        options.forEach { o ->
                            MenuRow(o.label, null, checked = o.selected) {
                                o.select()
                                onClose()
                            }
                        }
                    }
                }
                PlayerOption.BACKUP -> vm.playing.collectAsStateWithLifecycle().value?.let { channel ->
                    BackupSection(vm, channel) { target ->
                        onClose()
                        val data = vm.content.value ?: return@BackupSection
                        scope.launch { vm.backupStream(data, channel, target)?.let(playback.core::playBackup) }
                    }
                }
                PlayerOption.SLEEP -> Section(sleepAt?.let { s.sleepRemaining(minutesLeft(it, System.currentTimeMillis())) } ?: s.sleepTimer) {
                    MenuRow(s.subtitlesOff, null, checked = sleepAt == null) {
                        vm.setSleepTimer(null)
                        onClose()
                    }
                    listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                        MenuRow("$minutes min", null) {
                            vm.setSleepTimer(minutes)
                            onClose()
                        }
                    }
                }
            }
        }
    }
}

/** Por baixo do vídeo, numa fila que desliza: direto (ou voltar a ele), guia, favorito, áudio, legendas, temporizador e a janela pequena. */
@Composable
internal fun PlayerActions(vm: AppViewModel, channel: Channel, playback: MobilePlayback, onGuide: () -> Unit, onPip: (() -> Unit)?, onOption: (PlayerOption) -> Unit) {
    val s = LocalStrings.current
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val rows = rememberRowState(vm)
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val activeRecordings by vm.activeRecordings.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Atrás do direto (catch-up ou a pausa do direto): o botão volta a ele.
        val ts = playback.timeshift
        LiveButton(catchup == null && !ts.shifted, behind = behindLabel(ts.behindMs).takeIf { catchup == null }) { if (catchup != null) vm.exitCatchup() else ts.goLive(playback.player) }
        if (catchup != null || ts.shifted) ActionPill(ItvIcons.Replay, "${s.playbackSpeed} ${PlaybackSpeed.label(playback.speed, s)}") { playback.speed = PlaybackSpeed.next(playback.speed) }
        ActionPill(ItvIcons.Calendar, s.tabGuide, onClick = onGuide)
        val favorite = rows.isFavorite(channel)
        ActionPill(if (favorite) ItvIcons.Star else ItvIcons.StarOutline, s.favorite, tint = if (favorite) PhoneColors.FavoriteGold else ItvColors.Text) { vm.toggleFavorite(channel) }
        // Gravar o programa a dar (pelo anel da pausa do direto), ou parar a gravação deste canal.
        val recording = activeRecordings.isNotEmpty() && vm.recordingOf(channel) != null
        if (recording) {
            ActionPill(ItvIcons.Stop, s.stopRecording, tint = PhoneColors.LiveRed) { vm.stopRecording(channel) }
        } else if (catchup == null && ts.available) {
            ActionPill(ItvIcons.Record, s.record, tint = PhoneColors.LiveRed) { vm.recordNow(channel, ts.feed) }
        }
        PlayerOption.entries.forEach { option ->
            // Com o temporizador ligado, o botão diz quanto falta.
            val label = if (option == PlayerOption.SLEEP && sleepAt != null) "${minutesLeft(sleepAt!!, System.currentTimeMillis())} min" else option.label()
            ActionPill(option.icon, label, tint = if (option == PlayerOption.SLEEP && sleepAt != null) LocalAccent.current.start else ItvColors.Text) { onOption(option) }
        }
        onPip?.let { ActionPill(ItvIcons.Pip, "PiP", onClick = it) }
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, tint: Color = ItvColors.Text, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(PhoneColors.Card).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = ItvColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** O programa seguinte do canal a dar, com o lembrete (o que está a dar já se vê no vídeo e na lista). */
@Composable
internal fun NextUp(vm: AppViewModel, channel: Channel) {
    val s = LocalStrings.current
    val accent = LocalAccent.current
    val now = rememberNow()
    val epg = rememberEpg(channel, vm::cachedEpg, vm::epgFor)
    val entries = remember(epg, channel.key) { vm.programmes(channel, epg) }
    val rows = rememberRowState(vm)
    val current = entries.at(now)
    val next = entries.after(current, now) ?: return
    val reminded = rows.reminded(channel, next)
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp).fillMaxWidth().clip(PhoneShapes.List).background(PhoneColors.Card)
            .clickable { vm.toggleReminder(channel, next) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.next(formatTime(next.startMs)), color = ItvColors.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text(next.title, color = ItvColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(ItvIcons.Bell, s.setReminder, tint = if (reminded) accent.start else ItvColors.TextFaint, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}


private const val CONTROLS_MS = 4_000L
private const val SEEK_MS = 30_000L
private const val SEEK_DEBOUNCE_MS = 700L
private const val ZAP_DRAG_PX = 120f

/** O canal de reserva do canal a dar (como o painel da TV, ver BackupChannelPanel). */
@Composable
internal fun BackupSection(vm: AppViewModel, channel: Channel, mainId: String? = null, onTry: ((Channel) -> Unit)?) {
    val s = LocalStrings.current
    val state = rememberBackupPanel(vm, channel, mainId)
    val choices = state.choices
    Section(listOfNotNull(s.backupChannel, choices?.backup?.name).joinToString(" · ")) {
        when {
            state.loading -> Note(s.backupChecking)
            choices == null -> Note(s.backupUnavailable((mainId ?: vm.content.value?.playlist?.id)?.let(vm::backupOf)?.name.orEmpty()))
            else -> {
                val effective = state.effective
                if (onTry != null) MenuRow(s.backupChannelTry, null, icon = ItvIcons.Play, subtitle = effective?.name) { effective?.let(onTry) }
                val auto = choices.autoChannel
                when {
                    // Manual: o automático é uma sugestão, que se escolhe com um toque.
                    state.manual && auto != null -> MenuRow(s.backupSuggestion(auto.name), null, checked = state.choice == auto.key, subtitle = choices.content.categoryName(auto.categoryId)) { state.pick(auto.key) }
                    state.manual -> Note(state.autoLabel(s))
                    else -> MenuRow(s.backupChannelAuto, null, checked = state.choice == null, subtitle = state.autoLabel(s)) { state.pick(null) }
                }
                MenuRow(s.backupChannelOff, null, checked = state.none) { state.pick(ChannelMatch.OFF) }
                val chosen = choices.content.byKey[state.choice ?: ""]
                val similar = (listOfNotNull(chosen) + choices.similar).distinctBy { it.key }
                if (similar.isNotEmpty()) Note(s.backupChannelSimilar)
                similar.forEach { c -> BackupRow(state, choices, c, s) }
                Field(s.backupChannelSearch(choices.backup.name), state.query, Modifier.padding(horizontal = 12.dp).padding(vertical = 8.dp)) { state.query = it }
                state.results.forEach { c -> BackupRow(state, choices, c, s) }
            }
        }
    }
}

@Composable
private fun BackupRow(state: BackupPanelState, choices: AppViewModel.BackupChoices, c: Channel, s: Strings) {
    val sub = listOfNotNull(choices.content.categoryName(c.categoryId), s.backupChannelAuto.takeIf { c.key == choices.autoChannel?.key }).joinToString(" · ")
    MenuRow(c.name, null, checked = state.choice == c.key, subtitle = sub) { state.pick(c.key) }
}
