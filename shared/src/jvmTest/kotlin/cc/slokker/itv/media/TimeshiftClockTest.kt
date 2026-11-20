package cc.slokker.itv.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** O relógio do anel da pausa do direto (o mesmo no Android e no Apple; os casos com o anel estão no TimeshiftRingTest). */
class TimeshiftClockTest {
    private var now = 1_000L
    private val clock = TimeshiftClock()

    /** Uma ligação com [ms] de vídeo: um PCR a cada 100 ms, a chegar ao ritmo real, a começar no PCR [base]. */
    private fun connection(base: Long, ms: Long) {
        clock.beginSegment()
        var t = 0L
        while (t < ms) {
            clock.onPcr(now, (base + t) % TimeshiftClock.PCR_WRAP_MS)
            clock.onData(now, ts = true)
            now += 100
            t += 100
        }
        clock.endSegment(now)
    }

    @Test
    fun countsThePcrInsideAConnection() {
        connection(base = 50_000, ms = 10_000)
        assertEquals(9_900L, clock.mediaMs)
    }

    @Test
    fun reconnectionsWithNewPcrBasesDoNotCountTheGaps() {
        val random = Random(11)
        repeat(300) {
            connection(base = random.nextLong(0, TimeshiftClock.PCR_WRAP_MS - 10_000), ms = 1_000)
            now += 4_000
        }
        // 300 s gravados: cada ligação nova continua um passo de PCR depois da anterior.
        assertEquals(300_000L - 100, clock.mediaMs)
        // E o direto não andou enquanto não havia ligação.
        assertTrue(clock.liveEdgeMs(now) - clock.mediaMs <= 100)
    }

    @Test
    fun pcrWrapInsideAConnectionIsNotAJump() {
        connection(base = TimeshiftClock.PCR_WRAP_MS - 500, ms = 1_000)
        assertEquals(900L, clock.mediaMs)
    }

    @Test
    fun withoutPcrTheClockIsRealTime() {
        now = 10_000
        clock.beginSegment()
        assertTrue(clock.onData(now, ts = true))
        now += 2_000
        assertTrue(clock.onData(now, ts = true))
        assertEquals(2_000L, clock.mediaMs)
    }
}
