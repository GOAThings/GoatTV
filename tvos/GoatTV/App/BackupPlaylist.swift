import Foundation
import GoatCore

/// Os canais de uma lista na reserva: a cobertura e a lista "Canais de reserva" (cada canal e o seu estado).
struct BackupReview {
    let main: PlaylistContent
    let backup: PlaylistContent
    let rows: [ChannelMatch.ReviewRow]
    let coverage: ChannelMatch.Coverage
    let manual: Bool
    let overrides: [String: [String]]
}

/// A cobertura da reserva de uma lista: quantos canais ela tem (o subtítulo da linha "Lista de reserva").
enum BackupCoverage {
    case checking
    case ready(backupId: String, review: BackupReview)
    case unavailable
}

/// O painel "Canal de reserva" de um canal: os failovers dele, o automático, os parecidos na reserva e na própria lista,
/// e a escolha à mão.
struct BackupChoices {
    /// A lista principal.
    let mainId: String
    let backup: Playlist
    let content: PlaylistContent
    /// A lista principal (para os failovers dela); nil se não estiver carregada.
    let main: PlaylistContent?
    let auto: ChannelMatch.Result
    let similar: [Channel]
    /// Os parecidos na própria lista (sem o canal).
    let similarMain: [Channel]
    /// Os failovers escolhidos à mão, pela ordem: lista vazia = "nenhum", nil = automático.
    var choice: [String]?
    /// Modo manual: o automático é só uma sugestão (e sem escolha não muda de lista).
    let manual: Bool

    var autoChannel: Channel? { auto.index.flatMap { content.channels.indices.contains($0) ? content.channels[$0] : nil } }

    /// O canal de um failover (da reserva, ou da própria lista com ChannelMatch.mainPrefix).
    func channel(of failover: String) -> Channel? {
        if let key = ChannelMatch.mainKey(of: failover) { return main?.byKey[key] }
        return content.byKey[failover]
    }

    /// A lista de um failover (a própria, ou a reserva).
    func list(of failover: String) -> PlaylistContent? { ChannelMatch.mainKey(of: failover) != nil ? main : content }

    /// O failover de [channel], da reserva ou ([fromMain]) da própria lista.
    func failover(_ channel: Channel, fromMain: Bool) -> String { fromMain ? ChannelMatch.mainFailover(channel.key) : channel.key }

    /// Os failovers que se tentam, pela ordem: os escolhidos que ainda existem, ou o automático. Uma escolha cujos canais
    /// já não existem conta como sem escolha (como na lista "Canais de reserva").
    var failovers: [String] {
        let chosen = choice?.filter { channel(of: $0) != nil } ?? []
        if choice?.isEmpty == true || !chosen.isEmpty { return chosen }
        return manual ? [] : (autoChannel.map { [$0.key] } ?? [])
    }

    /// "Nenhum": este canal não muda de lista.
    var none: Bool { failovers.isEmpty }

    /// Os failovers ainda são os do automático (nada escolhido à mão).
    var automatic: Bool { choice == nil }

    /// Junta [failover] no fim (ou tira-o, se já lá estiver).
    func toggled(_ failover: String) -> [String] {
        let list = failovers
        return list.contains(failover) ? list.filter { $0 != failover } : list + [failover]
    }

    /// Os canais sugeridos para juntar: o do automático e os parecidos na reserva, e os parecidos na própria lista.
    var suggested: [BackupHit] {
        var seen = Set<String>()
        let backup = ([autoChannel].compactMap { $0 } + similar).filter { seen.insert($0.key).inserted }.map { BackupHit(channel: $0, fromMain: false) }
        return Array(backup.prefix(5)) + similarMain.prefix(3).map { BackupHit(channel: $0, fromMain: true) } + backup.dropFirst(5)
    }

    /// Move [failover] [delta] lugares (−1 = para cima); nil se não puder.
    func moved(_ failover: String, by delta: Int) -> [String]? {
        var list = failovers
        guard let from = list.firstIndex(of: failover), list.indices.contains(from + delta) else { return nil }
        list.swapAt(from, from + delta)
        return list
    }
}

/// Um canal encontrado na pesquisa do painel: da reserva, ou ([fromMain]) da própria lista.
struct BackupHit: Identifiable {
    let channel: Channel
    let fromMain: Bool
    var id: String { (fromMain ? "m:" : "b:") + channel.key }
}

