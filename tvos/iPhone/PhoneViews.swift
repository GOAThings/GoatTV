import GoatCore
import SwiftUI
import UIKit

/// Email mascarado para o ecrã ("a••••••@exemplo.com"), como no resto da GoatTV.
private func phoneMaskEmail(_ email: String) -> String {
    guard let at = email.firstIndex(of: "@"), at > email.startIndex, let first = email.first else { return email }
    return "\(first)••••••\(email[at...])"
}

private let cardFill = Color.white.opacity(0.08)
private let cardShape = RoundedRectangle(cornerRadius: 18, style: .continuous)

private let listShape = RoundedRectangle(cornerRadius: 12, style: .continuous)
private let selectedFill = Theme.sky.opacity(0.13)

private let timeFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateFormat = "HH:mm"
    return formatter
}()

private func phoneTime(_ ms: Int64) -> String {
    timeFormatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

struct PhoneRoot: View {
    @Environment(AppModel.self) private var model
    /// A abertura (o logo animado) só passa uma vez, ao abrir a app.
    @State private var introDone = false
    /// A IPA descarregada pela folha de versão nova, para a folha de partilha.
    @State private var sharedIPA: DownloadedIPA?

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            switch model.screen {
            case .starting:
                GoatLogoView(intro: false)
            case .loading(let name):
                // O mesmo logo da abertura, no mesmo sítio, com o que se está a carregar por baixo.
                GoatLogoView(intro: false, message: name.isEmpty ? nil : model.strings.loading(name))
            case .welcome:
                PhoneWelcome()
            case .newPlaylist:
                PlaylistFormPage { model.leaveNewPlaylist() }
            case .activation:
                PhoneActivation()
            case .player:
                PhoneMain()
            case .noPlaylists:
                PhoneError(info: model.strings.errNoPlaylists())
            case .error(let info):
                PhoneError(info: info)
            }
            if !introDone {
                GoatLogoView(intro: true) {
                    withAnimation(.easeOut(duration: 0.45)) { introDone = true }
                }
                .transition(.opacity)
            }
            // Versão nova: uma folha em baixo (na TV é um cartão no player).
            if introDone {
                PhoneUpdateSheet { sharedIPA = $0 }
            }
            if let toast = model.toast {
                VStack {
                    Text(toast).font(.subheadline).padding(.horizontal, 18).padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                    Spacer()
                }
                .padding(.top, 8)
                .allowsHitTesting(false)
            }
        }
        .sheet(item: $sharedIPA) { ShareSheet(url: $0.url) }
    }
}

private struct PhoneWelcome: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let s = model.strings
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                BrandMark(scale: 0.35).padding(.top, 24)
                Text(s.r("welcome_title")).font(.title.bold())
                Text(s.r("welcome_sub")).foregroundStyle(Theme.textDim)
                choice(s.r("welcome_account"), s.r("welcome_account_sub"), badge: s.r("recommended"), model.connectAccount)
                choice(s.t("Playlists on this phone", "Listas neste telemóvel"), s.t("No account: add Xtream or M3U playlists here. Everything stays on this phone only.", "Sem conta: adiciona listas Xtream ou M3U aqui. Tudo fica só neste telemóvel."), model.startLocal)
            }
            .padding(20)
        }
    }

    private func choice(_ title: String, _ body: String, badge: String? = nil, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                if let badge {
                    Text(badge.uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Theme.accent(model.prefs.accent), in: RoundedRectangle(cornerRadius: 6))
                        .padding(.bottom, 6)
                }
                Text(title).font(.headline).foregroundStyle(Theme.text)
                Text(body).font(.subheadline).foregroundStyle(Theme.textDim).multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(cardFill, in: cardShape)
        }
    }
}

private struct PhoneActivation: View {
    @Environment(AppModel.self) private var model
    @State private var copied = false

    var body: some View {
        let s = model.strings
        let a = model.activation
        VStack(spacing: 0) {
            Spacer()
            BrandMark(scale: 0.35).frame(maxWidth: .infinity).padding(.bottom, 28)
            VStack(spacing: 8) {
                Text(s.t("Connect your GOAT account", "Liga a tua conta GOAT")).font(.title2.bold()).multilineTextAlignment(.center)
                Text(s.t("Your playlists, favourites and settings, on every device.", "As tuas listas, favoritos e definições, em todos os dispositivos."))
                    .font(.subheadline).foregroundStyle(Theme.textDim).multilineTextAlignment(.center)
            }
            Button {
                guard let code = a.code else { return }
                UIPasteboard.general.string = code
                copied = true
            } label: {
                VStack(spacing: 8) {
                    Text(a.code ?? "······")
                        .font(.system(size: 42, weight: .bold, design: .monospaced))
                        .minimumScaleFactor(0.6).lineLimit(1)
                        .foregroundStyle(Theme.accent)
                    Label(copied ? s.t("Copied", "Copiado") : s.t("Copy code", "Copiar código"), systemImage: copied ? "checkmark" : "doc.on.doc")
                        .font(.footnote).foregroundStyle(Theme.textFaint)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
                .background(cardFill, in: cardShape)
            }
            .buttonStyle(.plain)
            .disabled(a.code == nil)
            .padding(.top, 24)
            Group {
                if let error = a.error {
                    Text(error).foregroundStyle(Theme.error)
                } else if let expires = a.expires {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let left = max(0, Int(expires.timeIntervalSince(context.date)))
                        Text(s.r("activate_expires_text", "\(left / 60)").replacingOccurrences(of: "%2$02d", with: String(format: "%02d", left % 60)))
                            .foregroundStyle(Theme.textFaint)
                    }
                } else {
                    ProgressView()
                }
            }
            .font(.footnote).multilineTextAlignment(.center).padding(.top, 12).padding(.bottom, 24)
            if let text = a.qrText ?? a.url, let url = URL(string: text.contains("://") ? text : "https://\(text)") {
                Link(destination: url) {
                    Text(s.t("Sign in to approve", "Entrar para aprovar"))
                        .font(.headline).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).padding(.vertical, 15)
                        .background(Theme.accent(model.prefs.accent), in: cardShape)
                }
                // A página de aprovar também cria a conta (com signup=1 abre já nesse separador).
                if let signup = URL(string: url.absoluteString + (url.query == nil ? "?" : "&") + "signup=1") {
                    Link(destination: signup) {
                        Text(s.r("create_account"))
                            .font(.subheadline.weight(.semibold)).foregroundStyle(Theme.text)
                            .padding(.horizontal, 16).padding(.vertical, 10)
                    }
                    .padding(.top, 4)
                }
                Text(a.url ?? text).font(.caption).foregroundStyle(Theme.textFaint)
            }
            Spacer()
            Button(model.isLocal ? s.r("back_key_name") : s.r("use_without_account")) { model.leaveActivation() }
                .font(.subheadline).foregroundStyle(Theme.textDim).padding(.top, 16)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onChange(of: a.code) { copied = false }
    }
}

private struct PhoneError: View {
    @Environment(AppModel.self) private var model
    let info: ErrorInfo
    @State private var adding = false
    @State private var askingSignOut = false
    @State private var showingSettings = false

    var body: some View {
        let s = model.strings
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(info.title).font(.title2.bold())
                if let cause = info.cause { Text(cause).foregroundStyle(Theme.textDim) }
                ForEach(info.fixes, id: \.self) { Text("• \($0)").font(.subheadline).foregroundStyle(Theme.textDim) }
                Group {
                    if model.isLocal || model.playlists.isEmpty { MenuRow(s.r("add_playlist")) { adding = true } }
                    MenuRow(s.r("retry")) { model.retry() }
                    // Como na TV: com listas, as Definições (mudar de lista, editá-la, removê-la); sem conta, ligar uma.
                    if !model.playlists.isEmpty { MenuRow(s.r("settings")) { showingSettings = true } }
                    if model.hasAccount {
                        MenuRow(s.r("logout")) {
                            if model.signOutNeedsPin { askingSignOut = true } else { model.signOut() }
                        }
                    } else {
                        MenuRow(s.r("connect_account")) { model.connectAccount() }
                    }
                }
                .background(cardFill, in: cardShape)
            }
            .padding(20)
        }
        .sheet(isPresented: $adding) { PlaylistFormPage { adding = false } }
        .sheet(isPresented: $showingSettings) { MenuTab() }
        .modifier(SignOutPrompt(isPresented: $askingSignOut))
    }
}

/// Terminar sessão pede o PIN, quando há um (em todos os botões, como na TV).
private struct SignOutPrompt: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var isPresented: Bool
    @State private var pin = ""

    func body(content: Content) -> some View {
        content.alert(model.strings.r("enter_pin"), isPresented: $isPresented) {
            SecureField("PIN", text: $pin).keyboardType(.numberPad)
            Button("OK") {
                if model.checkPin(pin) { model.signOut() } else { model.showToast(model.pinRejected()) }
                pin = ""
            }
            Button(model.strings.r("cancel"), role: .cancel) { pin = "" }
        }
    }
}

private enum PhoneTab: String { case tv, guide, favorites, search, menu }

/// O ecrã principal: abre logo a dar o último canal, com a lista por baixo.
struct PhoneMain: View {
    @Environment(AppModel.self) private var model
    @Environment(\.verticalSizeClass) private var vertical
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = PhoneMain.startTab
    @State private var epgFor: Channel?
    /// O ecrã inicial (recentes e grupos), sem nada a dar; o vídeo só aparece quando se escolhe um canal.
    @State private var home = true
    /// Nada a dar (ao abrir no ecrã inicial, ou depois de fechar o mini-leitor).
    @State private var stopped = true
    @State private var homeGroup: String?
    @State private var homePinFor: ChannelGroup?
    @State private var homePin = ""
    /// Áudio, legendas ou temporizador, cada um no seu botão (por baixo do vídeo).
    @State private var playerOption: PlayerOption?
    @State private var showRecordings = PhoneMain.startRecordings
    /// Um programa com lembrete vai começar (o cartão em cima, como no Android); com a definição, os segundos até mudar.
    @State private var reminder: AppModel.Reminder?
    @State private var reminderLeft: Int?

    /// O cartão do lembrete: ver o programa (muda de canal) ou ignorar.
    private func answerReminder(_ r: AppModel.Reminder, watch: Bool) {
        model.consumeReminder(r, watch: watch)
        reminder = nil
        if watch { played() }
    }

    /// Abriu-se um canal: sai do ecrã inicial.
    private func played() {
        stopped = false
        home = false
        homeGroup = nil
        tab = .tv
    }

    private static var startRecordings: Bool {
        #if DEBUG
        return UserDefaults.standard.string(forKey: "PhoneTab") == "recordings"
        #else
        return false
        #endif
    }

