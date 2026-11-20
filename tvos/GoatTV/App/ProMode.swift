import Foundation
import GoatCore

/// Modo TV Pro (como no Android: ProContent.kt): as listas de onde vêm os canais ([members], como se mostram) e as
/// fontes de cada canal da lista única, da melhor para a pior (ver GoatCore ProLineup). A primeira que existe é a que
/// dá; as outras são os failovers. As que falharam há pouco ([ProHealth], guardado nesta TV) vão para o fim.
@MainActor
final class ProSet {
    let lineupId: String
    let members: [String: PlaylistContent]
    private let sources: [String: [ProLineup.Ref]]
    private let health: ProHealth
    /// A fiabilidade de cada fonte (as quedas, as aberturas falhadas, o tempo a ver): desempata as fontes.
    private let history: SourceHistory

    init(lineupId: String, members: [String: PlaylistContent], sources: [String: [ProLineup.Ref]], health: ProHealth, history: SourceHistory) {
        self.lineupId = lineupId
        self.members = members
        self.sources = sources
        self.health = health
        self.history = history
    }

    /// As fontes do canal [key] que ainda existem, com a qualidade da tabela, da melhor para a pior. As que falharam há
    /// pouco (ProHealth) vão para o fim, e em cada grupo as claramente más segundo o historial também; entre fontes da
    /// mesma qualidade, a mais fiável primeiro. Sem historial, é a ordem da tabela.
    private func ordered(_ key: String) -> [(ref: ProLineup.Ref, member: PlaylistContent, channel: Channel)] {
        let found = (sources[key] ?? []).compactMap { r -> (ref: ProLineup.Ref, member: PlaylistContent, channel: Channel)? in
            guard let m = members[r.playlistId], let c = m.byKey[r.key] else { return nil }
            return (r, m, c)
        }
        func sorted(_ list: [(ref: ProLineup.Ref, member: PlaylistContent, channel: Channel)]) -> [(ref: ProLineup.Ref, member: PlaylistContent, channel: Channel)] {
            let order = history.order(keys: list.map { SourceHistory.key($0.ref.playlistId, $0.ref.key) }, tiers: list.map { $0.ref.quality })
            return order.map { list[$0] }
        }
        return sorted(found.filter { !isDown($0.member, $0.channel) }) + sorted(found.filter { isDown($0.member, $0.channel) })
    }

    /// As fontes do canal [key] que ainda existem, da melhor para a pior (as que falharam no fim).
    func resolve(_ key: String) -> [(member: PlaylistContent, channel: Channel)] {
        ordered(key).map { ($0.member, $0.channel) }
    }

    /// A chave no historial (SourceHistory.key) da fonte [channel] de [member].
    func sourceKey(_ member: PlaylistContent, _ channel: Channel) -> String { SourceHistory.key(member.playlist.id, channel.key) }

    /// A fonte [channel] de [member] falhou (cai sempre, ou não abre): durante uns dias as outras vêm antes.
    func demote(_ member: PlaylistContent, _ channel: Channel) { health.markDown(member.playlist.id, channel.key, channel.name) }

    /// A pessoa escolheu a fonte no painel: volta ao lugar dela.
    func promote(_ member: PlaylistContent, _ channel: Channel) { health.markUp(member.playlist.id, channel.key, channel.name) }

    /// Com o nome do canal: a versão dele ("ᴴᴰ") também conta (ver ProHealth.variantOf no GoatShared).
    func isDown(_ member: PlaylistContent, _ channel: Channel) -> Bool { health.isDown(member.playlist.id, channel.key, channel.name) }

    /// A qualidade (pelo nome, ChannelMatch.Quality) da fonte que vai dar o canal [key], ou nil.
    func quality(_ key: String) -> Int? {
        ordered(key).first?.ref.quality
    }
}

/// A lista única do Modo TV Pro, feita com as listas da pessoa.
@MainActor
enum ProContent {
    /// A "lista" que se abre no Modo TV Pro com a tabela [lineupId]: só o id e o nome; não tem fornecedor.
    static func playlist(_ lineupId: String, _ s: Strings) -> Playlist {
        Playlist(id: ProLineup.playlistId(lineupId), type: .m3u, name: lineupName(lineupId, s))
    }

    /// O nome do país da tabela ("Portugal"), na língua da app.
    static func lineupName(_ lineupId: String, _ s: Strings) -> String {
        let code = (ProLineup.country(lineupId) ?? lineupId).uppercased()
        return Locale(identifier: s.lang).localizedString(forRegionCode: code) ?? code
    }

