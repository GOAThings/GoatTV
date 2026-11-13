package cc.slokker.itv.media

/** Uma faixa de áudio numa playlist à parte (EXT-X-MEDIA com URI): a playlist e a língua (LANGUAGE, ou o NAME). */
class HlsAudio(val uri: String, val language: String?) {
    /** A língua em ISO 639-2 (a que o PMT leva), ou null se não disser. */
    val code: String? get() = cc.slokker.itv.data.Languages.threeLetter(language)
}

/**
 * Uma qualidade de uma playlist HLS principal: a playlist dela, o débito, (se o disser) a altura da imagem, o grupo de
 * áudio ([audioGroup], o atributo AUDIO) e, quando o áudio vem em playlists à parte, as do grupo ([audio], a DEFAULT
 * primeiro). Vazio em [audio]: o áudio vem dentro dos segmentos.
 */
class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val height: Int,
    val codecs: String?,
    val audioGroup: String? = null,
    val audio: List<HlsAudio> = emptyList(),
) {
    /** A playlist do áudio principal, quando vem à parte. */
    val audioUri: String? get() = audio.firstOrNull()?.uri
}

/** A chave AES-128 de um segmento: o endereço dela e o vetor inicial (16 bytes; sem `IV`, o número de sequência). */
class HlsKey(val uri: String, val iv: ByteArray)

/** Um pedaço de um endereço: [length] bytes a partir de [offset] ([length] -1 = o ficheiro todo). */
class HlsRange(val uri: String, val offset: Long, val length: Long) {
    fun sameAs(other: HlsRange?): Boolean = other != null && other.uri == uri && other.offset == offset && other.length == length
}

/**
 * Um segmento da playlist: os bytes ([range]), o número de sequência, a duração (s), se vem depois de uma
 * descontinuidade, se vier cifrado (AES-128, o segmento inteiro em CBC) a [key] e, nos fMP4, o segmento de
 * inicialização ([map], o `moov`) que tem de ir antes.
 */
class HlsSegment(
    val range: HlsRange,
    val sequence: Long,
    val duration: Double,
    val discontinuity: Boolean,
    val key: HlsKey?,
    val map: HlsRange?,
) {
    val uri: String get() = range.uri
}

