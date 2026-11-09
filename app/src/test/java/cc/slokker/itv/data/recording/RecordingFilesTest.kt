package cc.slokker.itv.data.recording

import com.google.gson.JsonParser
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** As fichas das gravações e a lista do que está marcado: o formato no disco fica fixo (como em PersistedFormatTest). */
class RecordingFilesTest {
    private lateinit var dir: File
    private lateinit var files: RecordingFiles

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "recfiles-${System.nanoTime()}").apply { mkdirs() }
        files = RecordingFiles(File(dir, "recordings"), File(dir, "internal/schedule.json"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("formats/$name")!!.readText()

    @Test
    fun aFichaLidaEEscritaFicaIgual() {
        val json = fixture("recording.json")
        val info = files.parseInfo(json)!!
        assertEquals("Telejornal", info.title)
        assertEquals(RecordingState.DONE, info.state)
        assertEquals(125_500L, info.mediaStartMs)
        assertEquals(3_905_250L, info.mediaEndMs)
        assertEquals(1, info.gaps)
        assertEquals(info.fileEndMs - info.fileStartMs, info.durationMs)
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(files.encodeInfo(info)))
    }

    @Test
    fun aListaMarcadaLidaEEscritaFicaIgual() {
        val json = fixture("schedule.json")
        val s = files.parseSchedule(json)
        assertEquals(1, s.recordings.size)
        assertEquals("http://iptv.example.com/live/u/p/101.ts", s.recordings[0].url)
        assertEquals("series-1", s.series.single().id)
        assertEquals(2, s.paddingBeforeMin)
        assertEquals(5, s.paddingAfterMin)
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(files.encodeSchedule(s)))
    }

    @Test
    fun campoQueFaltaFicaVazio() {
        val s = files.parseSchedule("""{"version":1}""")
        assertTrue(s.recordings.isEmpty() && s.series.isEmpty() && s.handled.isEmpty())
        // Sem margens guardadas (ou uma que não está nas opções): as de omissão.
        assertEquals(RecordingPlan.PADDING_BEFORE_DEFAULT, s.paddingBeforeMin)
        assertEquals(RecordingPlan.PADDING_AFTER_DEFAULT, files.parseSchedule("""{"paddingAfterMin":7}""").paddingAfterMin)
        assertNull(files.parseInfo("""{"version":1}"""))
    }

    @Test
    fun gravarListarEApagar() {
        val info = files.parseInfo(fixture("recording.json"))!!
        files.write(info)
        files.write(info.copy(id = "older", fileStartMs = info.fileStartMs - 1_000))
        files.videoFile(info.id).writeText("ts")
        File(files.dir, "partida.json").writeText("{")
        assertEquals(listOf(info.id, "older"), files.list().map { it.id })
        assertTrue(files.delete(info.id))
        assertFalse(files.videoFile(info.id).exists())
        assertEquals(listOf("older"), files.list().map { it.id })
        // Nenhum .tmp fica para trás.
        assertTrue(files.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun listaMarcadaNoDisco() {
        assertTrue(files.loadSchedule().recordings.isEmpty())
        val s = files.parseSchedule(fixture("schedule.json"))
        files.saveSchedule(s)
        assertEquals(s, files.loadSchedule())
    }
}
