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
}
