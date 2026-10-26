import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A conta GOAT (thegoat.systems) vista por esta TV: o mesmo login de dispositivo e o mesmo perfil `goattv` do
/// GoatTV Android, por isso listas, favoritos e definições são os mesmos em todas as TVs.
/// O token vai em `Authorization: <token>` (sem "Bearer").
public final class Backend: @unchecked Sendable {
    public static let baseURL = URL(string: "https://thegoat.systems/")!
    public static let app = "goattv"

    public struct DeviceStart: Sendable {
        public var code: String
        public var deviceSecret: String
        public var verificationURL: String
        public var verificationURLComplete: String?
        public var expiresIn: Int
        public var interval: Int
    }

    public struct Approval: Sendable {
        public var token: String
        public var userId: String?
        public var email: String?
        public var deviceId: String?
        public var deviceName: String?
    }

    public enum PollResult: Sendable {
        case pending
        case approved(Approval)
        case expired
        case slowDown
    }

    public struct Config: Sendable {
        public var userId: String?
        public var email: String?
        public var deviceName: String?
        public var playlists: [Playlist]
        /// playlistId → channelKeys.
        public var favorites: [String: [String]]
        public var settings: [String: JSON]
        /// Versão do `settings` (soma 1 a cada gravação); nil se nunca se gravou.
        public var settingsRev: Int64?
        /// A resposta tal como veio (para guardar em cache).
        public var raw: JSON
        /// O `index_hash` que a conta tem de cada lista (nil: nenhuma TV enviou ainda o índice de canais dela).
        public var indexHashes: [String: String?] = [:]
    }

    public enum Failure: Error, Equatable, Sendable {
        case http(Int)
        case badResponse
    }

    private let session: URLSession
    private let lock = NSLock()
    private var _token: String?

    /// Chamado quando o servidor responde 401 com um token (ex.: "Terminar sessão em todas as TVs" no site).
    public var onSessionEnded: (@Sendable () -> Void)?

    public init(session: URLSession = HTTP.makeSession()) {
        self.session = session
    }

    public var token: String? {
        get { lock.lock(); defer { lock.unlock() }; return _token }
        set { lock.lock(); _token = newValue; lock.unlock() }
    }

    // MARK: Ativação

    public func deviceStart(deviceName: String) async throws -> DeviceStart {
        let (status, json, _) = try await send("POST", "api/devices/start", body: ["app": .string(Self.app), "device_name": .string(deviceName)])
        guard status == 200 else { throw Failure.http(status) }
        guard let json, let code = json["code"]?.text, let secret = json["device_secret"]?.text,
              let url = json["verification_url"]?.text else { throw Failure.badResponse }
        return DeviceStart(
            code: code,
            deviceSecret: secret,
            verificationURL: url,
            verificationURLComplete: json["verification_url_complete"]?.text,
            expiresIn: json["expires_in"]?.int ?? 600,
            interval: max(2, json["interval"]?.int ?? 5)
        )
    }

    public func devicePoll(secret: String) async throws -> PollResult {
        let (status, json, _) = try await send("POST", "api/devices/poll", body: ["device_secret": .string(secret)])
        switch status {
        case 200:
            if json?["status"]?.text == "approved", let token = json?["token"]?.text {
                return .approved(Approval(
                    token: token,
                    userId: json?["user"]?["id"]?.text,
                    email: json?["user"]?["email"]?.text,
                    deviceId: json?["device"]?["id"]?.text,
                    deviceName: json?["device"]?["name"]?.text
                ))
            }
            return .pending
        case 202: return .pending
        case 404, 410: return .expired
        case 429: return .slowDown
        default: return .pending
        }
    }

    /// Renova o token (dura um ano; cada renovação estende-o). 401/404: a TV foi desligada na conta.
    public func authRefresh() async throws -> String? {
        let (status, json, _) = try await send("POST", "api/collections/devices/auth-refresh", body: nil)
        guard status == 200 else { throw Failure.http(status) }
        return json?["token"]?.text
    }

    // MARK: Perfil GoatTV

    /// O `ETag` da última config lida.
    public private(set) var configETag: String?

    /// A config da conta; com `ifChanged`, nil (304) quando nada mudou desde a última leitura.
    public func config(ifChanged: Bool = false) async throws -> Config? {
        let etag = ifChanged ? configETag : nil
        let (status, json, response) = try await send("GET", "api/goattv/config", headers: etag.map { ["If-None-Match": $0] } ?? [:], body: nil)
        if status == 304 { return nil }
        guard status == 200 else { throw Failure.http(status) }
        guard let json else { throw Failure.badResponse }
        configETag = response.value(forHTTPHeaderField: "ETag")
        return Self.parseConfig(json)
    }

