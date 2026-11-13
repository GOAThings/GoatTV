package cc.slokker.itv.media

/**
 * Escreve um MPEG-TS com o que um demuxer entrega ([TsListener]): é por aqui que um HLS em fMP4 vira um .ts para o
 * anel da pausa do direto e para as gravações (que só sabem ler TS). Um programa só:
 * - PAT e PMT à frente de cada imagem-chave (e de meio em meio segundo), para se poder começar a ler em qualquer lado;
 * - o PCR no PID do vídeo (ou do primeiro áudio, sem vídeo), um pouco atrás do DTS;
 * - as faixas que não conhece ([Codec.UNKNOWN], ID3…) ficam de fora.
 * Os PID das faixas não mudam enquanto as faixas forem as mesmas (o init segment repetido de outra qualidade).
 */
class TsMuxer(private val out: (ByteArray) -> Unit) : TsListener {
    private class Out(val pid: Int, val streamId: Int, val streamType: Int, val track: TsTrack)

    private var outs: Map<Int, Out> = emptyMap()
    private var pcrPid = -1
    private var version = 0
    private val counters = IntArray(0x2000)
    private var tablesAt = Long.MIN_VALUE
    private val packet = ByteArray(PACKET)

    override fun onTracks(tracks: List<TsTrack>) {
        val usable = tracks.filter { it.codec in STREAM_TYPES }
        val same = usable.size == outs.size && usable.all { t ->
            outs[t.pid]?.let { it.track.codec == t.codec && it.track.language == t.language } == true
        }
        if (same) return
        outs = usable.mapIndexed { i, t ->
            val type = STREAM_TYPES.getValue(t.codec)
            val id = when {
                t.kind == TrackKind.VIDEO -> 0xE0
                t.codec == Codec.AC3 || t.codec == Codec.EAC3 || t.codec == Codec.WEBVTT -> 0xBD
                else -> 0xC0
            }
            t.pid to Out(FIRST_PID + i, id, type, t)
        }.toMap()
        pcrPid = (outs.values.firstOrNull { it.track.kind == TrackKind.VIDEO } ?: outs.values.firstOrNull())?.pid ?: -1
        version = (version + 1) and 0x1F
        tablesAt = Long.MIN_VALUE
    }

    override fun onSample(track: TsTrack, sample: TsSample) {
        val o = outs[track.pid] ?: return
        val time = if (sample.dts >= 0) sample.dts else sample.pts
        // As tabelas à frente de tudo (o áudio pode vir antes da primeira imagem) e depois pelo PID do relógio.
        if (tablesAt == Long.MIN_VALUE || o.pid == pcrPid && (sample.randomAccess && o.track.kind == TrackKind.VIDEO ||
                ((time - tablesAt) and MASK_33) > TABLES_EVERY)
        ) {
            tables()
            tablesAt = time
        }
        pes(o, sample, time)
    }

    private fun tables() {
        section(0, byteArrayOf(0x00, PROGRAM.toByte(), (0xE0 or (PMT_PID shr 8)).toByte(), PMT_PID.toByte()), 0x00, 1)
        val body = ArrayList<Byte>()
        body += (0xE0 or (pcrPid shr 8)).toByte()
        body += pcrPid.toByte()
        body += 0xF0.toByte()
        body += 0x00
        for (o in outs.values) {
            val descriptors = ArrayList<Byte>()
            o.track.language?.takeIf { it.length == 3 }?.let { l ->
                descriptors += listOf(0x0A, 4, l[0].code.toByte(), l[1].code.toByte(), l[2].code.toByte(), 0).map { it.toByte() }
            }
            // As legendas WebVTT: dados privados com o registo "WVTT" (o TsDemuxer reconhece-as por aí).
            if (o.track.codec == Codec.WEBVTT) descriptors += listOf(0x05, 4, 'W'.code, 'V'.code, 'T'.code, 'T'.code).map { it.toByte() }
            body += o.streamType.toByte()
            body += (0xE0 or (o.pid shr 8)).toByte()
            body += o.pid.toByte()
            body += (0xF0 or (descriptors.size shr 8)).toByte()
            body += descriptors.size.toByte()
            body += descriptors
        }
        section(PMT_PID, body.toByteArray(), 0x02, PROGRAM)
    }

