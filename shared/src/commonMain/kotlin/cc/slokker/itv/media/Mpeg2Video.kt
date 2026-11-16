package cc.slokker.itv.media

/**
 * Lê o vídeo MPEG-2 do TS (ainda muito usado em canais SD de cabo e satélite): cada PES é uma imagem, que vai tal como
 * vem para o descodificador. Daqui sai se é uma imagem I (onde se pode começar) e o cabeçalho da sequência (o
 * "sequence header" com a extensão, que o descodificador do Android quer como csd-0), sempre que muda.
 */
class Mpeg2Reader {
    private var sequence: ByteArray? = null

    /** O cabeçalho da sequência em uso (00 00 01 B3 … com a extensão), ou null antes do primeiro. */
    val sequenceHeader: ByteArray? get() = sequence

    /**
     * A imagem de [data] (um PES), com os tempos dados. [VideoUnit.parameterSets] traz o cabeçalho da sequência quando
     * esta imagem o tem e ele mudou (ou numa imagem I), como no [NalReader].
     */
    fun next(data: ByteArray, pts: Long, dts: Long): VideoUnit {
        var keyframe = false
        var changed = false
        var i = 0
        while (i + 5 < data.size) {
            if (data[i].toInt() != 0 || data[i + 1].toInt() != 0 || data[i + 2].toInt() != 1) {
                i++
                continue
            }
            when (data[i + 3].toInt() and 0xFF) {
                // sequence_header: vai até ao código seguinte que não seja a extensão da sequência.
                0xB3 -> {
                    val end = sequenceEnd(data, i)
                    val header = data.copyOfRange(i, end)
                    if (!header.contentEquals(sequence)) {
                        sequence = header
                        changed = true
                    }
                    i = end
                    continue
                }
                // picture_header: temporal_reference (10 bits), picture_coding_type (3 bits): 1 = I.
                0x00 -> if (((data[i + 5].toInt() and 0xFF) shr 3) and 0x07 == 1) keyframe = true
            }
            i += 4
        }
        val sets = sequence?.takeIf { changed || keyframe }?.let { listOf(it) }.orEmpty()
        return VideoUnit(data, keyframe, sets, pts, dts)
    }

    /**
     * Largura ÷ altura de um píxel, pelo aspect_ratio_information do cabeçalho (a proporção do ecrã: 4:3, 16:9 ou
     * 2,21:1); 1 sem cabeçalho ou com píxeis quadrados. Os canais SD vêm em 720×576 a mostrar 16:9 ou 4:3.
     */
    val pixelRatio: Float
        get() {
            val h = sequence ?: return 1f
            if (h.size < 8) return 1f
            val width = ((h[4].toInt() and 0xFF) shl 4) or ((h[5].toInt() and 0xFF) shr 4)
            val height = ((h[5].toInt() and 0x0F) shl 8) or (h[6].toInt() and 0xFF)
            val display = when ((h[7].toInt() and 0xFF) shr 4) {
                2 -> 4f / 3
                3 -> 16f / 9
                4 -> 2.21f
                else -> return 1f
            }
            return if (width > 0 && height > 0) display * height / width else 1f
        }

    fun reset() {
        sequence = null
    }

    /** Onde acaba o cabeçalho da sequência que começa em [from]: no primeiro código que não é uma extensão (B5). */
    private fun sequenceEnd(d: ByteArray, from: Int): Int {
        var i = from + 4
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                val code = d[i + 3].toInt() and 0xFF
                // A extensão da sequência (B5, com o id 1 nos 4 bits seguintes) e a de "display" (id 2) ficam.
                if (code != 0xB5) return i
            }
            i++
        }
        return d.size
    }
}
