package cc.slokker.itv.media

/**
 * O que o SPS diz do vídeo: o tamanho que se vê (sem o recorte), a cadência (0 se não disser), a profundidade e a cor
 * pelos códigos do VUI (H.273: [primaries] 1 = BT.709, 9 = BT.2020; [transfer] 1 = BT.709, 16 = PQ/HDR10, 18 = HLG;
 * [matrix]; -1 quando não diz). É por isto que a TV muda para HDR e para a cadência do canal.
 */
class VideoInfo(
    val width: Int,
    val height: Int,
    val fps: Float,
    val bitDepth: Int,
    val primaries: Int,
    val transfer: Int,
    val matrix: Int,
    val fullRange: Boolean,
) {
    val isHdr10: Boolean get() = transfer == 16
    val isHlg: Boolean get() = transfer == 18
    val isHdr: Boolean get() = isHdr10 || isHlg

    companion object {
        /** O SPS de H.264 (o NAL inteiro, com o byte do cabeçalho), ou null se não se perceber. */
        fun h264(nal: ByteArray, from: Int = 0, to: Int = nal.size): VideoInfo? = runCatching {
            val b = SpsBits(SpsBits.rbsp(nal, from + 1, to))
            val profile = b.u(8)
            b.u(16)
            b.ue()
            var chroma = 1
            var depth = 8
            if (profile in HIGH_PROFILES) {
                chroma = b.ue()
                if (chroma == 3) b.u(1)
                depth = b.ue() + 8
                b.ue()
                b.u(1)
                if (b.u(1) == 1) {
                    repeat(if (chroma != 3) 8 else 12) { i ->
                        if (b.u(1) == 1) b.skipScalingList(if (i < 6) 16 else 64)
                    }
                }
            }
            b.ue() // log2_max_frame_num
            when (b.ue()) {
                0 -> b.ue()
                1 -> {
                    b.u(1)
                    b.se()
                    b.se()
                    repeat(b.ue()) { b.se() }
                }
            }
            b.ue()
            b.u(1)
            val widthMbs = b.ue() + 1
            val heightUnits = b.ue() + 1
            val frameMbsOnly = b.u(1) == 1
            if (!frameMbsOnly) b.u(1)
            b.u(1)
            var width = widthMbs * 16
            var height = (if (frameMbsOnly) 1 else 2) * heightUnits * 16
            if (b.u(1) == 1) {
                val cropX = if (chroma == 0 || chroma == 3) 1 else 2
                val cropY = (if (chroma == 1) 2 else 1) * (if (frameMbsOnly) 1 else 2)
                width -= (b.ue() + b.ue()) * cropX
                height -= (b.ue() + b.ue()) * cropY
            }
            var color = intArrayOf(-1, -1, -1, 0)
            var fps = 0f
            if (b.u(1) == 1) {
                color = b.signalType()
                if (b.u(1) == 1) {
                    b.ue()
                    b.ue()
                }
                if (b.u(1) == 1) {
                    val units = b.u32()
                    val scale = b.u32()
                    // No H.264 o relógio conta campos: uma imagem são dois tiques.
                    if (units > 0) fps = (scale / (2.0 * units)).toFloat()
                }
            }
            VideoInfo(width, height, fps, depth, color[0], color[1], color[2], color[3] == 1)
        }.getOrNull()

        /** O SPS de HEVC (o NAL inteiro, com os 2 bytes do cabeçalho), ou null se não se perceber. */
        fun hevc(nal: ByteArray, from: Int = 0, to: Int = nal.size): VideoInfo? = runCatching {
            val b = SpsBits(SpsBits.rbsp(nal, from + 2, to))
            b.u(4)
            val maxSubLayers = b.u(3)
            b.u(1)
            // profile_tier_level: o geral (88 + 8 bits) e o que houver das subcamadas.
            b.skip(96)
            val profilePresent = BooleanArray(maxSubLayers)
            val levelPresent = BooleanArray(maxSubLayers)
            for (i in 0 until maxSubLayers) {
                profilePresent[i] = b.u(1) == 1
                levelPresent[i] = b.u(1) == 1
            }
            if (maxSubLayers > 0) for (i in maxSubLayers until 8) b.u(2)
            for (i in 0 until maxSubLayers) {
                if (profilePresent[i]) b.skip(88)
                if (levelPresent[i]) b.skip(8)
            }
            b.ue()
            val chroma = b.ue()
            if (chroma == 3) b.u(1)
            var width = b.ue()
            var height = b.ue()
            if (b.u(1) == 1) {
                val cropX = if (chroma == 1 || chroma == 2) 2 else 1
                val cropY = if (chroma == 1) 2 else 1
                width -= (b.ue() + b.ue()) * cropX
                height -= (b.ue() + b.ue()) * cropY
            }
            val depth = b.ue() + 8
            b.ue()
            val log2MaxPocLsb = b.ue() + 4
            val orderingAll = b.u(1) == 1
            for (i in (if (orderingAll) 0 else maxSubLayers)..maxSubLayers) {
                b.ue()
                b.ue()
                b.ue()
            }
            repeat(6) { b.ue() }
            if (b.u(1) == 1 && b.u(1) == 1) b.skipHevcScalingLists()
            b.u(1) // amp
            b.u(1) // sao
            if (b.u(1) == 1) {
                // pcm
                b.u(4)
                b.u(4)
                b.ue()
                b.ue()
                b.u(1)
            }
            b.skipShortTermRefPicSets(b.ue())
            if (b.u(1) == 1) {
                repeat(b.ue()) {
                    b.u(log2MaxPocLsb)
                    b.u(1)
                }
            }
            b.u(1)
            b.u(1)
            var color = intArrayOf(-1, -1, -1, 0)
            var fps = 0f
            if (b.u(1) == 1) {
                color = b.signalType()
                if (b.u(1) == 1) {
                    b.ue()
                    b.ue()
                }
                b.u(1) // neutral_chroma
                b.u(1) // field_seq
                b.u(1) // frame_field_info
                if (b.u(1) == 1) repeat(4) { b.ue() } // default_display_window
                if (b.u(1) == 1) {
                    val units = b.u32()
                    val scale = b.u32()
                    if (units > 0) fps = (scale / units.toDouble()).toFloat()
                }
            }
            VideoInfo(width, height, fps, depth, color[0], color[1], color[2], color[3] == 1)
        }.getOrNull()

        private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    }
}

