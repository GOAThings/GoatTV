package cc.slokker.itv.media

/** Uma qualidade de uma playlist HLS principal: a playlist dela, o débito e (se o disser) a altura da imagem. */
class HlsVariant(val uri: String, val bandwidth: Long, val height: Int, val codecs: String?)

/** Um segmento da playlist: o endereço, o número de sequência, a duração (s) e se vem depois de uma descontinuidade. */
class HlsSegment(val uri: String, val sequence: Long, val duration: Double, val discontinuity: Boolean)

/**
 * Uma playlist HLS lida: ou principal ([variants]), ou de segmentos ([segments]). [unsupported] diz porque é que o
 * motor não a toca (segmentos fMP4, cifra que não é AES-128 por segmento…), e então outro motor fica com ela.
 */
class HlsPlaylist(
    val variants: List<HlsVariant>,
    val segments: List<HlsSegment>,
    val targetDuration: Double,
    val ended: Boolean,
    val unsupported: String?,
) {
    val isMaster: Boolean get() = variants.isNotEmpty()

    companion object {
        /** Lê [text] (a playlist em [base], para os endereços relativos). */
        fun parse(text: String, base: String): HlsPlaylist {
            val variants = ArrayList<HlsVariant>()
            val segments = ArrayList<HlsSegment>()
            var target = 6.0
            var sequence = 0L
            var ended = false
            var unsupported: String? = null
            var pendingVariant: Map<String, String>? = null
            var pendingDuration = -1.0
            var discontinuity = false
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> pendingVariant = attributes(line.substringAfter(':'))
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').toDoubleOrNull() ?: target
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                    line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                    line == "#EXT-X-ENDLIST" -> ended = true
                    // fMP4 e cifra: este motor ainda não os lê.
                    line.startsWith("#EXT-X-MAP:") -> unsupported = unsupported ?: "fMP4 segments"
                    line.startsWith("#EXT-X-KEY:") -> {
                        val method = attributes(line.substringAfter(':'))["METHOD"]
                        if (method != null && method != "NONE") unsupported = unsupported ?: "encrypted ($method)"
                    }
                    line.startsWith("#") -> {}
                    pendingVariant != null -> {
                        val a = pendingVariant
                        val height = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0
                        variants += HlsVariant(resolve(base, line), a["BANDWIDTH"]?.toLongOrNull() ?: 0, height, a["CODECS"])
                        pendingVariant = null
                    }
                    pendingDuration >= 0 -> {
                        segments += HlsSegment(resolve(base, line), sequence++, pendingDuration, discontinuity)
                        pendingDuration = -1.0
                        discontinuity = false
                    }
                }
            }
            return HlsPlaylist(variants, segments, target, ended, unsupported)
        }

        /** Os atributos de uma etiqueta (`BANDWIDTH=1280000,CODECS="avc1.64001f,mp4a.40.2"`), com as aspas tiradas. */
        fun attributes(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            while (i < text.length) {
                val eq = text.indexOf('=', i)
                if (eq < 0) break
                val key = text.substring(i, eq).trim().trimStart(',').trim()
                var j = eq + 1
                val value: String
                if (j < text.length && text[j] == '"') {
                    val end = text.indexOf('"', j + 1).let { if (it < 0) text.length else it }
                    value = text.substring(j + 1, end)
                    j = end + 1
                } else {
                    val end = text.indexOf(',', j).let { if (it < 0) text.length else it }
                    value = text.substring(j, end).trim()
                    j = end
                }
                out[key] = value
                i = j + 1
            }
            return out
        }

        /** [ref] em relação a [base] (como um navegador: absoluto, a partir da raiz, ou ao lado). */
        fun resolve(base: String, ref: String): String {
            if (ref.contains("://")) return ref
            val scheme = base.substringBefore("://", "")
            val afterScheme = base.substringAfter("://")
            val authority = afterScheme.substringBefore('/')
            val origin = "$scheme://$authority"
            if (ref.startsWith("//")) return "$scheme:$ref"
            if (ref.startsWith("/")) return origin + ref
            val path = afterScheme.substring(authority.length).substringBefore('?').substringBefore('#')
            val dir = path.substringBeforeLast('/', "")
            return "$origin$dir/$ref"
        }
    }
}
