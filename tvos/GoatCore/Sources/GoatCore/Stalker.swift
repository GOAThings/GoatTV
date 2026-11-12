import Foundation
import GoatShared
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Portais Stalker / MAC, só para TV em direto e só para listas sem conta (como o StalkerSource do Android):
/// os pedidos saem deste aparelho, que se apresenta como uma MAG250. As regras são as do núcleo partilhado
/// (StalkerRules); aqui ficam os pedidos e as sessões.
public enum Stalker {
    public static let userAgent = StalkerRules.shared.USER_AGENT

    /// Um MAC novo com o prefixo das MAG, que os portais esperam: as apps não têm acesso ao MAC verdadeiro.
    public static func generateMac() -> String {
        "00:1A:79:" + (0..<3).map { _ in String(format: "%02X", UInt8.random(in: 0...255)) }.joined(separator: ":")
    }

    /// O MAC como `AA:BB:CC:DD:EE:FF` (aceita traços ou sem separadores), ou nil se não for um MAC.
    public static func normalizeMac(_ mac: String) -> String? { StalkerRules.shared.normalizeMac(mac: mac) }

    /// Onde o portal pode responder, pela ordem a tentar.
    public static func endpoints(_ server: String) -> [String] { StalkerRules.shared.endpoints(server: server) }

    /// Nos `/stalker_portal/`, MAC codificado e token na cookie (menos no `get_profile`); nos outros, MAC tal como está e token sempre.
    public static func cookie(mac: String, token: String, stalkerPortal: Bool, action: String, timezone: String) -> String {
        StalkerRules.shared.cookie(mac: mac, token: token, stalkerPortal: stalkerPortal, action: action, timezone: timezone)
    }

    /// Só os comandos `/ch/` são pedidos ao portal; os outros já são o link.
    public static func needsLink(_ cmd: String) -> Bool { StalkerRules.shared.needsLink(cmd: cmd) }

    /// O URL dentro de um comando (`ffmpeg http://…`). Sem host, ou com localhost, aponta para o próprio portal.
    public static func commandURL(_ cmd: String, portal: String) -> String { StalkerRules.shared.commandUrl(cmd: cmd, portal: portal) }

    public static func candidate(_ url: String) -> StreamCandidate {
        let c = StalkerRules.shared.candidate(url: url)
        return StreamCandidate(url: c.url, format: c.format)
    }

    /// O que identifica a "box" no portal: o que a pessoa escreveu ou, sem isso, o calculado a partir do MAC.
    public struct Identity: Equatable, Sendable {
        public var mac, serial, deviceId, deviceId2, signature: String

        public init(_ p: Playlist) {
            let id = StalkerRules.shared.identity(username: p.username, serial: p.serial, deviceId: p.deviceId, deviceId2: p.deviceId2,
                                                  signature: p.signature)
            mac = id.mac
            serial = id.serial
            deviceId = id.deviceId
            deviceId2 = id.deviceId2
            signature = id.signature
        }

        var shared: StalkerIdentity {
            StalkerIdentity(mac: mac, serial: serial, deviceId: deviceId, deviceId2: deviceId2, signature: signature)
        }
    }

    /// O nome dentro de um separador (`✦●✦ DESPORTO ✦●✦`, `=== UK ===`), ou nil se for um canal normal.
    public static func sectionTitle(_ name: String) -> String? { StalkerRules.shared.sectionTitle(name: name) }

    /// Portais sem géneros: os separadores da lista passam a grupos e deixam de contar como canais.
    public static func groupBySections(_ channels: [Channel]) -> ([Channel], [Category]) {
        let s = StalkerRules.shared.groupBySections(channels: channels.map(\.shared))
        return (s.channels.map { Channel($0) }, s.categories.map { Category(id: $0.id, name: $0.name) })
    }

    /// Os campos [keys] de um objeto do portal em texto, e o `url` do primeiro de `cmds` (o que o núcleo partilhado lê).
    static func fields(_ c: JSON, _ keys: Set<String>) -> [String: String] {
        var out: [String: String] = [:]
        for key in keys { if let t = c[key]?.text { out[key] = t } }
        if let url = c["cmds"]?.array?.first?["url"]?.text { out[StalkerRules.shared.CMDS_URL] = url }
        return out
    }

