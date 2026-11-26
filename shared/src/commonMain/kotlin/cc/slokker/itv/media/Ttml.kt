package cc.slokker.itv.media

/**
 * As legendas TTML (IMSC, EBU-TT-D, DFXP: um ficheiro, ou cada amostra de um `stpp` num MP4) lidas para falas como as
 * do WebVTT ([WebVtt.Cue]). De cada `<p>` fica o início e o fim (`begin`/`end`/`dur`, no `<p>` ou nos `<span>` dele)
 * e o texto, com o `<br/>` como mudança de linha; o estilo e a posição ficam de fora. Os tempos podem ser
 * `hh:mm:ss.mmm`, `hh:mm:ss:ff` (imagens, pelo `ttp:frameRate`), `12.5s`, `500ms`, `2m`, `1h` ou `100t` (pelo
 * `ttp:tickRate`).
 */
object Ttml {
    private val P = Regex("<(?:[A-Za-z0-9_]+:)?p(\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?p\\s*>", RegexOption.DOT_MATCHES_ALL)
    private val SPAN_TIME = Regex("<(?:[A-Za-z0-9_]+:)?span\\s[^>]*\\bbegin\\s*=\\s*\"([^\"]*)\"[^>]*\\bend\\s*=\\s*\"([^\"]*)\"")
    private val BR = Regex("<(?:[A-Za-z0-9_]+:)?br\\s*/?>")
    private val TAG = Regex("<[^>]*>")
    private val ATTR = Regex("([A-Za-z0-9_:.-]+)\\s*=\\s*\"([^\"]*)\"")

    /** As falas de [xml]; [offsetMs] soma-se aos tempos (as amostras de um MP4 com tempos relativos a elas). */
    fun parse(xml: String, offsetMs: Long = 0): List<WebVtt.Cue> {
        val head = xml.substringBefore("<body").substringBefore(":body")
        val rootAttrs = attributes(Regex("<(?:[A-Za-z0-9_]+:)?tt\\s[^>]*>").find(head)?.value.orEmpty())
        val frameRate = rootAttrs.entries.firstOrNull { it.key.endsWith("frameRate") }?.value?.toDoubleOrNull() ?: 30.0
        val multiplier = rootAttrs.entries.firstOrNull { it.key.endsWith("frameRateMultiplier") }?.value?.split(' ')
            ?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 && it[1] != 0.0 }?.let { it[0] / it[1] } ?: 1.0
        val tickRate = rootAttrs.entries.firstOrNull { it.key.endsWith("tickRate") }?.value?.toDoubleOrNull() ?: (frameRate * multiplier)
        val clock = Clock(frameRate * multiplier, tickRate)
        val out = ArrayList<WebVtt.Cue>()
        for (m in P.findAll(xml)) {
            val attrs = attributes(m.groupValues[1])
            val body = m.groupValues[2]
            var begin = attrs["begin"]?.let { clock.ms(it) }
            var end = attrs["end"]?.let { clock.ms(it) } ?: attrs["dur"]?.let { d -> begin?.let { b -> clock.ms(d)?.plus(b) } }
            if (begin == null || end == null) {
                // Os tempos nos <span> (cada um com o seu): fica do primeiro início ao último fim.
                val spans = SPAN_TIME.findAll(body).mapNotNull { s -> clock.ms(s.groupValues[1])?.let { b -> clock.ms(s.groupValues[2])?.let { e -> b to e } } }.toList()
                if (spans.isEmpty()) continue
                begin = spans.minOf { it.first }
                end = spans.maxOf { it.second }
            }
            val text = decode(TAG.replace(BR.replace(body, "\n"), "")).lines().joinToString("\n") { it.trim() }.trim()
            if (text.isEmpty() || end <= begin) continue
            out += WebVtt.Cue(begin + offsetMs, end + offsetMs, text)
        }
        return out
    }

    /** O texto parece um documento TTML. */
    fun looksLike(text: String): Boolean {
        val head = text.take(2048)
        return Regex("<(?:[A-Za-z0-9_]+:)?tt[\\s>]").containsMatchIn(head)
    }

    private class Clock(val fps: Double, val tickRate: Double) {
        fun ms(text: String): Long? {
            val t = text.trim()
            val clock = t.split(':')
            if (clock.size in 3..4) {
                val h = clock[0].toDoubleOrNull() ?: return null
                val m = clock[1].toDoubleOrNull() ?: return null
                val s = clock[2].toDoubleOrNull() ?: return null
                val frames = clock.getOrNull(3)?.substringBefore('.')?.toDoubleOrNull() ?: 0.0
                return ((h * 3600 + m * 60 + s + frames / fps) * 1000).toLong()
            }
            val unit = t.dropWhile { it.isDigit() || it == '.' }
            val v = t.dropLast(unit.length).toDoubleOrNull() ?: return null
            val seconds = when (unit) {
                "h" -> v * 3600
                "m" -> v * 60
                "s", "" -> v
                "ms" -> v / 1000
                "f" -> v / fps
                "t" -> v / tickRate
                else -> return null
            }
            return (seconds * 1000).toLong()
        }
    }

    private fun attributes(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1].substringAfter(':') to it.groupValues[2] } +
            ATTR.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun decode(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#160;", " ").replace("&nbsp;", " ").replace("&amp;", "&")
}

