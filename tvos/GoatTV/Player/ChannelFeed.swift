import Foundation
import GoatCore

/// A ligação ao fornecedor de um canal e o anel em disco onde ela escreve ([TimeshiftRing]); o ChannelFeed do GoatTV
/// Android. É a única ligação da app enquanto existe: o leitor lê do anel (a pausa do direto, ver Timeshift) e as
/// gravações copiam dele (RecordingManager).
///
/// Quem a usa segura-a ([hold]): o leitor e as gravações. Fecha (e o anel apaga-se) quando ninguém a segura. Há no
/// máximo uma de cada vez ([current]): abrir outra fecha a anterior, mesmo que uma gravação a segure (a gravação acaba
/// aí), e a nova só liga depois de a anterior largar a ligação (o gravador espera pelo anterior). Assim, ao voltar ao
/// canal que se está a gravar, o leitor lê deste anel sem abrir outra ligação ([find]).
@MainActor
final class ChannelFeed {
    /// Quem segura a ligação.
    enum Holder: Hashable { case player, recording }

    let ring: TimeshiftRing
    /// O endereço do canal (é por ele que o leitor e as gravações sabem que é o mesmo).
    let url: String
    let recorder: TimeshiftRecorder
    private var holders: [Holder: Int] = [:]
    /// Já não há ligação (fechou, ou foi trocada por outra).
    private(set) var isClosed = false

    /// A ligação caiu e volta a ligar (tentativa, espera em s); voltou a dar; afinal não é TS. Na thread principal.
    var onReconnect: ((_ attempt: Int, _ wait: TimeInterval) -> Void)?
    var onConnected: (() -> Void)?
    var onNotTs: (() -> Void)?

    /// A ligação aberta agora (no máximo uma).
    private(set) static var current: ChannelFeed?
    /// O gravador da última ligação (a seguinte espera que ele feche).
    private static var last: TimeshiftRecorder?
    private static var generation = 0

    private init(ring: TimeshiftRing, url: String, recorder: TimeshiftRecorder) {
        self.ring = ring
        self.url = url
        self.recorder = recorder
    }

    /// A ligação aberta para [url], se ainda está aberta.
    static func find(_ url: String) -> ChannelFeed? {
        guard let feed = current, !feed.isClosed, feed.url == url else { return nil }
        return feed
    }

    /// Abre [url] num anel novo, segura por [holder]. Fecha antes a que estiver aberta. nil sem espaço para o anel.
    /// [maxBytes]: o tamanho máximo do anel (o da pausa do direto, ou um pequeno para uma gravação sem ninguém a ver).
    static func open(_ url: URL, userAgent: String, referrer: String?, reconnectSeconds: Int, holder: Holder,
                     maxBytes: Int64 = Timeshift.maxBytes) -> ChannelFeed? {
        current?.close()
        guard let capacity = capacity(maxBytes) else { return nil }
        generation += 1
        let ring: TimeshiftRing
        do {
            ring = try TimeshiftRing(path: dir.appendingPathComponent("ring-\(generation).ts").path, capacity: capacity,
                                     maxAgeMs: Timeshift.maxAgeMs, clock: clock)
        } catch {
            DebugLog.add("Timeshift: no ring (\(error))")
            return nil
        }
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let referrer, !referrer.isEmpty { request.setValue(referrer, forHTTPHeaderField: "Referer") }
        let recorder = TimeshiftRecorder(ring: ring, request: request, reconnectSeconds: reconnectSeconds, previous: last)
        let feed = ChannelFeed(ring: ring, url: url.absoluteString, recorder: recorder)
        // Os avisos do gravador chegam de outra fila; uma ligação já fechada não avisa ninguém.
        recorder.onReconnect = { [weak feed] attempt, wait in
            Task { @MainActor in
                guard let feed, !feed.isClosed else { return }
                feed.onReconnect?(attempt, wait)
            }
        }
        recorder.onConnected = { [weak feed] in
            Task { @MainActor in
                guard let feed, !feed.isClosed else { return }
                feed.onConnected?()
            }
        }
        recorder.onNotTs = { [weak feed] in
            Task { @MainActor in
                guard let feed, !feed.isClosed else { return }
                feed.onNotTs?()
            }
        }
        feed.holders[holder] = 1
        current = feed
        last = recorder
        recorder.start()
        DebugLog.add("Timeshift: connection opened for the \(holder) (ring \(capacity / 1_048_576) MB)")
        return feed
    }

    /// Passa a segurar a ligação; false se já fechou.
    func hold(_ holder: Holder) -> Bool {
        guard !isClosed else { return false }
        holders[holder, default: 0] += 1
        return true
    }

    /// Larga a ligação; sem mais ninguém a segurá-la, fecha.
    func release(_ holder: Holder) {
        guard let n = holders[holder] else { return }
        if n <= 1 { holders.removeValue(forKey: holder) } else { holders[holder] = n - 1 }
        if holders.isEmpty { close() }
    }

    /// [holder] segura a ligação.
    func heldBy(_ holder: Holder) -> Bool { (holders[holder] ?? 0) > 0 }

    /// Fecha a ligação e apaga o anel, segure-a quem a segurar.
    func close() {
        guard !isClosed else { return }
        isClosed = true
        holders.removeAll()
        if Self.current === self { Self.current = nil }
        onReconnect = nil
        onConnected = nil
        onNotTs = nil
        recorder.close()
        ring.close()
        DebugLog.add("Timeshift: connection closed")
    }

    // MARK: Uma ligação de cada vez

    /// O leitor vai abrir uma ligação sua (sem anel): a do anel fecha primeiro, mesmo que uma gravação a segure.
    static func yieldConnection() {
        current?.close()
    }

    /// A ligação anterior ainda não fechou.
    static var closing: Bool { last?.isDone == false && (current == nil || current?.isClosed == true) }

    /// Espera (até 3 s) que a ligação anterior feche: o fornecedor só deixa uma.
    static func waitClosed() async {
        var waited = 0
        while closing, waited < Timeshift.previousWaitMs {
            try? await Task.sleep(for: .milliseconds(50))
            waited += 50
        }
    }

    // MARK: Disco

    /// Espaço para o anel: até [maxBytes], sem passar de metade do livre (tirando uma reserva).
    private static func capacity(_ maxBytes: Int64) -> Int64? {
        let files = FileManager.default
        try? files.createDirectory(at: dir, withIntermediateDirectories: true)
        // Restos de uma sessão que não fechou (a app morreu a meio); a ligação anterior já fechou o seu.
        for file in (try? files.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] {
            try? files.removeItem(at: file)
        }
        let free = freeBytes(dir)
        let bytes = min(maxBytes, (free - Timeshift.reserveBytes) / 2)
        guard bytes >= TimeshiftRing.minCapacity else {
            DebugLog.add("Timeshift: little free space (\(free / 1_048_576) MB), no ring")
            return nil
        }
        return bytes
    }

    /// O espaço livre no disco onde fica [url].
    nonisolated static func freeBytes(_ url: URL) -> Int64 {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return ((try? FileManager.default.attributesOfFileSystem(forPath: url.path))?[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
    }

    private static let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("timeshift", isDirectory: true)

    /// Um relógio que só anda para a frente (o do vídeo não pode recuar se a hora do aparelho mudar).
    private static let clock: () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
}
