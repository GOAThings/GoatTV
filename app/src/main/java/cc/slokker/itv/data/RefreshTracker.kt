package cc.slokker.itv.data

/**
 * O que já se verificou desde que a app abriu e a última tentativa de cada lista e de cada guia, para "sempre que
 * abrir a app" e para não insistir com um fornecedor em baixo. Um só registo para os três (eram três conjuntos e um
 * mapa com prefixos "p:"/"g:"/"x:", que tinham de se limpar juntos).
 */
class RefreshTracker(private val now: () -> Long = System::currentTimeMillis) {

    /** O que se renova: os canais da lista, o guia XMLTV dela e o guia extra. */
    enum class Slot { PLAYLIST, GUIDE, EXTRA_GUIDE }

    private val checked = HashMap<Slot, HashSet<String>>()
    private val lastAttempt = HashMap<Pair<Slot, String>, Long>()

    fun isChecked(slot: Slot, id: String): Boolean = checked[slot]?.contains(id) == true

    /**
     * Está na hora de voltar a descarregar: o intervalo das definições ([hours]) já passou desde [fetchedAt] e, se já se
     * tentou desde que a app abriu, já passou [RefreshPolicy.RETRY_MS] desde essa tentativa.
     */
    fun isDue(slot: Slot, id: String, hours: Int, fetchedAt: Long): Boolean {
        val now = now()
        val seen = isChecked(slot, id)
        return RefreshPolicy.isDue(hours, fetchedAt, now, seen) &&
            (!seen || now - (lastAttempt[slot to id] ?: 0L) >= RefreshPolicy.RETRY_MS)
    }

    /** Tentou-se agora (correu bem ou não). */
    fun attempted(slot: Slot, id: String) {
        checked.getOrPut(slot) { HashSet() } += id
        lastAttempt[slot to id] = now()
    }

    /** Acabou de se descarregar (sem contar como tentativa: a seguinte não espera pelo intervalo entre tentativas). */
    fun markChecked(slot: Slot, id: String) {
        checked.getOrPut(slot) { HashSet() } += id
    }

    /** A app voltou ao primeiro plano: conta como abrir a app (as tentativas falhadas continuam a contar). */
    fun clearChecked() {
        checked.clear()
    }

    /** Outro perfil: esquece tudo. */
    fun clear() {
        checked.clear()
        lastAttempt.clear()
    }
}
