package cc.slokker.itv.media

/**
 * O áudio "empacotado" de um HLS (segmentos .aac/.ac3/.ec3/.mp3 sem TS à volta): uma etiqueta ID3 com o tempo do
 * primeiro bloco (o PRIV `com.apple.streaming.transportStreamTimestamp`, 33 bits a 90 kHz) e os blocos seguidos.
 * Entrega-o como o [TsDemuxer]: uma faixa e um [TsSample] por bloco. Cada [feed] é um segmento inteiro.
 */
class PackedAudioDemuxer(private val listener: TsListener) {
    private var track: TsTrack? = null
    private var reader: AudioFrameReader? = null
    /** O último tempo dito (ID3) e as amostras desde então: o tempo de cada bloco conta-se daí, sem somar arredondamentos. */
    private var anchor = -1L
    private var anchorRate = 0
    private var samples = 0L

    fun feed(segment: ByteArray) {
        var at = 0
        var pts = -1L
        // Pode haver mais do que uma etiqueta à frente.
        while (at + 10 <= segment.size && segment[at] == 'I'.code.toByte() && segment[at + 1] == 'D'.code.toByte() && segment[at + 2] == '3'.code.toByte()) {
            val size = syncsafe(segment, at + 6)
            val end = (at + 10 + size).coerceAtMost(segment.size)
            timestamp(segment, at + 10, end, segment[at + 3].toInt())?.let { pts = it }
            at = end
        }
        if (pts >= 0) {
            anchor = pts
            samples = 0
        }
        val codec = codecAt(segment, at) ?: return
        if (track?.codec != codec) {
            track = TsTrack(1, STREAM_TYPES.getValue(codec), codec, TrackKind.AUDIO, null)
            reader = AudioFrameReader(codec)
            listener.onTracks(listOfNotNull(track))
        }
        val t = track ?: return
        val body = segment.copyOfRange(at, segment.size)
        for (f in reader?.rawFrames(body).orEmpty()) {
            if (f.sampleRate != anchorRate && anchor >= 0) {
                // Mudou a frequência: o ponto de partida passa a ser este bloco.
                if (anchorRate > 0) anchor = (anchor + samples * 90_000L / anchorRate) and MASK_33
                samples = 0
            }
            anchorRate = f.sampleRate
            val clock = if (anchor < 0) -1L else (anchor + samples * 90_000L / f.sampleRate) and MASK_33
            listener.onSample(t, TsSample(clock, clock, body.copyOfRange(f.offset, f.offset + f.size), true, false))
            samples += f.samples
        }
    }

    /** O PRIV com o tempo do TS dentro das frames ID3 de [from, to) (versão [version] da etiqueta). */
    private fun timestamp(d: ByteArray, from: Int, to: Int, version: Int): Long? {
        var i = from
        while (i + 10 <= to) {
            val id = d.decodeToString(i, i + 4)
            if (id[0] == '\u0000') return null
            val size = if (version >= 4) syncsafe(d, i + 4) else u32(d, i + 4)
            val body = i + 10
            val end = (body + size).coerceAtMost(to)
            if (id == "PRIV") {
                val owner = "com.apple.streaming.transportStreamTimestamp"
                val zero = (body until end).firstOrNull { d[it].toInt() == 0 } ?: return null
                if (d.decodeToString(body, zero) == owner && zero + 9 <= end) {
                    var v = 0L
                    for (k in 1..8) v = (v shl 8) or (d[zero + k].toLong() and 0xFF)
                    return v and MASK_33
                }
            }
            i = end
        }
        return null
    }

    private fun codecAt(d: ByteArray, at: Int): Codec? {
        if (at + 6 > d.size) return null
        val a = d[at].toInt() and 0xFF
        val b = d[at + 1].toInt() and 0xFF
        return when {
            a == 0xFF && (b and 0xF6) == 0xF0 -> Codec.AAC_ADTS
            a == 0x0B && b == 0x77 -> if ((d[at + 5].toInt() and 0xFF) shr 3 > 10) Codec.EAC3 else Codec.AC3
            a == 0xFF && (b and 0xE0) == 0xE0 -> Codec.MPEG_AUDIO
            else -> null
        }
    }

    private fun syncsafe(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0x7F) shl 21) or ((d[at + 1].toInt() and 0x7F) shl 14) or ((d[at + 2].toInt() and 0x7F) shl 7) or (d[at + 3].toInt() and 0x7F)

    private fun u32(d: ByteArray, at: Int): Int =
        ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)

    companion object {
        private const val MASK_33 = (1L shl 33) - 1
        private val STREAM_TYPES = mapOf(Codec.AAC_ADTS to 0x0F, Codec.AC3 to 0x81, Codec.EAC3 to 0x87, Codec.MPEG_AUDIO to 0x03)

        /** Parece áudio empacotado (uma etiqueta ID3, ou logo um bloco de áudio)? */
        fun looksLike(d: ByteArray): Boolean {
            if (d.size >= 3 && d[0] == 'I'.code.toByte() && d[1] == 'D'.code.toByte() && d[2] == '3'.code.toByte()) return true
            if (d.size < 2) return false
            val a = d[0].toInt() and 0xFF
            val b = d[1].toInt() and 0xFF
            return (a == 0xFF && (b and 0xE0) == 0xE0) || (a == 0x0B && b == 0x77)
        }
    }
}

