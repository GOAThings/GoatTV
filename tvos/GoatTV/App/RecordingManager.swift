import Foundation
import GoatCore
import Observation

/// As gravações da app (o RecordingManager do GoatTV Android): gravar o canal a dar, as marcadas no guia e a lista no
/// disco. Tudo na thread principal; a cópia para o disco corre numa thread por gravação ([RecordingWriter]) e as fichas
/// escrevem-se noutra fila.
///
/// Uma ligação ao fornecedor: uma gravação segura a ligação do canal ([ChannelFeed]). Se o leitor está nesse canal pelo
/// anel, é a mesma ligação; sem leitor nesse canal (a ver uma gravação do disco), a gravação abre a sua, com um anel
/// pequeno, e o leitor, se voltar a esse canal, lê dela. Só se grava um canal de cada vez.
///
/// Ao contrário do Android, só grava com a app aberta e à frente: o tvOS e o iOS não deixam uma app gravar em segundo
/// plano (nem acordar à hora de uma marcada). Ao sair da app, as gravações param e ficam "paradas" (`app_ended`), como
/// no Android quando o processo morre. No Apple TV o que se grava fica na cache, que o sistema pode limpar.
@MainActor
@Observable
final class RecordingManager {
    /// O que se grava: o canal e o programa (sem guia, [programmeStartMs]/[programmeEndMs] nil).
    struct Meta {
        var playlistId: String?
        var channelKey: String?
        var channelName: String
        var logo: String?
        var title: String
        var programmeStartMs: Int64?
        var programmeEndMs: Int64?
        var scheduledId: String?
    }

    /// Porque não começou.
    enum Refusal { case noSpace, notRecordable, otherChannel, already }

    enum Event {
        case started(RecordingInfo)
        case finished(RecordingInfo)
        case skipped(ScheduledRecording, String)
    }

    /// As gravações no disco (as a decorrer também), as mais recentes primeiro.
    private(set) var list: [RecordingInfo] = []
    /// O que está marcado.
    private(set) var schedule = Schedule()
    /// As gravações a decorrer.
    private(set) var active: [RecordingInfo] = []
    /// A marcada que está quase a começar noutro canal: "Mudar para X para gravar" / "Não gravar".
    private(set) var prompt: ScheduledRecording?

    /// O canal no leitor, dito pela app (nil: nada, ou uma gravação do disco).
    @ObservationIgnored var watching: RecordingPlan.Watching? {
        didSet { if watching != oldValue { tick() } }
    }
    /// O guia de um canal de uma lista (para as séries), quando a app o tem.
    @ObservationIgnored var guide: ((_ playlistId: String, _ channelKey: String) -> [EpgEntry]?)?
    @ObservationIgnored var onEvent: ((Event) -> Void)?
    /// O leitor tem de mudar para o canal desta gravação (respondeu-se que sim, ou ninguém respondeu).
    @ObservationIgnored var onSwitch: ((ScheduledRecording) -> Void)?
    /// Uma marcada vai abrir a sua ligação: o leitor larga antes a dele (uma ligação de cada vez) e volta a abrir o
    /// canal depois, pelo anel dela.
    @ObservationIgnored var beforeOwnFeed: (() -> Void)?
    @ObservationIgnored var afterOwnFeed: (() -> Void)?

    let files: RecordingFiles

    private final class Running {
        var info: RecordingInfo
        let feed: ChannelFeed
        let writer: RecordingWriter
        /// Porque se pediu para parar (à mão, ou a app saiu): ganha ao que a escrita diz.
        var stopReason: String?

        init(info: RecordingInfo, feed: ChannelFeed, writer: RecordingWriter) {
            self.info = info
            self.feed = feed
            self.writer = writer
        }
    }

    @ObservationIgnored private var running: [String: Running] = [:]
    @ObservationIgnored private var answers: [String: RecordingPlan.Answer] = [:]
    @ObservationIgnored private var switched: Set<String> = []
    /// Quando uma marcada do canal a ver chegou à hora sem o leitor ainda ter a ligação (acabou de mudar para ele).
    @ObservationIgnored private var waitingPlayer: [String: Int64] = [:]
    @ObservationIgnored private var ticker: Task<Void, Never>?
    /// As fichas escrevem-se por ordem, fora da thread principal.
    private let disk = DispatchQueue(label: "recordings-disk")

