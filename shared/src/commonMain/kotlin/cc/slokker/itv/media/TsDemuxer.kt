package cc.slokker.itv.media

/** O formato de uma faixa de um TS, pelo stream_type do PMT e pelos descritores. */
enum class Codec { H264, HEVC, MPEG2_VIDEO, AAC_ADTS, AAC_LATM, MPEG_AUDIO, AC3, EAC3, DVB_SUBTITLE, TELETEXT, ID3, UNKNOWN }

enum class TrackKind { VIDEO, AUDIO, SUBTITLE, DATA }

/**
 * Uma faixa do programa: o PID, o formato e a língua que o PMT diz (ISO 639-2, como vem: "por", "eng"…), se disser.
 * [config]: nas legendas DVB, as páginas (composition e ancillary, 2 bytes cada) que o descodificador precisa.
 */
data class TsTrack(
    val pid: Int,
    val streamType: Int,
    val codec: Codec,
    val kind: TrackKind,
    val language: String?,
    val config: ByteArray? = null,
)

/**
 * Uma unidade de uma faixa (um PES inteiro: uma imagem, um ou mais blocos de áudio, uma legenda). [pts] e [dts] no
 * relógio do TS (90 kHz, 33 bits, como vêm), ou -1 sem eles. [randomAccess]: o TS marca que se pode começar aqui.
 * [discontinuity]: faltaram pacotes antes desta (ou o fornecedor marcou um salto no relógio).
 */
class TsSample(val pts: Long, val dts: Long, val data: ByteArray, val randomAccess: Boolean, val discontinuity: Boolean)

/** Quem recebe o que o [TsDemuxer] tira do stream (na thread que o alimenta). */
interface TsListener {
    /** As faixas do programa: a primeira vez, e sempre que o PMT muda. */
    fun onTracks(tracks: List<TsTrack>)

    fun onSample(track: TsTrack, sample: TsSample)
}

/**
 * Separa as faixas de um MPEG-TS (o que os canais IPTV mandam em direto e dentro dos segmentos HLS): encontra os
 * pacotes de 188 bytes, lê o PAT e o PMT do primeiro programa e junta os pacotes de cada faixa em PES. Serve para
 * qualquer formato: o que não conhece fica como [Codec.UNKNOWN] e passa na mesma. Não decide nada sobre os tempos
 * (saltos, voltas aos 33 bits): isso é de quem toca.
 */
class TsDemuxer(private val listener: TsListener) {
    private var pending = ByteArray(PACKET * 64)
    private var pendingLen = 0
    private var synced = false

    private val sections = HashMap<Int, Section>()
    private var pmtPid = -1
    private var pmtVersion = -1
    private var tracks: Map<Int, TsTrack> = emptyMap()
    private val pes = HashMap<Int, Pes>()

    /** O último continuity_counter de cada faixa, e as faixas em que faltaram pacotes desde o último PES. */
    private val counters = HashMap<Int, Int>()
    private val gaps = HashSet<Int>()

