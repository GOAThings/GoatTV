package cc.slokker.itv.data

import cc.slokker.itv.data.m3u.M3uCatchup
import cc.slokker.itv.data.m3u.M3uParser
import cc.slokker.itv.data.recording.RecordingCut
import cc.slokker.itv.data.recording.RecordingPlan
import cc.slokker.itv.data.recording.RecordingSpace
import cc.slokker.itv.data.recording.Schedule
import cc.slokker.itv.data.recording.ScheduledRecording
import cc.slokker.itv.data.recording.SeriesRule
import cc.slokker.itv.media.PictureLook
import cc.slokker.itv.playback.PictureName
import cc.slokker.itv.playback.ChannelMemory
import cc.slokker.itv.playback.SubtitleLook
import cc.slokker.itv.playback.SubtitleRect
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A porta da Apple (GoatCore em Swift) para [ChannelMatch], [ChannelVariants] e [ChannelNames]: só tipos que chegam
 * ao Swift sem surpresas (as classes Apple* de topo, sem sealed, sem lambdas, sem Int?: "nenhum" é -1). O Swift põe
 * por cima os seus tipos de sempre (GoatCore ChannelMatch.swift), por isso o resto da app não muda.
 */
object AppleBridge {
    fun parse(name: String): AppleParsed = ChannelMatch.parse(name).let { AppleParsed(it.base, it.country, it.quality, it.shift) }

    fun epgKey(id: String?): String? = ChannelMatch.epgKey(id)

    fun levenshtein(a: String, b: String): Int = ChannelMatch.levenshtein(a, b)

    fun groupKey(name: String): String = ChannelMatch.groupKey(name)

    /** O país de cada nome de grupo ([GroupCountries.of]), lado a lado; "none" nos sem país. */
    fun groupCountryCodes(names: List<String>): List<String> = names.map { GroupCountries.of(it) ?: GroupCountries.NONE }

    fun find(matcher: AppleMatcher, name: String, epgId: String?, chosen: String?, group: String?): AppleResult =
        result(matcher.matcher.find(name, epgId, chosen, group))

    fun ranked(matcher: AppleMatcher, name: String, epgId: String?, limit: Int): List<Int> = matcher.matcher.ranked(name, epgId, limit)

    fun decide(key: String, name: String, epgId: String?, group: String?, matcher: AppleMatcher, chosen: String?, manual: Boolean): AppleResult =
        result(ChannelMatch.decide(ChannelMatch.Entry(key, name, epgId, group), matcher.matcher, chosen, manual))

    /** [ChannelMatch.failovers], com as chaves da lista principal em [mainKeys]. */
    fun failovers(
        key: String, name: String, epgId: String?, group: String?, matcher: AppleMatcher, chosen: List<String>?, manual: Boolean,
        variants: List<String>, mainKeys: Set<String>,
    ): List<String> =
        ChannelMatch.failovers(ChannelMatch.Entry(key, name, epgId, group), matcher.matcher, chosen, manual, variants, mainKeys::contains)

    fun review(main: AppleEntries, matcher: AppleMatcher, overrides: Map<String, List<String>>, manual: Boolean): List<AppleRow> =
        ChannelMatch.review(main.list, matcher.matcher, overrides, manual).map { r ->
            AppleRow(r.main, STATES.getValue(r.state), r.backup ?: -1, r.suggestion ?: -1, r.failovers)
        }

    /** [ChannelVariants.of] com as chaves e os nomes lado a lado. */
    fun variants(keys: List<String>, names: List<String>): Map<String, List<String>> = ChannelVariants.of(keys.zip(names))

    /** [ChannelNames.apply] com as regras partidas em [finds] e [replaces] (lado a lado). */
    fun names(name: String, finds: List<String>, replaces: List<String>): String =
        ChannelNames.apply(name, finds.zip(replaces) { f, r -> NameRule(f, r) })

    // ---- Definições ---------------------------------------------------------------------------

    /** As definições no texto JSON [json] (o `settings`), com os motores da Apple; um texto estragado dá as de padrão. */
    fun prefs(json: String): Prefs {
        return Prefs.from(obj(json), Prefs.Engine.APPLE)
    }

    fun prefsHashPin(pin: String): String = Prefs.hashPin(pin)

    /** [SettingsPatch] em texto JSON (objetos); um texto estragado conta como {}. */
    fun settingsDiff(base: String, target: String): String = SettingsPatch.diff(obj(base), obj(target)).toString()

    fun settingsApply(base: String, patch: String): String = SettingsPatch.apply(obj(base), obj(patch)).toString()

    fun settingsRebase(server: String, base: String, local: String): String =
        SettingsPatch.rebase(obj(server), obj(base), obj(local)).toString()

    // ---- O aspeto das legendas ([SubtitleLook]) ---------------------------------------------------

