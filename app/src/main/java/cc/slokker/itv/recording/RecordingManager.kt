package cc.slokker.itv.recording

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.recording.RecordingCut
import cc.slokker.itv.data.recording.RecordingFiles
import cc.slokker.itv.data.recording.RecordingInfo
import cc.slokker.itv.data.recording.RecordingPlan
import cc.slokker.itv.data.recording.RecordingPlan.Answer
import cc.slokker.itv.data.recording.RecordingPlan.Decision
import cc.slokker.itv.data.recording.RecordingReason
import cc.slokker.itv.data.recording.RecordingSpace
import cc.slokker.itv.data.recording.RecordingState
import cc.slokker.itv.data.recording.RecordingWriter
import cc.slokker.itv.data.recording.Schedule
import cc.slokker.itv.data.recording.ScheduledRecording
import cc.slokker.itv.data.recording.SeriesRule
import cc.slokker.itv.ui.player.ChannelFeed
import cc.slokker.itv.ui.player.engine.StreamGate
import cc.slokker.itv.ui.player.engine.providerHttp
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * As gravações da app (uma por processo, no AppContainer): gravar o canal a dar, as marcadas no guia, a lista no
 * disco, o serviço em primeiro plano e o alarme. Tudo na thread principal; a cópia para o disco corre numa thread
 * por gravação ([RecordingWriter]) e as fichas escrevem-se noutra.
 *
 * Uma ligação ao fornecedor: uma gravação segura a ligação do canal ([ChannelFeed]). Se o player está nesse canal
 * pelo anel, é a mesma ligação; sem player (app em segundo plano ou fechada), a gravação abre a sua, com um anel
 * pequeno, e o player, se voltar a esse canal, lê dela. Só se grava um canal de cada vez (programas seguidos do mesmo
 * canal gravam juntos, na mesma ligação).
 *
 * Quem diz o que o player está a ver ([watching]), as margens e os guias das séries é o ViewModel, quando existe; com
 * a app fechada, o alarme acorda o processo e as marcadas gravam com o que ficou guardado no [Schedule].
 */
