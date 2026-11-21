package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A ocultação de erros de ponta a ponta: um TS feito aqui (H.264 com AAC), com pacotes tirados, estragados ou com o
 * discontinuity_indicator, passa pelo [TsDemuxer] e pelo [ErrorConcealment] como no motor (ver GoatCorePlayer.video).
 */
class ErrorConcealmentTest {
    private val video = 0x100
    private val audio = 0x101
    private val counters = HashMap<Int, Int>()

    /** Os pacotes (188 bytes cada, à parte, para se poderem tirar) de [payload] no [pid]. */
    private fun packets(pid: Int, payload: ByteArray, flags: Int = 0, counterJump: Int = 0): MutableList<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0
        var first = true
        while (at < payload.size || first) {
            val f = if (first) flags else 0
            val room = 184 - if (f != 0) 2 else 0
            val n = minOf(room, payload.size - at)
            val stuffing = room - n
            val c = (counters.getOrElse(pid) { 0 } + if (first) counterJump else 0) and 0x0F
            counters[pid] = (c + 1) and 0x0F
            val p = ArrayList<Byte>()
            p += 0x47.toByte()
            p += ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            p += (pid and 0xFF).toByte()
            val adaptation = f != 0 || stuffing > 0
            p += ((if (adaptation) 0x30 else 0x10) or c).toByte()
            if (f != 0) {
                p += (1 + stuffing).toByte()
                p += f.toByte()
                repeat(stuffing) { p += 0xFF.toByte() }
            } else if (stuffing > 0) {
                p += (stuffing - 1).toByte()
                if (stuffing >= 2) {
                    p += 0
                    repeat(stuffing - 2) { p += 0xFF.toByte() }
                }
            }
            for (k in 0 until n) p += payload[at + k]
            check(p.size == 188)
            out += p.toByteArray()
            at += n
            first = false
        }
        return out
    }

    private fun section(table: Int, version: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val s = byteArrayOf(table.toByte(), (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte(), 0, 1, (0xC1 or (version shl 1)).toByte(), 0, 0) + body
        val crc = TsDemuxer.crc32(s, 0, s.size)
        return byteArrayOf(0) + s + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun tables(): List<ByteArray> =
        packets(0, section(0x00, 0, byteArrayOf(0, 1, 0xF0.toByte(), 0x00))) +
            packets(0x1000, section(0x02, 0, byteArrayOf(
                0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
                0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
                0x0F, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
            )))

    private fun ts(value: Long): ByteArray = byteArrayOf(
        ((2 shl 4) or (((value shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
        ((value shr 22) and 0xFF).toByte(),
        ((((value shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
        ((value shr 7) and 0xFF).toByte(),
        (((value and 0x7F).toInt() shl 1) or 1).toByte(),
    )

    private fun pes(streamId: Int, pts: Long, data: ByteArray, sized: Boolean): ByteArray {
        val header = byteArrayOf(0x80.toByte(), 0x80.toByte(), 5) + ts(pts)
        val length = if (sized) header.size + data.size else 0
        return byteArrayOf(0, 0, 1, streamId.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + header + data
    }

    private enum class Pic(val header: ByteArray) {
        // IDR; P (nal_ref_idc 2, slice_type 0); B sem referência (nal_ref_idc 0, slice_type 1).
        I(byteArrayOf(0x65, 0x88.toByte())),
        P(byteArrayOf(0x41, 0xC0.toByte())),
        B(byteArrayOf(0x01, 0xA0.toByte())),
    }

    /** Uma imagem H.264 de uns 4 pacotes (sem 00 00 01 pelo meio). */
    private fun picture(pic: Pic): ByteArray = byteArrayOf(0, 0, 0, 1) + pic.header + ByteArray(700) { 0x55 }

    /** Um bloco ADTS (AAC) pequeno, num PES com tamanho. */
    private fun aac(): ByteArray {
        val size = 7 + 20
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), (size shr 3).toByte(), (((size and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte()) +
            ByteArray(20) { 0x11 }
    }

    /** O que saiu: as imagens que foram para o descodificador (pelo pts) e os blocos de som. */
    private class Played(val pictures: List<Long>, val audio: List<Long>, val corrupt: List<Long>, val discontinuity: List<Long>, val conceal: ErrorConcealment)

    private fun play(packets: List<ByteArray>): Played {
        val conceal = ErrorConcealment()
        val reader = NalReader(hevc = false)
        val pictures = ArrayList<Long>()
        val audioOut = ArrayList<Long>()
        val corrupt = ArrayList<Long>()
        val discontinuity = ArrayList<Long>()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.pid == audio) {
                    audioOut += sample.pts
                    return
                }
                if (sample.corrupt) {
                    corrupt += sample.pts
                    conceal.lost(sample.lostNonReference)
                }
                if (sample.discontinuity) discontinuity += sample.pts
                val unit = reader.unit(sample.data)
                val key = unit.keyframe || ErrorConcealment.recoveryPoint(false, sample.data)
                if (conceal.pass(key, sample.pts)) pictures += sample.pts
            }
        })
        val all = packets.fold(ByteArray(0)) { a, b -> a + b }
        d.feed(all, 0, all.size)
        d.flush()
        return Played(pictures, audioOut, corrupt, discontinuity, conceal)
    }

    /** Um GOP de [pics], 25 imagens por segundo (3600 a 90 kHz), com um bloco de som a seguir a cada imagem. */
    private fun stream(pics: List<Pic>, edit: (index: Int, video: MutableList<ByteArray>, audio: MutableList<ByteArray>) -> Unit = { _, _, _ -> }): List<ByteArray> {
        val out = ArrayList(tables())
        for ((i, pic) in pics.withIndex()) {
            val pts = 90_000L + i * 3600L
            val v = packets(video, pes(0xE0, pts, picture(pic), sized = false))
            val a = packets(audio, pes(0xC0, pts, aac(), sized = true))
            edit(i, v, a)
            out += v
            out += a
        }
        return out
    }

    private fun pts(vararg index: Int) = index.map { 90_000L + it * 3600L }

    @Test
    fun aGapInTheVideoHoldsTheLastGoodPictureUntilTheNextKeyframe() {
        val pics = listOf(Pic.I, Pic.P, Pic.P, Pic.P, Pic.P, Pic.I, Pic.P)
        // Um pacote a meio da imagem 2 perdeu-se.
        val p = play(stream(pics) { i, v, _ -> if (i == 2) v.removeAt(2) })
        // A 2 não sai (está estragada); a 3 e a 4 dependiam dela; da 5 (IDR) em diante volta.
        assertEquals(pts(0, 1, 5, 6), p.pictures)
        assertEquals(pts(3), p.corrupt)
        assertEquals(3, p.conceal.dropped) // a perdida e as duas seguradas
        assertFalse(p.conceal.concealing)
        // O som não perdeu nada.
        assertEquals(pts(0, 1, 2, 3, 4, 5, 6), p.audio)
    }

    @Test
    fun aLostBFrameIsDroppedAlone() {
        val pics = listOf(Pic.I, Pic.P, Pic.B, Pic.P, Pic.P, Pic.I)
        val p = play(stream(pics) { i, v, _ -> if (i == 2) v.removeAt(2) })
        assertEquals(pts(0, 1, 3, 4, 5), p.pictures)
        assertEquals(1, p.conceal.dropped)
    }

    @Test
    fun aGapInTheAudioOnlyLeavesTheVideoAlone() {
        val pics = listOf(Pic.I, Pic.P, Pic.P, Pic.P, Pic.I)
        val p = play(stream(pics) { i, _, a -> if (i == 1) a.removeAt(0) })
        assertEquals(pts(0, 1, 2, 3, 4), p.pictures)
        assertEquals(pts(0, 2, 3, 4), p.audio)
        assertEquals(0, p.conceal.dropped)
        assertTrue(p.corrupt.isEmpty())
    }

    @Test
    fun theDiscontinuityIndicatorIsNotALoss() {
        val pics = listOf(Pic.I, Pic.P, Pic.P, Pic.P, Pic.I)
        // A imagem 2 começa com o contador a saltar, mas marcado (discontinuity_indicator): é permitido.
        val p = play(stream(pics) { i, v, _ ->
            if (i == 2) {
                val again = ArrayList<ByteArray>()
                counters[video] = (counters[video]!! - v.size + 16) and 0x0F
                again += packets(video, pes(0xE0, 90_000L + 2 * 3600L, picture(Pic.P), sized = false), flags = 0x80, counterJump = 7)
                v.clear()
                v += again
            }
        })
        assertEquals(pts(0, 1, 2, 3, 4), p.pictures)
        assertEquals(pts(2), p.discontinuity)
        assertTrue(p.corrupt.isEmpty())
        assertEquals(0, p.conceal.dropped)
    }

    @Test
    fun aDamagedPacketCountsAsLost() {
        val pics = listOf(Pic.I, Pic.P, Pic.P, Pic.P, Pic.I)
        // transport_error_indicator num pacote a meio da imagem 1.
        val p = play(stream(pics) { i, v, _ -> if (i == 1) v[1][1] = (v[1][1].toInt() or 0x80).toByte() })
        assertEquals(pts(0, 4), p.pictures)
        assertEquals(pts(2), p.corrupt)
    }

    @Test
    fun aRepeatedPacketIsNotALoss() {
        val pics = listOf(Pic.I, Pic.P, Pic.P, Pic.I)
        // Um pacote a meio repetido, e o do início de outra imagem também.
        val p = play(stream(pics) { i, v, _ ->
            if (i == 1) v.add(2, v[1].copyOf())
            if (i == 2) v.add(1, v[0].copyOf())
        })
        assertEquals(pts(0, 1, 2, 3), p.pictures)
        assertTrue(p.corrupt.isEmpty())
    }

    @Test
    fun aShortPesIsALoss() {
        val pics = listOf(Pic.I, Pic.P, Pic.P, Pic.I)
        // O PES de som diz mais do que traz (sem faltar nenhum pacote).
        val p = play(stream(pics) { i, _, a ->
            if (i == 1) {
                val b = a[0]
                // PES_packet_length (bytes 4 e 5 do PES, a seguir aos 4 do TS e ao campo de adaptação).
                val at = 4 + 1 + (b[4].toInt() and 0xFF)
                b[at + 5] = (b[at + 5] + 50).toByte()
            }
        })
        assertEquals(pts(0, 2, 3), p.audio)
        assertEquals(pts(0, 1, 2, 3), p.pictures)
    }

    @Test
    fun aLongGopResumesAfterTheCap() {
        // Uma imagem-chave e depois só P (GOP de 10 s): perde-se a 2 e não vem imagem-chave.
        val pics = listOf(Pic.I) + List(100) { Pic.P }
        val p = play(stream(pics) { i, v, _ -> if (i == 2) v.removeAt(2) })
        // Seguram-se 2 s (50 imagens a 25 por segundo) a contar da primeira segurada (a 3); a 53 já vai.
        val held = (ErrorConcealment.CAP_TICKS / 3600).toInt()
        assertEquals(pts(0, 1) + (3 + held..100).map { 90_000L + it * 3600L }, p.pictures)
        assertEquals(1, p.conceal.capped)
    }

    @Test
    fun aRecoveryPointSeiCountsAsKeyframe() {
        // SEI com recovery_point (payloadType 6, 2 bytes) antes de uma fatia P.
        val sei = byteArrayOf(0, 0, 0, 1, 0x06, 0x06, 0x02, 0x84.toByte(), 0x80.toByte(), 0x80.toByte())
        assertTrue(ErrorConcealment.recoveryPoint(false, sei + picture(Pic.P)))
        assertFalse(ErrorConcealment.recoveryPoint(false, picture(Pic.P)))
        // Um SEI só com outra mensagem (user data, tipo 5).
        assertFalse(ErrorConcealment.recoveryPoint(false, byteArrayOf(0, 0, 1, 0x06, 0x05, 0x01, 0x00, 0x80.toByte()) + picture(Pic.P)))
    }

    @Test
    fun nonReferencePicturesPerCodec() {
        assertTrue(ErrorConcealment.nonReference(Codec.H264, picture(Pic.B), 0, 10))
        assertFalse(ErrorConcealment.nonReference(Codec.H264, picture(Pic.P), 0, 10))
        assertFalse(ErrorConcealment.nonReference(Codec.H264, picture(Pic.I), 0, 10))
        // HEVC: TRAIL_N (0) sim, TRAIL_R (1) e CRA (21) não.
        assertTrue(ErrorConcealment.nonReference(Codec.HEVC, byteArrayOf(0, 0, 1, 0x00, 0x01, 0x55), 0, 6))
        assertFalse(ErrorConcealment.nonReference(Codec.HEVC, byteArrayOf(0, 0, 1, 0x02, 0x01, 0x55), 0, 6))
        assertFalse(ErrorConcealment.nonReference(Codec.HEVC, byteArrayOf(0, 0, 1, 0x2A, 0x01, 0x55), 0, 6))
        // MPEG-2: picture_coding_type 3 (B) sim, 2 (P) não.
        assertTrue(ErrorConcealment.nonReference(Codec.MPEG2_VIDEO, byteArrayOf(0, 0, 1, 0x00, 0x00, 0x18, 0, 0), 0, 8))
        assertFalse(ErrorConcealment.nonReference(Codec.MPEG2_VIDEO, byteArrayOf(0, 0, 1, 0x00, 0x00, 0x10, 0, 0), 0, 8))
    }

    @Test
    fun afterResumingTheLeadingPicturesOfAnOpenGopStayOut() {
        val c = ErrorConcealment()
        assertTrue(c.pass(true, 0))
        c.lost(false)
        assertFalse(c.pass(false, 3600))
        // A imagem-chave em 10800; a B a seguir mostra-se antes dela (7200) e apontava para trás.
        assertTrue(c.pass(true, 10800))
        assertFalse(c.pass(false, 7200))
        assertTrue(c.pass(false, 14400))
        assertEquals(3, c.dropped)
    }

    @Test
    fun bFramesInDecodeOrderKeepHolding() {
        // Depois da perda: P (tempo 10800), depois B (3600, 7200), pela ordem de descodificação. As B não são um salto.
        val c = ErrorConcealment()
        c.lost(false)
        assertFalse(c.pass(false, 10800))
        assertFalse(c.pass(false, 3600))
        assertFalse(c.pass(false, 7200))
        assertTrue(c.concealing)
        assertEquals(0, c.capped)
        assertTrue(c.pass(true, 21600))
        // Um salto a sério para trás (mais de 1 s) acaba a espera.
        c.lost(false)
        assertFalse(c.pass(false, 500_000))
        assertTrue(c.pass(false, 100_000))
        assertEquals(1, c.capped)
    }

    @Test
    fun repeatedLossDoesNotRestartTheCap() {
        val c = ErrorConcealment()
        c.lost(false)
        var pts = 0L
        var shown = -1
        for (i in 0 until 100) {
            if (i % 10 == 5) c.lost(false) // mais perdas a meio da espera
            pts += 3600
            if (c.pass(false, pts)) { shown = i; break }
        }
        // 2 s depois da primeira imagem segurada (25 imagens/s): volta à 51.ª, mesmo com perdas novas pelo meio.
        assertEquals(50, shown)
        assertEquals(1, c.capped)
    }
}
