package cc.slokker.itv.data

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Gravar as definições sem desfazer o que mudou noutro sítio (site ou outra TV): a app manda só o que
 * mudou, como JSON Merge Patch (RFC 7396) em `settings_merge` (ver PATCH /api/goattv/profile).
 */
object SettingsMerge {

    /**
     * O patch que leva de [base] a [target]: as chaves mudadas; nos objetos, só as sub-chaves mudadas
     * (ex.: a ordem dos grupos de uma só lista); as chaves apagadas vão a null. Arrays e valores vão inteiros.
     */
    fun diff(base: JsonObject, target: JsonObject): JsonObject {
        val patch = JsonObject()
        for ((key, value) in target.entrySet()) {
            val old = base.get(key)
            when {
                old == value -> Unit
                old is JsonObject && value is JsonObject -> diff(old, value).takeIf { it.size() > 0 }?.let { patch.add(key, it) }
                else -> patch.add(key, value.deepCopy())
            }
        }
        for (key in base.keySet()) if (!target.has(key)) patch.add(key, JsonNull.INSTANCE)
        return patch
    }

    /** RFC 7396: os objetos juntam-se chave a chave, null apaga, o resto substitui. */
    fun apply(base: JsonObject, patch: JsonObject): JsonObject {
        val result = base.deepCopy()
        for ((key, value) in patch.entrySet()) {
            val old = result.get(key)
            when {
                value.isJsonNull -> result.remove(key)
                value is JsonObject -> result.add(key, apply(old as? JsonObject ?: JsonObject(), value))
                else -> result.add(key, value.deepCopy())
            }
        }
        return result
    }

    /** O patch tem arrays no primeiro nível (ex.: hidden_groups): esses vão inteiros, por isso pede-se `settings_rev`. */
    fun hasTopLevelArray(patch: JsonObject): Boolean = patch.entrySet().any { it.value.isJsonArray }

    /**
     * As alterações locais ([local] em relação a [base]) postas por cima do que o servidor tem agora ([server]).
     * Os arrays de valores simples do primeiro nível são conjuntos de ids ("lista:grupo"): em vez de os substituir,
     * tiram-se e juntam-se só os ids que mudaram aqui, para não apagar os que o site juntou entretanto.
     */
    fun rebase(server: JsonObject, base: JsonObject, local: JsonObject): JsonObject {
        val patch = diff(base, local)
        for ((key, value) in patch.entrySet().toList()) {
            // Uma chave que ainda não existia aqui conta como conjunto vazio (ex.: o primeiro canal escondido).
            val old = base.get(key) ?: JsonArray()
            if (value is JsonArray && old is JsonArray && value.isPrimitives() && old.isPrimitives()) {
                val added = value.filter { it !in old }
                val removed = old.filter { it !in value }.toSet()
                val merged = JsonArray()
                (server.get(key) as? JsonArray)?.filter { it !in removed }?.forEach { merged.add(it) }
                added.filter { it !in merged }.forEach { merged.add(it) }
                patch.add(key, merged)
            }
        }
        return apply(server, patch)
    }

    private fun JsonArray.isPrimitives() = all { it.isJsonPrimitive }
}
