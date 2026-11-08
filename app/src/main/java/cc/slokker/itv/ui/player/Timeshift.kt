@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.TimeshiftRing
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Pausar o direto": pausar, recuar e voltar ao direto em qualquer canal, mesmo sem catch-up no servidor.
 *
 * Streams .ts (o normal no Xtream): a única ligação ao servidor passa a ser a do [Recorder], que escreve tudo
 * num anel em disco ([TimeshiftRing], na cache da app); o ExoPlayer lê do anel ([RingDataSource]) e nunca abre
 * uma ligação sua — o fornecedor só deixa uma de cada vez. Em direto o player lê logo atrás da escrita; em pausa
 * a escrita continua. Recuar ou avançar é recomeçar o player noutro ponto do anel (o TS ao vivo não tem índice
 * para o ExoPlayer saltar sozinho); o índice do anel diz onde fica cada instante.
 *
 * HLS (.m3u8): cada bocado é um pedido à parte e a lista do servidor já guarda uns segundos ou minutos; usa-se
 * essa janela do próprio ExoPlayer (sem disco), quando tem pelo menos [HLS_MIN_WINDOW_MS].
 *
 * Com o VLC, em catch-up ou com a definição desligada, nada disto existe (o player abre o stream como sempre).
 * O estado ([available], [behindMs]…) é da thread principal, atualizado por [update].
 */
