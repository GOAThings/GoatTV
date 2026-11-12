package cc.slokker.itv.media

/**
 * O relógio de uma faixa do TS (90 kHz, 33 bits) num tempo seguido, que é o que os leitores querem:
 * - a cada ~26 h 30 min o relógio do TS volta a 0, e um direto pode estar a dar quando isso acontece;
 * - quando o fornecedor muda de encoder ou o stream recomeça, o relógio salta para outro valor qualquer.
 *
 * Num salto ([jumped]), o tempo continua logo a seguir ao último (mais [step]): para quem toca, o canal segue sem
 * parar. Para trás, mais de [BACK] é um salto (as imagens B andam para trás menos do que isso); para a frente, mais
 * de [JUMP].
 */
class TsClock {
    private var last = -1L
    private var lastOut = -1L
    private var offset = 0L

    /** O passo de uma unidade desta faixa (90 kHz), para continuar depois de um salto; aprende-se com os tempos. */
    var step = 3600L
        private set

    /** Houve um salto no último [unwrap]. */
    var jumped = false
        private set

    /** [ts] (33 bits) no tempo seguido; -1 fica -1. */
    fun unwrap(ts: Long): Long {
        jumped = false
        if (ts < 0) return -1
        if (last >= 0) {
            var delta = ts - last
            // Uma volta dos 33 bits não é um salto.
            if (delta < -WRAP / 2) delta += WRAP else if (delta > WRAP / 2) delta -= WRAP
            if (delta < -BACK || delta > JUMP) {
                jumped = true
                offset = lastOut + step - ts
            } else {
                if (delta in 1..MAX_STEP) step = delta
                offset += wrapAdjust(ts - last)
            }
        }
        last = ts
        val out = ts + offset
        if (out > lastOut) lastOut = out
        return out
    }

    /** O que somar ao offset quando o relógio do TS deu a volta entre [last] e o atual. */
    private fun wrapAdjust(raw: Long): Long = when {
        raw < -WRAP / 2 -> WRAP
        raw > WRAP / 2 -> -WRAP
        else -> 0
    }

    fun reset() {
        last = -1
        lastOut = -1
        offset = 0
        step = 3600
        jumped = false
    }

    companion object {
        const val WRAP = 1L shl 33

        /** Mais de 1 s para trás entre unidades seguidas não é uma imagem B: é um salto. */
        const val BACK = 90_000L

        /** Mais de 10 s para a frente também. */
        const val JUMP = 10L * 90_000

        /** Um passo maior do que isto (0,5 s) não é o de uma unidade. */
        private const val MAX_STEP = 45_000L
    }
}
