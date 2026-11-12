import Foundation
import GoatShared

// As gravações (o `data/recording` do GoatTV Android): os mesmos ficheiros (a ficha `<id>.json` ao lado do `<id>.ts`
// e a lista do que está marcado), onde cortar no anel da pausa do direto e as regras de quando gravar. Os nomes dos
// campos são os do Android, para as fichas se lerem nas duas apps.

/// Uma gravação no disco: o `<id>.ts` e esta ficha ao lado, `<id>.json` (ver [RecordingFiles]). As horas são ms desde
/// 1970; os cortes [mediaStartMs]/[mediaEndMs] são do relógio do vídeo do anel (os PCR do TS, ver TimeshiftRing).
public struct RecordingInfo: Equatable, Sendable {
    public static let version = 1

    public var version = RecordingInfo.version
    public var id: String
    public var playlistId: String?
    public var channelKey: String?
    public var channelName: String
    public var logo: String?
    public var title: String
    /// O guia desta app não guarda descrições: fica nil (o campo existe para quando houver).
    public var description: String?
    /// O programa no guia (sem guia, o que se pediu: do carregar no botão até ao máximo).
    public var programmeStartMs: Int64
    public var programmeEndMs: Int64
    /// O que se quis gravar: o programa com as margens antes e depois.
    public var startMs: Int64
    public var endMs: Int64
    /// A hora real do primeiro e do último byte do ficheiro (o fim vai avançando enquanto grava).
    public var fileStartMs: Int64
    public var fileEndMs: Int64
    public var mediaStartMs: Int64
    public var mediaEndMs: Int64
    public var bytes: Int64
    /// Ver [RecordingState].
    public var state: String
    /// Porque parou antes do fim (ver [RecordingReason]); nil quando acabou no fim ou ainda grava.
    public var reason: String?
    /// Quantas vezes faltou um bocado (a ligação caiu e voltou, ou a escrita ficou para trás).
    public var gaps = 0
    /// A gravação marcada que a fez; nil quando foi "gravar agora".
    public var scheduledId: String?

    public var durationMs: Int64 { max(0, fileEndMs - fileStartMs) }
    public var recording: Bool { state == RecordingState.recording }

    /// A ficha em JSON (com os nulos, como o Gson do Android a escreve).
    public var json: JSON {
        func opt(_ s: String?) -> JSON { s.map(JSON.string) ?? .null }
        func n(_ v: Int64) -> JSON { .number(Double(v)) }
        return .object([
            "version": .number(Double(version)), "id": .string(id), "playlistId": opt(playlistId), "channelKey": opt(channelKey),
            "channelName": .string(channelName), "logo": opt(logo), "title": .string(title), "description": opt(description),
            "programmeStartMs": n(programmeStartMs), "programmeEndMs": n(programmeEndMs), "startMs": n(startMs), "endMs": n(endMs),
            "fileStartMs": n(fileStartMs), "fileEndMs": n(fileEndMs), "mediaStartMs": n(mediaStartMs), "mediaEndMs": n(mediaEndMs),
            "bytes": n(bytes), "state": .string(state), "reason": opt(reason), "gaps": .number(Double(gaps)), "scheduledId": opt(scheduledId),
        ])
    }

    /// Lê uma ficha; sem `id` ou `state` não é uma ficha. Os campos que faltem ficam a zero (ou nil nos textos).
    public init?(json o: JSON) {
        guard let id = o["id"]?.string, let state = o["state"]?.string else { return nil }
        func l(_ k: String) -> Int64 { o[k]?.double.flatMap { $0.isFinite ? Int64($0) : nil } ?? 0 }
        self.version = o["version"]?.int ?? 0
        self.id = id
        playlistId = o["playlistId"]?.string
        channelKey = o["channelKey"]?.string
        channelName = o["channelName"]?.string ?? ""
        logo = o["logo"]?.string
        title = o["title"]?.string ?? ""
        description = o["description"]?.string
        programmeStartMs = l("programmeStartMs")
        programmeEndMs = l("programmeEndMs")
        startMs = l("startMs")
        endMs = l("endMs")
        fileStartMs = l("fileStartMs")
        fileEndMs = l("fileEndMs")
        mediaStartMs = l("mediaStartMs")
        mediaEndMs = l("mediaEndMs")
        bytes = l("bytes")
        self.state = state
        reason = o["reason"]?.string
        gaps = o["gaps"]?.int ?? 0
        scheduledId = o["scheduledId"]?.string
    }

