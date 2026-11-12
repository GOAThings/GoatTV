package cc.slokker.itv.data

/** As tentativas erradas do PIN: quanto esperar depois de [failures] falhas. Igual no Android e na Apple. */
object PinBackoff {
    const val FREE_ATTEMPTS = 3
    const val FIRST_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 3_600_000L

    /** Sem espera nas primeiras; depois 30 s, 1 min, 2 min… até 1 hora. */
    fun delayFor(failures: Int): Long =
        if (failures < FREE_ATTEMPTS) 0 else minOf(FIRST_DELAY_MS shl minOf(failures - FREE_ATTEMPTS, 10), MAX_DELAY_MS)
}

/**
 * Listas em baixo (ver ProviderOutage em cada plataforma): quanto tempo uma lista fica marcada e que respostas a
 * marcam. Um erro da conta ou do servidor, não só daquele canal.
 */
object OutageRules {
    const val WINDOW_MS = 5 * 60_000L

    /** Códigos da conta (401, 429, 456 "linha bloqueada", 458) e do servidor (5xx). 403 e 404 podem ser só do canal. */
    private val ACCOUNT_CODES = setOf(401, 429, 456, 458)

    /** O código HTTP [code] diz que é a conta ou o servidor que não responde, não só este canal. */
    fun providerWide(code: Int?): Boolean = code != null && (code in ACCOUNT_CODES || code in 500..599)
}

/** As versões da app (o aviso de versão nova): comparar e tirar as novidades das notas. */
object Versions {
    /** [remote] é mais nova do que [current] ("v" à frente não conta; partes que faltam valem 0). */
    fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * As novidades das notas da versão (as do GitHub, em Markdown): os primeiros pontos de lista, sem negrito. Notas
     * sem lista dão as primeiras linhas que não são títulos ("Android TV:").
     */
    fun highlights(notes: String, max: Int): List<String> {
        val lines = notes.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val bullets = lines.filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2) }
        return (bullets.ifEmpty { lines.filterNot { it.endsWith(":") || it.startsWith("#") } })
            .map { it.replace("**", "").trim() }
            .filter { it.isNotEmpty() }
            .take(max)
    }
}
