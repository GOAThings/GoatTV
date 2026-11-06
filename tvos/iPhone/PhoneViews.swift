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
                Text(a.url ?? text).font(.caption).foregroundStyle(Theme.textFaint).padding(.top, 8)
            }
            Spacer()
            Button(s.r("use_without_account")) { model.leaveActivation() }
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
                    if model.hasAccount {
                        MenuRow(s.r("logout")) {
                            if model.signOutNeedsPin { askingSignOut = true } else { model.signOut() }
                        }
                    }
                }
                .background(cardFill, in: cardShape)
            }
            .padding(20)
        }
        .sheet(isPresented: $adding) { PlaylistFormPage { adding = false } }
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

    /// Abriu-se um canal: sai do ecrã inicial.
    private func played() {
        stopped = false
        home = false
        homeGroup = nil
        tab = .tv
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
                    .frame(maxWidth: .infinity, maxHeight: landscape ? .infinity : nil)
                    .frame(height: landscape ? nil : geo.size.width * 9 / (model.second == nil ? 16 : 8))
            }
            if !landscape {
                Group {
                    switch tab {
                    case .tv:
                        if !home {
                            VStack(spacing: 0) {
                                if let current = model.current { NowStrip(channel: current) { home = true } }
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
                                onSearch: { tab = .search }
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
                if let r = model.dueReminder(now) {
                    model.showToast("\(r.title) · \(r.channelName)")
                    model.consumeReminder(r, watch: false)
                }
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }
}

private struct VideoPane: View {
    @Environment(AppModel.self) private var model
    let fullscreen: Bool
    let onGuide: () -> Void
    @State private var showingOptions = false
    @State private var seekDelta: Int64 = 0
    @State private var seekBase: Int64 = 0
    @State private var seekTask: Task<Void, Never>?
    @State private var scrub: Double?
    @State private var controls = true
    @State private var touched = 0
    @State private var focusSecond = false
    @State private var pickingSecond = false

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
            // Enquanto ainda vai tentar outra vez, não há erro para mostrar.
            if let error = model.playbackError, model.player.retries >= PlayerEngine.maxStartRetries || model.prefs.reconnectSeconds == 0 {
                Text(error.title).font(.subheadline).multilineTextAlignment(.center).padding()
            } else if !controls, model.player.frozen || (model.player.state == .playing && !model.player.hasPicture) {
                ProgressView().tint(.white)
            } else if !controls, model.player.state != .playing, let current = model.current {
                Text(current.name).font(.subheadline).foregroundStyle(Theme.textDim)
            }
            if controls { overlay }
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
        .sheet(isPresented: $showingOptions) {
            PlayerOptionsSheet(player: model.second != nil && focusSecond ? model.secondPlayer : model.player)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            PhoneOrientation.deviceMoved()
        }
    }

    private var overlay: some View {
        VStack {
            HStack {
                Text(model.current?.name ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                Spacer()
                if fullscreen { Text(Date(), style: .time).font(.footnote) }
                if let c = model.current {
                    iconButton(model.isFavorite(c) ? "star.fill" : "star") {
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                        model.toggleFavorite(c)
                        touched += 1
                    }
                }
            }
            .padding(.horizontal, 12)
            .background(Color.black.opacity(0.45))
            Spacer()
            HStack(spacing: 28) {
                // Em direto, recua-se para o catch-up do programa a dar; à frente do direto não há nada.
                let canSeek = barState?.seekable == true
                if canSeek { seekButton("gobackward.30", -seekStep) }
                // A abrir, a pausa não faz nada: fica o indicador no lugar dela, sem mexer nos outros botões.
                if model.player.frozen || model.player.state == .opening || (model.player.state == .playing && !model.player.hasPicture) {
                    ProgressView().tint(.white).frame(width: 44, height: 44)
                } else {
                    iconButton(model.player.paused ? "play.fill" : "pause.fill") { model.player.togglePause(); touched += 1 }
                }
                if canSeek {
                    seekButton("goforward.30", seekStep)
                        .disabled(model.catchup == nil)
                        .opacity(model.catchup == nil ? 0.35 : 1)
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
            VStack(spacing: 0) {
                TimelineView(.periodic(from: .now, by: 1)) { _ in progressRow }
                HStack(spacing: 6) {
                    liveButton
                    Spacer()
                    iconButton("calendar") { onGuide(); touched += 1 }
                    iconButton("ellipsis") { showingOptions = true; touched += 1 }
                    if model.second == nil {
                        iconButton("rectangle.split.2x1") { pickingSecond = true; touched += 1 }
                    } else {
                        iconButton("rectangle.2.swap") { pickingSecond = true; touched += 1 }
                        iconButton("xmark") { model.closeSecond(); touched += 1 }
                    }
                    iconButton(fullscreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", action: rotate)
                }
            }
            .padding(.horizontal, 6)
            .background(LinearGradient(colors: [.clear, Color.black.opacity(0.65)], startPoint: .top, endPoint: .bottom))
        }
        .foregroundStyle(.white)
    }

    /// Onde vai e quanto dura: o catch-up, ou o programa a dar. Em direto, só se pode arrastar se houver catch-up.
    private var barState: (position: Int64, duration: Int64, seekable: Bool)? {
        if let catchup = model.catchup { return (model.catchupPosition, catchup.durationMs, true) }
        guard let channel = model.current, let entry = model.nowNext(for: channel).now, entry.endMs > entry.startMs else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return (now - entry.startMs, entry.endMs - entry.startMs, model.canCatchup(channel, entry))
    }

    @ViewBuilder private var progressRow: some View {
        if let bar = barState {
            let fraction = scrub ?? min(max(Double(bar.position) / Double(bar.duration), 0), 1)
            HStack(spacing: 8) {
                Text(durationText(Int64(fraction * Double(bar.duration))))
                PlayerBar(progress: fraction, seekable: bar.seekable) { value in
                    scrub = value
                    touched += 1
                } onEnd: { value in
                    scrub = nil
                    goTo(Int64(value * Double(bar.duration)), livePosition: bar.position)
                }
                Text(durationText(bar.duration))
            }
            .font(.caption2.monospacedDigit())
            .padding(.horizontal, 8)
        }
    }

    /// O botão do direto: em direto só avisa; em catch-up, volta.
    private var liveButton: some View {
        let live = model.catchup == nil
        return Button {
            model.exitCatchup()
        } label: {
            HStack(spacing: 6) {
                Circle().fill(live ? Color.red : Color.white.opacity(0.6)).frame(width: 8, height: 8)
                Text(live ? model.strings.t("LIVE", "DIRETO") : model.strings.t("Go live", "Ir para o direto"))
                    .font(.footnote.weight(.semibold))
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
        guard let channel = model.current, let entry = model.nowNext(for: channel).now else { return "" }
        return "→ " + durationText(liveLanding(target, livePosition: seekBase, entry: entry))
    }

    private var seekStep: Int64 { 30_000 }

    private func seekButton(_ name: String, _ delta: Int64) -> some View {
        Button { seek(delta) } label: { Image(systemName: name).font(.title).frame(width: 52, height: 52) }
    }

    /// Vários toques seguidos somam-se, e só depois se pede a posição nova: cada pedido reabre o stream.
    private func seek(_ delta: Int64) {
        if seekDelta == 0 { seekBase = barState?.position ?? 0 }
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

    private func iconButton(_ name: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: name).font(.title3).frame(width: 44, height: 44) }
    }
}

/// A barra de progresso do leitor, para arrastar.
private struct PlayerBar: View {
    let progress: Double
    let seekable: Bool
    let onChange: (Double) -> Void
    let onEnd: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let fraction = { (x: CGFloat) in Double(min(max(x / max(width, 1), 0), 1)) }
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.28)).frame(height: 4)
                Capsule().fill(Theme.sky).frame(width: width * progress, height: 4)
                if seekable {
                    Circle().fill(Color.white).frame(width: 14, height: 14).offset(x: width * progress - 7)
                }
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

/// Áudio, legendas e temporizador do canal a dar.
private struct PlayerOptionsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let player: PlayerEngine

    var body: some View {
        let s = model.strings
        NavigationStack {
            List {
                Section(s.r("audio")) {
                    let tracks = player.audioTracks
                    if tracks.isEmpty { Text(s.r("no_tracks")).foregroundStyle(Theme.textFaint) }
                    ForEach(tracks, id: \.id) { track in
                        row(track.name, track.id == player.currentAudio) { player.selectAudio(track.id) }
                    }
                }
                Section(s.r("subtitles")) {
                    let tracks = player.subtitleTracks
                    if tracks.isEmpty { Text(s.r("no_tracks")).foregroundStyle(Theme.textFaint) }
                    ForEach(tracks, id: \.id) { track in
                        row(track.id < 0 ? s.r("subtitles_off") : track.name, track.id == player.currentSubtitle) { player.selectSubtitle(track.id) }
                    }
                }
                Section(model.sleepAt.map { s.r("sleep_remaining", "\(Int($0.timeIntervalSinceNow / 60) + 1)") } ?? s.r("sleep_timer")) {
                    row(s.r("subtitles_off"), model.sleepAt == nil) { model.setSleepTimer(nil) }
                    ForEach([15, 30, 45, 60, 90, 120], id: \.self) { minutes in
                        row("\(minutes) min", false) { model.setSleepTimer(minutes) }
                    }
                }
            }
            .navigationTitle(model.current?.name ?? "")
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
                PhoneGuideGrid(channels: model.channels(in: shown), group: shown, epgFor: $epgFor)
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
                        }
                    }
                }
                .overlay { if entries.isEmpty { Text(model.strings.r("no_guide")).foregroundStyle(Theme.textFaint) } }
                .onAppear {
                    if let e = entries.first(where: { $0.endMs > now }) { proxy.scrollTo(e.startMs, anchor: .top) }
                }
            }
            .navigationTitle(channel.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
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
    @State private var downloadedIPA: DownloadedFile?

    struct DownloadedFile: Identifiable {
        let url: URL
        var id: URL { url }
    }

    /// Descarrega a IPA da release e abre a folha de partilha, para a passar à app que a assina e instala.
    /// Sem conseguir, abre a página da release no navegador.
    private func downloadUpdate() {
        guard !downloadingUpdate else { return }
        downloadingUpdate = true
        Task {
            defer { downloadingUpdate = false }
            do {
                let (file, response) = try await URLSession.shared.download(from: AppUpdate.iPhoneIPA)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                let target = FileManager.default.temporaryDirectory.appendingPathComponent("GoatTV-iPhone.ipa")
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: file, to: target)
                downloadedIPA = DownloadedFile(url: target)
            } catch {
                await UIApplication.shared.open(AppUpdate.releasePage)
            }
        }
    }
    @State private var managingGroups = false
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
        .sheet(isPresented: $pickingLogoSource) { PhoneEpgCountries(logo: true) { pickingLogoSource = false } }
        .sheet(isPresented: $managingGroups) {
            NavigationStack {
                List(model.allGroups, id: \.id) { g in
                    Toggle(g.name, isOn: Binding(get: { !model.isGroupHidden(g.id) }, set: { model.setGroupHidden(g.id, !$0) }))
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
            .padding(.horizontal, 12)
        }
        let others = model.playlists.filter { $0.id != model.content?.playlist.id }
        if !others.isEmpty || model.isLocal {
            section(s.r("playlists")) {
                ForEach(others, id: \.id) { pl in
                    Button { model.switchPlaylist(pl) } label: { MenuRowLabel(title: pl.name, icon: "list.bullet") }.buttonStyle(.plain)
                }
                if model.isLocal {
                    Button { adding = true } label: { MenuRowLabel(title: s.r("add_playlist"), icon: "plus", chevron: true) }.buttonStyle(.plain)
                }
            }
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
            if model.hasAccount {
                MenuRow(s.r("logout"), value: model.email.map(phoneMaskEmail)) {
                    if model.signOutNeedsPin { askingSignOut = true } else { model.signOut() }
                }
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
            }
            section(s.t("Connection", "Ligação")) {
                pick(s.r("buffer"), "buffer", p.buffer, bufferOptions)
                pick(s.t("Reconnect if the stream fails", "Reconectar se a stream falhar"), "reconnect_seconds", .number(Double(p.reconnectSeconds)), [(.number(0), s.t("No", "Não"))] + [2, 5, 10, 30].map { (.number(Double($0)), s.t("After %@ s", "Ao fim de %@ s", "\($0)")) })
            }
            section(s.t("Advanced", "Avançado")) {
                pick(s.t("Stream format", "Formato do stream"), "stream_format", p.streamFormat.map { .string($0) }, formatOptions)
                pick(s.t("Player", "Player"), "engine", p.engine, [("exo", s.t("Default (Apple player for HLS, VLC for the rest)", "Normal (o da Apple nos HLS, o VLC no resto)")), ("vlc", s.t("VLC (for streams that don't open)", "VLC (para streams que não abram)"))])
                pick(s.t("Video decoder (VLC)", "Descodificador de vídeo (VLC)"), "decoder", p.decoder, [("hw", "Hardware"), ("sw", s.t("Software (compatibility)", "Software (compatibilidade)"))])
                pick(s.t("Deinterlace (VLC)", "Desentrelaçar (VLC)"), "deinterlace", p.deinterlace, [("yadif2x", s.t("Smooth (50/60 frames)", "Suave (50/60 imagens)")), ("yadif", "Normal"), ("off", s.t("Off", "Desligado"))])
                MenuRow(s.t("User agent", "User agent"), value: p.userAgent.isEmpty ? s.t("Default", "Normal") : p.userAgent, chevron: true) {
                    agentText = p.userAgent
                    editingAgent = true
                }
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
                    MenuRow(s.t("Remove PIN", "Tirar o PIN")) { model.setPin(nil) }
                    ForEach(model.allGroups, id: \.id) { g in
                        MenuRow(g.name, value: model.isGroupLocked(g.id) ? "🔒" : nil) { model.toggleGroupLock(g.id) }
                    }
                }
            }
        }
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
    let onDone: () -> Void
    @State private var form = PlaylistForm()
    @State private var error: String?

    var body: some View {
        let s = model.strings
        NavigationStack {
            Form {
                Picker("", selection: Binding(get: { form.type }, set: { form.setType($0, deviceMac: model.deviceMac) })) {
                    Text("Xtream").tag(PlaylistType.xtream)
                    Text("M3U").tag(PlaylistType.m3u)
                    if !model.hasAccount { Text("Stalker").tag(PlaylistType.stalker) }
                }
                .pickerStyle(.segmented)
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
            .navigationTitle(s.r("add_playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(s.r("save")) {
                        Task {
                            error = await model.savePlaylist(id: nil, form: form)
                            if error == nil { onDone() }
                        }
                    }
                }
            }
        }
    }
}
