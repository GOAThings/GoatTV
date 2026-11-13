package cc.slokker.itv.media

/**
 * As legendas WebVTT de um HLS (EXT-X-MEDIA TYPE=SUBTITLES): cada segmento .vtt é um ficheiro com o cabeçalho
 * `X-TIMESTAMP-MAP` (o tempo do TS que corresponde a um tempo do ficheiro) e as falas. Dentro do TS que o motor lê,
 * cada fala vai num PES ([Codec.WEBVTT]): o PTS é o início e o [payload] tem a duração e o texto.
 */
object WebVtt {
    /** Uma fala: início e fim em ms (no tempo do ficheiro) e o texto, sem as etiquetas (<i>, <c.x>…). */
    class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Um ficheiro lido: as falas e o mapa para o relógio do TS (null sem `X-TIMESTAMP-MAP`). */
    class File(val cues: List<Cue>, val mpegts: Long?, val localMs: Long)

    fun parse(text: String): File {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>()
        var mpegts: Long? = null
        var localMs = 0L
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("X-TIMESTAMP-MAP=")) {
                for (part in line.substringAfter('=').split(',')) {
                    val key = part.substringBefore(':').trim()
                    val value = part.substringAfter(':').trim()
                    if (key == "MPEGTS") mpegts = value.toLongOrNull()
                    if (key == "LOCAL") localMs = time(value) ?: 0
                }
            }
            if ("-->" in line) {
                val start = time(line.substringBefore("-->").trim())
                val end = time(line.substringAfter("-->").trim().substringBefore(' ').substringBefore('\t'))
                val body = ArrayList<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) body += lines[i++]
                val clean = body.joinToString("\n") { stripTags(it) }.trim()
                if (start != null && end != null && end > start && clean.isNotEmpty()) cues += Cue(start, end, clean)
                continue
            }
            i++
        }
        return File(cues, mpegts, localMs)
    }

    /** `hh:mm:ss.mmm` ou `mm:ss.mmm` em ms. */
    private fun time(text: String): Long? {
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().replace(',', '.')
        val whole = seconds.substringBefore('.').toLongOrNull() ?: return null
        val ms = seconds.substringAfter('.', "0").padEnd(3, '0').take(3).toLongOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        return ((hours * 60 + minutes) * 60 + whole) * 1000 + ms
    }

    private val TAG = Regex("<[^>]*>")

    private fun stripTags(line: String): String =
        TAG.replace(line, "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&lrm;", "").replace("&rlm;", "")

    /** O PES de uma fala: a duração (ms, 4 bytes) e o texto em UTF-8. */
    fun payload(durationMs: Long, text: String): ByteArray {
        val d = durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        return byteArrayOf((d ushr 24).toByte(), (d ushr 16).toByte(), (d ushr 8).toByte(), d.toByte()) + text.encodeToByteArray()
    }

    /** A duração (ms) de um PES de [payload]. */
    fun durationMs(data: ByteArray): Long =
        if (data.size < 4) 0 else (((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF))

    /** O texto de um PES de [payload]. */
    fun text(data: ByteArray): String = if (data.size <= 4) "" else data.decodeToString(4, data.size)
}

/**
 * Os segmentos .vtt de uma faixa de legendas, entregues como o [TsDemuxer] entrega: uma faixa e um [TsSample] por
 * fala, no relógio do TS (pelo `X-TIMESTAMP-MAP`). As falas que passam de um segmento para o seguinte vêm repetidas
 * nos dois: só saem uma vez.
 */
class WebVttDemuxer(private val listener: TsListener, private val language: String?) {
    private var announced = false
    private val seen = ArrayDeque<String>()

    fun feed(segment: ByteArray) {
        val file = WebVtt.parse(segment.decodeToString())
        val track = TsTrack(1, 0x06, Codec.WEBVTT, TrackKind.SUBTITLE, language)
        if (!announced) {
            announced = true
            listener.onTracks(listOf(track))
        }
        for (c in file.cues) {
            val pts = ((file.mpegts ?: 0) + (c.startMs - file.localMs) * 90) and MASK_33
            val key = "$pts|${c.text}"
            if (key in seen) continue
            seen.addLast(key)
            if (seen.size > 200) seen.removeFirst()
            listener.onSample(track, TsSample(pts, pts, WebVtt.payload(c.endMs - c.startMs, c.text), true, false))
        }
    }

    private companion object {
        const val MASK_33 = (1L shl 33) - 1
    }
}
