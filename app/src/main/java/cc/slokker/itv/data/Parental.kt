package cc.slokker.itv.data

/**
 * Tentativas erradas do PIN, para não dar para as ir experimentando todas: depois de umas quantas, cada PIN tem de
 * esperar um tempo que vai crescendo. Fica guardado neste aparelho, por isso conta entre pedidos do PIN e ao reabrir.
 */
class PinAttempts(
    private var failures: Int,
    private var lastFailureAt: Long,
    private val save: (failures: Int, lastFailureAt: Long) -> Unit,
) {
    /** Quanto falta para se poder tentar outra vez (0 = já). */
    fun waitMs(now: Long): Long {
        // O relógio andou para trás: a espera conta a partir de agora, senão podia nunca mais acabar.
        if (now < lastFailureAt) {
            lastFailureAt = now
            save(failures, lastFailureAt)
        }
        return (lastFailureAt + delayFor(failures) - now).coerceAtLeast(0)
    }

    /** A tentativa conta só se não estiver a esperar: aí, nem o PIN certo entra. Devolve se entrou. */
    fun check(now: Long, correct: Boolean): Boolean {
        if (waitMs(now) > 0) return false
        if (correct) {
            reset()
            return true
        }
        failures++
        lastFailureAt = now
        save(failures, lastFailureAt)
        return false
    }

    fun reset() {
        if (failures == 0) return
        failures = 0
        lastFailureAt = 0
        save(failures, lastFailureAt)
    }

    companion object {
        private const val FREE_ATTEMPTS = 3
        private const val FIRST_DELAY_MS = 30_000L
        private const val MAX_DELAY_MS = 3_600_000L

        /** Sem espera nas primeiras; depois 30 s, 1 min, 2 min… até 1 hora. */
        fun delayFor(failures: Int): Long =
            if (failures < FREE_ATTEMPTS) 0 else minOf(FIRST_DELAY_MS shl minOf(failures - FREE_ATTEMPTS, 10), MAX_DELAY_MS)
    }
}

/** O canal a [delta] passos do [index] em [list] (dá a volta), saltando os que estão [locked] neste momento. */
fun nextAllowed(list: List<Channel>, index: Int, delta: Int, locked: (Channel) -> Boolean): Channel? =
    (1 until list.size).asSequence().map { list[(index + delta * it).mod(list.size)] }.firstOrNull { !locked(it) }
