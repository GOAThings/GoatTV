import Foundation

/// Endereços e respostas da API Xtream Codes (só TV em direto), como no GoatTV Android.
public enum Xtream {
    public struct Login: Sendable {
        public var auth: String?
        public var status: String?
        public var activeConnections: Int?
        public var maxConnections: Int?
        public var allowedFormats: Set<String>
        public var timezone: String?
    }

    // MARK: Endereços

    /// O servidor com `http://` se faltar, sem barra no fim do caminho.
    public static func baseURL(_ playlist: Playlist) throws -> URLComponents {
        let raw = playlist.server?.trimmingCharacters(in: .whitespaces) ?? ""
        let withScheme = raw.hasPrefix("http://") || raw.hasPrefix("https://") ? raw : "http://" + raw
        guard var c = URLComponents(string: withScheme), let host = c.host, !host.isEmpty else {
            throw SourceError.badURL
        }
        while c.percentEncodedPath.hasSuffix("/") { c.percentEncodedPath.removeLast() }
        c.query = nil
        c.fragment = nil
        return c
    }

    public static func apiURL(_ playlist: Playlist, action: String? = nil, extra: [(String, String)] = []) throws -> URL {
        var c = try baseURL(playlist)
        c.percentEncodedPath += "/player_api.php"
        var items: [(String, String)] = [("username", playlist.username ?? ""), ("password", playlist.password ?? "")]
        if let action { items.append(("action", action)) }
        items += extra
        c.percentEncodedQuery = items.map { "\(encodeQuery($0.0))=\(encodeQuery($0.1))" }.joined(separator: "&")
        guard let url = c.url else { throw SourceError.badURL }
        return url
    }

    public static func streamURL(_ playlist: Playlist, streamId: String, ext: String) -> String? {
        guard var c = try? baseURL(playlist) else { return nil }
        c.percentEncodedPath += "/live/" + [playlist.username ?? "", playlist.password ?? "", "\(streamId).\(ext)"]
            .map(encodeSegment).joined(separator: "/")
        return c.url?.absoluteString
    }

    /// Guia XMLTV completo da conta (`xmltv.php`).
    public static func xmltvURL(_ playlist: Playlist) -> String? {
        guard var c = try? baseURL(playlist) else { return nil }
        c.percentEncodedPath += "/xmltv.php"
        c.percentEncodedQuery = "username=\(encodeQuery(playlist.username ?? ""))&password=\(encodeQuery(playlist.password ?? ""))"
        return c.url?.absoluteString
    }

    /// Catch-up de [streamId] a partir de [startMs], durante [minutes] minutos, com a hora no fuso do servidor.
    /// Há duas formas na API Xtream; os servidores aceitam uma ou as duas, por isso tentam-se ambas.
    public static func catchupURLs(_ playlist: Playlist, streamId: String, startMs: Int64, minutes: Int, timezone: String?) -> [String] {
        guard let base = try? baseURL(playlist) else { return [] }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timezone.flatMap { TimeZone(identifier: $0) } ?? TimeZone.current
        formatter.dateFormat = "yyyy-MM-dd:HH-mm"
        let start = formatter.string(from: Date(timeIntervalSince1970: Double(startMs) / 1000))
        let user = playlist.username ?? ""
        let pass = playlist.password ?? ""
        var path = base
        path.percentEncodedPath += "/timeshift/" + [user, pass, String(minutes), start, "\(streamId).ts"].map(encodeSegment).joined(separator: "/")
        var php = base
        php.percentEncodedPath += "/streaming/timeshift.php"
        php.percentEncodedQuery = [("username", user), ("password", pass), ("stream", streamId), ("start", start), ("duration", String(minutes))]
            .map { "\(encodeQuery($0.0))=\(encodeQuery($0.1))" }.joined(separator: "&")
        return [path.url?.absoluteString, php.url?.absoluteString].compactMap { $0 }
    }

    // MARK: Respostas