/// Um failover pronto a abrir: a lista dele, o canal, as tentativas, e se é outro canal da própria lista.
struct FailoverStream {
    let playlist: Playlist
    let channel: Channel
    let candidates: [StreamCandidate]
    let sameList: Bool

    /// Como aparece no cartão de erro: "RTP 1 HD (BackupTV)", ou só o canal se for da própria lista.
    var label: String { sameList ? channel.name : "\(channel.name) (\(playlist.name))" }
}

/// A lista de reserva (como no GoatTV Android): quando um canal em direto não abre na lista em uso, dá o mesmo canal de
/// outra lista. A escolha fica no `settings` da conta (`backup_playlist`, `backup_channels`), como o guia extra.
extension AppModel {
    /// A lista de reserva de [playlistId], se ainda existir e não for ela própria.
    func backupOf(_ playlistId: String) -> Playlist? {
        guard let id = prefs.backupPlaylist[playlistId], id != playlistId else { return nil }
        return playlists.first { $0.id == id }
    }

    /// Muda a reserva de [playlistId] (nil = nenhuma). Os canais escolhidos à mão e o modo eram da reserva anterior: saem.
    func setBackupPlaylist(_ playlistId: String, _ backupId: String?) {
        let target = backupId == playlistId ? nil : backupId
        guard prefs.backupPlaylist[playlistId] != target else { return }
        var map = prefs.backupPlaylist
        map[playlistId] = target
        let channels = prefs.backupChannels.filter { !$0.key.hasPrefix("\(playlistId):") }
        var modes = prefs.backupMode
        modes[playlistId] = nil
        setPrefs([
            "backup_playlist": .object(map.mapValues { JSON.string($0) }),
            "backup_channels": .object(channels.mapValues(Prefs.failoverValue)),
            "backup_mode": .object(modes.mapValues { JSON.string($0) }),
        ])
        backupCoverage[playlistId] = nil
    }

    /// Modo manual: só os canais escolhidos à mão passam para a reserva.
    func backupManual(_ playlistId: String) -> Bool { prefs.backupMode[playlistId] == ChannelMatch.modeManual }

    func setBackupManual(_ playlistId: String, _ manual: Bool) {
        var modes = prefs.backupMode
        modes[playlistId] = manual ? ChannelMatch.modeManual : nil
        setPref("backup_mode", .object(modes.mapValues { JSON.string($0) }))
    }

    /// As escolhas à mão de [playlistId] (chave do canal → failovers).
    private func backupOverrides(_ playlistId: String) -> [String: [String]] {
        Dictionary(uniqueKeysWithValues: prefs.backupChannels.compactMap { k, v -> (String, [String])? in
            k.hasPrefix("\(playlistId):") ? (String(k.dropFirst(playlistId.count + 1)), v) : nil
        })
    }

    /// Os failovers escolhidos à mão para [channelKey] de [playlistId], pela ordem (vazia = "nenhum", nil = automático).
    func backupChoice(_ playlistId: String, _ channelKey: String) -> [String]? {
        prefs.backupChannels["\(playlistId):\(channelKey)"]
    }

    /// Grava os failovers escolhidos à mão ([value] nil = volta ao automático; vazia = "nenhum").
    func setBackupChoice(_ playlistId: String, _ channelKey: String, _ value: [String]?) {
        var map = prefs.backupChannels
        var seen = Set<String>()
        map["\(playlistId):\(channelKey)"] = value?.filter { seen.insert($0).inserted }
        setPref("backup_channels", .object(map.mapValues(Prefs.failoverValue)))
    }

    /// Os canais da reserva (a lista em uso, da cache, ou descarregados como os do segundo canal).
    private func backupContent(_ backup: Playlist) async -> PlaylistContent? {
        try? await loadForSecond(backup)
    }

    private func matcher(for content: PlaylistContent) async -> ChannelMatcher {
        if let cached = backupMatcher, cached.id == content.playlist.id, cached.fetchedAt == content.fetchedAt { return cached.matcher }
        let entries = content.channels.map { ChannelMatch.Entry($0, in: content) }
        let matcher = await Task.detached(priority: .userInitiated) { ChannelMatcher(entries) }.value
        backupMatcher = (content.playlist.id, content.fetchedAt, matcher)
        return matcher
    }

