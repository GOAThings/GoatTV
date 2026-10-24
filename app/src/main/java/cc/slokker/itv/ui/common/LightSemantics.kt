package cc.slokker.itv.ui.common

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Sem leitor de ecrã (TalkBack), a app não publica a árvore de acessibilidade.
 *
 * O Compose compara essa árvore a cada frame sempre que há *qualquer* serviço de acessibilidade ligado, mesmo um
 * que não lê o ecrã (ex.: o "Manter como ecrã inicial" do GoatLauncher). Na SHIELD isso era quase um terço do
 * tempo de cada frame a andar no guia. Com o TalkBack ligado, a árvore volta inteira (ouve as mudanças).
 * O comando não depende disto: o foco e o OK vão pelas teclas.
 */
@Composable
fun Modifier.semanticsOnlyForScreenReaders(): Modifier {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var screenReader by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { screenReader = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return if (screenReader) this else this.clearAndSetSemantics { }
}