    public static func parseLogin(_ data: Data) -> Login? {
        guard let json = try? JSON.parse(data), let info = json["user_info"] else { return nil }
        var formats = Set<String>()
        switch info["allowed_output_formats"] {
        case .array(let values)?: values.compactMap(\.text).forEach { formats.insert($0) }
        case .string(let s)?: s.split(separator: ",").forEach { formats.insert(String($0)) }
        default: break
        }
        formats = Set(formats.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty })
        let tz = json["server_info"]?["timezone"]?.text
        return Login(
            auth: info["auth"]?.text,
            status: info["status"]?.text,
            activeConnections: info["active_cons"]?.text.flatMap { Int($0) },
            maxConnections: info["max_connections"]?.text.flatMap { Int($0) },
            allowedFormats: formats,
            timezone: (tz?.isEmpty ?? true) ? nil : tz
        )
    }

    public static func parseCategories(_ data: Data) throws -> [Category] {
        guard let list = try parseList(data) else { return [] }
        return list.compactMap { c in
            guard let id = c["category_id"]?.text, !id.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            let name = c["category_name"]?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return Category(id: id, name: name.isEmpty ? id : name)
        }
    }

    public static func parseStreams(_ data: Data, channelName: (String) -> String = { "Channel \($0)" }) throws -> [Channel] {
        guard let list = try parseList(data) else { return [] }
        var channels: [Channel] = []
        channels.reserveCapacity(list.count)
        for (i, s) in list.enumerated() {
            guard let id = s["stream_id"]?.text, !id.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
            let name = s["name"]?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let icon = s["stream_icon"]?.text
            let epg = s["epg_channel_id"]?.text?.trimmingCharacters(in: .whitespaces)
            let archive = s["tv_archive"]?.text?.trimmingCharacters(in: .whitespaces) == "1"
            let duration = s["tv_archive_duration"]?.text.flatMap { Double($0.trimmingCharacters(in: .whitespaces)) }.map { Int($0) }
            channels.append(Channel(
                uid: i,
                key: id,
                num: s["num"]?.text.flatMap { Double($0) }.map { Int($0) } ?? (i + 1),
                name: name.isEmpty ? channelName(id) : name,
                logo: (icon?.trimmingCharacters(in: .whitespaces).isEmpty ?? true) ? nil : icon,
                categoryId: s["category_id"]?.text ?? "",
                streamId: id,
                epgId: (epg?.isEmpty ?? true) ? nil : epg,
                archiveDays: archive ? max(1, duration ?? 1) : 0
            ))
        }
        return channels
    }

    /// `get_short_epg`: os títulos vêm em Base64.
    public static func parseShortEpg(_ data: Data) -> [EpgEntry] {
        guard let json = try? JSON.parse(data), let listings = json["epg_listings"]?.array else { return [] }
        return listings.compactMap { l -> EpgEntry? in
            guard let start = l["start_timestamp"]?.text.flatMap({ Int64($0) }),
                  let stop = l["stop_timestamp"]?.text.flatMap({ Int64($0) }) else { return nil }
            let raw = l["title"]?.text ?? ""
            let title = Data(base64Encoded: raw, options: .ignoreUnknownCharacters).flatMap { String(data: $0, encoding: .utf8) } ?? raw
            return EpgEntry(title: title.trimmingCharacters(in: .whitespacesAndNewlines), startMs: start * 1000, endMs: stop * 1000)
        }.sorted { $0.startMs < $1.startMs }
    }

    /// Uma lista JSON; null (ou `{}`/`[]` vazio) quando o servidor não tem nada. Outra coisa é resposta inválida.
    private static func parseList(_ data: Data) throws -> [JSON]? {
        let json: JSON
        do {
            json = try JSON.parse(data)
        } catch {
            throw SourceError.badResponse
        }
        switch json {
        case .array(let a): return a
        case .null: return nil
        case .object(let o) where o.isEmpty: return nil
        default: throw SourceError.badResponse
        }
    }

    // MARK: Codificação (como o HttpUrl do OkHttp)

    private static let segmentAllowed: CharacterSet = {
        var s = CharacterSet.urlPathAllowed
        s.remove(charactersIn: "/;?#%")
        return s
    }()

    private static let queryAllowed: CharacterSet = {
        var s = CharacterSet.urlQueryAllowed
        s.remove(charactersIn: "&=+#%")
        return s
    }()

    static func encodeSegment(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: segmentAllowed) ?? s
    }

    static func encodeQuery(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: queryAllowed) ?? s
    }
}

/// Erro ao carregar uma lista (o ecrã mostra uma mensagem própria para cada um).
public enum SourceError: Error, Equatable, Sendable {
    case noURL
    case badURL
    case http(Int)
    case badLogin
    case inactive(String)
    case badResponse
    case offline
}