/**
 * As legendas dentro de um MP4 fragmentado (DASH, ou HLS com EXT-X-MAP): o WebVTT (`wvtt`, cada amostra com caixas
 * `vttc` > `payl`) e o TTML (`stpp`, cada amostra um documento, ver [Ttml]). Uma por faixa de legendas: guarda o que o
 * segmento de inicialização disse (a escala de tempo, o formato) para os segmentos seguintes.
 * Os tempos saem em ms no tempo da faixa (o mesmo que o vídeo, como os do [Mp4Demuxer]).
 */
class Mp4Text {
    private var timescale = 1000L
    private var format: String? = null
    private var defaultDuration = 0L
    private var defaultSize = 0

    private class Sample(val offset: Long, val size: Int, val time: Long, val duration: Long)

    /** Os primeiros bytes são de um MP4 (ftyp, styp, moov, moof…). */
    companion object {
        fun looksLike(d: ByteArray): Boolean {
            if (d.size < 8) return false
            val t = CharArray(4) { (d[4 + it].toInt() and 0xFF).toChar() }.concatToString()
            return t in setOf("ftyp", "styp", "moov", "moof", "sidx", "emsg", "prft", "free")
        }
    }

    /** As falas de [d] (um ou mais pedaços de caixas de topo: o moov, ou moof + mdat). */
    fun cues(d: ByteArray): List<WebVtt.Cue> {
        val out = ArrayList<WebVtt.Cue>()
        var at = 0
        var moof = -1
        var moofEnd = -1
        while (at + 8 <= d.size) {
            val size = u32(d, at)
            if (size < 8 || at + size > d.size) break
            val end = (at + size).toInt()
            when (type(d, at + 4)) {
                "moov" -> moov(d, at + 8, end)
                "moof" -> { moof = at; moofEnd = end }
                "mdat" -> if (moof >= 0) {
                    traf(d, moof, moofEnd, d.size, out)
                    moof = -1
                }
            }
            at = end
        }
        return out
    }

    private fun moov(d: ByteArray, from: Int, to: Int) {
        each(d, from, to) { t, s, e ->
            when (t) {
                "trak" -> each(d, s, e) { t2, s2, e2 ->
                    if (t2 == "mdia") each(d, s2, e2) { t3, s3, e3 ->
                        when (t3) {
                            "mdhd" -> timescale = u32(d, s3 + if (d[s3].toInt() == 1) 20 else 12).takeIf { it > 0 } ?: 1000
                            "minf" -> find(d, s3, e3, "stbl")?.let { (a, b) -> find(d, a, b, "stsd") }?.let { (a, _) ->
                                if (a + 16 <= e3) format = type(d, a + 12)
                            }
                        }
                    }
                }
                "mvex" -> each(d, s, e) { t2, s2, _ ->
                    if (t2 == "trex") {
                        defaultDuration = u32(d, s2 + 12)
                        defaultSize = u32(d, s2 + 16).toInt()
                    }
                }
            }
        }
    }

