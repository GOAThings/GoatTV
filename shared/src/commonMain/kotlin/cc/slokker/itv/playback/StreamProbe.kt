package cc.slokker.itv.playback

import kotlin.math.roundToLong

/**
 * "Testar este canal": o que se mediu a ver o canal durante uns segundos (sem abrir uma segunda ligação: os
 * fornecedores só deixam uma) e a frase que diz de quem é o problema. Os números que faltam vão a -1 (a ponte para o
 * Swift não tem `Int?`); as strings vazias querem dizer "não se sabe".
 */
class ChannelProbeInput(
    /** Só o nome do servidor (ver [StreamProbe.host]); nunca o caminho, o utilizador ou a palavra-passe. */
    val host: String,
    /** O código HTTP da resposta, ou -1. */
    val httpStatus: Int,
    /** Redirecionamentos vistos (0 ou 1: a stream só diz se o endereço final é outro), ou -1. */
    val redirects: Int,
    val firstByteMs: Long,
    val firstPictureMs: Long,
    /** "TS", "HLS" ou "". */
    val container: String,
    /** "H.264", "HEVC", "MPEG-2"…, ou "". */
    val videoCodec: String,
    val width: Int,
    val height: Int,
    val fps: Float,
    val interlaced: Boolean,
    val hdr: Boolean,
    /** As faixas de som já escritas ("AAC 2.0, AC-3 5.1"), ou "". */
    val audio: String,
    /** O débito medido na janela, em kbit/s, ou -1. */
    val measuredKbps: Int,
    val windowMs: Long,
    /** Imagens deitadas fora por chegarem tarde e imagens mostradas na janela, ou -1 se o motor não conta. */
    val droppedFrames: Int,
    val renderedFrames: Int,
    /** Ligações que caíram e voltaram a abrir na janela. */
    val reconnects: Int,
    /** O codec que este aparelho não descodifica ("HEVC"), ou "". */
    val cannotDecode: String,
    /** Já mostrou alguma imagem (ou som, num rádio). */
    val showing: Boolean,
)

enum class ProbeVerdict { OK, SLOW, REFUSED, DROPS, CANNOT_DECODE, NO_PICTURE }

/** O veredicto e o número que vai na frase ([arg]: o código HTTP, os segundos ou o codec). */
class ProbeVerdictLine(val verdict: ProbeVerdict, val arg: String)

enum class ListVerdict { OK, SLOW, REFUSED, UNREACHABLE, EMPTY }

object StreamProbe {
    /** Quanto tempo o canal é observado. */
    const val WINDOW_MS = 10_000L

    /** Primeiro byte depois disto: o fornecedor demora (os canais abrem devagar). */
    const val SLOW_FIRST_BYTE_MS = 3_000L

    /** Imagens deitadas fora em mais de [DROP_PERCENT] % das mostradas, ou qualquer reconexão: a stream falha. */
    const val DROP_PERCENT = 5

    /** Uma lista ou guia que demora mais do que isto a responder. */
    const val SLOW_LIST_MS = 15_000L

    fun verdict(i: ChannelProbeInput): ProbeVerdictLine {
        if (i.httpStatus >= 400) return ProbeVerdictLine(ProbeVerdict.REFUSED, i.httpStatus.toString())
        if (i.cannotDecode.isNotEmpty()) return ProbeVerdictLine(ProbeVerdict.CANNOT_DECODE, i.cannotDecode)
        val total = i.droppedFrames + i.renderedFrames
        val dropping = i.droppedFrames > 0 && i.renderedFrames >= 0 && i.droppedFrames * 100L > total * DROP_PERCENT
        if (i.reconnects > 0 || dropping) return ProbeVerdictLine(ProbeVerdict.DROPS, "")
        if (i.firstByteMs > SLOW_FIRST_BYTE_MS) return ProbeVerdictLine(ProbeVerdict.SLOW, seconds(i.firstByteMs))
        if (!i.showing) return ProbeVerdictLine(ProbeVerdict.NO_PICTURE, "")
        return ProbeVerdictLine(ProbeVerdict.OK, "")
    }

    fun listVerdict(status: Int, ms: Long, count: Int): ListVerdict = when {
        status < 0 -> ListVerdict.UNREACHABLE
        status >= 400 -> ListVerdict.REFUSED
        count == 0 -> ListVerdict.EMPTY
        ms > SLOW_LIST_MS -> ListVerdict.SLOW
        else -> ListVerdict.OK
    }

