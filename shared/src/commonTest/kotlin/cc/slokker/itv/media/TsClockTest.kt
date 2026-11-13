package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TsClockTest {
    @Test
    fun wrapsAt33Bits() {
        val c = TsClock()
        val end = TsClock.WRAP - 3000
        assertEquals(end, c.unwrap(end))
        assertEquals(TsClock.WRAP + 600, c.unwrap(600))
        assertFalse(c.jumped)
        // Uma imagem B de antes da volta, já depois dela.
        assertEquals(end + 1500, c.unwrap(end + 1500))
        assertEquals(TsClock.WRAP + 4200, c.unwrap(4200))
    }

    @Test
    fun bFramesGoBackALittle() {
        val c = TsClock()
        for (ts in listOf(90_000L, 100_800, 93_600, 97_200, 111_600)) assertEquals(ts, c.unwrap(ts))
    }

    /** O stream recomeça (o relógio volta 2 s atrás, ou salta horas): o tempo continua seguido, a uma unidade do último. */
    @Test
    fun jumpsContinueTheTime() {
        val c = TsClock()
        c.unwrap(1_000_000)
        c.unwrap(1_003_600)
        assertFalse(c.jumped)
        assertEquals(1_007_200, c.unwrap(1_003_600 - 180_000))
        assertTrue(c.jumped)
        assertEquals(1_010_800, c.unwrap(1_003_600 - 180_000 + 3600))
        assertFalse(c.jumped)
        assertEquals(1_014_400, c.unwrap(900_000_000))
        assertTrue(c.jumped)
        assertEquals(-1, c.unwrap(-1))
    }

    @Test
    fun mapFollowsWithoutJumping() {
        val c = TsClock()
        assertEquals(-1L, c.map(1_000))
        c.unwrap(900_000)
        // Um minuto depois (legendas esparsas): não é um salto, e o relógio não mexe.
        assertEquals(900_000L + 5_400_000, c.map(900_000L + 5_400_000))
        assertEquals(900_000L + 3_600, c.unwrap(900_000L + 3_600))
        // Depois de um salto do vídeo, segue o mesmo desvio.
        val out = c.unwrap(50_000_000)
        assertEquals(out + 7_200, c.map(50_007_200))
        // A volta dos 33 bits.
        val w = TsClock()
        w.unwrap(TsClock.WRAP - 9_000)
        assertEquals(TsClock.WRAP + 9_000, w.map(9_000))
    }
}