    private static var startTab: PhoneTab {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "PhoneTab").flatMap(PhoneTab.init) { return t }
        #endif
        return .tv
    }

    var body: some View {
        let settings = tab == .menu
        // A dar: em grande no separador TV, ou no mini-leitor por cima dos separadores (no início, no guia...).
        let video = !settings && !stopped
        let big = video && tab == .tv && !home
        let landscape = vertical == .compact && big
        GeometryReader { geo in
        VStack(spacing: 0) {
            if big {
                // Ao alto, 16:9 com a largura toda; deitado, o ecrã todo.
                VideoPane(fullscreen: landscape) { epgFor = model.current }
                    .overlay(alignment: .topLeading) { PhoneRecBadge() }
                    .frame(maxWidth: .infinity, maxHeight: landscape ? .infinity : nil)
                    .frame(height: landscape ? nil : geo.size.width * 9 / (model.second == nil ? 16 : 8))
            }
            if !landscape {
                Group {
                    switch tab {
                    case .tv:
                        if !home {
                            VStack(spacing: 0) {
                                if let current = model.current {
                                    NowStrip(channel: current, onGuide: { epgFor = current }, onOption: { playerOption = $0 }) { home = true }
                                }
                                ChannelsTab(epgFor: $epgFor)
                            }
                        } else if let id = homeGroup {
                            VStack(spacing: 0) {
                                ZStack {
                                    Text(model.groups.first { $0.id == id }?.name ?? "").font(.headline).lineLimit(1).padding(.horizontal, 70)
                                    HStack {
                                        Button { homeGroup = nil } label: {
                                            HStack(spacing: 2) { Image(systemName: "chevron.left").font(.body.weight(.semibold)); Text("TV") }
                                                .foregroundStyle(Theme.sky)
                                        }
                                        Spacer()
                                    }
                                    .padding(.horizontal, 10)
                                }
                                .padding(.vertical, 10)
                                ChannelList(channels: model.channels(in: id), group: id, epgFor: $epgFor)
                            }
                        } else {
                            PhoneHome(
                                onPlay: { channel in
                                    model.play(channel, from: channel.key == model.current?.key ? model.groupId : model.groupFor(channel))
                                    played()
                                },
                                onGroup: { homeGroup = $0 },
                                onLockedGroup: { homePinFor = $0 },
                                onSearch: { tab = .search },
                                onRecordings: { showRecordings = true }
                            )
                        }
                    case .guide: ChannelsTab(epgFor: $epgFor, guide: true)
                    case .favorites: PhoneFavorites(epgFor: $epgFor)
                    case .search:
                        PhoneSearch(epgFor: $epgFor) { id in
                            tab = .tv
                            home = true
                            homeGroup = id
                        }
                    case .menu: MenuTab()
                    }
                }
                .frame(maxHeight: .infinity)
                // A barra fica colada ao fundo do ecrã, por baixo da lista.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    VStack(spacing: 0) {
                        if video, !big {
                            MiniPlayer(onOpen: { tab = .tv; home = false }, onClose: { stopped = true; model.player.stop() })
                                .padding(.horizontal, 8).padding(.bottom, 8)
                        }
                        // Tocar em "TV" já na TV volta ao ecrã inicial (o canal continua no mini-leitor).
                        TabRow(tab: $tab) { if $0 == .tv { home = true; homeGroup = nil } }
                    }
                }
            }
        }
        }
        .sheet(item: $epgFor) { GuideSheet(channel: $0).environment(\.played, played) }
        .sheet(item: $playerOption) { PlayerOptionsSheet(player: model.player, option: $0) }
        .sheet(isPresented: $showRecordings) { PhoneRecordings().environment(\.played, played) }
        // Mudar de canal com uma gravação a decorrer: o fornecedor só deixa uma ligação.
        // Em pausa ou atrás do direto, o mesmo cartão com outras palavras: o que está guardado apaga-se.
        .alert(model.switchPrompt.map { $0.live ? model.strings.r("behind_switch_title") : model.strings.r("recording_switch_title", $0.title) } ?? "",
               isPresented: Binding(get: { model.switchPrompt != nil }, set: { if !$0, model.switchPrompt != nil { model.answerSwitch(stop: false) } })) {
            let live = model.switchPrompt?.live == true
            Button(model.strings.r(live ? "behind_switch_go" : "recording_stop_and_switch"), role: .destructive) { model.answerSwitch(stop: true) }
            Button(model.strings.r(live ? "behind_switch_stay" : "recording_keep"), role: .cancel) { model.answerSwitch(stop: false) }
        } message: {
            Text(model.strings.r(model.switchPrompt?.live == true ? "behind_switch_text" : "recording_switch_text"))
        }
        // Uma marcada noutro canal quase a começar: mudar para gravar, ou não gravar (sem resposta, muda à hora).
        .alert(model.recordings.prompt.map { model.strings.r("recording_soon", $0.channelName) } ?? "",
               isPresented: Binding(get: { model.recordings.prompt != nil && model.switchPrompt == nil }, set: { _ in })) {
            if let r = model.recordings.prompt {
                Button(model.strings.r("recording_switch_to", r.channelName)) { model.answerRecordingPrompt(r.id, switchChannel: true) }
                Button(model.strings.r("recording_skip"), role: .cancel) { model.answerRecordingPrompt(r.id, switchChannel: false) }
            }
        } message: {
            Text(model.recordings.prompt?.title ?? "")
        }
        .environment(\.played, played)
        .alert(model.strings.r("enter_pin"), isPresented: Binding(get: { homePinFor != nil }, set: { if !$0 { homePinFor = nil } })) {
            SecureField("PIN", text: $homePin).keyboardType(.numberPad)
            Button("OK") {
                if let g = homePinFor {
                    if model.unlockGroup(g.id, pin: homePin) { homeGroup = g.id } else { model.showToast(model.pinRejected()) }
                }
                homePin = ""
            }
            Button(model.strings.r("cancel"), role: .cancel) { homePin = "" }
        }
        // Ao abrir: o ecrã inicial, ou (nas Definições) já o último canal.
        .onAppear {
            home = model.prefs.phoneStartup == Prefs.PhoneStartup.home
            stopped = home
            if stopped { model.player.stop() }
        }
        // O modelo abre canais sozinho (ao carregar a lista, ao voltar à app): sem nada a dar, ficam fechados.
        .onChange(of: model.zapCount) { _, _ in
            if stopped { model.player.stop() }
        }
        // Nas Definições o canal fecha-se (e liberta a ligação); ao sair, volta a dar.
        .onChange(of: tab) { old, new in
            if new == .menu {
                model.closeSecond()
                model.player.stop()
            } else if old == .menu, !stopped, let current = model.current {
                model.play(current, remember: false)
            }
        }
        // Trocar de lista nas Definições abre outro canal: volta à TV para o ver.
        .onChange(of: model.current?.key) { _, _ in
            if tab == .menu { tab = .tv }
        }
        // Ao voltar à app, o modelo retoma o canal: nas Definições fica fechado.
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active, tab == .menu || stopped else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(200))
                if tab == .menu || stopped { model.player.stop() }
            }
        }
        // Em segundo plano o vídeo já parou: o temporizador deixa de fazer falta.
        .onChange(of: scenePhase) { _, phase in
            if phase == .background, model.sleepAt != nil { model.setSleepTimer(nil, announce: false) }
        }
        // Temporizador: aviso no último minuto; ao acabar, para e sai da app.
        .task(id: model.sleepAt) {
            var warned = false
            while let at = model.sleepAt, !Task.isCancelled {
                let left = at.timeIntervalSinceNow
                if left <= 0 {
                    model.sleepNow()
                    return
                }
                if left <= 60, !warned {
                    warned = true
                    model.showToast(model.strings.r("sleep_soon", "\(Int(left.rounded(.up)))"))
                }
                try? await Task.sleep(for: .seconds(left <= 61 ? 1 : min(left - 60, 30)))
            }
        }
        .sensoryFeedback(.selection, trigger: model.current?.key)
        .statusBarHidden(landscape)
        .persistentSystemOverlays(landscape ? .hidden : .automatic)
        .task {
            // Um lembrete de um programa que vai começar.
            while !Task.isCancelled {
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                if reminder == nil, let r = model.dueReminder(now) { reminder = r }
                try? await Task.sleep(for: .seconds(15))
            }
        }
        // Com "os lembretes mudam de canal": muda sozinho ao fim de uns segundos (o X fica neste canal).
        .task(id: reminder?.id) {
            reminderLeft = nil
            guard let r = reminder, model.prefs.reminderSwitch else { return }
            for s in stride(from: AppModel.reminderSwitchSeconds, through: 1, by: -1) {
                reminderLeft = s
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
            }
            answerReminder(r, watch: true)
        }
        .overlay(alignment: .top) {
            if let r = reminder {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(r.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Text(reminderLeft.map { "\(r.channelName) · \(model.strings.r("recording_switching_in", "\($0)"))" } ?? r.channelName)
                            .font(.footnote).foregroundStyle(Theme.textDim).monospacedDigit()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button { answerReminder(r, watch: true) } label: { Image(systemName: "play.fill").frame(width: 40, height: 40) }
                    Button { answerReminder(r, watch: false) } label: { Image(systemName: "xmark").frame(width: 40, height: 40) }
                }
                .foregroundStyle(Theme.text)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color(white: 0.16)))
                .padding(12)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: reminder?.id)
    }
}

private struct VideoPane: View {
    @Environment(AppModel.self) private var model
    let fullscreen: Bool
    let onGuide: () -> Void
    @State private var option: PlayerOption?
    @State private var seekDelta: Int64 = 0
    @State private var seekBase: Int64 = 0
    @State private var seekTask: Task<Void, Never>?
    @State private var scrub: Double?
    @State private var controls = true
    @State private var touched = 0
    @State private var focusSecond = false
    @State private var pickingSecond = false
    @State private var explaining = false

