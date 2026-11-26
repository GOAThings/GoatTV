package cc.slokker.itv.media

/**
 * Decifra um MP4 fragmentado com Common Encryption (ISO/IEC 23001-7) quando se tem a chave (ClearKey): os esquemas
 * `cenc` (AES-CTR) e `cbcs` (AES-CBC aos padrões, com IV constante). Trabalha no próprio buffer e o tamanho não muda,
 * por isso o resto do motor ([Mp4Demuxer], [TsRemuxer]…) lê um fMP4 normal:
 * - no `moov`, cada `encv`/`enca` volta ao formato original (o do `frma`) e o `sinf` passa a `free`;
 * - em cada `moof`, lê-se onde estão as amostras de cada faixa (`tfhd`/`trun`) e os IV e subsamples (`senc`, o `uuid`
 *   do PIFF, ou `saiz`/`saio`), e as amostras do `mdat` a seguir decifram-se.
 * [keyFor] dá a chave (16 bytes) de um KID (32 hexadecimais em minúsculas), ou null se não a tem: essa faixa fica
 * como vem, `encv`/`enca` incluído (e o demuxer diz que o stream vem cifrado). Os KID sem chave ficam em [missing].
 * [prepare] é chamado em cada `moov` com os KID que ainda não têm chave, antes de se decidir (é aí que o leitor pede
 * as chaves ao servidor de licenças). Um objeto por stream: guarda as faixas do último `moov` visto.
 */
class Cenc(private val keyFor: (String) -> ByteArray?, private val prepare: (Set<String>) -> Unit = {}) {
    private class Track(
        val id: Int,
        val scheme: String,
        val kid: String,
        val ivSize: Int,
        val constantIv: ByteArray?,
        val cryptBlocks: Int,
        val skipBlocks: Int,
        val protected: Boolean,
    ) {
        var aes: Aes? = null
        /** Onde está a entrada `encv`/`enca` e o `sinf` dela no `moov`, e o formato original (para os trocar). */
        var entryAt = -1
        var sinfAt = -1
        var format: String? = null
    }

    private var tracks: Map<Int, Track> = emptyMap()
    /** Os `trex` (o tamanho por defeito das amostras), para as faixas que o `tfhd` e o `trun` não dizem. */
    private var trexSize: Map<Int, Int> = emptyMap()

    /** Os KID que apareceram sem chave (para pedir ao servidor de licenças, ou dizer porque não toca). */
    val missing = LinkedHashSet<String>()

    /** Os KID das faixas cifradas do último `moov` (para pedir as chaves antes da primeira amostra). */
    val kids: Set<String> get() = tracks.values.filter { it.protected }.mapTo(LinkedHashSet()) { it.kid }

    /** Um esquema que não se decifra (`cens`, `cbc1`…), se houver. */
    var unsupported: String? = null
        private set

    /** Há faixas cifradas (o último `moov` tinha `encv`/`enca`). */
    val encrypted: Boolean get() = tracks.values.any { it.protected }

