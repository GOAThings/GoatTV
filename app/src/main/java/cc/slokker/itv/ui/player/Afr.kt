@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.playback.FrameRates
import cc.slokker.itv.playback.AfrSession as SharedAfrSession
import cc.slokker.itv.ui.player.engine.PLAYER_LOGS
import cc.slokker.itv.ui.player.engine.ZapTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * A cadência do vídeo medida pelas imagens que se mostram. Os leitores de TS do Media3 (H.264, H.265, MPEG-2) não
 * a dão (o `Format.frameRate` fica -1), e é assim em quase todos os canais IPTV (TS e HLS com TS): sem isto o AFR
 * nunca mudava nada.
 *
 * Mede-se pela diferença entre os tempos de apresentação de imagens seguidas (a mediana, e depois a média das que
 * ficam perto dela, para separar 29,97 de 30), e aproxima-se das cadências habituais. Um canal entrelaçado (576i,
 * 1080i) chega como 25 ou 29,97 imagens por segundo (cada uma com os dois campos) ou, se o descodificador separa os
 * campos, 50 ou 59,94: em ambos os casos a TV vai para 50 ou 59,94 Hz ([FrameRateMatcher.bestMode]).
 *
 * Não é thread-safe: usa-se só na thread que entrega as imagens.
 */
internal class FpsEstimator(private val frames: Int = FRAMES) {
    private val deltas = LongArray(frames)
    private var count = 0
    private var last = NONE

    fun reset() {
        count = 0
        last = NONE
    }

    /** Mais uma imagem, a [presentationTimeUs]. Devolve a cadência quando já há imagens que cheguem; senão null. */
    fun add(presentationTimeUs: Long): Float? {
        val previous = last
        last = presentationTimeUs
        if (previous == NONE) return null
        val d = presentationTimeUs - previous
        // Um salto (outro canal, recomeço no anel, descontinuidade): recomeça a contar daqui. Imagens largadas ou
        // repetidas (o intervalo sai do normal, mas pouco) só não contam.
        if (d < 0 || d > RESET_DELTA_US) {
            count = 0
            return null
        }
        if (d !in MIN_DELTA_US..MAX_DELTA_US) return null
        deltas[count % frames] = d
        count++
        return if (count >= frames) estimate() else null
    }

    private fun estimate(): Float {
        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2]
        val near = sorted.filter { abs(it - median) * 10 <= median }
        val fps = 1_000_000f / near.average().toFloat()
        return snap(fps)
    }

    companion object {
        /** Umas 24 imagens (1 s a 25 fps) chegam para uma medida estável. */
        const val FRAMES = 24
        private const val NONE = Long.MIN_VALUE

        /** Entre 10 e 200 imagens por segundo. */
        private const val MIN_DELTA_US = 5_000L
        private const val MAX_DELTA_US = 100_000L

        /** Mais do que isto entre duas imagens já não é a mesma sequência. */
        private const val RESET_DELTA_US = 1_000_000L

        /** A cadência habitual mais perto de [fps] (até 1 % de diferença); fora disso, [fps] como está (FrameRates.snap). */
        fun snap(fps: Float): Float = FrameRates.snap(fps.toDouble()).toFloat()
    }
}

/**
 * Quando o AFR muda o modo da TV ([Prefs.AfrMode]): uma vez por canal, quando a cadência dele fica conhecida;
 * "ao começar" só no primeiro canal do player (o modo fica nos seguintes, sem o ecrã apagar 1–3 s a cada zap) até
 * [reset] (ao sair do player, quando a TV volta ao modo normal).
 */
internal class AfrSession {
    // A regra é a do núcleo partilhado (igual na Apple); aqui só a cadência em Float, null = não mexer.
    private val shared = SharedAfrSession()

    /** Outro canal: ainda não se decidiu nada para ele. */
    fun newChannel() = shared.newChannel()

    /** A TV voltou ao modo normal (saiu-se do player, ou o AFR foi desligado). */
    fun reset() = shared.reset()

    /** Já se sabe a cadência [fps] do canal: devolve a que se pede à TV, ou null para não mexer. */
    fun onFps(mode: String, fps: Float): Float? = shared.onFps(mode, fps.toDouble()).takeIf { it > 0 }?.toFloat()
}

/**
 * Liga um [FpsEstimator] às imagens do ExoPlayer (na thread dele) e publica a cadência em [fps] (na principal).
 * [reset] a cada canal: as medidas que ainda cheguem do anterior são ignoradas.
 */
internal class FrameRateProbe {
    private val main = Handler(Looper.getMainLooper())
    private val estimator = FpsEstimator()
    private val _fps = MutableStateFlow(0f)

    /** A cadência do canal atual (0 = ainda não se sabe). */
    val fps: StateFlow<Float> = _fps

    @Volatile private var generation = 0

    // Só na thread do player: a geração que o estimador está a medir e a última já medida.
    private var seen = -1
    private var measured = -1

    private val listener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
        val gen = generation
        if (gen == measured) return@VideoFrameMetadataListener
        if (gen != seen) {
            seen = gen
            estimator.reset()
        }
        val fps = estimator.add(presentationTimeUs) ?: return@VideoFrameMetadataListener
        // Uma medida por canal chega.
        measured = gen
        main.post { if (generation == gen) publish(fps, "medida") }
    }

    /** Começa outro canal. Na thread principal. */
    fun reset() {
        generation++
        _fps.value = 0f
    }

    /** A cadência que o próprio formato diz (HLS com FRAME-RATE, VLC); ganha à medida. Na thread principal. */
    fun fromFormat(frameRate: Float) {
        if (frameRate > 0f && _fps.value <= 0f) publish(FpsEstimator.snap(frameRate), "formato")
    }

    private fun publish(fps: Float, source: String) {
        if (_fps.value > 0f) return
        _fps.value = fps
        if (PLAYER_LOGS) Log.d(TAG, "Cadência do canal: $fps fps ($source)")
        ZapTrace.mark("FPS", "$fps $source")
    }

    fun attach(player: ExoPlayer) = player.setVideoFrameMetadataListener(listener)

    fun detach(player: ExoPlayer) = player.clearVideoFrameMetadataListener(listener)

    private companion object {
        const val TAG = "Afr"
    }
}
