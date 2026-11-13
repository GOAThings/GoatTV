package cc.slokker.itv.media

import platform.Foundation.NSData

/**
 * O [HlsMerger] para o Swift: entram os segmentos do vídeo e do áudio à parte, sai o TS que os dois já juntaram (às
 * vezes nada, à espera do outro lado), numa chamada só pela ponte.
 */
class HlsMergeBuffer {
    private var out = ByteArray(256 * 1024)
    private var size = 0
    private val merger = HlsMerger { b ->
        if (size + b.size > out.size) out = out.copyOf(maxOf(out.size * 2, size + b.size))
        b.copyInto(out, size)
        size += b.size
    }

    /** Porque é que um dos lados não se toca (um fMP4 cifrado…), ou null. */
    val unsupported: String? get() = merger.unsupported

    fun video(data: NSData): NSData {
        merger.video(AppleMedia.bytes(data))
        return take()
    }

    fun audio(data: NSData): NSData {
        merger.audio(AppleMedia.bytes(data))
        return take()
    }

    fun finish(): NSData {
        merger.flush()
        return take()
    }

    private fun take(): NSData {
        val result = AppleMedia.data(out.copyOf(size))
        size = 0
        return result
    }
}