    fun subtitleScale(size: String): Double = SubtitleLook.scale(size).toDouble()

    fun subtitleGap(position: String): Double = SubtitleLook.gap(position).toDouble()

    fun subtitleBoxed(style: String): Boolean = SubtitleLook.boxed(style)

    /** A cor do texto em ARGB. */
    fun subtitleTextColor(style: String): Long = SubtitleLook.textColor(style)

    /** [SubtitleLook.place], tudo em fração do ecrã do emissor. */
    fun subtitlePlace(x: Double, y: Double, width: Double, height: Double, size: String, position: String): SubtitleRect =
        SubtitleLook.place(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), size, position)

    // ---- Os ajustes da imagem ([PictureLook]) -----------------------------------------------------

    fun pictureLook(brightness: Int, contrast: Int, saturation: Int, sharpness: Int): PictureLook =
        PictureLook(brightness, contrast, saturation, sharpness)

    /** [PictureLook.applies]: este vídeo passa pelos ajustes (8 bits, SDR, e na Apple até [PictureLook.APPLE_MAX_HEIGHT]). */
    fun pictureApplies(look: PictureLook, bitDepth: Int, hdr: Boolean, height: Int): Boolean =
        look.applies(bitDepth, hdr, height, PictureLook.APPLE_MAX_HEIGHT)

    /** [PictureName.of]: o nome (sem extensão) de uma imagem guardada do canal, com a hora [epochMs]. */
    fun pictureName(channel: String, epochMs: Long): String = PictureName.of(channel, epochMs)

    private fun obj(json: String): JsonObject =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    // ---- O índice para o site ---------------------------------------------------------------

    /** [ChannelIndex.of]; null se passar os limites do servidor. */
    fun channelIndex(categories: List<Category>, channels: List<Channel>): ChannelIndex? = ChannelIndex.of(categories, channels)

    // ---- Gravações -----------------------------------------------------------------------------

    /**
     * [RecordingPlan.decide] para [r] na janela [fromMs]–[toMs]: "wait", "prompt", "start", "switch", "expired" ou
     * "skip:<razão>". [watchingKey] null = não se está a ver nada; [busy]: as gravações a decorrer ("lista|canal");
     * [answer]: "switch", "skip" ou null.
     */
    fun recordingDecide(
        r: ScheduledRecording, fromMs: Long, toMs: Long, nowMs: Long, watchingPlaylist: String?, watchingKey: String?,
        watchingLive: Boolean, busy: List<String>, answer: String?,
    ): String {
        val watching = if (watchingPlaylist != null && watchingKey != null) RecordingPlan.Watching(watchingPlaylist, watchingKey, watchingLive) else null
        val busyList = busy.map { RecordingPlan.Busy(it.substringBefore('|'), it.substringAfter('|')) }
        val a = when (answer) {
            "switch" -> RecordingPlan.Answer.SWITCH
            "skip" -> RecordingPlan.Answer.SKIP
            else -> null
        }
        return when (val d = RecordingPlan.decide(r, RecordingPlan.Window(fromMs, toMs), nowMs, watching, busyList, a)) {
            RecordingPlan.Decision.Wait -> "wait"
            RecordingPlan.Decision.Prompt -> "prompt"
            RecordingPlan.Decision.Start -> "start"
            RecordingPlan.Decision.Switch -> "switch"
            RecordingPlan.Decision.Expired -> "expired"
            is RecordingPlan.Decision.Skip -> "skip:" + d.reason
        }
    }

    /** [RecordingPlan.conflicts]: os ids das marcadas que se sobrepõem a [r]. */
    fun recordingConflicts(r: ScheduledRecording, all: List<ScheduledRecording>, beforeMin: Int, afterMin: Int): List<String> =
        RecordingPlan.conflicts(r, all, beforeMin, afterMin).map { it.id }

    /** [RecordingPlan.nextWake], ou -1. */
    fun recordingNextWake(schedule: Schedule, beforeMin: Int, afterMin: Int, nowMs: Long): Long =
        RecordingPlan.nextWake(schedule, beforeMin, afterMin, nowMs) ?: -1

    fun recordingTitleKey(title: String): String = RecordingPlan.titleKey(title)

    /** [RecordingPlan.seriesEntries]: as posições em [entries] dos programas que a série marca agora. */
    fun recordingSeriesEntries(rule: SeriesRule, entries: List<EpgEntry>, schedule: Schedule, nowMs: Long): List<Int> {
        val picked = RecordingPlan.seriesEntries(rule, entries, schedule, nowMs).toSet()
        return entries.indices.filter { entries[it] in picked }
    }

    /** [RecordingPlan.prune]: o que fica (as ids das marcadas e as chaves dos tratados). */
    fun recordingPrune(schedule: Schedule, beforeMin: Int, afterMin: Int, nowMs: Long): ApplePruned =
        RecordingPlan.prune(schedule, beforeMin, afterMin, nowMs).let { ApplePruned(it.recordings.map { r -> r.id }, it.handled.keys.toList()) }

    fun recordingCanStart(free: Long, bytesPerSecond: Long): Boolean = RecordingSpace.canStart(free, bytesPerSecond.takeIf { it >= 0 })

    fun recordingMustStop(free: Long): Boolean = RecordingSpace.mustStop(free)

    /** [RecordingSpace.rate], ou -1. */
    fun recordingRate(bytes: Long, mediaMs: Long): Long = RecordingSpace.rate(bytes, mediaMs) ?: -1

    /** [RecordingCut.range] com o índice em dois arrays lado a lado ([mediaMs] e [offsets]); null sem índice. */
    fun recordingCut(mediaMs: List<Long>, offsets: List<Long>, liveMediaMs: Long, nowWall: Long, startWall: Long, endWall: Long): AppleCut? =
        RecordingCut.range(mediaMs.zip(offsets) { m, o -> RingEntry(m, o) }, liveMediaMs, nowWall, startWall, endWall)
            ?.let { AppleCut(it.startOffset, it.startMediaMs, it.endMediaMs, it.fileStartMs, it.late) }

    // ---- Listas M3U e catch-up ------------------------------------------------------------------

    /** [M3uCatchup.spec], ou null. */
    fun m3uCatchupSpec(attrs: Map<String, String>, header: Map<String, String>, url: String): AppleCatchupSpec? =
        M3uCatchup.spec(attrs, header, url)?.let { AppleCatchupSpec(it.type, it.source, it.days) }

    fun m3uCatchupPrecisionMs(channel: Channel): Long = M3uCatchup.precisionMs(channel)

    /** [M3uCatchup.candidates] com as datas no fuso [zoneId] (ex.: "Europe/Lisbon"; um desconhecido conta como UTC). */
    fun m3uCatchupCandidates(channel: Channel, startS: Long, endS: Long, nowS: Long, zoneId: String): List<StreamCandidate> =
        M3uCatchup.candidates(channel, startS, endS, nowS, zone(zoneId))

    fun m3uCatchupExpand(template: String, startS: Long, endS: Long, nowS: Long, zoneId: String): String? =
        M3uCatchup.expand(template, startS, endS, nowS, zone(zoneId))

    fun m3uUrlKey(url: String): String = M3uParser.urlKey(url)

    fun m3uIsVod(url: String): Boolean = M3uParser.isVod(url)

    fun m3uAttributes(s: String): Map<String, String> = M3uParser.attributes(s)

    /** O número do canal nos atributos, ou -1. */
    fun m3uChannelNumber(attrs: Map<String, String>): Int = M3uParser.channelNumber(attrs) ?: -1

    // ---- Modo TV Pro (ProLineup, ProHealth) ----

    /** Os ids das tabelas que se podem escolher ("pt"), pela ordem do menu. */
    fun proLineupIds(): List<String> = ProLineup.LINEUPS.map { it.id }

    /** Os países sem tabela dos grupos [groupNames] ("pl"), do que tem mais grupos para o que tem menos. */
    fun proCountriesIn(groupNames: List<String>): List<String> = ProLineup.countriesIn(groupNames)

    /** O país da tabela [id] ("pt"), ou null se não existir. */
    fun proLineupCountry(id: String): String? = ProLineup.lineup(id)?.country

    /** A lista única da tabela [lineupId] feita com [sources] (as listas da pessoa, pela ordem: ver [AppleProSources]). */
    fun proBuild(lineupId: String, sources: AppleProSources): AppleProResult {
        val lineup = ProLineup.lineup(lineupId)
        val r = ProLineup.build(lineup, lineup?.country ?: lineupId, sources.list.toList())
        return AppleProResult(r.groups, r.channels.map { c ->
            AppleProChannel(c.id, c.number, c.name, c.group, c.sources.map { it.playlistId }, c.sources.map { it.key }, c.sources.map { it.quality })
        })
    }

    /** Os "Mais canais" de [result] que parecem um da tabela [lineupId] ("CNN PT ~ CNN"), para o registo. */
    fun proNearMisses(lineupId: String, result: AppleProResult): List<String> =
        ProLineup.nearMisses(ProLineup.lineup(lineupId), ProLineup.Result(result.groups, result.channels.map { ProLineup.Channel(it.id, it.number, it.name, it.group, emptyList()) }))

    fun proPlaylistId(lineupId: String): String = ProLineup.playlistId(lineupId)

    fun proIsPro(playlistId: String): Boolean = ProLineup.isPro(playlistId)

    fun proLineupIdOf(playlistId: String): String = ProLineup.lineupIdOf(playlistId)

    fun proMoreGroup(): String = ProLineup.MORE

    fun proHealthDecode(text: String?, nowMs: Long): ProHealth = ProHealth.decode(text, nowMs)

    // ---- A memória por canal (ChannelMemory) ----

    /** A memória dos canais guardada neste aparelho ([text] é o que o `encode` deu; null = vazia). */
    fun channelMemoryDecode(text: String?): ChannelMemory = ChannelMemory.decode(text)

    private fun zone(id: String): TimeZone = runCatching { TimeZone.of(id) }.getOrDefault(TimeZone.UTC)

    private val STATES = mapOf(
        ChannelMatch.State.AUTO to "auto",
        ChannelMatch.State.CHOSEN to "chosen",
        ChannelMatch.State.OFF to "off",
        ChannelMatch.State.UNSURE to "unsure",
        ChannelMatch.State.MISSING to "missing",
        ChannelMatch.State.NOT_SET to "not_set",
    )

    private fun result(r: ChannelMatch.Result): AppleResult = when (r) {
        is ChannelMatch.Result.Match -> AppleResult("match", r.index)
        is ChannelMatch.Result.Unsure -> AppleResult("unsure", r.count)
        ChannelMatch.Result.Missing -> AppleResult("missing", 0)
    }
}

