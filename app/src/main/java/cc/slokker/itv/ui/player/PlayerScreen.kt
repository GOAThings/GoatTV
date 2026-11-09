@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.media3.common.Tracks
import androidx.media3.common.C
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.i18n.LocalStrings
import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import android.util.Log
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Prefs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.GlassBackdrop
import cc.slokker.itv.ui.glass.LocalGlassBackdrop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameMillis
import androidx.compose.animation.core.tween
import cc.slokker.itv.data.AppRelease
import cc.slokker.itv.ui.UpdateState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.exoplayer.ExoPlayer
import cc.slokker.itv.ui.player.engine.FREEZE_HEIGHT
import cc.slokker.itv.ui.player.engine.FREEZE_WIDTH
import cc.slokker.itv.ui.player.engine.aspectOrNull
import cc.slokker.itv.ui.player.engine.resizeModeFor
import cc.slokker.itv.ui.player.engine.PLAYER_LOGS
import cc.slokker.itv.ui.player.engine.ZapTrace
import cc.slokker.itv.ui.player.engine.cannotDecode
import cc.slokker.itv.ui.player.engine.selectSurroundTrack
import cc.slokker.itv.ui.player.engine.videoFormat
import cc.slokker.itv.ui.player.engine.titleCard
import cc.slokker.itv.ui.player.engine.status
import cc.slokker.itv.ui.player.engine.rememberChannelPlayback
import cc.slokker.itv.ui.player.engine.PlaybackRequest
import cc.slokker.itv.ui.player.engine.PlaybackPolicy
import cc.slokker.itv.ui.player.engine.AppPlaybackHost
import cc.slokker.itv.ui.common.focusWithRetry

/** Painel lateral: fechado, canais do grupo, ou grupos (no mesmo sítio). */
internal enum class Panel { None, Channels, Groups }

/**
 * Ecrã principal: o vídeo em ecrã inteiro com painéis de vidro por cima.
 *  - ←: canais do grupo, encostados à esquerda; ← outra vez: grupos no mesmo painel.
 *  - ↑/↓, CH+/CH−: mudar de canal; OK: informação do programa; Menu: favorito.
 *  - Guia, Pesquisa, Legendas, Áudio e Definições abrem o seu painel; Stop sai do catch-up; números: canal.
 *  - Voltar: fecha um nível; com tudo fechado, duas vezes para sair.
 */
