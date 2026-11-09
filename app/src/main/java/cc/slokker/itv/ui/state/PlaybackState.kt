package cc.slokker.itv.ui.state

import cc.slokker.itv.data.Catchup
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.ChannelCatalog
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.SpecialCategory
import cc.slokker.itv.data.nextAllowed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * O que está a tocar e como se anda de canal em canal: o canal, o grupo por onde CH+/CH− navegam, o anterior, o
 * catch-up e o segundo canal. Não sabe de ecrãs nem de rede: o ViewModel diz que canal pôr, e [onTuned] grava-o como
 * último canal. [catalog] dá o que se vê da lista aberta nesse momento (o bloqueio conta na altura, não quando a lista
 * de zapping foi feita).
 */
class PlaybackState(
    private val catalog: () -> ChannelCatalog,
    private val catalogFor: (PlaylistContent) -> ChannelCatalog,
    private val onTuned: (Channel) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Segundo canal no ecrã (imagem na imagem / dividido). Pode ser de outra lista (outro fornecedor). */
    data class SecondView(val content: PlaylistContent, val channel: Channel, val zapList: List<Channel>)

    /** Grupo do canal a ver: é por ele que CH+/CH− navegam e é o que o painel de canais abre. */
    private val _selectedCategory = MutableStateFlow(SpecialCategory.ALL)
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _playing = MutableStateFlow<Channel?>(null)
    val playing: StateFlow<Channel?> = _playing.asStateFlow()

    /** Programa a ver em catch-up (null = em direto). */
    private val _catchup = MutableStateFlow<Catchup?>(null)
    val catchup: StateFlow<Catchup?> = _catchup.asStateFlow()

    private val _second = MutableStateFlow<SecondView?>(null)
    val second: StateFlow<SecondView?> = _second.asStateFlow()

    /** Canais da categoria onde se carregou OK: é por eles que CH+/CH− navegam. */
    private var zapList: List<Channel> = emptyList()

    /** O canal visto antes do atual (→ volta a ele) e desde quando o atual está no ecrã. */
    private var previous: Channel? = null
    private var playingSince = 0L

    /** Quando não há canal para retomar, o player abre já com o painel de canais. */
    private var openPanelOnStart = false

    /** Põe [channel] a tocar; CH+/CH− passam a percorrer o grupo [groupId]. Salta de propósito: conta para o anterior. */
    fun playIn(channel: Channel, groupId: String) {
        _selectedCategory.value = groupId
        zapList = catalog().channelsOf(groupId).ifEmpty { listOf(channel) }
        _catchup.value = null
        setPlaying(channel, explicit = true)
    }

    /** Muda de canal dentro da categoria atual (dá a volta nas pontas). */
    fun zap(delta: Int) {
        val current = _playing.value ?: return
        if (zapList.size < 2) return
        val index = zapList.indexOfFirst { it.uid == current.uid }.coerceAtLeast(0)
        // A lista foi feita antes: o que conta é o bloqueio neste momento.
        val catalog = catalog()
        val next = nextAllowed(zapList, index, delta) { catalog.isGroupLocked(it.categoryId) } ?: return
        _catchup.value = null
        setPlaying(next)
    }

    /**
     * Troca o canal no ecrã e decide qual fica como "anterior" (→): o que se via, se se ficou nele uns segundos
     * (passar por canais no zapping não conta), ou sempre que se salta de propósito com [explicit].
     */
    private fun setPlaying(next: Channel, explicit: Boolean = false) {
        val old = _playing.value
        val now = now()
        if (old != null && old.key != next.key && (explicit || now - playingSince >= WATCHED_MS)) previous = old
        if (old?.key != next.key) playingSince = now
        _playing.value = next
        onTuned(next)
    }

    /** Já se ficou no canal atual o bastante para contar como visto (anterior e Recentes). */
    fun watchedCurrent(): Boolean = now() - playingSince >= WATCHED_MS

    /** O último canal visto antes do atual: o da sessão, ou o dos Recentes (ex.: logo ao abrir a app). */
    fun previousChannel(): Channel? {
        val catalog = catalog()
        val content = catalog.content ?: return null
        val current = _playing.value?.key
        previous?.let { p -> content.byKey[p.key]?.takeIf { it.key != current && !catalog.isGroupLocked(it.categoryId) }?.let { return it } }
        return catalog.recentKeys().asSequence()
            .filter { it != current }
            .mapNotNull { content.byKey[it] }
            .firstOrNull { !catalog.isGroupLocked(it.categoryId) }
    }

    /** Grupo onde [channel] fica a fazer zapping: o atual, se o tiver, senão o dele. */
    fun groupFor(channel: Channel): String {
        val selected = _selectedCategory.value
        return if (catalog().channelsOf(selected).any { it.uid == channel.uid }) selected else channel.categoryId
    }

    /** Abriu-se outra lista (ou a mesma de novo): nada a tocar, e o painel volta a "Todos". */
    fun clearForNewContent() {
        _selectedCategory.value = SpecialCategory.ALL
        _playing.value = null
        zapList = emptyList()
    }

    /** Pede que o player abra com o painel de canais (true) ou não, quando arrancar. */
    fun openPanelOnStart(open: Boolean) {
        openPanelOnStart = open
    }

    /** true uma única vez depois de carregar sem canal para retomar. */
    fun consumeOpenPanelOnStart(): Boolean = openPanelOnStart.also { openPanelOnStart = false }

    /**
     * Os canais da lista aberta foram atualizados por trás: o canal a tocar continua o mesmo (pela chave) e o zapping
     * passa a usar a lista nova.
     */
    fun onContentRefreshed(fresh: PlaylistContent) {
        _playing.value?.let { p -> fresh.byKey[p.key]?.let { _playing.value = it } }
        // Se o grupo do canal deixou de existir (o fornecedor mudou a lista), o painel abre em "Todos".
        val group = _selectedCategory.value
        if (group != SpecialCategory.ALL && group != SpecialCategory.FAVORITES && fresh.byCategory[group].isNullOrEmpty()) {
            _selectedCategory.value = SpecialCategory.ALL
        }
        zapList = catalog().channelsOf(_selectedCategory.value).ifEmpty { listOfNotNull(_playing.value) }
    }

    // ---- Catch-up ----------------------------------------------------------------------------

    /** Vê [entry] de [channel] em catch-up, desde [positionMs] (o canal já tem de estar a tocar). */
    fun startCatchup(channel: Channel, entry: EpgEntry, positionMs: Long) {
        _catchup.value = Catchup(channel, entry.title, entry.startMs, entry.endMs, positionMs.coerceAtLeast(0))
    }

    /** Avança/recua no catch-up para [positionMs]; passar do direto volta ao direto. */
    fun seekCatchup(positionMs: Long) {
        val current = _catchup.value ?: return
        val liveEdge = now() - current.startMs - Catchup.MIN_AGE_MS
        _catchup.value = when {
            positionMs >= liveEdge -> null
            positionMs >= current.durationMs -> null
            else -> current.copy(positionMs = positionMs.coerceAtLeast(0))
        }
    }

    fun exitCatchup() {
        _catchup.value = null
    }

    // ---- Segundo canal -------------------------------------------------------------------------

    fun openSecond(content: PlaylistContent, channel: Channel, groupId: String) {
        _second.value = SecondView(content, channel, catalogFor(content).channelsOf(groupId).ifEmpty { listOf(channel) })
    }

    /** ↑/↓ no segundo canal (dá a volta nas pontas, como o principal). */
    fun zapSecond(delta: Int) {
        val current = _second.value ?: return
        if (current.zapList.size < 2) return
        val index = current.zapList.indexOfFirst { it.uid == current.channel.uid }.coerceAtLeast(0)
        val catalog = catalogFor(current.content)
        val next = nextAllowed(current.zapList, index, delta) { catalog.isGroupLocked(it.categoryId) } ?: return
        _second.value = current.copy(channel = next)
    }

    fun closeSecond() {
        _second.value = null
    }

    /** Esquece o que está a tocar (outra sessão, outro perfil, ou já não há listas). */
    fun reset() {
        _second.value = null
        _playing.value = null
        _catchup.value = null
        zapList = emptyList()
        previous = null
        _selectedCategory.value = SpecialCategory.ALL
    }

    companion object {
        /** Tempo num canal para contar como visto (anterior e Recentes). */
        const val WATCHED_MS = 5_000L
    }
}
