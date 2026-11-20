import GoatCore
import Network
import SwiftUI

/// Quem para o canal no telemóvel depois de o mandar para uma TV ("Enviar para a TV"): larga a ligação ao fornecedor.
private struct SentToTvKey: EnvironmentKey {
    static let defaultValue: () -> Void = {}
}

extension EnvironmentValues {
    var sentToTv: () -> Void {
        get { self[SentToTvKey.self] }
        set { self[SentToTvKey.self] = newValue }
    }
}

/// Uma TV com a GoatTV encontrada na rede ([pkg] vazio: sem GoatLauncher para a trazer para a frente, ex. Apple TV).
struct FoundTv: Identifiable, Equatable {
    let name: String
    let host: String
    let port: Int
    let pkg: String
    /// O nome com que se anunciou no Bonjour (para a tirar quando sai da rede).
    let announced: String
    var id: String { host }
}

/// Procura as TVs com a GoatTV na rede de casa (Bonjour, `TvSend.service`) e confirma cada uma com um /hello. O
/// telemóvel nunca abre porta nenhuma: só faz pedidos diretos, como o GoatRemote. Tudo no thread principal.
@MainActor @Observable
final class TvBrowser {
    private(set) var tvs: [FoundTv] = []
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var resolving: [NWEndpoint: NWConnection] = [:]

    func start() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjour(type: TvSend.service, domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            MainActor.assumeIsolated { self?.changed(results) }
        }
        browser = b
        b.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolving.values.forEach { $0.cancel() }
        resolving = [:]
    }

    private func changed(_ results: Set<NWBrowser.Result>) {
        var names = Set<String>()
        for r in results {
            if case .service(let name, _, _, _) = r.endpoint { names.insert(name) }
        }
        // Saiu da rede (ou fechou a GoatTV na Apple TV): deixa de aparecer.
        tvs.removeAll { !names.contains($0.announced) }
        for r in results { resolve(r.endpoint) }
    }

    /// O endereço IPv4 de um serviço anunciado: liga-se a ele e vê-se para onde foi (o endereço vai no Host do pedido).
    private func resolve(_ endpoint: NWEndpoint) {
        guard case .service(let announced, _, _, _) = endpoint, resolving[endpoint] == nil,
              !tvs.contains(where: { $0.announced == announced }) else { return }
        let parameters = NWParameters.tcp
        (parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let connection = NWConnection(to: endpoint, using: parameters)
        resolving[endpoint] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            MainActor.assumeIsolated {
                guard let self, let connection else { return }
                switch state {
                case .ready:
                    let remote = connection.currentPath?.remoteEndpoint
                    connection.cancel()
                    self.resolving[endpoint] = nil
                    if case .hostPort(.ipv4(let ip), let port)? = remote {
                        let host = ip.rawValue.map { String($0) }.joined(separator: ".")
                        Task { await self.confirm(host: host, port: Int(port.rawValue), announced: announced) }
                    }
                case .failed, .waiting:
                    connection.cancel()
                    self.resolving[endpoint] = nil
                default:
                    break
                }
            }
        }
        connection.start(queue: .main)
    }

    private func confirm(host: String, port: Int, announced: String) async {
        let (status, body) = await TvLink.request(host: host, port: port, method: "GET", path: "/hello")
        guard status == TvSend.ok, let hello = TvSend.parseHello(body), browser != nil else { return }
        let name = hello.name.isEmpty ? TvSend.clean(announced) : hello.name
        tvs.removeAll { $0.host == host }
        tvs.append(FoundTv(name: name.isEmpty ? host : name, host: host, port: port, pkg: hello.pkg, announced: announced))
    }
}

/// Os pedidos do telemóvel a uma TV: diretos (sem Origin, Host = o IP), numa sessão sem cookies nem cache.
enum TvLink {
    private static let session = URLSession(configuration: .ephemeral)

