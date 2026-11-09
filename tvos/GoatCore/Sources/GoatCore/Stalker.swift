import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Portais Stalker / MAC, só para TV em direto e só para listas sem conta (como o StalkerSource do Android):
/// os pedidos saem deste aparelho, que se apresenta como uma MAG250.
public enum Stalker {
    public static let userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    /// Um MAC novo com o prefixo das MAG, que os portais esperam: as apps não têm acesso ao MAC verdadeiro.
    public static func generateMac() -> String {
        "00:1A:79:" + (0..<3).map { _ in String(format: "%02X", UInt8.random(in: 0...255)) }.joined(separator: ":")
    }

    /// O MAC como `AA:BB:CC:DD:EE:FF` (aceita traços ou sem separadores), ou nil se não for um MAC.
    public static func normalizeMac(_ mac: String) -> String? {
        let hex = Array(mac.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ":", with: "").replacingOccurrences(of: "-", with: "").uppercased())
        guard hex.count == 12, hex.allSatisfy({ "0123456789ABCDEF".contains($0) }) else { return nil }
        return stride(from: 0, to: 12, by: 2).map { String(hex[$0...($0 + 1)]) }.joined(separator: ":")
    }

    /// Onde o portal pode responder, pela ordem a tentar.
    public static func endpoints(_ server: String) -> [String] {
        var s = server.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        if s.hasSuffix("/c") { s.removeLast(2) }
        if s.split(separator: "?", omittingEmptySubsequences: false)[0].hasSuffix(".php") { return [s] }
        if let r = s.range(of: "/stalker_portal") { return [s[..<r.lowerBound] + "/stalker_portal/server/load.php"] }
        return [s + "/portal.php", s + "/server/load.php", s + "/stalker_portal/server/load.php"]
    }

    /// Nos `/stalker_portal/`, MAC codificado e token na cookie (menos no `get_profile`); nos outros, MAC tal como está e token sempre.
    public static func cookie(mac: String, token: String, stalkerPortal: Bool, action: String, timezone: String) -> String {
        var c = "mac=" + (stalkerPortal ? mac.replacingOccurrences(of: ":", with: "%3A") : mac) + "; stb_lang=en; timezone=" + timezone
        if !token.isEmpty, !stalkerPortal || action != "get_profile" { c += "; token=" + token }
        return c
    }

    /// Só os comandos `/ch/` são pedidos ao portal; os outros já são o link.
    public static func needsLink(_ cmd: String) -> Bool { cmd.contains("/ch/") }

    /// O URL dentro de um comando (`ffmpeg http://…`). Sem host, ou com localhost, aponta para o próprio portal.
    public static func commandURL(_ cmd: String, portal: String) -> String {
        let url = String(cmd.trimmingCharacters(in: .whitespaces).split(separator: " ").last ?? "")
        var origin = ""
        if let p = URL(string: portal), let scheme = p.scheme, let host = p.host {
            origin = scheme + "://" + host + (p.port.map { ":\($0)" } ?? "")
        }
        guard let scheme = url.range(of: "://") else {
            return origin + "/" + url.drop { $0 == "/" }
        }
        let rest = url[scheme.upperBound...]
        let host = rest.prefix { $0 != "/" && $0 != ":" }
        guard host == "localhost" || host == "127.0.0.1" else { return url }
        return origin + rest.drop { $0 != "/" }
    }

    public static func candidate(_ url: String) -> StreamCandidate {
        let path = url.split(separator: "?", omittingEmptySubsequences: false)[0].lowercased()
        return StreamCandidate(url: url, format: path.hasSuffix(".m3u8") ? StreamFormat.hls : StreamFormat.ts)
    }

    /// O que identifica a "box" no portal: o que a pessoa escreveu ou, sem isso, o calculado a partir do MAC.
    public struct Identity: Equatable, Sendable {
        public var mac, serial, deviceId, deviceId2, signature: String

        public init(_ p: Playlist) {
            func given(_ s: String?) -> String? { s.flatMap { $0.trimmed.isEmpty ? nil : $0.trimmed } }
            func hash(_ s: String) -> String { SHA.hex(SHA.sha256(Array(s.utf8))).uppercased() }
            mac = (p.username ?? "").trimmed.uppercased()
            serial = given(p.serial) ?? String(SHA.hex(SHA.md5(Array(mac.utf8))).uppercased().prefix(13))
            deviceId = given(p.deviceId) ?? hash(mac)
            deviceId2 = given(p.deviceId2) ?? deviceId
            signature = given(p.signature) ?? hash(serial + mac)
        }
    }

    /// O nome dentro de um separador (`✦●✦ DESPORTO ✦●✦`, `=== UK ===`), ou nil se for um canal normal.
    public static func sectionTitle(_ name: String) -> String? {
        let chars = Array(name.trimmingCharacters(in: .whitespaces))
        func text(_ c: Character) -> Bool { c.isLetter || c.isNumber }
        guard let first = chars.firstIndex(where: text), let last = chars.lastIndex(where: text) else { return nil }
        let edges = chars[..<first] + chars[(last + 1)...]
        guard first >= 2, chars.count - last - 1 >= 2, !edges.contains(where: { "()[]{}".contains($0) }) else { return nil }
        return String(chars[first...last])
    }

    /// Portais sem géneros: os separadores da lista passam a grupos e deixam de contar como canais.
    public static func groupBySections(_ channels: [Channel]) -> ([Channel], [Category]) {
        guard channels.filter({ sectionTitle($0.name) != nil }).count >= 2 else { return (channels, []) }
        var sections: [Category] = []
        var current = ""
        var grouped: [Channel] = []
        for c in channels {
            if let title = sectionTitle(c.name) {
                current = title
                if !sections.contains(where: { $0.id == title }) { sections.append(Category(id: title, name: title)) }
            } else {
                var channel = c
                channel.categoryId = current
                channel.uid = grouped.count
                grouped.append(channel)
            }
        }
        let used = Set(grouped.map(\.categoryId))
        return (grouped, sections.filter { used.contains($0.id) })
    }

    static func parseChannels(_ raw: [JSON], endpoint: String, channelName: (String) -> String) -> [Channel] {
        var result: [Channel] = []
        for c in raw {
            guard let id = c["id"]?.text, !id.isEmpty, let cmd = c["cmd"]?.text ?? c["cmds"]?.array?.first?["url"]?.text, !cmd.isEmpty else { continue }
            let i = result.count
            let logo = c["logo"]?.text
            result.append(Channel(
                uid: i, key: id, num: c["number"]?.text.flatMap { SafeInput.int(text: $0) } ?? i + 1,
                name: c["name"]?.text.flatMap { $0.trimmed.isEmpty ? nil : $0.trimmed } ?? channelName(id),
                logo: logo.flatMap { $0.contains("://") ? $0 : $0.hasPrefix("/") ? commandURL($0, portal: endpoint) : nil },
                categoryId: c["tv_genre_id"]?.text ?? "", streamId: id, url: cmd, userAgent: userAgent,
                epgId: c["xmltv_id"]?.text.flatMap { $0.trimmed.isEmpty ? nil : $0.trimmed },
                archiveDays: archiveDays(c)
            ))
        }
        return result
    }

    /// Dias de arquivo de um canal (como no Android): o portal marca-o (`tv_archive`, `enable_tv_archive` ou
    /// `archive` a 1) e diz `tv_archive_duration`, que nuns portais vem em horas (o Ministra traz 168, uma semana) e
    /// noutros em dias. Como o QiTV e o E2iPlayer: mais de 31, ou um múltiplo de 24, são horas; marcado sem duração,
    /// uma semana.
    public static func archiveDays(_ c: JSON) -> Int {
        let marked = ["tv_archive", "enable_tv_archive", "archive"].contains { key in
            let t = c[key]?.text
            return t == "1" || t?.lowercased() == "true"
        }
        guard marked else { return 0 }
        guard let value = c["tv_archive_duration"]?.text.flatMap({ Double($0.trimmed) }), value.isFinite, value > 0 else { return 7 }
        let days = value > 31 || value.truncatingRemainder(dividingBy: 24) == 0 ? (value / 24).rounded(.up) : value.rounded(.up)
        return min(31, max(1, Int(days)))
    }

    /// Um programa da tabela do portal com gravação.
    public struct ArchiveProgramme: Equatable, Sendable {
        public var id: String
        public var startMs: Int64
        public var stopMs: Int64
    }

    /// Uma linha da tabela do portal com gravação (sem `mark_archive` 0) e id, ou nil.
    static func recorded(_ row: JSON) -> ArchiveProgramme? {
        guard let start = row["start_timestamp"]?.text.flatMap({ Int64($0) }).flatMap({ SafeInput.epochMs(seconds: $0) }),
              let stopSeconds = row["stop_timestamp"]?.text.flatMap({ Int64($0) }), stopSeconds > 0,
              let stop = SafeInput.epochMs(seconds: stopSeconds), stop > start, row["mark_archive"]?.text != "0",
              let id = row["id"]?.text ?? row["real_id"]?.text else { return nil }
        return ArchiveProgramme(id: id, startMs: start, stopMs: stop)
    }

    /// Até quanto o início de um programa pode variar entre o guia XMLTV e a tabela do portal.
    static let driftMs: Int64 = 5 * 60_000

    /// O programa do portal para o do guia ([startMs]–[endMs]) às [wantedMs]: o que dá a essa hora ou começa até
    /// [driftMs] depois, o que mais se sobrepõe ao do guia. Assim, se o guia diz 20:00 e o portal 20:01, abrir o
    /// programa desde o início dá a gravação dele e não o último minuto do anterior.
    static func matching(_ rows: [ArchiveProgramme], wantedMs: Int64, startMs: Int64, endMs: Int64) -> ArchiveProgramme? {
        let near = rows.filter { ($0.startMs <= wantedMs && wantedMs < $0.stopMs) || ($0.startMs > wantedMs && $0.startMs <= wantedMs + driftMs) }
        func overlap(_ p: ArchiveProgramme) -> Int64 { min(p.stopMs, endMs) - max(p.startMs, startMs) }
        // Como o maxWith do Kotlin: em empate fica a primeira; a seguir, a que já dá às [wantedMs].
        var best: ArchiveProgramme?
        for p in near {
            guard let b = best else { best = p; continue }
            let (op, ob) = (overlap(p), overlap(b))
            if op > ob || (op == ob && (p.startMs <= wantedMs) && !(b.startMs <= wantedMs)) { best = p }
        }
        return best
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
        let base = endpoint.components(separatedBy: "/server/load.php")[0]
        request.setValue((base == endpoint ? String(endpoint[..<(endpoint.lastIndex(of: "/") ?? endpoint.endIndex)]) : base) + "/c/", forHTTPHeaderField: "Referer")
        request.setValue(Stalker.cookie(mac: identity.mac, token: token, stalkerPortal: endpoint.contains("/stalker_portal/"), action: action, timezone: TimeZone.current.identifier), forHTTPHeaderField: "Cookie")
        request.httpShouldHandleCookies = false
        if !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let (data, response) = try await HTTP.fetch(urlSession, request, maxBytes: SafeInput.maxPlaylistBytes)
        if response.statusCode == 401 || String(decoding: data, as: UTF8.self).range(of: "Authorization failed", options: .caseInsensitive) != nil { throw ExpiredToken() }
        guard (200..<300).contains(response.statusCode) else { throw SourceError.http(response.statusCode) }
        guard let js = (try? JSON.parse(data))?["js"] else { throw SourceError.badResponse }
        return js
    }

    private static func profileParams(_ id: Stalker.Identity, random: String) -> [(String, String)] {
        [("hd", "1"), ("ver", "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.1.0; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566"),
         ("num_banks", "2"), ("sn", id.serial), ("stb_type", "MAG250"), ("image_version", "218"), ("video_out", "hdmi"),
         ("device_id", id.deviceId), ("device_id2", id.deviceId2), ("signature", id.signature), ("auth_second_step", "1"),
         ("hw_version", "1.7-BD-" + SHA.hex(SHA.md5(Array(id.mac.utf8))).uppercased().prefix(2)), ("hw_version_2", SHA.hex(SHA.md5(Array((id.serial + id.mac).lowercased().utf8)))),
         ("client_type", "STB"), ("metrics", "{\"mac\":\"\(id.mac)\",\"sn\":\"\(id.serial)\",\"model\":\"MAG250\",\"type\":\"STB\",\"uid\":\"\",\"random\":\"\(random)\"}"), ("not_valid_token", "0"), ("api_signature", "263"), ("prehash", "0"),
         ("timestamp", String(Int(Date().timeIntervalSince1970))), ("random", random)]
    }
}