@Composable
fun PlayerScreen(vm: AppViewModel) {
    val channel by vm.playing.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val streamFormat by vm.streamFormat.collectAsStateWithLifecycle()
    val selectedGroup by vm.selectedCategory.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val catchup by vm.catchup.collectAsStateWithLifecycle()
    val sleepAt by vm.sleepAt.collectAsStateWithLifecycle()
    val current = channel ?: return
    val data = content ?: return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestVm by rememberUpdatedState(vm)
    val latestPrefs by rememberUpdatedState(prefs)
    val latestData by rememberUpdatedState(data)
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    // A reprodução (tentativas, voltar a ligar, VLC, pausa do direto, ligação): a mesma do telemóvel e do segundo
    // canal. O player recria-se quando mudam as definições dele, ou quando este canal passa para o VLC.
    val host = remember { AppPlaybackHost(vm, { latestPrefs }, { latestData }) }
    val playback = rememberChannelPlayback(PlaybackPolicy.TV, host, prefs, current.key, net = vm.net) {
        if (latestPrefs.freezeFrame) videoView?.capture(FREEZE_WIDTH, FREEZE_HEIGHT)?.asImageBitmap() else null
    }
    val player = checkNotNull(playback.player)
    val frameRateMatcher = remember { (context as? Activity)?.let { FrameRateMatcher(it) } }
    /** AFR: a cadência do canal (medida pelas imagens, ver FpsEstimator) e quando se muda o modo da TV. */
    val frameRate = remember { FrameRateProbe() }
    val afr = remember { AfrSession() }
    /** Pausar o direto (ver Timeshift): o anel em disco do canal a dar. */
    val timeshift = checkNotNull(playback.timeshift)
    val backdrop = remember { GlassBackdrop() }

    var panel by remember { mutableStateOf(if (vm.consumeOpenPanelOnStart()) Panel.Channels else Panel.None) }
    var panelGroup by remember { mutableStateOf(selectedGroup) }
    var lastPanel by remember { mutableStateOf(Panel.Channels) }
    /** Os canais foram abertos a partir dos grupos: Voltar regressa aos grupos. */
    var playlistMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    /** As Definições abrem já na escolha do guia do canal (botão "Canal do guia" do HUD). */
    var settingsAtEpg by remember { mutableStateOf(false) }
    var infoToken by remember { mutableIntStateOf(0) }
    var infoVisible by remember { mutableStateOf(true) }
    /** false: cartão compacto (ao mudar de canal); true: barra completa (OK / Info). */
    var infoFull by remember { mutableStateOf(false) }
    // Hora da última tecla. Não é estado do Compose: escrevê-la a cada tecla não deve recompor o ecrã.
    val lastInput = remember { longArrayOf(0L) }
    var lastBack by remember { mutableLongStateOf(0L) }

    /** O guia foi aberto pelo Voltar (definição "Voltar no vídeo: abre o guia"), não pelo botão ou tecla do guia. */
    var guideFromBack by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    val rootFocus = remember { FocusRequester() }
    val hudFocus = remember { FocusRequester() }
    /** OK carregado no player, à espera de ser largado (a ação faz-se ao largar). */
    val okPressed = remember { booleanArrayOf(false) }

    var showGuide by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    /** Aviso "este canal está sempre a parar" (uma vez por canal; ver NetHintCard). */
    var netHint by remember { mutableStateOf(false) }
    val netHintFocus = remember { FocusRequester() }
    /** As Definições abrem já na página Ligação (botão "Detalhes" do aviso). */
    var settingsAtConnection by remember { mutableStateOf(false) }
    /** Lista de faixas aberta a partir do HUD: C.TRACK_TYPE_AUDIO ou C.TRACK_TYPE_TEXT. */
    var trackPicker by remember { mutableStateOf<Int?>(null) }
    /** EPG aberto ao lado da lista de canais (→ num canal). */
    var epgPanelChannel by remember { mutableStateOf<Channel?>(null) }
    val stream = rememberStreamState(player)
    /** Número a ser escrito com as teclas numéricas. */
    var numberEntry by remember { mutableStateOf("") }
    var reminder by rememberDueReminder(vm)
    /** Segundos até o temporizador parar a app (null = sem aviso). */
    var sleepWarning by remember { mutableStateOf<Int?>(null) }
    /** Catch-up: onde vai o vídeo (ms desde o início do programa), para a barra. */
    var catchupPosition by remember { mutableLongStateOf(0L) }
    /** Pausar, recuar e avançar, o mesmo no catch-up e na pausa do direto (ver TvSeek). */
    val seek = remember { TvSeek() }
    // Segundo canal (imagem na imagem / dividido).
    val second by vm.second.collectAsStateWithLifecycle()
    var dualLayout by remember { mutableStateOf(DualLayout.PIP) }
    /** Trocados: o segundo canal na imagem grande (ou à esquerda, no dividido). */
    var swapped by remember { mutableStateOf(false) }
    /** O som (e as setas ↑/↓) estão no segundo canal. */
    var focusSecond by remember { mutableStateOf(false) }
    var showSecondPicker by remember { mutableStateOf(false) }
    val secondPicker = remember { SecondPickerState() }
    var smallView by remember { mutableStateOf<VideoView?>(null) }
    // Onde cada player desenha: o principal no grande, a não ser que estejam trocados.
    val mainView = if (second != null && swapped) smallView else videoView
    val secondView = if (swapped) videoView else smallView
    val latestSecondView by rememberUpdatedState(secondView)
    val latestMainView by rememberUpdatedState(mainView)
    val secondPlayback = rememberSecondPlayback(vm, second, prefs, streamFormat) { ratio -> latestSecondView?.setAspectRatio(ratio) }
    val overlayOpen = showGuide || showSearch || showSleep || trackPicker != null || showSecondPicker || netHint

    /** Um painel, as Definições ou uma janela tapam o vídeo (a informação e a barra completa ficam escondidas). */
    fun covered() = panel != Panel.None || showSettings || overlayOpen

    /** As teclas são de outro (painel, Definições, janela ou PIN), não do player. */
    fun keysTaken() = covered() || pinFor != null

    // Versão nova (ver UpdateCard): aparece com o vídeo livre, ou enquanto descarrega/instala.
    val updateNotice by vm.updateNotice.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updateBusy = update is UpdateState.Downloading || update is UpdateState.Installing
    val showUpdate = updateNotice != null && (update is UpdateState.Available || updateBusy) && (
        updateBusy || !keysTaken() && reminder == null &&
            sleepWarning == null && catchup == null && !(timeshift.available && (seek.paused || !timeshift.atLive)) && !(infoVisible && infoFull)
        )
    var updateHold by remember { mutableFloatStateOf(0f) }
    var updateHidesIn by remember { mutableIntStateOf(UPDATE_CARD_SECONDS) }
    /** O OK que pode ser o "manter": quando se carregou (uptimeMillis; 0 = não está) e 1 se já passou a manter. */
    val holdFrom = remember { longArrayOf(0L, 0L) }
    val holdJob = remember { arrayOfNulls<Job>(1) }

    SideEffect { if (panel != Panel.None) lastPanel = panel }

    fun showInfo(full: Boolean = false) {
        infoFull = full
        infoVisible = true
        infoToken++
    }

    fun zap(delta: Int) = vm.zap(if (prefs.reverseZap) -delta else delta)

    /** O catch-up: ← → somam um minuto; cada salto é um pedido novo ao servidor a partir dessa posição. */
    val archive = catchup?.let { cu ->
        SeekTarget.archive(
            position = { catchupPosition }, durationMs = cu.durationMs, stepMs = SEEK_STEP_MS,
            onSeek = vm::seekCatchup, onPause = player::pause, onPlay = player::play, onExit = vm::exitCatchup,
        )
    }

    /** A pausa do direto: ← → somam 10 s atrás do direto; o salto é no disco (recomeça o player nesse ponto do anel). */
    val ring = SeekTarget.ring(
        behind = { timeshift.behindMs }, window = { timeshift.windowMs }, stepMs = TIMESHIFT_STEP_MS,
        onSeek = { timeshift.seekBehind(player, it) }, onPause = player::pause, onResume = { timeshift.resume(player) },
        onLive = { timeshift.goLive(player) },
    )
    val latestSeekTarget by rememberUpdatedState(archive ?: ring)

    /** A pausa do direto vale para este canal (o principal, em direto e sem o segundo canal). */
    fun tsActive() = catchup == null && second == null && timeshift.available

    /** Em pausa, atrás do direto, ou a escolher para onde ir: as teclas são as do catch-up. */
    fun tsShifted() = tsActive() && (seek.paused || !timeshift.atLive || seek.pending != null)

    fun tsToggle() {
        infoVisible = false
        seek.togglePause(ring)
    }

    /** Recua ou avança um passo; o salto só se faz quando se pára de carregar. */
    fun tsSeek(forward: Boolean) {
        infoVisible = false
        seek.step(ring, forward)
    }

    fun openChannels() {
        panelGroup = selectedGroup
        panel = Panel.Channels
    }

    // O cartão de versão nova desaparece sozinho (não conta enquanto se mantém OK, nem a descarregar).
    LaunchedEffect(showUpdate, updateBusy, updateNotice) {
        if (!showUpdate || updateBusy) return@LaunchedEffect
        updateHidesIn = UPDATE_CARD_SECONDS
        while (updateHidesIn > 0) {
            delay(1_000)
            if (holdFrom[0] == 0L) updateHidesIn--
        }
        vm.dismissUpdateNotice()
    }
    // Falhou a descarregar: o erro aparece nas Definições → Atualizar; o cartão sai.
    LaunchedEffect(update) {
        if (update is UpdateState.Failed) vm.dismissUpdateNotice()
    }

    // Novo canal: congela a última imagem e recomeça pelas tentativas na ordem do formato preferido.
    // Chaves estáveis: a lista atualizada por trás (mesmo servidor e canal) não reinicia o stream.
    // Em catch-up o mesmo: cada recuo/avanço é um pedido novo ao servidor a partir dessa posição.
    // A informação do canal aparece antes de o abrir: a passar canais depressa (atraso ao mudar de canal) acompanha
    // cada tecla, e o canal só abre quando se pára.
    LaunchedEffect(current.key, data.playlist, player, catchup) {
        val cu = catchup
        catchupPosition = cu?.positionMs ?: 0L
        seek.reset()
        when {
            cu != null -> {
                infoVisible = false
                seek.showBar()
            }
            prefs.infoSeconds > 0 -> showInfo()
            else -> infoVisible = false
        }
        playback.tune(PlaybackRequest.of(data, current, cu, streamFormat, vm::stalkerCandidates))
        // AFR: quando se souber a cadência deste canal, a TV muda para ela (conforme o modo).
        frameRate.reset()
        afr.newChannel()
        val mode = latestPrefs.afrMode
        val matcher = frameRateMatcher
        if (mode != Prefs.AfrMode.OFF && matcher != null) {
            snapshotFlow { playback.ready }.first { it }
            // Sem cadência de recurso, espera pela medida o que for preciso (até mudar de canal).
            val measured = withTimeoutOrNull(AFR_MEASURE_MS) { frameRate.fps.first { it > 0f } }
            val fps = measured ?: Prefs.AfrFallback.fps(latestPrefs.afrFallback) ?: frameRate.fps.first { it > 0f }
            val target = fps?.let { afr.onFps(mode, it) }
            if (PLAYER_LOGS) Log.d("Afr", "modo=$mode medida=$measured → ${target ?: "fica"}")
            target?.let { matcher.match(it, HdrType.of(player.videoFormat())) }
        }
    }

    // Posição no catch-up (para a barra).
    LaunchedEffect(catchup, player) {
        while (catchup != null) {
            if (playback.ready) playback.programmePositionMs()?.let { catchupPosition = it }
            delay(500)
        }
    }

    // A barra (do catch-up ou da pausa) esconde-se uns segundos depois da última tecla (fica enquanto está em pausa).
    LaunchedEffect(seek.barToken, seek.paused) {
        if (seek.paused) return@LaunchedEffect
        delay(CATCHUP_BAR_MS)
        seek.barVisible = false
    }

    // ← → somam-se; o salto só se faz quando se pára de carregar.
    LaunchedEffect(seek.pending) {
        val target = seek.pending ?: return@LaunchedEffect
        delay(SEEK_COMMIT_MS)
        seek.commit(latestSeekTarget, target)
    }

    // Cada player na sua imagem. Ao trocar, primeiro larga-se a imagem antiga (dois players não podem partilhar uma).
    LaunchedEffect(player, secondPlayback, mainView, secondView) {
        val p2 = secondPlayback?.player
        player.clearVideoSurface()
        p2?.clearVideoSurface()
        mainView?.let { v ->
            player.setVideoSurfaceView(v.surfaceView)
            player.videoSize.aspectOrNull()?.let { v.setAspectRatio(it) }
        }
        if (p2 != null) {
            secondView?.let { v ->
                p2.setVideoSurfaceView(v.surfaceView)
                p2.videoSize.aspectOrNull()?.let { v.setAspectRatio(it) }
            }
        }
    }

    // Só um dos canais tem som: o que tem o foco.
    LaunchedEffect(player, secondPlayback, focusSecond) {
        val p2 = secondPlayback?.player
        setAudible(player, p2 == null || !focusSecond)
        p2?.let { setAudible(it, focusSecond) }
    }

    // Sem segundo canal, tudo volta ao normal.
    LaunchedEffect(second == null) {
        if (second == null) {
            swapped = false
            focusSecond = false
        }
    }

    LaunchedEffect(prefs.aspect, videoView) {
        videoView?.setResizeMode(resizeModeFor(prefs.aspect))
    }

    // O que é só da TV (o resto dos eventos trata-o o ChannelPlayback): proporção, AFR, áudio surround, legendas.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoSize.aspectOrNull()?.let { latestMainView?.setAspectRatio(it) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // A passar para o VLC (o ChannelPlayback trata disso): neste player não há nada a escolher.
                if (player !is VlcPlayer && tracks.cannotDecode()) return
                // AFR: a cadência que o formato diz (HLS com FRAME-RATE, VLC); no TS mede-se pelas imagens.
                val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)
                frameRate.fromFormat(video?.frameRate ?: player.videoFormat()?.frameRate ?: 0f)
                // Faixa surround por defeito (uma vez por canal, para não desfazer uma escolha manual).
                if (latestPrefs.surroundDefault && !playback.audioChosen) {
                    playback.audioChosen = selectSurroundTrack(player, tracks)
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                latestMainView?.subtitleView?.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Não arrancou: a informação do canal aparece com o erro.
    LaunchedEffect(playback.failure?.analysing) {
        if (playback.failure?.analysing == true) showInfo()
    }

    // Box em segundo plano / ecrã desligado: parar o stream (e libertar a ligação Xtream). Ao recriar o player
    // (definições, VLC) o canal só volta a abrir pelo efeito acima, uma vez.
    LifecycleStartEffect(playback) {
        playback.onStart()
        onStopOrDispose {
            // O segundo canal fecha-se ao sair da app (liberta a ligação do outro fornecedor).
            vm.closeSecond()
            playback.stop()
            seek.reset()
            frameRateMatcher?.restore()
            afr.reset()
            vm.flushLastChannel()
        }
    }

    // Aviso de paragens: uma vez por canal, quando parou várias vezes e se percebe porquê. drop(1): o estado
    // atual ainda pode ser o do canal anterior (a medição recomeça do zero ao mudar).
    LaunchedEffect(current.key, prefs.netWarnings) {
        netHint = false
        if (!prefs.netWarnings) return@LaunchedEffect
        vm.net.state.drop(1).first { it.needsHint() }
        netHint = true
        delay(NET_HINT_MS)
        netHint = false
    }

    // Guia, Definições ou painéis por cima: o aviso sai (não fica escondido à espera).
    LaunchedEffect(showGuide, showSettings, panel) {
        if (showGuide || showSettings || panel != Panel.None) netHint = false
    }

    LaunchedEffect(netHint) { if (netHint) focusWithRetry(netHintFocus) }

    // AFR desligado nas definições: devolve já a TV ao modo normal.
    LaunchedEffect(prefs.afrMode) {
        if (prefs.afrMode == Prefs.AfrMode.OFF) {
            frameRateMatcher?.restore()
            afr.reset()
        }
    }

    // As imagens do ExoPlayer medem a cadência do canal (o TS não a diz).
    DisposableEffect(player) {
        val exo = player as? ExoPlayer
        exo?.let(frameRate::attach)
        onDispose { exo?.let(frameRate::detach) }
    }

    LaunchedEffect(prefs.glassOpacity) { backdrop.opacity = prefs.glassOpacity / 100f }

    if (PLAYER_LOGS) {
        LaunchedEffect(player) {
            while (true) {
                delay(2_000)
                // Parado (app em segundo plano, ou sem canal): nada a registar.
                if (player.playbackState == Player.STATE_IDLE) continue
                android.util.Log.d("ItvPlayer", "buffer=${player.totalBufferedDuration}ms state=${player.playbackState}")
            }
        }
    }

    // Fundo do vidro: miniatura desfocada do vídeo, renovada enquanto houver painéis à vista.
    // O guia não usa vidro (o vídeo fica pequeno, no canto).
    val glassVisible = !showGuide && (
        panel != Panel.None || showSettings || infoVisible || playback.failure != null || !playback.ready ||
            showSearch || showSleep || trackPicker != null || showSecondPicker || catchup != null || reminder != null || sleepWarning != null || numberEntry.isNotEmpty() ||
            tsShifted()
        )
    GlassBackdropEffect(glassVisible, videoView, backdrop, prefs.glassOpacity, lastInput, player)

    // A informação esconde-se 4 s depois de o canal arrancar (fica enquanto sintoniza ou há erro).
    // A barra completa (OK) tem botões: fica enquanto se mexe no comando.
    LaunchedEffect(infoToken, playback.ready, playback.failure, infoFull) {
        if (playback.ready && playback.failure == null) {
            if (infoFull) {
                lastInput[0] = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - lastInput[0] < HUD_TIMEOUT_MS) delay(500)
            } else {
                delay((prefs.infoSeconds.takeIf { it > 0 } ?: 4) * 1_000L)
            }
            infoVisible = false
        }
    }

    // Com a barra completa à vista, o foco vai para os botões dela.
    val hudOpen = infoVisible && infoFull && !covered() && catchup == null
    LaunchedEffect(hudOpen) { if (hudOpen) focusWithRetry(hudFocus) }

    // Teclas numéricas: o número fica à espera de mais algarismos e muda de canal ao fim de um bocado (logo, se
    // nenhum canal tem um número mais comprido que comece assim).
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        if (numberEntry.length < 4 && vm.numberCanGrow(numberEntry)) delay(NUMBER_COMMIT_MS)
        val n = numberEntry.toIntOrNull()
        numberEntry = ""
        if (n != null && !vm.playNumber(n)) vm.showToast(I18n.s.noChannelNumber(n))
    }

    // Temporizador: aviso no último minuto e, no fim, pára e fecha a app.
    SleepTimerEffect(vm, sleepAt, player, context) { sleepWarning = it }


    LaunchedEffect(panel) {
        if (panel != Panel.Groups) playlistMenu = false
        if (panel != Panel.Channels) epgPanelChannel = null
    }

    // Sem mexer no comando, o painel fecha-se sozinho.
    LaunchedEffect(panel, prefs.panelTimeout) {
        if (panel == Panel.None || prefs.panelTimeout <= 0) return@LaunchedEffect
        lastInput[0] = SystemClock.uptimeMillis()
        while (true) {
            delay(500)
            if (SystemClock.uptimeMillis() - lastInput[0] >= prefs.panelTimeout * 1_000L) {
                panel = Panel.None
                break
            }
        }
    }

    // Com os painéis fechados, as teclas voltam ao player.
    LaunchedEffect(panel, showSettings, pinFor, overlayOpen, hudOpen) {
        if (!keysTaken() && !hudOpen) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    /** Voltar recua um nível (a ordem está em [tvBack]). */
    fun goBack() {
        val now = SystemClock.uptimeMillis()
        val state = TvBackState(
            reminder = reminder != null,
            numberEntry = numberEntry.isNotEmpty(),
            netHint = netHint,
            pin = pinFor != null,
            playlistMenu = playlistMenu,
            sleep = showSleep,
            secondPicker = showSecondPicker,
            tracks = trackPicker != null,
            search = showSearch,
            guide = showGuide,
            epgPanel = epgPanelChannel != null,
            panel = panel != Panel.None,
            info = infoVisible && playback.failure == null,
            catchup = catchup != null,
            shifted = tsShifted(),
            second = second != null,
            backOpensGuide = prefs.backAction == Prefs.BackAction.GUIDE,
            exitConfirm = prefs.exitConfirm,
        )
        when (tvBack(state, now - lastBack)) {
            TvBack.DISMISS_REMINDER -> {
                reminder?.let { vm.consumeReminder(it, watch = false) }
                reminder = null
            }
            TvBack.CLEAR_NUMBER -> numberEntry = ""
            TvBack.CLOSE_NET_HINT -> netHint = false
            TvBack.CLOSE_PIN -> pinFor = null
            TvBack.CLOSE_PLAYLIST_MENU -> playlistMenu = false
            TvBack.CLOSE_SLEEP -> showSleep = false
            TvBack.SECOND_PICKER -> if (!secondPicker.back()) showSecondPicker = false
            TvBack.CLOSE_TRACKS -> trackPicker = null
            TvBack.CLOSE_SEARCH -> showSearch = false
            TvBack.CLOSE_GUIDE -> {
                showGuide = false
                // Fechar o guia que o Voltar abriu conta como o 1.º Voltar para sair: o seguinte sai,
                // em vez de abrir o guia outra vez (senão Voltar ficava a abrir e fechar o guia para sempre).
                if (guideFromBack) {
                    guideFromBack = false
                    lastBack = now
                    if (prefs.exitConfirm) vm.showToast(I18n.s.pressBackToExit)
                }
            }
            TvBack.CLOSE_EPG_PANEL -> epgPanelChannel = null
            TvBack.CLOSE_PANEL -> panel = Panel.None
            TvBack.HIDE_INFO -> infoVisible = false
            TvBack.EXIT_CATCHUP -> vm.exitCatchup()
            TvBack.GO_LIVE -> seek.goLive(ring)
            TvBack.CLOSE_SECOND -> vm.closeSecond()
            TvBack.OPEN_GUIDE -> {
                showGuide = true
                guideFromBack = true
            }
            TvBack.EXIT -> (context as? Activity)?.finish()
            TvBack.WARN_EXIT -> {
                lastBack = now
                vm.showToast(I18n.s.pressBackToExit)
            }
        }
    }

    // As Definições tratam o seu próprio Voltar (páginas).
    BackHandler(enabled = !showSettings) { goBack() }

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { e ->
                    lastInput[0] = SystemClock.uptimeMillis()
                    val code = e.key.nativeKeyCode
                    val ok = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER || code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    // Aviso do temporizador: qualquer tecla mostra que há alguém a ver, e desliga-o.
                    if (sleepWarning != null && code != AndroidKeyEvent.KEYCODE_BACK) {
                        if (e.type == KeyEventType.KeyUp) vm.setSleepTimer(null)
                        return@onPreviewKeyEvent true
                    }
                    // Lembrete no ecrã: OK muda para o programa (Voltar ignora, mais abaixo).
                    if (reminder != null && ok) {
                        if (e.type == KeyEventType.KeyUp) {
                            reminder?.let { vm.consumeReminder(it, watch = true) }
                            reminder = null
                            showGuide = false
                            showSearch = false
                        }
                        return@onPreviewKeyEvent true
                    }
                    // Cartão de versão nova: manter OK carregado instala; um toque continua a ser o OK de sempre.
                    // Conta-se o tempo aqui, sem depender das repetições da tecla (cada comando manda as suas).
                    if (showUpdate && !updateBusy && ok) {
                        if (e.type == KeyEventType.KeyDown) {
                            if (e.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true
                            holdFrom[0] = SystemClock.uptimeMillis()
                            holdJob[0]?.cancel()
                            holdJob[0] = scope.launch {
                                delay(UPDATE_TAP_MS)
                                holdFrom[1] = 1L
                                while (true) {
                                    updateHold = ((SystemClock.uptimeMillis() - holdFrom[0]).toFloat() / UPDATE_HOLD_MS).coerceAtMost(1f)
                                    if (updateHold >= 1f) {
                                        vm.installUpdate()
                                        break
                                    }
                                    withFrameMillis { }
                                }
                            }
                            return@onPreviewKeyEvent false
                        }
                        if (e.type == KeyEventType.KeyUp) {
                            holdJob[0]?.cancel()
                            val held = holdFrom[1] == 1L
                            holdFrom[0] = 0L
                            holdFrom[1] = 0L
                            updateHold = 0f
                            if (held) {
                                okPressed[0] = false
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    // Apanhar o Voltar aqui: senão a 1.ª pressão só tira o foco do painel.
                    if (e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_BACK && !showSettings) {
                        if (e.type == KeyEventType.KeyUp) goBack()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(rootFocus)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp && okPressed[0] && isOkKey(e.key.nativeKeyCode)) {
                        okPressed[0] = false
                        if (keysTaken()) return@onKeyEvent false
                        if (numberEntry.isNotEmpty()) {
                            val n = numberEntry.toIntOrNull()
                            numberEntry = ""
                            if (n != null && !vm.playNumber(n)) vm.showToast(I18n.s.noChannelNumber(n))
                        } else {
                            if (playback.failure != null) playback.retry()
                            showInfo(full = true)
                        }
                        return@onKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown || keysTaken()) return@onKeyEvent false
                    val code = e.key.nativeKeyCode
                    // Medir o zapping (debug/staging): a hora da própria tecla.
                    if (code in ZAP_KEYS) ZapTrace.key(e.nativeKeyEvent.eventTime, AndroidKeyEvent.keyCodeToString(code))
                    // Teclas numéricas: escrever o número do canal.
                    digitOf(code)?.let { d ->
                        if (numberEntry.length < 4) numberEntry += d
                        return@onKeyEvent true
                    }
                    when (code) {
                        AndroidKeyEvent.KEYCODE_GUIDE -> {
                            guideFromBack = false
                            showGuide = true; return@onKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_SEARCH -> {
                            showSearch = true; return@onKeyEvent true
                        }
                        // Teclas de comandos com mais botões (ou do GoatRemote, no telemóvel).
                        AndroidKeyEvent.KEYCODE_CAPTIONS -> {
                            infoVisible = false
                            trackPicker = C.TRACK_TYPE_TEXT; return@onKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                            infoVisible = false
                            trackPicker = C.TRACK_TYPE_AUDIO; return@onKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_SETTINGS -> {
                            infoVisible = false
                            showSettings = true
                            vm.refreshConfig()
                            return@onKeyEvent true
                        }
                        AndroidKeyEvent.KEYCODE_MEDIA_STOP -> {
                            if (catchup != null) {
                                vm.exitCatchup(); return@onKeyEvent true
                            }
                            if (tsShifted()) {
                                seek.goLive(ring); return@onKeyEvent true
                            }
                        }
                    }
                    // Catch-up: ← → recuam/avançam 1 minuto, OK pausa.
                    if (archive != null) {
                        when (code) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                val back = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND
                                seek.step(archive, forward = !back)
                                return@onKeyEvent true
                            }
                            // No catch-up as setas não mudam de canal (é um programa gravado): só mostram a barra.
                            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                seek.showBar()
                                return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                if (playback.failure != null) {
                                    playback.retry()
                                    seek.showBar()
                                } else {
                                    seek.togglePause(archive)
                                }
                                return@onKeyEvent true
                            }
                        }
                    }
                    // Pausar o direto: Play/Pausa pausa, ⏪ ⏩ recuam/avançam. Em pausa ou atrás do direto, as teclas
                    // são as do catch-up (← → recuar/avançar, OK pausa, ↑ ↓ só mostram a barra, Voltar direto).
                    if (tsActive()) {
                        val shifted = tsShifted()
                        when (code) {
                            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                val already = (code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY && !seek.paused) || (code == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE && seek.paused)
                                if (already) seek.showBar() else tsToggle()
                                return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                tsSeek(forward = false); return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                if (shifted) tsSeek(forward = true)
                                return@onKeyEvent true
                            }
                        }
                        if (shifted) {
                            when (code) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    tsSeek(forward = code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT)
                                    return@onKeyEvent true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                    seek.showBar(); return@onKeyEvent true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                    tsToggle(); return@onKeyEvent true
                                }
                            }
                        }
                    }
                    // Dois canais: ← → passam o foco (e o som) de um para o outro; ↑ ↓ mudam o canal com foco.
                    if (second != null) {
                        when (code) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                focusSecond = !focusSecond
                                return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                val delta = if (code == AndroidKeyEvent.KEYCODE_DPAD_UP) -1 else 1
                                if (focusSecond) vm.zapSecond(if (prefs.reverseZap) -delta else delta) else zap(delta)
                                return@onKeyEvent true
                            }
                        }
                    }
                    when (code) {
                        // → volta ao último canal visto (e outra vez regressa).
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> {
                            if (!vm.playPrevious()) vm.showToast(I18n.s.noPreviousChannel)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            openChannels(); true
                        }
                        // ↑ = canal anterior na lista, ↓ = seguinte (ou abrem a lista, conforme as definições).
                        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (prefs.upDown == Prefs.UpDown.LIST) {
                                openChannels()
                            } else {
                                zap(if (e.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                            }
                            true
                        }
                        // CH+ = seguinte, CH− = anterior (sempre, não dependem das definições).
                        AndroidKeyEvent.KEYCODE_CHANNEL_UP -> {
                            vm.zap(1); true
                        }
                        AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            vm.zap(-1); true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_INFO -> {
                            // Só ao largar a tecla (mais acima): senão o botão do HUD que recebe o foco
                            // apanha o "largar" desta mesma pressão e abre-se sozinho.
                            okPressed[0] = true
                            true
                        }
                        AndroidKeyEvent.KEYCODE_MENU -> {
                            vm.toggleFavorite(current); true
                        }
                        else -> false
                    }
                },
        ) {
            val split = second != null && dualLayout == DualLayout.SPLIT && !showGuide
            val bigModifier = when {
                split -> Modifier.align(Alignment.CenterStart).fillMaxWidth(0.5f).aspectRatio(16f / 9f)
                else -> Modifier.fillMaxSize()
            }
            val smallModifier = if (split) {
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).aspectRatio(16f / 9f)
            } else {
                Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 32.dp).size(PIP_WIDTH, PIP_HEIGHT)
            }
            Box(bigModifier) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).also { view ->
                            view.onBoundsChanged = { backdrop.videoBounds = it }
                            videoView = view
                        }
                    },
                    update = { it.keepScreenOn = playback.playing || playback.buffering },
                    modifier = Modifier.fillMaxSize(),
                )

                // Última imagem do canal anterior, até o novo mostrar o primeiro frame (sem ecrã preto).
                playback.freeze?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }

            // Segundo canal: a imagem pequena (por cima da grande) ou a metade direita.
            if (second != null && !showGuide) {
                Box(smallModifier.background(Color.Black)) {
                    AndroidView(
                        factory = { ctx -> VideoView(ctx, overlay = true).also { smallView = it } },
                        onRelease = { smallView = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                DualDecorations(
                    second = second!!,
                    mainChannel = current,
                    playback = secondPlayback,
                    swapped = swapped,
                    focusSecond = focusSecond,
                    split = split,
                    bigModifier = bigModifier,
                    smallModifier = smallModifier,
                )
            }

            AnimatedVisibility(panel != Panel.None, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.6f to Color.Transparent)))
            }

            // Guia de TV: por cima do vídeo, que continua em ecrã inteiro (escurecido por trás da grelha).
            if (showGuide) {
                TvGuide(
                    vm = vm,
                    playing = current,
                    startGroup = selectedGroup,
                    onPlay = { ch, group ->
                        vm.play(ch, group)
                        showGuide = false
                    },
                    onCatchup = { ch, entry ->
                        vm.playCatchup(ch, entry)
                        showGuide = false
                    },
                )
            }

            // Também enquanto espera pela próxima tentativa, com a última imagem ainda no ecrã.
            // Na pausa do direto, a gravar por trás, a cápsula de voltar a ligar não tapa a da pausa.
            if ((playback.reconnects > 0 && !tsShifted() || !playback.ready) && playback.failure == null && !showGuide && numberEntry.isEmpty()) {
                // Com o segundo canal no canto direito, o aviso passa para a esquerda.
                TuningPill(
                    channel = current.name,
                    reconnecting = playback.wasPlaying,
                    attempt = playback.reconnects,
                    nextTryAt = playback.nextTryAt,
                    waitMs = playback.retryDelayMs,
                    modifier = Modifier.align(if (second != null) Alignment.TopStart else Alignment.TopEnd),
                )
            }

            if (!showGuide) {
                playback.failure?.let { f ->
                    val s = LocalStrings.current
                    ErrorCard(f.titleCard(s), f.status(s), Modifier.align(Alignment.Center))
                }
            }

            val infoAllowed = infoVisible && !covered() && catchup == null
            AnimatedVisibility(
                visible = infoAllowed && !infoFull,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ZapCard(current, vm)
            }
            AnimatedVisibility(
                visible = infoAllowed && infoFull,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                val favorites by vm.favorites.collectAsStateWithLifecycle()
                val isFavorite = current.key in favorites[data.playlist.id].orEmpty()
                val container = playback.currentCandidate?.format
                NowPlayingBar(current, data, vm, badges = qualityBadges(stream, container)) { live ->
                    HudButtons(
                        vm, current, live, prefs.aspect, isFavorite, sleepAt,
                        pauseLive = tsActive(), second = second != null, dualLayout = dualLayout, firstFocus = hudFocus,
                    ) { action ->
                        when (action) {
                            HudAction.AUDIO -> trackPicker = C.TRACK_TYPE_AUDIO
                            HudAction.SUBTITLES -> trackPicker = C.TRACK_TYPE_TEXT
                            HudAction.PAUSE_LIVE -> tsToggle()
                            HudAction.SECOND_CHANNEL -> {
                                infoVisible = false
                                secondPicker.reset()
                                showSecondPicker = true
                            }
                            HudAction.SWAP -> swapped = !swapped
                            HudAction.LAYOUT -> dualLayout = if (dualLayout == DualLayout.PIP) DualLayout.SPLIT else DualLayout.PIP
                            HudAction.SEARCH -> {
                                infoVisible = false
                                showSearch = true
                            }
                            HudAction.GUIDE -> {
                                infoVisible = false
                                guideFromBack = false
                                showGuide = true
                            }
                            HudAction.EPG_CHANNEL -> {
                                infoVisible = false
                                settingsAtEpg = true
                                showSettings = true
                            }
                            HudAction.SLEEP -> showSleep = true
                        }
                    }
                }
            }

            catchup?.let { cu ->
                if (!showGuide) {
                    AnimatedVisibility(
                        visible = seek.barVisible || seek.paused || seek.pending != null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn() + slideInVertically { it / 3 },
                        exit = fadeOut() + slideOutVertically { it / 3 },
                    ) {
                        CatchupBar(cu, catchupPosition, seek.pending, seek.paused)
                    }
                    if (playback.ready || playback.failure != null) CatchupPill(seek.paused, Modifier.align(Alignment.TopEnd))
                }
            }

            if (tsActive() && !showGuide) {
                val shifted = tsShifted()
                AnimatedVisibility(
                    visible = (shifted && seek.barVisible) || seek.paused || seek.pending != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                ) {
                    TimeshiftBar(current.name, timeshift.behindMs, timeshift.windowMs, seek.pending, seek.paused, timeshift.atLive)
                }
                // Atrás do direto, a cápsula no canto diz quanto (como a do catch-up).
                if (shifted && playback.ready && playback.failure == null) {
                    CatchupPill(seek.paused, Modifier.align(Alignment.TopEnd), label = behindLabel(seek.pending ?: timeshift.behindMs))
                }
            }

            if (numberEntry.isNotEmpty()) {
                NumberEntryBadge(numberEntry, numberEntry.toIntOrNull()?.let { vm.channelByNumber(it) }, Modifier.align(Alignment.TopEnd))
            }

            AnimatedVisibility(
                visible = showSearch,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn() + slideInHorizontally { -it / 4 },
                exit = fadeOut() + slideOutHorizontally { -it / 4 },
            ) {
                SearchPanel(
                    vm = vm,
                    width = (prefs.panelWidth + 60).dp,
                    onPick = { hit ->
                        val p = hit.programme
                        if (p != null && p.startMs > System.currentTimeMillis()) {
                            vm.toggleReminder(hit.channel, p)
                        } else {
                            vm.play(hit.channel, vm.groupFor(hit.channel))
                            showSearch = false
                        }
                    },
                )
            }

            if (showSleep) {
                SleepPicker(
                    current = sleepAt,
                    onPick = { minutes ->
                        vm.setSleepTimer(minutes)
                        showSleep = false
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            AnimatedVisibility(
                visible = showSecondPicker,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn() + slideInHorizontally { -it / 4 },
                exit = fadeOut() + slideOutHorizontally { -it / 4 },
            ) {
                SecondChannelPicker(
                    vm = vm,
                    state = secondPicker,
                    width = (prefs.panelWidth + 60).dp,
                    onPick = { content, ch, group ->
                        vm.openSecond(content, ch, group)
                        showSecondPicker = false
                        focusSecond = false
                    },
                )
            }

            trackPicker?.let { type ->
                TrackPicker(
                    title = if (type == C.TRACK_TYPE_AUDIO) LocalStrings.current.audio else LocalStrings.current.subtitles,
                    options = trackOptions(stream.tracks, type, player, allowOff = type == C.TRACK_TYPE_TEXT),
                    onPicked = { trackPicker = null },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            sleepWarning?.let { SleepWarning(it, Modifier.align(Alignment.Center)) }

            if (prefs.netGraph && !showGuide && panel == Panel.None) {
                NetGraph(vm.net, current.name, Modifier.align(Alignment.TopStart))
            }

            AnimatedVisibility(
                visible = netHint,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                val actions = netHintActions(
                    LocalStrings.current, vm, prefs, playback, catchup != null,
                    onDismiss = { netHint = false },
                    onDetails = {
                        settingsAtConnection = true
                        showSettings = true
                    },
                )
                NetHintCard(vm.net, actions, netHintFocus)
            }
            reminder?.let { ReminderCard(it, Modifier.align(Alignment.BottomEnd)) }

            var lastNotice by remember { mutableStateOf<AppRelease?>(null) }
            updateNotice?.let { lastNotice = it }
            val hold by animateFloatAsState(updateHold, tween(if (updateHold == 0f) 250 else 80), label = "updateHold")
            AnimatedVisibility(
                visible = showUpdate,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                lastNotice?.let { UpdateCard(it, update, hold, updateHidesIn) }
            }

            AnimatedVisibility(
                visible = panel != Panel.None,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn() + slideInHorizontally { -it / 4 },
                exit = fadeOut() + slideOutHorizontally { -it / 4 },
            ) {
                SidePanel(
                    mode = if (panel == Panel.None) lastPanel else panel,
                    group = panelGroup,
                    playing = current,
                    vm = vm,
                    onPlay = { ch ->
                        vm.play(ch, panelGroup)
                        panel = Panel.None
                    },
                    onOpenGroups = { panel = Panel.Groups },
                    onSelectGroup = { id ->
                        if (vm.isGroupLocked(id)) {
                            pinError = null
                            pinFor = id
                            panel = Panel.None
                        } else {
                            panelGroup = id
                            panel = Panel.Channels
                        }
                    },
                    onSettings = {
                        panel = Panel.None
                        showSettings = true
                        vm.refreshConfig()
                    },
                    onClose = { panel = Panel.None },
                    playlistMenu = playlistMenu,
                    onPlaylistMenu = { playlistMenu = it },
                    epgChannel = epgPanelChannel,
                    onOpenEpg = { epgPanelChannel = it },
                    onEpgPlay = { ch ->
                        vm.play(ch, panelGroup)
                        epgPanelChannel = null
                        panel = Panel.None
                    },
                    onEpgCatchup = { ch, entry ->
                        vm.play(ch, panelGroup)
                        vm.playCatchup(ch, entry)
                        epgPanelChannel = null
                        panel = Panel.None
                    },
                    onCloseEpg = { epgPanelChannel = null },
                )
            }

            AnimatedVisibility(
                visible = showSettings,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn() + slideInHorizontally { it / 4 },
                exit = fadeOut() + slideOutHorizontally { it / 4 },
            ) {
                SettingsSheet(vm, player, current, startAtEpg = settingsAtEpg, startAtConnection = settingsAtConnection, onClose = {
                    showSettings = false
                    settingsAtEpg = false
                    settingsAtConnection = false
                })
            }

            pinFor?.let { group ->
                PinDialog(
                    title = LocalStrings.current.groupLocked(data.categoryName(group)),
                    error = pinError,
                    onDone = { pin ->
                        if (vm.unlockGroup(group, pin)) {
                            pinFor = null
                            panelGroup = group
                            panel = Panel.Channels
                        } else {
                            pinError = vm.pinRejected(I18n.s.wrongPinRetry)
                        }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private const val HUD_TIMEOUT_MS = 8_000L
/** O aviso de paragens sai sozinho ao fim deste tempo. */
private const val NET_HINT_MS = 30_000L
/** Tamanho do segundo canal em imagem na imagem (16:9). */
private val PIP_WIDTH = 352.dp
private val PIP_HEIGHT = 198.dp
private const val CATCHUP_BAR_MS = 5_000L
private const val SEEK_STEP_MS = 60_000L
private const val SEEK_COMMIT_MS = 900L
/** Pausa do direto: cada ← → (ou ⏪ ⏩) recua/avança isto. */
private const val TIMESHIFT_STEP_MS = 10_000L
private const val NUMBER_COMMIT_MS = 1_500L
/** AFR: quanto se espera pela cadência do canal depois de ele arrancar, antes de usar a de recurso. */
private const val AFR_MEASURE_MS = 3_000L
/** O cartão de versão nova: quanto fica no ecrã e quanto tempo se mantém OK para instalar. */
private const val UPDATE_CARD_SECONDS = 12
private const val UPDATE_HOLD_MS = 1_500L
/** Até aqui é um toque no OK (abre a info, como sempre); a partir daqui conta como "manter". */
private const val UPDATE_TAP_MS = 250L

/** As teclas que mudam de canal (para as marcas do [ZapTrace]). */
private val ZAP_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_UP,
    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_LAST_CHANNEL,
)

private fun isOkKey(code: Int) = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
    code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || code == AndroidKeyEvent.KEYCODE_INFO

/** Algarismo de uma tecla numérica do comando (também as do teclado numérico), ou null. */
private fun digitOf(code: Int): Char? = when (code) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_0)
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}
