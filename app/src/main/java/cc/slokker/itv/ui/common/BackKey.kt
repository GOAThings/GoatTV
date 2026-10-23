package cc.slokker.itv.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Trata a tecla Voltar antes do Compose. Sem isto, a primeira pressão só tira o foco do
 * grupo atual (FocusDirection.Exit) e é preciso carregar duas vezes.
 * [onBack] devolve false para deixar a tecla seguir (ex.: para um nível de fora).
 */
fun Modifier.onBackKey(onBack: () -> Boolean): Modifier = onPreviewKeyEvent { e ->
    if (e.key.nativeKeyCode != AndroidKeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
    // Consome as duas metades da tecla; a ação corre ao largar.
    if (e.type == KeyEventType.KeyUp) onBack() else true
}
