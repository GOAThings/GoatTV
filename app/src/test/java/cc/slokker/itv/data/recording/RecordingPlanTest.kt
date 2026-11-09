package cc.slokker.itv.data.recording

import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.recording.RecordingPlan.Answer
import cc.slokker.itv.data.recording.RecordingPlan.Busy
import cc.slokker.itv.data.recording.RecordingPlan.Decision
import cc.slokker.itv.data.recording.RecordingPlan.Watching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlanTest {
    private val min = 60_000L
    private val start = 10_000_000L

    private fun rec(
        id: String = "r1",
        channel: String = "101",
        startMs: Long = start,
        endMs: Long = start + 30 * min,
        candidates: List<StreamCandidate> = listOf(StreamCandidate("http://x/$channel.ts", "ts"), StreamCandidate("http://x/$channel.m3u8", "m3u8")),
    ) = ScheduledRecording(id, "p1", channel, "Ch $channel", null, "News", startMs, endMs, candidates, "UA")

    private val w = RecordingPlan.window(start, start + 30 * min, 2, 5)

    private fun decide(now: Long, watching: Watching? = null, busy: List<Busy> = emptyList(), answer: Answer? = null, r: ScheduledRecording = rec()) =
        RecordingPlan.decide(r, w, now, watching, busy, answer)

    @Test
    fun margens() {
        assertEquals(start - 2 * min, w.fromMs)
        assertEquals(start + 35 * min, w.toMs)
        val none = RecordingPlan.window(start, start + min, 0, 0)
        assertEquals(start, none.fromMs)
        assertEquals(start + min, none.toMs)
        assertEquals(listOf(0, 1, 2, 5, 10), RecordingPlan.PADDING_OPTIONS)
    }

    @Test
    fun semNadaAVerGravaAHora() {
        assertEquals(Decision.Wait, decide(w.fromMs - 2 * min))
        assertEquals(Decision.Wait, decide(w.fromMs - 30_000))
        assertEquals(Decision.Start, decide(w.fromMs))
        assertEquals(Decision.Start, decide(start + 10 * min))
        assertEquals(Decision.Expired, decide(w.toMs))
    }

    @Test
    fun noMesmoCanalPartilhaALigacao() {
        val same = Watching("p1", "101")
        assertEquals(Decision.Wait, decide(w.fromMs - 30_000, same))
        assertEquals(Decision.Start, decide(w.fromMs, same))
        // O mesmo canal noutra lista é outro canal.
        assertEquals(Decision.Prompt, decide(w.fromMs - 30_000, Watching("p2", "101")))
    }

    @Test
    fun aVerOutroCanalPerguntaEMudaSemResposta() {
        val other = Watching("p1", "202")
        assertEquals(Decision.Wait, decide(w.fromMs - 61_000, other))
        assertEquals(Decision.Prompt, decide(w.fromMs - 60_000, other))
        assertEquals(Decision.Prompt, decide(w.fromMs - 1, other))
        // Ninguém respondeu: à hora, muda-se.
        assertEquals(Decision.Switch, decide(w.fromMs, other))
        assertEquals(Decision.Switch, decide(w.fromMs - 30_000, other, answer = Answer.SWITCH))
        assertEquals(Decision.Skip(RecordingPlan.SKIP_USER), decide(w.fromMs - 30_000, other, answer = Answer.SKIP))
        assertEquals(Decision.Skip(RecordingPlan.SKIP_USER), decide(w.fromMs + min, other, answer = Answer.SKIP))
    }

    /** O catch-up (do servidor) do mesmo canal usa outra ligação: conta como outro canal. */
    @Test
    fun catchUpDoMesmoCanalContaComoOutro() {
        assertEquals(Decision.Prompt, decide(w.fromMs - 30_000, Watching("p1", "101", live = false)))
    }

    @Test
    fun outraGravacaoAGravarGanha() {
        val busy = listOf(Busy("p1", "303"))
        assertEquals(Decision.Wait, decide(w.fromMs - 30_000, busy = busy))
        assertEquals(Decision.Skip(RecordingPlan.SKIP_BUSY), decide(w.fromMs, busy = busy))
        // A gravar o mesmo canal (programas seguidos): grava também, na mesma ligação.
        assertEquals(Decision.Start, decide(w.fromMs, watching = Watching("p1", "999"), busy = listOf(Busy("p1", "101"))))
    }

    @Test
    fun soHlsNaoSeGrava() {
        val hls = rec(candidates = listOf(StreamCandidate("http://x/1.m3u8", "m3u8")))
        assertNull(hls.url)
        assertEquals(Decision.Wait, decide(w.fromMs - 30_000, r = hls))
        assertEquals(Decision.Skip(RecordingPlan.SKIP_HLS), decide(w.fromMs, r = hls))
        assertEquals("http://x/101.ts", rec().url)
    }

    @Test
    fun conflitosSoDeOutrosCanaisQueSeSobrepoem() {
        val a = rec("a", "101")
        val b = rec("b", "202", startMs = start + 30 * min, endMs = start + 60 * min) // começa quando a acaba: margens sobrepõem
        val c = rec("c", "101", startMs = start + 10 * min) // mesmo canal: partilha
        val d = rec("d", "303", startMs = start + 90 * min, endMs = start + 120 * min)
        assertEquals(listOf(b), RecordingPlan.conflicts(a, listOf(a, b, c, d), 2, 5))
        assertTrue(RecordingPlan.conflicts(a, listOf(a, b, c, d), 0, 0).isEmpty())
    }

    @Test
    fun proximoAlarme() {
        val s = Schedule(recordings = listOf(rec("a"), rec("b", startMs = start + 60 * min, endMs = start + 90 * min)))
        assertEquals(w.fromMs - RecordingPlan.WAKE_LEAD_MS, RecordingPlan.nextWake(s, 2, 5, start - 60 * min))
        // A meio da primeira: acorda já (ainda não acabou).
        assertEquals(start + 5 * min, RecordingPlan.nextWake(s, 2, 5, start + 5 * min))
        assertNull(RecordingPlan.nextWake(Schedule(), 2, 5, start))
    }

    @Test
    fun serieMarcaOMesmoTituloAindaPorGravar() {
        val rule = SeriesRule("s1", "p1", "101", "Ch 101", null, "Telejornal")
        val entries = listOf(
            EpgEntry("Telejornal", start - 60 * min, start - 30 * min), // já acabou
            EpgEntry("TELEJORNAL ", start, start + 30 * min),
            EpgEntry("Desporto", start + 30 * min, start + 60 * min),
            EpgEntry("Telejornál", start + 24 * 60 * min, start + 24 * 60 * min + 30 * min),
            EpgEntry("Telejornal", start + 8 * 24 * 60 * min, start + 8 * 24 * 60 * min + 30 * min), // fora da janela
        )
        val marked = Schedule(
            recordings = listOf(rec(startMs = start)),
            handled = mapOf(ScheduledRecording.instanceKey("p1", "101", start + 24 * 60 * min) to start),
        )
        assertEquals(listOf(entries[1], entries[3]), RecordingPlan.seriesEntries(rule, entries, Schedule(), start - min))
        assertTrue(RecordingPlan.seriesEntries(rule, entries, marked, start - min).isEmpty())
    }

    @Test
    fun limpaOQueJaPassou() {
        val s = Schedule(recordings = listOf(rec("a")), handled = mapOf("old" to start - 4 * 24 * 60 * min, "new" to start))
        val pruned = RecordingPlan.prune(s, 2, 5, start + 36 * min)
        assertTrue(pruned.recordings.isEmpty())
        assertEquals(setOf("new"), pruned.handled.keys)
        assertEquals(1, RecordingPlan.prune(s, 2, 5, start + 34 * min).recordings.size)
    }

    @Test
    fun espaco() {
        val reserve = RecordingSpace.RESERVE_BYTES
        // Sem débito conhecido: 1 MB/s, 10 min = 600 MB além da reserva.
        assertTrue(RecordingSpace.canStart(reserve + 600_000_000, null))
        assertFalse(RecordingSpace.canStart(reserve + 599_999_999, null))
        assertTrue(RecordingSpace.canStart(reserve + 60_000_000, 100_000))
        assertTrue(RecordingSpace.mustStop(reserve))
        assertFalse(RecordingSpace.mustStop(reserve + 1))
        assertNull(RecordingSpace.rate(1_000, 4_999))
        assertEquals(500_000L, RecordingSpace.rate(5_000_000, 10_000))
    }
}
