#if os(tvOS)
import Darwin
import Foundation
import GoatCore
import Network
import SwiftUI

/// Os canais da GoatTV para o GoatRemote (o comando no telemóvel), na rede de casa, só para outros aparelhos com
/// endereços privados e em pedidos diretos (ver `LanRequest` e `LanPeer` no GoatCore). As mesmas regras da porta 6470
/// do GoatLauncher e da GoatTV Android.
///
///   GET /channels      a lista aberta: grupos e canais pela ordem do ecrã, sem escondidos nem bloqueados
///                      (`PhoneChannelList`). 503 enquanto não há lista (ativação, a carregar, erro, sem listas).
///   GET /logo/<chave>  o logótipo do canal, pedido pela TV ao fornecedor (o telemóvel nunca fala com ele)
///   POST /play/<chave> põe o canal a dar, como escolhê-lo na lista. 409 se a GoatTV não estiver à frente (o
///                      telemóvel abre-a e tenta outra vez), 404 se não puder ser visto.
///
/// Só na Apple TV (a app de iPhone não abre porta nenhuma). O tvOS suspende a app em segundo plano, por isso a porta
/// só está aberta com a app ativa: fecha-se em segundo plano e volta a abrir no regresso. Tudo corre no thread
/// principal (as chamadas do Network.framework também), e o estado vem do `AppModel`.
@MainActor
final class PhoneChannels {
    private weak var model: AppModel?
    private var listener: NWListener?
    private var retryTask: Task<Void, Never>?
    /// A GoatTV está à frente (cena ativa): só aí o /play troca o canal.
    private var inFront = false

    /// As ligações a ser servidas, e as que esperam lugar.
    private var links: [ObjectIdentifier: PhoneLink] = [:]
    private var waiting: [NWConnection] = []

    /// Logótipos servidos há pouco (URL → imagem); os mais antigos saem primeiro.
    private var logos: [String: PhoneLogo] = [:]
    private var logoOrder: [String] = []
    private var logoBytes = 0

    func attach(_ model: AppModel) {
        self.model = model
    }

    /// A cena mudou: a porta abre com a app ativa e fecha em segundo plano.
    func scenePhase(_ phase: ScenePhase) {
        inFront = phase == .active
        switch phase {
        case .active: listen()
        case .background: stop()
        default: break
        }
    }

    // MARK: Porta

    private func listen() {
        retryTask?.cancel()
        retryTask = nil
        guard listener == nil else { return }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let port: NWListener
        do {
            port = try NWListener(using: parameters, on: PhoneLimits.port)
        } catch {
            DebugLog.add("Phone port \(PhoneLimits.port): \(error)")
            retryLater()
            return
        }
        // Na fila principal: os avisos chegam já no thread do AppModel.
        port.newConnectionHandler = { [weak self] connection in
            MainActor.assumeIsolated { self?.accept(connection) }
        }
        port.stateUpdateHandler = { [weak self, weak port] state in
            MainActor.assumeIsolated {
                guard let owner = self, let port, owner.listener === port else { return }
                owner.listenerChanged(state)
            }
        }
        listener = port
        port.start(queue: .main)
    }

    private func listenerChanged(_ state: NWListener.State) {
        switch state {
        case .ready:
            DebugLog.add("Phone port \(PhoneLimits.port) open")
        case .failed(let error):
            // Porta ocupada (ou a rede foi abaixo): fecha e tenta daqui a pouco, sem nunca parar a app.
            DebugLog.add("Phone port \(PhoneLimits.port) failed: \(error)")
            listener?.cancel()
            listener = nil
            retryLater()
        default:
            break
        }
    }

