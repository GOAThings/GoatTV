@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import cc.slokker.itv.data.TimeshiftRing
import cc.slokker.itv.ui.player.engine.OpenRetry
import cc.slokker.itv.ui.player.engine.StreamGate
import cc.slokker.itv.ui.player.engine.ZapTrace
import cc.slokker.itv.ui.player.goatcore.UnsupportedStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A ligação ao fornecedor de um canal e o anel em disco onde ela escreve ([TimeshiftRing]). É a única ligação da
 * app enquanto existe: o player lê do anel (a pausa do direto, ver [Timeshift]) e as gravações copiam dele.
 *
 * Quem a usa segura-a ([hold]): o player e as gravações. Fecha (e o anel apaga-se) quando ninguém a segura. Há no
 * máximo uma de cada vez ([current]): abrir outra fecha a anterior, mesmo que uma gravação a segure (a gravação
 * acaba aí), e só liga depois de a anterior largar a ligação. Assim uma gravação continua com a app em segundo
 * plano, e o player, ao voltar ao mesmo canal, volta a ler deste anel sem abrir outra ligação ([find]).
 *
 * Cada tentativa de ligar é uma geração nova do [gate] (o da lista principal, partilhado pela TV, pelo telemóvel e
 * pelas gravações): uma abertura de outro player espera que esta feche, e esta espera pelas outras.
 */