    /// Os canais de [members] (pela ordem de preferência, já como se mostram) arrumados por [result]. Cada canal fica
    /// com o nome e o número da tabela, e com o logótipo e o guia da melhor fonte que os tenha (o guia, de
    /// preferência, da lista cujo guia se usa: o da primeira que tem um).
    static func build(_ lineupId: String, members: [PlaylistContent], result: ProLineup.Result, health: ProHealth, history: SourceHistory, strings s: Strings)
        -> (content: PlaylistContent, set: ProSet) {
        let guide = members.first { $0.guideUrl != nil }
        var byId: [String: PlaylistContent] = [:]
        for m in members where byId[m.playlist.id] == nil { byId[m.playlist.id] = m }
        let set = ProSet(lineupId: lineupId, members: byId,
                         sources: Dictionary(result.channels.map { ($0.id, $0.sources) }, uniquingKeysWith: { a, _ in a }), health: health, history: history)
        var channels: [Channel] = []
        for pc in result.channels {
            let found = set.resolve(pc.id)
            guard let first = found.first?.channel else { continue }
            let epgId = found.first { $0.member === guide && !($0.channel.epgId ?? "").isEmpty }?.channel.epgId
                ?? found.lazy.compactMap { ($0.channel.epgId ?? "").isEmpty ? nil : $0.channel.epgId }.first
            let logo = found.lazy.compactMap { ($0.channel.logo ?? "").isEmpty ? nil : $0.channel.logo }.first
            channels.append(Channel(uid: channels.count, key: pc.id, num: pc.number, name: pc.name, logo: logo, categoryId: pc.group,
                                    epgId: epgId, archiveDays: first.archiveDays))
        }
        let categories = result.groups.map { GoatCore.Category(id: $0, name: groupName($0, s)) }
        let content = PlaylistContent(playlist: playlist(lineupId, s), categories: categories, channels: channels, guideUrl: guide?.guideUrl)
        return (content, set)
    }

    /// O canal [source] (de uma lista da pessoa) a dar no lugar de [shown] (o da lista Pro): o ecrã continua a mostrar este.
    static func asShown(_ source: Channel, _ shown: Channel) -> Channel {
        var c = source
        c.key = shown.key
        c.num = shown.num
        c.name = shown.name
        c.logo = shown.logo ?? source.logo
        c.categoryId = shown.categoryId
        return c
    }

    static func groupName(_ id: String, _ s: Strings) -> String {
        switch id {
        case "general": return s.r("pro_general")
        case "news": return s.r("pro_news")
        case "entertainment": return s.r("pro_entertainment")
        case "movies": return s.r("pro_movies")
        case "sports": return s.r("pro_sports")
        case "kids": return s.r("pro_kids")
        case "docs": return s.r("pro_docs")
        case "music": return s.r("pro_music")
        case "international": return s.r("pro_international")
        default: return s.r("pro_more")
        }
    }
}

/// Uma fonte de um canal do Modo TV Pro (o painel "Fontes"): [down] se falhou há pouco ou a lista esteve em baixo.
struct ProSourceInfo: Identifiable {
    let index: Int
    let playlistName: String
    let channelName: String
    let quality: Int
    let down: Bool
    /// O que se passou com a fonte esta semana ("3 quedas em 2 h esta semana"); nil sem nada para dizer.
    var history: String?
    var id: Int { index }
}

extension AppModel {
    /// O Modo TV Pro está ligado (a lista aberta é a única, feita com as da pessoa).
    var proMode: Bool { proSet != nil && content.map { ProLineup.isPro($0.playlist.id) } == true }

    /// De onde dá [channel] de [list] (a lista aberta, sem ela): no Modo TV Pro, a melhor fonte (a lista dela e o canal
    /// dela, com o nome e o número da lista única); fora dele, a própria lista e o canal.
    func streamSource(_ channel: Channel, in list: PlaylistContent? = nil) -> (content: PlaylistContent, channel: Channel)? {
        guard let list = list ?? content else { return nil }
        guard ProLineup.isPro(list.playlist.id), let pro = proSet else { return (list, channel) }
        guard let first = pro.resolve(channel.key).first else { return nil }
        return (first.member, ProContent.asShown(first.channel, channel))
    }

