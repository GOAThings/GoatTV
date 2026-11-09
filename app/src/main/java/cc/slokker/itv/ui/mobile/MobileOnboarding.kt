package cc.slokker.itv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.Logo
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent

/** Primeira vez no telemóvel: conta GOAT ou listas só neste aparelho. */
@Composable
fun MobileWelcome(onAccount: () -> Unit, onLocal: () -> Unit) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Logo(size = 28)
        Text(s.welcomeTitle, color = ItvColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(s.welcomeSub, color = ItvColors.TextDim, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        ChoiceCard(s.welcomeAccount, s.welcomeAccountSub, s.recommended, onAccount)
        ChoiceCard(s.welcomeLocalPhone, s.welcomeLocalPhoneSub, null, onLocal)
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, badge: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(PhoneShapes.Card).background(PhoneColors.Card).clickable(onClick = onClick).padding(18.dp)) {
        if (badge != null) {
            Text(
                badge.uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(LocalAccent.current.brush, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(title, color = ItvColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = ItvColors.TextDim, fontSize = 14.sp)
    }
}

/** Erro ao carregar uma lista, com botões de toque. */
@Composable
fun MobileLoadError(error: ErrorInfo, onRetry: () -> Unit, onSettings: (() -> Unit)?, onLogout: (() -> Unit)?, onAddPlaylist: (() -> Unit)?) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Logo(size = 26)
        Spacer(Modifier.height(8.dp))
        Text(error.title, color = ItvColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        error.cause?.let { Text(it, color = ItvColors.TextDim, fontSize = 15.sp) }
        error.fixes.forEach { Text("• $it", color = ItvColors.TextDim, fontSize = 14.sp) }
        error.detail?.let { Text(it, color = ItvColors.TextFaint, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        onAddPlaylist?.let { MenuRow(s.addPlaylist, null, onClick = it) }
        MenuRow(s.retry, null, onClick = onRetry)
        onSettings?.let { MenuRow(s.settings, null, onClick = it) }
        onLogout?.let { MenuRow(s.logout, null, onClick = it) }
    }
}
