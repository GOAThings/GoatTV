package cc.slokker.itv.ui.state

import android.util.Log
import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.ChannelIndex
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileRepository
import cc.slokker.itv.data.RefreshTracker
import cc.slokker.itv.data.api.BackendApi
import cc.slokker.itv.ui.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Os canais da lista aberta: lê-los da cache ou do fornecedor, renová-los por trás pelo intervalo das definições,
 * mandar o índice à conta, e as listas carregadas só para o segundo canal. Não decide o que se mostra: o ViewModel
 * abre a lista ([show]) e reage aos canais novos ([onRefreshed]).
 */
class PlaylistController(
    private val scope: CoroutineScope,
    /** Descarrega a lista do fornecedor (AppContainer.loadPlaylist). */
    private val load: suspend (Playlist) -> PlaylistContent,
    private val cache: CacheStore,
    private val backend: BackendApi,
    private val profile: ProfileStore,
    private val repository: () -> ProfileRepository,
    private val tracker: RefreshTracker,
    private val prefs: StateFlow<Prefs>,
    private val toast: (String) -> Unit,
    /** Chegaram canais novos da lista aberta ([guideChanged]: o guia dela também mudou). */
    private val onRefreshed: (fresh: PlaylistContent, guideChanged: Boolean) -> Unit,
) {
    private val _content = MutableStateFlow<PlaylistContent?>(null)
    val content: StateFlow<PlaylistContent?> = _content.asStateFlow()

    /** Listas carregadas só para o segundo canal (a principal está em [content]). */
    private val secondContents = HashMap<String, PlaylistContent>()

    private var refreshJob: Job? = null
    private var indexJob: Job? = null

    /** A lista em cache, só se tiver sido carregada com os mesmos dados (servidor, credenciais, URL). */
    suspend fun cached(playlist: Playlist): PlaylistContent? = cache.loadPlaylist(playlist)

    suspend fun hasCached(playlist: Playlist): Boolean = cache.hasPlaylist(playlist)

    /** Descarrega [playlist] e guarda-a (conta como verificada desde que a app abriu). Os erros saem como exceção. */
    suspend fun download(playlist: Playlist): PlaylistContent {
        val loaded = load(playlist)
        cache.savePlaylist(loaded)
        tracker.markChecked(RefreshTracker.Slot.PLAYLIST, playlist.id)
        return loaded
    }

    /** [loaded] passa a ser a lista aberta (e o índice dela vai para a conta, se mudou). */
    fun show(loaded: PlaylistContent) {
        _content.value = loaded
        uploadIndex(loaded)
    }

    /** Volta a descarregar os canais por trás se o intervalo das definições já passou. */
    fun maybeRefresh(content: PlaylistContent) {
        val id = content.playlist.id
        if (!tracker.isDue(RefreshTracker.Slot.PLAYLIST, id, prefs.value.playlistRefreshHours, content.fetchedAt)) return
        if (refreshJob?.isActive == true) return
        tracker.attempted(RefreshTracker.Slot.PLAYLIST, id)
        refreshInBackground(content.playlist)
    }

    /** Volta a descarregar [playlist] sem interromper o que está a tocar, e troca os canais. */
    fun refreshInBackground(playlist: Playlist, notify: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val fresh = try {
                load(playlist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (notify) toast(I18n.s.playlistLoadFailed(playlist.name, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            if (_content.value?.playlist != playlist || fresh.channels.isEmpty()) return@launch
            cache.savePlaylist(fresh)
            val guideChanged = fresh.guideUrl != _content.value?.guideUrl
            _content.value = fresh
            uploadIndex(fresh)
            onRefreshed(fresh, guideChanged)
            if (notify) toast(I18n.s.playlistUpdated)
        }
    }

    /**
     * Manda à conta o índice de grupos e canais de [content] (para o site o poder organizar), se for diferente do
     * que ela tem. Corre por trás e nunca atrapalha a TV: sem rede ou com erro, tenta-se na próxima carga.
     */
    private fun uploadIndex(content: PlaylistContent) {
        if (!repository().uploadsIndex) return
        val playlistId = content.playlist.id
        if (!profile.knowsIndexOf(playlistId)) return // lista que a conta não conhece
        indexJob?.cancel()
        indexJob = scope.launch(Dispatchers.Default) {
            val index = ChannelIndex.of(content) ?: return@launch
            if (profile.indexHash(playlistId) == index.hash) return@launch
            try {
                val res = backend.putPlaylistIndex(playlistId, index.gzipBody().toRequestBody(JSON_MEDIA))
                if (res.isSuccessful) {
                    profile.setIndexHash(playlistId, index.hash)
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

    /** Os canais de [playlist] para escolher o segundo canal (da cache, ou descarregados). */
    suspend fun loadForSecond(playlist: Playlist): PlaylistContent {
        _content.value?.takeIf { it.playlist.id == playlist.id }?.let { return it }
        secondContents[playlist.id]?.let { return it }
        val loaded = cache.loadPlaylist(playlist) ?: load(playlist).also { cache.savePlaylist(it) }
        secondContents[playlist.id] = loaded
        return loaded
    }

    /** A lista [playlistId] foi apagada: o segundo canal esquece-a. */
    fun forget(playlistId: String) {
        secondContents.remove(playlistId)
    }

    /** Para o que está a correr por trás (renovar, mandar o índice). */
    fun cancelJobs() {
        refreshJob?.cancel()
        indexJob?.cancel()
    }

    /** Nada aberto, e as listas do segundo canal (têm credenciais) esquecidas. */
    fun close() {
        secondContents.clear()
        _content.value = null
    }

    private companion object {
        const val TAG = "GoatTV"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
