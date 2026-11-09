import Foundation
import GoatCore
import Observation

/// "Pausar o direto": pausar, recuar e voltar ao direto em qualquer canal, mesmo sem catch-up no servidor (o
/// Timeshift do GoatTV Android).
///
/// Streams .ts (o normal no Xtream, e o que o VLC abre): a única ligação ao servidor passa a ser a do
/// [TimeshiftRecorder], que escreve tudo num anel em disco ([TimeshiftRing], na cache da app); o VLC lê do anel por
/// um servidor HTTP em 127.0.0.1 ([RingServer]) e nunca abre uma ligação sua — o fornecedor só deixa uma de cada vez.
/// Em direto o VLC lê logo atrás da escrita; em pausa a escrita continua. Recuar ou avançar é reabrir o VLC noutro
/// ponto do anel (o TS ao vivo não tem índice para o VLC saltar sozinho); o índice do anel diz onde fica cada instante.
///
/// Guarda-se só o programa que se está a ver (pelo guia): ao passar para o seguinte, o anterior sai do anel; ao
/// mudar de canal ou sair, o anel é apagado. Sem guia, os últimos [noEpgAgeMs].
///
/// HLS no leitor da Apple: cada bocado é um pedido à parte e a lista do servidor já guarda uns segundos ou minutos;
/// usa-se essa janela do próprio AVPlayer (sem disco), quando tem pelo menos [hlsMinWindowMs]. HLS no VLC, catch-up,
/// o segundo canal ou a definição desligada: nada disto existe (o leitor abre o stream como sempre).
///
/// Quem faz os saltos é o PlayerEngine (reabre o VLC ou faz seek no AVPlayer); aqui fica o estado ([available],
/// [behindMs]…), atualizado de meio em meio segundo, e as contas de onde ir.
@MainActor
@Observable
final class Timeshift {
    /// Dá para pausar e recuar neste canal.
    private(set) var available = false
    /// Quanto o que se vê está atrás do direto (do direto normal: sem contar o buffer do leitor).
    private(set) var behindMs: Int64 = 0
    /// Quanto se pode recuar ao todo (do mais antigo guardado até ao direto).
    private(set) var windowMs: Int64 = 0
    /// A tocar no direto (não em pausa nem atrás).
    private(set) var atLive = true

    /// Em pausa ou atrás do direto.
    var shifted: Bool { available && !atLive }

    /// O leitor está a recomeçar noutro ponto: a espera que se segue não é uma paragem da rede.
    @ObservationIgnored var seeking = false

    /// A ligação caiu e o gravador volta a ligar (tentativa, quando, quanto espera).
    @ObservationIgnored var onReconnect: ((_ attempt: Int, _ at: Date, _ wait: TimeInterval) -> Void)?
    /// A ligação voltou a dar dados.
    @ObservationIgnored var onConnected: (() -> Void)?
    /// Afinal não é TS: o gravador já largou a ligação, o leitor abre o stream ele próprio.
    @ObservationIgnored var onNotTs: (() -> Void)?

    /// A acompanhar o direto: o normal, até se pausar ou recuar; volta a sê-lo ao voltar ao direto ou ao avançar até
    /// lá. Em direto o que se vê fica sempre um pouco atrás do último byte gravado (o buffer do leitor): esse atraso é
    /// o [baselineMs], o "zero" do direto.
    @ObservationIgnored private var following = true
    @ObservationIgnored private var baselineMs: Int64 = 0
    @ObservationIgnored private var session: Session?
    @ObservationIgnored private var lastRecorder: TimeshiftRecorder?
    @ObservationIgnored private var generation = 0
    /// HLS no leitor da Apple: o direto e o mais antigo da janela dele, na última atualização (ms do AVPlayer).
    @ObservationIgnored private(set) var hls = false
    @ObservationIgnored private var hlsLive: Int64 = 0
    /// O último fim da janela do HLS que se viu, e quando (ver [updateHls]).
    @ObservationIgnored private var hlsSeenEnd: Int64?
    @ObservationIgnored private var hlsSeenAt: Int64 = 0

