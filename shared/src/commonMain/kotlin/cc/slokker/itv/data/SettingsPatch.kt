package cc.slokker.itv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gravar as definições sem desfazer o que mudou noutro sítio (site ou outra TV): a app manda só o que mudou, como
 * JSON Merge Patch (RFC 7396) em `settings_merge` (ver PATCH /api/goattv/profile). Igual no Android e na Apple.
 */
object SettingsPatch {
    /**
     * O patch que leva de [base] a [target]: as chaves mudadas; nos objetos, só as sub-chaves mudadas (ex.: a ordem
     * dos grupos de uma só lista); as chaves apagadas vão a null. Arrays e valores vão inteiros.
     */
    fun diff(base: JsonObject, target: JsonObject): JsonObject {
        val patch = LinkedHashMap<String, JsonElement>()
        for ((key, value) in target) {
            val old = base[key]
            when {
                old == value -> Unit
                old is JsonObject && value is JsonObject -> diff(old, value).takeIf { it.isNotEmpty() }?.let { patch[key] = it }
                else -> patch[key] = value
            }
        }
        for (key in base.keys) if (key !in target) patch[key] = JsonNull
        return JsonObject(patch)
    }

    /** RFC 7396: os objetos juntam-se chave a chave, null apaga, o resto substitui. */
    fun apply(base: JsonObject, patch: JsonObject): JsonObject {
        val result = LinkedHashMap(base)
        for ((key, value) in patch) {
            val old = result[key]
            when (value) {
                is JsonNull -> result.remove(key)
                is JsonObject -> result[key] = apply(old as? JsonObject ?: JsonObject(emptyMap()), value)
                else -> result[key] = value
            }
        }
        return JsonObject(result)
    }

    /** O patch tem arrays no primeiro nível (ex.: hidden_groups): esses vão inteiros, por isso pede-se `settings_rev`. */
    fun hasTopLevelArray(patch: JsonObject): Boolean = patch.values.any { it is JsonArray }

    /**
     * As alterações locais ([local] em relação a [base]) postas por cima do que o servidor tem agora ([server]).
     * Os arrays de valores simples do primeiro nível são conjuntos de ids ("lista:grupo"): em vez de os substituir,
     * tiram-se e juntam-se só os ids que mudaram aqui, para não apagar os que o site juntou entretanto.
     */
    fun rebase(server: JsonObject, base: JsonObject, local: JsonObject): JsonObject {
        val patch = LinkedHashMap(diff(base, local))
        for ((key, value) in patch.toList()) {
            // Uma chave que ainda não existia aqui conta como conjunto vazio (ex.: o primeiro canal escondido).
            val old = base[key] ?: JsonArray(emptyList())
            if (value is JsonArray && old is JsonArray && value.isPrimitives() && old.isPrimitives()) {
                val added = value.filter { it !in old }
                val removed = old.filter { it !in value }.toSet()
                val merged = ArrayList<JsonElement>()
                (server[key] as? JsonArray)?.filter { it !in removed }?.forEach { merged += it }
                added.filter { it !in merged }.forEach { merged += it }
                patch[key] = JsonArray(merged)
            }
        }
        return apply(server, JsonObject(patch))
    }

    private fun JsonArray.isPrimitives() = all { it is JsonPrimitive }
}
