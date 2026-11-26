package cc.slokker.itv.media

import kotlin.math.ceil
import kotlin.time.Instant

/**
 * Um manifesto MPEG-DASH (.mpd) lido para a forma de uma playlist HLS ([HlsPlaylist]), para os leitores do HLS do
 * Android e da Apple o tocarem sem mais nada (a qualidade, o áudio à parte juntado pelo [HlsMerger], a pausa do
 * direto, o CENC em [Cenc]):
 * - sem marca no endereço, é a "playlist principal": as qualidades do vídeo (`#goatdash=v0`, `v1`…) e o áudio de cada
 *   língua (`#goatdash=a0`…), cada um apontando para o mesmo MPD;
 * - com a marca, os segmentos dessa faixa: o `SegmentTemplate` (com ou sem `SegmentTimeline`, `$Number$`/`$Time$`)
 *   ou a `SegmentList`, de todos os períodos, com o segmento de inicialização em [HlsSegment.map];
 * - o `SegmentBase` (um ficheiro só, VOD): a playlist diz onde está o índice ([HlsPlaylist.dashIndex]); o leitor lê
 *   esses bytes (o `sidx`) e [withIndex] dá os segmentos;
 * - as legendas (WebVTT, TTML) são legendas à parte (`#goatdash=t0`…), lidas pelo WebVttDemuxer.
 * Os números dos segmentos são o tempo de início em ms (igual em todas as qualidades, de recarga em recarga e de um
 * período para o outro), e [HlsSegment.next] é onde acaba. Num direto (`type="dynamic"`) sem linha do tempo, os
 * segmentos que já existem contam-se pelo relógio ([nowMs]) e pelo `availabilityStartTime`.
 * O relógio dos diretos pode vir do servidor ([timing], o `UTCTiming`): o leitor acerta o [nowMs] com ele.
 * As legendas em texto: WebVTT e TTML, num ficheiro, aos segmentos ou dentro de MP4 (`wvtt`, `stpp`).
 */
object DashManifest {
    /** A marca que diz a faixa no endereço do MPD (não vai ao servidor: ver [fetchUrl]). */
    const val MARK = "#goatdash="

    /** Num direto, quantos segmentos para trás do fim se listam, no máximo. */
    private const val LIVE_WINDOW = 60

    /** O endereço a pedir ao servidor: sem a marca. */
    fun fetchUrl(uri: String): String = uri.substringBefore(MARK)

