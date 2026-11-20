package cc.slokker.itv.media

/**
 * O relógio do anel do "pausar o direto" (TimeshiftRing no Android e no Apple): o tempo do vídeo (ms) do que já está
 * gravado, sempre a crescer, e o direto estimado para quem vê. O anel chama-o com o seu relógio (ms, só para a
 * frente); os ms que saem são os do índice.
 *
 * - Com PCR, conta o PCR (o tempo do emissor), não o tempo real: um servidor que manda às rajadas não conta duas vezes.
 * - Sem PCR, conta o tempo real em que os dados chegam.
 * - Numa descontinuidade (uma ligação nova, ou o PCR que salta para outro valor), continua logo a seguir ao último
 *   (mais um passo de PCR), como o TsClock do player: o tempo em que não chegou nada não foi gravado. Contá-lo fazia
 *   um fornecedor que fecha a ligação a cada segundo mostrar "30 min guardados" com uns segundos no anel, e recuar
 *   para onde não havia nada.
 */
class TimeshiftClock {
    /** Tempo do vídeo no direto (o último byte que chegou). */
    var mediaMs = 0L
        private set

    private var lastPcr = -1L
    private var lastTick = 0L
    private var lastPcrAt = 0L

    /** O tempo só pelo PCR: o tempo real contado sem PCR é provisório e [mediaMs] espera que o PCR o alcance. */
    private var pcrMedia = 0L

    /** O passo normal entre dois PCR (aprende-se com eles): o que se soma numa descontinuidade. */
    private var step = DEFAULT_STEP_MS

    /** Uma ligação nova ainda sem tempo contado: o primeiro avanço é só um passo. */
    private var joining = false

    // O direto estimado (ver [liveEdgeMs]): o maior avanço do vídeo sobre o relógio e quando foi.
    private var peakOffset = Long.MIN_VALUE
    private var peakAt = 0L

    /** Sem ligação desde aqui (o direto não anda enquanto não chega nada); [Long.MIN_VALUE]: ligado. */
    private var stoppedAt = Long.MIN_VALUE

    /** Começa uma ligação nova: o PCR dela não continua o da anterior. */
    fun beginSegment() {
        lastPcr = -1L
        joining = true
    }

    /** A ligação caiu ou acabou em [now]: até chegarem dados de novo, o direto fica onde está. */
    fun endSegment(now: Long) {
        if (stoppedAt == Long.MIN_VALUE) stoppedAt = now
    }

    /** Um PCR ([pcrMs], do TS) chegou em [now]. */
    fun onPcr(now: Long, pcrMs: Long) {
        resume(now)
        var d = -1L
        if (lastPcr >= 0) {
            d = pcrMs - lastPcr
            if (d < -PCR_WRAP_MS / 2) d += PCR_WRAP_MS
        }
        if (!joining && d in 0..MAX_PCR_STEP_MS) {
            // O tempo do PCR desde o anterior, e não somado ao tempo real que entretanto se contou sem PCR.
            if (d in 1..MAX_LEARN_STEP_MS) step = d
            pcrMedia += d
            mediaMs = maxOf(mediaMs, pcrMedia)
        } else {
            // O primeiro PCR desta ligação, ou um salto (canal trocado no servidor, relógio reposto).
            mediaMs += discontinuity(now)
            pcrMedia = mediaMs
        }
        joining = false
        lastPcr = pcrMs
        lastTick = now
        lastPcrAt = now
    }

    /**
     * Chegaram dados em [now] (depois de os PCR deles terem passado por [onPcr]); [ts]: pode ser TS (não se sabe que
     * não é). Devolve true quando o relógio andou pelo tempo real (sem PCR há algum tempo): o anel põe aí um ponto
     * no índice.
     */
    fun onData(now: Long, ts: Boolean): Boolean {
        resume(now)
        var counted = false
        if (ts && now - lastPcrAt > NO_PCR_MS) {
            if (lastTick != 0L) mediaMs += if (joining) discontinuity(now) else (now - lastTick).coerceAtLeast(0)
            joining = false
            lastTick = now
            counted = true
        }
        notePeak(now)
        return counted
    }

    /**
     * Onde vai o direto, para dizer quanto se está atrás dele: há servidores que mandam às rajadas (vários segundos de
     * vídeo de cada vez, e depois nada), e pelo último byte o "atrás do direto" andava aos saltos. Daqui sai o ponto
     * mais adiantado que as rajadas mostraram, a andar com o tempo real (e a esquecer-se devagar). Sem ligação não anda.
     * Nunca antes de [mediaMs].
     */
    fun liveEdgeMs(now: Long): Long {
        if (peakOffset == Long.MIN_VALUE) return mediaMs
        val at = if (stoppedAt != Long.MIN_VALUE) minOf(now, stoppedAt) else now
        return maxOf(mediaMs, at + decayedPeak(at))
    }

    /** Numa descontinuidade: um passo de PCR, nunca mais do que o tempo real que passou. */
    private fun discontinuity(now: Long): Long =
        if (lastTick == 0L) 0L else minOf(step, (now - lastTick).coerceAtLeast(0))

    /** Voltaram os dados: o tempo sem ligação não conta para o direto (nem para o esquecer). */
    private fun resume(now: Long) {
        val stopped = stoppedAt
        if (stopped == Long.MIN_VALUE) return
        stoppedAt = Long.MIN_VALUE
        val gap = (now - stopped).coerceAtLeast(0)
        if (peakOffset != Long.MIN_VALUE) {
            peakOffset -= gap
            peakAt += gap
        }
    }

    private fun decayedPeak(now: Long) = peakOffset - (now - peakAt) / PEAK_DECAY

    private fun notePeak(now: Long) {
        val offset = mediaMs - now
        if (peakOffset == Long.MIN_VALUE || offset >= decayedPeak(now)) {
            peakOffset = offset
            peakAt = now
        }
    }

    companion object {
        /** Sem PCR há mais do que isto: o relógio passa a ser o tempo real. */
        const val NO_PCR_MS = 1_500L

        /** Entre dois PCR seguidos nunca há mais do que isto (o normal é 40–100 ms). */
        const val MAX_PCR_STEP_MS = 5_000L

        /** Um passo maior do que isto não é o normal entre PCR (a norma pede até 100 ms). */
        private const val MAX_LEARN_STEP_MS = 500L
        private const val DEFAULT_STEP_MS = 40L

        /** O direto estimado ([liveEdgeMs]) esquece-se 1 ms por cada tantos ms (2 %). */
        const val PEAK_DECAY = 50L

        /** O PCR tem 33 bits a 90 kHz: dá a volta a cada ~26,5 h. */
        const val PCR_WRAP_MS = (1L shl 33) / 90
    }
}
