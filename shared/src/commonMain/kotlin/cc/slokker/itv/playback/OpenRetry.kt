package cc.slokker.itv.playback

/**
 * Quanto esperar para voltar a abrir uma stream que ainda não deu nada (o gravador da pausa do direto e o leitor),
 * pelo código HTTP com que o fornecedor a recusou. Igual no Android e na Apple.
 *
 * O caso mais comum ao mudar de canal: o fornecedor só deixa uma ligação e ainda não largou a do canal anterior,
 * e recusa a nova ("max connections": 403, 503, 509…). Liberta-a em centenas de ms, por isso tenta-se depressa e
 * várias vezes ([BUSY]). Um 404 é quase sempre "este formato não existe aqui": só mais uma tentativa rápida antes de
 * passar ao outro formato. O resto (rede) fica perto do que era, um pouco mais curto.
 */
object OpenRetry {
    /** Os códigos com que os painéis Xtream recusam uma ligação a mais (ver [isBusy]). */
    val BUSY_CODES: Set<Int> = setOf(403, 406, 458, 503, 509)

    /**
     * Ocupado: 4 tentativas em uns 3 s (o fornecedor ainda está a fechar a ligação anterior). Não mais amiúde: na
     * SHIELD, com um fornecedor real, 9 pedidos em 3 s levavam a conta a "demasiados pedidos" (429) no formato seguinte.
     */
    val BUSY: List<Long> = listOf(250, 500, 1_000, 1_500)

    /** "Demasiados pedidos" (429): o servidor pede calma; espera-se a sério antes de voltar a pedir. */
    val RATE_LIMITED: List<Long> = listOf(2_000, 4_000)

    /** Não existe (404/410): uma só tentativa, logo a seguir. */
    val NOT_FOUND: List<Long> = listOf(250)

    /** Outros erros (rede, 5xx): 0, 0,5 e 1 s. */
    val OTHER: List<Long> = listOf(0, 500, 1_000)

    /** A espera antes da repetição [retry] (1 = a primeira) depois do código [httpCode] (null = sem resposta HTTP); null = desistir. */
    fun delayMs(httpCode: Int?, retry: Int): Long? {
        val ladder = when (httpCode) {
            in BUSY_CODES -> BUSY
            429 -> RATE_LIMITED
            404, 410 -> NOT_FOUND
            else -> OTHER
        }
        return ladder.getOrNull(retry - 1)
    }

    /** O fornecedor recusou por estar ocupado (a ligação anterior ainda conta): o outro formato também seria recusado. */
    fun isBusy(httpCode: Int?): Boolean = httpCode in BUSY_CODES

    /** Uma recusa do fornecedor: ocupado ou "demasiados pedidos" (429). */
    fun isRefusal(httpCode: Int?): Boolean = isBusy(httpCode) || httpCode == 429
}
