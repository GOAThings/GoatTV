package cc.slokker.itv.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cc.slokker.itv.ui.common.Logo
import cc.slokker.itv.ui.glass.AuroraBackground
import cc.slokker.itv.ui.glass.glass
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.focusWithRetry
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.ItvShapes
import cc.slokker.itv.ui.theme.LocalAccent

/**
 * Primeira vez que se abre a app: entrar com a conta GOAT (recomendado, sincroniza tudo entre dispositivos)
 * ou adicionar listas só nesta TV, sem conta.
 */
@Composable
fun WelcomeScreen(onAccount: () -> Unit, onLocal: () -> Unit) {
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusWithRetry(focus) }
    AuroraBackground {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(900.dp)
                .glass(ItvShapes.Panel)
                .padding(40.dp),
        ) {
            Logo(size = 28)
            Spacer(Modifier.height(12.dp))
            Text(s.welcomeTitle, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(s.welcomeSub, color = ItvColors.TextDim, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(24.dp))
            // Os dois cartões com a mesma altura (a do texto mais longo).
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Choice(
                    title = s.welcomeAccount,
                    badge = s.recommended,
                    body = s.welcomeAccountSub,
                    onClick = onAccount,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                Choice(
                    title = s.welcomeLocal,
                    badge = null,
                    body = s.welcomeLocalSub,
                    onClick = onLocal,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Cartão de escolha: com foco fica branco com texto escuro, como as linhas das listas. */
@Composable
private fun Choice(title: String, badge: String?, body: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = ClickableSurfaceDefaults.shape(ItvShapes.Panel),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = ItvColors.Text,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (badge != null) {
                Text(
                    badge.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(LocalAccent.current.brush, ItvShapes.Small)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.82f))
        }
    }
}
