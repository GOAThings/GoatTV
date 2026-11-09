package cc.slokker.itv.ui.state

import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileRepository
import cc.slokker.itv.data.ProfileKeys
import cc.slokker.itv.data.ProfileKeys.parseKeyLists
import cc.slokker.itv.data.ProfileKeys.string
import cc.slokker.itv.data.SettingsMerge
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.toPlaylist
import cc.slokker.itv.data.sameSourceAs
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * O perfil: listas, favoritos, recentes e o `settings` da conta (ou desta TV, sem conta). É o único dono desse JSON: o
 * resto lê [prefs], [favorites], [recents]… e muda-os pelos métodos daqui, que gravam no servidor só a diferença (num
 * 409 põem o que mudou aqui por cima do que lá está) e guardam a cópia em disco. `settings` guarda-se inteiro, para não
 * perder chaves que esta versão não conhece.
 */
class ProfileStore(
    private val scope: CoroutineScope,
    private val gson: Gson,
    /** Onde se grava agora: na conta, só nesta TV, ou em nenhum lado (modo de teste). Lê-se em cada gravação. */
    private val repository: () -> ProfileRepository,
    /** A sessão em curso: uma resposta que chega depois de a sessão mudar já não grava nada. */
    private val generation: () -> Int,
    /** Uma gravação no servidor falhou: o código HTTP, ou null sem rede. */
    private val onSaveFailed: (Int?) -> Unit,
    /** Chegaram preferências (da conta ou mudadas aqui): a língua delas passa a ser a da app. */
    private val onLanguage: (String) -> Unit,
) {
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** `favorites` da conta: playlistId → channelKeys. */
    private val _favorites = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val favorites: StateFlow<Map<String, List<String>>> = _favorites.asStateFlow()

    /** Últimos canais vistos ([ProfileKeys.RECENT_CHANNELS]): playlistId → channelKeys, o mais recente primeiro. */
    private val _recents = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val recents: StateFlow<Map<String, List<String>>> = _recents.asStateFlow()

    private val _streamFormat = MutableStateFlow<String?>(null)
    val streamFormat: StateFlow<String?> = _streamFormat.asStateFlow()

    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    /** `settings` da conta, guardado inteiro para não perder chaves que esta versão não conhece. */
    private var settings = JsonObject()

    /** O `settings` tal como está no servidor (o último que se leu ou gravou) e a versão dele: grava-se só a diferença. */
    private var syncedSettings = JsonObject()
    private var settingsRev: Long? = null

    /** O `ETag` da última config lida (para o servidor responder 304 quando nada mudou). */
    var configEtag: String? = null

    /** A config da conta tal como veio (para manter a cópia em disco igual ao que muda localmente). */
    var lastConfig: ConfigResponse? = null
        private set

    /** Que índice de canais a conta tem de cada lista (`index_hash`), para só enviar quando muda. */
    private val indexHashes = HashMap<String, String?>()

    private val patchMutex = Mutex()

    /** A conta conhece a lista [playlistId] (só se manda o índice das listas dela). */
    fun knowsIndexOf(playlistId: String): Boolean = indexHashes.containsKey(playlistId)

    fun indexHash(playlistId: String): String? = indexHashes[playlistId]

    fun setIndexHash(playlistId: String, hash: String) {
        indexHashes[playlistId] = hash
    }

    /** Lembra o `index_hash` que a conta tem de cada lista. */
    fun rememberIndexHashes(config: ConfigResponse) {
        for (p in config.playlists.orEmpty()) p.id?.let { indexHashes[it] = p.indexHash }
    }

    /**
     * Passa a usar [config] (a da conta, a da cache ou a desta TV). Sem `settings` (não é um objeto) começa-se do
     * zero, ou, com [keepSettingsIfMissing] (uma config que só se foi reler à conta), ficam as definições que havia.
     * Devolve true se o bloqueio parental mudou (o ecrã inicial tem de se atualizar).
     */
    fun applyConfig(config: ConfigResponse, keepSettingsIfMissing: Boolean = false): Boolean {
        rememberIndexHashes(config)
        lastConfig = config
        _playlists.value = config.playlists.orEmpty().mapNotNull { it.toPlaylist() }
        _favorites.value = parseKeyLists(config.favorites)
        val incoming = config.settings as? JsonObject ?: if (keepSettingsIfMissing) return false else JsonObject()
        val locksBefore = _prefs.value.pinHash to _prefs.value.lockedGroups
        settings = incoming
        syncedSettings = incoming.deepCopy()
        settingsRev = config.settingsRev
        applySettings()
        return locksBefore != (_prefs.value.pinHash to _prefs.value.lockedGroups)
    }

    /** Lê de [settings] os recentes, o formato e as preferências. */
    private fun applySettings() {
        _recents.value = parseKeyLists(settings.get(ProfileKeys.RECENT_CHANNELS))
        _streamFormat.value = settings.string(ProfileKeys.STREAM_FORMAT)
            ?.takeIf { it == StreamFormat.TS || it == StreamFormat.HLS }
        _prefs.value = Prefs.from(settings)
        onLanguage(_prefs.value.language)
    }

    // ---- O que a app guarda no settings (ProfileKeys) ------------------------------------------

    val lastPlaylistId: String? get() = settings.string(ProfileKeys.LAST_PLAYLIST)

    val lastChannelKey: String? get() = settings.string(ProfileKeys.LAST_CHANNEL)

    /** Quando o último canal da conta mudou (0 = nunca, ou ilegível). */
    val lastChannelAt: Long
        get() = settings.get(ProfileKeys.LAST_CHANNEL_AT)?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull() ?: 0L

    /** A lista a abrir da próxima vez (nesta e nas outras TVs). Grava. */
    fun setLastPlaylist(playlistId: String) {
        settings.addProperty(ProfileKeys.LAST_PLAYLIST, playlistId)
        save()
    }

    /** O último canal visto, mudado em [at]. Grava. */
    fun setLastChannel(playlistId: String, channelKey: String, at: Long) {
        putLastChannel(playlistId, channelKey, at)
        save()
    }

    private fun putLastChannel(playlistId: String, channelKey: String, at: Long) {
        settings.addProperty(ProfileKeys.LAST_PLAYLIST, playlistId)
        settings.addProperty(ProfileKeys.LAST_CHANNEL, channelKey)
        settings.addProperty(ProfileKeys.LAST_CHANNEL_AT, at)
    }

    /**
     * Guarda o canal onde se ficou: com [at] (mudou neste aparelho) como último canal, e com [watched] também nos
     * Recentes (no máximo [maxRecents]). Grava uma vez, se alguma coisa mudou.
     */
    fun recordLast(playlistId: String, channelKey: String, at: Long?, watched: Boolean, maxRecents: Int) {
        var changed = false
        if (at != null) {
            putLastChannel(playlistId, channelKey, at)
            changed = true
        }
        // Recentes: só entram os canais em que se ficou (os mesmos segundos do último canal).
        val recents = (listOf(channelKey) + _recents.value[playlistId].orEmpty().filter { it != channelKey }).take(maxRecents)
        if (watched && recents != _recents.value[playlistId]) {
            _recents.value = _recents.value + (playlistId to recents)
            settings.add(ProfileKeys.RECENT_CHANNELS, gson.toJsonTree(_recents.value))
            changed = true
        }
        if (changed) save()
    }

    /** null = Auto. */
    fun setStreamFormat(format: String?) {
        _streamFormat.value = format
        if (format == null) settings.remove(ProfileKeys.STREAM_FORMAT) else settings.addProperty(ProfileKeys.STREAM_FORMAT, format)
        save()
    }

    /** Muda uma definição da conta: String, Boolean, Int, lista de Strings, ou null para repor o padrão. */
    fun setPref(key: String, value: Any?) = setPrefs(mapOf(key to value))

    /** Muda várias definições de uma vez (uma só gravação), como [setPref]. */
    fun setPrefs(values: Map<String, Any?>) {
        values.forEach { (key, value) -> Prefs.put(settings, key, value) }
        _prefs.value = Prefs.from(settings)
        onLanguage(_prefs.value.language)
        save()
    }

    // ---- Favoritos ---------------------------------------------------------------------------

    /** Adiciona/remove [channelKey] dos favoritos de [playlistId] e grava logo na conta. Devolve true se entrou. */
    fun toggleFavorite(playlistId: String, channelKey: String): Boolean {
        val current = _favorites.value[playlistId].orEmpty()
        val added = channelKey !in current
        val updated = if (added) current + channelKey else current - channelKey
        _favorites.value = _favorites.value + (playlistId to updated)
        saveFavorites()
        return added
    }

    /** Move um favorito [delta] posições (−1 = para cima). */
    fun moveFavorite(playlistId: String, channelKey: String, delta: Int) {
        val keys = _favorites.value[playlistId].orEmpty().toMutableList()
        val from = keys.indexOf(channelKey)
        val to = (from + delta).coerceIn(0, keys.lastIndex)
        if (from < 0 || from == to) return
        keys.add(to, keys.removeAt(from))
        _favorites.value = _favorites.value + (playlistId to keys)
        saveFavorites()
    }

    private fun saveFavorites() {
        patchUser("favorites", gson.toJsonTree(_favorites.value))
        persistConfigCache()
    }

    // ---- Listas desta TV (sem conta) -----------------------------------------------------------

    /** Junta [playlist] às listas (ou troca a que tem o mesmo id) e guarda. */
    fun upsertPlaylist(playlist: Playlist) {
        val current = _playlists.value
        _playlists.value = if (current.any { it.id == playlist.id }) {
            current.map { if (it.id == playlist.id) playlist else it }
        } else {
            current + playlist
        }
        persistConfigCache()
    }

    /** Tira a lista [playlistId] e o que é só dela: favoritos, recentes e definições (grupos, ordens, logótipos, guia extra). */
    fun removeLocalPlaylist(playlistId: String): List<Playlist> {
        val remaining = _playlists.value.filter { it.id != playlistId }
        _playlists.value = remaining
        _favorites.value = _favorites.value - playlistId
        if (_recents.value.containsKey(playlistId)) {
            _recents.value = _recents.value - playlistId
            settings.add(ProfileKeys.RECENT_CHANNELS, gson.toJsonTree(_recents.value))
        }
        if (Prefs.removePlaylist(settings, playlistId)) _prefs.value = Prefs.from(settings)
        save()
        return remaining
    }

    // ---- Sem conta → conta -----------------------------------------------------------------

    /** O perfil desta TV como está agora (definições e favoritos), para passar para a conta; null se nunca foi lido. */
    fun snapshot(): ConfigResponse? = lastConfig?.copy(settings = settings.deepCopy(), favorites = gson.toJsonTree(_favorites.value))

    /**
     * Acabou de se ligar a conta numa TV que estava sem conta. As definições desta TV passam para a conta
     * se a conta ainda não tiver nenhumas; os favoritos passam para as listas da conta que são as mesmas
     * (mesmo servidor e utilizador, ou mesmo URL) e ainda não têm favoritos. As listas em si não passam sozinhas
     * (o login da TV pode criá-las na conta, mas só quando a pessoa as adiciona). Devolve true se passou alguma coisa.
     */
    fun adoptLocalProfile(local: ConfigResponse): Boolean {
        val localPlaylists = local.playlists.orEmpty().mapNotNull { it.toPlaylist() }
        val ids = localPlaylists.mapNotNull { lp -> _playlists.value.firstOrNull { it.sameSourceAs(lp) }?.let { lp.id to it.id } }.toMap()
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
            save()
            moved = true
        }
        val localFavorites = parseKeyLists(remap(local.favorites)).filter { (id, keys) -> id in ids.values && keys.isNotEmpty() }
        val adopted = localFavorites.filterKeys { _favorites.value[it].isNullOrEmpty() }
        if (adopted.isNotEmpty()) {
            _favorites.value = _favorites.value + adopted
            patchUser("favorites", gson.toJsonTree(_favorites.value))
            persistConfigCache()
            moved = true
        }
        return moved
    }

    // ---- Reposição -------------------------------------------------------------------------

    /**
     * Esquece o perfil: listas, favoritos, recentes, definições (e a cópia do servidor) e os índices. As preferências
     * ficam (ver [resetPrefs]), para o aspeto e a língua não mudarem a meio de uma troca de conta.
     */
    fun reset() {
        _playlists.value = emptyList()
        _favorites.value = emptyMap()
        _recents.value = emptyMap()
        settings = JsonObject()
        syncedSettings = JsonObject()
        settingsRev = null
        configEtag = null
        lastConfig = null
        _streamFormat.value = null
        indexHashes.clear()
    }

    /** As preferências voltam ao padrão (terminou a sessão). */
    fun resetPrefs() {
        _prefs.value = Prefs()
    }

    // ---- Gravação ----------------------------------------------------------------------------

    /** Grava o `settings` (no servidor, só a diferença) e a cópia em disco. */
    fun save() {
        pushSettings()
        persistConfigCache()
    }

    /**
     * Atualiza a config em cache com as definições e favoritos atuais (o arranque rápido lê daqui).
     * Sem conta é a própria config desta TV (com as listas), no LocalStore.
     */
    fun persistConfigCache() {
        val base = lastConfig ?: return
        val repo = repository()
        val updated = repo.withProfile(base, settings.deepCopy(), gson.toJsonTree(_favorites.value), _playlists.value)
        lastConfig = updated
        scope.launch { repo.save(updated) }
    }

    /** Grava `settings` ou `favorites` (o objeto inteiro) no perfil GoatTV da conta (só quando o perfil [ProfileRepository.syncs]). */
    private fun patchUser(field: String, value: JsonElement) {
        val repo = repository().takeIf { it.syncs } ?: return
        scope.launch {
            // Em série, para os PATCH chegarem pela ordem em que foram feitos.
            patchMutex.withLock {
                try {
                    val res = repo.patch(JsonObject().apply { add(field, value) })
                    if (!res.isSuccessful && res.code() != 401) onSaveFailed(res.code())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onSaveFailed(null)
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
        val repo = repository().takeIf { it.syncs } ?: return
        val generation = generation()
        scope.launch {
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
                        repo.patch(body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onSaveFailed(null)
                        return@withLock
                    }
                    if (generation != generation()) return@withLock
                    if (!res.isSuccessful && res.code() != 409) {
                        if (res.code() != 401) onSaveFailed(res.code())
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
                    // As alterações feitas durante o pedido seguem no próximo save (já está na fila).
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
        /** Tentativas de gravar as definições quando o servidor responde 409 (igual ao site). */
        const val SETTINGS_ATTEMPTS = 4
    }
}
