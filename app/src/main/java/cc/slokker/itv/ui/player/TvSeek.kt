package cc.slokker.itv.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * O que se pode pausar, recuar e avançar na TV: o programa em catch-up ([archive]) ou a pausa do direto ([ring]).
 * As posições vão no eixo de cada barra: no catch-up, ms desde o início do programa; na pausa, ms atrás do direto.
 */
internal interface SeekTarget {
    /** Onde está agora. */
    fun current(): Long

    /** Um passo para a frente ou para trás a partir de [from], dentro do que existe. */
    fun stepped(from: Long, forward: Boolean): Long

    /** Vai mesmo para [target] (depois de se parar de carregar nas setas). */
    fun commit(target: Long)

    /** Depois de [commit] o salto está feito (pausa do direto); no catch-up fica por confirmar até o vídeo reabrir. */
    val settlesOnCommit: Boolean

    fun pause()
    fun resume()
    fun goLive()

    companion object {
        /** O programa em catch-up: ← → somam [stepMs]; cada salto é um pedido novo ao servidor. */
        fun archive(position: () -> Long, durationMs: Long, stepMs: Long, onSeek: (Long) -> Unit, onPause: () -> Unit, onPlay: () -> Unit, onExit: () -> Unit) =
            object : SeekTarget {
                override fun current() = position()
                override fun stepped(from: Long, forward: Boolean) = (from + if (forward) stepMs else -stepMs).coerceIn(0L, durationMs)
                override fun commit(target: Long) = onSeek(target)
                override val settlesOnCommit = false
                override fun pause() = onPause()
                override fun resume() = onPlay()
                override fun goLive() = onExit()
            }

        /** A pausa do direto: ← → somam [stepMs] atrás do direto; o salto é no disco (ver [Timeshift]). */
        fun ring(behind: () -> Long, window: () -> Long, stepMs: Long, onSeek: (Long) -> Unit, onPause: () -> Unit, onResume: () -> Unit, onLive: () -> Unit) =
            object : SeekTarget {
                override fun current() = behind()
                override fun stepped(from: Long, forward: Boolean) = (from - if (forward) stepMs else -stepMs).coerceIn(0L, window())
                override fun commit(target: Long) = onSeek(target)
                override val settlesOnCommit = true
                override fun pause() = onPause()
                override fun resume() = onResume()
                override fun goLive() = onLive()
            }
    }
}

/**
 * Pausar, recuar e avançar na TV, igual no catch-up e na pausa do direto: os toques nas setas somam-se num salto
 * por confirmar ([pending]), feito quando se pára de carregar; a barra aparece a cada tecla e esconde-se sozinha
 * (fica enquanto está em pausa). As teclas e o aspeto de cada barra continuam no ecrã.
 */
@Stable
internal class TvSeek {
    /** O salto por confirmar, no eixo do [SeekTarget] atual. */
    var pending by mutableStateOf<Long?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var barVisible by mutableStateOf(false)

    /** Muda a cada tecla: o tempo até a barra se esconder recomeça. */
    var barToken by mutableIntStateOf(0)
        private set

    fun showBar() {
        barVisible = true
        barToken++
    }

    /** Outro canal, outro ponto do catch-up ou a app em segundo plano: sem pausa nem salto por confirmar. */
    fun reset() {
        pending = null
        paused = false
    }

    /** Uma tecla de recuar/avançar: soma ao salto por confirmar. */
    fun step(target: SeekTarget, forward: Boolean) {
        pending = target.stepped(pending ?: target.current(), forward)
        showBar()
    }

    fun togglePause(target: SeekTarget) {
        if (paused) {
            paused = false
            target.resume()
        } else {
            paused = true
            target.pause()
        }
        showBar()
    }

    /** Pararam as teclas: faz o salto. */
    fun commit(target: SeekTarget, to: Long) {
        if (target.settlesOnCommit) paused = false
        target.commit(to)
        if (target.settlesOnCommit) pending = null
    }

    /** Volta ao direto (sai do catch-up, ou da pausa). */
    fun goLive(target: SeekTarget) {
        paused = false
        pending = null
        target.goLive()
        barVisible = false
    }
}