    /** Uma secção PSI ([table]: 0 PAT, 2 PMT) com o [id] (transport_stream_id ou program_number), num pacote só. */
    private fun section(pid: Int, body: ByteArray, table: Int, id: Int) {
        val length = 5 + body.size + 4
        val s = ByteArray(3 + length)
        s[0] = table.toByte()
        s[1] = (0xB0 or (length shr 8)).toByte()
        s[2] = length.toByte()
        s[3] = (id shr 8).toByte()
        s[4] = id.toByte()
        s[5] = (0xC1 or (version shl 1)).toByte()
        s[6] = 0
        s[7] = 0
        body.copyInto(s, 8)
        val crc = TsDemuxer.crc32(s, 0, s.size - 4)
        for (k in 0 until 4) s[s.size - 4 + k] = (crc ushr (24 - 8 * k)).toByte()
        packet.fill(0xFF.toByte())
        header(pid, start = true, adaptation = false)
        packet[4] = 0 // pointer_field
        s.copyInto(packet, 5)
        out(packet.copyOf())
    }

    private fun pes(o: Out, sample: TsSample, time: Long) {
        val hasDts = sample.dts >= 0 && sample.pts >= 0 && sample.dts != sample.pts
        val headerData = if (sample.pts < 0) 0 else if (hasDts) 10 else 5
        val pes = ByteArray(9 + headerData + sample.data.size)
        pes[2] = 1
        pes[3] = o.streamId.toByte()
        val length = pes.size - 6
        // O vídeo pode passar dos 64 KB: aí o tamanho vai a 0 (permitido só no vídeo).
        if (length <= 0xFFFF && o.track.kind != TrackKind.VIDEO) {
            pes[4] = (length shr 8).toByte()
            pes[5] = length.toByte()
        }
        pes[6] = 0x80.toByte()
        pes[7] = (if (sample.pts < 0) 0 else if (hasDts) 0xC0 else 0x80).toByte()
        pes[8] = headerData.toByte()
        if (sample.pts >= 0) timestamp(pes, 9, if (hasDts) 0x3 else 0x2, sample.pts)
        if (hasDts) timestamp(pes, 14, 0x1, sample.dts)
        sample.data.copyInto(pes, 9 + headerData)

        var at = 0
        var first = true
        while (at < pes.size) {
            val pcr = first && o.pid == pcrPid && time >= 0
            val random = first && sample.randomAccess
            val discontinuity = first && sample.discontinuity
            // O campo de adaptação: os flags e o PCR, e o enchimento do último pacote.
            val flagsLength = if (pcr || random || discontinuity) 2 + (if (pcr) 6 else 0) else 0
            val room = PACKET - 4 - flagsLength
            val take = minOf(room, pes.size - at)
            val adaptationLength = if (flagsLength > 0 || take < PACKET - 4) PACKET - 4 - take else 0
            packet.fill(0xFF.toByte())
            header(o.pid, first, adaptationLength > 0)
            var p = 4
            if (adaptationLength > 0) {
                packet[4] = (adaptationLength - 1).toByte()
                if (adaptationLength > 1) {
                    packet[5] = ((if (discontinuity) 0x80 else 0) or (if (random) 0x40 else 0) or (if (pcr) 0x10 else 0)).toByte()
                    if (pcr) {
                        val base = (time - PCR_LEAD) and MASK_33
                        packet[6] = (base shr 25).toByte()
                        packet[7] = (base shr 17).toByte()
                        packet[8] = (base shr 9).toByte()
                        packet[9] = (base shr 1).toByte()
                        packet[10] = (((base and 1) shl 7) or 0x7E).toByte()
                        packet[11] = 0
                    }
                }
                p = 4 + adaptationLength
            }
            pes.copyInto(packet, p, at, at + take)
            at += take
            first = false
            out(packet.copyOf())
        }
    }

