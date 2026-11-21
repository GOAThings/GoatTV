package cc.slokker.itv.media

/**
 * O que fazer ao vídeo quando faltaram dados ([TsSample.corrupt]): em vez de mandar ao descodificador imagens que
 * apontam para uma que não chegou (os borrões e os quadrados até à imagem-chave seguinte), fica a última imagem boa
 * no ecrã e o vídeo volta na próxima imagem-chave (H.264 IDR, fatias I ou SEI recovery point; HEVC IRAP; MPEG-2 I).
 * O som continua sempre (é outra faixa). Se só se perdeu uma imagem que nenhuma outra usa (B, nal_ref_idc 0), não se
 * espera por nada: basta ela não ir. Num canal com GOP muito comprido, ao fim de [capTicks] sem imagem-chave volta na
 * mesma (melhor uns borrões do que a imagem parada para sempre). Sempre ligado.
 *
 * Os tempos são os do vídeo, seguidos (90 kHz, depois do [TsClock]), ou -1 sem eles.
 */
class ErrorConcealment(private val capTicks: Long = CAP_TICKS) {
    private var waiting = false
    private var since = NONE
    private var held = 0
    /** Depois de voltar numa imagem-chave: as imagens que se mostram antes dela (open GOP) ainda apontam para trás. */
    private var floor = NONE

    /** Imagens que não foram para o descodificador por faltarem dados (as perdidas e as seguradas), desde o início. */
    var dropped = 0
        private set

    /** As que se seguraram na última espera (ou na que está a decorrer), para o registo. */
    var lastRun = 0
        private set

    /** Quantas esperas acabaram pelo limite de tempo (sem imagem-chave). */
    var capped = 0
        private set

    /** Está à espera de uma imagem-chave (a última imagem boa fica no ecrã). */
    val concealing: Boolean get() = waiting

    /**
     * Chegou uma imagem com [TsSample.corrupt]: antes dela perdeu-se uma (ou mais). [onlyNonReference]: era uma imagem
     * de que nenhuma outra depende ([TsSample.lostNonReference]), e as seguintes podem ir.
     */
    fun lost(onlyNonReference: Boolean) {
        dropped++
        if (onlyNonReference) return
        // Mais perdas durante a espera não recomeçam o limite: conta desde a primeira (senão, num canal que perde
        // dados a toda a hora e com GOPs longos, a imagem ficava parada até à imagem-chave).
        if (!waiting) {
            lastRun = 0
            since = NONE
            held = 0
        }
        waiting = true
        floor = NONE
    }

    /** Esta imagem ([keyframe], [pts]) pode ir para o descodificador? */
    fun pass(keyframe: Boolean, pts: Long): Boolean {
        if (waiting) {
            if (keyframe) {
                waiting = false
                floor = if (pts >= 0) pts else NONE
                return true
            }
            if (since == NONE && pts >= 0) since = pts
            held++
            // Para trás no tempo (um salto) também acaba a espera pelo tempo: o limite contava-se do tempo de antes. As
            // imagens B vêm pela ordem de descodificação, com o tempo um pouco antes da anterior: isso não é um salto.
            val late = pts >= 0 && since != NONE && (pts - since >= capTicks || pts < since - BACK_JUMP_TICKS)
            if (late || held >= CAP_FRAMES) {
                waiting = false
                floor = NONE
                capped++
                return true
            }
            dropped++
            lastRun++
            return false
        }
        if (floor != NONE) {
            if (keyframe) {
                floor = NONE
            } else if (pts in 0 until floor) {
                dropped++
                return false
            }
        }
        return true
    }

    /** Outro canal ou outra ligação: esquece a espera (o contador fica). */
    fun reset() {
        waiting = false
        since = NONE
        held = 0
        floor = NONE
    }

    companion object {
        /** Quanto para trás conta como salto no tempo (1 s; as imagens B ficam muito aquém disto). */
        const val BACK_JUMP_TICKS = 90_000L

        /** 2 s a 90 kHz. */
        const val CAP_TICKS = 2L * 90_000
        /** Sem tempos: um limite em imagens (uns 5 s a 60 imagens por segundo, ou campos a 50). */
        const val CAP_FRAMES = 300
        private const val NONE = Long.MIN_VALUE

        /**
         * A imagem de [d] (de [from] a [to], Annex B ou MPEG-2 como vem no PES) é uma de que nenhuma outra depende: H.264
         * com nal_ref_idc 0, HEVC sub-layer non-reference (TRAIL_N, TSA_N, STSA_N, RADL_N, RASL_N…), MPEG-2 B. Na
         * dúvida, false (espera-se pela imagem-chave).
         */
        fun nonReference(codec: Codec, d: ByteArray, from: Int, to: Int): Boolean {
            var i = from
            while (i + 3 < to) {
                if (d[i].toInt() != 0 || d[i + 1].toInt() != 0 || d[i + 2].toInt() != 1) {
                    i++
                    continue
                }
                val h = d[i + 3].toInt() and 0xFF
                when (codec) {
                    Codec.H264 -> {
                        val type = h and 0x1F
                        if (type in 1..5) return type != 5 && (h shr 5) and 0x03 == 0
                    }
                    Codec.HEVC -> {
                        val type = (h shr 1) and 0x3F
                        if (type <= 31) return type <= 14 && type % 2 == 0
                    }
                    Codec.MPEG2_VIDEO -> if (h == 0x00) {
                        // picture_header: temporal_reference (10 bits), picture_coding_type (3 bits): 3 = B.
                        return i + 5 < to && ((d[i + 5].toInt() and 0xFF) shr 3) and 0x07 == 3
                    }
                    else -> return false
                }
                i += 3
            }
            return false
        }

        /**
         * A imagem (Annex B) traz um SEI recovery point (payloadType 6): os canais com intra refresh, sem imagens I, só
         * dizem assim por onde se pode entrar.
         */
        fun recoveryPoint(hevc: Boolean, d: ByteArray): Boolean {
            var i = 0
            while (i + 3 < d.size) {
                if (d[i].toInt() != 0 || d[i + 1].toInt() != 0 || d[i + 2].toInt() != 1) {
                    i++
                    continue
                }
                val h = d[i + 3].toInt() and 0xFF
                val sei = if (hevc) (h shr 1) and 0x3F == 39 else h and 0x1F == 6
                if (sei && seiHasRecoveryPoint(d, i + 3 + if (hevc) 2 else 1)) return true
                i += 3
            }
            return false
        }

        /** Percorre as mensagens de um SEI (tipo e tamanho em bytes de 0xFF somados) até ao fim do NAL. */
        private fun seiHasRecoveryPoint(d: ByteArray, from: Int): Boolean {
            var at = from
            while (at < d.size) {
                if (at + 2 < d.size && d[at].toInt() == 0 && d[at + 1].toInt() == 0 && (d[at + 2].toInt() and 0xFF) <= 1) return false
                var type = 0
                while (at < d.size && d[at].toInt() and 0xFF == 0xFF) { type += 255; at++ }
                if (at >= d.size) return false
                type += d[at++].toInt() and 0xFF
                var size = 0
                while (at < d.size && d[at].toInt() and 0xFF == 0xFF) { size += 255; at++ }
                if (at >= d.size) return false
                size += d[at++].toInt() and 0xFF
                if (type == 6) return true
                at += size
            }
            return false
        }
    }
}
