package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TsDemuxerTest {
    private class Collect : TsListener {
        val tracks = ArrayList<List<TsTrack>>()
        val samples = ArrayList<Pair<TsTrack, TsSample>>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks += tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples += track to sample
        }

        fun of(pid: Int) = samples.filter { it.first.pid == pid }.map { it.second }
    }

    // ---- Montar um TS ------------------------------------------------------------------------

    private val counters = HashMap<Int, Int>()

    /** Os pacotes de [payload] no [pid] (o primeiro com payload_unit_start), com enchimento no último. */
    private fun packets(pid: Int, payload: ByteArray, randomAccess: Boolean = false, skipCounter: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        var at = 0
        var first = true
        while (at < payload.size || first) {
            val flags = if (first && randomAccess) 0x40 else 0
            val needsAdaptation = flags != 0
            val room = 184 - if (needsAdaptation) 2 else 0
            val n = minOf(room, payload.size - at)
            val stuffing = room - n
            val c = counters.getOrElse(pid) { 0 }.let { if (skipCounter && first) (it + 1) and 0x0F else it }
            counters[pid] = (c + 1) and 0x0F
            val adaptation = needsAdaptation || stuffing > 0
            out += 0x47.toByte()
            out += ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            out += (pid and 0xFF).toByte()
            out += ((if (adaptation) 0x30 else 0x10) or c).toByte()
            if (adaptation) {
                val length = (if (needsAdaptation) 1 else 0) + stuffing + if (!needsAdaptation && stuffing > 0) -1 else 0
                if (needsAdaptation) {
                    out += (1 + stuffing).toByte()
                    out += flags.toByte()
                    repeat(stuffing) { out += 0xFF.toByte() }
                } else {
                    // Só enchimento: o campo de adaptação ocupa [stuffing] bytes (o tamanho e as flags contam).
                    out += (stuffing - 1).toByte()
                    if (stuffing >= 2) {
                        out += 0
                        repeat(stuffing - 2) { out += 0xFF.toByte() }
                    }
                    check(length >= 0)
                }
            }
            for (k in 0 until n) out += payload[at + k]
            at += n
            first = false
        }
        return out.toByteArray()
    }

    private fun section(table: Int, idField: Int, version: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val head = byteArrayOf(
            table.toByte(), (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte(),
            (idField shr 8).toByte(), (idField and 0xFF).toByte(), (0xC1 or (version shl 1)).toByte(), 0, 0,
        )
        val s = head + body
        val crc = TsDemuxer.crc32(s, 0, s.size)
        return byteArrayOf(0) + s + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private fun pat(pmtPid: Int) = packets(0, section(0x00, 1, 0, byteArrayOf(0, 0, 0xE0.toByte(), 0x10, 0, 1, (0xE0 or (pmtPid shr 8)).toByte(), (pmtPid and 0xFF).toByte())))

    private class Es(val type: Int, val pid: Int, val descriptors: ByteArray = ByteArray(0))

    private fun pmt(pmtPid: Int, version: Int, vararg es: Es, corrupt: Boolean = false): ByteArray {
        val body = ArrayList<Byte>()
        body += 0xE1.toByte(); body += 0x00 // PCR PID 0x100
        body += 0xF0.toByte(); body += 0x00
        for (e in es) {
            body += e.type.toByte()
            body += (0xE0 or (e.pid shr 8)).toByte(); body += (e.pid and 0xFF).toByte()
            body += (0xF0 or (e.descriptors.size shr 8)).toByte(); body += (e.descriptors.size and 0xFF).toByte()
            e.descriptors.forEach { body += it }
        }
        val sec = section(0x02, 1, version, body.toByteArray())
        if (corrupt) sec[sec.size - 1] = (sec[sec.size - 1].toInt() xor 0x01).toByte()
        return packets(pmtPid, sec)
    }

    private fun ts(value: Long, prefix: Int): ByteArray = byteArrayOf(
        ((prefix shl 4) or (((value shr 30) and 0x07).toInt() shl 1) or 1).toByte(),
        ((value shr 22) and 0xFF).toByte(),
        ((((value shr 15) and 0x7F).toInt() shl 1) or 1).toByte(),
        ((value shr 7) and 0xFF).toByte(),
        (((value and 0x7F).toInt() shl 1) or 1).toByte(),
    )

    /** Um PES de vídeo (tamanho 0, como os encoders fazem) ou de áudio (com tamanho). */
    private fun pes(streamId: Int, pts: Long, dts: Long, data: ByteArray, sized: Boolean): ByteArray {
        val header = if (dts >= 0 && dts != pts) byteArrayOf(0x80.toByte(), 0xC0.toByte(), 10) + ts(pts, 3) + ts(dts, 1) else byteArrayOf(0x80.toByte(), 0x80.toByte(), 5) + ts(pts, 2)
        val length = if (sized) header.size + data.size else 0
        return byteArrayOf(0, 0, 1, streamId.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + header + data
    }

    private fun lang(code: String) = byteArrayOf(0x0A, 4) + code.encodeToByteArray() + byteArrayOf(0)

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun demux(vararg parts: ByteArray, chunk: Int = 1000): Collect {
        val c = Collect()
        val d = TsDemuxer(c)
        val all = parts.fold(ByteArray(0)) { a, b -> a + b }
        var at = 0
        while (at < all.size) {
            val n = minOf(chunk, all.size - at)
            d.feed(all, at, n)
            at += n
        }
        d.flush()
        return c
    }

    // ---- Testes ------------------------------------------------------------------------------

    @Test
    fun tracksFromThePmt() {
        val c = demux(
            pat(0x1000),
            pmt(
                0x1000, 0,
                Es(0x1B, 0x100),
                Es(0x0F, 0x101, lang("por")),
                Es(0x06, 0x102, byteArrayOf(0x6A, 1, 0) + lang("ENG")),
                Es(0x04, 0x103, lang("spa")),
                Es(0x06, 0x104, byteArrayOf(0x59, 8) + "por".encodeToByteArray() + byteArrayOf(0x10, 0, 1, 0, 1)),
                Es(0x06, 0x105, byteArrayOf(0x56, 5) + "deu".encodeToByteArray() + byteArrayOf(0x09, 0x00)),
                Es(0x24, 0x106),
                Es(0x87, 0x107),
                Es(0x06, 0x108, byteArrayOf(0x05, 4) + "EAC3".encodeToByteArray()),
                Es(0x15, 0x109),
                Es(0x86, 0x10A),
            ),
        )
        val tracks = c.tracks.single().associateBy { it.pid }
        assertEquals(Codec.H264 to TrackKind.VIDEO, tracks.getValue(0x100).let { it.codec to it.kind })
        assertEquals(Codec.AAC_ADTS to "por", tracks.getValue(0x101).let { it.codec to it.language })
        assertEquals(Codec.AC3 to "eng", tracks.getValue(0x102).let { it.codec to it.language })
        assertEquals(Codec.MPEG_AUDIO to "spa", tracks.getValue(0x103).let { it.codec to it.language })
        assertEquals(Codec.DVB_SUBTITLE to "por", tracks.getValue(0x104).let { it.codec to it.language })
        assertEquals(TrackKind.SUBTITLE, tracks.getValue(0x104).kind)
        assertEquals(Codec.TELETEXT to "deu", tracks.getValue(0x105).let { it.codec to it.language })
        assertEquals(Codec.HEVC, tracks.getValue(0x106).codec)
        assertEquals(Codec.EAC3, tracks.getValue(0x107).codec)
        assertEquals(Codec.EAC3, tracks.getValue(0x108).codec)
        assertEquals(Codec.ID3 to TrackKind.DATA, tracks.getValue(0x109).let { it.codec to it.kind })
        assertEquals(Codec.UNKNOWN to TrackKind.DATA, tracks.getValue(0x10A).let { it.codec to it.kind })
    }

    @Test
    fun videoAndAudioUnitsWithTimestamps() {
        val frame1 = bytes(5000, 1)
        val frame2 = bytes(700, 2)
        val audio = bytes(400, 3)
        val c = demux(
            pat(0x1000),
            pmt(0x1000, 0, Es(0x1B, 0x100), Es(0x0F, 0x101)),
            packets(0x100, pes(0xE0, 126_000, 123_000, frame1, sized = false), randomAccess = true),
            packets(0x101, pes(0xC0, 124_500, -1, audio, sized = true)),
            packets(0x100, pes(0xE0, 129_000, 126_000, frame2, sized = false)),
            chunk = 77,
        )
        val video = c.of(0x100)
        assertEquals(2, video.size)
        assertContentEquals(frame1, video[0].data)
        assertEquals(126_000L, video[0].pts)
        assertEquals(123_000L, video[0].dts)
        assertTrue(video[0].randomAccess)
        assertFalse(video[1].randomAccess)
        // O último vídeo só sai no fim (o tamanho é 0): o flush entrega-o.
        assertContentEquals(frame2, video[1].data)
        val a = c.of(0x101).single()
        assertContentEquals(audio, a.data)
        assertEquals(124_500L, a.pts)
        assertEquals(124_500L, a.dts)
        // O áudio com tamanho sai antes do vídeo seguinte, sem esperar pelo próximo PES.
        assertTrue(c.samples.indexOfFirst { it.first.pid == 0x101 } < c.samples.indexOfLast { it.first.pid == 0x100 })
    }

    @Test
    fun timestampsUseAll33Bits() {
        val big = (1L shl 33) - 10
        val c = demux(pat(0x1000), pmt(0x1000, 0, Es(0x0F, 0x101)), packets(0x101, pes(0xC0, big, -1, bytes(10, 0), sized = true)))
        assertEquals(big, c.of(0x101).single().pts)
    }

    @Test
    fun lostPacketsDropTheBrokenUnitAndMarkTheNext() {
        val c = demux(
            pat(0x1000),
            pmt(0x1000, 0, Es(0x1B, 0x100)),
            packets(0x100, pes(0xE0, 1000, -1, bytes(3000, 1), sized = false)),
            // O PES seguinte começa com um contador que salta um: faltou um pacote antes.
            packets(0x100, pes(0xE0, 4000, -1, bytes(3000, 2), sized = false), skipCounter = true),
            packets(0x100, pes(0xE0, 7000, -1, bytes(300, 3), sized = false)),
        )
        val video = c.of(0x100)
        // Não se sabe se o pacote perdido era o fim do primeiro: esse não sai, e o seguinte vem marcado.
        assertEquals(listOf(4000L, 7000L), video.map { it.pts })
        assertEquals(listOf(true, false), video.map { it.discontinuity })
    }

    @Test
    fun resyncsAfterGarbage() {
        val c = demux(
            bytes(333, 9).also { it.indices.forEach { i -> if (it[i] == 0x47.toByte()) it[i] = 0 } },
            pat(0x1000),
            pmt(0x1000, 0, Es(0x0F, 0x101)),
            packets(0x101, pes(0xC0, 90_000, -1, bytes(50, 4), sized = true)),
            chunk = 64,
        )
        assertEquals(90_000L, c.of(0x101).single().pts)
    }

    @Test
    fun pmtVersionChangeGivesNewTracks() {
        val c = demux(
            pat(0x1000),
            pmt(0x1000, 0, Es(0x1B, 0x100), Es(0x0F, 0x101, lang("por"))),
            pmt(0x1000, 0, Es(0x1B, 0x100), Es(0x0F, 0x101, lang("por"))),
            pmt(0x1000, 1, Es(0x1B, 0x100), Es(0x0F, 0x101, lang("por")), Es(0x0F, 0x102, lang("eng"))),
        )
        assertEquals(listOf(2, 3), c.tracks.map { it.size })
    }

    @Test
    fun badCrcIsIgnored() {
        val c = demux(pat(0x1000), pmt(0x1000, 0, Es(0x1B, 0x100), corrupt = true))
        assertTrue(c.tracks.isEmpty())
    }
}
