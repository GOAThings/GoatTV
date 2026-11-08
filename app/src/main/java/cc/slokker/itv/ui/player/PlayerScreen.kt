@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.ui.glass.GlassChipButton
import cc.slokker.itv.ui.glass.capsuleBar
import cc.slokker.itv.ui.glass.ItvIcons
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
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
import cc.slokker.itv.data.StreamFormat
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.GlassBackdrop
import cc.slokker.itv.ui.glass.LocalGlassBackdrop
import cc.slokker.itv.ui.glass.blurInto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameMillis
import androidx.compose.animation.core.tween
import cc.slokker.itv.data.AppRelease
import cc.slokker.itv.ui.UpdateState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.slokker.itv.ui.player.engine.FREEZE_HEIGHT
import cc.slokker.itv.ui.player.engine.FREEZE_WIDTH
import cc.slokker.itv.ui.player.engine.aspectOrNull
import cc.slokker.itv.ui.player.engine.resizeModeFor
import cc.slokker.itv.ui.player.engine.PLAYER_LOGS
import cc.slokker.itv.ui.player.engine.cannotDecode
import cc.slokker.itv.ui.player.engine.selectSurroundTrack
import cc.slokker.itv.ui.player.engine.videoFormat
import cc.slokker.itv.ui.player.engine.titleCard
import cc.slokker.itv.ui.player.engine.status
import cc.slokker.itv.ui.player.engine.rememberChannelPlayback
import cc.slokker.itv.ui.player.engine.PlaybackRequest
import cc.slokker.itv.ui.player.engine.PlaybackPolicy
import cc.slokker.itv.ui.player.engine.AppPlaybackHost

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
    var reminder by remember { mutableStateOf<Reminder?>(null) }
    /** Segundos até o temporizador parar a app (null = sem aviso). */
    var sleepWarning by remember { mutableStateOf<Int?>(null) }
    // Catch-up: posição, recuo/avanço por confirmar, pausa.
    var catchupPosition by remember { mutableLongStateOf(0L) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var paused by remember { mutableStateOf(false) }
    var catchupBarToken by remember { mutableIntStateOf(0) }
    var catchupBarVisible by remember { mutableStateOf(false) }
    /** Pausa do direto: o salto por confirmar, em ms atrás do direto (← → somam-se, como no catch-up). */
    var tsPending by remember { mutableStateOf<Long?>(null) }
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

    // Versão nova (ver UpdateCard): aparece com o vídeo livre, ou enquanto descarrega/instala.
    val updateNotice by vm.updateNotice.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updateBusy = update is UpdateState.Downloading || update is UpdateState.Installing
    val showUpdate = updateNotice != null && (update is UpdateState.Available || updateBusy) && (
        updateBusy || panel == Panel.None && !showSettings && !overlayOpen && pinFor == null && reminder == null &&
            sleepWarning == null && catchup == null && !(timeshift.available && (paused || !timeshift.atLive)) && !(infoVisible && infoFull)
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

    fun showCatchupBar() {
        catchupBarVisible = true
        catchupBarToken++
    }

    /** A pausa do direto vale para este canal (o principal, em direto e sem o segundo canal). */
    fun tsActive() = catchup == null && second == null && timeshift.available

    /** Em pausa, atrás do direto, ou a escolher para onde ir: as teclas são as do catch-up. */
    fun tsShifted() = tsActive() && (paused || !timeshift.atLive || tsPending != null)

    fun tsToggle() {
        if (paused) {
            paused = false
            timeshift.resume(player)
        } else {
            paused = true
            player.pause()
        }
        infoVisible = false
        showCatchupBar()
    }

    /** Recua (negativo) ou avança [deltaMs]; o salto só se faz quando se pára de carregar. */
    fun tsSeek(deltaMs: Long) {
        val from = tsPending ?: timeshift.behindMs
        tsPending = (from - deltaMs).coerceIn(0L, timeshift.windowMs)
        infoVisible = false
        showCatchupBar()
    }

    fun tsGoLive() {
        paused = false
        tsPending = null
        timeshift.goLive(player)
        catchupBarVisible = false
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
    LaunchedEffect(current.key, data.playlist, player, catchup) {
        val cu = catchup
        catchupPosition = cu?.positionMs ?: 0L
        pendingSeek = null
        tsPending = null
        paused = false
        playback.tune(PlaybackRequest.of(data, current, cu, streamFormat, vm::stalkerCandidates))
        when {
            cu != null -> {
                infoVisible = false
                showCatchupBar()
            }
            prefs.infoSeconds > 0 -> showInfo()
            else -> infoVisible = false
        }
    }

    // Posição no catch-up (para a barra).
    LaunchedEffect(catchup, player) {
        while (catchup != null) {
            if (playback.ready) playback.programmePositionMs()?.let { catchupPosition = it }
            delay(500)
        }
    }

    // A barra do catch-up esconde-se uns segundos depois da última tecla (fica enquanto está em pausa).
    LaunchedEffect(catchupBarToken, paused) {
        if (paused) return@LaunchedEffect
        delay(CATCHUP_BAR_MS)
        catchupBarVisible = false
    }

    // ← → no catch-up somam minutos; o pedido ao servidor só se faz quando se pára de carregar.
    LaunchedEffect(pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        delay(SEEK_COMMIT_MS)
        vm.seekCatchup(target)
    }

    // Na pausa do direto o mesmo, mas o salto é no disco (recomeça o player nesse ponto do anel).
    LaunchedEffect(tsPending) {
        val target = tsPending ?: return@LaunchedEffect
        delay(SEEK_COMMIT_MS)
        paused = false
        timeshift.seekBehind(player, target)
        tsPending = null
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
                // AFR: a TV acompanha a cadência do canal.
                if (latestPrefs.afr) player.videoFormat()?.let { f -> frameRateMatcher?.match(f.frameRate, HdrType.of(f)) }
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
            paused = false
            tsPending = null
            frameRateMatcher?.restore()
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
    LaunchedEffect(prefs.afr) { if (!prefs.afr) frameRateMatcher?.restore() }

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
    LaunchedEffect(glassVisible, videoView) {
        val view = videoView ?: return@LaunchedEffect
        val scratch = createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT)
        // Dois buffers alternados: escreve-se sempre no que não está a ser mostrado. Sem alocações por frame.
        val buffers = Array(2) { createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT) }
        val images = buffers.map { it.asImageBitmap() }
        val workA = IntArray(BACKDROP_WIDTH * BACKDROP_HEIGHT)
        val workB = IntArray(BACKDROP_WIDTH * BACKDROP_HEIGHT)
        var next = 0
        while (glassVisible) {
            val started = SystemClock.uptimeMillis()
            // Com o vidro quase opaco o fundo nem se vê, por isso não vale a pena renová-lo.
            val opaque = prefs.glassOpacity >= 95 && backdrop.image != null
            // Enquanto se navega com o comando, o fundo fica parado: copiar e desfocar o vídeo a cada frame
            // competia com o scroll pelo CPU e pela GPU. Desfocado, nem se nota que esperou pelo fim das teclas.
            val navigating = SystemClock.uptimeMillis() - lastInput[0] < BACKDROP_PAUSE_MS
            if (opaque || navigating) {
                delay(50)
                continue
            }
            view.capture(BACKDROP_WIDTH, BACKDROP_HEIGHT, into = scratch)?.let { frame ->
                val target = next
                withContext(Dispatchers.Default) { blurInto(frame, buffers[target], workA, workB) }
                backdrop.image = images[target]
                next = 1 - target
            }
            // O vidro renova-se ao ritmo do vídeo: à cadência até 30 fps, a meia cadência acima (50 → 25),
            // para ficar alinhado com os frames sem pesar na box.
            val videoFps = player.videoFormat()?.frameRate?.takeIf { it > 0f } ?: DEFAULT_BACKDROP_FPS
            val fps = if (videoFps > 30f) videoFps / 2 else videoFps
            val frameMs = (1000f / fps).toLong().coerceIn(33L, 66L)
            delay((frameMs - (SystemClock.uptimeMillis() - started)).coerceAtLeast(4))
        }
    }

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
    val hudOpen = infoVisible && infoFull && panel == Panel.None && !showSettings && !overlayOpen && catchup == null
    LaunchedEffect(hudOpen) { if (hudOpen) focusWithRetry(hudFocus) }

    // Teclas numéricas: o número fica à espera de mais algarismos e muda de canal ao fim de um bocado.
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(NUMBER_COMMIT_MS)
        val n = numberEntry.toIntOrNull()
        numberEntry = ""
        if (n != null && !vm.playNumber(n)) vm.showToast(I18n.s.noChannelNumber(n))
    }

    // Temporizador: aviso no último minuto e, no fim, pára e fecha a app.
    LaunchedEffect(sleepAt) {
        val at = sleepAt
        if (at == null) {
            sleepWarning = null
            return@LaunchedEffect
        }
        while (true) {
            val left = at - System.currentTimeMillis()
            if (left <= 0) {
                player.stop()
                vm.setSleepTimer(null, announce = false)
                (context as? Activity)?.finish()
                break
            }
            sleepWarning = if (left <= SLEEP_WARNING_MS) ((left + 999) / 1000).toInt() else null
            delay(if (left <= SLEEP_WARNING_MS + 1_000) 1_000 else minOf(left - SLEEP_WARNING_MS, 30_000))
        }
    }

    // Lembretes: vê de 15 em 15 s se algum programa vai começar; o aviso some-se sozinho.
    LaunchedEffect(Unit) {
        while (true) {
            if (reminder == null) vm.dueReminder(System.currentTimeMillis())?.let { reminder = it }
            delay(15_000)
        }
    }
    LaunchedEffect(reminder) {
        val r = reminder ?: return@LaunchedEffect
        delay(REMINDER_CARD_MS)
        if (reminder == r) {
            vm.consumeReminder(r, watch = false)
            reminder = null
        }
    }

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
        if (panel == Panel.None && !showSettings && pinFor == null && !overlayOpen && !hudOpen) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { rootFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    /**
     * Voltar recua um nível: avisos → PIN → janelas (pesquisa, guia…) → grupos → canais → informação →
     * catch-up (volta ao direto) → guia ou sair, conforme as definições.
     */
    fun goBack() {
        val now = SystemClock.uptimeMillis()
        when {
            reminder != null -> {
                reminder?.let { vm.consumeReminder(it, watch = false) }
                reminder = null
            }
            numberEntry.isNotEmpty() -> numberEntry = ""
            netHint -> netHint = false
            pinFor != null -> pinFor = null
            playlistMenu -> playlistMenu = false
            showSleep -> showSleep = false
            showSecondPicker -> if (!secondPicker.back()) showSecondPicker = false
            trackPicker != null -> trackPicker = null
            showSearch -> showSearch = false
            showGuide -> {
                showGuide = false
                // Fechar o guia que o Voltar abriu conta como o 1.º Voltar para sair: o seguinte sai,
                // em vez de abrir o guia outra vez (senão Voltar ficava a abrir e fechar o guia para sempre).
                if (guideFromBack) {
                    guideFromBack = false
                    lastBack = now
                    if (prefs.exitConfirm) vm.showToast(I18n.s.pressBackToExit)
                }
            }
            epgPanelChannel != null -> epgPanelChannel = null
            // Voltar fecha o painel lateral (canais ou grupos) e volta ao vídeo. Antes os grupos voltavam
            // aos canais e os canais aos grupos: vindo de um grupo, o painel nunca chegava a fechar.
            panel != Panel.None -> panel = Panel.None
            infoVisible && playback.failure == null -> infoVisible = false
            catchup != null -> vm.exitCatchup()
            tsShifted() -> tsGoLive()
            second != null -> vm.closeSecond()
            prefs.backAction == Prefs.BackAction.GUIDE && now - lastBack >= 2_500 -> {
                showGuide = true
                guideFromBack = true
            }
            !prefs.exitConfirm || now - lastBack < 2_500 -> (context as? Activity)?.finish()
            else -> {
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
                        if (panel != Panel.None || showSettings || pinFor != null || overlayOpen) return@onKeyEvent false
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
                    if (e.type != KeyEventType.KeyDown || panel != Panel.None || showSettings || pinFor != null || overlayOpen) return@onKeyEvent false
                    val code = e.key.nativeKeyCode
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
                                tsGoLive(); return@onKeyEvent true
                            }
                        }
                    }
                    // Catch-up: ← → recuam/avançam 1 minuto, OK pausa.
                    if (catchup != null) {
                        val cu = catchup!!
                        when (code) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                val back = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND
                                val from = pendingSeek ?: catchupPosition
                                pendingSeek = (from + if (back) -SEEK_STEP_MS else SEEK_STEP_MS).coerceIn(0L, cu.durationMs)
                                showCatchupBar()
                                return@onKeyEvent true
                            }
                            // No catch-up as setas não mudam de canal (é um programa gravado): só mostram a barra.
                            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                showCatchupBar()
                                return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                if (playback.failure != null) {
                                    playback.retry()
                                } else {
                                    paused = !paused
                                    if (paused) player.pause() else player.play()
                                }
                                showCatchupBar()
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
                                val already = (code == AndroidKeyEvent.KEYCODE_MEDIA_PLAY && !paused) || (code == AndroidKeyEvent.KEYCODE_MEDIA_PAUSE && paused)
                                if (already) showCatchupBar() else tsToggle()
                                return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                                tsSeek(-TIMESHIFT_STEP_MS); return@onKeyEvent true
                            }
                            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                if (shifted) tsSeek(TIMESHIFT_STEP_MS)
                                return@onKeyEvent true
                            }
                        }
                        if (shifted) {
                            when (code) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    tsSeek(if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -TIMESHIFT_STEP_MS else TIMESHIFT_STEP_MS)
                                    return@onKeyEvent true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                    showCatchupBar(); return@onKeyEvent true
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

            val infoAllowed = infoVisible && panel == Panel.None && !showSettings && !overlayOpen && catchup == null
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
                val s = LocalStrings.current
                val favorites by vm.favorites.collectAsStateWithLifecycle()
                val isFavorite = current.key in favorites[data.playlist.id].orEmpty()
                val container = playback.currentCandidate?.format
                NowPlayingBar(current, data, vm, badges = qualityBadges(stream, container)) { live ->
                    // ← → andam entre os botões (sem abrir a lista nem saltar de canal).
                    val focusManager = LocalFocusManager.current
                    Row(
                        Modifier
                            .onKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (e.key.nativeKeyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { focusManager.moveFocus(FocusDirection.Left); true }
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { focusManager.moveFocus(FocusDirection.Right); true }
                                    else -> false
                                }
                            }
                            .capsuleBar()
                            .horizontalScroll(rememberScrollState())
                            // Margem para o botão com foco (cresce um pouco) não ficar cortado nas pontas.
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // Como num player: faixas e imagem primeiro, depois o resto.
                        GlassChipButton(ItvIcons.Audio, s.audio, { trackPicker = C.TRACK_TYPE_AUDIO }, Modifier.focusRequester(hudFocus))
                        GlassChipButton(ItvIcons.Subtitles, s.subtitles, { trackPicker = C.TRACK_TYPE_TEXT })
                        GlassChipButton(
                            ItvIcons.Aspect,
                            when (prefs.aspect) {
                                Prefs.Aspect.ZOOM -> s.aspectZoom
                                Prefs.Aspect.FILL -> s.aspectFill
                                else -> s.aspectFit
                            },
                            {
                                val next = when (prefs.aspect) {
                                    Prefs.Aspect.FIT -> Prefs.Aspect.ZOOM
                                    Prefs.Aspect.ZOOM -> Prefs.Aspect.FILL
                                    else -> Prefs.Aspect.FIT
                                }
                                vm.setPref(Prefs.Key.ASPECT, next)
                            },
                        )
                        // Pausar o direto (também com Play/Pausa no comando, para quem o tem).
                        if (tsActive()) GlassChipButton(ItvIcons.Pause, s.pauseLive, { tsToggle() })
                        if (second == null) {
                            GlassChipButton(ItvIcons.Pip, s.secondChannel, {
                                infoVisible = false
                                secondPicker.reset()
                                showSecondPicker = true
                            })
                        } else {
                            GlassChipButton(ItvIcons.SwapPlaylist, s.swapChannels, { swapped = !swapped })
                            GlassChipButton(
                                if (dualLayout == DualLayout.PIP) ItvIcons.Split else ItvIcons.Pip,
                                if (dualLayout == DualLayout.PIP) s.splitScreen else s.pictureInPicture,
                                { dualLayout = if (dualLayout == DualLayout.PIP) DualLayout.SPLIT else DualLayout.PIP },
                            )
                            GlassChipButton(ItvIcons.Pip, s.changeSecond, {
                                infoVisible = false
                                secondPicker.reset()
                                showSecondPicker = true
                            })
                            GlassChipButton(ItvIcons.Close, s.closeSecond, { vm.closeSecond() })
                        }
                        GlassChipButton(ItvIcons.Search, s.search, { infoVisible = false; showSearch = true })
                        GlassChipButton(ItvIcons.Guide, s.tvGuide, { infoVisible = false; guideFromBack = false; showGuide = true })
                        // Trocar o guia deste canal por outro (só com conta; sem conta explica como ligar).
                        GlassChipButton(ItvIcons.Guide, s.epgGuideChannel, {
                            if (vm.epgPickerAvailable) {
                                infoVisible = false
                                settingsAtEpg = true
                                showSettings = true
                            } else {
                                vm.showToast(s.epgNeedsAccount)
                            }
                        })
                        GlassChipButton(
                            if (isFavorite) ItvIcons.Star else ItvIcons.StarOutline,
                            if (isFavorite) s.removeFavorite else s.addFavorite,
                            { vm.toggleFavorite(current) },
                        )
                        if (live != null && vm.canCatchup(current, live)) {
                            GlassChipButton(ItvIcons.Replay, s.startOver, { vm.playCatchup(current, live) })
                        }
                        GlassChipButton(
                            ItvIcons.Sleep,
                            sleepAt?.let { s.sleepRemaining(((it - System.currentTimeMillis()) / 60_000 + 1).toInt()) } ?: s.sleepTimer,
                            { showSleep = true },
                        )
                    }
                }
            }

            catchup?.let { cu ->
                if (!showGuide) {
                    AnimatedVisibility(
                        visible = catchupBarVisible || paused || pendingSeek != null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn() + slideInVertically { it / 3 },
                        exit = fadeOut() + slideOutVertically { it / 3 },
                    ) {
                        CatchupBar(cu, catchupPosition, pendingSeek, paused)
                    }
                    if (playback.ready || playback.failure != null) CatchupPill(paused, Modifier.align(Alignment.TopEnd))
                }
            }

            if (tsActive() && !showGuide) {
                val shifted = tsShifted()
                AnimatedVisibility(
                    visible = (shifted && catchupBarVisible) || paused || tsPending != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                ) {
                    TimeshiftBar(current.name, timeshift.behindMs, timeshift.windowMs, tsPending, paused, timeshift.atLive)
                }
                // Atrás do direto, a cápsula no canto diz quanto (como a do catch-up).
                if (shifted && playback.ready && playback.failure == null) {
                    CatchupPill(paused, Modifier.align(Alignment.TopEnd), label = behindLabel(tsPending ?: timeshift.behindMs))
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
                val s = LocalStrings.current
                val actions = buildList {
                    // Buffer maior (o player recomeça com ele).
                    val bigger = when (prefs.buffer) {
                        Prefs.Buffer.LIVE -> Prefs.Buffer.SMALL
                        Prefs.Buffer.SMALL -> Prefs.Buffer.NORMAL
                        Prefs.Buffer.NORMAL -> Prefs.Buffer.LARGE
                        else -> null
                    }
                    if (bigger != null) add(NetHintAction(ItvIcons.Buffer, s.netBiggerBuffer) { netHint = false; vm.setPref(Prefs.Key.BUFFER, bigger) })
                    // O outro formato (TS ⇄ HLS), quando é mesmo outro endereço (Xtream, ou .ts ⇄ .m3u8); num link sem
                    // extensão o "outro" é o mesmo URL, que já falhou nesse formato.
                    val candidates = playback.candidates
                    val playingNow = playback.currentCandidate
                    val other = candidates.indices.firstOrNull { i ->
                        val c = candidates[i]
                        i != playback.attempt && c.format != playingNow?.format && c.url != playingNow?.url
                    }
                    if (other != null && catchup == null) {
                        val format = candidates[other].format
                        add(NetHintAction(ItvIcons.SwapPlaylist, s.netTryFormat(if (format == StreamFormat.HLS) "HLS" else "TS")) {
                            netHint = false
                            vm.setStreamFormat(format)
                            playback.switchTo(other)
                        })
                    }
                    add(NetHintAction(ItvIcons.Info, s.netDetails) { netHint = false; settingsAtConnection = true; showSettings = true })
                    add(NetHintAction(ItvIcons.Close, s.netDontShow) { netHint = false; vm.setPref(Prefs.Key.NET_WARNINGS, false) })
                }
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
private const val TIMESHIFT_TICK_MS = 500L
private const val NUMBER_COMMIT_MS = 1_500L
private const val SLEEP_WARNING_MS = 60_000L
private const val REMINDER_CARD_MS = 45_000L
/** O cartão de versão nova: quanto fica no ecrã e quanto tempo se mantém OK para instalar. */
private const val UPDATE_CARD_SECONDS = 12
private const val UPDATE_HOLD_MS = 1_500L
/** Até aqui é um toque no OK (abre a info, como sempre); a partir daqui conta como "manter". */
private const val UPDATE_TAP_MS = 250L

private fun isOkKey(code: Int) = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
    code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || code == AndroidKeyEvent.KEYCODE_INFO

/** Algarismo de uma tecla numérica do comando (também as do teclado numérico), ou null. */
private fun digitOf(code: Int): Char? = when (code) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_0)
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}
private const val BACKDROP_WIDTH = 128
private const val BACKDROP_HEIGHT = 72
private const val DEFAULT_BACKDROP_FPS = 30f

/** Quanto tempo depois da última tecla o fundo do vidro volta a acompanhar o vídeo. */
private const val BACKDROP_PAUSE_MS = 400L
