import Foundation
import GoatShared

/// O Modo TV Pro (como no Android: shared/…/data/ProLineup.kt, aqui pelo GoatShared.AppleBridge): uma lista só,
/// arrumada como a box de um operador do país, feita com os canais de todas as listas da pessoa. Cada canal fica com
/// as suas fontes, da melhor para a pior; a primeira é a que dá, as outras são os failovers.
public enum ProLineup {
    /// Uma fonte de um canal: a lista, a chave do canal nela e a qualidade pelo nome (ChannelMatch.Quality).
    public struct Ref: Equatable, Sendable {
        public let playlistId: String
        public let key: String
        public let quality: Int
    }

    /// Um canal da lista única: [id] fica igual entre arranques ("pro:pt:rtp1"), [sources] da melhor para a pior.
    public struct Entry: Sendable {
        public let id: String
        public let number: Int
        public let name: String
        public let group: String
        public let sources: [Ref]
    }

    public struct Result: Sendable {
        public let groups: [String]
        public let channels: [Entry]
    }

    /// As tabelas que se podem escolher ("pt"), pela ordem do menu.
    public static var lineupIds: [String] { AppleBridge.shared.proLineupIds() }

    /// O país da tabela [id] ("pt").
    public static func country(_ id: String) -> String? { AppleBridge.shared.proLineupCountry(id: id) }

    /// O id da lista única da tabela [lineupId] (não é uma lista da pessoa: não vai para a cache nem para a conta).
    public static func playlistId(_ lineupId: String) -> String { AppleBridge.shared.proPlaylistId(lineupId: lineupId) }

    public static func isPro(_ playlistId: String) -> Bool { AppleBridge.shared.proIsPro(playlistId: playlistId) }

    public static func lineupId(of playlistId: String) -> String { AppleBridge.shared.proLineupIdOf(playlistId: playlistId) }

    /// O grupo dos canais do país que a tabela não tem ("Mais canais").
    public static var moreGroup: String { AppleBridge.shared.proMoreGroup() }

    /// Os canais de [members] (pela ordem de preferência, cada uma como se mostra) arrumados pela tabela [lineupId].
    public static func build(_ lineupId: String, members: [PlaylistContent]) -> Result {
        let sources = AppleProSources()
        for m in members {
            sources.playlist(playlistId: m.playlist.id)
            for c in m.channels + m.hidden {
                sources.channel(key: c.key, name: c.name, group: m.categoryName(c.categoryId))
            }
        }
        let r = AppleBridge.shared.proBuild(lineupId: lineupId, sources: sources)
        let channels = r.channels.map { c -> Entry in
            let refs = c.sourcePlaylists.indices.map { i in
                Ref(playlistId: c.sourcePlaylists[i], key: c.sourceKeys[i], quality: c.sourceQualities[i].intValue)
            }
            return Entry(id: c.id, number: Int(c.number), name: c.name, group: c.group, sources: refs)
        }
        return Result(groups: r.groups, channels: channels)
    }
}

/// As fontes do Modo TV Pro que falharam, guardadas nesta TV (como no Android: ProHealth.kt). Uma fonte que falhou
/// passa para o fim da fila durante uns dias, ou até a pessoa a escolher no painel das fontes.
@MainActor
public final class ProHealth {
    private static let key = "pro_health"
    private let health: GoatShared.ProHealth

    public init() {
        health = AppleBridge.shared.proHealthDecode(text: UserDefaults.standard.string(forKey: Self.key), nowMs: Self.nowMs())
    }

    public func isDown(_ playlistId: String, _ channelKey: String) -> Bool {
        health.isDown(playlistId: playlistId, channelKey: channelKey, nowMs: Self.nowMs())
    }

    public func markDown(_ playlistId: String, _ channelKey: String) {
        health.markDown(playlistId: playlistId, channelKey: channelKey, nowMs: Self.nowMs())
        save()
    }

    public func markUp(_ playlistId: String, _ channelKey: String) {
        if health.markUp(playlistId: playlistId, channelKey: channelKey) { save() }
    }

    private func save() {
        UserDefaults.standard.set(health.encode(nowMs: Self.nowMs()), forKey: Self.key)
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