    /// Os failovers de [channel] de [main], prontos a abrir pela ordem ([chosen]: os do painel, "experimentar agora";
    /// sem eles, os escolhidos à mão ou o automático). Os da reserva ficam de fora sem reserva, quando ela não carrega,
    /// ou quando abri-la passava o limite de ligações dela; os da própria lista, quando ela está em baixo (falhavam
    /// também).
    private func backupStreams(_ main: PlaylistContent, _ channel: Channel, chosen: [String]? = nil) async -> [FailoverStream] {
        let id = main.playlist.id
        // A mesma conta do fornecedor não ajuda (e contava como outra ligação).
        let backup = backupOf(id).flatMap { sameAccount($0, main.playlist) ? nil : $0 }
        var content: PlaylistContent?
        if let backup {
            content = await backupContent(backup)
            if content == nil { DebugLog.add("Backup: \(backup.name) channels did not load") }
        }
        // O segundo canal já usa a conta da reserva e ela só deixa uma ligação: não se abre outra.
        var busy = false
        if let backup, let c = content, let sp = second?.content.playlist, sameAccount(sp, backup) || sp.id == backup.id,
           backup.type == .stalker || (backup.type == .xtream && (c.account?.maxConnections ?? 1) < 2) {
            DebugLog.add("Backup: the second channel already uses its account")
            busy = true
        }
        let m: ChannelMatcher
        if let content { m = await matcher(for: content) } else { m = ChannelMatcher([]) }
        let manual = backupManual(id)
        let list = ChannelMatch.failovers(ChannelMatch.Entry(channel, in: main), m, chosen: chosen ?? backupChoice(id, channel.key), manual: manual) { main.byKey[$0] != nil }
        let mainDown = providerOutage.isDown(id, now: Self.uptimeMs())
        let streams: [FailoverStream] = list.compactMap { f in
            if let key = ChannelMatch.mainKey(of: f) {
                guard !mainDown, let c = main.byKey[key] else { return nil }
                return FailoverStream(playlist: main.playlist, channel: c, candidates: candidates(main, c), sameList: true)
            }
            guard !busy, let content, let c = content.byKey[f] else { return nil }
            return FailoverStream(playlist: content.playlist, channel: c, candidates: candidates(content, c), sameList: false)
        }
        DebugLog.add(streams.isEmpty
            ? "Backup: no failover for \"\(channel.name)\"\(manual ? " (manual)" : "")"
            : "Backup: \"\(channel.name)\" -> " + streams.map { "\"\($0.channel.name)\" (\($0.playlist.name))" }.joined(separator: " | "))
        return streams
    }

    private func candidates(_ content: PlaylistContent, _ channel: Channel) -> [StreamCandidate] {
        streamCandidates(playlist: content.playlist, channel: channel, preferred: prefs.streamFormat, allowedFormats: content.account?.allowedFormats ?? [])
    }

    /// Abre o primeiro de [streams]; os outros ficam na fila para se, também ele, não abrir (ver [failoverFailed]).
    private func openFailovers(_ streams: [FailoverStream]) {
        guard let first = streams.first else { return }
        failoverQueue = Array(streams.dropFirst())
        failoversFailed = []
        openBackupStream(first)
    }

    private func openBackupStream(_ stream: FailoverStream) {
        let stalker = loader.stalker
        let playlist = stream.playlist
        let channel = stream.channel
        failoverNow = stream
        player.playBackup(stream.candidates, userAgent: channel.userAgent ?? prefs.userAgent, referrer: channel.referrer,
                          linkResolver: playlist.type == .stalker ? { await stalker.candidates(playlist, channel: channel) } : nil)
    }

    /// O failover a abrir também não abriu: passa ao seguinte (o leitor espera). Sem mais nenhum, o cartão diz os que
    /// falharam e as tentativas voltam à lista principal.
    func failoverFailed() -> Bool {
        if let now = failoverNow { failoversFailed.append(now.label) }
        failoverNow = nil
        guard !failoverQueue.isEmpty, let key = current?.key else { return false }
        let next = failoverQueue.removeFirst()
        Task { @MainActor [weak self] in
            guard let self, self.current?.key == key, self.catchup == nil else { return }
            self.openBackupStream(next)
        }
        return true
    }

