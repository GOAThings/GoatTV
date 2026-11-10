@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.TimeshiftRing
import cc.slokker.itv.ui.player.engine.StreamGate
import java.io.InterruptedIOException

/**
 * "Pausar o direto": pausar, recuar e voltar ao direto em qualquer canal, mesmo sem catch-up no servidor.
 *
 * Streams .ts (o normal no Xtream): a única ligação ao servidor passa a ser a do [ChannelFeed], que escreve tudo
 * num anel em disco ([TimeshiftRing], na cache da app); o ExoPlayer lê do anel ([RingDataSource]) e nunca abre
 * uma ligação sua — o fornecedor só deixa uma de cada vez. Uma gravação pode segurar a mesma ligação: ao sair, o
 * player larga-a mas ela continua, e ao voltar ao canal o player volta a ler do mesmo anel. Em direto o player lê logo atrás da escrita; em pausa
 * a escrita continua. Recuar ou avançar é recomeçar o player noutro ponto do anel (o TS ao vivo não tem índice
 * para o ExoPlayer saltar sozinho); o índice do anel diz onde fica cada instante.
 *
 * Guarda-se só o programa que se está a ver (pelo guia): ao passar para o seguinte, o anterior sai do anel; ao
 * mudar de canal ou sair, o anel é apagado (a não ser que uma gravação ainda o use). Sem guia, os últimos [NO_EPG_AGE_MS].
 *
 * HLS (.m3u8): cada bocado é um pedido à parte e a lista do servidor já guarda uns segundos ou minutos; usa-se
 * essa janela do próprio ExoPlayer (sem disco), quando tem pelo menos [HLS_MIN_WINDOW_MS].
 *
 * Com o VLC, em catch-up ou com a definição desligada, nada disto existe (o player abre o stream como sempre).
 * O estado ([available], [behindMs]…) é da thread principal, atualizado por [update].
 */
@Stable
internal class Timeshift(context: Context) {
    private val dir = ChannelFeed.dir(context)
    private var session: Session? = null
    private var hls = false
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

    /** Em pausa ou atrás do direto há uns segundos: mudar de canal perde o que está guardado. */
    val worthAsking: Boolean get() = shifted && behindMs >= ASK_BEHIND_MS

    /** O player está a recomeçar noutro ponto do anel: a espera que se segue não é uma paragem da rede. */
    var seeking = false

    /**
     * A acompanhar o direto: o normal, até se pausar ou recuar; volta a sê-lo com [goLive] ou ao avançar até lá.
     * Em direto o que se vê fica sempre um pouco atrás do último byte gravado (o buffer do player, maior ou menor
     * conforme a rajada com que o servidor começou): esse atraso é o [baselineMs], o "zero" do direto.
     */
    private var following = true
    private var baselineMs = 0L
    private var lastProgrammeStart: (Long) -> Long? = { null }

    /** A ligação caiu e o gravador volta a ligar (tentativa, quando — elapsedRealtime — e quanto espera). */
    var onReconnect: ((attempt: Int, nextTryAt: Long, waitMs: Long) -> Unit)? = null

    /** A ligação voltou a dar dados. */
    var onConnected: (() -> Unit)? = null

    /** Em pausa ou atrás do direto. */
    val shifted: Boolean get() = available && !atLive

    /** Com o anel: o tempo de vídeo já gravado (para a página Ligação medir o que chega do servidor); senão null. */
    val recordedMs: Long? get() = session?.takeIf { !it.notTs && it.ring.isTs == true }?.ring?.liveMediaMs

    /** A ligação (e o anel) do canal a dar, quando é pelo anel e é mesmo TS (para gravar). */
    val feed: ChannelFeed? get() = session?.takeIf { !it.notTs && it.ring.isTs == true }?.feed

    /**
     * Abre [candidate] pelo anel e devolve a fonte para o ExoPlayer, ou null para abrir como sempre (HLS, pouco
     * espaço livre). [direct]: a ligação HTTP ao servidor, que passa pelo [gate]. [reconnectMs]: de quanto em quanto
     * tempo a ligação volta a ligar se cair (0 = não). Se uma gravação ainda tem este canal aberto, o player volta a
     * esse anel (perto do direto), sem abrir outra ligação.
     */
    fun open(candidate: StreamCandidate, direct: DataSource.Factory, gate: StreamGate, extractors: ExtractorsFactory, reconnectMs: Long): MediaSource? {
        clear()
        if (candidate.isHls) {
            hls = true
            return null
        }
        val reused = ChannelFeed.find(candidate.url)?.takeIf { it.hold(ChannelFeed.Holder.PLAYER) }
        val feed = reused ?: ChannelFeed.open(dir, candidate.url, direct, gate, reconnectMs, ChannelFeed.Holder.PLAYER) ?: return null
        feed.onReconnect = { n, at, waitMs -> onReconnect?.invoke(n, at, waitMs) }
        feed.onConnected = { onConnected?.invoke() }
        val s = Session(feed, extractors)
        session = s
        if (reused != null) {
            val ring = reused.ring
            ring.entryAtOrBefore(ring.liveMediaMs - LIVE_START_MS)?.let { entry ->
                s.startMediaMs = entry.mediaMs
                return s.sourceAt(entry.offset)
            }
        }
        return s.sourceAt(0)
    }