/**
 * Uma playlist HLS lida: ou principal ([variants]), ou de segmentos ([segments]). [unsupported] diz porque é que o
 * motor não a toca (cifra que não é AES-128 por segmento: SAMPLE-AES…), e então outro motor fica com ela.
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
            // As faixas de áudio de cada grupo (EXT-X-MEDIA): o URI (null = dentro dos segmentos), a língua e se é a DEFAULT.
            val audio = LinkedHashMap<String, MutableList<Triple<String?, String?, Boolean>>>()
            var pendingDuration = -1.0
            var discontinuity = false
            // A chave em vigor: o endereço e o IV dito (null = o número de sequência).
            var keyUri: String? = null
            var keyIv: ByteArray? = null
            var map: HlsRange? = null
            // EXT-X-BYTERANGE do próximo segmento, e onde acabou o último pedaço (para os que não dizem o início).
            var pendingRange: LongArray? = null
            var lastEnd = 0L
            var lastUri: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> pendingVariant = attributes(line.substringAfter(':'))
                    line.startsWith("#EXT-X-MEDIA:") -> {
                        val a = attributes(line.substringAfter(':'))
                        val group = a["GROUP-ID"]
                        if (a["TYPE"] == "AUDIO" && group != null) {
                            audio.getOrPut(group) { ArrayList() } += Triple(a["URI"]?.let { resolve(base, it) }, a["LANGUAGE"] ?: a["NAME"], a["DEFAULT"] == "YES")
                        }
                    }
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').toDoubleOrNull() ?: target
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                    line.startsWith("#EXTINF:") -> pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line == "#EXT-X-DISCONTINUITY" -> discontinuity = true
                    line == "#EXT-X-ENDLIST" -> ended = true
                    line.startsWith("#EXT-X-MAP:") -> {
                        val a = attributes(line.substringAfter(':'))
                        val range = a["BYTERANGE"]?.let { byteRange(it) }
                        map = a["URI"]?.let { HlsRange(resolve(base, it), range?.get(1) ?: 0, range?.get(0) ?: -1) }
                    }
                    line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = byteRange(line.substringAfter(':'))
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = attributes(line.substringAfter(':'))
                        when (val method = a["METHOD"]) {
                            null, "NONE" -> { keyUri = null; keyIv = null }
                            "AES-128" -> {
                                val uri = a["URI"]
                                if (uri == null) unsupported = unsupported ?: "AES-128 without a key URI"
                                keyUri = uri?.let { resolve(base, it) }
                                keyIv = a["IV"]?.let { hex(it) }
                                if (a["IV"] != null && keyIv == null) unsupported = unsupported ?: "AES-128 with a bad IV"
                            }
                            else -> unsupported = unsupported ?: "encrypted ($method)"
                        }
                    }
                    line.startsWith("#") -> {}
                    pendingVariant != null -> {
                        val a = pendingVariant
                        val height = a["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull() ?: 0
                        variants += HlsVariant(resolve(base, line), a["BANDWIDTH"]?.toLongOrNull() ?: 0, height, a["CODECS"], a["AUDIO"])
                        pendingVariant = null
                    }
                    pendingDuration >= 0 -> {
                        val key = keyUri?.let { HlsKey(it, keyIv ?: sequenceIv(sequence)) }
                        val uri = resolve(base, line)
                        val range = pendingRange?.let { r ->
                            val offset = if (r[1] >= 0) r[1] else if (uri == lastUri) lastEnd else 0
                            lastEnd = offset + r[0]
                            HlsRange(uri, offset, r[0])
                        } ?: HlsRange(uri, 0, -1)
                        lastUri = uri
                        pendingRange = null
                        segments += HlsSegment(range, sequence++, pendingDuration, discontinuity, key, map)
                        pendingDuration = -1.0
                        discontinuity = false
                    }
                }
            }
            // O áudio à parte de cada qualidade: um grupo com alguma faixa sem URI tem o áudio principal dentro dos
            // segmentos (as outras línguas à parte ficam por agora); senão, todas, a DEFAULT primeiro.
            val withAudio = variants.map { v ->
                val renditions = v.audioGroup?.let { audio[it] }?.takeIf { r -> r.isNotEmpty() && r.none { it.first == null } }
                    ?: return@map v
                val ordered = renditions.sortedByDescending { it.third }.map { HlsAudio(it.first!!, it.second) }
                HlsVariant(v.uri, v.bandwidth, v.height, v.codecs, v.audioGroup, ordered)
            }
            return HlsPlaylist(withAudio, segments, target, ended, unsupported)
        }

        /** `n[@o]` em [n, o] (o -1 quando não vem). */
        private fun byteRange(text: String): LongArray? {
            val length = text.substringBefore('@').trim().toLongOrNull() ?: return null
            val offset = if ('@' in text) text.substringAfter('@').trim().toLongOrNull() ?: return null else -1L
            return longArrayOf(length, offset)
        }

        /** O IV de quando a etiqueta não o diz: o número de sequência em 16 bytes, big-endian. */
        private fun sequenceIv(sequence: Long): ByteArray {
            val iv = ByteArray(16)
            for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
            return iv
        }

        /** `0x` e 32 dígitos hexadecimais em 16 bytes (os que faltam à esquerda são zeros); null se não for isso. */
        private fun hex(text: String): ByteArray? {
            val digits = text.removePrefix("0x").removePrefix("0X")
            if (digits.isEmpty() || digits.length > 32) return null
            val padded = digits.padStart(32, '0')
            val out = ByteArray(16)
            for (i in 0 until 16) out[i] = (padded.substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: return null).toByte()
            return out
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
