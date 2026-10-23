package cc.slokker.itv.data.epg

import java.io.Reader

/**
 * Parser XMLTV em streaming (um carácter de cada vez): aguenta guias de dezenas de MB sem os carregar.
 * Só percebe o que o guia precisa: `<channel id>` com `<display-name>` e `<programme start stop channel>`
 * com `<title>`. Os resultados saem por callbacks, para quem chama decidir o que guardar.
 */
class XmltvParser(
    private val onChannel: (id: String, names: List<String>) -> Unit,
    /** Devolve false para não ler o título (programa que não interessa). */
    private val wantsProgramme: (channelId: String, startMs: Long, endMs: Long) -> Boolean,
    private val onProgramme: (channelId: String, startMs: Long, endMs: Long, title: String) -> Unit,
) {
    private var capture: StringBuilder? = null // texto do elemento atual (display-name ou title)

    fun parse(reader: Reader) {
        val r = if (reader is java.io.BufferedReader) reader else reader.buffered(64 * 1024)
        var channelId: String? = null
        val names = ArrayList<String>()
        var progChannel: String? = null
        var progStart = 0L
        var progEnd = 0L
        var title: String? = null
        capture = null

        while (true) {
            val c = r.read()
            if (c == -1) break
            if (c != '<'.code) {
                capture?.append(c.toChar())
                continue
            }
            val tag = readTag(r) ?: continue
            if (tag.closing) {
                when (tag.name) {
                    "display-name" -> capture?.let { names += decode(it.toString()).trim() }
                    "title" -> capture?.let { if (title == null) title = decode(it.toString()).trim() }
                    "channel" -> {
                        channelId?.let { onChannel(it, names.filter { n -> n.isNotEmpty() }) }
                        channelId = null
                        names.clear()
                    }
                    "programme" -> {
                        val ch = progChannel
                        val t = title
                        if (ch != null && !t.isNullOrEmpty()) onProgramme(ch, progStart, progEnd, t)
                        progChannel = null
                        title = null
                    }
                }
                if (tag.name == "display-name" || tag.name == "title") capture = null
                continue
            }
            when (tag.name) {
                "channel" -> {
                    channelId = if (tag.selfClosing) null else tag.attrs["id"]?.trim()
                    names.clear()
                }
                "display-name" -> if (channelId != null && !tag.selfClosing) capture = StringBuilder()
                "programme" -> {
                    val ch = tag.attrs["channel"]?.trim()
                    val start = tag.attrs["start"]?.let(::parseTime)
                    val stop = tag.attrs["stop"]?.let(::parseTime)
                    title = null
                    progChannel = if (!tag.selfClosing && ch != null && start != null && stop != null && stop > start &&
                        wantsProgramme(ch, start, stop)
                    ) ch else null
                    progStart = start ?: 0L
                    progEnd = stop ?: 0L
                }
                "title" -> if (progChannel != null && title == null && !tag.selfClosing) capture = StringBuilder()
            }
        }
    }

    private class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean, val selfClosing: Boolean)

    /** Lê uma etiqueta depois de '<'. Devolve null para comentários, `<?xml ?>`, `<!DOCTYPE>` e CDATA. */
    private fun readTag(r: Reader): Tag? {
        val sb = StringBuilder()
        var quote = 0
        while (true) {
            val c = r.read()
            if (c == -1) return null
            if (quote != 0) {
                if (c == quote) quote = 0
            } else if (c == '"'.code || c == '\''.code) {
                // Aspas só contam dentro de etiquetas normais (não em <!DOCTYPE …> nem <!-- … -->).
                if (sb.isNotEmpty() && sb[0] != '!') quote = c
            } else if (c == '>'.code) {
                break
            }
            sb.append(c.toChar())
            if (sb.length == 3 && sb.startsWith("!--")) {
                skipUntil(r, "-->", null)
                return null
            }
            if (sb.length == 8 && sb.startsWith("![CDATA[")) {
                // O texto do CDATA conta como texto do elemento, sem entidades.
                val text = StringBuilder()
                skipUntil(r, "]]>", text)
                capture?.append(text.toString().replace("&", "&amp;"))
                return null
            }
        }
        if (sb.isEmpty() || sb[0] == '?' || sb[0] == '!') return null
        val closing = sb[0] == '/'
        val selfClosing = sb[sb.length - 1] == '/'
        val body = sb.substring(if (closing) 1 else 0, if (selfClosing) sb.length - 1 else sb.length).trim()
        val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
        val name = body.substring(0, nameEnd)
        val attrs = if (nameEnd == body.length) emptyMap() else HashMap<String, String>().also { map ->
            ATTR.findAll(body, nameEnd).forEach { m -> map[m.groupValues[1]] = decode(m.groupValues[2].ifEmpty { m.groupValues[3] }) }
        }
        return Tag(name, attrs, closing, selfClosing)
    }

    /** Avança até [end]; o que ficar antes vai para [into], se houver. */
    private fun skipUntil(r: Reader, end: String, into: StringBuilder?) {
        val pending = StringBuilder()
        while (true) {
            val c = r.read()
            if (c == -1) return
            pending.append(c.toChar())
            if (pending.endsWith(end)) {
                into?.append(pending, 0, pending.length - end.length)
                return
            }
            if (pending.length > end.length) {
                into?.append(pending[0])
                pending.deleteCharAt(0)
            }
        }
    }

    companion object {
        private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""")

        fun decode(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault("")
            }
        }

        /**
         * "20261019120000 +0100" → epoch ms (sem fuso, assume-se UTC). Feito à mão porque um guia tem
         * centenas de milhares de datas e o SimpleDateFormat é lento demais para isso.
         */
        fun parseTime(s: String): Long? {
            val t = s.trim()
            if (t.length < 12) return null
            fun num(from: Int, len: Int): Int? {
                var v = 0
                for (i in from until from + len) {
                    val d = t.getOrNull(i)?.minus('0') ?: return null
                    if (d !in 0..9) return null
                    v = v * 10 + d
                }
                return v
            }
            val year = num(0, 4) ?: return null
            val month = num(4, 2)?.takeIf { it in 1..12 } ?: return null
            val day = num(6, 2)?.takeIf { it in 1..31 } ?: return null
            val hour = num(8, 2)?.takeIf { it in 0..23 } ?: return null
            val minute = num(10, 2)?.takeIf { it in 0..59 } ?: return null
            val second = if (t.length >= 14) num(12, 2) ?: 0 else 0

            var offsetMin = 0
            val zone = t.substring(minOf(14, t.length)).trim()
            if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
                val hh = zone.substring(1, 3).toIntOrNull() ?: return null
                val mm = zone.substring(3, 5).toIntOrNull() ?: return null
                offsetMin = (hh * 60 + mm) * if (zone[0] == '-') -1 else 1
            }
            val days = daysFromCivil(year, month, day)
            return ((days * 86400L + hour * 3600L + minute * 60L + second) - offsetMin * 60L) * 1000L
        }

        /** Dias desde 1970-01-01 (algoritmo de Howard Hinnant). */
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }
    }
}
