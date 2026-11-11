import Foundation
import GoatCore

/// Os canais de uma lista na reserva: a cobertura e a lista "Canais de reserva" (cada canal e o seu estado).
struct BackupReview {
    let main: PlaylistContent
    let backup: PlaylistContent
    let rows: [ChannelMatch.ReviewRow]
    let coverage: ChannelMatch.Coverage
    let manual: Bool
    let overrides: [String: String]
}

/// A cobertura da reserva de uma lista: quantos canais ela tem (o subtítulo da linha "Lista de reserva").
enum BackupCoverage {
    case checking
    case ready(backupId: String, review: BackupReview)
    case unavailable
}

/// O painel "Canal de reserva" de um canal: o automático, os parecidos e a escolha à mão.
struct BackupChoices {
    /// A lista principal.
    let mainId: String
    let backup: Playlist
    let content: PlaylistContent
    let auto: ChannelMatch.Result
    let similar: [Channel]
    /// A escolha à mão: uma chave da reserva, "off", ou nil (automático).
    var choice: String?
    /// Modo manual: o automático é só uma sugestão (e sem escolha não muda de lista).
    let manual: Bool

    /// "Nenhum" está escolhido: à mão, ou (no modo manual) por não haver escolha.
    var none: Bool { choice == ChannelMatch.off || (manual && choice == nil) }

    var autoChannel: Channel? { auto.index.flatMap { content.channels.indices.contains($0) ? content.channels[$0] : nil } }

    /// O canal a experimentar: o escolhido, ou o automático; nil com "nenhum" ou sem canal.
    var effective: Channel? {
        switch choice {
        case nil: return manual ? nil : autoChannel
        case ChannelMatch.off: return nil
        case let key?: return content.byKey[key]
        }
    }
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
            "backup_channels": .object(channels.mapValues { JSON.string($0) }),
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

    /// As escolhas à mão de [playlistId] (chave do canal → escolha).
    private func backupOverrides(_ playlistId: String) -> [String: String] {
        Dictionary(uniqueKeysWithValues: prefs.backupChannels.compactMap { k, v -> (String, String)? in
            k.hasPrefix("\(playlistId):") ? (String(k.dropFirst(playlistId.count + 1)), v) : nil
        })
    }

    /// O canal da reserva escolhido à mão para [channelKey] de [playlistId] (uma chave, "off", ou nil = automático).
    func backupChoice(_ playlistId: String, _ channelKey: String) -> String? {
        prefs.backupChannels["\(playlistId):\(channelKey)"]
    }

    func setBackupChoice(_ playlistId: String, _ channelKey: String, _ value: String?) {
        var map = prefs.backupChannels
        map["\(playlistId):\(channelKey)"] = value
        setPref("backup_channels", .object(map.mapValues { JSON.string($0) }))
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

    /// O que abrir na reserva para [channel] de [main] ([choice]: um canal já escolhido, "experimentar agora"); nil sem
    /// reserva, sem o canal (ou na dúvida), ou quando abrir a reserva passava o limite de ligações dela.
    private func backupStream(_ main: PlaylistContent, _ channel: Channel, choice: Channel? = nil)
        async -> (playlist: Playlist, channel: Channel, candidates: [StreamCandidate])? {
        guard let backup = backupOf(main.playlist.id), !sameAccount(backup, main.playlist) else { return nil }
        guard let content = await backupContent(backup) else {
            DebugLog.add("Backup: \(backup.name) channels did not load")
            return nil
        }
        // O segundo canal já usa a conta da reserva e ela só deixa uma ligação: não se abre outra.
        if let sp = second?.content.playlist, sameAccount(sp, backup) || sp.id == backup.id,
           backup.type == .stalker || (backup.type == .xtream && (content.account?.maxConnections ?? 1) < 2) {
            DebugLog.add("Backup: the second channel already uses its account")
            return nil
        }
        let target: Channel
        if let choice {
            target = choice
        } else {
            let m = await matcher(for: content)
            let manual = backupManual(main.playlist.id)
            let result = ChannelMatch.decide(ChannelMatch.Entry(channel, in: main), m, override: backupChoice(main.playlist.id, channel.key), manual: manual)
            guard let i = result.index, content.channels.indices.contains(i) else {
                DebugLog.add("Backup: \(result == .missing ? (manual ? "not chosen (manual)" : "no channel") : "unsure") for \"\(channel.name)\"")
                return nil
            }
            target = content.channels[i]
        }
        DebugLog.add("Backup: \"\(channel.name)\" -> \"\(target.name)\" (\(backup.name))")
        let candidates = streamCandidates(playlist: content.playlist, channel: target, preferred: prefs.streamFormat,
                                          allowedFormats: content.account?.allowedFormats ?? [])
        return (content.playlist, target, candidates)
    }

    private func openBackupStream(_ stream: (playlist: Playlist, channel: Channel, candidates: [StreamCandidate])) {
        let stalker = loader.stalker
        let playlist = stream.playlist
        let channel = stream.channel
        player.playBackup(stream.candidates, userAgent: channel.userAgent ?? prefs.userAgent, referrer: channel.referrer,
                          linkResolver: playlist.type == .stalker ? { await stalker.candidates(playlist, channel: channel) } : nil)
    }

    /// O direto da lista principal não abriu: procura o mesmo canal na reserva (o leitor espera pela resposta).
    func startFailover() -> Bool {
        guard catchup == nil, let content, let channel = current, let backup = backupOf(content.playlist.id),
              !sameAccount(backup, content.playlist) else { return false }
        Task { [weak self] in
            guard let self else { return }
            let stream = await self.backupStream(content, channel)
            guard self.current?.key == channel.key, self.catchup == nil else { return }
            if let stream { self.openBackupStream(stream) } else { self.player.backupUnavailable() }
        }
        return true
    }

    /// O canal da reserva começou a dar: o aviso.
    func backupPlaying() {
        guard let content, let backup = backupOf(content.playlist.id) else { return }
        showToast(strings.r("backup_playing", content.playlist.name, backup.name))
    }

    /// "Experimentar agora": dá já [target] (da reserva) no lugar do canal a ver; o próximo canal volta à principal.
    func tryBackup(_ target: Channel) {
        guard let content, let channel = current else { return }
        Task { [weak self] in
            guard let self, let stream = await self.backupStream(content, channel, choice: target), self.current?.key == channel.key else { return }
            self.openBackupStream(stream)
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
        return BackupChoices(mainId: id, backup: backup, content: backupContent, auto: auto, similar: similar,
                             choice: backupChoice(id, channel.key), manual: backupManual(id))
    }

    /// Pesquisa nos canais da reserva (pelo nome, sem acentos nem maiúsculas).
    func searchBackup(_ choices: BackupChoices, _ query: String) -> [Channel] {
        let q = query.trimmingCharacters(in: .whitespaces).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        guard q.count >= 2 else { return [] }
        return Array(choices.content.channels.lazy.filter {
            $0.name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).contains(q)
        }.prefix(40))
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

    /// Uma linha da lista: "RTP 1 → RTP 1 FHD" e o estado (no manual sem escolha, a sugestão).
    func backupRowText(_ review: BackupReview, _ row: ChannelMatch.ReviewRow) -> (title: String, subtitle: String) {
        let main = review.main.channels[row.main].name
        let backup = row.backup.flatMap { review.backup.channels.indices.contains($0) ? review.backup.channels[$0].name : nil }
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
