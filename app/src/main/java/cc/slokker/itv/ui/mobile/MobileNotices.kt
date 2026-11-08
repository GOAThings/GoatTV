package cc.slokker.itv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import cc.slokker.itv.R
import cc.slokker.itv.data.AppRelease
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.UpdateState
import cc.slokker.itv.ui.i18n.LocalStrings
import cc.slokker.itv.ui.player.CountdownRing
import cc.slokker.itv.ui.player.releaseHighlights
import cc.slokker.itv.ui.player.rememberMsLeft
import cc.slokker.itv.ui.theme.GoatPalette
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.theme.tabular

/**
 * Ao alto, no topo do vídeo junto à câmara: a pílula preta "A reconectar…" com o anel da próxima tentativa (como
 * uma Live Activity). Deitado, em ecrã inteiro, o player usa a cápsula da TV.
 */
@Composable
internal fun ReconnectIsland(channel: String, nextTryAt: Long, waitMs: Long, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(
        modifier
            .height(34.dp)
            .widthIn(min = 230.dp, max = 300.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channel,
            color = ItvColors.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(s.reconnecting, color = GoatPalette.Warning, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        CountdownRing(rememberMsLeft(nextTryAt), waitMs, Modifier.size(26.dp), color = GoatPalette.Warning)
    }
}

/**
 * Versão nova no telemóvel: uma folha que sobe de baixo com as novidades (as mesmas do cartão da TV), "Atualizar"
 * (descarrega e instala; o botão mostra o progresso) e "Mais tarde". Toca-se fora ou Voltar para a fechar.
 */
@Composable
internal fun MobileUpdateSheet(vm: AppViewModel, modifier: Modifier = Modifier) {
    val notice by vm.updateNotice.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val busy = update is UpdateState.Downloading || update is UpdateState.Installing
    val visible = notice != null && (update is UpdateState.Available || busy)
    var last by remember { mutableStateOf<AppRelease?>(null) }
    notice?.let { last = it }
    // Falhou a descarregar: o erro fica nas Definições → Atualizar; a folha sai.
    LaunchedEffect(update) {
        if (update is UpdateState.Failed) vm.dismissUpdateNotice()
    }
    BackHandler(enabled = visible) { vm.dismissUpdateNotice() }

    Box(modifier) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { if (!busy) vm.dismissUpdateNotice() },
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            last?.let { UpdateSheetContent(it, update, onUpdate = { vm.installUpdate() }, onLater = { vm.dismissUpdateNotice() }) }
        }
    }
}

@Composable
private fun UpdateSheetContent(release: AppRelease, state: UpdateState, onUpdate: () -> Unit, onLater: () -> Unit) {
    val s = LocalStrings.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xFF151821))
            // Não deixa o toque passar para o fundo (que fecharia a folha).
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).graphicsLayer { scaleX = 1.45f; scaleY = 1.45f },
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(s.updateReadyLabel.uppercase(), color = ItvColors.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(s.updateWhatsNew(release.version), color = ItvColors.Text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        val highlights = releaseHighlights(release.notes)
        if (highlights.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                highlights.forEach { line ->
                    Row {
                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(LocalAccent.current.start))
                        Spacer(Modifier.width(12.dp))
                        Text(line, color = ItvColors.Text.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        val label = when (state) {
            is UpdateState.Downloading -> s.downloading((state.progress * 100).toInt())
            is UpdateState.Installing -> s.installing
            else -> s.updateNow
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable(enabled = state is UpdateState.Available, onClick = onUpdate)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.Center, style = LocalTextStyle.current.tabular)
        }
        if (state is UpdateState.Available) {
            Text(
                s.updateLater,
                color = ItvColors.TextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLater).padding(vertical = 12.dp),
            )
        }
    }
}