    /// Um canal aberto pelo anel.
    private final class Session {
        let id: Int
        let ring: TimeshiftRing
        let server: RingServer
        let recorder: TimeshiftRecorder
        /// Tempo do vídeo (no anel) onde começou a leitura atual do VLC.
        var startMediaMs: Int64 = 0

        init(id: Int, ring: TimeshiftRing, server: RingServer, recorder: TimeshiftRecorder) {
            self.id = id
            self.ring = ring
            self.server = server
            self.recorder = recorder
        }

        /// Fecha a ligação ao servidor, a porta local e apaga o anel.
        func close() {
            recorder.close()
            server.stop()
            ring.close()
        }
    }

    /// O VLC está a ler do anel (o gravador tem a ligação).
    var usesRing: Bool { session != nil }
    /// O código HTTP com que o servidor recusou o stream ao gravador (para explicar o erro).
    var httpStatus: Int? { session?.recorder.httpStatus }

    // MARK: Abrir e fechar

    /// Abre [url] pelo anel e devolve o endereço local para o VLC, ou nil para abrir como sempre (pouco espaço
    /// livre, sem porta). [reconnectSeconds]: de quanto em quanto tempo o gravador volta a ligar se a ligação cair.
    func open(_ url: URL, userAgent: String, referrer: String?, reconnectSeconds: Int) -> URL? {
        clear()
        guard let capacity = Self.capacity() else { return nil }
        generation += 1
        let id = generation
        let ring: TimeshiftRing
        do {
            ring = try TimeshiftRing(path: Self.dir.appendingPathComponent("ring-\(id).ts").path, capacity: capacity,
                                     maxAgeMs: Self.maxAgeMs, clock: Self.clock)
        } catch {
            DebugLog.add("Timeshift: no ring (\(error))")
            return nil
        }
        let server = RingServer(ring: ring)
        do {
            try server.start()
        } catch {
            ring.close()
            DebugLog.add("Timeshift: no local port (\(error))")
            return nil
        }
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let referrer, !referrer.isEmpty { request.setValue(referrer, forHTTPHeaderField: "Referer") }
        let recorder = TimeshiftRecorder(ring: ring, request: request, reconnectSeconds: reconnectSeconds, previous: lastRecorder)
        // Os avisos do gravador chegam de outra fila; um gravador antigo (canal anterior) já não conta.
        recorder.onReconnect = { [weak self] attempt, wait in
            Task { @MainActor in
                guard let self, self.session?.id == id else { return }
                self.onReconnect?(attempt, Date().addingTimeInterval(wait), wait)
            }
        }
        recorder.onConnected = { [weak self] in
            Task { @MainActor in
                guard let self, self.session?.id == id else { return }
                self.onConnected?()
            }
        }
        recorder.onNotTs = { [weak self] in
            Task { @MainActor in
                guard let self, self.session?.id == id else { return }
                DebugLog.add("Timeshift: not a TS stream, opening it directly")
                self.onNotTs?()
            }
        }
        session = Session(id: id, ring: ring, server: server, recorder: recorder)
        lastRecorder = recorder
        recorder.start()
        DebugLog.add("Timeshift: recording (ring \(capacity / 1_048_576) MB)")
        return server.url(at: 0)
    }

    /// HLS no leitor da Apple: a janela dele (ver [updateHls]).
    func openHls() {
        clear()
        hls = true
    }

    /// Fecha a ligação e apaga o anel (outro canal, catch-up, app em segundo plano).
    func clear() {
        session?.close()
        session = nil
        hls = false
        seeking = false
        following = true
        baselineMs = 0
        hlsSeenEnd = nil
        unavailable()
    }

    /// A ligação do gravador anterior ainda não fechou.
    var closing: Bool { session == nil && lastRecorder?.isDone == false }

    /// Espera (até 3 s) que a ligação do gravador anterior feche: o fornecedor só deixa uma.
    func waitClosed() async {
        var waited = 0
        while closing, waited < Self.previousWaitMs {
            try? await Task.sleep(for: .milliseconds(50))
            waited += 50
        }
    }

