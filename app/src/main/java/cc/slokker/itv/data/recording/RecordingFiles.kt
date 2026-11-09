package cc.slokker.itv.data.recording

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

/**
 * As gravações no disco: em [dir] cada uma é `<id>.ts` (o vídeo) e `<id>.json` (a [RecordingInfo]). A lista do que
 * está marcado ([Schedule]) fica noutro sítio, [scheduleFile], na memória interna (tem os URLs com as credenciais).
 *
 * As fichas escrevem-se primeiro num `.tmp` e depois trocam de nome: uma app que morre a meio da escrita não deixa
 * uma ficha partida. Uma ficha que não se lê é ignorada (a gravação não aparece, o ficheiro fica).
 */
class RecordingFiles(val dir: File, private val scheduleFile: File) {
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    fun videoFile(id: String) = File(dir, "$id$VIDEO")
    private fun infoFile(id: String) = File(dir, "$id$INFO")

    /** Todas as gravações, as mais recentes primeiro. */
    fun list(): List<RecordingInfo> =
        dir.listFiles { f -> f.name.endsWith(INFO) }.orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.fileStartMs }

    fun read(id: String): RecordingInfo? = read(infoFile(id))

    private fun read(file: File): RecordingInfo? = try {
        parseInfo(file.readText())
    } catch (e: Exception) {
        null
    }

    fun write(info: RecordingInfo) {
        dir.mkdirs()
        atomicWrite(infoFile(info.id), encodeInfo(info))
    }

    /** Apaga o vídeo e a ficha. */
    fun delete(id: String): Boolean {
        val video = videoFile(id)
        val ok = !video.exists() || video.delete()
        infoFile(id).delete()
        return ok
    }

    fun encodeInfo(info: RecordingInfo): String = gson.toJson(info)

    /** Lê uma ficha; os campos que faltem ficam com o valor por omissão (null nos textos opcionais). */
    fun parseInfo(json: String): RecordingInfo? {
        val o = JsonParser.parseString(json).asJsonObject
        if (!o.has("id") || !o.has("state")) return null
        return gson.fromJson(o, RecordingInfo::class.java)
    }

    fun loadSchedule(): Schedule = try {
        if (!scheduleFile.exists()) Schedule() else parseSchedule(scheduleFile.readText())
    } catch (e: Exception) {
        Schedule()
    }

    fun saveSchedule(schedule: Schedule) {
        scheduleFile.parentFile?.mkdirs()
        atomicWrite(scheduleFile, encodeSchedule(schedule))
    }

    fun encodeSchedule(schedule: Schedule): String = gson.toJson(schedule)

    fun parseSchedule(json: String): Schedule {
        val s = gson.fromJson(json, Schedule::class.java) ?: return Schedule()
        // O Gson não usa os valores por omissão do Kotlin: uma lista que falte vem null.
        @Suppress("SENSELESS_COMPARISON")
        return Schedule(
            version = s.version,
            recordings = (s.recordings ?: emptyList()).filter { it.id != null && it.candidates != null },
            series = s.series ?: emptyList(),
            handled = s.handled ?: emptyMap(),
        )
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Não deu para gravar ${file.name}")
        }
    }

    companion object {
        const val VIDEO = ".ts"
        const val INFO = ".json"
    }
}
