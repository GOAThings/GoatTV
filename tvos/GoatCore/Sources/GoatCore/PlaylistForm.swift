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
    }

    public enum Problem: Sendable {
        case server, username, password, url
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
        }
        return nil
    }

    /// A lista a gravar. Sem nome fica o do servidor; sem "http://" no endereço, junta-se.
    public func playlist(id: String) -> Playlist {
        let xtream = type == .xtream
        var server = Self.withScheme(server)
        while server.hasSuffix("/") { server.removeLast() }
        let url = Self.withScheme(url)
        let host = URL(string: xtream ? server : url)?.host
        let fallback = host ?? (xtream ? "Xtream" : "M3U")
        let epg = Self.withScheme(epgUrl)
        return Playlist(
            id: id,
            type: type,
            name: name.trimmed.isEmpty ? fallback : name.trimmed,
            server: xtream ? server : nil,
            username: xtream ? username.trimmed : nil,
            password: xtream ? password : nil,
            url: xtream ? nil : url,
            epgUrl: epg.isEmpty ? nil : epg
        )
    }

    private static func withScheme(_ address: String) -> String {
        let a = address.trimmed
        return a.isEmpty || a.contains("://") ? a : "http://" + a
    }
}