    /**
     * O player larga a ligação (outro canal, catch-up, app em segundo plano): fecha-a e apaga o anel, a não ser que
     * uma gravação ainda a use.
     */
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

    /**
     * O player vai ligar-se ao servidor sem o anel (HLS, VLC, catch-up do servidor): larga o anel e fecha também a
     * ligação que uma gravação ainda tenha aberta (uma ligação de cada vez; a gravação acaba aí).
     */
    fun yieldConnection() {
        clear()
        ChannelFeed.closeCurrent()
    }

    /**
     * Atualiza o estado; chamar de meio em meio segundo com o canal aberto. [programmeStart]: quando começou o
     * programa do guia que dá a uma hora (ms desde 1970), ou null sem guia para essa hora.
     */
    fun update(player: Player, programmeStart: (wallMs: Long) -> Long? = lastProgrammeStart) {
        lastProgrammeStart = programmeStart
        val s = session
        if (s != null) {
            val ring = s.ring
            if (ring.isTs != true || s.notTs || player !is ExoPlayer) return unavailable()
            var live = ring.liveMediaMs
            // O programa no ponto que se vê (não no direto: em pausa no fim do jogo, o jogo fica até se passar dele).
            // Folga de um minuto antes do início, que as horas do guia raramente são certas.
            val wall = System.currentTimeMillis()
            val start = programmeStart(wall - (live - (s.startMediaMs + player.currentPosition)))
            ring.setMaxAge(if (start == null) NO_EPG_AGE_MS else ChannelFeed.MAX_AGE_MS)
            if (start != null) ring.dropBefore(live - (wall - start) - PROGRAMME_MARGIN_MS)
            live = ring.liveMediaMs
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

    /** Um canal aberto pelo anel: a ligação (partilhada) e onde vai a leitura deste player. */
    private inner class Session(val feed: ChannelFeed, private val extractors: ExtractorsFactory) {
        val ring: TimeshiftRing get() = feed.ring
        val notTs: Boolean get() = feed.notTs

        /** Tempo do vídeo (no anel) onde começou a fonte atual do player. */
        @Volatile var startMediaMs = 0L

        /** Porque acabou a última leitura (BOUNDARY, OVERRUN, END) e onde. */
        @Volatile var end = 0
        @Volatile var endAt = 0L

        fun sourceAt(offset: Long): MediaSource =
            ProgressiveMediaSource.Factory({ RingDataSource(this) }, extractors)
                .setLoadErrorHandlingPolicy(ringErrors)
                .createMediaSource(MediaItem.fromUri(Uri.parse("timeshift://ring/${feed.id}?at=$offset")))

        /**
         * A ligação já tentou as vezes que devia: quando o anel acabou com um erro, o ExoPlayer não volta a ler o
         * mesmo erro do disco (eram mais 0, 1 e 2 s antes de o canal passar ao outro formato ou mostrar o erro).
         */
        private val ringErrors = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (ring.isFinished && !notTs) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
        }

        fun close() {
            feed.onReconnect = null
            feed.onConnected = null
            feed.release(ChannelFeed.Holder.PLAYER)
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
                    s.feed.done.await()
                } catch (e: InterruptedException) {
                    throw InterruptedIOException().apply { initCause(e) }
                }
                val d = s.feed.connect()
                direct = d
                val length = d.open(dataSpec.buildUpon().setUri(s.feed.uriForPlayer).build())
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

    internal companion object {
        /** Sem guia, os últimos 30 min (com guia, o programa todo: ver [ChannelFeed.MAX_AGE_MS]). */
        private const val NO_EPG_AGE_MS = 30 * 60_000L
        const val PROGRAMME_MARGIN_MS = 60_000L

        /** Para voltar ao direto, começa-se este tanto antes do fim. */
        private const val LIVE_START_MS = 5_000L

        /** Pedir para ficar a menos disto do direto é voltar ao direto (também no telemóvel, ao saltar). */
        const val LIVE_SNAP_MS = 3_000L

        /** O ponto da pausa saiu do anel: continua-se este tanto depois do mais antigo (para não voltar a sair já). */
        private const val OVERRUN_MARGIN_MS = 15_000L

        /** Atrás do direto, chegar a menos disto do atraso normal é estar outra vez no direto. */
        private const val LIVE_SLACK_MS = 3_000L

        /** Menos atrás do que isto não vale a pergunta antes de mudar de canal (ver [worthAsking]). */
        private const val ASK_BEHIND_MS = 5_000L

        private const val HLS_MIN_WINDOW_MS = 20_000L
        private const val HLS_LIVE_SLACK_MS = 6_000L
        private const val WAIT_MS = 250L
        private const val KIND_POLL_MS = 20L
    }
}
