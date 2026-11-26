package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CencTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun aesKnownAnswers() {
        // FIPS-197 C.1.
        val aes = Aes(hex("000102030405060708090a0b0c0d0e0f"))
        val out = ByteArray(16)
        aes.encryptBlock(hex("00112233445566778899aabbccddeeff"), 0, out, 0)
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", out.hex())
        aes.decryptBlock(out, 0, out, 0)
        assertEquals("00112233445566778899aabbccddeeff", out.hex())
        // SP 800-38A F.5.1 (CTR) e F.2.2 (CBC).
        val k = Aes(hex("2b7e151628aed2a6abf7158809cf4f3c"))
        val ctr = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        k.ctr(Aes.CtrState(hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")), ctr, 0, ctr.size)
        assertEquals("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff", ctr.hex())
        val cbc = hex("7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b2")
        k.cbcDecrypt(hex("000102030405060708090a0b0c0d0e0f"), cbc, 0, 2)
        assertEquals("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51", cbc.hex())
        // O CTR aos bocados continua o mesmo fluxo (os subsamples).
        val parts = hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51")
        val state = Aes.CtrState(hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"))
        k.ctr(state, parts, 0, 5)
        k.ctr(state, parts, 5, 20)
        k.ctr(state, parts, 25, 7)
        assertEquals("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff", parts.hex())
    }

    // ---- um fMP4 cifrado feito à mão ----

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return u32(8 + body.size) + type.encodeToByteArray() + body
    }
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun full(version: Int, flags: Int) = byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte())

    private val kid = hex("0123456789abcdef0123456789abcdef")
    private val key = hex("fedcba9876543210fedcba9876543210")
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0x33, 0x44)

    private fun init(scheme: String, tencVersion: Int, pattern: Int, ivSize: Int, constantIv: ByteArray?): ByteArray {
        val avcC = box("avcC", byteArrayOf(1, 0x42, 0, 0x1E, 0xFF.toByte(), 0xE1.toByte()), u16(sps.size), sps, byteArrayOf(1), u16(pps.size), pps)
        val tenc = box(
            "tenc", full(tencVersion, 0), byteArrayOf(0, pattern.toByte(), 1, ivSize.toByte()), kid,
            constantIv?.let { byteArrayOf(it.size.toByte()) + it } ?: ByteArray(0),
        )
        val sinf = box("sinf", box("frma", "avc1".encodeToByteArray()), box("schm", full(0, 0), scheme.encodeToByteArray(), u32(0x10000)), box("schi", tenc))
        val encv = box("encv", ByteArray(78).also { it[7] = 1 }, avcC, sinf)
        val stsd = box("stsd", full(0, 0), u32(1), encv)
        val trak = box(
            "trak",
            box("tkhd", full(0, 3), u32(0), u32(0), u32(1), ByteArray(64)),
            box("mdia", box("mdhd", full(0, 0), u32(0), u32(0), u32(90000), u32(0), ByteArray(4)), box("hdlr", full(0, 0), u32(0), "vide".encodeToByteArray(), ByteArray(13)), box("minf", box("stbl", stsd))),
        )
        val mvex = box("mvex", box("trex", full(0, 0), u32(1), u32(1), u32(3000), u32(0), u32(0)))
        return box("ftyp", "iso6".encodeToByteArray(), u32(0)) + box("moov", box("mvhd", full(0, 0), ByteArray(96)), trak, mvex)
    }

    /** Um segmento com [samples] (já cifradas), os [ivs] e os subsamples (pares claro/cifrado) no senc. */
    private fun segment(samples: List<ByteArray>, ivs: List<ByteArray>, subs: List<List<Pair<Int, Int>>>): ByteArray {
        var senc = full(0, 2) + u32(samples.size)
        for (i in samples.indices) {
            senc += ivs[i] + u16(subs[i].size)
            for ((c, e) in subs[i]) senc += u16(c) + u32(e)
        }
        fun moof(dataOffset: Int) = box(
            "moof",
            box("mfhd", full(0, 0), u32(1)),
            box(
                "traf",
                box("tfhd", full(0, 0x20000), u32(1)),
                box("tfdt", full(1, 0), u32(0), u32(0)),
                box("trun", full(0, 0x201), u32(samples.size), u32(dataOffset), samples.fold(ByteArray(0)) { a, s -> a + u32(s.size) }),
                box("senc", senc),
            ),
        )
        val size = moof(0).size
        return moof(size + 8) + box("mdat", samples.fold(ByteArray(0)) { a, s -> a + s })
    }

    /** Uma imagem H.264 em comprimentos de 4 bytes: o NAL de [n] bytes. */
    private fun picture(n: Int, seed: Int): ByteArray = u32(n) + ByteArray(n) { (it * 7 + seed).toByte() }.also { it[0] = 0x65 }

    private fun demux(bytes: ByteArray): Pair<Mp4Demuxer, List<TsSample>> {
        val samples = ArrayList<TsSample>()
        val d = Mp4Demuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) { samples += sample }
        })
        d.feed(bytes, 0, bytes.size)
        d.flush()
        return d to samples
    }

    @Test
    fun cencCtrWithSubsamples() {
        val clear = listOf(picture(100, 1), picture(53, 9))
        // O cabeçalho do NAL (5 bytes) em claro; o resto cifrado, num só fluxo CTR por amostra.
        val subs = listOf(listOf(5 to 60, 20 to 19), listOf(5 to 52))
        val ivs = listOf(hex("1111111111111111"), hex("2222222222222222"))
        val aes = Aes(key)
        val encrypted = clear.mapIndexed { i, s ->
            val e = s.copyOf()
            val st = Aes.CtrState(ivs[i] + ByteArray(8))
            var p = 0
            for ((c, n) in subs[i]) { p += c; aes.ctr(st, e, p, n); p += n }
            e
        }
        val bytes = init("cenc", 0, 0, 8, null) + segment(encrypted, ivs, subs)
        val cenc = Cenc({ if (it == kid.hex()) key else null })
        cenc.process(bytes)
        assertNull(cenc.unsupported)
        val (demuxer, samples) = demux(bytes)
        assertNull(demuxer.unsupported)
        assertEquals(2, samples.size)
        // Annex B: o SPS/PPS à frente da imagem-chave, e o NAL decifrado.
        val annexB0 = samples[0].data
        assertContentEquals(clear[0].copyOfRange(4, clear[0].size), annexB0.copyOfRange(annexB0.size - 100, annexB0.size))
        assertContentEquals(clear[1].copyOfRange(4, clear[1].size), samples[1].data.copyOfRange(samples[1].data.size - 53, samples[1].data.size))
    }

    @Test
    fun cbcsPatternWithConstantIv() {
        val clear = listOf(picture(400, 3))
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val subs = listOf(listOf(5 to 320, 79 to 0))
        val aes = Aes(key)
        // 1 bloco cifrado, 9 em claro; o IV volta ao início em cada subsample; os bytes que não enchem um bloco ficam.
        val e = clear[0].copyOf()
        var p = 5
        var left = 320
        val chain = iv.copyOf()
        while (left >= 16) {
            for (j in 0 until 16) e[p + j] = (e[p + j].toInt() xor chain[j].toInt()).toByte()
            aes.encryptBlock(e, p, e, p)
            e.copyInto(chain, 0, p, p + 16)
            p += 16; left -= 16
            val s = minOf(16 * 9, left)
            p += s; left -= s
        }
        val bytes = init("cbcs", 1, 0x19, 0, iv) + segment(listOf(e), listOf(ByteArray(0)), subs)
        Cenc({ key }).process(bytes)
        val (demuxer, samples) = demux(bytes)
        assertNull(demuxer.unsupported)
        val out = samples.single().data
        assertContentEquals(clear[0].copyOfRange(4, clear[0].size), out.copyOfRange(out.size - 400, out.size))
    }

    @Test
    fun withoutTheKeyItStaysEncrypted() {
        val bytes = init("cenc", 0, 0, 8, null) + segment(listOf(picture(40, 1)), listOf(hex("1111111111111111")), listOf(listOf(5 to 39)))
        val asked = ArrayList<Set<String>>()
        val cenc = Cenc({ null }, { asked += it })
        cenc.process(bytes)
        assertEquals(listOf(setOf(kid.hex())), asked)
        assertTrue(kid.hex() in cenc.missing)
        val (demuxer, _) = demux(bytes)
        assertEquals("encrypted fMP4 (CENC)", demuxer.unsupported)
    }

    @Test
    fun keyFromPrepare() {
        // A chave chega no prepare (o servidor de licenças): a mesma passagem já decifra.
        val keys = HashMap<String, ByteArray>()
        val clear = picture(64, 5)
        val e = clear.copyOf()
        Aes(key).ctr(Aes.CtrState(hex("3333333333333333") + ByteArray(8)), e, 5, 63)
        val bytes = init("cenc", 0, 0, 8, null) + segment(listOf(e), listOf(hex("3333333333333333")), listOf(listOf(5 to 63)))
        Cenc({ keys[it] }, { kids -> kids.forEach { keys[it] = key } }).process(bytes)
        val (demuxer, samples) = demux(bytes)
        assertNull(demuxer.unsupported)
        val out = samples.single().data
        assertContentEquals(clear.copyOfRange(4, clear.size), out.copyOfRange(out.size - 64, out.size))
    }
}
