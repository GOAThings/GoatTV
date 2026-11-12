import Foundation
import GoatShared

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
        // A hora no fuso do servidor (sem ele, deste aparelho), como no Android (CatchupRules.xtreamStart).
        let start = CatchupRules.shared.xtreamStart(startMs: startMs, timezone: timezone)
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

    // As respostas leem-se no núcleo partilhado com o Android (XtreamParse).

    public static func parseLogin(_ data: Data) -> Login? {
        guard let info = XtreamParse.shared.login(text: String(decoding: data, as: UTF8.self)) else { return nil }
        return Login(
            auth: info.auth,
            status: info.status,
            activeConnections: info.activeConnections.map { Int($0.int32Value) },
            maxConnections: info.maxConnections.map { Int($0.int32Value) },
            allowedFormats: info.allowedFormats,
            timezone: info.timezone
        )
    }

    public static func parseCategories(_ data: Data) throws -> [Category] {
        do {
            return try XtreamParse.shared.categoriesText(text: String(decoding: data, as: UTF8.self))
                .map { Category(id: $0.id, name: $0.name) }
        } catch {
            throw SourceError.badResponse
        }
    }

    public static func parseStreams(_ data: Data, channelName: @escaping (String) -> String = { "Channel \($0)" }) throws -> [Channel] {
        do {
            return try XtreamParse.shared.streamsText(text: String(decoding: data, as: UTF8.self), channelName: channelName).map { Channel($0) }
        } catch {
            throw SourceError.badResponse
        }
    }

    /// `get_short_epg`: os títulos vêm em Base64.
    public static func parseShortEpg(_ data: Data) -> [EpgEntry] {
        XtreamParse.shared.shortEpg(text: String(decoding: data, as: UTF8.self))
            .map { EpgEntry(title: $0.title, startMs: $0.startMs, endMs: $0.endMs) }
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
