package cc.slokker.itv.ui.state

import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.EpgRepository
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.ProfileRepository
import cc.slokker.itv.data.RefreshTracker
import cc.slokker.itv.data.api.BackendApi
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.api.EpgMapping
import cc.slokker.itv.data.api.EpgMappingRequest
import cc.slokker.itv.data.api.EpgResult
import cc.slokker.itv.data.api.epgErrorText
import cc.slokker.itv.data.api.parseServerError
import cc.slokker.itv.data.api.unescaped
import cc.slokker.itv.data.epg.XmltvGuide
import cc.slokker.itv.ui.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * O guia da lista aberta: os XMLTV dela (o da lista e o extra) e quando os renovar, o guia da conta com as associações
 * canal → canal de guia (e os pedidos ao IPTV-EPG.org para as mudar), o desvio horário das definições e os logótipos
 * que vêm do guia. Os programas em si ficam no [epg]; a lista aberta e as definições lêem-se de [openContent] e [prefs].
 * Quem lê programas para o ecrã passa por aqui, para levar o desvio horário.
 */
class GuideController(
    private val scope: CoroutineScope,
    private val epg: EpgRepository,
    private val backend: BackendApi,
    private val openContent: StateFlow<PlaylistContent?>,
    private val prefs: StateFlow<Prefs>,
    /** O guia da conta só existe com conta, e o modo de teste não grava associações. */
    private val repository: () -> ProfileRepository,
    private val tracker: RefreshTracker,
    private val toast: (String) -> Unit,
) {
    /**
     * Canais da lista atual associados a um canal de guia da conta (channelKey → associação). Por defeito
     * nenhum: cada canal usa o guia da lista, e só muda quando a pessoa escolhe outro (Definições → canal → Guia).
     */
    private val _mappings = MutableStateFlow<Map<String, EpgMapping>>(emptyMap())
    val mappings: StateFlow<Map<String, EpgMapping>> = _mappings.asStateFlow()
    private var mappingsLoadedFor: String? = null

    /** Logótipos do país escolhido em "Origem dos logótipos", para a lista em uso: ("playlistId:país", channelKey → URL). */
    private val _countryLogos = MutableStateFlow<Pair<String, Map<String, String>>?>(null)
    private var countryGuide: Pair<String, List<EpgGuideChannel>>? = null

    /**
     * Os logótipos que substituem os da lista (channelKey → URL), na lista em uso: os da [Prefs.logoSource]
     * e, por cima, os escolhidos canal a canal ("" num canal volta ao da lista). Canais fora do mapa usam o da lista.
     */
    val channelLogos: StateFlow<Map<String, String>> =
        combine(openContent, prefs, _mappings, _countryLogos) { content, p, mappings, country ->
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
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // Com um país em "Origem dos logótipos", vai buscar os canais de guia desse país e associa-os aos da lista
        // pelo tvg-id ou pelo nome (como em "Usar <país> na lista inteira", mas sem gravar associações).
        scope.launch {
            combine(openContent, prefs) { content, p -> content?.takeIf { Prefs.LogoSource.isCountry(p.logoSource) }?.let { it to p.logoSource } }
                .distinctUntilChanged { a, b -> a?.first === b?.first && a?.second == b?.second }
                .collectLatest { pair ->
                    val (content, country) = pair ?: return@collectLatest
                    if (!pickerAvailable) return@collectLatest
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
    val version: StateFlow<Int> get() = epg.version

    /** Abre o guia XMLTV da lista (do disco) e descarrega-o se o intervalo já passou; e o guia da conta. */
    fun open(content: PlaylistContent, force: Boolean = false, notify: Boolean = false) {
        val id = content.playlist.id
        openAccount(content, force)
        epg.open(
            scope, content, force,
            isDue = { fetchedAt -> tracker.isDue(RefreshTracker.Slot.GUIDE, id, prefs.value.epgRefreshHours, fetchedAt) },
            onDone = { ok ->
                tracker.attempted(RefreshTracker.Slot.GUIDE, id)
                if (notify) toast(if (ok) I18n.s.epgRefreshed else I18n.s.epgRefreshFailed)
            },
        )
        openExtra(content, force)
    }

    /** O guia extra da lista (se o tiver), com as mesmas regras de renovação do guia da lista. */
    fun openExtra(content: PlaylistContent, force: Boolean = false, notify: Boolean = false) {
        val id = content.playlist.id
        epg.openExtra(
            scope, content, prefs.value.extraEpg[id], force,
            isDue = { fetchedAt -> tracker.isDue(RefreshTracker.Slot.EXTRA_GUIDE, id, prefs.value.epgRefreshHours, fetchedAt) },
            onDone = { ok ->
                tracker.attempted(RefreshTracker.Slot.EXTRA_GUIDE, id)
                if (notify) toast(if (ok) I18n.s.epgRefreshed else I18n.s.epgRefreshFailed)
            },
        )
    }
    /** Esquece o guia em cache; os programas são pedidos de novo. */
    fun refresh() {
        epg.clearShort()
        val content = openContent.value
        when {
            content == null -> toast(I18n.s.epgRefreshed)
            // O guia da lista, o da conta e o extra (o aviso diz quando o da lista chegou).
            content.guideUrl != null -> {
                toast(I18n.s.epgRefreshing)
                open(content, force = true, notify = true)
            }
            // Sem guia da lista: o da conta e o extra, e o aviso vem do extra se houver.
            else -> {
                openAccount(content, force = true)
                val extra = prefs.value.extraEpg[content.playlist.id] != null
                toast(if (extra) I18n.s.epgRefreshing else I18n.s.epgRefreshed)
                openExtra(content, force = true, notify = extra)
            }
        }
    }
    suspend fun epgFor(channel: Channel): List<EpgEntry> {
        val content = openContent.value ?: return emptyList()
        return epg.shortEpg(content.playlist, channel).shifted()
    }

    fun cachedEpg(channel: Channel): List<EpgEntry> {
        val content = openContent.value ?: return emptyList()
        return epg.cached(content.playlist, channel).orEmpty().shifted()
    }

    /** Desvio horário do guia (servidores com o fuso errado). */
    private fun List<EpgEntry>.shifted(): List<EpgEntry> {
        val offset = prefs.value.epgOffsetMin * 60_000L
        return if (offset == 0L) this else map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
    }
    /** Programas do canal para a grelha (passado incluído), com o desvio horário das definições. */
    fun guideEntries(channel: Channel): List<EpgEntry> {
        val content = openContent.value ?: return emptyList()
        // Sem dois programas à mesma hora: as listas do ecrã usam a hora como chave única.
        return (epg.guideEntries(content.playlist, channel) ?: epg.cached(content.playlist, channel).orEmpty()).distinctBy { it.startMs }.shifted()
    }

    /** Quando começou o programa do guia de [channel] que dá às [wallMs], ou null (sem guia para essa hora). */
    fun programmeStartAt(channel: Channel, wallMs: Long): Long? =
        guideEntries(channel).firstOrNull { it.startMs <= wallMs && it.endMs > wallMs }?.startMs

    // ---- Guia da conta: trocar o guia de um canal ----------------------------------------------
    // Só com conta. Dados do IPTV-EPG.org no servidor da conta (ver EpgRepository.openAccount).

    /** Escolher o guia de um canal só existe com conta GOAT. */
    val pickerAvailable: Boolean get() = repository().supportsEpgMapping

    /** Lê as associações da lista (uma vez por lista e por abertura da app) e abre o guia da conta. */
    fun openAccount(content: PlaylistContent, force: Boolean = false) {
        if (!pickerAvailable) return
        val id = content.playlist.id
        scope.launch {
            if (mappingsLoadedFor != id) {
                if (_mappings.value.isNotEmpty() && openContent.value?.playlist?.id != id) _mappings.value = emptyMap()
                val mappings = try {
                    backend.epgMappings(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpException) {
                    if (e.code() == 404) emptyMap() else null
                } catch (e: Exception) {
                    null
                }
                if (mappings == null) {
                    // Sem rede: usa o guia da conta que houver em disco (e tenta outra vez mais tarde).
                    epg.openAccount(scope, id, mapped = true)
                    return@launch
                }
                mappingsLoadedFor = id
                _mappings.value = mappings
            }
            epg.openAccount(scope, id, mapped = _mappings.value.isNotEmpty(), force = force)
        }
    }

    /** As associações podem ter mudado noutra TV: voltam a ler-se da próxima vez que se abre o guia da conta. */
    fun forgetMappings() {
        mappingsLoadedFor = null
    }

    private fun clearEpgMappings() {
        epg.closeAccount()
        _mappings.value = emptyMap()
        mappingsLoadedFor = null
    }

    private suspend fun <T> epgCall(block: suspend () -> T): EpgResult<T> = try {
        EpgResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        // Limites da conta (associações, países novos): a frase do servidor em vez do código.
        val error = parseServerError(runCatching { e.response()?.errorBody()?.string() }.getOrNull())
        EpgResult.Error(epgErrorText(e.code(), error, I18n.s.tooManyRequests, I18n.s.epgFailed(e.code().toString())))
    } catch (e: Exception) {
        EpgResult.Error(I18n.s.epgOffline)
    }

    /** Países com um canal de guia com esse nome (o país da pessoa primeiro). */
    suspend fun epgSearch(query: String): EpgResult<List<EpgCountryResult>> =
        if (query.trim().length < 2) EpgResult.Ok(emptyList()) else epgCall { backend.epgSearch(query.trim()).map { it.unescaped() } }

    /** Canais de guia prováveis para [channel] (só para mostrar como opções; nada se associa sozinho). */
    suspend fun epgSuggest(channel: Channel): EpgResult<List<EpgGuideChannel>> {
        val id = openContent.value?.playlist?.id ?: return EpgResult.Ok(emptyList())
        return epgCall { backend.epgSuggest(id, channel.key, channel.name).map { it.unescaped() } }
    }

    suspend fun epgCountries(): EpgResult<List<EpgCountryInfo>> = epgCall { backend.epgCountries().map { it.unescaped() } }

    /**
     * A lista inteira passa a usar o guia de [country]: cada canal com o mesmo tvg-id ou o mesmo nome
     * (normalizado: "PT: SIC HD" = "SIC") que um canal de guia desse país fica associado a ele.
     * Os canais já trocados um a um ficam como estão; os que não têm par continuam com o guia da lista.
     */
    fun applyEpgCountry(country: String, countryName: String) {
        val content = openContent.value ?: return
        val playlistId = content.playlist.id
        repository().refusal?.let {
            toast(it)
            return
        }
        toast(I18n.s.epgCountryApplying(countryName))
        scope.launch {
            val guide = when (val r = epgCountryChannels(country)) {
                is EpgResult.Error -> {
                    toast(r.message)
                    return@launch
                }
                is EpgResult.Ok -> r.value.channels.orEmpty().filter { it.id != null }
            }
            val already = _mappings.value
            val matches = matchGuide(content.channels.filter { it.key !in already }, guide)
            if (matches.isEmpty()) {
                toast(I18n.s.epgCountryNone(countryName))
                return@launch
            }
            var added = 0
            var error: String? = null
            // Poucos pedidos de cada vez e com pausa: o servidor aceita 300 pedidos em 10 s.
            for (chunk in matches.chunked(EPG_BATCH)) {
                val results = coroutineScope {
                    chunk.map { (ch, g) ->
                        async { (ch to g) to epgCall { backend.putEpgMapping(EpgMappingRequest(playlistId, ch.key, country, g.id!!, ch.name)) } }
                    }.awaitAll()
                }
                results.forEach { (pair, r) ->
                    val (ch, g) = pair
                    if (r is EpgResult.Ok) {
                        _mappings.value = _mappings.value + (ch.key to EpgMapping(country, g.id, ch.name, g.name))
                        added++
                    } else {
                        error = (r as EpgResult.Error).message
                    }
                }
                if (added == 0 && error != null) break
                delay(EPG_BATCH_PAUSE_MS)
            }
            mappingsLoadedFor = playlistId
            toast(if (added > 0) I18n.s.epgCountryDone(added, countryName) else error ?: I18n.s.epgOffline)
            if (added == 0) return@launch
            epg.openAccount(scope, playlistId, mapped = true, force = true)
            // O país pode não estar em uso ainda: os programas chegam ao servidor no minuto seguinte.
            delay(EPG_NEW_COUNTRY_MS)
            if (openContent.value?.playlist?.id == playlistId) epg.openAccount(scope, playlistId, mapped = _mappings.value.isNotEmpty(), force = true)
        }
    }

    /** Todos os canais da lista voltam ao guia da lista. */
    fun resetEpgMappings() {
        val playlistId = openContent.value?.playlist?.id ?: return
        repository().refusal?.let {
            toast(it)
            return
        }
        scope.launch {
            var error: String? = null
            for (chunk in _mappings.value.keys.toList().chunked(EPG_BATCH)) {
                val results = coroutineScope {
                    chunk.map { key ->
                        async {
                            key to epgCall {
                                try {
                                    backend.deleteEpgMapping(playlistId, key)
                                } catch (e: HttpException) {
                                    if (e.code() != 404) throw e
                                }
                            }
                        }
                    }.awaitAll()
                }
                results.forEach { (key, r) -> if (r is EpgResult.Ok) _mappings.value = _mappings.value - key else error = (r as EpgResult.Error).message }
                delay(EPG_BATCH_PAUSE_MS)
            }
            toast(error ?: I18n.s.epgResetDone)
            epg.openAccount(scope, playlistId, mapped = _mappings.value.isNotEmpty(), force = true)
        }
    }

    /** Todos os canais de guia de um país. */
    suspend fun epgCountryChannels(country: String): EpgResult<EpgCountryResult> = epgCall { backend.epgCountryChannels(country).unescaped() }

    /** O guia de [channel] passa a ser o do canal de guia [guide] (de [country]). Grava na conta. */
    suspend fun setEpgMapping(channel: Channel, country: String, guide: EpgGuideChannel): Boolean {
        val playlistId = openContent.value?.playlist?.id ?: return false
        val guideId = guide.id ?: return false
        repository().refusal?.let {
            toast(it)
            return false
        }
        val result = epgCall { backend.putEpgMapping(EpgMappingRequest(playlistId, channel.key, country, guideId, channel.name)) }
        if (result is EpgResult.Error) {
            toast(result.message)
            return false
        }
        val guideName = guide.name ?: guideId
        _mappings.value = _mappings.value + (channel.key to EpgMapping(country, guideId, channel.name, guideName))
        mappingsLoadedFor = playlistId
        toast(I18n.s.epgMapped(channel.name, guideName))
        epg.openAccount(scope, playlistId, mapped = true, force = true)
        // País que ainda não estava em uso: o servidor descarrega os programas no minuto seguinte.
        if (guide.now == null) {
            scope.launch {
                delay(EPG_NEW_COUNTRY_MS)
                if (openContent.value?.playlist?.id == playlistId) epg.openAccount(scope, playlistId, mapped = _mappings.value.isNotEmpty(), force = true)
            }
        }
        return true
    }

    /** [channel] volta ao guia da lista. */
    suspend fun removeEpgMapping(channel: Channel): Boolean {
        val playlistId = openContent.value?.playlist?.id ?: return false
        repository().refusal?.let {
            toast(it)
            return false
        }
        val result = epgCall {
            try {
                backend.deleteEpgMapping(playlistId, channel.key)
            } catch (e: HttpException) {
                // 404: já não estava associado (ex.: mudou noutra TV); o resultado é o mesmo.
                if (e.code() != 404) throw e
            }
        }
        if (result is EpgResult.Error) {
            toast(result.message)
            return false
        }
        _mappings.value = _mappings.value - channel.key
        toast(I18n.s.epgUnmapped(channel.name))
        epg.openAccount(scope, playlistId, mapped = _mappings.value.isNotEmpty(), force = true)
        return true
    }
    /** Esquece o guia em cache; os programas pedem-se de novo. */
    fun clearShort() = epg.clearShort()

    /** Fecha os guias e esquece as associações e os logótipos do país (outra sessão, outro perfil, sem listas). */
    fun reset() {
        epg.closeGuides()
        clearEpgMappings()
        countryGuide = null
        _countryLogos.value = null
    }

    internal companion object {
        /** Um país pedido pela primeira vez tem programas no servidor em ≤ 1 min. */
        const val EPG_NEW_COUNTRY_MS = 90_000L
        /** Associações por país: pedidos em paralelo e pausa entre grupos (≈ 20 pedidos/s). */
        const val EPG_BATCH = 4
        const val EPG_BATCH_PAUSE_MS = 200L

        /** Pares canal da lista → canal de guia com o mesmo tvg-id ou o mesmo nome (normalizado: "PT: SIC HD" = "SIC"). */
        internal fun matchGuide(channels: List<Channel>, guide: List<EpgGuideChannel>): List<Pair<Channel, EpgGuideChannel>> {
            val byId = guide.filter { it.id != null }.associateBy { it.id!!.lowercase() }
            val byName = HashMap<String, EpgGuideChannel>()
            guide.forEach { g -> g.name?.let { XmltvGuide.normalizeName(it) }?.takeIf { it.isNotEmpty() }?.let { byName.putIfAbsent(it, g) } }
            return channels.asSequence()
                .mapNotNull { ch -> (ch.epgId?.lowercase()?.let(byId::get) ?: byName[XmltvGuide.normalizeName(ch.name)])?.let { ch to it } }
                .distinctBy { it.first.key }
                .toList()
        }
    }
}