class RecordingManager(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** As fichas escrevem-se por ordem, fora da thread principal. */
    private val disk = Executors.newSingleThreadExecutor { r -> Thread(r, "recordings-disk").apply { isDaemon = true } }

    val files = RecordingFiles(storageDir(app), File(app.filesDir, "recordings/schedule.json"))

    private val _list = MutableStateFlow<List<RecordingInfo>>(emptyList())

    /** As gravações no disco (as a decorrer também), as mais recentes primeiro. */
    val list: StateFlow<List<RecordingInfo>> = _list.asStateFlow()

    private val _schedule = MutableStateFlow(Schedule())
    val schedule: StateFlow<Schedule> = _schedule.asStateFlow()

    /** Uma gravação a decorrer. */
    data class Active(val info: RecordingInfo, val url: String) {
        val playlistId: String? get() = info.playlistId
        val channelKey: String? get() = info.channelKey
    }

    private class Running(var info: RecordingInfo, val feed: ChannelFeed, val writer: RecordingWriter) {
        /** Parou-se à mão (a ligação pode fechar antes de a escrita o ver: conta como parada à mão na mesma). */
        var stopped = false
    }

    private val running = LinkedHashMap<String, Running>()
    private val _active = MutableStateFlow<List<Active>>(emptyList())
    val active: StateFlow<List<Active>> = _active.asStateFlow()

    private val _prompt = MutableStateFlow<ScheduledRecording?>(null)

    /** A marcada que está quase a começar noutro canal: "Mudar para X para gravar" / "Não gravar". */
    val prompt: StateFlow<ScheduledRecording?> = _prompt.asStateFlow()

    private val _switchTo = MutableSharedFlow<ScheduledRecording>(extraBufferCapacity = 4)

    /** O player tem de mudar para o canal desta gravação (respondeu-se que sim, ou ninguém respondeu). */
    val switchTo: SharedFlow<ScheduledRecording> = _switchTo

    /** O canal que uma gravação usa (ou vai usar já a seguir): o player desse canal abre pelo anel dela. */
    private val _ringFor = MutableStateFlow<RecordingPlan.Busy?>(null)
    val ringFor: StateFlow<RecordingPlan.Busy?> = _ringFor.asStateFlow()
    private var ringForUrl: String? = null

    sealed interface Event {
        data class Finished(val info: RecordingInfo) : Event
        data class Skipped(val recording: ScheduledRecording, val reason: String) : Event
        data class Started(val info: RecordingInfo) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events

    /** O canal no player, dito pelo ViewModel (null: nada, a app em segundo plano, ou uma gravação no disco). */
    var watching: RecordingPlan.Watching? = null
        set(value) {
            if (field == value) return
            field = value
            tick()
        }

    /** O guia de um canal de uma lista (para as séries), quando o ViewModel o tem; null sem ele. */
    var guide: ((playlistId: String, channelKey: String) -> List<EpgEntry>?)? = null

    private val answers = HashMap<String, Answer>()
    private val switched = HashSet<String>()

    /** Quando uma marcada do canal a ver chegou à hora sem o player ainda ter a ligação (acabou de mudar para ele). */
    private val waitingPlayer = HashMap<String, Long>()

    init {
        _schedule.value = files.loadSchedule()
        disk.execute {
            // Uma ficha "a gravar" de um processo que morreu: acabou aí, com o que ficou no ficheiro.
            files.list().filter { it.recording }.forEach { info ->
                val bytes = files.videoFile(info.id).length()
                files.write(info.copy(state = if (bytes > 0) RecordingState.STOPPED else RecordingState.FAILED, reason = RecordingReason.APP_ENDED, bytes = bytes))
            }
            refreshList()
        }
        scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
        RecordingAlarm.arm(app, nextWake())
    }

    // ---- Espaço --------------------------------------------------------------------------------

    /** O espaço livre onde ficam as gravações. */
    fun freeBytes(): Long = files.dir.apply { mkdirs() }.usableSpace

    // ---- Gravar o canal a dar -----------------------------------------------------------------

    /** O que se grava: o canal e o programa (sem guia, [programmeStartMs]/[programmeEndMs] null). */
    data class Meta(
        val playlistId: String?,
        val channelKey: String?,
        val channelName: String,
        val logo: String?,
        val title: String,
        val programmeStartMs: Long?,
        val programmeEndMs: Long?,
        val scheduledId: String? = null,
    )

    /** Porque não começou. */
    enum class Refusal { NO_SPACE, NOT_RECORDABLE, OTHER_CHANNEL, ALREADY }

    /**
     * Grava o programa do canal de [feed] (a ligação do player, pela pausa do direto): o que o anel já tem desde o
     * início do programa (menos a margem), e continua até ao fim com a margem. Sem guia, de agora até
     * [RecordingPlan.MAX_NO_EPG_MS]. Devolve porque não começou, ou null.
     */
    internal fun recordNow(feed: ChannelFeed, meta: Meta): Refusal? {
        if (running.values.any { it.feed !== feed }) return Refusal.OTHER_CHANNEL
        if (running.values.any { it.info.channelKey == meta.channelKey && it.info.programmeStartMs == (meta.programmeStartMs ?: -1L) }) return Refusal.ALREADY
        val ring = feed.ring
        if (ring.isTs != true || feed.isClosed) return Refusal.NOT_RECORDABLE
        if (!RecordingSpace.canStart(freeBytes(), RecordingSpace.rate(minOf(ring.writtenBytes, ring.capacity), ring.liveMediaMs - (ring.oldest?.mediaMs ?: 0)))) return Refusal.NO_SPACE
        if (!feed.hold(ChannelFeed.Holder.RECORDING)) return Refusal.NOT_RECORDABLE
        val now = System.currentTimeMillis()
        val s = _schedule.value
        val pStart = meta.programmeStartMs
        val pEnd = meta.programmeEndMs
        val (from, to) = if (pStart != null && pEnd != null && pEnd > now) {
            RecordingPlan.window(pStart, pEnd, s.paddingBeforeMin, s.paddingAfterMin).let { it.fromMs to it.toMs }
        } else {
            now to now + RecordingPlan.MAX_NO_EPG_MS
        }
        val range = RecordingCut.range(ring.index(), ring.liveMediaMs, now, from, to)
        if (range == null) {
            feed.release(ChannelFeed.Holder.RECORDING)
            return Refusal.NOT_RECORDABLE
        }
        start(feed, meta, from, to, range.startOffset, range.startMediaMs, range.endMediaMs, range.fileStartMs)
        return null
    }

    private fun start(feed: ChannelFeed, meta: Meta, from: Long, to: Long, offset: Long, mediaStart: Long, mediaEnd: Long, fileStart: Long) {
        val id = newId(System.currentTimeMillis())
        val info = RecordingInfo(
            id = id, playlistId = meta.playlistId, channelKey = meta.channelKey, channelName = meta.channelName, logo = meta.logo,
            title = meta.title, programmeStartMs = meta.programmeStartMs ?: from, programmeEndMs = meta.programmeEndMs ?: to,
            startMs = from, endMs = to, fileStartMs = fileStart, fileEndMs = fileStart, mediaStartMs = mediaStart, mediaEndMs = mediaStart,
            bytes = 0, state = RecordingState.RECORDING, scheduledId = meta.scheduledId,
        )
        val writer = RecordingWriter(
            feed.ring, files.videoFile(id), offset, mediaEnd, freeBytes = ::freeBytes,
            onProgress = { bytes, media -> main.post { progress(id, bytes, media) } },
        )
        val r = Running(info, feed, writer)
        running[id] = r
        publishActive()
        disk.execute {
            files.write(info)
            refreshList()
        }
        Thread({
            val result = writer.run()
            main.post { finish(id, result) }
        }, "recording-$id").apply { isDaemon = true }.start()
        RecordingService.update(app, running.values.map { it.info })
        _events.tryEmit(Event.Started(info))
        Log.i(TAG, "A gravar ${meta.title} (${meta.channelName}) de ${mediaStart / 1000}s, até ${mediaEnd / 1000}s")
    }

    private fun progress(id: String, bytes: Long, media: Long) {
        val r = running[id] ?: return
        val info = r.info.copy(bytes = bytes, mediaEndMs = media, fileEndMs = r.info.fileStartMs + (media - r.info.mediaStartMs))
        r.info = info
        disk.execute {
            files.write(info)
            refreshList()
        }
    }

    private fun finish(id: String, result: RecordingWriter.Result) {
        val r = running.remove(id) ?: return
        val reason = if (r.stopped && result.state == RecordingState.STOPPED) RecordingReason.USER else result.reason
        val info = r.info.copy(
            state = result.state, reason = reason, bytes = result.bytes, gaps = result.gaps,
            mediaEndMs = result.mediaEndMs.coerceAtLeast(r.info.mediaStartMs),
            fileEndMs = r.info.fileStartMs + (result.mediaEndMs - r.info.mediaStartMs).coerceAtLeast(0),
        )
        r.feed.release(ChannelFeed.Holder.RECORDING)
        publishActive()
        disk.execute {
            files.write(info)
            if (info.state == RecordingState.FAILED) files.videoFile(id).delete()
            refreshList()
        }
        info.scheduledId?.let { sid -> updateSchedule { s -> s.copy(recordings = s.recordings.filter { it.id != sid }) } }
        RecordingService.update(app, running.values.map { it.info })
        _events.tryEmit(Event.Finished(info))
        Log.i(TAG, "Gravação ${info.title}: ${info.state} ${info.reason.orEmpty()} (${info.bytes / 1_048_576} MB, ${info.gaps} buracos)")
        tick()
    }

    /** Volta a mostrar a notificação (ex.: depois de o utilizador dar a autorização das notificações a meio). */
    fun refreshNotification() {
        if (running.isNotEmpty()) RecordingService.update(app, running.values.map { it.info })
    }

    /** Pára [id] (fica o que já gravou). */
    fun stop(id: String) {
        running[id]?.let {
            it.stopped = true
            it.writer.stop()
        }
    }

    fun stopAll() {
        running.keys.toList().forEach(::stop)
    }

    /** A gravação de [id] ainda decorre. */
    fun isRunning(id: String): Boolean = id in running

    /** Apaga uma gravação acabada (as que decorrem param-se primeiro). */
    fun delete(id: String) {
        if (id in running) return
        disk.execute {
            files.delete(id)
            refreshList()
        }
    }

    private fun publishActive() {
        _active.value = running.values.map { Active(it.info, it.feed.url) }
        updateRingFor()
    }

    private fun refreshList() {
        _list.value = files.list()
    }

    // ---- Marcadas ------------------------------------------------------------------------------

    private fun updateSchedule(change: (Schedule) -> Schedule) {
        val next = change(_schedule.value)
        _schedule.value = next
        disk.execute { files.saveSchedule(next) }
        RecordingAlarm.arm(app, nextWake())
    }

    /** As margens das Definições (guardam-se com a lista, para valerem com a app fechada). */
    fun setPadding(beforeMin: Int, afterMin: Int) {
        val s = _schedule.value
        if (s.paddingBeforeMin == beforeMin && s.paddingAfterMin == afterMin) return
        updateSchedule { it.copy(paddingBeforeMin = beforeMin, paddingAfterMin = afterMin) }
    }

    fun scheduledFor(playlistId: String, channelKey: String, startMs: Long): ScheduledRecording? =
        _schedule.value.recordings.firstOrNull { it.sameChannel(playlistId, channelKey) && it.startMs == startMs }

    fun seriesFor(playlistId: String, channelKey: String, title: String): SeriesRule? {
        val key = RecordingPlan.titleKey(title)
        return _schedule.value.series.firstOrNull { it.playlistId == playlistId && it.channelKey == channelKey && RecordingPlan.titleKey(it.title) == key }
    }

    /** Marca [r]; devolve as marcadas de outros canais que se sobrepõem (só uma vai gravar). */
    fun schedule(r: ScheduledRecording): List<ScheduledRecording> {
        updateSchedule { s -> s.copy(recordings = (s.recordings.filter { it.instanceKey != r.instanceKey } + r).sortedBy { it.startMs }) }
        val s = _schedule.value
        tick()
        return RecordingPlan.conflicts(r, s.recordings, s.paddingBeforeMin, s.paddingAfterMin)
    }

    /** Desmarca [id]; um programa de uma série não volta a ser marcado por ela. */
    fun cancel(id: String) {
        answers.remove(id)
        if (_prompt.value?.id == id) _prompt.value = null
        updateSchedule { s ->
            val r = s.recordings.firstOrNull { it.id == id } ?: return@updateSchedule s
            s.copy(recordings = s.recordings - r, handled = s.handled + (r.instanceKey to r.startMs))
        }
    }

    /** "Gravar a série" de [rule]: marca [first] (o programa escolhido, se ainda não está a gravar) e os seguintes. */
    fun addSeries(rule: SeriesRule, first: ScheduledRecording?, make: (SeriesRule, EpgEntry) -> ScheduledRecording?) {
        updateSchedule { s -> s.copy(series = s.series.filter { it.id != rule.id } + rule) }
        first?.let(::schedule)
        expandSeries(make)
    }

    /** Tira a regra da série e os programas dela ainda por gravar. */
    fun cancelSeries(seriesId: String) {
        updateSchedule { s ->
            val gone = s.recordings.filter { it.seriesId == seriesId }
            s.copy(
                series = s.series.filter { it.id != seriesId },
                recordings = s.recordings - gone.toSet(),
                handled = s.handled + gone.associate { it.instanceKey to it.startMs },
            )
        }
    }

    /** Marca os programas novos das séries que o guia já tem ([make] faz a marcada de um programa da série). */
    fun expandSeries(make: (SeriesRule, EpgEntry) -> ScheduledRecording?) {
        val lookup = guide ?: return
        val now = System.currentTimeMillis()
        val s = _schedule.value
        val added = s.series.flatMap { rule ->
            val entries = lookup(rule.playlistId, rule.channelKey) ?: return@flatMap emptyList()
            RecordingPlan.seriesEntries(rule, entries, s, now).mapNotNull { e -> make(rule, e)?.copy(seriesId = rule.id) }
        }
        if (added.isEmpty()) return
        updateSchedule { cur -> cur.copy(recordings = (cur.recordings + added).distinctBy { it.instanceKey }.sortedBy { it.startMs }) }
    }

    /** A resposta ao aviso de [id]. */
    fun answer(id: String, switch: Boolean) {
        answers[id] = if (switch) Answer.SWITCH else Answer.SKIP
        _prompt.value = null
        tick()
    }

    // ---- A hora das marcadas -------------------------------------------------------------------

    /** O alarme acordou a app (ver [RecordingAlarm]): o que começa agora começa já, e põe-se o alarme seguinte. */
    fun onAlarm() {
        tick(System.currentTimeMillis() + RecordingPlan.ALARM_SLACK_MS)
        // Com a app em segundo plano, o serviço só pode começar agora (por causa do alarme), mesmo que a gravação já
        // tenha começado uns segundos antes pela volta normal.
        if (running.isNotEmpty()) RecordingService.update(app, running.values.map { it.info })
        RecordingAlarm.arm(app, nextWake())
    }

    private fun nextWake(): Long? {
        val s = _schedule.value
        return RecordingPlan.nextWake(s, s.paddingBeforeMin, s.paddingAfterMin, System.currentTimeMillis())
    }

    /** Vê as marcadas: avisa, muda de canal, começa ou salta. De [TICK_MS] em [TICK_MS] e a cada mudança. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val s = _schedule.value
        val busy = running.values.mapNotNull { r -> r.info.playlistId?.let { p -> r.info.channelKey?.let { RecordingPlan.Busy(p, it) } } }
        var prompt: ScheduledRecording? = null
        for (r in s.recordings) {
            if (running.values.any { it.info.scheduledId == r.id }) continue
            val w = RecordingPlan.window(r, s.paddingBeforeMin, s.paddingAfterMin)
            when (val d = RecordingPlan.decide(r, w, now, watching, busy, answers[r.id])) {
                Decision.Wait -> Unit
                Decision.Prompt -> if (prompt == null) prompt = r
                Decision.Switch -> if (switched.add(r.id)) _switchTo.tryEmit(r)
                Decision.Start -> startScheduled(r, w, now)
                is Decision.Skip -> skip(r, d.reason)
                Decision.Expired -> skip(r, null)
            }
        }
        if (_prompt.value?.id != prompt?.id) _prompt.value = prompt
        updateRingFor()
        val pruned = RecordingPlan.prune(_schedule.value, s.paddingBeforeMin, s.paddingAfterMin, now)
        if (pruned != _schedule.value) updateSchedule { pruned }
    }

    private fun skip(r: ScheduledRecording, reason: String?) {
        answers.remove(r.id)
        switched.remove(r.id)
        updateSchedule { s -> s.copy(recordings = s.recordings.filter { it.id != r.id }, handled = s.handled + (r.instanceKey to r.startMs)) }
        if (reason != null) _events.tryEmit(Event.Skipped(r, reason))
        Log.i(TAG, "Não gravada: ${r.title} (${reason ?: "passou a hora"})")
    }

    /**
     * Começa a marcada [r]: na ligação que já está aberta para o canal (o player ou outra gravação), ou numa nova (sem
     * ninguém a ver). Se o player está no canal sem o anel (pausa do direto desligada, VLC, HLS), abre-se a ligação da
     * gravação: o portão fecha a do player primeiro, e o player volta pelo anel desta ([ringFor]).
     */
    private fun startScheduled(r: ScheduledRecording, w: RecordingPlan.Window, now: Long) {
        val url = r.url ?: return skip(r, RecordingPlan.SKIP_HLS)
        val meta = Meta(r.playlistId, r.channelKey, r.channelName, r.logo, r.title, r.startMs, r.endMs, scheduledId = r.id)
        val shared = ChannelFeed.find(url)?.takeIf { it.hold(ChannelFeed.Holder.RECORDING) }
        if (shared != null) {
            val ring = shared.ring
            val range = RecordingCut.range(ring.index(), ring.liveMediaMs, now, w.fromMs, w.toMs)
            if (range == null) {
                // Ainda sem dados (acabou de abrir): tenta no próximo.
                shared.release(ChannelFeed.Holder.RECORDING)
                return
            }
            waitingPlayer.remove(r.id)
            return start(shared, meta, w.fromMs, w.toMs, range.startOffset, range.startMediaMs, range.endMediaMs, range.fileStartMs)
        }
        // O player está (ou acabou de ficar) neste canal: espera uns segundos que ele abra a ligação, para a gravação
        // usar a dele (com o anel grande da pausa do direto) em vez de abrir uma e o player vir depois ler desta.
        val v = watching
        if (v != null && v.live && r.sameChannel(v.playlistId, v.channelKey)) {
            val since = waitingPlayer.getOrPut(r.id) { now }
            if (now - since < PLAYER_WAIT_MS) return
        }
        waitingPlayer.remove(r.id)
        if (!RecordingSpace.canStart(freeBytes(), null)) return skip(r, RecordingPlan.SKIP_SPACE)
        // O serviço primeiro: com a app em segundo plano, é ele que deixa a gravação continuar.
        RecordingService.update(app, listOf(placeholder(r, now)))
        val feed = ChannelFeed.open(
            ChannelFeed.dir(app), url, providerHttp(r.userAgent, r.referrer), StreamGate.MAIN,
            reconnectMs = RECONNECT_MS, holder = ChannelFeed.Holder.RECORDING, maxBytes = BACKGROUND_RING_BYTES,
        ) ?: return skip(r, RecordingPlan.SKIP_SPACE)
        // Anel novo: o vídeo começa a contar agora (quando chegar o primeiro byte); grava tudo até ao fim da janela.
        start(feed, meta, w.fromMs, w.toMs, 0L, 0L, (w.toMs - now).coerceAtLeast(0), now)
    }

    private fun placeholder(r: ScheduledRecording, now: Long) = RecordingInfo(
        id = r.id, playlistId = r.playlistId, channelKey = r.channelKey, channelName = r.channelName, logo = r.logo, title = r.title,
        programmeStartMs = r.startMs, programmeEndMs = r.endMs, startMs = now, endMs = r.endMs, fileStartMs = now, fileEndMs = now,
        mediaStartMs = 0, mediaEndMs = 0, bytes = 0, state = RecordingState.RECORDING,
    )

    /**
     * O canal cuja ligação é (ou vai ser já) de uma gravação: o player desse canal abre pelo anel, mesmo com a pausa do
     * direto desligada. Vale também um minuto antes de uma marcada do canal a ver, para o player já lá estar.
     */
    private fun updateRingFor() {
        val now = System.currentTimeMillis()
        val s = _schedule.value
        val activeOne = running.values.firstOrNull()
        val soon = s.recordings.firstOrNull { r ->
            val w = RecordingPlan.window(r, s.paddingBeforeMin, s.paddingAfterMin)
            val v = watching
            now >= w.fromMs - RecordingPlan.PROMPT_LEAD_MS && now < w.toMs && v != null && r.sameChannel(v.playlistId, v.channelKey)
        }
        val next = when {
            activeOne != null -> activeOne.info.playlistId?.let { p -> activeOne.info.channelKey?.let { RecordingPlan.Busy(p, it) } }
            soon != null -> RecordingPlan.Busy(soon.playlistId, soon.channelKey)
            else -> null
        }
        ringForUrl = activeOne?.feed?.url ?: soon?.url
        if (_ringFor.value != next) _ringFor.value = next
    }

    /** O URL que o player deve abrir pelo anel para o canal [playlistId]/[channelKey] (ver [ringFor]), ou null. */
    fun ringUrl(playlistId: String?, channelKey: String): String? =
        ringForUrl?.takeIf { _ringFor.value?.let { it.playlistId == playlistId && it.channelKey == channelKey } == true }

    companion object {
        private const val TAG = "Recordings"
        private const val TICK_MS = 5_000L
        private const val RECONNECT_MS = 5_000L

        /** Quanto se espera pelo player que está a abrir o canal da gravação (ver [startScheduled]). */
        private const val PLAYER_WAIT_MS = 6_000L

        /** Uma gravação sem ninguém a ver só precisa de um anel pequeno (uns minutos, para a escrita ir atrás). */
        private const val BACKGROUND_RING_BYTES = 256L * 1024 * 1024

        /** Onde ficam as gravações: na pasta da app no armazenamento externo, se houver; senão na interna. */
        fun storageDir(context: Context): File {
            val external = context.getExternalFilesDir("recordings")
            if (external != null && Environment.getExternalStorageState(external) == Environment.MEDIA_MOUNTED) return external
            return File(context.filesDir, "recordings/files")
        }

        fun newId(now: Long): String =
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(now)) + "-" +
                java.util.UUID.randomUUID().toString().take(6)
    }
}
