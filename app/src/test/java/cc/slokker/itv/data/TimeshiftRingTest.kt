package cc.slokker.itv.data

import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TimeshiftRingTest {
    private lateinit var dir: File
    private var now = 1_000L
    private val rings = mutableListOf<TimeshiftRing>()

    @Before
    fun setUp() {
        dir = createTempDir()
    }

    @After
    fun tearDown() {
        rings.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun createTempDir(): File = File(System.getProperty("java.io.tmpdir"), "ring-test-${System.nanoTime()}").apply { mkdirs() }

    private fun ring(capacity: Long = 1L shl 20, maxAgeMs: Long = 30 * 60_000L) =
        TimeshiftRing(File(dir, "ring.ts"), capacity, maxAgeMs) { now }.also { rings += it }

    /** Um pacote TS de 188 bytes; com [pcrMs], leva o PCR no campo de adaptação. [fill]: o resto do pacote. */
    private fun packet(pid: Int = 0x100, pcrMs: Long? = null, fill: Byte = 0x11): ByteArray {
        val p = ByteArray(188) { fill }
        p[0] = 0x47
        p[1] = ((pid shr 8) and 0x1F).toByte()
        p[2] = (pid and 0xFF).toByte()
        if (pcrMs == null) {
            p[3] = 0x10
        } else {
            p[3] = 0x30
            p[4] = 7
            p[5] = 0x10
            val base = pcrMs * 90
            p[6] = (base shr 25).toByte()
            p[7] = (base shr 17).toByte()
            p[8] = (base shr 9).toByte()
            p[9] = (base shr 1).toByte()
            p[10] = (((base and 1) shl 7) or 0x7E).toByte()
            p[11] = 0
        }
        return p
    }

    /** [seconds] de "vídeo": um PCR a cada 100 ms e [perPcr] pacotes sem PCR entre eles. */
    private fun stream(fromMs: Long, seconds: Int, perPcr: Int = 4): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until seconds * 10) {
            out.write(packet(pcrMs = fromMs + i * 100L))
            repeat(perPcr) { out.write(packet()) }
        }
        return out.toByteArray()
    }

    private fun TimeshiftRing.writeAll(data: ByteArray, chunk: Int = 7_000) {
        var o = 0
        while (o < data.size) {
            val n = minOf(chunk, data.size - o)
            write(data, o, n)
            o += n
        }
    }

    private fun TimeshiftRing.readAll(from: Long, segment: Int, max: Int): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4_096)
        var pos = from
        while (out.size() < max) {
            val n = read(pos, segment, buf, 0, minOf(buf.size, max - out.size()), 10)
            if (n <= 0) return out.toByteArray() to n
            out.write(buf, 0, n)
            pos += n
        }
        return out.toByteArray() to 1
    }

    // ---- TsScanner ----

    @Test
    fun scannerFindsPcrsAcrossChunksAndResyncs() {
        val pcrs = mutableListOf<Pair<Long, Long>>()
        val scanner = TsScanner { offset, pcr -> pcrs += offset to pcr }
        scanner.reset(0)
        // Lixo no início (desalinhado), depois pacotes, partidos em bocados que cortam os pacotes ao meio.
        val garbage = ByteArray(77) { 0x22 }
        val data = garbage + stream(10_000, 1)
        var o = 0
        while (o < data.size) {
            val n = minOf(101, data.size - o)
            scanner.feed(data, o, n)
            o += n
        }
        assertEquals(true, scanner.isTs)
        assertEquals(10, pcrs.size)
        assertEquals(77L, pcrs.first().first)
        assertEquals(10_000L, pcrs.first().second)
        assertEquals(10_900L, pcrs.last().second)
        // Cada PCR está no início de um pacote: 5 pacotes entre um e o seguinte.
        assertEquals(77L + 9 * 5 * 188, pcrs.last().first)
    }

    @Test
    fun scannerRejectsNonTs() {
        val scanner = TsScanner { _, _ -> fail("não é TS") }
        scanner.reset(0)
        // Um MP4 começa por "ftyp" e não tem 0x47 de 188 em 188 bytes.
        val mp4 = ByteArray(8_192) { i -> (i * 31 % 251).toByte() }
        "....ftypisom".toByteArray().copyInto(mp4)
        scanner.feed(mp4, 0, mp4.size)
        assertEquals(false, scanner.isTs)
    }

    @Test
    fun scannerUndecidedWithFewBytes() {
        val scanner = TsScanner { _, _ -> }
        scanner.reset(0)
        val p = packet()
        scanner.feed(p, 0, p.size)
        assertNull(scanner.isTs)
    }

    // ---- TimeshiftRing ----

    @Test
    fun readsBackWhatWasWritten() {
        val r = ring()
        r.beginSegment()
        val data = stream(0, 2)
        r.writeAll(data)
        val (back, _) = r.readAll(0, 0, data.size)
        assertArrayEquals(data, back)
        assertEquals(data.size.toLong(), r.writtenBytes)
    }

    @Test
    fun waitsAtTheLiveEdgeAndWakesUpWithNewData() {
        val r = ring()
        r.beginSegment()
        val first = stream(0, 1)
        r.write(first, 0, first.size)
        val buf = ByteArray(1_000)
        // No fim do que há: espera e devolve WAIT.
        assertEquals(TimeshiftRing.WAIT, r.read(first.size.toLong(), 0, buf, 0, buf.size, 5))
        // Outra thread escreve: o leitor acorda logo com os dados.
        val more = stream(1_000, 1)
        val writer = Thread {
            Thread.sleep(50)
            r.write(more, 0, more.size)
        }
        writer.start()
        val n = r.read(first.size.toLong(), 0, buf, 0, buf.size, 5_000)
        writer.join()
        assertTrue(n > 0)
        assertArrayEquals(more.copyOf(n), buf.copyOf(n))
    }

    @Test
    fun wrapsAroundAndReportsOverrun() {
        val capacity = 64L * 1024
        val r = ring(capacity)
        r.beginSegment()
        val data = stream(0, 20) // ~94 KB: dá a volta ao anel
        r.writeAll(data, 4_000)
        assertTrue(data.size > capacity)
        val buf = ByteArray(188)
        // O início já foi reescrito.
        assertEquals(TimeshiftRing.OVERRUN, r.read(0, 0, buf, 0, buf.size, 1))
        // O fim ainda está lá (e lê-se bem através da volta do ficheiro).
        val from = data.size - capacity + 1_000
        val (back, _) = r.readAll(from, 0, (capacity - 2_000).toInt())
        assertArrayEquals(data.copyOfRange(from.toInt(), (from + capacity - 2_000).toInt()), back)
        // O índice só aponta para o que ainda se pode ler.
        val oldest = r.oldest
        assertNotNull(oldest)
        assertTrue(oldest!!.offset >= data.size - capacity)
    }

    @Test
    fun indexFollowsThePcrClock() {
        val r = ring()
        r.beginSegment()
        // O servidor manda 10 s de vídeo de rajada (o relógio real quase não anda): conta o PCR.
        r.writeAll(stream(50_000, 10))
        assertEquals(9_900L, r.liveMediaMs)
        // Um ponto a cada meio segundo.
        val e = r.entryAtOrBefore(4_200)!!
        assertEquals(4_000L, e.mediaMs)
        assertEquals(40L * 5 * 188, e.offset)
        assertEquals(0L, r.entryAtOrBefore(-5)!!.offset)
        assertEquals(4_000L, r.mediaAt(e.offset + 10))
    }

    @Test
    fun pcrJumpUsesWallClock() {
        val r = ring()
        r.beginSegment()
        r.writeAll(stream(0, 2))
        val before = r.liveMediaMs
        // O relógio do emissor salta uma hora para trás (outra fonte no servidor); passaram 3 s reais.
        now += 3_000
        r.writeAll(stream(-3_600_000L + 7_200_000L, 1))
        assertEquals(before + 3_000 + 900, r.liveMediaMs)
    }

    @Test
    fun burstsWithoutPcrAtTheStartAreNotCountedTwice() {
        val r = ring()
        r.beginSegment()
        r.writeAll(stream(0, 4))
        // A rajada seguinte, 4 s depois, começa com pacotes sem PCR: o tempo real conta até chegar o PCR, que o acerta.
        now += 4_000
        r.write(ByteArray(188 * 10).also { b -> repeat(10) { packet().copyInto(b, it * 188) } }, 0, 188 * 10)
        r.writeAll(stream(4_000, 4))
        assertEquals(7_900L, r.liveMediaMs)
    }

    @Test
    fun liveEdgeMovesWithTheClockBetweenBursts() {
        val r = ring()
        r.beginSegment()
        r.writeAll(stream(0, 4))
        assertEquals(3_900L, r.liveEdgeMs)
        // Nada chega durante 3 s: o direto continua a andar (menos 2 % que se esquece).
        now += 3_000
        assertEquals(3_900L, r.liveMediaMs)
        assertEquals(3_900L + 3_000 - 60, r.liveEdgeMs)
        // A rajada seguinte traz 4 s de uma vez: o direto vai para onde ela acaba.
        r.writeAll(stream(4_000, 4))
        assertEquals(7_900L, r.liveEdgeMs)
    }

    @Test
    fun segmentsDoNotMixAndReportBoundary() {
        val r = ring()
        r.beginSegment()
        val a = stream(0, 2)
        r.writeAll(a)
        // A ligação caiu; voltou 5 s depois (o PCR do servidor continua daí).
        r.endSegment(final = false)
        val buf = ByteArray(4_096)
        assertEquals(TimeshiftRing.WAIT, r.read(a.size.toLong(), 0, buf, 0, buf.size, 1))
        now += 5_000
        r.beginSegment()
        val b = stream(7_000, 2)
        r.writeAll(b)
        // Quem lia o primeiro segmento chega ao fim dele: BOUNDARY, e reabre no seguinte.
        val (backA, end) = r.readAll(0, 0, Int.MAX_VALUE)
        assertArrayEquals(a, backA)
        assertEquals(TimeshiftRing.BOUNDARY, end)
        assertEquals(1, r.segmentAt(a.size.toLong()))
        val next = r.entryFrom(a.size.toLong())!!
        assertEquals(a.size.toLong(), next.offset)
        // O tempo do segundo segmento continua o do primeiro mais a espera.
        assertEquals(1_900L + 5_000, next.mediaMs)
        val (backB, _) = r.readAll(next.offset, r.segmentAt(next.offset), b.size)
        assertArrayEquals(b, backB)
    }

    @Test
    fun finishedStreamEndsOrThrowsAfterTheData() {
        val r = ring()
        r.beginSegment()
        val a = stream(0, 1)
        r.writeAll(a)
        r.endSegment(final = true, error = IOException("caiu"))
        val (back, _) = r.readAll(0, 0, a.size)
        assertArrayEquals(a, back)
        try {
            r.read(a.size.toLong(), 0, ByteArray(10), 0, 10, 1)
            fail("devia lançar o erro da ligação")
        } catch (e: IOException) {
            assertEquals("caiu", e.message)
        }
        // Sem erro: só o fim.
        val r2 = TimeshiftRing(File(dir, "ring2.ts"), 1L shl 20, 60_000) { now }.also { rings += it }
        r2.beginSegment()
        r2.writeAll(a)
        r2.endSegment(final = true)
        assertTrue(r2.isFinished)
        assertEquals(TimeshiftRing.END, r2.read(a.size.toLong(), 0, ByteArray(10), 0, 10, 1))
    }

    @Test
    fun failureBeforeAnyDataIsThrownRightAway() {
        val r = ring()
        r.endSegment(final = true, error = IOException("403"))
        try {
            r.read(0, r.segmentAt(0), ByteArray(10), 0, 10, 1)
            fail("devia lançar")
        } catch (e: IOException) {
            assertEquals("403", e.message)
        }
    }

    @Test
    fun keepsAtMostMaxAge() {
        val r = ring(capacity = 8L shl 20, maxAgeMs = 5_000)
        r.beginSegment()
        r.writeAll(stream(0, 12))
        val oldest = r.oldest!!
        assertTrue("o mais antigo tem ${r.liveMediaMs - oldest.mediaMs} ms", r.liveMediaMs - oldest.mediaMs <= 5_000)
        assertTrue(oldest.mediaMs >= 6_000)
    }

    @Test
    fun dropsThePreviousProgramme() {
        val r = ring(capacity = 8L shl 20)
        r.beginSegment()
        r.writeAll(stream(0, 12))
        // O programa seguinte começou aos 7 s: antes disso já não se pode recuar.
        r.dropBefore(7_000)
        assertTrue("o mais antigo está em ${r.oldest!!.mediaMs} ms", r.oldest!!.mediaMs in 7_000..7_500)
        assertTrue(r.entryAtOrBefore(2_000)!!.mediaMs >= 7_000)
        // Só avança: um início mais antigo (guia recarregado) não traz nada de volta.
        r.dropBefore(3_000)
        assertTrue(r.oldest!!.mediaMs >= 7_000)
        // Nunca passa do direto: fica pelo menos o último ponto.
        r.dropBefore(60_000)
        assertNotNull(r.oldest)
    }

    @Test
    fun shorterMaxAgeTrimsRightAway() {
        val r = ring(capacity = 8L shl 20, maxAgeMs = 60_000)
        r.beginSegment()
        r.writeAll(stream(0, 12))
        assertEquals(0L, r.oldest!!.mediaMs)
        r.setMaxAge(5_000)
        assertTrue(r.liveMediaMs - r.oldest!!.mediaMs <= 5_000)
    }

    @Test
    fun shrinksToTheChannelBitrateAfterAMinute() {
        val r = ring(capacity = 64L shl 20, maxAgeMs = 2 * 60_000L)
        r.beginSegment()
        val minute = stream(0, 61, perPcr = 1)
        r.writeAll(minute, 32_000)
        // ~376 bytes por 100 ms: 2 min com 15 % de folga ficam muito abaixo de 64 MB (mas nunca abaixo do mínimo).
        assertEquals(TimeshiftRing.MIN_CAPACITY, r.capacity)
        // E continua a ler-se bem.
        val (back, _) = r.readAll(0, 0, 10_000)
        assertArrayEquals(minute.copyOf(10_000), back)
    }

    @Test
    fun closeWakesReadersAndDeletesTheFile() {
        val r = ring()
        r.beginSegment()
        val a = stream(0, 1)
        r.writeAll(a)
        var result = 99
        val reader = Thread { result = r.read(a.size.toLong(), 0, ByteArray(10), 0, 10, 10_000) }
        reader.start()
        Thread.sleep(50)
        r.close()
        reader.join(2_000)
        assertFalse(reader.isAlive)
        assertEquals(TimeshiftRing.END, result)
        assertFalse(File(dir, "ring.ts").exists())
        try {
            r.write(a, 0, a.size)
            fail("escrever num anel fechado")
        } catch (_: IOException) {
        }
    }

    @Test
    fun readerInterruptIsReported() {
        val r = ring()
        r.beginSegment()
        var error: Throwable? = null
        val reader = Thread {
            try {
                r.read(0, 0, ByteArray(10), 0, 10, 10_000)
            } catch (e: Throwable) {
                error = e
            }
        }
        reader.start()
        Thread.sleep(50)
        reader.interrupt()
        reader.join(2_000)
        assertTrue(error is java.io.InterruptedIOException)
    }
}
