package cc.slokker.itv.data.recording

import cc.slokker.itv.data.TimeshiftRing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Copia um pedaço do anel para o ficheiro da gravação: de [startOffset] até o vídeo chegar a [endMediaMs] (ver
 * [RecordingCut]), ou até se pedir para parar. Lê o anel como o player (é mais um leitor; a ligação é a do anel) e
 * vai atrás do direto. O fim é cortado num ponto do índice (o início de um pacote TS).
 *
 * Quando a ligação cai e volta, o anel começa um segmento novo: a gravação continua no início dele (conta um buraco
 * em [Result.gaps]). Se a escrita ficar tão para trás que o anel já reescreveu o ponto, continua no mais antigo.
 * Pára sozinha quando o livre no disco ([freeBytes]) chega à reserva ([RecordingSpace]).
 */
class RecordingWriter(
    private val ring: TimeshiftRing,
    private val out: File,
    private val startOffset: Long,
    endMediaMs: Long,
    private val freeBytes: () -> Long,
    private val onProgress: (bytes: Long, mediaMs: Long) -> Unit = { _, _ -> },
    /** De quantos em quantos bytes se vê o espaço e se avisa do progresso (também de 5 em 5 s). */
    private val checkBytes: Long = CHECK_BYTES,
) {
    /** Até onde gravar (pode mudar a meio: parar mais cedo ou mais tarde). */
    @Volatile var endMediaMs: Long = endMediaMs

    @Volatile private var stopRequested = false

    data class Result(val state: String, val reason: String?, val bytes: Long, val mediaEndMs: Long, val gaps: Int)

    /** Pára no ponto a que já chegou (o resultado diz [RecordingReason.USER]). */
    fun stop() {
        stopRequested = true
    }

    /** Grava (bloqueia: corre numa thread sua) e devolve como acabou. */
    fun run(): Result {
        val buffer = ByteArray(BUFFER_BYTES)
        var pos = startOffset
        var segment = ring.segmentAt(pos)
        var bytes = 0L
        var gaps = 0
        var endOffset: Long? = null
        var sinceCheck = 0L
        var checkedAt = System.nanoTime()
        var lastMedia = ring.mediaAt(pos) ?: 0L
        fun result(state: String, reason: String?) =
            Result(if (bytes == 0L && state != RecordingState.DONE) RecordingState.FAILED else state, reason, bytes, lastMedia, gaps)
        try {
            FileOutputStream(out).use { file ->
                while (true) {
                    if (stopRequested) return result(RecordingState.STOPPED, RecordingReason.USER)
                    // O vídeo já passou do fim: o corte é no ponto do índice nesse instante.
                    if (endOffset == null && ring.liveMediaMs >= endMediaMs) endOffset = ring.entryAtOrAfter(endMediaMs)?.offset
                    val limit = endOffset
                    if (limit != null && pos >= limit) {
                        lastMedia = ring.mediaAt(limit) ?: lastMedia
                        return result(RecordingState.DONE, null)
                    }
                    val max = if (limit != null) minOf(BUFFER_BYTES.toLong(), limit - pos).toInt() else BUFFER_BYTES
                    var n = try {
                        ring.read(pos, segment, buffer, 0, max, WAIT_MS)
                    } catch (e: IOException) {
                        // A ligação acabou com um erro (e não volta).
                        return result(RecordingState.STOPPED, RecordingReason.CONNECTION)
                    }
                    // O que se leu pode já passar do fim (a escrita chegou lá entre ver o direto e ler): corta-se no ponto.
                    if (n > 0) {
                        if (endOffset == null && ring.liveMediaMs >= endMediaMs) endOffset = ring.entryAtOrAfter(endMediaMs)?.offset
                        endOffset?.let { end -> if (pos + n > end) n = maxOf(0L, end - pos).toInt() }
                        if (n == 0) continue
                    }
                    when {
                        n > 0 -> {
                            file.write(buffer, 0, n)
                            pos += n
                            bytes += n
                            sinceCheck += n
                            if (sinceCheck >= checkBytes || System.nanoTime() - checkedAt >= CHECK_NANOS) {
                                sinceCheck = 0
                                checkedAt = System.nanoTime()
                                file.flush()
                                lastMedia = ring.mediaAt(pos) ?: lastMedia
                                onProgress(bytes, lastMedia)
                                if (RecordingSpace.mustStop(freeBytes())) return result(RecordingState.STOPPED, RecordingReason.LOW_SPACE)
                            }
                        }
                        n == TimeshiftRing.WAIT -> Unit
                        n == TimeshiftRing.BOUNDARY -> {
                            val next = ring.segmentStart(segment + 1)
                            if (next == null) {
                                val oldest = ring.oldest ?: return result(RecordingState.STOPPED, RecordingReason.CONNECTION)
                                pos = oldest.offset
                                segment = ring.segmentAt(pos)
                            } else {
                                segment++
                                pos = next
                            }
                            gaps++
                        }
                        n == TimeshiftRing.OVERRUN -> {
                            val oldest = ring.oldest ?: return result(RecordingState.STOPPED, RecordingReason.CONNECTION)
                            pos = oldest.offset
                            segment = ring.segmentAt(pos)
                            gaps++
                        }
                        // O anel fechou (a ligação foi para outro canal) ou acabou de vez.
                        else -> {
                            lastMedia = ring.mediaAt(pos) ?: lastMedia
                            val reached = endOffset != null && pos >= endOffset!!
                            return result(if (reached) RecordingState.DONE else RecordingState.STOPPED, if (reached) null else RecordingReason.CONNECTION)
                        }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("o ciclo só sai por return")
        } catch (e: IOException) {
            // Sem espaço (ENOSPC) ou o disco saiu: fica o que já se escreveu.
            return result(RecordingState.STOPPED, RecordingReason.LOW_SPACE)
        } finally {
            onProgress(bytes, lastMedia)
        }
    }

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val WAIT_MS = 250L

        /** De quantos em quantos bytes (ou segundos) se vê o espaço livre e se avisa do progresso. */
        const val CHECK_BYTES = 8L * 1024 * 1024
        private const val CHECK_NANOS = 5_000_000_000L
    }
}
