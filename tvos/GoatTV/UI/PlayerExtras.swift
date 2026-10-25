import GoatCore
import SwiftUI

/// Pesquisa de canais e programas, encostada à esquerda (como os painéis).
struct SearchPanel: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let onPick: (AppModel.SearchHit) -> Void

    @State private var query = ""
    @State private var hits: [AppModel.SearchHit] = []
    @State private var now = nowMs()
    @FocusState private var focus: String?

    init(onPick: @escaping (AppModel.SearchHit) -> Void) {
        self.onPick = onPick
    }

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 16) {
            Text(s.t("Search", "Pesquisar")).font(Fonts.panelTitle).padding(.horizontal, 16)
            TextField(s.t("Channel or programme", "Canal ou programa"), text: $query)
                .font(Fonts.rowTitle)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focus, equals: "field")
                .onSubmit {
                    if let first = hits.first { focus = first.id }
                }
            if query.trimmingCharacters(in: .whitespaces).count < 2 {
                Text(s.t("Type at least 2 letters", "Escreve pelo menos 2 letras")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            } else if hits.isEmpty {
                Text(s.t("Nothing found", "Não se encontrou nada")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            }
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(hits) { hit in
                        Button { onPick(hit) } label: {
                            HStack(spacing: 18) {
                                if model.prefs.showLogos {
                                    ChannelLogo(url: model.logo(for: hit.channel), name: hit.channel.name, size: CGSize(width: 72, height: 46))
                                }
                                RowContent(title: hit.programme?.title ?? hit.channel.name, subtitle: subtitle(hit)) {
                                    if let p = hit.programme, model.hasReminder(hit.channel, p) {
                                        Image(systemName: "bell.fill").font(.system(size: 22))
                                    }
                                }
                            }
                        }
                        .buttonStyle(FillRowStyle())
                        .focused($focus, equals: hit.id)
                    }
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 6)
            }
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: model.panelPoints(model.prefs.panelWidth + 60))
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .focusSection()
        .onChange(of: focus) { _, now in panelActivity(now == "field") }
        .onChange(of: query) { _, _ in panelActivity(focus == "field") }
        // Pesquisa enquanto se escreve, com uma pequena pausa.
        .task(id: query) {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            now = nowMs()
            hits = model.search(query)
        }
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = "field"
        }
    }

    private func subtitle(_ hit: AppModel.SearchHit) -> String? {
        let s = model.strings
        guard let p = hit.programme else {
            let name = model.content?.categoryName(hit.channel.categoryId) ?? hit.channel.categoryId
            return name == PlaylistContent.others ? s.others : name
        }
        if p.startMs <= now { return s.t("Now on %@", "Agora na %@", "\(hit.channel.name)") }
        return "\(hhmm(p.start)) · \(hit.channel.name)"
    }
}

/// Barra do catch-up: programa, posição e as teclas (← → recuar/avançar, OK pausa, Voltar direto).
struct CatchupBar: View {
    @Environment(AppModel.self) private var model
    let catchup: Catchup
    let positionMs: Int64
    let pendingSeekMs: Int64?
    let paused: Bool

    var body: some View {
        let s = model.strings
        let shown = pendingSeekMs ?? positionMs
        VStack(alignment: .leading, spacing: 22) {
            HStack(spacing: 22) {
                Text("CATCH-UP")
                    .font(.system(size: 21, weight: .heavy))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Theme.accent(model.prefs.accent)))
                VStack(alignment: .leading, spacing: 4) {
                    Text(catchup.title).font(.system(size: 36, weight: .semibold)).lineLimit(1)
                    Text("\(catchup.channel.name) · \(hhmm(Date(timeIntervalSince1970: Double(catchup.startMs) / 1000))) – \(hhmm(Date(timeIntervalSince1970: Double(catchup.endMs) / 1000)))")
                        .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
                }
                Spacer()
                if paused { Image(systemName: "pause.fill").font(.system(size: 40)) }
            }
            HStack(spacing: 22) {
                Text(Self.duration(shown)).font(.system(size: 25, weight: .medium).monospacedDigit())
                    .foregroundStyle(pendingSeekMs != nil ? Theme.sky : Theme.text)
                ProgressLine(value: min(1, max(0, Double(shown) / Double(max(1, catchup.durationMs)))), height: 6)
                Text(Self.duration(catchup.durationMs)).font(.system(size: 25).monospacedDigit()).foregroundStyle(Theme.textDim)
            }
            HStack(spacing: 40) {
                KeyHint(key: "← →", label: s.t("1 minute", "1 minuto"))
                KeyHint(key: "OK", label: paused ? s.t("Resume", "Continuar") : s.t("Pause", "Pausa"))
                KeyHint(key: "MENU", label: s.t("Back to live", "Voltar ao direto"))
            }
        }
        .padding(.horizontal, 44)
        .padding(.vertical, 32)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glass(radius: 34)
    }

    static func duration(_ ms: Int64) -> String {
        let total = max(0, ms / 1000)
        let h = total / 3600
        let m = (total % 3600) / 60
        let sec = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}

