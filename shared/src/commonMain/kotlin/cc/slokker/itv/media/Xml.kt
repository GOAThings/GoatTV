package cc.slokker.itv.media

/**
 * Um elemento de um XML pequeno (o manifesto DASH): o nome e os atributos sem o prefixo do namespace
 * (`cenc:default_KID` → `default_KID`), os filhos e o texto de dentro.
 */
class XmlNode(val name: String, val attrs: Map<String, String>, val children: List<XmlNode>, val text: String) {
    operator fun get(attr: String): String? = attrs[attr]
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlNode> = children.filter { it.name == name }
}

/** Um leitor de XML só para o que os manifestos têm: elementos, atributos, texto, comentários e CDATA. */
object Xml {
    private class Builder(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<XmlNode>()
        val text = StringBuilder()
        fun build() = XmlNode(name, attrs, children, text.toString().trim())
    }

    /** O elemento de topo, ou null se [s] não é XML. */
    fun parse(s: String): XmlNode? {
        val stack = ArrayList<Builder>()
        var root: XmlNode? = null
        var i = 0
        val n = s.length
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) break
            if (lt > i && stack.isNotEmpty()) stack.last().text.append(decode(s.substring(i, lt)))
            when {
                s.startsWith("<!--", lt) -> {
                    val end = s.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                }
                s.startsWith("<![CDATA[", lt) -> {
                    val end = s.indexOf("]]>", lt + 9)
                    val stop = if (end < 0) n else end
                    if (stack.isNotEmpty()) stack.last().text.append(s, lt + 9, stop)
                    i = if (end < 0) n else end + 3
                }
                s.startsWith("<?", lt) -> {
                    val end = s.indexOf("?>", lt + 2)
                    i = if (end < 0) n else end + 2
                }
                s.startsWith("<!", lt) -> {
                    val end = s.indexOf('>', lt + 2)
                    i = if (end < 0) n else end + 1
                }
                s.startsWith("</", lt) -> {
                    val end = s.indexOf('>', lt + 2)
                    i = if (end < 0) n else end + 1
                    val b = stack.removeLastOrNull() ?: continue
                    val node = b.build()
                    if (stack.isEmpty()) root = root ?: node else stack.last().children += node
                }
                else -> {
                    var j = lt + 1
                    while (j < n && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                    val name = local(s.substring(lt + 1, j))
                    val attrs = HashMap<String, String>()
                    var selfClosing = false
                    while (j < n) {
                        while (j < n && s[j].isWhitespace()) j++
                        if (j >= n) break
                        if (s[j] == '>') { j++; break }
                        if (s[j] == '/') { selfClosing = true; j++; continue }
                        val nameStart = j
                        while (j < n && s[j] != '=' && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                        val attr = s.substring(nameStart, j)
                        while (j < n && s[j].isWhitespace()) j++
                        if (j < n && s[j] == '=') {
                            j++
                            while (j < n && s[j].isWhitespace()) j++
                            if (j < n && (s[j] == '"' || s[j] == '\'')) {
                                val q = s[j]
                                val end = s.indexOf(q, j + 1).let { if (it < 0) n else it }
                                if (!attr.startsWith("xmlns")) attrs[local(attr)] = decode(s.substring(j + 1, end))
                                j = end + 1
                            }
                        }
                    }
                    i = j
                    if (name.isEmpty()) continue
                    val b = Builder(name, attrs)
                    if (selfClosing) {
                        val node = b.build()
                        if (stack.isEmpty()) root = root ?: node else stack.last().children += node
                    } else {
                        stack += b
                    }
                }
            }
        }
        // Um XML cortado: o que ficou aberto conta na mesma.
        while (stack.isNotEmpty()) {
            val node = stack.removeLast().build()
            if (stack.isEmpty()) root = root ?: node else stack.last().children += node
        }
        return root
    }

    private fun local(name: String): String = name.substringAfter(':')

    private fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi > i && semi - i <= 10) {
                    val entity = s.substring(i + 1, semi)
                    val ch: String? = when {
                        entity == "amp" -> "&"
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                        entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { codePoint(it) }
                        else -> null
                    }
                    if (ch != null) {
                        out.append(ch)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? {
        if (cp < 0 || cp > 0x10FFFF) return null
        if (cp < 0x10000) return cp.toChar().toString()
        val v = cp - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}
