package cc.slokker.itv.data

import cc.slokker.itv.data.epg.AccountGuide
import cc.slokker.itv.data.epg.AccountGuideSource
import cc.slokker.itv.data.epg.XmltvGuide
import cc.slokker.itv.data.epg.XmltvSource
import cc.slokker.itv.data.xtream.XtreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Guia de programação ("agora / a seguir").
 * - Lista com guia XMLTV (o da conta ou o `x-tvg-url` da M3U): descarrega-se inteiro, guarda-se em disco
 *   e renova-se pelo intervalo das definições.
 * - Xtream sem guia: EPG curto pedido canal a canal (e também para os canais que o XMLTV não tenha).
 * - Guia da conta (só com conta): os canais associados a um canal de guia (Definições → canal → Guia).
 *   Quando o canal de guia tem programas, substituem os da lista; senão fica o guia da lista.
 */
class EpgRepository(
    private val xtream: XtreamSource,
    private val xmltv: XmltvSource,
    private val accountSource: AccountGuideSource,
    private val store: CacheStore,
) {

    private val account = MutableStateFlow<AccountGuide?>(null)
    private var accountJob: Job? = null

    /** Programas do guia da conta para o canal (null se não está associado, ou o guia não os tem). */
    private fun accountEntries(playlist: Playlist, channel: Channel): List<EpgEntry>? =
        account.value?.takeIf { it.playlistId == playlist.id }?.programmes?.get(channel.key)?.takeIf { it.isNotEmpty() }

    /**
     * Prepara o guia da conta da lista: o do disco logo, e um novo por trás se tiver mais de 6 h
     * (ou com [force], ex.: acabou de se mudar uma associação). Sem associações ([mapped] false), esquece-o.
     */
    fun openAccount(scope: CoroutineScope, playlistId: String, mapped: Boolean, force: Boolean = false) {
        if (!mapped) {
            accountJob?.cancel()
            if (account.value?.playlistId == playlistId) account.value = null
            scope.launch { store.removeAccountGuide(playlistId) }
            _version.value++
            return
        }
        if (accountJob?.isActive == true && !force) return
        accountJob?.cancel()
        accountJob = scope.launch {
            val current = account.value?.takeIf { it.playlistId == playlistId } ?: store.loadAccountGuide(playlistId)?.trimmed(System.currentTimeMillis())?.also {
                account.value = it
                _version.value++
            }
            if (!force && current != null && System.currentTimeMillis() - current.fetchedAt < ACCOUNT_REFRESH_MS) return@launch
            try {
                val fresh = accountSource.load(playlistId, current)
                store.saveAccountGuide(fresh)
                if (fresh.etag != current?.etag || current == null) _version.value++
                account.value = fresh
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sem rede ou servidor em baixo: fica o guia que havia.
            }
        }
    }

    /** Esquece o guia da conta em memória (outra lista, ou terminou a sessão). */
    fun closeAccount() {
        accountJob?.cancel()
        account.value = null
    }

    private class Cached(val fetchedAt: Long, val entries: List<EpgEntry>)

    private val shortCache = ConcurrentHashMap<String, Cached>()

    /** Guia XMLTV da lista aberta; [loading] enquanto se lê do disco ou se descarrega. */
    private class GuideState(val generation: Int, val playlistId: String, val url: String, val guide: XmltvGuide?, val loading: Boolean)

    /**
     * Um guia XMLTV de uma lista: o dela (`x-tvg-url` ou o do Xtream) ou o extra das definições. Guarda-se em
     * disco com [storeSuffix] junto ao id da lista (o extra não pisa o da lista).
     */
    private inner class XmltvSlot(private val storeSuffix: String) {
        val state = MutableStateFlow<GuideState?>(null)
        private var job: Job? = null
        private var generation = 0

        fun entries(playlist: Playlist, channel: Channel): List<EpgEntry>? =
            state.value?.takeIf { it.playlistId == playlist.id }?.guide?.entriesFor(channel)?.takeIf { it.isNotEmpty() }

        /** O guia da lista, esperando só se ainda não há nenhum (primeira vez, a descarregar). */
        suspend fun ready(playlist: Playlist): XmltvGuide? {
            if (state.value?.playlistId != playlist.id) return null
            return state.first { it == null || it.playlistId != playlist.id || it.guide != null || !it.loading }?.guide
        }

        fun open(
            scope: CoroutineScope,
            content: PlaylistContent,
            url: String?,
            force: Boolean,
            isDue: (fetchedAt: Long) -> Boolean,
            onDone: (Boolean) -> Unit,
        ) {
            val id = content.playlist.id
            val key = id + storeSuffix
            // Já a tratar do mesmo guia (ex.: a descarregar): não recomeça.
            val busy = state.value?.let { it.playlistId == id && it.url == url } == true && job?.isActive == true
            if (busy && !force) return
            job?.cancel()
            val gen = ++generation
            if (url == null) {
                if (state.value != null) _version.value++
                state.value = null
                return
            }
            val previous = state.value?.takeIf { it.playlistId == id && it.url == url }?.guide
            state.value = GuideState(gen, id, url, previous, loading = true)
            job = scope.launch {
                try {
                    val stored = previous ?: store.loadGuide(key, url)
                    state.value = GuideState(gen, id, url, stored, loading = true)
                    if (stored != null && previous == null) _version.value++
                    if (!force && stored != null && !isDue(stored.fetchedAt)) return@launch
                    val fresh = try {
                        xmltv.load(url, content.channels).takeIf { it.programmes.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (fresh != null) {
                        store.saveGuide(key, fresh)
                        state.value = GuideState(gen, id, url, fresh, loading = true)
                        _version.value++
                    }
                    onDone(fresh != null)
                } finally {
                    // Só este pedido pode marcar o fim; um open() mais recente já tem o seu estado.
                    state.value?.takeIf { it.generation == gen }?.let {
                        state.value = GuideState(gen, it.playlistId, it.url, it.guide, loading = false)
                    }
                }
            }
        }
    }

    /** O guia XMLTV da lista e o extra (Definições → Listas → lista → Guia extra). */
    private val main = XmltvSlot("")
    private val extra = XmltvSlot(EXTRA_SUFFIX)

    private val _version = MutableStateFlow(0)

    /** Muda sempre que chega guia novo (XMLTV ou EPG curto): a grelha volta a ler. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * Todos os programas do canal (passado incluído): os do guia da conta, senão os do guia da lista, senão os do
     * guia extra; null se nenhum o tem.
     */
    fun guideEntries(playlist: Playlist, channel: Channel): List<EpgEntry>? =
        accountEntries(playlist, channel) ?: main.entries(playlist, channel) ?: extra.entries(playlist, channel)

    /** Quando o guia foi descarregado (null se não há guia XMLTV ou ainda não chegou). */
    val guideFetchedAt: Long? get() = main.state.value?.guide?.fetchedAt

    /**
     * Prepara o guia de [content]: usa o que está em disco e, se [isDue] disser que está velho (ou com
     * [force]), descarrega um novo por trás. [onDone] recebe false se o download falhou.
     */
    fun open(
        scope: CoroutineScope,
        content: PlaylistContent,
        force: Boolean = false,
        isDue: (fetchedAt: Long) -> Boolean,
        onDone: (Boolean) -> Unit = {},
    ) = main.open(scope, content, content.guideUrl, force, isDue, onDone)

    /** Igual, para o guia extra da lista ([url] null tira-o). */
    fun openExtra(
        scope: CoroutineScope,
        content: PlaylistContent,
        url: String?,
        force: Boolean = false,
        isDue: (fetchedAt: Long) -> Boolean,
        onDone: (Boolean) -> Unit = {},
    ) = extra.open(scope, content, url, force, isDue, onDone)

    /** O que já se sabe do canal, sem pedidos (para mostrar logo). */
    fun cached(playlist: Playlist, channel: Channel): List<EpgEntry>? {
        val now = System.currentTimeMillis()
        accountEntries(playlist, channel)?.filter { it.endMs > now }?.takeIf { it.isNotEmpty() }?.let { return it }
        for (slot in listOf(main, extra)) {
            slot.entries(playlist, channel)?.filter { it.endMs > now }?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val c = shortCache[cacheKey(playlist, channel)] ?: return null
        if (now - c.fetchedAt > TTL_MS) return null
        val current = c.entries.filter { it.endMs > now }
        // Se já não há programa a decorrer, é preciso pedir de novo.
        return current.takeIf { it.isNotEmpty() && it.first().startMs <= now }
    }

    suspend fun shortEpg(playlist: Playlist, channel: Channel): List<EpgEntry> {
        val now0 = System.currentTimeMillis()
        accountEntries(playlist, channel)?.filter { it.endMs > now0 }?.takeIf { it.isNotEmpty() }?.let { return it }
        for (slot in listOf(main, extra)) {
            val ready = slot.ready(playlist) ?: continue
            val now = System.currentTimeMillis()
            ready.entriesFor(channel).filter { it.endMs > now }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (playlist.type != PlaylistType.XTREAM) return emptyList()
        val streamId = channel.streamId ?: return emptyList()
        cached(playlist, channel)?.let { return it }
        return try {
            val entries = xtream.shortEpg(playlist, streamId)
            shortCache[cacheKey(playlist, channel)] = Cached(System.currentTimeMillis(), entries)
            _version.value++
            val now = System.currentTimeMillis()
            entries.filter { it.endMs > now }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Esquece o EPG curto (o guia XMLTV renova-se com [open] e `force`). */
    fun clearShort() = shortCache.clear()

    private fun cacheKey(playlist: Playlist, channel: Channel) = "${playlist.id}:${channel.key}"

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
        /** Junta-se ao id da lista no ficheiro do guia extra. */
        const val EXTRA_SUFFIX = "extra"
        /** O servidor atualiza os programas de 12 em 12 h; pede-se no máximo de 6 em 6 h (com ETag). */
        const val ACCOUNT_REFRESH_MS = 6 * 3600_000L
    }
}

/** Quando voltar a descarregar listas e guias (intervalos das definições). */
object RefreshPolicy {
    /** 0 = sempre que se abre a app; o resto em horas. */
    val OPTIONS = listOf(0, 3, 6, 8, 12, 24)

    /** Entre tentativas falhadas, para não insistir com um fornecedor que está em baixo (ou a dar 429). */
    const val RETRY_MS = 30 * 60_000L

    fun isDue(hours: Int, fetchedAt: Long, now: Long, checkedSinceOpen: Boolean): Boolean = when {
        fetchedAt <= 0L -> true
        hours <= 0 -> !checkedSinceOpen
        else -> now - fetchedAt >= hours * 3600_000L
    }
}
