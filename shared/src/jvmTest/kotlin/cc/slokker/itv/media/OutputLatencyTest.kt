package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputLatencyTest {
    @Test
    fun `the timestamp wins over the reported value and Bluetooth has a default`() {
        assertEquals(180_000, OutputLatency.pick(180_000, 60_000, bluetooth = true))
        assertEquals(60_000, OutputLatency.pick(-1, 60_000, bluetooth = true))
        assertEquals(OutputLatency.BLUETOOTH_DEFAULT_US, OutputLatency.pick(-1, -1, bluetooth = true))
        assertEquals(-1, OutputLatency.pick(-1, -1, bluetooth = false))
        assertEquals(0, OutputLatency.pick(0, 90_000, bluetooth = false))
    }

    @Test
    fun `values stay within 0 to 500 ms and nonsense is ignored`() {
        assertEquals(OutputLatency.MAX_US, OutputLatency.pick(700_000, -1, bluetooth = false))
        // Um carimbo disparatado (3 s) não conta: vale o que a saída declara.
        assertEquals(40_000, OutputLatency.pick(3_000_000, 40_000, bluetooth = false))
        assertEquals(-1, OutputLatency.pick(3_000_000, -5, bluetooth = false))
        assertEquals(OutputLatency.BLUETOOTH_DEFAULT_US, OutputLatency.pick(-20_000, 9_000_000, bluetooth = true))
    }

    @Test
    fun `the reported latency leaves out the track buffer`() {
        // PCM: 140 ms com um buffer de 4800 amostras a 48 kHz (100 ms) são 40 ms da saída.
        assertEquals(40_000, OutputLatency.reported(140, 4_800, 48_000))
        // Passthrough: o buffer de 64 KB conta-se em bytes (1365 ms a 48 kHz).
        assertEquals(1_415_000 - 65_536 * 1_000_000L / 48_000, OutputLatency.reported(1_415, 65_536, 48_000))
        // Números que não batem (o buffer maior do que o total, nada declarado): não se sabe.
        assertEquals(-1, OutputLatency.reported(50, 4_800, 48_000))
        assertEquals(-1, OutputLatency.reported(0, 4_800, 48_000))
        assertEquals(-1, OutputLatency.reported(140, 4_800, 0))
        assertEquals(-1, OutputLatency.reported(5_000, 4_800, 48_000))
    }

    @Test
    fun `the first measure counts at once and jitter is smoothed`() {
        val l = OutputLatency()
        assertFalse(l.known)
        assertEquals(0, l.latencyUs)
        assertEquals(0, l.measure(-1, -1, bluetooth = false))
        assertFalse(l.known)
        assertEquals(200_000, l.measure(200_000, -1, bluetooth = true))
        assertTrue(l.known)
        // ±10 ms a tremer: o valor quase não mexe.
        for (i in 0 until 40) {
            val v = l.measure(if (i % 2 == 0) 210_000 else 190_000, -1, bluetooth = true)
            assertTrue(v in 195_000..205_000, "$v")
        }
        // Uma deriva pequena acompanha-se aos poucos.
        repeat(30) { l.measure(220_000, -1, bluetooth = true) }
        assertTrue(l.latencyUs in 218_000..220_000, "${l.latencyUs}")
        // Sem medida nova fica o que estava.
        assertEquals(l.latencyUs, l.measure(-1, -1, bluetooth = false))
    }

    @Test
    fun `a lone outlier is ignored and a real change is taken after three measures`() {
        val l = OutputLatency()
        l.measure(30_000, -1, bluetooth = false)
        assertEquals(30_000, l.measure(250_000, -1, bluetooth = false))
        assertEquals(30_000, l.measure(30_000, -1, bluetooth = false))
        // Duas longe mas diferentes uma da outra não chegam.
        assertEquals(30_000, l.measure(250_000, -1, bluetooth = false))
        assertEquals(30_000, l.measure(120_000, -1, bluetooth = false))
        assertEquals(30_000, l.measure(250_000, -1, bluetooth = false))
        assertEquals(30_000, l.measure(255_000, -1, bluetooth = false))
        assertEquals(245_000, l.measure(245_000, -1, bluetooth = false))
    }

    @Test
    fun `a route change forgets the value or starts from the one that route had`() {
        val l = OutputLatency()
        l.measure(200_000, -1, bluetooth = true)
        l.reset()
        assertFalse(l.known)
        assertEquals(0, l.latencyUs)
        assertEquals(25_000, l.measure(-1, 25_000, bluetooth = false))
        l.reset(180_000)
        assertTrue(l.known)
        assertEquals(180_000, l.latencyUs)
        l.reset(9_000_000)
        assertEquals(OutputLatency.MAX_US, l.latencyUs)
    }
}