@Stable
internal class Timeshift(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "timeshift")
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var lastRecorder: Recorder? = null
    private var hls = false
    private var generation = 0
    private val window = Timeline.Window()

    /** Dá para pausar e recuar neste canal. */
    var available by mutableStateOf(false)
        private set

    /** Quanto o que se vê está atrás do direto (do direto normal: sem contar o buffer do player). */
    var behindMs by mutableLongStateOf(0L)
        private set

    /** Quanto se pode recuar ao todo (do mais antigo guardado até ao direto). */
    var windowMs by mutableLongStateOf(0L)
        private set

    /** A tocar no direto (não em pausa nem atrás). */
    var atLive by mutableStateOf(true)
        private set

    /** O player está a recomeçar noutro ponto do anel: a espera que se segue não é uma paragem da rede. */
    var seeking = false

    /**
     * A acompanhar o direto: o normal, até se pausar ou recuar; volta a sê-lo com [goLive] ou ao avançar até lá.
     * Em direto o que se vê fica sempre um pouco atrás do último byte gravado (o buffer do player, maior ou menor
     * conforme a rajada com que o servidor começou): esse atraso é o [baselineMs], o "zero" do direto.
     */
    private var following = true
    private var baselineMs = 0L

    /** A ligação caiu e o gravador volta a ligar (tentativa, quando — elapsedRealtime — e quanto espera). */
    var onReconnect: ((attempt: Int, nextTryAt: Long, waitMs: Long) -> Unit)? = null

    /** A ligação voltou a dar dados. */
    var onConnected: (() -> Unit)? = null

    /** Em pausa ou atrás do direto. */
    val shifted: Boolean get() = available && !atLive

    /** Com o anel: o tempo de vídeo já gravado (para a página Ligação medir o que chega do servidor); senão null. */
    val recordedMs: Long? get() = session?.takeIf { !it.notTs && it.ring.isTs == true }?.ring?.liveMediaMs

    /**
     * Abre [candidate] pelo anel e devolve a fonte para o ExoPlayer, ou null para abrir como sempre (HLS, pouco
     * espaço livre). [reconnectMs]: de quanto em quanto tempo o gravador volta a ligar se a ligação cair (0 = não).
     */
    fun open(candidate: StreamCandidate, upstream: DataSource.Factory, extractors: ExtractorsFactory, reconnectMs: Long): MediaSource? {
        clear()
        if (candidate.isHls) {
            hls = true
            return null
        }
        val capacity = capacity() ?: return null
        val ring = try {
            TimeshiftRing(File(dir, "ring-${++generation}.ts"), capacity, MAX_AGE_MS, SystemClock::elapsedRealtime)
        } catch (e: IOException) {
            Log.w(TAG, "Sem anel", e)
            return null
        }
        val s = Session(ring, upstream, Uri.parse(candidate.url), extractors, generation)
        val recorder = Recorder(s, reconnectMs, lastRecorder)
        s.recorder = recorder
        session = s
        lastRecorder = recorder
        recorder.start()
        return s.sourceAt(0)
    }

    /** Fecha a ligação e apaga o anel (outro canal, catch-up, app em segundo plano). */
    fun clear() {
        session?.close()
        session = null
        hls = false
        seeking = false
        following = true
        baselineMs = 0L
        available = false
        behindMs = 0L
        windowMs = 0L
        atLive = true
    }

    /** Espaço para o anel: até [MAX_BYTES], sem passar de metade do livre (tirando uma reserva). */
    private fun capacity(): Long? {
        dir.mkdirs()
        // Restos de uma sessão que não fechou (a app morreu a meio).
        dir.listFiles()?.forEach { it.delete() }
        val free = dir.usableSpace
        val bytes = minOf(MAX_BYTES, (free - RESERVE_BYTES) / 2)
        if (bytes < TimeshiftRing.MIN_CAPACITY) {
            Log.i(TAG, "Pouco espaço livre (${free / 1_048_576} MB): sem pausa do direto")
            return null
        }
        return bytes
    }

    /** Atualiza o estado; chamar de meio em meio segundo com o canal aberto. */
    fun update(player: Player) {
        val s = session
        if (s != null) {
            val ring = s.ring
            if (ring.isTs != true || s.notTs || player !is ExoPlayer) return unavailable()
            val live = ring.liveMediaMs
            val oldest = ring.oldest?.mediaMs ?: live
            val span = (live - oldest).coerceAtLeast(0)
            val behind = (live - (s.startMediaMs + player.currentPosition)).coerceIn(0, span)
            val settled = !seeking && player.playbackState == Player.STATE_READY
            when {
                !player.playWhenReady -> following = false
                following -> if (settled) baselineMs = behind
                // Avançou até ao direto (ou quase): volta a acompanhá-lo.
                settled && behind <= baselineMs + LIVE_SLACK_MS -> following = true
            }
            behindMs = if (following) 0L else (behind - baselineMs).coerceAtLeast(0)
            windowMs = (span - baselineMs).coerceAtLeast(0)
            atLive = following
            available = true
            return
        }
        if (hls && player is ExoPlayer) {
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val w = timeline.getWindow(player.currentMediaItemIndex, window)
                if (w.isLive && w.isSeekable && w.durationUs != C.TIME_UNSET) {
                    val edge = w.defaultPositionMs
                    windowMs = edge
                    behindMs = (edge - player.currentPosition).coerceIn(0, edge)
                    available = edge >= HLS_MIN_WINDOW_MS
                    atLive = player.playWhenReady && behindMs <= HLS_LIVE_SLACK_MS
                    return
                }
            }
        }
        unavailable()
    }

    private fun unavailable() {
        available = false
        behindMs = 0L
        windowMs = 0L
        atLive = true
    }

    /** Vai para [behind] ms atrás do direto (e continua a dar). */
    fun seekBehind(player: Player, behind: Long) {
        if (behind <= LIVE_SNAP_MS) return goLive(player)
        val s = session
        if (s != null) {
            s.ring.entryAtOrBefore(s.ring.liveMediaMs - baselineMs - behind)?.let { prepareAt(player, s, it) }
            return
        }
        if (hls) {
            seeking = true
            player.seekTo((windowMs - behind).coerceAtLeast(0))
            player.play()
        }
    }

    /** Volta ao direto. */
    fun goLive(player: Player) {
        val s = session
        if (s != null) {
            // Um pouco antes do fim, para o player arrancar logo (e apanhar um keyframe) com o que já está no disco.
            s.ring.entryAtOrBefore(s.ring.liveMediaMs - LIVE_START_MS)?.let { prepareAt(player, s, it, live = true) }
                ?: player.play()
            return
        }
        if (hls) {
            seeking = true
            player.seekToDefaultPosition()
            player.play()
        }
    }

    /** Continua depois da pausa; se o ponto da pausa já saiu do anel, continua do mais antigo que há. */
    fun resume(player: Player) {
        val s = session
        val oldest = s?.ring?.oldest
        if (s != null && oldest != null && s.startMediaMs + player.currentPosition < oldest.mediaMs) {
            s.ring.entryAtOrBefore(oldest.mediaMs + OVERRUN_MARGIN_MS)?.let {
                prepareAt(player, s, it)
                return
            }
        }
        player.play()
    }

    /**
     * O player chegou ao fim do que lia. Se foi o fim de uma ligação (havendo outra a seguir) ou o ponto já foi
     * reescrito, continua no sítio certo e devolve true; senão (a ligação acabou de vez) devolve false.
     */
    fun handleEnded(player: Player): Boolean {
        val s = session ?: return false
        if (s.notTs) return false
        val entry = when (s.end) {
            TimeshiftRing.BOUNDARY -> s.ring.entryFrom(s.endAt)
            TimeshiftRing.OVERRUN -> s.ring.oldest?.let { s.ring.entryAtOrBefore(it.mediaMs + OVERRUN_MARGIN_MS) }
            else -> null
        } ?: return false
        // No fim de uma ligação, quem acompanhava o direto continua a acompanhá-lo.
        prepareAt(player, s, entry, live = following && s.end == TimeshiftRing.BOUNDARY)
        return true
    }

    private fun prepareAt(player: Player, s: Session, entry: TimeshiftRing.Entry, live: Boolean = false) {
        val exo = player as? ExoPlayer ?: return
        s.end = 0
        s.startMediaMs = entry.mediaMs
        seeking = true
        following = live
        exo.setMediaSource(s.sourceAt(entry.offset))
        exo.prepare()
        exo.play()
        update(player)
    }

    /** Um canal aberto pelo anel. */
    private inner class Session(
        val ring: TimeshiftRing,
        val upstream: DataSource.Factory,
        val uri: Uri,
        private val extractors: ExtractorsFactory,
        private val id: Int,
    ) {
        lateinit var recorder: Recorder

        /** Tempo do vídeo (no anel) onde começou a fonte atual do player. */
        @Volatile var startMediaMs = 0L

        /** Porque acabou a última leitura (BOUNDARY, OVERRUN, END) e onde. */
        @Volatile var end = 0
        @Volatile var endAt = 0L

        /** Afinal não é TS: o player liga-se ao servidor ele próprio (depois de o gravador largar a ligação). */
        @Volatile var notTs = false

        fun sourceAt(offset: Long): MediaSource =
            ProgressiveMediaSource.Factory({ RingDataSource(this) }, extractors)
                .createMediaSource(MediaItem.fromUri(Uri.parse("timeshift://ring/$id?at=$offset")))

        fun close() {
            recorder.close()
            ring.close()
        }
    }

    /**
     * A ligação ao servidor: lê o stream e escreve-o no anel. Se cair depois de já ter dado, volta a ligar de
     * [reconnectMs] em [reconnectMs] (como o ecrã fazia), num segmento novo do anel; o que já estava guardado fica.
     */
    private inner class Recorder(private val s: Session, private val reconnectMs: Long, private val previous: Recorder?) : Thread("timeshift-${s.ring.hashCode()}") {
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
                s.ring.endSegment(final = true)
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
                val src = s.upstream.createDataSource()
                source = src
                try {
                    Log.d(TAG, "A ligar (${s.ring.hashCode()})")
                    src.open(DataSpec(s.uri))
                    var gotData = false
                    while (!closed) {
                        val n = src.read(buffer, 0, buffer.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n <= 0) continue
                        if (!gotData) {
                            gotData = true
                            s.ring.beginSegment()
                            if (everData) main.post { onConnected?.invoke() }
                            everData = true
                            failures = 0
                            reconnects = 0
                        }
                        s.ring.write(buffer, 0, n)
                        // Não é TS (um MP4 num link sem extensão…): o anel não serve, o player liga-se ele próprio.
                        if (s.ring.isTs == false) {
                            s.notTs = true
                            return
                        }
                    }
                } catch (e: IOException) {
                    error = e
                } finally {
                    source = null
                    runCatching { src.close() }
                    Log.d(TAG, "Ligação fechada (${s.ring.hashCode()})" + (error?.let { ": $it" } ?: ""))
                }
                if (closed) return
                if (!everData) {
                    // Ainda não deu: como o ExoPlayer, mais umas tentativas seguidas (o servidor pode ainda não ter
                    // largado a ligação do canal anterior); depois o erro vai para o player, que o explica.
                    if (++failures > OPEN_RETRIES) {
                        s.ring.endSegment(final = true, error = error)
                        return
                    }
                    sleep(minOf((failures - 1) * 1_000L, 5_000L))
                    continue
                }
                if (reconnectMs <= 0) {
                    s.ring.endSegment(final = true, error = error)
                    return
                }
                s.ring.endSegment(final = false)
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

    /** O que o ExoPlayer lê: o anel a partir de um ponto (`?at=`), ou o servidor diretamente se não for TS. */
    private class RingDataSource(private val s: Session) : BaseDataSource(false) {
        private var uri: Uri? = null
        private var pos = 0L
        private var segment = 0
        private var direct: DataSource? = null
        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            // Se é TS só se sabe com os primeiros KB.
            while (!s.notTs && s.ring.isTs == null && !s.ring.isFinished) {
                try {
                    Thread.sleep(KIND_POLL_MS)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException().apply { initCause(e) }
                }
            }
            if (s.notTs) {
                try {
                    s.recorder.done.await()
                } catch (e: InterruptedException) {
                    throw InterruptedIOException().apply { initCause(e) }
                }
                val d = s.upstream.createDataSource()
                direct = d
                val length = d.open(dataSpec.buildUpon().setUri(s.uri).build())
                opened = true
                transferStarted(dataSpec)
                return length
            }
            pos = (dataSpec.uri.getQueryParameter("at")?.toLongOrNull() ?: 0L) + dataSpec.position
            segment = s.ring.segmentAt(pos)
            opened = true
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            direct?.let { d ->
                val n = d.read(buffer, offset, length)
                if (n > 0) bytesTransferred(n)
                return n
            }
            while (true) {
                val n = s.ring.read(pos, segment, buffer, offset, length, WAIT_MS)
                when {
                    n > 0 -> {
                        pos += n
                        bytesTransferred(n)
                        return n
                    }
                    n == TimeshiftRing.WAIT -> if (Thread.interrupted()) throw InterruptedIOException()
                    else -> {
                        s.end = n
                        s.endAt = pos
                        return C.RESULT_END_OF_INPUT
                    }
                }
            }
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
            try {
                direct?.close()
            } finally {
                direct = null
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }

    private companion object {
        const val TAG = "Timeshift"

        /** Até 30 min ou 1,5 GB, o que vier primeiro (o anel acerta o tamanho pelo débito, ver TimeshiftRing). */
        const val MAX_AGE_MS = 30 * 60_000L
        const val MAX_BYTES = 1_536L * 1024 * 1024

        /** O que fica sempre livre no disco, para o sistema e as outras apps. */
        const val RESERVE_BYTES = 512L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024

        /** Para voltar ao direto, começa-se este tanto antes do fim. */
        const val LIVE_START_MS = 5_000L

        /** Pedir para ficar a menos disto do direto é voltar ao direto. */
        const val LIVE_SNAP_MS = 3_000L

        /** O ponto da pausa saiu do anel: continua-se este tanto depois do mais antigo (para não voltar a sair já). */
        const val OVERRUN_MARGIN_MS = 15_000L

        /** Atrás do direto, chegar a menos disto do atraso normal é estar outra vez no direto. */
        const val LIVE_SLACK_MS = 3_000L

        const val HLS_MIN_WINDOW_MS = 20_000L
        const val HLS_LIVE_SLACK_MS = 6_000L
        const val WAIT_MS = 250L
        const val KIND_POLL_MS = 20L
        const val PREVIOUS_WAIT_MS = 3_000L
        const val CLOSE_GRACE_MS = 800L

        /** Como o ExoPlayer (DefaultLoadErrorHandlingPolicy): mais 3 tentativas antes de dar erro. */
        const val OPEN_RETRIES = 3
    }
}
