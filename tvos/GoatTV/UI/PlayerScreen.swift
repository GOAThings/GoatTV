import GoatCore
import SwiftUI
import UIKit

/// O ecrã principal, com as teclas do GoatTV Android: ← lista de canais, → canal anterior, ↑/↓ mudam de canal,
/// OK abre a barra com os botões (áudio, legendas, imagem, favorito, definições).
struct PlayerScreen: View {
    @Environment(AppModel.self) private var model

    enum Panel: Equatable {
        case none
        case channels
        case groups
        /// Pedir o PIN para abrir um grupo bloqueado.
        case pin(String)
        case search
        case sleep
        case second
        case settings(SettingsPanel.Page)
    }

    enum Focus: Hashable {
        case surface
        case channel(Int)
        case group(String)
        case gear
        case swap
        case playlist(String)
        case hud(String)
    }

    @State private var panel: Panel = .none
    @State private var hud = false
    @State private var zapCard = false
    @State private var zapToken = 0
    @State private var browseGroup = SpecialCategory.all
    @State private var showGuide = false
    @State private var playlistMenu = false
    @State private var activity = 0
    /// A escrever num campo das Definições: o painel não fecha sozinho.
    @State private var typing = false
    @State private var now = nowMs()
    /// O guia de TV em grelha (o vídeo encolhe para o canto).
    @State private var tvGuide = false
    /// O guia foi aberto pelo Voltar: fechá-lo conta como o 1.º Voltar para sair.
    @State private var guideFromBack = false
    /// Durante 2,5 s depois de um Voltar, o seguinte sai da app (o sistema trata-o).
    @State private var exitArmed = false
    @State private var exitToken = 0
    // Catch-up
    @State private var catchupPosition: Int64 = 0
    @State private var pendingSeek: Int64?
    @State private var seekToken = 0
    @State private var catchupBar = false
    @State private var catchupBarToken = 0
    // Lembretes e temporizador
    @State private var reminder: AppModel.Reminder?
    @State private var sleepWarning: Int?
    // Segundo canal
    @State private var split = false
    @State private var swapped = false
    @State private var focusSecond = false
    @FocusState private var focus: Focus?

    /// Onde fica um vídeo: tamanho (nil = ecrã inteiro), canto e margens.
    private struct VideoBox {
        var size: CGSize?
        var alignment: Alignment = .center
        var top: CGFloat = 0
        var leading: CGFloat = 0
        var trailing: CGFloat = 0
        var z: Double = 0

        static let full = VideoBox()
        static let pip = VideoBox(size: CGSize(width: 704, height: 396), alignment: .topTrailing, top: 48, trailing: 64, z: 0.5)
        static let left = VideoBox(size: CGSize(width: 960, height: 540), alignment: .leading)
        static let right = VideoBox(size: CGSize(width: 960, height: 540), alignment: .trailing)
        static let guide = VideoBox(size: GuideLayout.previewSize, alignment: .topTrailing, top: GuideLayout.marginTop, trailing: GuideLayout.marginSide, z: 2)
    }

    private var mainBox: VideoBox {
        if tvGuide { return .guide }
        guard model.second != nil else { return .full }
        if split { return swapped ? .right : .left }
        return swapped ? .pip : .full
    }

    private var secondBox: VideoBox {
        if split { return swapped ? .left : .right }
        return swapped ? .full : .pip
    }

    private func videoView(_ view: UIView, box: VideoBox, name: String?, audible: Bool) -> some View {
        let small = box.size != nil
        return VideoSurface(view: view)
            .frame(width: box.size?.width, height: box.size?.height)
            .clipShape(RoundedRectangle(cornerRadius: small ? 18 : 0, style: .continuous))
            .overlay {
                if small, !tvGuide {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(audible ? AnyShapeStyle(Theme.accent(model.prefs.accent)) : AnyShapeStyle(Color.white.opacity(0.35)), lineWidth: audible ? 5 : 2)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if let name, small, !tvGuide {
                    HStack(spacing: 10) {
                        if audible { Image(systemName: "speaker.wave.2.fill") }
                        Text(name).lineLimit(1)
                    }
                    .font(.system(size: 22, weight: .semibold))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(Color.black.opacity(0.6)))
                    .padding(16)
                }
            }
            .padding(.top, box.top)
            .padding(.leading, box.leading)
            .padding(.trailing, box.trailing)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: box.alignment)
            .ignoresSafeArea()
            .zIndex(box.z)
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            // O vídeo: em ecrã inteiro, pequeno no canto com o guia aberto, ou com o segundo canal ao lado.
            videoView(model.player.view, box: mainBox, name: model.current?.name, audible: model.second == nil || !focusSecond)