    /// O direto da lista principal não abriu: procura os failovers dele (o leitor espera pela resposta). Uma recusa da
    /// conta ou do servidor ([status]) marca a lista como em baixo (ver [skipToBackup]).
    func startFailover(status: Int?) -> Bool {
        guard catchup == nil, let content, let channel = current, backupOf(content.playlist.id) != nil else { return false }
        if ProviderOutage.providerWide(status) { providerOutage.markDown(content.playlist.id, now: Self.uptimeMs()) }
        Task { [weak self] in
            guard let self else { return }
            let streams = await self.backupStreams(content, channel)
            guard self.current?.key == channel.key, self.catchup == nil else { return }
            if streams.isEmpty { self.player.backupUnavailable() } else { self.openFailovers(streams) }
        }
        return true
    }

    /// A lista principal esteve em baixo há pouco: o canal novo vai logo aos failovers, sem esperar pelas tentativas de
    /// sempre (sem nenhum — os da própria lista não contam —, tenta-se a principal).
    func skipToBackup() -> Bool {
        guard catchup == nil, let content, let channel = current, backupOf(content.playlist.id) != nil,
              providerOutage.isDown(content.playlist.id, now: Self.uptimeMs()) else { return false }
        Task { [weak self] in
            guard let self else { return }
            let streams = await self.backupStreams(content, channel)
            guard self.current?.key == channel.key, self.catchup == nil else { return }
            if streams.isEmpty { self.player.openMain() } else { self.openFailovers(streams) }
        }
        return true
    }

    /// Um canal em direto da lista principal deu: ela já não está em baixo.
    func mainPlaying() {
        guard catchup == nil, !player.onBackup, let content else { return }
        providerOutage.clear(content.playlist.id)
    }

    static func uptimeMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    /// A abrir um failover (o canal não abriu): "RTP 1 HD (BackupTV) · 1 de 2", para a cápsula "A tentar um failover";
    /// nil fora disso (e depois de ele dar e cair, que é "a voltar a ligar").
    var failoverConnecting: String? {
        guard player.onBackup, player.state == .opening, !player.wasPlaying, player.reconnects == 0, let now = failoverNow else { return nil }
        let position = failoversFailed.count + 1
        return strings.r("backup_connecting_to", now.label, "\(position)", "\(position + failoverQueue.count)")
    }

    /// Um failover começou a dar: o aviso ("<lista> não responde. A dar da <reserva>.", ou o canal da própria lista).
    func backupPlaying() {
        guard let content, let stream = failoverNow else { return }
        if stream.sameList {
            showToast(strings.r("backup_playing_channel", current?.name ?? "", stream.channel.name))
        } else {
            showToast(strings.r("backup_playing", content.playlist.name, stream.playlist.name))
        }
    }

    /// "Experimentar agora": dá já os failovers [failovers] (pela ordem) no lugar do canal a ver; o próximo canal volta
    /// à principal.
    func tryBackup(_ failovers: [String]) {
        guard let content, let channel = current, !failovers.isEmpty else { return }
        Task { [weak self] in
            guard let self else { return }
            let streams = await self.backupStreams(content, channel, chosen: failovers)
            guard self.current?.key == channel.key else { return }
            self.openFailovers(streams)
        }
    }

