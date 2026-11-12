package cc.slokker.itv.data

import java.io.InputStream

/** Lê até à linha em branco. null: fechou antes disso, passou de [LanRequest.MAX_HEAD_BYTES] ou não se percebe. */
internal fun LanRequest.Companion.read(input: InputStream): LanRequest? {
    val head = StringBuilder()
    var emptyLine = true
    while (head.length < LanRequest.MAX_HEAD_BYTES) {
        val byte = input.read()
        if (byte < 0) return null
        val char = byte.toChar()
        head.append(char)
        if (char == '\n') {
            if (emptyLine) return parse(head.toString())
            emptyLine = true
        } else if (char != '\r') {
            emptyLine = false
        }
    }
    return null
}
