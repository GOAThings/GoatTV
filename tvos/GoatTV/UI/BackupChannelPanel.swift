import GoatCore
import SwiftUI

/// O painel "Canal de reserva" do canal a ver (como no Android): os failovers dele, pela ordem em que se tentam quando
/// não abre (canais da reserva ou da própria lista). Sem escolha, é o automático (ChannelMatch). OK num failover pega
/// nele e ↑/↓ mudam-no de lugar (como em "Reordenar grupos"); carregar e manter abre o menu para o tirar. Os parecidos
/// (nas duas listas) e a pesquisa juntam-se com OK. Qualquer mudança grava a lista (ganha ao automático); "Automático"
/// apaga a escolha e "Nenhum" deixa-a vazia. "Experimentar agora" dá já os failovers, pela ordem (a ligação à lista
/// principal fecha antes). Voltar fecha o painel.
struct BackupChannelPanel: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let channel: Channel
    /// A lista principal (nil = a em uso).
    var mainId: String? = nil
    /// "Experimentar agora" (nil nas Definições, onde nada está a dar).
    var onTry: (([String]) -> Void)?
    /// Dentro das Definições: sem o título nem o vidro próprios.
    var embedded = false

    @State private var choices: BackupChoices?
    @State private var loading = true
    @State private var query = ""
    @State private var results: [BackupHit] = []
    @State private var moving: String?
    @FocusState private var focus: String?

    var body: some View {
        let s = model.strings
        let panel = VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                if !embedded { Text(s.r("backup_channel")).font(Fonts.panelTitle) }
                Text([channel.name, choices?.backup.name].compactMap { $0 }.joined(separator: " → "))
                    .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim)
            }
            .padding(.horizontal, 16)
            if loading {
                Text(s.r("backup_checking")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            } else if let choices {
                ScrollViewReader { proxy in
                    ScrollView(.vertical, showsIndicators: false) {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            let failovers = choices.failovers
                            if let onTry {
                                Button { if !failovers.isEmpty { onTry(failovers) } } label: {
                                    RowContent(title: s.r("backup_channel_try"), subtitle: failovers.first.flatMap { choices.channel(of: $0)?.name }) {
                                        Image(systemName: "play.fill").font(.system(size: 22))
                                    }
                                }
                                .buttonStyle(FillRowStyle())
                                .focused($focus, equals: "try")
                                .disabled(moving != nil)
                            }
                            header(failovers.isEmpty ? s.r("backup_state_none") : s.r("backup_failovers"))
                            ForEach(Array(failovers.enumerated()), id: \.element) { i, f in failoverRow(i, f, choices) }
                            if !failovers.isEmpty { header(s.r("backup_failovers_hint")) }
                            if choices.manual {
                                checkRow("auto", model.backupAutoLabel(choices), nil, false) {}
                            } else {
                                checkRow("auto", s.r("backup_channel_auto"), model.backupAutoLabel(choices), choices.automatic) { save(nil) }
                            }
                            checkRow("off", s.r("backup_channel_off"), nil, choices.none) { save([]) }
                            // Para juntar: os parecidos na reserva (com a sugestão do automático) e na própria lista.
                            let similar = uniqueByKey([choices.autoChannel].compactMap { $0 } + choices.similar)
                            if !similar.isEmpty { header(s.r("backup_add_from", choices.backup.name)) }
                            ForEach(similar, id: \.key) { c in addRow("s:", c, false, choices) }
                            if !choices.similarMain.isEmpty { header(s.r("backup_add_from", choices.main?.playlist.name ?? "")) }
                            ForEach(choices.similarMain, id: \.key) { c in addRow("m:", c, true, choices) }
                            TextField(s.r("backup_channel_search_all"), text: $query)
                                .font(Fonts.rowTitle)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focus, equals: "field")
                                .disabled(moving != nil)
                                .padding(.top, 10)
                            if !results.isEmpty { header(s.r("backup_channel_results")) }
                            ForEach(results) { r in addRow(r.fromMain ? "rm:" : "r:", r.channel, r.fromMain, choices) }
                        }
                        .padding(.vertical, 6)
                        .padding(.horizontal, 6)
                    }
                    .onMoveCommand { direction in
                        guard let f = moving else { return }
                        if direction == .up { move(f, -1) }
                        if direction == .down { move(f, 1) }
                        proxy.scrollTo("f:" + f, anchor: .center)
                    }
                }
            } else {
                Text(s.r("backup_unavailable", (mainId ?? model.content?.playlist.id).flatMap { model.backupOf($0)?.name } ?? ""))
                    .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            }
        }
        return Group {
            if embedded {
                panel
            } else {
                panel
                    .padding(.vertical, 28)
                    .padding(.horizontal, 16)
                    .frame(width: model.panelPoints(model.prefs.panelWidth + 60))
                    .frame(maxHeight: .infinity, alignment: .top)
                    .glass()
            }
        }
        .focusSection()
        .onChange(of: focus) { _, now in panelActivity(now == "field") }
        .onChange(of: query) { _, _ in panelActivity(focus == "field") }
        .task(id: query) {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let choices else { return }
            results = model.searchBackup(choices, query, except: channel.key)
        }
        .task {
            choices = await model.backupChoices(channel, mainId: mainId)
            loading = false
            try? await Task.sleep(for: .milliseconds(80))
            focus = onTry != nil ? "try" : (choices?.failovers.first.map { "f:" + $0 } ?? "auto")
        }
    }

    private func save(_ list: [String]?) {
        guard let main = choices?.mainId else { return }
        choices?.choice = list
        model.setBackupChoice(main, channel.key, list)
    }

    private func move(_ failover: String, _ delta: Int) {
        guard let list = choices?.moved(failover, by: delta) else { return }
        save(list)
    }

    private func header(_ text: String) -> some View {
        Text(text).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 10).padding(.top, 12)
    }

    private func checkRow(_ id: String, _ title: String, _ subtitle: String?, _ checked: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            RowContent(title: title, subtitle: subtitle) {
                if checked { Image(systemName: "checkmark").font(.system(size: 22)) }
            }
        }
        .buttonStyle(FillRowStyle())
        .focused($focus, equals: id)
        .disabled(moving != nil)
    }

    /// Um failover: o número, o canal e a lista. OK pega/larga; com ele "na mão", ↑/↓ movem-no; manter abre "Tirar".
    private func failoverRow(_ index: Int, _ f: String, _ choices: BackupChoices) -> some View {
        let isMoving = moving == f
        return Button { moving = isMoving ? nil : f } label: {
            RowContent(title: "\(index + 1). " + (choices.channel(of: f)?.name ?? ""), subtitle: model.failoverSubtitle(choices, f)) {
                Text(isMoving ? model.strings.r("moving") : "↕")
                    .font(Fonts.rowValue)
                    .foregroundStyle(isMoving ? AnyShapeStyle(Theme.sky) : AnyShapeStyle(Color.secondary))
            }
        }
        .buttonStyle(FillRowStyle(selected: isMoving))
        .focused($focus, equals: "f:" + f)
        .disabled(moving != nil && !isMoving)
        .id("f:" + f)
        .contextMenu {
            Button(role: .destructive) {
                if moving == f { moving = nil }
                save(choices.failovers.filter { $0 != f })
            } label: { Text(model.strings.r("backup_failover_remove")) }
        }
    }

    /// Um canal para juntar aos failovers (da reserva, ou [fromMain] da própria lista); com o visto se já lá estiver.
    private func addRow(_ prefix: String, _ c: Channel, _ fromMain: Bool, _ choices: BackupChoices) -> some View {
        let f = choices.failover(c, fromMain: fromMain)
        let added = choices.failovers.contains(f)
        return Button { save(choices.toggled(f)) } label: {
            RowContent(title: c.name, subtitle: model.failoverSubtitle(choices, f)) {
                Image(systemName: added ? "checkmark" : "plus").font(.system(size: 22))
            }
        }
        .buttonStyle(FillRowStyle(selected: added))
        .focused($focus, equals: prefix + c.key)
        .disabled(moving != nil)
    }

    private func uniqueByKey(_ list: [Channel]) -> [Channel] {
        var seen = Set<String>()
        return list.filter { seen.insert($0.key).inserted }
    }
}

