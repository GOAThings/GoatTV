package cc.slokker.itv.ui.player.goatcore

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import cc.slokker.itv.media.AudioFrameReader
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
) {
    interface Listener {
        fun onFirstFrame(ms: Long)
        fun onTracks(tracks: List<TsTrack>)
        /** Falhou; [unsupported]: um formato que este aparelho (ou este motor) não toca, e outro motor talvez sim. */
        fun onError(why: String, unsupported: Boolean, cause: Throwable?)
        fun onEnded()
        fun onVideoSize(width: Int, height: Int) {}

        /** O que o SPS diz do vídeo (tamanho, cadência, cor/HDR): a primeira vez e quando muda. */
        fun onVideoInfo(info: VideoInfo) {}

        /** O vídeo traz legendas fechadas (CEA-608): passam a poder escolher-se ([CAPTIONS]). */
        fun onCaptionsFound() {}

        /** Legendas a mostrar às [atUs] (o relógio do motor, ver [nowUs]), durante [durationUs] (ou até às seguintes). */
        fun onCues(atUs: Long, cues: List<androidx.media3.common.text.Cue>, durationUs: Long) {}
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

    private class VideoUnit(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)
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
        videoQueue.put(unit)
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

    /** O tempo do relógio agora (µs), ou Long.MIN_VALUE antes de começar: as legendas acertam-se por ele. */
    val nowUs: Long get() = nowPtsUs()

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
                "video=${threads.getOrNull(1)?.state}")
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
                Codec.H264 -> { nal = NalReader(hevc = false); hevc = false; videoMime = MediaFormat.MIMETYPE_VIDEO_AVC }
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
            queueVideo(VideoUnit(unit.data, unit.pts * 100 / 9, unit.keyframe))
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
        val data = avccToAnnexB(unit.data)
        queueVideo(VideoUnit(inBand?.plus(data) ?: data, pts * 100 / 9, unit.keyframe))
    }

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

    private fun audio(pid: Int, sample: TsSample) {
        val state = audioTracks[pid] ?: return
        val reader = state.reader
        for (frame in reader.frames(sample.data, sample.pts, sample.discontinuity)) {
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
                if (info.isHdr && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && needsSdr(info)) {
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
            }
            val c = decoderFor(mime, softwareVideo)
            codec = c
            c.configure(format, surface, null, 0)
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
                    listener.onVideoSize(w, h)
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
        }
    }

    /** Mostra a imagem na hora dela pelo relógio (o som, ou o do sistema); atrasada mais de 50 ms, salta-se. */
    private fun render(c: MediaCodec, index: Int, ptsUs: Long) {
        // A primeira imagem mostra-se logo que está descodificada (como o ExoPlayer): mudar de canal parece mais rápido,
        // e o som e as imagens seguintes acertam-se pelo relógio a partir daí.
        if (!firstFrameReported) {
            stats.framesRendered++
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
            startWallClock(ptsUs)
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
            early = ptsUs - nowPtsUs()
        }
        val late = nowPtsUs() - ptsUs
        if (late > 50_000) {
            stats.framesLate++
            c.releaseOutputBuffer(index, false)
        } else {
            stats.framesRendered++
            c.releaseOutputBuffer(index, System.nanoTime() - late * 1000)
            lastOutputAt = SystemClock.elapsedRealtime()
            lastShownPtsUs = ptsUs
            if (!firstFrameReported) firstFrame()
        }
    }

    /** O tempo do relógio; sem ele ainda, começa o do sistema nesta imagem se não houver som (ou não vier a tempo). */
    private fun clockNow(ptsUs: Long): Long {
        val now = nowPtsUs()
        if (now != Long.MIN_VALUE) return now
        if (audioPid < 0 || audioFailed || audioWaitOver()) {
            startWallClock(ptsUs)
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

    /** O tempo do vídeo agora, pelo som se houver; Long.MIN_VALUE antes de haver relógio. */
    private fun nowPtsUs(): Long {
        val track = audioTrack
        val base = audioBasePtsUs
        if (track != null && base != Long.MIN_VALUE) return audioClock.now(track, base, audioRate)
        if (clockBasePtsUs == Long.MIN_VALUE) return Long.MIN_VALUE
        return clockBasePtsUs + SystemClock.elapsedRealtimeNanos() / 1000 - clockBaseRealtimeUs
    }

    private val audioClock = AudioClock()

    /**
     * O relógio pelo som. O carimbo do AudioTrack (a amostra que está a sair agora, com a hora) é o mais certo, também
     * em passthrough pelo HDMI; antes de o haver, a posição da cabeça. Nos primeiros segundos ambos andam aos saltos
     * (±100 ms) e as imagens saíam aos solavancos: o relógio anda com o tempo real e acerta-se pelo som aos poucos; uma
     * diferença grande (o som começou, recomeçou, ou mudou) acerta-o de vez. [now] não aloca nada: a thread do vídeo,
     * ao alocar no arranque da app, ficava parada à espera do GC.
     */
    private class AudioClock {
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
        fun sample(track: AudioTrack) {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return
            if (!runCatching { track.getTimestamp(ts) }.getOrDefault(false) || ts.framePosition <= 0) return
            synchronized(this) {
                stampTrack = track
                stampFrames = ts.framePosition
                stampNanos = ts.nanoTime
            }
        }

        @Synchronized
        fun now(track: AudioTrack, baseUs: Long, rate: Int): Long {
            // Em pausa o carimbo fica parado no último (com a hora de então): só conta a tocar.
            val playing = track.playState == AudioTrack.PLAYSTATE_PLAYING
            // Um carimbo que falha de vez em quando (nos primeiros segundos é frequente) continua pelo último: a
            // posição da cabeça não desconta a latência da saída (o HDMI, centenas de ms) e o relógio saltava.
            val raw = if (playing && stampTrack === track) {
                baseUs + stampFrames * 1_000_000 / rate + (System.nanoTime() - stampNanos) / 1000
            } else {
                baseUs + (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) * 1_000_000 / rate
            }
            val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
            // Parado (pausa, ou antes de tocar) o som manda.
            if (!playing || ptsUs == Long.MIN_VALUE) return reset(raw, nowUs)
            val predicted = ptsUs + (nowUs - atUs)
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
        }
    }

    // ---- Som ---------------------------------------------------------------------------------

    private fun audioLoop() {
        waitPrevious()
        var first = true
        try {
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
        val mime = mimeOf(f.codec) ?: return true
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
                    track = newTrack(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    if (audioBasePtsUs != Long.MIN_VALUE) audioBasePtsUs = Long.MIN_VALUE
                } else if (o >= 0) {
                    val t = track ?: newTrack(f.sampleRate, f.channels).also { track = it }
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
                    t.write(pcm, 0, pcm.size)
                    audioClock.sample(t)
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
                    val t = track ?: newTrack(dec.sampleRate, dec.channels).also { track = it }
                    if (audioTrack !== t) {
                        if (audioBasePtsUs == Long.MIN_VALUE && !startAudioAt(ptsUs)) continue
                        audioBasePtsUs = ptsUs
                        audioTrack = t
                        t.setVolume(volume)
                        if (!paused) t.play()
                    }
                    t.write(pcm, 0, pcm.size)
                    audioClock.sample(t)
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
            if (audioTrack === track) audioTrack = null
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { dec.release() }
        }
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
                    if (!paused) t.play()
                }
                t.write(u.data, 0, u.data.size)
                audioClock.sample(t)
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
    }

    private fun fail(why: String, unsupported: Boolean = false, cause: Throwable? = null) {
        if (!running) return
        Log.w(TAG, why)
        listener.onError(why, unsupported, cause)
    }

    companion object {
        private const val TAG = "GoatCore"

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
