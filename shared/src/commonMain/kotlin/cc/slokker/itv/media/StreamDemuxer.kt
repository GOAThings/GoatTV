package cc.slokker.itv.media

/**
 * O demuxer de um canal, escolhido pelos primeiros bytes: um MP4 fragmentado ([Mp4Demuxer], o fMP4 do HLS), o som
 * solto das rádios ([RawAudioDemuxer]: MP3, AAC, AC-3, ou o áudio empacotado de um HLS só de som) ou, em tudo o resto,
 * um MPEG-TS ([TsDemuxer]). Todos entregam o mesmo ao [listener].
 */
class StreamDemuxer(private val listener: TsListener) {
    private var head = ByteArray(0)
    private var ts: TsDemuxer? = null
    private var mp4: Mp4Demuxer? = null
    private var raw: RawAudioDemuxer? = null

    /** Porque é que o stream não se toca (um fMP4 cifrado…), ou null. */
    val unsupported: String? get() = mp4?.unsupported

    /** É um MP4 (já se sabe quando houver 8 bytes)? */
    val isMp4: Boolean get() = mp4 != null

    fun feed(data: ByteArray, off: Int, len: Int) {
        ts?.let { it.feed(data, off, len); return }
        mp4?.let { it.feed(data, off, len); return }
        raw?.let { it.feed(data, off, len); return }
        head += data.copyOfRange(off, off + len)
        if (head.size < 8) return
        // Um TS apanhado a meio de um pacote pode começar por bytes que parecem som solto: antes de o tratar como
        // rádio, espera-se por uns pacotes e vê-se se há o 0x47 de 188 em 188.
        if (!Mp4Demuxer.looksLike(head, head.size) && RawAudioDemuxer.looksLike(head, head.size) && head.size < TS_PROBE_BYTES) return
        decide()
    }

    private fun decide() {
        val bytes = head
        head = ByteArray(0)
        if (bytes.isEmpty()) return
        if (Mp4Demuxer.looksLike(bytes, bytes.size)) {
            mp4 = Mp4Demuxer(listener).also { it.feed(bytes, 0, bytes.size) }
        } else if (RawAudioDemuxer.looksLike(bytes, bytes.size) && !hasTsSync(bytes)) {
            raw = RawAudioDemuxer(listener).also { it.feed(bytes, 0, bytes.size) }
        } else {
            ts = TsDemuxer(listener).also { it.feed(bytes, 0, bytes.size) }
        }
    }

    /** Há quatro 0x47 seguidos à distância de um pacote, a começar algures no primeiro: é um TS. */
    private fun hasTsSync(d: ByteArray): Boolean {
        for (i in 0 until TS_PACKET) {
            if (i + TS_PACKET * 3 >= d.size) return false
            if (d[i] == SYNC && d[i + TS_PACKET] == SYNC && d[i + TS_PACKET * 2] == SYNC && d[i + TS_PACKET * 3] == SYNC) return true
        }
        return false
    }

    fun flush() {
        // Um stream mais curto do que a espera pelo TS: decide-se com o que há.
        if (ts == null && mp4 == null && raw == null && head.size >= 8) decide()
        ts?.flush()
        mp4?.flush()
        raw?.flush()
    }

    private companion object {
        const val TS_PACKET = 188
        const val SYNC: Byte = 0x47
        /** Quanto se espera para distinguir um TS apanhado a meio de som solto: cinco pacotes. */
        const val TS_PROBE_BYTES = TS_PACKET * 5
    }
}
