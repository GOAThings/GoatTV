package cc.slokker.itv.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Gravar as definições sem desfazer o que mudou noutro sítio (site ou outra TV): as regras são as do núcleo
 * partilhado ([SettingsPatch], iguais na Apple); aqui só se passa do JsonObject do Gson para o da kotlinx e de volta.
 */
object SettingsMerge {
    private fun JsonObject.shared() = Json.parseToJsonElement(toString()).jsonObject
    private fun kotlinx.serialization.json.JsonObject.gson(): JsonObject = JsonParser.parseString(toString()).asJsonObject

    /** O patch que leva de [base] a [target] (ver [SettingsPatch.diff]). */
    fun diff(base: JsonObject, target: JsonObject): JsonObject = SettingsPatch.diff(base.shared(), target.shared()).gson()

    /** RFC 7396 (ver [SettingsPatch.apply]). */
    fun apply(base: JsonObject, patch: JsonObject): JsonObject = SettingsPatch.apply(base.shared(), patch.shared()).gson()

    fun hasTopLevelArray(patch: JsonObject): Boolean = patch.entrySet().any { it.value.isJsonArray }

    /** As alterações locais por cima do que o servidor tem agora (ver [SettingsPatch.rebase]). */
    fun rebase(server: JsonObject, base: JsonObject, local: JsonObject): JsonObject =
        SettingsPatch.rebase(server.shared(), base.shared(), local.shared()).gson()
}