    public init(id: String, playlistId: String?, channelKey: String?, channelName: String, logo: String?, title: String,
                programmeStartMs: Int64, programmeEndMs: Int64, startMs: Int64, endMs: Int64, fileStartMs: Int64, fileEndMs: Int64,
                mediaStartMs: Int64, mediaEndMs: Int64, bytes: Int64, state: String, reason: String? = nil, gaps: Int = 0,
                scheduledId: String? = nil) {
        self.id = id
        self.playlistId = playlistId
        self.channelKey = channelKey
        self.channelName = channelName
        self.logo = logo
        self.title = title
        self.programmeStartMs = programmeStartMs
        self.programmeEndMs = programmeEndMs
        self.startMs = startMs
        self.endMs = endMs
        self.fileStartMs = fileStartMs
        self.fileEndMs = fileEndMs
        self.mediaStartMs = mediaStartMs
        self.mediaEndMs = mediaEndMs
        self.bytes = bytes
        self.state = state
        self.reason = reason
        self.gaps = gaps
        self.scheduledId = scheduledId
    }
}

public enum RecordingState {
    public static let recording = "recording"
    /// Chegou ao fim que se queria.
    public static let done = "done"
    /// Parou antes (à mão, por falta de espaço, porque a ligação acabou ou a app terminou): ver o `reason`.
    public static let stopped = "stopped"
    /// Não chegou a gravar nada.
    public static let failed = "failed"
}

public enum RecordingReason {
    public static let user = "user"
    public static let lowSpace = "low_space"
    /// A ligação fechou de vez (outro canal precisou dela, ou o servidor deixou de dar).
    public static let connection = "connection"
    /// A app terminou a meio (a ficha ficou como "a gravar").
    public static let appEnded = "app_ended"
}

/// Uma gravação marcada no guia, com o que é preciso para a gravar: as tentativas do canal (com as credenciais da
/// lista no URL, por isso esta lista não fica ao lado das gravações) e o User-Agent.
public struct ScheduledRecording: Equatable, Sendable {
    public var id: String
    public var playlistId: String
    public var channelKey: String
    public var channelName: String
    public var logo: String?
    public var title: String
    public var startMs: Int64
    public var endMs: Int64
    public var candidates: [StreamCandidate]
    public var userAgent: String
    public var referrer: String?
    /// Veio de uma regra "gravar a série" ([SeriesRule]).
    public var seriesId: String?

    public init(id: String, playlistId: String, channelKey: String, channelName: String, logo: String?, title: String,
                startMs: Int64, endMs: Int64, candidates: [StreamCandidate], userAgent: String, referrer: String? = nil, seriesId: String? = nil) {
        self.id = id
        self.playlistId = playlistId
        self.channelKey = channelKey
        self.channelName = channelName
        self.logo = logo
        self.title = title
        self.startMs = startMs
        self.endMs = endMs
        self.candidates = candidates
        self.userAgent = userAgent
        self.referrer = referrer
        self.seriesId = seriesId
    }

    /// O programa (lista, canal e início): é assim que se reconhece no guia e nas séries.
    public var instanceKey: String { Self.instanceKey(playlistId, channelKey, startMs) }

    /// O endereço que se grava (o primeiro TS: o HLS não se grava).
    public var url: String? { candidates.first { !$0.isHLS }?.url }

    public func sameChannel(_ playlistId: String, _ channelKey: String) -> Bool {
        self.playlistId == playlistId && self.channelKey == channelKey
    }

    public static func instanceKey(_ playlistId: String, _ channelKey: String, _ startMs: Int64) -> String {
        "\(playlistId)|\(channelKey)|\(startMs)"
    }

    var json: JSON {
        .object([
            "id": .string(id), "playlistId": .string(playlistId), "channelKey": .string(channelKey), "channelName": .string(channelName),
            "logo": logo.map(JSON.string) ?? .null, "title": .string(title), "startMs": .number(Double(startMs)), "endMs": .number(Double(endMs)),
            "candidates": .array(candidates.map { .object(["url": .string($0.url), "format": .string($0.format)]) }),
            "userAgent": .string(userAgent), "referrer": referrer.map(JSON.string) ?? .null, "seriesId": seriesId.map(JSON.string) ?? .null,
        ])
    }

