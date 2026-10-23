package cc.slokker.itv.data

import java.io.IOException

/** Falha ao carregar uma lista, com o tipo, para a app explicar a causa e como resolver. */
class SourceException(val kind: Kind, message: String, val httpCode: Int? = null, val status: String? = null) : IOException(message) {
    enum class Kind { NO_URL, BAD_URL, BAD_LOGIN, INACTIVE, HTTP, BAD_RESPONSE }
}