    /** Os primeiros bytes de uma resposta: um XML (o MPD diz-se depois, em [isManifest]). */
    fun looksLike(head: ByteArray): Boolean {
        var i = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < head.size && head[i].toInt().toChar().isWhitespace()) i++
        if (i >= head.size || head[i] != '<'.code.toByte()) return false
        val rest = head.copyOfRange(i, minOf(head.size, i + 5)).decodeToString()
        return rest.startsWith("<?xml") || rest.startsWith("<MPD") || rest.startsWith("<!--") || rest.startsWith("<mpd")
    }

    /** O texto é um MPD (tem o elemento `<MPD`). */
    fun isManifest(text: String): Boolean {
        val head = text.take(4096)
        return head.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("<") && Regex("<(?:[A-Za-z0-9_]+:)?MPD[\\s>]").containsMatchIn(head)
    }

    private fun failed(why: String) = HlsPlaylist(emptyList(), emptyList(), 6.0, true, why)

    private class Period(val node: XmlNode, val startMs: Long, val durationMs: Long?, val base: String)

    private class Clock(val dynamic: Boolean, val astMs: Long, val nowMs: Long, val windowMs: Long)

    /** [text] (o MPD de [url], talvez com a marca de uma faixa), com o relógio [nowMs] (ms desde 1970). */
    fun parse(text: String, url: String, nowMs: Long): HlsPlaylist {
        val selected = url.substringAfter(MARK, "").ifEmpty { null }
        val mpdUrl = fetchUrl(url)
        val root = Xml.parse(text)?.takeIf { it.name == "MPD" } ?: return failed("not a DASH manifest")
        val dynamic = root["type"] == "dynamic"
        val clock = Clock(
            dynamic,
            root["availabilityStartTime"]?.let { dateTime(it) } ?: 0L,
            nowMs,
            root["timeShiftBufferDepth"]?.let { durationMs(it) } ?: 0L,
        )
        val base = baseUrl(mpdUrl, root)
        val total = root["mediaPresentationDuration"]?.let { durationMs(it) }
        val periods = periods(root, base, total)
        if (periods.isEmpty()) return failed("DASH without periods")
        // A estrutura (as qualidades, as línguas) é a do período que está a dar (direto) ou do primeiro.
        val current = if (dynamic) periods.lastOrNull { it.startMs <= nowMs - clock.astMs } ?: periods.first() else periods.first()
        return if (selected == null) master(current, mpdUrl) else media(selected, periods, clock, total)
    }

    // ---- a playlist principal ----

    private class Sets(val video: List<XmlNode>, val audio: List<XmlNode>, val text: List<XmlNode>)

    private fun kind(set: XmlNode): String {
        val type = set["contentType"] ?: set["mimeType"]?.substringBefore('/')
            ?: set.all("Representation").firstNotNullOfOrNull { it["mimeType"]?.substringBefore('/') }
            ?: set.child("ContentComponent")?.get("contentType")
        if (type != null) return type
        val codecs = (set["codecs"] ?: set.child("Representation")?.get("codecs")).orEmpty()
        return when {
            set["width"] != null || set.child("Representation")?.get("width") != null -> "video"
            codecs.startsWith("mp4a") || codecs.startsWith("ac-3") || codecs.startsWith("ec-3") || codecs.startsWith("opus") -> "audio"
            else -> "other"
        }
    }

    private fun sets(period: XmlNode): Sets {
        val all = period.all("AdaptationSet").filter { set ->
            // As faixas para avançar depressa (trick mode) não são vídeo para ver.
            set.all("EssentialProperty").none { it["schemeIdUri"]?.contains("trickmode") == true }
        }
        val video = all.filter { kind(it) == "video" }
        val audio = all.filter { kind(it) == "audio" }
        // As legendas em texto: WebVTT e TTML, em ficheiro ou dentro de MP4 (wvtt, stpp); as de imagem (im1i…) não.
        val text = all.filter { set ->
            val rep = set.child("Representation")
            val mime = set["mimeType"] ?: rep?.get("mimeType")
            val codecs = (set["codecs"] ?: rep?.get("codecs")).orEmpty()
            val image = "im1i" in codecs || "im2i" in codecs
            !image && (mime == "text/vtt" || mime == "application/ttml+xml" ||
                ((mime == "application/mp4" || kind(set) == "text") && (codecs.startsWith("stpp") || codecs.startsWith("wvtt"))))
        }
        return Sets(video, audio, text)
    }

    /** O vídeo: as qualidades do primeiro conjunto (as de outro codec não se misturam). */
    private fun videoReps(sets: Sets): List<XmlNode> = sets.video.firstOrNull()?.all("Representation").orEmpty()

    /** O áudio de um conjunto: a melhor qualidade (o áudio é pequeno). */
    private fun audioRep(set: XmlNode): XmlNode? = set.all("Representation").maxByOrNull { it["bandwidth"]?.toLongOrNull() ?: 0 }

    private fun master(period: Period, mpdUrl: String): HlsPlaylist {
        val sets = sets(period.node)
        val audio = sets.audio.mapIndexedNotNull { i, set ->
            val rep = audioRep(set) ?: return@mapIndexedNotNull null
            val lang = set["lang"] ?: rep["lang"]
            val channels = (rep.child("AudioChannelConfiguration") ?: set.child("AudioChannelConfiguration"))?.get("value")?.toIntOrNull() ?: 0
            val label = (rep.child("Label") ?: set.child("Label"))?.text?.ifEmpty { null }
            HlsRendition("$mpdUrl${MARK}a$i", lang, role(set), channels, label ?: lang)
        }
        val subtitles = sets.text.mapIndexed { i, set ->
            val lang = set["lang"] ?: set.child("Representation")?.get("lang")
            val label = set.child("Label")?.text?.ifEmpty { null }
            HlsRendition("$mpdUrl${MARK}t$i", lang, name = label ?: lang)
        }
        val video = videoReps(sets)
        if (video.isEmpty()) {
            // Só áudio (uma rádio em DASH): o áudio é a "qualidade".
            if (audio.isEmpty()) return failed("DASH without video or audio")
            val a = audio.first()
            val rep = audioRep(sets.audio.first())
            return HlsPlaylist(listOf(HlsVariant(a.uri, rep?.get("bandwidth")?.toLongOrNull() ?: 0, 0, rep?.get("codecs"), subtitles = subtitles)), emptyList(), 6.0, false, null)
        }
        val set = sets.video.first()
        val variants = video.mapIndexed { i, rep ->
            HlsVariant(
                "$mpdUrl${MARK}v$i",
                rep["bandwidth"]?.toLongOrNull() ?: 0,
                (rep["height"] ?: set["height"])?.toIntOrNull() ?: 0,
                listOfNotNull(rep["codecs"] ?: set["codecs"], sets.audio.firstOrNull()?.let { audioRep(it)?.get("codecs") ?: it["codecs"] }).joinToString(",").ifEmpty { null },
                audioGroup = if (audio.isEmpty()) null else "dash-audio",
                audio = audio,
                subtitles = subtitles,
                frameRate = (rep["frameRate"] ?: set["frameRate"])?.let { frameRate(it) } ?: 0.0,
            )
        }
        return HlsPlaylist(variants, emptyList(), 6.0, false, null)
    }

    private fun role(set: XmlNode): Int {
        val roles = set.all("Role").mapNotNull { it["value"] } + set.all("Accessibility").mapNotNull { it["value"] }
        return when {
            "description" in roles || "1" in roles -> AudioRole.DESCRIPTION
            "enhanced-audio-intelligibility" in roles -> AudioRole.HEARING_IMPAIRED
            else -> AudioRole.MAIN
        }
    }

    // ---- os segmentos de uma faixa ----

    private fun media(selected: String, periods: List<Period>, clock: Clock, totalMs: Long?): HlsPlaylist {
        val kindChar = selected.firstOrNull()
        val index = selected.drop(1).toIntOrNull() ?: return failed("bad DASH track")
        val segments = ArrayList<HlsSegment>()
        var unsupported: String? = null
        var maxDuration = 0.0
        var dashIndex: DashIndex? = null
        for ((p, period) in periods.withIndex()) {
            val sets = sets(period.node)
            val (set, rep) = when (kindChar) {
                'v' -> {
                    val reps = videoReps(sets)
                    if (reps.isEmpty()) continue
                    sets.video.first() to reps[index.coerceIn(0, reps.lastIndex)]
                }
                'a' -> {
                    if (sets.audio.isEmpty()) continue
                    val s = sets.audio[index.coerceIn(0, sets.audio.lastIndex)]
                    s to (audioRep(s) ?: continue)
                }
                't' -> {
                    if (sets.text.isEmpty()) continue
                    val s = sets.text[index.coerceIn(0, sets.text.lastIndex)]
                    s to (s.child("Representation") ?: continue)
                }
                else -> return failed("bad DASH track")
            }
            val end = period.durationMs?.let { period.startMs + it } ?: periods.getOrNull(p + 1)?.startMs ?: totalMs
            val before = segments.size
            val why = representation(period, set, rep, end, clock, segments, text = kindChar == 't') { found ->
                if (periods.size > 1) unsupported = unsupported ?: "DASH SegmentBase with several periods" else dashIndex = found
            }
            if (why != null) unsupported = unsupported ?: why
            // Um período novo: o que vem depois é outro conteúdo (outro moov, outros tempos).
            if (before > 0 && segments.size > before) {
                val first = segments[before]
                segments[before] = HlsSegment(first.range, first.sequence, first.duration, true, null, first.map, first.next)
            }
        }
        for (s in segments) maxDuration = maxOf(maxDuration, s.duration)
        var list: List<HlsSegment> = segments
        if (clock.dynamic) {
            // O que já saiu da janela do direto (timeShiftBufferDepth), e no máximo [LIVE_WINDOW] segmentos.
            val nowMedia = clock.nowMs - clock.astMs
            if (clock.windowMs > 0) list = list.filter { it.next >= nowMedia - clock.windowMs - 1000 }
            list = list.takeLast(LIVE_WINDOW)
        }
        dashIndex?.let { if (unsupported == null) return HlsPlaylist(emptyList(), emptyList(), 6.0, true, null, dashIndex = it) }
        if (list.isEmpty() && unsupported == null) unsupported = if (clock.dynamic) null else "DASH without segments"
        return HlsPlaylist(emptyList(), list, if (maxDuration > 0) maxDuration else 6.0, !clock.dynamic, unsupported)
    }

    /**
     * Os segmentos de [rep] em [period] (até [endMs], o fim do período, se se sabe), juntos a [out]. Um `SegmentBase`
     * vai para [onIndex] (os segmentos estão no índice do ficheiro); umas legendas ([text]) sem segmentos são um
     * ficheiro só.
     */
    private fun representation(
        period: Period, set: XmlNode, rep: XmlNode, endMs: Long?, clock: Clock, out: MutableList<HlsSegment>,
        text: Boolean = false, onIndex: (DashIndex) -> Unit = {},
    ): String? {
        val base = baseUrl(baseUrl(period.base, set), rep)
        val id = rep["id"].orEmpty()
        val bandwidth = rep["bandwidth"]?.toLongOrNull() ?: 0
        val template = merged(listOfNotNull(period.node.child("SegmentTemplate"), set.child("SegmentTemplate"), rep.child("SegmentTemplate")))
        if (template != null) return fromTemplate(template, base, id, bandwidth, period, endMs, clock, out)
        val list = rep.child("SegmentList") ?: set.child("SegmentList") ?: period.node.child("SegmentList")
        if (list != null) return fromList(list, base, period, out)
        val segmentBase = rep.child("SegmentBase") ?: set.child("SegmentBase") ?: period.node.child("SegmentBase")
        if (text) {
            // Um ficheiro .vtt inteiro: o período todo.
            val end = endMs ?: (period.startMs + 6 * 3_600_000L)
            out += HlsSegment(HlsRange(base, 0, -1), period.startMs, (end - period.startMs) / 1000.0, false, null, null, end)
            return null
        }
        if (segmentBase != null) {
            val indexRange = segmentBase["indexRange"] ?: return "DASH SegmentBase without indexRange"
            val a = indexRange.substringBefore('-').trim().toLongOrNull() ?: return "DASH bad indexRange"
            val b = indexRange.substringAfter('-', "").trim().toLongOrNull() ?: return "DASH bad indexRange"
            val init = segmentBase.child("Initialization")?.let { range(it["sourceURL"]?.let { u -> HlsPlaylist.resolve(base, u) } ?: base, it["range"]) }
                ?: HlsRange(base, 0, a)
            val timescale = segmentBase["timescale"]?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
            val pto = segmentBase["presentationTimeOffset"]?.toLongOrNull() ?: 0L
            onIndex(DashIndex(HlsRange(base, a, b - a + 1), init, period.startMs, floorDiv(pto * 1000, timescale)))
            return null
        }
        return "DASH without segment information"
    }

    /**
     * Os segmentos de um `SegmentBase` ([playlist] com [HlsPlaylist.dashIndex]), pelo `sidx` em [sidx] (os bytes do
     * `indexRange`). Um índice que aponta para outros índices (`reference_type` 1) devolve outra vez uma playlist com
     * [HlsPlaylist.dashIndex]: o seguinte a ler (o leitor repete até não haver; os segmentos já lidos vão nela).
     */
    fun withIndex(playlist: HlsPlaylist, sidx: ByteArray): HlsPlaylist {
        val idx = playlist.dashIndex ?: return playlist
        var at = 0
        while (at + 8 <= sidx.size) {
            val size = u32(sidx, at)
            val type = sidx.copyOfRange(at + 4, at + 8).decodeToString()
            if (size < 8 || at + size > sidx.size) break
            if (type == "sidx") return fromSidx(idx, sidx, at + 8, (at + size).toInt())
            at += size.toInt()
        }
        return failed("DASH index without sidx")
    }

    private fun fromSidx(idx: DashIndex, d: ByteArray, from: Int, end: Int): HlsPlaylist {
        val version = d[from].toInt()
        var p = from + 4 + 4 // versão/flags, reference_ID
        val timescale = u32(d, p).takeIf { it > 0 } ?: return failed("DASH sidx without timescale")
        p += 4
        val earliest: Long
        val firstOffset: Long
        if (version == 0) {
            earliest = u32(d, p); firstOffset = u32(d, p + 4); p += 8
        } else {
            earliest = (u32(d, p) shl 32) or u32(d, p + 4); firstOffset = (u32(d, p + 8) shl 32) or u32(d, p + 12); p += 16
        }
        p += 2 // reserved
        val count = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        p += 2
        // Os pedaços contam a partir do fim do sidx (no ficheiro: o início do indexRange mais o que se leu até lá).
        var offset = idx.range.offset + end + firstOffset
        var time = earliest
        val segments = ArrayList<HlsSegment>(count)
        val children = ArrayList<HlsRange>()
        fun ms(t: Long) = idx.periodStartMs + floorDiv(t * 1000, timescale) - idx.ptoMs
        for (i in 0 until count) {
            if (p + 12 > d.size) break
            val ref = u32(d, p)
            val size = ref and 0x7FFFFFFF
            val duration = u32(d, p + 4)
            if (ref ushr 31 == 1L) {
                // Outro sidx (o dos pedaços desta parte): lê-se o início dele, que é onde está o índice.
                children += HlsRange(idx.range.uri, offset, minOf(size, MAX_SIDX_BYTES))
            } else {
                segments += HlsSegment(HlsRange(idx.range.uri, offset, size), ms(time), duration.toDouble() / timescale, false, null, idx.map, ms(time + duration))
            }
            offset += size
            time += duration
            p += 12
        }
        val done = idx.done + segments
        val queue = children + idx.queue
        if (queue.isNotEmpty()) {
            if (idx.depth >= MAX_SIDX_READS) return failed("DASH sidx too deep")
            val next = DashIndex(queue.first(), idx.map, idx.periodStartMs, idx.ptoMs, done, queue.drop(1), idx.depth + 1)
            return HlsPlaylist(emptyList(), emptyList(), 6.0, true, null, dashIndex = next)
        }
        val target = done.maxOfOrNull { it.duration } ?: 6.0
        return HlsPlaylist(emptyList(), done, target, true, if (done.isEmpty()) "DASH empty sidx" else null)
    }

    /** Um sidx filho lê-se até aqui (chega para uns 5000 pedaços). */
    private const val MAX_SIDX_BYTES = 64L * 1024
    /** Quantos sidx se leem, no máximo, num ficheiro. */
    const val MAX_SIDX_READS = 256

    /**
     * De onde vem a hora certa dos diretos (`UTCTiming`): "direct" (a hora vem no valor) ou "http" (o valor é um
     * endereço que responde com a hora, `http-xsdate`/`http-iso`). null se o MPD não diz, ou diz de outra forma.
     */
    fun timing(text: String): DashTiming? {
        val root = Xml.parse(text)?.takeIf { it.name == "MPD" } ?: return null
        for (t in root.all("UTCTiming")) {
            val scheme = t["schemeIdUri"].orEmpty()
            val value = t["value"]?.trim()?.ifEmpty { null } ?: continue
            when {
                scheme.startsWith("urn:mpeg:dash:utc:direct:") -> return DashTiming("direct", value)
                scheme.startsWith("urn:mpeg:dash:utc:http-xsdate:") || scheme.startsWith("urn:mpeg:dash:utc:http-iso:") ->
                    return DashTiming("http", value.split(' ').first())
            }
        }
        return null
    }

    /** A hora que um servidor de `UTCTiming` respondeu (ISO 8601, ou ms desde 1970), em ms; null se não se percebe. */
    fun serverTimeMs(body: String): Long? {
        val t = body.trim().removeSurrounding("\"")
        t.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return dateTime(t)
    }

    private fun u32(d: ByteArray, at: Int): Long =
        ((d[at].toLong() and 0xFF) shl 24) or ((d[at + 1].toLong() and 0xFF) shl 16) or ((d[at + 2].toLong() and 0xFF) shl 8) or (d[at + 3].toLong() and 0xFF)

    /** Um `SegmentTemplate` com os atributos e a linha do tempo herdados (o mais de dentro ganha). */
    private class Template(val attrs: Map<String, String>, val timeline: XmlNode?)

    private fun merged(levels: List<XmlNode>): Template? {
        if (levels.isEmpty()) return null
        val attrs = HashMap<String, String>()
        var timeline: XmlNode? = null
        for (l in levels) {
            attrs.putAll(l.attrs)
            l.child("SegmentTimeline")?.let { timeline = it }
        }
        return Template(attrs, timeline)
    }

    private fun fromTemplate(
        t: Template, base: String, id: String, bandwidth: Long, period: Period, endMs: Long?, clock: Clock, out: MutableList<HlsSegment>,
    ): String? {
        val media = t.attrs["media"] ?: return "DASH template without media"
        val timescale = t.attrs["timescale"]?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val pto = t.attrs["presentationTimeOffset"]?.toLongOrNull() ?: 0L
        val startNumber = t.attrs["startNumber"]?.toLongOrNull() ?: 1L
        val map = t.attrs["initialization"]?.let { HlsRange(HlsPlaylist.resolve(base, expand(it, id, 0, 0, bandwidth)), 0, -1) }
        fun ms(mediaTime: Long): Long = period.startMs + floorDiv((mediaTime - pto) * 1000, timescale)
        fun add(time: Long, duration: Long, number: Long) {
            val url = HlsPlaylist.resolve(base, expand(media, id, number, time, bandwidth))
            out += HlsSegment(HlsRange(url, 0, -1), ms(time), duration.toDouble() / timescale, false, null, map, ms(time + duration))
        }
        // Até onde já há segmentos: o fim do período e, num direto, o agora (em tempo do período, na escala dele).
        val limitMs = listOfNotNull(endMs, if (clock.dynamic) clock.nowMs - clock.astMs else null).minOrNull()
        val limit = limitMs?.let { floorDiv((it - period.startMs) * timescale, 1000) + pto }
        val timeline = t.timeline
        if (timeline != null) {
            val ss = timeline.all("S")
            var time = pto
            var number = startNumber
            var count = 0
            for ((i, s) in ss.withIndex()) {
                s["t"]?.toLongOrNull()?.let { time = it }
                val d = s["d"]?.toLongOrNull()?.takeIf { it > 0 } ?: continue
                val r = s["r"]?.toLongOrNull() ?: 0L
                val nextT = ss.getOrNull(i + 1)?.get("t")?.toLongOrNull()
                var left = if (r >= 0) r + 1 else Long.MAX_VALUE
                while (left > 0 && count < MAX_SEGMENTS) {
                    // r = -1: repete até ao S seguinte, ao fim do período ou ao agora.
                    if (r < 0) {
                        val stop = nextT ?: limit ?: break
                        if (time + d > stop) break
                    }
                    add(time, d, number)
                    time += d
                    number++
                    count++
                    left--
                }
            }
            return null
        }
        val d = t.attrs["duration"]?.toLongOrNull()?.takeIf { it > 0 } ?: return "DASH template without duration or timeline"
        val first: Long
        val last: Long
        if (clock.dynamic) {
            val now = limit ?: return null
            // Um segmento existe quando já acabou: o último é o que acabou antes de agora.
            last = floorDiv(now - pto, d) - 1
            first = maxOf(0, last - LIVE_WINDOW + 1)
        } else {
            val spanMs = (endMs ?: return "DASH without duration") - period.startMs
            first = 0
            last = ceil(spanMs.toDouble() * timescale / 1000 / d).toLong() - 1
        }
        if (last - first + 1 > MAX_SEGMENTS) return "DASH with too many segments"
        for (k in first..last) add(pto + k * d, d, startNumber + k)
        return null
    }

    private fun fromList(list: XmlNode, base: String, period: Period, out: MutableList<HlsSegment>): String? {
        val timescale = list["timescale"]?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val d = list["duration"]?.toLongOrNull() ?: return "DASH SegmentList without duration"
        val map = list.child("Initialization")?.let { init ->
            val uri = HlsPlaylist.resolve(base, init["sourceURL"] ?: "")
            range(uri, init["range"])
        }
        for ((k, s) in list.all("SegmentURL").withIndex()) {
            val uri = HlsPlaylist.resolve(base, s["media"] ?: "")
            val start = period.startMs + floorDiv(k * d * 1000, timescale)
            val end = period.startMs + floorDiv((k + 1) * d * 1000, timescale)
            out += HlsSegment(range(uri, s["mediaRange"]), start, d.toDouble() / timescale, false, null, map, end)
        }
        return null
    }

    /** `inicio-fim` (inclusivos) num [HlsRange]; sem ele, o ficheiro todo. */
    private fun range(uri: String, text: String?): HlsRange {
        val a = text?.substringBefore('-')?.trim()?.toLongOrNull()
        val b = text?.substringAfter('-', "")?.trim()?.toLongOrNull()
        return if (a != null && b != null && b >= a) HlsRange(uri, a, b - a + 1) else HlsRange(uri, 0, -1)
    }

    // ---- os períodos, os endereços e os tempos ----

    private fun periods(root: XmlNode, base: String, totalMs: Long?): List<Period> {
        val out = ArrayList<Period>()
        val nodes = root.all("Period").filter { it["href"] == null }
        var previousEnd: Long? = 0L
        for ((i, p) in nodes.withIndex()) {
            val start = p["start"]?.let { durationMs(it) } ?: previousEnd ?: break
            val duration = p["duration"]?.let { durationMs(it) }
            out += Period(p, start, duration, baseUrl(base, p))
            previousEnd = duration?.let { start + it } ?: nodes.getOrNull(i + 1)?.get("start")?.let { durationMs(it) }
        }
        if (out.isEmpty() && totalMs != null) return emptyList()
        return out
    }

    /** O endereço de [node] (o primeiro `BaseURL` dele, em relação a [parent]), ou o do pai. */
    private fun baseUrl(parent: String, node: XmlNode): String {
        val b = node.child("BaseURL")?.text?.trim()?.ifEmpty { null } ?: return parent
        return HlsPlaylist.resolve(parent, b)
    }

    /** Os `$Identificador$` de um modelo de endereço (com o formato `%05d`, se vier). */
    internal fun expand(template: String, id: String, number: Long, time: Long, bandwidth: Long): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '$') { out.append(c); i++; continue }
            val end = template.indexOf('$', i + 1)
            if (end < 0) { out.append(template, i, template.length); break }
            val token = template.substring(i + 1, end)
            if (token.isEmpty()) {
                out.append('$')
            } else {
                val name = token.substringBefore('%')
                val width = token.substringAfter('%', "").removeSuffix("d").removePrefix("0").toIntOrNull() ?: 0
                val value = when (name) {
                    "RepresentationID" -> id
                    "Number" -> number.toString().padStart(width, '0')
                    "Time" -> time.toString().padStart(width, '0')
                    "Bandwidth" -> bandwidth.toString().padStart(width, '0')
                    else -> null
                }
                if (value == null) out.append('$').append(token).append('$') else out.append(value)
            }
            i = end + 1
        }
        return out.toString()
    }

    /** Uma duração ISO 8601 (`PT1H2M3.5S`, `P1DT2H`) em ms, ou null. */
    internal fun durationMs(text: String): Long? {
        val m = DURATION.matchEntire(text.trim()) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        fun v(x: String) = x.toDoubleOrNull() ?: 0.0
        val seconds = v(y) * 365 * 86400 + v(mo) * 30 * 86400 + v(d) * 86400 + v(h) * 3600 + v(mi) * 60 + v(s)
        return (seconds * 1000).toLong()
    }

    private val DURATION = Regex("""-?P(?:([\d.]+)Y)?(?:([\d.]+)M)?(?:([\d.]+)D)?(?:T(?:([\d.]+)H)?(?:([\d.]+)M)?(?:([\d.]+)S)?)?""")

    /** Uma data ISO 8601 (`2024-01-01T00:00:00Z`; sem fuso conta como UTC) em ms desde 1970, ou null. */
    internal fun dateTime(text: String): Long? {
        val t = text.trim()
        val zoned = if (Regex("(Z|[+-]\\d\\d:?\\d\\d)$").containsMatchIn(t)) t else t + "Z"
        return runCatching { Instant.parse(zoned).toEpochMilliseconds() }.getOrNull()
    }

    private fun frameRate(text: String): Double {
        val a = text.substringBefore('/').toDoubleOrNull() ?: return 0.0
        val b = text.substringAfter('/', "1").toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        return a / b
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && (a < 0) != (b < 0)) q - 1 else q
    }

    private const val MAX_SEGMENTS = 20_000
}

/**
 * Onde está o índice (`sidx`) de um DASH com `SegmentBase`: os bytes a ler ([range]), o segmento de inicialização
 * ([map]), e o início do período e o presentationTimeOffset (ms) para os tempos (ver [DashManifest.withIndex]).
 */
class DashIndex(
    val range: HlsRange,
    val map: HlsRange?,
    val periodStartMs: Long,
    val ptoMs: Long,
    /** Num índice com outros índices: os segmentos já lidos, os índices que faltam e quantos já se leram. */
    val done: List<HlsSegment> = emptyList(),
    val queue: List<HlsRange> = emptyList(),
    val depth: Int = 0,
)

/** O `UTCTiming` de um MPD (ver [DashManifest.timing]): [kind] "direct" ou "http", e o valor. */
class DashTiming(val kind: String, val value: String)