    /// As fontes de [channel] no Modo TV Pro, da melhor para a pior; vazia fora dele.
    func proSources(_ channel: Channel) -> [ProSourceInfo] {
        guard proMode, let pro = proSet else { return [] }
        let now = Self.uptimeMs()
        return pro.resolve(channel.key).enumerated().map { i, s in
            ProSourceInfo(index: i, playlistName: s.member.playlist.name, channelName: s.channel.name,
                          quality: ChannelMatch.parse(s.channel.name).quality,
                          down: providerOutage.isDown(s.member.playlist.id, now: now) || pro.isDown(s.member, s.channel),
                          history: sourceLine(pro.sourceKey(s.member, s.channel)))
        }
    }

    /// A qualidade da fonte que vai dar [channel] ("FHD"), no Modo TV Pro; nil fora dele ou se o nome não diz.
    func proQualityLabel(_ channel: Channel) -> String? {
        guard proMode, let q = proSet?.quality(channel.key) else { return nil }
        return Self.qualityLabel(q)
    }

    static func qualityLabel(_ q: Int) -> String? {
        switch q {
        case ChannelMatch.Quality.sd: return "SD"
        case ChannelMatch.Quality.hd: return "HD"
        case ChannelMatch.Quality.fhd: return "FHD"
        case ChannelMatch.Quality.uhd: return "4K"
        case ChannelMatch.Quality.q8k: return "8K"
        default: return nil
        }
    }

    /// Um failover começou a dar: no Modo TV Pro, a fonte que falhou passa para o fim (ver ProHealth). Devolve-a como
    /// aparece no aviso ("SIC ᴴᴰ (PrincipalTV)"), ou nil se não havia nenhuma à espera.
    @discardableResult
    func proBackupPlaying() -> String? {
        guard let pending = proPendingDemote else { return nil }
        proPendingDemote = nil
        pending.set.demote(pending.member, pending.channel)
        return "\(pending.channel.name) (\(pending.member.playlist.name))"
    }

    /// O painel das fontes: dá já a fonte [index] (e as seguintes ficam como failovers dela).
    func tryProSource(_ index: Int) { tryBackup(["\(index)"]) }

    /// Os failovers de [channel] no Modo TV Pro: as outras fontes, pela ordem (as das listas em baixo há pouco no
    /// fim). Com [start] (o painel das fontes: o índice de uma), a partir dessa e depois as outras.
    func proStreams(_ pro: ProSet, _ channel: Channel, start: Int?) -> [FailoverStream] {
        let all = pro.resolve(channel.key)
        guard !all.isEmpty else { return [] }
        // Sem escolha no painel, é porque a fonte que dava falhou: se outra der no lugar dela (ver [proBackupPlaying]),
        // fica para o fim nas próximas vezes. Se nenhuma der (a conta toda recusa), não é culpa desta fonte.
        proPendingDemote = start == nil ? all.first.map { (set: pro, member: $0.member, channel: $0.channel) } : nil
        // O aviso diz a lista que falhou (a da primeira fonte), não "Portugal".
        proFailedFrom = all.first?.member.playlist.name
        let order: [(member: PlaylistContent, channel: Channel)]
        if let start {
            let s = min(max(start, 0), all.count - 1)
            // Escolhida no painel: a pessoa quer essa, mesmo que tenha falhado antes.
            pro.promote(all[s].member, all[s].channel)
            order = Array(all[s...]) + Array(all[..<s])
        } else {
            order = Array(all.dropFirst())
        }
        let now = Self.uptimeMs()
        let up = order.filter { !providerOutage.isDown($0.member.playlist.id, now: now) }
        let down = order.filter { providerOutage.isDown($0.member.playlist.id, now: now) }
        let streams = (up + down).map { s -> FailoverStream in
            var shown = ProContent.asShown(s.channel, channel)
            shown.name = s.channel.name
            return FailoverStream(playlist: s.member.playlist, channel: shown,
                                  candidates: streamCandidates(playlist: s.member.playlist, channel: s.channel, preferred: prefs.streamFormat,
                                                               allowedFormats: s.member.account?.allowedFormats ?? []),
                                  sameList: false, sourceKey: pro.sourceKey(s.member, s.channel))
        }
        DebugLog.add("Pro: \"\(channel.name)\" -> " + streams.map { "\"\($0.channel.name)\" (\($0.playlist.name))" }.joined(separator: " | "))
        return streams
    }
}
