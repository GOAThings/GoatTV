import Foundation
import GoatShared

/// Um canal onde dá um jogo do Sports Hub. `rank`: 0 do país da pessoa, 1 da língua dela, 2 do país do jogo, 3 outro.
public struct SportsChannelRef: Hashable, Sendable {
    public let key: String
    public let name: String
    public let country: String?
    public let rank: Int
}

/// Um jogo (ou uma corrida) que o guia tem, com os canais pela ordem em que se tenta abrir (ver SportsHub.kt).
public struct SportsGameInfo: Identifiable, Hashable, Sendable {
    public let id: String
    public let sport: String
    public let league: String
    /// Vazios numa corrida (o nome está em `name`).
    public let home: String
    public let away: String
    public let name: String
    public let startMs: Int64
    public let endMs: Int64
    public let channels: [SportsChannelRef]

    public var title: String { home.isEmpty ? name : "\(home) – \(away)" }
    public func isLive(_ now: Int64) -> Bool { startMs <= now && now < endMs }
}

/// O que o Sports Hub mostra: os jogos (a decorrer primeiro), os canais onde dão e os desportos que há.
public struct SportsHubData: Sendable {
    public let games: [SportsGameInfo]
    public let channels: [SportsChannelRef]
    public let sports: [String]
}

/// O Sports Hub do núcleo partilhado (SportsHub.kt): as mesmas regras do Android para achar os jogos no guia. Guarda o
/// que já percebeu dos títulos de uma vez para a outra (um por lista aberta); as contas fazem-se fora do main.
public final class SportsEngine: @unchecked Sendable {
    private let lock = NSLock()
    private var memory = GoatShared.SportsMemory()
    private var playlistId: String?

    public init() {}

    /// Canal de desporto pelo nome dele ou do grupo.
    public static func isSportsChannel(name: String, group: String) -> Bool {
        GoatShared.SportsHub.shared.isSportsChannel(name: name, group: group)
    }

    /// Duas a três letras para o círculo da equipa ("Benfica" → "BEN").
    public static func initials(_ team: String) -> String { GoatShared.SportsHub.shared.initials(team: team) }

    /// A cor do círculo da equipa (ARGB), sempre a mesma para a mesma equipa.
    public static func color(_ team: String) -> UInt32 {
        let colors = GoatShared.SportsHub.shared.COLORS
        let i = Int(GoatShared.SportsHub.shared.colorIndex(team: team))
        return UInt32(truncatingIfNeeded: colors[i].int64Value)
    }

    /// Os jogos de `sources` (canal, nome do grupo, programas) à volta de `now`.
    public func build(playlistId: String, sources: [(channel: Channel, group: String, entries: [EpgEntry])], now: Int64,
                      country: String?, language: String?) -> SportsHubData {
        let list = sources.map { s in
            GoatShared.SportsSource(
                key: s.channel.key, name: s.channel.name, group: s.group,
                entries: s.entries.map { GoatShared.EpgEntry(title: $0.title, startMs: $0.startMs, endMs: $0.endMs) }
            )
        }
        lock.lock()
        defer { lock.unlock() }
        if self.playlistId != playlistId {
            self.playlistId = playlistId
            memory = GoatShared.SportsMemory()
        }
        let r = GoatShared.SportsHub.shared.build(sources: list, now: now, country: country, language: language, memory: memory)
        func ref(_ c: GoatShared.SportsChannel) -> SportsChannelRef {
            SportsChannelRef(key: c.key, name: c.name, country: c.country, rank: Int(c.rank))
        }
        let games = r.games.map { g in
            SportsGameInfo(id: g.id, sport: g.sport, league: g.league, home: g.home, away: g.away, name: g.name,
                           startMs: g.startMs, endMs: g.endMs, channels: g.channels.map(ref))
        }
        return SportsHubData(games: games, channels: r.channels.map(ref), sports: r.sports)
    }
}
