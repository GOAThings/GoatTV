package cc.slokker.itv.ui

import cc.slokker.itv.data.AppRelease
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.HomeItem
import cc.slokker.itv.data.Reminder
import cc.slokker.itv.data.Updater
import cc.slokker.itv.data.epg.XmltvGuide
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import cc.slokker.itv.IPTV_USER_AGENT
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.ui.common.ErrorInfo
import cc.slokker.itv.ui.common.explainPlaylistError
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.slokker.itv.ItvApp
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistForm
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.RefreshPolicy
import cc.slokker.itv.data.SessionStore
import cc.slokker.itv.data.SettingsMerge
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.api.BackendDevice
import cc.slokker.itv.data.api.BackendUser
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.api.EpgMapping
import cc.slokker.itv.data.api.EpgMappingRequest
import cc.slokker.itv.data.api.PlaylistDto
import cc.slokker.itv.data.api.unescaped
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import cc.slokker.itv.data.ChannelIndex
import retrofit2.HttpException
import java.io.IOException

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

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _content = MutableStateFlow<PlaylistContent?>(null)
    val content: StateFlow<PlaylistContent?> = _content.asStateFlow()

    /** `favorites` da conta: playlistId → channelKeys. */
    private val _favorites = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val favorites: StateFlow<Map<String, List<String>>> = _favorites.asStateFlow()

    /** `settings` da conta, guardado inteiro para não perder chaves que esta versão não conhece. */
    private var settings = JsonObject()

    /** O `settings` tal como está no servidor (o último que se leu ou gravou) e a versão dele: grava-se só a diferença. */
    private var syncedSettings = JsonObject()
    private var settingsRev: Long? = null
    /** O `ETag` da última config lida (para o servidor responder 304 quando nada mudou). */
    private var configEtag: String? = null
    /** Que índice de canais a conta tem de cada lista (`index_hash`), para só enviar quando muda. */
    private val indexHashes = HashMap<String, String?>()
    private var indexJob: Job? = null
    private val _streamFormat = MutableStateFlow<String?>(null)
    val streamFormat: StateFlow<String?> = _streamFormat.asStateFlow()

    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    /** Textos na língua da app (a escolhida, ou a do sistema). Mudam quando a língua muda. */
    val strings: StateFlow<Strings> = _prefs
        .map { Strings.of(getApplication()).also { s -> I18n.s = s } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, I18n.s)

    /**
     * A língua das preferências (da conta, ou mudada aqui) passa a ser a da app. Só quando as preferências
     * chegam de facto: as de defeito ("auto", ao arrancar ou ao terminar sessão) não desfazem a escolha.
     */
    private fun applyLanguage() = Strings.apply(_prefs.value.language)

    init {
        // Preferências que vivem fora do Compose: User-Agent dos pedidos e cópia local do arranque automático.
        viewModelScope.launch {
            _prefs.collect { p ->
                c.userAgent = p.userAgent.ifBlank { IPTV_USER_AGENT }
                if (_content.value != null) c.session.setAutoStart(p.autoStart)
            }
        }
    }

    /** Grupos bloqueados já desbloqueados com o PIN nesta sessão. */
    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())
    val unlocked: StateFlow<Set<String>> = _unlocked.asStateFlow()

    /** Grupo do canal a ver: é por ele que CH+/CH− navegam e é o que o painel de canais abre. */
    private val _selectedCategory = MutableStateFlow(SpecialCategory.ALL)
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    /** Quando não há canal para retomar, o player abre já com o painel de canais. */
    private var openPanelOnStart = false

    private val _playing = MutableStateFlow<Channel?>(null)
    val playing: StateFlow<Channel?> = _playing.asStateFlow()

    /** Canais da categoria onde se carregou OK: é por eles que CH+/CH− navegam. */
    private var zapList: List<Channel> = emptyList()

    /** Últimos canais vistos (`settings.recent_channels`): playlistId → channelKeys, o mais recente primeiro. */
    private val _recents = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val recents: StateFlow<Map<String, List<String>>> = _recents.asStateFlow()

    /** O canal visto antes do atual (→ volta a ele) e desde quando o atual está no ecrã. */
    private var previous: Channel? = null
    private var playingSince = 0L

    /** Programa a ver em catch-up (null = em direto). */
    private val _catchup = MutableStateFlow<Catchup?>(null)
    val catchup: StateFlow<Catchup?> = _catchup.asStateFlow()

    /** Hora a que o temporizador para a app (null = desligado). Só nesta TV, até a app fechar. */
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()

    /** Lembretes de programas (só nesta TV). */
    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    /** Segundo canal no ecrã (imagem na imagem / dividido). Pode ser de outra lista (outro fornecedor). */
    data class SecondView(val content: PlaylistContent, val channel: Channel, val zapList: List<Channel>)

    private val _second = MutableStateFlow<SecondView?>(null)
    val second: StateFlow<SecondView?> = _second.asStateFlow()

    /** Listas carregadas só para o segundo canal (a principal está em [content]). */
    private val secondContents = HashMap<String, PlaylistContent>()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var lastUpdateCheck = 0L

    /** Canal pedido por um link do ecrã inicial, à espera de a lista carregar: (playlistId, channelKey). */
    private var pendingLink: Pair<String, String>? = null

    /** As Definições já foram desbloqueadas com o PIN nesta sessão. */
    private var settingsUnlocked = false

    /**
     * Canais da lista atual associados a um canal de guia da conta (channelKey → associação). Por defeito
     * nenhum: cada canal usa o guia da lista, e só muda quando a pessoa escolhe outro (Definições → canal → Guia).
     */
    private val _epgMappings = MutableStateFlow<Map<String, EpgMapping>>(emptyMap())
    val epgMappings: StateFlow<Map<String, EpgMapping>> = _epgMappings.asStateFlow()
    private var mappingsLoadedFor: String? = null

    /** Logótipos do país escolhido em "Origem dos logótipos", para a lista em uso: ("playlistId:país", channelKey → URL). */
    private val _countryLogos = MutableStateFlow<Pair<String, Map<String, String>>?>(null)
    private var countryGuide: Pair<String, List<EpgGuideChannel>>? = null

    /**
     * Os logótipos que substituem os da lista (channelKey → URL), na lista em uso: os da [Prefs.logoSource]
     * e, por cima, os escolhidos canal a canal ("" num canal volta ao da lista). Canais fora do mapa usam o da lista.
     */
    val channelLogos: StateFlow<Map<String, String>> =
        combine(_content, _prefs, _epgMappings, _countryLogos) { content, p, mappings, country ->
            val playlistId = content?.playlist?.id ?: return@combine emptyMap()
            val logos = when {
                p.logoSource == Prefs.LogoSource.GUIDE -> mappings.mapNotNull { (key, m) -> m.icon?.let { key to it } }.toMap()
                Prefs.LogoSource.isCountry(p.logoSource) -> {
                    // Pelo nome, e por cima os canais com guia escolhido nesse país (acerta mesmo com o nome diferente).
                    val byName = country?.takeIf { it.first == "$playlistId:${p.logoSource}" }?.second.orEmpty()
                    byName + mappings.mapNotNull { (key, m) -> m.icon?.takeIf { m.country.equals(p.logoSource, ignoreCase = true) }?.let { key to it } }
                }
                else -> emptyMap()
            }.toMutableMap()
            val prefix = "$playlistId:"
            p.channelLogos.forEach { (k, url) ->
                if (k.startsWith(prefix)) {
                    val key = k.removePrefix(prefix)
                    if (url.isEmpty()) logos.remove(key) else logos[key] = url
                }
            }
            logos
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // Com um país em "Origem dos logótipos", vai buscar os canais de guia desse país e associa-os aos da lista
        // pelo tvg-id ou pelo nome (como em "Usar <país> na lista inteira", mas sem gravar associações).
        viewModelScope.launch {
            combine(_content, _prefs) { content, p -> content?.takeIf { Prefs.LogoSource.isCountry(p.logoSource) }?.let { it to p.logoSource } }
                .distinctUntilChanged { a, b -> a?.first === b?.first && a?.second == b?.second }
                .collectLatest { pair ->
                    val (content, country) = pair ?: return@collectLatest
                    if (!epgPickerAvailable) return@collectLatest
                    // Os canais de guia do país ficam em memória: uma lista atualizada só volta a associar.
                    val guide = countryGuide?.takeIf { it.first == country }?.second
                        ?: ((epgCountryChannels(country) as? EpgResult.Ok)?.value?.channels ?: return@collectLatest)
                            .filter { it.icon != null }
                            .also { countryGuide = country to it }
                    _countryLogos.value = "${content.playlist.id}:$country" to matchGuide(content.channels, guide).associate { (ch, g) -> ch.key to g.icon!! }
                }
        }
    }

    /** Muda quando chega guia novo: a grelha e as listas voltam a ler. */
    val epgVersion: StateFlow<Int> get() = c.epg.version

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var retryAction: () -> Unit = { startLoading(resume = true) }
    private var loadJob: Job? = null
    private var saveLastJob: Job? = null
    private var refreshJob: Job? = null

    /** Listas e guias já verificados desde que a app abriu (para "sempre que abrir a app"). */
    private val playlistsChecked = HashSet<String>()
    private val guidesChecked = HashSet<String>()
    /** Última tentativa de atualizar: "p:id" (lista) / "g:id" (guia). */
    private val lastAttempt = HashMap<String, Long>()
    private var startedOnce = false

    /** Último canal à espera de ser gravado: (playlistId, channelKey). */
    private var pendingLast: Pair<String, String>? = null

    /** A config da conta tal como veio (para manter a cópia em disco igual ao que muda localmente). */
    private var lastConfig: ConfigResponse? = null
    private val patchMutex = Mutex()

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
                val content = _content.value ?: continue
                if (_screen.value == Screen.Player || _screen.value == Screen.Settings) {
                    maybeRefreshPlaylist(content)
                    openGuide(content)
                }
            }
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            _reminders.value = c.cache.loadReminders().filter { it.startMs > now - REMINDER_GRACE_MS }
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
            _content.value != null && _playing.value != null -> _screen.value = Screen.Player
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
        loadJob?.cancel()
        refreshJob?.cancel()
        saveLastJob?.cancel()
        pendingLast = null
        pendingLocalProfile = lastConfig?.copy(settings = settings.deepCopy(), favorites = c.gson.toJsonTree(_favorites.value))
            ?: c.local.load()
        _local.value = false
        c.session.setLocal(false)
        c.home.clear()
        clearEpgMappings()
        _second.value = null
        _content.value = null
        _playing.value = null
        _playlists.value = emptyList()
        _favorites.value = emptyMap()
        _recents.value = emptyMap()
        settings = JsonObject()
        syncedSettings = JsonObject()
        settingsRev = null
        lastConfig = null
    }

    private fun startLoading(resume: Boolean) {
        retryAction = { startLoading(resume) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (_local.value) {
                // Em memória se já foi lida (acabou de se gravar uma lista), senão do disco.
                startLocal(lastConfig ?: c.local.load(), resume)
                return@launch
            }
            // Arranque rápido: com a config e a lista em cache, abre logo e atualiza por trás.
            if (resume && startFromCache()) return@launch

            _screen.value = Screen.Loading(I18n.s.loadingSession)
            try {
                if (!renewToken()) return@launch

                _screen.value = Screen.Loading(I18n.s.loadingPlaylists)
                val config = fetchConfig(null) ?: return@launch
                applyConfig(config)
                c.cache.saveConfig(config)
                pendingLocalProfile?.let { localProfile ->
                    pendingLocalProfile = null
                    adoptLocalProfile(localProfile)
                }

                val playlists = _playlists.value
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
        if (_playlists.value.isEmpty()) {
            _screen.value = Screen.Welcome
            return
        }
        loadPlaylist(lastPlaylist(), resume)
    }

    /**
     * Acabou de se ligar a conta numa TV que estava sem conta. As definições desta TV passam para a conta
     * se a conta ainda não tiver nenhumas; os favoritos passam para as listas da conta que são as mesmas
     * (mesmo servidor e utilizador, ou mesmo URL) e ainda não têm favoritos. As listas em si não passam:
     * o login da TV não pode criar listas na conta.
     */
    private fun adoptLocalProfile(local: ConfigResponse) {
        val localPlaylists = local.playlists.orEmpty().mapNotNull { it.toPlaylist() }
        val ids = localPlaylists.mapNotNull { lp -> _playlists.value.firstOrNull { sameSource(it, lp) }?.let { lp.id to it.id } }.toMap()
        // Os ids das listas locais são únicos ("local…"): trocá-los no JSON muda favoritos, grupos e recentes de uma vez.
        fun remap(json: JsonElement?): JsonObject? {
            val obj = json as? JsonObject ?: return null
            var text = obj.toString()
            ids.forEach { (from, to) -> text = text.replace("\"$from", "\"$to") }
            return JsonParser.parseString(text).asJsonObject
        }
        var moved = false
        val localSettings = remap(local.settings)
        if (settings.size() == 0 && localSettings != null && localSettings.size() > 0) {
            settings = localSettings
            applySettings()
            saveSettings()
            moved = true
        }
        val localFavorites = parseFavorites(remap(local.favorites)).filter { (id, keys) -> id in ids.values && keys.isNotEmpty() }
        val adopted = localFavorites.filterKeys { _favorites.value[it].isNullOrEmpty() }
        if (adopted.isNotEmpty()) {
            _favorites.value = _favorites.value + adopted
            patchUser("favorites", c.gson.toJsonTree(_favorites.value))
            persistConfigCache()
            moved = true
        }
        if (moved) showToast(I18n.s.localProfileMoved)
    }

    /** Abre com a config e a lista guardadas; a sessão e a config renovam-se por trás. */
    private suspend fun startFromCache(): Boolean {
        val config = c.cache.loadConfig() ?: return false
        applyConfig(config)
        if (_playlists.value.isEmpty()) return false
        val playlist = lastPlaylist()
        if (!c.cache.hasPlaylist(playlist)) return false
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
        try {
            c.backend.authRefresh().token?.let {
                c.token = it
                c.session.updateToken(it)
            }
        } catch (e: HttpException) {
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
        return true
    }

    /** A config da conta; null quando [etag] ainda é o atual (304). Erros HTTP saem como HttpException. */
    private suspend fun fetchConfig(etag: String?): ConfigResponse? {
        val res = c.backend.config(etag)
        if (res.code() == 304) {
            Log.i(TAG, "Config: sem alterações (304)")
            return null
        }
        val config = res.body() ?: throw HttpException(res)
        configEtag = res.headers()["ETag"]
        return config
    }

    /**
     * Manda à conta o índice de grupos e canais de [content] (para o site o poder organizar), se for diferente do
     * que ela tem. Corre por trás e nunca atrapalha a TV: sem rede ou com erro, tenta-se na próxima carga.
     */
    private fun uploadIndex(content: PlaylistContent) {
        if (_local.value || c.testMode) return
        val playlistId = content.playlist.id
        if (!indexHashes.containsKey(playlistId)) return // lista que a conta não conhece
        indexJob?.cancel()
        indexJob = viewModelScope.launch(Dispatchers.Default) {
            val index = ChannelIndex.of(content) ?: return@launch
            if (indexHashes[playlistId] == index.hash) return@launch
            try {
                val res = c.backend.putPlaylistIndex(playlistId, index.gzipBody().toRequestBody(JSON_MEDIA))
                if (res.isSuccessful) {
                    indexHashes[playlistId] = index.hash
                    Log.i(TAG, "Índice de canais enviado: ${content.channels.size} canais")
                } else {
                    Log.w(TAG, "Índice de canais: HTTP ${res.code()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Índice de canais: ${e.message}")
            }
        }
    }

    /** Lembra o `index_hash` que a conta tem de cada lista. */
    private fun rememberIndexHashes(config: ConfigResponse) {
        for (p in config.playlists.orEmpty()) p.id?.let { indexHashes[it] = p.indexHash }
    }

    private fun applyConfig(config: ConfigResponse) {
        rememberIndexHashes(config)
        lastConfig = config
        config.user?.id?.let { userId = it }
        config.user?.email?.let { _email.value = it }
        config.device?.name?.let { _deviceName.value = it }
        _playlists.value = config.playlists.orEmpty().mapNotNull { it.toPlaylist() }
        _favorites.value = parseFavorites(config.favorites)
        settings = (config.settings as? JsonObject) ?: JsonObject()
        syncedSettings = settings.deepCopy()
        settingsRev = config.settingsRev
        applySettings()
    }

    /** Lê de [settings] os recentes, o formato e as preferências. */
    private fun applySettings() {
        _recents.value = parseFavorites(settings.get(RECENTS_KEY))
        _streamFormat.value = settings.string("stream_format")
            ?.takeIf { it == StreamFormat.TS || it == StreamFormat.HLS }
        _prefs.value = Prefs.from(settings)
        applyLanguage()
    }

    private suspend fun lastPlaylist(): Playlist {
        // Um link do ecrã inicial manda na lista a abrir (se ainda existir).
        pendingLink?.first?.let { id -> _playlists.value.firstOrNull { it.id == id }?.let { return it } }
        val lastId = settings.string("last_playlist") ?: c.session.lastPlaylist()
        return _playlists.value.firstOrNull { it.id == lastId } ?: _playlists.value.first()
    }

    /**
     * Mostra [playlist]. Se estiver em cache abre logo e atualiza os canais por trás;
     * senão mostra "A carregar" enquanto a descarrega.
     */
    private suspend fun loadPlaylist(playlist: Playlist, resume: Boolean) {
        retryAction = { loadJob = viewModelScope.launch { loadPlaylist(playlist, resume) } }
        c.cache.loadPlaylist(playlist)?.let { cached ->
            showContent(cached, resume)
            maybeRefreshPlaylist(cached)
            return
        }
        _screen.value = Screen.Loading(I18n.s.loadingPlaylist(playlist.name))
        val loaded = try {
            c.loadPlaylist(playlist)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _screen.value = Screen.Loading("", explainPlaylistError(I18n.s, playlist.name, e))
            return
        }
        c.cache.savePlaylist(loaded)
        playlistsChecked += playlist.id
        showContent(loaded, resume)
    }

    /** Volta a descarregar os canais por trás se o intervalo das definições já passou. */
    private fun maybeRefreshPlaylist(content: PlaylistContent) {
        val id = content.playlist.id
        val now = System.currentTimeMillis()
        if (!RefreshPolicy.isDue(_prefs.value.playlistRefreshHours, content.fetchedAt, now, id in playlistsChecked)) return
        if (id in playlistsChecked && now - (lastAttempt["p:$id"] ?: 0L) < RefreshPolicy.RETRY_MS) return
        if (refreshJob?.isActive == true) return
        playlistsChecked += id
        lastAttempt["p:$id"] = now
        refreshPlaylistInBackground(content.playlist)
    }

    /** Abre o guia XMLTV da lista (do disco) e descarrega-o se o intervalo já passou; e o guia da conta. */
    private fun openGuide(content: PlaylistContent, force: Boolean = false, notify: Boolean = false) {
        val id = content.playlist.id
        openAccountGuide(content, force)
        c.epg.open(
            viewModelScope, content, force,
            isDue = { fetchedAt ->
                val now = System.currentTimeMillis()
                RefreshPolicy.isDue(_prefs.value.epgRefreshHours, fetchedAt, now, id in guidesChecked) &&
                    (id !in guidesChecked || now - (lastAttempt["g:$id"] ?: 0L) >= RefreshPolicy.RETRY_MS)
            },
            onDone = { ok ->
                guidesChecked += id
                lastAttempt["g:$id"] = System.currentTimeMillis()
                if (notify) showToast(if (ok) I18n.s.epgRefreshed else I18n.s.epgRefreshFailed)
            },
        )
        openExtraGuide(content, force)
    }

    /** O guia extra da lista (se o tiver), com as mesmas regras de renovação do guia da lista. */
    private fun openExtraGuide(content: PlaylistContent, force: Boolean = false) {
        val id = content.playlist.id
        c.epg.openExtra(
            viewModelScope, content, _prefs.value.extraEpg[id], force,
            isDue = { fetchedAt ->
                val now = System.currentTimeMillis()
                RefreshPolicy.isDue(_prefs.value.epgRefreshHours, fetchedAt, now, id in extraGuidesChecked) &&
                    (id !in extraGuidesChecked || now - (lastAttempt["x:$id"] ?: 0L) >= RefreshPolicy.RETRY_MS)
            },
            onDone = {
                extraGuidesChecked += id
                lastAttempt["x:$id"] = System.currentTimeMillis()
            },
        )
    }

    private val extraGuidesChecked = HashSet<String>()

    /** Guia XMLTV extra de [playlistId] ([url] vazio ou null tira-o); na lista em uso, descarrega-se já. */
    fun setExtraEpg(playlistId: String, url: String?) {
        val clean = url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val current = _prefs.value.extraEpg
        setPref(Prefs.Key.EXTRA_EPG, if (clean == null) current - playlistId else current + (playlistId to clean))
        _content.value?.takeIf { it.playlist.id == playlistId }?.let { openExtraGuide(it, force = true) }
    }

    /** A app voltou ao primeiro plano: conta como "abrir a app" para as atualizações. */
    fun onAppStart() {
        if (!startedOnce) {
            startedOnce = true
            return
        }
        playlistsChecked.clear()
        guidesChecked.clear()
        extraGuidesChecked.clear()
        mappingsLoadedFor = null // as associações podem ter mudado noutra TV
        val content = _content.value ?: return
        if (loadJob?.isActive == true) return
        maybeRefreshPlaylist(content)
        openGuide(content)
    }

    private suspend fun showContent(loaded: PlaylistContent, resume: Boolean) {
        val playlist = loaded.playlist
        _second.value?.let { sv ->
            // A lista principal passou a ser a da conta do segundo canal, e o plano só tem 1 ligação.
            val twoAllowed = playlist.type != PlaylistType.XTREAM || (loaded.account?.maxConnections ?: 0) >= 2
            if (sameAccount(sv.content.playlist, playlist) && !twoAllowed) {
                _second.value = null
                showToast(I18n.s.secondClosedSameAccount)
            }
        }
        c.epg.clearShort()
        openGuide(loaded)
        _content.value = loaded
        uploadIndex(loaded)
        _selectedCategory.value = SpecialCategory.ALL
        _playing.value = null
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

        if (resume) {
            val lastKey = if (settings.string("last_playlist") == playlist.id) {
                settings.string("last_channel")
            } else {
                c.session.lastChannel().takeIf { c.session.lastPlaylist() == playlist.id }
            }
            val channel = lastKey?.let { loaded.byKey[it] }?.takeIf { !isGroupLocked(it.categoryId) }
            if (channel != null) {
                openPanelOnStart = _prefs.value.startup == Prefs.Startup.LIST
                play(channel, channel.categoryId)
                return
            }
        }
        // Nada para retomar: começa pelo primeiro canal e mostra a lista para escolher.
        val first = channelsOf(SpecialCategory.ALL).firstOrNull() ?: loaded.channels.first()
        openPanelOnStart = true
        play(first, SpecialCategory.ALL)
    }

    /** Volta a descarregar [playlist] sem interromper o que está a tocar, e troca os canais. */
    private fun refreshPlaylistInBackground(playlist: Playlist, notify: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val fresh = try {
                c.loadPlaylist(playlist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (notify) showToast(I18n.s.playlistLoadFailed(playlist.name, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            if (_content.value?.playlist != playlist || fresh.channels.isEmpty()) return@launch
            c.cache.savePlaylist(fresh)
            val guideChanged = fresh.guideUrl != _content.value?.guideUrl
            _content.value = fresh
            uploadIndex(fresh)
            if (guideChanged) openGuide(fresh)
            // O canal a tocar continua o mesmo (pela chave); o zapping passa a usar a lista nova.
            _playing.value?.let { p -> fresh.byKey[p.key]?.let { _playing.value = it } }
            // Se o grupo do canal deixou de existir (o fornecedor mudou a lista), o painel abre em "Todos".
            val group = _selectedCategory.value
            if (group != SpecialCategory.ALL && group != SpecialCategory.FAVORITES && fresh.byCategory[group].isNullOrEmpty()) {
                _selectedCategory.value = SpecialCategory.ALL
            }
            zapList = channelsOf(_selectedCategory.value).ifEmpty { listOfNotNull(_playing.value) }
            if (notify) showToast(I18n.s.playlistUpdated)
        }
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
        if (_local.value || c.token == null) {
            saveLocalPlaylist(id, form)
            return null
        }
        if (c.testMode) return "Test mode"
        val saved = try {
            val body = playlistBody(form.toPlaylist(id.orEmpty()))
            if (id == null) c.backend.createPlaylist(body) else c.backend.updatePlaylist(id, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return playlistError(e)
        }
        val playlist = saved.toPlaylist() ?: return I18n.s.playlistRejected
        // Lê as listas da conta (uma editada em uso recarrega com os dados novos; ver syncConfig).
        syncConfig()
        when {
            _content.value == null -> {
                settings.addProperty("last_playlist", playlist.id)
                saveSettings()
                startLoading(resume = false)
            }
            // A lista nova passa a ser a que se vê.
            id == null -> switchPlaylist(playlist)
        }
        return null
    }

    /** Apaga uma lista: da conta (em todas as TVs) ou desta TV. Devolve o erro a mostrar, ou null. */
    suspend fun deletePlaylist(playlist: Playlist): String? {
        if (_local.value || c.token == null) {
            deleteLocalPlaylist(playlist)
            return null
        }
        if (c.testMode) return "Test mode"
        try {
            c.backend.deletePlaylist(playlist.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() != 404) return playlistError(e) // 404: já não existia
        } catch (e: Exception) {
            return playlistError(e)
        }
        c.cache.removePlaylist(playlist.id)
        syncConfig()
        // Era a última: mostra o aviso de conta sem listas (com o botão para adicionar).
        if (_playlists.value.isEmpty()) {
            loadJob?.cancel()
            refreshJob?.cancel()
            _second.value = null
            _content.value = null
            _playing.value = null
            startLoading(resume = false)
        }
        return null
    }

    /** O corpo de uma lista para a conta (os campos do outro tipo vão vazios, como no site). */
    private fun playlistBody(p: Playlist) = JsonObject().apply {
        val xtream = p.type == PlaylistType.XTREAM
        addProperty("type", if (xtream) "xtream" else "m3u")
        addProperty("name", p.name)
        addProperty("server", if (xtream) p.server.orEmpty() else "")
        addProperty("username", if (xtream) p.username.orEmpty() else "")
        addProperty("password", if (xtream) p.password.orEmpty() else "")
        addProperty("url", if (xtream) "" else p.url.orEmpty())
        addProperty("epg_url", p.epgUrl.orEmpty())
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
        val first = !_local.value || _content.value == null
        if (first) _screen.value = Screen.Loading(I18n.s.loadingPlaylist(playlist.name))
        viewModelScope.launch {
            if (!_local.value || lastConfig == null) {
                // Definições e favoritos de quando já se usou a TV sem conta continuam lá.
                applyConfig(c.local.load())
                enterLocal()
            }
            val current = _playlists.value
            _playlists.value = if (current.any { it.id == playlist.id }) {
                current.map { if (it.id == playlist.id) playlist else it }
            } else {
                current + playlist
            }
            persistConfigCache()
            when {
                first -> {
                    settings.addProperty("last_playlist", playlist.id)
                    saveSettings()
                    startLoading(resume = false)
                }
                // A lista nova passa a ser a que se vê; a editada em uso recarrega com os dados novos.
                id == null -> switchPlaylist(playlist)
                id == _content.value?.playlist?.id -> switchPlaylist(playlist, force = true)
            }
        }
    }

    /** Sem conta: apaga uma lista desta TV (e os favoritos dela). Sem listas, volta às boas-vindas. */
    private fun deleteLocalPlaylist(playlist: Playlist) {
        val remaining = _playlists.value.filter { it.id != playlist.id }
        _playlists.value = remaining
        _favorites.value = _favorites.value - playlist.id
        if (_recents.value.containsKey(playlist.id)) {
            _recents.value = _recents.value - playlist.id
            settings.add(RECENTS_KEY, c.gson.toJsonTree(_recents.value))
        }
        saveSettings()
        viewModelScope.launch { c.cache.removePlaylist(playlist.id) }
        when {
            remaining.isEmpty() -> {
                loadJob?.cancel()
                refreshJob?.cancel()
                _second.value = null
                _content.value = null
                _playing.value = null
                viewModelScope.launch { c.home.clear() }
                _screen.value = Screen.Welcome
            }
            playlist.id == _content.value?.playlist?.id -> switchPlaylist(remaining.first())
            _second.value?.content?.playlist?.id == playlist.id -> _second.value = null
        }
    }

    /** Esquece o guia em cache; os programas são pedidos de novo. */
    fun refreshEpg() {
        c.epg.clearShort()
        val content = _content.value
        content?.let { openAccountGuide(it, force = true) }
        if (content?.guideUrl != null) {
            showToast(I18n.s.epgRefreshing)
            openGuide(content, force = true, notify = true)
        } else {
            showToast(I18n.s.epgRefreshed)
        }
    }

    /** Volta a descarregar a lista atual (ex.: o fornecedor mudou canais). */
    fun reloadPlaylist() {
        val playlist = _content.value?.playlist ?: return
        refreshPlaylistInBackground(playlist, notify = true)
    }

    /** true uma única vez depois de carregar sem canal para retomar. */
    fun consumeOpenPanelOnStart(): Boolean = openPanelOnStart.also { openPanelOnStart = false }

    /**
     * Volta a ler a config da conta (listas, favoritos, definições) sem recarregar a lista atual.
     * Chamado ao voltar à app e ao abrir as Definições, para apanhar listas novas do site.
     */
    fun refreshConfig() {
        if (_local.value || _content.value == null || loadJob?.isActive == true) return
        viewModelScope.launch { syncConfig() }
    }

    /** Lê a config da conta e reage a listas apagadas ou alteradas no site. */
    private suspend fun syncConfig() {
        val config = try {
            fetchConfig(configEtag) ?: return // 304: nada mudou na conta
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // sem rede: fica o que já havia
        }
        c.cache.saveConfig(config)
        lastConfig = config
        rememberIndexHashes(config)
        config.device?.name?.let { _deviceName.value = it }
        val playlists = config.playlists.orEmpty().mapNotNull { it.toPlaylist() }
        _playlists.value = playlists
        _favorites.value = parseFavorites(config.favorites)
        (config.settings as? JsonObject)?.let {
            settings = it
            syncedSettings = it.deepCopy()
            settingsRev = config.settingsRev
            applySettings()
        }
        val current = _content.value?.playlist ?: return
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
        if (!force && playlist.id == _content.value?.playlist?.id) {
            if (_screen.value == Screen.Settings) closeSettings()
            return
        }
        settings.addProperty("last_playlist", playlist.id)
        saveSettings()
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadPlaylist(playlist, resume = false) }
    }

    // ---- Canais e grupos ---------------------------------------------------------------------------

    fun channelsOf(categoryId: String): List<Channel> {
        val content = _content.value ?: return emptyList()
        val list = when (categoryId) {
            // Os canais de grupos bloqueados não aparecem em "Todos" nem nos favoritos; os escondidos em lado nenhum.
            SpecialCategory.ALL -> content.channels.filterNot { isGroupLocked(it.categoryId) || isGroupHidden(it.categoryId) || isChannelHidden(it) }
            SpecialCategory.FAVORITES ->
                _favorites.value[content.playlist.id].orEmpty().mapNotNull { content.byKey[it] }
                    .filterNot { isGroupLocked(it.categoryId) || isGroupHidden(it.categoryId) || isChannelHidden(it) }
            // Pela ordem em que foram vistos (não se ordenam).
            SpecialCategory.RECENT -> return _recents.value[content.playlist.id].orEmpty().mapNotNull { content.byKey[it] }
                .filterNot { isGroupLocked(it.categoryId) || isGroupHidden(it.categoryId) || isChannelHidden(it) }
            // Um grupo com ordem escolhida fica por essa ordem (mesmo com "Ordenar por nome").
            else -> {
                val ordered = orderedChannels(categoryId, includeHidden = false)
                if (hasChannelOrder(categoryId)) return ordered
                ordered
            }
        }
        return if (_prefs.value.sort == Prefs.Sort.NAME) list.sortedBy { it.name.lowercase() } else list
    }

    // ---- Canais: esconder e reordenar (por grupo) --------------------------------------------

    fun isChannelHidden(channel: Channel): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        return _prefs.value.isChannelHidden(playlistId, channel.key)
    }

    private fun channelOrderKey(groupId: String): String? = _content.value?.playlist?.id?.let { "$it:$groupId" }

    fun hasChannelOrder(groupId: String): Boolean = channelOrderKey(groupId)?.let { it in _prefs.value.channelOrder } == true

    /** Canais do grupo pela ordem escolhida (os novos ficam no fim, pela ordem da lista). */
    fun orderedChannels(groupId: String, includeHidden: Boolean): List<Channel> {
        val content = _content.value ?: return emptyList()
        val list = content.byCategory[groupId].orEmpty()
        val order = channelOrderKey(groupId)?.let { _prefs.value.channelOrder[it] }.orEmpty()
        val ordered = if (order.isEmpty()) {
            list
        } else {
            val position = order.withIndex().associate { (i, key) -> key to i }
            list.withIndex().sortedBy { (i, ch) -> position[ch.key] ?: (order.size + i) }.map { it.value }
        }
        return if (includeHidden) ordered else ordered.filterNot { isChannelHidden(it) }
    }

    fun setChannelHidden(channel: Channel, hidden: Boolean) {
        val playlistId = _content.value?.playlist?.id ?: return
        val key = "$playlistId:${channel.key}"
        val current = _prefs.value.hiddenChannels
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
        setPref(Prefs.Key.CHANNEL_ORDER, _prefs.value.channelOrder + (orderKey to keys))
    }

    /** O grupo volta à ordem da lista. */
    fun resetChannelOrder(groupId: String) {
        val orderKey = channelOrderKey(groupId) ?: return
        if (orderKey !in _prefs.value.channelOrder) return
        setPref(Prefs.Key.CHANNEL_ORDER, _prefs.value.channelOrder - orderKey)
    }

    /** Os grupos voltam à ordem da lista. */
    fun resetGroupOrder() {
        val playlistId = _content.value?.playlist?.id ?: return
        if (playlistId !in _prefs.value.groupOrder) return
        setPref(Prefs.Key.GROUP_ORDER, _prefs.value.groupOrder - playlistId)
    }

    // ---- Grupos: esconder e reordenar ------------------------------------------------------

    fun isGroupHidden(groupId: String): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        return _prefs.value.isHidden(playlistId, groupId)
    }

    /** Grupos da lista atual pela ordem escolhida (os novos ficam no fim, pela ordem da lista). */
    fun orderedGroups(includeHidden: Boolean): List<Category> {
        val content = _content.value ?: return emptyList()
        val order = _prefs.value.groupOrder[content.playlist.id].orEmpty()
        val position = order.withIndex().associate { (i, id) -> id to i }
        return content.categories
            .sortedBy { position[it.id] ?: (order.size + content.categories.indexOf(it)) }
            .filter { includeHidden || !isGroupHidden(it.id) }
    }

    /** Grupos que o guia percorre (← →): os especiais visíveis e os normais sem os escondidos nem bloqueados. */
    fun guideGroups(): List<Category> {
        val s = I18n.s
        val p = _prefs.value
        return listOfNotNull(
            Category(SpecialCategory.FAVORITES, s.favoritesStar).takeIf { p.showFavoritesGroup && channelsOf(SpecialCategory.FAVORITES).isNotEmpty() },
            Category(SpecialCategory.RECENT, s.recents).takeIf { p.showRecentGroup && channelsOf(SpecialCategory.RECENT).isNotEmpty() },
            Category(SpecialCategory.ALL, s.all),
        ) + orderedGroups(includeHidden = false)
            .filterNot { isGroupLocked(it.id) }
            .map { if (it.id == PlaylistContent.OTHERS) it.copy(name = s.others) else it }
    }

    fun setGroupHidden(groupId: String, hidden: Boolean) {
        val playlistId = _content.value?.playlist?.id ?: return
        val key = "$playlistId:$groupId"
        val current = _prefs.value.hiddenGroups
        setPref(Prefs.Key.HIDDEN_GROUPS, (if (hidden) current + key else current - key).toList())
    }

    /** Move um grupo [delta] posições (−1 = para cima). */
    fun moveGroup(groupId: String, delta: Int) {
        val playlistId = _content.value?.playlist?.id ?: return
        val ids = orderedGroups(includeHidden = true).map { it.id }.toMutableList()
        val from = ids.indexOf(groupId)
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (from < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        setPref(Prefs.Key.GROUP_ORDER, _prefs.value.groupOrder + (playlistId to ids))
    }

    /** Bloqueado pelo controlo parental e ainda não desbloqueado nesta sessão. */
    fun isGroupLocked(groupId: String): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        return _prefs.value.isLocked(playlistId, groupId) && "$playlistId:$groupId" !in _unlocked.value
    }

    /** Confere o PIN; se estiver certo, desbloqueia [groupId] até a app fechar. */
    fun unlockGroup(groupId: String, pin: String): Boolean {
        if (Prefs.hashPin(pin) != _prefs.value.pinHash) return false
        val playlistId = _content.value?.playlist?.id ?: return true
        _unlocked.value = _unlocked.value + "$playlistId:$groupId"
        return true
    }

    fun checkPin(pin: String): Boolean = _prefs.value.pinHash == null || Prefs.hashPin(pin) == _prefs.value.pinHash

    /** Define (ou remove, com null) o PIN parental. Sem PIN não há grupos bloqueados. */
    fun setPin(pin: String?) {
        setPref(Prefs.Key.PIN_HASH, pin?.let { Prefs.hashPin(it) })
        if (pin == null) setPref(Prefs.Key.LOCKED_GROUPS, emptyList<String>())
        _unlocked.value = emptySet()
    }

    fun toggleGroupLock(groupId: String) {
        val playlistId = _content.value?.playlist?.id ?: return
        val key = "$playlistId:$groupId"
        val current = _prefs.value.lockedGroups
        setPref(Prefs.Key.LOCKED_GROUPS, (if (key in current) current - key else current + key).toList())
    }

    /** Muda uma definição da conta: String, Boolean, Int, lista de Strings, ou null para repor o padrão. */
    fun setPref(key: String, value: Any?) {
        when (value) {
            null -> settings.remove(key)
            is String -> settings.addProperty(key, value)
            is Boolean -> settings.addProperty(key, value)
            is Int -> settings.addProperty(key, value)
            is Collection<*> -> settings.add(key, JsonArray().apply { value.forEach { add(it.toString()) } })
            is Map<*, *> -> settings.add(
                key,
                JsonObject().apply {
                    value.forEach { (k, v) ->
                        if (v is Collection<*>) add(k.toString(), JsonArray().apply { v.forEach { add(it.toString()) } }) else addProperty(k.toString(), v.toString())
                    }
                },
            )
            else -> error("Tipo não suportado: ${value::class}")
        }
        _prefs.value = Prefs.from(settings)
        applyLanguage()
        saveSettings()
    }

    fun isFavorite(channel: Channel): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        return channel.key in _favorites.value[playlistId].orEmpty()
    }

    /** Adiciona/remove dos favoritos e grava logo na conta. */
    fun toggleFavorite(channel: Channel) {
        val playlistId = _content.value?.playlist?.id ?: return
        val current = _favorites.value[playlistId].orEmpty()
        val added = channel.key !in current
        val updated = if (added) current + channel.key else current - channel.key
        _favorites.value = _favorites.value + (playlistId to updated)
        showToast(if (added) I18n.s.favoriteAdded(channel.name) else I18n.s.favoriteRemoved(channel.name))
        patchUser("favorites", c.gson.toJsonTree(_favorites.value))
        persistConfigCache()
    }

    suspend fun epgFor(channel: Channel): List<EpgEntry> {
        val content = _content.value ?: return emptyList()
        return c.epg.shortEpg(content.playlist, channel).shifted()
    }

    fun cachedEpg(channel: Channel): List<EpgEntry> {
        val content = _content.value ?: return emptyList()
        return c.epg.cached(content.playlist, channel).orEmpty().shifted()
    }

    /** Desvio horário do guia (servidores com o fuso errado). */
    private fun List<EpgEntry>.shifted(): List<EpgEntry> {
        val offset = _prefs.value.epgOffsetMin * 60_000L
        return if (offset == 0L) this else map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
    }

    suspend fun xtreamConnections(): Pair<Int, Int>? {
        val content = _content.value ?: return null
        if (content.playlist.type != PlaylistType.XTREAM) return null
        return c.xtream.connections(content.playlist)
    }

    // ---- Guia da conta: trocar o guia de um canal ----------------------------------------------
    // Só com conta. Dados do IPTV-EPG.org no servidor da conta (ver EpgRepository.openAccount).

    /** Escolher o guia de um canal só existe com conta GOAT. */
    val epgPickerAvailable: Boolean get() = !_local.value && c.token != null

    /** Lê as associações da lista (uma vez por lista e por abertura da app) e abre o guia da conta. */
    private fun openAccountGuide(content: PlaylistContent, force: Boolean = false) {
        if (!epgPickerAvailable) return
        val id = content.playlist.id
        viewModelScope.launch {
            if (mappingsLoadedFor != id) {
                if (_epgMappings.value.isNotEmpty() && _content.value?.playlist?.id != id) _epgMappings.value = emptyMap()
                val mappings = try {
                    c.backend.epgMappings(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    if (e.code() == 404) emptyMap() else null
                } catch (e: Exception) {
                    null
                }
                if (mappings == null) {
                    // Sem rede: usa o guia da conta que houver em disco (e tenta outra vez mais tarde).
                    c.epg.openAccount(viewModelScope, id, mapped = true)
                    return@launch
                }
                mappingsLoadedFor = id
                _epgMappings.value = mappings
            }
            c.epg.openAccount(viewModelScope, id, mapped = _epgMappings.value.isNotEmpty(), force = force)
        }
    }

    private fun clearEpgMappings() {
        c.epg.closeAccount()
        _epgMappings.value = emptyMap()
        mappingsLoadedFor = null
    }

    /** Resultado de um pedido ao guia da conta: o valor, ou a mensagem de erro a mostrar. */
    sealed interface EpgResult<out T> {
        data class Ok<T>(val value: T) : EpgResult<T>
        data class Error(val message: String) : EpgResult<Nothing>
    }

    private suspend fun <T> epgCall(block: suspend () -> T): EpgResult<T> = try {
        EpgResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        EpgResult.Error(I18n.s.epgFailed(e.code().toString()))
    } catch (e: Exception) {
        EpgResult.Error(I18n.s.epgOffline)
    }

    /** Países com um canal de guia com esse nome (o país da pessoa primeiro). */
    suspend fun epgSearch(query: String): EpgResult<List<EpgCountryResult>> =
        if (query.trim().length < 2) EpgResult.Ok(emptyList()) else epgCall { c.backend.epgSearch(query.trim()).map { it.unescaped() } }

    /** Canais de guia prováveis para [channel] (só para mostrar como opções; nada se associa sozinho). */
    suspend fun epgSuggest(channel: Channel): EpgResult<List<EpgGuideChannel>> {
        val id = _content.value?.playlist?.id ?: return EpgResult.Ok(emptyList())
        return epgCall { c.backend.epgSuggest(id, channel.key, channel.name).map { it.unescaped() } }
    }

    suspend fun epgCountries(): EpgResult<List<EpgCountryInfo>> = epgCall { c.backend.epgCountries().map { it.unescaped() } }

    /**
     * A lista inteira passa a usar o guia de [country]: cada canal com o mesmo tvg-id ou o mesmo nome
     * (normalizado: "PT: SIC HD" = "SIC") que um canal de guia desse país fica associado a ele.
     * Os canais já trocados um a um ficam como estão; os que não têm par continuam com o guia da lista.
     */
    fun applyEpgCountry(country: String, countryName: String) {
        val content = _content.value ?: return
        val playlistId = content.playlist.id
        if (c.testMode) {
            showToast("Test mode")
            return
        }
        showToast(I18n.s.epgCountryApplying(countryName))
        viewModelScope.launch {
            val guide = when (val r = epgCountryChannels(country)) {
                is EpgResult.Error -> {
                    showToast(r.message)
                    return@launch
                }
                is EpgResult.Ok -> r.value.channels.orEmpty().filter { it.id != null }
            }
            val already = _epgMappings.value
            val matches = matchGuide(content.channels.filter { it.key !in already }, guide)
            if (matches.isEmpty()) {
                showToast(I18n.s.epgCountryNone(countryName))
                return@launch
            }
            var added = 0
            var error: String? = null
            // Poucos pedidos de cada vez e com pausa: o servidor aceita 300 pedidos em 10 s.
            for (chunk in matches.chunked(EPG_BATCH)) {
                val results = coroutineScope {
                    chunk.map { (ch, g) ->
                        async { (ch to g) to epgCall { c.backend.putEpgMapping(EpgMappingRequest(playlistId, ch.key, country, g.id!!, ch.name)) } }
                    }.awaitAll()
                }
                results.forEach { (pair, r) ->
                    val (ch, g) = pair
                    if (r is EpgResult.Ok) {
                        _epgMappings.value = _epgMappings.value + (ch.key to EpgMapping(country, g.id, ch.name, g.name))
                        added++
                    } else {
                        error = (r as EpgResult.Error).message
                    }
                }
                if (added == 0 && error != null) break
                delay(EPG_BATCH_PAUSE_MS)
            }
            mappingsLoadedFor = playlistId
            showToast(if (added > 0) I18n.s.epgCountryDone(added, countryName) else error ?: I18n.s.epgOffline)
            if (added == 0) return@launch
            c.epg.openAccount(viewModelScope, playlistId, mapped = true, force = true)
            // O país pode não estar em uso ainda: os programas chegam ao servidor no minuto seguinte.
            delay(EPG_NEW_COUNTRY_MS)
            if (_content.value?.playlist?.id == playlistId) c.epg.openAccount(viewModelScope, playlistId, mapped = _epgMappings.value.isNotEmpty(), force = true)
        }
    }

    /** Pares canal da lista → canal de guia com o mesmo tvg-id ou o mesmo nome (normalizado: "PT: SIC HD" = "SIC"). */
    private fun matchGuide(channels: List<Channel>, guide: List<EpgGuideChannel>): List<Pair<Channel, EpgGuideChannel>> {
        val byId = guide.filter { it.id != null }.associateBy { it.id!!.lowercase() }
        val byName = HashMap<String, EpgGuideChannel>()
        guide.forEach { g -> g.name?.let { XmltvGuide.normalizeName(it) }?.takeIf { it.isNotEmpty() }?.let { byName.putIfAbsent(it, g) } }
        return channels.asSequence()
            .mapNotNull { ch -> (ch.epgId?.lowercase()?.let(byId::get) ?: byName[XmltvGuide.normalizeName(ch.name)])?.let { ch to it } }
            .distinctBy { it.first.key }
            .toList()
    }

    /** Logótipo escolhido para [channel] na lista em uso: um URL, "" para o da lista, ou null para seguir a origem geral. */
    fun channelLogoChoice(channel: Channel): String? {
        val playlistId = _content.value?.playlist?.id ?: return null
        return _prefs.value.channelLogos["$playlistId:${channel.key}"]
    }

    fun setChannelLogo(channel: Channel, url: String?) {
        val playlistId = _content.value?.playlist?.id ?: return
        val key = "$playlistId:${channel.key}"
        val current = _prefs.value.channelLogos
        setPref(Prefs.Key.CHANNEL_LOGOS, if (url == null) current - key else current + (key to url))
    }

    /** Todos os canais da lista voltam ao guia da lista. */
    fun resetEpgMappings() {
        val playlistId = _content.value?.playlist?.id ?: return
        if (c.testMode) {
            showToast("Test mode")
            return
        }
        viewModelScope.launch {
            var error: String? = null
            for (chunk in _epgMappings.value.keys.toList().chunked(EPG_BATCH)) {
                val results = coroutineScope {
                    chunk.map { key ->
                        async {
                            key to epgCall {
                                try {
                                    c.backend.deleteEpgMapping(playlistId, key)
                                } catch (e: HttpException) {
                                    if (e.code() != 404) throw e
                                }
                            }
                        }
                    }.awaitAll()
                }
                results.forEach { (key, r) -> if (r is EpgResult.Ok) _epgMappings.value = _epgMappings.value - key else error = (r as EpgResult.Error).message }
                delay(EPG_BATCH_PAUSE_MS)
            }
            showToast(error ?: I18n.s.epgResetDone)
            c.epg.openAccount(viewModelScope, playlistId, mapped = _epgMappings.value.isNotEmpty(), force = true)
        }
    }

    /** Todos os canais de guia de um país. */
    suspend fun epgCountryChannels(country: String): EpgResult<EpgCountryResult> = epgCall { c.backend.epgCountryChannels(country).unescaped() }

    /** O guia de [channel] passa a ser o do canal de guia [guide] (de [country]). Grava na conta. */
    suspend fun setEpgMapping(channel: Channel, country: String, guide: EpgGuideChannel): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        val guideId = guide.id ?: return false
        if (c.testMode) {
            showToast("Test mode")
            return false
        }
        val result = epgCall { c.backend.putEpgMapping(EpgMappingRequest(playlistId, channel.key, country, guideId, channel.name)) }
        if (result is EpgResult.Error) {
            showToast(result.message)
            return false
        }
        val guideName = guide.name ?: guideId
        _epgMappings.value = _epgMappings.value + (channel.key to EpgMapping(country, guideId, channel.name, guideName))
        mappingsLoadedFor = playlistId
        showToast(I18n.s.epgMapped(channel.name, guideName))
        c.epg.openAccount(viewModelScope, playlistId, mapped = true, force = true)
        // País que ainda não estava em uso: o servidor descarrega os programas no minuto seguinte.
        if (guide.now == null) {
            viewModelScope.launch {
                delay(EPG_NEW_COUNTRY_MS)
                if (_content.value?.playlist?.id == playlistId) c.epg.openAccount(viewModelScope, playlistId, mapped = _epgMappings.value.isNotEmpty(), force = true)
            }
        }
        return true
    }

    /** [channel] volta ao guia da lista. */
    suspend fun removeEpgMapping(channel: Channel): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        if (c.testMode) {
            showToast("Test mode")
            return false
        }
        val result = epgCall {
            try {
                c.backend.deleteEpgMapping(playlistId, channel.key)
            } catch (e: HttpException) {
                // 404: já não estava associado (ex.: mudou noutra TV); o resultado é o mesmo.
                if (e.code() != 404) throw e
            }
        }
        if (result is EpgResult.Error) {
            showToast(result.message)
            return false
        }
        _epgMappings.value = _epgMappings.value - channel.key
        showToast(I18n.s.epgUnmapped(channel.name))
        c.epg.openAccount(viewModelScope, playlistId, mapped = _epgMappings.value.isNotEmpty(), force = true)
        return true
    }

    // ---- Player ----------------------------------------------------------------------------

    /** Põe [channel] a tocar; CH+/CH− passam a percorrer o grupo [groupId]. */
    fun play(channel: Channel, groupId: String) {
        _selectedCategory.value = groupId
        zapList = channelsOf(groupId).ifEmpty { listOf(channel) }
        _catchup.value = null
        setPlaying(channel, explicit = true)
        _screen.value = Screen.Player
    }

    /** Muda de canal dentro da categoria atual (dá a volta nas pontas). */
    fun zap(delta: Int) {
        val current = _playing.value ?: return
        if (zapList.size < 2) return
        val index = zapList.indexOfFirst { it.uid == current.uid }.coerceAtLeast(0)
        val next = zapList[(index + delta).mod(zapList.size)]
        _catchup.value = null
        setPlaying(next)
    }

    /**
     * Troca o canal no ecrã e decide qual fica como "anterior" (→): o que se via, se se ficou nele uns segundos
     * (passar por canais no zapping não conta), ou sempre que se salta de propósito com [explicit].
     */
    private fun setPlaying(next: Channel, explicit: Boolean = false) {
        val old = _playing.value
        val now = System.currentTimeMillis()
        if (old != null && old.key != next.key && (explicit || now - playingSince >= WATCHED_MS)) previous = old
        if (old?.key != next.key) playingSince = now
        _playing.value = next
        scheduleSaveLast(next)
    }

    /** O último canal visto antes do atual: o da sessão, ou o dos Recentes (ex.: logo ao abrir a app). */
    fun previousChannel(): Channel? {
        val content = _content.value ?: return null
        val current = _playing.value?.key
        previous?.let { p -> content.byKey[p.key]?.takeIf { it.key != current && !isGroupLocked(it.categoryId) }?.let { return it } }
        return _recents.value[content.playlist.id].orEmpty().asSequence()
            .filter { it != current }
            .mapNotNull { content.byKey[it] }
            .firstOrNull { !isGroupLocked(it.categoryId) }
    }

    /** → no player: volta ao canal anterior (e carregar outra vez regressa). */
    fun playPrevious(): Boolean {
        val channel = previousChannel() ?: return false
        _selectedCategory.value = groupFor(channel)
        zapList = channelsOf(_selectedCategory.value).ifEmpty { listOf(channel) }
        _catchup.value = null
        setPlaying(channel, explicit = true)
        _screen.value = Screen.Player
        return true
    }

    /**
     * Link do ecrã inicial (`itv://play?playlist=…&channel=…`): abre esse canal. Se a lista ainda não está
     * aberta (a app está a arrancar, ou o canal é de outra lista), fica pendente até carregar.
     */
    fun openLink(playlistId: String, channelKey: String) {
        val content = _content.value
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
            _playlists.value.firstOrNull { it.id == playlistId }?.let { switchPlaylist(it) }
        }
    }

    /** Põe os canais recentes no ecrã inicial (o primeiro também no "A seguir"). */
    private fun publishHome() {
        val content = _content.value ?: return
        val items = channelsOf(SpecialCategory.RECENT).take(HOME_ITEMS).map { ch ->
            val now = System.currentTimeMillis()
            // O launcher mostra o mesmo logótipo que a app.
            val logo = channelLogos.value[ch.key]
            HomeItem(if (logo != null) ch.copy(logo = logo) else ch, content.playlist.id, guideEntries(ch).firstOrNull { it.startMs <= now && it.endMs > now }?.title)
        }
        viewModelScope.launch { c.home.update(items) }
    }

    /** Grupo onde [channel] fica a fazer zapping: o atual, se o tiver, senão o dele. */
    fun groupFor(channel: Channel): String {
        val selected = _selectedCategory.value
        return if (channelsOf(selected).any { it.uid == channel.uid }) selected else channel.categoryId
    }

    /** Canal com o número [n] (teclas numéricas do comando). Os de grupos bloqueados não contam. */
    fun channelByNumber(n: Int): Channel? =
        _content.value?.channels?.firstOrNull { it.num == n && !isGroupLocked(it.categoryId) && !isChannelHidden(it) }

    fun playNumber(n: Int): Boolean {
        val channel = channelByNumber(n) ?: return false
        _selectedCategory.value = SpecialCategory.ALL
        zapList = channelsOf(SpecialCategory.ALL).ifEmpty { listOf(channel) }
        _catchup.value = null
        setPlaying(channel, explicit = true)
        return true
    }

    /** Resultado da pesquisa: um canal, ou um programa (a dar agora ou mais logo) num canal. */
    data class SearchHit(val channel: Channel, val programme: EpgEntry? = null)

    /** Procura canais pelo nome e programas pelo título (sem acentos nem maiúsculas). */
    fun search(query: String): List<SearchHit> {
        val content = _content.value ?: return emptyList()
        val q = searchKey(query)
        if (q.length < 2) return emptyList()
        val visible = content.channels.filterNot { isGroupLocked(it.categoryId) || isGroupHidden(it.categoryId) || isChannelHidden(it) }
        val channels = visible.asSequence().filter { q in searchKey(it.name) }.take(MAX_SEARCH).map { SearchHit(it) }.toList()
        val now = System.currentTimeMillis()
        val until = now + 12 * 3600_000L
        val programmes = visible.asSequence().flatMap { ch ->
            guideEntries(ch).asSequence()
                .filter { it.endMs > now && it.startMs < until && q in searchKey(it.title) }
                .take(2)
                .map { SearchHit(ch, it) }
        }.take(MAX_SEARCH).toList().sortedBy { it.programme?.startMs }
        return channels + programmes
    }

    private fun searchKey(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(ACCENTS, "")

    /** Programas do canal para a grelha (passado incluído), com o desvio horário das definições. */
    fun guideEntries(channel: Channel): List<EpgEntry> {
        val content = _content.value ?: return emptyList()
        return (c.epg.guideEntries(content.playlist, channel) ?: c.epg.cached(content.playlist, channel).orEmpty()).shifted()
    }

    // ---- Catch-up ----------------------------------------------------------------------------

    /** O servidor ainda guarda [entry] de [channel]? (Xtream com `tv_archive`, dentro dos dias que guarda.) */
    fun canCatchup(channel: Channel, entry: EpgEntry): Boolean {
        val content = _content.value ?: return false
        if (content.playlist.type != PlaylistType.XTREAM || channel.archiveDays <= 0) return false
        val now = System.currentTimeMillis()
        return entry.startMs < now - CATCHUP_MIN_AGE_MS && entry.startMs > now - channel.archiveDays * 86_400_000L
    }

    /** Vê [entry] em catch-up, desde o início (ou desde [positionMs]). */
    fun playCatchup(channel: Channel, entry: EpgEntry, positionMs: Long = 0L) {
        if (_playing.value?.key != channel.key) play(channel, groupFor(channel))
        _screen.value = Screen.Player
        _catchup.value = Catchup(channel, entry.title, entry.startMs, entry.endMs, positionMs.coerceAtLeast(0))
    }

    /** Avança/recua no catch-up para [positionMs]; passar do direto volta ao direto. */
    fun seekCatchup(positionMs: Long) {
        val current = _catchup.value ?: return
        val liveEdge = System.currentTimeMillis() - current.startMs - CATCHUP_MIN_AGE_MS
        _catchup.value = when {
            positionMs >= liveEdge -> null
            positionMs >= current.durationMs -> null
            else -> current.copy(positionMs = positionMs.coerceAtLeast(0))
        }
    }

    fun exitCatchup() {
        _catchup.value = null
    }

    // ---- Temporizador e lembretes ------------------------------------------------------------

    /** Liga o temporizador para daqui a [minutes] minutos (null desliga). */
    fun setSleepTimer(minutes: Int?, announce: Boolean = true) {
        _sleepAt.value = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        if (announce) showToast(minutes?.let { I18n.s.sleepSet(it) } ?: I18n.s.sleepOff)
    }

    fun hasReminder(channel: Channel, entry: EpgEntry): Boolean {
        val playlistId = _content.value?.playlist?.id ?: return false
        return _reminders.value.any { it.playlistId == playlistId && it.channelKey == channel.key && it.startMs == entry.startMs }
    }

    /** Marca/desmarca o lembrete de um programa que ainda vai começar. */
    fun toggleReminder(channel: Channel, entry: EpgEntry) {
        val playlistId = _content.value?.playlist?.id ?: return
        val existing = _reminders.value.firstOrNull { it.playlistId == playlistId && it.channelKey == channel.key && it.startMs == entry.startMs }
        _reminders.value = if (existing != null) {
            showToast(I18n.s.reminderRemoved)
            _reminders.value - existing
        } else {
            showToast(I18n.s.reminderSet(entry.title))
            (_reminders.value + Reminder(playlistId, channel.key, channel.name, entry.title, entry.startMs)).sortedBy { it.startMs }
        }
        viewModelScope.launch { c.cache.saveReminders(_reminders.value) }
    }

    /** Lembrete a mostrar agora (o programa começa dentro de 1 min, ou começou há pouco). */
    fun dueReminder(now: Long): Reminder? {
        val playlistId = _content.value?.playlist?.id ?: return null
        return _reminders.value.firstOrNull {
            it.playlistId == playlistId && it.startMs <= now + REMINDER_AHEAD_MS && it.startMs > now - REMINDER_GRACE_MS
        }
    }

    /** O lembrete já foi mostrado: sai da lista (e, com [watch], muda para o canal). */
    fun consumeReminder(reminder: Reminder, watch: Boolean) {
        _reminders.value = _reminders.value - reminder
        viewModelScope.launch { c.cache.saveReminders(_reminders.value) }
        if (!watch) return
        val channel = _content.value?.byKey?.get(reminder.channelKey) ?: return
        play(channel, groupFor(channel))
    }

    // ---- Segundo canal -------------------------------------------------------------------------

    /** As duas listas usam a mesma conta do fornecedor (a mesma lista, ou o mesmo servidor e utilizador). */
    private fun sameAccount(a: Playlist, b: Playlist): Boolean =
        a.id == b.id || (a.type == PlaylistType.XTREAM && b.type == PlaylistType.XTREAM &&
            a.server?.trim()?.lowercase() == b.server?.trim()?.lowercase() && a.username == b.username)

    /**
     * Pode-se ver o segundo canal de [playlist]? Da conta da lista principal só se o plano deixar 2+ ligações
     * (nas M3U não se sabe: deixa-se tentar).
     */
    fun secondAllowed(playlist: Playlist): Boolean {
        val main = _content.value ?: return true
        if (!sameAccount(playlist, main.playlist)) return true
        if (main.playlist.type != PlaylistType.XTREAM) return true
        val limit = main.account?.maxConnections ?: return false
        return limit >= 2
    }

    /** Os canais de [playlist] para escolher o segundo canal (da cache, ou descarregados). */
    suspend fun loadForSecond(playlist: Playlist): PlaylistContent {
        _content.value?.takeIf { it.playlist.id == playlist.id }?.let { return it }
        secondContents[playlist.id]?.let { return it }
        val loaded = c.cache.loadPlaylist(playlist) ?: c.loadPlaylist(playlist).also { c.cache.savePlaylist(it) }
        secondContents[playlist.id] = loaded
        return loaded
    }

    /** Grupos de [content] para o segundo canal (sem escondidos nem bloqueados). */
    fun groupsForSecond(content: PlaylistContent): List<Category> {
        val p = _prefs.value
        val id = content.playlist.id
        val s = I18n.s
        val favorites = _favorites.value[id].orEmpty()
        return listOfNotNull(
            Category(SpecialCategory.FAVORITES, s.favoritesStar).takeIf { favorites.isNotEmpty() },
            Category(SpecialCategory.ALL, s.all),
        ) + content.categories
            .filterNot { p.isHidden(id, it.id) || (p.isLocked(id, it.id) && "$id:${it.id}" !in _unlocked.value) }
            .map { if (it.id == PlaylistContent.OTHERS) it.copy(name = s.others) else it }
    }

    fun channelsForSecond(content: PlaylistContent, groupId: String): List<Channel> {
        val p = _prefs.value
        val id = content.playlist.id
        fun visible(ch: Channel) = !p.isHidden(id, ch.categoryId) && !p.isChannelHidden(id, ch.key) &&
            !(p.isLocked(id, ch.categoryId) && "$id:${ch.categoryId}" !in _unlocked.value)
        val list = when (groupId) {
            SpecialCategory.FAVORITES -> _favorites.value[id].orEmpty().mapNotNull { content.byKey[it] }.filter(::visible)
            SpecialCategory.ALL -> content.channels.filter(::visible)
            else -> content.byCategory[groupId].orEmpty().filterNot { p.isChannelHidden(id, it.key) }
        }
        return if (p.sort == Prefs.Sort.NAME) list.sortedBy { it.name.lowercase() } else list
    }

    fun openSecond(content: PlaylistContent, channel: Channel, groupId: String) {
        _second.value = SecondView(content, channel, channelsForSecond(content, groupId).ifEmpty { listOf(channel) })
    }

    /** ↑/↓ no segundo canal (dá a volta nas pontas, como o principal). */
    fun zapSecond(delta: Int) {
        val current = _second.value ?: return
        if (current.zapList.size < 2) return
        val index = current.zapList.indexOfFirst { it.uid == current.channel.uid }.coerceAtLeast(0)
        _second.value = current.copy(channel = current.zapList[(index + delta).mod(current.zapList.size)])
    }

    fun closeSecond() {
        _second.value = null
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
                    if (!manual) showToast(I18n.s.updateAvailable(latest.version))
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

    fun settingsNeedPin(): Boolean = _prefs.value.settingsPin && _prefs.value.pinHash != null && !settingsUnlocked

    fun unlockSettings(pin: String): Boolean {
        if (!checkPin(pin)) return false
        settingsUnlocked = true
        return true
    }

    /** O fallback encontrou um formato que funciona: fica guardado na conta. */
    fun onFormatWorked(format: String) {
        if (_content.value?.playlist?.type != PlaylistType.XTREAM) return
        if (format != _streamFormat.value) setStreamFormat(format)
    }

    private fun scheduleSaveLast(channel: Channel) {
        val playlistId = _content.value?.playlist?.id ?: return
        saveLastJob?.cancel()
        pendingLast = playlistId to channel.key
        saveLastJob = viewModelScope.launch {
            delay(WATCHED_MS) // só grava se ficar no canal (evita um PATCH por cada CH+)
            saveLast(watched = true)
        }
    }

    /**
     * A app vai para segundo plano: grava já o canal atual como último (para abrir onde se saiu); nos Recentes
     * só entra se já se ficou nele [WATCHED_MS].
     */
    fun flushLastChannel() {
        if (pendingLast == null) return
        saveLastJob?.cancel()
        val watched = System.currentTimeMillis() - playingSince >= WATCHED_MS
        viewModelScope.launch { saveLast(watched) }
    }

    private suspend fun saveLast(watched: Boolean) {
        val (playlistId, channelKey) = pendingLast ?: return
        pendingLast = null
        c.session.saveLast(playlistId, channelKey)
        var changed = false
        if (settings.string("last_playlist") != playlistId || settings.string("last_channel") != channelKey) {
            settings.addProperty("last_playlist", playlistId)
            settings.addProperty("last_channel", channelKey)
            changed = true
        }
        // Recentes: só entram os canais em que se ficou (os mesmos WATCHED_MS do último canal).
        val recents = (listOf(channelKey) + _recents.value[playlistId].orEmpty().filter { it != channelKey }).take(MAX_RECENTS)
        if (watched && recents != _recents.value[playlistId]) {
            _recents.value = _recents.value + (playlistId to recents)
            settings.add(RECENTS_KEY, c.gson.toJsonTree(_recents.value))
            changed = true
        }
        if (changed) saveSettings()
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
            _content.value != null && _playing.value != null -> _screen.value = Screen.Player
            // Sem conta e sem listas (ex.: Voltar no formulário da primeira lista).
            c.token == null && _playlists.value.isEmpty() -> _screen.value = Screen.Welcome
            else -> startLoading(resume = false)
        }
    }

    /** null = Auto. */
    fun setStreamFormat(format: String?) {
        _streamFormat.value = format
        if (format == null) settings.remove("stream_format") else settings.addProperty("stream_format", format)
        saveSettings()
    }

    /** "Terminar sessão nesta TV": apaga só o token local (as listas de quando se usou sem conta ficam). */
    fun logoutThisTv() {
        viewModelScope.launch { resetToActivation() }
    }

    private suspend fun resetToActivation() {
        loadJob?.cancel()
        saveLastJob?.cancel()
        refreshJob?.cancel()
        c.session.clear()
        c.cache.clear() // tem credenciais das listas
        c.home.clear()
        clearEpgMappings()
        c.token = null
        userId = null
        _email.value = null
        _playlists.value = emptyList()
        _content.value = null
        _favorites.value = emptyMap()
        settings = JsonObject()
        syncedSettings = JsonObject()
        settingsRev = null
        _streamFormat.value = null
        _prefs.value = Prefs()
        lastConfig = null
        pendingLast = null
        _unlocked.value = emptySet()
        _playing.value = null
        _second.value = null
        _local.value = false
        _selectedCategory.value = SpecialCategory.ALL
        _screen.value = Screen.Welcome
    }

    // ---- Avisos e gravação na conta --------------------------------------------------------

    fun showToast(message: String) {
        _toast.value = message
    }

    fun clearToast(message: String) {
        if (_toast.value == message) _toast.value = null
    }

    private fun saveSettings() {
        pushSettings()
        persistConfigCache()
    }

    /**
     * Atualiza a config em cache com as definições e favoritos atuais (o arranque rápido lê daqui).
     * Sem conta é a própria config desta TV (com as listas), no LocalStore.
     */
    private fun persistConfigCache() {
        val base = lastConfig ?: return
        val local = _local.value
        val updated = base.copy(
            settings = settings.deepCopy(),
            favorites = c.gson.toJsonTree(_favorites.value),
            playlists = if (local) _playlists.value.map { it.toDto() } else base.playlists,
        )
        lastConfig = updated
        viewModelScope.launch { if (local) c.local.save(updated) else c.cache.saveConfig(updated) }
    }

    /** Modo de teste: sem streams nem escritas na conta (ver AppContainer.testMode). */
    val testMode: Boolean get() = c.testMode

    /** Grava `settings` ou `favorites` (o objeto inteiro) no perfil GoatTV da conta (sem conta, só no LocalStore). */
    private fun patchUser(field: String, value: JsonElement) {
        if (c.testMode || _local.value) return
        viewModelScope.launch {
            // Em série, para os PATCH chegarem pela ordem em que foram feitos.
            patchMutex.withLock {
                try {
                    val res = c.backend.updateProfile(JsonObject().apply { add(field, value) })
                    if (!res.isSuccessful && res.code() != 401) showToast(I18n.s.saveFailed(res.code()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showToast(I18n.s.saveOffline)
                }
            }
        }
    }

    /**
     * Grava no servidor só o que mudou no `settings` desde a última vez ([syncedSettings]), em `settings_merge`.
     * Com arrays (ex.: hidden_channels, que vão inteiros) junta `settings_rev`; num 409 (alguém gravou entretanto,
     * ex.: no site) põe as alterações daqui por cima das do servidor e tenta outra vez.
     */
    private fun pushSettings() {
        if (c.testMode || _local.value) return
        viewModelScope.launch {
            patchMutex.withLock {
                repeat(SETTINGS_ATTEMPTS) {
                    val base = syncedSettings
                    val patch = SettingsMerge.diff(base, settings)
                    if (patch.size() == 0) return@withLock
                    val body = JsonObject().apply {
                        add("settings_merge", patch)
                        if (SettingsMerge.hasTopLevelArray(patch)) settingsRev?.let { addProperty("settings_rev", it) }
                    }
                    val res = try {
                        c.backend.updateProfile(body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showToast(I18n.s.saveOffline)
                        return@withLock
                    }
                    if (!res.isSuccessful && res.code() != 409) {
                        if (res.code() != 401) showToast(I18n.s.saveFailed(res.code()))
                        return@withLock
                    }
                    val reply = if (res.isSuccessful) {
                        res.body()
                    } else {
                        res.errorBody()?.string()?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                    }
                    reply?.get("settings_rev")?.takeIf { it.isJsonPrimitive }?.let { settingsRev = it.asLong }
                    val server = reply?.get("settings") as? JsonObject
                    if (server != null) adoptServerSettings(server, base) else syncedSettings = SettingsMerge.apply(base, patch)
                    // As alterações feitas durante o pedido seguem no próximo saveSettings (já está na fila).
                    if (res.isSuccessful) return@withLock
                }
            }
        }
    }

    /** O servidor mandou o `settings` completo: fica-se com ele, mais o que ainda só mudou aqui. */
    private fun adoptServerSettings(server: JsonObject, base: JsonObject) {
        val rebased = SettingsMerge.rebase(server, base, settings)
        syncedSettings = server
        if (rebased != settings) {
            settings = rebased
            applySettings()
            persistConfigCache()
        }
    }

    private companion object {
        const val TAG = "GoatTV"
        val JSON_MEDIA = "application/json".toMediaType()
        /** Tentativas de gravar as definições quando o servidor responde 409 (igual ao site). */
        const val SETTINGS_ATTEMPTS = 4
        const val UPDATE_FIRST_CHECK_MS = 20_000L
        const val UPDATE_INTERVAL_MS = 12 * 3600_000L
        const val RECENTS_KEY = "recent_channels"
        /** Canais na linha da GoatTV no ecrã inicial. */
        const val HOME_ITEMS = 10
        const val MAX_RECENTS = 20
        const val MAX_SEARCH = 40
        /** Tempo num canal para contar como visto (anterior e Recentes). */
        const val WATCHED_MS = 5_000L
        /** O servidor precisa de uns minutos para ter um programa em arquivo. */
        const val CATCHUP_MIN_AGE_MS = 2 * 60_000L
        const val REMINDER_AHEAD_MS = 60_000L
        const val REMINDER_GRACE_MS = 10 * 60_000L
        /** Um país pedido pela primeira vez tem programas no servidor em ≤ 1 min. */
        const val EPG_NEW_COUNTRY_MS = 90_000L
        /** Associações por país: pedidos em paralelo e pausa entre grupos (≈ 20 pedidos/s). */
        const val EPG_BATCH = 4
        const val EPG_BATCH_PAUSE_MS = 200L
        private val ACCENTS = Regex("\\p{Mn}+")

        /** De quanto em quanto tempo ver se a lista ou o guia já passaram do intervalo. */
        const val REFRESH_TICK_MS = 10 * 60_000L

        fun PlaylistDto.toPlaylist(): Playlist? {
            val id = id ?: return null
            val type = when (type?.lowercase()) {
                "xtream" -> PlaylistType.XTREAM
                "m3u" -> PlaylistType.M3U
                else -> return null
            }
            return Playlist(
                id = id,
                type = type,
                name = name?.takeIf { it.isNotBlank() } ?: if (type == PlaylistType.XTREAM) "Xtream" else "M3U",
                // A conta manda sempre os 8 campos ("" nos que não são do tipo): vazio = em falta, para a lista
                // não parecer diferente da guardada em cache (e não recarregar sem razão).
                server = server?.takeIf { it.isNotBlank() },
                username = username?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotEmpty() },
                url = url?.takeIf { it.isNotBlank() },
                epgUrl = epgUrl?.takeIf { it.isNotBlank() },
            )
        }

        fun Playlist.toDto() = PlaylistDto(
            id = id,
            type = if (type == PlaylistType.XTREAM) "xtream" else "m3u",
            name = name,
            server = server,
            username = username,
            password = password,
            url = url,
            epgUrl = epgUrl,
        )

        /** Id de uma lista desta TV (sem conta). */
        fun newLocalId(): String = "local" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        /** A mesma lista do fornecedor (para passar favoritos de uma lista desta TV para a da conta). */
        fun sameSource(a: Playlist, b: Playlist): Boolean = a.type == b.type && when (a.type) {
            PlaylistType.XTREAM -> a.server?.trim()?.trimEnd('/')?.lowercase() == b.server?.trim()?.trimEnd('/')?.lowercase() &&
                a.username?.trim() == b.username?.trim()
            PlaylistType.M3U -> a.url?.trim() == b.url?.trim()
        }

        fun parseFavorites(json: JsonElement?): Map<String, List<String>> {
            if (json == null || !json.isJsonObject) return emptyMap()
            return json.asJsonObject.entrySet().associate { (playlistId, value) ->
                playlistId to if (value.isJsonArray) {
                    value.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.distinct()
                } else {
                    emptyList()
                }
            }
        }

        fun JsonObject.string(key: String): String? {
            val e = get(key) ?: return null
            return if (e.isJsonPrimitive) e.asString.takeIf { it.isNotEmpty() } else null
        }
    }
}