/**
 * Um HLS com o áudio em playlists à parte, junto num só MPEG-TS: os segmentos do vídeo ([video]) e os de cada faixa
 * de áudio ([audio], uma por língua, em TS, fMP4 ou empacotado) entram inteiros, cada lado pela ordem dele, e as
 * amostras saem pelo [TsMuxer] intercaladas pelo DTS, só até onde todos os lados já chegaram (o que falta de um espera
 * pelos outros). Do vídeo fica só o que não é áudio; as faixas do áudio ganham outro número para não se confundirem
 * com as do vídeo (num fMP4 todos têm o track_ID 1) e a língua da playlist, se os segmentos não a disserem.
 */
class HlsMerger(out: (ByteArray) -> Unit) {
    private val muxer = TsMuxer(out)
    private var videoTracks: List<TsTrack> = emptyList()
    private val queued = ArrayList<Pair<TsTrack, TsSample>>()
    /** O tempo de cada uma das [queued] (ver [queue]). */
    private val times = ArrayList<Long>()
    private var origin = -1L
    private var videoAt = Long.MIN_VALUE

    private val videoDemuxer = StreamDemuxer(object : TsListener {
        override fun onTracks(tracks: List<TsTrack>) {
            videoTracks = tracks.filter { it.kind != TrackKind.AUDIO }
            publishTracks()
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            if (track.kind == TrackKind.AUDIO) return
            videoAt = maxOf(videoAt, queue(track, sample))
        }
    })

    /** Uma faixa de áudio à parte: o demuxer dela (escolhido pelo primeiro segmento) e até onde já chegou. */
    private inner class AudioSide(val index: Int, val language: String?) : TsListener {
        var tracks: List<TsTrack> = emptyList()
        var at = Long.MIN_VALUE
        var stream: StreamDemuxer? = null
        var packed: PackedAudioDemuxer? = null

        private fun mapped(t: TsTrack) = t.copy(pid = t.pid + AUDIO_PIDS * (index + 1), language = t.language ?: language)

        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks = tracks.filter { it.kind == TrackKind.AUDIO }.map(::mapped)
            publishTracks()
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            if (track.kind != TrackKind.AUDIO) return
            at = maxOf(at, queue(mapped(track), sample))
        }
    }

    private val sides = ArrayList<AudioSide>()

    private fun publishTracks() = muxer.onTracks(videoTracks + sides.flatMap { it.tracks })

    /** Um segmento do vídeo (com o init segment à frente, num fMP4, quando muda). */
    fun video(data: ByteArray) {
        videoDemuxer.feed(data, 0, data.size)
        release()
    }

    /**
     * Um segmento da faixa de áudio [lane] (0 = a principal; com o init segment à frente, num fMP4, quando muda).
     * [language]: a da playlist (ISO 639-2), para quando os segmentos não a dizem.
     */
    fun audio(data: ByteArray, lane: Int = 0, language: String? = null) {
        while (sides.size <= lane) sides += AudioSide(sides.size, if (sides.size == lane) language else null)
        val side = sides[lane]
        if (side.packed == null && side.stream == null) {
            if (PackedAudioDemuxer.looksLike(data)) side.packed = PackedAudioDemuxer(side) else side.stream = StreamDemuxer(side)
        }
        side.packed?.feed(data)
        side.stream?.feed(data, 0, data.size)
        release()
    }

    /** Acabou (um VOD): sai o que estava à espera do outro lado. */
    fun flush() {
        videoDemuxer.flush()
        for (s in sides) {
            s.stream?.flush()
            s.at = Long.MAX_VALUE
        }
        videoAt = Long.MAX_VALUE
        release()
    }

    /** Porque é que um dos lados não se toca (um fMP4 cifrado…), ou null. */
    val unsupported: String? get() = videoDemuxer.unsupported ?: sides.firstNotNullOfOrNull { it.stream?.unsupported }

    /** O tempo da amostra, sempre a crescer (os 33 bits dão a volta ao fim de 26 h). */
    private fun queue(track: TsTrack, sample: TsSample): Long {
        val raw = if (sample.dts >= 0) sample.dts else sample.pts
        if (raw < 0) return Long.MIN_VALUE
        if (origin < 0) origin = raw
        val time = ((raw - origin + HALF_33) and MASK_33) - HALF_33
        queued += track to sample
        times += time
        return time
    }

    private fun release() {
        if (queued.isEmpty()) return
        // Sem um dos lados ainda (ou se um deixou de vir), não se espera para sempre.
        val slowest = (sides.map { it.at } + videoAt).minOrNull() ?: return
        val limit = when {
            queued.size > MAX_QUEUED -> Long.MAX_VALUE
            sides.isEmpty() || slowest == Long.MIN_VALUE -> return
            else -> slowest
        }
        val order = queued.indices.sortedBy { times[it] }
        val keep = ArrayList<Pair<TsTrack, TsSample>>()
        val keepTimes = ArrayList<Long>()
        for (i in order) {
            if (times[i] <= limit) muxer.onSample(queued[i].first, queued[i].second) else {
                keep += queued[i]
                keepTimes += times[i]
            }
        }
        queued.clear()
        queued += keep
        times.clear()
        times += keepTimes
    }

    private companion object {
        const val AUDIO_PIDS = 0x10000
        const val MASK_33 = (1L shl 33) - 1
        const val HALF_33 = 1L shl 32
        /** Uns 20 s de 25 fps com áudio: se um lado não vem, os outros saem na mesma. */
        const val MAX_QUEUED = 2_000
    }
}
