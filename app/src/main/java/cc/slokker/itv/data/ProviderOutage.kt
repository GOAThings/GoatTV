package cc.slokker.itv.data

/**
 * Listas em baixo. Um canal em direto que passou para a lista de reserva por um erro da conta ou do servidor (não só
 * daquele canal: ver [providerWide]) marca a lista principal como em baixo durante [WINDOW_MS]: os canais seguintes dela
 * vão logo à reserva, em vez de esperarem em cada um pelas tentativas de sempre. Um canal dela que dê apaga a marca.
 * As mesmas regras na Apple (GoatCore ProviderOutage.swift).
 */
class ProviderOutage(private val now: () -> Long) {
    private val until = HashMap<String, Long>()

    @Synchronized
    fun markDown(playlistId: String) {
        until[playlistId] = now() + WINDOW_MS
    }

    @Synchronized
    fun isDown(playlistId: String): Boolean {
        val end = until[playlistId] ?: return false
        if (now() < end) return true
        until.remove(playlistId)
        return false
    }

    @Synchronized
    fun clear(playlistId: String) {
        until.remove(playlistId)
    }

    companion object {
        const val WINDOW_MS = 5 * 60_000L

        /** Códigos da conta (401, 429, 456 "linha bloqueada", 458) e do servidor (5xx). 403 e 404 podem ser só do canal. */
        private val ACCOUNT_CODES = setOf(401, 429, 456, 458)

        /** O código HTTP [code] diz que é a conta ou o servidor que não responde, não só este canal. */
        fun providerWide(code: Int?): Boolean = code != null && (code in ACCOUNT_CODES || code in 500..599)
    }
}
