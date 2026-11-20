package cc.slokker.itv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.slokker.itv.playback.IdleWatch
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.glass.ItvIcons
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import androidx.tv.material3.Icon

/**
 * "Ainda estás a ver?" e o ecrã de parado (definição idle_stop, ver playback/IdleWatch), na TV e no telemóvel.
 * O aviso fecha-se com qualquer tecla ou toque (o MainActivity trata disso); o botão só mostra o que se pode fazer.
 * [onWatchAgain]: reabre o canal parado (o AppViewModel já saiu do estado de parado).
 */
@Composable
internal fun IdleOverlays(vm: AppViewModel, onWatchAgain: () -> Unit) {
    val phase by vm.idlePhase.collectAsStateWithLifecycle()
    val left by vm.idleSecondsLeft.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    when (phase) {
        IdleWatch.PROMPT -> IdleCard(
            title = s.idlePromptTitle, text = s.idlePromptSub(left), button = s.idleKeep, scrim = Color(0x99000000),
            onButton = { vm.noteInput() },
        )
        IdleWatch.STOPPED -> IdleCard(
            title = s.idleStoppedTitle, text = null, button = s.idleWatchAgain, scrim = Color(0xF2000000),
            onButton = {
                vm.idleWatchAgain()
                onWatchAgain()
            },
        )
        else -> Unit
    }
}

@Composable
private fun IdleCard(title: String, text: String?, button: String, scrim: Color, onButton: () -> Unit) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    // Tira o foco do que está por baixo: na TV, o OK carrega logo no botão.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Come os toques por cima do que está por baixo (o aviso e o ecrã de parado tapam tudo).
    Box(Modifier.fillMaxSize().background(scrim).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(ItvIcons.Sleep, contentDescription = null, tint = ItvColors.TextDim, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = ItvColors.Text, textAlign = TextAlign.Center)
            if (text != null) {
                Spacer(Modifier.height(8.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, color = ItvColors.TextDim, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (focused) accent.start.copy(alpha = 0.35f) else Color(0x33FFFFFF))
                    .border(2.dp, if (focused) accent.start else Color.Transparent, RoundedCornerShape(50))
                    .onFocusChanged { focused = it.isFocused }
                    .focusRequester(focus)
                    .clickable(onClick = onButton)
                    .padding(horizontal = 36.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(button, style = MaterialTheme.typography.titleMedium, color = ItvColors.Text)
            }
        }
    }
}
