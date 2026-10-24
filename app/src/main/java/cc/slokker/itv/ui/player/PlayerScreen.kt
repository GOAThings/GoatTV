@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import cc.slokker.itv.data.catchupCandidates
import cc.slokker.itv.ui.common.isAccountBusy
import cc.slokker.itv.ui.glass.GlassChipButton
import cc.slokker.itv.ui.glass.capsuleBar
import cc.slokker.itv.ui.glass.ItvIcons
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.explainPlaybackError
import cc.slokker.itv.ui.i18n.LocalStrings
import android.app.Activity
import android.content.Context
import android.os.Build
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
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.common.Format
import android.media.MediaFormat
import android.os.Handler
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.IPTV_USER_AGENT
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.streamCandidates
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.GlassBackdrop
import cc.slokker.itv.ui.glass.LocalGlassBackdrop
import cc.slokker.itv.ui.glass.blurInto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

/** Painel lateral: fechado, canais do grupo, ou grupos (no mesmo sítio). */
internal enum class Panel { None, Channels, Groups }

/**
 * Ecrã principal: o vídeo em ecrã inteiro com painéis de vidro por cima.
 *  - ←: canais do grupo, encostados à esquerda; ← outra vez: grupos no mesmo painel.
 *  - ↑/↓, CH+/CH−: mudar de canal; OK: informação do programa; Menu: favorito.
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
    // Canal em que o ExoPlayer não consegue descodificar o áudio ou o vídeo (ex.: áudio MP2): toca com o VLC.
    // Só nesse canal; ao mudar de canal volta ao player das definições.
    var vlcForChannel by remember { mutableStateOf<String?>(null) }
    val engine = if (vlcForChannel == current.key) Prefs.Engine.VLC else prefs.engine
    // Player, buffer e descodificador só se mudam criando um player novo.
    val player = remember(engine, prefs.buffer, prefs.decoder, prefs.audioDecoder, prefs.audioPassthrough, prefs.hdr) {
        buildPlayer(context, prefs.copy(engine = engine))
    }
    val latestKey by rememberUpdatedState(current.key)

    fun playWithVlc() {
        if (player is VlcPlayer || vlcForChannel == latestKey) return
        vlcForChannel = latestKey
        vm.showToast(I18n.s.usingVlc)
    }
    val frameRateMatcher = remember { (context as? Activity)?.let { FrameRateMatcher(it) } }
    val session = remember { PlaybackSession() }
    val backdrop = remember { GlassBackdrop() }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var freezeFrame by remember { mutableStateOf<ImageBitmap?>(null) }

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
    // Catch-up: início realmente pedido ao servidor, posição, recuo/avanço por confirmar, pausa.
    var catchupBase by remember { mutableLongStateOf(0L) }
    var catchupPosition by remember { mutableLongStateOf(0L) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var paused by remember { mutableStateOf(false) }
    var catchupBarToken by remember { mutableIntStateOf(0) }
    var catchupBarVisible by remember { mutableStateOf(false) }
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
    val overlayOpen = showGuide || showSearch || showSleep || trackPicker != null || showSecondPicker

    val latestVm by rememberUpdatedState(vm)
    val latestPrefs by rememberUpdatedState(prefs)
    val latestData by rememberUpdatedState(data)
    val latestCatchup by rememberUpdatedState(catchup)

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

    /** Posição atual no programa em catch-up (ms desde o início dele). */
    fun catchupNow(): Long = catchupBase - (latestCatchup?.startMs ?: 0L) + player.currentPosition

    fun openChannels() {
        panelGroup = selectedGroup
        panel = Panel.Channels
    }

    fun start(candidateIndex: Int) {
        if (vm.testMode) return
        val candidate = session.candidates.getOrNull(candidateIndex) ?: return
        session.attempt = candidateIndex
        session.error = null
        session.errorStatus = null
        session.ready = false
        player.loadStream(
            candidate, session.channel,
            nearLive = prefs.buffer == Prefs.Buffer.LIVE && !session.catchup,
            userAgent = prefs.userAgent.ifBlank { IPTV_USER_AGENT },
        )
        player.prepare()
        player.play()
    }

    // Novo canal: congela a última imagem e recomeça pelas tentativas na ordem do formato preferido.
    // Chaves estáveis: a lista atualizada por trás (mesmo servidor e canal) não reinicia o stream.
    // Em catch-up o mesmo: cada recuo/avanço é um pedido novo ao servidor a partir dessa posição.
    LaunchedEffect(current.key, data.playlist, player, catchup) {
        if (prefs.freezeFrame && session.channel != null && session.ready) {
            freezeFrame = videoView?.capture(FREEZE_WIDTH, FREEZE_HEIGHT)?.asImageBitmap()
        }
        session.channel = current
        val cu = catchup
        val archive = cu?.let { catchupCandidates(data.playlist, it, data.account?.timezone) }
        session.catchup = cu != null
        session.candidates = archive?.candidates ?: streamCandidates(
            data.playlist, current, streamFormat, data.account?.allowedFormats.orEmpty(),
        )
        catchupBase = archive?.startMs ?: 0L
        catchupPosition = cu?.positionMs ?: 0L
        pendingSeek = null
        paused = false
        session.retries = 0
        session.wasPlaying = false
        session.audioChosen = false
        session.retryJob?.cancel()
        start(0)
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
            if (session.ready) catchupPosition = catchupNow()
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

    // Cada player na sua imagem. Ao trocar, primeiro larga-se a imagem antiga (dois players não podem partilhar uma).
    LaunchedEffect(player, secondPlayback, mainView, secondView) {
        val p2 = secondPlayback?.player
        player.clearVideoSurface()
        p2?.clearVideoSurface()
        mainView?.let { v ->
            player.setVideoSurfaceView(v.surfaceView)
            player.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { v.setAspectRatio(it.width * it.pixelWidthHeightRatio / it.height) }
        }
        if (p2 != null) {
            secondView?.let { v ->
                p2.setVideoSurfaceView(v.surfaceView)
                p2.videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { v.setAspectRatio(it.width * it.pixelWidthHeightRatio / it.height) }
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
        videoView?.setResizeMode(
            when (prefs.aspect) {
                Prefs.Aspect.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                Prefs.Aspect.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
        )
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                session.buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    when {
                        // O programa em catch-up acabou: volta ao direto.
                        session.catchup -> latestVm.exitCatchup()
                        // Um canal em direto não acaba: "acabou" quer dizer que o servidor fechou a ligação
                        // (comum em streams .ts). Sem isto o vídeo parava em silêncio e a TV entrava em
                        // proteção de ecrã. Volta a ligar como depois de um erro.
                        session.wasPlaying -> {
                            session.retryJob?.cancel()
                            session.retryJob = scope.launch {
                                delay(latestPrefs.reconnectSeconds.coerceAtLeast(1) * 1_000L)
                                start(session.attempt)
                            }
                        }
                    }
                }
                if (state == Player.STATE_READY && !session.ready) {
                    session.ready = true
                    session.wasPlaying = true
                    session.retries = 0
                    val candidate = session.candidates.getOrNull(session.attempt)
                    if (session.attempt > 0 && candidate != null) latestVm.onFormatWorked(candidate.format)
                }
            }

            override fun onRenderedFirstFrame() {
                freezeFrame = null
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    latestMainView?.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (player !is VlcPlayer && tracks.cannotDecode()) {
                    playWithVlc()
                    return
                }
                // AFR: a TV acompanha a cadência do canal.
                if (latestPrefs.afr) player.videoFormat()?.let { f -> frameRateMatcher?.match(f.frameRate, HdrType.of(f)) }
                // Faixa surround por defeito (uma vez por canal, para não desfazer uma escolha manual).
                if (latestPrefs.surroundDefault && !session.audioChosen) {
                    session.audioChosen = selectSurroundTrack(player, tracks)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                session.playing = isPlaying
            }

            override fun onCues(cueGroup: CueGroup) {
                latestMainView?.subtitleView?.setCues(cueGroup.cues)
            }

            override fun onPlayerError(error: PlaybackException) {
                when {
                    // Muito comum em live: voltar ao ponto em direto.
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    // Nem a box nem o FFmpeg descodificam o canal: o VLC talvez sim.
                    player !is VlcPlayer && error.errorCode in DECODER_ERRORS -> playWithVlc()
                    // Ainda não começou: tenta o outro formato (ts ⇄ m3u8) uma vez.
                    !session.ready && session.attempt + 1 < session.candidates.size -> start(session.attempt + 1)
                    // Catch-up caiu a meio: pede de novo a partir de onde ia.
                    session.wasPlaying && session.catchup -> {
                        val position = catchupNow()
                        session.retryJob = scope.launch {
                            delay(latestPrefs.reconnectSeconds.coerceAtLeast(1) * 1_000L)
                            latestVm.seekCatchup(position)
                        }
                    }
                    // Caiu a meio: volta a ligar, de N em N segundos, enquanto se estiver no canal.
                    session.wasPlaying && latestPrefs.reconnectSeconds > 0 -> {
                        session.retryJob = scope.launch {
                            delay(latestPrefs.reconnectSeconds * 1_000L)
                            start(session.attempt)
                        }
                    }
                    else -> {
                        freezeFrame = null
                        session.error = ErrorInfo(I18n.s.cantOpenChannel.removeSuffix("."))
                        session.errorStatus = I18n.s.analysingError
                        showInfo()
                        // Não arrancou: mostra o erro e tenta mais umas vezes (sem martelar o servidor).
                        val retry = latestPrefs.reconnectSeconds > 0 && session.retries < MAX_START_RETRIES
                        if (retry) session.retries++
                        session.retryJob = scope.launch {
                            // Pergunta ao servidor se a conta está mesmo ocupada antes de o dizer.
                            val connections = if (error.errorCode in 2000..2999) latestVm.xtreamConnections() else null
                            val format = session.candidates.getOrNull(session.attempt)?.format
                            session.error = if (session.catchup && !isAccountBusy(latestData, connections)) {
                                I18n.s.errCatchup.copy(detail = I18n.s.technical(error.errorCodeName))
                            } else {
                                explainPlaybackError(I18n.s, error, latestData, connections, latestPrefs, format)
                            }
                            session.errorStatus = if (retry) {
                                I18n.s.retryIn(session.retries, MAX_START_RETRIES, latestPrefs.reconnectSeconds)
                            } else {
                                null
                            }
                            if (retry) {
                                delay(latestPrefs.reconnectSeconds * 1_000L)
                                start(0)
                            }
                        }
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            session.retryJob?.cancel()
            player.release()
        }
    }

    // Box em segundo plano / ecrã desligado: parar o stream (e libertar a ligação Xtream).
    LifecycleStartEffect(player) {
        if (player.playbackState == Player.STATE_IDLE && session.candidates.isNotEmpty()) start(session.attempt)
        onStopOrDispose {
            // O segundo canal fecha-se ao sair da app (liberta a ligação do outro fornecedor).
            vm.closeSecond()
            player.stop()
            frameRateMatcher?.restore()
            vm.flushLastChannel()
        }
    }

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
        panel != Panel.None || showSettings || infoVisible || session.error != null || !session.ready ||
            showSearch || showSleep || trackPicker != null || showSecondPicker || catchup != null || reminder != null || sleepWarning != null || numberEntry.isNotEmpty()
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
            if (opaque) {
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
    LaunchedEffect(infoToken, session.ready, session.error, infoFull) {
        if (session.ready && session.error == null) {
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
            infoVisible && session.error == null -> infoVisible = false
            catchup != null -> vm.exitCatchup()
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
                            if (session.error != null) {
                                session.retries = 0
                                start(0)
                            }
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
                                if (session.error != null) {
                                    session.retries = 0
                                    start(0)
                                } else {
                                    paused = !paused
                                    if (paused) player.pause() else player.play()
                                }
                                showCatchupBar()
                                return@onKeyEvent true
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
            // Guia de TV: desenha-se antes do vídeo, que encolhe para o canto (fica à vista por cima do guia).
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

            val split = second != null && dualLayout == DualLayout.SPLIT && !showGuide
            val bigModifier = when {
                showGuide -> Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = GUIDE_PREVIEW_TOP, end = GUIDE_PREVIEW_END)
                    .size(GUIDE_PREVIEW_WIDTH, GUIDE_PREVIEW_HEIGHT)
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
                    update = { it.keepScreenOn = session.playing || session.buffering },
                    modifier = Modifier.fillMaxSize(),
                )

                // Última imagem do canal anterior, até o novo mostrar o primeiro frame (sem ecrã preto).
                freezeFrame?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
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

            if (!session.ready && session.error == null && !showGuide && numberEntry.isEmpty()) {
                // Com o segundo canal no canto direito, o aviso passa para a esquerda.
                TuningPill(
                    if (session.wasPlaying) LocalStrings.current.reconnecting else LocalStrings.current.tuning,
                    Modifier.align(if (second != null) Alignment.TopStart else Alignment.TopEnd),
                )
            }

            if (!showGuide) session.error?.let { ErrorCard(it, session.errorStatus, Modifier.align(Alignment.Center)) }

            val infoAllowed = infoVisible && panel == Panel.None && !showSettings && !overlayOpen && catchup == null
            AnimatedVisibility(
                visible = infoAllowed && !infoFull,
                modifier = Modifier.align(Alignment.BottomStart),
                enter = fadeIn() + slideInHorizontally { -it / 8 },
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
                val container = session.candidates.getOrNull(session.attempt)?.format
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
                    if (session.ready || session.error != null) CatchupPill(paused, Modifier.align(Alignment.TopEnd))
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
            reminder?.let { ReminderCard(it, Modifier.align(Alignment.BottomEnd)) }

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
                SettingsSheet(vm, player, current, startAtEpg = settingsAtEpg, onClose = {
                    showSettings = false
                    settingsAtEpg = false
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
                            pinError = I18n.s.wrongPinRetry
                        }
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private const val FREEZE_WIDTH = 1280
private const val HUD_TIMEOUT_MS = 8_000L
/** Tamanho do segundo canal em imagem na imagem (16:9). */
private val PIP_WIDTH = 352.dp
private val PIP_HEIGHT = 198.dp
private const val CATCHUP_BAR_MS = 5_000L
private const val SEEK_STEP_MS = 60_000L
private const val SEEK_COMMIT_MS = 900L
private const val NUMBER_COMMIT_MS = 1_500L
private const val SLEEP_WARNING_MS = 60_000L
private const val REMINDER_CARD_MS = 45_000L

private fun isOkKey(code: Int) = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER ||
    code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || code == AndroidKeyEvent.KEYCODE_INFO

/** Algarismo de uma tecla numérica do comando (também as do teclado numérico), ou null. */
private fun digitOf(code: Int): Char? = when (code) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_0)
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}
private const val MAX_START_RETRIES = 3
private const val NEAR_LIVE_OFFSET_MS = 4_000L
private const val FREEZE_HEIGHT = 720
private const val BACKDROP_WIDTH = 128
private const val BACKDROP_HEIGHT = 72
private const val DEFAULT_BACKDROP_FPS = 30f

/** Estado da reprodução do canal atual (tentativas, erro, etc.). */
private class PlaybackSession {
    var channel: Channel? = null
    var candidates: List<StreamCandidate> = emptyList()
    var attempt = 0
    var retries = 0
    var retryJob: Job? = null

    /** Já se escolheu a faixa de áudio deste canal (surround por defeito). */
    var audioChosen = false

    /** Já tocou neste canal (uma falha agora é uma quebra, não um canal em baixo). */
    var wasPlaying = false
    var ready by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var error by mutableStateOf<ErrorInfo?>(null)

    /** A tocar um programa em catch-up (não em direto). */
    var catchup = false

    /** Linha de estado no cartão de erro (a analisar / próxima tentativa). */
    var errorStatus by mutableStateOf<String?>(null)
}

/** O player das definições: o ExoPlayer, ou o [VlcPlayer] (Definições → Reprodução → Player). */
internal fun buildPlayer(context: Context, prefs: Prefs): Player {
    val softwareVideo = isEmulator || prefs.decoder == Prefs.Decoder.SOFTWARE
    if (prefs.engine == Prefs.Engine.VLC) {
        val caching = when (prefs.buffer) {
            Prefs.Buffer.LIVE -> 1_000
            Prefs.Buffer.SMALL -> 1_500
            Prefs.Buffer.LARGE -> 8_000
            else -> 3_000
        }
        // Se o VLC não arrancar (biblioteca nativa, box sem suporte…), fica o ExoPlayer em vez de fechar a app.
        try {
            return VlcPlayer(context, passthrough = prefs.audioPassthrough, softwareVideo = softwareVideo, networkCachingMs = caching).apply {
                playWhenReady = true
            }
        } catch (e: Throwable) {
            Log.w("itv", "O VLC não arrancou; fica o ExoPlayer", e)
        }
    }
    val softwareAudio = prefs.audioDecoder == Prefs.Decoder.SOFTWARE
    val renderers = ItvRenderersFactory(context, passthrough = prefs.audioPassthrough, hdr = prefs.hdr, ffmpegFirst = softwareAudio)
        .setEnableDecoderFallback(true)
    if (softwareVideo || softwareAudio) renderers.setMediaCodecSelector(decoderSelector(softwareVideo, softwareAudio))
    // Pequeno: arranca depressa; grande: aguenta melhor quebras de rede.
    // Mínimo = máximo e alto em todos: a ligação lê-se sempre. Com um teto baixo (o antigo "Ao vivo", 10 s) a app
    // parava de ler; muitos servidores Xtream abrandam então e o buffer esvaziava até parar (visto na SHIELD).
    // A diferença entre as opções está em quanto se espera para arrancar e depois de uma paragem.
    val (minMs, maxMs, startMs, rebufferMs) = when (prefs.buffer) {
        Prefs.Buffer.LIVE -> listOf(30_000, 30_000, 500, 2_500)
        Prefs.Buffer.SMALL -> listOf(30_000, 30_000, 1_000, 3_000)
        Prefs.Buffer.LARGE -> listOf(120_000, 120_000, 4_000, 8_000)
        else -> listOf(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    }
    val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs).build()
    return ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).build().apply {
        playWhenReady = true
        if (PLAYER_LOGS) addAnalyticsListener(EventLogger())
    }
}

private val isEmulator = Build.HARDWARE in setOf("ranchu", "goldfish")

/** Registos do player (buffer de 2 em 2 s e EventLogger): no debug e no staging, para medir na TV. */
private val PLAYER_LOGS = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "staging"

/**
 * Põe os descodificadores de software primeiro para vídeo e/ou áudio, conforme as definições
 * (o vídeo sempre no emulador: lá o H.264 "goldfish" bloqueia em 1080p).
 */
private fun decoderSelector(softwareVideo: Boolean, softwareAudio: Boolean) = MediaCodecSelector { mimeType, secure, tunneling ->
    val infos = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
    val software = if (MimeTypes.isAudio(mimeType)) softwareAudio else softwareVideo
    if (software) infos.sortedBy { if (it.softwareOnly) 0 else 1 } else infos
}

/**
 * Sem passthrough, o áudio Dolby/DTS é sempre descodificado na box (só PCM sai para a TV).
 * O vídeo usa o [HdrVideoRenderer] (HDR → SDR quando a TV não aceita o HDR do canal).
 * O áudio tem também o FFmpeg: depois dos descodificadores da box (só entra quando ela não sabe descodificar
 * o áudio do canal: MP2, AC-3, E-AC-3, DTS…), ou primeiro com o áudio por software ([ffmpegFirst]).
 */
private class ItvRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val hdr: String,
    private val ffmpegFirst: Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        val ffmpeg = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        if (ffmpegFirst) out.add(0, ffmpeg) else out.add(ffmpeg)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Igual ao do DefaultRenderersFactory (a app não tem extensões de vídeo).
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out.add(HdrVideoRenderer(builder, context.applicationContext, hdr))
        // FFmpeg depois do descodificador da box: abre por software o vídeo que ela não descodifica (MPEG-2, HEVC 10-bit, AV1…).
        out.add(FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY))
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        if (passthrough) {
            super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        } else {
            // Capacidades "só PCM": obsoleto no Media3 1.11, mas a alternativa (AudioOutputProvider) não tem esta opção simples.
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .build()
        }
}

/**
 * Pede ao descodificador que converta o vídeo HDR para SDR quando é preciso (ver [HdrType.needsSdr]).
 * Só existe no Android 13+; os descodificadores que não o fazem ignoram o pedido e o vídeo segue em HDR.
 */
private class HdrVideoRenderer(builder: Builder, private val context: Context, private val hdr: String) : MediaCodecVideoRenderer(builder) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && HdrType.needsSdr(context, format, hdr)) {
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        return mediaFormat
    }
}

/** Escolhe a faixa de áudio com mais canais (5.1/7.1), se houver. Devolve true se decidiu. */
private fun selectSurroundTrack(player: Player, tracks: Tracks): Boolean {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.isEmpty()) return false
    val best = audio.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        .maxByOrNull { (g, i) -> g.getTrackFormat(i).channelCount }
        ?: return true
    val (group, index) = best
    if (group.getTrackFormat(index).channelCount > 2 && !group.isTrackSelected(index)) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }
    return true
}

