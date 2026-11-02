import GoatCore
import SwiftUI
import UIKit

private let cardFill = Color.white.opacity(0.08)
private let cardShape = RoundedRectangle(cornerRadius: 18, style: .continuous)

struct PhoneRoot: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            switch model.screen {
            case .starting, .loading:
                GoatLogoView(intro: false)
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
                choice(s.r("welcome_account"), s.r("welcome_account_sub"), model.connectAccount)
                choice(s.t("Playlists on this phone", "Listas neste telemóvel"), s.t("No account: add Xtream or M3U playlists here. Everything stays on this phone only.", "Sem conta: adiciona listas Xtream ou M3U aqui. Tudo fica só neste telemóvel."), model.startLocal)
            }
            .padding(20)
        }
    }

    private func choice(_ title: String, _ body: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
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

    var body: some View {
        let s = model.strings
        let a = model.activation
        VStack(alignment: .leading, spacing: 14) {
            BrandMark(scale: 0.35)
            Text(s.activateTitle).font(.title.bold())
            if let text = a.qrText ?? a.url, let url = URL(string: text.contains("://") ? text : "https://\(text)") {
                Link(a.url ?? text, destination: url).foregroundStyle(Theme.sky)
            }
            Text(s.activateStep2).foregroundStyle(Theme.textDim)
            Text(a.code ?? "······").font(.system(size: 46, weight: .bold, design: .monospaced))
            if let error = a.error { Text(error).foregroundStyle(Theme.error) }
            Button(s.r("use_without_account")) { model.leaveActivation() }.padding(.top, 8)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct PhoneError: View {
    @Environment(AppModel.self) private var model
    let info: ErrorInfo
    @State private var adding = false

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
                    if model.hasAccount { MenuRow(s.r("logout")) { model.signOut() } }
                }
                .background(cardFill, in: cardShape)
            }
            .padding(20)
        }
        .sheet(isPresented: $adding) { PlaylistFormPage { adding = false } }
    }
}

private enum PhoneTab: String { case tv, favorites, search, menu }

/// O ecrã principal: abre logo a dar o último canal, com a lista por baixo.
struct PhoneMain: View {
    @Environment(AppModel.self) private var model
    @Environment(\.verticalSizeClass) private var vertical
    @State private var tab = PhoneMain.startTab
    @State private var epgFor: Channel?

    private static var startTab: PhoneTab {
        #if DEBUG
        if let t = UserDefaults.standard.string(forKey: "PhoneTab").flatMap(PhoneTab.init) { return t }
        #endif
        return .tv
    }

    var body: some View {
        let landscape = vertical == .compact
        VStack(spacing: 0) {
            VideoPane(fullscreen: landscape)
                .aspectRatio(landscape ? nil : 16 / 9, contentMode: .fit)
                .ignoresSafeArea(edges: landscape ? .all : [])
            if !landscape {
                Group {
                    switch tab {
                    case .tv: ChannelsTab(epgFor: $epgFor)
                    case .favorites: ChannelList(channels: model.channels(in: SpecialCategory.favorites), group: SpecialCategory.favorites, epgFor: $epgFor)
                    case .search: SearchTab(epgFor: $epgFor)
                    case .menu: MenuTab()
                    }
                }
                .frame(maxHeight: .infinity)
                // A barra flutua por cima da lista: o conteúdo passa por baixo do vidro.
                .safeAreaInset(edge: .bottom, spacing: 0) { TabRow(tab: $tab) }
            }
        }
        .sheet(item: $epgFor) { GuideSheet(channel: $0) }
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
    @State private var controls = true
    @State private var touched = 0

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(view: model.player.view)
            if let error = model.playbackError {
                Text(error.title).font(.subheadline).multilineTextAlignment(.center).padding()
            } else if model.player.state != .playing, let current = model.current {
                Text(current.name).font(.subheadline).foregroundStyle(Theme.textDim)
            }
            if controls { overlay }
        }
        .contentShape(Rectangle())
        .onTapGesture { controls.toggle(); touched += 1 }
        .gesture(DragGesture(minimumDistance: 40).onEnded { v in
            if abs(v.translation.height) > abs(v.translation.width) { model.zap(v.translation.height < 0 ? 1 : -1) }
        })
        .task(id: touched) {
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { controls = false }
        }
    }

    private var overlay: some View {
        let s = model.strings
        return VStack {
            HStack {
                Text(model.current?.name ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                Spacer()
                if let c = model.current {
                    iconButton(model.isFavorite(c) ? "star.fill" : "star") { model.toggleFavorite(c); touched += 1 }
                }
            }
            .padding(.horizontal, 12)
            .background(Color.black.opacity(0.45))
            Spacer()
            iconButton(model.player.paused ? "play.fill" : "pause.fill") { model.player.togglePause(); touched += 1 }
            Spacer()
            HStack(spacing: 6) {
                if model.catchup != nil {
                    pill("−5") { model.seekCatchup(model.catchupPosition - 300_000); touched += 1 }
                    pill("+5") { model.seekCatchup(model.catchupPosition + 300_000); touched += 1 }
                    pill(s.r("back_to_live")) { model.exitCatchup() }
                }
                Spacer()
                iconButton(fullscreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", action: rotate)
            }
            .padding(6)
        }
        .foregroundStyle(.white)
    }

    private func rotate() {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: fullscreen ? .portrait : .landscapeRight))
    }

    private func iconButton(_ name: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: name).font(.title3).frame(width: 44, height: 44) }
    }

    private func pill(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(Color.black.opacity(0.55), in: Capsule())
        }
    }
}

