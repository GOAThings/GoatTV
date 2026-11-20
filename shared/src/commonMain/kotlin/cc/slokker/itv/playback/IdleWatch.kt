package cc.slokker.itv.playback

import cc.slokker.itv.data.Prefs

/**
 * "Ainda estás a ver?" (definição `idle_stop`): conta o tempo desde o último comando (tecla, toque, o GoatRemote, a
 * sessão de media). Passado o limite escolhido, avisa ([PROMPT]) com uma contagem de 60 s; sem resposta, [STOPPED]:
 * o canal pára e a ligação ao fornecedor fica livre. Qualquer comando durante o aviso volta a [WATCHING] e recomeça a
 * contagem. Igual no Android e na Apple; quem a usa chama [tick] de segundo a segundo com o relógio (ms) e [onInput]
 * em cada comando. Sem tipos aninhados nem Int?, para chegar ao Swift sem surpresas.
 */
class IdleWatch {
    private var lastInput = 0L
    private var promptAt = -1L
    private var phase = WATCHING

    /** A fase em que está (WATCHING, PROMPT ou STOPPED). */
    fun phase(): Int = phase

    /** Um comando do utilizador: recomeça a contagem e fecha o aviso. Depois de parar, só [watchAgain] volta. */
    fun onInput(nowMs: Long) {
        if (phase == STOPPED) return
        lastInput = nowMs
        promptAt = -1L
        phase = WATCHING
    }

    /** "Ver outra vez" (ou outro canal): sai do ecrã de parado e recomeça a contagem. */
    fun watchAgain(nowMs: Long) {
        phase = WATCHING
        lastInput = nowMs
        promptAt = -1L
    }

    /**
     * Avança a máquina até [nowMs]. [limitMs] vem de [limitMs] (0 = desligado); [active]: há um canal a dar e a
     * contagem faz sentido (sem canal, ou com uma gravação a usar a ligação, fica parada e recomeça ao voltar).
     * Devolve a fase.
     */
    fun tick(nowMs: Long, limitMs: Long, active: Boolean): Int {
        if (phase == STOPPED) {
            if (limitMs <= 0L) phase = WATCHING // desligada nas definições: já não há ecrã de parado
            return phase
        }
        if (limitMs <= 0L || !active || lastInput == 0L) {
            // Sem contagem: a referência anda com o relógio, para o tempo parado não contar quando voltar.
            lastInput = nowMs
            promptAt = -1L
            phase = WATCHING
            return phase
        }
        // O relógio recuou (a hora mudou): recomeça em vez de ficar à espera.
        if (nowMs < lastInput) lastInput = nowMs
        if (phase == WATCHING && nowMs - lastInput >= limitMs) {
            phase = PROMPT
            promptAt = nowMs
        }
        if (phase == PROMPT && nowMs - promptAt >= COUNTDOWN_MS) phase = STOPPED
        return phase
    }

    /** Os segundos que faltam no aviso (60 → 1); 0 fora dele. */
    fun secondsLeft(nowMs: Long): Int {
        if (phase != PROMPT) return 0
        val left = COUNTDOWN_MS - (nowMs - promptAt)
        return ((left + 999) / 1000).toInt().coerceIn(1, (COUNTDOWN_MS / 1000).toInt())
    }

    companion object {
        const val WATCHING = 0
        const val PROMPT = 1
        const val STOPPED = 2

        /** A contagem do aviso. */
        const val COUNTDOWN_MS = 60_000L

        /** O limite de [Prefs.IdleStop] em ms; 0 = desligado (ou um valor que não se conhece). */
        fun limitMs(setting: String): Long = when (setting) {
            Prefs.IdleStop.H2 -> 2 * HOUR_MS
            Prefs.IdleStop.H4 -> 4 * HOUR_MS
            Prefs.IdleStop.H6 -> 6 * HOUR_MS
            else -> 0L
        }

        private const val HOUR_MS = 3_600_000L
    }
}
