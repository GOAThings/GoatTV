import Foundation

public enum PlaylistType: String, Codable, Sendable {
    case xtream
    case m3u
}

/// Uma lista de canais da conta (ou desta TV). Os mesmos campos que no GoatTV Android e no site.
public struct Playlist: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var type: PlaylistType
    public var name: String
    // Xtream
    public var server: String?
    public var username: String?
    public var password: String?
    // M3U
    public var url: String?
    public var epgUrl: String?

    public init(id: String, type: PlaylistType, name: String, server: String? = nil, username: String? = nil,
                password: String? = nil, url: String? = nil, epgUrl: String? = nil) {
        self.id = id
        self.type = type
        self.name = name
        self.server = server
        self.username = username
        self.password = password
        self.url = url
        self.epgUrl = epgUrl
    }

    /// A lista tal como vem da conta (`GET /api/goattv/config`); null se o tipo não for conhecido.
    public init?(account json: JSON) {
        guard let id = json["id"]?.text, !id.isEmpty else { return nil }
        let type: PlaylistType
        switch json["type"]?.string?.lowercased() {
        case "xtream": type = .xtream
        case "m3u": type = .m3u
        default: return nil
        }
        // A conta manda sempre os campos todos ("" nos que não são do tipo): vazio = em falta.
        func field(_ key: String) -> String? {
            guard let s = json[key]?.string?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
            return s
        }
        self.init(
            id: id,
            type: type,
            name: field("name") ?? (type == .xtream ? "Xtream" : "M3U"),
            server: field("server"),
            username: field("username"),
            password: json["password"]?.string.flatMap { $0.isEmpty ? nil : $0 },
            url: field("url"),
            epgUrl: field("epg_url")
        )
    }
}

public struct Category: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

/// Um canal dentro de uma lista.
///
/// `uid` é a posição na lista (única nesta lista). `key` é o `channelKey` sincronizado com a conta (favoritos,
/// último canal): tem de ser calculado exatamente como no GoatTV Android.
public struct Channel: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var uid: Int
    public var key: String
    public var num: Int
    public var name: String
    public var logo: String?
    public var categoryId: String
    public var streamId: String?
    public var url: String?
    public var userAgent: String?
    public var referrer: String?
    /// Id do canal no guia XMLTV (`tvg-id` / `epg_channel_id`).
    public var epgId: String?
    /// Dias de catch-up que o servidor guarda (0 = sem catch-up).
    public var archiveDays: Int

    public var id: Int { uid }

    public init(uid: Int, key: String, num: Int, name: String, logo: String?, categoryId: String,
                streamId: String? = nil, url: String? = nil, userAgent: String? = nil, referrer: String? = nil,
                epgId: String? = nil, archiveDays: Int = 0) {
        self.uid = uid
        self.key = key
        self.num = num
        self.name = name
        self.logo = logo
        self.categoryId = categoryId
        self.streamId = streamId
        self.url = url
        self.userAgent = userAgent
        self.referrer = referrer
        self.epgId = epgId
        self.archiveDays = archiveDays
    }
}

public struct XtreamAccount: Codable, Equatable, Sendable {
    public var maxConnections: Int?
    public var status: String?
    /// `allowed_output_formats` (ex.: só "ts"); vazio = não indicado.
    public var allowedFormats: Set<String>
    /// Fuso do servidor: os pedidos de catch-up usam a hora dele.
    public var timezone: String?
}

/// Os canais de uma lista já descarregados e arrumados por grupo.
public final class PlaylistContent: Codable, Sendable {
    public static let others = "Outros"

    public let playlist: Playlist
    public let categories: [Category]
    public let channels: [Channel]
    public let account: XtreamAccount?
    /// Guia XMLTV: o da conta, o indicado na lista M3U (`x-tvg-url`) ou o `xmltv.php` do Xtream.
    public let guideUrl: String?
    public let fetchedAt: Date