    private func retryLater() {
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(PhoneLimits.retrySeconds))
            guard !Task.isCancelled, let self, self.inFront else { return }
            self.listen()
        }
    }

    /// Segundo plano: fecha a porta e as ligações (o tvOS ia suspender a app de qualquer maneira).
    private func stop() {
        retryTask?.cancel()
        retryTask = nil
        listener?.cancel()
        listener = nil
        waiting.forEach { $0.cancel() }
        waiting = []
        links.values.forEach { $0.close() }
        logos = [:]
        logoOrder = []
        logoBytes = 0
    }

    // MARK: Ligações

    private func accept(_ connection: NWConnection) {
        // Quem não pode falar é fechado logo: não fica com lugar.
        guard listener != nil, let peer = PhoneLink.address(connection.endpoint),
              LanPeer.allowed(peer: peer, local: nil, own: PhoneLink.ownAddresses()) else {
            connection.cancel()
            return
        }
        if links.count < PhoneLimits.maxConnections {
            begin(connection)
        } else if waiting.count < PhoneLimits.maxWaiting {
            // Um telemóvel pede muitos logótipos de uma vez: os seguintes esperam pela sua vez.
            waiting.append(connection)
        } else {
            connection.cancel()
        }
    }

    /// Uma ligação dura no máximo `PhoneLimits.connectionSeconds`, e o que falhar nela fica nela: a app continua.
    private func begin(_ connection: NWConnection) {
        let link = PhoneLink(connection)
        let id = ObjectIdentifier(link)
        links[id] = link
        let work = Task { [weak self] in
            guard let self else { return }
            await self.serve(link)
        }
        let deadline = Task {
            try? await Task.sleep(for: .seconds(PhoneLimits.connectionSeconds))
            guard !Task.isCancelled else { return }
            work.cancel()
            link.close()
        }
        Task { [weak self] in
            await work.value
            deadline.cancel()
            link.close()
            self?.finished(id)
        }
    }

    private func finished(_ id: ObjectIdentifier) {
        links.removeValue(forKey: id)
        guard listener != nil, !waiting.isEmpty else { return }
        begin(waiting.removeFirst())
    }

    private func serve(_ link: PhoneLink) async {
        guard await link.start() else { return }
        // A ligação da própria TV pelo endereço dela (já se viu o resto ao aceitar).
        if let peer = PhoneLink.address(link.connection.endpoint),
           !LanPeer.allowed(peer: peer, local: PhoneLink.address(link.connection.currentPath?.localEndpoint), own: []) {
            return
        }
        let reading = Task {
            try? await Task.sleep(for: .seconds(PhoneLimits.readSeconds))
            guard !Task.isCancelled else { return }
            link.close()
        }
        let request = await readHead(link)
        reading.cancel()
        guard let request else { return }
        guard request.allowed else {
            await reply(link, "403 Forbidden", text: "recusado")
            return
        }
        let method = request.method
        let path = request.path
        if method == "GET", path == "/channels" {
            await channels(link)
        } else if method == "GET", path.hasPrefix("/logo/") {
            await logo(link, PhoneChannelList.decodeKey(String(path.dropFirst("/logo/".count))))
        } else if method == "POST", path.hasPrefix("/play/") {
            await play(link, PhoneChannelList.decodeKey(String(path.dropFirst("/play/".count))))
        } else {
            await notFound(link)
        }
    }

    /// Lê até à linha em branco, no máximo `LanRequest.maxHeadBytes`. nil se fechar antes ou não se perceber.
    private func readHead(_ link: PhoneLink) async -> LanRequest? {
        var head: [UInt8] = []
        while true {
            let (data, ended) = await link.receive(maximum: LanRequest.maxHeadBytes - head.count)
            if let data { head += data }
            switch LanRequest.scan(head) {
            case .done(let request): return request
            case .needMore: if ended { return nil }
            }
        }
    }

    private func channels(_ link: PhoneLink) async {
        // O estado tira-se aqui (thread principal); o JSON (pode ser grande) monta-se fora dele.
        guard let list = model?.phoneChannelList() else {
            await reply(link, "503 Service Unavailable", text: "sem lista")
            return
        }
        let body = await Task.detached(priority: .userInitiated) { list.json() }.value
        await reply(link, "200 OK", type: "application/json; charset=utf-8", body: body)
    }

    private func logo(_ link: PhoneLink, _ key: String?) async {
        guard let key, let url = model?.phoneLogoURL(key) else {
            await notFound(link)
            return
        }
        var image = cachedLogo(url)
        if image == nil {
            image = await Self.fetchLogo(url)
            if let image { remember(url, image) }
        }
        guard let found = image else {
            await notFound(link)
            return
        }
        await reply(link, "200 OK", type: found.type, body: found.bytes)
    }

    private func play(_ link: PhoneLink, _ key: String?) async {
        let status: String
        let channel = key.flatMap { model?.phoneChannel($0) }
        if let model, model.phoneReady() {
            if let channel {
                // Só se troca o canal com a GoatTV à frente; atrás de outra app, o telemóvel abre-a primeiro.
                if inFront {
                    model.playFromPhone(channel)
                    status = "200 OK"
                } else {
                    status = "409 Conflict"
                }
            } else {
                status = "404 Not Found"
            }
        } else {
            status = "503 Service Unavailable"
        }
        let text = status == "200 OK" ? "ok" : String(status.drop { $0 != " " }.dropFirst())
        await reply(link, status, text: text)
    }

    private func notFound(_ link: PhoneLink) async {
        await reply(link, "404 Not Found", text: "não há")
    }

    private func reply(_ link: PhoneLink, _ status: String, text: String) async {
        await reply(link, status, type: "text/plain; charset=utf-8", body: Data(text.utf8))
    }

    private func reply(_ link: PhoneLink, _ status: String, type: String, body: Data) async {
        var data = Data(("HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").utf8)
        data.append(body)
        await link.send(data)
    }

    // MARK: Logótipos

    private func cachedLogo(_ url: String) -> PhoneLogo? {
        guard let hit = logos[url] else { return nil }
        if let i = logoOrder.firstIndex(of: url) {
            logoOrder.remove(at: i)
            logoOrder.append(url)
        }
        return hit
    }

    private func remember(_ url: String, _ logo: PhoneLogo) {
        if let old = logos.updateValue(logo, forKey: url) {
            logoBytes -= old.bytes.count
            logoOrder.removeAll { $0 == url }
        }
        logoOrder.append(url)
        logoBytes += logo.bytes.count
        while logos.count > PhoneLimits.cachedLogos || logoBytes > PhoneLimits.cachedBytes, !logoOrder.isEmpty {
            if let gone = logos.removeValue(forKey: logoOrder.removeFirst()) { logoBytes -= gone.bytes.count }
        }
    }

    /// Vai buscar o logótipo ao fornecedor como o ecrã o carrega (a mesma sessão, os mesmos cabeçalhos), até
    /// `PhoneLimits.maxLogoBytes`. nil se falhar, se for maior ou se não for uma imagem.
    nonisolated private static func fetchLogo(_ address: String) async -> PhoneLogo? {
        guard let url = URL(string: address) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = PhoneLimits.logoTimeoutSeconds
        do {
            let (data, response) = try await HTTP.fetch(URLSession.shared, request, maxBytes: PhoneLimits.maxLogoBytes)
            guard (200..<300).contains(response.statusCode),
                  let type = PhoneChannelList.logoType(contentType: response.value(forHTTPHeaderField: "Content-Type"), bytes: data)
            else { return nil }
            return PhoneLogo(type: type, bytes: data)
        } catch {
            return nil
        }
    }
}