    /// Espaço para o anel: até [maxBytes], sem passar de metade do livre (tirando uma reserva).
    private static func capacity() -> Int64? {
        let files = FileManager.default
        try? files.createDirectory(at: dir, withIntermediateDirectories: true)
        // Restos de uma sessão que não fechou (a app morreu a meio).
        for file in (try? files.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] {
            try? files.removeItem(at: file)
        }
        let free = ((try? files.attributesOfFileSystem(forPath: dir.path))?[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        let bytes = min(maxBytes, (free - reserveBytes) / 2)
        guard bytes >= TimeshiftRing.minCapacity else {
            DebugLog.add("Timeshift: little free space (\(free / 1_048_576) MB), no pause")
            return nil
        }
        return bytes
    }

    private static let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("timeshift", isDirectory: true)

    /// Um relógio que só anda para a frente (o do vídeo não pode recuar se a hora do aparelho mudar).
    private static let clock: () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    // MARK: Estado

    /// Atualiza o estado com o VLC a ler do anel; chamar de meio em meio segundo. [positionMs]: quanto vídeo o VLC já
    /// mostrou desde que começou a ler (ver PlayerEngine.ringPlayedMs). [settled]: a dar normalmente (não a abrir nem
    /// a saltar).
    /// [programmeStart]: quando começou o programa do guia que dá a uma hora (ms desde 1970), ou nil sem guia.
    func update(positionMs: Int64?, paused: Bool, settled: Bool, programmeStart: (Int64) -> Int64?) {
        guard let s = session else { return }
        let ring = s.ring
        guard ring.isTs == true, !s.recorder.notTs else { return unavailable() }
        var live = ring.liveMediaMs
        let at = s.startMediaMs + (positionMs ?? 0)
        // O programa no ponto que se vê (não no direto: em pausa no fim do jogo, o jogo fica até se passar dele).
        // Folga de um minuto antes do início, que as horas do guia raramente são certas.
        let wall = nowMs()
        let start = programmeStart(wall - (live - at))
        ring.setMaxAge(start == nil ? Self.noEpgAgeMs : Self.maxAgeMs)
        if let start { ring.dropBefore(live - (wall - start) - Self.programmeMarginMs) }
        live = ring.liveMediaMs
        track(live: live, oldest: ring.oldest?.mediaMs ?? live, position: at, paused: paused, settled: settled, slack: Self.liveSlackMs)
        if !available { available = true }
    }

    /// O mesmo para o HLS no leitor da Apple: [end] e [oldest] são as pontas da janela que se pode ver, [position]
    /// onde vai (tudo em ms do AVPlayer).
    func updateHls(end: Int64, oldest: Int64, position: Int64, paused: Bool, settled: Bool) {
        guard hls else { return }
        // O fim da janela só anda quando o AVPlayer relê a lista (aos saltos, um bocado de cada vez; em pausa às vezes
        // nem isso): o direto anda com o tempo real desde a última vez que o fim mudou.
        let now = Self.clock()
        if end != hlsSeenEnd {
            hlsSeenEnd = end
            hlsSeenAt = now
        }
        let live = end + (now - hlsSeenAt)
        hlsLive = live
        track(live: live, oldest: oldest, position: position, paused: paused, settled: settled, slack: Self.hlsLiveSlackMs)
        let ok = live - oldest >= Self.hlsMinWindowMs
        if available != ok { available = ok }
    }

    private func track(live: Int64, oldest: Int64, position: Int64, paused: Bool, settled: Bool, slack: Int64) {
        let span = max(0, live - oldest)
        let behind = min(max(0, live - position), span)
        if paused {
            following = false
        } else if following {
            if settled { baselineMs = behind }
        } else if settled, behind <= baselineMs + slack {
            // Avançou até ao direto (ou quase): volta a acompanhá-lo.
            following = true
        }
        // Só se muda o que mudou: cada mudança redesenha o ecrã do leitor.
        let shown = following ? 0 : max(0, behind - baselineMs)
        if behindMs != shown { behindMs = shown }
        let window = max(0, span - baselineMs)
        if windowMs != window { windowMs = window }
        if atLive != following { atLive = following }
    }

    func unavailable() {
        if available { available = false }
        if behindMs != 0 { behindMs = 0 }
        if windowMs != 0 { windowMs = 0 }
        if !atLive { atLive = true }
    }

    // MARK: Para onde ir (o anel)

    /// O ponto do anel [behind] ms atrás do direto; nil sem anel.
    func entry(behind: Int64) -> TimeshiftRing.Entry? {
        guard let ring = session?.ring else { return nil }
        return ring.entry(atOrBefore: ring.liveMediaMs - baselineMs - behind)
    }

    /// Para voltar ao direto: um pouco antes do fim, para o VLC arrancar logo (e apanhar um keyframe) com o que já
    /// está no disco.
    func liveEntry() -> TimeshiftRing.Entry? {
        guard let ring = session?.ring else { return nil }
        return ring.entry(atOrBefore: ring.liveMediaMs - Self.liveStartMs)
    }

    /// Ao continuar depois da pausa: se o ponto da pausa já saiu do anel, o mais antigo que há (com folga); nil se
    /// ainda lá está (continua-se onde se estava).
    func resumeEntry(positionMs: Int64) -> TimeshiftRing.Entry? {
        guard let s = session, let oldest = s.ring.oldest, s.startMediaMs + positionMs < oldest.mediaMs else { return nil }
        return s.ring.entry(atOrBefore: oldest.mediaMs + Self.overrunMarginMs)
    }

    /// O VLC chegou ao fim do que lia. Se foi o fim de uma ligação (havendo outra a seguir) ou o ponto já foi
    /// reescrito, onde continuar (e se continua a acompanhar o direto); nil se a ligação acabou de vez.
    func entryAfterEnd() -> (entry: TimeshiftRing.Entry, live: Bool)? {
        guard let s = session, let ending = s.server.takeEnding() else { return nil }
        switch ending.reason {
        case .boundary:
            // No fim de uma ligação, quem acompanhava o direto continua a acompanhá-lo.
            return s.ring.entry(from: ending.at).map { ($0, following) }
        case .overrun:
            return s.ring.oldest.flatMap { s.ring.entry(atOrBefore: $0.mediaMs + Self.overrunMarginMs) }.map { ($0, false) }
        default:
            return nil
        }
    }

    /// O VLC vai ler a partir de [entry]: devolve o endereço local. [live]: é para acompanhar o direto.
    func prepare(at entry: TimeshiftRing.Entry, live: Bool) -> URL? {
        guard let s = session else { return nil }
        // O fim da leitura anterior já não conta.
        _ = s.server.takeEnding()
        s.startMediaMs = entry.mediaMs
        seeking = true
        following = live
        return s.server.url(at: entry.offset)
    }

    // MARK: Para onde ir (HLS)

    /// A posição do AVPlayer (ms) [behind] ms atrás do direto, e o salto começa.
    func hlsPosition(behind: Int64) -> Int64 {
        seeking = true
        following = false
        return hlsLive - baselineMs - behind
    }

    /// A posição do direto no AVPlayer (com o atraso com que ele próprio o dá), e o salto começa.
    func hlsLivePosition() -> Int64 {
        seeking = true
        following = true
        return hlsLive - baselineMs
    }

    // MARK: Limites (os do Android)

    /// Com guia, o programa todo até 4 h; sem guia, 30 min. Nunca mais de 4 GB nem de metade do espaço livre (o anel
    /// acerta o tamanho pelo débito, ver TimeshiftRing): um canal pesado guarda menos tempo.
    static let maxAgeMs: Int64 = 4 * 60 * 60_000
    static let noEpgAgeMs: Int64 = 30 * 60_000
    static let maxBytes: Int64 = 4_096 * 1024 * 1024
    static let programmeMarginMs: Int64 = 60_000
    /// O que fica sempre livre no disco, para o sistema e as outras apps.
    static let reserveBytes: Int64 = 512 * 1024 * 1024
    /// Para voltar ao direto, começa-se este tanto antes do fim.
    static let liveStartMs: Int64 = 5_000
    /// Pedir para ficar a menos disto do direto é voltar ao direto.
    static let liveSnapMs: Int64 = 3_000
    /// O ponto da pausa saiu do anel: continua-se este tanto depois do mais antigo (para não voltar a sair já).
    static let overrunMarginMs: Int64 = 15_000
    /// Atrás do direto, chegar a menos disto do atraso normal é estar outra vez no direto.
    static let liveSlackMs: Int64 = 3_000
    static let hlsMinWindowMs: Int64 = 20_000
    /// No HLS o fim da janela anda aos saltos (um bocado de cada vez, 6–10 s): a folga é maior.
    static let hlsLiveSlackMs: Int64 = 10_000
    static let previousWaitMs = 3_000
}

/// A ligação ao servidor do canal: lê o stream e escreve-o no anel. Se cair depois de já ter dado, volta a ligar de
/// [reconnectSeconds] em [reconnectSeconds] (como o leitor fazia), num segmento novo do anel; o que já estava
/// guardado fica. Uma URLSession só sua, que se invalida ao fechar (a ligação fecha logo, para libertar a única que o
/// fornecedor deixa).
final class TimeshiftRecorder: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let ring: TimeshiftRing
    private let request: URLRequest
    private let reconnectSeconds: Int
    private var previous: TimeshiftRecorder?
    /// A fila da ligação: os avisos da URLSession e as tentativas, um de cada vez.
    private let queue: OperationQueue
    private var urlSession: URLSession?

    private let lock = NSCondition()
    // Protegido por lock.
    private var closed = false
    private var done = false
    private var task: URLSessionDataTask?
    private var status: Int?
    private var notTsFlag = false

    // Só na fila da ligação.
    private var gotData = false
    private var everData = false
    private var failures = 0
    private var reconnects = 0
    private var refused: Int?

    /// A ligação caiu e volta a ligar daqui a [wait] s (tentativa [attempt]). Chamados na fila da ligação.
    var onReconnect: (@Sendable (_ attempt: Int, _ wait: TimeInterval) -> Void)?
    var onConnected: (@Sendable () -> Void)?
    var onNotTs: (@Sendable () -> Void)?

    /// Quanto se espera que a ligação do canal anterior feche.
    static let previousWaitSeconds = 3.0

    enum Failure: Error {
        case http(Int)
        case empty
    }

    init(ring: TimeshiftRing, request: URLRequest, reconnectSeconds: Int, previous: TimeshiftRecorder?) {
        self.ring = ring
        self.request = request
        self.reconnectSeconds = reconnectSeconds
        self.previous = previous
        queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.name = "timeshift-recorder"
        super.init()
        let config = URLSessionConfiguration.ephemeral
        // Sem dados durante 20 s: a ligação caiu (como no Android).
        config.timeoutIntervalForRequest = 20
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        config.httpMaximumConnectionsPerHost = 1
        urlSession = URLSession(configuration: config, delegate: self, delegateQueue: queue)
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Já fechou a ligação (e não volta a ligar).
    var isDone: Bool { locked { done } }
    /// Afinal o stream não é TS.
    var notTs: Bool { locked { notTsFlag } }
    /// O código HTTP da última resposta do servidor.
    var httpStatus: Int? { locked { status } }
    private var isClosed: Bool { locked { closed } }

    func start() {
        queue.addOperation { [self] in
            // Uma ligação de cada vez: a do canal anterior fecha antes de abrir esta.
            if let previous, !previous.waitDone(seconds: Self.previousWaitSeconds) {
                DebugLog.add("Timeshift: the previous connection is still open")
            }
            previous = nil
            connect()
        }
    }

    /// Espera até [seconds] que a ligação feche; true se fechou.
    func waitDone(seconds: Double) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        lock.lock()
        defer { lock.unlock() }
        while !done {
            if !lock.wait(until: deadline) { break }
        }
        return done
    }

    func close() {
        let running: URLSessionDataTask? = locked {
            closed = true
            return task
        }
        // Uma leitura presa (servidor parado) só acabava no timeout: cancela-se já, para libertar a ligação.
        if let running { running.cancel() } else { queue.addOperation { [self] in finish() } }
    }

    private func connect() {
        guard !isClosed, !isDone, let urlSession else {
            finish()
            return
        }
        gotData = false
        refused = nil
        let t = urlSession.dataTask(with: request)
        locked { task = t }
        t.resume()
    }

    /// Daqui a [seconds], outra tentativa (se entretanto não fechou).
    private func retry(after seconds: Double) {
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { [weak self] in
            guard let self else { return }
            self.queue.addOperation { self.connect() }
        }
    }

    private func finish() {
        let first: Bool = locked {
            guard !done else { return false }
            done = true
            task = nil
            lock.broadcast()
            return true
        }
        guard first else { return }
        ring.endSegment(final: true)
        urlSession?.invalidateAndCancel()
        urlSession = nil
    }

    // MARK: URLSessionDataDelegate

    /// Uma recusa (403, 404, conta ocupada…) vê-se logo no cabeçalho: a ligação fecha já, sem esperar pela página de erro.
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse {
            locked { status = http.statusCode }
            if !(200..<300).contains(http.statusCode) {
                refused = http.statusCode
                completionHandler(.cancel)
                return
            }
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !isClosed, !data.isEmpty, refused == nil else { return }
        if !gotData {
            // Uma página de erro (403, 404, conta ocupada…) não é vídeo: não vai para o anel.
            if let http = dataTask.response as? HTTPURLResponse {
                locked { status = http.statusCode }
                guard (200..<300).contains(http.statusCode) else {
                    refused = http.statusCode
                    dataTask.cancel()
                    return
                }
            }
            gotData = true
            ring.beginSegment()
            if everData { onConnected?() }
            everData = true
            failures = 0
            reconnects = 0
        }
        do {
            try ring.write(data)
        } catch {
            dataTask.cancel()
            return
        }
        // Não é TS (um MP4 num link sem extensão…): o anel não serve, o leitor liga-se ele próprio.
        if ring.isTs == false {
            locked { notTsFlag = true }
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        locked { if self.task === task { self.task = nil } }
        if refused == nil, let http = task.response as? HTTPURLResponse {
            locked { status = http.statusCode }
            if !(200..<300).contains(http.statusCode) { refused = http.statusCode }
        }
        if isClosed {
            finish()
            return
        }
        if notTs {
            finish()
            onNotTs?()
            return
        }
        let failure: Error? = refused.map { Failure.http($0) } ?? error
        if !everData {
            // Ainda não deu: mais umas tentativas seguidas (ver OpenRetry, como no Android). Recusado por estar ocupado
            // (o fornecedor ainda não largou a ligação do canal anterior), depressa e várias vezes; um 404, só mais
            // uma; o resto, 0, 0,5 e 1 s. Depois o erro vai para o leitor, que o explica.
            failures += 1
            guard let wait = OpenRetry.delayMs(httpCode: refused, retry: failures) else {
                DebugLog.add("Timeshift: gave up after \(failures - 1) retries (HTTP \(refused.map(String.init) ?? "-"))")
                ring.endSegment(final: true, error: failure ?? Failure.empty)
                finish()
                return
            }
            retry(after: Double(wait) / 1000)
            return
        }
        guard reconnectSeconds > 0 else {
            ring.endSegment(final: true, error: failure)
            finish()
            return
        }
        ring.endSegment(final: false)
        reconnects += 1
        let wait = Double(reconnectSeconds)
        DebugLog.add("Timeshift: connection dropped, reconnecting in \(reconnectSeconds) s (\(reconnects))")
        onReconnect?(reconnects, wait)
        retry(after: wait)
    }
}
