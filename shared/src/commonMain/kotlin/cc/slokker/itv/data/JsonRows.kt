package cc.slokker.itv.data

/**
 * Uma lista JSON de objetos lida aos poucos (as listas Xtream e Stalker têm dezenas de milhar de canais): de cada
 * objeto só ficam as [keys] pedidas, como texto (os servidores misturam `1` e `"1"`). Os valores dentro de outros
 * objetos ou listas saltam-se. Um elemento que não é um objeto conta como um objeto vazio (fica na posição, para os uid não mudarem).
 * [input] enche o buffer e devolve quantos caracteres pôs, ou -1 no fim.
 */
class JsonRows(private val input: (CharArray) -> Int, private val keys: Set<String>) {
    private val buf = CharArray(16 * 1024)
    private var len = 0
    private var pos = 0

    /** As linhas da lista; null quando o servidor não tem nada (`null`, `{}` ou `[]`). Outra coisa dá [BadJson]. */
    fun read(): List<Map<String, String>>? {
        if (peek() == 0xFEFF) pos++
        when (skipSpace()) {
            'n'.code -> {
                literal()
                return null
            }
            '{'.code -> {
                pos++
                if (skipSpace() != '}'.code) throw BadJson()
                return null
            }
            '['.code -> pos++
            else -> throw BadJson()
        }
        val rows = ArrayList<Map<String, String>>()
        while (true) {
            when (skipSpace()) {
                ']'.code -> break
                '{'.code -> rows += row()
                else -> {
                    skipValue()
                    rows += emptyMap()
                }
            }
            when (next()) {
                ','.code -> continue
                ']'.code -> break
                else -> throw BadJson()
            }
        }
        return rows.ifEmpty { null }
    }

    private fun row(): Map<String, String> {
        pos++
        val out = HashMap<String, String>()
        if (skipSpace() == '}'.code) {
            pos++
            skipSpace()
            return out
        }
        while (true) {
            if (skipSpace() != '"'.code) throw BadJson()
            val key = string(build = true)!!
            if (skipSpace() != ':'.code) throw BadJson()
            pos++
            val c = skipSpace()
            if (key in keys && c != '{'.code && c != '['.code) {
                val value = if (c == '"'.code) string(build = true) else literal().takeIf { it != "null" }
                if (value != null) out[key] = value
            } else {
                skipValue()
            }
            when (skipSpaceNext()) {
                ','.code -> continue
                '}'.code -> break
                else -> throw BadJson()
            }
        }
        skipSpace()
        return out
    }

    private fun skipValue() {
        when (skipSpace()) {
            '"'.code -> string(build = false)
            '{'.code, '['.code -> {
                var depth = 0
                while (true) {
                    when (peek()) {
                        -1 -> throw BadJson()
                        '"'.code -> string(build = false)
                        '{'.code, '['.code -> { depth++; pos++ }
                        '}'.code, ']'.code -> {
                            depth--
                            pos++
                            if (depth == 0) break
                        }
                        else -> pos++
                    }
                }
            }
            else -> literal()
        }
        skipSpace()
    }

    /** Um texto entre aspas (o cursor está nas aspas); com [build], o texto já sem os escapes. */
    private fun string(build: Boolean): String? {
        pos++
        val out = if (build) StringBuilder() else null
        while (true) {
            val c = next()
            when (c) {
                -1 -> throw BadJson()
                '"'.code -> return out?.toString()
                '\\'.code -> {
                    val e = next()
                    val char = when (e) {
                        '"'.code, '\\'.code, '/'.code -> e.toChar()
                        'b'.code -> '\b'
                        'f'.code -> '\u000C'
                        'n'.code -> '\n'
                        'r'.code -> '\r'
                        't'.code -> '\t'
                        'u'.code -> {
                            var v = 0
                            repeat(4) {
                                val h = next()
                                v = v * 16 + when (h) {
                                    in '0'.code..'9'.code -> h - '0'.code
                                    in 'a'.code..'f'.code -> h - 'a'.code + 10
                                    in 'A'.code..'F'.code -> h - 'A'.code + 10
                                    else -> throw BadJson()
                                }
                            }
                            v.toChar()
                        }
                        else -> throw BadJson()
                    }
                    out?.append(char)
                }
                else -> out?.append(c.toChar())
            }
        }
    }

    /** Um número, true, false ou null, tal como vem. */
    private fun literal(): String {
        val out = StringBuilder()
        while (true) {
            val c = peek()
            if (c == -1 || c == ','.code || c == '}'.code || c == ']'.code || isSpace(c)) break
            out.append(c.toChar())
            pos++
        }
        if (out.isEmpty()) throw BadJson()
        return out.toString()
    }

    private fun peek(): Int {
        if (pos == len) {
            len = input(buf)
            pos = 0
            if (len <= 0) {
                len = 0
                return -1
            }
        }
        return buf[pos].code
    }

    private fun next(): Int = peek().also { if (it >= 0) pos++ }

    private fun skipSpace(): Int {
        while (true) {
            val c = peek()
            if (!isSpace(c)) return c
            pos++
        }
    }

    private fun skipSpaceNext(): Int {
        skipSpace()
        return next()
    }

    private fun isSpace(c: Int) = c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code

    companion object {
        /** Lê de um texto que já está todo em memória. */
        fun of(text: String, keys: Set<String>): JsonRows {
            var at = 0
            return JsonRows({ buf ->
                if (at >= text.length) {
                    -1
                } else {
                    val n = minOf(buf.size, text.length - at)
                    for (i in 0 until n) buf[i] = text[at + i]
                    at += n
                    n
                }
            }, keys)
        }
    }
}

/** Uma resposta que não é JSON (ou não é o JSON esperado). */
class BadJson : IllegalArgumentException("Bad JSON")