    /// Um vídeo. O vídeo é uma vista UIKit: sem desligar os toques, fica com eles e os gestos nem sempre os recebem.
    /// Com dois canais, tocar no que está em silêncio passa-lhe o som.
    private func videoTile(_ view: UIView, name: String?, audible: Bool, focus: @escaping () -> Void) -> some View {
        ZStack {
            VideoSurface(view: view).allowsHitTesting(false)
            if let name { Text(name).font(.footnote).foregroundStyle(Theme.textDim) }
            if model.second != nil, audible {
                Rectangle().strokeBorder(Theme.accent(model.prefs.accent), lineWidth: 2).allowsHitTesting(false)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if model.second != nil, !audible {
                focus()
                controls = true
            } else {
                controls.toggle()
            }
            touched += 1
        }
    }

    private func applyAudio() {
        if model.second == nil { focusSecond = false }
        model.player.setMuted(model.second != nil && focusSecond)
        model.secondPlayer.setMuted(!focusSecond)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea(edges: fullscreen ? .all : [])
            // Com dois canais: lado a lado deitado, um por cima do outro ao alto.
            (fullscreen ? AnyLayout(HStackLayout(spacing: 2)) : AnyLayout(VStackLayout(spacing: 2))) {
                videoTile(model.player.view, name: nil, audible: !focusSecond) { focusSecond = false }
                if let second = model.second {
                    videoTile(model.secondPlayer.view, name: model.secondPlayer.state == .playing ? nil : second.channel.name, audible: focusSecond) { focusSecond = true }
                }
            }
            .ignoresSafeArea(edges: fullscreen ? .all : [])
            if model.playbackError != nil, model.player.state == .failed {
                // O erro explicado fica por cima de tudo (mais abaixo): aqui não há nada a carregar.
            } else if !controls, model.player.frozen || (model.player.state == .playing && !model.player.hasPicture) {
                ProgressView().tint(.white)
            } else if !controls, model.player.state != .playing, model.player.reconnects == 0, let current = model.current {
                // A abrir: que está a carregar, e o quê (a voltar a ligar diz-o a pílula, mais abaixo).
                VStack(spacing: 10) {
                    ProgressView().tint(.white)
                    Text(model.strings.loading(current.name)).font(.subheadline).foregroundStyle(Theme.textDim)
                }
            }
            if controls { overlay }
            // Não abriu: o erro explicado logo à primeira falha (como na TV), com a tentativa seguinte por baixo. Antes
            // só aparecia o título ao fim de todas as tentativas, e até lá parecia que estava a carregar.
            if let error = model.playbackError, model.player.state == .failed {
                PlaybackErrorCard(info: error, status: model.playbackStatus) { explaining = true }
            }
            // A abrir um failover (o canal não abriu): o mesmo, a dizer qual.
            if let failover = model.failoverConnecting, let current = model.current {
                VStack {
                    if fullscreen {
                        TuningPill(channel: current.name, reconnecting: false, attempt: 0, nextTryAt: nil, waitSeconds: 0, scale: 0.55, failover: failover)
                            .padding(.top, 12)
                    } else {
                        ReconnectIsland(channel: model.failoverConnectingParts?.name ?? failover, nextTryAt: nil, waitSeconds: 0,
                                        label: model.strings.r("backup_connecting"), badge: model.failoverConnectingParts?.position)
                            .padding(.top, 8)
                    }
                    Spacer()
                }
                .allowsHitTesting(false)
            }
            // Caiu e está a voltar a ligar: ao alto a pílula no topo; em ecrã inteiro a cápsula da TV.
            if model.player.reconnects > 0, let current = model.current {
                VStack {
                    if fullscreen {
                        TuningPill(channel: current.name, reconnecting: true, attempt: model.player.reconnects,
                                   nextTryAt: model.player.nextTryAt, waitSeconds: model.player.retryDelay, scale: 0.55)
                            .padding(.top, 12)
                    } else {
                        ReconnectIsland(channel: current.name, nextTryAt: model.player.nextTryAt, waitSeconds: model.player.retryDelay)
                            .padding(.top, 8)
                    }
                    Spacer()
                }
                .allowsHitTesting(false)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { controls.toggle(); touched += 1 }
        .gesture(DragGesture(minimumDistance: 40).onEnded { v in
            guard abs(v.translation.height) > abs(v.translation.width) else { return }
            let delta = v.translation.height < 0 ? 1 : -1
            if focusSecond { model.zapSecond(delta) } else { model.zap(delta) }
        })
        .task(id: touched) {
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled, scrub == nil { controls = false }
        }
        .onChange(of: focusSecond) { applyAudio() }
        .onChange(of: model.second?.channel.key) { applyAudio() }
        .onChange(of: model.current?.key) { applyAudio() }
        .sheet(isPresented: $pickingSecond) {
            PhoneSecondPicker { content, channel, group in
                model.openSecond(content, channel, groupId: group)
                pickingSecond = false
            } onClose: { pickingSecond = false }
        }
        .sheet(item: $option) {
            PlayerOptionsSheet(player: model.second != nil && focusSecond ? model.secondPlayer : model.player, option: $0)
        }
        .sheet(isPresented: $explaining) {
            if let error = model.playbackError { PlaybackErrorSheet(info: error) { explaining = false } }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            PhoneOrientation.deviceMoved()
        }
    }

    /// Como o HUD da TV: em baixo o logótipo, o canal, o programa, as horas, o seguinte e a barra larga; ao meio
    /// pausa e ±30 s. Em ecrã inteiro também a hora e os botões; ao alto, os botões ficam por baixo do vídeo.
    private var overlay: some View {
        ZStack {
            // O vídeo escurece um pouco, para os botões se verem mesmo num vídeo claro.
            Color.black.opacity(0.28).allowsHitTesting(false)
            VStack(spacing: 0) {
                HStack(spacing: 10) {
                    if fullscreen {
                        hudButton("arrow.down.right.and.arrow.up.left", action: rotate)
                    }
                    Spacer()
                    if fullscreen { Text(Date(), style: .time).font(.subheadline.weight(.semibold)) }
                    // Dois canais lado a lado.
                    if model.second == nil {
                        hudButton("rectangle.split.2x1") { pickingSecond = true; touched += 1 }
                    } else {
                        hudButton("rectangle.2.swap") { pickingSecond = true; touched += 1 }
                        hudButton("xmark") { model.closeSecond(); touched += 1 }
                    }
                }
                .padding(.horizontal, fullscreen ? 20 : 10).padding(.top, fullscreen ? 10 : 8)
                Spacer()
                HStack(spacing: 28) {
                    // Em direto, recua-se no disco (a pausa do direto) ou para o catch-up do programa a dar; à frente do
                    // direto não há nada.
                    let ts = model.player.timeshift
                    let canSeek = barState?.seekable == true || (model.catchup == nil && ts.available)
                    if canSeek { seekButton("gobackward.30", -seekStep) }
                    // A abrir, a pausa não faz nada: fica o indicador no lugar dela, sem mexer nos outros botões. Na pausa
                    // do direto, continuar é do sítio onde parou (o disco continuou a gravar).
                    if model.player.frozen || model.player.state == .opening || (model.player.state == .playing && !model.player.hasPicture) {
                        ProgressView().tint(.white).frame(width: 60, height: 60)
                    } else {
                        Button { model.player.togglePause(); touched += 1 } label: {
                            Image(systemName: model.player.paused ? "play.fill" : "pause.fill").font(.title2)
                                .frame(width: 60, height: 60)
                                .background(Color.white.opacity(0.14), in: Circle())
                        }
                    }
                    if canSeek {
                        let forward = model.catchup != nil || ts.shifted
                        seekButton("goforward.30", seekStep)
                            .disabled(!forward)
                            .opacity(forward ? 1 : 0.35)
                    }
                }
                // Por cima dos botões, sem os mexer.
                .overlay(alignment: .top) {
                    if seekDelta != 0 {
                        Text(seekLabel).font(.footnote.weight(.semibold))
                            .padding(.horizontal, 12).padding(.vertical, 5)
                            .background(Color.black.opacity(0.55), in: Capsule())
                            .fixedSize()
                            .offset(y: -32)
                    }
                }
                Spacer()
                TimelineView(.periodic(from: .now, by: 1)) { _ in hud }
                    .padding(.horizontal, fullscreen ? 24 : 12)
                    .padding(.top, fullscreen ? 50 : 26)
                    .padding(.bottom, fullscreen ? 12 : 2)
                    .background(LinearGradient(colors: [.clear, Theme.background.opacity(0.6), Theme.background.opacity(0.92)], startPoint: .top, endPoint: .bottom))
            }
        }
        .foregroundStyle(.white)
    }

    /// O programa a dar (ou o do catch-up), o seguinte e a barra; em ecrã inteiro também os botões.
    @ViewBuilder private var hud: some View {
        if let channel = model.current {
            let s = model.strings
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let pair = model.nowNext(for: channel)
            let programme: EpgEntry? = model.catchup.map { EpgEntry(title: $0.title, startMs: $0.startMs, endMs: $0.endMs) } ?? pair.now
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .bottom, spacing: fullscreen ? 16 : 10) {
                    if model.prefs.showLogos {
                        ChannelLogo(url: model.logo(for: channel), name: channel.name, size: fullscreen ? CGSize(width: 92, height: 52) : CGSize(width: 56, height: 32))
                            .shadow(color: .black.opacity(0.6), radius: 6)
                    }
                    VStack(alignment: .leading, spacing: 1) {
                        if fullscreen {
                            Text((model.prefs.showNumbers ? "\(channel.num)  " : "") + channel.name + (model.isFavorite(channel) ? "  ★" : ""))
                                .font(.caption.weight(.semibold)).foregroundStyle(Theme.textDim).lineLimit(1)
                        }
                        Text(programme?.title ?? channel.name).font(.system(size: fullscreen ? 22 : 16, weight: .heavy)).lineLimit(1)
                        if let programme {
                            let left = model.catchup == nil ? " · " + s.r("minutes_left", "\(max(0, (programme.endMs - now + 59_999) / 60_000))") : ""
                            Text("\(hhmm(programme.start)) — \(hhmm(programme.end))" + left)
                                .font(.system(size: fullscreen ? 13 : 12)).foregroundStyle(Theme.textDim).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if fullscreen, model.catchup == nil, let next = pair.next {
                        VStack(alignment: .trailing, spacing: 1) {
                            Text(s.r("next", hhmm(next.start))).font(.caption2).foregroundStyle(Theme.textDim)
                            Text(next.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                        }
                        .frame(maxWidth: 220, alignment: .trailing)
                    }
                    if !fullscreen {
                        hudButton("arrow.up.left.and.arrow.down.right", action: rotate)
                    }
                }
                progressRow
                if fullscreen {
                    HStack(spacing: 8) {
                        // Num ecrã estreito (ou com textos compridos) as pílulas deslizam; os botões da direita ficam.
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                liveButton
                                // Catch-up ou atrás do direto: a velocidade (1× → 1,25× → 1,5× → 2×).
                                if model.catchup != nil || model.player.timeshift.shifted {
                                    hudPill("gauge.with.dots.needle.67percent", "\(s.r("playback_speed")) \(speedLabel(model.player.speed))") {
                                        model.player.speed = PlaybackSpeed.next(model.player.speed)
                                        touched += 1
                                    }
                                }
                                hudPill("calendar", s.r("tab_guide")) { onGuide(); touched += 1 }
                                hudPill(model.isFavorite(channel) ? "star.fill" : "star", s.r("favorite")) {
                                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                    model.toggleFavorite(channel)
                                    touched += 1
                                }
                                hudPill("list.bullet", s.r("search_channels"), action: rotate)
                            }
                        }
                        ForEach(PlayerOption.shown(model)) { o in
                            hudButton(o.symbol) { option = o; touched += 1 }
                        }
                    }
                    .padding(.top, 4)
                }
            }
        }
    }

    /// Onde vai e quanto dura: o catch-up, ou o programa a dar. Em direto, só se pode arrastar com a pausa do direto
    /// ou com catch-up; na pausa do direto, o ponto fica onde está o que se vê.
    private var barState: (position: Int64, duration: Int64, seekable: Bool)? {
        if let catchup = model.catchup { return (model.catchupPosition, catchup.durationMs, true) }
        guard let channel = model.current, let entry = model.nowNext(for: channel).now, entry.endMs > entry.startMs else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ts = model.player.timeshift
        return (now - entry.startMs - (ts.available ? ts.behindMs : 0), entry.endMs - entry.startMs, ts.available || model.canCatchup(channel, entry))
    }

    /// Em direto, as posições contam-se desde o início do programa a dar (ou desde agora, sem guia).
    private func liveBase(_ now: Int64) -> Int64 {
        model.current.flatMap { model.nowNext(for: $0).now?.startMs } ?? now
    }

    /// Onde vai agora: no catch-up, no programa; em direto, desde o início do programa a dar (atrás do direto, na
    /// pausa do direto).
    private var currentPosition: Int64 {
        if model.catchup != nil { return model.catchupPosition }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ts = model.player.timeshift
        return now - (ts.available ? ts.behindMs : 0) - liveBase(now)
    }

    /// Em direto: quanto [target] fica atrás do direto, se for a pausa do direto (no disco) a lá chegar; senão nil
    /// (catch-up, ou sem pausa do direto).
    private func tsBehind(_ target: Int64) -> Int64? {
        let ts = model.player.timeshift
        guard model.catchup == nil, ts.available, let channel = model.current else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let behind = now - liveBase(now) - target
        // Mais atrás do que o disco guarda: o catch-up do programa, se houver.
        if behind <= ts.windowMs { return max(0, behind) }
        guard let entry = model.nowNext(for: channel).now, model.canCatchup(channel, entry) else { return ts.windowMs }
        return nil
    }

    @ViewBuilder private var progressRow: some View {
        if let bar = barState {
            let fraction = scrub ?? min(max(Double(bar.position) / Double(bar.duration), 0), 1)
            HStack(spacing: 8) {
                // No catch-up conta onde vai; em direto, as horas já estão por cima (atrás do direto, quanto).
                if model.catchup != nil { Text(durationText(Int64(fraction * Double(bar.duration)))) }
                if model.catchup == nil, model.player.timeshift.shifted, scrub == nil { Text(behindLabel(model.player.timeshift.behindMs)) }
                PlayerBar(progress: fraction, seekable: bar.seekable) { value in
                    scrub = value
                    touched += 1
                } onEnd: { value in
                    scrub = nil
                    goTo(Int64(value * Double(bar.duration)), livePosition: bar.position)
                }
                if model.catchup != nil { Text(durationText(bar.duration)) }
            }
            .font(.caption2.monospacedDigit())
        }
    }

    /// O botão do direto: em direto só avisa; em catch-up ou na pausa do direto, volta (e diz quanto vai atrás).
    private var liveButton: some View {
        let ts = model.player.timeshift
        let live = model.catchup == nil && !ts.shifted
        return Button {
            if model.catchup != nil { model.exitCatchup() } else { model.player.timeshiftGoLive() }
        } label: {
            HStack(spacing: 6) {
                Circle().fill(live ? Color.red : Color.white.opacity(0.6)).frame(width: 8, height: 8)
                Text(live ? model.strings.r("player_live") : model.strings.r("player_go_live"))
                    .font(.footnote.weight(.semibold))
                if !live, model.catchup == nil {
                    Text(behindLabel(ts.behindMs)).font(.footnote.weight(.semibold).monospacedDigit()).foregroundStyle(.white.opacity(0.7))
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(live ? Color.clear : Color.white.opacity(0.16), in: Capsule())
        }
        .disabled(live)
    }

    private func durationText(_ ms: Int64) -> String {
        let total = max(ms, 0) / 1000
        let hours = total / 3600
        let minutes = total / 60 % 60
        let seconds = total % 60
        return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, seconds) : String(format: "%d:%02d", minutes, seconds)
    }

    /// Larguei a barra: no catch-up, vai para lá; em direto, entra no catch-up do programa a dar.
    private func goTo(_ target: Int64, livePosition: Int64) {
        seekTask?.cancel()
        seekDelta = 0
        touched += 1
        if let behind = tsBehind(target) {
            model.player.timeshiftSeek(behindMs: behind)
            return
        }
        if model.catchup != nil {
            model.seekCatchup(target)
        } else if target < livePosition - 5_000, let channel = model.current, let entry = model.nowNext(for: channel).now {
            model.playCatchup(channel, entry, positionMs: liveLanding(target, livePosition: livePosition, entry: entry))
        }
    }

    /// Onde um salto a partir do direto vai parar: os últimos minutos ainda não estão no arquivo do servidor, e o
    /// servidor só abre em minutos certos.
    private func liveLanding(_ target: Int64, livePosition: Int64, entry: EpgEntry) -> Int64 {
        let wanted = max(0, min(target, livePosition - Catchup.minAgeMs - seekStep))
        return max(0, wanted - (entry.startMs + wanted) % 60_000)
    }

    /// Para onde vão os toques em ±30 s.
    private var seekLabel: String {
        let target = seekBase + seekDelta
        if model.catchup != nil {
            if model.catchupGoesLive(target) { return model.strings.t("Go live", "Ir para o direto") }
            return "→ " + durationText(model.catchupLanding(target))
        }
        if let behind = tsBehind(target) {
            return behind <= Timeshift.liveSnapMs ? model.strings.r("player_go_live") : behindLabel(behind)
        }
        guard let channel = model.current, let entry = model.nowNext(for: channel).now else { return "" }
        return "→ " + durationText(liveLanding(target, livePosition: seekBase, entry: entry))
    }

    private var seekStep: Int64 { 30_000 }

    private func seekButton(_ name: String, _ delta: Int64) -> some View {
        Button { seek(delta) } label: { Image(systemName: name).font(.title).frame(width: 52, height: 52) }
    }

    /// Vários toques seguidos somam-se, e só depois se pede a posição nova: cada pedido reabre o stream.
    private func seek(_ delta: Int64) {
        if seekDelta == 0 { seekBase = currentPosition }
        seekDelta += delta
        touched += 1
        seekTask?.cancel()
        seekTask = Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            let target = seekBase + seekDelta
            seekDelta = 0
            goTo(target, livePosition: seekBase)
        }
    }

    private func rotate() {
        touched += 1
        PhoneOrientation.force(fullscreen ? .portrait : .landscape)
    }

    private func hudButton(_ name: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 16, weight: .semibold)).frame(width: 40, height: 40)
                .background(Color.white.opacity(0.14), in: Circle())
        }
    }

    /// Um botão com texto por cima do vídeo (ecrã inteiro).
    private func hudPill(_ symbol: String, _ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 14, weight: .semibold))
                Text(title).font(.footnote.weight(.semibold)).lineLimit(1)
            }
            .padding(.horizontal, 13).padding(.vertical, 8)
            .background(Color.white.opacity(0.14), in: Capsule())
        }
    }
}

