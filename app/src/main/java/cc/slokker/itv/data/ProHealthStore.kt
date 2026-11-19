package cc.slokker.itv.data

import android.content.Context

/** Onde fica o ProHealth (as fontes do Modo TV Pro que falharam): só nesta TV, fora da conta. */
class ProHealthStore(context: Context) {
    private val prefs = context.getSharedPreferences("pro_health", Context.MODE_PRIVATE)

    val health: ProHealth by lazy { ProHealth.decode(prefs.getString(KEY, null), System.currentTimeMillis()) }

    fun save() {
        prefs.edit().putString(KEY, health.encode(System.currentTimeMillis())).apply()
    }

    private companion object {
        const val KEY = "failed"
    }
}