    init?(json o: JSON) {
        guard let id = o["id"]?.string, let list = o["candidates"]?.array else { return nil }
        let candidates = list.compactMap { c -> StreamCandidate? in
            guard let url = c["url"]?.string, let format = c["format"]?.string else { return nil }
            return StreamCandidate(url: url, format: format)
        }
        self.init(id: id, playlistId: o["playlistId"]?.string ?? "", channelKey: o["channelKey"]?.string ?? "",
                  channelName: o["channelName"]?.string ?? "", logo: o["logo"]?.string, title: o["title"]?.string ?? "",
                  startMs: Int64(o["startMs"]?.double ?? 0), endMs: Int64(o["endMs"]?.double ?? 0), candidates: candidates,
                  userAgent: o["userAgent"]?.string ?? "", referrer: o["referrer"]?.string, seriesId: o["seriesId"]?.string)
    }
}

/// "Gravar a série": todos os programas com este título neste canal.
public struct SeriesRule: Equatable, Sendable {
    public var id: String
    public var playlistId: String
    public var channelKey: String
    public var channelName: String
    public var logo: String?
    public var title: String

    public init(id: String, playlistId: String, channelKey: String, channelName: String, logo: String?, title: String) {
        self.id = id
        self.playlistId = playlistId
        self.channelKey = channelKey
        self.channelName = channelName
        self.logo = logo
        self.title = title
    }
}

/// O que está marcado. [handled]: programas que já não se voltam a marcar pela série (já gravados, cancelados ou
/// saltados), com a hora de início para se esquecerem ao fim de uns dias (ver RecordingPlan.prune).
public struct Schedule: Equatable, Sendable {
    public static let version = 1

    public var version = Schedule.version
    public var recordings: [ScheduledRecording] = []
    public var series: [SeriesRule] = []
    public var handled: [String: Int64] = [:]
    /// As margens das Definições, guardadas aqui para se saberem sem as Definições.
    public var paddingBeforeMin = RecordingPlan.paddingBeforeDefault
    public var paddingAfterMin = RecordingPlan.paddingAfterDefault

    public init(recordings: [ScheduledRecording] = [], series: [SeriesRule] = [], handled: [String: Int64] = [:]) {
        self.recordings = recordings
        self.series = series
        self.handled = handled
    }

    public var json: JSON {
        .object([
            "version": .number(Double(version)),
            "recordings": .array(recordings.map(\.json)),
            "series": .array(series.map { r in
                .object(["id": .string(r.id), "playlistId": .string(r.playlistId), "channelKey": .string(r.channelKey),
                         "channelName": .string(r.channelName), "logo": r.logo.map(JSON.string) ?? .null, "title": .string(r.title)])
            }),
            "handled": .object(handled.mapValues { .number(Double($0)) }),
            "paddingBeforeMin": .number(Double(paddingBeforeMin)),
            "paddingAfterMin": .number(Double(paddingAfterMin)),
        ])
    }

    public init(json o: JSON) {
        version = o["version"]?.int ?? Schedule.version
        recordings = (o["recordings"]?.array ?? []).compactMap(ScheduledRecording.init(json:))
        series = (o["series"]?.array ?? []).compactMap { r in
            guard let id = r["id"]?.string else { return nil }
            return SeriesRule(id: id, playlistId: r["playlistId"]?.string ?? "", channelKey: r["channelKey"]?.string ?? "",
                              channelName: r["channelName"]?.string ?? "", logo: r["logo"]?.string, title: r["title"]?.string ?? "")
        }
        handled = (o["handled"]?.object ?? [:]).compactMapValues { $0.double.map { Int64($0) } }
        func padding(_ key: String, _ def: Int) -> Int {
            guard let v = o[key]?.int, RecordingPlan.paddingOptions.contains(v) else { return def }
            return v
        }
        paddingBeforeMin = padding("paddingBeforeMin", RecordingPlan.paddingBeforeDefault)
        paddingAfterMin = padding("paddingAfterMin", RecordingPlan.paddingAfterDefault)
    }
}

