package cc.slokker.itv

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import cc.slokker.itv.ui.theme.LocalAccent
import cc.slokker.itv.ui.theme.Accent
import androidx.compose.runtime.CompositionLocalProvider
import cc.slokker.itv.ui.i18n.LocalStrings
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.activity.viewModels
import cc.slokker.itv.data.HomeScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import cc.slokker.itv.ui.common.semanticsOnlyForScreenReaders
import cc.slokker.itv.ui.theme.outlineBrush
import cc.slokker.itv.ui.theme.LocalOutlineBrush
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
import android.content.pm.PackageManager
import cc.slokker.itv.ui.mobile.MobileLoadError
import cc.slokker.itv.ui.mobile.MobileMain
import cc.slokker.itv.ui.mobile.MobileMenu
import cc.slokker.itv.ui.mobile.MobileWelcome
import cc.slokker.itv.ui.mobile.PlaylistFormPage
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
        if (BuildConfig.DEBUG && intent.getBooleanExtra("no_video", false)) {
            (application as ItvApp).container.noVideo = true
        }
        // Testes no emulador: entra logo com uma lista de demonstração, sem conta.
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            intent.getStringExtra("demo_m3u")?.let { url ->
                lifecycleScope.launch { vm.savePlaylist(null, PlaylistForm(type = PlaylistType.M3U, name = "Demo", url = url)) }
            }
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

    /** Está na janela pequena: só se mostra o vídeo. */
    var inPip by mutableStateOf(false)
        private set
    private var pipOnLeave = false

    val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** O telemóvel diz se, ao sair da app, o vídeo passa para a janela pequena. */
    fun setPipOnLeave(enabled: Boolean) {
        pipOnLeave = enabled
        if (pipSupported) setPictureInPictureParams(pipParams(enabled))
    }

    fun enterPip() {
        if (pipSupported) enterPictureInPictureMode(pipParams(pipOnLeave))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(auto: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(auto)
        }.build()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // A partir do Android 12 o sistema entra sozinho.
        if (pipOnLeave && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
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
    // Telemóveis e tablets têm interface de toque; as TVs (leanback) a do comando.
    val activity = LocalContext.current as? ComponentActivity
    val phone = remember {
        val forced = BuildConfig.DEBUG && activity?.intent?.getBooleanExtra("phone_ui", false) == true
        forced || activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == false
    }

    // Ao voltar à app, apanha listas/favoritos alterados no site ou noutra TV.
    LifecycleStartEffect(vm) {
        vm.onAppStart()
        vm.refreshConfig()
        onStopOrDispose { }
    }

    // Escala da interface (Definições → Aspeto): muda o tamanho de tudo, como se o ecrã tivesse outra densidade.
    val baseDensity = LocalDensity.current
    val scaled = remember(baseDensity, prefs.uiScale) {
        Density(baseDensity.density * (if (phone) 100 else prefs.uiScale) / 100f, baseDensity.fontScale)
    }
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalAccent provides Accent.byId(prefs.accent),
        LocalOutlineBrush provides outlineBrush(prefs.outlineColor, Accent.byId(prefs.accent)),
        LocalDensity provides scaled,
        LocalChannelLogos provides channelLogos,
    ) {
    // A abertura passa uma vez por arranque (sobrevive a rodar/recriar a atividade); qualquer tecla salta-a.
    var introDone by rememberSaveable { mutableStateOf(vm.noVideo) }
    // A tecla que salta a abertura é consumida inteira (também o largar), para não fazer nada no ecrã de baixo.
    var skipKey by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .semanticsOnlyForScreenReaders()
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
            Screen.Welcome -> if (phone) {
                MobileWelcome(onAccount = vm::chooseAccount, onLocal = vm::chooseLocal)
            } else {
                WelcomeScreen(onAccount = vm::chooseAccount, onLocal = vm::chooseLocal)
            }
            Screen.Activation -> ActivationScreen(
                api,
                backLabel = if (local) strings.backKeyName else strings.useWithoutAccount,
                onBack = vm::leaveActivation,
                onApproved = vm::onActivated,
                phone = phone,
            )
            is Screen.Loading -> if (phone && s.error != null) {
                MobileLoadError(
                    s.error,
                    onRetry = vm::retry,
                    onSettings = vm::openSettings.takeIf { playlists.isNotEmpty() },
                    onLogout = vm::logoutThisTv.takeIf { !local },
                    onAddPlaylist = vm::openAddPlaylist.takeIf { !local && playlists.isEmpty() },
                )
            } else LoadingScreen(
                state = s,
                canOpenSettings = playlists.isNotEmpty(),
                onRetry = vm::retry,
                onSettings = vm::openSettings,
                // Sem conta não há sessão a terminar: as listas corrigem-se nas Definições.
                onLogout = vm::logoutThisTv.takeIf { !local },
                onAddPlaylist = vm::openAddPlaylist.takeIf { !local && playlists.isEmpty() && s.error != null },
            )
            Screen.Player -> if (phone) MobileMain(vm) else PlayerScreen(vm)
            Screen.Settings -> when {
                !phone -> SettingsScreen(vm)
                playlists.isEmpty() -> PlaylistFormPage(vm, onDone = vm::closeSettings)
                else -> Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { MobileMenu(vm) }
            }
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
