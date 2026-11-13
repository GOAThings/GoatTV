package cc.slokker.itv.ui.player.goatcore

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import cc.slokker.itv.media.AudioFrameReader
import cc.slokker.itv.media.Codec
import cc.slokker.itv.media.NalReader
import cc.slokker.itv.media.TrackKind
import cc.slokker.itv.media.TsClock
import cc.slokker.itv.media.TsDemuxer
import cc.slokker.itv.media.TsListener
import cc.slokker.itv.media.TsSample
import cc.slokker.itv.media.TsTrack
import cc.slokker.itv.media.VideoStart
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
    private val previous: Thread? = null,
) {
    interface Listener {
        fun onFirstFrame(ms: Long)
        fun onTracks(tracks: List<TsTrack>)
        /** Falhou; [unsupported]: um formato que este aparelho (ou este motor) não toca, e outro motor talvez sim. */
        fun onError(why: String, unsupported: Boolean, cause: Throwable?)
        fun onEnded()
        fun onVideoSize(width: Int, height: Int) {}
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

    private class VideoUnit(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)
    private class AudioUnit(val data: ByteArray, val ptsUs: Long)

    private val videoQueue = LinkedBlockingQueue<VideoUnit>(600)
    private val audioQueue = LinkedBlockingQueue<AudioUnit>(2000)

    @Volatile private var running = true
    private val openedAt = SystemClock.elapsedRealtime()
    private var threads = emptyList<Thread>()

    // Rede (só a thread da rede).
    private var nal: NalReader? = null
    private var hevc = false
    private var videoPid = -1
    private var audioPid = -1
    private var audioReader: AudioFrameReader? = null
    private val videoClock = TsClock()
    private val audioClock = TsClock()
    private val videoStart = VideoStart()

    /** Os conjuntos de parâmetros da última imagem-chave (Annex B), para configurar o descodificador. */
    @Volatile private var csd: List<ByteArray> = emptyList()
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
        val video = Thread({ videoLoop() }, "goatcore-video")
        val audio = Thread({ audioLoop() }, "goatcore-audio")
        threads = listOf(net, video, audio)
        threads.forEach { it.start() }
    }

    @Volatile private var paused = false
    @Volatile private var volume = 1f

    /** Pausa: o som pára (e com ele o relógio) e a imagem espera. A rede continua até as filas encherem. */
    fun setPaused(paused: Boolean) {
        if (paused == this.paused) return
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
    }

    /** O tempo do vídeo a mostrar agora (ms, no tempo seguido do canal), ou -1 antes de começar. */
    val positionMs: Long
        get() {
            val now = nowPtsUs()
            return if (now == Long.MIN_VALUE) -1 else now / 1000
        }

    fun stop() {
        if (running) {
            Log.i(TAG, "stop after ${SystemClock.elapsedRealtime() - openedAt} ms: units=${stats.videoUnits} shown=${stats.framesRendered} " +
                "late=${stats.framesLate} audio=${stats.audioFrames} jumps=${stats.jumps} decoderErrors=${stats.decoderErrors} " +
                "configured=${stats.configured} in=${stats.codecInputs} out=${stats.codecOutputs} formats=${stats.codecFormatChanges} " +
                "video=${threads.getOrNull(1)?.state}")
        }
        running = false
        threads.forEach { it.interrupt() }
        threads.forEach { runCatching { it.join(1500) } }
    }

    // ---- Rede e núcleo partilhado ------------------------------------------------------------

    private fun readLoop(input: () -> InputStream) {
        val demuxer = TsDemuxer(object : TsListener {
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
                }
            }
            out?.close()
            demuxer.flush()
            if (running) listener.onEnded()
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: Exception) {
            fail("network: ${e.message}", cause = e)
        }
    }

    private fun tracksChanged(tracks: List<TsTrack>) {
        Log.i(TAG, "tracks " + tracks.joinToString { "${it.codec}${it.language?.let { l -> "/$l" } ?: ""}" })
        listener.onTracks(tracks)
        val video = tracks.firstOrNull { it.kind == TrackKind.VIDEO }
        if (video != null && video.pid != videoPid) {
            videoPid = video.pid
            when (video.codec) {
                Codec.H264 -> { nal = NalReader(hevc = false); hevc = false }
                Codec.HEVC -> { nal = NalReader(hevc = true); hevc = true }
                else -> fail("unsupported video ${video.codec}", unsupported = true)
            }
        }
        // A primeira faixa de áudio que este aparelho descodifica (um canal com MP2 e AC-3 toca o que puder).
        val audios = tracks.filter { it.kind == TrackKind.AUDIO }
        val audio = audios.firstOrNull { mimeOf(it.codec)?.let(::hasDecoder) == true } ?: audios.firstOrNull()
        if (audio != null && audio.pid != audioPid) {
            audioPid = audio.pid
            audioReader = when (audio.codec) {
                Codec.AAC_ADTS, Codec.AC3, Codec.EAC3, Codec.MPEG_AUDIO -> AudioFrameReader(audio.codec)
                else -> null
            }
        }
    }

    private fun sample(track: TsTrack, sample: TsSample) {
        if (!running) return
        when (track.pid) {
            videoPid -> video(sample)
            audioPid -> audio(sample)
        }
    }

    private fun video(sample: TsSample) {
        val reader = nal ?: return
        val samplePts = videoClock.unwrap(sample.pts)
        if (videoClock.jumped) {
            stats.jumps++
            videoStart.restart()
        }
        if (sample.discontinuity) videoStart.restart()
        // Numa imagem entrelaçada por campos, sai com o segundo campo (os dois juntos, com o tempo do primeiro).
        val unit = reader.next(sample.data, samplePts, samplePts) ?: return
        if (unit.parameterSets.isNotEmpty()) csd = unit.parameterSets.map { annexB(it) }
        if (csd.isEmpty()) return
        val pts = unit.pts
        if (!videoStart.pass(unit.keyframe, pts)) return
        stats.videoUnits++
        videoQueue.put(VideoUnit(avccToAnnexB(unit.data), pts * 100 / 9, unit.keyframe))
    }

    private fun audio(sample: TsSample) {
        val reader = audioReader ?: return
        for (frame in reader.frames(sample.data, sample.pts, sample.discontinuity)) {
            if (audioFormat != reader.format) reader.format?.let { Log.i(TAG, "audio ${it.codec} ${it.sampleRate} Hz ${it.channels} ch") }
            audioFormat = reader.format
            val pts = audioClock.unwrap(frame.pts)
            audioQueue.put(AudioUnit(frame.data, pts * 100 / 9))
        }
    }

    // ---- Vídeo -------------------------------------------------------------------------------

    private fun videoLoop() {
        var codec: MediaCodec? = null
        try {
            // A primeira imagem-chave com os parâmetros: é com ela que se configura.
            var first: VideoUnit
            while (true) {
                first = videoQueue.take()
                if (first.keyframe) break
            }
            previous?.join(2_000)
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, 1920, 1080)
            if (hevc) {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.fold(ByteArray(0)) { a, b -> a + b }))
            } else {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd[0]))
                format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd[1]))
            }
            val c = decoderFor(mime, softwareVideo)
            codec = c
            c.configure(format, surface, null, 0)
            c.start()
            stats.configured = true
            var pending: VideoUnit? = first
            val info = MediaCodec.BufferInfo()
            while (running) {
                // Entrada: uma unidade por vez, sem bloquear a saída.
                val unit = pending ?: videoQueue.poll(5, TimeUnit.MILLISECONDS)
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
            fail("video decoder: ${e.javaClass.simpleName} ${e.message}", unsupported = !stats.configured, cause = e)
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
            firstFrame()
            return
        }
        while (running && paused) Thread.sleep(20)
        var now = clockNow(ptsUs)
        // À espera do som (no máximo meio segundo): a imagem fica no descodificador.
        while (running && now == Long.MIN_VALUE) {
            Thread.sleep(10)
            now = clockNow(ptsUs)
        }
        val early = ptsUs - now
        if (early > 0) Thread.sleep((early / 1000).coerceAtMost(200))
        val late = nowPtsUs() - ptsUs
        if (late > 50_000) {
            stats.framesLate++
            c.releaseOutputBuffer(index, false)
        } else {
            stats.framesRendered++
            c.releaseOutputBuffer(index, true)
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
        if (track != null && audioBasePtsUs != Long.MIN_VALUE) {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return audioBasePtsUs + played * 1_000_000 / audioRate
        }
        if (clockBasePtsUs == Long.MIN_VALUE) return Long.MIN_VALUE
        return clockBasePtsUs + SystemClock.elapsedRealtimeNanos() / 1000 - clockBaseRealtimeUs
    }

    // ---- Som ---------------------------------------------------------------------------------

    private fun audioLoop() {
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val first = audioQueue.take()
            val f = audioFormat ?: return
            val mime = mimeOf(f.codec) ?: return
            previous?.join(2_000)
            val format = MediaFormat.createAudioFormat(mime, f.sampleRate, f.channels)
            if (f.codec == Codec.AAC_ADTS) format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(f.config))
            val c = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                // Sem descodificador para este som no aparelho (o AC-3 em muitos): o vídeo segue com o relógio do sistema.
                // Sem descodificador para este som no aparelho: outro motor (o VLC) talvez o tenha.
                audioFailed = true
                fail("no audio decoder for $mime", unsupported = true)
                return
            }
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var pending: AudioUnit? = first
            while (running) {
                val unit = pending ?: audioQueue.poll(5, TimeUnit.MILLISECONDS)
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
                    track?.release()
                    track = newTrack(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (o >= 0) {
                    val t = track ?: newTrack(f.sampleRate, f.channels).also { track = it }
                    val buf = c.getOutputBuffer(o)!!
                    if (audioBasePtsUs == Long.MIN_VALUE) {
                        audioBasePtsUs = info.presentationTimeUs
                        audioTrack = t
                        t.setVolume(volume)
                        if (!paused) t.play()
                    }
                    val pcm = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(pcm)
                    c.releaseOutputBuffer(o, false)
                    t.write(pcm, 0, pcm.size)
                    stats.audioFrames++
                }
            }
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: Exception) {
            stats.decoderErrors++
            fail("audio decoder: ${e.javaClass.simpleName} ${e.message}", unsupported = true, cause = e)
        } finally {
            audioTrack = null
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
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

        /** O tipo do MediaCodec para o som em [codec], ou null se não houver. */
        fun mimeOf(codec: Codec): String? = when (codec) {
            Codec.AAC_ADTS -> MediaFormat.MIMETYPE_AUDIO_AAC
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
