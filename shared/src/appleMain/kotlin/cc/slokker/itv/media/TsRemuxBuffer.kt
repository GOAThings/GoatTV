package cc.slokker.itv.media

import platform.Foundation.NSData

/**
 * O [TsRemuxer] para o Swift: entra o que chegou da rede, sai o TS para o anel (um fMP4 passado a TS; o resto como
 * vem), numa chamada só pela ponte.
 */
class TsRemuxBuffer {
    private var out = ByteArray(256 * 1024)
    private var size = 0
    private val remuxer = TsRemuxer { b ->
        if (size + b.size > out.size) out = out.copyOf(maxOf(out.size * 2, size + b.size))
        b.copyInto(out, size)
        size += b.size
    }

    fun feed(data: NSData): NSData {
        val b = AppleMedia.bytes(data)
        remuxer.feed(b, 0, b.size)
        return take()
    }

    fun finish(): NSData {
        remuxer.flush()
        return take()
    }

    private fun take(): NSData {
        val result = AppleMedia.data(out.copyOf(size))
        size = 0
        return result
    }
}
