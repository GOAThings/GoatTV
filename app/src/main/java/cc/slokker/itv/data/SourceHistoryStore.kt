package cc.slokker.itv.data

import android.content.Context
import cc.slokker.itv.playback.SourceHistory

/**
 * Onde fica o [SourceHistory] (as quedas, as aberturas falhadas e o tempo a ver de cada fonte, nos últimos dias): só
 * neste aparelho, fora da conta. Usa-se na thread principal (o player e o ecrã).
 */
class SourceHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("source_history", Context.MODE_PRIVATE)

    val history: SourceHistory by lazy { SourceHistory.decode(prefs.getString(KEY, null), System.currentTimeMillis()) }

    /** Grava se [changed] (o que os `record…` do [SourceHistory] devolvem). */
    fun saveIf(changed: Boolean) {
        if (changed) prefs.edit().putString(KEY, history.encode(System.currentTimeMillis())).apply()
    }

    fun recordOpen(key: String, ok: Boolean) = saveIf(history.recordOpen(key, ok, System.currentTimeMillis()))

    fun recordDrop(key: String) = saveIf(history.recordDrop(key, System.currentTimeMillis()))

    fun recordWatched(key: String, seconds: Long) = saveIf(history.recordWatched(key, seconds, System.currentTimeMillis()))

    private companion object {
        const val KEY = "sources"
    }
}
