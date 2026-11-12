package cc.slokker.itv.media

/**
 * O relógio do TS (90 kHz, 33 bits) num tempo que só cresce: a cada ~26 h 30 min volta a 0, e um direto pode estar a
 * dar quando isso acontece. Um salto grande (o fornecedor mudou de encoder, ou o stream recomeçou) não é uma volta:
 * [jumped] diz-se, e quem toca recomeça o relógio dele.
 */
class TsClock {
    private var last = -1L
    private var offset = 0L

    /** Houve um salto no último [unwrap] (nem volta nem tempo seguido). */
    var jumped = false
        private set

    /** [ts] (33 bits) no tempo contínuo; -1 fica -1. */
    fun unwrap(ts: Long): Long {
        jumped = false
        if (ts < 0) return -1
        if (last >= 0) {
            val delta = ts - last
            when {
                // Voltou a 0: o seguinte está logo a seguir ao fim.
                delta < -WRAP / 2 -> offset += WRAP
                // Recuou ao fim depois de uma volta (um B atrasado): pertence à volta anterior.
                delta > WRAP / 2 -> return ts + offset - WRAP
                delta > JUMP || delta < -JUMP -> jumped = true
            }
        }
        last = ts
        return ts + offset
    }

    fun reset() {
        last = -1
        offset = 0
        jumped = false
    }

    companion object {
        const val WRAP = 1L shl 33

        /** Mais de 10 s para a frente ou para trás entre unidades seguidas não é o vídeo a andar. */
        const val JUMP = 10L * 90_000
    }
}
