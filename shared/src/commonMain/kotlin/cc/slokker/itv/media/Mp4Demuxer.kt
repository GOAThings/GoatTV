package cc.slokker.itv.media

/**
 * Separa as faixas de um MP4 fragmentado (fMP4/CMAF: o `moov` do segmento de inicialização do HLS e depois pares
 * `moof` + `mdat`) e entrega-as como o [TsDemuxer] entrega as de um TS, para o resto do motor não ver diferença:
 * - o PID de cada faixa é o track_ID;
 * - os tempos ficam a 90 kHz, cortados a 33 bits como os de um TS;
 * - o H.264/HEVC sai em Annex B, com os conjuntos de parâmetros do `avcC`/`hvcC` à frente de cada imagem-chave;
 * - o AAC sai com um cabeçalho ADTS em cada bloco;
 * - o AC-3/E-AC-3/MP3 sai como vem (já trazem a sincronização).
 * As faixas cifradas (`encv`/`enca`, CENC) dão [unsupported] e o que não conhece fica como [Codec.UNKNOWN].
 */
class Mp4Demuxer(private val listener: TsListener) {
    private var pending = ByteArray(256 * 1024)
    private var pendingLen = 0
    /** A posição no stream do primeiro byte de [pending] (os `trun` contam a partir do início do `moof`). */
    private var pendingAt = 0L

    private var tracks: Map<Int, Track> = emptyMap()
    private val queued = ArrayList<Queued>()
    private var restarted = false

    /** Porque é que este stream não se toca (faixas cifradas…), ou null. */
    var unsupported: String? = null
        private set