    /// Manda à conta o índice de grupos e canais de uma lista (ver ChannelIndex). Devolve o código HTTP.
    public func putPlaylistIndex(_ playlistId: String, _ index: ChannelIndex) async throws -> Int {
        let path = "api/goattv/playlists/\(playlistId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? playlistId)/index"
        var request = URLRequest(url: URL(string: path, relativeTo: Self.baseURL)!)
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = index.body()
        authorize(&request)
        let (_, response) = try await HTTP.fetch(session, request)
        checkSession(response.statusCode)
        return response.statusCode
    }

    public static func parseConfig(_ json: JSON) -> Config {
        Config(
            userId: json["user"]?["id"]?.text,
            email: json["user"]?["email"]?.text,
            deviceName: json["device"]?["name"]?.text,
            playlists: (json["playlists"]?.array ?? []).compactMap(Playlist.init(account:)),
            favorites: parseKeyLists(json["favorites"]),
            settings: json["settings"]?.object ?? [:],
            settingsRev: json["settings_rev"]?.int.map(Int64.init),
            raw: json,
            indexHashes: Dictionary((json["playlists"]?.array ?? []).compactMap { p -> (String, String?)? in
                guard let id = p["id"]?.text else { return nil }
                return (id, p["index_hash"]?.text)
            }, uniquingKeysWith: { a, _ in a })
        )
    }

    /// `favorites` e `recent_channels`: playlistId → channelKeys (sem repetidos).
    public static func parseKeyLists(_ json: JSON?) -> [String: [String]] {
        guard let object = json?.object else { return [:] }
        return object.mapValues { value in
            var seen = Set<String>()
            return value.strings.filter { seen.insert($0).inserted }
        }
    }

    /// Grava no perfil: `settings_merge` (com `settings_rev` opcional) ou `favorites`. 200 e 409 trazem o
    /// `settings` completo e o `settings_rev` novo.
    public func updateProfile(_ body: [String: JSON]) async throws -> (status: Int, body: JSON?) {
        let (status, json, _) = try await send("PATCH", "api/goattv/profile", body: body)
        return (status, json)
    }

    // MARK: Relatórios

    public enum ReportFailure: Error {
        case tooMany
        case http(Int)
    }

    /// Envia um relatório para a zona admin do site (com o token, fica ligado à conta) e devolve o id.
    public func sendReport(_ body: [String: JSON]) async throws -> String {
        let (status, json, _) = try await send("POST", "api/reports", body: body)
        if status == 429 { throw ReportFailure.tooMany }
        guard (200..<300).contains(status), let id = json?["id"]?.text else { throw ReportFailure.http(status) }
        return id
    }

    // MARK: Guia da conta

    /// XMLTV só com os canais associados da lista (ids = channelKeys). Com [etag], 304 sem corpo se nada mudou.
    public func epgGuide(playlistId: String, etag: String?) async throws -> (status: Int, data: Data, etag: String?) {
        var c = URLComponents(url: Self.baseURL.appendingPathComponent("api/goattv/epg/guide"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "playlist", value: playlistId)]
        var request = URLRequest(url: c.url!)
        request.timeoutInterval = 60
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        authorize(&request)
        let (data, response) = try await HTTP.fetch(session, request)
        checkSession(response.statusCode)
        return (response.statusCode, data, response.value(forHTTPHeaderField: "ETag"))
    }

    // MARK: Listas da conta (o site continua a ser o sítio recomendado: escreve-se melhor lá)

    /// Uma lista para a conta (os campos do outro tipo vão vazios, como no site).
    public static func playlistBody(_ p: Playlist) -> [String: JSON] {
        let xtream = p.type == .xtream
        return [
            "type": .string(xtream ? "xtream" : "m3u"),
            "name": .string(p.name),
            "server": .string(xtream ? p.server ?? "" : ""),
            "username": .string(xtream ? p.username ?? "" : ""),
            "password": .string(xtream ? p.password ?? "" : ""),
            "url": .string(xtream ? "" : p.url ?? ""),
            "epg_url": .string(p.epgUrl ?? ""),
        ]
    }

    /// O erro de gravar uma lista: o `code` e o `reason` que o servidor manda (em inglês).
    public struct PlaylistRejected: Error, Sendable {
        public var status: Int
        public var code: String?
        public var reason: String?
    }

    /// Cria ([id] nil) ou altera uma lista na conta; devolve-a como ficou.
    public func savePlaylist(id: String?, _ playlist: Playlist) async throws -> Playlist {
        let (status, json, _) = try await send(id == nil ? "POST" : "PATCH", id.map { "api/goattv/playlists/\($0)" } ?? "api/goattv/playlists",
                                               body: Self.playlistBody(playlist))
        guard (200..<300).contains(status), let json, let saved = Playlist(account: json) else {
            throw PlaylistRejected(status: status, code: json?["code"]?.text, reason: json?["params"]?["reason"]?.text)
        }
        return saved
    }

