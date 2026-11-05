package cc.slokker.itv.data.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Um erro do servidor da conta (no estilo do PocketBase): o `code` e a frase para a pessoa (em inglês). */
data class ServerError(val code: String?, val message: String?)

/** Lê o corpo de um erro (`{status, code, message, params}`); null se não for JSON. O `code` pode vir dentro de `data`. */
fun parseServerError(body: String?): ServerError? {
    if (body.isNullOrBlank()) return null
    val json = runCatching { JsonParser.parseString(body) }.getOrNull() as? JsonObject ?: return null
    fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    val code = json.text("code") ?: (json.get("data") as? JsonObject)?.text("code")
    return ServerError(code, json.text("message"))
}

/** Os limites do guia da conta que o servidor explica numa frase: mais de 5 canais num só canal de guia, países novos a mais. */
val EPG_LIMITS = setOf("mapping_limit", "new_country_limit")

/**
 * O texto de um erro do guia da conta: nos limites que o servidor explica, a frase dele (ou [tooMany] num 429
 * sem frase); nos outros erros, [failed].
 */
fun epgErrorText(status: Int, error: ServerError?, tooMany: String, failed: String): String {
    if (error?.code !in EPG_LIMITS) return failed
    return error?.message ?: if (status == 429) tooMany else failed
}