    /** Junta [len] bytes de [data] a partir de [off]: as caixas que ficarem inteiras são lidas já. */
    fun feed(data: ByteArray, off: Int, len: Int) {
        if (pendingLen + len > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + len))
        data.copyInto(pending, pendingLen, off, off + len)
        pendingLen += len
        var at = 0
        while (pendingLen - at >= 8) {
            var size = u32(pending, at)
            var header = 8
            if (size == 1L) {
                if (pendingLen - at < 16) break
                size = u64(pending, at + 8)
                header = 16
            } else if (size == 0L) {
                break // até ao fim do ficheiro: só se lê no flush()
            }
            if (size < header) {
                // Lixo: não é MP4 a partir daqui; deita-se fora o que há.
                at = pendingLen
                break
            }
            if (pendingLen - at < size) {
                if (size > MAX_BOX) { at = pendingLen; break }
                break
            }
            box(type(pending, at + 4), at, at + header, (at + size).toInt())
            at += size.toInt()
        }
        if (at > 0) {
            pending.copyInto(pending, 0, at, pendingLen)
            pendingLen -= at
            pendingAt += at
        }
    }

    /** O stream acabou: o que estiver numa última caixa sem tamanho (`size` 0) lê-se agora. */
    fun flush() {
        if (pendingLen >= 8 && u32(pending, 0) == 0L) box(type(pending, 4), 0, 8, pendingLen)
        pendingAt += pendingLen
        pendingLen = 0
    }

    private fun box(type: String, start: Int, body: Int, end: Int) {
        when (type) {
            "moov" -> moov(body, end)
            "moof" -> moof(pendingAt + start, body, end)
            "mdat" -> mdat(pendingAt + body, body, end)
        }
    }

    // ---- moov: as faixas -----------------------------------------------------------------------

    private class Track(
        val ts: TsTrack,
        val timescale: Long,
        /** H.264/HEVC: o tamanho do comprimento de cada NAL (1, 2 ou 4) e os conjuntos de parâmetros, já em Annex B. */
        val nalLength: Int,
        val parameterSets: ByteArray,
        /** AAC: o perfil, a frequência e os canais para o cabeçalho ADTS. */
        val adts: IntArray?,
    ) {
        var defaultDuration = 0L
        var defaultSize = 0
        var defaultFlags = 0
    }

    private fun moov(from: Int, to: Int) {
        val found = LinkedHashMap<Int, Track>()
        val trex = HashMap<Int, IntArray>()
        children(from, to) { t, b, e ->
            when (t) {
                "trak" -> trak(b, e)?.let { found[it.ts.pid] = it }
                "mvex" -> children(b, e) { t2, b2, _ ->
                    if (t2 == "trex") trex[s32(b2 + 4)] = intArrayOf(s32(b2 + 12), s32(b2 + 16), s32(b2 + 20))
                }
            }
        }
        for ((id, d) in trex) found[id]?.apply { defaultDuration = d[0].toLong() and 0xFFFFFFFFL; defaultSize = d[1]; defaultFlags = d[2] }
        tracks = found
        queued.clear()
        restarted = true
        listener.onTracks(found.values.map { it.ts })
    }

    private fun trak(from: Int, to: Int): Track? {
        var id = -1
        var timescale = 0L
        var language: String? = null
        var handler = ""
        var entry: Track? = null
        children(from, to) { t, b, e ->
            when (t) {
                "tkhd" -> id = s32(b + if (pending[b].toInt() == 1) 20 else 12)
                "mdia" -> children(b, e) { t2, b2, e2 ->
                    when (t2) {
                        "mdhd" -> {
                            val v1 = pending[b2].toInt() == 1
                            timescale = u32(pending, b2 + if (v1) 20 else 12)
                            val lang = u16(b2 + if (v1) 32 else 20)
                            if (lang != 0 && lang != 0x55C4) {
                                language = CharArray(3) { ((lang shr (10 - 5 * it) and 0x1F) + 0x60).toChar() }.concatToString()
                            }
                        }
                        "hdlr" -> handler = type(pending, b2 + 8)
                        "minf" -> find(b2, e2, "stbl")?.let { stbl -> find(stbl.first, stbl.second, "stsd")?.let { stsd -> entry = sampleEntry(stsd.first, stsd.second, handler) } }
                    }
                }
            }
        }
        val e = entry ?: return null
        if (id < 0 || timescale <= 0) return null
        return Track(e.ts.copy(pid = id, language = language), timescale, e.nalLength, e.parameterSets, e.adts)
    }

    /** A primeira descrição do `stsd` (full box + contagem, depois as entradas). */
    private fun sampleEntry(from: Int, to: Int, handler: String): Track? {
        val at = from + 8
        if (at + 8 > to) return null
        val size = u32(pending, at).toInt()
        val fourcc = type(pending, at + 4)
        val end = minOf(at + size, to)
        val body = at + 8
        fun track(codec: Codec, kind: TrackKind, stream: Int, nal: Int = 0, sets: ByteArray = ByteArray(0), adts: IntArray? = null) =
            Track(TsTrack(0, stream, codec, kind, null), 1, nal, sets, adts)
        return when (fourcc) {
            "avc1", "avc3" -> find(body + 78, end, "avcC")?.let { (b, e) -> avcC(b, e) }?.let { (nal, sets) -> track(Codec.H264, TrackKind.VIDEO, 0x1B, nal, sets) }
            "hvc1", "hev1" -> find(body + 78, end, "hvcC")?.let { (b, e) -> hvcC(b, e) }?.let { (nal, sets) -> track(Codec.HEVC, TrackKind.VIDEO, 0x24, nal, sets) }
            "mp4a" -> {
                val version = u16(body + 8)
                val children = body + 28 + when (version) { 1 -> 16; 2 -> 36; else -> 0 }
                val esds = find(children, end, "esds") ?: return track(Codec.UNKNOWN, TrackKind.AUDIO, 0)
                when (val es = esds(esds.first + 4, esds.second)) {
                    null -> track(Codec.UNKNOWN, TrackKind.AUDIO, 0)
                    else -> if (es.first == 0x40) {
                        audioSpecificConfig(es.second)?.let { track(Codec.AAC_ADTS, TrackKind.AUDIO, 0x0F, adts = it) } ?: track(Codec.UNKNOWN, TrackKind.AUDIO, 0)
                    } else if (es.first == 0x69 || es.first == 0x6B) {
                        track(Codec.MPEG_AUDIO, TrackKind.AUDIO, 0x03)
                    } else {
                        track(Codec.UNKNOWN, TrackKind.AUDIO, 0)
                    }
                }
            }
            "ac-3" -> track(Codec.AC3, TrackKind.AUDIO, 0x81)
            "ec-3" -> track(Codec.EAC3, TrackKind.AUDIO, 0x87)
            ".mp3" -> track(Codec.MPEG_AUDIO, TrackKind.AUDIO, 0x03)
            "encv", "enca" -> {
                unsupported = unsupported ?: "encrypted fMP4 (CENC)"
                track(Codec.UNKNOWN, if (fourcc == "encv") TrackKind.VIDEO else TrackKind.AUDIO, 0)
            }
            else -> track(
                Codec.UNKNOWN,
                when (handler) { "vide" -> TrackKind.VIDEO; "soun" -> TrackKind.AUDIO; "subt", "text", "sbtl" -> TrackKind.SUBTITLE; else -> TrackKind.DATA },
                0,
            )
        }
    }

    /** `avcC`: o tamanho dos comprimentos e o SPS/PPS em Annex B. */
    private fun avcC(from: Int, to: Int): Pair<Int, ByteArray>? {
        if (to - from < 7) return null
        val nal = (pending[from + 4].toInt() and 3) + 1
        val out = ArrayList<Byte>()
        var i = from + 5
        repeat(2) { round ->
            if (i >= to) return@repeat
            val count = if (round == 0) pending[i].toInt() and 0x1F else pending[i].toInt() and 0xFF
            i++
            repeat(count) {
                if (i + 2 > to) return null
                val len = u16(i)
                i += 2
                if (i + len > to) return null
                START.forEach { out += it }
                for (k in i until i + len) out += pending[k]
                i += len
            }
        }
        return nal to out.toByteArray()
    }

    /** `hvcC`: o tamanho dos comprimentos e o VPS/SPS/PPS (e o resto das listas) em Annex B. */
    private fun hvcC(from: Int, to: Int): Pair<Int, ByteArray>? {
        if (to - from < 23) return null
        val nal = (pending[from + 21].toInt() and 3) + 1
        val out = ArrayList<Byte>()
        val arrays = pending[from + 22].toInt() and 0xFF
        var i = from + 23
        repeat(arrays) {
            if (i + 3 > to) return null
            val count = u16(i + 1)
            i += 3
            repeat(count) {
                if (i + 2 > to) return null
                val len = u16(i)
                i += 2
                if (i + len > to) return null
                START.forEach { out += it }
                for (k in i until i + len) out += pending[k]
                i += len
            }
        }
        return nal to out.toByteArray()
    }

    /** `esds` (sem a versão): o objectTypeIndication e o DecoderSpecificInfo (para o AAC, o AudioSpecificConfig). */
    private fun esds(from: Int, to: Int): Pair<Int, ByteArray>? {
        var i = from
        fun descriptor(tag: Int): Int? {
            if (i >= to || (pending[i].toInt() and 0xFF) != tag) return null
            i++
            var size = 0
            repeat(4) {
                if (i >= to) return null
                val b = pending[i++].toInt() and 0xFF
                size = (size shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) return size
            }
            return size
        }
        descriptor(0x03) ?: return null
        val flags = pending.getOrNull(i + 2)?.toInt() ?: return null
        i += 3
        if ((flags and 0x80) != 0) i += 2
        if ((flags and 0x40) != 0) i += 1 + (pending.getOrNull(i)?.toInt()?.and(0xFF) ?: return null)
        if ((flags and 0x20) != 0) i += 2
        descriptor(0x04) ?: return null
        if (i + 13 > to) return null
        val objectType = pending[i].toInt() and 0xFF
        i += 13
        val size = descriptor(0x05) ?: return objectType to ByteArray(0)
        if (i + size > to) return null
        return objectType to pending.copyOfRange(i, i + size)
    }

    // ---- moof + mdat: as amostras --------------------------------------------------------------

    private class Queued(val track: Track, val offset: Long, val size: Int, val dts: Long, val pts: Long, val key: Boolean)

    private fun moof(moofAt: Long, from: Int, to: Int) {
        queued.clear()
        children(from, to) { t, b, e -> if (t == "traf") traf(moofAt, b, e) }
        queued.sortBy { it.offset }
    }

    private fun traf(moofAt: Long, from: Int, to: Int) {
        val tfhd = find(from, to, "tfhd") ?: return
        var i = tfhd.first
        val flags = s32(i) and 0xFFFFFF
        val track = tracks[s32(i + 4)] ?: return
        i += 8
        var base = moofAt
        if ((flags and 0x01) != 0) { base = u64(pending, i); i += 8 }
        if ((flags and 0x02) != 0) i += 4
        var duration = track.defaultDuration
        var size = track.defaultSize
        var sampleFlags = track.defaultFlags
        if ((flags and 0x08) != 0) { duration = u32(pending, i); i += 4 }
        if ((flags and 0x10) != 0) { size = s32(i); i += 4 }
        if ((flags and 0x20) != 0) { sampleFlags = s32(i); i += 4 }
        var dts = find(from, to, "tfdt")?.let { (b, _) -> if (pending[b].toInt() == 1) u64(pending, b + 4) else u32(pending, b + 4) } ?: 0L
        // Sem data_offset, as amostras seguem as do trun anterior (o primeiro: logo a seguir ao moof, no mdat).
        var next = -1L
        children(from, to) { t, b, _ ->
            if (t != "trun") return@children
            var j = b
            val version = pending[j].toInt()
            val f = s32(j) and 0xFFFFFF
            val count = s32(j + 4)
            j += 8
            var offset = if ((f and 0x01) != 0) base + s32(j).also { j += 4 } else if (next >= 0) next else -1L
            val hasFirst = (f and 0x04) != 0
            val first = if (hasFirst) s32(j).also { j += 4 } else 0
            for (n in 0 until count) {
                var d = duration
                var s = size
                var fl = if (n == 0 && hasFirst) first else sampleFlags
                var cts = 0L
                if ((f and 0x100) != 0) { d = u32(pending, j); j += 4 }
                if ((f and 0x200) != 0) { s = s32(j); j += 4 }
                if ((f and 0x400) != 0) { fl = s32(j); j += 4 }
                if ((f and 0x800) != 0) { cts = if (version == 0) u32(pending, j) else s32(j).toLong(); j += 4 }
                if (offset >= 0) {
                    val key = track.ts.kind != TrackKind.VIDEO || (fl and 0x10000) == 0
                    queued += Queued(track, offset, s, ticks(dts, track.timescale), ticks(dts + cts, track.timescale), key)
                    offset += s
                }
                dts += d
            }
            next = offset
        }
    }

    private fun mdat(dataAt: Long, from: Int, to: Int) {
        val end = dataAt + (to - from)
        val discontinuity = restarted
        restarted = false
        val ready = ArrayList<Pair<Queued, ByteArray>>()
        val it = queued.iterator()
        while (it.hasNext()) {
            val q = it.next()
            if (q.offset < dataAt || q.offset + q.size > end) continue
            it.remove()
            val at = from + (q.offset - dataAt).toInt()
            val data = when {
                q.track.nalLength > 0 -> annexB(q.track, at, at + q.size, q.key)
                q.track.adts != null -> adts(q.track.adts, at, q.size)
                else -> pending.copyOfRange(at, at + q.size)
            } ?: continue
            ready += q to data
        }
        // As faixas vêm em blocos (o vídeo do segmento todo, depois o áudio): saem intercaladas pelo DTS, como num TS.
        val origin = ready.firstOrNull()?.first?.dts ?: return
        ready.sortBy { ((it.first.dts - origin + HALF_33) and MASK_33) }
        ready.forEach { (q, data) ->
            listener.onSample(q.track.ts, TsSample(q.pts, q.dts, data, q.key, discontinuity))
        }
    }

    /** Uma imagem com os NAL em comprimentos: em Annex B, com os conjuntos de parâmetros à frente se for chave. */
    private fun annexB(track: Track, from: Int, to: Int, key: Boolean): ByteArray? {
        var size = if (key) track.parameterSets.size else 0
        var i = from
        while (i + track.nalLength <= to) {
            val len = length(i, track.nalLength)
            i += track.nalLength + len
            if (len < 0 || i > to) return null
            size += 4 + len
        }
        val out = ByteArray(size)
        var o = 0
        if (key) { track.parameterSets.copyInto(out); o = track.parameterSets.size }
        i = from
        while (i + track.nalLength <= to) {
            val len = length(i, track.nalLength)
            i += track.nalLength
            START.copyInto(out, o)
            pending.copyInto(out, o + 4, i, i + len)
            o += 4 + len
            i += len
        }
        return out
    }

    private fun length(at: Int, n: Int): Int {
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (pending[at + k].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) -1 else v.toInt()
    }

    /** Um bloco AAC cru com o cabeçalho ADTS de 7 bytes à frente. */
    private fun adts(config: IntArray, from: Int, size: Int): ByteArray {
        val total = size + 7
        val out = ByteArray(total)
        val (profile, rate, channels) = Triple(config[0], config[1], config[2])
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()
        out[2] = ((profile shl 6) or (rate shl 2) or (channels shr 2)).toByte()
        out[3] = (((channels and 3) shl 6) or (total shr 11)).toByte()
        out[4] = (total shr 3).toByte()
        out[5] = (((total and 7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        pending.copyInto(out, 7, from, from + size)
        return out
    }

    // ---- Leitura -------------------------------------------------------------------------------

    /** Cada caixa entre [from] e [to]: o tipo, o início do corpo e o fim. */
    private inline fun children(from: Int, to: Int, each: (String, Int, Int) -> Unit) {
        var i = from
        while (i + 8 <= to) {
            var size = u32(pending, i)
            var header = 8
            if (size == 1L) { size = u64(pending, i + 8); header = 16 } else if (size == 0L) size = (to - i).toLong()
            if (size < header || i + size > to) return
            each(type(pending, i + 4), i + header, (i + size).toInt())
            i += size.toInt()
        }
    }

    private fun find(from: Int, to: Int, type: String): Pair<Int, Int>? {
        children(from, to) { t, b, e -> if (t == type) return b to e }
        return null
    }

    private fun u16(at: Int): Int = ((pending[at].toInt() and 0xFF) shl 8) or (pending[at + 1].toInt() and 0xFF)

    private fun s32(at: Int): Int = u32(pending, at).toInt()

    companion object {
        private val START = byteArrayOf(0, 0, 0, 1)
        private const val MAX_BOX = 64L * 1024 * 1024
        private const val MASK_33 = 0x1FFFFFFFFL
        private const val HALF_33 = 1L shl 32

        /** Parece um MP4 (a primeira caixa é uma das que abrem um fMP4)? */
        fun looksLike(d: ByteArray, len: Int): Boolean =
            len >= 8 && type(d, 4) in setOf("ftyp", "styp", "moov", "moof", "sidx", "free", "emsg", "prft")

        /** [v] em [timescale] passado a 90 kHz e cortado a 33 bits (sem passar do Long, mesmo a 10 MHz e anos de tempo). */
        internal fun ticks(v: Long, timescale: Long): Long =
            ((v / timescale) * 90_000 + (v % timescale) * 90_000 / timescale) and MASK_33

        /** O AudioSpecificConfig em [perfil ADTS, índice da frequência, canais], ou null se não der para ADTS. */
        internal fun audioSpecificConfig(c: ByteArray): IntArray? {
            if (c.size < 2) return null
            var bit = 0
            fun bits(n: Int): Int {
                var v = 0
                repeat(n) {
                    if (bit / 8 >= c.size) return -1
                    v = (v shl 1) or ((c[bit / 8].toInt() shr (7 - bit % 8)) and 1)
                    bit++
                }
                return v
            }
            var objectType = bits(5)
            if (objectType == 31) objectType = 32 + bits(6)
            var rate = bits(4)
            if (rate == 15) return null
            val channels = bits(4)
            // HE-AAC com sinalização explícita: o ADTS leva o núcleo (AAC LC à frequência de base); o SBR acha-se sozinho.
            if (objectType == 5 || objectType == 29) {
                val extension = bits(4)
                if (extension == 15) bits(24)
                objectType = bits(5)
            }
            if (objectType !in 1..4 || channels !in 1..7 || rate < 0) return null
            return intArrayOf(objectType - 1, rate, channels)
        }

        private fun type(d: ByteArray, at: Int): String = CharArray(4) { (d[at + it].toInt() and 0xFF).toChar() }.concatToString()

        private fun u32(d: ByteArray, at: Int): Long =
            ((d[at].toLong() and 0xFF) shl 24) or ((d[at + 1].toLong() and 0xFF) shl 16) or ((d[at + 2].toLong() and 0xFF) shl 8) or (d[at + 3].toLong() and 0xFF)

        private fun u64(d: ByteArray, at: Int): Long = (u32(d, at) shl 32) or u32(d, at + 4)
    }
}