    /** Decifra [data] (de [off] a [off] + [len]: um ou mais pedaços seguidos de caixas de topo), no lugar. */
    fun process(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        val end = off + len
        var at = off
        var lastMoof = -1
        var lastMoofEnd = -1
        while (at + 8 <= end) {
            var size = u32(data, at)
            var header = 8
            if (size == 1L) {
                if (at + 16 > end) return
                size = (u32(data, at + 8) shl 32) or u32(data, at + 12)
                header = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            if (size < header || at + size > end) return
            val boxEnd = (at + size).toInt()
            when (type(data, at + 4)) {
                "moov" -> moov(data, at + header, boxEnd)
                "moof" -> {
                    lastMoof = at
                    lastMoofEnd = boxEnd
                }
                "mdat" -> if (lastMoof >= 0 && encrypted) {
                    moof(data, lastMoof, lastMoofEnd, end)
                    lastMoof = -1
                }
            }
            at = boxEnd
        }
    }

    // ---- moov ----

    private fun moov(d: ByteArray, from: Int, to: Int) {
        val found = HashMap<Int, Track>()
        val sizes = HashMap<Int, Int>()
        children(d, from, to) { type, s, e ->
            when (type) {
                "trak" -> trak(d, s, e)?.let { found[it.id] = it }
                "mvex" -> children(d, s, e) { t, a, _ ->
                    // trex: versão/flags, track_ID, sample_description_index, duration, size, flags.
                    if (t == "trex" && a + 20 <= e) sizes[u32(d, a + 4).toInt()] = u32(d, a + 16).toInt()
                }
            }
        }
        tracks = found
        trexSize = sizes
        unsupported = null
        for (t in found.values) {
            if (t.protected && t.scheme != "cenc" && t.scheme != "cbcs") unsupported = "encrypted fMP4 (${t.scheme})"
        }
        val without = found.values.filter { it.protected && keyFor(it.kid) == null }.mapTo(LinkedHashSet()) { it.kid }
        if (without.isNotEmpty()) prepare(without)
        for (t in found.values) {
            if (t.scheme != "cenc" && t.scheme != "cbcs") continue
            // Só com a chave: sem ela, a entrada fica encv/enca e o demuxer diz porque não toca.
            if (t.protected && aesFor(t) == null) continue
            val f = t.format ?: continue
            if (t.entryAt >= 0) writeType(d, t.entryAt + 4, f)
            // O sinf deixa de contar: o demuxer vê uma entrada normal.
            if (t.sinfAt >= 0) writeType(d, t.sinfAt + 4, "free")
        }
    }

    private fun trak(d: ByteArray, from: Int, to: Int): Track? {
        var id = -1
        var track: Track? = null
        children(d, from, to) { type, s, e ->
            when (type) {
                "tkhd" -> if (s + 24 <= e) id = u32(d, s + if (d[s].toInt() == 1) 20 else 12).toInt()
                "mdia" -> track = find(d, s, e, "minf")?.let { (a, b) -> find(d, a, b, "stbl") }
                    ?.let { (a, b) -> find(d, a, b, "stsd") }
                    ?.let { (a, b) -> stsd(d, a, b) }
            }
        }
        val t = track ?: return null
        return Track(id, t.scheme, t.kid, t.ivSize, t.constantIv, t.cryptBlocks, t.skipBlocks, t.protected).also {
            it.entryAt = t.entryAt
            it.sinfAt = t.sinfAt
            it.format = t.format
        }
    }

    /** O `stsd`: a primeira entrada; se é `encv`/`enca`, a cifra do `sinf` e onde estão (para [moov] os trocar). */
    private fun stsd(d: ByteArray, from: Int, to: Int): Track? {
        // versão/flags (4) + entry_count (4), depois as entradas.
        val at = from + 8
        if (at + 8 > to) return null
        val size = u32(d, at).toInt()
        val fourcc = type(d, at + 4)
        if (fourcc != "encv" && fourcc != "enca") return null
        val end = minOf(at + size, to)
        // Os campos fixos antes das caixas filhas: 78 bytes no vídeo, 28 no áudio (versão 0 do QuickTime).
        var childAt = at + 8 + if (fourcc == "encv") 78 else 28
        if (fourcc == "enca" && childAt + 8 <= end) {
            // QuickTime v1/v2: mais campos antes das caixas.
            when ((d[at + 16].toInt() and 0xFF shl 8) or (d[at + 17].toInt() and 0xFF)) {
                1 -> childAt += 16
                2 -> childAt += 36
            }
        }
        var result: Track? = null
        children(d, childAt, end) { type, s, e ->
            if (type == "sinf" && result == null) {
                val (f, t) = sinf(d, s, e)
                result = t?.also {
                    it.format = f
                    it.entryAt = at
                    it.sinfAt = s - 8
                }
            }
        }
        return result
    }

    /** O `sinf`: o formato original (`frma`) e a cifra (`schm` + `tenc`). */
    private fun sinf(d: ByteArray, from: Int, to: Int): Pair<String?, Track?> {
        var format: String? = null
        var scheme = "cenc"
        var tenc: Track? = null
        children(d, from, to) { type, s, e ->
            when (type) {
                "frma" -> if (s + 4 <= e) format = type(d, s)
                "schm" -> if (s + 8 <= e) scheme = type(d, s + 4)
                "schi" -> find(d, s, e, "tenc")?.let { (a, b) -> tenc = tenc(d, a, b, scheme) }
            }
        }
        val t = tenc ?: return format to null
        return format to Track(0, scheme, t.kid, t.ivSize, t.constantIv, t.cryptBlocks, t.skipBlocks, t.protected)
    }

    private fun tenc(d: ByteArray, from: Int, to: Int, scheme: String): Track? {
        if (from + 24 > to) return null
        val version = d[from].toInt()
        val pattern = d[from + 5].toInt() and 0xFF
        val isProtected = d[from + 6].toInt() != 0
        val ivSize = d[from + 7].toInt() and 0xFF
        val kid = hex(d, from + 8, 16)
        var constant: ByteArray? = null
        if (isProtected && ivSize == 0 && from + 25 <= to) {
            val n = d[from + 24].toInt() and 0xFF
            if (from + 25 + n <= to) constant = d.copyOfRange(from + 25, from + 25 + n)
        }
        val crypt = if (version >= 1) pattern ushr 4 else 0
        val skip = if (version >= 1) pattern and 15 else 0
        return Track(0, scheme, kid, ivSize, constant, crypt, skip, isProtected)
    }

    // ---- moof + mdat ----

    private class Sample(val offset: Long, val size: Int)

    private class AuxInfo(val iv: ByteArray, val clear: IntArray?, val protectedBytes: IntArray?)

    private fun moof(d: ByteArray, moofAt: Int, moofEnd: Int, end: Int) {
        children(d, moofAt + 8, moofEnd) { type, s, e -> if (type == "traf") traf(d, moofAt, s, e, end) }
    }

    private fun traf(d: ByteArray, moofAt: Int, from: Int, to: Int, end: Int) {
        var track: Track? = null
        var base = moofAt.toLong()
        var defaultSize = -1
        val samples = ArrayList<Sample>()
        var aux: List<AuxInfo>? = null
        var saizSizes: IntArray? = null
        var saizDefault = 0
        var saizCount = 0
        var saioOffset = -1L
        var sencAt = -1
        var sencEnd = -1
        var sencFlags = 0
        children(d, from, to) { type, s, e ->
            when (type) {
                "tfhd" -> {
                    val flags = u24(d, s + 1)
                    val id = u32(d, s + 4).toInt()
                    track = tracks[id]
                    var p = s + 8
                    if (flags and 0x1 != 0) { base = (u32(d, p) shl 32) or u32(d, p + 4); p += 8 }
                    if (flags and 0x2 != 0) p += 4
                    if (flags and 0x8 != 0) p += 4
                    if (flags and 0x10 != 0) defaultSize = u32(d, p).toInt()
                    if (defaultSize < 0) defaultSize = trexSize[id] ?: 0
                }
                "trun" -> {
                    val flags = u24(d, s + 1)
                    val count = u32(d, s + 4).toInt()
                    var p = s + 8
                    var offset = if (samples.isEmpty()) base else samples.last().let { it.offset + it.size }
                    if (flags and 0x1 != 0) { offset = base + s32(d, p); p += 4 }
                    if (flags and 0x4 != 0) p += 4
                    for (i in 0 until count) {
                        if (p > e) break
                        if (flags and 0x100 != 0) p += 4
                        val size = if (flags and 0x200 != 0) u32(d, p).toInt().also { p += 4 } else defaultSize
                        if (flags and 0x400 != 0) p += 4
                        if (flags and 0x800 != 0) p += 4
                        samples += Sample(offset, size)
                        offset += size
                    }
                }
                "senc" -> { sencAt = s; sencEnd = e; sencFlags = u24(d, s + 1) }
                "uuid" -> if (s + 16 <= e && hex(d, s, 16) == PIFF_SENC) { sencAt = s + 16; sencEnd = e; sencFlags = u24(d, s + 17) }
                "saiz" -> {
                    val flags = u24(d, s + 1)
                    var p = s + 4
                    if (flags and 1 != 0) p += 8
                    saizDefault = d[p].toInt() and 0xFF
                    saizCount = u32(d, p + 1).toInt()
                    if (saizDefault == 0) saizSizes = IntArray(saizCount) { d[p + 5 + it].toInt() and 0xFF }
                }
                "saio" -> {
                    val version = d[s].toInt()
                    val flags = u24(d, s + 1)
                    var p = s + 4
                    if (flags and 1 != 0) p += 8
                    if (u32(d, p) >= 1) saioOffset = if (version == 0) u32(d, p + 4) else (u32(d, p + 4) shl 32) or u32(d, p + 8)
                }
            }
        }
        val t = track ?: return
        if (!t.protected) return
        val aes = aesFor(t) ?: return
        aux = when {
            sencAt >= 0 -> senc(d, sencAt, sencEnd, sencFlags, t, samples.size)
            saioOffset >= 0 -> {
                val start = base + saioOffset
                val sizes = saizSizes ?: IntArray(saizCount) { saizDefault }
                var p = start
                sizes.map { n -> auxEntry(d, p.toInt(), p.toInt() + n, t).also { p += n } }
            }
            t.constantIv != null -> List(samples.size) { AuxInfo(t.constantIv, null, null) }
            else -> null
        }
        val info = aux ?: return
        for ((i, sample) in samples.withIndex()) {
            val a = info.getOrNull(i) ?: break
            val at = sample.offset
            if (at < 0 || at + sample.size > end) break
            decryptSample(aes, t, a, d, at.toInt(), sample.size)
        }
    }

    private fun senc(d: ByteArray, from: Int, to: Int, flags: Int, t: Track, count: Int): List<AuxInfo> {
        var p = from + 4
        if (flags and 1 != 0) p += 20 // PIFF: AlgorithmID (3) + IV_size (1) + KID (16)
        val n = u32(d, p).toInt()
        p += 4
        val out = ArrayList<AuxInfo>(n)
        val ivSize = t.ivSize
        for (i in 0 until n) {
            if (p + ivSize > to) break
            val iv = if (ivSize > 0) d.copyOfRange(p, p + ivSize) else t.constantIv ?: ByteArray(16)
            p += ivSize
            if (flags and 2 != 0) {
                if (p + 2 > to) break
                val subs = (d[p].toInt() and 0xFF shl 8) or (d[p + 1].toInt() and 0xFF)
                p += 2
                val clear = IntArray(subs)
                val enc = IntArray(subs)
                for (j in 0 until subs) {
                    if (p + 6 > to) break
                    clear[j] = (d[p].toInt() and 0xFF shl 8) or (d[p + 1].toInt() and 0xFF)
                    enc[j] = u32(d, p + 2).toInt()
                    p += 6
                }
                out += AuxInfo(iv, clear, enc)
            } else {
                out += AuxInfo(iv, null, null)
            }
        }
        return out
    }

    /** Uma entrada do `saiz`/`saio`: o IV e, se couberem, os subsamples. */
    private fun auxEntry(d: ByteArray, from: Int, to: Int, t: Track): AuxInfo {
        val ivSize = t.ivSize
        val iv = if (ivSize > 0 && from + ivSize <= to) d.copyOfRange(from, from + ivSize) else t.constantIv ?: ByteArray(16)
        var p = from + ivSize
        if (p + 2 > to) return AuxInfo(iv, null, null)
        val subs = (d[p].toInt() and 0xFF shl 8) or (d[p + 1].toInt() and 0xFF)
        p += 2
        val clear = IntArray(subs)
        val enc = IntArray(subs)
        for (j in 0 until subs) {
            if (p + 6 > to) break
            clear[j] = (d[p].toInt() and 0xFF shl 8) or (d[p + 1].toInt() and 0xFF)
            enc[j] = u32(d, p + 2).toInt()
            p += 6
        }
        return AuxInfo(iv, clear, enc)
    }

    private fun aesFor(t: Track): Aes? {
        t.aes?.let { return it }
        // Sem a chave, volta a perguntar-se no moof seguinte (pode ter chegado do servidor de licenças).
        val key = keyFor(t.kid)
        if (key == null || key.size != 16) {
            missing += t.kid
            return null
        }
        missing -= t.kid
        return Aes(key).also { t.aes = it }
    }

    private fun decryptSample(aes: Aes, t: Track, a: AuxInfo, d: ByteArray, at: Int, size: Int) {
        val iv = ByteArray(16)
        a.iv.copyInto(iv, 0, 0, minOf(16, a.iv.size))
        val clear = a.clear
        val enc = a.protectedBytes
        if (t.scheme == "cbcs") {
            if (clear == null || enc == null) {
                cbcs(aes, t, iv, d, at, size)
            } else {
                var p = at
                for (j in clear.indices) {
                    p += clear[j]
                    val n = enc[j]
                    if (p + n > at + size) break
                    // No cbcs o IV volta ao início em cada subsample.
                    cbcs(aes, t, iv.copyOf(), d, p, n)
                    p += n
                }
            }
            return
        }
        // cenc: um só fluxo CTR pela amostra, só nos bytes cifrados.
        val state = Aes.CtrState(iv)
        if (clear == null || enc == null) {
            aes.ctr(state, d, at, size)
            return
        }
        var p = at
        for (j in clear.indices) {
            p += clear[j]
            val n = enc[j]
            if (p + n > at + size) break
            aes.ctr(state, d, p, n)
            p += n
        }
    }

    /** cbcs: [crypt] blocos cifrados e [skip] em claro, a repetir; sem padrão (0:0, o áudio), todos os blocos inteiros. */
    private fun cbcs(aes: Aes, t: Track, iv: ByteArray, d: ByteArray, at: Int, len: Int) {
        val crypt = t.cryptBlocks
        val skip = t.skipBlocks
        if (crypt == 0) {
            aes.cbcDecrypt(iv, d, at, len / 16)
            return
        }
        var p = at
        var left = len
        while (left >= 16 * crypt) {
            aes.cbcDecrypt(iv, d, p, crypt)
            p += 16 * crypt
            left -= 16 * crypt
            val s = minOf(16 * skip, left)
            p += s
            left -= s
        }
    }

    // ---- caixas ----

    private inline fun children(d: ByteArray, from: Int, to: Int, each: (String, Int, Int) -> Unit) {
        var at = from
        while (at + 8 <= to) {
            val size = u32(d, at)
            if (size < 8 || at + size > to) return
            each(type(d, at + 4), at + 8, (at + size).toInt())
            at += size.toInt()
        }
    }

    private fun find(d: ByteArray, from: Int, to: Int, want: String): Pair<Int, Int>? {
        var r: Pair<Int, Int>? = null
        children(d, from, to) { type, s, e -> if (r == null && type == want) r = s to e }
        return r
    }

    companion object {
        private const val PIFF_SENC = "a2394f525a9b4f14a2446c427c648df4"

        /** [d] tem um `moov` nas caixas de topo (o segmento de inicialização vem à frente). */
        fun hasMoov(d: ByteArray): Boolean {
            var at = 0
            while (at + 8 <= d.size) {
                val size = u32(d, at)
                if (type(d, at + 4) == "moov") return true
                if (size < 8 || at + size > d.size) return false
                at += size.toInt()
            }
            return false
        }

        private fun u32(d: ByteArray, at: Int): Long =
            ((d[at].toLong() and 0xFF) shl 24) or ((d[at + 1].toLong() and 0xFF) shl 16) or ((d[at + 2].toLong() and 0xFF) shl 8) or (d[at + 3].toLong() and 0xFF)

        private fun s32(d: ByteArray, at: Int): Int = u32(d, at).toInt()

        private fun u24(d: ByteArray, at: Int): Int = ((d[at].toInt() and 0xFF) shl 16) or ((d[at + 1].toInt() and 0xFF) shl 8) or (d[at + 2].toInt() and 0xFF)

        private fun type(d: ByteArray, at: Int): String = CharArray(4) { (d[at + it].toInt() and 0xFF).toChar() }.concatToString()

        private fun writeType(d: ByteArray, at: Int, t: String) {
            for (i in 0 until 4) d[at + i] = t[i].code.toByte()
        }

        private fun hex(d: ByteArray, at: Int, n: Int): String {
            val sb = StringBuilder(n * 2)
            for (i in at until at + n) {
                val v = d[i].toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 15])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