/// Onde cortar uma gravação no anel. O anel não sabe horas: sabe o tempo do vídeo (pelos PCR) de meio em meio segundo
/// ([TimeshiftRing.Entry]) e o do último byte que chegou ([liveMediaMs], que é "agora"). Uma hora real `t` fica no
/// tempo do vídeo `liveMediaMs - (agora - t)`; como o relógio do anel avança com o tempo real quando a ligação cai,
/// isto não escorrega com as quebras.
public enum RecordingCut {
    /// O pedaço a gravar: começa num ponto do índice ([startOffset], o início de um pacote TS) e acaba quando o vídeo
    /// chegar a [endMediaMs]. [late]: o início pedido já não está no anel (começa no mais antigo que há).
    public struct Range: Equatable, Sendable {
        public var startOffset: Int64
        public var startMediaMs: Int64
        public var endMediaMs: Int64
        /// A hora real do início do ficheiro.
        public var fileStartMs: Int64
        public var late: Bool
    }

    /// O pedaço de [startWall] a [endWall] (horas reais), com o índice [entries] do anel, o tempo do vídeo no direto
    /// [liveMediaMs] e a hora [nowWall]. O início é o último ponto do índice até ao instante pedido (nunca depois:
    /// mais vale meio segundo a mais do que a menos). nil sem índice.
    public static func range(_ entries: [TimeshiftRing.Entry], liveMediaMs: Int64, nowWall: Int64, startWall: Int64, endWall: Int64) -> Range? {
        // A regra é a do núcleo partilhado com o Android (RecordingCut.kt).
        guard let r = AppleBridge.shared.recordingCut(
            mediaMs: entries.map { KotlinLong(value: $0.mediaMs) }, offsets: entries.map { KotlinLong(value: $0.offset) },
            liveMediaMs: liveMediaMs, nowWall: nowWall, startWall: startWall, endWall: endWall) else { return nil }
        return Range(startOffset: r.startOffset, startMediaMs: r.startMediaMs, endMediaMs: r.endMediaMs, fileStartMs: r.fileStartMs, late: r.late)
    }

    /// O tempo do vídeo à hora real [wall].
    public static func mediaAt(_ liveMediaMs: Int64, _ nowWall: Int64, _ wall: Int64) -> Int64 { liveMediaMs - (nowWall - wall) }

    /// A hora real do tempo do vídeo [mediaMs].
    public static func wallAt(_ liveMediaMs: Int64, _ nowWall: Int64, _ mediaMs: Int64) -> Int64 { nowWall - (liveMediaMs - mediaMs) }

