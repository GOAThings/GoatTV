package cc.slokker.itv.media

/**
 * As etapas do arranque de um canal no GoatCore, para o registo das versões de testes (tag GoatZap): a ligação, o
 * primeiro byte, as faixas, a primeira imagem-chave, o descodificador, a primeira imagem no ecrã, o primeiro som.
 * Cada etapa conta só a primeira vez, com o tempo desde a etapa anterior e desde o início ([startMs], o motor criado).
 * O mesmo no Android e na Apple; quem chama dá a hora (ms, sempre do mesmo relógio) e sincroniza, se precisar.
 */
class ZapStages(private val startMs: Long) {
    private var lastMs = startMs
    private val seen = HashSet<String>()

    /** A etapa [stage] chegou às [nowMs]: a linha a registar ("stage +Δ ms (total ms) detalhe"), ou null se já chegou. */
    fun mark(stage: String, nowMs: Long, detail: String = ""): String? {
        if (!seen.add(stage)) return null
        val line = "$stage +${nowMs - lastMs} ms (${nowMs - startMs} ms)" + if (detail.isEmpty()) "" else " $detail"
        lastMs = nowMs
        return line
    }

    /** A etapa [stage] já chegou. */
    fun reached(stage: String): Boolean = stage in seen
}
