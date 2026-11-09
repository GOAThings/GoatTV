package cc.slokker.itv.data.recording

import cc.slokker.itv.data.TimeshiftRing
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingWriterTest {
    private lateinit var dir: File
    private val rings = mutableListOf<TimeshiftRing>()
    private val group = 5 * 188 // um PCR e quatro pacotes: 100 ms de vídeo

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "rec-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        rings.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun ring() = TimeshiftRing(File(dir, "ring.ts"), 1L shl 22, 30 * 60_000L) { 1_000L }.also { rings += it }

    private fun TimeshiftRing.writeAll(data: ByteArray) {
        var o = 0
        while (o < data.size) {
            val n = minOf(7_000, data.size - o)
            write(data, o, n)
            o += n
        }
    }

    private fun writer(ring: TimeshiftRing, out: File, range: RecordingCut.Range, free: Long = Long.MAX_VALUE, check: Long = 8L shl 20) =
        RecordingWriter(ring, out, range.startOffset, range.endMediaMs, freeBytes = { free }, checkBytes = check)

    /** O programa dos 5 aos 15 s de um anel com 20 s: o ficheiro é exatamente esses bytes, cortado nos PCR. */
    @Test
    fun copiaOPedacoDoProgramaCortadoNosPontosDoIndice() {
        val ring = ring()
        val data = TsSamples.stream(0, 20)
        ring.beginSegment()
        ring.writeAll(data)
        val now = 100_000L
        val live = ring.liveMediaMs
        assertEquals(19_900L, live)
        val range = RecordingCut.range(ring.index(), live, now, now - (live - 5_000), now - (live - 15_000))!!
        assertEquals(50L * group, range.startOffset)
        assertEquals(5_000L, range.startMediaMs)
        assertEquals(15_000L, range.endMediaMs)
        assertFalse(range.late)
        val out = File(dir, "a.ts")
        val result = writer(ring, out, range).run()
        assertEquals(RecordingState.DONE, result.state)
        assertEquals(100L * group, result.bytes)
        assertEquals(15_000L, result.mediaEndMs)
        assertArrayEquals(data.copyOfRange(50 * group, 150 * group), out.readBytes())
    }

    /** O fim ainda não chegou: grava o que há, espera pelo direto e corta quando o vídeo passa do fim. */
    @Test
    fun vaiAtrasDoDiretoAteAoFim() {
        val ring = ring()
        val first = TsSamples.stream(0, 4)
        ring.beginSegment()
        ring.writeAll(first)
        val out = File(dir, "b.ts")
        val w = RecordingWriter(ring, out, 0L, 6_000L, freeBytes = { Long.MAX_VALUE })
        var result: RecordingWriter.Result? = null
        val t = Thread { result = w.run() }.apply { start() }
        Thread.sleep(300)
        assertTrue(t.isAlive)
        val second = TsSamples.stream(4_000, 4, fill = 0x22)
        ring.writeAll(second)
        t.join(5_000)
        assertEquals(RecordingState.DONE, result!!.state)
        val all = first + second
        assertArrayEquals(all.copyOfRange(0, 60 * group), out.readBytes())
    }

    @Test
    fun pararGuardaOQueJaTem() {
        val ring = ring()
        ring.beginSegment()
        ring.writeAll(TsSamples.stream(0, 3))
        val out = File(dir, "c.ts")
        val w = RecordingWriter(ring, out, 0L, 60_000L, freeBytes = { Long.MAX_VALUE })
        var result: RecordingWriter.Result? = null
        val t = Thread { result = w.run() }.apply { start() }
        Thread.sleep(300)
        w.stop()
        t.join(5_000)
        assertEquals(RecordingState.STOPPED, result!!.state)
        assertEquals(RecordingReason.USER, result!!.reason)
        assertEquals(30L * group, out.length())
    }

    /** A ligação foi para outro canal (o anel fechou): a gravação acaba com o que tem. */
    @Test
    fun oAnelFechadoAcabaAGravacao() {
        val ring = ring()
        ring.beginSegment()
        ring.writeAll(TsSamples.stream(0, 3))
        val w = RecordingWriter(ring, File(dir, "d.ts"), 0L, 60_000L, freeBytes = { Long.MAX_VALUE })
        var result: RecordingWriter.Result? = null
        val t = Thread { result = w.run() }.apply { start() }
        Thread.sleep(300)
        ring.close()
        t.join(5_000)
        assertEquals(RecordingState.STOPPED, result!!.state)
        assertEquals(RecordingReason.CONNECTION, result!!.reason)
        assertEquals(30L * group, result!!.bytes)
    }

    /** A ligação caiu e voltou: continua no segmento novo e conta o buraco. */
    @Test
    fun continuaNoSegmentoSeguinte() {
        val ring = ring()
        val a = TsSamples.stream(0, 2)
        val b = TsSamples.stream(50_000, 2, fill = 0x33)
        ring.beginSegment()
        ring.writeAll(a)
        ring.endSegment(final = false)
        ring.beginSegment()
        ring.writeAll(b)
        ring.endSegment(final = true)
        val out = File(dir, "e.ts")
        val result = RecordingWriter(ring, out, 0L, 60_000L, freeBytes = { Long.MAX_VALUE }).run()
        assertEquals(1, result.gaps)
        assertArrayEquals(a + b, out.readBytes())
    }

    @Test
    fun semEspacoParaComOQueTem() {
        val ring = ring()
        ring.beginSegment()
        ring.writeAll(TsSamples.stream(0, 10))
        val out = File(dir, "f.ts")
        val result = RecordingWriter(ring, out, 0L, 60_000L, freeBytes = { RecordingSpace.RESERVE_BYTES }, checkBytes = 10_000).run()
        assertEquals(RecordingState.STOPPED, result.state)
        assertEquals(RecordingReason.LOW_SPACE, result.reason)
        assertTrue(result.bytes in 10_000L..300_000L)
    }

    @Test
    fun semNadaGravadoFalha() {
        val ring = ring()
        ring.beginSegment()
        ring.endSegment(final = true)
        val result = RecordingWriter(ring, File(dir, "g.ts"), 0L, 60_000L, freeBytes = { Long.MAX_VALUE }).run()
        assertEquals(RecordingState.FAILED, result.state)
    }
}
