package cc.slokker.itv.media

/**
 * AES-128 (FIPS-197) em Kotlin puro, para decifrar o CENC (ver [Cenc]) igual no Android e na Apple: os blocos de 16
 * bytes, em tabelas de 32 bits. Os modos ([ctr], [cbcDecrypt]) trabalham no próprio array.
 */
class Aes(key: ByteArray) {
    private val ek = IntArray(44)
    private val dk = IntArray(44)

    init {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        for (i in 0 until 4) ek[i] = word(key, i * 4)
        var rcon = 1
        for (i in 4 until 44) {
            var t = ek[i - 1]
            if (i % 4 == 0) {
                t = sub(t.rotateLeft(8)) xor (rcon shl 24)
                rcon = xtime(rcon)
            }
            ek[i] = ek[i - 4] xor t
        }
        // A cifra inversa equivalente: as chaves das rondas ao contrário, com InvMixColumns nas do meio.
        for (r in 0..10) for (c in 0 until 4) {
            val w = ek[(10 - r) * 4 + c]
            dk[r * 4 + c] = if (r == 0 || r == 10) w else
                TD0[S[w ushr 24]] xor TD1[S[(w ushr 16) and 255]] xor TD2[S[(w ushr 8) and 255]] xor TD3[S[w and 255]]
        }
    }

    /** Cifra o bloco de [inp] em [inOff] para [out] em [outOff]. */
    fun encryptBlock(inp: ByteArray, inOff: Int, out: ByteArray, outOff: Int) {
        var s0 = word(inp, inOff) xor ek[0]
        var s1 = word(inp, inOff + 4) xor ek[1]
        var s2 = word(inp, inOff + 8) xor ek[2]
        var s3 = word(inp, inOff + 12) xor ek[3]
        var k = 4
        for (round in 1 until 10) {
            val t0 = TE0[s0 ushr 24] xor TE1[(s1 ushr 16) and 255] xor TE2[(s2 ushr 8) and 255] xor TE3[s3 and 255] xor ek[k]
            val t1 = TE0[s1 ushr 24] xor TE1[(s2 ushr 16) and 255] xor TE2[(s3 ushr 8) and 255] xor TE3[s0 and 255] xor ek[k + 1]
            val t2 = TE0[s2 ushr 24] xor TE1[(s3 ushr 16) and 255] xor TE2[(s0 ushr 8) and 255] xor TE3[s1 and 255] xor ek[k + 2]
            val t3 = TE0[s3 ushr 24] xor TE1[(s0 ushr 16) and 255] xor TE2[(s1 ushr 8) and 255] xor TE3[s2 and 255] xor ek[k + 3]
            s0 = t0; s1 = t1; s2 = t2; s3 = t3
            k += 4
        }
        put(out, outOff, last(S, s0, s1, s2, s3) xor ek[40])
        put(out, outOff + 4, last(S, s1, s2, s3, s0) xor ek[41])
        put(out, outOff + 8, last(S, s2, s3, s0, s1) xor ek[42])
        put(out, outOff + 12, last(S, s3, s0, s1, s2) xor ek[43])
    }

    /** Decifra o bloco de [inp] em [inOff] para [out] em [outOff] (podem ser o mesmo sítio). */
    fun decryptBlock(inp: ByteArray, inOff: Int, out: ByteArray, outOff: Int) {
        var s0 = word(inp, inOff) xor dk[0]
        var s1 = word(inp, inOff + 4) xor dk[1]
        var s2 = word(inp, inOff + 8) xor dk[2]
        var s3 = word(inp, inOff + 12) xor dk[3]
        var k = 4
        for (round in 1 until 10) {
            val t0 = TD0[s0 ushr 24] xor TD1[(s3 ushr 16) and 255] xor TD2[(s2 ushr 8) and 255] xor TD3[s1 and 255] xor dk[k]
            val t1 = TD0[s1 ushr 24] xor TD1[(s0 ushr 16) and 255] xor TD2[(s3 ushr 8) and 255] xor TD3[s2 and 255] xor dk[k + 1]
            val t2 = TD0[s2 ushr 24] xor TD1[(s1 ushr 16) and 255] xor TD2[(s0 ushr 8) and 255] xor TD3[s3 and 255] xor dk[k + 2]
            val t3 = TD0[s3 ushr 24] xor TD1[(s2 ushr 16) and 255] xor TD2[(s1 ushr 8) and 255] xor TD3[s0 and 255] xor dk[k + 3]
            s0 = t0; s1 = t1; s2 = t2; s3 = t3
            k += 4
        }
        put(out, outOff, last(SI, s0, s3, s2, s1) xor dk[40])
        put(out, outOff + 4, last(SI, s1, s0, s3, s2) xor dk[41])
        put(out, outOff + 8, last(SI, s2, s1, s0, s3) xor dk[42])
        put(out, outOff + 12, last(SI, s3, s2, s1, s0) xor dk[43])
    }

