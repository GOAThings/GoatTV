import Foundation
import GoatCore

/// A cobertura da reserva de uma lista: quantos canais ela tem (o subtítulo da linha "Lista de reserva").
enum BackupCoverage: Equatable {
    case checking
    case ready(backupId: String, coverage: ChannelMatch.Coverage)
    case unavailable
}

/// O painel "Canal de reserva" de um canal: o automático, os parecidos e a escolha à mão.
struct BackupChoices {
    let backup: Playlist
    let content: PlaylistContent
    let auto: ChannelMatch.Result
    let similar: [Channel]
    /// A escolha à mão: uma chave da reserva, "off", ou nil (automático).
    var choice: String?

    var autoChannel: Channel? { auto.index.flatMap { content.channels.indices.contains($0) ? content.channels[$0] : nil } }

    /// O canal a experimentar: o escolhido, ou o automático; nil com "nenhum" ou sem canal.
    var effective: Channel? {
        switch choice {
        case nil: return autoChannel
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

    /// Muda a reserva de [playlistId] (nil = nenhuma). Os canais escolhidos à mão eram da reserva anterior: saem.
    func setBackupPlaylist(_ playlistId: String, _ backupId: String?) {
        let target = backupId == playlistId ? nil : backupId
        guard prefs.backupPlaylist[playlistId] != target else { return }
        var map = prefs.backupPlaylist
        map[playlistId] = target
        let channels = prefs.backupChannels.filter { !$0.key.hasPrefix("\(playlistId):") }
        setPrefs([
            "backup_playlist": .object(map.mapValues { JSON.string($0) }),
            "backup_channels": .object(channels.mapValues { JSON.string($0) }),
        ])
        backupCoverage[playlistId] = nil
    }

    /// O canal da reserva escolhido à mão para [channelKey] de [playlistId] (uma chave, "off", ou nil = automático).
    func backupChoice(_ playlistId: String, _ channelKey: String) -> String? {
        prefs.backupChannels["\(playlistId):\(channelKey)"]
    }

    func setBackupChoice(_ playlistId: String, _ channelKey: String, _ value: String?) {
        var map = prefs.backupChannels
        map["\(playlistId):\(channelKey)"] = value
        setPref("backup_channels", .object(map.mapValues { JSON.string($0) }))
        backupCoverage[playlistId] = nil
    }

    /// Os canais da reserva (a lista em uso, da cache, ou descarregados como os do segundo canal).
    private func backupContent(_ backup: Playlist) async -> PlaylistContent? {
        try? await loadForSecond(backup)
    }

    private func matcher(for content: PlaylistContent) async -> ChannelMatcher {
        if let cached = backupMatcher, cached.id == content.playlist.id, cached.fetchedAt == content.fetchedAt { return cached.matcher }
        let entries = content.channels.map(ChannelMatch.Entry.init)
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
            let result = m.find(channel.name, epgId: channel.epgId, override: backupChoice(main.playlist.id, channel.key))
            guard let i = result.index, content.channels.indices.contains(i) else {
                DebugLog.add("Backup: \(result == .missing ? "no channel" : "unsure") for \"\(channel.name)\"")
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

    /// O painel "Canal de reserva" do canal a ver.
    func backupChoices(_ channel: Channel) async -> BackupChoices? {
        guard let main = content, let backup = backupOf(main.playlist.id), let backupContent = await backupContent(backup) else { return nil }
        let m = await matcher(for: backupContent)
        let name = channel.name, epg = channel.epgId
        let (auto, ranked) = await Task.detached(priority: .userInitiated) { (m.find(name, epgId: epg), m.ranked(name, epgId: epg)) }.value
        let similar = ranked.compactMap { backupContent.channels.indices.contains($0) ? backupContent.channels[$0] : nil }
        return BackupChoices(backup: backup, content: backupContent, auto: auto, similar: similar, choice: backupChoice(main.playlist.id, channel.key))
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

    /// Conta, por trás, quantos canais de [playlistId] têm o seu na reserva. Os nomes dos que faltam ou ficam em dúvida
    /// vão para o registo da app (só os nomes), que segue no relatório.
    func checkBackupCoverage(_ playlistId: String) {
        guard let backup = backupOf(playlistId), let main = playlists.first(where: { $0.id == playlistId }) else { return }
        switch backupCoverage[playlistId] {
        case .checking: return
        case .ready(let id, _) where id == backup.id: return
        default: break
        }
        backupCoverage[playlistId] = .checking
        let overrides = Dictionary(uniqueKeysWithValues: prefs.backupChannels.compactMap { k, v -> (String, String)? in
            k.hasPrefix("\(playlistId):") ? (String(k.dropFirst(playlistId.count + 1)), v) : nil
        })
        Task { [weak self] in
            guard let self else { return }
            let mainContent: PlaylistContent? = self.content?.playlist.id == playlistId ? self.content : await self.backupContent(main)
            guard let mainContent, let backupContent = await self.backupContent(backup) else {
                self.backupCoverage[playlistId] = .unavailable
                return
            }
            let m = await self.matcher(for: backupContent)
            let entries = mainContent.channels.map(ChannelMatch.Entry.init)
            let cov = await Task.detached(priority: .utility) { ChannelMatch.coverage(entries, m, overrides: overrides) }.value
            DebugLog.add("Backup coverage \(main.name) -> \(backup.name): \(cov.matched)/\(cov.total), \(cov.unsure) unsure, \(cov.missing) missing")
            // Só os nomes dos canais (nunca endereços), para estudar o que falha.
            for (label, names) in [("Unsure", cov.unsureNames), ("Missing", cov.missingNames)] where !names.isEmpty {
                DebugLog.add("Backup \(label): " + names.prefix(400).joined(separator: " | "))
            }
            self.backupCoverage[playlistId] = .ready(backupId: backup.id, coverage: cov)
        }
    }

    /// O subtítulo da linha "Lista de reserva": o que faz (sem reserva), "a verificar…", ou quantos canais a reserva tem.
    func backupSubtitle(_ playlistId: String) -> String {
        guard let backup = backupOf(playlistId) else { return strings.r("backup_playlist_sub") }
        switch backupCoverage[playlistId] {
        case .ready(let id, let cov) where id == backup.id:
            return strings.r("backup_coverage", backup.name, "\(cov.matched)", "\(cov.total)", "\(cov.unsure)")
        case .unavailable: return strings.r("backup_unavailable", backup.name)
        default: return strings.r("backup_checking")
        }
    }
}