            if let second = model.second, !tvGuide {
                videoView(model.secondPlayer.view, box: secondBox, name: second.channel.name, audible: focusSecond)
            }

            surface

            playerStatus

            if tvGuide, let playing = model.current {
                GuideView(
                    playing: playing,
                    startGroup: model.groupId,
                    onPlay: { channel, group in
                        model.play(channel, from: group)
                        closeGuide(fromBack: false)
                    },
                    onCatchup: { channel, entry in
                        model.playCatchup(channel, entry)
                        closeGuide(fromBack: false)
                        showCatchupBar()
                    },
                    onClose: { closeGuide(fromBack: guideFromBack) }
                )
                .zIndex(1)
                .transition(.opacity)
            }

            if let cu = model.catchup, !tvGuide {
                VStack {
                    HStack {
                        Spacer()
                        CatchupPill(paused: model.player.paused)
                    }
                    Spacer()
                    if catchupBar || model.player.paused || pendingSeek != nil {
                        CatchupBar(catchup: cu, positionMs: catchupPosition, pendingSeekMs: pendingSeek, paused: model.player.paused)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .padding(.horizontal, 64)
                .padding(.vertical, 48)
                .ignoresSafeArea()
                .allowsHitTesting(false)
            }

            if zapCard, !hud, panel == .none, let channel = model.current {
                VStack {
                    Spacer()
                    ZapCard(channel: channel, now: now)
                }
                .padding(.leading, 64)
                .padding(.bottom, 64)
                .ignoresSafeArea()
                .transition(.move(edge: .leading).combined(with: .opacity))
            }

            if hud, panel == .none, model.catchup == nil, let channel = model.current {
                VStack {
                    Spacer()
                    hudView(channel)
                }
                .padding(.horizontal, 64)
                .padding(.bottom, 48)
                .ignoresSafeArea()
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if panel == .channels || panel == .groups || isPin {
                HStack(alignment: .top, spacing: 0) {
                    sidePanel
                    if panel == .channels, showGuide, let channel = focusedChannel {
                        ChannelGuidePanel(channel: channel, now: now)
                            .transition(.opacity)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.leading, 40)
                .padding(.vertical, 40)
                .ignoresSafeArea()
                .transition(.move(edge: .leading).combined(with: .opacity))
            }

            if panel == .search {
                HStack {
                    SearchPanel(onPick: pickSearch)
                        .onExitCommand(perform: closePanel)
                    Spacer(minLength: 0)
                }
                .padding(.leading, 40)
                .padding(.vertical, 40)
                .ignoresSafeArea()
                .transition(.move(edge: .leading).combined(with: .opacity))
            }

            if panel == .second {
                HStack {
                    SecondChannelPicker(onPick: { c, ch, g in
                        model.openSecond(c, ch, groupId: g)
                        focusSecond = false
                        closePanel()
                    }, onClose: closePanel)
                    Spacer(minLength: 0)
                }
                .padding(.leading, 40)
                .padding(.vertical, 40)
                .ignoresSafeArea()
                .transition(.move(edge: .leading).combined(with: .opacity))
            }

            if panel == .sleep {
                HStack {
                    Spacer(minLength: 0)
                    SleepPicker(onPick: { minutes in
                        model.setSleepTimer(minutes)
                        closePanel()
                    }, onClose: closePanel)
                }
                .padding(.trailing, 40)
                .padding(.vertical, 40)
                .ignoresSafeArea()
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }

            if let reminder, panel == .none, !tvGuide {
                VStack {
                    Spacer()
                    HStack {
                        ReminderCard(reminder: reminder, onWatch: {
                            model.consumeReminder(reminder, watch: true)
                            self.reminder = nil
                            focusLater(.surface)
                        }, onDismiss: {
                            model.consumeReminder(reminder, watch: false)
                            self.reminder = nil
                            focusLater(.surface)
                        })
                        Spacer()
                    }
                }
                .padding(64)
                .ignoresSafeArea()
                .transition(.move(edge: .leading).combined(with: .opacity))
            }

            if let sleepWarning {
                SleepWarning(seconds: sleepWarning)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .allowsHitTesting(false)
            }

            if case .settings(let page) = panel {
                HStack {
                    Spacer(minLength: 0)
                    SettingsPanel(start: page, onClose: closePanel)
                        .id(page)
                }
                .padding(.trailing, 40)
                .padding(.vertical, 40)
                .ignoresSafeArea()
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.18), value: panel)
        .animation(.easeOut(duration: 0.18), value: hud)
        .animation(.easeOut(duration: 0.18), value: zapCard)
        .animation(.easeOut(duration: 0.25), value: tvGuide)
        .animation(.easeOut(duration: 0.25), value: split)
        .animation(.easeOut(duration: 0.25), value: swapped)
        // Só tem som o canal com foco.
        .onChange(of: focusSecond) { _, _ in applyAudio() }
        .onChange(of: model.second?.channel.key) { _, _ in applyAudio() }
        .animation(.easeOut(duration: 0.18), value: reminder)
        .defaultFocus($focus, .surface)
        .onAppear {
            focus = .surface
            if model.prefs.startup == Prefs.Startup.list { openChannels() }
        }
        .onChange(of: model.zapCount) { _, _ in
            guard panel == .none, !hud, model.prefs.infoSeconds > 0 else { return }
            zapCard = true
            zapToken += 1
        }
        .onChange(of: focus) { _, _ in activity += 1 }
        .environment(\.panelActivity) { now in
            typing = now
            activity += 1
        }
        // Painéis e barra fecham sozinhos ao fim de uns segundos sem mexer (Definições → Aspeto).
        .task(id: activity) {
            // A barra com os botões fecha ao fim de 8 s; os painéis no tempo das definições.
            guard hud || (panel != .none && model.prefs.panelTimeout > 0) else { return }
            if typing, case .settings = panel { return }
            try? await Task.sleep(for: .seconds(hud && panel == .none ? 8 : model.prefs.panelTimeout))
            guard !Task.isCancelled else { return }
            if hud { closeHud() } else { closePanel() }
        }
        .task(id: zapToken) {
            guard zapCard else { return }
            try? await Task.sleep(for: .seconds(max(model.prefs.infoSeconds, 1)))
            if !Task.isCancelled { zapCard = false }
        }
        .task {
            while !Task.isCancelled {
                now = nowMs()
                // Lembretes: um programa marcado vai começar.
                if reminder == nil, let due = model.dueReminder(now) { reminder = due }
                try? await Task.sleep(for: .seconds(15))
            }
        }
        // A posição do catch-up, de segundo a segundo.
        .task(id: model.catchup) {
            while model.catchup != nil, !Task.isCancelled {
                catchupPosition = model.catchupPosition
                try? await Task.sleep(for: .seconds(1))
            }
        }
        .task(id: catchupBarToken) {
            guard catchupBar else { return }
            try? await Task.sleep(for: .seconds(5))
            if !Task.isCancelled { catchupBar = false }
        }
        // ← → no catch-up: junta os saltos e só pede ao servidor quando se pára de carregar.
        .task(id: seekToken) {
            guard let target = pendingSeek else { return }
            try? await Task.sleep(for: .milliseconds(900))
            guard !Task.isCancelled else { return }
            pendingSeek = nil
            model.seekCatchup(target)
            showCatchupBar()
        }
        // Temporizador: aviso no último minuto; ao acabar, para e sai para o ecrã inicial.
        .task(id: model.sleepAt) {
            sleepWarning = nil
            while let at = model.sleepAt, !Task.isCancelled {
                let left = at.timeIntervalSinceNow
                if left <= 0 {
                    sleepWarning = nil
                    model.sleepNow()
                    return
                }
                sleepWarning = left <= 60 ? Int(left.rounded(.up)) : nil
                try? await Task.sleep(for: .seconds(left <= 61 ? 1 : min(left - 60, 30)))
            }
        }
        .task(id: exitToken) {
            guard exitArmed else { return }
            try? await Task.sleep(for: .seconds(2.5))
            if !Task.isCancelled { exitArmed = false }
        }
    }

    // MARK: Vídeo e teclas

    /// Um botão invisível do tamanho do ecrã: é ele que tem o foco quando não há nada aberto.
    private var surface: some View {
        Button(action: select) {
            Color.black.opacity(0.001).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(InvisibleButtonStyle())
        .focused($focus, equals: .surface)
        .disabled(panel != .none || hud || tvGuide || reminder != nil)
        .onMoveCommand(perform: move)
        .onPlayPauseCommand(perform: playPause)
        .onExitCommand(perform: backAction)
        .contextMenu {
            if let channel = model.current {
                Button(model.isFavorite(channel) ? model.strings.removeFavorite : model.strings.addFavorite) {
                    model.toggleFavorite(channel)
                }
            }
        }
    }

    /// OK no vídeo: no catch-up pausa; senão abre a barra com os botões.
    private func select() {
        if sleepWarning != nil {
            model.setSleepTimer(nil)
            return
        }
        if model.player.state == .failed {
            model.retryPlayback()
            return
        }
        if model.catchup != nil {
            model.player.togglePause()
            showCatchupBar()
        } else {
            openHud()
        }
    }

    private func playPause() {
        if model.catchup != nil {
            model.player.togglePause()
            showCatchupBar()
        } else {
            openHud()
        }
    }

    /// O Voltar com tudo fechado (como no Android): sai do catch-up; abre o guia; ou, à segunda, sai da app.
    /// nil deixa o Voltar para o sistema, que sai para o ecrã inicial.
    private var backAction: (() -> Void)? {
        if model.catchup != nil { return { model.exitCatchup() } }
        if model.second != nil {
            return {
                model.closeSecond()
                focusSecond = false
                split = false
                swapped = false
            }
        }
        if exitArmed { return nil }
        if model.prefs.backAction == Prefs.BackAction.guide {
            return {
                guideFromBack = true
                openGuide()
            }
        }
        if model.prefs.exitConfirm { return { armExit() } }
        return nil
    }

    private func applyAudio() {
        let dual = model.second != nil
        model.player.setMuted(dual && focusSecond)
        model.secondPlayer.setMuted(!focusSecond)
        if !dual { focusSecond = false }
    }

    private func armExit() {
        exitArmed = true
        exitToken += 1
        model.showToast(model.strings.t("Press Back again to exit", "Carrega outra vez em Voltar para sair"))
    }

    private func openGuide() {
        hud = false
        zapCard = false
        panel = .none
        tvGuide = true
    }

    private func closeGuide(fromBack: Bool) {
        tvGuide = false
        guideFromBack = false
        // Fechar o guia que o Voltar abriu conta como o 1.º Voltar para sair.
        if fromBack {
            if model.prefs.exitConfirm { armExit() } else { exitArmed = true; exitToken += 1 }
        }
        focusLater(.surface)
    }

    private func showCatchupBar() {
        catchupBar = true
        catchupBarToken += 1
    }

    private func pickSearch(_ hit: AppModel.SearchHit) {
        if let p = hit.programme, p.startMs > nowMs() {
            model.toggleReminder(hit.channel, p)
        } else {
            model.play(hit.channel, from: model.groupFor(hit.channel))
            closePanel()
        }
    }

    private func move(_ direction: MoveCommandDirection) {
        if sleepWarning != nil {
            model.setSleepTimer(nil)
            return
        }
        // No catch-up, ← → andam um minuto para trás e para a frente.
        if let cu = model.catchup, direction == .left || direction == .right {
            let from = pendingSeek ?? catchupPosition
            let step: Int64 = direction == .left ? -60_000 : 60_000
            pendingSeek = min(max(0, from + step), cu.durationMs)
            seekToken += 1
            showCatchupBar()
            return
        }
        // Dois canais: ← → passam o foco (e o som) de um para o outro; ↑ ↓ mudam o canal com foco.
        if model.second != nil {
            switch direction {
            case .left, .right: focusSecond.toggle()
            case .up: if focusSecond { model.zapSecond(-1) } else { model.zap(-1) }
            case .down: if focusSecond { model.zapSecond(1) } else { model.zap(1) }
            @unknown default: break
            }
            return
        }
        switch direction {
        case .left:
            openChannels()
        case .right:
            model.playPrevious()
        case .up:
            if model.prefs.upDown == Prefs.UpDown.list { openChannels() } else { model.zap(-1) }
        case .down:
            if model.prefs.upDown == Prefs.UpDown.list { openChannels() } else { model.zap(1) }
        @unknown default:
            break
        }
    }

    /// "A sintonizar…" / "A voltar a ligar…" no canto, ou o erro explicado ao centro (como no Android).
    @ViewBuilder
    private var playerStatus: some View {
        let s = model.strings
        if let error = model.playbackError, model.player.state == .failed, !tvGuide {
            ErrorPanel(info: error, status: model.playbackStatus) {
                HStack(spacing: 40) {
                    KeyHint(key: "OK", label: s.r("retry"))
                    KeyHint(key: "↑ ↓", label: s.r("change_channel"))
                    KeyHint(key: "←", label: s.r("channel_list_hint"))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .allowsHitTesting(false)
        } else if model.player.state == .opening, !tvGuide {
            VStack {
                HStack {
                    Spacer()
                    TuningPill(text: model.player.wasPlaying ? s.r("reconnecting") : s.r("tuning"))
                }
                Spacer()
            }
            .padding(.top, 48)
            .padding(.trailing, 64)
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
    }

    // MARK: Barra com botões (OK)

    private func openHud() {
        zapCard = false
        hud = true
        activity += 1
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(60))
            focus = .hud("audio")
        }
    }

    private func closeHud() {
        hud = false
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(60))
            if panel == .none { focus = .surface }
        }
    }

    private func hudView(_ channel: Channel) -> some View {
        let s = model.strings
        let favorite = model.isFavorite(channel)
        let aspectLabel: String
        switch model.prefs.aspect {
        case Prefs.Aspect.zoom: aspectLabel = s.t("Zoom", "Zoom")
        case Prefs.Aspect.fill: aspectLabel = s.t("Stretch", "Esticar")
        default: aspectLabel = s.t("Fit", "Ajustar")
        }
        return Hud(channel: channel, now: now) {
            // Os botões passam para os lados quando não cabem (o foco faz deslizar).
            ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                chip("audio", "speaker.wave.2.fill", s.t("Audio", "Áudio")) { openSettings(.audio) }
                chip("subtitles", "captions.bubble.fill", s.t("Subtitles", "Legendas")) { openSettings(.subtitles) }
                chip("aspect", "aspectratio", aspectLabel) {
                    let next: String
                    switch model.prefs.aspect {
                    case Prefs.Aspect.fit: next = Prefs.Aspect.zoom
                    case Prefs.Aspect.zoom: next = Prefs.Aspect.fill
                    default: next = Prefs.Aspect.fit
                    }
                    model.setPref("aspect", .string(next))
                }
                chip("search", "magnifyingglass", s.t("Search", "Pesquisar")) {
                    hud = false
                    panel = .search
                }
                chip("tvguide", "calendar", s.t("TV guide", "Guia TV")) { openGuide() }
                // Trocar o guia deste canal por outro (só com conta; sem conta explica porquê).
                chip("epgchannel", "list.bullet.rectangle.portrait", s.r("epg_guide_channel")) {
                    if model.hasAccount {
                        openSettings(.epgPick(channel.key, false, channel.name))
                    } else {
                        model.showToast(s.r("epg_needs_account"))
                    }
                }
                chip("favorite", favorite ? "star.fill" : "star", favorite ? s.removeFavorite : s.addFavorite) {
                    model.toggleFavorite(channel)
                }
                if let live = Guide.nowNext(model.programmes(for: channel), now: now).now, model.canCatchup(channel, live) {
                    chip("startover", "backward.end.fill", s.t("Start over", "Ver do início")) {
                        hud = false
                        model.playCatchup(channel, live)
                        showCatchupBar()
                        focusLater(.surface)
                    }
                }
                if model.second == nil {
                    chip("second", "rectangle.inset.topright.filled", s.t("Second channel", "Segundo canal")) {
                        hud = false
                        panel = .second
                    }
                } else {
                    chip("swapdual", "arrow.left.arrow.right", s.t("Swap channels", "Trocar canais")) { swapped.toggle() }
                    chip("layout", split ? "rectangle.inset.topright.filled" : "rectangle.split.2x1",
                         split ? s.t("Picture in picture", "Imagem na imagem") : s.t("Split screen", "Ecrã dividido")) { split.toggle() }
                    chip("changesecond", "rectangle.2.swap", s.t("Change second", "Mudar o segundo")) {
                        hud = false
                        panel = .second
                    }
                    chip("closesecond", "xmark", s.t("Close second", "Fechar o segundo")) {
                        model.closeSecond()
                        focusSecond = false
                        split = false
                        swapped = false
                    }
                }
                chip("sleep", "moon.zzz.fill", model.sleepAt.map { s.t("Off in %@ min", "Desliga em %@ min", "\(Int($0.timeIntervalSinceNow / 60) + 1)") } ?? s.t("Sleep timer", "Temporizador")) {
                    hud = false
                    panel = .sleep
                }
                chip("settings", "gearshape.fill", s.t("Settings", "Definições")) { openSettings(.root) }
            }
            .padding(.vertical, 14)
            .padding(.horizontal, 10)
            }
        }
        .focusSection()
        .onExitCommand(perform: closeHud)
    }