    private fun header(pid: Int, start: Boolean, adaptation: Boolean) {
        packet[0] = 0x47
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = ((if (adaptation) 0x30 else 0x10) or counters[pid]).toByte()
        counters[pid] = (counters[pid] + 1) and 0xF
    }

    private fun timestamp(d: ByteArray, at: Int, prefix: Int, value: Long) {
        val v = value and MASK_33
        d[at] = ((prefix shl 4) or ((v shr 29).toInt() and 0x0E) or 1).toByte()
        d[at + 1] = (v shr 22).toByte()
        d[at + 2] = (((v shr 14).toInt() and 0xFE) or 1).toByte()
        d[at + 3] = (v shr 7).toByte()
        d[at + 4] = (((v shl 1).toInt() and 0xFE) or 1).toByte()
    }

    companion object {
        private const val PACKET = 188
        private const val PROGRAM = 1
        private const val PMT_PID = 0x1000
        private const val FIRST_PID = 0x100
        private const val MASK_33 = (1L shl 33) - 1
        /** O PCR vai 100 ms atrás do DTS (o tempo que o descodificador tem para receber a imagem). */
        private const val PCR_LEAD = 9_000L
        private const val TABLES_EVERY = 45_000L

        private val STREAM_TYPES = mapOf(
            Codec.H264 to 0x1B,
            Codec.HEVC to 0x24,
            Codec.MPEG2_VIDEO to 0x02,
            Codec.AAC_ADTS to 0x0F,
            Codec.AAC_LATM to 0x11,
            Codec.MPEG_AUDIO to 0x03,
            Codec.AC3 to 0x81,
            Codec.EAC3 to 0x87,
            Codec.WEBVTT to 0x06,
        )
    }
}

/**
 * Um stream de um canal sempre em TS: se os primeiros bytes forem de um MP4 fragmentado, sai remultiplexado pelo
 * [TsMuxer]; se forem TS (ou outra coisa), sai como vem. Um fMP4 que não se toque (cifrado) também sai como vem, do
 * princípio, para quem lê decidir como dantes.
 */
class TsRemuxer(private val out: (ByteArray) -> Unit) {
    private var head = ByteArray(0)
    private var decided = false
    private var demuxer: Mp4Demuxer? = null
    private var held: ArrayList<ByteArray>? = ArrayList()
    private var emitted = false

    /** É um fMP4 a ser convertido (sabe-se ao fim de 8 bytes). */
    val remuxing: Boolean get() = demuxer != null

    fun feed(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (!decided) {
            head += data.copyOfRange(off, off + len)
            if (head.size < 8) return
            decided = true
            val bytes = head
            head = ByteArray(0)
            if (!Mp4Demuxer.looksLike(bytes, bytes.size)) {
                held = null
                out(bytes)
                return
            }
            val muxer = TsMuxer { emitted = true; out(it) }
            demuxer = Mp4Demuxer(muxer)
            mp4(bytes, 0, bytes.size)
            return
        }
        if (demuxer == null) {
            out(data.copyOfRange(off, off + len))
            return
        }
        mp4(data, off, len)
    }

    private fun mp4(data: ByteArray, off: Int, len: Int) {
        val d = demuxer ?: return
        // Até sair o primeiro pacote guarda-se o que veio: se afinal for cifrado, sai tudo como veio.
        held?.add(data.copyOfRange(off, off + len))
        d.feed(data, off, len)
        if (d.unsupported != null && !emitted) {
            demuxer = null
            held?.forEach(out)
            held = null
            return
        }
        if (emitted || (held?.sumOf { it.size } ?: 0) > MAX_HELD) held = null
    }

    fun flush() {
        demuxer?.flush()
    }

    private companion object {
        const val MAX_HELD = 4 * 1024 * 1024
    }
}
