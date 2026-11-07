package cc.slokker.itv.ui.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/** O que a ligação do canal a tocar explica: o veredicto da página Ligação, do gráfico e do aviso. */
enum class NetVerdict {
    /** Ainda não há segundos suficientes para dizer. */
    MEASURING,
    GOOD,
    /** Chega menos do que o canal precisa: o vídeo de reserva vai-se gastando até parar. */
    SLOW,
    /** Chega o suficiente, mas o servidor pára de mandar de vez em quando. */
    GAPS,
    /** O servidor demora a responder (os canais demoram a abrir). */
    SLOW_REPLY,
    /** Com o VLC não se mede nada. */
    UNSUPPORTED,
}

/** Por onde a TV está ligada. [band]: 2.4, 5 ou 6 (GHz); [signal]: 0–100. */
data class NetLink(val wifi: Boolean = false, val cable: Boolean = false, val band: String? = null, val signal: Int? = null)

data class NetState(
    val verdict: NetVerdict = NetVerdict.MEASURING,
    /** O que chega do servidor enquanto o player lê (média dos últimos 20 s). */
    val gettingKbps: Int? = null,
    /** O débito do canal: o declarado ou o medido (bytes por segundo de vídeo). */
    val needsKbps: Int? = null,
    /** Vídeo já descarregado, à frente do que se está a ver. */
    val bufferMs: Long = 0,
    /** Mediana do tempo até o servidor começar a responder. */
    val replyMs: Long? = null,
    /** Paragens neste canal nos últimos 10 minutos. */
    val stops: Int = 0,
    /** Velocidade de segundo a segundo, no último minuto (Mbit/s), para o gráfico. */
    val history: List<Float> = emptyList(),
    val link: NetLink = NetLink(),
)

/**
 * Diagnóstico da ligação do canal a tocar (Definições → Canal atual → Ligação, o gráfico no vídeo e o aviso
 * "este canal está sempre a parar"). Conta os bytes que chegam do servidor e quanto vídeo trazem, e compara a
 * velocidade a que chegam com a que o canal precisa. Os bytes chegam pelas threads do player
 * ([TransferListener]); o resto corre na thread principal, em [sample], de segundo a segundo.
 */
class NetMonitor(context: Context) : TransferListener {
    private val app = context.applicationContext
    private val bytes = AtomicLong()
    private val opening = IdentityHashMap<DataSource, Long>()
    private val replies = ArrayDeque<Long>()

    private class Sample(val bytes: Long, val wallMs: Long, val mediaMs: Long, val bufferMs: Long, val loading: Boolean)

