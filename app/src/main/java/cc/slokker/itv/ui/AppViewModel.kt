package cc.slokker.itv.ui

import cc.slokker.itv.data.AppRelease
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.HomeItem
import cc.slokker.itv.data.PhoneChannelList
import cc.slokker.itv.data.PinAttempts
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.data.SafeInput
import cc.slokker.itv.data.Updater
import cc.slokker.itv.IPTV_USER_AGENT
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.data.api.EpgResult
import cc.slokker.itv.ui.state.GuideController
import cc.slokker.itv.ui.state.ParentalControl
import cc.slokker.itv.ui.state.PlaybackState
import cc.slokker.itv.ui.state.PlaylistController
import cc.slokker.itv.ui.state.ProfileStore
import cc.slokker.itv.ui.state.Reminders
import cc.slokker.itv.ui.common.explainPlaylistError
import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.slokker.itv.ItvApp
import cc.slokker.itv.data.Audience
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.ChannelMatch
import cc.slokker.itv.ui.player.engine.BackupStream
import cc.slokker.itv.ui.player.engine.PlaybackRequest
import kotlinx.coroutines.withTimeoutOrNull
import cc.slokker.itv.data.ChannelCatalog
import cc.slokker.itv.data.GroupNames
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.kind
import cc.slokker.itv.data.sharesConnectionsWith
import cc.slokker.itv.data.AccountProfile
import cc.slokker.itv.data.LocalProfile
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileRepository
import cc.slokker.itv.data.TestModeProfile
import cc.slokker.itv.data.ProfileKeys.string
import cc.slokker.itv.data.RefreshTracker
import cc.slokker.itv.data.SessionStore
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.data.api.BackendDevice
import cc.slokker.itv.data.api.BackendUser
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.api.EpgMapping
import cc.slokker.itv.ui.player.NetMonitor
import cc.slokker.itv.ui.player.engine.FreshLinks
import cc.slokker.itv.ui.player.engine.StalkerLinks
import cc.slokker.itv.data.api.accountBody
import cc.slokker.itv.data.api.toPlaylist
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.BugReport
import retrofit2.HttpException
import java.io.IOException
import cc.slokker.itv.data.at
import cc.slokker.itv.data.after
import cc.slokker.itv.data.isOn
import cc.slokker.itv.data.recording.RecordingInfo
import cc.slokker.itv.data.recording.RecordingPlan
import cc.slokker.itv.data.recording.RecordingReason
import cc.slokker.itv.data.recording.RecordingState
import cc.slokker.itv.data.recording.Schedule
import cc.slokker.itv.data.recording.ScheduledRecording
import cc.slokker.itv.data.recording.SeriesRule
import cc.slokker.itv.recording.RecordingManager
import cc.slokker.itv.ui.player.ChannelFeed

/** Estado das atualizações da app. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** O envio de um relatório (Definições → Conta). */
sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data class Sent(val id: String) : ReportState
    data class Failed(val message: String) : ReportState
}

sealed interface Screen {
    data object Starting : Screen
    /** Primeira vez: conta GOAT (recomendada) ou listas só nesta TV. */
    data object Welcome : Screen
    data object Activation : Screen
    data class Loading(val message: String, val error: ErrorInfo? = null) : Screen
    data object Player : Screen
    data object Settings : Screen
}

