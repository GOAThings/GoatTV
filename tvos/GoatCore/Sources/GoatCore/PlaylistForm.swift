import Foundation
import GoatShared

/// Os dados de uma lista a criar ou editar na TV (como o `PlaylistForm` do Android). As regras são as do núcleo
/// partilhado (PlaylistRules).
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

    /// Muda o tipo: o Stalker começa com o MAC deste aparelho, e ao sair dele esse MAC não fica como utilizador.
    public mutating func setType(_ next: PlaylistType, deviceMac: String) {
        username = PlaylistRules.shared.usernameFor(next: next.rawValue, username: username, deviceMac: deviceMac)
        type = next
    }

    public enum Problem: Sendable {
        case server, username, password, url, mac
    }

    /// O primeiro campo em falta, ou nil se estiver pronta a gravar.
    public var problem: Problem? {
        guard let p = PlaylistRules.shared.problem(type: type.rawValue, server: server, username: username, password: password, url: url) else { return nil }
        switch p.name {
        case "USERNAME": return .username
        case "PASSWORD": return .password
        case "URL": return .url
        case "MAC": return .mac
        default: return .server
        }
    }

    /// A lista a gravar. Sem nome fica o do servidor; sem "http://" no endereço, junta-se.
    public func playlist(id: String) -> Playlist {
        let f = PlaylistRules.shared.fields(type: type.rawValue, name: name, server: server, username: username, password: password, url: url,
                                            epgUrl: epgUrl, serial: serial, deviceId: deviceId, deviceId2: deviceId2, signature: signature)
        return Playlist(id: id, type: type, name: f.name, server: f.server, username: f.username, password: f.password, url: f.url,
                        epgUrl: f.epgUrl, serial: f.serial, deviceId: f.deviceId, deviceId2: f.deviceId2, signature: f.signature)
    }
}
