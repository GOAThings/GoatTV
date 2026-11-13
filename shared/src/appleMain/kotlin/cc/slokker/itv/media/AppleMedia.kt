package cc.slokker.itv.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * A passagem dos bytes entre o Swift (Data) e o Kotlin (ByteArray) de uma só vez: byte a byte, uma imagem de vídeo
 * levava milhares de chamadas pela ponte.
 */
object AppleMedia {
    @OptIn(ExperimentalForeignApi::class)
    fun data(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun bytes(data: NSData): ByteArray {
        val n = data.length.toInt()
        val out = ByteArray(n)
        if (n > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    /** Junta ao [demuxer] o que chegou da rede. */
    fun feed(demuxer: TsDemuxer, data: NSData) {
        val b = bytes(data)
        demuxer.feed(b, 0, b.size)
    }

    /** Junta ao [demuxer] (TS ou fMP4, escolhido pelos primeiros bytes) o que chegou; devolve porque é que o stream não se toca, ou null. */
    fun feedStream(demuxer: StreamDemuxer, data: NSData): String? {
        val b = bytes(data)
        demuxer.feed(b, 0, b.size)
        return demuxer.unsupported
    }

    /** O IV de [key] (16 bytes) para o CommonCrypto. */
    fun iv(key: HlsKey): NSData = data(key.iv)

    /** As páginas de legendas do teletexto que a faixa [track] anuncia (ver [TeletextPage]). */
    fun teletextPages(track: TsTrack): List<TeletextPage> =
        if (track.codec == Codec.TELETEXT) TeletextPage.of(track.config).filter { it.isSubtitle } else emptyList()

    /** O descodificador das legendas DVB da faixa [track] (as páginas do PMT), ou null se não for DVB. */
    fun dvbDecoder(track: TsTrack): DvbSubtitleDecoder? {
        if (track.codec != Codec.DVB_SUBTITLE) return null
        val c = track.config ?: byteArrayOf(0, 1, 0, 1)
        if (c.size < 4) return null
        return DvbSubtitleDecoder(((c[0].toInt() and 0xFF) shl 8) or (c[1].toInt() and 0xFF), ((c[2].toInt() and 0xFF) shl 8) or (c[3].toInt() and 0xFF))
    }

    /** A página nova das legendas DVB neste PES (data_identifier 0x20, subtitle_stream_id 0x00), ou null. */
    fun dvbPage(decoder: DvbSubtitleDecoder, sample: TsSample): DvbSubtitlePage? {
        val d = sample.data
        if (d.size < 3 || d[0].toInt() != 0x20 || d[1].toInt() != 0x00) return null
        return decoder.decode(d.copyOfRange(2, d.size))
    }

    /** Os pixels de [image] (RGBA pré-multiplicado) para o Core Graphics. */
    fun pixels(image: DvbSubtitleImage): NSData = data(image.rgba)

    /** O texto novo da página de [decoder] neste PES, as linhas juntas por "\n"; "" para limpar; null se não mudou. */
    fun teletextText(decoder: TeletextDecoder, sample: TsSample): String? = decoder.feed(sample.data)?.joinToString("\n")
}
