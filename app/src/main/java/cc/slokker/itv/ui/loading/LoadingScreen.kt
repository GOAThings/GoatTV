package cc.slokker.itv.ui.loading

import cc.slokker.itv.ui.i18n.LocalStrings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cc.slokker.itv.ui.Screen
import cc.slokker.itv.ui.common.ErrorPanel
import cc.slokker.itv.ui.common.Logo
import cc.slokker.itv.ui.glass.AuroraBackground
import cc.slokker.itv.ui.glass.GlassButton
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.player.focusWithRetry
import cc.slokker.itv.ui.theme.ItvColors

@Composable
fun LoadingScreen(
    state: Screen.Loading,
    canOpenSettings: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onLogout: (() -> Unit)?,
    /** Conta sem listas: abre o formulário da primeira lista (também se pode adicionar no site). */
    onAddPlaylist: (() -> Unit)? = null,
) {
    val error = state.error
    // A carregar: o logo animado (o mesmo da abertura, no mesmo sítio) e o que se está a carregar por baixo.
    if (error == null) {
        GoatLoading(state.message)
        return
    }
    AuroraBackground {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            run {
                Logo(size = 34)
                Spacer(Modifier.height(24.dp))
                val focus = remember { FocusRequester() }
                ErrorPanel(error) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val s = LocalStrings.current
                        if (onAddPlaylist != null) GlassButton(s.addPlaylist, onAddPlaylist, Modifier.focusRequester(focus))
                        GlassButton(s.retry, onRetry, if (onAddPlaylist == null) Modifier.focusRequester(focus) else Modifier)
                        if (canOpenSettings) GlassButton(s.settings, onSettings)
                        if (onLogout != null) GlassButton(s.logout, onLogout)
                    }
                }
                LaunchedEffect(error) { focusWithRetry(focus) }
            }
        }
    }
}