    private val samples = ArrayDeque<Sample>()
    private val stopTimes = ArrayDeque<Long>()
    private var lastBytes = 0L
    private var lastAt = 0L
    private var lastBuffer = 0L
    private var lastPlaying = false
    private var link = NetLink()
    private var linkAt = 0L

    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork) synchronized(opening) { opening[source] = SystemClock.elapsedRealtime() }
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val started = synchronized(opening) { opening.remove(source) } ?: return
        synchronized(replies) {
            replies.addLast(SystemClock.elapsedRealtime() - started)
            while (replies.size > REPLIES) replies.removeFirst()
        }
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    /** Outro canal (ou outro player): começa do zero. */
    fun reset() {
        samples.clear()
        stopTimes.clear()
        synchronized(replies) { replies.clear() }
        lastAt = 0L
        _state.value = NetState(link = link)
    }

    /** O vídeo parou para carregar (ou caiu e voltou a ligar) depois de já estar a dar. */
    fun onStop() {
        stopTimes.addLast(SystemClock.elapsedRealtime())
    }

    /** Uma medição; chamar de segundo a segundo enquanto o canal está aberto. */
    fun sample(player: Player) {
        val now = SystemClock.elapsedRealtime()
        if (now - linkAt > LINK_EVERY_MS) {
            link = readLink()
            linkAt = now
        }
        if (player !is ExoPlayer) {
            _state.value = NetState(verdict = NetVerdict.UNSUPPORTED, link = link)
            return
        }
        val total = bytes.get()
        val buffer = player.totalBufferedDuration
        val playing = player.isPlaying
        if (lastAt != 0L && player.playbackState != Player.STATE_IDLE) {
            val wall = now - lastAt
            // Vídeo que chegou: o que o buffer cresceu mais o que se viu entretanto (as posições de um canal em
            // direto mudam de referência; a diferença do buffer não).
            val media = (buffer - lastBuffer + if (lastPlaying) wall else 0L).coerceAtLeast(0L)
            samples.addLast(Sample(total - lastBytes, wall, media, buffer, player.isLoading))
            while (samples.size > HISTORY) samples.removeFirst()
        }
        lastBytes = total
        lastAt = now
        lastBuffer = buffer
        lastPlaying = playing
        while (stopTimes.isNotEmpty() && now - stopTimes.first() > STOPS_WINDOW_MS) stopTimes.removeFirst()
        _state.value = evaluate(player, buffer)
    }

    private fun evaluate(player: ExoPlayer, buffer: Long): NetState {
        // Com o buffer cheio o player deixa de ler: vale a última velocidade medida (no último minuto).
        val getting = speedKbps(samples.takeLast(SPEED_WINDOW)) ?: speedKbps(samples)
        val needs = declaredKbps(player) ?: measuredKbps()
        val reply = synchronized(replies) { replies.sorted().getOrNull(replies.size / 2) }
        // Para onde vai o buffer nos últimos 20 s (ms de vídeo por segundo).
        val window = samples.takeLast(SPEED_WINDOW)
        val trend = if (window.size >= 10) (window.last().bufferMs - window.first().bufferMs) * 1_000 / window.sumOf { it.wallMs }.coerceAtLeast(1) else 0L
        val stops = stopTimes.size
        val verdict = when {
            samples.size < MIN_SAMPLES -> NetVerdict.MEASURING
            needs != null && getting != null && getting < needs * SLOW_RATIO && (stops > 0 || trend < SHRINKING) -> NetVerdict.SLOW
            stops >= 2 -> NetVerdict.GAPS
            reply != null && reply > SLOW_REPLY_MS -> NetVerdict.SLOW_REPLY
            // Nada medido e nada em reserva: ainda não se sabe.
            getting == null && buffer < 2_000 -> NetVerdict.MEASURING
            else -> NetVerdict.GOOD
        }
        // Gráfico: média de 3 s, só com o player a ler (num HLS com o buffer cheio a leitura pára e não é lentidão).
        var last = 0f
        val history = samples.mapIndexed { i, s ->
            val around = samples.subList((i - 2).coerceAtLeast(0), i + 1).filter { it.loading }
            val ms = around.sumOf { it.wallMs }
            if (ms > 0) last = around.sumOf { it.bytes } * 8f / ms / 1_000f
            last
        }
        return NetState(verdict, getting, needs, buffer, reply, stops, history, link)
    }

    /** O que chegou enquanto o player lia, nestas medições (precisa de 3 s a ler para dizer). */
    private fun speedKbps(from: List<Sample>): Int? {
        val reading = from.filter { it.loading }
        val ms = reading.sumOf { it.wallMs }
        return if (ms >= 3_000) (reading.sumOf { it.bytes } * 8 / ms).toInt() else null
    }

    /** O débito que o canal declara (HLS: o BANDWIDTH da variante); as streams .ts quase nunca o dizem. */
    private fun declaredKbps(player: ExoPlayer): Int? {
        fun Format?.kbps() = this?.bitrate?.takeIf { it != Format.NO_VALUE && it > 0 }?.div(1_000)
        val video = player.videoFormat.kbps() ?: return null
        return video + (player.audioFormat.kbps() ?: 0)
    }

    /** Bytes por segundo de vídeo, no último minuto (precisa de pelo menos 5 s de vídeo para dizer). */
    private fun measuredKbps(): Int? {
        val media = samples.sumOf { it.mediaMs }
        if (media < 5_000) return null
        return (samples.sumOf { it.bytes } * 8 / media).toInt()
    }

    private fun readLink(): NetLink {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return NetLink()
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return NetLink()
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetLink(cable = true)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                @Suppress("DEPRECATION") // Sem alternativa sem pedir a localização; a força e a banda não precisam dela.
                val info = runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
                val band = info?.frequency?.let { f ->
                    when {
                        f in 2_400..2_500 -> "2.4"
                        f in 4_900..5_900 -> "5"
                        f >= 5_925 -> "6"
                        else -> null
                    }
                }
                // −90 dBm (quase sem rede) a −50 dBm (ao lado do router).
                val signal = info?.rssi?.takeIf { it < 0 && it > -127 }?.let { ((it + 90) * 100 / 40).coerceIn(0, 100) }
                NetLink(wifi = true, band = band, signal = signal)
            }
            else -> NetLink()
        }
    }

    private companion object {
        const val HISTORY = 60
        const val SPEED_WINDOW = 20
        const val MIN_SAMPLES = 8
        const val REPLIES = 9
        const val STOPS_WINDOW_MS = 10 * 60_000L
        const val LINK_EVERY_MS = 5_000L
        const val SLOW_RATIO = 0.9
        /** O buffer a encolher mais de 0,2 s por segundo. */
        const val SHRINKING = -200L
        const val SLOW_REPLY_MS = 2_500L
    }
}

/** Kbit/s em Mbit/s, com uma casa decimal ("4.1"; a vírgula segue a língua). */
fun mbps(kbps: Int): String = "%.1f".format(kbps / 1_000f)

/** As paragens que pedem um aviso: tantas em 10 minutos, com a causa à vista. */
fun NetState.needsHint(): Boolean = stops >= HINT_STOPS && (verdict == NetVerdict.SLOW || verdict == NetVerdict.GAPS)

private const val HINT_STOPS = 3