    /** Uma casa decimal sem `String.format` (não existe no commonMain). */
    fun oneDecimal(v: Double): String {
        val t = (v * 10).roundToLong()
        val sign = if (t < 0) "-" else ""
        val a = if (t < 0) -t else t
        return "$sign${a / 10}.${a % 10}"
    }

    /** "1.2" (segundos) a partir de milissegundos. */
    fun seconds(ms: Long): String = oneDecimal(ms / 1000.0)

    /** "4.5 Mbit/s" ou "800 kbit/s"; "—" sem medição. */
    fun bitrate(kbps: Int): String = when {
        kbps < 0 -> "—"
        kbps >= 1000 -> oneDecimal(kbps / 1000.0) + " Mbit/s"
        else -> "$kbps kbit/s"
    }

    /** "512 KB", "4.5 MB"; "—" sem tamanho. */
    fun size(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes >= 1_000_000 -> oneDecimal(bytes / 1_000_000.0) + " MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    /** "H.264 · 1920×1080 · 25 fps · interlaced · HDR"; [interlacedWord] e [hdrWord] vêm traduzidos de quem chama. */
    fun format(i: ChannelProbeInput, interlacedWord: String, hdrWord: String): String {
        val parts = ArrayList<String>()
        if (i.container.isNotEmpty()) parts += i.container
        if (i.videoCodec.isNotEmpty()) parts += i.videoCodec
        if (i.width > 0 && i.height > 0) parts += "${i.width}×${i.height}" + if (i.interlaced) "i" else if (i.fps > 0) "p" else ""
        if (i.fps > 0) parts += "${i.fps.roundToLong()} fps"
        if (i.interlaced) parts += interlacedWord
        if (i.hdr) parts += hdrWord
        return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
    }

    /** O nome do servidor de [url] (sem utilizador, porta, caminho nem parâmetros), ou "" se não se percebe. */
    fun host(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) s = s.substring(0, end)
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        if (s.startsWith("[")) return s.substringBefore(']') + "]"
        return s.substringBefore(':')
    }

    /** Tira de uma mensagem de erro qualquer endereço (ficam só os servidores): "GET http://h/x?u=1 failed" → "GET h failed". */
    fun scrub(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val http = text.startsWith("http://", i) || text.startsWith("https://", i)
            if (!http) {
                out.append(text[i++])
                continue
            }
            var j = i
            while (j < text.length && !text[j].isWhitespace() && text[j] != '"' && text[j] != '\'' && text[j] != ')' && text[j] != '>') j++
            out.append(host(text.substring(i, j)))
            i = j
        }
        return out.toString()
    }
}

/**
 * Conta quantas vezes aparece [token] num texto que chega aos pedaços (sem o guardar): "#EXTINF" numa lista M3U,
 * `"stream_id"` na resposta Xtream, "<programme" num guia XMLTV. Apanha o que fica entre dois pedaços.
 */
class TokenCounter(private val token: String) {
    var count = 0
        private set
    private var tail = ""

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        val text = tail + chunk
        var from = 0
        while (true) {
            val at = text.indexOf(token, from)
            if (at < 0) break
            count++
            from = at + token.length
        }
        // O que sobra pode ser o princípio de um token: guarda-se (menos do que o token, para não o contar duas vezes).
        val keep = minOf(token.length - 1, text.length - from)
        tail = if (keep > 0) text.substring(text.length - keep) else ""
    }
}

/** A mesma lógica sem tipos que o Swift não vê bem: tudo em strings e números (ver as regras da ponte). */
class AppleStreamProbe {
    fun verdict(i: ChannelProbeInput): String = StreamProbe.verdict(i).verdict.name
    fun verdictArg(i: ChannelProbeInput): String = StreamProbe.verdict(i).arg
    fun listVerdict(status: Int, ms: Long, count: Int): String = StreamProbe.listVerdict(status, ms, count).name
    fun seconds(ms: Long): String = StreamProbe.seconds(ms)
    fun bitrate(kbps: Int): String = StreamProbe.bitrate(kbps)
    fun size(bytes: Long): String = StreamProbe.size(bytes)
    fun format(i: ChannelProbeInput, interlacedWord: String, hdrWord: String): String = StreamProbe.format(i, interlacedWord, hdrWord)
    fun host(url: String): String = StreamProbe.host(url)
    fun scrub(text: String): String = StreamProbe.scrub(text)
}