/// A lista "Canais de reserva" (Definições → lista): cada canal em direto da lista principal, o da reserva e o estado,
/// com filtros e a pesquisa pelo nome. Feita uma vez (a mesma conta da cobertura); OK abre a escolha ([onOpen]).
struct BackupChannelsList: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let playlistId: String
    let onOpen: (Channel) -> Void
    @State private var filter = ChannelMatch.Filter.all
    @State private var query = ""
    @FocusState private var focus: String?

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 12) {
            if let review = model.backupReview(playlistId) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(ChannelMatch.Filter.allCases, id: \.self) { f in
                            Button { filter = f } label: {
                                Text("\(model.backupFilterLabel(f)) (\(review.rows.filter { $0.passes(f) }.count))")
                                    .font(Fonts.rowSubtitle)
                                    .padding(.horizontal, 16).padding(.vertical, 8)
                                    .background(f == filter ? Color.white.opacity(0.25) : Color.clear, in: Capsule())
                            }
                            .buttonStyle(FillRowStyle())
                            .focused($focus, equals: "f:" + f.rawValue)
                        }
                    }
                    .padding(.horizontal, 6)
                }
                TextField(s.r("backup_channel_search", review.main.playlist.name), text: $query)
                    .font(Fonts.rowTitle)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: "field")
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(model.backupRows(review, filter, query), id: \.main) { row in
                            let text = model.backupRowText(review, row)
                            Button { onOpen(review.main.channels[row.main]) } label: {
                                RowContent(title: text.title, subtitle: text.subtitle)
                            }
                            .buttonStyle(FillRowStyle())
                            .focused($focus, equals: "r:\(row.main)")
                        }
                    }
                    .padding(.vertical, 6)
                    .padding(.horizontal, 6)
                }
            } else {
                Text(s.r("backup_checking")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            }
        }
        .focusSection()
        .onChange(of: focus) { _, now in panelActivity(now == "field") }
        .task(id: model.backupReview(playlistId) != nil) {
            try? await Task.sleep(for: .milliseconds(80))
            if focus == nil, model.backupReview(playlistId) != nil { focus = "f:" + ChannelMatch.Filter.all.rawValue }
        }
    }
}
