package cc.slokker.itv.media

/**
 * O som solto, sem TS à volta, a chegar aos bocados: as rádios por HTTP (Icecast/SHOUTcast em MP3, AAC em ADTS,
 * AC-3) e os HLS de áudio empacotado sem vídeo (os segmentos .aac/.mp3 um atrás do outro, cada um com a etiqueta ID3
 * do tempo à frente). Entrega como o [TsDemuxer]: uma faixa e um [TsSample] por bloco. O tempo conta-se pelas amostras
 * a partir de 0, ou do tempo que a etiqueta ID3 diz (ver [PackedAudioDemuxer]).
 */
class RawAudioDemuxer(private val listener: TsListener) {
    private var pending = ByteArray(0)
    private var track: TsTrack? = null
    private var reader: AudioFrameReader? = null
    private var anchor = 0L
    private var anchorRate = 0
    private var samples = 0L

    fun feed(data: ByteArray, off: Int, len: Int) {
        pending = if (pending.isEmpty()) data.copyOfRange(off, off + len) else pending + data.copyOfRange(off, off + len)
        val buf = pending
        var at = 0
        while (at < buf.size) {
            if (isId3(buf, at)) {
                if (at + 10 > buf.size) break
                // O rodapé (bit 4 das flags) junta mais 10 bytes.
                val end = at + 10 + PackedAudioDemuxer.syncsafe(buf, at + 6) + if ((buf[at + 5].toInt() and 0x10) != 0) 10 else 0
                if (end > buf.size) break
                PackedAudioDemuxer.timestamp(buf, at + 10, end, buf[at + 3].toInt())?.let {
                    anchor = it
                    samples = 0
                }
                at = end
                continue
            }
            // Até à etiqueta seguinte (o segmento seguinte do HLS), ou ao fim do que chegou.
            val next = nextId3(buf, at + 1)
            val limit = if (next >= 0) next else buf.size
            if (track == null) {
                val codec = PackedAudioDemuxer.codecAt(buf, at)
                if (codec == null) {
                    at++
                    continue
                }
                track = TsTrack(1, PackedAudioDemuxer.STREAM_TYPES.getValue(codec), codec, TrackKind.AUDIO, null)
                reader = AudioFrameReader(codec)
                listener.onTracks(listOfNotNull(track))
            }
            val t = track ?: break
            val body = if (at == 0 && limit == buf.size) buf else buf.copyOfRange(at, limit)
            val frames = reader?.rawFrames(body).orEmpty()
            for (f in frames) {
                if (f.sampleRate != anchorRate) {
                    // Mudou a frequência: o ponto de partida passa a ser este bloco.
                    if (anchorRate > 0) anchor = (anchor + samples * 90_000L / anchorRate) and PackedAudioDemuxer.MASK_33
                    samples = 0
                    anchorRate = f.sampleRate
                }
                val clock = (anchor + samples * 90_000L / f.sampleRate) and PackedAudioDemuxer.MASK_33
                listener.onSample(t, TsSample(clock, clock, body.copyOfRange(f.offset, f.offset + f.size), true, false))
                samples += f.samples
            }
            val used = frames.lastOrNull()?.let { it.offset + it.size } ?: 0
            if (next >= 0) {
                // O resto antes da etiqueta é um bloco partido: fica de fora.
                at = next
                continue
            }
            at += used
            break
        }
        pending = if (at >= buf.size) ByteArray(0) else buf.copyOfRange(at, buf.size)
        // Sem nenhum bloco num pedaço grande, é lixo: guarda-se só o fim (onde pode começar o seguinte).
        if (pending.size > MAX_PENDING) pending = pending.copyOfRange(pending.size - KEEP, pending.size)
    }

    fun flush() {
        pending = ByteArray(0)
    }

    private fun isId3(d: ByteArray, at: Int): Boolean =
        at + 3 <= d.size && d[at] == 'I'.code.toByte() && d[at + 1] == 'D'.code.toByte() && d[at + 2] == '3'.code.toByte() &&
            (at + 4 > d.size || d[at + 3].toInt() in 2..4)

    /** A próxima etiqueta ID3 a partir de [from] (a versão 2 a 4 e a revisão 0, para não a confundir com som), ou -1. */
    private fun nextId3(d: ByteArray, from: Int): Int {
        var i = from
        while (i + 5 <= d.size) {
            if (d[i] == 'I'.code.toByte() && d[i + 1] == 'D'.code.toByte() && d[i + 2] == '3'.code.toByte() &&
                d[i + 3].toInt() in 2..4 && d[i + 4].toInt() == 0
            ) return i
            i++
        }
        return -1
    }

    companion object {
        private const val MAX_PENDING = 256 * 1024
        private const val KEEP = 16 * 1024

        /**
         * Os primeiros bytes de um stream parecem som solto (uma etiqueta ID3 ou um bloco MP3/ADTS/AC-3 logo no início)
         * e não um TS (que começa em 0x47) nem um MP4.
         */
        fun looksLike(d: ByteArray, len: Int): Boolean = len >= 2 && d[0] != 0x47.toByte() && PackedAudioDemuxer.looksLike(d.copyOf(minOf(len, d.size)))
    }
}
