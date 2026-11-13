package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** No E-AC-3 7.1 cada bloco vem com uma sub-stream dependente (os canais a mais): vão juntos, num pacote só. */
class Eac3DependentTest {
    /** Um bloco E-AC-3 de 16 bytes: 48 kHz, 6 blocos de áudio, 3/2 com LFE; [dependent] = strmtyp 1. */
    private fun frame(dependent: Boolean, fill: Int): ByteArray {
        val d = ByteArray(16) { fill.toByte() }
        d[0] = 0x0B
        d[1] = 0x77
        d[2] = ((if (dependent) 1 else 0) shl 6).toByte() // strmtyp, substreamid 0, frmsiz alto
        d[3] = 7 // frmsiz: (7 + 1) palavras = 16 bytes
        d[4] = ((0 shl 6) or (3 shl 4) or (7 shl 1) or 1).toByte() // fscod 48 kHz, numblkscod 6 blocos, acmod 3/2, LFE
        d[5] = (16 shl 3).toByte() // bsid 16
        return d
    }

    @Test
    fun dependentSubstreamsGoWithTheirFrame() {
        val data = frame(false, 1) + frame(true, 2) + frame(false, 3) + frame(true, 4)
        val reader = AudioFrameReader(Codec.EAC3)
        val frames = reader.frames(data + ByteArray(16), 90_000, false)
        assertEquals(2, frames.size)
        assertEquals(listOf(32, 32), frames.map { it.data.size })
        assertEquals(listOf(90_000L, 92_880L), frames.map { it.pts })
        assertEquals(listOf(1536, 1536), frames.map { it.samples })
        assertEquals(6, reader.format?.channels)
        assertEquals(listOf(0 to 32, 32 to 32), reader.rawFrames(data + ByteArray(16)).map { it.offset to it.size })
    }
}