/// Cápsula "CATCH-UP" no canto, sempre visível enquanto não se está em direto.
struct CatchupPill: View {
    let paused: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: paused ? "pause.fill" : "clock.arrow.circlepath").font(.system(size: 24))
            Text("CATCH-UP").font(.system(size: 22, weight: .bold))
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .glass(radius: 30)
    }
}

/// Um programa com lembrete vai começar.
struct ReminderCard: View {
    @Environment(AppModel.self) private var model
    let reminder: AppModel.Reminder
    let onWatch: () -> Void
    let onDismiss: () -> Void
    @FocusState private var focus: String?

    init(reminder: AppModel.Reminder, onWatch: @escaping () -> Void, onDismiss: @escaping () -> Void) {
        self.reminder = reminder
        self.onWatch = onWatch
        self.onDismiss = onDismiss
    }

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 26) {
            HStack(spacing: 24) {
                Image(systemName: "bell.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(.white)
                    .frame(width: 80, height: 80)
                    .background(Circle().fill(Theme.accent(model.prefs.accent)))
                VStack(alignment: .leading, spacing: 6) {
                    Text(s.t("Starting now", "Vai começar")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
                    Text(reminder.title).font(.system(size: 36, weight: .semibold)).lineLimit(2)
                    Text("\(hhmm(Date(timeIntervalSince1970: Double(reminder.startMs) / 1000))) · \(reminder.channelName)")
                        .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
                }
            }
            HStack(spacing: 20) {
                Button(s.t("Watch", "Ver"), action: onWatch).buttonStyle(ChipButtonStyle()).focused($focus, equals: "watch")
                Button(s.t("Dismiss", "Ignorar"), action: onDismiss).buttonStyle(ChipButtonStyle()).focused($focus, equals: "dismiss")
            }
        }
        .padding(44)
        .frame(maxWidth: 1040, alignment: .leading)
        .glass(radius: 34)
        .focusSection()
        .onExitCommand(perform: onDismiss)
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = "watch"
        }
    }
}

/// Escolha do temporizador para desligar.
struct SleepPicker: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let onPick: (Int?) -> Void
    let onClose: () -> Void
    @FocusState private var focus: Int?

    init(onPick: @escaping (Int?) -> Void, onClose: @escaping () -> Void) {
        self.onPick = onPick
        self.onClose = onClose
    }

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 8) {
            Text(s.t("Sleep timer", "Temporizador")).font(Fonts.panelTitle).padding(.horizontal, 24)
            if let at = model.sleepAt {
                Text(s.t("Turns off in %@ min", "Desliga daqui a %@ min", "\(Int(at.timeIntervalSinceNow / 60) + 1)"))
                    .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 24).padding(.bottom, 8)
            }
            ForEach([0, 15, 30, 45, 60, 90, 120], id: \.self) { minutes in
                Button { onPick(minutes == 0 ? nil : minutes) } label: {
                    RowContent(title: minutes == 0 ? s.t("Off", "Desligado") : "\(minutes) min")
                }
                .buttonStyle(FillRowStyle())
                .focused($focus, equals: minutes)
            }
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: 720)
        .glass()
        .focusSection()
        .onChange(of: focus) { _, _ in panelActivity(false) }
        .onExitCommand(perform: onClose)
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = 0
        }
    }
}

/// Aviso no último minuto do temporizador: qualquer tecla mostra que há alguém a ver.
struct SleepWarning: View {
    @Environment(AppModel.self) private var model
    let seconds: Int

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 18) {
                Image(systemName: "moon.zzz.fill").font(.system(size: 40))
                Text(s.t("Turning off in %@ s", "Vai desligar daqui a %@ s", "\(seconds)")).font(.system(size: 36, weight: .semibold))
            }
            KeyHint(key: "OK", label: s.t("Keep watching", "Continuar a ver"))
        }
        .padding(44)
        .glass(radius: 34)
    }
}