    /// O último ponto com o tempo até [mediaMs] (procura binária: o índice vai por ordem).
    public static func atOrBefore(_ entries: [TimeshiftRing.Entry], _ mediaMs: Int64) -> TimeshiftRing.Entry? {
        var lo = 0
        var hi = entries.count - 1
        var found: TimeshiftRing.Entry?
        while lo <= hi {
            let mid = (lo + hi) / 2
            if entries[mid].mediaMs <= mediaMs {
                found = entries[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

/// As regras das gravações: as margens, o que fazer quando chega a hora de uma gravação marcada, as séries. O
/// fornecedor só deixa uma ligação: grava-se um canal de cada vez e, a ver outro canal, ou se muda para o da gravação
/// ou não se grava.
public enum RecordingPlan {
    /// As margens a escolher (minutos), antes e depois do programa.
    public static let paddingOptions = [0, 1, 2, 5, 10]
    public static let paddingBeforeDefault = 1
    public static let paddingAfterDefault = 2
    /// O aviso "Mudar para X para gravar" aparece este tanto antes de a gravação começar (como os lembretes).
    public static let promptLeadMs: Int64 = 60_000
    /// Sem guia, "gravar agora" grava até isto (ou até se parar).
    public static let maxNoEpgMs: Int64 = 4 * 3_600_000
    /// As séries marcam os programas desta janela do guia.
    public static let seriesHorizonMs: Int64 = 7 * 24 * 3_600_000
    /// Os programas já tratados esquecem-se depois disto (o guia já não os tem).
    public static let handledKeepMs: Int64 = 3 * 24 * 3_600_000

    /// De [fromMs] a [toMs]: o programa com as margens.
    public struct Window: Equatable, Sendable {
        public var fromMs: Int64
        public var toMs: Int64
    }

    public static func window(_ startMs: Int64, _ endMs: Int64, _ beforeMin: Int, _ afterMin: Int) -> Window {
        Window(fromMs: startMs - Int64(beforeMin) * 60_000, toMs: endMs + Int64(afterMin) * 60_000)
    }

    public static func window(_ r: ScheduledRecording, _ beforeMin: Int, _ afterMin: Int) -> Window {
        window(r.startMs, r.endMs, beforeMin, afterMin)
    }

    /// O que o leitor está a usar: o canal em direto (nil = nada, a app em segundo plano, ou uma gravação no disco).
    public struct Watching: Equatable, Sendable {
        public var playlistId: String
        public var channelKey: String
        public var live = true

        public init(_ playlistId: String, _ channelKey: String, live: Bool = true) {
            self.playlistId = playlistId
            self.channelKey = channelKey
            self.live = live
        }
    }

    /// Uma gravação a decorrer (o canal dela).
    public struct Busy: Equatable, Sendable {
        public var playlistId: String
        public var channelKey: String

        public init(_ playlistId: String, _ channelKey: String) {
            self.playlistId = playlistId
            self.channelKey = channelKey
        }
    }

    /// A resposta ao aviso.
    public enum Answer: Sendable { case switchChannel, skip }

    public enum Decision: Equatable, Sendable {
        /// Ainda falta.
        case wait
        /// A ver outro canal: perguntar se muda (falta pouco para começar).
        case prompt
        /// Gravar agora: o canal já está aberto (a ver ou a gravar), ou não se está a ver nada.
        case start
        /// Mudar o leitor para o canal da gravação (respondeu que sim, ou ninguém respondeu até à hora).
        case switchChannel
        /// Não gravar: [skipUser], [skipBusy] (já se grava outro canal), [skipHls] ou [skipSpace].
        case skip(String)
        /// A hora já passou sem se ter gravado.
        case expired
    }

    public static let skipUser = "user"
    public static let skipBusy = "busy"
    public static let skipHls = "hls"
    public static let skipSpace = "space"

    /// O que fazer agora ([now]) com [r], que grava em [w]. Sem resposta até à hora, muda-se para o canal da gravação
    /// (como uma box com um só sintonizador). Uma gravação de outro canal a decorrer ganha (a que já começou não se corta).
    public static func decide(_ r: ScheduledRecording, _ w: Window, now: Int64, watching: Watching?, busy: [Busy], answer: Answer?) -> Decision {
        // As regras são as do núcleo partilhado com o Android (RecordingPlan.kt).
        let d = AppleBridge.shared.recordingDecide(
            r: r.shared, fromMs: w.fromMs, toMs: w.toMs, nowMs: now, watchingPlaylist: watching?.playlistId,
            watchingKey: watching?.channelKey, watchingLive: watching?.live ?? true, busy: busy.map { "\($0.playlistId)|\($0.channelKey)" },
            answer: answer.map { $0 == .skip ? "skip" : "switch" })
        switch d {
        case "wait": return .wait
        case "prompt": return .prompt
        case "start": return .start
        case "switch": return .switchChannel
        case "expired": return .expired
        default: return .skip(String(d.dropFirst("skip:".count)))
        }
    }

    /// As marcadas de outros canais que se sobrepõem a [r] (com as margens): só uma delas vai gravar.
    public static func conflicts(_ r: ScheduledRecording, _ all: [ScheduledRecording], _ beforeMin: Int, _ afterMin: Int) -> [ScheduledRecording] {
        let ids = Set(AppleBridge.shared.recordingConflicts(r: r.shared, all: all.map(\.shared), beforeMin: Int32(clamping: beforeMin),
                                                            afterMin: Int32(clamping: afterMin)))
        return all.filter { ids.contains($0.id) }
    }

    /// A próxima hora a que a app tem de acordar: o início, com a margem, da próxima marcada que ainda não começou.
    public static func nextWake(_ schedule: Schedule, _ beforeMin: Int, _ afterMin: Int, now: Int64) -> Int64? {
        let t = AppleBridge.shared.recordingNextWake(schedule: schedule.shared, beforeMin: Int32(clamping: beforeMin),
                                                     afterMin: Int32(clamping: afterMin), nowMs: now)
        return t < 0 ? nil : t
    }

    /// O mesmo título, sem maiúsculas, acentos nem espaços a mais (o guia não escreve sempre igual).
    public static func titleKey(_ title: String) -> String { AppleBridge.shared.recordingTitleKey(title: title) }

    /// Os programas de [entries] que a regra [rule] marca agora (ver o RecordingPlan.kt).
    public static func seriesEntries(_ rule: SeriesRule, _ entries: [EpgEntry], _ schedule: Schedule, now: Int64) -> [EpgEntry] {
        AppleBridge.shared.recordingSeriesEntries(rule: rule.shared, entries: entries.map(\.shared), schedule: schedule.shared, nowMs: now)
            .map { entries[Int($0.int32Value)] }
    }

    /// Tira o que já passou: marcadas cujo fim (com margem) já foi, e programas tratados há mais de uns dias.
    public static func prune(_ schedule: Schedule, _ beforeMin: Int, _ afterMin: Int, now: Int64) -> Schedule {
        let kept = AppleBridge.shared.recordingPrune(schedule: schedule.shared, beforeMin: Int32(clamping: beforeMin),
                                                     afterMin: Int32(clamping: afterMin), nowMs: now)
        let ids = Set(kept.recordingIds)
        let keys = Set(kept.handledKeys)
        var s = schedule
        s.recordings = schedule.recordings.filter { ids.contains($0.id) }
        s.handled = schedule.handled.filter { keys.contains($0.key) }
        return s
    }
}

public enum RecordingSpace {
    public static let reserveBytes: Int64 = 512 * 1024 * 1024
    /// Sem saber o débito do canal: 8 Mbit/s (um canal HD normal).
    public static let defaultBytesPerSecond: Int64 = 1_000_000
    /// Para começar, tem de caber isto do canal além da reserva.
    public static let minStartMs: Int64 = 10 * 60_000

    /// Cabe uma gravação nova? [free]: o livre no disco; [bytesPerSecond]: o débito do canal, se se souber.
    public static func canStart(free: Int64, bytesPerSecond: Int64?) -> Bool {
        AppleBridge.shared.recordingCanStart(free: free, bytesPerSecond: bytesPerSecond ?? -1)
    }

    /// A meio: pára quando o livre chega à reserva.
    public static func mustStop(free: Int64) -> Bool { AppleBridge.shared.recordingMustStop(free: free) }

    /// O débito de um anel que gravou [bytes] em [mediaMs] de vídeo, ou nil com pouco ainda (menos de 5 s).
    public static func rate(bytes: Int64, mediaMs: Int64) -> Int64? {
        let r = AppleBridge.shared.recordingRate(bytes: bytes, mediaMs: mediaMs)
        return r < 0 ? nil : r
    }
}

/// As gravações no disco: em [dir] cada uma é `<id>.ts` (o vídeo) e `<id>.json` (a [RecordingInfo]). A lista do que
/// está marcado fica noutro sítio, [scheduleFile] (tem os URLs com as credenciais). As fichas escrevem-se de forma
/// atómica (um ficheiro temporário que depois troca de nome): uma app que morre a meio não deixa uma ficha partida.
public struct RecordingFiles: Sendable {
    public let dir: URL
    public let scheduleFile: URL

    public init(dir: URL, scheduleFile: URL) {
        self.dir = dir
        self.scheduleFile = scheduleFile
    }

    public func videoFile(_ id: String) -> URL { dir.appendingPathComponent(id + ".ts") }
    private func infoFile(_ id: String) -> URL { dir.appendingPathComponent(id + ".json") }

    /// Todas as gravações, as mais recentes primeiro (uma ficha que não se lê é ignorada; o ficheiro fica).
    public func list() -> [RecordingInfo] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { (try? Data(contentsOf: $0)).flatMap(Self.parseInfo) }
            .sorted { $0.fileStartMs > $1.fileStartMs }
    }

    public func read(_ id: String) -> RecordingInfo? { (try? Data(contentsOf: infoFile(id))).flatMap(Self.parseInfo) }

    public func write(_ info: RecordingInfo) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try info.json.encoded().write(to: infoFile(info.id), options: .atomic)
    }

    /// Apaga o vídeo e a ficha.
    @discardableResult
    public func delete(_ id: String) -> Bool {
        let files = FileManager.default
        let video = videoFile(id)
        let ok = !files.fileExists(atPath: video.path) || (try? files.removeItem(at: video)) != nil
        try? files.removeItem(at: infoFile(id))
        return ok
    }

    public static func parseInfo(_ data: Data) -> RecordingInfo? { (try? JSON.parse(data)).flatMap(RecordingInfo.init(json:)) }

    public func loadSchedule() -> Schedule {
        guard let data = try? Data(contentsOf: scheduleFile), let js = try? JSON.parse(data) else { return Schedule() }
        return Schedule(json: js)
    }

    public func saveSchedule(_ schedule: Schedule) throws {
        try FileManager.default.createDirectory(at: scheduleFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try schedule.json.encoded().write(to: scheduleFile, options: .atomic)
    }
}

/// Copia um pedaço do anel para o ficheiro da gravação: de [startOffset] até o vídeo chegar a [endMediaMs] (ver
/// [RecordingCut]), ou até se pedir para parar. Lê o anel como o leitor (é mais um leitor; a ligação é a do anel) e vai
/// atrás do direto. O fim é cortado num ponto do índice (o início de um pacote TS).
///
/// Quando a ligação cai e volta, o anel começa um segmento novo: a gravação continua no início dele (conta um buraco em
/// [Result.gaps]). Se a escrita ficar tão para trás que o anel já reescreveu o ponto, continua no mais antigo. Pára
/// sozinha quando o livre no disco ([freeBytes]) chega à reserva ([RecordingSpace]). O RecordingWriter do Android.
public final class RecordingWriter: @unchecked Sendable {
    private let ring: TimeshiftRing
    private let out: URL
    private let startOffset: Int64
    private let freeBytes: () -> Int64
    private let onProgress: (_ bytes: Int64, _ mediaMs: Int64) -> Void
    private let checkBytes: Int64
    private let lock = NSLock()
    private var end: Int64
    private var stopRequested = false

    public struct Result: Equatable, Sendable {
        public var state: String
        public var reason: String?
        public var bytes: Int64
        public var mediaEndMs: Int64
        public var gaps: Int
    }

    public init(ring: TimeshiftRing, out: URL, startOffset: Int64, endMediaMs: Int64, freeBytes: @escaping () -> Int64,
                checkBytes: Int64 = RecordingWriter.checkBytes, onProgress: @escaping (_ bytes: Int64, _ mediaMs: Int64) -> Void = { _, _ in }) {
        self.ring = ring
        self.out = out
        self.startOffset = startOffset
        self.end = endMediaMs
        self.freeBytes = freeBytes
        self.checkBytes = checkBytes
        self.onProgress = onProgress
    }

    /// Até onde gravar (pode mudar a meio).
    public var endMediaMs: Int64 {
        get { lock.lock(); defer { lock.unlock() }; return end }
        set { lock.lock(); end = newValue; lock.unlock() }
    }

    private var stopping: Bool { lock.lock(); defer { lock.unlock() }; return stopRequested }

    /// Pára no ponto a que já chegou (o resultado diz [RecordingReason.user]).
    public func stop() {
        lock.lock()
        stopRequested = true
        lock.unlock()
    }

    /// Grava (bloqueia: corre numa thread sua) e devolve como acabou.
    public func run() -> Result {
        var pos = startOffset
        var segment = ring.segment(at: pos)
        var bytes: Int64 = 0
        var gaps = 0
        var endOffset: Int64?
        var sinceCheck: Int64 = 0
        var checkedAt = Date()
        var lastMedia = ring.media(at: pos) ?? 0
        func result(_ state: String, _ reason: String?) -> Result {
            Result(state: bytes == 0 && state != RecordingState.done ? RecordingState.failed : state, reason: reason, bytes: bytes,
                   mediaEndMs: lastMedia, gaps: gaps)
        }
        let files = FileManager.default
        files.createFile(atPath: out.path, contents: nil)
        guard let file = try? FileHandle(forWritingTo: out) else { return result(RecordingState.stopped, RecordingReason.lowSpace) }
        defer {
            try? file.close()
            onProgress(bytes, lastMedia)
        }
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: Self.bufferBytes, alignment: 1)
        defer { buffer.deallocate() }
        while true {
            if stopping { return result(RecordingState.stopped, RecordingReason.user) }
            // O vídeo já passou do fim: o corte é no ponto do índice nesse instante.
            let wanted = endMediaMs
            if endOffset == nil, ring.liveMediaMs >= wanted { endOffset = ring.entry(atOrAfter: wanted)?.offset }
            if let limit = endOffset, pos >= limit {
                lastMedia = ring.media(at: limit) ?? lastMedia
                return result(RecordingState.done, nil)
            }
            let max = endOffset.map { Int(min(Int64(Self.bufferBytes), $0 - pos)) } ?? Self.bufferBytes
            let read: TimeshiftRing.Read
            do {
                read = try ring.read(at: pos, segment: segment, into: UnsafeMutableRawBufferPointer(rebasing: buffer[0..<max]), waitMs: Self.waitMs)
            } catch {
                // A ligação acabou com um erro (e não volta).
                return result(RecordingState.stopped, RecordingReason.connection)
            }
            switch read {
            case .bytes(var n) where n > 0:
                // O que se leu pode já passar do fim (a escrita chegou lá entre ver o direto e ler): corta-se no ponto.
                if endOffset == nil, ring.liveMediaMs >= wanted { endOffset = ring.entry(atOrAfter: wanted)?.offset }
                if let limit = endOffset, pos + Int64(n) > limit { n = Int(Swift.max(0, limit - pos)) }
                if n == 0 { continue }
                do {
                    try file.write(contentsOf: Data(bytes: buffer.baseAddress!, count: n))
                } catch {
                    // Sem espaço ou o disco saiu: fica o que já se escreveu.
                    return result(RecordingState.stopped, RecordingReason.lowSpace)
                }
                pos += Int64(n)
                bytes += Int64(n)
                sinceCheck += Int64(n)
                if sinceCheck >= checkBytes || Date().timeIntervalSince(checkedAt) >= Self.checkSeconds {
                    sinceCheck = 0
                    checkedAt = Date()
                    lastMedia = ring.media(at: pos) ?? lastMedia
                    onProgress(bytes, lastMedia)
                    if RecordingSpace.mustStop(free: freeBytes()) { return result(RecordingState.stopped, RecordingReason.lowSpace) }
                }
            case .bytes, .wait:
                break
            case .boundary:
                if let next = ring.segmentStart(segment + 1) {
                    segment += 1
                    pos = next
                } else {
                    guard let oldest = ring.oldest else { return result(RecordingState.stopped, RecordingReason.connection) }
                    pos = oldest.offset
                    segment = ring.segment(at: pos)
                }
                gaps += 1
            case .overrun:
                guard let oldest = ring.oldest else { return result(RecordingState.stopped, RecordingReason.connection) }
                pos = oldest.offset
                segment = ring.segment(at: pos)
                gaps += 1
            case .end:
                // O anel fechou (a ligação foi para outro canal) ou acabou de vez.
                lastMedia = ring.media(at: pos) ?? lastMedia
                let reached = endOffset.map { pos >= $0 } ?? false
                return result(reached ? RecordingState.done : RecordingState.stopped, reached ? nil : RecordingReason.connection)
            }
        }
    }

    static let bufferBytes = 256 * 1024
    static let waitMs = 250
    /// De quantos em quantos bytes (ou segundos) se vê o espaço livre e se avisa do progresso.
    public static let checkBytes: Int64 = 8 * 1024 * 1024
    static let checkSeconds: TimeInterval = 5
}

// Os mesmos dados para o núcleo partilhado.
extension ScheduledRecording {
    var shared: GoatShared.ScheduledRecording {
        GoatShared.ScheduledRecording(id: id, playlistId: playlistId, channelKey: channelKey, channelName: channelName, logo: logo,
                                      title: title, startMs: startMs, endMs: endMs,
                                      candidates: candidates.map { GoatShared.StreamCandidate(url: $0.url, format: $0.format) },
                                      userAgent: userAgent, referrer: referrer, seriesId: seriesId)
    }
}

extension SeriesRule {
    var shared: GoatShared.SeriesRule {
        GoatShared.SeriesRule(id: id, playlistId: playlistId, channelKey: channelKey, channelName: channelName, logo: logo, title: title)
    }
}

extension Schedule {
    var shared: GoatShared.Schedule {
        GoatShared.Schedule(version: Int32(clamping: version), recordings: recordings.map(\.shared), series: series.map(\.shared),
                            handled: handled.mapValues { KotlinLong(value: $0) }, paddingBeforeMin: Int32(clamping: paddingBeforeMin),
                            paddingAfterMin: Int32(clamping: paddingAfterMin))
    }
}

extension EpgEntry {
    var shared: GoatShared.EpgEntry { GoatShared.EpgEntry(title: title, startMs: startMs, endMs: endMs) }
}