    /// Apaga a lista da conta (em todas as TVs). 404: já não existia.
    public func deletePlaylist(id: String) async throws {
        let (status, json, _) = try await send("DELETE", "api/goattv/playlists/\(id)", body: nil)
        guard (200..<300).contains(status) || status == 404 else {
            throw PlaylistRejected(status: status, code: json?["code"]?.text, reason: json?["params"]?["reason"]?.text)
        }
    }

    // MARK: Catálogo do guia (escolher o guia de cada canal)

    /// Canais de guia com esse nome, agrupados por país (o do utilizador primeiro).
    public func epgSearch(_ query: String) async throws -> [EpgCountryResult] {
        let (status, json, _) = try await send("GET", "api/goattv/epg/search", query: [("q", query)], body: nil)
        guard status == 200 else { throw Failure.http(status) }
        return (json?.array ?? []).map(EpgCountryResult.init(json:))
    }

    /// Até 5 canais de guia prováveis para um canal da lista.
    public func epgSuggest(playlistId: String, channelKey: String, name: String) async throws -> [EpgGuideChannel] {
        let (status, json, _) = try await send("GET", "api/goattv/epg/suggest",
                                               query: [("playlist", playlistId), ("channel_key", channelKey), ("name", name)], body: nil)
        guard status == 200 else { throw Failure.http(status) }
        return (json?.array ?? []).map(EpgGuideChannel.init(json:))
    }

    public func epgCountries() async throws -> [EpgCountryInfo] {
        let (status, json, _) = try await send("GET", "api/goattv/epg/countries", body: nil)
        guard status == 200 else { throw Failure.http(status) }
        return (json?.array ?? []).map(EpgCountryInfo.init(json:))
    }

    public func epgCountryChannels(_ country: String) async throws -> EpgCountryResult {
        let (status, json, _) = try await send("GET", "api/goattv/epg/countries/\(country)/channels", body: nil)
        guard status == 200, let json else { throw Failure.http(status) }
        return EpgCountryResult(json: json)
    }

    /// channelKey → canal de guia associado (404: a lista ainda não tem nenhum).
    public func epgMappings(playlistId: String) async throws -> [String: EpgMapping] {
        let (status, json, _) = try await send("GET", "api/goattv/epg/mappings", query: [("playlist", playlistId)], body: nil)
        if status == 404 { return [:] }
        guard status == 200 else { throw Failure.http(status) }
        return (json?.object ?? [:]).mapValues(EpgMapping.init(json:))
    }

    public func putEpgMapping(playlistId: String, channelKey: String, country: String, channelId: String, channelName: String) async throws {
        let (status, _, _) = try await send("PUT", "api/goattv/epg/mappings", body: [
            "playlist": .string(playlistId), "channel_key": .string(channelKey), "country": .string(country),
            "channel_id": .string(channelId), "channel_name": .string(channelName),
        ])
        guard (200..<300).contains(status) else { throw Failure.http(status) }
    }

    /// 404: já não estava associado (o resultado é o mesmo).
    public func deleteEpgMapping(playlistId: String, channelKey: String) async throws {
        let (status, _, _) = try await send("DELETE", "api/goattv/epg/mappings", query: [("playlist", playlistId), ("channel_key", channelKey)], body: nil)
        guard (200..<300).contains(status) || status == 404 else { throw Failure.http(status) }
    }

    // MARK: Pedidos

    private func send(_ method: String, _ path: String, query: [(String, String)] = [], headers: [String: String] = [:], body: [String: JSON]?) async throws -> (Int, JSON?, HTTPURLResponse) {
        var components = URLComponents(url: Self.baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query.map { URLQueryItem(name: $0.0, value: $0.1) } }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = JSON.object(body).encoded()
        }
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        authorize(&request)
        let (data, response) = try await HTTP.fetch(session, request)
        checkSession(response.statusCode)
        let json = data.isEmpty ? nil : try? JSON.parse(data)
        return (response.statusCode, json, response)
    }

    /// `goattv/<versão> (apple-tv)`: diz ao site a app, a versão e o tipo de TV (aparece em Dispositivos).
    public var client: String?

    private func authorize(_ request: inout URLRequest) {
        if let client { request.setValue(client, forHTTPHeaderField: "X-Goat-Client") }
        if let token { request.setValue(token, forHTTPHeaderField: "Authorization") }
    }

    private func checkSession(_ status: Int) {
        if status == 401, token != nil { onSessionEnded?() }
    }
}