/** Carrega a stream no player, seja o ExoPlayer ou o VLC (o equivalente ao `setMediaSource`). */
internal fun Player.loadStream(candidate: StreamCandidate, channel: Channel?, nearLive: Boolean, userAgent: String) {
    when (this) {
        is VlcPlayer -> setStream(candidate.url, channel?.userAgent ?: userAgent, channel?.referrer)
        is ExoPlayer -> setMediaSource(mediaSourceFor(candidate, channel, nearLive, userAgent))
    }
}

/** Há áudio ou vídeo, mas nenhuma faixa desse tipo que este player consiga descodificar. */
internal fun Tracks.cannotDecode(): Boolean {
    fun none(type: Int) = groups.filter { it.type == type }.let { g -> g.isNotEmpty() && g.none { it.isSupported(true) } }
    return none(C.TRACK_TYPE_AUDIO) || none(C.TRACK_TYPE_VIDEO)
}

/** Erros de descodificação: o canal abre, mas este player não sabe descodificar o que lá vem. */
private val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

/** O formato do vídeo a tocar (só o ExoPlayer o dá; com o VLC não há AFR nem a cadência do vidro). */
internal fun Player.videoFormat(): Format? = (this as? ExoPlayer)?.videoFormat

/** [nearLive]: nas streams HLS, tocar o mais perto possível do direto. */
internal fun mediaSourceFor(candidate: StreamCandidate, channel: Channel?, nearLive: Boolean, userAgent: String): MediaSource {
    // Muitos servidores redirecionam de http para https ou para outro host.
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(channel?.userAgent ?: userAgent)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    channel?.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

    val item = MediaItem.Builder()
        .setUri(candidate.url)
        .apply {
            if (nearLive) {
                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(NEAR_LIVE_OFFSET_MS).build())
            }
        }
        .build()
    return if (candidate.isHls) {
        HlsMediaSource.Factory(http).createMediaSource(item)
    } else {
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        ProgressiveMediaSource.Factory(http, extractors).createMediaSource(item)
    }
}
