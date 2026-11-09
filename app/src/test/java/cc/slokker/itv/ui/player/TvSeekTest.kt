package cc.slokker.itv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pausar, recuar e avançar na TV: o mesmo modelo para o catch-up e para a pausa do direto. */
class TvSeekTest {
    private val log = mutableListOf<String>()
    private var position = 600_000L
    private var behind = 0L

    private val archive = SeekTarget.archive(
        position = { position }, durationMs = 3_600_000L, stepMs = 60_000L,
        onSeek = { log += "seekCatchup $it" }, onPause = { log += "pause" }, onPlay = { log += "play" }, onExit = { log += "exit" },
    )
    private val ring = SeekTarget.ring(
        behind = { behind }, window = { 45_000L }, stepMs = 10_000L,
        onSeek = { log += "seekBehind $it" }, onPause = { log += "pause" }, onResume = { log += "resume" }, onLive = { log += "live" },
    )

    @Test
    fun catchUpSomaMinutosEPedeSoNoFim() {
        val seek = TvSeek()
        seek.step(archive, forward = false)
        seek.step(archive, forward = false)
        assertEquals(480_000L, seek.pending)
        assertTrue(seek.barVisible)
        seek.commit(archive, seek.pending!!)
        assertEquals(listOf("seekCatchup 480000"), log)
        // Fica por confirmar até o vídeo reabrir nessa posição (o ecrã chama reset).
        assertEquals(480_000L, seek.pending)
        seek.reset()
        assertNull(seek.pending)
    }

    @Test
    fun catchUpNaoPassaDoInicioNemDoFim() {
        val seek = TvSeek()
        position = 30_000L
        seek.step(archive, forward = false)
        assertEquals(0L, seek.pending)
        position = 3_590_000L
        seek.reset()
        seek.step(archive, forward = true)
        assertEquals(3_600_000L, seek.pending)
    }

    @Test
    fun pausaDoDiretoRecuaAtrasDoDiretoEFazOSaltoNoDisco() {
        val seek = TvSeek()
        seek.togglePause(ring)
        assertTrue(seek.paused)
        repeat(6) { seek.step(ring, forward = false) }
        // A janela só tem 45 s.
        assertEquals(45_000L, seek.pending)
        seek.step(ring, forward = true)
        assertEquals(35_000L, seek.pending)
        seek.commit(ring, seek.pending!!)
        assertEquals(listOf("pause", "seekBehind 35000"), log)
        assertNull(seek.pending)
        assertFalse(seek.paused)
    }

    @Test
    fun pausaEContinuaNoMesmoSitio() {
        val seek = TvSeek()
        seek.togglePause(ring)
        seek.togglePause(ring)
        seek.togglePause(archive)
        seek.togglePause(archive)
        assertEquals(listOf("pause", "resume", "pause", "play"), log)
    }

    @Test
    fun voltarAoDiretoLimpaTudo() {
        val seek = TvSeek()
        seek.togglePause(ring)
        seek.step(ring, forward = false)
        seek.goLive(ring)
        assertFalse(seek.paused)
        assertNull(seek.pending)
        assertFalse(seek.barVisible)
        assertEquals("live", log.last())
        seek.goLive(archive)
        assertEquals("exit", log.last())
    }
}
