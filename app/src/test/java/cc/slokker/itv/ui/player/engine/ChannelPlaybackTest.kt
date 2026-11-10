package cc.slokker.itv.ui.player.engine

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.common.ErrorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/** As tentativas, o fallback ts ⇄ m3u8, voltar a ligar, o catch-up e o VLC, sem player nem rede. */
class ChannelPlaybackTest {
    /** Relógio de mentira: as esperas só acabam com [advance]. */
    private class FakeTime {
        var now = 0L
        private val sleeping = mutableListOf<Pair<Long, Continuation<Unit>>>()

        suspend fun wait(ms: Long) = suspendCancellableCoroutine { c ->
            val entry = (now + ms) to c
            sleeping += entry
            c.invokeOnCancellation { sleeping.remove(entry) }
        }

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = sleeping.filter { it.first <= target }.minByOrNull { it.first } ?: break
                sleeping.remove(next)
                now = next.first
                next.second.resume(Unit)
            }
            now = target
        }

        val pending: Int get() = sleeping.size
    }

    /** Um player de mentira: regista o que se abriu e por que ordem. */
    private class FakeEngine(override val isVlc: Boolean = false) : StreamEngine {
        val log = mutableListOf<String>()
        val opened = mutableListOf<StreamCandidate>()
        var lastOptions: OpenOptions? = null
        override val player: Player? = null
        override val supportsRing get() = !isVlc
        override var idle = true
        override var positionMs = 0L
        override fun open(candidate: StreamCandidate, channel: Channel, options: OpenOptions) {
            log += "open ${candidate.url}"
            opened += candidate
            lastOptions = options
            idle = false
        }
        override fun restartAtLive() { log += "live" }
        override fun stop() { log += "stop"; idle = true }
        override fun holdForZap() { log += "hold"; idle = true }
        override fun release() { log += "release" }
        override fun ringContinues() = false
        val wishes = mutableListOf<TrackWish>()
        /** Os tipos que as faixas já deixam decidir (no player verdadeiro, os que já chegaram). */
        var tracksArrived = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)
        override fun chooseTracks(wish: TrackWish): Set<Int> {
            wishes += wish
            return tracksArrived.filter { (it == C.TRACK_TYPE_AUDIO && wish.audio) || (it == C.TRACK_TYPE_TEXT && wish.text) }.toSet()
        }
        override fun tick(programmeStart: (Long) -> Long?) = Unit
        override fun sampleNet() = Unit
    }

    private class FakeHost : PlaybackHost {
        override var prefs = Prefs(reconnectSeconds = 5)
        override var streamsDisabled = false
        val learned = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var exits = 0
        var vlcToasts = 0
        override fun onFormatWorked(format: String) { learned += format }
        override fun exitCatchup() { exits++ }
        override fun seekCatchup(positionMs: Long) { seeks += positionMs }
        override fun onVlcFallback() { vlcToasts++ }
        override var appLanguage: String? = "pt"
        override suspend fun explain(error: PlaybackException, request: PlaybackRequest, format: String?) = ErrorInfo("explicado")
        override fun programmeStartAt(channel: Channel, wallMs: Long): Long? = null
        var recording: String? = null
        override fun recordingUrl(channel: Channel): String? = recording
        var backup: BackupStream? = null
        var backupAsked = 0
        val backupNotices = mutableListOf<String>()
        override suspend fun backupFor(request: PlaybackRequest): BackupStream? { backupAsked++; return backup }
        override fun onBackupPlaying(origin: BackupOrigin) { backupNotices += "${origin.mainName}>${origin.backupName}" }
    }

    private val time = FakeTime()
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val host = FakeHost()
    private val engine = FakeEngine()
    private val channel = Channel(uid = 1, key = "k1", num = 1, name = "Um", logo = null, categoryId = "g", url = "http://x/1.ts")
    private val ts = StreamCandidate("http://x/1.ts", StreamFormat.TS)
    private val hls = StreamCandidate("http://x/1.m3u8", StreamFormat.HLS)

    private fun playback(policy: PlaybackPolicy = PlaybackPolicy.TV, e: FakeEngine = engine) =
        ChannelPlayback(policy, scope, host, now = { time.now }, wait = time::wait).also { it.attach(e) }

    private fun live(vararg c: StreamCandidate = arrayOf(ts, hls)) = PlaybackRequest(channel, null, 0L, c.toList())

    private fun ChannelPlayback.tuneNow(r: PlaybackRequest) = runBlocking { tune(r) }

    private fun error(code: Int) = PlaybackException("teste", null, code)
    private val ioError get() = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun abrePeloPrimeiroFormato() {
        val p = playback()
        p.tuneNow(live())
        assertEquals(listOf("open ${ts.url}"), engine.log)
        assertFalse(p.ready)
        p.onState(engine, Player.STATE_READY)
        assertTrue(p.ready)
        assertTrue(p.wasPlaying)
        assertNull(p.failure)
    }

    @Test
    fun semStreamsNaoAbreNada() {
        host.streamsDisabled = true
        playback().tuneNow(live())
        assertTrue(engine.log.isEmpty())
    }

    @Test
    fun falhaAntesDeDarTentaOOutroFormatoEDepoisMostraOErro() {
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, ioError)
        assertEquals(listOf(ts, hls), engine.opened)
        p.onError(engine, ioError)
        val f = p.failure!!
        // Explicado (o anfitrião respondeu logo) e a 1.ª de 3 tentativas, de 5 em 5 s.
        assertEquals("explicado", f.explained?.title)
        assertEquals(1, f.retry)
        assertEquals(3, f.maxRetries)
        assertEquals(5, f.retrySeconds)
        // A tentativa começa outra vez pelo primeiro formato.
        time.advance(5_000)
        assertEquals(listOf(ts, hls, ts), engine.opened)
        assertNull(p.failure)
    }

    @Test
    fun tresTentativasEDepoisFicaOErroAteOK() {
        val p = playback()
        p.tuneNow(live(ts))
        repeat(3) { n ->
            p.onError(engine, ioError)
            assertEquals(n + 1, p.failure?.retry)
            time.advance(5_000)
        }
        assertEquals(4, engine.opened.size)
        p.onError(engine, ioError)
        assertEquals(0, p.failure?.retry)
        time.advance(60_000)
        assertEquals(4, engine.opened.size)
        // OK: outra vez, com as tentativas todas.
        p.retry()
        assertEquals(5, engine.opened.size)
        p.onError(engine, ioError)
        assertEquals(1, p.failure?.retry)
    }

    @Test
    fun voltarALigarRecomecaSemprePeloPrimeiroFormato() {
        val p = playback()
        p.tuneNow(live())
        p.onState(engine, Player.STATE_READY)
        // Caiu a meio: pílula com a 1.ª tentativa, daqui a 5 s.
        p.onError(engine, ioError)
        assertEquals(1, p.reconnects)
        assertEquals(5_000, p.nextTryAt)
        time.advance(5_000)
        assertEquals(ts, engine.opened.last())
        // O .ts ainda não volta: tenta o .m3u8 logo; também falha: volta a esperar...
        p.onError(engine, ioError)
        assertEquals(hls, engine.opened.last())
        p.onError(engine, ioError)
        assertEquals(2, p.reconnects)
        // ...e a tentativa seguinte é outra vez pelo .ts (antes ficava presa no .m3u8 para sempre).
        time.advance(5_000)
        assertEquals(ts, engine.opened.last())
        p.onError(engine, ioError)
        p.onError(engine, ioError)
        assertEquals(3, p.reconnects)
        time.advance(5_000)
        assertEquals(ts, engine.opened.last())
        // Voltou a dar: a contagem recomeça.
        p.onState(engine, Player.STATE_READY)
        assertEquals(0, p.reconnects)
        assertEquals(0L, p.nextTryAt)
        assertNull(p.failure)
    }

    @Test
    fun oServidorFechouALigacaoVoltaALigarPeloPrimeiro() {
        host.prefs = Prefs(reconnectSeconds = 0)
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, ioError)
        p.onState(engine, Player.STATE_READY)
        assertEquals(hls, engine.opened.last())
        p.onState(engine, Player.STATE_ENDED)
        // Mesmo com "voltar a ligar" desligado: o mínimo de 1 s.
        time.advance(999)
        assertEquals(2, engine.opened.size)
        time.advance(1)
        assertEquals(ts, engine.opened.last())
    }

    @Test
    fun acabarAntesDeDarNaoFazNada() {
        val p = playback()
        p.tuneNow(live())
        p.onState(engine, Player.STATE_ENDED)
        time.advance(60_000)
        assertEquals(1, engine.opened.size)
        assertEquals(0, p.reconnects)
    }

    @Test
    fun voltarALigarDesligadoMostraOErroSemTentar() {
        host.prefs = Prefs(reconnectSeconds = 0)
        val p = playback()
        p.tuneNow(live(ts))
        p.onState(engine, Player.STATE_READY)
        p.onError(engine, ioError)
        assertEquals(0, p.failure?.retry)
        time.advance(60_000)
        assertEquals(1, engine.opened.size)
    }

    @Test
    fun doisErrosSeguidosSoDeixamUmaReabertura() {
        val p = playback()
        p.tuneNow(live(ts))
        p.onState(engine, Player.STATE_READY)
        p.onError(engine, ioError)
        p.onError(engine, ioError)
        assertEquals(1, time.pending)
        time.advance(5_000)
        assertEquals(2, engine.opened.size)
    }

    @Test
    fun emSegundoPlanoNaoFicaNadaPendente() {
        val p = playback()
        p.tuneNow(live(ts))
        p.onState(engine, Player.STATE_READY)
        p.onError(engine, ioError)
        p.stop()
        assertEquals(0L, p.nextTryAt)
        time.advance(60_000)
        assertEquals(1, engine.opened.size)
        assertEquals("stop", engine.log.last())
        // Ao voltar, o canal parado recomeça.
        p.onStart()
        assertEquals(2, engine.opened.size)
    }

    @Test
    fun catchUpQueCaiContinuaOndeIa() {
        val p = playback()
        val cu = Catchup(channel, "Programa", startMs = 1_000_000L, endMs = 4_600_000L, positionMs = 600_000L)
        // O servidor abriu ao minuto: 30 s antes da posição pedida.
        p.tuneNow(PlaybackRequest(channel, cu, archiveStartMs = 1_570_000L, candidates = listOf(ts)))
        assertEquals(false, engine.lastOptions?.ring)
        p.onState(engine, Player.STATE_READY)
        engine.positionMs = 95_000L
        p.onError(engine, ioError)
        assertTrue(host.seeks.isEmpty())
        time.advance(5_000)
        // 570 s (início pedido) + 95 s (o que já deu) desde o início do programa.
        assertEquals(listOf(665_000L), host.seeks)
        assertEquals(0, p.reconnects)
    }

    @Test
    fun catchUpQueAcabaVoltaAoDireto() {
        val p = playback()
        val cu = Catchup(channel, "Programa", 0L, 1_000L)
        p.tuneNow(PlaybackRequest(channel, cu, 0L, listOf(ts)))
        p.onState(engine, Player.STATE_READY)
        p.onState(engine, Player.STATE_ENDED)
        assertEquals(1, host.exits)
        assertEquals(1, engine.opened.size)
    }

    @Test
    fun erroDeDescodificadorPassaParaOVlc() {
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals("k1", p.vlcFor)
        assertEquals(1, host.vlcToasts)
        assertEquals(Prefs.Engine.VLC, p.engineFor("k1", Prefs.Engine.EXO))
        assertEquals(Prefs.Engine.EXO, p.engineFor("outro", Prefs.Engine.EXO))
        // Não abriu o outro formato: o canal volta a abrir com o VLC (player novo).
        assertEquals(1, engine.opened.size)
        val vlc = FakeEngine(isVlc = true)
        p.attach(vlc)
        p.detach(engine)
        p.tuneNow(live())
        assertEquals(listOf(ts), vlc.opened)
        // No VLC, um erro destes já não muda de player: segue para o outro formato.
        p.onError(vlc, error(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(listOf(ts, hls), vlc.opened)
        assertEquals(1, host.vlcToasts)
    }

    @Test
    fun faixasQueNaoSeDescodificamPassamParaOVlc() {
        val p = playback()
        p.tuneNow(live())
        p.onUndecodable(engine)
        assertEquals("k1", p.vlcFor)
    }

    @Test
    fun outrosErrosNaoPassamParaOVlc() {
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, ioError)
        assertNull(p.vlcFor)
        // O conjunto é o da TV: 4006 (recursos do descodificador recuperados) não conta.
        assertFalse(isDecoderError(4006))
        assertTrue(isDecoderError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
    }

    @Test
    fun eventosDeUmPlayerAntigoIgnoramSe() {
        val p = playback()
        p.tuneNow(live())
        val novo = FakeEngine()
        p.attach(novo)
        p.onError(engine, ioError)
        p.onState(engine, Player.STATE_READY)
        assertTrue(novo.opened.isEmpty())
        assertFalse(p.ready)
    }

    @Test
    fun oFormatoQueFuncionouFicaGuardado() {
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, ioError)
        p.onState(engine, Player.STATE_READY)
        assertEquals(listOf(StreamFormat.HLS), host.learned)
        // O primeiro a funcionar não muda nada.
        val other = FakeEngine()
        val q = playback(e = other)
        q.tuneNow(live())
        q.onState(other, Player.STATE_READY)
        assertEquals(listOf(StreamFormat.HLS), host.learned)
    }

    @Test
    fun noTelemovelOMesmoQueNaTv() {
        val p = playback(PlaybackPolicy.PHONE)
        p.tuneNow(live())
        p.onError(engine, ioError)
        p.onState(engine, Player.STATE_READY)
        assertEquals(listOf(StreamFormat.HLS), host.learned)
        p.onError(engine, error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals("k1", p.vlcFor)
    }

    @Test
    fun segundoCanalTentaSempreDe3Em3Segundos() {
        host.prefs = Prefs(reconnectSeconds = 0)
        val p = playback(PlaybackPolicy.SECOND)
        p.tuneNow(live(ts))
        repeat(10) {
            p.onError(engine, ioError)
            assertNotNull(p.failure)
            time.advance(3_000)
        }
        assertEquals(11, engine.opened.size)
        // Sem VLC nem formato guardado.
        p.onError(engine, error(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertNull(p.vlcFor)
        p.onState(engine, Player.STATE_READY)
        assertTrue(host.learned.isEmpty())
    }

    @Test
    fun stalkerPedeUmLinkNovoACadaTentativaDesdeOInicio() {
        val p = playback()
        var asked = 0
        p.tuneNow(PlaybackRequest(channel, null, 0L, emptyList(), freshLinks = { asked++; FreshLinks(listOf(StreamCandidate("http://portal/$asked", StreamFormat.TS))) }))
        assertEquals(listOf("open http://portal/1"), engine.log)
        p.onError(engine, ioError)
        time.advance(5_000)
        assertEquals("open http://portal/2", engine.log.last())
    }

    @Test
    fun aPausaDoDiretoSoEmDiretoNoExoPlayer() {
        val withRing = ChannelPlayback(PlaybackPolicy.TV, scope, host, now = { time.now }, wait = time::wait).also { it.attach(engine) }
        withRing.tuneNow(live())
        // Sem Timeshift (nos testes não há disco) não há anel; a opção nunca vai ligada sem ele.
        assertEquals(false, engine.lastOptions?.ring)
        assertEquals(false, engine.lastOptions?.nearLive)
        host.prefs = Prefs(buffer = Prefs.Buffer.LIVE)
        // (O buffer muda o player: recria-se, e o novo está parado.)
        engine.idle = true
        withRing.tuneNow(live())
        assertEquals(true, engine.lastOptions?.nearLive)
    }

    // ---- Gravações ----

    /** Uma gravação deste canal grava o endereço .m3u8? Não: grava um TS; o player abre o que ela grava, primeiro. */
    @Test
    fun comUmaGravacaoAbreOEnderecoDela() {
        val other = StreamCandidate("http://x/1-b.ts", StreamFormat.TS)
        host.recording = other.url
        playback().tuneNow(live(ts, other, hls))
        assertEquals(listOf("open ${other.url}"), engine.log)
    }

    @Test
    fun umaGravacaoNoDiscoAbreOFicheiroNoPontoPedido() {
        val p = playback()
        val cu = Catchup(channel, "Telejornal", 1_000_000L, 4_600_000L, positionMs = 90_000L, recording = "file:///rec/a.ts")
        val content = cc.slokker.itv.data.PlaylistContent(
            cc.slokker.itv.data.Playlist(id = "p1", name = "P", type = cc.slokker.itv.data.PlaylistType.M3U, url = "http://x/list.m3u"),
            emptyList(), listOf(channel),
        )
        p.tuneNow(PlaybackRequest.of(content, channel, cu, null, null))
        assertEquals(listOf("open file:///rec/a.ts"), engine.log)
        assertEquals(90_000L, engine.lastOptions?.startMs)
        assertEquals(false, engine.lastOptions?.ring)
        // A posição no programa é a do ficheiro (que começa no início da gravação).
        engine.positionMs = 95_000L
        assertEquals(95_000L, p.programmePositionMs())
    }

    // ---- Atraso ao mudar de canal e recusas "ocupado" ----

    private fun request(key: String) = PlaybackRequest(channel.copy(key = key, name = key), null, 0L, listOf(StreamCandidate("http://x/$key.ts", StreamFormat.TS)))

    @Test
    fun umaTeclaAbreJaAPassarCanaisSoAbreOndeSePara() {
        val p = playback()
        p.tuneNow(request("a"))
        time.advance(5_000)
        // Uma tecla: abre logo.
        p.tuneNow(request("b"))
        assertEquals("open http://x/b.ts", engine.log.last())
        // Mais teclas seguidas (cada uma cancela a espera da anterior, como o LaunchedEffect): só abre a última.
        time.advance(200)
        val c = scope.launch { p.tune(request("c")) }
        time.advance(200)
        c.cancel()
        scope.launch { p.tune(request("d")) }
        time.advance(ZapPacer.SETTLE_MS - 1)
        assertEquals("hold", engine.log.last())
        time.advance(1)
        // A primeira tecla de mais seguidas pára o canal que estava a dar (larga a ligação) enquanto se escolhe.
        assertEquals(listOf("open http://x/a.ts", "open http://x/b.ts", "hold", "open http://x/d.ts"), engine.log)
    }

    @Test
    fun aPassarCanaisVoltarAoQueParouTambemEspera() {
        val p = playback()
        p.tuneNow(request("a"))
        time.advance(5_000)
        p.tuneNow(request("b"))
        time.advance(200)
        val c = scope.launch { p.tune(request("c")) }
        time.advance(200)
        c.cancel()
        // De volta ao b (parado enquanto se passava): é mais uma tecla, espera como as outras.
        scope.launch { p.tune(request("b")) }
        assertEquals("hold", engine.log.last())
        time.advance(ZapPacer.SETTLE_MS)
        assertEquals(listOf("open http://x/a.ts", "open http://x/b.ts", "hold", "open http://x/b.ts"), engine.log)
    }

    @Test
    fun voltarAoCanalQueEstaADarNaoAbreNada() {
        val p = playback()
        p.tuneNow(request("a"))
        time.advance(5_000)
        p.tuneNow(request("b"))
        // b ainda a dar: de volta a ele (a lista deu a volta, ou a lista do servidor foi lida outra vez) não reabre.
        time.advance(5_000)
        p.tuneNow(request("b"))
        assertEquals(listOf("open http://x/a.ts", "open http://x/b.ts"), engine.log)
        // Parado (outro player, ou parou ao passar canais): abre.
        engine.idle = true
        p.tuneNow(request("b"))
        assertEquals("open http://x/b.ts", engine.log.last())
        assertEquals(3, engine.log.size)
    }

    @Test
    fun oMesmoCanalOuOCatchUpNaoEsperam() {
        host.prefs = Prefs(zapDelayMs = 2_000)
        val p = playback()
        p.tuneNow(request("a"))
        // Outro player (parado) ou o mesmo canal outra vez: abre logo, sem esperar.
        engine.idle = true
        p.tuneNow(request("a"))
        assertEquals(2, engine.log.size)
    }

    @Test
    fun atrasoDesligado() {
        host.prefs = Prefs(zapDelayMs = Prefs.ZapDelay.OFF)
        val p = playback()
        listOf("a", "b", "c").forEach { p.tuneNow(request(it)) }
        assertEquals(3, engine.log.size)
    }

    @Test
    fun recusaPassaAoOutroFormatoSemAprender() {
        val p = playback()
        p.tuneNow(live())
        // O .ts foi recusado (403) durante as tentativas rápidas: há canais que o fornecedor só dá em m3u8.
        p.onError(engine, PlaybackException("recusado", busy(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(listOf(ts, hls), engine.opened)
        assertNull(p.failure)
        // Deu em m3u8, mas não se aprende para a conta: noutro canal, o .ts pode dar.
        p.onState(engine, Player.STATE_READY)
        assertTrue(host.learned.isEmpty())
    }

    @Test
    fun recusaEmTodosOsFormatosMostraOErro() {
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, PlaybackException("recusado", busy(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        p.onError(engine, PlaybackException("recusado", busy(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        // Os dois recusados: o erro explicado e, mais tarde, outra volta desde o .ts.
        assertNotNull(p.failure)
        time.advance(5_000)
        assertEquals(listOf(ts, hls, ts), engine.opened)
    }

    /** Uma recusa 403 do servidor (sem Uri: no PC o Android não existe, por isso sem passar pelo construtor). */
    private fun busy(): androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException {
        val type = androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException::class.java
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val e = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
        type.getField("responseCode").apply { isAccessible = true }.setInt(e, 403)
        return e
    }

    @Test
    fun aImagemCongeladaNaoAtrasaAAbertura() {
        host.prefs = Prefs(zapDelayMs = Prefs.ZapDelay.OFF)
        val p = playback()
        p.tuneNow(request("a"))
        p.onState(engine, Player.STATE_READY)
        val bitmap = object : androidx.compose.ui.graphics.ImageBitmap {
            override val width = 1
            override val height = 1
            override val config = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
            override val colorSpace = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
            override val hasAlpha = false
            override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) = Unit
            override fun prepareToDraw() = Unit
        }
        // A imagem demora (PixelCopy): o canal novo abre logo, sem esperar por ela.
        p.freezeCapture = { time.wait(40); bitmap }
        time.advance(5_000)
        scope.launch { p.tune(request("b")) }
        assertEquals("open http://x/b.ts", engine.log.last())
        time.advance(40)
        assertEquals(bitmap, p.freeze)
        // Já com a primeira imagem do canal novo, uma imagem atrasada do anterior não fica por cima.
        p.onState(engine, Player.STATE_READY)
        scope.launch { p.tune(request("c")) }
        p.onFirstFrame(engine)
        time.advance(40)
        assertNull(p.freeze)
    }

    // ---- Faixas: língua preferida, surround e a escolha à mão -------------------------------------

    @Test
    fun asFaixasEscolhemSeUmaVezPorAberturaComALinguaDasDefinicoes() {
        host.prefs = Prefs(audioLanguage = "en", subtitleLanguage = "pt", surroundDefault = true)
        val p = playback()
        p.tuneNow(live())
        p.onTracksChanged(engine)
        p.onTracksChanged(engine)
        assertEquals(listOf(TrackWish(audioLanguage = "en", textLanguage = "pt", surround = true)), engine.wishes)
        // O VLC escolhe as línguas sozinho ao abrir: vão nas opções.
        assertEquals("en", engine.lastOptions?.audioLanguage)
        assertEquals("pt", engine.lastOptions?.textLanguage)
    }

    @Test
    fun automaticoEALinguaDaAppSemLegendas() {
        val p = playback()
        p.tuneNow(live())
        p.onTracksChanged(engine)
        assertEquals(TrackWish(audioLanguage = "pt", textLanguage = null, surround = false), engine.wishes.single())
        assertNull(engine.lastOptions?.textLanguage)
    }

    @Test
    fun asLegendasQueAindaNaoChegaramDecidemSeQuandoChegarem() {
        host.prefs = Prefs(subtitleLanguage = "pt")
        val p = playback()
        engine.tracksArrived = setOf(C.TRACK_TYPE_AUDIO)
        p.tuneNow(live())
        p.onTracksChanged(engine)
        engine.tracksArrived = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)
        p.onTracksChanged(engine)
        p.onTracksChanged(engine)
        assertEquals(listOf(true to true, false to true), engine.wishes.map { it.audio to it.text })
    }

    @Test
    fun aEscolhaAMaoGanhaAteMudarDeCanal() {
        host.prefs = Prefs(audioLanguage = "en", subtitleLanguage = "pt")
        val p = playback()
        p.tuneNow(live())
        p.onTracksChanged(engine)
        p.onManualTrack(C.TRACK_TYPE_AUDIO)
        // O mesmo canal noutro ponto (catch-up, um salto): o áudio escolhido à mão fica, as legendas voltam a decidir-se.
        val cu = Catchup(channel, "Programa", 0L, 3_600_000L)
        p.tuneNow(PlaybackRequest(channel, cu, 0L, listOf(ts)))
        assertNull(engine.lastOptions?.audioLanguage)
        assertEquals("pt", engine.lastOptions?.textLanguage)
        p.onTracksChanged(engine)
        assertEquals(TrackWish("en", "pt", surround = false, audio = false, text = true), engine.wishes.last())
        // Outro canal: volta a valer a definição.
        p.tuneNow(PlaybackRequest(channel.copy(key = "k2"), null, 0L, listOf(hls)))
        assertEquals("en", engine.lastOptions?.audioLanguage)
        p.onTracksChanged(engine)
        assertEquals(TrackWish("en", "pt", surround = false), engine.wishes.last())
    }

    @Test
    fun oTelemovelNaoEscolheSurroundEOSegundoCanalNaoLigaLegendas() {
        host.prefs = Prefs(surroundDefault = true, subtitleLanguage = "pt")
        val phone = playback(PlaybackPolicy.PHONE)
        phone.tuneNow(live())
        phone.onTracksChanged(engine)
        assertEquals(false, engine.wishes.last().surround)
        val second = FakeEngine()
        val p = playback(PlaybackPolicy.SECOND, second)
        p.tuneNow(live())
        p.onTracksChanged(second)
        assertEquals(TrackWish("pt", null, surround = false, audio = true, text = false), second.wishes.single())
    }

    // ---- Catch-up do Stalker: a gravação do programa, e o salto até ao ponto pedido -----------------

    @Test
    fun stalkerCatchUpAbreAGravacaoESaltaParaOPonto() {
        val p = playback()
        val cu = Catchup(channel, "Programa", 1_000_000L, 4_600_000L, positionMs = 600_000L)
        p.tuneNow(PlaybackRequest(channel, cu, 1_600_000L, emptyList(), freshLinks = {
            FreshLinks(listOf(StreamCandidate("http://portal/rec.mpg", StreamFormat.TS)), archiveStartMs = 900_000L, seekMs = 700_000L)
        }))
        assertEquals(listOf("open http://portal/rec.mpg"), engine.log)
        assertEquals(700_000L, engine.lastOptions?.startMs)
        // O player vai na posição da gravação (já com o salto): o programa vai em 900 000 + 700 000 − 1 000 000.
        engine.positionMs = 700_000L
        assertEquals(600_000L, p.programmePositionMs())
    }

    @Test
    fun stalkerSemGravacaoMostraOErroSemTentarSozinho() {
        val p = playback()
        val cu = Catchup(channel, "Programa", 0L, 3_600_000L)
        p.tuneNow(PlaybackRequest(channel, cu, 0L, emptyList(), freshLinks = { FreshLinks(emptyList()) }))
        assertTrue(engine.opened.isEmpty())
        val failure = p.failure!!
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, failure.error.errorCode)
        assertEquals(0, failure.retry)
        time.advance(60_000)
        assertTrue(engine.opened.isEmpty())
    }

    // ---- Lista de reserva ----------------------------------------------------------------------

    private val backupChannel = Channel(uid = 7, key = "b7", num = 7, name = "Um HD", logo = null, categoryId = "g", url = "http://y/7.ts")
    private val backupTs = StreamCandidate("http://y/7.ts", StreamFormat.TS)
    private fun backupStream() = BackupStream(PlaybackRequest(backupChannel, null, 0L, listOf(backupTs)), "Principal", "Reserva")

    @Test
    fun falhaNaPrincipalPassaParaAReservaComOAviso() {
        host.backup = backupStream()
        val p = playback()
        p.tuneNow(live())
        p.onError(engine, ioError)
        p.onError(engine, ioError)
        // Os dois formatos da principal falharam: abre o da reserva, sem cartão de erro.
        assertEquals(listOf(ts, hls, backupTs), engine.opened)
        assertNull(p.failure)
        assertEquals("Reserva", p.onBackup?.backupName)
        assertEquals("k1", p.request?.shownKey)
        p.onState(engine, Player.STATE_READY)
        assertEquals(listOf("Principal>Reserva"), host.backupNotices)
        // O formato da reserva é de outra conta: não se aprende.
        assertTrue(host.learned.isEmpty())
    }

    @Test
    fun oCanalSeguinteVoltaAPrincipal() {
        host.backup = backupStream()
        host.prefs = host.prefs.copy(zapDelayMs = Prefs.ZapDelay.OFF)
        val p = playback()
        p.tuneNow(live(ts))
        p.onError(engine, ioError)
        assertEquals(backupTs, engine.opened.last())
        // O mesmo canal outra vez (ex.: o ecrã reabriu) começa pela principal.
        p.tuneNow(live(ts))
        assertEquals(ts, engine.opened.last())
        assertNull(p.onBackup)
    }

    @Test
    fun aReservaTambemFalhaMostraOErroDaPrincipalComAsDuas() {
        host.backup = backupStream()
        val p = playback()
        p.tuneNow(live(ts))
        p.onError(engine, error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        p.onError(engine, ioError)
        val f = p.failure!!
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, f.error.errorCode)
        assertEquals("Reserva", f.backupFailed)
        assertEquals(1, f.retry)
        assertNull(p.onBackup)
        // A tentativa seguinte começa outra vez pela principal (e volta a passar à reserva se falhar).
        time.advance(5_000)
        assertEquals(listOf(ts, backupTs, ts), engine.opened)
        p.onError(engine, ioError)
        assertEquals(listOf(ts, backupTs, ts, backupTs), engine.opened)
    }

    @Test
    fun semCanalNaReservaMostraOErroNormal() {
        val p = playback()
        p.tuneNow(live(ts))
        p.onError(engine, ioError)
        assertEquals(1, host.backupAsked)
        assertNull(p.failure!!.backupFailed)
        assertEquals(listOf(ts), engine.opened)
    }

    @Test
    fun catchUpESegundoCanalNaoPassamParaAReserva() {
        host.backup = backupStream()
        val cu = Catchup(channel, "P", 0L, 3_600_000L)
        val p = playback()
        p.tuneNow(PlaybackRequest(channel, cu, 0L, listOf(ts)))
        p.onError(engine, ioError)
        assertEquals(0, host.backupAsked)
        val e2 = FakeEngine()
        val second = playback(PlaybackPolicy.SECOND, e2)
        second.tuneNow(live(ts))
        second.onError(e2, ioError)
        assertEquals(0, host.backupAsked)
    }

    @Test
    fun experimentarAgoraDaJaAReservaEOProximoCanalVoltaAPrincipal() {
        host.prefs = host.prefs.copy(zapDelayMs = Prefs.ZapDelay.OFF)
        val p = playback()
        p.tuneNow(live(ts))
        p.onState(engine, Player.STATE_READY)
        p.playBackup(backupStream())
        assertEquals(backupTs, engine.opened.last())
        assertNull(p.onBackup?.mainError)
        val other = Channel(uid = 2, key = "k2", num = 2, name = "Dois", logo = null, categoryId = "g", url = "http://x/2.ts")
        val ts2 = StreamCandidate("http://x/2.ts", StreamFormat.TS)
        p.tuneNow(PlaybackRequest(other, null, 0L, listOf(ts2)))
        assertEquals(ts2, engine.opened.last())
        assertNull(p.onBackup)
    }

    @Test
    fun voltarDoSegundoPlanoNaReservaTentaAPrincipal() {
        host.backup = backupStream()
        val p = playback()
        p.tuneNow(live(ts))
        p.onError(engine, ioError)
        p.stop()
        p.onStart()
        assertEquals(listOf(ts, backupTs, ts), engine.opened)
    }
}
