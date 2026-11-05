package cc.slokker.itv.data

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader

/**
 * Limites para o que vem dos servidores IPTV: passar deles é um pedido falhado (IOException), como um erro de rede,
 * nunca um crash. Folgados, porque há fornecedores com listas e guias enormes.
 */
object SafeInput {
    /** Respostas JSON (Xtream, Stalker), que ficam em memória para se lerem. */
    const val MAX_PLAYLIST_BYTES = 200L * 1024 * 1024
    /** Listas M3U: lêem-se em streaming e só ficam os canais, e as m3u_plus trazem filmes e séries às dezenas de milhar. */
    const val MAX_M3U_BYTES = 4L * 1024 * 1024 * 1024
    /** Guias XMLTV, já descomprimidos: lêem-se em streaming, por isso podem ser muito maiores. */
    const val MAX_GUIDE_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_M3U_LINE = 4 * 1024 * 1024
    /** Uma etiqueta, um CDATA ou um título de um guia. */
    const val MAX_XMLTV_TEXT = 1024 * 1024
    /** Ano 3000: uma data do fornecedor para lá disto (ou negativa) não é uma data. */
    private const val MAX_EPOCH_SECONDS = 32_503_680_000L

    /** Segundos do fornecedor em ms, ou null fora de um intervalo que faça sentido. */
    fun epochMs(seconds: Long): Long? = if (seconds in 0..MAX_EPOCH_SECONDS) seconds * 1000 else null

    /** User-Agent ou Referer de uma lista sem caracteres de controlo nem separadores de linha. */
    fun stripControl(value: String): String = value.filterNot { it.isISOControl() || it == ' ' || it == ' ' }

    /** O que o OkHttp aceita num cabeçalho (tudo o resto faz o pedido rebentar antes de sair). */
    fun isHeaderSafe(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

    /** [value] só com o que o OkHttp aceita num cabeçalho. */
    fun headerSafe(value: String): String = value.filter { it == '\t' || it in ' '..'~' }

    /** Sem memória para uma resposta: é um pedido falhado, e o que se ia guardar liberta-se ao sair daqui. */
    inline fun <T> outOfMemoryAsIo(block: () -> T): T = try {
        block()
    } catch (e: OutOfMemoryError) {
        throw IOException("Response too large for the memory available")
    }
}

/** Dá erro em vez de ler mais de [max] bytes. */
class LimitedInputStream(inner: InputStream, private val max: Long) : FilterInputStream(inner) {
    private var count = 0L

    private fun add(n: Int): Int {
        if (n > 0) {
            count += n
            if (count > max) throw IOException("Response over the size limit")
        }
        return n
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) add(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

/** Dá erro em vez de ler mais de [max] caracteres (o texto de um corpo já com a codificação certa). */
class LimitedReader(private val inner: Reader, private val max: Long) : Reader() {
    private var count = 0L

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = inner.read(cbuf, off, len)
        if (n > 0) {
            count += n
            if (count > max) throw IOException("Response over the size limit")
        }
        return n
    }

    override fun close() = inner.close()
}

/** As linhas de [this] (separadas por \n, \r ou \r\n, como o `useLines`), com erro numa linha maior do que [maxLine]. */
fun Reader.boundedLines(maxLine: Int = SafeInput.MAX_M3U_LINE): Sequence<String> = sequence {
    val buffer = CharArray(64 * 1024)
    val line = StringBuilder()
    var skipLf = false
    fun add(from: Int, to: Int) {
        line.append(buffer, from, to - from)
        if (line.length > maxLine) throw IOException("Line over the size limit")
    }
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        var start = 0
        for (i in 0 until n) {
            val c = buffer[i]
            if (skipLf) {
                skipLf = false
                if (c == '\n') {
                    start = i + 1
                    continue
                }
            }
            if (c == '\n' || c == '\r') {
                add(start, i)
                yield(line.toString())
                line.setLength(0)
                start = i + 1
                skipLf = c == '\r'
            }
        }
        add(start, n)
    }
    if (line.isNotEmpty()) yield(line.toString())
}