    /// As escolhas de [channel] da lista [mainId] (a em uso, com nil): o painel do player e a lista "Canais de reserva".
    func backupChoices(_ channel: Channel, mainId: String? = nil) async -> BackupChoices? {
        guard let id = mainId ?? content?.playlist.id, let backup = backupOf(id), let backupContent = await backupContent(backup) else { return nil }
        let mainContent: PlaylistContent?
        if content?.playlist.id == id {
            mainContent = content
        } else if case .ready(_, let review) = backupCoverage[id] {
            mainContent = review.main
        } else {
            mainContent = nil
        }
        let m = await matcher(for: backupContent)
        let entry = ChannelMatch.Entry(channel, in: mainContent)
        let (auto, ranked) = await Task.detached(priority: .userInitiated) {
            (m.find(entry.name, epgId: entry.epgId, group: entry.group), m.ranked(entry.name, epgId: entry.epgId))
        }.value
        let similar = ranked.compactMap { backupContent.channels.indices.contains($0) ? backupContent.channels[$0] : nil }
        // Na própria lista: os parecidos sem o canal (o mesmo canal noutra qualidade, ou noutro grupo).
        var similarMain: [Channel] = []
        if let mainContent {
            let mainEntries = mainContent.channels.map { ChannelMatch.Entry($0, in: mainContent) }
            let rankedMain = await Task.detached(priority: .userInitiated) { ChannelMatcher(mainEntries).ranked(entry.name, epgId: entry.epgId) }.value
            similarMain = rankedMain.compactMap { mainContent.channels.indices.contains($0) ? mainContent.channels[$0] : nil }.filter { $0.key != channel.key }
        }
        return BackupChoices(mainId: id, backup: backup, content: backupContent, main: mainContent, auto: auto, similar: similar,
                             similarMain: similarMain, choice: backupChoice(id, channel.key), manual: backupManual(id))
    }