internal class ChannelFeed private constructor(
    val ring: TimeshiftRing,
    /** O endereço do canal (é por ele que o player e as gravações sabem que é o mesmo). */
    val url: String,
    private val direct: DataSource.Factory,
    private val gate: StreamGate,
    private val reconnectMs: Long,
    val id: Int,
    previous: ChannelFeed?,
) {
    /** Quem segura a ligação. */
    enum class Holder { PLAYER, RECORDING }

    private val lock = Any()
    private val holders = HashMap<Holder, Int>()
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val uri = Uri.parse(url)

    /** Afinal não é TS: o anel não serve (o player liga-se ele próprio, depois de esta largar a ligação). */
    @Volatile var notTs = false
        private set

    /** A ligação caiu e volta a ligar (tentativa, quando — elapsedRealtime — e quanto espera). Na thread principal. */
    @Volatile var onReconnect: ((attempt: Int, nextTryAt: Long, waitMs: Long) -> Unit)? = null

    /** A ligação voltou a dar dados. Na thread principal. */
    @Volatile var onConnected: (() -> Unit)? = null

    private val recorder: Recorder = Recorder(previous?.recorder)

    /** Já não há ligação (fechou, ou foi trocada por outra). */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** A ligação ao servidor parou de vez (fechou, ou acabou sem voltar a ligar). */
    val done: CountDownLatch get() = recorder.done

    /** Uma ligação nova ao servidor, nesta geração do portão (para o player, quando afinal não é TS). */
    fun connect(): DataSource = gated().createDataSource()

    val uriForPlayer: Uri get() = uri

    /** Passa a segurar a ligação; false se já fechou. */
    fun hold(holder: Holder): Boolean = synchronized(lock) {
        if (closed) return false
        holders[holder] = (holders[holder] ?: 0) + 1
        true
    }

    /** Larga a ligação; sem mais ninguém a segurá-la, fecha. */
    fun release(holder: Holder) {
        val last = synchronized(lock) {
            val n = (holders[holder] ?: return) - 1
            if (n <= 0) holders.remove(holder) else holders[holder] = n
            holders.isEmpty()
        }
        if (last) close()
    }

    /** Alguém (além de [holder]) segura a ligação. */
    fun heldBy(holder: Holder): Boolean = synchronized(lock) { (holders[holder] ?: 0) > 0 }

    /** Fecha a ligação e apaga o anel (noutra thread), segure-a quem a segurar. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            holders.clear()
        }
        synchronized(CURRENT_LOCK) { if (current === this) current = null }
        onReconnect = null
        onConnected = null
        recorder.close()
        trash(ring, id)
    }

    private fun start() = recorder.start()

    /** Cada tentativa é uma geração nova do portão: a anterior (deste ou de outro player) fecha primeiro. */
    private fun gated(): DataSource.Factory {
        gate.next()
        return gate.factory(direct, exclusive = true)
    }

    /**
     * A ligação ao servidor: lê o stream e escreve-o no anel. Se cair depois de já ter dado, volta a ligar de
     * [reconnectMs] em [reconnectMs], num segmento novo do anel; o que já estava guardado fica.
     */
    private inner class Recorder(private val previous: Recorder?) : Thread("timeshift-${ring.hashCode()}") {
        @Volatile private var closed = false
        @Volatile private var source: DataSource? = null
        val done = CountDownLatch(1)

        override fun run() {
            try {
                // Uma ligação de cada vez: a do canal anterior fecha antes de abrir esta.
                if (previous?.done?.await(PREVIOUS_WAIT_MS, TimeUnit.MILLISECONDS) == false) Log.w(TAG, "A ligação anterior ainda não fechou")
                record()
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                if (!closed) Log.w(TAG, "Gravador parou", e)
            } finally {
                ring.endSegment(final = true)
                done.countDown()
            }
        }

        private fun record() {
            val buffer = ByteArray(BUFFER_BYTES)
            var failures = 0
            var everData = false
            var reconnects = 0
            while (!closed) {
                var error: IOException? = null
                val src = gated().createDataSource()
                source = src
                try {
                    Log.d(TAG, "A ligar (${ring.hashCode()})")
                    src.open(DataSpec(uri))
                    var gotData = false
                    while (!closed) {
                        val n = src.read(buffer, 0, buffer.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n <= 0) continue
                        if (!gotData) {
                            gotData = true
                            ring.beginSegment()
                            if (everData) main.post { onConnected?.invoke() }
                            everData = true
                            failures = 0
                            reconnects = 0
                        }
                        ring.write(buffer, 0, n)
                        // Não é TS (um MP4 num link sem extensão…): o anel não serve, o player liga-se ele próprio.
                        if (ring.isTs == false) {
                            notTs = true
                            return
                        }
                    }
                } catch (e: IOException) {
                    error = e
                } finally {
                    source = null
                    runCatching { src.close() }
                    Log.d(TAG, "Ligação fechada (${ring.hashCode()})" + (error?.let { ": $it" } ?: ""))
                }
                if (closed) return
                // Um formato que o motor não toca (HLS com o áudio à parte, cifra…): tentar outra vez não muda nada.
                if (error is UnsupportedStream) {
                    ring.endSegment(final = true, error = error)
                    return
                }
                if (!everData) {
                    // Ainda não deu: mais umas tentativas seguidas, depressa se o servidor recusou por ainda não ter
                    // largado a ligação do canal anterior (ver OpenRetry); depois o erro vai para o player, que o explica.
                    val wait = OpenRetry.delayMs(error, ++failures)
                    if (wait == null) {
                        ring.endSegment(final = true, error = error)
                        return
                    }
                    ZapTrace.mark("RETRY", "${OpenRetry.httpCode(error) ?: error} +${wait}ms")
                    sleep(wait)
                    continue
                }
                if (reconnectMs <= 0) {
                    ring.endSegment(final = true, error = error)
                    return
                }
                ring.endSegment(final = false)
                reconnects++
                val attempt = reconnects
                main.post { onReconnect?.invoke(attempt, SystemClock.elapsedRealtime() + reconnectMs, reconnectMs) }
                sleep(reconnectMs)
            }
        }

        fun close() {
            closed = true
            interrupt()
            // Uma leitura presa (servidor parado) só acabava no timeout: fecha-se a ligação por fora, para libertar
            // já a única que o fornecedor deixa.
            val src = source ?: return
            Thread {
                try {
                    if (!done.await(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) src.close()
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true }.start()
        }
    }

    internal companion object {
        private const val TAG = "Timeshift"

        /**
         * Com guia, o programa todo até 4 h; sem guia, 30 min. Nunca mais de 4 GB nem de metade do espaço livre
         * (o anel acerta o tamanho pelo débito, ver TimeshiftRing): um canal pesado guarda menos tempo.
         */
        const val MAX_AGE_MS = 4 * 60 * 60_000L
        private const val MAX_BYTES = 4_096L * 1024 * 1024

        /** O que fica sempre livre no disco, para o sistema e as outras apps (também para as gravações). */
        const val RESERVE_BYTES = 512L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val PREVIOUS_WAIT_MS = 3_000L
        private const val CLOSE_GRACE_MS = 800L
        private const val RING_PREFIX = "ring-"

        /** A pasta dos anéis (na cache da app). */
        fun dir(context: Context) = File(context.applicationContext.cacheDir, "timeshift")

        /** O número de cada anel, único no processo (o nome do ficheiro). */
        private val GENERATION = AtomicInteger()

        /** Os anéis deste processo ainda não apagados: o resto da pasta são restos (ver [capacity]). */
        private val LIVE: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        /** Apagar anéis e restos: uma thread só, fora da principal (a ordem fica a dos pedidos). */
        private val DISK = Executors.newSingleThreadExecutor { r -> Thread(r, "timeshift-disk").apply { isDaemon = true } }

        /** Bytes de anéis anteriores que ainda se estão a apagar (contam como livres para o anel novo). */
        private val PENDING_BYTES = AtomicLong()

        private val CURRENT_LOCK = Any()

        /** A ligação aberta agora (no máximo uma). */
        @Volatile
        var current: ChannelFeed? = null
            private set

        /** A ligação de [url], se ainda estiver a dar (para o player voltar a ela sem abrir outra). */
        fun find(url: String): ChannelFeed? =
            current?.takeIf { it.url == url && !it.isClosed && !it.notTs && !it.ring.isFinished }

        /** Fecha a ligação aberta (outro player vai ligar-se sem anel, ex.: HLS, VLC ou o catch-up do servidor). */
        fun closeCurrent() {
            current?.close()
        }

        /**
         * Abre [url] num anel novo, seguro por [holder]; fecha antes a ligação que houver. [maxBytes]: o tamanho
         * máximo do anel (uma gravação sem ninguém a ver só precisa de uns minutos). null se não houver espaço.
         */
        fun open(
            dir: File,
            url: String,
            direct: DataSource.Factory,
            gate: StreamGate,
            reconnectMs: Long,
            holder: Holder,
            maxBytes: Long = MAX_BYTES,
        ): ChannelFeed? {
            val previous = current
            previous?.close()
            val capacity = capacity(dir, maxBytes) ?: return null
            val generation = GENERATION.incrementAndGet()
            LIVE += generation
            val ring = try {
                TimeshiftRing(File(dir, "$RING_PREFIX$generation.ts"), capacity, MAX_AGE_MS, SystemClock::elapsedRealtime)
            } catch (e: IOException) {
                LIVE -= generation
                Log.w(TAG, "Sem anel", e)
                return null
            }
            val feed = ChannelFeed(ring, url, direct, gate, reconnectMs, generation, previous)
            feed.hold(holder)
            synchronized(CURRENT_LOCK) { current = feed }
            feed.start()
            return feed
        }

        /**
         * Espaço para um anel novo: até [maxBytes], sem passar de metade do livre (tirando uma reserva). O livre conta
         * já com os anéis anteriores que ainda se estão a apagar (ver [trash]).
         */
        private fun capacity(dir: File, maxBytes: Long): Long? {
            dir.mkdirs()
            // Restos de uma sessão que não fechou (a app morreu a meio): apagados fora da thread principal. Os anéis
            // abertos neste processo ([LIVE]) ficam; os fechados já se apagaram antes (a mesma thread, por ordem).
            DISK.execute {
                dir.listFiles()?.forEach { f ->
                    val gen = f.name.removePrefix(RING_PREFIX).removeSuffix(".ts").toIntOrNull()
                    if (gen == null || gen !in LIVE) f.delete()
                }
            }
            val free = dir.usableSpace + PENDING_BYTES.get()
            val bytes = minOf(maxBytes, (free - RESERVE_BYTES) / 2)
            if (bytes < TimeshiftRing.MIN_CAPACITY) {
                Log.i(TAG, "Pouco espaço livre (${free / 1_048_576} MB): sem pausa do direto")
                return null
            }
            return bytes
        }

        /**
         * Fecha e apaga [ring] noutra thread: apagar um ficheiro de vários GB (horas no mesmo canal) pode levar centenas
         * de ms, e era na thread principal, a atrasar o canal seguinte. Quem estiver a ler recebe o fim logo
         * ([TimeshiftRing.close] avisa antes de fechar os ficheiros).
         */
        private fun trash(ring: TimeshiftRing, generation: Int) {
            val bytes = minOf(ring.writtenBytes, ring.capacity)
            PENDING_BYTES.addAndGet(bytes)
            DISK.execute {
                val started = SystemClock.uptimeMillis()
                try {
                    ring.close()
                } finally {
                    LIVE -= generation
                    PENDING_BYTES.addAndGet(-bytes)
                }
                Log.d(TAG, "Anel apagado em ${SystemClock.uptimeMillis() - started} ms (${bytes / 1_048_576} MB)")
            }
        }
    }
}