private struct PhoneLogo: Sendable {
    let type: String
    let bytes: Data
}

private enum PhoneLimits {
    static let port: NWEndpoint.Port = 6471
    /// Chega para dois telemóveis a pedir os logótipos todos ao mesmo tempo.
    static let maxConnections = 16
    static let maxWaiting = 64
    static let connectionSeconds = 10
    static let readSeconds = 5
    static let retrySeconds = 10
    /// Antes do fim da ligação ao telemóvel: depois disso já ninguém espera a resposta.
    static let logoTimeoutSeconds: TimeInterval = 8
    static let maxLogoBytes = 1024 * 1024
    static let cachedLogos = 200
    static let cachedBytes = 8 * 1024 * 1024
}

/// Uma ligação de um telemóvel, com as chamadas do Network.framework em async. Os avisos chegam na fila principal.
private final class PhoneLink: @unchecked Sendable {
    let connection: NWConnection
    private let lock = NSLock()
    private var started: CheckedContinuation<Bool, Never>?

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    /// Abre a ligação: true quando está pronta, false se falhou ou foi fechada antes.
    func start() async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            lock.lock()
            started = continuation
            lock.unlock()
            connection.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready: self?.settle(true)
                case .failed, .cancelled: self?.settle(false)
                // Uma ligação que chegou e fica à espera de rede já não serve.
                case .waiting: self?.connection.cancel()
                default: break
                }
            }
            connection.start(queue: .main)
        }
    }

    private func settle(_ ready: Bool) {
        lock.lock()
        let continuation = started
        started = nil
        lock.unlock()
        continuation?.resume(returning: ready)
    }

    /// O que chegar (até [maximum] bytes) e se a ligação acabou (fechada do outro lado, erro ou fechada aqui).
    func receive(maximum: Int) async -> (Data?, Bool) {
        await withCheckedContinuation { (continuation: CheckedContinuation<(Data?, Bool), Never>) in
            connection.receive(minimumIncompleteLength: 1, maximumLength: max(1, maximum)) { data, _, isComplete, error in
                continuation.resume(returning: (data, isComplete || error != nil))
            }
        }
    }

    /// Manda a resposta e fecha o envio (`Connection: close`).
    func send(_ data: Data) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            connection.send(content: data, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in
                continuation.resume()
            })
        }
    }

    func close() {
        connection.cancel()
    }

    /// Os bytes de um endereço IP (4 ou 16), ou nil se não for um.
    static func address(_ endpoint: NWEndpoint?) -> [UInt8]? {
        guard case .hostPort(let host, _)? = endpoint else { return nil }
        switch host {
        case .ipv4(let ip): return [UInt8](ip.rawValue)
        case .ipv6(let ip): return [UInt8](ip.rawValue)
        default: return nil
        }
    }

    /// Os endereços de todas as interfaces desta TV.
    static func ownAddresses() -> [[UInt8]] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return [] }
        defer { freeifaddrs(first) }
        var list: [[UInt8]] = []
        var cursor = first
        while let item = cursor {
            if let sa = item.pointee.ifa_addr {
                switch Int32(sa.pointee.sa_family) {
                case AF_INET:
                    sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { sin in
                        list.append(withUnsafeBytes(of: sin.pointee.sin_addr) { Array($0) })
                    }
                case AF_INET6:
                    sa.withMemoryRebound(to: sockaddr_in6.self, capacity: 1) { sin6 in
                        list.append(withUnsafeBytes(of: sin6.pointee.sin6_addr) { Array($0) })
                    }
                default:
                    break
                }
            }
            cursor = item.pointee.ifa_next
        }
        return list
    }
}
#endif
