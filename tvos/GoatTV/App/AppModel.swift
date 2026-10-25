import Foundation
import TVServices
import GoatCore
import Observation
import UIKit

/// O estado da app: conta, listas, canal a dar, guia. Tudo no thread principal; o trabalho pesado (descarregar e
/// ler listas e guias) corre fora dele, nas funções async do GoatCore.
@MainActor
@Observable
final class AppModel {
    enum Screen: Equatable {
        case starting
        /// Primeira vez: conta GOAT ou listas só nesta TV.
        case welcome
        /// Sem conta e sem listas: o formulário da primeira lista.
        case newPlaylist
        case activation
        case loading(String)
        case player
        case noPlaylists
        case error(ErrorInfo)
    }

    struct Activation: Equatable {
        var expires: Date?
        var code: String?
        var url: String?
        var qrText: String?
        var error: String?
    }

    // MARK: Estado

    private(set) var screen: Screen = .starting
    private(set) var activation = Activation()
    private(set) var strings = Strings(language: "auto")

    private(set) var playlists: [Playlist] = []
    private(set) var content: PlaylistContent?
    private(set) var prefs = Prefs()
    private(set) var favorites: [String: [String]] = [:]
    private(set) var recents: [String: [String]] = [:]
    private(set) var unlocked: Set<String> = []
    private(set) var email: String?
    private(set) var deviceName: String?

    private(set) var current: Channel?
    /// O canal que se via antes (seta direita volta a ele), só se lá se ficou uns segundos.
    private(set) var previous: Channel?
    /// O grupo de onde se escolheu o canal: ↑/↓ andam dentro dele.
    var groupId = SpecialCategory.all

    /// Muda quando chega guia novo (as vistas pedem os programas de novo).
    private(set) var guideVersion = 0
    private(set) var toast: String?
    /// Muda a cada mudança de canal (a barra de informação aparece).
    private(set) var zapCount = 0

    let player = PlayerEngine()
    /// O leitor do segundo canal (imagem na imagem ou ecrã dividido).
    let secondPlayer = PlayerEngine()

    /// O segundo canal: a lista dele (pode ser outra), o canal e os canais por onde ↑/↓ andam.
    struct SecondView {
        let content: PlaylistContent
        var channel: Channel
        let zapList: [Channel]
    }

    private(set) var second: SecondView?
    @ObservationIgnored private var secondContents: [String: PlaylistContent] = [:]

    /// O programa a ver em catch-up (nil = em direto).
    private(set) var catchup: Catchup?
    /// O início (ms desde 1970) do que se pediu ao servidor no catch-up: a posição é isto + o tempo do vídeo.
    @ObservationIgnored private var catchupBase: Int64 = 0
    /// Lembretes de programas que ainda vão começar (só nesta TV).
    private(set) var reminders: [Reminder] = []
    /// Quando o temporizador desliga (nil = desligado).
    private(set) var sleepAt: Date?
    /// Canais desta lista com o guia trocado na conta (channelKey → canal de guia).
    private(set) var epgMappings: [String: EpgMapping] = [:]
    @ObservationIgnored private var mappingsLoadedFor: String?
    /// Logótipos do país escolhido em "Origem dos logótipos" para a lista em uso ("playlistId:país", channelKey → URL).
    private var countryLogos: (key: String, logos: [String: String])?
    @ObservationIgnored private var countryGuide: (country: String, channels: [EpgGuideChannel])?

    // MARK: Dependências

    private let backend = Backend()
    private let session = SessionStore()
    private let cache = DiskCache.caches()
    @ObservationIgnored private var settings: [String: JSON] = [:]
    @ObservationIgnored private var syncedSettings: [String: JSON] = [:]
    @ObservationIgnored private var settingsRev: Int64?
    @ObservationIgnored private var accountGuide: AccountGuide?
    @ObservationIgnored private var mainGuide: XMLTVGuide?
    @ObservationIgnored private var extraGuide: XMLTVGuide?
    @ObservationIgnored private var shortEpg: [String: [EpgEntry]] = [:]
    @ObservationIgnored private var activationTask: Task<Void, Never>?
    @ObservationIgnored private var guideTask: Task<Void, Never>?
    @ObservationIgnored private var watchTask: Task<Void, Never>?
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var saveChain: Task<Void, Never>?
    @ObservationIgnored private var playingSince = Date()

    /// Ficar num canal pelo menos isto para ele contar como visto (recentes, canal anterior).
    private static let watchedSeconds: Double = 5
    private static let maxRecents = 20

    private var loader: PlaylistLoader {
        let s = strings
        return PlaylistLoader(
            userAgent: prefs.userAgent.isEmpty ? iptvUserAgent : prefs.userAgent,
            gunzip: { Gzip.decompress($0) },
            channelName: { s.channelName($0) }
        )
    }

    var library: Library? {
        guard let content else { return nil }
        let id = content.playlist.id
        return Library(content: content, prefs: prefs, favorites: favorites[id] ?? [], recents: recents[id] ?? [], unlocked: unlocked)
    }

    /// Os grupos da lista de grupos: os especiais e os normais (os bloqueados aparecem com cadeado).
    var groups: [ChannelGroup] {
        library?.groups(favoritesName: strings.favorites, recentName: strings.recents, allName: strings.all,
                        othersName: strings.others, includeLocked: true) ?? []
    }

    /// Os grupos que o guia percorre (← →): sem os escondidos nem os bloqueados.
    var guideGroups: [ChannelGroup] {
        library?.groups(favoritesName: strings.favorites, recentName: strings.recents, allName: strings.all,
                        othersName: strings.others, includeLocked: false, forGuide: true) ?? []
    }

    /// Todos os grupos da lista, pela ordem escolhida, incluindo os escondidos (Definições → Grupos).
    var allGroups: [ChannelGroup] {
        (library?.orderedGroups(includeHidden: true) ?? []).map {
            $0.id == PlaylistContent.others ? ChannelGroup(id: $0.id, name: strings.others) : $0
        }
    }

    func groupChannels(_ groupId: String, includeHidden: Bool) -> [Channel] {
        library?.orderedChannels(groupId, includeHidden: includeHidden) ?? []
    }