    static func parseChannels(_ raw: [JSON], endpoint: String, channelName: (String) -> String) -> [Channel] {
        var result: [Channel] = []
        for c in raw {
            // O nome dos canais sem nome dá-se aqui: o Kotlin guarda as funções que recebe, e esta não pode sair daqui.
            guard let ch = StalkerRules.shared.channel(c: fields(c, StalkerRules.shared.CHANNEL_KEYS), uid: Int32(clamping: result.count),
                                                       endpoint: endpoint, channelName: { _ in "" }) else { continue }
            var channel = Channel(ch)
            if channel.name.isEmpty { channel.name = channelName(channel.key) }
            result.append(channel)
        }
        return result
    }

    /// Dias de arquivo de um canal (a regra do núcleo partilhado: horas ou dias, marcado sem duração dá uma semana).
    public static func archiveDays(_ c: JSON) -> Int {
        Int(StalkerRules.shared.archiveDays(c: fields(c, ["tv_archive", "enable_tv_archive", "archive", "tv_archive_duration"])))
    }

    /// Um programa da tabela do portal com gravação.
    public struct ArchiveProgramme: Equatable, Sendable {
        public var id: String
        public var startMs: Int64
        public var stopMs: Int64
    }

    /// Uma linha da tabela do portal com gravação (sem `mark_archive` 0) e id, ou nil.
    static func recorded(_ row: JSON) -> ArchiveProgramme? {
        StalkerRules.shared.recorded(row: fields(row, ["start_timestamp", "stop_timestamp", "mark_archive", "id", "real_id"]))
            .map { ArchiveProgramme(id: $0.id, startMs: $0.startMs, stopMs: $0.stopMs) }
    }

    /// O programa do portal para o do guia ([startMs]–[endMs]) às [wantedMs]: o que dá a essa hora ou começa até
    /// 5 minutos depois, o que mais se sobrepõe ao do guia (ver o StalkerRules.matching).
    static func matching(_ rows: [ArchiveProgramme], wantedMs: Int64, startMs: Int64, endMs: Int64) -> ArchiveProgramme? {
        StalkerRules.shared.matching(rows: rows.map { StalkerProgramme(id: $0.id, startMs: $0.startMs, stopMs: $0.stopMs) },
                                     wantedMs: wantedMs, startMs: startMs, endMs: endMs)
            .map { ArchiveProgramme(id: $0.id, startMs: $0.startMs, stopMs: $0.stopMs) }
    }
}