    private fun traf(d: ByteArray, moofAt: Int, moofEnd: Int, end: Int, out: MutableList<WebVtt.Cue>) {
        each(d, moofAt + 8, moofEnd) { t, s, e ->
            if (t != "traf") return@each
            var base = moofAt.toLong()
            var duration = defaultDuration
            var size = defaultSize
            var time = 0L
            val samples = ArrayList<Sample>()
            each(d, s, e) { t2, s2, e2 ->
                when (t2) {
                    "tfhd" -> {
                        val flags = u24(d, s2 + 1)
                        var p = s2 + 8
                        if (flags and 0x1 != 0) { base = (u32(d, p) shl 32) or u32(d, p + 4); p += 8 }
                        if (flags and 0x2 != 0) p += 4
                        if (flags and 0x8 != 0) { duration = u32(d, p); p += 4 }
                        if (flags and 0x10 != 0) size = u32(d, p).toInt()
                    }
                    "tfdt" -> time = if (d[s2].toInt() == 1) (u32(d, s2 + 4) shl 32) or u32(d, s2 + 8) else u32(d, s2 + 4)
                    "trun" -> {
                        val flags = u24(d, s2 + 1)
                        val count = u32(d, s2 + 4).toInt()
                        var p = s2 + 8
                        var offset = if (samples.isEmpty()) base else samples.last().let { it.offset + it.size }
                        if (flags and 0x1 != 0) { offset = base + u32(d, p).toInt(); p += 4 }
                        if (flags and 0x4 != 0) p += 4
                        var t0 = samples.lastOrNull()?.let { it.time + it.duration } ?: time
                        for (i in 0 until count) {
                            if (p > e2) break
                            val dur = if (flags and 0x100 != 0) u32(d, p).also { p += 4 } else duration
                            val sz = if (flags and 0x200 != 0) u32(d, p).toInt().also { p += 4 } else size
                            if (flags and 0x400 != 0) p += 4
                            if (flags and 0x800 != 0) p += 4
                            samples += Sample(offset, sz, t0, dur)
                            offset += sz
                            t0 += dur
                        }
                    }
                }
            }
            for (sample in samples) {
                val a = sample.offset.toInt()
                if (a < 0 || a + sample.size > end || sample.size <= 0) continue
                val startMs = sample.time * 1000 / timescale
                val endMs = (sample.time + sample.duration) * 1000 / timescale
                when (format) {
                    "wvtt" -> each(d, a, a + sample.size) { bt, bs, be ->
                        if (bt == "vttc") {
                            var text: String? = null
                            each(d, bs, be) { ct, cs, ce -> if (ct == "payl") text = d.decodeToString(cs, ce) }
                            text?.let { WebVtt.parse("WEBVTT\n\n00:00.000 --> 00:01.000\n$it\n").cues.firstOrNull()?.text }
                                ?.let { if (endMs > startMs) out += WebVtt.Cue(startMs, endMs, it) }
                        }
                    }
                    "stpp" -> {
                        val xml = d.decodeToString(a, a + sample.size)
                        val cues = Ttml.parse(xml)
                        // Tempos relativos à amostra (todos dentro da duração dela) ou já no tempo da faixa.
                        val relative = startMs > 10_000 && cues.isNotEmpty() && cues.all { it.endMs <= (endMs - startMs) + 1000 }
                        out += if (relative) cues.map { WebVtt.Cue(it.startMs + startMs, it.endMs + startMs, it.text) } else cues
                    }
                }
            }
        }
    }

    private inline fun each(d: ByteArray, from: Int, to: Int, f: (String, Int, Int) -> Unit) {
        var at = from
        while (at + 8 <= to) {
            val size = u32(d, at)
            if (size < 8 || at + size > to) return
            f(type(d, at + 4), at + 8, (at + size).toInt())
            at += size.toInt()
        }
    }

    private fun find(d: ByteArray, from: Int, to: Int, want: String): Pair<Int, Int>? {
        var r: Pair<Int, Int>? = null
        each(d, from, to) { t, s, e -> if (r == null && t == want) r = s to e }
        return r
    }

    private fun u32(d: ByteArray, at: Int): Long =
        ((d[at].toLong() and 0xFF) shl 24) or ((d[at + 1].toLong() and 0xFF) shl 16) or ((d[at + 2].toLong() and 0xFF) shl 8) or (d[at + 3].toLong() and 0xFF)

    private fun u24(d: ByteArray, at: Int): Int = ((d[at].toInt() and 0xFF) shl 16) or ((d[at + 1].toInt() and 0xFF) shl 8) or (d[at + 2].toInt() and 0xFF)

    private fun type(d: ByteArray, at: Int): String = CharArray(4) { (d[at + it].toInt() and 0xFF).toChar() }.concatToString()
}