/// A barra de progresso do leitor, para arrastar.
/// O canal não abriu: o que se passou e a causa por cima do vídeo, com "Tentar de novo" e "Como resolver".
private struct PlaybackErrorCard: View {
    @Environment(AppModel.self) private var model
    let info: ErrorInfo
    let status: String?
    let onExplain: () -> Void

    var body: some View {
        let s = model.strings
        VStack(spacing: 6) {
            Text(info.title).font(.subheadline.weight(.semibold)).multilineTextAlignment(.center).lineLimit(2)
            if let cause = info.cause {
                Text(cause).font(.caption).foregroundStyle(Theme.textDim).multilineTextAlignment(.center).lineLimit(3)
            }
            if let status { Text(status).font(.caption).foregroundStyle(Theme.textFaint) }
            HStack(spacing: 10) {
                Button(s.r("retry")) { model.retryPlayback() }
                    .font(.footnote.weight(.semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 7)
                    .background(Theme.accent(model.prefs.accent), in: Capsule())
                if !info.fixes.isEmpty {
                    Button(s.r("how_to_fix"), action: onExplain)
                        .font(.footnote.weight(.semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(Color.white.opacity(0.14), in: Capsule())
                }
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
        }
        .padding(14)
        .frame(maxWidth: 420)
        .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.horizontal, 16)
    }
}

/// "Como resolver": o erro por inteiro, com os passos e o código técnico (o que a TV mostra no ecrã do erro).
private struct PlaybackErrorSheet: View {
    @Environment(AppModel.self) private var model
    let info: ErrorInfo
    let onClose: () -> Void

    var body: some View {
        let s = model.strings
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(info.title).font(.title3.bold())
                if let cause = info.cause { Text(cause).foregroundStyle(Theme.textDim) }
                Text(s.r("how_to_fix").uppercased()).font(.caption.weight(.bold)).foregroundStyle(Theme.textFaint).padding(.top, 8)
                ForEach(Array(info.fixes.enumerated()), id: \.offset) { i, fix in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("\(i + 1)").font(.footnote.weight(.semibold))
                            .frame(width: 24, height: 24)
                            .background(Color.white.opacity(0.10), in: Circle())
                        Text(fix).font(.subheadline)
                    }
                }
                if let detail = info.detail { Text(detail).font(.caption).foregroundStyle(Theme.textFaint).padding(.top, 8) }
                Button(s.r("retry")) { onClose(); model.retryPlayback() }
                    .font(.headline).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(Theme.accent(model.prefs.accent), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .buttonStyle(.plain)
                    .padding(.top, 12)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .foregroundStyle(Theme.text)
        .background(Theme.background)
        .presentationDetents([.medium, .large])
    }
}

private struct PlayerBar: View {
    @Environment(AppModel.self) private var model
    let progress: Double
    let seekable: Bool
    let onChange: (Double) -> Void
    let onEnd: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let fraction = { (x: CGFloat) in Double(min(max(x / max(width, 1), 0), 1)) }
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.22)).frame(height: 4)
                Capsule().fill(Theme.accent(model.prefs.accent)).frame(width: width * progress, height: 4)
                // O ponto onde vai, como na barra larga da TV.
                Circle().fill(Color.white).frame(width: 10, height: 10)
                    .background(Circle().fill(Color.white.opacity(0.25)).frame(width: 18, height: 18))
                    .offset(x: width * progress - 5)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(seekable ? DragGesture(minimumDistance: 0)
                .onChanged { onChange(fraction($0.location.x)) }
                .onEnded { onEnd(fraction($0.location.x)) } : nil)
        }
        .frame(height: 30)
    }
}

/// O que se escolhe no canal a dar, cada um no seu botão.
enum PlayerOption: String, CaseIterable, Identifiable {
    case audio, subtitles, backup, sleep

    var id: String { rawValue }
    var symbol: String {
        switch self {
        case .audio: "music.note"
        case .subtitles: "captions.bubble"
        case .backup: "arrow.left.arrow.right.circle"
        case .sleep: "moon.fill"
        }
    }
    var key: String {
        switch self {
        case .audio: "audio"
        case .subtitles: "subtitles"
        case .backup: "backup_channel"
        case .sleep: "sleep_timer"
        }
    }

    /// Os botões a mostrar: o canal de reserva só no direto, numa lista com reserva.
    @MainActor static func shown(_ model: AppModel) -> [PlayerOption] {
        let backup = model.catchup == nil && model.content.flatMap { model.backupOf($0.playlist.id) } != nil
        return allCases.filter { $0 != .backup || backup }
    }
}

/// O áudio, as legendas ou o temporizador do canal a dar.
struct PlayerOptionsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let player: PlayerEngine
    let option: PlayerOption

    var body: some View {
        if option == .backup, let channel = model.current {
            NavigationStack { PhoneBackupChannelSheet(channel: channel) }
        } else {
            options
        }
    }

    private var options: some View {
        let s = model.strings
        return NavigationStack {
            List {
                switch option {
                case .audio:
                    let tracks = player.audioTracks
                    if tracks.isEmpty { Text(s.r("no_tracks")).foregroundStyle(Theme.textFaint) }
                    ForEach(tracks, id: \.id) { track in
                        row(track.name, track.id == player.currentAudio) { player.selectAudio(track.id) }
                    }
                case .subtitles:
                    let tracks = player.subtitleTracks
                    if tracks.isEmpty { Text(s.r("no_tracks")).foregroundStyle(Theme.textFaint) }
                    ForEach(tracks, id: \.id) { track in
                        row(track.id < 0 ? s.r("subtitles_off") : track.name, track.id == player.currentSubtitle) { player.selectSubtitle(track.id) }
                    }
                case .backup:
                    EmptyView()
                case .sleep:
                    row(s.r("subtitles_off"), model.sleepAt == nil) { model.setSleepTimer(nil) }
                    ForEach([15, 30, 45, 60, 90, 120], id: \.self) { minutes in
                        row("\(minutes) min", false) { model.setSleepTimer(minutes) }
                    }
                }
            }
            .navigationTitle(option == .sleep ? model.sleepAt.map { s.r("sleep_remaining", "\(Int($0.timeIntervalSinceNow / 60) + 1)") } ?? s.r("sleep_timer") : s.r(option.key))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ title: String, _ checked: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
            dismiss()
        } label: {
            HStack {
                Text(title).foregroundStyle(Theme.text)
                Spacer()
                if checked { Image(systemName: "checkmark").foregroundStyle(Theme.sky) }
            }
        }
    }
}

private struct ChannelsTab: View {
    @Environment(AppModel.self) private var model
    @Binding var epgFor: Channel?
    var guide = false
    @State private var group: String?
    @State private var pinFor: ChannelGroup?
    @State private var pin = ""

    var body: some View {
        let groups = model.groups
        // O grupo pode não estar à vista (ex.: "Todos" escondido): fica o do canal a dar, ou o primeiro.
        let wanted = group ?? model.groupId
        let shown = groups.contains { $0.id == wanted } ? wanted
            : groups.first { $0.id == model.current?.categoryId }?.id
            ?? groups.first { !model.isGroupLocked($0.id) }?.id ?? wanted
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(groups, id: \.id) { g in
                        let locked = model.isGroupLocked(g.id)
                        let on = g.id == shown
                        Button {
                            if locked { pinFor = g } else { group = g.id }
                        } label: {
                            Text(locked ? "🔒 \(g.name)" : g.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(on ? Theme.sky : Theme.textFaint)
                                .padding(.horizontal, 12).padding(.vertical, 8)
                                .background(on ? selectedFill : .clear, in: listShape)
                        }
                        .id(g.id)
                    }
                }
                .padding(.horizontal, 4).padding(.vertical, 8)
            }
            // O grupo em uso fica sempre à vista, mesmo com muitos grupos antes dele.
            .onAppear { proxy.scrollTo(shown, anchor: .center) }
            .onChange(of: shown) { _, id in withAnimation { proxy.scrollTo(id, anchor: .center) } }
            }
            if guide {
                PhoneGuideList(channels: model.channels(in: shown), group: shown, epgFor: $epgFor)
            } else {
                ChannelList(channels: model.channels(in: shown), group: shown, epgFor: $epgFor)
            }
        }
        .alert(model.strings.r("enter_pin"), isPresented: Binding(get: { pinFor != nil }, set: { if !$0 { pinFor = nil } })) {
            SecureField("PIN", text: $pin).keyboardType(.numberPad)
            Button("OK") {
                if let g = pinFor {
                    if model.unlockGroup(g.id, pin: pin) { group = g.id } else { model.showToast(model.pinRejected()) }
                }
                pin = ""
            }
            Button(model.strings.r("cancel"), role: .cancel) { pin = "" }
        }
    }
}