    /// Pesquisa nos canais da reserva e da própria lista (pelo nome, sem acentos nem maiúsculas): os da reserva primeiro,
    /// cada um com true nos da própria lista (sem o canal [except]).
    func searchBackup(_ choices: BackupChoices, _ query: String, except: String) -> [BackupHit] {
        let q = query.trimmingCharacters(in: .whitespaces).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        guard q.count >= 2 else { return [] }
        func hits(_ c: PlaylistContent?) -> [Channel] {
            (c?.channels ?? []).filter { $0.name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).contains(q) }
        }
        let backup = hits(choices.content).map { BackupHit(channel: $0, fromMain: false) }
        let main = hits(choices.main).filter { $0.key != except }.map { BackupHit(channel: $0, fromMain: true) }
        return Array((backup + main).prefix(40))
    }

    /// A etiqueta de um failover: "Esta lista" ou o nome da reserva.
    func failoverSource(_ choices: BackupChoices, _ failover: String) -> String {
        ChannelMatch.mainKey(of: failover) != nil ? strings.r("backup_this_playlist") : choices.backup.name
    }

    /// "Automático: SIC HD", "Sem correspondência" ou "Em dúvida…".
    func backupAutoLabel(_ choices: BackupChoices) -> String {
        switch choices.auto {
        case .match: return strings.r("backup_channel_auto_is", choices.autoChannel?.name ?? "")
        case .unsure: return strings.r("backup_channel_unsure")
        case .missing: return strings.r("backup_channel_no_match")
        }
    }

    /// Conta, por trás, como fica cada canal de [playlistId] na reserva (uma vez por reserva, escolhas e modo; depois de
    /// uma escolha, a anterior fica à vista até a nova estar pronta). Os nomes dos que faltam ou ficam em dúvida vão
    /// para o registo da app (só os nomes), que segue no relatório.
    func checkBackupCoverage(_ playlistId: String) {
        guard let backup = backupOf(playlistId), let main = playlists.first(where: { $0.id == playlistId }) else { return }
        let overrides = backupOverrides(playlistId)
        let manual = backupManual(playlistId)
        var previous: BackupReview?
        switch backupCoverage[playlistId] {
        case .checking: return
        case .ready(let id, let review) where id == backup.id:
            if review.overrides == overrides && review.manual == manual { return }
            previous = review
        default: break
        }
        if previous == nil { backupCoverage[playlistId] = .checking }
        let hadPrevious = previous != nil
        Task { [weak self] in
            guard let self else { return }
            let mainContent: PlaylistContent? = self.content?.playlist.id == playlistId ? self.content : await self.backupContent(main)
            guard let mainContent, let backupContent = await self.backupContent(backup) else {
                self.backupCoverage[playlistId] = .unavailable
                return
            }
            let m = await self.matcher(for: backupContent)
            let entries = mainContent.channels.map { ChannelMatch.Entry($0, in: mainContent) }
            let (rows, cov) = await Task.detached(priority: .utility) { () -> ([ChannelMatch.ReviewRow], ChannelMatch.Coverage) in
                let rows = ChannelMatch.review(entries, m, overrides: overrides, manual: manual)
                return (rows, ChannelMatch.coverage(entries, m, overrides: overrides, manual: manual, rows: rows))
            }.value
            if !hadPrevious {
                DebugLog.add("Backup coverage \(main.name) -> \(backup.name): \(cov.matched)/\(cov.total), \(cov.unsure) unsure, \(cov.missing) missing")
                // Só os nomes dos canais (nunca endereços), para estudar o que falha.
                for (label, names) in [("Unsure", cov.unsureNames), ("Missing", cov.missingNames)] where !names.isEmpty {
                    DebugLog.add("Backup \(label): " + names.prefix(400).joined(separator: " | "))
                }
            }
            let review = BackupReview(main: mainContent, backup: backupContent, rows: rows, coverage: cov, manual: manual, overrides: overrides)
            self.backupCoverage[playlistId] = .ready(backupId: backup.id, review: review)
        }
    }

    /// A lista "Canais de reserva" de [playlistId], já feita (nil enquanto se conta).
    func backupReview(_ playlistId: String) -> BackupReview? {
        if case .ready(let id, let review) = backupCoverage[playlistId], id == backupOf(playlistId)?.id { return review }
        return nil
    }

    /// Uma linha da lista: "RTP 1 → RTP 1 FHD" (com "+2" se houver mais failovers) e o estado (no manual sem escolha, a
    /// sugestão).
    func backupRowText(_ review: BackupReview, _ row: ChannelMatch.ReviewRow) -> (title: String, subtitle: String) {
        let main = review.main.channels[row.main].name
        let chosen = row.failovers.compactMap { f in
            (ChannelMatch.mainKey(of: f).map { review.main.byKey[$0] } ?? review.backup.byKey[f])?.name
        }
        let first = chosen.first ?? row.backup.flatMap { review.backup.channels.indices.contains($0) ? review.backup.channels[$0].name : nil }
        let backup = first.map { chosen.count > 1 ? "\($0) +\(chosen.count - 1)" : $0 }
        let suggestion = row.suggestion.flatMap { review.backup.channels.indices.contains($0) ? strings.r("backup_suggestion", review.backup.channels[$0].name) : nil }
        let state: String
        switch row.state {
        case .auto: state = strings.r("backup_channel_auto")
        case .chosen: state = strings.r("backup_state_chosen")
        case .off: state = strings.r("backup_state_none")
        case .unsure: state = strings.r("backup_state_unsure")
        case .missing: state = strings.r("backup_state_missing")
        case .notSet: state = strings.r("backup_state_not_set")
        }
        return (backup.map { "\(main) → \($0)" } ?? main, [state, suggestion].compactMap { $0 }.joined(separator: " · "))
    }

    func backupFilterLabel(_ filter: ChannelMatch.Filter) -> String {
        switch filter {
        case .all: return strings.all
        case .matched: return strings.r("backup_filter_matched")
        case .unsure: return strings.r("backup_state_unsure")
        case .missing: return strings.r("backup_channel_no_match")
        case .chosen: return strings.r("backup_state_chosen")
        }
    }

    /// Os canais da lista a mostrar com [filter] e a pesquisa [query] (pelo nome do canal principal).
    func backupRows(_ review: BackupReview, _ filter: ChannelMatch.Filter, _ query: String) -> [ChannelMatch.ReviewRow] {
        let q = query.trimmingCharacters(in: .whitespaces).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        return review.rows.filter { row in
            row.passes(filter) && (q.isEmpty || review.main.channels[row.main].name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).contains(q))
        }
    }

    /// O subtítulo da linha "Lista de reserva": o que faz (sem reserva), "a verificar…", ou quantos canais a reserva tem.
    func backupSubtitle(_ playlistId: String) -> String {
        guard let backup = backupOf(playlistId) else { return strings.r("backup_playlist_sub") }
        switch backupCoverage[playlistId] {
        case .ready(let id, let review) where id == backup.id:
            let cov = review.coverage
            if review.manual { return strings.r("backup_chosen", backup.name, "\(cov.matched)") }
            return strings.r("backup_coverage", backup.name, "\(cov.matched)", "\(cov.total)", "\(cov.unsure)")
        case .unavailable: return strings.r("backup_unavailable", backup.name)
        default: return strings.r("backup_checking")
        }
    }
}
