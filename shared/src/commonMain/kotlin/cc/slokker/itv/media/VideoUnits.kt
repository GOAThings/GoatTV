package cc.slokker.itv.media

/**
 * Uma imagem de H.264 ou HEVC pronta para o descodificador do sistema: os NAL com o tamanho à frente (4 bytes, o
 * formato "AVCC"/"HVCC" que o VideoToolbox e o MediaCodec em modo MP4 aceitam), sem os conjuntos de parâmetros.
 * [parameterSets] são os desta imagem quando ela os traz (VPS, SPS e PPS, por esta ordem; só SPS e PPS no H.264):
 * quem descodifica cria (ou recria) o formato com eles.
 */
class VideoUnit(val data: ByteArray, val keyframe: Boolean, val parameterSets: List<ByteArray>)

/**
 * Lê as imagens H.264 e HEVC no formato do TS ("Annex B": cada NAL começa por 00 00 01 ou 00 00 00 01). Guarda os
 * conjuntos de parâmetros vistos, porque muitos canais só os mandam nas imagens-chave: uma imagem sem eles usa os
 * últimos.
 */
class NalReader(private val hevc: Boolean) {
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** Os conjuntos de parâmetros já vistos, ou vazio se ainda falta algum (antes disso não se pode descodificar). */
    val parameterSets: List<ByteArray>
        get() {
            val s = sps ?: return emptyList()
            val p = pps ?: return emptyList()
            if (hevc) return listOf(vps ?: return emptyList(), s, p)
            return listOf(s, p)
        }

    fun unit(annexB: ByteArray): VideoUnit {
        val nals = split(annexB)
        var keyframe = false
        var changed = false
        var size = 0
        val keep = ArrayList<IntArray>(nals.size)
        for (n in nals) {
            val type = type(annexB, n[0])
            when (role(type)) {
                Role.VPS -> changed = remember(annexB, n, 0) or changed
                Role.SPS -> changed = remember(annexB, n, 1) or changed
                Role.PPS -> changed = remember(annexB, n, 2) or changed
                // O delimitador e o enchimento não vão para o descodificador.
                Role.SKIP -> {}
                Role.KEY -> {
                    keyframe = true
                    keep += n
                    size += 4 + n[1] - n[0]
                }
                Role.OTHER -> {
                    keep += n
                    size += 4 + n[1] - n[0]
                }
            }
        }
        val out = ByteArray(size)
        var at = 0
        for (n in keep) {
            val len = n[1] - n[0]
            out[at] = (len ushr 24).toByte()
            out[at + 1] = (len ushr 16).toByte()
            out[at + 2] = (len ushr 8).toByte()
            out[at + 3] = len.toByte()
            annexB.copyInto(out, at + 4, n[0], n[1])
            at += 4 + len
        }
        return VideoUnit(out, keyframe, if (changed || keyframe) parameterSets else emptyList())
    }

    fun reset() {
        vps = null
        sps = null
        pps = null
    }

    private enum class Role { VPS, SPS, PPS, KEY, SKIP, OTHER }

    private fun type(d: ByteArray, at: Int): Int = if (hevc) (d[at].toInt() shr 1) and 0x3F else d[at].toInt() and 0x1F

    private fun role(type: Int): Role = if (hevc) {
        when (type) {
            32 -> Role.VPS
            33 -> Role.SPS
            34 -> Role.PPS
            // IRAP: BLA, IDR e CRA (16–21); os canais começam a meio, num destes.
            in 16..21 -> Role.KEY
            35, 38 -> Role.SKIP // AUD, filler
            else -> Role.OTHER
        }
    } else {
        when (type) {
            7 -> Role.SPS
            8 -> Role.PPS
            5 -> Role.KEY
            9, 12 -> Role.SKIP // AUD, filler
            else -> Role.OTHER
        }
    }

    /** Guarda o conjunto [slot]; true se for diferente do que havia. */
    private fun remember(d: ByteArray, n: IntArray, slot: Int): Boolean {
        val bytes = d.copyOfRange(n[0], n[1])
        val old = when (slot) {
            0 -> vps
            1 -> sps
            else -> pps
        }
        if (old != null && old.contentEquals(bytes)) return false
        when (slot) {
            0 -> vps = bytes
            1 -> sps = bytes
            else -> pps = bytes
        }
        return true
    }

    companion object {
        /** Onde começa e acaba cada NAL de [d] (sem o código de início nem os zeros que sobram no fim). */
        fun split(d: ByteArray): List<IntArray> {
            val out = ArrayList<IntArray>()
            var start = -1
            var i = 0
            while (i + 2 < d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                    if (start >= 0) out += intArrayOf(start, trimZeros(d, start, i))
                    i += 3
                    start = i
                } else {
                    i++
                }
            }
            if (start >= 0 && start < d.size) out += intArrayOf(start, trimZeros(d, start, d.size))
            return out.filter { it[1] > it[0] }
        }

        private fun trimZeros(d: ByteArray, from: Int, to: Int): Int {
            var end = to
            while (end > from && d[end - 1].toInt() == 0) end--
            return end
        }
    }
}