    init() {
        let base: URL
        #if os(tvOS)
        // O tvOS só deixa escrever na cache (que pode limpar com pouco espaço): a lista avisa.
        base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        #else
        base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        #endif
        var dir = base.appendingPathComponent("recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        #if !os(tvOS)
        // Vídeos grandes: ficam no telemóvel, fora das cópias do iCloud.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        #endif
        // A lista do que está marcado tem os URLs com as credenciais: fica nas preferências da app, não ao lado dos vídeos.
        files = RecordingFiles(dir: dir, scheduleFile: dir.appendingPathComponent("schedule.json"))
        if let data = UserDefaults.standard.data(forKey: Self.scheduleKey), let js = try? JSON.parse(data) {
            schedule = Schedule(json: js)
        }
        let files = files
        disk.async { [weak self] in
            // Uma ficha "a gravar" de uma sessão que acabou a meio: acabou aí, com o que ficou no ficheiro.
            for info in files.list() where info.recording {
                var fixed = info
                let bytes = (try? FileManager.default.attributesOfItem(atPath: files.videoFile(info.id).path)[.size] as? NSNumber)?.int64Value ?? 0
                fixed.state = bytes > 0 ? RecordingState.stopped : RecordingState.failed
                fixed.reason = RecordingReason.appEnded
                fixed.bytes = bytes
                try? files.write(fixed)
            }
            let list = files.list()
            Task { @MainActor in self?.list = list }
        }
        startTicker()
    }

    // MARK: Espaço

    /// O espaço livre onde ficam as gravações.
    func freeBytes() -> Int64 { ChannelFeed.freeBytes(files.dir) }

    // MARK: Gravar o canal a dar

    /// Grava o programa do canal de [feed] (a ligação do leitor, pela pausa do direto): o que o anel já tem desde o
    /// início do programa (menos a margem), e continua até ao fim com a margem. Sem guia, de agora até
    /// [RecordingPlan.maxNoEpgMs]. Devolve porque não começou, ou nil.
    func recordNow(feed: ChannelFeed, meta: Meta) -> Refusal? {
        if running.values.contains(where: { $0.feed !== feed }) { return .otherChannel }
        if running.values.contains(where: { $0.info.channelKey == meta.channelKey && $0.info.programmeStartMs == (meta.programmeStartMs ?? -1) }) {
            return .already
        }
        let ring = feed.ring
        guard ring.isTs == true, !feed.isClosed else { return .notRecordable }
        let rate = RecordingSpace.rate(bytes: min(ring.writtenBytes, ring.capacity), mediaMs: ring.liveMediaMs - (ring.oldest?.mediaMs ?? 0))
        guard RecordingSpace.canStart(free: freeBytes(), bytesPerSecond: rate) else { return .noSpace }
        guard feed.hold(.recording) else { return .notRecordable }
        let now = nowMs()
        let window: RecordingPlan.Window
        if let start = meta.programmeStartMs, let end = meta.programmeEndMs, end > now {
            window = RecordingPlan.window(start, end, schedule.paddingBeforeMin, schedule.paddingAfterMin)
        } else {
            window = RecordingPlan.window(now, now + RecordingPlan.maxNoEpgMs, 0, 0)
        }
        guard let range = RecordingCut.range(ring.index, liveMediaMs: ring.liveMediaMs, nowWall: now, startWall: window.fromMs, endWall: window.toMs) else {
            feed.release(.recording)
            return .notRecordable
        }
        start(feed, meta, window, offset: range.startOffset, mediaStart: range.startMediaMs, mediaEnd: range.endMediaMs, fileStart: range.fileStartMs)
        return nil
    }

    private func start(_ feed: ChannelFeed, _ meta: Meta, _ w: RecordingPlan.Window, offset: Int64, mediaStart: Int64, mediaEnd: Int64, fileStart: Int64) {
        let id = Self.newId(nowMs())
        let info = RecordingInfo(
            id: id, playlistId: meta.playlistId, channelKey: meta.channelKey, channelName: meta.channelName, logo: meta.logo,
            title: meta.title, programmeStartMs: meta.programmeStartMs ?? w.fromMs, programmeEndMs: meta.programmeEndMs ?? w.toMs,
            startMs: w.fromMs, endMs: w.toMs, fileStartMs: fileStart, fileEndMs: fileStart, mediaStartMs: mediaStart, mediaEndMs: mediaStart,
            bytes: 0, state: RecordingState.recording, scheduledId: meta.scheduledId
        )
        let dir = files.dir
        let writer = RecordingWriter(ring: feed.ring, out: files.videoFile(id), startOffset: offset, endMediaMs: mediaEnd,
                                     freeBytes: { ChannelFeed.freeBytes(dir) },
                                     onProgress: { [weak self] bytes, media in
                                         Task { @MainActor in self?.progress(id, bytes, media) }
                                     })
        running[id] = Running(info: info, feed: feed, writer: writer)
        publishActive()
        write(info)
        Thread { [weak self] in
            let result = writer.run()
            Task { @MainActor in self?.finish(id, result) }
        }.start()
        onEvent?(.started(info))
        DebugLog.add("Recordings: recording \(meta.title) (\(meta.channelName)) from \(mediaStart / 1000) s to \(mediaEnd / 1000) s")
    }

    private func progress(_ id: String, _ bytes: Int64, _ media: Int64) {
        guard let r = running[id] else { return }
        r.info.bytes = bytes
        r.info.mediaEndMs = media
        r.info.fileEndMs = r.info.fileStartMs + (media - r.info.mediaStartMs)
        write(r.info)
    }

    private func finish(_ id: String, _ result: RecordingWriter.Result) {
        guard let r = running.removeValue(forKey: id) else { return }
        var info = r.info
        info.state = result.state
        info.reason = result.state == RecordingState.stopped ? (r.stopReason ?? result.reason) : result.reason
        info.bytes = result.bytes
        info.gaps = result.gaps
        info.mediaEndMs = max(info.mediaStartMs, result.mediaEndMs)
        info.fileEndMs = info.fileStartMs + max(0, result.mediaEndMs - info.mediaStartMs)
        r.feed.release(.recording)
        publishActive()
        let files = files
        let failed = info.state == RecordingState.failed
        let saved = info
        disk.async { [weak self] in
            try? files.write(saved)
            if failed { try? FileManager.default.removeItem(at: files.videoFile(id)) }
            let list = files.list()
            Task { @MainActor in self?.list = list }
        }
        if let sid = info.scheduledId { updateSchedule { s in s.recordings.removeAll { $0.id == sid } } }
        onEvent?(.finished(info))
        DebugLog.add("Recordings: \(info.title) \(info.state) \(info.reason ?? "") (\(info.bytes / 1_048_576) MB, \(info.gaps) gaps)")
        tick()
    }

    private func write(_ info: RecordingInfo) {
        let files = files
        disk.async { [weak self] in
            try? files.write(info)
            let list = files.list()
            Task { @MainActor in self?.list = list }
        }
    }

    /// Pára [id] (fica o que já gravou).
    func stop(_ id: String, reason: String = RecordingReason.user) {
        guard let r = running[id] else { return }
        r.stopReason = reason
        r.writer.stop()
    }

    func stopAll(reason: String = RecordingReason.user) {
        for id in running.keys { stop(id, reason: reason) }
    }

    /// A app vai para segundo plano: o tvOS/iOS não a deixam gravar lá, por isso tudo pára agora (fica o que já está
    /// gravado, como no Android quando o processo morre) e as marcadas só voltam a valer com a app aberta.
    func appEnded() {
        stopAll(reason: RecordingReason.appEnded)
        prompt = nil
        ticker?.cancel()
        ticker = nil
    }

    /// De volta à app.
    func appActive() {
        startTicker()
        tick()
    }

    func isRunning(_ id: String) -> Bool { running[id] != nil }

    /// Apaga uma gravação acabada (as que decorrem param-se primeiro).
    func delete(_ id: String) {
        guard running[id] == nil else { return }
        let files = files
        disk.async { [weak self] in
            files.delete(id)
            let list = files.list()
            Task { @MainActor in self?.list = list }
        }
    }

    private func publishActive() {
        active = running.values.map(\.info).sorted { $0.fileStartMs < $1.fileStartMs }
    }

    /// A gravação a decorrer do canal [channelKey] da lista [playlistId].
    func activeFor(_ playlistId: String?, _ channelKey: String) -> RecordingInfo? {
        active.first { $0.playlistId == playlistId && $0.channelKey == channelKey }
    }

    // MARK: Marcadas

    private func updateSchedule(_ change: (inout Schedule) -> Void) {
        var next = schedule
        change(&next)
        guard next != schedule else { return }
        schedule = next
        UserDefaults.standard.set(next.json.encoded(), forKey: Self.scheduleKey)
    }

    /// As margens das Definições (guardam-se com a lista, como no Android).
    func setPadding(before: Int, after: Int) {
        updateSchedule { s in
            s.paddingBeforeMin = before
            s.paddingAfterMin = after
        }
    }

    func scheduledFor(_ playlistId: String, _ channelKey: String, _ startMs: Int64) -> ScheduledRecording? {
        schedule.recordings.first { $0.sameChannel(playlistId, channelKey) && $0.startMs == startMs }
    }

    func seriesFor(_ playlistId: String, _ channelKey: String, _ title: String) -> SeriesRule? {
        let key = RecordingPlan.titleKey(title)
        return schedule.series.first { $0.playlistId == playlistId && $0.channelKey == channelKey && RecordingPlan.titleKey($0.title) == key }
    }

    /// Marca [r]; devolve as marcadas de outros canais que se sobrepõem (só uma vai gravar).
    @discardableResult
    func add(_ r: ScheduledRecording) -> [ScheduledRecording] {
        updateSchedule { s in
            s.recordings = (s.recordings.filter { $0.instanceKey != r.instanceKey } + [r]).sorted { $0.startMs < $1.startMs }
        }
        tick()
        return RecordingPlan.conflicts(r, schedule.recordings, schedule.paddingBeforeMin, schedule.paddingAfterMin)
    }

    /// Desmarca [id]; um programa de uma série não volta a ser marcado por ela.
    func cancel(_ id: String) {
        answers.removeValue(forKey: id)
        if prompt?.id == id { prompt = nil }
        updateSchedule { s in
            guard let r = s.recordings.first(where: { $0.id == id }) else { return }
            s.recordings.removeAll { $0.id == id }
            s.handled[r.instanceKey] = r.startMs
        }
    }

    /// "Gravar a série" de [rule]: marca [first] (o programa escolhido, se ainda não está a gravar) e os seguintes.
    func addSeries(_ rule: SeriesRule, first: ScheduledRecording?, make: (SeriesRule, EpgEntry) -> ScheduledRecording?) {
        updateSchedule { s in s.series = s.series.filter { $0.id != rule.id } + [rule] }
        if let first { add(first) }
        expandSeries(make)
    }

    /// Tira a regra da série e os programas dela ainda por gravar.
    func cancelSeries(_ seriesId: String) {
        updateSchedule { s in
            let gone = s.recordings.filter { $0.seriesId == seriesId }
            s.series.removeAll { $0.id == seriesId }
            s.recordings.removeAll { $0.seriesId == seriesId }
            for r in gone { s.handled[r.instanceKey] = r.startMs }
        }
    }

    /// Marca os programas novos das séries que o guia já tem ([make] faz a marcada de um programa da série).
    func expandSeries(_ make: (SeriesRule, EpgEntry) -> ScheduledRecording?) {
        guard let lookup = guide else { return }
        let now = nowMs()
        let s = schedule
        var added: [ScheduledRecording] = []
        for rule in s.series {
            guard let entries = lookup(rule.playlistId, rule.channelKey) else { continue }
            for e in RecordingPlan.seriesEntries(rule, entries, s, now: now) {
                if var r = make(rule, e) {
                    r.seriesId = rule.id
                    added.append(r)
                }
            }
        }
        guard !added.isEmpty else { return }
        updateSchedule { cur in
            var seen = Set<String>()
            cur.recordings = (cur.recordings + added).filter { seen.insert($0.instanceKey).inserted }.sorted { $0.startMs < $1.startMs }
        }
    }

    /// A resposta ao aviso de [id].
    func answer(_ id: String, switchChannel: Bool) {
        answers[id] = switchChannel ? .switchChannel : .skip
        prompt = nil
        tick()
    }

    // MARK: A hora das marcadas

    private func startTicker() {
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(Self.tickSeconds))
                guard !Task.isCancelled else { return }
                self?.tick()
            }
        }
    }

    /// Vê as marcadas: avisa, muda de canal, começa ou salta. De [tickSeconds] em [tickSeconds] e a cada mudança.
    func tick(now: Int64 = nowMs()) {
        guard ticker != nil else { return }
        let s = schedule
        let busy = running.values.compactMap { r in r.info.playlistId.flatMap { p in r.info.channelKey.map { RecordingPlan.Busy(p, $0) } } }
        var next: ScheduledRecording?
        for r in s.recordings {
            if running.values.contains(where: { $0.info.scheduledId == r.id }) { continue }
            let w = RecordingPlan.window(r, s.paddingBeforeMin, s.paddingAfterMin)
            switch RecordingPlan.decide(r, w, now: now, watching: watching, busy: busy, answer: answers[r.id]) {
            case .wait: break
            case .prompt: if next == nil { next = r }
            case .switchChannel: if switched.insert(r.id).inserted { onSwitch?(r) }
            case .start: startScheduled(r, w, now: now)
            case .skip(let reason): skip(r, reason)
            case .expired: skip(r, nil)
            }
        }
        if prompt?.id != next?.id { prompt = next }
        let pruned = RecordingPlan.prune(schedule, schedule.paddingBeforeMin, schedule.paddingAfterMin, now: now)
        if pruned != schedule { updateSchedule { $0 = pruned } }
    }

    private func skip(_ r: ScheduledRecording, _ reason: String?) {
        answers.removeValue(forKey: r.id)
        switched.remove(r.id)
        updateSchedule { s in
            s.recordings.removeAll { $0.id == r.id }
            s.handled[r.instanceKey] = r.startMs
        }
        if let reason { onEvent?(.skipped(r, reason)) }
        DebugLog.add("Recordings: not recorded \(r.title) (\(reason ?? "time passed"))")
    }

    /// Começa a marcada [r]: na ligação que já está aberta para o canal (o leitor ou outra gravação), ou numa nova (sem
    /// ninguém a ver esse canal).
    private func startScheduled(_ r: ScheduledRecording, _ w: RecordingPlan.Window, now: Int64) {
        guard let url = r.url, let target = URL(string: url) else { return skip(r, RecordingPlan.skipHls) }
        let meta = Meta(playlistId: r.playlistId, channelKey: r.channelKey, channelName: r.channelName, logo: r.logo, title: r.title,
                        programmeStartMs: r.startMs, programmeEndMs: r.endMs, scheduledId: r.id)
        if let shared = ChannelFeed.find(url), shared.hold(.recording) {
            let ring = shared.ring
            guard let range = RecordingCut.range(ring.index, liveMediaMs: ring.liveMediaMs, nowWall: now, startWall: w.fromMs, endWall: w.toMs) else {
                // Ainda sem dados (acabou de abrir): tenta na volta seguinte.
                shared.release(.recording)
                return
            }
            waitingPlayer.removeValue(forKey: r.id)
            return start(shared, meta, w, offset: range.startOffset, mediaStart: range.startMediaMs, mediaEnd: range.endMediaMs, fileStart: range.fileStartMs)
        }
        // O leitor está (ou acabou de ficar) neste canal: espera uns segundos que ele abra a ligação, para a gravação usar
        // a dele (com o anel grande da pausa do direto).
        if let v = watching, v.live, r.sameChannel(v.playlistId, v.channelKey) {
            let since = waitingPlayer[r.id] ?? now
            waitingPlayer[r.id] = since
            if now - since < Self.playerWaitMs { return }
        }
        waitingPlayer.removeValue(forKey: r.id)
        guard RecordingSpace.canStart(free: freeBytes(), bytesPerSecond: nil) else { return skip(r, RecordingPlan.skipSpace) }
        beforeOwnFeed?()
        guard let feed = ChannelFeed.open(target, userAgent: r.userAgent.isEmpty ? iptvUserAgent : r.userAgent, referrer: r.referrer,
                                          reconnectSeconds: Self.reconnectSeconds, holder: .recording, maxBytes: Self.ownRingBytes,
                                          hls: r.urlIsHLS) else {
            afterOwnFeed?()
            return skip(r, RecordingPlan.skipSpace)
        }
        // Anel novo: o vídeo começa a contar agora (quando chegar o primeiro byte); grava tudo até ao fim da janela.
        start(feed, meta, w, offset: 0, mediaStart: 0, mediaEnd: max(0, w.toMs - now), fileStart: now)
        afterOwnFeed?()
    }

    private static let scheduleKey = "recordings.schedule"
    private static let tickSeconds = 5.0
    private static let reconnectSeconds = 5
    /// Quanto se espera pelo leitor que está a abrir o canal da gravação.
    private static let playerWaitMs: Int64 = 6_000
    /// Uma gravação sem ninguém a ver o canal só precisa de um anel pequeno (uns minutos, para a escrita ir atrás).
    private static let ownRingBytes: Int64 = 256 * 1024 * 1024

    static func newId(_ now: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd-HHmmss"
        return f.string(from: Date(timeIntervalSince1970: Double(now) / 1000)) + "-" + UUID().uuidString.prefix(6).lowercased()
    }
}
