package cc.slokker.itv.ui.player.engine

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cc.slokker.itv.IPTV_USER_AGENT
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.ui.player.NetMonitor
import cc.slokker.itv.ui.player.Timeshift
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A reprodução de um canal, igual na TV, no telemóvel e no segundo canal (as diferenças estão na [PlaybackPolicy]):
 * abrir as tentativas por ordem (ts ⇄ m3u8), pedir o link novo ao Stalker, mostrar o erro e tentar mais umas vezes,
 * voltar a ligar quando cai (sempre a começar pelo primeiro formato), continuar o catch-up onde ia, passar para o
 * VLC quando o ExoPlayer não descodifica, a pausa do direto e a medição da ligação.
 *
 * Uma ligação ao fornecedor de cada vez: cada tentativa é uma geração do [gate], que só abre a stream nova depois
 * de fechar a anterior (também ao mudar de player).
 *
 * Tudo na thread principal: os eventos do player chegam pelos `on…` e o estado é do Compose. [now] e [wait]
 * existem para os testes.
 */
@Stable
internal class ChannelPlayback(
    private val policy: PlaybackPolicy,
    private val scope: CoroutineScope,
    private val host: PlaybackHost,
    /** A pausa do direto (com [PlaybackPolicy.timeshift]); fica a mesma ao recriar o player. */
    val timeshift: Timeshift? = null,
    /** A medição da ligação (com [PlaybackPolicy.diagnostics]). */
    private val net: NetMonitor? = null,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** As ligações deste player (o da lista principal é partilhado com as gravações, ver [StreamGate.MAIN]). */
    val gate: StreamGate = StreamGate(),
) {
    private var engine: StreamEngine? = null

    /** O player atual (muda quando se recria: definições, ou o VLC para este canal). */
    var player: Player? by mutableStateOf(null)
        private set

    var ready by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set

    /** Não arrancou (o cartão de erro); null a tentar ou a dar. */
    var failure by mutableStateOf<PlaybackFailure?>(null)
        private set

    /** Caiu depois de estar a dar: a tentativa de voltar a ligar (1, 2…; 0 = não caiu). */
    var reconnects by mutableIntStateOf(0)
        private set

    /** Quando é a próxima tentativa (elapsedRealtime; 0 = a tentar agora) e quanto se esperou por ela. */
    var nextTryAt by mutableLongStateOf(0L)
        private set
    var retryDelayMs = 0L
        private set

    /** Última imagem do canal anterior, até o novo mostrar o primeiro frame (sem ecrã preto). */
    var freeze by mutableStateOf<ImageBitmap?>(null)

    /** Tira a imagem para [freeze] (null = definição desligada ou sem imagem). */
    var freezeCapture: (suspend () -> ImageBitmap?)? = null

    /** Canal que o ExoPlayer não descodifica: toca com o VLC (só nesse; ao mudar de canal volta o das definições). */
    var vlcFor by mutableStateOf<String?>(null)
        private set

    var request: PlaybackRequest? = null
        private set
    var candidates: List<StreamCandidate> = emptyList()
        private set
    var attempt = 0
        private set

    /** Chegou a este formato porque o anterior foi recusado (403…) neste canal: dá, mas não se aprende para a conta. */
    private var fellBackOnRefusal = false

    /** Já tocou neste canal: uma falha agora é uma quebra, não um canal em baixo. */
    var wasPlaying = false
        private set

    /** Catch-up: o instante real em que o vídeo aberto começa (o do pedido, ou o da gravação que o Stalker deu). */
    var archiveStartMs = 0L
        private set

    /** Catch-up do Stalker: o salto dentro da gravação até ao ponto pedido. */
    private var seekMs = 0L

    /** As faixas a escolher no canal aberto (língua preferida, surround); as escolhidas à mão ficam (ver [TrackChoice]). */
    private val tracks = TrackChoice()

    private var retries = 0
    private var linkFresh = false
    private val pacer = ZapPacer()

    /** O canal novo já mostrou a primeira imagem: a do anterior, se só chegar agora, já não serve. */
    private var firstFrameSeen = false

    /** Parado por [holdForZap] (a passar canais), à espera do canal onde se parar. */
    private var held = false
    private var retryJob: Job? = null
    private var linkJob: Job? = null

    /** À procura do canal na lista de reserva (ver [failOver]). */
    private var backupJob: Job? = null

    /** A dar o canal da lista de reserva no lugar do da principal (o aviso e o painel "canal de reserva"); null na principal. */
    var onBackup by mutableStateOf<BackupOrigin?>(null)
        private set
    private var tickJob: Job? = null

    init {
        // O gravador da pausa do direto volta a ligar sozinho quando a ligação cai: a cápsula conta as tentativas.
        timeshift?.onReconnect = { n, at, waitMs ->
            reconnects = n
            retryDelayMs = waitMs
            nextTryAt = at
        }
        timeshift?.onConnected = {
            reconnects = 0
            nextTryAt = 0L
        }
    }

    val currentCandidate: StreamCandidate? get() = candidates.getOrNull(attempt)

    /** O motor para [prefsEngine] no canal [channelKey]: o VLC se este canal passou para ele. */
    fun engineFor(channelKey: String?, prefsEngine: String): String =
        if (channelKey != null && vlcFor == channelKey) Prefs.Engine.VLC else prefsEngine

    /** Catch-up: onde vai o vídeo, em ms desde o início do programa; null em direto. */
    fun programmePositionMs(): Long? {
        val r = request ?: return null
        return r.catchup?.positionAt(archiveStartMs, engine?.positionMs ?: 0L)
    }

    // ---- Player ---------------------------------------------------------------------------------

    /** Passa a usar [engine] (o anterior larga-se com [detach]; o canal volta a abrir com [tune]). */
    fun attach(engine: StreamEngine) {
        this.engine = engine
        player = engine.player
        tickJob?.cancel()
        tickJob = null
        if (timeshift == null && net == null) return
        tickJob = scope.launch {
            var ticks = 0
            while (true) {
                engine.tick { wall -> request?.channel?.let { host.programmeStartAt(it, wall) } }
                if (net != null && ++ticks % 2 == 0) engine.sampleNet()
                wait(TICK_MS)
            }
        }
    }

    /** Larga [engine] e liberta o player dele (as tentativas pendentes ficam sem efeito). */
    fun detach(engine: StreamEngine) {
        retryJob?.cancel()
        linkJob?.cancel()
        engine.release()
        timeshift?.clear()
        if (this.engine === engine) {
            this.engine = null
            player = null
            tickJob?.cancel()
            tickJob = null
        }
    }

    /** O ecrã saiu: nada fica a correr. */
    fun release() {
        backupJob?.cancel()
        retryJob?.cancel()
        linkJob?.cancel()
        tickJob?.cancel()
        engine?.let { detach(it) }
        timeshift?.onReconnect = null
        timeshift?.onConnected = null
        timeshift?.clear()
    }

    // ---- O que a app pede ---------------------------------------------------------------------

    /**
     * Novo canal (ou outro ponto do catch-up, ou um player novo): guarda a última imagem e recomeça pelas
     * tentativas na ordem do formato preferido. A imagem tira-se ao mesmo tempo que o canal novo abre: o
     * descodificador é esvaziado, não libertado, e a última imagem fica no ecrã até à primeira do canal novo.
     *
     * Outro canal em direto passa pelo atraso ao mudar de canal ([ZapPacer]): a passar canais depressa, o canal que
     * estava a dar pára (fica a última imagem) e larga a ligação, para o fornecedor a libertar enquanto se escolhe;
     * espera-se aqui, e uma mudança nova pelo meio cancela o efeito, e com ele esta espera. Voltar ao canal que
     * ainda está a dar não abre nada.
     */
    suspend fun tune(request: PlaybackRequest) {
        val previous = this.request
        // A dar da reserva, conta o canal do ecrã (o da lista principal).
        val same = previous?.shownKey == request.channel.key
        // Parado a meio de uma passagem de canais ([held]), voltar ao mesmo canal também é mais uma tecla.
        val zap = request.live && previous != null && (!same || held)
        val back = !zap && request.live && previous?.live == true && same &&
            previous.candidates == request.candidates && failure == null && engine?.idle == false
        if (zap || back) {
            val pause = pacer.delayFor(now(), host.prefs.zapDelayMs)
            if (back) return
            if (pause > 0) {
                ZapTrace.mark("W", "${pause}ms")
                holdForZap()
                wait(pause)
            }
        }
        ZapTrace.tune(request.channel.name)
        val capture = freezeCapture.takeIf { ready }
        coroutineScope {
            // O pedido da imagem sai já (antes de abrir); a resposta espera-se depois.
            val shot = capture?.let { async(start = CoroutineStart.UNDISPATCHED) { it() } }
            firstFrameSeen = false
            open(request)
            shot?.await()?.let { if (!firstFrameSeen) freeze = it }
            ZapTrace.mark("F")
        }
    }

    private fun open(request: PlaybackRequest) {
        held = false
        this.request = request
        candidates = request.candidates
        archiveStartMs = request.archiveStartMs
        seekMs = 0L
        net?.reset()
        retries = 0
        wasPlaying = false
        tracks.onOpen(request.channel.key)
        retryJob?.cancel()
        backupJob?.cancel()
        // Outro canal (ou o mesmo outra vez) começa sempre pela lista principal.
        onBackup = null
        reconnects = 0
        nextTryAt = 0L
        start(0)
    }

    /**
     * Passa a dar [backup] (o mesmo canal na lista de reserva) no lugar do pedido atual da lista principal, que falhou
     * com [error] (null: escolhido à mão). A ligação à principal fecha antes de abrir a da reserva: é outra geração do
     * [gate], que fecha as anteriores antes de ligar. O ecrã continua na lista principal; o próximo canal volta a ela.
     */
    private fun openBackup(backup: BackupStream, error: PlaybackException?) {
        val main = request?.let { it.backup?.main ?: it } ?: return
        val origin = BackupOrigin(main, error, currentCandidate?.format, backup.mainName, backup.backupName)
        val req = backup.request.asBackup(origin)
        held = false
        request = req
        candidates = req.candidates
        archiveStartMs = 0L
        seekMs = 0L
        linkFresh = false
        wasPlaying = false
        failure = null
        retryJob?.cancel()
        reconnects = 0
        nextTryAt = 0L
        onBackup = origin
        start(0)
    }

    /**
     * "Experimentar agora" (painel do canal de reserva): dá já [backup] no lugar do canal da lista principal, com o
     * mesmo aviso; o próximo canal volta à principal.
     */
    fun playBackup(backup: BackupStream) {
        if (request == null) return
        backupJob?.cancel()
        openBackup(backup, null)
    }

    /** A passar canais: pára o canal que está a dar e larga a ligação, com a última imagem no ecrã. */
    private suspend fun holdForZap() {
        val e = engine ?: return
        if (e.idle) return
        if (ready) freezeCapture?.invoke()?.let { freeze = it }
        retryJob?.cancel()
        linkJob?.cancel()
        nextTryAt = 0L
        e.holdForZap()
        timeshift?.clear()
        ready = false
        held = true
    }

    /** OK no cartão de erro: tenta outra vez desde o primeiro formato. */
    fun retry() {
        retries = 0
        backupJob?.cancel()
        start(0)
    }

    /** Abre a tentativa [index] (o aviso de paragens: "experimentar o outro formato"). */
    fun switchTo(index: Int) = start(index)

    /** App outra vez à vista: o canal parado recomeça. */
    fun onStart() {
        if (engine?.idle != true) return
        val req = request ?: return
        // A dar da reserva: ao voltar, tenta-se outra vez a lista principal primeiro.
        val main = req.backup?.main
        if (main != null) open(main) else start(attempt)
    }

    /** Box em segundo plano, ecrã desligado ou (telemóvel) nas Definições: pára e liberta a ligação. */
    fun stop() {
        // Uma reabertura pendente abria o stream com a app em segundo plano: ao voltar, recomeça em [onStart].
        backupJob?.cancel()
        retryJob?.cancel()
        linkJob?.cancel()
        nextTryAt = 0L
        engine?.stop()
        // O anel apaga-se: ao voltar, o canal recomeça no direto.
        timeshift?.clear()
    }

    // ---- Tentativas -----------------------------------------------------------------------------

    private fun start(index: Int) {
        if (host.streamsDisabled) return
        val req = request ?: return
        val engine = engine ?: return
        linkJob?.cancel()
        // Stalker: cada tentativa desde o início pede um link novo ao portal (os antigos caducam).
        val fresh = req.freshLinks
        if (fresh != null && index == 0 && !linkFresh) {
            ready = false
            linkJob = scope.launch {
                val links = fresh()
                candidates = links.candidates
                links.archiveStartMs?.let { archiveStartMs = it }
                seekMs = links.seekMs
                linkFresh = true
                start(0)
            }
            return
        }
        linkFresh = false
        // Uma gravação deste canal: abre-se o endereço que ela grava (é a mesma ligação).
        val recordingUrl = if (req.live) host.recordingUrl(req.channel) else null
        if (index == 0 && recordingUrl != null) {
            val i = candidates.indexOfFirst { it.url == recordingUrl }
            if (i > 0) return start(i)
        }
        val candidate = candidates.getOrNull(index)
        if (candidate == null) {
            // Catch-up do Stalker sem gravação deste programa: diz-se, em vez de ficar a sintonizar.
            if (index == 0 && !req.live) fail(PlaybackException("Sem gravação", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND), req, null)
            return
        }
        if (index == 0) fellBackOnRefusal = false
        attempt = index
        failure = null
        ready = false
        val prefs = host.prefs
        // Pausar o direto: pelo anel (só no ExoPlayer, em direto). Sem ele, o anel (e a ligação dele) fecha antes;
        // uma gravação deste canal também abre pelo anel, para o player ler a ligação dela.
        val ring = policy.timeshift && timeshift != null && (prefs.timeshift || candidate.url == recordingUrl) && req.live && engine.supportsRing
        when {
            ring -> Unit
            // Uma gravação no disco não liga ao fornecedor: a gravação que esteja a decorrer continua.
            candidate.isLocal -> timeshift?.clear()
            else -> timeshift?.yieldConnection()
        }
        ZapTrace.mark("O", "${candidate.format} #$index${if (ring) " ring" else ""}")
        engine.open(
            candidate, req.channel,
            OpenOptions(
                nearLive = prefs.buffer == Prefs.Buffer.LIVE && req.live,
                userAgent = prefs.userAgent.ifBlank { IPTV_USER_AGENT },
                ring = ring,
                reconnectMs = prefs.reconnectSeconds * 1_000L,
                startMs = seekMs,
                audioLanguage = audioLanguage(prefs).takeIf { !tracks.isManual(C.TRACK_TYPE_AUDIO) },
                textLanguage = textLanguage(prefs).takeIf { !tracks.isManual(C.TRACK_TYPE_TEXT) },
            ),
        )
    }

    /** A língua do áudio: a das Definições ou, em "Automático", a da app. */
    private fun audioLanguage(prefs: Prefs): String? = prefs.audioLanguage.ifEmpty { host.appLanguage.orEmpty() }.ifEmpty { null }

    /** A língua das legendas (vazio nas Definições = sem legendas, além das que o stream marca). */
    private fun textLanguage(prefs: Prefs): String? = prefs.subtitleLanguage.ifEmpty { null }.takeIf { policy.subtitleLanguage }

    /** Uma só espera de cada vez: dois erros seguidos não deixam duas reaberturas pendentes. */
    private fun retryLater(block: suspend () -> Unit) {
        retryJob?.cancel()
        retryJob = scope.launch { block() }
    }

    /**
     * Quanto esperar para voltar a tentar, ou null para não tentar ("voltar a ligar" desligado). [ended]: o servidor
     * fechou a ligação (um direto não acaba), que volta sempre a ligar.
     */
    private fun retryDelay(prefs: Prefs, ended: Boolean): Long? {
        val chosen = prefs.reconnectSeconds * 1_000L
        return if (chosen > 0 || ended || policy.alwaysRetry) maxOf(policy.reconnectFloorMs, chosen) else null
    }

    /** Caiu a meio: volta a ligar daqui a [delayMs], outra vez pelo primeiro formato (a cápsula conta as tentativas). */
    private fun reconnectIn(delayMs: Long) {
        reconnects++
        retryDelayMs = delayMs
        nextTryAt = now() + delayMs
        retryLater {
            wait(delayMs)
            nextTryAt = 0L
            start(0)
        }
    }

    /** O ExoPlayer não descodifica este canal: o VLC talvez sim (o player recria-se, e o canal volta a abrir). */
    private fun useVlc(engine: StreamEngine) {
        // A chave do ecrã: o player recria-se com o VLC para o canal da lista principal (e volta a passar à reserva).
        val key = request?.shownKey ?: return
        if (!policy.vlcFallback || engine.isVlc || vlcFor == key) return
        vlcFor = key
        host.onVlcFallback()
    }

    // ---- Eventos do player ----------------------------------------------------------------------

    fun onState(source: StreamEngine, state: Int) {
        if (source !== engine) return
        buffering = state == Player.STATE_BUFFERING
        val live = request?.live == true
        // Parou para carregar depois de já estar a dar (a página Ligação e o aviso contam estas paragens).
        // (Não conta a espera depois de saltar na pausa do direto.)
        if (state == Player.STATE_BUFFERING && ready && live && timeshift?.seeking != true) net?.onStop()
        if (state == Player.STATE_READY) timeshift?.seeking = false
        if (state == Player.STATE_ENDED) {
            when {
                // O programa em catch-up acabou: volta ao direto.
                !live -> host.exitCatchup()
                // Pausa do direto: acabou uma ligação (há outra a seguir) ou o ponto saiu do anel; continua.
                source.ringContinues() -> Unit
                // Um canal em direto não acaba: "acabou" quer dizer que o servidor fechou a ligação (comum em streams
                // .ts). Sem isto o vídeo parava em silêncio e a TV entrava em proteção de ecrã. Antes de alguma vez
                // dar, fica a cápsula "a sintonizar" (como sempre na TV).
                wasPlaying -> retryDelay(host.prefs, ended = true)?.let(::reconnectIn)
            }
        }
        if (state == Player.STATE_READY && !ready) {
            ready = true
            wasPlaying = true
            retries = 0
            reconnects = 0
            nextTryAt = 0L
            // O fallback encontrou um formato que funciona (no direto; as tentativas do catch-up não são formatos).
            val candidate = currentCandidate
            // O formato da reserva é de outra conta: não se aprende.
            val backup = request?.backup
            if (policy.learnFormat && attempt > 0 && candidate != null && live && !fellBackOnRefusal && backup == null) host.onFormatWorked(candidate.format)
            if (backup != null) host.onBackupPlaying(backup)
        }
    }

    fun onPlayingChanged(source: StreamEngine, isPlaying: Boolean) {
        if (source === engine) playing = isPlaying
    }

    fun onFirstFrame(source: StreamEngine) {
        if (source !== engine) return
        firstFrameSeen = true
        freeze = null
    }

    /**
     * As faixas chegaram (ou mudaram): escolhe as que faltam neste canal, uma vez por abertura (a língua preferida e,
     * na TV, o surround). As escolhidas à mão no menu ([onManualTrack]) ficam até mudar de canal.
     */
    fun onTracksChanged(source: StreamEngine) {
        if (source !== engine || request == null) return
        val audio = tracks.pending(C.TRACK_TYPE_AUDIO)
        val text = policy.subtitleLanguage && tracks.pending(C.TRACK_TYPE_TEXT)
        if (!audio && !text) return
        val prefs = host.prefs
        val wish = TrackWish(
            audioLanguage = audioLanguage(prefs),
            textLanguage = textLanguage(prefs),
            surround = policy.surroundDefault && prefs.surroundDefault,
            audio = audio,
            text = text,
        )
        source.chooseTracks(wish).forEach(tracks::done)
    }

    /** O menu das faixas: a escolha de [type] (C.TRACK_TYPE_AUDIO/TEXT) ganha às Definições até mudar de canal. */
    fun onManualTrack(type: Int) = tracks.onManual(type)

    /** As faixas chegaram e o player não descodifica o áudio ou o vídeo ([cannotDecode]). */
    fun onUndecodable(source: StreamEngine) {
        if (source === engine) useVlc(source)
    }

    fun onError(source: StreamEngine, error: PlaybackException) {
        if (source !== engine) return
        val req = request ?: return
        val prefs = host.prefs
        if (wasPlaying && req.live) net?.onStop()
        val reconnect = retryDelay(prefs, ended = false)
        when {
            // Muito comum em live: voltar ao ponto em direto.
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> source.restartAtLive()
            // Nem a box nem o FFmpeg descodificam o canal: o VLC talvez sim.
            policy.vlcFallback && !source.isVlc && isDecoderError(error.errorCode) -> useVlc(source)
            // Ainda não começou: tenta o formato seguinte (ts ⇄ m3u8). Uma recusa de "ocupado" (403, 503…) já foi
            // tentada uns 3 s (ver OpenRetry): a ligação anterior já fechou, por isso é este formato que o fornecedor
            // recusa neste canal (há canais que só dão em m3u8). Passa-se ao outro, mas sem o aprender para a conta.
            !ready && attempt + 1 < candidates.size -> {
                fellBackOnRefusal = fellBackOnRefusal || OpenRetry.isBusy(error) || OpenRetry.httpCode(error) == 429
                start(attempt + 1)
            }
            // Catch-up caiu a meio: pede de novo a partir de onde ia.
            wasPlaying && !req.live -> {
                val position = programmePositionMs() ?: 0L
                val delayMs = retryDelay(prefs, ended = true) ?: policy.reconnectFloorMs
                retryLater {
                    wait(delayMs)
                    host.seekCatchup(position)
                }
            }
            // Caiu a meio: volta a ligar, de N em N segundos, enquanto se estiver no canal.
            wasPlaying && reconnect != null -> reconnectIn(reconnect)
            else -> fail(error, req, reconnect)
        }
    }

    /**
     * Não arrancou: mostra o erro (primeiro "a analisar") e tenta mais umas vezes, sem martelar o servidor.
     *
     * Um canal em direto da lista principal passa antes para o mesmo canal da lista de reserva, se houver (só depois
     * de as tentativas de sempre desistirem: os formatos, e as esperas do OpenRetry em cada um). Se a reserva também
     * falhar, volta-se ao pedido da principal (as tentativas seguintes começam por ela) e o cartão diz as duas.
     */
    private fun fail(error: PlaybackException, req: PlaybackRequest, delayMs: Long?) {
        if (policy.failover && req.live && req.backup == null && backupJob?.isActive != true) {
            val shown = currentCandidate?.format
            backupJob = scope.launch {
                val backup = runCatching { host.backupFor(req) }.getOrNull()
                if (request !== req) return@launch
                if (backup != null) {
                    openBackup(backup, error)
                } else {
                    showFailure(error, req, delayMs, null, shown)
                }
            }
            return
        }
        val origin = req.backup
        if (origin != null) {
            // A reserva também não abriu: o cartão é o da principal (com o erro dela), e as tentativas recomeçam nela.
            request = origin.main
            candidates = origin.main.candidates
            attempt = 0
            onBackup = null
            showFailure(origin.mainError ?: error, origin.main, delayMs, origin.backupName, origin.mainFormat)
            return
        }
        showFailure(error, req, delayMs, null, currentCandidate?.format)
    }

    private fun showFailure(error: PlaybackException, req: PlaybackRequest, delayMs: Long?, backupFailed: String?, format: String?) {
        freeze = null
        val retry = delayMs != null && retries < policy.maxStartRetries
        if (retry) retries++
        val shown = PlaybackFailure(
            error,
            retry = if (retry) retries else 0,
            maxRetries = policy.maxStartRetries,
            retrySeconds = ((delayMs ?: 0L) / 1_000).toInt(),
            backupFailed = backupFailed,
        )
        failure = shown
        retryLater {
            // Pergunta ao servidor se a conta está mesmo ocupada antes de o dizer.
            val explained = host.explain(error, req, format)
            if (failure === shown) failure = shown.copy(explained = explained, analysing = false)
            if (retry && delayMs != null) {
                wait(delayMs)
                start(0)
            }
        }
    }

    private companion object {
        /** O estado da pausa do direto atualiza-se a este ritmo (a medição da ligação, ao dobro). */
        const val TICK_MS = 500L
    }
}
