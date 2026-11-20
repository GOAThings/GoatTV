import Foundation
import GoatCore
import Observation

/// "Testar esta lista": pede ao fornecedor a lista de canais e o guia (os mesmos endereços que a app usa) e conta o
/// que vem, sem abrir nenhuma stream. Lê o corpo aos poucos e só guarda o contador. Nada do endereço (caminho,
/// utilizador, palavra-passe) chega à interface: só o nome do servidor. Igual no Apple TV e no iPhone; as regras do
/// veredicto são as do núcleo partilhado (StreamProbeRules), as do Android.
@MainActor
@Observable
final class ListProbe {
    struct Row: Identifiable {
        let id: Int
        let guide: Bool
        let host: String
        /// -1: o servidor não respondeu.
        let status: Int
        let ms: Int64
        let bytes: Int64
        /// Canais (lista) ou programas (guia).
        let count: Int
        /// "OK", "SLOW", "REFUSED", "UNREACHABLE" ou "EMPTY".
        let verdict: String
    }

    private(set) var running = false
    private(set) var rows: [Row] = []
    /// As listas de portal (Stalker) não se medem sem abrir sessão no portal.
    private(set) var portal = false
    /// A lista não tem endereço de guia.
    private(set) var noGuide = false
    @ObservationIgnored private var task: Task<Void, Never>?

    func clear() {
        task?.cancel()
        task = nil
        running = false
        rows = []
        portal = false
        noGuide = false
    }

    /// [knownGuideURL]: o guia que o M3U trouxe (só se sabe na lista em uso).
    func start(_ playlist: Playlist, knownGuideURL: String?) {
        guard !running else { return }
        clear()
        if playlist.type == .stalker {
            portal = true
            return
        }
        running = true
        task = Task {
            let xtream = playlist.type == .xtream
            func nonBlank(_ s: String?) -> String? {
                let t = s?.trimmingCharacters(in: .whitespaces) ?? ""
                return t.isEmpty ? nil : t
            }
            let list = xtream ? (try? Xtream.apiURL(playlist, action: "get_live_streams"))?.absoluteString : nonBlank(playlist.url)
            if let list {
                let row = await Self.fetch(list, token: xtream ? "\"stream_id\"" : "#EXTINF", guide: false, id: 0)
                if Task.isCancelled { return }
                rows.append(row)
            }
            let guide = nonBlank(playlist.epgUrl) ?? nonBlank(knownGuideURL) ?? (xtream ? Xtream.xmltvURL(playlist) : nil)
            if let guide {
                let row = await Self.fetch(guide, token: "<programme", guide: true, id: 1)
                if Task.isCancelled { return }
                rows.append(row)
            }
            noGuide = guide == nil
            running = false
        }
    }

    private nonisolated static func fetch(_ url: String, token: String, guide: Bool, id: Int) async -> Row {
        let host = StreamProbeRules.host(url)
        guard let u = URL(string: url) else {
            return Row(id: id, guide: guide, host: host, status: -1, ms: 0, bytes: 0, count: 0, verdict: "UNREACHABLE")
        }
        let t0 = ProbeCounters.nowMs()
        let fetch = ListFetch(token: token, limit: guide ? Int64(SafeInput.maxGuideBytes) : Int64(SafeInput.maxPlaylistBytes))
        let out = await fetch.run(URLRequest(url: u))
        let ms = ProbeCounters.nowMs() - t0
        let count = out.count
        return Row(id: id, guide: guide, host: host, status: out.status, ms: ms, bytes: out.bytes, count: count,
                   verdict: StreamProbeRules.listVerdict(status: out.status, ms: ms, count: count))
    }
}

/// Um pedido que se lê aos pedaços, só para contar um marcador. Um guia em `.gz` (sem Content-Encoding) guarda-se
/// comprimido (até [gzipCap]) e descomprime-se de uma vez no fim, sem guardar o resultado (ver Gzip.scan).
private final class ListFetch: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    struct Outcome {
        let status: Int
        let bytes: Int64
        let count: Int
    }

    private static let gzipCap = 96 * 1024 * 1024

    private let tally: TokenTally
    private let limit: Int64
    private var continuation: CheckedContinuation<Outcome, Never>?
    private var status = -1
    private var bytes: Int64 = 0
    private var first = true
    private var gzip: Data?

    init(token: String, limit: Int64) {
        tally = TokenTally(token)
        self.limit = limit
    }

    func run(_ request: URLRequest) async -> Outcome {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: config, delegate: self, delegateQueue: queue)
        return await withCheckedContinuation { (c: CheckedContinuation<Outcome, Never>) in
            continuation = c
            session.dataTask(with: request).resume()
            session.finishTasksAndInvalidate()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse {
            status = http.statusCode
            // Recusou: o corpo não interessa.
            if !(200..<300).contains(http.statusCode) { return completionHandler(.cancel) }
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        bytes += Int64(data.count)
        if first {
            first = false
            if data.count >= 2, data[data.startIndex] == 0x1F, data[data.startIndex + 1] == 0x8B { gzip = Data() }
        }
        if gzip != nil {
            if gzip!.count < Self.gzipCap { gzip!.append(data) }
        } else {
            tally.feed(data)
        }
        if bytes >= limit { dataTask.cancel() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let c = continuation else { return }
        continuation = nil
        if let gz = gzip { _ = Gzip.scan(gz) { [tally] in tally.feed($0) } }
        c.resume(returning: Outcome(status: status, bytes: bytes, count: tally.count))
    }
}

/// Os textos das linhas do painel, nas línguas da app (as chaves probe_* do Android).
extension ListProbe.Row {
    func verdictText(_ s: Strings) -> String {
        switch verdict {
        case "OK": return s.r("probe_lv_ok")
        case "SLOW": return s.r("probe_lv_slow")
        case "REFUSED": return s.r("probe_lv_refused", "\(status)")
        case "EMPTY": return s.r("probe_lv_empty")
        default: return s.r("probe_lv_unreachable")
        }
    }

    var good: Bool { verdict == "OK" }

    /// "1.2 s · 4.5 MB" e, a seguir, quantos canais ou programas (sem o número se o servidor recusou); nil se não respondeu.
    func detail(_ s: Strings) -> (String, String)? {
        guard status >= 0 else { return nil }
        let count = guide ? s.r("probe_list_programmes", "\(self.count)") : s.r("probe_list_channels", "\(self.count)")
        return ("\(s.r("probe_seconds", StreamProbeRules.seconds(ms))) · \(StreamProbeRules.size(bytes))", verdict == "REFUSED" ? "" : count)
    }

    func title(_ s: Strings) -> String { guide ? s.r("probe_list_guide") : s.r("probe_list_playlist") }
}