    /**
     * AES-CTR sobre [len] bytes de [data] em [off], no lugar. [counter] (16 bytes) avança com os blocos e [used] é
     * quanto do bloco atual do fluxo já se gastou: chamadas seguidas continuam o mesmo fluxo (os subsamples do CENC).
     */
    class CtrState(val counter: ByteArray) {
        val stream = ByteArray(16)
        var used = 16
    }

    fun ctr(state: CtrState, data: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (state.used == 16) {
                encryptBlock(state.counter, 0, state.stream, 0)
                // O contador sobe nos 8 bytes de baixo (como o CENC e o openssl).
                var j = 15
                while (j >= 8) {
                    val v = (state.counter[j].toInt() and 0xFF) + 1
                    state.counter[j] = v.toByte()
                    if (v < 256) break
                    j--
                }
                state.used = 0
            }
            data[i] = (data[i].toInt() xor state.stream[state.used].toInt()).toByte()
            state.used++
            i++
        }
    }

    /** AES-CBC: decifra [blocks] blocos de [data] a partir de [off], no lugar; [iv] fica com o último cifrado (para continuar). */
    fun cbcDecrypt(iv: ByteArray, data: ByteArray, off: Int, blocks: Int) {
        val saved = ByteArray(16)
        var at = off
        repeat(blocks) {
            data.copyInto(saved, 0, at, at + 16)
            decryptBlock(data, at, data, at)
            for (j in 0 until 16) data[at + j] = (data[at + j].toInt() xor iv[j].toInt()).toByte()
            saved.copyInto(iv)
            at += 16
        }
    }

    private companion object {
        val S = IntArray(256)
        val SI = IntArray(256)
        val TE0 = IntArray(256); val TE1 = IntArray(256); val TE2 = IntArray(256); val TE3 = IntArray(256)
        val TD0 = IntArray(256); val TD1 = IntArray(256); val TD2 = IntArray(256); val TD3 = IntArray(256)

        fun xtime(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x1B else 0)) and 0xFF

        fun mul(a: Int, b: Int): Int {
            var x = a
            var y = b
            var r = 0
            while (y != 0) {
                if (y and 1 != 0) r = r xor x
                x = xtime(x)
                y = y shr 1
            }
            return r
        }

        init {
            // A S-box pelo inverso em GF(2^8) e a transformação afim (FIPS-197 5.1.1).
            var p = 1
            var q = 1
            do {
                p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1B else 0)) and 0xFF
                q = q xor (q shl 1)
                q = q xor (q shl 2)
                q = q xor (q shl 4)
                q = q and 0xFF
                if (q and 0x80 != 0) q = q xor 0x09
                fun rot(v: Int, n: Int) = ((v shl n) or (v ushr (8 - n))) and 0xFF
                S[p] = (q xor rot(q, 1) xor rot(q, 2) xor rot(q, 3) xor rot(q, 4) xor 0x63) and 0xFF
            } while (p != 1)
            S[0] = 0x63
            for (i in 0 until 256) SI[S[i]] = i
            for (x in 0 until 256) {
                val s = S[x]
                val e = (mul(s, 2) shl 24) or (s shl 16) or (s shl 8) or mul(s, 3)
                TE0[x] = e; TE1[x] = e.rotateRight(8); TE2[x] = e.rotateRight(16); TE3[x] = e.rotateRight(24)
                val si = SI[x]
                val d = (mul(si, 14) shl 24) or (mul(si, 9) shl 16) or (mul(si, 13) shl 8) or mul(si, 11)
                TD0[x] = d; TD1[x] = d.rotateRight(8); TD2[x] = d.rotateRight(16); TD3[x] = d.rotateRight(24)
            }
        }

        fun sub(w: Int): Int = (S[w ushr 24] shl 24) or (S[(w ushr 16) and 255] shl 16) or (S[(w ushr 8) and 255] shl 8) or S[w and 255]

        fun last(box: IntArray, a: Int, b: Int, c: Int, d: Int): Int =
            (box[a ushr 24] shl 24) or (box[(b ushr 16) and 255] shl 16) or (box[(c ushr 8) and 255] shl 8) or box[d and 255]

        fun word(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

        fun put(b: ByteArray, at: Int, w: Int) {
            b[at] = (w ushr 24).toByte(); b[at + 1] = (w ushr 16).toByte(); b[at + 2] = (w ushr 8).toByte(); b[at + 3] = w.toByte()
        }
    }
}
