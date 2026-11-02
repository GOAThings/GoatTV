import Foundation

/// Os dados de uma lista a criar ou editar na TV (como o `PlaylistForm` do Android).
public struct PlaylistForm: Hashable, Sendable {
    public var type: PlaylistType = .xtream
    public var name = ""
    public var server = ""
    public var username = ""
    public var password = ""
    public var url = ""
    public var epgUrl = ""
    public var serial = ""
    public var deviceId = ""
    public var deviceId2 = ""
    public var signature = ""

    public init(type: PlaylistType = .xtream) {
        self.type = type
    }

    public init(_ p: Playlist) {
        type = p.type
        name = p.name
        server = p.server ?? ""
        username = p.username ?? ""
        password = p.password ?? ""
        url = p.url ?? ""
        epgUrl = p.epgUrl ?? ""
        serial = p.serial ?? ""
        deviceId = p.deviceId ?? ""
        deviceId2 = p.deviceId2 ?? ""
        signature = p.signature ?? ""
    }

    public enum Problem: Sendable {
        case server, username, password, url, mac
    }

    /// O primeiro campo em falta, ou nil se estiver pronta a gravar.
    public var problem: Problem? {
        switch type {
        case .xtream:
            if server.trimmed.isEmpty { return .server }
            if username.trimmed.isEmpty { return .username }
            if password.isEmpty { return .password }
        case .m3u:
            if url.trimmed.isEmpty { return .url }
        case .stalker:
            if server.trimmed.isEmpty { return .server }
            if Stalker.normalizeMac(username) == nil { return .mac }
        }
        return nil
    }

    /// A lista a gravar. Sem nome fica o do servidor; sem "http://" no endereço, junta-se.
    public func playlist(id: String) -> Playlist {
        let stalker = type == .stalker
        let xtream = type == .xtream || stalker
        func extra(_ s: String) -> String? { stalker && !s.trimmed.isEmpty ? s.trimmed : nil }
        var server = Self.withScheme(server)
        while server.hasSuffix("/") { server.removeLast() }
        let url = Self.withScheme(url)
        let host = URL(string: xtream ? server : url)?.host
        let fallback = host ?? type.label
        let epg = Self.withScheme(epgUrl)
        return Playlist(
            id: id,
            type: type,
            name: name.trimmed.isEmpty ? fallback : name.trimmed,
            server: xtream ? server : nil,
            username: stalker ? Stalker.normalizeMac(username) : xtream ? username.trimmed : nil,
            password: type == .xtream ? password : nil,
            url: xtream ? nil : url,
            epgUrl: epg.isEmpty ? nil : epg,
            serial: extra(serial),
            deviceId: extra(deviceId),
            deviceId2: extra(deviceId2),
            signature: extra(signature)
        )
    }

    private static func withScheme(_ address: String) -> String {
        let a = address.trimmed
        return a.isEmpty || a.contains("://") ? a : "http://" + a
    }
}
