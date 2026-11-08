@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.engine

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Uma ligação ao fornecedor de cada vez (o fornecedor só deixa uma). Ao mudar de canal, de formato ou ao voltar a
 * ligar, o ExoPlayer abria a stream nova 3–100 ms antes de a antiga acabar de fechar (o servidor via as duas).
 *
 * Cada tentativa de abrir é uma geração nova ([next]); as ligações dela passam por [factory] e, ao abrir, esperam
 * que fechem as das gerações anteriores. Se a antiga não fecha sozinha (leitura presa num servidor parado), é
 * fechada por fora ao fim de [graceMs]; ao fim de [maxWaitMs] abre-se na mesma (melhor do que um canal que nunca
 * abre). Uma abertura de uma geração ultrapassada é recusada: o canal anterior já não volta a ligar.
 *
 * Dentro da mesma geração o HLS pode ter dois pedidos ao mesmo tempo (a lista e um bocado, como sempre); uma
 * stream contínua (TS) não: um salto dentro dela também espera que a leitura anterior feche.
 * O VLC liga-se sem passar por aqui: antes de o abrir espera-se por [awaitIdle].
 */
internal class StreamGate(
    private val graceMs: Long = GRACE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Object()
    private var generation = 0
    private val open = mutableListOf<Gated>()

    /** Começa uma geração nova (uma tentativa de abrir o canal). */
    fun next(): Int = synchronized(lock) { ++generation }

    /** Ligações abertas agora (de qualquer geração). */
    val openCount: Int get() = synchronized(lock) { open.size }

    /** As ligações de [upstream], na geração atual; [exclusive]: uma de cada vez também dentro dela (TS). */
    fun factory(upstream: DataSource.Factory, exclusive: Boolean): DataSource.Factory {
        val gen = synchronized(lock) { generation }
        return DataSource.Factory { Gated(upstream.createDataSource(), gen, exclusive) }
    }

    /** Espera que não haja nenhuma ligação aberta (ver a classe). Bloqueia: nunca na thread principal. */
    fun awaitIdle() {
        waitFor(null) { true }
    }

    /**
     * Espera até não haver nenhuma ligação de [blocks]; fecha-as por fora depois de [graceMs]. [self] é a ligação
     * que vai abrir (para recusar se a geração dela já passou), ou null.
     */
    private fun waitFor(self: Gated?, blocks: (Gated) -> Boolean) {
        val started = clock()
        var forced = false
        while (true) {
            val blocking: List<Gated>
            synchronized(lock) {
                if (self != null && self.gen < generation) throw IOException("Ligação de um canal anterior (já não é precisa)")
                blocking = open.filter(blocks)
                if (blocking.isEmpty()) {
                    if (self != null) open += self
                    if (forced || clock() - started > 0) Log.d(TAG, "Abriu depois de esperar ${clock() - started} ms pela ligação anterior")
                    return
                }
            }
            val waited = clock() - started
            if (waited >= maxWaitMs) {
                Log.w(TAG, "A ligação anterior não fechou em $maxWaitMs ms: abre-se na mesma")
                synchronized(lock) { if (self != null) open += self }
                return
            }
            if (!forced && waited >= graceMs) {
                forced = true
                Log.w(TAG, "A ligação anterior não fechou em $graceMs ms: fecha-se por fora")
                blocking.forEach { it.forceClose() }
                continue
            }
            synchronized(lock) {
                // Volta a ver ao fechar uma ligação (notifyAll) ou no próximo prazo.
                val deadline = if (forced) maxWaitMs else graceMs
                if (open.any(blocks)) lock.wait((deadline - waited).coerceIn(1, POLL_MS))
            }
        }
    }

    private fun closed(c: Gated) {
        synchronized(lock) {
            open.remove(c)
            lock.notifyAll()
        }
    }

    /** Uma ligação de [gen]: abre depois das anteriores e avisa o portão ao fechar. */
    private inner class Gated(private val upstream: DataSource, val gen: Int, val exclusive: Boolean) : DataSource {
        /** Já se pediu ao [upstream] para fechar (aqui ou por fora): só se fecha uma vez. */
        private var closing = false

        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            waitFor(this) { other -> other !== this && (other.gen < gen || other.gen == gen && (exclusive || other.exclusive)) }
            synchronized(this) { closing = false }
            return try {
                upstream.open(dataSpec)
            } catch (e: Throwable) {
                // Não abriu: o DefaultHttpDataSource já largou a ligação; não fica a bloquear as seguintes.
                closed(this)
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            try {
                if (claimClose()) upstream.close()
            } finally {
                closed(this)
            }
        }

        /** Fecha a ligação de outra thread (a leitura dela estava presa): liberta já o lugar. */
        fun forceClose() {
            if (claimClose()) runCatching { upstream.close() }
            closed(this)
        }

        private fun claimClose(): Boolean = synchronized(this) {
            if (closing) false else true.also { closing = true }
        }
    }

    private companion object {
        const val TAG = "StreamGate"

        /** Como o gravador da pausa do direto (Timeshift): 0,8 s para fechar sozinha, 3 s no máximo. */
        const val GRACE_MS = 800L
        const val MAX_WAIT_MS = 3_000L
        const val POLL_MS = 50L
    }
}