private struct ChannelList: View {
    @Environment(AppModel.self) private var model
    let channels: [Channel]
    let group: String
    @Binding var epgFor: Channel?

    var body: some View {
        if channels.isEmpty {
            Text(model.strings.r("no_channels_in_group")).foregroundStyle(Theme.textFaint)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(channels) { channel in
                        ChannelCard(channel: channel, group: group, epgFor: $epgFor)
                    }
                }
                .padding(.horizontal, 6).padding(.bottom, 12)
            }
        }
    }
}

struct ChannelCard: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let group: String
    /// O programa encontrado na pesquisa, em vez do que está a dar.
    var found: EpgEntry?
    @Binding var epgFor: Channel?
    @Environment(\.played) private var played

    var body: some View {
        let playing = model.current?.key == channel.key
        let time = Int64(Date().timeIntervalSince1970 * 1000)
        let now = found ?? (model.prefs.epgInList ? model.nowNext(for: channel).now : nil)
        Button {
            if let found, found.startMs > time {
                epgFor = channel
            } else {
                model.play(channel, from: group)
                played()
            }
        } label: {
            HStack(spacing: 14) {
                if model.prefs.showLogos {
                    ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 56, height: 38))
                }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(channel.name).font(.body.weight(.semibold)).foregroundStyle(playing ? Theme.sky : Theme.text).lineLimit(1)
                        Spacer(minLength: 8)
                        if model.prefs.showNumbers {
                            Text("\(channel.num)").font(.footnote).monospacedDigit().foregroundStyle(Theme.textFaint.opacity(0.6))
                        }
                    }
                    if let now {
                        Text(found == nil ? now.title : "\(phoneTime(now.startMs))  \(now.title)").font(.footnote).foregroundStyle(Theme.textFaint).lineLimit(1)
                        if now.startMs <= time {
                            ThinProgress(value: Double(now.progress(now: time)), color: playing ? Theme.sky : Color.white.opacity(0.34))
                                .padding(.top, 5)
                        }
                    }
                }
            }
            .padding(.horizontal, 10).padding(.vertical, 11)
            .background(playing ? selectedFill : .clear, in: listShape)
            .contentShape(listShape)
        }
        .simultaneousGesture(LongPressGesture().onEnded { _ in epgFor = channel })
        .task { model.requestShortEpg(channel) }
    }
}

/// A barra do programa a dar: fina, para não pesar em cada linha.
private struct ThinProgress: View {
    let value: Double
    let color: Color

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.08))
                Capsule().fill(color).frame(width: geo.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 2)
    }
}