/// As sessões abertas nos portais (o token fica em memória; entra-se de novo se caducar).
public actor StalkerClient {
    private struct Session { var endpoint: String; var token: String; var identity: Stalker.Identity }
    private struct ExpiredToken: Error {}
    private static let maxPages = 200

    private let urlSession: URLSession
    private var sessions: [String: Session] = [:]

    public init(session: URLSession) { urlSession = session }

    public func load(_ playlist: Playlist, channelName: @Sendable (String) -> String) async throws -> PlaylistContent {
        sessions[playlist.id] = nil
        let genres = try await api(playlist, "itv", "get_genres").array ?? []
        let categories = genres.compactMap { g -> Category? in
            guard let id = g["id"]?.text, !id.isEmpty, id != "*" else { return nil }
            return Category(id: id, name: g["title"]?.text ?? id)
        }
        var seen = Set<String>()
        var raw: [JSON] = []
        func add(_ list: [JSON]) {
            for c in list { if let id = c["id"]?.text, seen.insert(id).inserted { raw.append(c) } }
        }
        func data(_ js: JSON) -> [JSON] { js["data"]?.array ?? js.array ?? [] }
        add(data(try await api(playlist, "itv", "get_all_channels", [("force_ch_link_check", ""), ("include_censored", "1")])))
        // Os géneros adultos muitas vezes só dão os canais página a página.
        for genre in genres where genre["censored"]?.text == "1" {
            guard let id = genre["id"]?.text else { continue }
            for page in 1...Self.maxPages {
                let js = try await api(playlist, "itv", "get_ordered_list", [("genre", id), ("force_ch_link_check", ""), ("fav", "0"), ("sortby", "number"), ("hd", "0"), ("p", String(page))])
                let list = data(js)
                add(list)
                let total = js["total_items"]?.text.flatMap { Int($0) } ?? 0
                let perPage = js["max_page_items"]?.text.flatMap { Int($0) } ?? list.count
                if list.isEmpty || (SafeInput.product(page, perPage) ?? total) >= total { break }
            }
        }
        let channels = Stalker.parseChannels(raw, endpoint: sessions[playlist.id]?.endpoint ?? "", channelName: channelName)
        let (grouped, sections) = categories.isEmpty ? Stalker.groupBySections(channels) : (channels, [])
        return PlaylistContent.build(playlist: playlist, categories: categories + sections, channels: grouped,
                                     guideUrl: playlist.epgUrl.flatMap { $0.trimmed.isEmpty ? nil : $0 })
    }

    /// O link de [channel] neste momento (nil se o portal não o der: o player mostra o erro e volta a tentar).
    public func candidates(_ playlist: Playlist, channel: Channel) async -> [StreamCandidate]? {
        guard let cmd = channel.url, let session = try? await session(playlist) else { return nil }
        var link = cmd
        if Stalker.needsLink(cmd) {
            let js = try? await api(playlist, "itv", "create_link", [("cmd", cmd), ("series", ""), ("forced_storage", "undefined"), ("disable_ad", "0"), ("download", "0")])
            guard let answer = js?["cmd"]?.text, !answer.isEmpty else { return nil }
            link = answer
        }
        return [Stalker.candidate(Stalker.commandURL(link, portal: session.endpoint))]
    }

    /// Onde a gravação de um programa começa (ms desde 1970) e como abri-la.
    public struct Recording: Sendable {
        public var startMs: Int64
        public var candidate: StreamCandidate
    }

    /// A gravação do programa [startMs]–[endMs] de [channel] (visto às [wantedMs]), no arquivo do portal: o portal não
    /// faz URLs com horas, dá o link da gravação de um programa do seu guia (`type=tv_archive&action=create_link`, com
    /// o comando `auto /media/<id do programa>.mpg`). O id procura-se na tabela do dia (`get_simple_data_table`).
    /// nil sem gravação, ou se o portal não respondeu.
    public func archive(_ playlist: Playlist, channel: Channel, wantedMs: Int64, startMs: Int64, endMs: Int64) async -> Recording? {
        guard let id = channel.streamId,
              let programme = try? await recordingAt(playlist, channelId: id, wantedMs: wantedMs, startMs: startMs, endMs: endMs),
              let js = try? await api(playlist, "tv_archive", "create_link", [("cmd", "auto /media/\(programme.id).mpg"), ("series", ""),
                                                                              ("forced_storage", ""), ("disable_ad", "0"), ("download", "0")]) else { return nil }
        // "nothing_to_play" (ou outro erro) e um comando vazio: não há gravação.
        if js["error"]?.text.map({ !$0.isEmpty }) == true { return nil }
        guard let cmd = js["cmd"]?.text, !cmd.isEmpty else { return nil }
        let url = Stalker.commandURL(cmd, portal: (try? await session(playlist))?.endpoint ?? "")
        guard url.hasPrefix("http://") || url.hasPrefix("https://") else { return nil }
        return Recording(startMs: programme.startMs, candidate: Stalker.candidate(url))
    }

    /// A tabela do dia vem às páginas (de 10, nos portais Ministra): um dia cheio cabe em 30.
    private static let maxEpgPages = 30

    /// O programa da tabela do portal que corresponde ao do guia, visto às [wantedMs] (o dia vem no fuso deste
    /// aparelho, que vai na cookie). Perto da meia-noite, o programa pode estar na tabela do dia anterior.
    private func recordingAt(_ playlist: Playlist, channelId: String, wantedMs: Int64, startMs: Int64, endMs: Int64) async throws -> Stalker.ArchiveProgramme? {
        let format = DateFormatter()
        format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = .current
        format.dateFormat = "yyyy-MM-dd"
        for dayMs in [wantedMs, wantedMs - 86_400_000] {
            let date = format.string(from: Date(timeIntervalSince1970: Double(dayMs) / 1000))
            var rows: [Stalker.ArchiveProgramme] = []
            for page in 1...Self.maxEpgPages {
                let js = try await api(playlist, "epg", "get_simple_data_table", [("ch_id", channelId), ("date", date), ("p", String(page))])
                let list = js["data"]?.array ?? js.array ?? []
                rows += list.compactMap(Stalker.recorded)
                let total = js["total_items"]?.text.flatMap { Int($0) } ?? 0
                let perPage = js["max_page_items"]?.text.flatMap { Int($0) }.flatMap { $0 > 0 ? $0 : nil } ?? list.count
                if list.isEmpty || page * perPage >= total { break }
            }
            if let found = Stalker.matching(rows, wantedMs: wantedMs, startMs: startMs, endMs: endMs) { return found }
        }
        return nil
    }

    public func shortEpg(_ playlist: Playlist, streamId: String, size: Int = 4) async throws -> [EpgEntry] {
        let js = try await api(playlist, "itv", "get_short_epg", [("ch_id", streamId), ("size", String(size))])
        return (js.array ?? []).compactMap { e in
            guard let start = e["start_timestamp"]?.text.flatMap({ Int64($0) }).flatMap({ SafeInput.epochMs(seconds: $0) }),
                  let stop = e["stop_timestamp"]?.text.flatMap({ Int64($0) }).flatMap({ SafeInput.epochMs(seconds: $0) }) else { return nil }
            return EpgEntry(title: e["name"]?.text ?? "", startMs: start, endMs: stop)
        }
    }

    private func api(_ playlist: Playlist, _ type: String, _ action: String, _ params: [(String, String)] = []) async throws -> JSON {
        let s = try await session(playlist)
        do {
            return try await call(s.endpoint, s.identity, s.token, type, action, params)
        } catch is ExpiredToken {
            sessions[playlist.id] = nil
            let again = try await session(playlist)
            do {
                return try await call(again.endpoint, again.identity, again.token, type, action, params)
            } catch is ExpiredToken {
                throw SourceError.badLogin
            }
        }
    }

    private func session(_ playlist: Playlist) async throws -> Session {
        if let s = sessions[playlist.id] { return s }
        guard let server = playlist.server, URL(string: server)?.host != nil else { throw SourceError.badURL }
        let identity = Stalker.Identity(playlist)
        var failure: Error = SourceError.badResponse
        for endpoint in Stalker.endpoints(server) {
            do {
                let hello = try await call(endpoint, identity, "", "stb", "handshake", [("token", "")])
                guard let token = hello["token"]?.text, !token.isEmpty else { throw SourceError.badLogin }
                let profile = try await call(endpoint, identity, token, "stb", "get_profile", Self.profileParams(identity, random: hello["random"]?.text ?? ""))
                if profile["status"]?.text == "1" { throw SourceError.badLogin }
                let s = Session(endpoint: endpoint, token: profile["token"]?.text.flatMap { $0.isEmpty ? nil : $0 } ?? token, identity: identity)
                sessions[playlist.id] = s
                return s
            } catch SourceError.badLogin {
                throw SourceError.badLogin
            } catch is ExpiredToken {
                throw SourceError.badLogin
            } catch {
                failure = error
            }
        }
        throw failure
    }

    private func call(_ endpoint: String, _ identity: Stalker.Identity, _ token: String, _ type: String, _ action: String, _ params: [(String, String)]) async throws -> JSON {
        guard var parts = URLComponents(string: endpoint) else { throw SourceError.badURL }
        parts.queryItems = (parts.queryItems ?? []) + ([("type", type), ("action", action)] + params + [("JsHttpRequest", "1-xml")]).map { URLQueryItem(name: $0.0, value: $0.1) }
        guard let url = parts.url else { throw SourceError.badURL }
        var request = URLRequest(url: url)
        request.setValue(Stalker.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("Model: MAG250; Link: WiFi", forHTTPHeaderField: "X-User-Agent")
        request.setValue(StalkerRules.shared.referer(endpoint: endpoint), forHTTPHeaderField: "Referer")
        request.setValue(Stalker.cookie(mac: identity.mac, token: token, stalkerPortal: StalkerRules.shared.isStalkerPortal(endpoint: endpoint), action: action, timezone: TimeZone.current.identifier), forHTTPHeaderField: "Cookie")
        request.httpShouldHandleCookies = false
        if !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let (data, response) = try await HTTP.fetch(urlSession, request, maxBytes: SafeInput.maxPlaylistBytes)
        if response.statusCode == 401 || String(decoding: data, as: UTF8.self).range(of: "Authorization failed", options: .caseInsensitive) != nil { throw ExpiredToken() }
        guard (200..<300).contains(response.statusCode) else { throw SourceError.http(response.statusCode) }
        guard let js = (try? JSON.parse(data))?["js"] else { throw SourceError.badResponse }
        return js
    }

    private static func profileParams(_ id: Stalker.Identity, random: String) -> [(String, String)] {
        StalkerRules.shared.profileParams(id: id.shared, random: random, nowSeconds: Int64(Date().timeIntervalSince1970)).map { ($0[0], $0[1]) }
    }
}