/** Um nome partido ([ChannelMatch.parse]). */
class AppleParsed(val base: String, val country: String?, val quality: Int, val shift: Int)

/** Os canais de uma lista, juntos um a um (uma lista de objetos com nulos não passa direita para o Swift). */
class AppleEntries {
    internal val list = ArrayList<ChannelMatch.Entry>()

    fun add(key: String, name: String, epgId: String?, group: String?) {
        list += ChannelMatch.Entry(key, name, epgId, group)
    }
}

/** Os canais de uma lista de reserva, prontos a procurar ([ChannelMatch.Matcher]). */
class AppleMatcher(entries: AppleEntries) {
    internal val matcher = ChannelMatch.Matcher(entries.list.toList())

    /** A posição do canal [key], ou -1. */
    fun indexOfKey(key: String): Int = matcher.indexOfKey(key) ?: -1
}

/** [ChannelMatch.Result]: [kind] "match" (o canal [value]), "unsure" ([value] candidatos) ou "missing". */
class AppleResult(val kind: String, val value: Int)

/** [ChannelMatch.ReviewRow]: [state] como o Swift (auto, chosen, off, unsure, missing, not_set); -1 = nenhum. */
class AppleRow(val main: Int, val state: String, val backup: Int, val suggestion: Int, val failovers: List<String>)