/// O guia de um canal (manter o dedo no cartão): o passado para catch-up, o futuro para lembretes.
private struct GuideSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.played) private var played
    let channel: Channel
    @State private var pickingLogo: Bool?
    /// A mudar o nome do canal (o texto escrito; vazio volta ao da lista).
    @State private var renaming = false
    @State private var newName = ""

    var body: some View {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let entries = model.programmes(for: channel)
        NavigationStack {
            ScrollViewReader { proxy in
                List(entries, id: \.startMs) { e in
                    let catchup = model.canCatchup(channel, e)
                    let future = e.startMs > now
                    Button {
                        if catchup {
                            model.playCatchup(channel, e)
                            played()
                            dismiss()
                        } else if future {
                            model.toggleReminder(channel, e)
                        }
                    } label: {
                        HStack {
                            Text(Date(timeIntervalSince1970: Double(e.startMs) / 1000), style: .time).foregroundStyle(Theme.textFaint)
                            Text(e.title).foregroundStyle(e.endMs <= now ? Theme.textFaint : Theme.text)
                            Spacer()
                            if catchup { Image(systemName: "gobackward") }
                            if future, model.hasReminder(channel, e) { Image(systemName: "bell.fill").foregroundStyle(Theme.sky) }
                            // Gravar → gravar a série → desmarcar (como o botão do Android).
                            if e.endMs > now {
                                let mark = model.recordMark(channel, e)
                                Button { model.cycleRecording(channel, e) } label: {
                                    Image(systemName: mark == .series ? "record.circle.fill" : "record.circle")
                                        .foregroundStyle(mark == .unmarked ? Theme.textFaint : Color.red)
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel(model.strings.r("record"))
                            }
                        }
                    }
                }
                .overlay { if entries.isEmpty { Text(model.strings.r("no_guide")).foregroundStyle(Theme.textFaint) } }
                .onAppear {
                    if let e = entries.first(where: { $0.endMs > now }) { proxy.scrollTo(e.startMs, anchor: .top) }
                }
            }
            .navigationTitle(model.content?.byKey[channel.key]?.name ?? channel.name)
            .navigationBarTitleDisplayMode(.inline)
            .alert(model.strings.r("channel_rename"), isPresented: $renaming) {
                TextField(channel.name, text: $newName)
                Button(model.strings.t("Save", "Gravar")) { model.setChannelName(channel, newName) }
                Button(model.strings.r("cancel"), role: .cancel) {}
            } message: {
                Text(model.strings.r("channel_name_sub"))
            }
            .toolbar {
                Button {
                    newName = model.channelNameChoice(channel) ?? ""
                    renaming = true
                } label: { Image(systemName: "pencil") }
                if model.hasAccount {
                    Menu {
                        Button(model.strings.t("Guide channel", "Canal do guia")) { pickingLogo = false }
                        Button(model.strings.t("Channel logo", "Logótipo do canal")) { pickingLogo = true }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
                Button { model.toggleFavorite(channel) } label: { Image(systemName: model.isFavorite(channel) ? "star.fill" : "star") }
            }
            .navigationDestination(item: $pickingLogo) { logo in
                PhoneEpgPick(channel: channel, logo: logo) { dismiss() }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct TabRow: View {
    @Environment(AppModel.self) private var model
    @Binding var tab: PhoneTab
    /// Toque no separador que já está aberto.
    var reselect: (PhoneTab) -> Void = { _ in }

    var body: some View {
        let s = model.strings
        HStack(spacing: 0) {
            item(.tv, "TV", "tv")
            item(.guide, s.t("Guide", "Guia"), "calendar")
            item(.favorites, s.r("favorites"), "star")
            item(.search, s.r("search"), "magnifyingglass")
            item(.menu, s.r("settings"), "gearshape")
        }
        .padding(.horizontal, 8).padding(.top, 6)
        .background { Theme.hex(0x0C0E13).ignoresSafeArea(edges: .bottom) }
        .overlay(alignment: .top) { Rectangle().fill(Color.white.opacity(0.1)).frame(height: 0.5) }
    }

    private func item(_ t: PhoneTab, _ label: String, _ icon: String) -> some View {
        let on = tab == t
        return Button {
            if tab == t { reselect(t) }
            tab = t
        } label: {
            VStack(spacing: 2) {
                Image(systemName: on && icon != "magnifyingglass" && icon != "calendar" ? "\(icon).fill" : icon).font(.system(size: 19))
                    .overlay(alignment: .topTrailing) {
                        // Há versão nova: as Definições dizem onde a ir buscar.
                        if t == .menu, model.updateVersion != nil {
                            Circle().fill(Theme.sky).frame(width: 8, height: 8).offset(x: 4, y: -2)
                        }
                    }
                Text(label).font(.caption2).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .foregroundStyle(on ? Theme.sky : Theme.textFaint)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private extension View {
    /// A linha fina por baixo de uma fila das Definições.
    func rowLine(leading: CGFloat = 16) -> some View {
        overlay(alignment: .bottom) {
            Rectangle().fill(Color.white.opacity(0.08)).frame(height: 0.5).padding(.leading, leading)
        }
    }
}

/// O valor numa fila: as explicações longas ficam para a lista de opções.
private func shortLabel(_ label: String) -> String {
    label.count > 24 ? (label.components(separatedBy: " (").first ?? label) : label
}

private struct MenuRowLabel: View {
    let title: String
    var value: String?
    var icon: String?
    var chevron = false
    var checked = false

    var body: some View {
        HStack(spacing: 0) {
            if let icon {
                Image(systemName: icon).font(.system(size: 16)).foregroundStyle(Theme.textDim)
                    .frame(width: 34, height: 34)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .padding(.trailing, 14)
            }
            Text(title).foregroundStyle(Theme.text).lineLimit(1).layoutPriority(1)
            Spacer(minLength: 8)
            if let value { Text(value).foregroundStyle(Theme.textFaint).lineLimit(1) }
            if checked { Image(systemName: "checkmark").font(.footnote.weight(.semibold)).foregroundStyle(Theme.sky) }
            if chevron {
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(Theme.textFaint.opacity(0.7)).padding(.leading, 8)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .contentShape(Rectangle())
        .rowLine(leading: icon == nil ? 16 : 64)
    }
}

private struct MenuRow: View {
    let title: String
    var value: String?
    var chevron = false
    var checked = false
    let action: () -> Void

    init(_ title: String, value: String? = nil, chevron: Bool = false, checked: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.value = value
        self.chevron = chevron
        self.checked = checked
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            MenuRowLabel(title: title, value: value, chevron: chevron, checked: checked)
        }
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Definições do telemóvel: um ecrã curto com as categorias, e uma página para cada.
private struct MenuTab: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false
    @State private var choosingPin = false
    @State private var pinText = ""
    @State private var unlocked = false
    /// Com PIN, a página do controlo parental e o terminar sessão pedem-no sempre (como na TV).
    @State private var path = NavigationPath()
    @State private var askingParental = false
    @State private var parentalPin = ""
    @State private var askingSignOut = false
    @State private var reporting = false
    @State private var message = ""
    @State private var picking: Picking?
    @State private var downloadingUpdate = false
    @State private var downloadedIPA: DownloadedIPA?

    /// Descarrega a IPA da release e abre a folha de partilha, para a passar à app que a assina e instala.
    /// Sem conseguir, abre a página da release no navegador (ver PhoneUpdate).
    private func downloadUpdate() {
        guard !downloadingUpdate else { return }
        downloadingUpdate = true
        Task {
            downloadedIPA = await PhoneUpdate.downloadIPA()
            downloadingUpdate = false
        }
    }
    @State private var managingGroups = false
    /// A lista tocada (usar, recarregar, editar, remover), a que se edita e a que se vai remover.
    @State private var openedPlaylist: Playlist?
    @State private var editingPlaylist: Playlist?
    /// A lista cuja lista de reserva se está a escolher.
    @State private var backupPlaylistFor: Playlist?
    @State private var removingPlaylist: Playlist?
    @State private var confirmingSignOut = false
    @State private var confirmingPinOff = false
    @State private var pickingGuideCountry = false
    @State private var confirmingEpgReset = false
    @State private var pickingLogoSource = false
    @State private var editingAgent = false
    @State private var agentText = ""

    private enum Page: Hashable { case playback, channels, guide, appearance, parental }

    /// Uma escolha aberta numa página própria, com as opções todas à vista.
    struct Picking: Identifiable {
        let id: String
        let title: String
        let options: [(JSON?, String)]
        let current: JSON?
    }

    private var refreshOptions: [(JSON?, String)] {
        let s = model.strings
        return Prefs.refreshChoices.map { h in
            (.number(Double(h)), h == 0 ? s.t("Every time the app opens", "Sempre que a app abre") : s.t("Every %1$@ h", "De %1$@ em %1$@ h", "\(h)"))
        }
    }

    private var formatOptions: [(JSON?, String)] {
        [(nil, "Auto"), (.string("ts"), "TS (MPEG-TS)"), (.string("m3u8"), "HLS (m3u8)")]
    }

    private var bufferOptions: [(String, String)] {
        let s = model.strings
        return [("live", s.r("buffer_live")), ("small", s.r("buffer_small")), ("normal", s.r("buffer_normal")), ("large", s.r("buffer_large"))]
    }

    private var startupOptions: [(String, String)] {
        let s = model.strings
        return [(Prefs.PhoneStartup.home, s.r("home_screen")), (Prefs.PhoneStartup.last, s.r("startup_last"))]
    }

    private var languageOptions: [(String, String)] {
        [("auto", model.strings.r("language_auto"))] + Strings.languages.map { ($0.code, $0.name) }
    }

    private var accentOptions: [(String, String)] {
        let s = model.strings
        return [("itv", "GoatTV"), ("violet", s.t("Violet", "Violeta")), ("mint", s.t("Mint", "Menta")), ("sunset", s.t("Sunset", "Pôr do sol")), ("gold", s.t("Gold", "Dourado")), ("white", s.t("White", "Branco"))]
    }

    private var languageCode: String {
        model.prefs.language == "pt" ? "pt-PT" : model.prefs.language
    }

    private func label(_ options: [(String, String)], _ value: String) -> String {
        options.first { $0.0 == value }?.1 ?? ""
    }

    private func title(of page: Page) -> String {
        let s = model.strings
        switch page {
        case .playback: return s.r("playback")
        case .channels: return s.t("Channels", "Canais")
        case .guide: return s.t("TV guide", "Guia de programação")
        case .appearance: return s.t("Appearance", "Aparência")
        case .parental: return s.r("parental")
        }
    }

    private func pageRow(_ page: Page, value: String, icon: String) -> some View {
        NavigationLink(value: page) {
            MenuRowLabel(title: title(of: page), value: value, icon: icon, chevron: true)
        }
        .buttonStyle(.plain)
    }

    var body: some View {
        let s = model.strings
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 14) { rootRows }.padding(.vertical, 8)
            }
            .background(Theme.background)
            .navigationTitle(s.r("settings"))
            .navigationDestination(for: Page.self) { page in
                ScrollView {
                    VStack(spacing: 14) { rows(of: page) }.padding(.vertical, 8)
                }
                .background(Theme.background)
                .navigationTitle(title(of: page))
                .navigationBarTitleDisplayMode(.inline)
            }
        }
        .tint(Theme.sky)
        .sheet(isPresented: $adding) { PlaylistFormPage { adding = false } }
        .sheet(item: $downloadedIPA) { ShareSheet(url: $0.url) }
        .sheet(item: $picking) { pick in
            NavigationStack {
                List(pick.options.indices, id: \.self) { i in
                    Button {
                        model.setPref(pick.id, pick.options[i].0)
                        picking = nil
                    } label: {
                        HStack {
                            Text(pick.options[i].1).foregroundStyle(Theme.text)
                            Spacer()
                            if pick.options[i].0 == pick.current { Image(systemName: "checkmark").foregroundStyle(Theme.sky) }
                        }
                    }
                }
                .navigationTitle(pick.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel")) { picking = nil } } }
            }
        }
        .sheet(isPresented: $pickingGuideCountry) { PhoneEpgCountries(logo: false) { pickingGuideCountry = false } }
        .confirmationDialog(openedPlaylist?.name ?? "", isPresented: Binding(get: { openedPlaylist != nil }, set: { if !$0 { openedPlaylist = nil } }), titleVisibility: .visible, presenting: openedPlaylist) { pl in
            let current = pl.id == model.content?.playlist.id
            if !current { Button(s.r("use_playlist")) { model.switchPlaylist(pl) } }
            Button(s.r("reload_channels")) { if current { model.reloadPlaylist() } else { model.switchPlaylist(pl) } }
            // A lista de reserva (como na TV): o mesmo canal de outra lista quando um canal em direto não abre nesta.
            if model.playlists.count > 1 {
                Button(s.r("backup_playlist") + ": " + (model.backupOf(pl.id)?.name ?? s.r("backup_playlist_none"))) { backupPlaylistFor = pl }
            }
            // Juntar as versões do mesmo canal (HD, SD, FHD…) numa linha: liga e desliga.
            let merged = model.prefs.mergeVariants.contains(pl.id)
            Button(s.r("merge_variants") + ": " + (merged ? s.t("On", "Ligado") : s.t("Off", "Desligado"))) { model.setMergeVariants(pl.id, !merged) }
            Button(s.r("edit")) { editingPlaylist = pl }
            Button(s.r("remove_playlist"), role: .destructive) { removingPlaylist = pl }
            Button(s.r("cancel"), role: .cancel) {}
        }
        .alert(s.r("remove_playlist"), isPresented: Binding(get: { removingPlaylist != nil }, set: { if !$0 { removingPlaylist = nil } }), presenting: removingPlaylist) { pl in
            Button(s.r("remove_playlist"), role: .destructive) {
                Task {
                    let error = await model.deletePlaylist(pl)
                    model.showToast(error ?? s.r("playlist_removed"))
                }
            }
            Button(s.r("cancel"), role: .cancel) {}
        } message: { pl in
            Text(model.hasAccount ? s.r("remove_playlist_account_sub") : pl.name)
        }
        .sheet(isPresented: Binding(get: { editingPlaylist != nil }, set: { if !$0 { editingPlaylist = nil } })) {
            if let pl = editingPlaylist { PlaylistFormPage(editing: pl) { editingPlaylist = nil } }
        }
        .sheet(isPresented: Binding(get: { backupPlaylistFor != nil }, set: { if !$0 { backupPlaylistFor = nil } })) {
            if let pl = backupPlaylistFor { PhoneBackupPlaylistPicker(playlist: pl) { backupPlaylistFor = nil } }
        }
        .alert(s.r("logout"), isPresented: $confirmingSignOut) {
            Button(s.r("logout"), role: .destructive) {
                if model.signOutNeedsPin { askingSignOut = true } else { model.signOut() }
            }
            Button(s.r("cancel"), role: .cancel) {}
        } message: {
            Text(model.email.map(phoneMaskEmail) ?? "")
        }
        .alert(s.r("parental_off"), isPresented: $confirmingPinOff) {
            Button(s.t("Remove PIN", "Tirar o PIN"), role: .destructive) {
                model.setPin(nil)
                model.showToast(s.r("parental_off_toast"))
            }
            Button(s.r("cancel"), role: .cancel) {}
        }
        .sheet(isPresented: $pickingLogoSource) { PhoneEpgCountries(logo: true) { pickingLogoSource = false } }
        .sheet(isPresented: $managingGroups) {
            NavigationStack {
                List {
                    Section {
                        Toggle(s.r("favorites_star"), isOn: Binding(get: { model.prefs.showFavoritesGroup }, set: { model.setPref("show_favorites_group", .bool($0)) }))
                        Toggle(s.r("all"), isOn: Binding(get: { model.prefs.showAllGroup }, set: { model.setPref("show_all_group", .bool($0)) }))
                        Toggle(s.r("recents"), isOn: Binding(get: { model.prefs.showRecentGroup }, set: { model.setPref("show_recent_group", .bool($0)) }))
                    }
                    Section {
                        ForEach(model.allGroups, id: \.id) { g in
                            Toggle(g.name, isOn: Binding(get: { !model.isGroupHidden(g.id) }, set: { model.setGroupHidden(g.id, !$0) }))
                        }
                    }
                }
                .navigationTitle(s.t("Visible groups", "Grupos visíveis"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { managingGroups = false } } }
            }
        }
        .alert(s.t("User agent", "User agent"), isPresented: $editingAgent) {
            TextField("", text: $agentText).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("OK") {
                let text = agentText.trimmingCharacters(in: .whitespaces)
                model.setPref("user_agent", text.isEmpty ? nil : .string(text))
            }
            Button(s.r("cancel"), role: .cancel) {}
        }
        .alert(s.r("enter_pin"), isPresented: $askingParental) {
            SecureField("PIN", text: $parentalPin).keyboardType(.numberPad)
            Button("OK") {
                if model.checkPin(parentalPin) { path.append(Page.parental) } else { model.showToast(model.pinRejected()) }
                parentalPin = ""
            }
            Button(s.r("cancel"), role: .cancel) { parentalPin = "" }
        }
        .modifier(SignOutPrompt(isPresented: $askingSignOut))
        .alert(s.r("choose_pin"), isPresented: $choosingPin) {
            SecureField("PIN", text: $pinText).keyboardType(.numberPad)
            Button("OK") {
                if pinText.count == 4 { model.setPin(pinText) }
                pinText = ""
            }
            Button(s.r("cancel"), role: .cancel) { pinText = "" }
        }
        .alert(s.r("send_report"), isPresented: $reporting) {
            TextField(s.r("report_what"), text: $message)
            Button(s.r("report_send")) {
                model.sendReport(message)
                message = ""
            }
            Button(s.r("cancel"), role: .cancel) {}
        }
        .overlay {
            if model.settingsNeedPin, !unlocked {
                VStack(spacing: 12) {
                    Text(s.r("enter_pin")).font(.headline)
                    SecureField("PIN", text: $pinText).keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .padding(14).background(cardFill, in: RoundedRectangle(cornerRadius: 14))
                        .frame(width: 180)
                        .onChange(of: pinText) { _, new in
                            guard new.count == 4 else { return }
                            unlocked = model.unlockSettings(new)
                            if !unlocked { model.showToast(model.pinRejected()) }
                            pinText = ""
                        }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background)
            }
        }
    }

    /// O ecrã principal: as listas, as categorias e a conta.
    @ViewBuilder private var rootRows: some View {
        let s = model.strings
        let p = model.prefs
        if let version = model.updateVersion {
            Button {
                downloadUpdate()
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "arrow.down.circle.fill").font(.title2).foregroundStyle(Theme.sky)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.t("Version %@ is available", "Há uma versão nova: %@", version)).foregroundStyle(Theme.text)
                        Text(downloadingUpdate ? s.t("Downloading…", "A descarregar…") : s.t("Tap to download", "Toca para descarregar")).font(.footnote).foregroundStyle(Theme.textFaint)
                    }
                    Spacer(minLength: 0)
                    if downloadingUpdate { ProgressView() }
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        // A lista em uso em destaque, como o canal atual nas Definições da TV; as outras por baixo.
        if let content = model.content {
            HStack(spacing: 14) {
                Image(systemName: "list.bullet").font(.system(size: 20, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(Theme.accent(p.accent), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(content.playlist.name).font(.headline).foregroundStyle(Theme.text).lineLimit(1)
                    Text("\(playlistTypeName(content.playlist.type)) · \(channelCount(s, content.channels.count))").font(.footnote).foregroundStyle(Theme.textDim).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(LinearGradient(colors: [Theme.hex(0x1D2236), Theme.hex(0x12151D)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(Rectangle())
            .onTapGesture { openedPlaylist = content.playlist }
            .padding(.horizontal, 12)
        }
        // Como na TV: cada lista abre-se (usar, recarregar, editar, remover) e há sempre "Adicionar lista".
        let others = model.playlists.filter { $0.id != model.content?.playlist.id }
        section(s.r("playlists")) {
            ForEach(others, id: \.id) { pl in
                Button { openedPlaylist = pl } label: { MenuRowLabel(title: pl.name, value: playlistTypeName(pl.type), icon: "list.bullet", chevron: true) }.buttonStyle(.plain)
            }
            Button { adding = true } label: { MenuRowLabel(title: s.r("add_playlist"), icon: "plus", chevron: true) }.buttonStyle(.plain)
        }
        section(s.r("section_watching")) {
            let format = formatOptions.first { $0.0 == p.streamFormat.map { JSON.string($0) } }?.1 ?? ""
            let buffer = label(bufferOptions, p.buffer).components(separatedBy: " (").first ?? ""
            pageRow(.playback, value: "\(format.components(separatedBy: " ").first ?? ""), \(buffer)", icon: "play.circle")
            pageRow(.channels, value: label(startupOptions, p.phoneStartup), icon: "arrow.up.arrow.down")
            pageRow(.guide, value: refreshOptions.first { $0.0 == .number(Double(p.epgRefreshHours)) }?.1 ?? "", icon: "calendar")
        }
        section(s.r("section_interface")) {
            pageRow(.appearance, value: "\(label(languageOptions, languageCode)), \(label(accentOptions, p.accent))", icon: "paintpalette")
        }
        section(s.r("section_library")) {
            if p.pinHash == nil {
                pageRow(.parental, value: s.t("Off", "Desligado"), icon: "lock")
            } else {
                Button {
                    askingParental = true
                } label: {
                    MenuRowLabel(title: title(of: .parental), value: s.t("On", "Ligado"), icon: "lock", chevron: true)
                }
                .buttonStyle(.plain)
            }
            Button {
                reporting = true
            } label: {
                MenuRowLabel(title: s.r("send_report"), icon: "bubble.left", chevron: true)
            }
            if model.updateVersion == nil {
                MenuRow(s.r("check_updates"), value: model.appVersion) { model.checkForUpdate(force: true) }
            }
            if model.hasAccount {
                // Pede confirmação (na TV é carregar outra vez).
                MenuRow(s.r("logout"), value: model.email.map(phoneMaskEmail)) { confirmingSignOut = true }
            } else {
                // Sem conta: ligar a conta passa as definições deste telemóvel para lá e sincroniza tudo.
                Button { model.connectAccount() } label: {
                    MenuRowLabel(title: s.r("connect_account"), icon: "person.crop.circle", chevron: true)
                }
                .buttonStyle(.plain)
            }
        }
        Text("GoatTV \(model.appVersion)").font(.caption).foregroundStyle(Theme.textFaint).frame(maxWidth: .infinity).padding(.top, 4)
    }

    private func playlistTypeName(_ type: PlaylistType) -> String {
        switch type {
        case .xtream: return "Xtream"
        case .stalker: return "Stalker"
        default: return "M3U"
        }
    }

    /// O que fica dentro de cada categoria.
    @ViewBuilder private func rows(of page: Page) -> some View {
        let s = model.strings
        let p = model.prefs
        switch page {
        case .playback:
            section(s.t("Picture and sound", "Imagem e som")) {
                pick(s.r("aspect"), "aspect", p.aspect, [("fit", s.r("aspect_fit")), ("zoom", s.r("aspect_zoom")), ("fill", s.r("aspect_fill"))])
                toggle(s.r("surround_default"), "surround_default", p.surroundDefault)
                pick(s.r("audio_language"), "audio_language", .string(p.audioLanguage), [(.string(""), s.r("auto"))] + phoneLanguageOptions)
                pick(s.r("audio_delay"), "audio_delay_ms", .number(Double(p.audioDelayMs)),
                     Prefs.audioDelayOptions.map { (.number(Double($0)), $0 == 0 ? s.r("off") : String(format: "%+d ms", $0)) })
                pick(s.r("subtitle_language"), "subtitle_language", .string(p.subtitleLanguage), [(.string(""), s.r("subtitles_off"))] + phoneLanguageOptions)
            }
            section(s.t("Connection", "Ligação")) {
                pick(s.r("buffer"), "buffer", p.buffer, bufferOptions)
                pick(s.t("Reconnect if the stream fails", "Reconectar se a stream falhar"), "reconnect_seconds", .number(Double(p.reconnectSeconds)), [(.number(0), s.t("No", "Não"))] + [2, 5, 10, 30].map { (.number(Double($0)), s.t("After %@ s", "Ao fim de %@ s", "\($0)")) })
                toggle(s.r("timeshift"), "timeshift", p.timeshift)
                toggle(s.r("protect_paused"), "protect_paused", p.protectPaused)
                pick(s.r("zap_delay"), "zap_delay_ms", .number(Double(p.zapDelayMs)),
                     zapDelayOptions)
            }
            section(s.t("Advanced", "Avançado")) {
                pick(s.t("Stream format", "Formato do stream"), "stream_format", p.streamFormat.map { .string($0) }, formatOptions)
                pick(s.t("Player", "Player"), "engine", p.engine, [("exo", s.t("Apple + VLC", "Apple + VLC")), ("vlc", s.t("VLC only (for streams that don't open)", "Só VLC (para streams que não abram)")), ("aether", s.t("Apple + AetherEngine (experimental)", "Apple + AetherEngine (experimental)"))])
                pick(s.t("Video decoder (VLC)", "Descodificador de vídeo (VLC)"), "decoder", p.decoder, [("hw", "Hardware"), ("sw", s.t("Software (compatibility)", "Software (compatibilidade)"))])
                pick(s.r("deinterlace"), "deinterlace", p.deinterlace, [("yadif2x", s.r("deinterlace_smooth")), ("yadif", s.r("deinterlace_normal")), ("off", s.r("off"))])
                MenuRow(s.t("User agent", "User agent"), value: p.userAgent.isEmpty ? s.t("Default", "Normal") : p.userAgent, chevron: true) {
                    agentText = p.userAgent
                    editingAgent = true
                }
            }
            section(s.r("recordings")) {
                pick(s.r("recording_padding_before"), "record_padding_before_min", .number(Double(p.recordPaddingBeforeMin)), paddingOptions)
                pick(s.r("recording_padding_after"), "record_padding_after_min", .number(Double(p.recordPaddingAfterMin)), paddingOptions)
            }
        case .channels:
            section(nil) {
                pick(s.r("on_startup"), "phone_startup", p.phoneStartup, startupOptions)
                pick(s.t("Sort channels", "Ordenar canais"), "sort", p.sort, [("number", s.t("Playlist order", "Pela ordem da lista")), ("name", s.t("By name (A–Z)", "Por nome (A–Z)"))])
                toggle(s.t("Channel numbers", "Números dos canais"), "show_numbers", p.showNumbers)
                toggle(s.t("Channel logos", "Logos dos canais"), "show_logos", p.showLogos)
                toggle(s.t("Current show in the list", "Programa atual na lista"), "epg_in_list", p.epgInList)
                if model.hasAccount { MenuRow(s.t("Logo source", "Origem dos logótipos"), chevron: true) { pickingLogoSource = true } }
                MenuRow(s.t("Visible groups", "Grupos visíveis"), chevron: true) { managingGroups = true }
            }
        case .guide:
            section(nil) {
                pick(s.t("Update TV guide", "Atualizar guia"), "epg_refresh_hours", .number(Double(p.epgRefreshHours)), refreshOptions)
                pick(s.t("Update playlists", "Atualizar listas"), "playlist_refresh_hours", .number(Double(p.playlistRefreshHours)), refreshOptions)
                pick(s.t("Time offset", "Desvio horário"), "epg_offset_min", .number(Double(p.epgOffsetMin)), stride(from: -180, through: 180, by: 30).map { (.number(Double($0)), $0 == 0 ? "0" : String(format: "%+d min", $0)) })
                pick(s.r("catchup_offset"), "catchup_offset_min", .number(Double(p.catchupOffsetMin)),
                     (-12...12).map { (h: Int) -> (JSON?, String) in (.number(Double(h * 60)), h == 0 ? "0" : String(format: "%+d h", h)) })
                pick(s.r("epg_past"), "epg_past_hours", .number(Double(p.epgPastHours)), GuideWindow.pastHours.map { (.number(Double($0)), guideDays(s, $0)) })
                pick(s.r("epg_future"), "epg_future_hours", .number(Double(p.epgFutureHours)), GuideWindow.futureHours.map { (.number(Double($0)), guideDays(s, $0)) })
                toggle(s.r("reminder_switch"), "reminder_switch", p.reminderSwitch)
                if model.hasAccount { MenuRow(s.t("Guide by country", "Guia por país"), chevron: true) { pickingGuideCountry = true } }
                if model.hasAccount, !model.epgMappings.isEmpty {
                    MenuRow(confirmingEpgReset ? s.t("Press again to confirm", "Carrega outra vez para confirmar") : s.r("epg_reset_all")) {
                        if confirmingEpgReset { model.resetEpgMappings() }
                        confirmingEpgReset.toggle()
                    }
                }
                MenuRow(s.t("Update TV guide now", "Atualizar o guia agora")) { model.refreshEpg() }
            }
        case .appearance:
            section(nil) {
                pick(s.r("language"), "language", languageCode, languageOptions)
                pick(s.r("accent"), "accent", p.accent, accentOptions)
            }
        case .parental:
            section(nil) {
                if p.pinHash == nil {
                    MenuRow(s.r("new_pin")) { choosingPin = true }
                } else {
                    toggle(s.t("Protect settings", "Proteger as Definições"), "settings_pin", p.settingsPin)
                    MenuRow(s.t("Change PIN", "Mudar o PIN")) { choosingPin = true }
                    MenuRow(s.t("Remove PIN", "Tirar o PIN")) { confirmingPinOff = true }
                    ForEach(model.allGroups, id: \.id) { g in
                        MenuRow(g.name, value: model.isGroupLocked(g.id) ? "🔒" : nil) { model.toggleGroupLock(g.id) }
                    }
                }
            }
        }
    }

    /// As margens das gravações: desligada ou 1, 2, 5, 10 min (como no Android).
    private var paddingOptions: [(JSON?, String)] {
        let s = model.strings
        return Prefs.paddingOptions.map { m -> (JSON?, String) in (.number(Double(m)), m == 0 ? s.r("off") : s.r("minutes", "\(m)")) }
    }

    /// O atraso ao mudar de canal: automático, desligado, 1 ou 2 s (como no Android).
    private var zapDelayOptions: [(JSON?, String)] {
        let s = model.strings
        var list: [(JSON?, String)] = [(.number(Double(Prefs.ZapDelay.auto)), s.r("auto")), (.number(Double(Prefs.ZapDelay.off)), s.r("off"))]
        for seconds in [1, 2] { list.append((.number(Double(seconds * 1_000)), s.r("seconds_short", "\(seconds)"))) }
        return list
    }

    /// As línguas do áudio e das legendas, com o nome na língua da app e por ordem alfabética dela.
    private var phoneLanguageOptions: [(JSON?, String)] {
        let s = model.strings
        return Languages.choices.map { (code: $0, name: Languages.displayName($0, locale: s.lang)) }
            .sorted { $0.name.compare($1.name, locale: s.locale) == .orderedAscending }
            .map { item -> (JSON?, String) in (.string(item.code), item.name) }
    }

    private func section<Rows: View>(_ title: String?, @ViewBuilder rows: () -> Rows) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(title.uppercased()).font(.caption.weight(.bold)).tracking(0.6).foregroundStyle(Theme.textFaint).padding(.leading, 20)
            }
            // Num cartão, como nas Definições da TV. A linha por baixo da última fila fica de fora.
            VStack(spacing: 0) { rows() }.padding(.bottom, -0.5)
                .background(Color.white.opacity(0.05))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(.horizontal, 12)
        }
    }

    private func pick(_ title: String, _ key: String, _ value: String, _ options: [(String, String)]) -> some View {
        pick(title, key, .string(value), options.map { (.string($0.0), $0.1) })
    }

    private func pick(_ title: String, _ key: String, _ value: JSON?, _ options: [(JSON?, String)]) -> some View {
        MenuRow(title, value: (options.first { $0.0 == value }?.1).map(shortLabel), chevron: true) {
            picking = Picking(id: key, title: title, options: options, current: value)
        }
    }

    private func toggle(_ title: String, _ key: String, _ on: Bool) -> some View {
        Toggle(title, isOn: Binding(get: { on }, set: { model.setPref(key, .bool($0)) }))
            .foregroundStyle(Theme.text).tint(Theme.sky)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .rowLine()
    }
}

/// Nova lista neste aparelho (sem conta).
private struct PlaylistFormPage: View {
    @Environment(AppModel.self) private var model
    /// A lista a editar (nil: uma nova).
    let editing: Playlist?
    let onDone: () -> Void
    @State private var form: PlaylistForm
    @State private var error: String?

    init(editing: Playlist? = nil, onDone: @escaping () -> Void) {
        self.editing = editing
        self.onDone = onDone
        _form = State(initialValue: editing.map { PlaylistForm($0) } ?? PlaylistForm())
    }

    var body: some View {
        let s = model.strings
        NavigationStack {
            Form {
                // Na conta, o tipo de uma lista não muda (como na TV: para outro tipo cria-se uma lista nova).
                if editing == nil || !model.hasAccount {
                    Picker("", selection: Binding(get: { form.type }, set: { form.setType($0, deviceMac: model.deviceMac) })) {
                        Text("Xtream").tag(PlaylistType.xtream)
                        Text("M3U").tag(PlaylistType.m3u)
                        if !model.hasAccount || form.type == .stalker { Text("Stalker").tag(PlaylistType.stalker) }
                    }
                    .pickerStyle(.segmented)
                }
                TextField(s.r("name"), text: $form.name)
                if form.type == .xtream {
                    TextField(s.r("server"), text: $form.server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField(s.r("username"), text: $form.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(s.r("password"), text: $form.password)
                } else if form.type == .stalker {
                    TextField(s.t("Portal URL", "URL do portal"), text: $form.server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField(s.t("MAC address", "Endereço MAC"), text: $form.username).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    Text(s.t("Give this MAC to your provider so they can activate it, or enter the one they gave you.", "Dá este MAC ao teu fornecedor para ele o ativar, ou escreve o que ele te deu.")).font(.footnote).foregroundStyle(.secondary)
                    TextField("Serial", text: $form.serial).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Device ID", text: $form.deviceId).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Device ID 2", text: $form.deviceId2).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Signature", text: $form.signature).textInputAutocapitalization(.never).autocorrectionDisabled()
                } else {
                    TextField(s.r("playlist_url"), text: $form.url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
                TextField(s.r("epg_url"), text: $form.epgUrl).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                if let error { Text(error).foregroundStyle(Theme.error) }
            }
            .navigationTitle(editing == nil ? s.r("add_playlist") : s.r("edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(s.r("save")) {
                        Task {
                            error = await model.savePlaylist(id: editing?.id, form: form)
                            if error == nil { onDone() }
                        }
                    }
                }
            }
        }
    }
}

/// A lista de reserva de uma lista: nenhuma ou outra das listas; por baixo, quantos canais ela tem (como na TV).
private struct PhoneBackupPlaylistPicker: View {
    @Environment(AppModel.self) private var model
    let playlist: Playlist
    let onClose: () -> Void

    var body: some View {
        let s = model.strings
        let current = model.backupOf(playlist.id)?.id
        NavigationStack {
            List {
                Section {
                    row(s.r("backup_playlist_none"), current == nil) { model.setBackupPlaylist(playlist.id, nil) }
                    ForEach(model.playlists.filter { $0.id != playlist.id }) { p in
                        row(p.name, current == p.id) { model.setBackupPlaylist(playlist.id, p.id) }
                    }
                } footer: {
                    Text(model.backupSubtitle(playlist.id))
                }
                if current != nil {
                    // Automático (com as escolhas à mão por cima) ou só os canais escolhidos à mão.
                    Section(s.r("backup_mode")) {
                        let manual = model.backupManual(playlist.id)
                        row(s.r("backup_channel_auto"), !manual) { model.setBackupManual(playlist.id, false) }
                        row(s.r("backup_mode_manual"), manual) { model.setBackupManual(playlist.id, true) }
                    }
                    Section {
                        NavigationLink {
                            PhoneBackupChannelsList(playlistId: playlist.id)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(s.r("backup_channels")).foregroundStyle(Theme.text)
                                Text(s.r("backup_channels_sub")).font(.footnote).foregroundStyle(Theme.textFaint)
                            }
                        }
                    }
                }
            }
            .navigationTitle(s.r("backup_playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(s.r("done")) { onClose() } } }
        }
        .task(id: "\(current ?? "")|\(model.backupManual(playlist.id))|\(model.prefs.backupChannels.description)") { model.checkBackupCoverage(playlist.id) }
    }

    private func row(_ title: String, _ checked: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(Theme.text)
                Spacer()
                if checked { Image(systemName: "checkmark").foregroundStyle(Theme.sky) }
            }
        }
    }
}

/// Os failovers do canal a ver (como o painel da TV, ver BackupChannelPanel): a lista numerada, com a etiqueta da lista
/// de cada um, a pega para os arrastar e, ao manter carregado (ou deslizar), "Tirar"; por baixo, "Juntar um canal" (uma
/// página com a pesquisa nas duas listas e os sugeridos; um toque junta e volta), "Experimentar agora" e "Voltar ao
/// automático" (só com uma escolha à mão).
private struct PhoneBackupChannelSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let channel: Channel
    /// A lista principal (nil = a em uso) e se há "Experimentar agora" (não nas Definições, onde nada está a dar).
    var mainId: String? = nil
    var canTry = true
    @State private var choices: BackupChoices?
    @State private var loading = true
    @State private var adding = false

    var body: some View {
        let s = model.strings
        List {
                if loading {
                    Text(s.r("backup_checking")).foregroundStyle(Theme.textFaint)
                } else if let choices {
                    let failovers = choices.failovers
                    Section {
                        ForEach(Array(failovers.enumerated()), id: \.element) { i, f in
                            HStack(spacing: 12) {
                                Text("\(i + 1)").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.textFaint).frame(width: 22)
                                Text(choices.channel(of: f)?.name ?? "").foregroundStyle(Theme.text).lineLimit(1)
                                Spacer(minLength: 8)
                                PhoneSourceTag(text: model.failoverSource(choices, f))
                            }
                            .contextMenu {
                                Button(role: .destructive) { save(failovers.filter { $0 != f }) } label: {
                                    Label(s.r("backup_failover_remove"), systemImage: "trash")
                                }
                            }
                        }
                        .onMove { from, to in
                            var list = failovers
                            list.move(fromOffsets: from, toOffset: to)
                            save(list)
                        }
                        .onDelete { rows in
                            var list = failovers
                            list.remove(atOffsets: rows)
                            save(list)
                        }
                        Button { adding = true } label: {
                            Label(s.r("backup_add_channel"), systemImage: "plus").foregroundStyle(Theme.text)
                        }
                    } header: {
                        Text(failovers.isEmpty ? s.r("backup_none_note", channel.name) : s.r("backup_failovers_intro", channel.name)).textCase(nil)
                    } footer: {
                        if !failovers.isEmpty { Text(s.r("backup_failovers_hint_phone")) }
                    }
                    if (canTry && !failovers.isEmpty) || !choices.automatic {
                        Section {
                            if canTry, !failovers.isEmpty {
                                Button {
                                    model.tryBackup(failovers)
                                    dismiss()
                                } label: { Label(s.r("backup_channel_try"), systemImage: "play.fill").foregroundStyle(Theme.text) }
                            }
                            if !choices.automatic {
                                Button { save(nil) } label: {
                                    Label {
                                        VStack(alignment: .leading) {
                                            Text(s.r("backup_reset_auto")).foregroundStyle(Theme.text)
                                            Text(model.backupAutoLabel(choices)).font(.footnote).foregroundStyle(Theme.textFaint)
                                        }
                                    } icon: { Image(systemName: "arrow.counterclockwise") }
                                }
                            }
                        }
                    }
                } else {
                    Text(s.r("backup_unavailable", (mainId ?? model.content?.playlist.id).flatMap { model.backupOf($0)?.name } ?? "")).foregroundStyle(Theme.textFaint)
                }
        }
        // As pegas para arrastar os failovers (e o "−" para os tirar) ficam sempre à vista.
        .environment(\.editMode, .constant(.active))
        .navigationTitle(canTry ? s.r("backup_channel") : channel.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { if canTry { ToolbarItem(placement: .cancellationAction) { Button(s.r("close")) { dismiss() } } } }
        .presentationDetents([.medium, .large])
        .navigationDestination(isPresented: $adding) {
            if let choices {
                PhoneBackupAdd(channel: channel, choices: choices) { list in
                    save(list)
                    adding = false
                }
            }
        }
        .task {
            choices = await model.backupChoices(channel, mainId: mainId)
            loading = false
        }
    }

    private func save(_ list: [String]?) {
        guard let main = choices?.mainId else { return }
        choices?.choice = list
        model.setBackupChoice(main, channel.key, list)
    }
}

/// "Juntar um canal" (iPhone): a pesquisa nas duas listas e os sugeridos; um toque junta o canal no fim dos failovers e
/// volta ([onDone] com a lista nova, ou nil se já lá estava).
private struct PhoneBackupAdd: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let choices: BackupChoices
    let onDone: ([String]?) -> Void
    @State private var query = ""
    @State private var results: [BackupHit] = []

    var body: some View {
        let s = model.strings
        let added = Set(choices.failovers)
        let list = query.trimmingCharacters(in: .whitespaces).isEmpty ? choices.suggested : results
        List {
            Section {
                TextField(s.r("backup_channel_search_all"), text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section(query.isEmpty ? s.r("backup_suggested") : s.r("backup_channel_results")) {
                ForEach(list) { hit in
                    let f = choices.failover(hit.channel, fromMain: hit.fromMain)
                    let already = added.contains(f)
                    Button { onDone(already ? choices.failovers : choices.toggled(f)) } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(hit.channel.name).foregroundStyle(already ? Theme.textFaint : Theme.text)
                                if already { Text(s.r("backup_already_added")).font(.footnote).foregroundStyle(Theme.textFaint) }
                            }
                            Spacer(minLength: 8)
                            PhoneSourceTag(text: model.failoverSource(choices, f))
                        }
                    }
                }
            }
        }
        .navigationTitle(s.r("backup_add_channel"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: query) {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            results = model.searchBackup(choices, query, except: channel.key)
        }
    }
}

/// A etiqueta pequena com a lista de um failover ("Backup", "Esta lista").
private struct PhoneSourceTag: View {
    let text: String

    var body: some View {
        Text(text).font(.caption.weight(.medium)).foregroundStyle(Theme.textFaint)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(Color.white.opacity(0.08), in: Capsule())
    }
}

/// A lista "Canais de reserva" no iPhone (como na TV): filtros, pesquisa pelo nome do canal e, ao tocar, a escolha do
/// canal da reserva (sem "Experimentar agora").
private struct PhoneBackupChannelsList: View {
    @Environment(AppModel.self) private var model
    let playlistId: String
    @State private var filter = ChannelMatch.Filter.all
    @State private var query = ""

    var body: some View {
        let s = model.strings
        Group {
            if let review = model.backupReview(playlistId) {
                List {
                    Section {
                        Picker(s.r("backup_channels"), selection: $filter) {
                            ForEach(ChannelMatch.Filter.allCases, id: \.self) { f in
                                Text("\(model.backupFilterLabel(f)) (\(review.rows.filter { $0.passes(f) }.count))").tag(f)
                            }
                        }
                    }
                    Section {
                        ForEach(model.backupRows(review, filter, query), id: \.main) { row in
                            let text = model.backupRowText(review, row)
                            NavigationLink {
                                PhoneBackupChannelSheet(channel: review.main.channels[row.main], mainId: playlistId, canTry: false)
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(text.title).foregroundStyle(Theme.text)
                                    Text(text.subtitle).font(.footnote).foregroundStyle(Theme.textFaint)
                                }
                            }
                        }
                    }
                }
                .searchable(text: $query, prompt: s.r("backup_channel_search", review.main.playlist.name))
            } else {
                Text(s.r("backup_checking")).foregroundStyle(Theme.textFaint)
            }
        }
        .navigationTitle(s.r("backup_channels"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.prefs.backupChannels) { model.checkBackupCoverage(playlistId) }
    }
}