/**
 * Estado da app e fluxo entre ecrãs: Boas-vindas → Ativação (conta) ou listas nesta TV → Carregar → Player
 * (com painéis de canais e grupos).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val c = (app as ItvApp).container

    /** A ligação do canal a tocar (página Ligação, gráfico no vídeo e aviso de paragens). */
    val net = NetMonitor(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    /** O nome desta TV na conta (thegoat.systems/conta → Dispositivos). */
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    private var userId: String? = null

    /**
     * Sem conta: listas, favoritos e definições ficam só nesta TV (LocalStore), e as listas gerem-se aqui.
     * Com conta (false), vêm de thegoat.systems e são iguais em todos os dispositivos.
     */
    private val _local = MutableStateFlow(false)
    val local: StateFlow<Boolean> = _local.asStateFlow()

    /** As Definições abrem já no formulário de lista nova (primeira lista sem conta). */
    var settingsStartAtNewPlaylist = false
        private set

    /** Definições e favoritos desta TV, a passar para a conta que se acabou de ligar (ver adoptLocalProfile). */
    private var pendingLocalProfile: ConfigResponse? = null

    /** Listas, favoritos, recentes e definições, e a gravação deles (ver ProfileStore). */
    private val profile = ProfileStore(
        viewModelScope, c.gson,
        repository = ::repository,
        generation = { sessionGeneration },
        onSaveFailed = { code -> showToast(if (code == null) I18n.s.saveOffline else I18n.s.saveFailed(code)) },
        onLanguage = Strings::apply,
    )

    val playlists: StateFlow<List<Playlist>> = profile.playlists

    private val localProfile = LocalProfile(c.local)
    private val accountProfile = AccountProfile(c.backend, c.cache)
    private val testModeProfile = TestModeProfile(c.backend, c.cache)

    /**
     * Onde vive o perfil agora. Sem conta, ou ainda sem sessão (a primeira lista nas boas-vindas): nesta TV. Com conta:
     * na conta, ou, no modo de teste, numa conta só de leitura.
     */
    private fun repository(): ProfileRepository = when {
        _local.value || c.token == null -> localProfile
        c.testMode -> testModeProfile
        else -> accountProfile
    }

    /** Listas e guias já verificados desde que a app abriu, e a última tentativa de cada (ver RefreshTracker). */
    private val refreshes = RefreshTracker()

    /** Os canais da lista aberta, a renovação por trás e as listas do segundo canal (ver PlaylistController). */
    private val lists = PlaylistController(
        viewModelScope, c::loadPlaylist, c.cache, c.backend, profile, ::repository, refreshes, profile.prefs, ::showToast,
        onRefreshed = { fresh, guideChanged ->
            if (guideChanged) guide.open(fresh)
            playback.onContentRefreshed(fresh)
        },
    )

    val content: StateFlow<PlaylistContent?> = lists.content

    /** `favorites` da conta: playlistId → channelKeys. */
    val favorites: StateFlow<Map<String, List<String>>> = profile.favorites

    /** O formato que funcionou nas Xtream (null = Auto). */
    val streamFormat: StateFlow<String?> = profile.streamFormat

    val prefs: StateFlow<Prefs> = profile.prefs

    /** Textos na língua da app (a escolhida, ou a do sistema). Mudam quando a língua muda. */
    val strings: StateFlow<Strings> = prefs
        .map { Strings.of(getApplication()).also { s -> I18n.s = s } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, I18n.s)

    init {
        // Preferências que vivem fora do Compose: User-Agent dos pedidos e cópia local do arranque automático.
        viewModelScope.launch {
            prefs.collect { p ->
                c.userAgent = SafeInput.headerSafe(p.userAgent).ifBlank { IPTV_USER_AGENT }
                if (content.value != null) c.session.setAutoStart(p.autoStart)
            }
        }
    }

    /** Grupos bloqueados já desbloqueados com o PIN nesta sessão. */
    val unlocked: StateFlow<Set<String>> get() = parental.unlocked

    /** O canal a tocar, o grupo do zapping, o anterior, o catch-up e o segundo canal (ver PlaybackState). */
    private val playback = PlaybackState(::catalog, ::catalogFor, onTuned = ::scheduleSaveLast)

    /** Grupo do canal a ver: é por ele que CH+/CH− navegam e é o que o painel de canais abre. */
    val selectedCategory: StateFlow<String> = playback.selectedCategory

    val playing: StateFlow<Channel?> = playback.playing

    /** Últimos canais vistos (`settings.recent_channels`): playlistId → channelKeys, o mais recente primeiro. */
    val recents: StateFlow<Map<String, List<String>>> = profile.recents

    /** Programa a ver em catch-up (null = em direto). */
    val catchup: StateFlow<Catchup?> = playback.catchup

    /** Hora a que o temporizador para a app (null = desligado). Só nesta TV, até a app fechar. */
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()

    /** Lembretes de programas (só nesta TV). */
    private val reminderList = Reminders(viewModelScope, c.cache, { content.value?.playlist?.id }, ::showToast)
    val reminders: StateFlow<List<Reminder>> = reminderList.reminders

    /** Segundo canal no ecrã (imagem na imagem / dividido). Pode ser de outra lista (outro fornecedor). */
    val second: StateFlow<PlaybackState.SecondView?> = playback.second

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** Versão nova encontrada sozinha, por avisar: na TV um cartão no player, no telemóvel um aviso curto. */
    private val _updateNotice = MutableStateFlow<AppRelease?>(null)
    val updateNotice: StateFlow<AppRelease?> = _updateNotice.asStateFlow()
    private var lastUpdateCheck = 0L

    private val _report = MutableStateFlow<ReportState>(ReportState.Idle)
    val report: StateFlow<ReportState> = _report.asStateFlow()

    /** Canal pedido por um link do ecrã inicial, à espera de a lista carregar: (playlistId, channelKey). */
    private var pendingLink: Pair<String, String>? = null

    /** O PIN, os grupos desbloqueados nesta sessão e as Definições desbloqueadas (ver ParentalControl). */
    private val parental = ParentalControl(
        prefs, profile::setPref,
        attempts = {
            val store = getApplication<Application>().getSharedPreferences("pin_attempts", Context.MODE_PRIVATE)
            PinAttempts(store.getInt("failures", 0), store.getLong("last_failure_at", 0L)) { failures, at ->
                store.edit { putInt("failures", failures).putLong("last_failure_at", at) }
            }
        },
        onLocksChanged = { publishHome(locksChanged = true) },
    )

    /** O guia: XMLTV da lista e o extra, o da conta e as associações, o desvio horário e os logótipos (ver GuideController). */
    private val guide = GuideController(viewModelScope, c.epg, c.backend, content, prefs, ::repository, refreshes, ::showToast)

    /** Canais da lista atual associados a um canal de guia da conta (channelKey → associação). */
    val epgMappings: StateFlow<Map<String, EpgMapping>> = guide.mappings

    /** Os logótipos que substituem os da lista (channelKey → URL), na lista em uso (ver GuideController.channelLogos). */
    val channelLogos: StateFlow<Map<String, String>> = guide.channelLogos

    /** Muda quando chega guia novo: a grelha e as listas voltam a ler. */
    val epgVersion: StateFlow<Int> get() = guide.version

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var retryAction: () -> Unit = { startLoading(resume = true) }
    private var loadJob: Job? = null
    private var saveLastJob: Job? = null
    /** Sobe ao terminar sessão: o que ainda vinha a caminho vê que já não é desta sessão e não grava nada. */
    private var sessionGeneration = 0

    private var startedOnce = false

    /** Último canal à espera de ser gravado: (playlistId, channelKey). */
    private var pendingLast: Pair<String, String>? = null

    init {
        viewModelScope.launch {
            c.sessionEnded.collect {
                // Modo de teste: segue com a cache (a sessão de teste é falsa e o servidor recusa-a).
                if (_screen.value != Screen.Activation && !c.testMode) {
                    resetToActivation()
                    showToast(I18n.s.sessionEnded)
                }
            }
        }
        // Com a app aberta horas a fio, as listas e o guia também se renovam pelo intervalo escolhido.
        // Uma instalação que o Android recusou: fica o motivo nas Definições (e dá para tentar outra vez).
        viewModelScope.launch {
            c.updater.installFailures.collect { _update.value = UpdateState.Failed(it) }
        }
        // Atualizações da app: pouco depois de abrir e depois de 12 em 12 horas.
        viewModelScope.launch {
            delay(UPDATE_FIRST_CHECK_MS)
            while (true) {
                if (System.currentTimeMillis() - lastUpdateCheck >= UPDATE_INTERVAL_MS) checkForUpdate(manual = false)
                delay(REFRESH_TICK_MS)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(REFRESH_TICK_MS)
                val content = content.value ?: continue
                if (_screen.value == Screen.Player || _screen.value == Screen.Settings) {
                    lists.maybeRefresh(content)
                    guide.open(content)
                }
            }
        }
        viewModelScope.launch {
            reminderList.load()
        }
        viewModelScope.launch {
            val session = c.session.load()
            when {
                session != null -> {
                    c.token = session.token
                    userId = session.userId
                    _email.value = session.email
                    startLoading(resume = true)
                }
                c.session.isLocal() -> {
                    _local.value = true
                    startLoading(resume = true)
                }
                else -> _screen.value = Screen.Welcome
            }
        }
    }

    // ---- Ativação e carregamento -------------------------------------------------------------

    fun onActivated(token: String, user: BackendUser?, device: BackendDevice?) {
        val id = user?.id ?: return
        val deviceId = device?.id ?: return
        viewModelScope.launch {
            if (_local.value) leaveLocal()
            c.session.save(SessionStore.Session(token, id, user.email, deviceId))
            _deviceName.value = device.name
            c.token = token
            userId = id
            _email.value = user.email
            startLoading(resume = true)
        }
    }

    fun retry() = retryAction()

    /** Boas-vindas → "Conta GOAT": mostra o código para ativar esta TV. */
    fun chooseAccount() {
        _screen.value = Screen.Activation
    }

    /** Sem conta → "Ligar conta": ativa a TV; as definições desta TV passam para a conta. */
    fun connectAccount() {
        flushLastChannel()
        _screen.value = Screen.Activation
    }

    /** Voltar na ativação: regressa às listas desta TV (se as havia) ou às boas-vindas. */
    fun leaveActivation() {
        when {
            !_local.value -> _screen.value = Screen.Welcome
            content.value != null && playback.playing.value != null -> _screen.value = Screen.Player
            else -> startLoading(resume = true)
        }
    }

    /**
     * Boas-vindas → "Listas nesta TV": se já há listas guardadas nesta TV (usou-se sem conta antes), abre-as;
     * senão abre o formulário da primeira lista.
     */
    fun chooseLocal() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val stored = c.local.load()
            if (stored.playlists.orEmpty().isNotEmpty()) {
                enterLocal()
                startLocal(stored, resume = true)
            } else {
                settingsStartAtNewPlaylist = true
                _screen.value = Screen.Settings
            }
        }
    }

    /** Passa a usar listas desta TV (sem conta). */
    private suspend fun enterLocal() {
        _local.value = true
        c.session.setLocal(true)
    }

    /**
     * A TV sem conta liga-se a uma conta: guarda o perfil desta TV para a conta e esquece o estado das listas
     * locais (ficam no LocalStore, para voltar a elas se se terminar a sessão).
     */
    private suspend fun leaveLocal() {
        cancelBackgroundJobs()
        pendingLocalProfile = profile.snapshot() ?: c.local.load()
        c.session.setLocal(false)
        resetState(Reset.PROFILE)
    }

    private fun startLoading(resume: Boolean) {
        retryAction = { startLoading(resume) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (_local.value) {
                // Em memória se já foi lida (acabou de se gravar uma lista), senão do disco.
                startLocal(profile.lastConfig ?: c.local.load(), resume)
                return@launch
            }
            // Arranque rápido: com a config e a lista em cache, abre logo e atualiza por trás.
            if (resume && startFromCache()) return@launch

            _screen.value = Screen.Loading(I18n.s.loadingSession)
            try {
                if (!renewToken()) return@launch

                _screen.value = Screen.Loading(I18n.s.loadingPlaylists)
                val config = fetchConfig(null) ?: return@launch
                c.cache.saveConfig(config)
                applyAccountConfig(config, keepSettingsIfMissing = false)

                val playlists = playlists.value
                if (playlists.isEmpty()) {
                    _screen.value = Screen.Loading("", I18n.s.errNoPlaylists())
                    return@launch
                }
                loadPlaylist(lastPlaylist(), resume)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() != 401) {
                    _screen.value = Screen.Loading("", I18n.s.errBackend(e.code()))
                }
                // 401: o interceptor já avisou e voltamos à ativação.
            } catch (e: Exception) {
                _screen.value = Screen.Loading("", I18n.s.errBackendOffline())
            }
        }
    }

    /** Sem conta: abre a última lista desta TV; sem nenhuma, volta às boas-vindas. */
    private suspend fun startLocal(config: ConfigResponse, resume: Boolean) {
        applyConfig(config)
        if (playlists.value.isEmpty()) {
            _screen.value = Screen.Welcome
            return
        }
        loadPlaylist(lastPlaylist(), resume)
    }

    /**
     * Acabou de se ligar a conta numa TV que estava sem conta. As definições desta TV passam para a conta
     * se a conta ainda não tiver nenhumas; os favoritos passam para as listas da conta que são as mesmas
     * (mesmo servidor e utilizador, ou mesmo URL) e ainda não têm favoritos. As listas em si não passam sozinhas
     * (o login da TV pode criá-las na conta, mas só quando a pessoa as adiciona).
     */
    private fun adoptLocalProfile(local: ConfigResponse) {
        if (profile.adoptLocalProfile(local)) showToast(I18n.s.localProfileMoved)
    }

    /** Abre com a config e a lista guardadas; a sessão e a config renovam-se por trás. */
    private suspend fun startFromCache(): Boolean {
        val config = c.cache.loadConfig() ?: return false
        applyConfig(config)
        if (playlists.value.isEmpty()) return false
        val playlist = lastPlaylist()
        if (!lists.hasCached(playlist)) return false
        loadPlaylist(playlist, resume = true)
        viewModelScope.launch {
            if (renewToken()) syncConfig()
        }
        return true
    }

    /**
     * Renova o token (dura 90 dias; cada refresh renova-o). Sempre ao arrancar.
     * Devolve false só se a sessão foi terminada (401); sem rede segue com o token que há.
     */
    private suspend fun renewToken(): Boolean {
        val generation = sessionGeneration
        try {
            val token = c.backend.authRefresh().token
            if (generation != sessionGeneration) return false
            token?.let {
                c.token = it
                c.session.updateToken(it)
            }
        } catch (e: HttpException) {
            if (generation != sessionGeneration) return false
            when (e.code()) {
                401 -> return false // o interceptor já mandou para a ativação
                // O dispositivo já não existe na conta (desligado no site, ou "Terminar todas as sessões").
                404 -> {
                    if (_screen.value != Screen.Activation && !c.testMode) {
                        resetToActivation()
                        showToast(I18n.s.sessionEnded)
                    }
                    return false
                }
            }
        } catch (e: IOException) {
            // Sem rede agora; o pedido seguinte decide.
        }
        return generation == sessionGeneration
    }

    /** A config da conta; null quando [etag] ainda é o atual (304). Erros HTTP saem como HttpException. */
    private suspend fun fetchConfig(etag: String?): ConfigResponse? {
        val generation = sessionGeneration
        val res = c.backend.config(etag)
        if (generation != sessionGeneration) return null
        if (res.code() == 304) {
            Log.i(TAG, "Config: sem alterações (304)")
            return null
        }
        val config = res.body() ?: throw HttpException(res)
        profile.configEtag = res.headers()["ETag"]
        return config
    }

    /**
     * Passa a usar [config]: a conta (utilizador, nome desta TV) e o perfil. O mesmo ao arrancar e ao reler a conta
     * ([keepSettingsIfMissing], ver ProfileStore.applyConfig); com o bloqueio mudado, o ecrã inicial atualiza-se.
     */
    private fun applyConfig(config: ConfigResponse, keepSettingsIfMissing: Boolean = false) {
        config.user?.id?.let { userId = it }
        config.user?.email?.let { _email.value = it }
        config.device?.name?.let { _deviceName.value = it }
        if (profile.applyConfig(config, keepSettingsIfMissing)) publishHome(locksChanged = true)
    }

    /**
     * Uma config que acabou de chegar da conta. Se a TV acabou de passar de sem conta para conta, o perfil desta TV
     * junta-se agora, venha a config do arranque ou de reler a conta (o arranque pela cache só relê).
     */
    private fun applyAccountConfig(config: ConfigResponse, keepSettingsIfMissing: Boolean) {
        applyConfig(config, keepSettingsIfMissing)
        pendingLocalProfile?.let { localProfile ->
            pendingLocalProfile = null
            adoptLocalProfile(localProfile)
        }
    }

    /** O canal mudou aqui depois do que a conta diz (a gravação não chegou, ou a config é antiga): vale o daqui. */
    private suspend fun adoptNewerLastChannel() {
        val at = c.session.lastAt()
        val saved = profile.lastChannelAt
        val playlistId = c.session.lastPlaylist()
        val channelKey = c.session.lastChannel()
        if (at <= saved || playlistId == null || channelKey == null) return
        profile.setLastChannel(playlistId, channelKey, at)
    }

    private suspend fun lastPlaylist(): Playlist {
        // Um link do ecrã inicial manda na lista a abrir (se ainda existir).
        pendingLink?.first?.let { id -> playlists.value.firstOrNull { it.id == id }?.let { return it } }
        adoptNewerLastChannel()
        val lastId = profile.lastPlaylistId ?: c.session.lastPlaylist()
        return playlists.value.firstOrNull { it.id == lastId } ?: playlists.value.first()
    }

    /**
     * Mostra [playlist]. Se estiver em cache abre logo e atualiza os canais por trás;
     * senão mostra "A carregar" enquanto a descarrega.
     */
    private suspend fun loadPlaylist(playlist: Playlist, resume: Boolean) {
        retryAction = { loadJob = viewModelScope.launch { loadPlaylist(playlist, resume) } }
        lists.cached(playlist)?.let { cached ->
            showContent(cached, resume)
            lists.maybeRefresh(cached)
            return
        }
        _screen.value = Screen.Loading(I18n.s.loadingPlaylist(playlist.name))
        val loaded = try {
            lists.download(playlist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _screen.value = Screen.Loading("", explainPlaylistError(I18n.s, playlist.name, e))
            return
        }
        showContent(loaded, resume)
    }

    /** Guia XMLTV extra de [playlistId] ([url] vazio ou null tira-o); na lista em uso, descarrega-se já. */
    fun setExtraEpg(playlistId: String, url: String?) {
        val clean = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val current = prefs.value.extraEpg
        setPref(Prefs.Key.EXTRA_EPG, if (clean == null) current - playlistId else current + (playlistId to clean))
        content.value?.takeIf { it.playlist.id == playlistId }?.let { guide.openExtra(it, force = true) }
    }

    /** A app voltou ao primeiro plano: conta como "abrir a app" para as atualizações. */
    fun onAppStart() {
        if (!startedOnce) {
            startedOnce = true
            return
        }
        refreshes.clearChecked()
        guide.forgetMappings() // as associações podem ter mudado noutra TV
        val content = content.value ?: return
        if (loadJob?.isActive == true) return
        lists.maybeRefresh(content)
        guide.open(content)
    }

    private suspend fun showContent(loaded: PlaylistContent, resume: Boolean) {
        val playlist = loaded.playlist
        playback.second.value?.let { sv ->
            // A lista principal passou a ser a da conta do segundo canal, e o plano só tem 1 ligação.
            val twoAllowed = !playlist.type.kind.knowsConnectionLimit || (loaded.account?.maxConnections ?: 0) >= 2
            if (sv.content.playlist.sharesConnectionsWith(playlist) && !twoAllowed) {
                playback.closeSecond()
                showToast(I18n.s.secondClosedSameAccount)
            }
        }
        guide.clearShort()
        guide.open(loaded)
        lists.show(loaded)
        playback.clearForNewContent()
        if (loaded.channels.isEmpty()) {
            _screen.value = Screen.Loading("", I18n.s.errPlaylistEmpty(playlist.name))
            return
        }

        // Aberta por um link do ecrã inicial: vai para esse canal.
        pendingLink?.takeIf { it.first == playlist.id }?.let { (_, key) ->
            pendingLink = null
            loaded.byKey[key]?.takeIf { !isGroupLocked(it.categoryId) }?.let { channel ->
                play(channel, channel.categoryId)
                return
            }
        }

        // Uma gravação deste canal a decorrer (a app foi aberta a meio): abre nele, que é a ligação que já está aberta.
        activeRecordings.value.firstOrNull { it.playlistId == playlist.id }?.channelKey?.let { loaded.byKey[it] }?.let { channel ->
            playback.playIn(channel, channel.categoryId)
            _screen.value = Screen.Player
            return
        }

        if (resume) {
            val lastKey = if (profile.lastPlaylistId == playlist.id) {
                profile.lastChannelKey
            } else {
                c.session.lastChannel().takeIf { c.session.lastPlaylist() == playlist.id }
            }
            val channel = lastKey?.let { loaded.byKey[it] }?.takeIf { !isGroupLocked(it.categoryId) }
            if (channel != null) {
                playback.openPanelOnStart(prefs.value.startup == Prefs.Startup.LIST)
                play(channel, channel.categoryId)
                return
            }
        }
        // Nada para retomar: começa pelo primeiro canal e mostra a lista para escolher.
        val first = channelsOf(SpecialCategory.ALL).firstOrNull() ?: loaded.channels.firstOrNull { !isGroupLocked(it.categoryId) }
        if (first == null) {
            // Tudo bloqueado: nunca abre um canal assim. As Definições (com o PIN) ou outra lista resolvem.
            _screen.value = Screen.Loading("", I18n.s.errAllLocked(playlist.name))
            return
        }
        playback.openPanelOnStart(true)
        play(first, SpecialCategory.ALL)
    }

    // ---- Listas ----------------------------------------------------------------------------
    // Com conta, as listas são da conta (iguais em todas as TVs): gerem-se no site (recomendado) ou aqui.
    // Sem conta, gerem-se aqui e ficam nesta TV.

    /**
     * Cria ([id] null) ou altera uma lista: na conta, ou nesta TV sem conta. Devolve a mensagem a mostrar
     * (campo em falta, ou erro ao gravar na conta), ou null se gravou.
     */
    suspend fun savePlaylist(id: String?, form: PlaylistForm): String? {
        form.problem()?.let { return it }
        val account = repository() as? AccountProfile
        if (account == null) {
            saveLocalPlaylist(id, form)
            return null
        }
        account.refusal?.let { return it }
        // Só a UI escondia o Stalker com conta: aqui também não passa (ver PlaylistKind.supportsAccount).
        if (!form.type.kind.supportsAccount) return I18n.s.playlistRejected
        val saved = try {
            val body = form.toPlaylist(id.orEmpty()).accountBody()
            if (id == null) account.createPlaylist(body) else account.updatePlaylist(id, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return playlistError(e)
        }
        val playlist = saved.toPlaylist() ?: return I18n.s.playlistRejected
        // Lê as listas da conta (uma editada em uso recarrega com os dados novos; ver syncConfig).
        syncConfig()
        when {
            content.value == null -> {
                profile.setLastPlaylist(playlist.id)
                startLoading(resume = false)
            }
            // A lista nova passa a ser a que se vê.
            id == null -> switchPlaylist(playlist)
        }
        return null
    }

    /** Apaga uma lista: da conta (em todas as TVs) ou desta TV. Devolve o erro a mostrar, ou null. */
    suspend fun deletePlaylist(playlist: Playlist): String? {
        val account = repository() as? AccountProfile
        if (account == null) {
            deleteLocalPlaylist(playlist)
            return null
        }
        account.refusal?.let { return it }
        try {
            account.deletePlaylist(playlist.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() != 404) return playlistError(e) // 404: já não existia
        } catch (e: Exception) {
            return playlistError(e)
        }
        c.cache.removePlaylist(playlist.id)
        lists.forget(playlist.id)
        syncConfig()
        // Era a última: mostra o aviso de conta sem listas (com o botão para adicionar).
        if (playlists.value.isEmpty()) {
            resetState(Reset.PLAYLISTS)
            startLoading(resume = false)
        }
        return null
    }

    /** A mensagem para um erro ao gravar uma lista na conta, pelo `code`/`params` do servidor (que vem em inglês). */
    private fun playlistError(e: Exception): String {
        if (e !is HttpException) return I18n.s.saveOffline
        val s = I18n.s
        val body = runCatching { e.response()?.errorBody()?.string()?.let { JsonParser.parseString(it).asJsonObject } }.getOrNull()
        val code = body?.string("code")
        val params = body?.get("params") as? JsonObject
        return when {
            code == "playlist_invalid" -> when (params?.string("reason")) {
                "username_required" -> s.missingUsername
                "password_required" -> s.missingPassword
                "m3u_required" -> s.missingUrl
                "server_invalid" -> s.playlistServerInvalid
                "m3u_invalid" -> s.playlistUrlInvalid
                "epg_invalid" -> s.playlistEpgInvalid
                "name_too_long" -> s.playlistNameTooLong
                "type_change" -> s.playlistTypeChange
                else -> s.playlistRejected
            }
            code == "address_blocked" -> s.playlistAddressBlocked
            code == "playlist_limit" -> s.playlistLimit(params?.get("max")?.takeIf { it.isJsonPrimitive }?.asInt ?: 20)
            e.code() == 404 -> s.playlistGone
            e.code() == 403 -> s.playlistForbidden
            e.code() == 429 -> s.tooManyRequests
            else -> s.saveFailed(e.code())
        }
    }

    /** Conta sem listas: abre já o formulário da primeira (o botão do aviso "Ainda não tens listas"). */
    fun openAddPlaylist() {
        settingsStartAtNewPlaylist = true
        _screen.value = Screen.Settings
    }

    /**
     * Sem conta: cria ([id] null) ou altera uma lista desta TV. A primeira lista põe a TV em modo sem conta e abre-a.
     */
    private fun saveLocalPlaylist(id: String?, form: PlaylistForm) {
        val playlist = form.toPlaylist(id ?: newLocalId())
        val first = !_local.value || content.value == null
        if (first) _screen.value = Screen.Loading(I18n.s.loadingPlaylist(playlist.name))
        viewModelScope.launch {
            if (!_local.value || profile.lastConfig == null) {
                // Definições e favoritos de quando já se usou a TV sem conta continuam lá.
                applyConfig(c.local.load())
                enterLocal()
            }
            profile.upsertPlaylist(playlist)
            when {
                first -> {
                    profile.setLastPlaylist(playlist.id)
                    startLoading(resume = false)
                }
                // A lista nova passa a ser a que se vê; a editada em uso recarrega com os dados novos.
                id == null -> switchPlaylist(playlist)
                id == content.value?.playlist?.id -> switchPlaylist(playlist, force = true)
            }
        }
    }

    /**
     * Sem conta: apaga uma lista desta TV (e os favoritos, os recentes, as definições e os guias dela). Sem listas,
     * volta às boas-vindas.
     */
    private fun deleteLocalPlaylist(playlist: Playlist) {
        val remaining = profile.removeLocalPlaylist(playlist.id)
        lists.forget(playlist.id)
        viewModelScope.launch { c.cache.removePlaylist(playlist.id) }
        when {
            remaining.isEmpty() -> {
                resetState(Reset.PLAYLISTS)
                _screen.value = Screen.Welcome
            }
            playlist.id == content.value?.playlist?.id -> switchPlaylist(remaining.first())
            playback.second.value?.content?.playlist?.id == playlist.id -> playback.closeSecond()
        }
    }

    /** Esquece o guia em cache; os programas são pedidos de novo. */
    fun refreshEpg() = guide.refresh()

    /** Volta a descarregar a lista atual (ex.: o fornecedor mudou canais). */
    fun reloadPlaylist() {
        val playlist = content.value?.playlist ?: return
        lists.refreshInBackground(playlist, notify = true)
    }

    /** true uma única vez depois de carregar sem canal para retomar. */
    fun consumeOpenPanelOnStart(): Boolean = playback.consumeOpenPanelOnStart()

    /**
     * Volta a ler a config da conta (listas, favoritos, definições) sem recarregar a lista atual.
     * Chamado ao voltar à app e ao abrir as Definições, para apanhar listas novas do site.
     */
    fun refreshConfig() {
        if (_local.value || content.value == null || loadJob?.isActive == true) return
        viewModelScope.launch { syncConfig() }
    }

    /** Lê a config da conta e reage a listas apagadas ou alteradas no site. */
    private suspend fun syncConfig() {
        val generation = sessionGeneration
        val config = try {
            fetchConfig(profile.configEtag) ?: return // 304: nada mudou na conta
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // sem rede: fica o que já havia
        }
        if (generation != sessionGeneration) return
        c.cache.saveConfig(config)
        if (generation != sessionGeneration) return
        applyAccountConfig(config, keepSettingsIfMissing = true)
        val playlists = profile.playlists.value
        val current = content.value?.playlist ?: return
        val updated = playlists.firstOrNull { it.id == current.id }
        when {
            // A lista atual foi apagada no site: abre outra.
            updated == null && playlists.isNotEmpty() ->
                switchPlaylist(playlists.first())
            // Mudaram o servidor/credenciais/URL: recarrega.
            updated != null && updated != current ->
                switchPlaylist(updated, force = true)
        }
    }

    fun switchPlaylist(playlist: Playlist, force: Boolean = false) {
        if (!force && playlist.id == content.value?.playlist?.id) {
            if (_screen.value == Screen.Settings) closeSettings()
            return
        }
        // Outra lista abre outro canal: com uma gravação a decorrer, pergunta primeiro.
        if (!force && activeRecordings.value.isNotEmpty() && _switchPrompt.value == null) {
            return guarded(null) { switchPlaylist(playlist, force = true) }
        }
        profile.setLastPlaylist(playlist.id)
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadPlaylist(playlist, resume = false) }
    }

    // ---- Canais e grupos ---------------------------------------------------------------------------

    /** O que se vê da lista aberta neste momento (ver ChannelCatalog). */
    private fun catalog() = catalogFor(content.value)

    /** O mesmo para outra lista (a do segundo canal): as definições, favoritos e desbloqueios são os mesmos. */
    private fun catalogFor(content: PlaylistContent?) = ChannelCatalog(content, prefs.value, favorites.value, recents.value, parental.unlocked.value)

    private fun groupNames() = I18n.s.let { GroupNames(it.favoritesStar, it.recents, it.all, it.others) }

    fun channelsOf(categoryId: String): List<Channel> = catalog().channelsOf(categoryId)

    private fun isVisible(channel: Channel): Boolean = catalog().isVisible(channel)

    // ---- Canais: esconder e reordenar (por grupo) --------------------------------------------

    fun isChannelHidden(channel: Channel): Boolean = catalog().isChannelHidden(channel)

    private fun channelOrderKey(groupId: String): String? = catalog().channelOrderKey(groupId)

    fun hasChannelOrder(groupId: String): Boolean = catalog().hasChannelOrder(groupId)

    fun orderedChannels(groupId: String, includeHidden: Boolean): List<Channel> = catalog().orderedChannels(groupId, includeHidden)

    fun setChannelHidden(channel: Channel, hidden: Boolean) {
        val playlistId = content.value?.playlist?.id ?: return
        val key = "$playlistId:${channel.key}"
        val current = prefs.value.hiddenChannels
        setPref(Prefs.Key.HIDDEN_CHANNELS, (if (hidden) current + key else current - key).toList())
    }

    /** Move um canal [delta] posições dentro do grupo (−1 = para cima). */
    fun moveChannel(groupId: String, channelKey: String, delta: Int) {
        val orderKey = channelOrderKey(groupId) ?: return
        val keys = orderedChannels(groupId, includeHidden = true).map { it.key }.toMutableList()
        val from = keys.indexOf(channelKey)
        val to = (from + delta).coerceIn(0, keys.lastIndex)
        if (from < 0 || from == to) return
        keys.add(to, keys.removeAt(from))
        setPref(Prefs.Key.CHANNEL_ORDER, prefs.value.channelOrder + (orderKey to keys))
    }

    /** O grupo volta à ordem da lista. */
    fun resetChannelOrder(groupId: String) {
        val orderKey = channelOrderKey(groupId) ?: return
        if (orderKey !in prefs.value.channelOrder) return
        setPref(Prefs.Key.CHANNEL_ORDER, prefs.value.channelOrder - orderKey)
    }

    /** Os grupos voltam à ordem da lista. */
    fun resetGroupOrder() {
        val playlistId = content.value?.playlist?.id ?: return
        if (playlistId !in prefs.value.groupOrder) return
        setPref(Prefs.Key.GROUP_ORDER, prefs.value.groupOrder - playlistId)
    }

    // ---- Grupos: esconder e reordenar ------------------------------------------------------

    fun isGroupHidden(groupId: String): Boolean = catalog().isGroupHidden(groupId)

    fun orderedGroups(includeHidden: Boolean): List<Category> = catalog().orderedGroups(includeHidden)

    fun guideGroups(): List<Category> = catalog().guideGroups(groupNames())

    fun setGroupHidden(groupId: String, hidden: Boolean) {
        val playlistId = content.value?.playlist?.id ?: return
        val key = "$playlistId:$groupId"
        val current = prefs.value.hiddenGroups
        setPref(Prefs.Key.HIDDEN_GROUPS, (if (hidden) current + key else current - key).toList())
    }

    /** Move um grupo [delta] posições (−1 = para cima). */
    fun moveGroup(groupId: String, delta: Int) {
        val playlistId = content.value?.playlist?.id ?: return
        val ids = orderedGroups(includeHidden = true).map { it.id }.toMutableList()
        val from = ids.indexOf(groupId)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        setPref(Prefs.Key.GROUP_ORDER, prefs.value.groupOrder + (playlistId to ids))
    }

    fun isGroupLockedSetting(groupId: String): Boolean = catalog().isGroupLockedSetting(groupId)

    fun isGroupLocked(groupId: String): Boolean = catalog().isGroupLocked(groupId)

    /** Confere o PIN; se estiver certo, desbloqueia [groupId] até a app fechar. */
    fun unlockGroup(groupId: String, pin: String): Boolean = parental.unlockGroup(content.value?.playlist?.id, groupId, pin)

    fun checkPin(pin: String): Boolean = parental.checkPin(pin)

    /** O que dizer quando o PIN não entrou: [wrong], ou quanto falta para se poder tentar outra vez. */
    fun pinRejected(wrong: String = I18n.s.wrongPin): String = parental.pinRejected(wrong)

    /** Terminar sessão pede o PIN, quando há um (é por aí que se saía para as listas desta TV sem ele). */
    fun logoutNeedsPin(): Boolean = parental.logoutNeedsPin()

    /** Define (ou remove, com null) o PIN parental. Sem PIN não há grupos bloqueados. */
    fun setPin(pin: String?) = parental.setPin(pin)

    fun toggleGroupLock(groupId: String) {
        val playlistId = content.value?.playlist?.id ?: return
        parental.toggleGroupLock(playlistId, groupId)
    }

    /** Muda uma definição da conta: String, Boolean, Int, lista de Strings, ou null para repor o padrão. */
    fun setPref(key: String, value: Any?) = profile.setPref(key, value)

    fun setPrefs(values: Map<String, Any?>) = profile.setPrefs(values)

    fun isFavorite(channel: Channel): Boolean = catalog().isFavorite(channel)

    /** Adiciona/remove dos favoritos e grava logo na conta. */
    fun toggleFavorite(channel: Channel) {
        val playlistId = content.value?.playlist?.id ?: return
        val added = profile.toggleFavorite(playlistId, channel.key)
        showToast(if (added) I18n.s.favoriteAdded(channel.name) else I18n.s.favoriteRemoved(channel.name))
    }

    /** Move um favorito [delta] posições (−1 = para cima). */
    fun moveFavorite(channel: Channel, delta: Int) {
        val playlistId = content.value?.playlist?.id ?: return
        profile.moveFavorite(playlistId, channel.key, delta)
    }

    suspend fun epgFor(channel: Channel): List<EpgEntry> = guide.epgFor(channel)

    fun cachedEpg(channel: Channel): List<EpgEntry> = guide.cachedEpg(channel)

    suspend fun xtreamConnections(): Pair<Int, Int>? {
        val content = content.value ?: return null
        if (!content.playlist.type.kind.knowsConnectionLimit) return null
        return c.xtream.connections(content.playlist)
    }

    // ---- Guia da conta: trocar o guia de um canal ----------------------------------------------
    // Só com conta (ver GuideController).

    /** Escolher o guia de um canal só existe com conta GOAT. */
    val epgPickerAvailable: Boolean get() = guide.pickerAvailable

    /** Países com um canal de guia com esse nome (o país da pessoa primeiro). */
    suspend fun epgSearch(query: String): EpgResult<List<EpgCountryResult>> = guide.epgSearch(query)

    /** Canais de guia prováveis para [channel] (só para mostrar como opções; nada se associa sozinho). */
    suspend fun epgSuggest(channel: Channel): EpgResult<List<EpgGuideChannel>> = guide.epgSuggest(channel)

    suspend fun epgCountries(): EpgResult<List<EpgCountryInfo>> = guide.epgCountries()

    /** A lista inteira passa a usar o guia de [country] (ver GuideController.applyEpgCountry). */
    fun applyEpgCountry(country: String, countryName: String) = guide.applyEpgCountry(country, countryName)

    /** Logótipo escolhido para [channel] na lista em uso: um URL, "" para o da lista, ou null para seguir a origem geral. */
    fun channelLogoChoice(channel: Channel): String? {
        val playlistId = content.value?.playlist?.id ?: return null
        return prefs.value.channelLogos["$playlistId:${channel.key}"]
    }

    fun setChannelLogo(channel: Channel, url: String?) {
        val playlistId = content.value?.playlist?.id ?: return
        val key = "$playlistId:${channel.key}"
        val current = prefs.value.channelLogos
        setPref(Prefs.Key.CHANNEL_LOGOS, if (url == null) current - key else current + (key to url))
    }

    /** Todos os canais da lista voltam ao guia da lista. */
    fun resetEpgMappings() = guide.resetEpgMappings()

    /** Todos os canais de guia de um país. */
    suspend fun epgCountryChannels(country: String): EpgResult<EpgCountryResult> = guide.epgCountryChannels(country)

    /** O guia de [channel] passa a ser o do canal de guia [guide] (de [country]). Grava na conta. */
    suspend fun setEpgMapping(channel: Channel, country: String, guide: EpgGuideChannel): Boolean = this.guide.setEpgMapping(channel, country, guide)

    /** [channel] volta ao guia da lista. */
    suspend fun removeEpgMapping(channel: Channel): Boolean = guide.removeEpgMapping(channel)

    // ---- Player ----------------------------------------------------------------------------

    /** Põe [channel] a tocar; CH+/CH− passam a percorrer o grupo [groupId]. */
    fun play(channel: Channel, groupId: String) {
        // Com uma gravação de outro canal a decorrer, pergunta antes de largar a ligação dela.
        guarded(channel) {
            playback.playIn(channel, groupId)
            _screen.value = Screen.Player
        }
    }

    /** Muda de canal dentro da categoria atual (dá a volta nas pontas). */
    fun zap(delta: Int) = guarded(null) { playback.zap(delta) }

    /** O último canal visto antes do atual: o da sessão, ou o dos Recentes (ex.: logo ao abrir a app). */
    fun previousChannel(): Channel? = playback.previousChannel()

    /** → no player: volta ao canal anterior (e carregar outra vez regressa). */
    fun playPrevious(): Boolean {
        val channel = previousChannel() ?: return false
        play(channel, groupFor(channel))
        return true
    }

    /**
     * Link do ecrã inicial (`itv://play?playlist=…&channel=…`): abre esse canal. Se a lista ainda não está
     * aberta (a app está a arrancar, ou o canal é de outra lista), fica pendente até carregar.
     */
    fun openLink(playlistId: String, channelKey: String) {
        val content = content.value
        if (content != null && content.playlist.id == playlistId && loadJob?.isActive != true) {
            val channel = content.byKey[channelKey]
            when {
                channel == null -> showToast(I18n.s.channelGone)
                isGroupLocked(channel.categoryId) -> showToast(I18n.s.groupLocked(content.categoryName(channel.categoryId)))
                else -> play(channel, groupFor(channel))
            }
            return
        }
        pendingLink = playlistId to channelKey
        if (content != null && content.playlist.id != playlistId) {
            playlists.value.firstOrNull { it.id == playlistId }?.let { switchPlaylist(it) }
        }
    }

    // ---- GoatRemote (o comando no telemóvel, ver PhoneChannels) -------------------------------

    /** Há canais para o telemóvel: a lista está aberta (no player ou nas Definições), não a carregar nem na ativação. */
    fun phoneReady(): Boolean = content.value != null && (_screen.value == Screen.Player || _screen.value == Screen.Settings)

    /** Os grupos e canais como o ecrã os mostra, sem os escondidos nem os bloqueados nas definições (mesmo desbloqueados). */
    internal fun phoneChannelList(): PhoneChannelList? {
        val content = content.value?.takeIf { phoneReady() } ?: return null
        val others = I18n.s.others
        val catalog = catalog()
        val groups = catalog.orderedGroups(includeHidden = false)
            .filterNot { catalog.isGroupLocked(it.id, Audience.PERSISTENT) }
            .map { group -> (if (group.id == PlaylistContent.OTHERS) group.copy(name = others) else group) to catalog.channelsOf(group.id, Audience.PERSISTENT) }
        return PhoneChannelList(
            playlist = content.playlist,
            groups = groups,
            prefs = prefs.value,
            favorites = favorites.value[content.playlist.id].orEmpty().toSet(),
            current = playback.playing.value?.key,
            logos = channelLogos.value,
            byKey = content.byKey,
        )
    }

    /** O canal [key] da lista aberta, se o telemóvel o puder ver e abrir (nunca pede o PIN). */
    fun phoneChannel(key: String): Channel? {
        val content = content.value?.takeIf { phoneReady() } ?: return null
        return content.byKey[key]?.takeIf { catalog().isVisible(it, Audience.PERSISTENT) }
    }

    /**
     * O canal a ver (o mesmo que o /channels diria) e, com guia, o programa e o seguinte, para o /now. null sem lista;
     * sem canal (ou um que o telemóvel não pode ver) vai tudo a null.
     */
    internal fun phoneNow(): ByteArray? {
        if (!phoneReady()) return null
        val channel = playback.playing.value?.key?.let(::phoneChannel) ?: return PhoneChannelList.nowJson(null, null, null)
        val now = System.currentTimeMillis()
        val epg = programmes(channel, cachedEpg(channel))
        val current = epg.at(now)
        val next = epg.after(current, now)
        return PhoneChannelList.nowJson(channel.key, current, next)
    }

    /** O logótipo que o ecrã mostra para o canal [key], se o telemóvel o puder ver. */
    fun phoneLogoUrl(key: String): String? = phoneChannel(key)?.let { PhoneChannelList.logoUrl(it, channelLogos.value) }

    /** A app foi para trás (ex.: voltou-se ao launcher): o "A seguir" fica com o programa que está a dar agora. */
    fun refreshHome() = publishHome()

    /** O telemóvel escolheu [channel]: como escolhê-lo na lista de canais (o último canal grava-se na mesma). */
    fun playFromPhone(channel: Channel) = play(channel, groupFor(channel))

    /**
     * Põe os canais recentes no ecrã inicial (o primeiro também no "A seguir"). Fica lá sem PIN e depois de a app
     * fechar, por isso conta o bloqueio tal como está nas definições, mesmo que esteja desbloqueado nesta sessão.
     */
    private fun publishHome(locksChanged: Boolean = false) {
        val content = content.value ?: return
        val items = catalog().channelsOf(SpecialCategory.RECENT, Audience.PERSISTENT).take(HOME_ITEMS).map { ch ->
            val now = System.currentTimeMillis()
            // O launcher mostra o mesmo logótipo que a app.
            val logo = channelLogos.value[ch.key]
            val guide = guideEntries(ch)
            val current = guide.indexOfFirst { it.isOn(now) }
            HomeItem(
                if (logo != null) ch.copy(logo = logo) else ch,
                content.playlist.id,
                guide.getOrNull(current),
                if (current >= 0) guide.drop(current + 1).take(HOME_UPCOMING) else emptyList(),
            )
        }
        viewModelScope.launch { if (items.isEmpty() && locksChanged) c.home.clear() else c.home.update(items) }
    }

    /** Grupo onde [channel] fica a fazer zapping: o atual, se o tiver, senão o dele. */
    fun groupFor(channel: Channel): String = playback.groupFor(channel)

    fun channelByNumber(n: Int): Channel? = catalog().channelByNumber(n)

    fun numberCanGrow(typed: String): Boolean = catalog().numberCanGrow(typed)

    /** Teclas numéricas: muda já de canal (sem mudar de ecrã) e o zapping passa a ser por "Todos". */
    fun playNumber(n: Int): Boolean {
        val channel = channelByNumber(n) ?: return false
        guarded(channel) { playback.playIn(channel, SpecialCategory.ALL) }
        return true
    }

    /** Resultado da pesquisa: um canal, ou um programa (a dar agora ou mais logo) num canal. */
    data class SearchHit(val channel: Channel, val programme: EpgEntry? = null)

    /**
     * Procura canais pelo nome e programas pelo título (sem acentos nem maiúsculas). Com [withPast], também os das
     * últimas 24 h que ainda se podem ver em catch-up.
     */
    fun search(query: String, withPast: Boolean = false): List<SearchHit> {
        val content = content.value ?: return emptyList()
        val q = searchKey(query)
        if (q.length < 2) return emptyList()
        val visible = content.channels.filter(::isVisible)
        val channels = visible.asSequence().filter { q in searchKey(it.name) }.take(MAX_SEARCH).map { SearchHit(it) }.toList()
        val now = System.currentTimeMillis()
        val until = now + 12 * 3600_000L
        val programmes = visible.asSequence().flatMap { ch ->
            guideEntries(ch).asSequence()
                .filter { (it.endMs > now || (withPast && it.endMs > now - 24 * 3600_000L && canCatchup(ch, it))) && it.startMs < until && q in searchKey(it.title) }
                .take(if (withPast) 3 else 2)
                .map { SearchHit(ch, it) }
        }.take(MAX_SEARCH).toList().sortedBy { it.programme?.startMs }
        return channels + programmes
    }

    private fun searchKey(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(ACCENTS, "")

    /** Programas do canal para a grelha (passado incluído), com o desvio horário das definições. */
    fun guideEntries(channel: Channel): List<EpgEntry> = guide.guideEntries(channel)

    /** Os programas a mostrar de [channel]: o guia, ou o EPG curto [shortEpg] já carregado (ver data/Programmes). */
    fun programmes(channel: Channel, shortEpg: List<EpgEntry>): List<EpgEntry> =
        cc.slokker.itv.data.programmes(guideEntries(channel), shortEpg)

    // ---- Catch-up ----------------------------------------------------------------------------

    /**
     * O servidor ainda guarda [entry] de [channel]? (Xtream com `tv_archive`, M3U com `catchup`, Stalker com o
     * arquivo do portal, dentro dos dias que guarda.)
     */
    fun canCatchup(channel: Channel, entry: EpgEntry): Boolean {
        val content = content.value ?: return false
        if (!content.playlist.type.kind.supportsCatchup || channel.archiveDays <= 0) return false
        val now = System.currentTimeMillis()
        return entry.startMs < now - Catchup.MIN_AGE_MS && entry.startMs > now - channel.archiveDays * 86_400_000L
    }

    /** Vê [entry] em catch-up, desde o início (ou desde [positionMs]). */
    fun playCatchup(channel: Channel, entry: EpgEntry, positionMs: Long = 0L) = guarded(null) {
        if (playback.playing.value?.key != channel.key) {
            playback.playIn(channel, groupFor(channel))
        }
        _screen.value = Screen.Player
        playback.startCatchup(channel, entry, positionMs)
    }

    /** Avança/recua no catch-up para [positionMs]; passar do direto volta ao direto. */
    fun seekCatchup(positionMs: Long) = playback.seekCatchup(positionMs)

    fun exitCatchup() = playback.exitCatchup()

    // ---- Temporizador e lembretes ------------------------------------------------------------

    /** Liga o temporizador para daqui a [minutes] minutos (null desliga). */
    fun setSleepTimer(minutes: Int?, announce: Boolean = true) {
        _sleepAt.value = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        if (announce) showToast(minutes?.let { I18n.s.sleepSet(it) } ?: I18n.s.sleepOff)
    }

    fun hasReminder(channel: Channel, entry: EpgEntry): Boolean = reminderList.has(channel, entry)

    /** Marca/desmarca o lembrete de um programa que ainda vai começar. */
    fun toggleReminder(channel: Channel, entry: EpgEntry) = reminderList.toggle(channel, entry)

    /** Lembrete a mostrar agora (o programa começa dentro de 1 min, ou começou há pouco). */
    fun dueReminder(now: Long): Reminder? = reminderList.due(now)

    /** O lembrete já foi mostrado: sai da lista (e, com [watch], muda para o canal). */
    fun consumeReminder(reminder: Reminder, watch: Boolean) {
        reminderList.consume(reminder)
        if (!watch) return
        val content = content.value ?: return
        val channel = content.byKey[reminder.channelKey] ?: return
        if (isGroupLocked(channel.categoryId)) {
            showToast(I18n.s.groupLocked(content.categoryName(channel.categoryId)))
            return
        }
        play(channel, groupFor(channel))
    }

    // ---- Gravações ---------------------------------------------------------------------------
    // O RecordingManager é do processo (continua com a app em segundo plano); aqui diz-se o que o player está a ver,
    // pergunta-se antes de largar a ligação de uma gravação e fazem-se as marcadas a partir do guia.

    private val recordings get() = c.recordings

    /** As gravações no disco (as a decorrer também). */
    val recordingList: StateFlow<List<RecordingInfo>> get() = recordings.list
    val recordingSchedule: StateFlow<Schedule> get() = recordings.schedule
    val activeRecordings: StateFlow<List<RecordingManager.Active>> get() = recordings.active

    /** "Mudar para X para gravar" / "Não gravar" (uma marcada noutro canal, quase a começar). */
    val recordingPrompt: StateFlow<ScheduledRecording?> get() = recordings.prompt

    /** O canal cuja ligação é de uma gravação: o player abre-o pelo anel dela (ver RecordingManager.ringFor). */
    val recordingRing: StateFlow<RecordingPlan.Busy?> get() = recordings.ringFor

    /**
     * Com uma gravação a decorrer, o que se ia fazer (outro canal, catch-up, outra lista) à espera da resposta.
     * [live]: não há gravação, mas está-se em pausa ou atrás do direto, e o que está guardado perde-se.
     */
    class SwitchPrompt(val title: String, val action: () -> Unit, val live: Boolean = false)
    private val _switchPrompt = MutableStateFlow<SwitchPrompt?>(null)
    val switchPrompt: StateFlow<SwitchPrompt?> = _switchPrompt.asStateFlow()

    private var playerVisible = false

    init {
        viewModelScope.launch {
            recordings.events.collect { e ->
                val s = I18n.s
                when (e) {
                    is RecordingManager.Event.Started -> showToast(s.recordingNow(e.info.title))
                    is RecordingManager.Event.Finished -> showToast(
                        when {
                            e.info.state == RecordingState.DONE || e.info.reason == RecordingReason.USER -> s.recordingSaved(e.info.title)
                            e.info.reason == RecordingReason.LOW_SPACE -> s.recordingStoppedSpace
                            else -> s.recordingStoppedConnection
                        },
                    )
                    is RecordingManager.Event.Skipped -> when (e.reason) {
                        RecordingPlan.SKIP_USER -> Unit
                        RecordingPlan.SKIP_HLS -> showToast(s.recordingHlsOnly)
                        RecordingPlan.SKIP_SPACE -> showToast(s.recordingNoSpace)
                        else -> showToast(s.recordingSkipped(e.recording.title))
                    }
                }
            }
        }
        // Uma marcada noutro canal: ninguém disse que não, ou disse que sim; muda-se (também de lista, se for de outra).
        viewModelScope.launch {
            recordings.switchTo.collect { r -> openLink(r.playlistId, r.channelKey) }
        }
        viewModelScope.launch { playback.playing.collect { updateWatching() } }
        viewModelScope.launch { playback.catchup.collect { updateWatching() } }
        viewModelScope.launch { prefs.collect { p -> recordings.setPadding(p.recordPaddingBeforeMin, p.recordPaddingAfterMin) } }
        // As séries marcam os programas novos que o guia vai trazendo.
        recordings.guide = { playlistId, key -> content.value?.takeIf { it.playlist.id == playlistId }?.byKey?.get(key)?.let(::guideEntries) }
        viewModelScope.launch { guide.version.collect { recordings.expandSeries(::seriesInstance) } }
    }

    /** A atividade fechou de vez: as gravações continuam, mas já não há quem diga o que se vê nem o guia. */
    override fun onCleared() {
        recordings.watching = null
        recordings.guide = null
        super.onCleared()
    }

    /** O player está à vista (a TV, ou o telemóvel com o canal aberto) ou não. */
    fun onPlayerVisible(visible: Boolean) {
        playerVisible = visible
        updateWatching()
    }

    private fun updateWatching() {
        val channel = playback.playing.value
        val content = content.value
        val cu = playback.catchup.value
        // Uma gravação do disco não usa a ligação: para as marcadas é como não estar a ver nada.
        recordings.watching = if (!playerVisible || channel == null || content == null || cu?.recording != null) {
            null
        } else {
            RecordingPlan.Watching(content.playlist.id, channel.key, live = cu == null)
        }
    }

    /** As gravações a decorrer de [channel] (da lista aberta). */
    fun recordingOf(channel: Channel): RecordingManager.Active? {
        val id = content.value?.playlist?.id ?: return null
        return activeRecordings.value.firstOrNull { it.playlistId == id && it.channelKey == channel.key }
    }

    /** O endereço de [channel] que uma gravação usa (o player abre-o pelo anel dela), ou null. */
    fun recordingRingUrl(channel: Channel): String? = recordings.ringUrl(content.value?.playlist?.id, channel.key)

    /**
     * [action] precisa da ligação ao fornecedor para outra coisa ([channel] em direto, ou null: outro canal, um
     * catch-up do servidor, outra lista). Com uma gravação a decorrer de outra coisa, pergunta primeiro.
     */
    private fun guarded(channel: Channel?, action: () -> Unit) {
        val active = activeRecordings.value
        val playlistId = content.value?.playlist?.id
        val same = channel != null && active.all { it.playlistId == playlistId && it.channelKey == channel.key }
        if (active.isNotEmpty() && !same) {
            _switchPrompt.value = SwitchPrompt(active.first().info.title, action)
            return
        }
        // Em pausa ou atrás do direto: mudar apaga o anel. O mesmo canal não muda nada.
        val leaving = channel == null || channel.key != playback.playing.value?.key
        if (leaving && prefs.value.protectPaused && liveShifted()) {
            _switchPrompt.value = SwitchPrompt("", action, live = true)
            return
        }
        action()
    }

    /** O player diz se se está em pausa ou atrás do direto (ver Timeshift.worthAsking); sem player, nunca. */
    var liveShifted: () -> Boolean = { false }

    /** A resposta: parar a gravação e fazer o que se pediu, ou ficar a gravar ([stop] false). */
    fun answerSwitch(stop: Boolean) {
        val p = _switchPrompt.value ?: return
        _switchPrompt.value = null
        if (!stop) return
        if (!p.live) recordings.stopAll()
        p.action()
    }

    /** A resposta ao aviso de uma marcada ("Mudar para X para gravar" ou "Não gravar"). */
    fun answerRecordingPrompt(id: String, switch: Boolean) = recordings.answer(id, switch)

    /** O programa a dar em [channel] (o guia, ou o EPG curto). */
    private fun programmeNow(channel: Channel): EpgEntry? = programmes(channel, cachedEpg(channel)).at(System.currentTimeMillis())

    /** "Gravar" no player: o programa a dar, desde o início que o anel da pausa do direto ainda tem. */
    internal fun recordNow(channel: Channel, feed: ChannelFeed?) {
        val s = I18n.s
        if (feed == null) return showToast(s.recordingNotRecordable)
        val content = content.value
        val p = programmeNow(channel)
        val refusal = recordings.recordNow(
            feed,
            RecordingManager.Meta(content?.playlist?.id, channel.key, channel.name, logoOf(channel), p?.title ?: channel.name, p?.startMs, p?.endMs),
        )
        when (refusal) {
            null -> Unit
            RecordingManager.Refusal.NO_SPACE -> showToast(s.recordingNoSpace)
            RecordingManager.Refusal.OTHER_CHANNEL -> showToast(s.recordingOtherChannel)
            RecordingManager.Refusal.ALREADY -> showToast(s.recordingNow(p?.title ?: channel.name))
            RecordingManager.Refusal.NOT_RECORDABLE -> showToast(s.recordingNotRecordable)
        }
    }

    /** "Parar a gravação" de [channel]. */
    fun stopRecording(channel: Channel) {
        recordingOf(channel)?.let { recordings.stop(it.info.id) }
    }

    fun stopRecording(id: String) = recordings.stop(id)

    fun deleteRecording(id: String) {
        // A ver esta gravação: sai dela primeiro (volta ao direto do canal a dar).
        val file = recordings.files.videoFile(id).toURI().toString()
        if (playback.catchup.value?.recording == file) playback.exitCatchup()
        recordings.delete(id)
        showToast(I18n.s.recordingDeleted)
    }

    fun cancelScheduled(id: String) {
        recordings.cancel(id)
        showToast(I18n.s.recordingCancelled)
    }

    fun cancelSeries(seriesId: String) {
        recordings.cancelSeries(seriesId)
        showToast(I18n.s.recordingCancelled)
    }

    /** O espaço livre onde ficam as gravações. */
    fun recordingFreeBytes(): Long = recordings.freeBytes()

    /** O logótipo que a app mostra para [channel] (o escolhido/do guia, ou o da lista). */
    private fun logoOf(channel: Channel): String? = channelLogos.value[channel.key] ?: channel.logo

    /** Como está marcado um programa no guia. */
    enum class RecordMark { NONE, ONE, SERIES }

    fun recordMark(channel: Channel, entry: EpgEntry): RecordMark {
        val id = content.value?.playlist?.id ?: return RecordMark.NONE
        if (recordings.seriesFor(id, channel.key, entry.title) != null) return RecordMark.SERIES
        if (recordings.scheduledFor(id, channel.key, entry.startMs) != null) return RecordMark.ONE
        // O programa a gravar agora também conta como marcado.
        val active = recordingOf(channel)
        return if (active != null && active.info.programmeStartMs == entry.startMs) RecordMark.ONE else RecordMark.NONE
    }

    /** A marcada de [entry] em [channel] (com as tentativas do canal e o User-Agent, para gravar sem a app aberta). */
    private fun scheduledFor(channel: Channel, entry: EpgEntry, seriesId: String? = null): ScheduledRecording? {
        val content = content.value ?: return null
        val playlist = content.playlist
        // O Stalker dá o link na hora (e caduca): não dá para guardar para depois.
        if (playlist.type.kind.linkAtPlayTime) return null
        val candidates = cc.slokker.itv.data.streamCandidates(playlist, channel, streamFormat.value, content.account?.allowedFormats.orEmpty())
        return ScheduledRecording(
            id = "s-${entry.startMs}-${java.util.UUID.randomUUID().toString().take(6)}",
            playlistId = playlist.id, channelKey = channel.key, channelName = channel.name, logo = logoOf(channel),
            title = entry.title, startMs = entry.startMs, endMs = entry.endMs, candidates = candidates,
            userAgent = SafeInput.headerSafe(channel.userAgent ?: prefs.value.userAgent).ifBlank { IPTV_USER_AGENT },
            referrer = channel.referrer, seriesId = seriesId,
        )
    }

    private fun seriesInstance(rule: SeriesRule, entry: EpgEntry): ScheduledRecording? {
        val channel = content.value?.takeIf { it.playlist.id == rule.playlistId }?.byKey?.get(rule.channelKey) ?: return null
        return scheduledFor(channel, entry, rule.id)
    }

    /**
     * Menu (TV) ou o botão ⏺ (telemóvel) num programa do guia: gravar → gravar a série → desmarcar. O programa a dar no
     * canal a ver grava-se já ("Gravar" do player).
     */
    internal fun cycleRecording(channel: Channel, entry: EpgEntry, feed: ChannelFeed? = null) {
        val s = I18n.s
        val content = content.value ?: return
        val now = System.currentTimeMillis()
        if (entry.endMs <= now) return
        val playlistId = content.playlist.id
        when (recordMark(channel, entry)) {
            RecordMark.NONE -> {
                // A dar agora no canal a ver: grava-se do anel (desde o início do programa).
                if (entry.startMs <= now && playback.playing.value?.key == channel.key && feed != null) return recordNow(channel, feed)
                val r = scheduledFor(channel, entry) ?: return showToast(s.recordingNotRecordable)
                if (r.url == null) return showToast(s.recordingHlsOnly)
                val overlap = recordings.schedule(r)
                showToast(overlap.firstOrNull()?.let { s.recordingOverlap(it.title) } ?: s.recordingScheduled(entry.title))
            }
            RecordMark.ONE -> {
                val first = recordings.scheduledFor(playlistId, channel.key, entry.startMs)
                val ruleId = "series-${java.util.UUID.randomUUID().toString().take(8)}"
                val rule = SeriesRule(ruleId, playlistId, channel.key, channel.name, logoOf(channel), entry.title)
                val one = (first ?: scheduledFor(channel, entry))?.copy(seriesId = ruleId)
                if (one == null) {
                    // A gravar agora (sem marcada): a série vale para os próximos.
                    recordings.addSeries(rule, null, ::seriesInstance)
                } else {
                    recordings.addSeries(rule, one, ::seriesInstance)
                }
                showToast(s.recordingSeriesScheduled(entry.title))
            }
            RecordMark.SERIES -> {
                recordings.seriesFor(playlistId, channel.key, entry.title)?.let { recordings.cancelSeries(it.id) }
                recordings.scheduledFor(playlistId, channel.key, entry.startMs)?.let { recordings.cancel(it.id) }
                showToast(s.recordingCancelled)
            }
        }
    }

    /** Vê uma gravação do disco no player (com a barra e os saltos do catch-up; sem ligação ao fornecedor). */
    fun playRecording(info: RecordingInfo) {
        val content = content.value
        val file = recordings.files.videoFile(info.id)
        if (!file.exists()) return
        val channel = content?.takeIf { it.playlist.id == info.playlistId }?.byKey?.get(info.channelKey)
            ?: Channel(uid = -1, key = "rec:${info.id}", num = 0, name = info.channelName, logo = info.logo, categoryId = "")
        // Sem canal a dar não há player: abre-se o canal da gravação (ou o primeiro) por baixo dela.
        if (playback.playing.value == null) {
            val base = content?.byKey?.get(info.channelKey) ?: channelsOf(SpecialCategory.ALL).firstOrNull() ?: return
            playback.playIn(base, groupFor(base))
        }
        _screen.value = Screen.Player
        val end = if (info.recording) System.currentTimeMillis() else info.fileEndMs
        playback.startRecording(Catchup(channel, info.title, info.fileStartMs, end.coerceAtLeast(info.fileStartMs + 1), 0L, recording = file.toURI().toString()))
    }

    // ---- Segundo canal -------------------------------------------------------------------------

    /**
     * Pode-se ver o segundo canal de [playlist]? Da conta da lista principal só se o plano deixar 2+ ligações
     * (nas M3U não se sabe: deixa-se tentar).
     */
    fun secondAllowed(playlist: Playlist): Boolean {
        // Um MAC é uma box: uma ligação de cada vez.
        if (playlist.type.kind.singleConnection) return false
        val main = content.value ?: return true
        if (!playlist.sharesConnectionsWith(main.playlist)) return true
        if (!main.playlist.type.kind.knowsConnectionLimit) return true
        val limit = main.account?.maxConnections ?: return false
        return limit >= 2
    }

    /** As listas Stalker só existem sem conta: com conta, o site expõe o portal como M3U. */
    val canAddStalker: Boolean get() = repository().supportsStalker

    val deviceMac: String get() = c.session.deviceMac

    /** O link de um canal Stalker neste momento (pede-se de cada vez que se abre, porque caduca). */
    /** O que o player pede ao portal Stalker: o link do direto, ou o da gravação de um programa (catch-up). */
    internal val stalkerLinks = object : StalkerLinks {
        override suspend fun live(playlist: Playlist, channel: Channel) = c.stalker.candidates(playlist, channel)

        override suspend fun archive(playlist: Playlist, channel: Channel, catchup: Catchup): FreshLinks {
            val wanted = catchup.startMs + catchup.positionMs
            val recording = c.stalker.archive(playlist, channel, wanted, catchup.startMs, catchup.endMs) ?: return FreshLinks(emptyList())
            return FreshLinks(listOf(recording.candidate), archiveStartMs = recording.startMs, seekMs = (wanted - recording.startMs).coerceAtLeast(0))
        }
    }

    /** Os canais de [playlist] para escolher o segundo canal (da cache, ou descarregados). */
    suspend fun loadForSecond(playlist: Playlist): PlaylistContent = lists.loadForSecond(playlist)

    /**
     * Grupos de [content] para o segundo canal: os mesmos da lista de grupos (ordem escolhida, Favoritos, Recentes e
     * Todos conforme as definições), sem os bloqueados (aqui não se pede o PIN). Como na Apple TV.
     */
    fun groupsForSecond(content: PlaylistContent): List<Category> = catalogFor(content).groups(groupNames())

    fun channelsForSecond(content: PlaylistContent, groupId: String): List<Channel> = catalogFor(content).channelsOf(groupId)

    fun openSecond(content: PlaylistContent, channel: Channel, groupId: String) = playback.openSecond(content, channel, groupId)

    /** ↑/↓ no segundo canal (dá a volta nas pontas, como o principal). */
    fun zapSecond(delta: Int) = playback.zapSecond(delta)

    fun closeSecond() = playback.closeSecond()

    // ---- Lista de reserva ------------------------------------------------------------------------

    /** A lista de reserva de [playlistId] (Prefs.backupPlaylist), se ainda existir e não for ela própria. */
    fun backupOf(playlistId: String): Playlist? {
        val id = prefs.value.backupPlaylist[playlistId] ?: return null
        return playlists.value.firstOrNull { it.id == id && it.id != playlistId }
    }

    /** Muda a reserva de [playlistId] (null = nenhuma). Os canais escolhidos à mão eram da reserva anterior: saem. */
    fun setBackupPlaylist(playlistId: String, backupId: String?) {
        val p = prefs.value
        val target = backupId?.takeIf { it != playlistId }
        if (p.backupPlaylist[playlistId] == target) return
        val map = if (target == null) p.backupPlaylist - playlistId else p.backupPlaylist + (playlistId to target)
        val channels = p.backupChannels.filterKeys { !it.startsWith("$playlistId:") }
        setPrefs(mapOf(Prefs.Key.BACKUP_PLAYLIST to map, Prefs.Key.BACKUP_CHANNELS to channels))
        _backupCoverage.value = _backupCoverage.value - playlistId
    }

    /** O canal da reserva escolhido à mão para [channelKey] de [playlistId]: uma chave, [ChannelMatch.OFF], ou null (automático). */
    fun backupChoice(playlistId: String, channelKey: String): String? = prefs.value.backupChannels["$playlistId:$channelKey"]

    /** Grava a escolha à mão ([value] null = volta ao automático). */
    fun setBackupChoice(playlistId: String, channelKey: String, value: String?) {
        val current = prefs.value.backupChannels
        val key = "$playlistId:$channelKey"
        setPref(Prefs.Key.BACKUP_CHANNELS, if (value == null) current - key else current + (key to value))
        _backupCoverage.value = _backupCoverage.value - playlistId
    }

    /** Os canais da reserva (da cache, ou descarregados como os do segundo canal), ou null se não carregarem a tempo. */
    private suspend fun backupContent(backup: Playlist): PlaylistContent? =
        withTimeoutOrNull(BACKUP_LOAD_MS) { runCatching { lists.loadForSecond(backup) }.getOrNull() }

    /** O índice da última reserva procurada (partir os nomes de milhares de canais faz-se uma vez). */
    private var backupMatcher: Pair<PlaylistContent, ChannelMatch.Matcher>? = null

    private suspend fun matcherFor(content: PlaylistContent): ChannelMatch.Matcher {
        backupMatcher?.takeIf { it.first === content }?.let { return it.second }
        val m = withContext(Dispatchers.Default) { ChannelMatch.Matcher(content.channels.map(ChannelMatch::entry)) }
        backupMatcher = content to m
        return m
    }

    /**
     * O canal [channel] de [main] na reserva, pronto a abrir ([choice]: um canal já escolhido no painel "experimentar
     * agora"); null sem reserva, sem o canal (ou na dúvida), ou quando abrir a reserva passava o limite de ligações
     * dela (o segundo canal já está nessa conta).
     */
    internal suspend fun backupStream(main: PlaylistContent, channel: Channel, choice: Channel? = null): BackupStream? {
        if (streamsDisabled) return null
        val backup = backupOf(main.playlist.id) ?: return null
        // A mesma conta do fornecedor não ajuda (e contava como outra ligação).
        if (backup.sharesConnectionsWith(main.playlist)) return null
        val content = backupContent(backup) ?: return null.also { Log.i(TAG_BACKUP, "Reserva ${backup.name}: os canais não carregaram") }
        playback.second.value?.content?.playlist?.let { sp ->
            val limit = content.account?.maxConnections
            val one = backup.type.kind.singleConnection || backup.type.kind.knowsConnectionLimit && (limit ?: 1) < 2
            if (sp.sharesConnectionsWith(backup) && one) return null.also { Log.i(TAG_BACKUP, "Reserva: o segundo canal já usa a conta dela") }
        }
        val target = choice ?: run {
            val result = matcherFor(content).find(channel.name, channel.epgId, backupChoice(main.playlist.id, channel.key))
            (result as? ChannelMatch.Result.Match)?.let { content.channels.getOrNull(it.index) }
                ?: return null.also { Log.i(TAG_BACKUP, "Reserva: ${if (result is ChannelMatch.Result.Unsure) "em dúvida" else "sem canal"} para \"${channel.name}\"") }
        }
        Log.i(TAG_BACKUP, "Reserva: \"${channel.name}\" → \"${target.name}\" (${backup.name})")
        val request = PlaybackRequest.of(content, target, null, streamFormat.value, stalkerLinks)
        return BackupStream(request, main.playlist.name, backup.name)
    }

    /** A cobertura da reserva de cada lista (o subtítulo da linha "Lista de reserva"). */
    sealed interface BackupCoverage {
        data object Checking : BackupCoverage
        data class Ready(val backupId: String, val coverage: ChannelMatch.Coverage) : BackupCoverage
        data object Unavailable : BackupCoverage
    }

    private val _backupCoverage = MutableStateFlow<Map<String, BackupCoverage>>(emptyMap())
    val backupCoverage: StateFlow<Map<String, BackupCoverage>> = _backupCoverage.asStateFlow()

    /**
     * Conta, por trás, quantos canais de [playlistId] têm o seu na reserva (as listas da cache, ou descarregadas como as
     * do segundo canal). Os nomes dos que faltam ou ficam em dúvida vão para o registo (só os nomes), para o relatório.
     */
    fun checkBackupCoverage(playlistId: String) {
        val backup = backupOf(playlistId) ?: return
        val known = _backupCoverage.value[playlistId]
        if (known == BackupCoverage.Checking || known is BackupCoverage.Ready && known.backupId == backup.id) return
        val main = playlists.value.firstOrNull { it.id == playlistId } ?: return
        _backupCoverage.value = _backupCoverage.value + (playlistId to BackupCoverage.Checking)
        viewModelScope.launch {
            val mainContent = content.value?.takeIf { it.playlist.id == playlistId } ?: backupContent(main)
            val backupContent = backupContent(backup)
            val result = if (mainContent == null || backupContent == null) {
                BackupCoverage.Unavailable
            } else {
                val overrides = prefs.value.backupChannels.filterKeys { it.startsWith("$playlistId:") }.mapKeys { it.key.removePrefix("$playlistId:") }
                val matcher = matcherFor(backupContent)
                val cov = withContext(Dispatchers.Default) { ChannelMatch.coverage(mainContent.channels.map(ChannelMatch::entry), matcher, overrides) }
                logCoverage(main.name, backup.name, cov)
                BackupCoverage.Ready(backup.id, cov)
            }
            _backupCoverage.value = _backupCoverage.value + (playlistId to result)
        }
    }

    private fun logCoverage(main: String, backup: String, cov: ChannelMatch.Coverage) {
        Log.i(TAG_BACKUP, "Cobertura $main → $backup: ${cov.matched}/${cov.total}, ${cov.unsure} em dúvida, ${cov.missing} sem canal")
        // Só os nomes dos canais (nunca endereços), aos bocados para o logcat não os cortar.
        for ((label, names) in listOf("Em dúvida" to cov.unsureNames, "Sem canal" to cov.missingNames)) {
            names.take(MAX_LOGGED_NAMES).chunked(40).forEach { Log.i(TAG_BACKUP, "$label: " + it.joinToString(" | ")) }
            if (names.size > MAX_LOGGED_NAMES) Log.i(TAG_BACKUP, "$label: … mais ${names.size - MAX_LOGGED_NAMES}")
        }
    }

    /** O painel "Canal de reserva" de [channel] (da lista principal): o automático, os parecidos e a escolha à mão. */
    class BackupChoices(
        val backup: Playlist,
        val content: PlaylistContent,
        val auto: ChannelMatch.Result,
        val similar: List<Channel>,
        /** A escolha à mão: uma chave da reserva, [ChannelMatch.OFF], ou null (automático). */
        val choice: String?,
    ) {
        val autoChannel: Channel? get() = (auto as? ChannelMatch.Result.Match)?.let { content.channels.getOrNull(it.index) }
        val chosenChannel: Channel? get() = choice?.takeIf { it != ChannelMatch.OFF }?.let { content.byKey[it] }
    }

    suspend fun backupChoices(channel: Channel): BackupChoices? {
        val main = content.value ?: return null
        val backup = backupOf(main.playlist.id) ?: return null
        val backupContent = backupContent(backup) ?: return null
        val matcher = matcherFor(backupContent)
        return withContext(Dispatchers.Default) {
            val auto = matcher.find(channel.name, channel.epgId)
            val similar = matcher.ranked(channel.name, channel.epgId, BACKUP_SIMILAR).mapNotNull { backupContent.channels.getOrNull(it) }
            BackupChoices(backup, backupContent, auto, similar, backupChoice(main.playlist.id, channel.key))
        }
    }

    /** Pesquisa nos canais da reserva (pelo nome, sem acentos nem maiúsculas). */
    fun searchBackup(choices: BackupChoices, query: String): List<Channel> {
        val q = searchKey(query.trim())
        if (q.length < 2) return emptyList()
        return choices.content.channels.asSequence().filter { q in searchKey(it.name) }.take(MAX_SEARCH).toList()
    }

    // ---- Atualizações da app -----------------------------------------------------------------

    val appVersion: String get() = c.updater.currentVersion
    val updatesEnabled: Boolean get() = c.updater.enabled

    /** Vê se há versão nova. Automático: só avisa se houver; [manual]: diz também "está atualizada" ou o erro. */
    fun checkForUpdate(manual: Boolean) {
        if (!c.updater.enabled) {
            if (manual) showToast(I18n.s.updatesOnlyRelease)
            return
        }
        val state = _update.value
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing) return
        lastUpdateCheck = System.currentTimeMillis()
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = try {
                val latest = c.updater.latest()
                if (latest != null && Updater.isNewer(latest.version, c.updater.currentVersion)) {
                    if (!manual) _updateNotice.value = latest
                    UpdateState.Available(latest)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(e.message ?: e.javaClass.simpleName) else UpdateState.Idle
            }
        }
    }

    /**
     * Envia um relatório para a zona admin do site: o registo desta app (sem palavras-passe, utilizadores, tokens
     * nem URLs das listas) e o que a pessoa escreveu. Com conta, fica ligado a ela.
     */
    fun sendReport(message: String) {
        if (_report.value == ReportState.Sending) return
        _report.value = ReportState.Sending
        viewModelScope.launch {
            _report.value = try {
                val playlists = playlists.value
                val header = "GoatTV ${BuildConfig.VERSION_NAME} · ${BugReport.device}\n" +
                    "Account: ${if (_local.value) "no" else "yes"} · playlists: ${playlists.joinToString { it.type.kind.wireName }.ifEmpty { "none" }}" +
                    (if (c.testMode) " · test mode" else "")
                val secrets = playlists.flatMap { it.secrets() } +
                    (playlists.flatMap { listOfNotNull(it.server, it.url, it.epgUrl) } + listOfNotNull(content.value?.guideUrl)).mapNotNull(BugReport::hostOf) +
                    listOfNotNull(c.token, _email.value)
                val log = kotlinx.coroutines.withContext(Dispatchers.IO) { BugReport.collect(getApplication(), header, secrets) }
                val body = JsonObject().apply {
                    addProperty("app", "goattv")
                    addProperty("version", BuildConfig.VERSION_NAME)
                    addProperty("platform", c.platform)
                    addProperty("device", BugReport.device)
                    message.trim().take(2000).takeIf { it.isNotEmpty() }?.let { addProperty("message", it) }
                    addProperty("log", log)
                }
                val res = c.backend.sendReport(body)
                val id = res.body()?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                when {
                    res.code() == 429 -> ReportState.Failed(I18n.s.reportTooMany)
                    res.isSuccessful && id != null -> ReportState.Sent(id)
                    else -> ReportState.Failed(I18n.s.reportFailed("HTTP ${res.code()}"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReportState.Failed(I18n.s.reportFailed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun dismissUpdateNotice() {
        _updateNotice.value = null
    }

    /** Descarrega e instala a versão nova (o Android pede confirmação). */
    fun installUpdate() {
        val release = (_update.value as? UpdateState.Available)?.release
            ?: (_update.value as? UpdateState.Failed)?.let { return checkForUpdate(manual = true) }
            ?: return
        // Da primeira vez, o Android tem de autorizar a GoatTV a instalar apps.
        if (!c.updater.canInstall()) {
            showToast(I18n.s.allowInstalls)
            c.updater.openInstallPermission()
            return
        }
        viewModelScope.launch {
            try {
                _update.value = UpdateState.Downloading(release, 0f)
                val apk = c.updater.download(release) { p -> _update.value = UpdateState.Downloading(release, p) }
                _update.value = UpdateState.Installing(release)
                flushLastChannel()
                c.updater.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- PIN das Definições ------------------------------------------------------------------

    fun settingsNeedPin(): Boolean = parental.settingsNeedPin()

    fun unlockSettings(pin: String): Boolean = parental.unlockSettings(pin)

    /** O fallback encontrou um formato que funciona: fica guardado na conta. */
    fun onFormatWorked(format: String) {
        if (content.value?.playlist?.type?.kind?.remembersFormat != true) return
        if (format != streamFormat.value) setStreamFormat(format)
    }

    private fun scheduleSaveLast(channel: Channel) {
        val playlistId = content.value?.playlist?.id ?: return
        saveLastJob?.cancel()
        pendingLast = playlistId to channel.key
        saveLastJob = viewModelScope.launch {
            delay(PlaybackState.WATCHED_MS) // só grava se ficar no canal (evita um PATCH por cada CH+)
            saveLast(watched = true)
        }
    }

    /**
     * A app vai para segundo plano: grava já o canal atual como último (para abrir onde se saiu); nos Recentes
     * só entra se já se ficou nele [PlaybackState.WATCHED_MS].
     */
    fun flushLastChannel() {
        if (pendingLast == null) return
        saveLastJob?.cancel()
        val watched = playback.watchedCurrent()
        viewModelScope.launch { saveLast(watched) }
    }

    private suspend fun saveLast(watched: Boolean) {
        val (playlistId, channelKey) = pendingLast ?: return
        pendingLast = null
        // Só quando o canal mudou neste aparelho: uma TV ligada não desfaz o que se escolheu noutro.
        val changedHere = c.session.lastPlaylist() != playlistId || c.session.lastChannel() != channelKey
        val at = if (changedHere) System.currentTimeMillis().also { c.session.saveLast(playlistId, channelKey, it) } else null
        profile.recordLast(playlistId, channelKey, at, watched, MAX_RECENTS)
        publishHome()
    }

    // ---- Definições ------------------------------------------------------------------------

    /** Definições em ecrã próprio (quando não há player, ex.: erro ao carregar). */
    fun openSettings() {
        _screen.value = Screen.Settings
        refreshConfig()
    }

    fun closeSettings() {
        settingsStartAtNewPlaylist = false
        when {
            content.value != null && playback.playing.value != null -> _screen.value = Screen.Player
            // Sem conta e sem listas (ex.: Voltar no formulário da primeira lista).
            c.token == null && playlists.value.isEmpty() -> _screen.value = Screen.Welcome
            else -> startLoading(resume = false)
        }
    }

    /** null = Auto. */
    fun setStreamFormat(format: String?) = profile.setStreamFormat(format)

    /**
     * "Terminar sessão nesta TV": avisa o servidor (que apaga este dispositivo da conta) e apaga a sessão local
     * (as listas de quando se usou sem conta ficam). Só aqui: quando a sessão acaba sozinha (401, 404) não se avisa.
     */
    fun logoutThisTv() {
        // O token antes de a sessão local ser apagada; o aviso não espera nem impede nada (ver logoutDevice).
        c.token?.takeIf { !c.testMode }?.let(c::logoutDevice)
        viewModelScope.launch { resetToActivation() }
    }

    private suspend fun resetToActivation() {
        sessionGeneration++
        c.token = null
        resetState(Reset.SESSION)
        _screen.value = Screen.Welcome
        // Pode correr dentro do loadJob (renewToken → 404), que o resetState acabou de cancelar: o disco limpa-se na mesma.
        withContext(NonCancellable) {
            c.session.clear()
            c.cache.clear() // tem credenciais das listas
        }
    }

    /** Até onde vai o [resetState]. */
    private enum class Reset {
        /** Apagou-se a última lista: a sessão e o perfil (definições, favoritos) continuam. */
        PLAYLISTS,

        /**
         * Sem conta → conta: o perfil desta TV fica no LocalStore (e em pendingLocalProfile). As preferências ficam
         * até chegarem as da conta, para o aspeto e a língua não mudarem a meio da ligação.
         */
        PROFILE,

        /** Terminou a sessão: tudo, e as preferências voltam ao padrão. */
        SESSION,
    }

    /**
     * Esquece o que está aberto e, conforme [scope], o perfil e a sessão. É o único sítio que o faz: um campo novo
     * com dados de uma lista, do perfil ou da conta junta-se aqui, na secção certa.
     */
    private fun resetState(scope: Reset) {
        // O que está aberto: canal, lista, segundo canal (as listas dele têm credenciais), guias e logótipos.
        cancelBackgroundJobs()
        pendingLast = null
        playback.reset()
        lists.close()
        guide.reset()
        viewModelScope.launch { c.home.clear() }
        if (scope == Reset.PLAYLISTS) return

        // O perfil: listas, favoritos, recentes, definições (e a cópia do servidor), desbloqueios e verificações.
        _local.value = false
        profile.reset()
        parental.reset()
        refreshes.clear()
        if (scope == Reset.PROFILE) return

        // A sessão.
        userId = null
        _email.value = null
        _deviceName.value = null
        pendingLocalProfile = null
        profile.resetPrefs()
    }

    private fun cancelBackgroundJobs() {
        loadJob?.cancel()
        lists.cancelJobs()
        saveLastJob?.cancel()
    }

    // ---- Avisos e gravação na conta --------------------------------------------------------

    fun showToast(message: String) {
        _toast.value = message
    }

    fun clearToast(message: String) {
        if (_toast.value == message) _toast.value = null
    }

    /** Modo de teste ou sem vídeo: os players não abrem streams (ver AppContainer.streamsDisabled). */
    val streamsDisabled: Boolean get() = c.streamsDisabled
    val noVideo: Boolean get() = c.noVideo

    private companion object {
        const val TAG = "GoatTV"
        /** O registo da lista de reserva (vai no relatório: só nomes de canais e de listas). */
        const val TAG_BACKUP = "GoatTV.Backup"
        const val BACKUP_LOAD_MS = 20_000L
        const val BACKUP_SIMILAR = 8
        const val MAX_LOGGED_NAMES = 400
        const val UPDATE_FIRST_CHECK_MS = 20_000L
        const val UPDATE_INTERVAL_MS = 12 * 3600_000L
        /** Canais na linha da GoatTV no ecrã inicial. */
        const val HOME_ITEMS = 10
        /** Os programas seguintes que vão para o "A seguir" (o GoatLauncher mostra até três, mesmo depois de o atual acabar). */
        const val HOME_UPCOMING = 4
        const val MAX_RECENTS = 20
        const val MAX_SEARCH = 40
        private val ACCENTS = Regex("\\p{Mn}+")

        /** De quanto em quanto tempo ver se a lista ou o guia já passaram do intervalo. */
        const val REFRESH_TICK_MS = 10 * 60_000L

        /** Id de uma lista desta TV (sem conta). */
        fun newLocalId(): String = "local" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
