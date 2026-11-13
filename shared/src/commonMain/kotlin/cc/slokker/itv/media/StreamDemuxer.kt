package cc.slokker.itv.media

/**
 * O demuxer de um canal, escolhido pelos primeiros bytes: um MP4 fragmentado ([Mp4Demuxer], o fMP4 do HLS) ou, em
 * tudo o resto, um MPEG-TS ([TsDemuxer]). Os dois entregam o mesmo ao [listener].
 */
class StreamDemuxer(private val listener: TsListener) {
    private var head = ByteArray(0)
    private var ts: TsDemuxer? = null
    private var mp4: Mp4Demuxer? = null

    /** Porque é que o stream não se toca (um fMP4 cifrado…), ou null. */
    val unsupported: String? get() = mp4?.unsupported

    /** É um MP4 (já se sabe quando houver 8 bytes)? */
    val isMp4: Boolean get() = mp4 != null

    fun feed(data: ByteArray, off: Int, len: Int) {
        ts?.let { it.feed(data, off, len); return }
        mp4?.let { it.feed(data, off, len); return }
        head += data.copyOfRange(off, off + len)
        if (head.size < 8) return
        val bytes = head
        head = ByteArray(0)
        if (Mp4Demuxer.looksLike(bytes, bytes.size)) {
            mp4 = Mp4Demuxer(listener).also { it.feed(bytes, 0, bytes.size) }
        } else {
            ts = TsDemuxer(listener).also { it.feed(bytes, 0, bytes.size) }
        }
    }

    fun flush() {
        ts?.flush()
        mp4?.flush()
    }
}