/// Escolha do segundo canal (lista → grupo → canal), encostada à esquerda como a pesquisa.
struct SecondChannelPicker: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let onPick: (PlaylistContent, Channel, String) -> Void
    let onClose: () -> Void

    @State private var content: PlaylistContent?
    @State private var group: ChannelGroup?
    @State private var loading = false
    @FocusState private var focus: String?

    init(onPick: @escaping (PlaylistContent, Channel, String) -> Void, onClose: @escaping () -> Void) {
        self.onPick = onPick
        self.onClose = onClose
    }

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 10) {
            Text(group?.name ?? content?.playlist.name ?? s.t("Second channel", "Segundo canal"))
                .font(Fonts.panelTitle).lineLimit(1).padding(.horizontal, 16)
            Text(group != nil ? s.t("Choose the channel", "Escolhe o canal") : (content != nil ? s.t("Choose the group", "Escolhe o grupo") : s.t("Choose the playlist", "Escolhe a lista")))
                .font(Fonts.panelSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16).padding(.bottom, 8)
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 4) {
                    if loading {
                        Text(s.t("Loading…", "A carregar…")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(16)
                    } else if let content, let group {
                        let channels = model.channelsForSecond(content, group.id)
                        if channels.isEmpty {
                            Text(s.noChannels).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(16)
                        }
                        ForEach(channels) { ch in
                            Button { onPick(content, ch, group.id) } label: {
                                HStack(spacing: 18) {
                                    if model.prefs.showLogos {
                                        ChannelLogo(url: ch.logo.flatMap(URL.init(string:)), name: ch.name, size: CGSize(width: 72, height: 46))
                                    }
                                    RowContent(title: model.prefs.showNumbers ? "\(ch.num)  \(ch.name)" : ch.name)
                                }
                            }
                            .buttonStyle(FillRowStyle())
                            .focused($focus, equals: "c:\(ch.uid)")
                        }
                    } else if let content {
                        ForEach(model.groupsForSecond(content)) { g in
                            Button { group = g } label: { RowContent(title: g.name) }
                                .buttonStyle(FillRowStyle())
                                .focused($focus, equals: "g:\(g.id)")
                        }
                    } else {
                        let allowed = model.playlists.filter { model.secondAllowed($0) }
                        if allowed.isEmpty {
                            Text(s.t("Your plan only allows one connection: add another playlist to watch two channels.",
                                     "O teu plano só deixa uma ligação: junta outra lista para veres dois canais."))
                                .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(16)
                        }
                        ForEach(allowed + model.playlists.filter { !model.secondAllowed($0) }) { p in
                            let ok = model.secondAllowed(p)
                            Button { open(p) } label: {
                                RowContent(title: p.name, subtitle: !ok ? s.t("Only one connection", "Só uma ligação")
                                           : (p.id == model.content?.playlist.id ? s.t("Playlist in use", "Lista em uso") : nil))
                            }
                            .buttonStyle(FillRowStyle())
                            .disabled(!ok)
                            .focused($focus, equals: "p:\(p.id)")
                        }
                    }
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 6)
            }
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: model.panelPoints(model.prefs.panelWidth + 60))
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .focusSection()
        .onChange(of: focus) { _, _ in panelActivity(false) }
        .onExitCommand {
            if group != nil { group = nil } else if content != nil { content = nil } else { onClose() }
        }
        // Cada passo começa no primeiro item.
        .task(id: "\(content?.playlist.id ?? "")|\(group?.id ?? "")|\(loading)") {
            try? await Task.sleep(for: .milliseconds(80))
            if let content, let group {
                focus = model.channelsForSecond(content, group.id).first.map { "c:\($0.uid)" }
            } else if let content {
                focus = model.groupsForSecond(content).first.map { "g:\($0.id)" }
            } else {
                focus = model.playlists.first { model.secondAllowed($0) }.map { "p:\($0.id)" }
            }
        }
    }

    private func open(_ p: Playlist) {
        loading = true
        Task {
            do {
                content = try await model.loadForSecond(p)
            } catch {
                model.showToast(model.strings.sourceError(error))
            }
            loading = false
        }
    }
}
