@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.engine

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * As marcas de tempo de cada mudança de canal (só no debug e no staging, tag "Zap"), para medir na TV e no
 * emulador: da tecla (K, a hora do próprio KeyEvent) ao primeiro frame (R1), ao READY (RD), a dar (PL) e ao
 * primeiro som (A). Pelo meio: T (tune), F (imagem congelada), O (abrir), G (o portão deixou abrir), H (cabeçalhos
 * da resposta), B (primeiro byte), P (faixas). Cada linha: `Zap #n +ms MARCA detalhe`, com o tempo desde K.
 * Tudo em uptimeMillis (a base dos KeyEvent). Pode ser chamado de qualquer thread.
 */
internal object ZapTrace {
    private const val TAG = "Zap"

    @Volatile private var id = 0
    @Volatile private var startAt = 0L

    /** As marcas que só contam a primeira vez em cada zap (as outras, como O ou H, repetem-se a cada tentativa). */
    private val once = HashSet<String>()

    /** Uma tecla de mudar de canal (ou o toque/gesto no telemóvel), à hora [eventTime] (uptimeMillis). */
    fun key(eventTime: Long = SystemClock.uptimeMillis(), what: String = "") {
        if (!PLAYER_LOGS) return
        synchronized(once) {
            id++
            startAt = eventTime
            once.clear()
        }
        line("K", what)
    }

    /** Começo de um tune: sem tecla recente (escolha numa lista, arranque), é ele o início. */
    fun tune(what: String) {
        if (!PLAYER_LOGS) return
        if (SystemClock.uptimeMillis() - startAt > NO_KEY_MS) key(what = "(sem tecla)")
        mark("T", what)
    }

    fun mark(name: String, detail: String = "") {
        if (!PLAYER_LOGS || startAt == 0L) return
        line(name, detail)
    }

    /** Como [mark], com o detalhe calculado só quando se regista. */
    inline fun mark(name: String, detail: () -> String) {
        if (active) mark(name, detail())
    }

    /** Há um zap a medir (debug/staging, depois de uma tecla). */
    val active: Boolean get() = PLAYER_LOGS && startAt != 0L

    /** Como [mark], mas só a primeira vez neste zap. */
    fun first(name: String, detail: String = "") {
        if (!PLAYER_LOGS || startAt == 0L) return
        if (synchronized(once) { once.add(name) }) line(name, detail)
    }

    private fun line(name: String, detail: String) {
        Log.d(TAG, "#$id +${SystemClock.uptimeMillis() - startAt} $name $detail".trimEnd())
    }

    /** As marcas que vêm do ExoPlayer (faixas, primeiro frame, estado, áudio, descodificadores). */
    val listener = object : AnalyticsListener {
        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
            val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }?.getTrackFormat(0)
            if (video != null) first("P", "${video.sampleMimeType} ${video.width}x${video.height} frameRate=${video.frameRate}")
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) = first("R1")

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (state == Player.STATE_READY) first("RD")
            // Parou para carregar depois de já ter arrancado (o início com pouco buffer pode fazer isto).
            if (state == Player.STATE_BUFFERING && synchronized(once) { "RD" in once }) mark("STALL")
        }

        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
            if (isPlaying) first("PL")
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) = first("A")

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
            mark("VDEC", "$decoderName ${initializationDurationMs}ms")

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
            mark("VFMT", "${format.width}x${format.height} reuse=${decoderReuseEvaluation?.result} discard=${decoderReuseEvaluation?.discardReasons}")

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) =
            mark("ATRK", "enc=${audioTrackConfig.encoding} rate=${audioTrackConfig.sampleRate} offload=${audioTrackConfig.offload}")
    }

    /** Sem tecla há mais do que isto: o tune é o início (outra forma de escolher o canal). */
    private const val NO_KEY_MS = 3_000L
}
