@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import cc.slokker.itv.playback.OpenRetry as Rules

/**
 * As tentativas de abrir uma stream que ainda não deu nada (o gravador da pausa do direto e o ExoPlayer, numa stream
 * .ts direta). As esperas são as do núcleo partilhado ([cc.slokker.itv.playback.OpenRetry], iguais na Apple); aqui
 * fica só o que é do Android: o código HTTP dentro das exceções do Media3 e a política do ExoPlayer.
 */
internal object OpenRetry {
    /** A espera antes da repetição [retry] (1 = a primeira) de uma abertura que falhou com [error]; null = desistir. */
    fun delayMs(error: Throwable?, retry: Int): Long? = Rules.delayMs(httpCode(error), retry)

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
    fun isBusy(error: Throwable?): Boolean = Rules.isBusy(httpCode(error))

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
            return maxOf(super.getMinimumLoadableRetryCount(dataType), Rules.BUSY.size)
        }
    }
}
