package cc.slokker.itv.ui.player.goatcore

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.media.Afd
import cc.slokker.itv.media.AudioFrameReader
import cc.slokker.itv.media.AutoCrop
import cc.slokker.itv.media.BlackBars
import cc.slokker.itv.media.CropRect
import cc.slokker.itv.media.AudioLeveler
import cc.slokker.itv.media.OutputLatency
import cc.slokker.itv.media.VoiceDownmix
import cc.slokker.itv.media.Codec
import cc.slokker.itv.media.Mpeg2Reader
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.GoatCoreFfmpegAudio
import cc.slokker.itv.media.NalReader
import cc.slokker.itv.media.TrackKind
import cc.slokker.itv.media.TsClock
import cc.slokker.itv.media.StreamDemuxer
import cc.slokker.itv.media.ClosedCaptions
import cc.slokker.itv.media.CaptionDecoder
import cc.slokker.itv.media.TeletextDecoder
import cc.slokker.itv.media.TeletextPage
import cc.slokker.itv.media.TsListener
import cc.slokker.itv.media.TsSample
import cc.slokker.itv.media.TsTrack
import cc.slokker.itv.media.Deinterlacing
import cc.slokker.itv.media.InterlaceVerdict
import cc.slokker.itv.media.PictureLook
import cc.slokker.itv.media.ToneMapping
import cc.slokker.itv.media.Upscaling
import cc.slokker.itv.media.VideoInfo
import cc.slokker.itv.media.VideoStart
import cc.slokker.itv.media.WebVtt
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * O motor GoatCore no Android (experimental): o stream separa-se no núcleo partilhado (TsDemuxer, NalReader,
 * AudioFrameReader, os mesmos da Apple) e cada faixa vai para o MediaCodec do aparelho. O som (AudioTrack) é o relógio;
 * sem som, o relógio é o do sistema a partir da primeira imagem.
 *
 * Três threads: a da rede (lê e separa), a do vídeo e a do som. Uma instância por canal: para mudar de canal, [stop]
 * e outra nova (como na Apple, nada do canal anterior chega ao seguinte).
 */