private struct ChannelsTab: View {
    @Environment(AppModel.self) private var model
    @Binding var epgFor: Channel?
    @State private var group: String?
    @State private var pinFor: ChannelGroup?
    @State private var pin = ""

    var body: some View {
        let groups = model.groups
        let shown = group ?? model.groupId
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(groups, id: \.id) { g in
                        let locked = model.isGroupLocked(g.id)
                        let on = g.id == shown
                        Button {
                            if locked { pinFor = g } else { group = g.id }
                        } label: {
                            Text(locked ? "🔒 \(g.name)" : g.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(on ? Color.black : Theme.text)
                                .padding(.horizontal, 16).padding(.vertical, 9)
                                .background(on ? Color.white : cardFill, in: Capsule())
                        }
                        .id(g.id)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
            }
            // O grupo em uso fica sempre à vista, mesmo com muitos grupos antes dele.
            .onAppear { proxy.scrollTo(shown, anchor: .center) }
            .onChange(of: shown) { _, id in withAnimation { proxy.scrollTo(id, anchor: .center) } }
            }
            ChannelList(channels: model.channels(in: shown), group: shown, epgFor: $epgFor)
        }
        .alert(model.strings.r("enter_pin"), isPresented: Binding(get: { pinFor != nil }, set: { if !$0 { pinFor = nil } })) {
            SecureField("PIN", text: $pin).keyboardType(.numberPad)
            Button("OK") {
                if let g = pinFor, model.unlockGroup(g.id, pin: pin) { group = g.id }
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
                LazyVStack(spacing: 8) {
                    ForEach(channels) { channel in
                        ChannelCard(channel: channel, group: group, epgFor: $epgFor)
                    }
                }
                .padding(.horizontal, 12).padding(.bottom, 12)
            }
        }
    }
}

private struct ChannelCard: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let group: String
    @Binding var epgFor: Channel?

    var body: some View {
        let playing = model.current?.key == channel.key
        let now = model.nowNext(for: channel).now
        Button {
            model.play(channel, from: group)
        } label: {
            HStack(spacing: 12) {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 64, height: 44))
                VStack(alignment: .leading, spacing: 3) {
                    Text(channel.name).font(.body.weight(.semibold)).foregroundStyle(Theme.text).lineLimit(1)
                    if let now {
                        Text(now.title).font(.footnote).foregroundStyle(Theme.textDim).lineLimit(1)
                        ProgressView(value: Double(now.progress(now: Int64(Date().timeIntervalSince1970 * 1000)))).tint(Theme.sky)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(playing ? Color.white.opacity(0.16) : cardFill, in: cardShape)
            .contentShape(cardShape)
        }
        .simultaneousGesture(LongPressGesture().onEnded { _ in epgFor = channel })
        .task { model.requestShortEpg(channel) }
    }
}

/// O guia de um canal (manter o dedo no cartão): o passado para catch-up, o futuro para lembretes.
private struct GuideSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let channel: Channel

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
                Button { model.toggleFavorite(channel) } label: { Image(systemName: model.isFavorite(channel) ? "star.fill" : "star") }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct SearchTab: View {
    @Environment(AppModel.self) private var model
    @Binding var epgFor: Channel?
    @State private var query = ""

    var body: some View {
        let s = model.strings
        let hits = model.search(query)
        VStack(spacing: 10) {
            TextField(s.r("search_hint"), text: $query)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(14).background(cardFill, in: RoundedRectangle(cornerRadius: 14))
                .padding([.horizontal, .top], 12)
            if query.count < 2 {
                Text(s.r("search_min_chars")).foregroundStyle(Theme.textFaint)
            } else if hits.isEmpty {
                Text(s.r("search_no_results")).foregroundStyle(Theme.textFaint)
            }
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(hits) { hit in
                        ChannelCard(channel: hit.channel, group: model.groupFor(hit.channel), epgFor: $epgFor)
                    }
                }
                .padding(.horizontal, 12)
            }
        }
    }
}

private struct TabRow: View {
    @Environment(AppModel.self) private var model
    @Binding var tab: PhoneTab