    private func chip(_ id: String, _ icon: String, _ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: {
            activity += 1
            action()
        }) {
            Label(title, systemImage: icon)
        }
        .buttonStyle(ChipButtonStyle())
        .focused($focus, equals: .hud(id))
    }

    // MARK: Painéis

    private func openChannels(guide: Bool = false) {
        browseGroup = model.groupId
        showGuide = guide
        zapCard = false
        panel = .channels
        activity += 1
    }

    private func openSettings(_ page: SettingsPanel.Page) {
        hud = false
        zapCard = false
        panel = .settings(page)
        activity += 1
    }

    private func closePanel() {
        panel = .none
        typing = false
        showGuide = false
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(60))
            focus = .surface
        }
    }

    private var isPin: Bool {
        if case .pin = panel { return true }
        return false
    }

    private var focusedChannel: Channel? {
        if case .channel(let uid)? = focus { return model.content?.channels.first { $0.uid == uid } }
        return model.current
    }

    private var outline: AnyShapeStyle { Theme.outline(model.prefs.outlineColor, accent: model.prefs.accent) }

    private var sidePanel: some View {
        VStack(alignment: .leading, spacing: 18) {
            if panel == .channels {
                channelHeader
                channelList
            } else if case .pin(let groupId) = panel {
                PinPad(title: model.strings.t("Enter the PIN", "Escreve o PIN"),
                       subtitle: model.groups.first { $0.id == groupId }?.name) { pin in
                    guard model.unlockGroup(groupId, pin: pin) else { return false }
                    showChannels(of: groupId)
                    return true
                }
                .padding(.top, 40)
            } else {
                groupHeader
                groupList
            }
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: model.panelPoints(model.prefs.panelWidth))
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .focusSection()
        .onExitCommand {
            if playlistMenu {
                playlistMenu = false
                focusLater(.swap)
            } else if isPin {
                showGroups()
            } else if panel == .groups {
                showChannels(of: browseGroup)
            } else if showGuide {
                showGuide = false
            } else {
                closePanel()
            }
        }
    }

    private var groupName: String {
        model.groups.first { $0.id == browseGroup }?.name ?? model.strings.all
    }

    private var channelHeader: some View {
        let count = model.channels(in: browseGroup).count
        return HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(groupName).font(Fonts.panelTitle).lineLimit(1)
                Text([model.strings.channelCount(count), model.content?.playlist.name].compactMap { $0 }.joined(separator: " · "))
                    .font(Fonts.panelSubtitle)
                    .foregroundStyle(Theme.textDim)
                    .lineLimit(1)
            }
            Spacer()
            Text("‹ " + model.strings.groups).font(Fonts.rowSubtitle).foregroundStyle(Theme.textFaint)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private var channelList: some View {
        let channels = model.channels(in: browseGroup)
        let guideVersion = model.guideVersion
        return ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(spacing: 6) {
                    if channels.isEmpty {
                        Text(browseGroup == SpecialCategory.favorites
                             ? model.strings.t("No favourites yet.\nHold OK on a channel to add it.", "Ainda não tens favoritos.\nMantém OK num canal para o juntar.")
                             : model.strings.noChannels)
                            .font(Fonts.rowSubtitle)
                            .foregroundStyle(Theme.textDim)
                            .multilineTextAlignment(.leading)
                            .padding(30)
                    }
                    ForEach(channels) { channel in
                        Button {
                            model.play(channel, from: browseGroup)
                            closePanel()
                        } label: {
                            ChannelRow(channel: channel, now: now, guideVersion: guideVersion)
                        }
                        .buttonStyle(RowButtonStyle(outline: outline))
                        .focused($focus, equals: .channel(channel.uid))
                        .accessibilityIdentifier("channel:\(channel.key)")
                        .contextMenu {
                            Button(model.isFavorite(channel) ? model.strings.removeFavorite : model.strings.addFavorite) {
                                model.toggleFavorite(channel)
                            }
                        }
                        .id(channel.uid)
                    }
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 6)
            }
            .onMoveCommand { direction in
                if direction == .left { showGroups() }
                if direction == .right { showGuide = true }
            }
            .task(id: browseGroup) {
                // Abre no canal a dar (se estiver neste grupo), senão no primeiro.
                let target = channels.first { $0.key == model.current?.key } ?? channels.first
                guard let target else { return }
                proxy.scrollTo(target.uid, anchor: .center)
                try? await Task.sleep(for: .milliseconds(80))
                focus = .channel(target.uid)
            }
        }
    }

    private var groupHeader: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.strings.groups).font(Fonts.panelTitle).lineLimit(1)
                if let name = model.content?.playlist.name {
                    Text(name).font(Fonts.panelSubtitle).foregroundStyle(Theme.textDim).lineLimit(1)
                }
            }
            Spacer()
            // ⇄ troca de lista (só com mais de uma), ⚙ abre as Definições.
            if model.playlists.count > 1 {
                Button {
                    playlistMenu = true
                    if let id = model.content?.playlist.id { focusLater(.playlist(id)) }
                } label: {
                    Image(systemName: "arrow.left.arrow.right").font(.system(size: 26, weight: .semibold))
                }
                .buttonStyle(IconButtonStyle())
                .focused($focus, equals: .swap)
                .disabled(playlistMenu)
            }
            Button {
                panel = .settings(.root)
            } label: {
                Image(systemName: "gearshape.fill").font(.system(size: 28))
            }
            .buttonStyle(IconButtonStyle())
            .focused($focus, equals: .gear)
            .accessibilityIdentifier("gear")
            .disabled(playlistMenu)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    /// O menu das listas por baixo do ⇄ (como no Android).
    private var playlistMenuView: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(model.playlists) { playlist in
                Button {
                    playlistMenu = false
                    model.switchPlaylist(playlist)
                    showChannels(of: SpecialCategory.all)
                } label: {
                    RowContent(title: playlist.name, subtitle: playlist.type == .xtream ? "Xtream" : "M3U") {
                        if playlist.id == model.content?.playlist.id { Text("✓").font(Fonts.rowTitle).foregroundStyle(Theme.sky) }
                    }
                }
                .buttonStyle(FillRowStyle(selected: playlist.id == model.content?.playlist.id))
                .focused($focus, equals: .playlist(playlist.id))
            }
        }
        .padding(10)
        .frame(width: 520)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color(red: 0.04, green: 0.05, blue: 0.09).opacity(0.94)))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
        .focusSection()
    }

    private func focusLater(_ target: Focus) {
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(60))
            focus = target
        }
    }

    private var groupList: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 6) {
                    ForEach(model.groups) { group in
                        let locked = model.isGroupLockable(group.id)
                        Button {
                            if model.isGroupLocked(group.id) {
                                panel = .pin(group.id)
                                activity += 1
                            } else {
                                showChannels(of: group.id)
                            }
                        } label: {
                            RowContent(title: group.name) {
                                if locked { Text(model.isGroupLocked(group.id) ? "🔒" : "🔓").font(Fonts.rowValue) }
                                Text("\(model.channels(in: group.id).count)").font(Fonts.rowValue).opacity(0.75)
                            }
                        }
                        .buttonStyle(FillRowStyle(selected: group.id == browseGroup))
                        .focused($focus, equals: .group(group.id))
                        .accessibilityIdentifier("group:\(group.id)")
                        .disabled(playlistMenu)
                        .id(group.id)
                    }
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 6)
            }
            .overlay(alignment: .topTrailing) {
                if playlistMenu { playlistMenuView }
            }
            .onMoveCommand { direction in
                if direction == .right, !playlistMenu, case .group? = focus { showChannels(of: browseGroup) }
            }
            .task {
                proxy.scrollTo(browseGroup, anchor: .center)
                try? await Task.sleep(for: .milliseconds(80))
                focus = .group(browseGroup)
            }
        }
    }

    private func showGroups() {
        showGuide = false
        panel = .groups
        activity += 1
    }

    private func showChannels(of group: String) {
        browseGroup = group
        panel = .channels
        activity += 1
    }
}