class GoatCorePlayer(
    private val surface: Surface,
    private val listener: Listener,
    /** Vídeo por software (Definições → Descodificador; sempre no emulador, onde o de hardware bloqueia em 1080p). */
    private val softwareVideo: Boolean = false,
    /** Só no debug: guarda aqui os primeiros 12 MB do stream (para os analisar no PC). */
    private val capture: java.io.File? = null,
    /**
     * O motor do canal anterior a fechar: espera-se por ele antes de criar os descodificadores (os de 4K, sobretudo,
     * são poucos; criados antes de o anterior os largar, o sistema tira um a meio).
     */
    previous: Thread? = null,
    /** O AC-3/E-AC-3 vai tal como vem para a TV ou o recetor (Definições → Passthrough), se eles o aceitarem. */
    private val passthrough: Boolean = false,
    /**
     * O descodificador deve converter este vídeo para SDR (a TV não aceita o HDR dele, ou "Converter para SDR"); ver
     * HdrType.needsSdr.
     */
    private val needsSdr: (VideoInfo) -> Boolean = { false },
    /** Nivelar o volume ([Prefs.AudioLevel]): só no PCM que sai daqui (em passthrough não se mexe no som). */
    private val audioLevel: String = Prefs.AudioLevel.OFF,
    /** O canal, para começar no ganho com que ele ficou da última vez (ver [levelGains]). */
    private val levelKey: String = "",
    /** Definições → Desentrelaçar (Prefs.Deinterlace): nos canais entrelaçados, a GPU tira o "pente" (ver [VideoGpuStage]). */
    private val deinterlace: String = cc.slokker.itv.data.Prefs.Deinterlace.OFF,
    /** O atraso do som à mão (Prefs.audioDelayMs): + o som mais tarde, − mais cedo. Soma-se ao automático. */
    private val audioDelayMs: Int = 0,
    /**
     * O atraso da saída de som (Bluetooth e outras saídas lentas) acerta-se sozinho (Prefs.audioDelayAuto; ver
     * [RouteLatency]). Desligado, o relógio é o de antes: o carimbo do AudioTrack tal como vem, e nada em passthrough.
     */
    private val audioDelayAuto: Boolean = true,
    /** Vozes mais claras (Prefs.AudioVoices; ver media/VoiceDownmix): só no PCM que sai daqui, como o nivelamento. */
    private val audioVoices: Boolean = false,
    /**
     * A saída de som só leva estéreo (a TV sem recetor, auscultadores)? Pergunta-se ao abrir cada faixa com canal
     * central: só então a mistura para estéreo se faz aqui (um recetor que aceita os canais todos fica com eles).
     */
    private val stereoOutput: () -> Boolean = { true },
    /**
     * Definições → Imagem (o brilho, o contraste, a saturação e a nitidez), lido a cada imagem: com algum fora do 0
     * ao abrir, o vídeo de 8 bits SDR passa pela GPU (ver [VideoGpuStage]), e a partir daí mudam sem reabrir o canal.
     */
    private val picture: () -> PictureLook = { PictureLook() },
    /**
     * Definições → Imagem → Aumentar a imagem (Prefs.Upscale; ver [VideoGpuStage] e media/Upscaling): só na imagem
     * principal (quem cria o player passa "off" nas outras), só no 8 bits SDR mais pequeno do que o ecrã.
     */
    private val upscale: String = Prefs.Upscale.OFF,
    /** O tamanho da superfície do vídeo agora (largura, altura), para saber logo se vale a pena aumentar; null sem ela. */
    private val surfaceSize: () -> IntArray? = { null },
    /**
     * Cortar as barras pretas (Prefs.autoCrop; ver media/AutoCrop): o vídeo de 8 bits SDR passa pela GPU, que mede as
     * barras e mostra só o [crop]; o AFD do canal lê-se de cada imagem. Quem mostra decide o [crop] (o modo Ajustar, o
     * tamanho do ecrã) pelo [Listener.onActiveArea].
     */
    private val autoCrop: Boolean = false,
) {
    interface Listener {
        fun onFirstFrame(ms: Long)
        fun onTracks(tracks: List<TsTrack>)
        /** Falhou; [unsupported]: um formato que este aparelho (ou este motor) não toca, e outro motor talvez sim. */
        fun onError(why: String, unsupported: Boolean, cause: Throwable?)
        fun onEnded()
        /** O tamanho do vídeo e a proporção de cada píxel ([VideoInfo.pixelRatio]). */
        fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {}

        /** O que o SPS diz do vídeo (tamanho, cadência, cor/HDR): a primeira vez e quando muda. */
        fun onVideoInfo(info: VideoInfo) {}

        /** O vídeo traz legendas fechadas (CEA-608): passam a poder escolher-se ([CAPTIONS]). */
        fun onCaptionsFound() {}

        /** Legendas a mostrar às [atUs] (o relógio do motor, ver [nowUs]), durante [durationUs] (ou até às seguintes). */
        fun onCues(atUs: Long, cues: List<androidx.media3.common.text.Cue>, durationUs: Long) {}

        /** Cortar as barras pretas: a parte do quadro com imagem mudou (a imagem toda quando deixa de haver barras). */
        fun onActiveArea(area: CropRect) {}
    }

    /**
     * Cortar as barras pretas: o rectângulo do quadro a mostrar (ver [Listener.onActiveArea]); muda com o canal a dar e
     * vale na imagem seguinte.
     */
    @Volatile var crop: CropRect = CropRect.FULL

    /** Quando mudar o corte (só com [autoCrop]); usa-se sincronizado nele (a thread do vídeo e a da GPU). */
    private val cropper: AutoCrop? = if (autoCrop) AutoCrop() else null

    /** O AFD de cada imagem que entrou no descodificador, pelo tempo dela (só na thread do vídeo). */
    private val afds = java.util.TreeMap<Long, Int>()

    private fun cropChanged(change: AutoCrop.() -> Boolean) {
        val c = cropper ?: return
        val area = synchronized(c) { if (c.change()) c.active else null } ?: return
        Log.i(TAG, "auto crop: picture area ${area.left} ${area.top} ${area.right} ${area.bottom}")
        listener.onActiveArea(area)
    }

    /** Números para o laboratório e para o registo. */
    class Stats {
        @Volatile var videoUnits = 0
        @Volatile var framesRendered = 0
        @Volatile var framesLate = 0
        @Volatile var audioFrames = 0
        @Volatile var decoderErrors = 0
        @Volatile var jumps = 0
        @Volatile var codecInputs = 0
        @Volatile var codecOutputs = 0
        @Volatile var codecFormatChanges = 0
        @Volatile var configured = false
    }

    val stats = Stats()

    /**
     * O fecho do motor anterior: larga-se logo que se esperou por ele (cada motor a guardar o anterior fazia uma cadeia
     * com todos os canais por onde se passou, e a app ficava sem memória ao fim de uns zaps em 4K).
     */
    @Volatile private var previous: Thread? = previous

    private fun waitPrevious() {
        previous?.join(2_000)
        previous = null
    }

    /** [scan]: como foi captada ([SCAN_PROGRESSIVE], [SCAN_BOTTOM_FIRST]; 0 = entrelaçada, o campo de cima primeiro). */
    private class VideoUnit(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean, val scan: Int = SCAN_PROGRESSIVE, val afd: Int = Afd.NONE)
    /** Um bloco de som para o descodificador, com o formato da faixa; [MARK] diz "mudou a faixa". */
    private class AudioUnit(val data: ByteArray, val ptsUs: Long, val format: cc.slokker.itv.media.AudioFormat?)

    /** O que cada faixa de áudio tem dos últimos segundos (para mudar de faixa sem esperar pelo som que vem à frente). */
    private class AudioTrackState(val track: TsTrack) {
        val reader = AudioFrameReader(track.codec)
        val clock = TsClock()
        val recent = ArrayDeque<AudioUnit>()
    }

    private val videoQueue = LinkedBlockingQueue<VideoUnit>(600)

    /**
     * Os bytes de vídeo na fila: a rede lê à frente até [MAX_VIDEO_BYTES] (em 4K, 600 imagens passavam dos 100 MB e a
     * app ficava sem memória a mudar de canal).
     */
    private val videoBytes = java.util.concurrent.atomic.AtomicLong()

    private fun queueVideo(unit: VideoUnit) {
        while (running && videoBytes.get() > MAX_VIDEO_BYTES) Thread.sleep(5)
        videoBytes.addAndGet(unit.data.size.toLong())
        if (unit.ptsUs > lastQueuedPtsUs || lastQueuedPtsUs - unit.ptsUs > 10_000_000) lastQueuedPtsUs = unit.ptsUs
        videoQueue.put(unit)
    }

    @Volatile private var lastQueuedPtsUs = Long.MIN_VALUE

    /** Quanto vídeo já chegou e ainda não se viu (ms): o buffer, para a página Ligação. */
    val bufferedMs: Long
        get() {
            val shown = lastShownPtsUs
            val queued = lastQueuedPtsUs
            if (shown == Long.MIN_VALUE || queued == Long.MIN_VALUE) return 0
            return ((queued - shown) / 1000).coerceIn(0, 60_000)
        }

    private fun takenVideo(unit: VideoUnit?): VideoUnit? {
        if (unit != null) videoBytes.addAndGet(-unit.data.size.toLong())
        return unit
    }
    private val audioQueue = LinkedBlockingQueue<AudioUnit>(2000)

    @Volatile private var running = true
    private val openedAt = SystemClock.elapsedRealtime()
    private var threads = emptyList<Thread>()

    // Rede (só a thread da rede).
    private var nal: NalReader? = null
    private var mpeg2: Mpeg2Reader? = null
    private var hevc = false

    /** O tipo do descodificador de vídeo do canal ("video/avc", "video/hevc", "video/mpeg2"). */
    @Volatile private var videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
    private var videoPid = -1
    private var audioPid = -1
    private val audioTracks = HashMap<Int, AudioTrackState>()

    /** As legendas pedidas (o PID, -1 = desligadas); [NO_CHANGE] = nada pedido. */
    @Volatile private var requestedText = NO_CHANGE
    private var textPid = -1
    private var dvb: androidx.media3.extractor.text.dvb.DvbParser? = null
    private var teletext: TeletextDecoder? = null
    /** A faixa de legendas escolhida é WebVTT (de um HLS). */
    private var webVtt = false

    // As legendas fechadas (CEA-608) que vêm dentro do vídeo: descodificam-se sempre (ao escolher, o ecrã de agora já
    // se sabe), pela ordem em que as imagens se mostram (com imagens B, a de descodificação é outra).
    private val captions = CaptionDecoder()
    private val captionQueue = ArrayList<Pair<Long, ByteArray>>()
    private var captionsFound = false
    private var captionsOn = false
    private var captionLines: List<String> = emptyList()
    private var captionLinesAt = 0L

    /**
     * Os últimos PES de cada faixa de legendas (DVB e teletexto), escolhida ou não: ao escolher uma, o que já passou
     * descodifica-se logo, em vez de só se ver o que chega depois (o motor lê ~20 s à frente da imagem).
     */
    private val recentText = HashMap<Int, ArrayDeque<TsSample>>()
    private var allTracks: List<TsTrack> = emptyList()

    /** Mostra as legendas da faixa [pid] (DVB) ou da página de teletexto ([teletextKey]), ou nenhuma com -1. */
    fun selectText(pid: Int) {
        requestedText = pid
    }

    /** A última imagem mostrada (ou, num canal só de som, o último som escrito): elapsedRealtime, 0 antes da primeira. */
    @Volatile private var lastOutputAt = 0L

    /** Há quanto tempo não sai imagem (ou som, sem vídeo): parado à espera de dados se for muito. 0 antes de começar. */
    val starvedMs: Long get() = lastOutputAt.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it } ?: 0

    /** Já saiu a primeira imagem (ou o primeiro som, sem vídeo). */
    val started: Boolean get() = lastOutputAt > 0

    /** O tempo do relógio agora (µs), ou Long.MIN_VALUE antes de começar: as legendas acertam-se por ele. */
    val nowUs: Long get() = videoNowUs()

    /** A faixa de áudio pedida (o menu ou a língua das Definições); -1 = a escolhida ao abrir. */
    @Volatile private var requestedAudio = -1

    /** A faixa de áudio a tocar (o PID), ou -1. */
    val selectedAudio: Int get() = audioPid
    private val videoClock = TsClock()
    private val videoStart = VideoStart()

    /** Os conjuntos de parâmetros da última imagem-chave (Annex B), para configurar o descodificador. */
    @Volatile private var csd: List<ByteArray> = emptyList()

    /** O que o SPS em uso diz do vídeo (a cor vai para o descodificador ao configurar). */
    @Volatile private var videoInfo: VideoInfo? = null
    @Volatile private var audioFormat: cc.slokker.itv.media.AudioFormat? = null

    // Relógio: o tempo do vídeo que corresponde a [clockBaseRealtimeUs] (ou à cabeça do AudioTrack).
    @Volatile private var clockBasePtsUs = Long.MIN_VALUE
    @Volatile private var clockBaseRealtimeUs = 0L
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioBasePtsUs = Long.MIN_VALUE
    @Volatile private var audioRate = 48000
    @Volatile private var firstFrameReported = false

    fun open(input: () -> InputStream) {
        val net = Thread({ readLoop(input) }, "goatcore-net")
        // Prioridade de imagem e de som (como o ExoPlayer): o trabalho da app no arranque não lhes tira a CPU.
        val video = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            videoLoop()
        }, "goatcore-video")
        val audio = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            audioLoop()
        }, "goatcore-audio")
        threads = listOf(net, video, audio)
        threads.forEach { it.start() }
    }

    @Volatile private var paused = false
    @Volatile private var volume = 1f
    /** A velocidade (catch-up, gravações, atrás do direto): o som PCM acelera no AudioTrack e o relógio com ele. */
    @Volatile private var speed = 1f

    fun setSpeed(speed: Float) {
        if (speed == this.speed || speed <= 0f) return
        Log.i(TAG, "speed $speed (${if (audioTrack != null) "audio clock" else if (bitstreamTrack != null) "passthrough" else "system clock"})")
        // Pelo relógio do sistema: continua de onde está, a contar à velocidade nova.
        val now = nowPtsUs()
        if (now != Long.MIN_VALUE && audioTrack == null) startWallClock(now)
        audioClock.setSpeed(audioTrack, audioRate, speed)
        this.speed = speed
        audioTrack?.let { t -> runCatching { t.playbackParams = playbackParams(speed) } }
        // Em passthrough a TV não acelera o AC-3: fora de 1× o som em bruto cala-se (ver [bitstream]).
        bitstreamTrack?.let { b -> runCatching { if (speed != 1f) { b.pause(); b.flush() } else if (!paused) b.play() } }
    }

    private fun playbackParams(speed: Float) = android.media.PlaybackParams().setSpeed(speed).setPitch(1f)

    /** Pausa: o som pára (e com ele o relógio) e a imagem espera. A rede continua até as filas encherem. */
    fun setPaused(paused: Boolean) {
        if (paused == this.paused) return
        bitstreamTrack?.let { b -> runCatching { if (paused) b.pause() else b.play() } }
        val t = audioTrack
        if (t != null) {
            runCatching { if (paused) t.pause() else t.play() }
        } else if (paused) {
            pausedAtUs = nowPtsUs()
        } else if (pausedAtUs != Long.MIN_VALUE) {
            // Sem som, o relógio do sistema não pára sozinho: recomeça de onde estava.
            startWallClock(pausedAtUs)
            pausedAtUs = Long.MIN_VALUE
        }
        this.paused = paused
    }

    @Volatile private var pausedAtUs = Long.MIN_VALUE

    fun setVolume(volume: Float) {
        this.volume = volume
        audioTrack?.setVolume(volume)
        bitstreamTrack?.setVolume(volume)
    }

    /**
     * Onde vai o que se vê (ms, no tempo seguido do canal), ou -1 antes de começar: a última imagem mostrada, ou o
     * relógio num canal só de som. É por ela que a pausa do direto mede quanto está atrás.
     */
    val positionMs: Long
        get() {
            val shown = lastShownPtsUs
            if (videoPid >= 0 && shown != Long.MIN_VALUE) return shown / 1000
            val now = nowPtsUs()
            return if (now == Long.MIN_VALUE) -1 else now / 1000
        }

    @Volatile private var lastShownPtsUs = Long.MIN_VALUE

    fun stop() {
        if (running) {
            Log.i(TAG, "stop after ${SystemClock.elapsedRealtime() - openedAt} ms: units=${stats.videoUnits} shown=${stats.framesRendered} " +
                "late=${stats.framesLate} audio=${stats.audioFrames} jumps=${stats.jumps} decoderErrors=${stats.decoderErrors} " +
                "configured=${stats.configured} in=${stats.codecInputs} out=${stats.codecOutputs} formats=${stats.codecFormatChanges} " +
                "video=${threads.getOrNull(1)?.state}" +
                nal?.let { " interlaced=${it.interlaced} frames=${it.frames} fieldPairs=${it.fieldPairs} loneFields=${it.loneFields}" }.orEmpty())
        }
        running = false
        previous = null
        threads.forEach { it.interrupt() }
        threads.forEach { runCatching { it.join(1500) } }
        // O que estava à espera sai já da memória (até 24 MB de vídeo).
        videoQueue.clear()
        videoBytes.set(0)
        audioQueue.clear()
        threads = emptyList()
    }

    // ---- Rede e núcleo partilhado ------------------------------------------------------------

    private fun readLoop(input: () -> InputStream) {
        val demuxer = StreamDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) = tracksChanged(tracks)
            override fun onSample(track: TsTrack, sample: TsSample) = sample(track, sample)
        })
        try {
            val out = capture?.let { runCatching { java.io.FileOutputStream(it) }.getOrNull() }
            var captured = 0L
            input().use { stream ->
                val buf = ByteArray(64 * 1024)
                while (running) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (out != null && captured < 12L * 1024 * 1024) {
                        out.write(buf, 0, n)
                        captured += n
                    }
                    demuxer.feed(buf, 0, n)
                    demuxer.unsupported?.let { throw UnsupportedStream(it) }
                }
            }
            out?.close()
            demuxer.flush()
            // O fim do ficheiro não é o fim do que se vê: as filas têm até uns 20 s (o fim de uma gravação também).
            drain()
            if (running) listener.onEnded()
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: UnsupportedStream) {
            fail(e.message ?: "unsupported stream", unsupported = true, cause = e)
        } catch (e: Exception) {
            fail("network: ${e.message}", cause = e)
        }
    }

    /** Espera que as filas saiam (em pausa, espera); sem sair nada durante [DRAIN_STALL_MS] a dar, desiste. */
    private fun drain() {
        var last = lastOutputAt
        var since = SystemClock.elapsedRealtime()
        while (running && (videoQueue.isNotEmpty() || audioQueue.isNotEmpty())) {
            Thread.sleep(100)
            val now = SystemClock.elapsedRealtime()
            if (paused || lastOutputAt != last) {
                last = lastOutputAt
                since = now
            } else if (now - since > DRAIN_STALL_MS) {
                break
            }
        }
        // O que o descodificador e o som ainda têm por mostrar.
        if (running) Thread.sleep(500)
    }

    private fun tracksChanged(tracks: List<TsTrack>) {
        allTracks = tracks
        Log.i(TAG, "tracks " + tracks.joinToString { "${it.codec}${it.language?.let { l -> "/$l" } ?: ""}" })
        listener.onTracks(tracks)
        val video = tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        if (video != null && video.pid != videoPid) {
            videoPid = video.pid
            when (video.codec) {
                Codec.H264 -> { nal = NalReader(hevc = false).apply { pairFields = false }; hevc = false; videoMime = MediaFormat.MIMETYPE_VIDEO_AVC }
                Codec.HEVC -> { nal = NalReader(hevc = true); hevc = true; videoMime = MediaFormat.MIMETYPE_VIDEO_HEVC }
                // O MPEG-2 (canais SD de cabo e satélite): muitas boxes têm descodificador de hardware para ele.
                Codec.MPEG2_VIDEO -> if (hasDecoder(MediaFormat.MIMETYPE_VIDEO_MPEG2)) {
                    mpeg2 = Mpeg2Reader()
                    videoMime = MediaFormat.MIMETYPE_VIDEO_MPEG2
                } else {
                    fail("no video decoder for MPEG-2", unsupported = true)
                }
                else -> fail("unsupported video ${video.codec}", unsupported = true)
            }
        }
        // Lê-se o som de todas as faixas que se sabem ler (para poder mudar de uma para outra a meio).
        val audios = tracks.filter { it.kind == TrackKind.AUDIO && mimeOf(it.codec) != null }
        audioTracks.keys.retainAll(audios.map { it.pid }.toSet())
        for (t in audios) if (t.pid !in audioTracks) audioTracks[t.pid] = AudioTrackState(t)
        // A primeira faixa de áudio que este aparelho descodifica (um canal com MP2 e AC-3 toca o que puder).
        if (audioPid !in audioTracks) {
            audioPid = (audios.firstOrNull { playable(it.codec) } ?: audios.firstOrNull())?.pid ?: -1
        }
    }

    /** Este som toca aqui: há descodificador, ou vai em bruto para a TV (passthrough). */
    private fun playable(codec: Codec): Boolean = Companion.playable(codec, passthrough)

    /** Passa a tocar a faixa de áudio [pid] (o menu, ou a língua preferida). */
    fun selectAudio(pid: Int) {
        requestedAudio = pid
    }

    /** Na thread da rede: a faixa pedida passa a ser a que toca, a começar no som de agora (o que está guardado dela). */
    private fun switchAudio(pid: Int) {
        val state = audioTracks[pid] ?: return
        if (!playable(state.track.codec)) {
            Log.w(TAG, "no decoder for audio ${state.track.codec}, keeping the current track")
            return
        }
        Log.i(TAG, "audio track -> ${state.track.codec}${state.track.language?.let { "/$it" } ?: ""}")
        audioPid = pid
        val now = nowPtsUs()
        audioQueue.clear()
        audioQueue.put(MARK)
        for (u in state.recent) if (now == Long.MIN_VALUE || u.ptsUs >= now - 100_000) audioQueue.put(u)
    }

    private fun sample(track: TsTrack, sample: TsSample) {
        if (!running) return
        val requested = requestedAudio
        if (requested >= 0 && requested != audioPid) {
            requestedAudio = -1
            switchAudio(requested)
        }
        val text = requestedText
        if (text != NO_CHANGE) {
            requestedText = NO_CHANGE
            switchText(text)
        }
        if (track.kind == TrackKind.SUBTITLE) {
            val recent = recentText.getOrPut(track.pid) { ArrayDeque() }
            recent.addLast(sample)
            if (recent.size > RECENT_TEXT) recent.removeFirst()
        }
        when (track.pid) {
            videoPid -> video(sample)
            textPid -> text(sample)
            in audioTracks -> audio(track.pid, sample)
        }
    }

    private fun video(sample: TsSample) {
        val reader = nal
        val mpeg2 = mpeg2
        if (reader == null && mpeg2 == null) return
        val samplePts = videoClock.unwrap(sample.pts)
        if (videoClock.jumped) {
            stats.jumps++
            // As legendas guardadas são do tempo de antes do salto: já não se acertam com a imagem.
            recentText.clear()
            videoStart.restart()
        }
        if (sample.discontinuity) videoStart.restart()
        // Numa imagem entrelaçada por campos, sai com o segundo campo (os dois juntos, com o tempo do primeiro).
        if (mpeg2 != null) {
            captions(sample.data, samplePts, mpeg2 = true)
            // MPEG-2: a imagem vai como vem; o cabeçalho da sequência é o csd-0.
            val unit = mpeg2.next(sample.data, samplePts, samplePts)
            if (unit.parameterSets.isNotEmpty()) csd = unit.parameterSets
            if (csd.isEmpty() || !videoStart.pass(unit.keyframe, unit.pts)) return
            stats.videoUnits++
            interlacedVideo = mpeg2.interlaced
            queueVideo(VideoUnit(unit.data, unit.pts * 100 / 9, unit.keyframe, scanOf(unit), afdOf(unit.data)))
            return
        }
        captions(sample.data, samplePts)
        val unit = reader!!.next(sample.data, samplePts, samplePts) ?: return
        // Os parâmetros mudaram a meio (outra qualidade do HLS, o canal mudou de resolução): o descodificador já está
        // configurado com os antigos, por isso os novos vão dentro desta imagem-chave e ele reconfigura-se sozinho.
        var inBand: ByteArray? = null
        if (unit.parameterSets.isNotEmpty()) {
            val sets = unit.parameterSets.map { annexB(it) }
            val changed = csd.isEmpty() || sets.size != csd.size || sets.indices.any { !sets[it].contentEquals(csd[it]) }
            if (csd.isNotEmpty() && changed) inBand = sets.fold(ByteArray(0)) { a, b -> a + b }
            csd = sets
            if (changed) spsInfo(unit.parameterSets)
        }
        if (csd.isEmpty()) return
        val pts = unit.pts
        if (!videoStart.pass(unit.keyframe, pts)) return
        stats.videoUnits++
        interlacedVideo = reader.interlaced
        val data = avccToAnnexB(unit.data)
        queueVideo(VideoUnit(inBand?.plus(data) ?: data, pts * 100 / 9, unit.keyframe, scanOf(unit), afdOf(data)))
    }

    /** O AFD da imagem [data] (ver media/AutoCrop), só a cortar as barras pretas. */
    private fun afdOf(data: ByteArray): Int = if (cropper != null) Afd.code(data) else Afd.NONE

    /** O canal é entrelaçado (pelo SPS ou pelo cabeçalho da sequência do MPEG-2), na última imagem lida. */
    @Volatile private var interlacedVideo = false

    private fun scanOf(unit: cc.slokker.itv.media.VideoUnit): Int =
        (if (unit.progressive) SCAN_PROGRESSIVE else 0) or (if (unit.bottomFirst) SCAN_BOTTOM_FIRST else 0)

    /**
     * O cc_data desta imagem (608 e 708): por ordem de apresentação (umas imagens de folga chegam para as B) ao
     * descodificador, que escolhe o 608 ou o 708.
     */
    private fun captions(data: ByteArray, pts: Long, mpeg2: Boolean = false) {
        val cc = ClosedCaptions.ccData(data, hevc, mpeg2)
        if (cc.isEmpty() && captionQueue.isEmpty()) return
        captionQueue += pts to cc
        if (captionQueue.size <= CAPTION_REORDER) return
        val next = captionQueue.minBy { it.first }
        captionQueue.remove(next)
        captions.feed(next.second)?.let { lines ->
            captionLines = lines
            captionLinesAt = next.first
            if (captionsOn) showCaptions(next.first, lines)
        }
        if (captions.found && !captionsFound) {
            captionsFound = true
            listener.onCaptionsFound()
        }
    }

    private fun showCaptions(pts: Long, lines: List<String>) {
        if (pts <= 0 && lines.isEmpty()) return
        val cues = if (lines.isEmpty()) emptyList() else listOf(androidx.media3.common.text.Cue.Builder().setText(lines.joinToString("\n")).build())
        listener.onCues(pts * 100 / 9, cues, androidx.media3.common.C.TIME_UNSET)
    }

    /** O SPS dos conjuntos [sets] (sem o código de início) em [VideoInfo], para quem mostra e para o descodificador. */
    private fun spsInfo(sets: List<ByteArray>) {
        val info = if (hevc) {
            sets.firstOrNull { it.isNotEmpty() && ((it[0].toInt() shr 1) and 0x3F) == 33 }?.let { VideoInfo.hevc(it) }
        } else {
            sets.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }?.let { VideoInfo.h264(it) }
        } ?: return
        if (videoInfo?.let { it.width != info.width || it.height != info.height || it.pixelRatio != info.pixelRatio } != false) {
            Log.i(TAG, "sps ${info.width}x${info.height} sar ${info.sarWidth}:${info.sarHeight} ${info.fps} fps")
        }
        videoInfo = info
        listener.onVideoInfo(info)
    }

    /** As legendas passam a ser as da faixa [pid] (-1 = nenhumas); as que estavam no ecrã saem. */
    private fun switchText(pid: Int) {
        captionsOn = pid == CAPTIONS
        webVtt = false
        if (captionsOn) {
            teletext = null
            dvb = null
            textPid = -1
            listener.onCues(Long.MIN_VALUE, emptyList(), 0)
            showCaptions(captionLinesAt, captionLines)
            return
        }
        // Uma página de teletexto: o PID e o número da página juntos.
        if (pid > MAX_PID) {
            val track = allTracks.firstOrNull { it.pid == pid / 0x1000 && it.codec == Codec.TELETEXT }
            textPid = track?.pid ?: -1
            teletext = track?.let { t ->
                val page = pid % 0x1000
                // A língua da página escolhe o alfabeto quando o canal não o diz (cirílico, grego…).
                TeletextDecoder(page, TeletextPage.of(t.config).firstOrNull { it.page == page }?.language ?: t.language)
            }
            dvb = null
            listener.onCues(Long.MIN_VALUE, emptyList(), 0)
            replayText()
            return
        }
        teletext = null
        // As legendas WebVTT de um HLS: cada PES é uma fala com a duração (ver WebVtt).
        allTracks.firstOrNull { it.pid == pid && it.codec == Codec.WEBVTT }?.let { t ->
            textPid = t.pid
            webVtt = true
            dvb = null
            listener.onCues(Long.MIN_VALUE, emptyList(), 0)
            replayText()
            return
        }
        val track = allTracks.firstOrNull { it.pid == pid && it.codec == Codec.DVB_SUBTITLE }
        textPid = track?.pid ?: -1
        // O descodificador do Media3 quer as páginas do PMT (composition e ancillary); sem elas, as do canal todo.
        dvb = track?.let { androidx.media3.extractor.text.dvb.DvbParser(listOf(it.config ?: byteArrayOf(0, 1, 0, 1))) }
        listener.onCues(Long.MIN_VALUE, emptyList(), 0)
        replayText()
    }

    /** As legendas da faixa escolhida que já passaram pelo leitor: as que ainda estão no ecrã aparecem já. */
    private fun replayText() {
        val recent = recentText[textPid] ?: return
        for (s in recent.toList()) text(s)
    }

    /** Um PES de legendas DVB: data_identifier 0x20 e subtitle_stream_id 0x00, e o resto para o descodificador. */
    private fun text(sample: TsSample) {
        teletext?.let { teletext(it, sample); return }
        if (webVtt) {
            val clock = if (videoPid >= 0) videoClock else audioTracks[audioPid]?.clock ?: return
            val pts = clock.map(sample.pts).takeIf { it >= 0 }?.let { it * 100 / 9 } ?: return
            val cue = androidx.media3.common.text.Cue.Builder().setText(WebVtt.text(sample.data)).build()
            listener.onCues(pts, listOf(cue), WebVtt.durationMs(sample.data) * 1000)
            return
        }
        val parser = dvb ?: return
        val d = sample.data
        if (d.size < 3 || d[0].toInt() != 0x20 || d[1].toInt() != 0x00) return
        // No relógio do vídeo (o das legendas sozinho tomava um minuto sem legendas por um salto); sem vídeo, no do som.
        val clock = if (videoPid >= 0) videoClock else audioTracks[audioPid]?.clock ?: return
        val pts = clock.map(sample.pts).takeIf { it >= 0 }?.let { it * 100 / 9 } ?: return
        runCatching {
            // Sem os dois primeiros bytes e com offset 0: o DvbParser toma o offset por bits (ParsableBitArray.setPosition).
            val segments = d.copyOfRange(2, d.size)
            parser.parse(segments, 0, segments.size, androidx.media3.extractor.text.SubtitleParser.OutputOptions.allCues()) { c ->
                val at = pts + (c.startTimeUs.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0)
                listener.onCues(at, c.cues, c.durationUs)
            }
        }.onFailure { Log.w(TAG, "dvb subtitles: ${it.message}") }
    }

    /** Um PES de teletexto: a página escolhida, quando muda, num só bloco em baixo (como numa TV), até à seguinte. */
    private fun teletext(decoder: TeletextDecoder, sample: TsSample) {
        val lines = decoder.feed(sample.data) ?: return
        val clock = if (videoPid >= 0) videoClock else audioTracks[audioPid]?.clock ?: return
        val pts = clock.map(sample.pts).takeIf { it >= 0 }?.let { it * 100 / 9 } ?: return
        val cues = if (lines.isEmpty()) emptyList() else listOf(
            androidx.media3.common.text.Cue.Builder().setText(lines.joinToString("\n")).build(),
        )
        listener.onCues(pts, cues, androidx.media3.common.C.TIME_UNSET)
    }

    /** Os canais que cada faixa de áudio tem de facto (os blocos descodificados, não o que o PMT diz), por PID. */
    private val decodedChannels = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Os canais descodificados da faixa [pid]; 0 enquanto não chegou nenhum bloco dela. */
    fun channelsOf(pid: Int): Int = decodedChannels[pid] ?: 0

    private fun audio(pid: Int, sample: TsSample) {
        val state = audioTracks[pid] ?: return
        val reader = state.reader
        val frames = reader.frames(sample.data, sample.pts, sample.discontinuity)
        // A primeira vez que se sabem os canais da faixa (ou que mudam, ex. de estéreo para 5.1): o nome dela passa a tê-los.
        val channels = reader.format?.channels ?: 0
        if (channels > 0 && decodedChannels.put(pid, channels) != channels) listener.onTracks(allTracks)
        for (frame in frames) {
            val pts = state.clock.unwrap(frame.pts)
            val unit = AudioUnit(frame.data, pts * 100 / 9, reader.format)
            // Os últimos segundos de cada faixa ficam guardados; a que toca vai também para o descodificador.
            state.recent.addLast(unit)
            while (state.recent.size > 1 && unit.ptsUs - state.recent.first().ptsUs > RECENT_AUDIO_US) state.recent.removeFirst()
            if (pid == audioPid) {
                if (audioFormat != reader.format) reader.format?.let { Log.i(TAG, "audio ${it.codec} ${it.sampleRate} Hz ${it.channels} ch") }
                audioFormat = reader.format
                audioQueue.put(unit)
            }
        }
    }

    // ---- Vídeo -------------------------------------------------------------------------------

    private fun videoLoop() {
        var codec: MediaCodec? = null
        try {
            // A primeira imagem-chave com os parâmetros: é com ela que se configura.
            var first: VideoUnit
            while (true) {
                first = takenVideo(videoQueue.take())!!
                if (first.keyframe) break
            }
            waitPrevious()
            val mime = videoMime
            val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
            if (mime == MediaFormat.MIMETYPE_VIDEO_MPEG2) {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.first()))
            } else if (hevc) {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.fold(ByteArray(0)) { a, b -> a + b }))
            } else {
                // Todos os SPS no csd-0 e todos os PPS no csd-1 (as emissões mandam vários).
                val sps = csd.filter { (it[4].toInt() and 0x1F) == 7 }.fold(ByteArray(0)) { a, b -> a + b }
                val pps = csd.filter { (it[4].toInt() and 0x1F) == 8 }.fold(ByteArray(0)) { a, b -> a + b }
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            }
            // A cor do SPS (como o ExoPlayer faz): os descodificadores que não leem o VUI ficam a saber que é HDR.
            videoInfo?.let { info ->
                val standard = androidx.media3.common.ColorInfo.isoColorPrimariesToColorSpace(info.primaries)
                val transfer = androidx.media3.common.ColorInfo.isoTransferCharacteristicsToColorTransfer(info.transfer)
                if (standard > 0) format.setInteger(MediaFormat.KEY_COLOR_STANDARD, standard)
                if (transfer > 0) format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer)
                if (standard > 0) format.setInteger(MediaFormat.KEY_COLOR_RANGE, if (info.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
                // A TV não aceita este HDR (ou pediu-se SDR): o descodificador converte (Android 13+).
                if (info.isHdr && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !forceToneMap && needsSdr(info)) {
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
            }
            val c = decoderFor(mime, softwareVideo)
            codec = c
            deinterlacer = gpuStageFor(c.name)
            c.configure(format, deinterlacer?.input ?: surface, null, 0)
            hdrToSdr(c, format)
            c.start()
            stats.configured = true
            var pending: VideoUnit? = first
            val info = MediaCodec.BufferInfo()
            while (running) {
                // Entrada: uma unidade por vez, sem bloquear a saída (a espera é a da saída; esperar na fila alocava).
                val unit = pending ?: takenVideo(videoQueue.poll())
                if (unit != null) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        b.clear()
                        b.put(unit.data)
                        c.queueInputBuffer(i, 0, unit.data.size, unit.ptsUs, if (unit.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        if (deinterlacer != null) {
                            scans[unit.ptsUs] = unit.scan
                            // Sem saída para estas (descodificador parado, imagens que não sai): não cresce sem fim.
                            if (scans.size > 64) scans.pollFirstEntry()
                        }
                        if (cropper != null) {
                            afds[unit.ptsUs] = unit.afd
                            if (afds.size > 64) afds.pollFirstEntry()
                        }
                        stats.codecInputs++
                        pending = null
                    } else {
                        pending = unit
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    stats.codecOutputs++
                    render(c, o, info.presentationTimeUs)
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    stats.codecFormatChanges++
                    val f = c.outputFormat
                    val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(TAG, "video ${w}x$h ${c.name}")
                    if (videoInfo?.isHdr == true && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        Log.i(TAG, "hdr: decoder output transfer ${f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)} (${MediaFormat.COLOR_TRANSFER_SDR_VIDEO} = SDR)")
                    }
                    deinterlacer?.setSize(w, h)
                    val ratio = mpeg2?.pixelRatio ?: videoInfo?.pixelRatio ?: 1f
                    listener.onVideoSize(w, h, ratio)
                    cropChanged { frame(w, h, ratio) }
                }
            }
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: Exception) {
            stats.decoderErrors++
            // Só não criar ou configurar o descodificador quer dizer formato que o aparelho não toca; depois disso é
            // uma falha como as outras (volta-se a tentar).
            // Sem ter saído uma única imagem, é o formato que este descodificador não faz (HEVC de 10 bits num que só faz
            // 8, um perfil a mais…): outro motor talvez sim (o ExoPlayer tem o FFmpeg).
            fail("video decoder: ${e.javaClass.simpleName} ${e.message}", unsupported = !stats.configured || stats.codecOutputs == 0, cause = e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            // Depois do descodificador: a superfície do ecrã fica livre para o canal seguinte.
            deinterlacer?.let { d ->
                Log.i(TAG, "video gpu: ${d.deinterlaced} deinterlaced, ${d.passedThrough} as is")
                runCatching { d.release() }
            }
            deinterlacer = null
        }
    }

    /** Entre o descodificador e o ecrã, nos canais entrelaçados e com ajustes da imagem (ver [gpuStageFor]). */
    @Volatile private var deinterlacer: VideoGpuStage? = null

    /**
     * Testes: `adb shell setprop debug.goattv.interlaced 1` trata qualquer canal como entrelaçado (o emulador não
     * descodifica canais entrelaçados, e assim vê-se o caminho da GPU num canal progressivo). Só nas versões de testes.
     */
    private val forceInterlaced: Boolean by lazy {
        cc.slokker.itv.BuildConfig.DEBUG && runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.goattv.interlaced")).inputStream.bufferedReader().readText().trim() == "1"
        }.getOrDefault(false)
    }

    /**
     * Testes: `adb shell setprop debug.goattv.tonemap 1` converte qualquer canal HDR para SDR na GPU (sem pedir ao
     * descodificador), e um canal SDR como se fosse PQ (escuro, mas vê-se o caminho no emulador). Só nas versões de testes.
     */
    private val forceToneMap: Boolean by lazy {
        cc.slokker.itv.BuildConfig.DEBUG && runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.goattv.tonemap")).inputStream.bufferedReader().readText().trim() == "1"
        }.getOrDefault(false)
    }

    /**
     * O HDR numa TV que não o mostra (HdrType.needsSdr): o descodificador [c] (já configurado com [format]) converte
     * para SDR quando o Android 13+ lho pede e ele aceita (o formato de entrada fica com o pedido). Senão (Android 12
     * ou mais antigo, como a SHIELD, ou um descodificador que recusa), configura-se de novo para um [VideoGpuStage]
     * que converte (ver media/ToneMapping); se a GPU não o fizer, fica como antes (lavado).
     */
    private fun hdrToSdr(c: MediaCodec, format: MediaFormat) {
        val info = videoInfo ?: return
        val sdr = info.isHdr && needsSdr(info)
        if (!sdr && !forceToneMap) return
        val api33 = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val requested = api33 && format.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
        val converted = requested && runCatching {
            val f = c.inputFormat
            f.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) &&
                f.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        }.getOrDefault(false)
        val mode = ToneMapping.mode(info.transfer, sdr, converted, forceToneMap)
        val why = when {
            forceToneMap -> "forced (debug.goattv.tonemap)"
            converted -> "the decoder ${c.name} converts"
            requested -> "the decoder ${c.name} refused the request"
            else -> "the decoder cannot be asked (Android ${android.os.Build.VERSION.SDK_INT})"
        }
        Log.i(TAG, "hdr: transfer=${info.transfer} to SDR, $why")
        if (mode == ToneMapping.NONE) return
        // A superfície do ecrã fica livre antes de o EGL do estágio se ligar a ela.
        c.reset()
        deinterlacer?.let { runCatching { it.release() } }
        deinterlacer = null
        val stage = runCatching {
            VideoGpuStage(surface, Deinterlacing.NONE, InterlaceVerdict(), pictureLook = picture, toneMap = mode,
                crop = if (cropper != null) ({ crop }) else null, onLuma = if (cropper != null) ::measureBars else null)
        }.onFailure { Log.w(TAG, "hdr: no tone mapping on the GPU (${it.javaClass.simpleName} ${it.message}), the picture stays washed out") }
            .getOrNull()
        if (stage != null) {
            deinterlacer = stage
            if (requested) format.removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
            Log.i(TAG, "hdr: tone mapping ${ToneMapping.name(mode)} on the GPU (BT.2390 to ${ToneMapping.TARGET_PEAK.toInt()} nits, BT.2020 to BT.709)")
        }
        c.configure(format, stage?.input ?: surface, null, 0)
    }

    /** Como foi captada cada imagem que entrou no descodificador, pelo tempo dela (para o [deinterlacer]). */
    private val scans = java.util.TreeMap<Long, Int>()

    /**
     * A passagem pela GPU para este canal e o descodificador [decoder], ou null para o vídeo ir direto ao ecrã (com
     * tudo no padrão, é sempre assim). Só no vídeo de 8 bits SDR, e por uma de duas razões:
     * - desentrelaçar: um canal entrelaçado (ver Deinterlacing.mode), com a definição ligada, e num descodificador que
     *   não o faça já (o da NVIDIA da SHIELD desentrelaça ele próprio). Nos outros, a GPU desentrelaça e mede se é
     *   preciso (InterlaceVerdict); o que se aprende de cada descodificador fica para os canais seguintes;
     * - os ajustes da imagem (Definições → Imagem) com algum fora do 0 (ver [PictureLook.applies]).
     */
    private fun gpuStageFor(decoder: String): VideoGpuStage? {
        val info = videoInfo
        val bitDepth = info?.bitDepth ?: 8
        val hdr = info?.isHdr == true
        var mode = Deinterlacing.mode(deinterlace, interlacedVideo || forceInterlaced, bitDepth, hdr)
        val known = Deinterlacing.decoder(decoder)
        if (mode != Deinterlacing.NONE && known == Deinterlacing.DECODER_DEINTERLACES) {
            Log.i(TAG, "interlaced: $decoder deinterlaces by itself")
            mode = Deinterlacing.NONE
        }
        val look = picture()
        val adjust = look.applies(bitDepth, hdr)
        if (!look.neutral && !adjust) Log.i(TAG, "picture: not applied to this video (bitDepth=$bitDepth hdr=$hdr)")
        val up = upscaleFor(info)
        // Cortar as barras pretas: só no 8 bits SDR (o resto da GPU é GLES2 de 8 bits; o HDR que a TV mostra em HDR fica direto).
        val cropping = cropper != null && bitDepth == 8 && !hdr
        if (cropper != null && !cropping) Log.i(TAG, "auto crop: only on 8-bit SDR or HDR tone mapped on the GPU (bitDepth=$bitDepth hdr=$hdr)")
        if (mode == Deinterlacing.NONE && !adjust && up == Prefs.Upscale.OFF && !cropping) return null
        val verdict = InterlaceVerdict(woven = known == Deinterlacing.DECODER_WOVEN || decoder in wovenDecoders)
        return runCatching {
            VideoGpuStage(surface, mode, verdict, pictureLook = picture, onWoven = { wovenDecoders += decoder }, upscale = up, upscaleMemory = upscaleMemory,
                crop = if (cropping) ({ crop }) else null, onLuma = if (cropping) ::measureBars else null)
        }
            .onSuccess {
                if (mode != Deinterlacing.NONE) Log.i(TAG, "interlaced: deinterlacing ($deinterlace) on the GPU, decoder $decoder woven=${verdict.woven}")
                if (adjust) Log.i(TAG, "picture: adjusting on the GPU ($look)")
            }
            .onFailure { Log.w(TAG, "video gpu: no stage: ${it.message}") }
            .getOrNull()
    }

    /**
     * O aumento a pedir ao [VideoGpuStage] para o vídeo [info] (Prefs.Upscale), ou "off": só no 8 bits SDR, e, se já se
     * sabe o tamanho do vídeo e o da superfície, só quando o vídeo é mais pequeno e esta resolução ainda não mostrou
     * que não aguenta. O modo de cada imagem decide-se no estágio (pelo tamanho que o descodificador dá).
     */
    private fun upscaleFor(info: VideoInfo?): String {
        if (upscale == Prefs.Upscale.OFF) return upscale
        val bitDepth = info?.bitDepth ?: 8
        val hdr = info?.isHdr == true
        val size = surfaceSize()
        val w = info?.width ?: 0
        val h = info?.height ?: 0
        val ceiling = synchronized(upscaleMemory) { upscaleMemory.ceiling(w, h) }
        val mode = if (size != null && w > 0 && h > 0) {
            Upscaling.mode(upscale, w, h, size[0], size[1], bitDepth, hdr, ceiling = ceiling)
        } else {
            Upscaling.mode(upscale, 1, 1, Upscaling.MAX_WIDTH, Upscaling.MAX_HEIGHT, bitDepth, hdr, ceiling = ceiling)
        }
        if (mode == Upscaling.OFF) {
            Log.i(TAG, "upscale: off for this video (${w}x$h bitDepth=$bitDepth hdr=$hdr surface=${size?.joinToString("x") ?: "?"} ceiling=${Upscaling.name(ceiling)})")
            return Prefs.Upscale.OFF
        }
        return upscale
    }

    /** Mostra a imagem na hora dela pelo relógio (o som, ou o do sistema); atrasada mais de 50 ms, salta-se. */
    private fun render(c: MediaCodec, index: Int, ptsUs: Long) {
        if (cropper != null) shownAfd(ptsUs)
        // A primeira imagem mostra-se logo que está descodificada (como o ExoPlayer): mudar de canal parece mais rápido,
        // e o som e as imagens seguintes acertam-se pelo relógio a partir daí.
        if (!firstFrameReported) {
            stats.framesRendered++
            // Sem hora marcada, a imagem vai com a dela (o tempo do vídeo em ns).
            scanned(ptsUs, ptsUs * 1000)
            c.releaseOutputBuffer(index, true)
            lastOutputAt = SystemClock.elapsedRealtime()
            lastShownPtsUs = ptsUs
            firstFrame()
            return
        }
        while (running && paused) Thread.sleep(20)
        // Pelo relógio do sistema (sem som, ou em passthrough), uma paragem da rede não o pára: a primeira imagem
        // depois dela volta a acertá-lo, senão saíam todas atrasadas.
        if (audioTrack == null && clockBasePtsUs != Long.MIN_VALUE && SystemClock.elapsedRealtime() - lastOutputAt > RESYNC_AFTER_MS) {
            startWallClock(ptsUs - videoOffsetUs())
        }
        var now = clockNow(ptsUs)
        // À espera do som (no máximo meio segundo): a imagem fica no descodificador.
        while (running && now == Long.MIN_VALUE) {
            Thread.sleep(10)
            now = clockNow(ptsUs)
        }
        // A imagem vai para o ecrã até [RELEASE_AHEAD_US] antes da hora, com a hora marcada (como o ExoPlayer): uma
        // paragem desta thread depois disso (o GC da app no arranque chega a 150 ms) já não a atrasa.
        var early = ptsUs - now
        while (running && early > RELEASE_AHEAD_US) {
            Thread.sleep(((early - RELEASE_AHEAD_US) / 1000).coerceIn(1, 200))
            early = ptsUs - videoNowUs()
        }
        val late = videoNowUs() - ptsUs
        if (late > 50_000) {
            stats.framesLate++
            c.releaseOutputBuffer(index, false)
        } else {
            stats.framesRendered++
            val at = System.nanoTime() - late * 1000
            scanned(ptsUs, at)
            c.releaseOutputBuffer(index, at)
            lastOutputAt = SystemClock.elapsedRealtime()
            lastShownPtsUs = ptsUs
            if (!firstFrameReported) firstFrame()
        }
    }

    /** O AFD da imagem [ptsUs], que está a sair agora, para o [cropper] (as anteriores já saíram ou não saem). */
    private fun shownAfd(ptsUs: Long) {
        val code = afds[ptsUs] ?: return
        afds.headMap(ptsUs, true).clear()
        cropChanged { afd(code, SystemClock.elapsedRealtime()) }
    }

    /** A grelha do brilho de uma imagem que foi para o ecrã agora (da thread da GPU, ver [VideoGpuStage.onLuma]). */
    private fun measureBars(luma: ByteArray) {
        val m = BlackBars.measure(luma, AutoCrop.GRID_COLS, AutoCrop.GRID_ROWS)
        cropChanged { bars(m, SystemClock.elapsedRealtime()) }
    }

    /** Diz ao [deinterlacer] como foi captada a imagem [ptsUs], que vai sair com a hora [stampNs]. */
    private fun scanned(ptsUs: Long, stampNs: Long) {
        val d = deinterlacer ?: return
        // As que entraram antes desta já saíram (ou não saem): a saída é pela ordem do tempo. Num par de campos que entrou
        // um a um, o tempo da imagem é o do primeiro.
        // Sem registo (não devia acontecer): entrelaçada, o campo de cima primeiro (o habitual).
        val scan = scans[ptsUs] ?: 0
        scans.headMap(ptsUs, true).clear()
        d.frame(stampNs, progressive = scan and SCAN_PROGRESSIVE != 0 && !forceInterlaced, bottomFirst = scan and SCAN_BOTTOM_FIRST != 0)
    }

    /** O tempo do relógio; sem ele ainda, começa o do sistema nesta imagem se não houver som (ou não vier a tempo). */
    private fun clockNow(ptsUs: Long): Long {
        val now = videoNowUs()
        if (now != Long.MIN_VALUE) return now
        if (audioPid < 0 || audioFailed || audioWaitOver()) {
            startWallClock(ptsUs - videoOffsetUs())
            return ptsUs
        }
        return Long.MIN_VALUE
    }

    @Volatile private var audioFailed = false

    /** Sem som ao fim de meio segundo da primeira imagem, o relógio passa a ser o do sistema. */
    private var waitAudioSince = 0L

    private fun audioWaitOver(): Boolean {
        if (waitAudioSince == 0L) waitAudioSince = SystemClock.elapsedRealtime()
        return SystemClock.elapsedRealtime() - waitAudioSince > 500
    }

    private fun firstFrame() {
        firstFrameReported = true
        val ms = SystemClock.elapsedRealtime() - openedAt
        Log.i(TAG, "first frame in $ms ms")
        listener.onFirstFrame(ms)
    }

    private fun startWallClock(ptsUs: Long) {
        clockBaseRealtimeUs = SystemClock.elapsedRealtimeNanos() / 1000
        clockBasePtsUs = ptsUs
    }

    /**
     * Quanto a imagem vai à frente do relógio do som (µs): o atraso do som pedido nas definições ([audioDelayMs]) e,
     * em passthrough, menos a latência da saída (o relógio é o do sistema e não a conta; no PCM já vem descontada no
     * [AudioClock]). Sem som não há nada a acertar.
     */
    private fun videoOffsetUs(): Long {
        if (audioPid < 0 || audioFailed) return 0
        return audioDelayMs * 1000L - (if (bitstreamTrack != null) routeLatency.us else 0L)
    }

    /** O relógio da imagem (e das legendas): o do som ([nowPtsUs]) com o [videoOffsetUs]. */
    private fun videoNowUs(): Long {
        val now = nowPtsUs()
        return if (now == Long.MIN_VALUE) now else now + videoOffsetUs()
    }

    /** O tempo do som agora (o que se está a ouvir), pelo AudioTrack se houver; Long.MIN_VALUE antes de haver relógio. */
    private fun nowPtsUs(): Long {
        val track = audioTrack
        val base = audioBasePtsUs
        if (track != null && base != Long.MIN_VALUE) return audioClock.now(track, base, audioRate)
        if (clockBasePtsUs == Long.MIN_VALUE) return Long.MIN_VALUE
        return clockBasePtsUs + ((SystemClock.elapsedRealtimeNanos() / 1000 - clockBaseRealtimeUs) * speed).toLong()
    }

    private val routeLatency = RouteLatency(audioDelayAuto)
    private val audioClock = AudioClock(routeLatency)

    /**
     * A latência da saída de som em uso ([OutputLatency]), medida na thread do som de meio em meio segundo e quando a
     * saída muda (uns auscultadores Bluetooth que se ligam a meio): pelo carimbo do AudioTrack onde ele é de confiança
     * (PCM), pelo getLatency escondido (como o AudioTrackPositionTracker do Media3) e, numa saída Bluetooth que não
     * diz nada, um valor habitual. Serve onde o relógio não vem do carimbo: antes de ele existir (o início de cada
     * canal) e em passthrough.
     */
    private class RouteLatency(val auto: Boolean) {
        private val latency = OutputLatency()
        /** A latência a descontar agora (µs); 0 com o acerto automático desligado. */
        @Volatile var us = 0L
            private set
        private var track: AudioTrack? = null
        private var measuredAt = 0L
        private var loggedUs = -1L
        /** A saída mudou (avisa o sistema, noutra thread): mede-se de novo na escrita seguinte. */
        @Volatile private var routeChanged = false

        private val onRouting = if (Build.VERSION.SDK_INT >= 24) android.media.AudioRouting.OnRoutingChangedListener { routeChanged = true } else null

        /** Segue as mudanças de saída de [t] (ao criá-lo). */
        fun watch(t: AudioTrack) {
            if (!auto || Build.VERSION.SDK_INT < 24) return
            val listener = onRouting ?: return
            runCatching { t.addOnRoutingChangedListener(listener, android.os.Handler(android.os.Looper.getMainLooper())) }
        }

        /**
         * Só na thread do som, depois de cada escrita em [t]. [stampUs]: a latência que o carimbo dá agora, ou -1
         * (sem carimbo, ou em passthrough, onde ele não é de confiança). [direct]: passthrough.
         */
        fun sample(t: AudioTrack, rate: Int, stampUs: Long, direct: Boolean) {
            if (!auto) return
            val now = SystemClock.elapsedRealtime()
            val changed = routeChanged || track !== t
            if (!changed && now - measuredAt < MEASURE_EVERY_MS) return
            measuredAt = now
            val device = runCatching { t.routedDevice }.getOrNull()
            val key = (device?.type ?: 0) + if (direct) 1000 else 0
            if (changed) {
                routeChanged = false
                track = t
                loggedUs = -1
                // Começa no que esta saída deu da última vez (outro canal): o zap já arranca acertado.
                latency.reset(remembered[key] ?: -1)
            }
            val reported = reportedUs(t, rate)
            us = latency.measure(stampUs, reported, bluetooth(device))
            if (!latency.known) return
            remembered[key] = us
            if (loggedUs < 0 || us - loggedUs > 20_000 || loggedUs - us > 20_000) {
                loggedUs = us
                Log.i(TAG, "audio output ${device?.let { "type ${it.type} ${it.productName}" } ?: "?"}${if (direct) " (passthrough)" else ""}: " +
                    "latency ${us / 1000} ms (timestamp ${if (stampUs < 0) "-" else (stampUs / 1000).toString()}, reported ${if (reported < 0) "-" else (reported / 1000).toString()})")
            }
        }

        private companion object {
            const val MEASURE_EVERY_MS = 500L

            /** O que cada tipo de saída deu da última vez, enquanto a app está aberta (PCM e passthrough à parte). */
            val remembered = java.util.concurrent.ConcurrentHashMap<Int, Long>()

            /** AudioTrack.getLatency (escondido, o mesmo que o Media3 usa); null se o aparelho não o deixa chamar. */
            val getLatency: java.lang.reflect.Method? by lazy { runCatching { AudioTrack::class.java.getMethod("getLatency") }.getOrNull() }

            /** O que a saída declara (µs, sem o buffer da faixa), ou -1. */
            fun reportedUs(t: AudioTrack, rate: Int): Long {
                val ms = runCatching { getLatency?.invoke(t) as? Int }.getOrNull() ?: return -1
                val buffer = runCatching { if (Build.VERSION.SDK_INT >= 24) t.bufferCapacityInFrames else t.bufferSizeInFrames }.getOrDefault(-1)
                return OutputLatency.reported(ms, buffer, rate)
            }

            fun bluetooth(d: android.media.AudioDeviceInfo?): Boolean = when (d?.type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                // Auscultadores, colunas e emissão Bluetooth LE (Android 12/13) e aparelhos auditivos (Android 9).
                26, 27, 30, 23 -> true
                else -> false
            }
        }
    }

    /**
     * O relógio pelo som. O carimbo do AudioTrack (a amostra que está a sair agora, com a hora) é o mais certo, também
     * em passthrough pelo HDMI; antes de o haver, a posição da cabeça. Nos primeiros segundos ambos andam aos saltos
     * (±100 ms) e as imagens saíam aos solavancos: o relógio anda com o tempo real e acerta-se pelo som aos poucos; uma
     * diferença grande (o som começou, recomeçou, ou mudou) acerta-o de vez. [now] não aloca nada: a thread do vídeo,
     * ao alocar no arranque da app, ficava parada à espera do GC.
     *
     * A latência da saída (o HDMI, uns auscultadores Bluetooth) já vem no carimbo: ele diz o que está a ouvir-se, não
     * o que se entregou. Com o acerto automático ([route]) confirma-se que o carimbo faz sentido (como o Media3: um
     * que diga que a saída vai segundos atrás, ou à frente, do que se entregou não se usa) e, enquanto não há carimbo,
     * a posição da cabeça desconta a latência medida (senão a imagem ia adiantada no início de cada canal).
     */
    private class AudioClock(private val route: RouteLatency) {
        private val ts = android.media.AudioTimestamp()
        private var stampTrack: AudioTrack? = null
        private var stampFrames = 0L
        private var stampNanos = 0L
        private var ptsUs = Long.MIN_VALUE
        private var atUs = 0L

        /**
         * Lê o carimbo de [track]; só na thread do som, depois de cada escrita (o getTimestamp aloca, e a thread do
         * vídeo não pode).
         */
        fun sample(track: AudioTrack, rate: Int) {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return
            var stamped = runCatching { track.getTimestamp(ts) }.getOrDefault(false) && ts.framePosition > 0
            var behindUs = -1L
            // Fora de 1× a cabeça e o carimbo não contam o mesmo: não se comparam.
            if (stamped && route.auto && speed == 1f && rate > 0) {
                // Quanto o que se ouve agora vai atrás do que o sistema já levou: a latência da saída. A conta em
                // 32 bits, como a cabeça (que dá a volta ao fim de um dia a 48 kHz).
                val heard = ts.framePosition + (System.nanoTime() - ts.nanoTime) * rate / 1_000_000_000L
                behindUs = (track.playbackHeadPosition - heard.toInt()).toLong() * 1_000_000L / rate
                if (behindUs < -STAMP_SLACK_US || behindUs > STAMP_MAX_US) {
                    stamped = false
                    behindUs = -1
                } else if (behindUs < 0) {
                    behindUs = 0
                }
            }
            if (stamped) {
                synchronized(this) {
                    stampTrack = track
                    stampFrames = ts.framePosition
                    stampNanos = ts.nanoTime
                }
            }
            route.sample(track, rate, behindUs, direct = false)
        }

        /**
         * A velocidade: o AudioTrack conta as amostras que saem (tempo real), e a [speed] cada uma leva [speed] de
         * conteúdo. O tempo do conteúdo conta-se desde a última mudança ([anchorFrames], [anchorUs]).
         */
        private var speed = 1f
        private var anchorTrack: AudioTrack? = null
        private var anchorFrames = 0L
        private var anchorUs = 0L

        /** As amostras que já saíram de [track] agora (pelo carimbo, ou pela cabeça sem ele). */
        private fun framesNow(track: AudioTrack, playing: Boolean, rate: Int): Long =
            if (playing && stampTrack === track) {
                stampFrames + (System.nanoTime() - stampNanos) * rate / 1_000_000_000L
            } else {
                // Sem carimbo (ainda): o que o sistema já levou, menos o que a saída demora a dá-lo a ouvir.
                ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - route.us * rate / 1_000_000L).coerceAtLeast(0)
            }

        /** O tempo do conteúdo (µs desde o início da faixa) ao fim de [frames] amostras tocadas. */
        private fun contentUs(track: AudioTrack, frames: Long, rate: Int): Long {
            if (anchorTrack !== track) {
                anchorTrack = track
                anchorFrames = 0
                anchorUs = 0
            }
            return anchorUs + ((frames - anchorFrames) * 1_000_000.0 / rate * speed).toLong()
        }

        /** Muda a velocidade a partir de agora (o que já tocou fica contado à anterior). */
        @Synchronized
        fun setSpeed(track: AudioTrack?, rate: Int, newSpeed: Float) {
            if (track != null) {
                val frames = framesNow(track, track.playState == AudioTrack.PLAYSTATE_PLAYING, rate)
                anchorUs = contentUs(track, frames, rate)
                anchorFrames = frames
            }
            speed = newSpeed
            ptsUs = Long.MIN_VALUE
        }

        @Synchronized
        fun now(track: AudioTrack, baseUs: Long, rate: Int): Long {
            // Em pausa o carimbo fica parado no último (com a hora de então): só conta a tocar.
            val playing = track.playState == AudioTrack.PLAYSTATE_PLAYING
            // Um carimbo que falha de vez em quando (nos primeiros segundos é frequente) continua pelo último: a
            // posição da cabeça não desconta a latência da saída (o HDMI, centenas de ms) e o relógio saltava.
            val raw = baseUs + contentUs(track, framesNow(track, playing, rate), rate)
            val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
            // Parado (pausa, ou antes de tocar) o som manda.
            if (!playing || ptsUs == Long.MIN_VALUE) return reset(raw, nowUs)
            val predicted = ptsUs + ((nowUs - atUs) * speed).toLong()
            val error = raw - predicted
            if (error > MAX_ERROR_US || error < -MAX_ERROR_US) return reset(raw, nowUs)
            ptsUs = predicted + error / 16
            atUs = nowUs
            return ptsUs
        }

        private fun reset(rawUs: Long, nowUs: Long): Long {
            ptsUs = rawUs
            atUs = nowUs
            return rawUs
        }

        private companion object {
            const val MAX_ERROR_US = 250_000L
            /** O carimbo pode ir um nada à frente da cabeça (ela anda aos blocos); mais do que isto é disparate. */
            const val STAMP_SLACK_US = 100_000L
            /** Como no Media3: um carimbo a mais de 5 s do que se entregou não é de confiança. */
            const val STAMP_MAX_US = 5_000_000L
        }
    }

    // ---- Som ---------------------------------------------------------------------------------

    private fun audioLoop() {
        var first = true
        try {
            // Dentro do try: um stop() logo a seguir ao arranque interrompe esta espera (e fechava a app).
            waitPrevious()
            while (running) {
                // Uma sessão por faixa: muda-se de faixa com uma marca na fila (ver switchAudio).
                val unit = audioQueue.take()
                if (unit === MARK) continue
                val f = unit.format ?: continue
                if (!session(unit, f, first)) return
                first = false
            }
        } catch (e: InterruptedException) {
            // stop()
        } finally {
            audioTrack = null
        }
    }

    /**
     * Toca a faixa de [f] a começar em [firstUnit], até à marca de mudar de faixa (true) ou ao fim (false).
     * [initial]: a primeira faixa do canal (sem descodificador, outro motor talvez o tenha).
     */
    private fun session(firstUnit: AudioUnit, f: cc.slokker.itv.media.AudioFormat, initial: Boolean): Boolean {
        if (passthrough) encodingOf(f.codec)?.takeIf { directSupported(it, f.sampleRate) }?.let { return bitstream(firstUnit, f, it) }
        // O MP3 (as rádios) tem descodificador próprio; o MP2 (a TV) é o "audio/mpeg-L2".
        val mime = (if (f.codec == Codec.MPEG_AUDIO && f.mpegLayer == 3) MediaFormat.MIMETYPE_AUDIO_MPEG else mimeOf(f.codec)) ?: return true
        // Sem descodificador no aparelho (a SHIELD não tem AC-3), o FFmpeg da app (o mesmo do ExoPlayer).
        if (!hasDecoder(mime) && GoatCoreFfmpegAudio.supports(mime)) return ffmpegSession(firstUnit, f, mime, initial)
        val format = MediaFormat.createAudioFormat(mime, f.sampleRate, f.channels)
        if (f.codec == Codec.AAC_ADTS || f.codec == Codec.AAC_LATM) format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(f.config))
        val c = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            if (initial) {
                // Sem descodificador para este som no aparelho: outro motor (o VLC) talvez o tenha.
                audioFailed = true
                fail("no audio decoder for $mime", unsupported = true)
                return false
            }
            Log.w(TAG, "no audio decoder for $mime")
            return true
        }
        var track: AudioTrack? = null
        var pcmRate = f.sampleRate
        var pcmChannels = f.channels
        try {
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var pending: AudioUnit? = firstUnit
            while (running) {
                val unit = pending ?: audioQueue.poll(5, TimeUnit.MILLISECONDS)
                if (unit === MARK) {
                    handOver()
                    return true
                }
                if (unit != null) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        b.clear()
                        b.put(unit.data)
                        c.queueInputBuffer(i, 0, unit.data.size, unit.ptsUs, 0)
                        pending = null
                    } else {
                        pending = unit
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = c.outputFormat
                    if (audioTrack === track) audioTrack = null
                    track?.release()
                    pcmRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    pcmChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    track = newTrack(pcmRate, outChannels(pcmRate, pcmChannels))
                    if (audioBasePtsUs != Long.MIN_VALUE) audioBasePtsUs = Long.MIN_VALUE
                } else if (o >= 0) {
                    val t = track ?: newTrack(pcmRate, outChannels(pcmRate, pcmChannels)).also { track = it }
                    if (audioBasePtsUs == Long.MIN_VALUE && !startAudioAt(info.presentationTimeUs)) {
                        // Mais antigo do que a imagem que está a dar: salta-se.
                        c.releaseOutputBuffer(o, false)
                        continue
                    }
                    if (audioTrack !== t) {
                        audioBasePtsUs = info.presentationTimeUs
                        audioTrack = t
                        t.setVolume(volume)
                        if (!paused) t.play()
                    }
                    val buf = c.getOutputBuffer(o)!!
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(pcm)
                    c.releaseOutputBuffer(o, false)
                    t.write(pcm, 0, processPcm(pcm, pcmRate, pcmChannels))
                    audioClock.sample(t, pcmRate)
                    stats.audioFrames++
                    if (videoPid < 0) lastOutputAt = SystemClock.elapsedRealtime()
                }
            }
            return false
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            stats.decoderErrors++
            fail("audio decoder: ${e.javaClass.simpleName} ${e.message}", unsupported = initial && audioTrack == null, cause = e)
            return false
        } finally {
            rememberLevel()
            if (audioTrack === track) audioTrack = null
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { c.stop() }
            runCatching { c.release() }
        }
    }

    /** Como [session], com o FFmpeg a descodificar. */
    private fun ffmpegSession(firstUnit: AudioUnit, f: cc.slokker.itv.media.AudioFormat, mime: String, initial: Boolean): Boolean {
        val dec = try {
            GoatCoreFfmpegAudio(mime, f.sampleRate, f.channels, f.config)
        } catch (e: Exception) {
            if (initial) {
                audioFailed = true
                fail("ffmpeg audio: ${e.message}", unsupported = true, cause = e)
            }
            return !initial
        }
        Log.i(TAG, "audio ffmpeg ${f.codec} ${f.channels} ch")
        var track: AudioTrack? = null
        try {
            var pending: AudioUnit? = firstUnit
            while (running) {
                val unit = pending ?: audioQueue.poll(5, TimeUnit.MILLISECONDS)
                if (unit === MARK) {
                    handOver()
                    return true
                }
                pending = if (unit != null && !dec.queue(unit.data, unit.ptsUs)) unit else null
                while (running) {
                    val (pcm, ptsUs) = dec.take() ?: break
                    if (pcm.isEmpty()) continue
                    val t = track ?: newTrack(dec.sampleRate, outChannels(dec.sampleRate, dec.channels)).also { track = it }
                    if (audioTrack !== t) {
                        if (audioBasePtsUs == Long.MIN_VALUE && !startAudioAt(ptsUs)) continue
                        audioBasePtsUs = ptsUs
                        audioTrack = t
                        t.setVolume(volume)
                        if (!paused) t.play()
                    }
                    t.write(pcm, 0, processPcm(pcm, dec.sampleRate, dec.channels))
                    audioClock.sample(t, dec.sampleRate)
                    stats.audioFrames++
                    if (videoPid < 0) lastOutputAt = SystemClock.elapsedRealtime()
                }
                if (pending != null) Thread.sleep(2)
            }
            return false
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            stats.decoderErrors++
            fail("ffmpeg audio: ${e.javaClass.simpleName} ${e.message}", unsupported = initial && audioTrack == null, cause = e)
            return false
        } finally {
            rememberLevel()
            if (audioTrack === track) audioTrack = null
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { dec.release() }
        }
    }

    // Nivelar o volume (só na thread do som): um por faixa, refeito se o PCM mudar de frequência ou de canais.
    private var leveler: AudioLeveler? = null
    private var levelerFormat = 0L

    /** O nivelamento para o PCM de [rate] Hz e [channels] canais, ou null (desligado). */
    private fun levelerFor(rate: Int, channels: Int): AudioLeveler? {
        if (audioLevel == Prefs.AudioLevel.OFF || channels <= 0) return null
        val key = rate.toLong() shl 8 or channels.toLong()
        leveler?.let { if (levelerFormat == key) return it }
        rememberLevel()
        levelerFormat = key
        return AudioLeveler(rate, channels, night = audioLevel == Prefs.AudioLevel.NIGHT, startGainDb = levelGains[levelKey] ?: Float.NaN)
            .also { leveler = it }
    }

    // Vozes mais claras (só na thread do som): refeito se o PCM mudar de frequência ou de canais.
    private var voices: VoiceDownmix? = null
    private var voicesFormat = 0L

    /** As vozes mais claras para o PCM de [rate] Hz e [channels] canais, ou null (desligado, ou nada a fazer nele). */
    private fun voicesFor(rate: Int, channels: Int): VoiceDownmix? {
        if (!audioVoices || !VoiceDownmix.applies(channels)) return null
        val key = rate.toLong() shl 8 or channels.toLong()
        if (voicesFormat == key) return voices
        voicesFormat = key
        // Com canal central só se mistura aqui se a saída for estéreo; a que leva os canais todos fica com eles.
        val stereo = channels <= 2 || runCatching { stereoOutput() }.getOrDefault(true)
        Log.i(TAG, if (stereo) "clearer voices: $channels ch -> stereo here" else "clearer voices: the output takes $channels ch, left alone")
        return (if (stereo) VoiceDownmix(rate, channels) else null).also { voices = it }
    }

    /** Os canais com que o PCM de [channels] canais sai daqui (2 quando as vozes mais claras o misturam). */
    private fun outChannels(rate: Int, channels: Int): Int = voicesFor(rate, channels)?.outChannels ?: channels

    /**
     * As vozes mais claras e o nivelamento em [pcm] (16 bits, [channels] canais), no próprio array e numa só passagem:
     * primeiro a mistura (o nivelamento mede o que se vai ouvir, e é o limitador dele que segura os picos). Devolve os
     * bytes a escrever (menos quando se mistura para estéreo).
     */
    private fun processPcm(pcm: ByteArray, rate: Int, channels: Int): Int {
        val v = voicesFor(rate, channels)
        if (v == null) {
            levelerFor(rate, channels)?.processPcm16(pcm, 0, pcm.size)
            return pcm.size
        }
        return v.processPcm16(pcm, 0, pcm.size, levelerFor(rate, v.outChannels))
    }

    /** Guarda o ganho a que o canal chegou (se chegou a ouvir-se), para o próximo zap começar nele. */
    private fun rememberLevel() {
        val l = leveler ?: return
        leveler = null
        if (l.measuredLufs.isNaN()) return
        if (levelGains.size > 500) levelGains.clear()
        levelGains[levelKey] = l.gainDb
    }

    /** Passthrough: os blocos AC-3/E-AC-3 vão tal como vêm para o AudioTrack (a TV ou o recetor descodificam). */
    private fun bitstream(firstUnit: AudioUnit, f: cc.slokker.itv.media.AudioFormat, encoding: Int): Boolean {
        val mask = if (f.channels > 2) AudioFormat.CHANNEL_OUT_5POINT1 else AudioFormat.CHANNEL_OUT_STEREO
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(f.sampleRate).setChannelMask(mask).setEncoding(encoding).build())
            .setBufferSizeInBytes(64 * 1024)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        routeLatency.watch(t)
        audioRate = f.sampleRate
        Log.i(TAG, "audio passthrough ${f.codec} ${f.channels} ch")
        try {
            var unit: AudioUnit? = firstUnit
            var started = false
            while (running) {
                val u = unit ?: audioQueue.poll(5, TimeUnit.MILLISECONDS)
                unit = null
                if (u == null) continue
                if (u === MARK) {
                    handOver()
                    return true
                }
                if (!started) {
                    if (!startAudioAt(u.ptsUs)) continue
                    started = true
                    // Em passthrough a posição que o AudioTrack dá não é de confiança (no AC-3 mono da SHIELD andava a
                    // metade): o relógio é o do sistema a partir daqui, e o som, escrito ao ritmo dele, acompanha.
                    if (clockBasePtsUs == Long.MIN_VALUE || nowPtsUs() < u.ptsUs - 40_000) startWallClock(u.ptsUs)
                    bitstreamTrack = t
                    t.setVolume(volume)
                    if (!paused && speed == 1f) t.play()
                }
                // Fora de 1× o som em bruto não acompanha a imagem: não se escreve.
                if (speed != 1f) continue
                t.write(u.data, 0, u.data.size)
                // A latência que a saída declara (o carimbo aqui não serve): a imagem espera por ela (ver [videoOffsetUs]).
                routeLatency.sample(t, f.sampleRate, -1, direct = true)
                stats.audioFrames++
                if (videoPid < 0) lastOutputAt = SystemClock.elapsedRealtime()
            }
            return false
        } finally {
            if (bitstreamTrack === t) bitstreamTrack = null
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    /** O AudioTrack em passthrough (não é o relógio, ver [bitstream]). */
    @Volatile private var bitstreamTrack: AudioTrack? = null

    /**
     * O som desta faixa pode começar em [ptsUs]? Sem relógio ainda (o início do canal), começa já. Com relógio (a
     * imagem já está a dar, ou mudou-se de faixa), espera pela hora dele; se já passou, não.
     */
    private fun startAudioAt(ptsUs: Long): Boolean {
        var now = nowPtsUs()
        if (now == Long.MIN_VALUE) return true
        if (ptsUs < now - 40_000) return false
        while (running && now != Long.MIN_VALUE && ptsUs - now > 10_000) {
            Thread.sleep(minOf((ptsUs - now) / 1000, 50))
            now = nowPtsUs()
        }
        return true
    }

    /** Muda-se de faixa: até o som novo começar, a imagem segue pelo relógio do sistema a partir de agora. */
    private fun handOver() {
        val now = nowPtsUs()
        val t = audioTrack ?: bitstreamTrack
        audioTrack = null
        bitstreamTrack = null
        audioBasePtsUs = Long.MIN_VALUE
        runCatching { t?.pause() }
        runCatching { t?.flush() }
        if (now != Long.MIN_VALUE) startWallClock(now)
    }

    private fun newTrack(rate: Int, channels: Int): AudioTrack {
        audioRate = rate
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, 16 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { t ->
                routeLatency.watch(t)
                if (speed != 1f) runCatching { t.playbackParams = playbackParams(speed) }
            }
    }

    private fun fail(why: String, unsupported: Boolean = false, cause: Throwable? = null) {
        if (!running) return
        Log.w(TAG, why)
        listener.onError(why, unsupported, cause)
    }

    companion object {
        private const val TAG = "GoatCore"

        /** O ganho do nivelamento de cada canal (pelo endereço), enquanto a app está aberta. */
        private val levelGains = java.util.concurrent.ConcurrentHashMap<String, Float>()

        /** Este som toca neste aparelho: descodificador do sistema, o FFmpeg da app, ou em bruto pelo HDMI. */
        fun playable(codec: Codec, passthrough: Boolean): Boolean {
            val mime = mimeOf(codec) ?: return false
            return hasDecoder(mime) || GoatCoreFfmpegAudio.supports(mime) ||
                (passthrough && encodingOf(codec)?.let { directSupported(it, 48000) } == true)
        }

        /** A codificação do AudioTrack para mandar [codec] em bruto (passthrough), ou null. */
        fun encodingOf(codec: Codec): Int? = when (codec) {
            Codec.AC3 -> AudioFormat.ENCODING_AC3
            Codec.EAC3 -> AudioFormat.ENCODING_E_AC3
            else -> null
        }

        /** A saída de som (HDMI) aceita [encoding] em bruto? */
        fun directSupported(encoding: Int, rate: Int): Boolean = runCatching {
            if (android.os.Build.VERSION.SDK_INT < 29) return@runCatching false
            AudioTrack.isDirectPlaybackSupported(
                AudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build(),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
        }.getOrDefault(false)

        /** O tipo do MediaCodec para o som em [codec], ou null se não houver. */
        fun mimeOf(codec: Codec): String? = when (codec) {
            // O LATM sai do AudioFrameReader como blocos AAC crus com o AudioSpecificConfig: o mesmo descodificador.
            Codec.AAC_ADTS, Codec.AAC_LATM -> MediaFormat.MIMETYPE_AUDIO_AAC
            Codec.AC3 -> MediaFormat.MIMETYPE_AUDIO_AC3
            Codec.EAC3 -> MediaFormat.MIMETYPE_AUDIO_EAC3
            Codec.MPEG_AUDIO -> "audio/mpeg-L2"
            else -> null
        }

        private val decoders = HashMap<String, Boolean>()

        /** Há um descodificador para [mime] neste aparelho? */
        fun hasDecoder(mime: String): Boolean = synchronized(decoders) {
            decoders.getOrPut(mime) {
                android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                    .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            }
        }

        /** Os NAL com o tamanho à frente (o que o NalReader dá) com o código de início (o que o MediaCodec quer). */
        fun avccToAnnexB(avcc: ByteArray): ByteArray {
            val out = ByteArray(avcc.size)
            var at = 0
            while (at + 4 <= avcc.size) {
                val len = ((avcc[at].toInt() and 0xFF) shl 24) or ((avcc[at + 1].toInt() and 0xFF) shl 16) or
                    ((avcc[at + 2].toInt() and 0xFF) shl 8) or (avcc[at + 3].toInt() and 0xFF)
                out[at] = 0; out[at + 1] = 0; out[at + 2] = 0; out[at + 3] = 1
                avcc.copyInto(out, at + 4, at + 4, minOf(at + 4 + len, avcc.size))
                at += 4 + len
            }
            return out
        }

        private fun annexB(nal: ByteArray) = byteArrayOf(0, 0, 0, 1) + nal

        private const val NO_CHANGE = Int.MIN_VALUE

        /** [VideoUnit.scan]: imagem progressiva; entrelaçada com o campo de baixo primeiro. */
        private const val SCAN_PROGRESSIVE = 1
        private const val SCAN_BOTTOM_FIRST = 2

        /** Os descodificadores que já se viu darem os campos juntos (ver InterlaceVerdict), enquanto a app está aberta. */
        private val wovenDecoders: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** O aumento que cada resolução aguentou nesta sessão (ver [VideoGpuStage]); sincroniza-se nele. */
        private val upscaleMemory = cc.slokker.itv.media.UpscaleMemory()
        private const val MAX_PID = 0x1FFF
        private const val CAPTION_REORDER = 6

        /** A faixa das legendas fechadas (CC1) no menu: um número que não é nenhum PID nem página de teletexto. */
        const val CAPTIONS = 0x7FFF_FFF0
        private const val DRAIN_STALL_MS = 3_000L

        /** Os PES de legendas guardados por faixa (o teletexto pode vir em todas as imagens: ~30 s). */
        private const val RECENT_TEXT = 800

        /** A faixa de uma página de teletexto: o PID e a página (0x888…) num só número, acima de qualquer PID. */
        fun teletextKey(pid: Int, page: Int): Int = pid * 0x1000 + page

        /** Sem imagem há mais do que isto, o relógio do sistema volta a acertar-se pela imagem seguinte. */
        private const val RESYNC_AFTER_MS = 500L

        /** Quanto antes da hora uma imagem vai para o ecrã (com a hora marcada). */
        private const val RELEASE_AHEAD_US = 50_000L

        /** O máximo de vídeo à espera de ser descodificado (cerca de 8 s em 4K). */
        private const val MAX_VIDEO_BYTES = 24L * 1024 * 1024

        /** A marca na fila do som: mudou a faixa. */
        private val MARK = AudioUnit(ByteArray(0), -1, null)

        /** Quanto som de cada faixa fica guardado. */
        private const val RECENT_AUDIO_US = 15_000_000L

        /** Um descodificador para [mime]: com [software], o do próprio Android (c2.android / OMX.google), se houver. */
        private fun decoderFor(mime: String, software: Boolean): MediaCodec {
            if (software) {
                val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google."))
                }?.name
                if (name != null) return MediaCodec.createByCodecName(name)
            }
            return MediaCodec.createDecoderByType(mime)
        }
    }
}