    /** Junta [len] bytes de [data] a partir de [off]: o que formar pacotes inteiros é lido já. */
    fun feed(data: ByteArray, off: Int, len: Int) {
        if (pendingLen + len > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + len))
        data.copyInto(pending, pendingLen, off, off + len)
        pendingLen += len
        val p = pending
        var i = 0
        while (true) {
            if (!synced) {
                // Um 0x47 com mais dois à distância de um pacote: é o início de um pacote.
                while (i + PACKET * 2 < pendingLen) {
                    if (p[i] == SYNC && p[i + PACKET] == SYNC && p[i + PACKET * 2] == SYNC) {
                        synced = true
                        break
                    }
                    i++
                }
                if (!synced) break
            }
            if (i + PACKET > pendingLen) break
            if (p[i] != SYNC) {
                synced = false
                // Perdeu-se o passo: o que estava a meio já não se pode juntar.
                for ((pid, unit) in pes) {
                    unit.broken = true
                    gaps += pid
                }
                continue
            }
            packet(p, i)
            i += PACKET
        }
        if (i > 0) {
            p.copyInto(p, 0, i, pendingLen)
            pendingLen -= i
        }
    }

    /**
     * O fim do stream (ou de um segmento HLS): os últimos pacotes leem-se mesmo sem os três seguidos que o [feed] pede
     * para acertar o passo, e os PES que estavam abertos saem como estão.
     */
    fun flush() {
        val p = pending
        var i = 0
        while (i + PACKET <= pendingLen) {
            if (p[i] == SYNC && (i + PACKET == pendingLen || p[i + PACKET] == SYNC || (synced && i + PACKET * 2 > pendingLen))) {
                packet(p, i)
                i += PACKET
            } else {
                i++
            }
        }
        pendingLen = 0
        for ((pid, unit) in pes) emit(pid, unit)
        pes.clear()
    }

    /** Outra ligação, outro stream: esquece tudo, incluindo as faixas (o PMT volta a ser lido). */
    fun reset() {
        pendingLen = 0
        synced = false
        sections.clear()
        pmtPid = -1
        pmtVersion = -1
        tracks = emptyMap()
        pes.clear()
        counters.clear()
        gaps.clear()
    }

    private fun packet(p: ByteArray, i: Int) {
        if (p[i + 1].toInt() and 0x80 != 0) return // transport_error_indicator: o pacote veio estragado.
        val start = p[i + 1].toInt() and 0x40 != 0
        val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
        if (pid == NULL_PID) return
        val control = (p[i + 3].toInt() shr 4) and 3
        val counter = p[i + 3].toInt() and 0x0F
        var payload = i + 4
        var randomAccess = false
        var marked = false
        if (control == 2 || control == 3) {
            val length = p[i + 4].toInt() and 0xFF
            if (length > 0 && length <= 183) {
                val flags = p[i + 5].toInt() and 0xFF
                marked = flags and 0x80 != 0
                randomAccess = flags and 0x40 != 0
            }
            payload = i + 5 + length
        }
        if (control == 0 || control == 2 || payload >= i + PACKET) return
        val end = i + PACKET
        when {
            pid == PAT_PID -> section(pid, p, payload, end, start)
            pid == pmtPid -> section(pid, p, payload, end, start)
            else -> {
                val track = tracks[pid] ?: return
                media(track, p, payload, end, start, counter, randomAccess, marked)
            }
        }
    }

    // ---- PES --------------------------------------------------------------------------------

    private class Pes {
        var data = ByteArray(4096)
        var len = 0
        var randomAccess = false
        var discontinuity = false
        var broken = false
        /** O tamanho que o cabeçalho do PES diz (0 = até ao próximo, como no vídeo). */
        var expected = 0

        fun add(p: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n))
            p.copyInto(data, len, from, to)
            len += n
        }
    }

    private fun media(track: TsTrack, p: ByteArray, from: Int, to: Int, start: Boolean, counter: Int, randomAccess: Boolean, marked: Boolean) {
        val pid = track.pid
        val last = counters[pid]
        if (last == counter && !start) return // Pacote repetido (é permitido uma vez).
        counters[pid] = counter
        var unit = pes[pid]
        if (last != null && counter != (last + 1) and 0x0F) {
            unit?.broken = true
            gaps += pid
        }
        if (start) {
            if (unit != null) emit(pid, unit)
            val next = Pes()
            next.randomAccess = randomAccess
            next.discontinuity = marked || gaps.remove(pid)
            next.add(p, from, to)
            if (next.len >= 6 && next.data[0].toInt() == 0 && next.data[1].toInt() == 0 && next.data[2].toInt() == 1) {
                val length = ((next.data[4].toInt() and 0xFF) shl 8) or (next.data[5].toInt() and 0xFF)
                next.expected = if (length == 0) 0 else length + 6
            }
            pes[pid] = next
            unit = next
        } else {
            if (unit == null) return // A meio de um PES de que não se viu o início.
            if (!unit.broken) unit.add(p, from, to)
        }
        // Com o tamanho dito no cabeçalho, sai logo que está inteiro (o áudio não espera pelo seguinte).
        if (unit.expected > 0 && unit.len >= unit.expected) {
            emit(pid, unit)
            pes.remove(pid)
        }
    }

    private fun emit(pid: Int, unit: Pes) {
        val track = tracks[pid] ?: return
        // Faltaram pacotes no meio: não sai (o seguinte desta faixa vem com discontinuity).
        if (unit.broken) return
        val d = unit.data
        val n = if (unit.expected > 0) minOf(unit.len, unit.expected) else unit.len
        if (n < 9 || d[0].toInt() != 0 || d[1].toInt() != 0 || d[2].toInt() != 1) return
        val streamId = d[3].toInt() and 0xFF
        // Estes não têm o cabeçalho opcional (program_stream_map, padding, private_stream_2, ECM, EMM, DSMCC…).
        if (streamId == 0xBC || streamId == 0xBE || streamId == 0xBF || streamId == 0xF0 || streamId == 0xF1 || streamId == 0xFF || streamId == 0xF2 || streamId == 0xF8) return
        val flags = d[7].toInt() and 0xFF
        val headerEnd = 9 + (d[8].toInt() and 0xFF)
        if (headerEnd > n) return
        var pts = -1L
        var dts = -1L
        if (flags and 0x80 != 0 && headerEnd >= 14) pts = timestamp(d, 9)
        if (flags and 0x40 != 0 && headerEnd >= 19) dts = timestamp(d, 14)
        if (dts < 0) dts = pts
        val body = d.copyOfRange(headerEnd, n)
        listener.onSample(track, TsSample(pts, dts, body, unit.randomAccess, unit.discontinuity))
    }

    // ---- PSI (PAT, PMT) ----------------------------------------------------------------------

    private class Section {
        var data = ByteArray(1024)
        var len = 0
        var counter = -1

        fun add(p: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (len + n > data.size) data = data.copyOf(maxOf(data.size * 2, len + n))
            p.copyInto(data, len, from, to)
            len += n
        }
    }

    private fun section(pid: Int, p: ByteArray, from: Int, to: Int, start: Boolean) {
        var at = from
        var s = sections[pid]
        if (start) {
            val pointer = p[at].toInt() and 0xFF
            at++
            // O fim da secção anterior vem antes do ponteiro.
            if (s != null && pointer > 0 && at + pointer <= to) {
                s.add(p, at, at + pointer)
                complete(pid, s)
            }
            at += pointer
            if (at >= to) return
            s = Section()
            sections[pid] = s
        } else if (s == null) {
            return
        }
        s.add(p, at, to)
        complete(pid, s)
    }

    /** Lê as secções inteiras que [s] já tem (pode haver mais do que uma no mesmo pacote). */
    private fun complete(pid: Int, s: Section) {
        var at = 0
        while (at + 3 <= s.len) {
            val table = s.data[at].toInt() and 0xFF
            if (table == 0xFF) break // Enchimento até ao fim do pacote.
            val length = ((s.data[at + 1].toInt() and 0x0F) shl 8) or (s.data[at + 2].toInt() and 0xFF)
            if (at + 3 + length > s.len) break
            if (length >= 5 && crc32(s.data, at, at + 3 + length) == 0) {
                when {
                    pid == PAT_PID && table == 0x00 -> pat(s.data, at, at + 3 + length)
                    pid == pmtPid && table == 0x02 -> pmt(s.data, at, at + 3 + length)
                }
            }
            at += 3 + length
        }
        if (at > 0) {
            s.data.copyInto(s.data, 0, at, s.len)
            s.len -= at
        }
    }

    private fun pat(d: ByteArray, from: Int, end: Int) {
        var at = from + 8
        val stop = end - 4
        while (at + 4 <= stop) {
            val program = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)
            val pid = ((d[at + 2].toInt() and 0x1F) shl 8) or (d[at + 3].toInt() and 0xFF)
            // O programa 0 é o da rede (NIT), não um canal. Fica o primeiro programa (um canal IPTV tem um).
            if (program != 0) {
                if (pid != pmtPid) {
                    pmtPid = pid
                    pmtVersion = -1
                    sections.remove(pid)
                }
                return
            }
            at += 4
        }
    }

    private fun pmt(d: ByteArray, from: Int, end: Int) {
        val version = (d[from + 5].toInt() shr 1) and 0x1F
        if (version == pmtVersion) return
        val infoLength = ((d[from + 10].toInt() and 0x0F) shl 8) or (d[from + 11].toInt() and 0xFF)
        var at = from + 12 + infoLength
        val stop = end - 4
        val found = LinkedHashMap<Int, TsTrack>()
        while (at + 5 <= stop) {
            val type = d[at].toInt() and 0xFF
            val pid = ((d[at + 1].toInt() and 0x1F) shl 8) or (d[at + 2].toInt() and 0xFF)
            val esLength = ((d[at + 3].toInt() and 0x0F) shl 8) or (d[at + 4].toInt() and 0xFF)
            val descEnd = minOf(at + 5 + esLength, stop)
            found[pid] = track(type, pid, d, at + 5, descEnd)
            at = descEnd
        }
        pmtVersion = version
        // As faixas que saíram deixam de juntar pacotes.
        for (pid in pes.keys.toList()) if (pid !in found) pes.remove(pid)
        tracks = found
        listener.onTracks(found.values.toList())
    }

    private fun track(type: Int, pid: Int, d: ByteArray, from: Int, to: Int): TsTrack {
        var language: String? = null
        var config: ByteArray? = null
        var codec = when (type) {
            0x1B -> Codec.H264
            0x24 -> Codec.HEVC
            0x01, 0x02 -> Codec.MPEG2_VIDEO
            0x0F -> Codec.AAC_ADTS
            0x11 -> Codec.AAC_LATM
            0x03, 0x04 -> Codec.MPEG_AUDIO
            0x81 -> Codec.AC3
            0x87 -> Codec.EAC3
            0x15 -> Codec.ID3
            else -> Codec.UNKNOWN
        }
        var at = from
        while (at + 2 <= to) {
            val tag = d[at].toInt() and 0xFF
            val length = d[at + 1].toInt() and 0xFF
            val body = at + 2
            if (body + length > to) break
            when (tag) {
                // ISO_639_language: a língua da faixa (áudio, ou legendas sem o descritor próprio).
                0x0A -> if (length >= 3 && language == null) language = text(d, body, 3)
                // DVB: o tipo 0x06 (dados privados) é que diz o que vem lá.
                0x6A -> if (type == 0x06) codec = Codec.AC3
                0x7A -> if (type == 0x06) codec = Codec.EAC3
                0x59 -> if (type == 0x06) {
                    codec = Codec.DVB_SUBTITLE
                    if (length >= 3) language = text(d, body, 3)
                    // ISO 639 (3), subtitling_type (1), composition_page_id (2), ancillary_page_id (2).
                    if (length >= 8) config = d.copyOfRange(body + 4, body + 8)
                }
                0x56 -> if (type == 0x06) {
                    codec = Codec.TELETEXT
                    if (length >= 3 && language == null) language = text(d, body, 3)
                }
                // registration_descriptor: "AC-3", "EAC3", "HEVC", "ID3 "…
                0x05 -> if (length >= 4 && codec == Codec.UNKNOWN) codec = when (text(d, body, 4)) {
                    "AC-3" -> Codec.AC3
                    "EAC3" -> Codec.EAC3
                    "HEVC" -> Codec.HEVC
                    "ID3 " -> Codec.ID3
                    else -> codec
                }
            }
            at = body + length
        }
        val kind = when (codec) {
            Codec.H264, Codec.HEVC, Codec.MPEG2_VIDEO -> TrackKind.VIDEO
            Codec.AAC_ADTS, Codec.AAC_LATM, Codec.MPEG_AUDIO, Codec.AC3, Codec.EAC3 -> TrackKind.AUDIO
            Codec.DVB_SUBTITLE, Codec.TELETEXT -> TrackKind.SUBTITLE
            Codec.ID3, Codec.UNKNOWN -> TrackKind.DATA
        }
        return TsTrack(pid, type, codec, kind, language?.lowercase()?.takeIf { it.isNotBlank() && it.all { c -> c in 'a'..'z' } }, config)
    }

    private fun text(d: ByteArray, from: Int, n: Int): String = CharArray(n) { (d[from + it].toInt() and 0xFF).toChar() }.concatToString()

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val PAT_PID = 0
        private const val NULL_PID = 0x1FFF

        /** Um PTS ou DTS (33 bits em 5 bytes, com bits de marcação pelo meio). */
        internal fun timestamp(d: ByteArray, at: Int): Long =
            ((d[at].toLong() and 0x0E) shl 29) or
                ((d[at + 1].toLong() and 0xFF) shl 22) or
                ((d[at + 2].toLong() and 0xFE) shl 14) or
                ((d[at + 3].toLong() and 0xFF) shl 7) or
                ((d[at + 4].toLong() and 0xFE) shr 1)

        private val CRC_TABLE = IntArray(256) { n ->
            var c = n shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            c
        }

        /** CRC-32/MPEG-2 de [from, to): 0 quando a secção (com o seu CRC no fim) está certa. */
        internal fun crc32(d: ByteArray, from: Int, to: Int): Int {
            var crc = -1
            for (i in from until to) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (d[i].toInt() and 0xFF)) and 0xFF]
            return crc
        }
    }
}