/** A leitura de bits do SPS (Exp-Golomb e o resto), sem os bytes de prevenção. */
internal class SpsBits(private val d: ByteArray) {
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

    fun u32(): Long = (u(16).toLong() shl 16) or u(16).toLong()

    fun skip(n: Int) {
        pos += n
        if ((pos - 1) ushr 3 >= d.size) throw IndexOutOfBoundsException()
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

    /** O aspect ratio, o overscan e o video_signal_type do VUI: [primárias, transferência, matriz, gama completa]. */
    fun signalType(): IntArray {
        if (u(1) == 1 && u(8) == 255) u32() // aspect_ratio_idc 255: sar_width, sar_height
        if (u(1) == 1) u(1) // overscan
        val out = intArrayOf(-1, -1, -1, 0)
        if (u(1) == 1) {
            u(3) // video_format
            out[3] = u(1)
            if (u(1) == 1) {
                out[0] = u(8)
                out[1] = u(8)
                out[2] = u(8)
            }
        }
        return out
    }

    fun skipScalingList(size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    fun skipHevcScalingLists() {
        for (sizeId in 0 until 4) {
            var matrixId = 0
            while (matrixId < 6) {
                if (u(1) == 0) {
                    ue() // scaling_list_pred_matrix_id_delta
                } else {
                    val coefs = minOf(64, 1 shl (4 + (sizeId shl 1)))
                    if (sizeId > 1) se()
                    repeat(coefs) { se() }
                }
                matrixId += if (sizeId == 3) 3 else 1
            }
        }
    }

    /** st_ref_pic_set() para cada um dos [count] conjuntos do SPS (NumDeltaPocs contado como a norma diz). */
    fun skipShortTermRefPicSets(count: Int) {
        val numDeltaPocs = IntArray(count)
        for (idx in 0 until count) {
            val inter = idx != 0 && u(1) == 1
            if (inter) {
                // No SPS, o conjunto de referência é sempre o anterior.
                val ref = numDeltaPocs[idx - 1]
                u(1)
                ue()
                var n = 0
                for (j in 0..ref) {
                    val used = u(1) == 1
                    val useDelta = if (!used) u(1) == 1 else true
                    if (used || useDelta) n++
                }
                numDeltaPocs[idx] = n
            } else {
                val negative = ue()
                val positive = ue()
                repeat(negative + positive) {
                    ue()
                    u(1)
                }
                numDeltaPocs[idx] = negative + positive
            }
        }
    }

    companion object {
        /** Sem os bytes 0x03 de prevenção (00 00 03 → 00 00). */
        fun rbsp(d: ByteArray, from: Int, to: Int): ByteArray {
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
    }
}
