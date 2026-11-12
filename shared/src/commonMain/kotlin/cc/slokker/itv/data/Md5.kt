package cc.slokker.itv.data

/** MD5 (RFC 1321), só para o que os portais Stalker esperam (o número de série e a versão da "box"), nunca segurança. */
object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val K = IntArray(64) { i -> (kotlin.math.abs(kotlin.math.sin(i + 1.0)) * 4294967296.0).toLong().toInt() }

    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 8) / 64 + 1) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                m[i] = (padded[o].toInt() and 0xFF) or ((padded[o + 1].toInt() and 0xFF) shl 8) or
                    ((padded[o + 2].toInt() and 0xFF) shl 16) or ((padded[o + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val next = d
                d = c
                c = b
                b += (a + f + K[i] + m[g]).rotateLeft(S[i])
                a = next
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        for ((n, v) in intArrayOf(a0, b0, c0, d0).withIndex()) for (i in 0 until 4) out[n * 4 + i] = (v ushr (8 * i)).toByte()
        return out
    }

    /** O MD5 de [text] (UTF-8) em hexadecimal maiúsculo. */
    fun hexUpper(text: String): String = Sha1.hex(digest(text.encodeToByteArray())).uppercase()
}
