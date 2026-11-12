package cc.slokker.itv.data.recording

import cc.slokker.itv.data.RingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCutTest {
    /** Um ponto a cada 500 ms, de [from] a [to] (tempo do vídeo), 1000 bytes cada. */
    private fun index(from: Long, to: Long) = (from..to step 500).map { RingEntry(it, it * 2) }

    @Test
    fun oInicioEOUltimoPontoAteAHoraPedida() {
        val now = 1_000_000L
        val entries = index(0, 600_000)
        // O direto (600 s de vídeo) é "agora": o programa começou há 7 min e acaba daqui a 20.
        val r = RecordingCut.range(entries, 600_000, now, now - 420_000, now + 1_200_000)!!
        assertEquals(180_000L, r.startMediaMs)
        assertEquals(360_000L, r.startOffset)
        assertEquals(1_800_000L, r.endMediaMs)
        assertEquals(now - 420_000, r.fileStartMs)
        assertFalse(r.late)
    }

    @Test
    fun entrePontosFicaNoAnterior() {
        val now = 1_000_000L
        val r = RecordingCut.range(index(0, 600_000), 600_000, now, now - 420_300, now)!!
        // 179 700 ms não é um ponto: começa no de 179 500 (meio segundo a mais, nunca a menos).
        assertEquals(179_500L, r.startMediaMs)
        assertEquals(now - 420_500, r.fileStartMs)
    }

    /** O programa começou antes do que o anel guarda: começa no mais antigo e diz que chegou tarde. */
    @Test
    fun antesDoAnelComecaNoMaisAntigo() {
        val now = 1_000_000L
        val r = RecordingCut.range(index(300_000, 600_000), 600_000, now, now - 3_600_000, now + 60_000)!!
        assertEquals(300_000L, r.startMediaMs)
        assertTrue(r.late)
        assertEquals(660_000L, r.endMediaMs)
    }

    @Test
    fun oFimNuncaAntesDoInicio() {
        val now = 1_000_000L
        val r = RecordingCut.range(index(0, 10_000), 10_000, now, now - 5_000, now - 60_000)!!
        assertEquals(r.startMediaMs, r.endMediaMs)
    }

    @Test
    fun semIndiceNaoHaCorte() {
        assertNull(RecordingCut.range(emptyList(), 0, 0, 0, 0))
    }

    @Test
    fun procuraBinaria() {
        val entries = index(0, 10_000)
        assertNull(RecordingCut.atOrBefore(entries, -1))
        assertEquals(0L, RecordingCut.atOrBefore(entries, 499)!!.mediaMs)
        assertEquals(500L, RecordingCut.atOrBefore(entries, 500)!!.mediaMs)
        assertEquals(10_000L, RecordingCut.atOrBefore(entries, 99_999)!!.mediaMs)
    }
}
