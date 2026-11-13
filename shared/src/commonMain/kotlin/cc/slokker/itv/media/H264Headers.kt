package cc.slokker.itv.media

/** O que interessa do SPS de H.264 para ler os cabeçalhos das fatias. */
internal class H264Sps(
    val log2MaxFrameNum: Int,
    val frameMbsOnly: Boolean,
    val separateColourPlane: Boolean,
)

/** De uma fatia: o número da imagem e, num campo (imagem entrelaçada enviada por campos), qual dos dois. */
internal class H264Slice(val frameNum: Int, val field: Boolean, val bottom: Boolean)

/**
 * Os cabeçalhos de H.264 que o motor precisa de ler: o SPS e o início das fatias, para juntar os dois campos de uma
 * imagem entrelaçada ("PAFF", comum na TV por cabo e satélite: cada campo vem num PES à parte e os descodificadores do
 * Android e da Apple querem os dois juntos).
 */
internal object H264Headers {
    /** O SPS (o NAL inteiro, com o byte do cabeçalho), ou null se não se perceber. */
    fun sps(nal: ByteArray, from: Int, to: Int): H264Sps? = runCatching {
        val b = Bits(rbsp(nal, from + 1, to))
        val profile = b.u(8)
        b.u(16) // constraint flags + level
        b.ue() // seq_parameter_set_id
        var separate = false
        if (profile in HIGH_PROFILES) {
            val chroma = b.ue()
            if (chroma == 3) separate = b.u(1) == 1
            b.ue() // bit_depth_luma
            b.ue() // bit_depth_chroma
            b.u(1) // qpprime_y_zero_transform_bypass
            if (b.u(1) == 1) {
                // As listas de escala: só se saltam.
                repeat(if (chroma != 3) 8 else 12) { i ->
                    if (b.u(1) == 1) skipScalingList(b, if (i < 6) 16 else 64)
                }
            }
        }
        val log2MaxFrameNum = b.ue() + 4
        when (b.ue()) {
            0 -> b.ue()
            1 -> {
                b.u(1)
                b.se()
                b.se()
                repeat(b.ue()) { b.se() }
            }
        }
        b.ue() // max_num_ref_frames
        b.u(1) // gaps_in_frame_num_allowed
        b.ue() // pic_width_in_mbs
        b.ue() // pic_height_in_map_units
        H264Sps(log2MaxFrameNum, b.u(1) == 1, separate)
    }.getOrNull()

    /** O início de uma fatia (o NAL inteiro, com o byte do cabeçalho), com o [sps] em uso. */
    fun slice(nal: ByteArray, from: Int, to: Int, sps: H264Sps): H264Slice? = runCatching {
        val b = Bits(rbsp(nal, from + 1, minOf(to, from + 1 + 32)))
        b.ue() // first_mb_in_slice
        b.ue() // slice_type
        b.ue() // pic_parameter_set_id
        if (sps.separateColourPlane) b.u(2)
        val frameNum = b.u(sps.log2MaxFrameNum)
        var field = false
        var bottom = false
        if (!sps.frameMbsOnly) {
            field = b.u(1) == 1
            if (field) bottom = b.u(1) == 1
        }
        H264Slice(frameNum, field, bottom)
    }.getOrNull()

    private fun skipScalingList(b: Bits, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + b.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    /** Sem os bytes 0x03 de prevenção (00 00 03 → 00 00). */
    private fun rbsp(d: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var n = 0
        var zeros = 0
        for (i in from until to) {
            val v = d[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            zeros = if (v == 0) zeros + 1 else 0
            out[n++] = d[i]
        }
        return out.copyOf(n)
    }

    private class Bits(private val d: ByteArray) {
        private var pos = 0

        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = pos ushr 3
                if (byte >= d.size) throw IndexOutOfBoundsException()
                v = (v shl 1) or ((d[byte].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                if (zeros > 31) throw IllegalStateException()
            }
            return (1 shl zeros) - 1 + if (zeros > 0) u(zeros) else 0
        }

        fun se(): Int {
            val k = ue()
            return if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
