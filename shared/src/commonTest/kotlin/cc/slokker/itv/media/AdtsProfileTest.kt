package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

/** Há emissões cujo ADTS diz AAC Main (que o Android não descodifica) e são LC: a configuração vai sempre como LC. */
class AdtsProfileTest {
    /** Um bloco ADTS de 16 bytes sem CRC: [profile] (0 = Main, 1 = LC), 24 kHz, estéreo. */
    private fun frame(profile: Int): ByteArray {
        val d = ByteArray(16)
        d[0] = 0xFF.toByte()
        d[1] = 0xF1.toByte() // MPEG-4, sem CRC
        d[2] = ((profile shl 6) or (6 shl 2)).toByte() // perfil, frequência 6 (24 kHz), canais (bit alto) 0
        d[3] = ((2 shl 6) or 0).toByte() // canais 2, tamanho (bits altos) 0
        d[4] = (16 shr 3).toByte()
        d[5] = ((16 and 7) shl 5 or 0x1F).toByte()
        d[6] = 0xFC.toByte() // 1 bloco
        return d
    }

    @Test
    fun mainProfileIsConfiguredAsLc() {
        for (profile in listOf(0, 1)) {
            val reader = AudioFrameReader(Codec.AAC_ADTS)
            reader.frames(frame(profile) + frame(profile), 90_000, false)
            val config = reader.format!!.config
            assertEquals(2, (config[0].toInt() and 0xFF) shr 3, "perfil $profile")
            assertEquals(24_000, reader.format!!.sampleRate)
        }
    }
}
