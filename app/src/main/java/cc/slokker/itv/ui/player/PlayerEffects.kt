package cc.slokker.itv.ui.player

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.media3.common.Player
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.GlassBackdrop
import cc.slokker.itv.ui.glass.blurInto
import cc.slokker.itv.ui.player.engine.videoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Os trabalhos de fundo do ecrã do player que não dependem das teclas nem dos painéis: o fundo do vidro, o
// temporizador e os lembretes. Cada um no seu efeito, com o estado que lhe cabe.

/**
 * Fundo do vidro: miniatura desfocada do vídeo de [videoView], renovada enquanto [visible] (há painéis à vista).
 * [lastInput] é a hora da última tecla: enquanto se navega, o fundo fica parado.
 */
@Composable
internal fun GlassBackdropEffect(visible: Boolean, videoView: VideoView?, backdrop: GlassBackdrop, glassOpacity: Int, lastInput: LongArray, player: Player) {
    val latestOpacity by rememberUpdatedState(glassOpacity)
    LaunchedEffect(visible, videoView) {
        val view = videoView ?: return@LaunchedEffect
        val scratch = createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT)
        // Dois buffers alternados: escreve-se sempre no que não está a ser mostrado. Sem alocações por frame.
        val buffers = Array(2) { createBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT) }
        val images = buffers.map { it.asImageBitmap() }
        val workA = IntArray(BACKDROP_WIDTH * BACKDROP_HEIGHT)
        val workB = IntArray(BACKDROP_WIDTH * BACKDROP_HEIGHT)
        var next = 0
        while (visible) {
            val started = SystemClock.uptimeMillis()
            // Com o vidro quase opaco o fundo nem se vê, por isso não vale a pena renová-lo.
            val opaque = latestOpacity >= 95 && backdrop.image != null
            // Enquanto se navega com o comando, o fundo fica parado: copiar e desfocar o vídeo a cada frame
            // competia com o scroll pelo CPU e pela GPU. Desfocado, nem se nota que esperou pelo fim das teclas.
            val navigating = SystemClock.uptimeMillis() - lastInput[0] < BACKDROP_PAUSE_MS
            if (opaque || navigating) {
                delay(50)
                continue
            }
            view.capture(BACKDROP_WIDTH, BACKDROP_HEIGHT, into = scratch)?.let { frame ->
                val target = next
                withContext(Dispatchers.Default) { blurInto(frame, buffers[target], workA, workB) }
                backdrop.image = images[target]
                next = 1 - target
            }
            // O vidro renova-se ao ritmo do vídeo: à cadência até 30 fps, a meia cadência acima (50 → 25),
            // para ficar alinhado com os frames sem pesar na box.
            val videoFps = player.videoFormat()?.frameRate?.takeIf { it > 0f } ?: DEFAULT_BACKDROP_FPS
            val fps = if (videoFps > 30f) videoFps / 2 else videoFps
            val frameMs = (1000f / fps).toLong().coerceIn(33L, 66L)
            delay((frameMs - (SystemClock.uptimeMillis() - started)).coerceAtLeast(4))
        }
    }
}

/**
 * Temporizador para desligar: [onWarning] com os segundos que faltam no último minuto (null fora dele) e, no fim,
 * pára o [player] e fecha a app.
 */
@Composable
internal fun SleepTimerEffect(vm: AppViewModel, sleepAt: Long?, player: Player, context: Context, onWarning: (Int?) -> Unit) {
    LaunchedEffect(sleepAt) {
        val at = sleepAt
        if (at == null) {
            onWarning(null)
            return@LaunchedEffect
        }
        while (true) {
            val left = at - System.currentTimeMillis()
            if (left <= 0) {
                player.stop()
                vm.setSleepTimer(null, announce = false)
                (context as? Activity)?.finish()
                break
            }
            onWarning(if (left <= SLEEP_WARNING_MS) ((left + 999) / 1000).toInt() else null)
            delay(if (left <= SLEEP_WARNING_MS + 1_000) 1_000 else minOf(left - SLEEP_WARNING_MS, 30_000))
        }
    }
}

/**
 * O lembrete a mostrar: vê de 15 em 15 s se algum programa vai começar; sem resposta, o aviso some-se sozinho
 * (e o lembrete conta como visto).
 */
@Composable
internal fun rememberDueReminder(vm: AppViewModel): MutableState<Reminder?> {
    val state = remember { mutableStateOf<Reminder?>(null) }
    var reminder by state
    LaunchedEffect(Unit) {
        while (true) {
            if (reminder == null) vm.dueReminder(System.currentTimeMillis())?.let { reminder = it }
            delay(15_000)
        }
    }
    LaunchedEffect(reminder) {
        val r = reminder ?: return@LaunchedEffect
        delay(REMINDER_CARD_MS)
        if (reminder == r) {
            vm.consumeReminder(r, watch = false)
            reminder = null
        }
    }
    return state
}

/** O temporizador avisa no último minuto. */
private const val SLEEP_WARNING_MS = 60_000L
private const val REMINDER_CARD_MS = 45_000L
private const val BACKDROP_WIDTH = 128
private const val BACKDROP_HEIGHT = 72
private const val DEFAULT_BACKDROP_FPS = 30f

/** Quanto tempo depois da última tecla o fundo do vidro volta a acompanhar o vídeo. */
private const val BACKDROP_PAUSE_MS = 400L