    init() {
        backend.onSessionEnded = { [weak self] in
            Task { @MainActor in self?.sessionEnded() }
        }
        loadReminders()
        player.onFailed = { [weak self] in self?.explainPlaybackFailure() }
        player.onFormatWorked = { [weak self] format in self?.formatWorked(format) }
        // Catch-up que caiu a meio: pede de novo a partir de onde ia.
        player.onDropped = { [weak self] in
            guard let self, self.catchup != nil else { return false }
            let position = self.catchupPosition
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(Double(max(1, self?.prefs.reconnectSeconds ?? 5))))
                self?.seekCatchup(position)
            }
            return true
        }
    }

    // MARK: Erros do player

    /// O erro do canal a dar (explicado como no Android) e a linha de estado ("Nova tentativa (1/3) em 5 s…").
    private(set) var playbackError: ErrorInfo?
    private(set) var playbackStatus: String?

    /// O canal não abriu: explica porquê (pergunta ao servidor Xtream se a conta está ocupada antes de o dizer).
    private func explainPlaybackFailure() {
        guard let content else { return }
        playbackError = ErrorInfo(title: strings.r("cant_open_channel").trimmingCharacters(in: CharacterSet(charactersIn: ".")))
        playbackStatus = strings.r("analysing_error")
        let playlist = content.playlist
        let loader = self.loader
        let retrying = player.state == .failed && player.retries > 0 && player.retries <= PlayerEngine.maxStartRetries && prefs.reconnectSeconds > 0
        let retries = player.retries
        let http = player.lastHTTPStatus
        let inCatchup = catchup != nil
        Task { [weak self] in
            let connections = playlist.type == .xtream ? await loader.connections(playlist) : nil
            guard let self else { return }
            let busy = playlist.type == .xtream && connections.map { $0.active >= $0.max } == true
            let limit = connections?.max ?? content.account?.maxConnections
            var info: ErrorInfo
            if inCatchup, !busy {
                info = self.strings.errCatchup
            } else if busy {
                info = self.strings.errAccountBusy(limit: limit, sure: true)
            } else if let http {
                switch http {
                case 404: info = self.strings.errChannelGone
                case 429: info = self.strings.errChannelRateLimited
                case 500...599: info = self.strings.errProviderServer(http)
                case 400...499: info = self.strings.errChannelRefused(http)
                default: info = self.strings.errCantPlay
                }
            } else if playlist.type == .xtream {
                info = self.strings.errAccountBusy(limit: limit, sure: false)
            } else {
                info = self.strings.errChannelUnreachable
            }
            if info.detail == nil { info.detail = self.strings.technical(self.player.backendName + (http.map { " · HTTP \($0)" } ?? "")) }
            guard self.player.state == .failed else { return }
            self.playbackError = info
            self.playbackStatus = retrying
                ? self.strings.r("retry_in", "\(retries)", "\(PlayerEngine.maxStartRetries)", "\(self.prefs.reconnectSeconds)")
                : nil
        }
    }

    /// OK no erro: tenta outra vez já.
    func retryPlayback() {
        playbackError = nil
        playbackStatus = nil
        player.retryNow()
    }

    /// Um canal Xtream abriu num formato: fica a ser o preferido (como no Android).
    private func formatWorked(_ format: String) {
        playbackError = nil
        playbackStatus = nil
        guard content?.playlist.type == .xtream, catchup == nil, format != prefs.streamFormat else { return }
        setPref("stream_format", .string(format))
    }

    // MARK: Arranque

    // MARK: Sem conta (listas só nesta TV)

    /// A TV usa listas próprias, sem conta.
    private(set) var isLocal = UserDefaults.standard.bool(forKey: "local")
    private static let localKey = "local.config"

    /// Abre as listas guardadas nesta TV (ou o formulário da primeira).
    func startLocal() {
        isLocal = true
        UserDefaults.standard.set(true, forKey: "local")
        activationTask?.cancel()
        let raw = UserDefaults.standard.data(forKey: Self.localKey).flatMap { try? JSON.parse($0) } ?? .object([:])
        apply(Backend.parseConfig(raw))
        guard let playlist = startPlaylist() else {
            screen = .newPlaylist
            return
        }
        if let cached = cachedContent(for: playlist) {
            show(cached, resume: true)
        } else {
            Task { await load(playlist, resume: true) }
        }
    }

    /// Guarda nesta TV as listas, favoritos e definições (o mesmo formato da conta).
    private func persistLocal() {
        let json = JSON.object([
            "playlists": .array(playlists.map { p in
                var o = Backend.playlistBody(p)
                o["id"] = .string(p.id)
                return .object(o)
            }),
            "favorites": .object(favorites.mapValues { JSON.strings($0) }),
            "settings": .object(settings),
        ])
        UserDefaults.standard.set(json.encoded(), forKey: Self.localKey)
        syncedSettings = settings
    }

    /// Voltar no formulário da primeira lista: regressa às boas-vindas.
    func leaveNewPlaylist() {
        isLocal = false
        UserDefaults.standard.set(false, forKey: "local")
        screen = .welcome
    }

    /// Escolhe a conta: do ecrã de boas-vindas, ou das Definições de uma TV sem conta.
    func connectAccount() {
        flushWatched()
        startActivation()
    }

    /// Voltar na ativação: regressa às listas desta TV (se as havia) ou às boas-vindas.
    func leaveActivation() {
        activationTask?.cancel()
        if isLocal, content != nil {
            screen = .player
        } else if isLocal {
            startLocal()
        } else {
            screen = .welcome
        }
    }

    /// Cria ([id] nil) ou altera uma lista: na conta, ou nesta TV. Devolve o erro a mostrar, ou nil.
    func savePlaylist(id: String?, form: PlaylistForm) async -> String? {
        if let problem = form.problem { return formProblem(problem) }
        let first = content == nil
        if isLocal || !hasAccount {
            let playlist = form.playlist(id: id ?? "local" + String(UUID().uuidString.prefix(12)).lowercased())
            if !isLocal {
                isLocal = true
                UserDefaults.standard.set(true, forKey: "local")
            }
            if let i = playlists.firstIndex(where: { $0.id == playlist.id }) { playlists[i] = playlist } else { playlists.append(playlist) }
            persistLocal()
            if first || id == nil || id == content?.playlist.id { open(playlist) }
            return nil
        }
        do {
            let saved = try await backend.savePlaylist(id: id, form.playlist(id: id ?? ""))
            if let i = playlists.firstIndex(where: { $0.id == saved.id }) { playlists[i] = saved } else { playlists.append(saved) }
            if first || id == nil || id == content?.playlist.id { open(saved) }
            return nil
        } catch let e as Backend.PlaylistRejected {
            return playlistError(e)
        } catch {
            return strings.saveOffline
        }
    }

    /// Apaga uma lista: da conta (em todas as TVs) ou desta TV. Devolve o erro a mostrar, ou nil.
    func deletePlaylist(_ playlist: Playlist) async -> String? {
        if !isLocal {
            do {
                try await backend.deletePlaylist(id: playlist.id)
            } catch let e as Backend.PlaylistRejected {
                return playlistError(e)
            } catch {
                return strings.saveOffline
            }
        }
        playlists.removeAll { $0.id == playlist.id }
        cache.remove("content-\(playlist.id)")
        if isLocal { persistLocal() }
        if content?.playlist.id == playlist.id {
            if let next = playlists.first {
                open(next)
            } else {
                player.stop()
                content = nil
                current = nil
                screen = isLocal ? .newPlaylist : .noPlaylists
            }
        }
        return nil
    }

    /// Passa a ver [playlist] (a lista nova, ou a editada em uso, com os dados novos).
    private func open(_ playlist: Playlist) {
        if content == nil || screen != .player { screen = .loading(playlist.name) }
        settings["last_playlist"] = .string(playlist.id)
        session.lastPlaylist = playlist.id
        saveSettings()
        cache.remove("content-\(playlist.id)")
        Task { await load(playlist, resume: true) }
    }

    private func formProblem(_ p: PlaylistForm.Problem) -> String {
        switch p {
        case .server: return strings.t("Enter the server", "Falta o servidor")
        case .username: return strings.t("Enter the username", "Falta o utilizador")
        case .password: return strings.t("Enter the password", "Falta a password")
        case .url: return strings.t("Enter the playlist address", "Falta o endereço da lista")
        }
    }

    private func playlistError(_ e: Backend.PlaylistRejected) -> String {
        switch e.reason {
        case "username_required": return formProblem(.username)
        case "password_required": return formProblem(.password)
        case "m3u_required": return formProblem(.url)
        case "server_invalid": return strings.t("The server address isn't valid", "O endereço do servidor não é válido")
        case "m3u_invalid": return strings.t("The playlist address isn't valid", "O endereço da lista não é válido")
        case "epg_invalid": return strings.t("The guide address isn't valid", "O endereço do guia não é válido")
        default: return strings.saveFailed(e.status)
        }
    }

    func start() {
        guard screen == .starting else { return }
        #if DEBUG
        // Testes de interface (UITests/): começa sem conta, só com a lista de demonstração servida pelo runner.
        if let url = UserDefaults.standard.string(forKey: "UITestPlaylist") {
            let demo = JSON.object([
                "playlists": .array([.object(["id": .string("demo"), "type": .string("m3u"), "name": .string("Demo"),
                                              "url": .string(url), "epg_url": .string("")])]),
                "favorites": .object([:]),
                "settings": .object([:]),
            ])
            UserDefaults.standard.set(demo.encoded(), forKey: Self.localKey)
            UserDefaults.standard.set(true, forKey: "local")
            isLocal = true
        }
        #endif
        checkForUpdate()
        if isLocal, session.load() == nil {
            startLocal()
            return
        }
        guard let saved = session.load() else {
            screen = .welcome
            return
        }
        backend.token = saved.token
        email = saved.email
        deviceName = saved.deviceName
        // Arranca já com o que ficou da última vez; a conta atualiza-se por trás.
        if let raw = cache.load(JSON.self, key: "config") {
            apply(Backend.parseConfig(raw))
            if let playlist = startPlaylist(), let cached = cachedContent(for: playlist) {
                show(cached, resume: true)
            }
        }
        Task { await refreshAccount() }
    }

    private func refreshAccount() async {
        do {
            if let token = try await backend.authRefresh() {
                backend.token = token
                session.saveToken(token)
            }
        } catch Backend.Failure.http(let code) where code == 401 || code == 404 {
            sessionEnded()
            return
        } catch {
            // Sem rede agora: segue com o token que há.
        }
        do {
            let config = try await backend.config()
            cache.save(config.raw, as: "config")
            let previousPlaylist = content?.playlist
            apply(config)
            adoptLocalProfile()
            guard let playlist = startPlaylist() else {
                content = nil
                player.stop()
                screen = .noPlaylists
                return
            }
            if previousPlaylist != playlist || content == nil {
                await load(playlist, resume: true)
            } else if isDue(prefs.playlistRefreshHours, since: content?.fetchedAt) {
                await load(playlist, resume: true, quiet: true)
            }
        } catch Backend.Failure.http(let code) {
            if content == nil { screen = .error(strings.errBackend(code)) }
        } catch {
            if content == nil { screen = .error(strings.errBackendOffline()) }
        }
    }

    /// A TV estava sem conta e ligou-se uma: as definições e os favoritos daqui passam para a conta (como no
    /// Android): as definições se a conta ainda não tiver nenhumas; os favoritos nas listas da conta que são as
    /// mesmas (mesmo servidor e utilizador, ou mesmo URL) e ainda não têm. As listas em si não passam.
    @ObservationIgnored private var pendingLocalProfile: (playlists: [Playlist], favorites: [String: [String]], settings: [String: JSON])?

    private func adoptLocalProfile() {
        guard let local = pendingLocalProfile else { return }
        pendingLocalProfile = nil
        func same(_ a: Playlist, _ b: Playlist) -> Bool {
            a.type == b.type && (a.type == .xtream
                ? a.server?.lowercased() == b.server?.lowercased() && a.username == b.username
                : a.url == b.url)
        }
        var ids: [String: String] = [:]
        for lp in local.playlists { if let p = playlists.first(where: { same($0, lp) }) { ids[lp.id] = p.id } }
        func remap(_ json: JSON) -> JSON {
            var text = String(decoding: json.encoded(), as: UTF8.self)
            for (from, to) in ids { text = text.replacingOccurrences(of: "\"\(from)", with: "\"\(to)") }
            return (try? JSON.parse(Data(text.utf8))) ?? json
        }
        var moved = false
        if settings.isEmpty, !local.settings.isEmpty, let remapped = remap(.object(local.settings)).object {
            settings = remapped
            applySettings()
            saveSettings()
            moved = true
        }
        var adopted = false
        for (from, keys) in local.favorites where !keys.isEmpty {
            guard let to = ids[from], (favorites[to] ?? []).isEmpty else { continue }
            favorites[to] = keys
            adopted = true
        }
        if adopted {
            moved = true
            let body = JSON.object(favorites.mapValues { JSON.strings($0) })
            enqueueSave { [weak self] in _ = try? await self?.backend.updateProfile(["favorites": body]) }
        }
        if moved { showToast(strings.r("local_profile_moved")) }
    }

    private func apply(_ config: Backend.Config) {
        playlists = config.playlists
        favorites = config.favorites
        settings = config.settings
        syncedSettings = config.settings
        settingsRev = config.settingsRev
        if let name = config.deviceName { deviceName = name }
        if let mail = config.email { email = mail }
        applySettings()
    }

    private func applySettings() {
        prefs = Prefs(settings: .object(settings))
        recents = Backend.parseKeyLists(settings["recent_channels"])
        strings = Strings(language: prefs.language)
        player.reconnectSeconds = prefs.reconnectSeconds
        player.cachingMs = Prefs.Buffer.milliseconds(prefs.buffer)
        player.aspect = prefs.aspect
        for engine in [player, secondPlayer] {
            engine.freezeFrame = prefs.freezeFrame
            engine.preferSurround = prefs.surroundDefault
            engine.passthrough = prefs.audioPassthrough
            engine.engine = prefs.engine
            engine.softwareDecoder = prefs.decoder == "sw"
            engine.deinterlace = prefs.deinterlace
            engine.hdr = prefs.hdr
        }
        secondPlayer.controlsDisplay = false
        player.matchFrameRate = prefs.afr
        Fonts.scale = CGFloat(Prefs.TextSize.scale(prefs.textSize)) * CGFloat(prefs.uiScale) / 100
    }

    /// Muda uma definição da conta (nil repõe o padrão) e grava-a; vale também para as outras TVs.
    func setPref(_ key: String, _ value: JSON?) {
        if let value { settings[key] = value } else { settings.removeValue(forKey: key) }
        applySettings()
        saveSettings()
    }

    // MARK: Organizar (Definições → Listas → lista)

    func isGroupHidden(_ groupId: String) -> Bool {
        guard let id = content?.playlist.id else { return false }
        return prefs.isHidden(id, groupId)
    }

    func setGroupHidden(_ groupId: String, _ hidden: Bool) {
        guard let id = content?.playlist.id else { return }
        var set = prefs.hiddenGroups
        if hidden { set.insert("\(id):\(groupId)") } else { set.remove("\(id):\(groupId)") }
        setPref("hidden_groups", .strings(set.sorted()))
    }

    func isChannelHidden(_ channel: Channel) -> Bool {
        guard let id = content?.playlist.id else { return false }
        return prefs.isChannelHidden(id, channel.key)
    }

    func setChannelHidden(_ channel: Channel, _ hidden: Bool) {
        guard let id = content?.playlist.id else { return }
        var set = prefs.hiddenChannels
        if hidden { set.insert("\(id):\(channel.key)") } else { set.remove("\(id):\(channel.key)") }
        setPref("hidden_channels", .strings(set.sorted()))
    }

    /// Guia XMLTV extra de uma lista (para os canais que o guia dela não tem); nil tira-o.
    func setExtraEpg(_ playlistId: String, _ url: String?) {
        let clean = url?.trimmingCharacters(in: .whitespacesAndNewlines)
        var map = prefs.extraEpg
        if let clean, clean.hasPrefix("http://") || clean.hasPrefix("https://") { map[playlistId] = clean } else { map.removeValue(forKey: playlistId) }
        setPref("extra_epg", .object(map.mapValues { JSON.string($0) }))
        if content?.playlist.id == playlistId { loadGuides(force: true) }
    }

    /// Volta a descarregar os canais da lista em uso.
    func reloadPlaylist() {
        guard let playlist = content?.playlist else { return }
        Task { await load(playlist, resume: false, quiet: true) }
    }

    /// Volta a descarregar o guia.
    func refreshEpg() {
        shortEpg = [:]
        loadGuides(force: true)
        showToast(strings.t("Updating the TV guide…", "A atualizar o guia…"))
    }

    // MARK: Controlo parental

    @ObservationIgnored private var settingsUnlocked = false

    func checkPin(_ pin: String) -> Bool { prefs.pinHash == nil || Prefs.hashPin(pin) == prefs.pinHash }

    var settingsNeedPin: Bool { prefs.settingsPin && prefs.pinHash != nil && !settingsUnlocked }

    func unlockSettings(_ pin: String) -> Bool {
        guard checkPin(pin) else { return false }
        settingsUnlocked = true
        return true
    }

    func isGroupLocked(_ groupId: String) -> Bool { library?.isGroupLocked(groupId) ?? false }

    func isGroupLockable(_ groupId: String) -> Bool {
        guard let id = content?.playlist.id else { return false }
        return prefs.isLocked(id, groupId)
    }

    /// Confere o PIN; se estiver certo, o grupo fica desbloqueado até a app fechar.
    func unlockGroup(_ groupId: String, pin: String) -> Bool {
        guard let id = content?.playlist.id, Prefs.hashPin(pin) == prefs.pinHash else { return false }
        unlocked.insert("\(id):\(groupId)")
        return true
    }

    /// Define (ou tira, com nil) o PIN. Sem PIN não há grupos bloqueados.
    func setPin(_ pin: String?) {
        settings["parental_pin_sha256"] = pin.map { .string(Prefs.hashPin($0)) }
        if pin == nil {
            settings.removeValue(forKey: "parental_pin_sha256")
            settings["locked_groups"] = .array([])
        }
        unlocked = []
        applySettings()
        saveSettings()
    }

    func toggleGroupLock(_ groupId: String) {
        guard let id = content?.playlist.id else { return }
        var set = prefs.lockedGroups
        let key = "\(id):\(groupId)"
        if set.contains(key) { set.remove(key) } else { set.insert(key) }
        setPref("locked_groups", .strings(set.sorted()))
    }

    /// A largura de um painel (dp do Android a dobrar, com a escala da interface).
    func panelPoints(_ dp: Int) -> CGFloat {
        CGFloat(dp) * 2 * CGFloat(prefs.uiScale) / 100
    }

    var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    private func startPlaylist() -> Playlist? {
        let lastId = pendingOpen?.playlist ?? settings["last_playlist"]?.text ?? session.lastPlaylist
        return playlists.first { $0.id == lastId } ?? playlists.first
    }

    private func cachedContent(for playlist: Playlist) -> PlaylistContent? {
        guard let cached = cache.load(PlaylistContent.self, key: "content-\(playlist.id)"), cached.playlist == playlist else { return nil }
        return cached
    }

    private func isDue(_ hours: Int, since date: Date?) -> Bool {
        guard let date else { return true }
        if hours <= 0 { return Date().timeIntervalSince(date) > 3600 }
        return Date().timeIntervalSince(date) >= Double(hours) * 3600
    }

    // MARK: Ativação

    private func startActivation() {
        screen = .activation
        activation = Activation()
        activationTask?.cancel()
        activationTask = Task { await runActivation() }
    }

    private func runActivation() async {
        while !Task.isCancelled {
            let start: Backend.DeviceStart
            do {
                start = try await backend.deviceStart(deviceName: Self.deviceLabel())
            } catch Backend.Failure.http(let code) {
                activation.error = strings.activateServerError(code)
                try? await Task.sleep(for: .seconds(code == 429 ? 30 : 10))
                continue
            } catch is CancellationError {
                return
            } catch {
                activation.error = strings.activateOffline
                try? await Task.sleep(for: .seconds(5))
                continue
            }
            activation = Activation(
                expires: Date().addingTimeInterval(Double(start.expiresIn)),
                code: start.code,
                url: start.verificationURL.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: ""),
                qrText: start.verificationURLComplete ?? start.verificationURL,
                error: nil
            )
            let expires = Date().addingTimeInterval(Double(start.expiresIn))
            var interval = Double(start.interval)
            // Pergunta de tantos em tantos segundos até aprovar ou expirar; ao expirar, pede código novo.
            poll: while Date() < expires, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(interval))
                guard let result = try? await backend.devicePoll(secret: start.deviceSecret) else { continue }
                switch result {
                case .approved(let approval):
                    approved(approval)
                    return
                case .expired:
                    break poll
                case .slowDown:
                    interval += 5
                case .pending:
                    break
                }
            }
        }
    }

    private func approved(_ a: Backend.Approval) {
        if isLocal { pendingLocalProfile = (playlists, favorites, settings) }
        isLocal = false
        UserDefaults.standard.set(false, forKey: "local")
        session.save(.init(token: a.token, userId: a.userId, email: a.email, deviceId: a.deviceId, deviceName: a.deviceName))
        backend.token = a.token
        email = a.email
        deviceName = a.deviceName
        screen = .loading("")
        Task { await refreshAccount() }
    }

    /// O nome desta TV na conta (aparece em thegoat.systems → Dispositivos).
    private static func deviceLabel() -> String {
        let name = UIDevice.current.name
        return name.isEmpty ? "Apple TV" : name
    }

    private func sessionEnded() {
        guard screen != .activation else { return }
        signOut(message: strings.sessionEnded)
    }

    func signOut(message: String? = nil) {
        player.stop()
        session.clear()
        cache.clear()
        backend.token = nil
        content = nil
        current = nil
        previous = nil
        playlists = []
        favorites = [:]
        settings = [:]
        syncedSettings = [:]
        accountGuide = nil
        mainGuide = nil
        extraGuide = nil
        shortEpg = [:]
        epgMappings = [:]
        mappingsLoadedFor = nil
        screen = .welcome
        if let message { showToast(message) }
    }

    func retry() {
        screen = content == nil ? .loading("") : .player
        Task { await refreshAccount() }
    }

    // MARK: Listas

    func switchPlaylist(_ playlist: Playlist) {
        guard playlist.id != content?.playlist.id else { return }
        settings["last_playlist"] = .string(playlist.id)
        session.lastPlaylist = playlist.id
        saveSettings()
        if let cached = cachedContent(for: playlist) {
            show(cached, resume: true)
            if isDue(prefs.playlistRefreshHours, since: cached.fetchedAt) {
                Task { await load(playlist, resume: false, quiet: true) }
            }
        } else {
            Task { await load(playlist, resume: true) }
        }
    }

    /// Descarrega a lista. [quiet]: já há canais no ecrã, só se trocam quando chegar a nova.
    private func load(_ playlist: Playlist, resume: Bool, quiet: Bool = false) async {
        if !quiet || content == nil { screen = .loading(playlist.name) }
        do {
            let loaded = try await loader.load(playlist)
            if loaded.channels.isEmpty {
                if content == nil || !quiet { screen = .error(strings.errPlaylistEmpty(playlist.name)) }
                return
            }
            cache.save(loaded, as: "content-\(playlist.id)")
            show(loaded, resume: resume && !(quiet && content?.playlist.id == playlist.id))
        } catch is CancellationError {
            return
        } catch {
            if content == nil || !quiet { screen = .error(strings.explainPlaylistError(playlist.name, error)) }
        }
    }

    /// Põe a lista no ecrã. [resume]: abre o último canal visto (ou o primeiro).
    private func show(_ loaded: PlaylistContent, resume: Bool) {
        let samePlaylist = content?.playlist.id == loaded.playlist.id
        content = loaded
        screen = .player
        if !samePlaylist {
            accountGuide = nil
            mainGuide = nil
            extraGuide = nil
            shortEpg = [:]
            groupId = SpecialCategory.all
            epgMappings = [:]
            mappingsLoadedFor = nil
            countryLogos = nil
        }
        loadGuides()
        if hasAccount { loadEpgMappings(loaded) }
        guard let library else { return }
        // Um canal escolhido na Top Shelf.
        if let open = pendingOpen {
            if open.playlist == loaded.playlist.id {
                pendingOpen = nil
                if let channel = loaded.byKey[open.key], !library.isGroupLocked(channel.categoryId) {
                    play(channel, from: groupFor(channel))
                    return
                }
            } else if let playlist = playlists.first(where: { $0.id == open.playlist }) {
                Task { @MainActor in self.switchPlaylist(playlist) }
            } else {
                pendingOpen = nil
            }
        }
        if resume || current == nil || !samePlaylist {
            let lastKey = settings["last_playlist"]?.text == loaded.playlist.id
                ? settings["last_channel"]?.text
                : (session.lastPlaylist == loaded.playlist.id ? session.lastChannel : nil)
            let channel = lastKey.flatMap { loaded.byKey[$0] }.flatMap { library.isGroupLocked($0.categoryId) ? nil : $0 }
                ?? library.channels(in: SpecialCategory.all).first
            if let channel, channel != current { play(channel, from: groupId, remember: false) }
        } else if let current, let updated = loaded.byKey[current.key] {
            self.current = updated
        }
    }

    // MARK: Canais

    func play(_ channel: Channel, from group: String? = nil, remember: Bool = true) {
        guard let content else { return }
        if let group { groupId = group }
        catchup = nil
        playbackError = nil
        playbackStatus = nil
        if let current, current.key != channel.key, remember, Date().timeIntervalSince(playingSince) >= Self.watchedSeconds {
            previous = current
        }
        flushWatched()
        current = channel
        playingSince = Date()
        zapCount += 1
        let candidates = streamCandidates(playlist: content.playlist, channel: channel, preferred: prefs.streamFormat,
                                          allowedFormats: content.account?.allowedFormats ?? [])
        player.play(candidates, userAgent: channel.userAgent ?? prefs.userAgent, referrer: channel.referrer)
        saveLast(channel, watched: false)
        watchTask?.cancel()
        watchTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.watchedSeconds))
            guard !Task.isCancelled, let self, self.current?.key == channel.key else { return }
            self.saveLast(channel, watched: true)
        }
        loadShortEpgIfNeeded(channel)
    }

    /// ↑/↓: o canal seguinte ou anterior do grupo (dá a volta nas pontas).
    func zap(_ delta: Int) {
        guard let library, let current else { return }
        var list = library.channels(in: groupId)
        if list.isEmpty { list = library.channels(in: SpecialCategory.all) }
        guard list.count > 1 else { return }
        let step = prefs.reverseZap ? -delta : delta
        let index = list.firstIndex { $0.key == current.key } ?? 0
        let next = list[((index + step) % list.count + list.count) % list.count]
        play(next)
    }

    /// O grupo de onde vem um canal (para ↑/↓ andarem nele).
    func groupFor(_ channel: Channel) -> String {
        channels(in: groupId).contains { $0.key == channel.key } ? groupId : SpecialCategory.all
    }

    // MARK: Pesquisa

    struct SearchHit: Identifiable, Equatable {
        let channel: Channel
        var programme: EpgEntry?
        var id: String { "\(channel.uid):\(programme?.startMs ?? 0)" }
    }

    /// Procura canais pelo nome e programas pelo título (sem acentos nem maiúsculas), como no Android.
    func search(_ query: String) -> [SearchHit] {
        guard let content, let library else { return [] }
        let q = Self.searchKey(query)
        guard q.count >= 2 else { return [] }
        let visible = content.channels.filter {
            !(library.isGroupLocked($0.categoryId) || library.isGroupHidden($0.categoryId) || library.isChannelHidden($0))
        }
        let channels = visible.lazy.filter { Self.searchKey($0.name).contains(q) }.prefix(40).map { SearchHit(channel: $0) }
        let now = nowMs()
        let until = now + 12 * 3_600_000
        var shows: [SearchHit] = []
        for ch in visible where shows.count < 40 {
            let hits = self.programmes(for: ch).filter { $0.endMs > now && $0.startMs < until && Self.searchKey($0.title).contains(q) }.prefix(2)
            shows += hits.map { SearchHit(channel: ch, programme: $0) }
        }
        return Array(channels) + shows.prefix(40).sorted { ($0.programme?.startMs ?? 0) < ($1.programme?.startMs ?? 0) }
    }

    private static func searchKey(_ s: String) -> String {
        s.lowercased().folding(options: .diacriticInsensitive, locale: nil)
    }

    // MARK: Catch-up

    func canCatchup(_ channel: Channel, _ entry: EpgEntry) -> Bool {
        guard let content else { return false }
        return Catchup.available(playlist: content.playlist, channel: channel, entry: entry)
    }

    /// Vê [entry] em catch-up, desde o início (ou desde [positionMs]).
    func playCatchup(_ channel: Channel, _ entry: EpgEntry, positionMs: Int64 = 0) {
        if current?.key != channel.key { play(channel, from: groupFor(channel)) }
        startCatchup(Catchup(channel: channel, title: entry.title, startMs: entry.startMs, endMs: entry.endMs, positionMs: max(0, positionMs)))
    }

    private func startCatchup(_ cu: Catchup) {
        guard let content, let start = catchupCandidates(playlist: content.playlist, catchup: cu, timezone: content.account?.timezone) else { return }
        catchup = cu
        catchupBase = start.startMs
        player.play(start.candidates, userAgent: cu.channel.userAgent ?? prefs.userAgent, referrer: cu.channel.referrer)
    }

    /// Onde vai o catch-up, em ms desde o início do programa.
    var catchupPosition: Int64 {
        guard let catchup else { return 0 }
        guard player.state == .playing else { return catchup.positionMs }
        return catchupBase - catchup.startMs + player.positionMs
    }

    /// Avança/recua para [positionMs]; passar do direto (ou do fim) volta ao direto.
    func seekCatchup(_ positionMs: Int64) {
        guard let cu = catchup else { return }
        let liveEdge = nowMs() - cu.startMs - Catchup.minAgeMs
        if positionMs >= liveEdge || positionMs >= cu.durationMs {
            exitCatchup()
            return
        }
        var next = cu
        next.positionMs = max(0, positionMs)
        startCatchup(next)
    }

    func exitCatchup() {
        guard catchup != nil, let current else { return }
        catchup = nil
        play(current, remember: false)
    }

    // MARK: Lembretes

    struct Reminder: Codable, Equatable, Identifiable {
        let playlistId: String
        let channelKey: String
        let channelName: String
        let title: String
        let startMs: Int64
        var id: String { "\(playlistId):\(channelKey):\(startMs)" }
    }

    private static let remindersKey = "reminders"
    private static let reminderAheadMs: Int64 = 60_000
    private static let reminderGraceMs: Int64 = 10 * 60_000

    private func loadReminders() {
        guard let data = UserDefaults.standard.data(forKey: Self.remindersKey),
              let list = try? JSONDecoder().decode([Reminder].self, from: data) else { return }
        let now = nowMs()
        reminders = list.filter { $0.startMs > now - Self.reminderGraceMs }
    }

    private func saveReminders() {
        if let data = try? JSONEncoder().encode(reminders) { UserDefaults.standard.set(data, forKey: Self.remindersKey) }
    }

    func hasReminder(_ channel: Channel, _ entry: EpgEntry) -> Bool {
        guard let id = content?.playlist.id else { return false }
        return reminders.contains { $0.playlistId == id && $0.channelKey == channel.key && $0.startMs == entry.startMs }
    }

    /// Marca/desmarca o lembrete de um programa que ainda vai começar.
    func toggleReminder(_ channel: Channel, _ entry: EpgEntry) {
        guard let id = content?.playlist.id else { return }
        if let i = reminders.firstIndex(where: { $0.playlistId == id && $0.channelKey == channel.key && $0.startMs == entry.startMs }) {
            reminders.remove(at: i)
            showToast(strings.t("Reminder removed", "Lembrete tirado"))
        } else {
            reminders.append(Reminder(playlistId: id, channelKey: channel.key, channelName: channel.name, title: entry.title, startMs: entry.startMs))
            reminders.sort { $0.startMs < $1.startMs }
            showToast(strings.t("You'll be reminded: %@", "Lembrete marcado: %@", "\(entry.title)"))
        }
        saveReminders()
    }

    /// O lembrete a mostrar agora (o programa começa dentro de 1 min, ou começou há pouco).
    func dueReminder(_ now: Int64) -> Reminder? {
        guard let id = content?.playlist.id else { return nil }
        return reminders.first { $0.playlistId == id && $0.startMs <= now + Self.reminderAheadMs && $0.startMs > now - Self.reminderGraceMs }
    }

    /// O lembrete já foi mostrado: sai da lista (e, com [watch], muda para o canal).
    func consumeReminder(_ reminder: Reminder, watch: Bool) {
        reminders.removeAll { $0 == reminder }
        saveReminders()
        guard watch, let channel = content?.byKey[reminder.channelKey] else { return }
        play(channel, from: groupFor(channel))
    }

    // MARK: Temporizador

    func setSleepTimer(_ minutes: Int?) {
        sleepAt = minutes.map { Date().addingTimeInterval(Double($0) * 60) }
        if let minutes {
            showToast(strings.t("The TV turns off in %@ min", "Desliga daqui a %@ min", "\(minutes)"))
        } else {
            showToast(strings.t("Sleep timer off", "Temporizador desligado"))
        }
    }

    /// O temporizador acabou: para o vídeo e sai para o ecrã inicial do Apple TV.
    func sleepNow() {
        sleepAt = nil
        flushWatched()
        player.stop()
        _ = UIApplication.shared.perform(NSSelectorFromString("suspend"))
    }

    /// Pede os programas curtos (Xtream) de um canal sem guia (ex.: ao passar por ele no guia).
    func requestShortEpg(_ channel: Channel) {
        loadShortEpgIfNeeded(channel)
    }

    // MARK: Segundo canal

    /// As duas listas usam a mesma conta do fornecedor (a mesma lista, ou o mesmo servidor e utilizador).
    private func sameAccount(_ a: Playlist, _ b: Playlist) -> Bool {
        a.id == b.id || (a.type == .xtream && b.type == .xtream &&
            a.server?.trimmingCharacters(in: .whitespaces).lowercased() == b.server?.trimmingCharacters(in: .whitespaces).lowercased() &&
            a.username == b.username)
    }

    /// Pode-se ver o segundo canal de [playlist]? Da conta da lista principal só se o plano deixar 2+ ligações.
    func secondAllowed(_ playlist: Playlist) -> Bool {
        guard let main = content else { return true }
        guard sameAccount(playlist, main.playlist), main.playlist.type == .xtream else { return true }
        return (main.account?.maxConnections ?? 0) >= 2
    }

    /// Os canais de [playlist] para escolher o segundo canal (a lista em uso, da cache, ou descarregados).
    func loadForSecond(_ playlist: Playlist) async throws -> PlaylistContent {
        if let content, content.playlist.id == playlist.id { return content }
        if let loaded = secondContents[playlist.id] { return loaded }
        let loaded: PlaylistContent
        if let cached = cachedContent(for: playlist) {
            loaded = cached
        } else {
            loaded = try await loader.load(playlist)
        }
        secondContents[playlist.id] = loaded
        return loaded
    }

    private func secondLibrary(_ c: PlaylistContent) -> Library {
        Library(content: c, prefs: prefs, favorites: favorites[c.playlist.id] ?? [], recents: recents[c.playlist.id] ?? [], unlocked: unlocked)
    }

    func groupsForSecond(_ c: PlaylistContent) -> [ChannelGroup] {
        secondLibrary(c).groups(favoritesName: strings.favorites, recentName: strings.recents, allName: strings.all, othersName: strings.others)
    }

    func channelsForSecond(_ c: PlaylistContent, _ groupId: String) -> [Channel] {
        secondLibrary(c).channels(in: groupId)
    }

    func openSecond(_ c: PlaylistContent, _ channel: Channel, groupId: String) {
        let list = channelsForSecond(c, groupId)
        second = SecondView(content: c, channel: channel, zapList: list.isEmpty ? [channel] : list)
        playSecond()
    }

    private func playSecond() {
        guard let second else { return }
        let candidates = streamCandidates(playlist: second.content.playlist, channel: second.channel, preferred: prefs.streamFormat,
                                          allowedFormats: second.content.account?.allowedFormats ?? [])
        secondPlayer.reconnectSeconds = prefs.reconnectSeconds
        secondPlayer.cachingMs = Prefs.Buffer.milliseconds(prefs.buffer)
        secondPlayer.play(candidates, userAgent: second.channel.userAgent ?? prefs.userAgent, referrer: second.channel.referrer)
    }

    /// ↑/↓ no segundo canal (dá a volta nas pontas, como o principal).
    func zapSecond(_ delta: Int) {
        guard var s = second, s.zapList.count > 1 else { return }
        let step = prefs.reverseZap ? -delta : delta
        let i = s.zapList.firstIndex { $0.key == s.channel.key } ?? 0
        s.channel = s.zapList[((i + step) % s.zapList.count + s.zapList.count) % s.zapList.count]
        second = s
        playSecond()
    }

    func closeSecond() {
        secondPlayer.stop()
        second = nil
        player.setMuted(false)
    }

    func playPrevious() {
        guard let previous else { return }
        play(previous)
    }

    func channels(in group: String) -> [Channel] {
        library?.channels(in: group) ?? []
    }

    func isFavorite(_ channel: Channel) -> Bool {
        guard let id = content?.playlist.id else { return false }
        return favorites[id]?.contains(channel.key) ?? false
    }

    func toggleFavorite(_ channel: Channel) {
        guard let id = content?.playlist.id else { return }
        var list = favorites[id] ?? []
        let added = !list.contains(channel.key)
        if added { list.append(channel.key) } else { list.removeAll { $0 == channel.key } }
        favorites[id] = list
        showToast(added ? strings.favoriteAdded(channel.name) : strings.favoriteRemoved(channel.name))
        if isLocal {
            persistLocal()
            return
        }
        let body = JSON.object(favorites.mapValues { JSON.strings($0) })
        enqueueSave { [weak self] in
            guard let self else { return }
            do {
                let (status, _) = try await self.backend.updateProfile(["favorites": body])
                if !(200..<300).contains(status), status != 401 { self.showToast(self.strings.saveFailed(status)) }
            } catch {
                self.showToast(self.strings.saveOffline)
            }
        }
    }

    /// O logótipo a mostrar: o escolhido no canal, senão o da origem geral (guia ou país), senão o da lista.
    func logo(for channel: Channel) -> URL? {
        guard let id = content?.playlist.id else { return channel.logo.flatMap(URL.init(string:)) }
        if let chosen = prefs.channelLogos["\(id):\(channel.key)"] {
            return URL(string: chosen.isEmpty ? (channel.logo ?? "") : chosen)
        }
        let source = prefs.logoSource
        if source == Prefs.LogoSource.guide, let icon = epgMappings[channel.key]?.icon {
            return URL(string: icon)
        }
        if Prefs.LogoSource.isCountry(source) {
            if let m = epgMappings[channel.key], let icon = m.icon, m.country?.lowercased() == source.lowercased() { return URL(string: icon) }
            if let c = countryLogos, c.key == "\(id):\(source)", let icon = c.logos[channel.key] { return URL(string: icon) }
        }
        return channel.logo.flatMap(URL.init(string:))
    }

    // MARK: Guia da conta: trocar o guia e o logótipo de um canal (só com conta)

    enum EpgResult<T> {
        case ok(T)
        case error(String)
    }

    private func epgCall<T>(_ work: () async throws -> T) async -> EpgResult<T> {
        do {
            return .ok(try await work())
        } catch Backend.Failure.http(let code) {
            return .error(strings.t("The guide server answered %@", "O servidor do guia respondeu %@", "\(code)"))
        } catch {
            return .error(strings.t("No connection to the guide server", "Sem ligação ao servidor do guia"))
        }
    }

    /// Lê as associações da lista (uma vez por lista) e, se a origem dos logótipos for um país, os logótipos dele.
    private func loadEpgMappings(_ content: PlaylistContent) {
        let id = content.playlist.id
        Task {
            if mappingsLoadedFor != id {
                if case .ok(let map) = await epgCall({ try await backend.epgMappings(playlistId: id) }), self.content?.playlist.id == id {
                    epgMappings = map
                    mappingsLoadedFor = id
                }
            }
            await loadCountryLogos()
        }
    }

    /// Com um país em "Origem dos logótipos", associa os canais de guia desse país aos da lista (tvg-id ou nome).
    private func loadCountryLogos() async {
        guard let content, Prefs.LogoSource.isCountry(prefs.logoSource) else { return }
        let country = prefs.logoSource
        let key = "\(content.playlist.id):\(country)"
        if countryLogos?.key == key { return }
        var guide = countryGuide?.country == country ? countryGuide?.channels : nil
        if guide == nil, case .ok(let r) = await epgCountryChannels(country) {
            guide = r.channels.filter { $0.icon != nil }
            countryGuide = (country, guide ?? [])
        }
        guard let guide else { return }
        var logos: [String: String] = [:]
        for (ch, g) in Guide.match(content.channels, guide) { if let icon = g.icon { logos[ch.key] = icon } }
        countryLogos = (key, logos)
    }

    func epgSearch(_ query: String) async -> EpgResult<[EpgCountryResult]> {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.count < 2 { return .ok([]) }
        return await epgCall { try await backend.epgSearch(q) }
    }

    func epgSuggest(_ channel: Channel) async -> EpgResult<[EpgGuideChannel]> {
        guard let id = content?.playlist.id else { return .ok([]) }
        return await epgCall { try await backend.epgSuggest(playlistId: id, channelKey: channel.key, name: channel.name) }
    }

    func epgCountries() async -> EpgResult<[EpgCountryInfo]> {
        await epgCall { try await backend.epgCountries() }
    }

    func epgCountryChannels(_ country: String) async -> EpgResult<EpgCountryResult> {
        await epgCall { try await backend.epgCountryChannels(country) }
    }

    /// O guia de [channel] passa a ser o do canal de guia [guide] (de [country]). Grava na conta.
    func setEpgMapping(_ channel: Channel, country: String, guide: EpgGuideChannel) async -> Bool {
        guard let id = content?.playlist.id, let guideId = guide.id else { return false }
        let r = await epgCall { try await backend.putEpgMapping(playlistId: id, channelKey: channel.key, country: country, channelId: guideId, channelName: channel.name) }
        if case .error(let message) = r {
            showToast(message)
            return false
        }
        let name = guide.name ?? guideId
        epgMappings[channel.key] = EpgMapping(country: country, channelId: guideId, channelName: channel.name, guideName: name, icon: guide.icon)
        mappingsLoadedFor = id
        showToast(strings.t("%@ now uses the guide of %@", "%@ passa a usar o guia de %@", "\(channel.name)", "\(name)"))
        refreshAccountGuide(again: guide.now == nil)
        return true
    }

    /// [channel] volta ao guia da lista.
    func removeEpgMapping(_ channel: Channel) async -> Bool {
        guard let id = content?.playlist.id else { return false }
        let r = await epgCall { try await backend.deleteEpgMapping(playlistId: id, channelKey: channel.key) }
        if case .error(let message) = r {
            showToast(message)
            return false
        }
        epgMappings.removeValue(forKey: channel.key)
        showToast(strings.t("%@ uses the playlist's guide again", "%@ volta ao guia da lista", "\(channel.name)"))
        refreshAccountGuide(again: false)
        return true
    }

    /// A lista inteira passa a usar o guia de [country] (os canais com o mesmo tvg-id ou nome). Os já trocados ficam.
    func applyEpgCountry(_ country: String, name countryName: String) {
        guard let content else { return }
        let id = content.playlist.id
        showToast(strings.t("Matching channels with %@…", "A associar os canais a %@…", "\(countryName)"))
        Task {
            let guide: [EpgGuideChannel]
            switch await epgCountryChannels(country) {
            case .error(let message):
                showToast(message)
                return
            case .ok(let r):
                guide = r.channels.filter { $0.id != nil }
            }
            let matches = Guide.match(content.channels.filter { epgMappings[$0.key] == nil }, guide)
            if matches.isEmpty {
                showToast(strings.t("No channel of this playlist is in %@'s guide", "Nenhum canal desta lista está no guia de %@", "\(countryName)"))
                return
            }
            var added = 0
            var lastError: String?
            // Poucos pedidos de cada vez e com pausa: o servidor aceita 300 pedidos em 10 s.
            for start in stride(from: 0, to: matches.count, by: 4) {
                let chunk = Array(matches[start..<min(matches.count, start + 4)])
                for (ch, g) in chunk {
                    let r = await epgCall { try await backend.putEpgMapping(playlistId: id, channelKey: ch.key, country: country, channelId: g.id!, channelName: ch.name) }
                    switch r {
                    case .ok:
                        epgMappings[ch.key] = EpgMapping(country: country, channelId: g.id, channelName: ch.name, guideName: g.name, icon: g.icon)
                        added += 1
                    case .error(let message):
                        lastError = message
                    }
                }
                if added == 0, lastError != nil { break }
                try? await Task.sleep(for: .milliseconds(200))
            }
            mappingsLoadedFor = id
            showToast(added > 0 ? strings.t("%@ channels now use %@'s guide", "%@ canais passam a usar o guia de %@", "\(added)", "\(countryName)")
                      : lastError ?? strings.t("No connection to the guide server", "Sem ligação ao servidor do guia"))
            if added > 0 { refreshAccountGuide(again: true) }
        }
    }

    /// Todos os canais da lista voltam ao guia da lista.
    func resetEpgMappings() {
        guard let id = content?.playlist.id else { return }
        Task {
            var lastError: String?
            for key in Array(epgMappings.keys) {
                switch await epgCall({ try await backend.deleteEpgMapping(playlistId: id, channelKey: key) }) {
                case .ok: epgMappings.removeValue(forKey: key)
                case .error(let message): lastError = message
                }
            }
            showToast(lastError ?? strings.t("Every channel uses the playlist's guide again", "Todos os canais voltaram ao guia da lista"))
            refreshAccountGuide(again: false)
        }
    }

    var hasAccount: Bool { backend.token != nil && !isLocal }

    /// O guia da conta volta a ser pedido (mudou uma associação). Com [again], pede-o outra vez daqui a 90 s: um país
    /// que ainda não estava em uso só tem programas no servidor no minuto seguinte.
    private func refreshAccountGuide(again: Bool) {
        guard let id = content?.playlist.id else { return }
        let backend = self.backend
        Task { [weak self] in
            for attempt in 0..<(again ? 2 : 1) {
                if attempt > 0 { try? await Task.sleep(for: .seconds(90)) }
                guard let reply = try? await backend.epgGuide(playlistId: id, etag: nil), (200..<300).contains(reply.status) else { continue }
                let data = reply.data
                let etag = reply.etag
                let guide = await Task.detached { AccountGuide.parse(playlistId: id, etag: etag, data: data) }.value
                guard self?.content?.playlist.id == id else { return }
                self?.setGuide(account: guide)
            }
        }
    }

    /// Logótipo escolhido para [channel]: um URL, "" para o da lista, ou nil para seguir a origem geral.
    func channelLogoChoice(_ channel: Channel) -> String? {
        guard let id = content?.playlist.id else { return nil }
        return prefs.channelLogos["\(id):\(channel.key)"]
    }

    func setChannelLogo(_ channel: Channel, _ url: String?) {
        guard let id = content?.playlist.id else { return }
        var map = prefs.channelLogos
        map["\(id):\(channel.key)"] = url
        setPref("channel_logos", .object(map.mapValues { JSON.string($0) }))
    }

    /// Muda a origem dos logótipos (e, se for um país, vai buscar os dele).
    func setLogoSource(_ source: String?) {
        setPref("logo_source", source.map { JSON.string($0) })
        Task { await loadCountryLogos() }
    }

    // MARK: Reordenar grupos e canais

    /// Move um grupo [delta] posições (−1 = para cima).
    func moveGroup(_ groupId: String, _ delta: Int) {
        guard let id = content?.playlist.id, let library else { return }
        var ids = library.orderedGroups(includeHidden: true).map(\.id)
        guard let from = ids.firstIndex(of: groupId) else { return }
        let to = min(max(0, from + delta), ids.count - 1)
        guard from != to else { return }
        ids.insert(ids.remove(at: from), at: to)
        var order = prefs.groupOrder
        order[id] = ids
        setPref("group_order", .object(order.mapValues { JSON.strings($0) }))
    }

    func resetGroupOrder() {
        guard let id = content?.playlist.id, prefs.groupOrder[id] != nil else { return }
        var order = prefs.groupOrder
        order.removeValue(forKey: id)
        setPref("group_order", .object(order.mapValues { JSON.strings($0) }))
    }

    /// Move um canal [delta] posições dentro do grupo.
    func moveChannel(_ groupId: String, _ channelKey: String, _ delta: Int) {
        guard let id = content?.playlist.id, let library else { return }
        var keys = library.orderedChannels(groupId, includeHidden: true).map(\.key)
        guard let from = keys.firstIndex(of: channelKey) else { return }
        let to = min(max(0, from + delta), keys.count - 1)
        guard from != to else { return }
        keys.insert(keys.remove(at: from), at: to)
        var order = prefs.channelOrder
        order["\(id):\(groupId)"] = keys
        setPref("channel_order", .object(order.mapValues { JSON.strings($0) }))
    }

    func resetChannelOrder(_ groupId: String) {
        guard let id = content?.playlist.id, prefs.channelOrder["\(id):\(groupId)"] != nil else { return }
        var order = prefs.channelOrder
        order.removeValue(forKey: "\(id):\(groupId)")
        setPref("channel_order", .object(order.mapValues { JSON.strings($0) }))
    }

    // MARK: Último canal e recentes

    private func flushWatched() {
        guard let current, Date().timeIntervalSince(playingSince) >= Self.watchedSeconds else { return }
        watchTask?.cancel()
        saveLast(current, watched: true)
    }

    private func saveLast(_ channel: Channel, watched: Bool) {
        guard let playlistId = content?.playlist.id else { return }
        session.lastPlaylist = playlistId
        session.lastChannel = channel.key
        var changed = false
        if settings["last_playlist"]?.text != playlistId || settings["last_channel"]?.text != channel.key {
            settings["last_playlist"] = .string(playlistId)
            settings["last_channel"] = .string(channel.key)
            changed = true
        }
        if watched {
            let old = recents[playlistId] ?? []
            let updated = Array(([channel.key] + old.filter { $0 != channel.key }).prefix(Self.maxRecents))
            if updated != old {
                recents[playlistId] = updated
                settings["recent_channels"] = .object(recents.mapValues { JSON.strings($0) })
                changed = true
            }
        }
        if changed { saveSettings() }
    }

    // MARK: Gravar na conta

    /// Os pedidos à conta vão um de cada vez, pela ordem em que foram feitos.
    private func enqueueSave(_ work: @escaping @MainActor () async -> Void) {
        let previousTask = saveChain
        saveChain = Task { @MainActor in
            await previousTask?.value
            await work()
        }
    }

    /// Grava só o que mudou no `settings` desde a última vez, em `settings_merge`. Num 409 (alguém gravou
    /// entretanto, ex.: no site), põe as alterações daqui por cima das do servidor e tenta outra vez.
    private func saveSettings() {
        if isLocal {
            persistLocal()
            return
        }
        enqueueSave { [weak self] in
            guard let self else { return }
            for _ in 0..<4 {
                let base = self.syncedSettings
                let patch = SettingsMerge.diff(base, self.settings)
                if patch.isEmpty { return }
                var body: [String: JSON] = ["settings_merge": .object(patch)]
                if SettingsMerge.hasTopLevelArray(patch), let rev = self.settingsRev { body["settings_rev"] = .number(Double(rev)) }
                let result: (status: Int, body: JSON?)
                do {
                    result = try await self.backend.updateProfile(body)
                } catch {
                    return // sem rede: fica para a próxima gravação
                }
                let status = result.status
                let reply = result.body
                guard (200..<300).contains(status) || status == 409 else {
                    if status != 401 { self.showToast(self.strings.saveFailed(status)) }
                    return
                }
                if let rev = reply?["settings_rev"]?.int { self.settingsRev = Int64(rev) }
                if let server = reply?["settings"]?.object {
                    let rebased = SettingsMerge.rebase(server: server, base: base, local: self.settings)
                    self.syncedSettings = server
                    if rebased != self.settings {
                        self.settings = rebased
                        self.applySettings()
                    }
                } else {
                    self.syncedSettings = SettingsMerge.apply(base, patch)
                }
                if (200..<300).contains(status) { return }
            }
        }
    }

    // MARK: Guia

    /// Os programas de um canal (guia da conta, da lista ou extra; senão o curto do Xtream), já com o desvio.
    func programmes(for channel: Channel) -> [EpgEntry] {
        var list = Guide.entries(for: channel, account: accountGuide, main: mainGuide, extra: extraGuide)
        if list.isEmpty { list = shortEpg[channel.key] ?? [] }
        return Guide.shifted(list, minutes: prefs.epgOffsetMin)
    }

    func nowNext(for channel: Channel) -> (now: EpgEntry?, next: EpgEntry?) {
        Guide.nowNext(programmes(for: channel))
    }

    /// Guias já atualizados desde que a app abriu (para "sempre que a app abre").
    @ObservationIgnored private var guidesChecked = Set<String>()

    /// Abre os guias: primeiro os guardados em disco (logo), depois atualiza os que estão na hora (ou todos, com [force]).
    private func loadGuides(force: Bool = false) {
        guard let content else { return }
        guideTask?.cancel()
        let loader = self.loader
        let backend = self.backend
        let cache = self.cache
        let playlistId = content.playlist.id
        let channels = content.channels
        let mainURL = content.guideUrl
        let extraURL = prefs.extraEpg[playlistId]
        let refreshHours = prefs.epgRefreshHours
        let hasAccount = self.hasAccount

        // O que está em disco aparece já.
        if accountGuide == nil, let saved = cache.load(AccountGuide.self, key: "guide-account-\(playlistId)") { accountGuide = saved }
        if mainGuide == nil, let mainURL, let saved = cache.load(XMLTVGuide.self, key: "guide-main-\(playlistId)"), saved.url == mainURL { mainGuide = saved }
        if extraGuide == nil, let extraURL, let saved = cache.load(XMLTVGuide.self, key: "guide-extra-\(playlistId)"), saved.url == extraURL { extraGuide = saved }
        guideVersion += 1

        func due(_ key: String, _ fetchedAt: Date?) -> Bool {
            if force { return true }
            guard let fetchedAt else { return true }
            if refreshHours <= 0 { return !guidesChecked.contains(key) }
            return Date().timeIntervalSince(fetchedAt) >= Double(refreshHours) * 3600
        }
        let mainDue = mainURL != nil && due("main:\(playlistId)", mainGuide?.url == mainURL ? mainGuide?.fetchedAt : nil)
        let extraDue = extraURL != nil && due("extra:\(playlistId)", extraGuide?.url == extraURL ? extraGuide?.fetchedAt : nil)
        let previousAccount = accountGuide
        guideTask = Task { [weak self] in
            // Guia da conta (associações feitas no site ou no Android): pequeno; com o ETag, um 304 não traz nada.
            if hasAccount, let reply = try? await backend.epgGuide(playlistId: playlistId, etag: previousAccount?.etag) {
                var guide: AccountGuide?
                if reply.status == 304 {
                    guide = previousAccount?.refreshed()
                } else if (200..<300).contains(reply.status) {
                    let data = reply.data
                    let etag = reply.etag
                    guide = await Task.detached { AccountGuide.parse(playlistId: playlistId, etag: etag, data: data) }.value
                }
                guard !Task.isCancelled else { return }
                if let guide {
                    self?.setGuide(account: guide)
                    cache.save(guide, as: "guide-account-\(playlistId)")
                }
            }
            if mainDue, let mainURL, let guide = try? await loader.loadGuide(url: mainURL, channels: channels) {
                guard !Task.isCancelled else { return }
                self?.guidesChecked.insert("main:\(playlistId)")
                self?.setGuide(main: guide)
                cache.save(guide, as: "guide-main-\(playlistId)")
            }
            if extraDue, let extraURL, let guide = try? await loader.loadGuide(url: extraURL, channels: channels) {
                guard !Task.isCancelled else { return }
                self?.guidesChecked.insert("extra:\(playlistId)")
                self?.setGuide(extra: guide)
                cache.save(guide, as: "guide-extra-\(playlistId)")
            }
        }
    }

    private func setGuide(account: AccountGuide? = nil, main: XMLTVGuide? = nil, extra: XMLTVGuide? = nil) {
        if let account { accountGuide = account }
        if let main { mainGuide = main }
        if let extra { extraGuide = extra }
        guideVersion += 1
    }

    /// Canal Xtream sem guia: pede os próximos programas ao servidor.
    private func loadShortEpgIfNeeded(_ channel: Channel) {
        guard let content, content.playlist.type == .xtream, let streamId = channel.streamId, shortEpg[channel.key] == nil,
              Guide.entries(for: channel, account: accountGuide, main: mainGuide, extra: extraGuide).isEmpty else { return }
        shortEpg[channel.key] = []
        let loader = self.loader
        let playlist = content.playlist
        Task { [weak self] in
            guard let list = try? await loader.shortEpg(playlist, streamId: streamId), !list.isEmpty else { return }
            self?.shortEpg[channel.key] = list
            self?.guideVersion += 1
        }
    }

    // MARK: Avisos

    func showToast(_ message: String) {
        toast = message
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    /// A app vai para segundo plano: guarda o canal, para o vídeo (liberta a ligação Xtream), fecha o segundo canal
    /// e devolve a TV ao modo normal.
    func didEnterBackground() {
        updateTopShelf()
        flushWatched()
        closeSecond()
        player.stop()
        backgrounded = true
    }

    @ObservationIgnored private var backgrounded = false

    /// De volta à app: o canal (ou o catch-up) volta a dar.
    func didBecomeActive() {
        checkForUpdate()
        guard backgrounded else { return }
        backgrounded = false
        guard screen == .player, let current else { return }
        if let cu = catchup {
            var resume = cu
            resume.positionMs = catchupPosition
            startCatchup(resume)
        } else {
            play(current, remember: false)
        }
    }

    // MARK: Top Shelf

    /// Canal escolhido na Top Shelf, à espera da lista dele.
    @ObservationIgnored private var pendingOpen: (playlist: String, key: String)?

    /// goattv://play?playlist=<id>&channel=<channelKey> (um canal da Top Shelf): abre-o.
    func open(_ url: URL) {
        guard url.scheme == "goattv", url.host == "play",
              let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let playlistId = query.first(where: { $0.name == "playlist" })?.value,
              let key = query.first(where: { $0.name == "channel" })?.value else { return }
        if let content, content.playlist.id == playlistId {
            if let channel = content.byKey[key], !(library?.isGroupLocked(channel.categoryId) ?? true) {
                play(channel, from: groupFor(channel))
            }
            return
        }
        pendingOpen = (playlistId, key)
        if content != nil, let playlist = playlists.first(where: { $0.id == playlistId }) { switchPlaylist(playlist) }
    }

    /// O que a Top Shelf mostra (favoritos e recentes da lista em uso, sem escondidos nem bloqueados), no App Group
    /// partilhado com a extensão. Sem App Group (a IPA normal), não faz nada.
    func updateTopShelf() {
        guard hasTopShelf, let content, let library, let dir = AppGroup.container()?.url else { return }
        func items(_ keys: [String]) -> JSON {
            let channels = keys.compactMap { content.byKey[$0] }
                .filter { !isChannelHidden($0) && !library.isGroupLocked($0.categoryId) }
            return .array(channels.prefix(12).map { ch in
                var item: [String: JSON] = ["key": .string(ch.key), "name": .string(ch.name)]
                if let logo = logo(for: ch) { item["logo"] = .string(logo.absoluteString) }
                if let now = nowNext(for: ch).now { item["now"] = .string(now.title) }
                return .object(item)
            })
        }
        let id = content.playlist.id
        let shelf = JSON.object([
            "playlist": .string(id),
            "sections": .array([
                .object(["title": .string(strings.favorites), "items": items(favorites[id] ?? [])]),
                .object(["title": .string(strings.recents), "items": items(recents[id] ?? [])]),
            ]),
        ])
        do {
            try shelf.encoded().write(to: dir.appendingPathComponent("topshelf.json"), options: .atomic)
        } catch {
            NSLog("GoatTV TopShelf: não deu para gravar: %@", "\(error)")
        }
        TVTopShelfContentProvider.topShelfContentDidChange()
    }

    /// Para o diagnóstico (Definições → Conta → Top Shelf): a extensão, os App Groups da assinatura e o que a
    /// extensão fez da última vez. nil sem extensão (a IPA normal).
    struct TopShelfStatus {
        var appGroups: [String]?
        var extensionGroups: [String]?
        var shared: String?
        var lastRun: String?
    }

    /// Só a IPA com a Top Shelf traz a extensão (há serviços de assinatura que não assinam extensões, por isso a normal vai sem ela).
    private var hasTopShelf: Bool {
        Bundle.main.builtInPlugInsURL.map { FileManager.default.fileExists(atPath: $0.appendingPathComponent("TopShelf.appex").path) } ?? false
    }

    var topShelfStatus: TopShelfStatus? {
        guard let plugins = Bundle.main.builtInPlugInsURL,
              let ext = Bundle(url: plugins.appendingPathComponent("TopShelf.appex")) else { return nil }
        let container = AppGroup.container()
        var lastRun: String?
        if let url = container?.url.appendingPathComponent(AppGroup.statusFile),
           let data = try? Data(contentsOf: url),
           let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] {
            lastRun = [json["time"], json["status"]].compactMap { $0 }.joined(separator: " · ")
        }
        return TopShelfStatus(appGroups: AppGroup.profileGroups(), extensionGroups: AppGroup.profileGroups(ext),
                              shared: container?.id, lastRun: lastRun)
    }

    // MARK: Versão nova

    /// A versão mais recente com IPA, se for mais nova do que esta (Definições mostram-na no topo).
    private(set) var updateVersion: String?
    @ObservationIgnored private var updateCheckedAt: Date?

    /// Vê se há versão nova (no máximo de 12 em 12 h). Na Apple TV não se instala por dentro da app: avisa uma vez
    /// por versão e as Definições dizem onde a ir buscar.
    func checkForUpdate() {
        #if DEBUG
        if UserDefaults.standard.string(forKey: "UITestPlaylist") != nil { return }
        #endif
        if let at = updateCheckedAt, Date().timeIntervalSince(at) < 12 * 3600 { return }
        updateCheckedAt = Date()
        let current = appVersion
        Task { [weak self] in
            var request = URLRequest(url: AppUpdate.latestRelease)
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            request.timeoutInterval = 20
            guard let (data, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse)?.statusCode == 200,
                  let json = try? JSON.parse(data),
                  let version = AppUpdate.version(fromLatest: json),
                  AppUpdate.isNewer(version, than: current) else { return }
            guard let self else { return }
            self.updateVersion = version
            guard UserDefaults.standard.string(forKey: "update.notified") != version else { return }
            UserDefaults.standard.set(version, forKey: "update.notified")
            // Depois de a lista abrir, para o aviso não se perder no ecrã de carregar.
            try? await Task.sleep(for: .seconds(8))
            self.showToast(self.strings.r("update_available", version))
        }
    }
}