/** [M3uCatchup.Spec]. */
class AppleCatchupSpec(val type: String, val source: String?, val days: Int)

/** Uma lista M3U lida linha a linha ([M3uParser.Reader]); [channelName]: o nome de um canal sem nome (desde 1). */
class AppleM3u(channelName: (Int) -> String) {
    private val reader = M3uParser.Reader(channelName)
    private var done: M3uParser.Result? = null

    fun line(raw: String) = reader.line(raw)

    private fun result(): M3uParser.Result = done ?: reader.result().also { done = it }

    fun channels(): List<Channel> = result().channels

    fun categories(): List<Category> = result().categories

    fun epgUrl(): String? = result().epgUrl
}

/** O que fica depois de [RecordingPlan.prune]. */
class ApplePruned(val recordingIds: List<String>, val handledKeys: List<String>)

/** [RecordingCut.Range]. */
class AppleCut(val startOffset: Long, val startMediaMs: Long, val endMediaMs: Long, val fileStartMs: Long, val late: Boolean)

/** As listas da pessoa para o Modo TV Pro, juntas canal a canal (uma lista de objetos com nulos não passa direita). */
class AppleProSources {
    internal val list = ArrayList<ProLineup.Source>()
    private var current: ArrayList<ProLineup.Input>? = null

    /** Começa a lista [playlistId] (as seguintes [channel] são dela). */
    fun playlist(playlistId: String) {
        val inputs = ArrayList<ProLineup.Input>()
        current = inputs
        list += ProLineup.Source(playlistId, inputs)
    }

    fun channel(key: String, name: String, group: String?) {
        current?.add(ProLineup.Input(key, name, group))
    }
}

/** [ProLineup.Result]. */
class AppleProResult(val groups: List<String>, val channels: List<AppleProChannel>)

/** [ProLineup.Channel]: as fontes em três listas paralelas (lista, chave do canal nela, qualidade), da melhor para a pior. */
class AppleProChannel(
    val id: String,
    val number: Int,
    val name: String,
    val group: String,
    val sourcePlaylists: List<String>,
    val sourceKeys: List<String>,
    val sourceQualities: List<Int>,
)
