package cc.slokker.itv.data.recording

import java.io.ByteArrayOutputStream

/** TS sintético para os testes das gravações: pacotes de 188 bytes, um PCR a cada 100 ms. */
internal object TsSamples {
    fun packet(pid: Int = 0x100, pcrMs: Long? = null, fill: Byte = 0x11): ByteArray {
        val p = ByteArray(188) { fill }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (pcrMs == null) {
            p[3] = 0x10
        } else {
            p[3] = 0x30
            p[4] = 7
            p[5] = 0x10
            val base = pcrMs * 90
            p[6] = (base shr 25).toByte()
            p[7] = (base shr 17).toByte()
            p[8] = (base shr 9).toByte()
            p[9] = (base shr 1).toByte()
            p[10] = (((base and 1) shl 7) or 0x7E).toByte()
            p[11] = 0
        }
        return p
    }

    /** [seconds] de vídeo a partir do PCR [fromMs]; [fill] muda o conteúdo (para se saber de onde veio cada byte). */
    fun stream(fromMs: Long, seconds: Int, perPcr: Int = 4, fill: Byte = 0x11): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in 0 until seconds * 10) {
            out.write(packet(pcrMs = fromMs + i * 100L, fill = fill))
            repeat(perPcr) { out.write(packet(fill = fill)) }
        }
        return out.toByteArray()
    }
}