    var body: some View {
        let s = model.strings
        HStack(spacing: 0) {
            item(.tv, "TV", "tv")
            item(.favorites, s.r("favorites"), "star")
            item(.search, s.r("search"), "magnifyingglass")
            item(.menu, s.r("settings"), "gearshape")
        }
        .padding(5)
        .glass(radius: 40)
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    private func item(_ t: PhoneTab, _ label: String, _ icon: String) -> some View {
        let on = tab == t
        return Button {
            tab = t
        } label: {
            VStack(spacing: 2) {
                Image(systemName: on && icon != "magnifyingglass" ? "\(icon).fill" : icon).font(.system(size: 19))
                Text(label).font(.caption2).lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .foregroundStyle(on ? Theme.sky : Theme.textDim)
            .background(on ? Color.white.opacity(0.12) : .clear, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct MenuRow: View {
    let title: String
    var value: String?
    let action: () -> Void

    init(_ title: String, value: String? = nil, action: @escaping () -> Void) {
        self.title = title
        self.value = value
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(Theme.text).lineLimit(1)
                Spacer()
                if let value { Text(value).foregroundStyle(Theme.textDim).lineLimit(1) }
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .contentShape(Rectangle())
        }
    }
}

/// Definições do telemóvel: o essencial, em lista agrupada.
private struct MenuTab: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false
    @State private var choosingPin = false
    @State private var pinText = ""
    @State private var unlocked = false
    @State private var reporting = false
    @State private var message = ""

    var body: some View {
        let s = model.strings
        let p = model.prefs
        ScrollView {
            VStack(spacing: 14) {
                section(s.r("playlists")) {
                    ForEach(model.playlists, id: \.id) { pl in
                        MenuRow(pl.name, value: pl.id == model.content?.playlist.id ? "✓" : nil) { model.switchPlaylist(pl) }
                    }
                    if model.isLocal { MenuRow(s.r("add_playlist")) { adding = true } }
                }
                section(s.r("playback")) {
                    cycle(s.r("aspect"), "aspect", p.aspect, [("fit", s.r("aspect_fit")), ("zoom", s.r("aspect_zoom")), ("fill", s.r("aspect_fill"))])
                    cycle(s.r("buffer"), "buffer", p.buffer, [("live", s.r("buffer_live")), ("small", s.r("buffer_small")), ("normal", s.r("buffer_normal")), ("large", s.r("buffer_large"))])
                    cycle(s.r("on_startup"), "startup", p.startup, [("last", s.r("startup_last")), ("list", s.r("startup_list"))])
                }
                section(s.r("settings")) {
                    cycle(s.r("language"), "language", p.language, [("auto", s.r("language_auto"))] + Strings.languages.map { ($0.code, $0.name) })
                    cycle(s.r("accent"), "accent", p.accent, [("itv", "GoatTV"), ("violet", s.t("Violet", "Violeta")), ("mint", s.t("Mint", "Menta")), ("sunset", s.t("Sunset", "Pôr do sol")), ("gold", s.t("Gold", "Dourado")), ("white", s.t("White", "Branco"))])
                    MenuRow(s.t("Update TV guide now", "Atualizar o guia agora")) { model.refreshEpg() }
                }
                section(s.r("parental")) {
                    if p.pinHash == nil {
                        MenuRow(s.r("new_pin")) { choosingPin = true }
                    } else {
                        ForEach(model.allGroups, id: \.id) { g in
                            MenuRow(g.name, value: model.isGroupLocked(g.id) ? "🔒" : nil) { model.toggleGroupLock(g.id) }
                        }
                    }
                }
                section(s.r("account")) {
                    if let email = model.email { MenuRow(email) {} }
                    MenuRow(s.r("send_report")) { reporting = true }
                    if model.hasAccount { MenuRow(s.r("logout")) { model.signOut() } }
                    MenuRow(s.r("version"), value: model.appVersion) {}
                }
            }
            .padding(12)
        }
        .sheet(isPresented: $adding) { PlaylistFormPage { adding = false } }
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
                            pinText = ""
                        }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background)
            }
        }
    }

    private func section<Rows: View>(_ title: String, @ViewBuilder rows: () -> Rows) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.footnote).foregroundStyle(Theme.textFaint).padding(.leading, 12)
            VStack(spacing: 0) { rows() }.background(cardFill, in: cardShape)
        }
    }

    /// Uma escolha entre poucas opções: tocar passa à seguinte.
    private func cycle(_ title: String, _ key: String, _ value: String, _ options: [(String, String)]) -> some View {
        let index = options.firstIndex { $0.0 == value } ?? 0
        return MenuRow(title, value: options[index].1) {
            model.setPref(key, .string(options[(index + 1) % options.count].0))
        }
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
                Picker("", selection: $form.type) {
                    Text("Xtream").tag(PlaylistType.xtream)
                    Text("M3U").tag(PlaylistType.m3u)
                }
                .pickerStyle(.segmented)
                TextField(s.r("name"), text: $form.name)
                if form.type == .xtream {
                    TextField(s.r("server"), text: $form.server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField(s.r("username"), text: $form.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(s.r("password"), text: $form.password)
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
