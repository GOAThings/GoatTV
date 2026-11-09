@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Quanto esperar para voltar a abrir uma stream que ainda não deu nada (o gravador da pausa do direto e o
 * ExoPlayer, numa stream .ts direta).
 *
 * O caso mais comum ao mudar de canal: o fornecedor só deixa uma ligação e ainda não largou a do canal anterior,
 * e recusa a nova ("max connections": 403, 503, 509…). Liberta-a em centenas de ms, por isso tenta-se depressa e
 * várias vezes ([BUSY]); antes eram 0, 1 e 2 s. Um 404 é quase sempre "este formato não existe aqui": só mais uma
 * tentativa rápida antes de passar ao outro formato. O resto (rede) fica perto do que era, um pouco mais curto.
 */
internal object OpenRetry {
    /** Os códigos com que os painéis Xtream recusam uma ligação a mais (ver [isBusy]). */
    val BUSY_CODES = setOf(403, 406, 458, 503, 509)

    /**
     * Ocupado: 4 tentativas em uns 3 s (o fornecedor ainda está a fechar a ligação anterior). Não mais amiúde: na
     * SHIELD, com um fornecedor real, 9 pedidos em 3 s levavam a conta a "demasiados pedidos" (429) no formato seguinte.
     */
    private val BUSY = longArrayOf(250, 500, 1_000, 1_500)

    /** "Demasiados pedidos" (429): o servidor pede calma; espera-se a sério antes de voltar a pedir. */
    private val RATE_LIMITED = longArrayOf(2_000, 4_000)

    /** Não existe (404/410): uma só tentativa, logo a seguir. */
    private val NOT_FOUND = longArrayOf(250)

    /** Outros erros (rede, 5xx): como o ExoPlayer, mas mais curto (era 0, 1 e 2 s). */
    private val OTHER = longArrayOf(0, 500, 1_000)

    /** A espera antes da repetição [retry] (1 = a primeira) de uma abertura que falhou com [error]; null = desistir. */
    fun delayMs(error: Throwable?, retry: Int): Long? {
        val ladder = when (httpCode(error)) {
            in BUSY_CODES -> BUSY
            429 -> RATE_LIMITED
            404, 410 -> NOT_FOUND
            else -> OTHER
        }
        return ladder.getOrNull(retry - 1)
    }

    /** O código HTTP da resposta que recusou a ligação ([error] ou uma das causas dele), ou null. */
    fun httpCode(error: Throwable?): Int? {
        var e = error
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is HttpDataSource.InvalidResponseCodeException) return e.responseCode
            e = e.cause
        }
        return null
    }

    /** O fornecedor recusou por estar ocupado (a ligação anterior ainda conta): o outro formato também seria recusado. */
    fun isBusy(error: Throwable?): Boolean = httpCode(error) in BUSY_CODES

    /**
     * As tentativas do ExoPlayer numa stream progressiva direta (.ts sem a pausa do direto): as mesmas esperas que o
     * gravador, só para respostas HTTP de erro. O resto (uma leitura que cai a meio…) fica como no Media3: as mesmas
     * esperas e o erro ao player na mesma tentativa (o mínimo de tentativas sobe só para caberem as de "ocupado").
     */
    class Policy : DefaultLoadErrorHandlingPolicy() {
        /** O tipo de dados da última pergunta do Media3 (antes de arrancar ou já em direto: mínimos diferentes). */
        @Volatile private var dataType = C.DATA_TYPE_MEDIA

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = loadErrorInfo.exception
            if (httpCode(e) != null) return delayMs(e, loadErrorInfo.errorCount) ?: C.TIME_UNSET
            if (loadErrorInfo.errorCount > super.getMinimumLoadableRetryCount(dataType)) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
            this.dataType = dataType
            return maxOf(super.getMinimumLoadableRetryCount(dataType), BUSY.size)
        }
    }
}
