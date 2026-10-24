package cc.slokker.itv

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.theme.Accent
import androidx.compose.runtime.CompositionLocalProvider
import cc.slokker.itv.ui.i18n.LocalStrings
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import cc.slokker.itv.data.HomeScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.Screen
import cc.slokker.itv.ui.activation.ActivationScreen
import cc.slokker.itv.ui.common.Logo
import cc.slokker.itv.ui.common.LocalChannelLogos
import cc.slokker.itv.ui.common.Toast
import cc.slokker.itv.ui.loading.LoadingScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import cc.slokker.itv.ui.loading.GoatIntro
import cc.slokker.itv.ui.loading.GoatLoading
import cc.slokker.itv.ui.player.PlayerScreen
import cc.slokker.itv.ui.settings.SettingsScreen
import cc.slokker.itv.ui.welcome.WelcomeScreen
import cc.slokker.itv.ui.theme.ItvColors
import cc.slokker.itv.ui.theme.ItvTheme

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("test_mode", false)) {
            (application as ItvApp).container.testMode = true
        }
        // Aberta a partir do ecrã inicial (um canal da linha da GoatTV ou do "A seguir").
        if (savedInstanceState == null) openLink(intent)
        setContent {
            ItvTheme { ItvRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        HomeScreen.parseLink(intent?.data)?.let { (playlist, channel) -> vm.openLink(playlist, channel) }
    }
}

@Composable
private fun ItvRoot(vm: AppViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val strings by vm.strings.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val channelLogos by vm.channelLogos.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val api = (LocalContext.current.applicationContext as ItvApp).container.backend

    // Ao voltar à app, apanha listas/favoritos alterados no site ou noutra TV.
    LifecycleStartEffect(vm) {
        vm.onAppStart()
        vm.refreshConfig()
        onStopOrDispose { }
    }

    // Escala da interface (Definições → Aspeto): muda o tamanho de tudo, como se o ecrã tivesse outra densidade.
    val baseDensity = LocalDensity.current
    val scaled = remember(baseDensity, prefs.uiScale) {
        Density(baseDensity.density * prefs.uiScale / 100f, baseDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalAccent provides Accent.byId(prefs.accent),
        LocalDensity provides scaled,
        LocalChannelLogos provides channelLogos,
    ) {
    // A abertura passa uma vez por arranque (sobrevive a rodar/recriar a atividade); qualquer tecla salta-a.
    var introDone by rememberSaveable { mutableStateOf(false) }
    // A tecla que salta a abertura é consumida inteira (também o largar), para não fazer nada no ecrã de baixo.
    var skipKey by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(ItvColors.Background)
            .onPreviewKeyEvent { e ->
                when {
                    !introDone -> {
                        introDone = true
                        skipKey = e.type == KeyEventType.KeyDown
                        true
                    }
                    skipKey && e.type == KeyEventType.KeyUp -> {
                        skipKey = false
                        true
                    }
                    else -> false
                }
            },
    ) {
        when (val s = screen) {
            // O mesmo logo da abertura e do carregamento, parado: a abertura acaba exatamente aqui.
            Screen.Starting -> GoatLoading("")
            Screen.Welcome -> WelcomeScreen(onAccount = vm::chooseAccount, onLocal = vm::chooseLocal)
            Screen.Activation -> ActivationScreen(
                api,
                backLabel = if (local) strings.backKeyName else strings.useWithoutAccount,
                onBack = vm::leaveActivation,
                onApproved = vm::onActivated,
            )
            is Screen.Loading -> LoadingScreen(
                state = s,
                canOpenSettings = playlists.isNotEmpty(),
                onRetry = vm::retry,
                onSettings = vm::openSettings,
                // Sem conta não há sessão a terminar: as listas corrigem-se nas Definições.
                onLogout = vm::logoutThisTv.takeIf { !local },
                onAddPlaylist = vm::openAddPlaylist.takeIf { !local && playlists.isEmpty() && s.error != null },
            )
            Screen.Player -> PlayerScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
        }
        // Abertura: uma vez por arranque, por cima de tudo (o que está por baixo vai carregando). Acaba com o logo
        // parado no sítio do ecrã de carregamento, por isso, se ainda estiver a carregar, a passagem não se nota.
        AnimatedVisibility(visible = !introDone, enter = EnterTransition.None, exit = fadeOut(tween(450))) {
            GoatIntro(onDone = { introDone = true })
        }
        Toast(toast, vm::clearToast, Modifier.align(Alignment.TopCenter))
    }
    }
}
