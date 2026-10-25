import Foundation

/// Um canal de guia do catálogo da conta (IPTV-EPG.org no servidor GOAT). [now] é o programa a dar agora;
/// [country] e [score] só vêm nas sugestões.
public struct EpgGuideChannel: Hashable, Sendable {
    public var id: String?
    public var name: String?
    public var icon: String?
    public var now: String?
    public var country: String?
    public var countryName: String?
    public var score: Double?

    public init(json: JSON) {
        id = json["id"]?.text
        name = json["name"]?.text.map(unescapeHTML)
        icon = json["icon"]?.text.flatMap { $0.isEmpty ? nil : $0 }
        now = json["now"]?.text.map(unescapeHTML)
        country = json["country"]?.text
        countryName = json["country_name"]?.text.map(unescapeHTML)
        score = json["score"]?.double
    }
}

/// Um país com canais de guia (pesquisa, ou todos os canais do país).
public struct EpgCountryResult: Hashable, Sendable {
    public var country: String?
    public var countryName: String?
    public var channels: [EpgGuideChannel]

    public init(json: JSON) {
        country = json["country"]?.text
        countryName = json["country_name"]?.text.map(unescapeHTML)
        channels = (json["channels"]?.array ?? []).map(EpgGuideChannel.init(json:))
    }
}

/// Um país do guia (`channels` = canais no catálogo; 0 enquanto ainda não foi lido).
public struct EpgCountryInfo: Hashable, Sendable {
    public var code: String?
    public var name: String?
    public var channels: Int

    public init(json: JSON) {
        code = json["code"]?.text
        name = json["name"]?.text.map(unescapeHTML)
        channels = json["channels"]?.int ?? 0
    }
}

/// O canal de guia associado a um canal da lista.
public struct EpgMapping: Hashable, Sendable {
    public var country: String?
    public var channelId: String?
    public var channelName: String?
    public var guideName: String?
    /// Logótipo do canal de guia (PNG, muitas vezes branco sobre transparente).
    public var icon: String?

    public init(country: String?, channelId: String?, channelName: String?, guideName: String?, icon: String? = nil) {
        self.country = country
        self.channelId = channelId
        self.channelName = channelName
        self.guideName = guideName
        self.icon = icon
    }

    public init(json: JSON) {
        self.init(country: json["country"]?.text, channelId: json["channel_id"]?.text, channelName: json["channel_name"]?.text,
                  guideName: json["guide_name"]?.text.map(unescapeHTML), icon: json["icon"]?.text.flatMap { $0.isEmpty ? nil : $0 })
    }
}

/// Os nomes do IPTV-EPG.org às vezes vêm com entidades HTML ("Trinidad &amp; Tobago").
public func unescapeHTML(_ s: String) -> String {
    guard s.contains("&") else { return s }
    return s.replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
        .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
        .replacingOccurrences(of: "&apos;", with: "'").replacingOccurrences(of: "&amp;", with: "&")
}

public extension Guide {
    /// Pares canal da lista → canal de guia com o mesmo tvg-id ou o mesmo nome (normalizado: "PT: SIC HD" = "SIC").
    static func match(_ channels: [Channel], _ guide: [EpgGuideChannel]) -> [(Channel, EpgGuideChannel)] {
        var byId: [String: EpgGuideChannel] = [:]
        var byName: [String: EpgGuideChannel] = [:]
        for g in guide {
            if let id = g.id?.lowercased(), byId[id] == nil { byId[id] = g }
            if let name = g.name.map(XMLTVGuide.normalizeName), !name.isEmpty, byName[name] == nil { byName[name] = g }
        }
        var seen = Set<String>()
        var result: [(Channel, EpgGuideChannel)] = []
        for ch in channels {
            guard let g = ch.epgId.flatMap({ byId[$0.lowercased()] }) ?? byName[XMLTVGuide.normalizeName(ch.name)] else { continue }
            if seen.insert(ch.key).inserted { result.append((ch, g)) }
        }
        return result
    }
}