    public let byCategory: [String: [Channel]]
    /// Com chaves repetidas fica o primeiro canal (como no Android).
    public let byKey: [String: Channel]
    private let categoryNames: [String: String]

    private enum CodingKeys: String, CodingKey {
        case playlist, categories, channels, account, guideUrl, fetchedAt
    }

    public init(playlist: Playlist, categories: [Category], channels: [Channel], account: XtreamAccount? = nil,
                guideUrl: String? = nil, fetchedAt: Date = Date()) {
        self.playlist = playlist
        self.categories = categories
        self.channels = channels
        self.account = account
        self.guideUrl = guideUrl
        self.fetchedAt = fetchedAt
        byCategory = Dictionary(grouping: channels, by: \.categoryId)
        var map: [String: Channel] = [:]
        for ch in channels where map[ch.key] == nil { map[ch.key] = ch }
        byKey = map
        categoryNames = Dictionary(categories.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
    }

    public convenience init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            playlist: try c.decode(Playlist.self, forKey: .playlist),
            categories: try c.decode([Category].self, forKey: .categories),
            channels: try c.decode([Channel].self, forKey: .channels),
            account: try c.decodeIfPresent(XtreamAccount.self, forKey: .account),
            guideUrl: try c.decodeIfPresent(String.self, forKey: .guideUrl),
            fetchedAt: try c.decode(Date.self, forKey: .fetchedAt)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(playlist, forKey: .playlist)
        try c.encode(categories, forKey: .categories)
        try c.encode(channels, forKey: .channels)
        try c.encodeIfPresent(account, forKey: .account)
        try c.encodeIfPresent(guideUrl, forKey: .guideUrl)
        try c.encode(fetchedAt, forKey: .fetchedAt)
    }

    public func categoryName(_ id: String) -> String? { categoryNames[id] }

    /// Agrupa os canais pelas categorias conhecidas; o que sobra vai para "Outros".
    public static func build(playlist: Playlist, categories: [Category], channels: [Channel],
                             account: XtreamAccount? = nil, guideUrl: String?) -> PlaylistContent {
        let known = Set(categories.map(\.id))
        let fixed = channels.map { ch -> Channel in
            if known.contains(ch.categoryId) { return ch }
            var c = ch
            c.categoryId = others
            return c
        }
        let used = Set(fixed.map(\.categoryId))
        var cats = categories.filter { used.contains($0.id) }
        if used.contains(others), !cats.contains(where: { $0.id == others }) {
            cats.append(Category(id: others, name: others))
        }
        let guide = guideUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        return PlaylistContent(playlist: playlist, categories: cats, channels: fixed, account: account,
                               guideUrl: (guide?.isEmpty ?? true) ? nil : guide)
    }
}

public enum SpecialCategory {
    public static let favorites = "__fav__"
    public static let all = "__all__"
    public static let recent = "__recent__"

    public static func isSpecial(_ id: String) -> Bool { id == favorites || id == all || id == recent }
}

/// Um programa do guia, com as horas em milissegundos desde 1970.
public struct EpgEntry: Codable, Equatable, Hashable, Sendable {
    public var title: String
    public var startMs: Int64
    public var endMs: Int64

    public init(title: String, startMs: Int64, endMs: Int64) {
        self.title = title
        self.startMs = startMs
        self.endMs = endMs
    }

    public func progress(now: Int64) -> Double {
        guard endMs > startMs else { return 0 }
        return min(1, max(0, Double(now - startMs) / Double(endMs - startMs)))
    }

    public func isOn(at now: Int64) -> Bool { startMs <= now && endMs > now }

    public var start: Date { Date(timeIntervalSince1970: Double(startMs) / 1000) }
    public var end: Date { Date(timeIntervalSince1970: Double(endMs) / 1000) }
}

public enum StreamFormat {
    public static let ts = "ts"
    public static let hls = "m3u8"
}

public func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/// Outro nome para [Category]: nas apps Apple, `Category` também é um tipo do Objective-C.
public typealias ChannelGroup = Category