/// O cartão pequeno ao mudar de canal (em baixo à esquerda): logótipo, canal, programa e progresso.
struct ZapCard: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let now: Int64

    var body: some View {
        let _ = model.guideVersion
        let current = Guide.nowNext(model.programmes(for: channel), now: now).now
        HStack(spacing: 24) {
            if model.prefs.showLogos {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 104, height: 64))
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    if model.prefs.showNumbers {
                        Text("\(channel.num)").font(.callout.monospacedDigit()).foregroundStyle(Theme.textFaint)
                    }
                    Text(channel.name).font(.callout.bold()).foregroundStyle(Theme.textDim).lineLimit(1)
                }
                Text(current?.title ?? model.strings.noGuideInfo).font(.headline).lineLimit(1)
                if let current {
                    HStack(spacing: 14) {
                        Text(hhmm(current.start)).font(.caption2.monospacedDigit()).foregroundStyle(Theme.textFaint)
                        ProgressLine(value: current.progress(now: now), height: 4)
                        Text(hhmm(current.end)).font(.caption2.monospacedDigit()).foregroundStyle(Theme.textFaint)
                    }
                }
            }
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 22)
        .frame(width: 820, alignment: .leading)
        .glass(radius: 26)
    }
}

/// A barra de baixo quando se carrega em OK: canal com os dados da stream, agora e a seguir, e os botões.
struct Hud<Buttons: View>: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let now: Int64
    let buttons: () -> Buttons

    init(channel: Channel, now: Int64, @ViewBuilder buttons: @escaping () -> Buttons) {
        self.channel = channel
        self.now = now
        self.buttons = buttons
    }

    var body: some View {
        let _ = model.guideVersion
        let pair = Guide.nowNext(model.programmes(for: channel), now: now)
        let badges = [model.player.resolution, model.player.format.map { $0 == StreamFormat.hls ? "HLS" : "TS" }].compactMap { $0 }
        VStack(alignment: .leading, spacing: 24) {
            HStack(alignment: .top, spacing: 48) {
                HStack(spacing: 24) {
                    if model.prefs.showLogos {
                        ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 120, height: 74))
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        Text(model.isFavorite(channel) ? "\(channel.name)  ★" : channel.name)
                            .font(.title3.bold())
                            .lineLimit(1)
                        HStack(spacing: 10) {
                            ForEach(badges, id: \.self) { badge in
                                Text(badge)
                                    .font(.caption2.bold())
                                    .foregroundStyle(Theme.textDim)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 3)
                                    .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Color.white.opacity(0.25), lineWidth: 1))
                            }
                        }
                    }
                }
                .frame(width: 560, alignment: .leading)

                VStack(alignment: .leading, spacing: 10) {
                    if let current = pair.now {
                        HStack(spacing: 12) {
                            Text(model.strings.now.uppercased()).font(.caption2.bold()).foregroundStyle(Theme.sky)
                            Text(current.title).font(.headline).lineLimit(1)
                        }
                        HStack(spacing: 14) {
                            Text(hhmm(current.start)).font(.caption.monospacedDigit()).foregroundStyle(Theme.textFaint)
                            ProgressLine(value: current.progress(now: now), height: 5)
                            Text(hhmm(current.end)).font(.caption.monospacedDigit()).foregroundStyle(Theme.textFaint)
                        }
                    } else {
                        Text(model.strings.noGuideInfo).font(.headline).foregroundStyle(Theme.textDim)
                    }
                    if let next = pair.next {
                        HStack(spacing: 12) {
                            Text(model.strings.next.uppercased()).font(.caption2.bold()).foregroundStyle(Theme.textFaint)
                            Text("\(hhmm(next.start))  \(next.title)").font(.callout).foregroundStyle(Theme.textDim).lineLimit(1)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            buttons()
        }
        .padding(.horizontal, 36)
        .padding(.vertical, 28)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glass(radius: 34)
    }
}

/// Botão da barra (ícone e texto numa cápsula), como os do Android.
struct ChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Chip(configuration: configuration)
    }

    private struct Chip: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: 25, weight: .semibold))
                .foregroundStyle(focused ? Color.black : Theme.text)
                .padding(.horizontal, 26)
                .padding(.vertical, 14)
                .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.1)))
                .scaleEffect(focused ? 1.05 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}

/// Botão só com ícone (a roda dentada dos Grupos).
struct IconButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        IconButton(configuration: configuration)
    }

    private struct IconButton: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Color.black : Theme.textDim)
                .frame(width: 64, height: 64)
                .background(Circle().fill(focused ? Color.white : Color.white.opacity(0.08)))
                .scaleEffect(focused ? 1.08 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}