    /// O estado (0 sem resposta) e o corpo.
    static func request(host: String, port: Int, method: String, path: String, body: String? = nil) async -> (Int, String) {
        guard let url = URL(string: "http://\(host):\(port)\(path)") else { return (0, "") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 4
        request.cachePolicy = .reloadIgnoringLocalCacheData
        if let body {
            request.httpBody = Data(body.utf8)
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        do {
            let (data, response) = try await HTTP.fetch(session, request, maxBytes: TvSend.maxBodyBytes)
            return (response.statusCode, String(decoding: data, as: UTF8.self))
        } catch {
            return (0, "")
        }
    }

    /// Manda o canal a [tv] (o corpo de `TvSend.body`). Com a GoatTV atrás de outra app pede ao GoatLauncher (só na
    /// Android TV) para a trazer e tenta outra vez (`TvSend.step`). O estado da última resposta (0 sem resposta).
    static func send(_ tv: FoundTv, body: String) async -> Int {
        var launched = false
        var status = 0
        for attempt in 0... {
            status = await request(host: tv.host, port: tv.port, method: "POST", path: "/open", body: body).0
            switch TvSend.step(status: status, attempt: attempt, launched: launched, canLaunch: !tv.pkg.isEmpty) {
            case .launch:
                launched = true
                let opened = await request(host: tv.host, port: TvSend.launcherPort, method: "POST", path: TvSend.launcherPath(tv.pkg)).0
                if opened != TvSend.ok { return status }
            case .wait:
                break
            case .done:
                return status
            }
            try? await Task.sleep(for: .milliseconds(TvSend.retryMs))
        }
        return status
    }
}

/// "Enviar para a TV": as TVs com a GoatTV na rede de casa (a última usada primeiro). Um toque manda o canal a dar (só
/// ids e nome); a dar na TV, o telemóvel para o canal e volta ao ecrã inicial.
struct PhoneSendToTvSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sentToTv) private var sentToTv
    @State private var browser = TvBrowser()
    @State private var searching = true
    @State private var sending: String?
    @State private var last = UserDefaults.standard.string(forKey: PhoneSendToTvSheet.lastKey)

    private static let lastKey = "send_to_tv.last"

    var body: some View {
        let s = model.strings
        let tvs = browser.tvs
        let ordered = TvSend.order(tvs.map(\.name), last: last).map { tvs[$0] }
        NavigationStack {
            List {
                ForEach(ordered) { tv in
                    Button { send(tv) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "tv").foregroundStyle(Theme.textDim)
                            Text(tv.name).foregroundStyle(Theme.text)
                            Spacer()
                            if sending == tv.host { ProgressView() }
                        }
                    }
                    .disabled(sending != nil)
                }
                // A procurar nos primeiros segundos; sem TVs, o que verificar.
                if searching {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(s.r("send_searching")).foregroundStyle(Theme.textFaint)
                    }
                } else if ordered.isEmpty {
                    Text(s.r("send_empty")).foregroundStyle(Theme.textFaint)
                }
            }
            .navigationTitle(s.r("send_to_tv"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .onAppear { browser.start() }
        .onDisappear { browser.stop() }
        .task {
            // O Bonjour pode levar uns segundos a responder numa rede com muitos aparelhos.
            try? await Task.sleep(for: .seconds(6))
            searching = false
        }
    }

    private func send(_ tv: FoundTv) {
        guard sending == nil, let channel = model.current, let content = model.content else { return }
        sending = tv.host
        let body = TvSend.body(playlist: content.playlist.id, channel: channel.key, name: channel.name,
                               pro: model.prefs.proLineup ?? "", from: UIDevice.current.name)
        let s = model.strings
        Task {
            let status = await TvLink.send(tv, body: body)
            sending = nil
            switch TvSend.outcome(status) {
            case .playing:
                last = tv.name
                UserDefaults.standard.set(tv.name, forKey: Self.lastKey)
                model.showToast(s.r("send_playing", tv.name))
                sentToTv()
                dismiss()
            case .missing:
                model.showToast(s.r("send_missing", tv.name))
            case .closed:
                model.showToast(s.r("send_closed", tv.name))
            case .failed:
                model.showToast(s.r("send_failed", tv.name))
            }
        }
    }
}
