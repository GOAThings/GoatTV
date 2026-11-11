import GoatCore
import SwiftUI

/// O painel "Canal de reserva" do canal a ver (como no Android): os failovers dele, pela ordem em que se tentam quando
/// não abre (canais da reserva ou da própria lista), cada um com a etiqueta da lista. OK num failover pega nele e ↑/↓
/// mudam-no de lugar (como em "Reordenar grupos"); carregar e manter abre o menu para o tirar. Por baixo, "Juntar um
/// canal" (uma página com a pesquisa nas duas listas e os sugeridos; Voltar recua para os failovers), "Experimentar
/// agora" e "Voltar ao automático" (só com uma escolha à mão). Voltar fecha o painel.
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
    @State private var adding = false
    @State private var query = ""
    @State private var results: [BackupHit] = []
    @State private var moving: String?
    @FocusState private var focus: String?

    var body: some View {
        let s = model.strings
        let panel = VStack(alignment: .leading, spacing: 16) {
            if !embedded || adding {
                Text(adding ? s.r("backup_add_channel") : s.r("backup_channel")).font(Fonts.panelTitle).padding(.horizontal, 16)
            }
            if loading {
                Text(s.r("backup_checking")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            } else if let choices {
                if adding { addPage(choices) } else { listPage(choices) }
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
        // Em "Juntar um canal", Voltar recua para os failovers (sem isto, fechava o painel).
        .onExitCommand(perform: adding ? { back() } : nil)
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
            focus = choices?.failovers.first.map { "f:" + $0 } ?? "add"
        }
    }

    /// Os failovers, "Juntar um canal", "Experimentar agora" e "Voltar ao automático".
    private func listPage(_ choices: BackupChoices) -> some View {
        let s = model.strings
        let failovers = choices.failovers
        return ScrollViewReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 4) {
                    Text(failovers.isEmpty ? s.r("backup_none_note", channel.name) : s.r("backup_failovers_intro", channel.name))
                        .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 10).padding(.bottom, 6)
                    ForEach(Array(failovers.enumerated()), id: \.element) { i, f in failoverRow(i, f, choices) }
                    actionRow("add", "plus", s.r("backup_add_channel"), nil) {
                        adding = true
                        focusSoon("search")
                    }
                    Divider().padding(.vertical, 8).padding(.horizontal, 10)
                    if let onTry, !failovers.isEmpty {
                        actionRow("try", "play.fill", s.r("backup_channel_try"), nil) { onTry(failovers) }
                    }
                    if !choices.automatic {
                        actionRow("auto", "arrow.counterclockwise", s.r("backup_reset_auto"), model.backupAutoLabel(choices)) {
                            save(nil)
                            focus = "add"
                        }
                    }
                    if !failovers.isEmpty {
                        Text(s.r("backup_failovers_hint")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 10).padding(.top, 10)
                    }
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
    }

    /// "Juntar um canal": a pesquisa nas duas listas e os sugeridos; OK junta no fim e volta aos failovers.
    private func addPage(_ choices: BackupChoices) -> some View {
        let s = model.strings
        let added = Set(choices.failovers)
        let list = query.trimmingCharacters(in: .whitespaces).isEmpty ? choices.suggested : results
        return ScrollView(.vertical, showsIndicators: false) {
            LazyVStack(alignment: .leading, spacing: 4) {
                TextField(s.r("backup_channel_search_all"), text: $query)
                    .font(Fonts.rowTitle)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: "search")
                    .padding(.bottom, 6)
                Text(query.isEmpty ? s.r("backup_suggested") : s.r("backup_channel_results"))
                    .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 10).padding(.top, 8)
                ForEach(list) { hit in
                    let f = choices.failover(hit.channel, fromMain: hit.fromMain)
                    let already = added.contains(f)
                    Button {
                        if !already { save(choices.toggled(f)) }
                        back()
                    } label: {
                        RowContent(title: hit.channel.name, subtitle: already ? s.r("backup_already_added") : nil) {
                            sourceTag(model.failoverSource(choices, f), dim: already)
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

    private func back() {
        adding = false
        query = ""
        focusSoon(choices?.failovers.last.map { "f:" + $0 } ?? "add")
    }

    private func focusSoon(_ id: String) {
        Task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = id
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

    private func actionRow(_ id: String, _ icon: String, _ title: String, _ subtitle: String?, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            RowContent(title: title, subtitle: subtitle) {
                Image(systemName: icon).font(.system(size: 22))
            }
        }
        .buttonStyle(FillRowStyle())
        .focused($focus, equals: id)
        .disabled(moving != nil)
    }

    /// Um failover: o número, o canal e a etiqueta da lista. OK pega/larga; com ele "na mão", ↑/↓ movem-no; manter abre
    /// "Tirar" (o foco passa ao que fica no lugar dele).
    private func failoverRow(_ index: Int, _ f: String, _ choices: BackupChoices) -> some View {
        let isMoving = moving == f
        return Button { moving = isMoving ? nil : f } label: {
            RowContent(title: "\(index + 1).  " + (choices.channel(of: f)?.name ?? ""), subtitle: nil) {
                if isMoving {
                    Text(model.strings.r("moving")).font(Fonts.rowValue).foregroundStyle(Theme.sky)
                } else {
                    sourceTag(model.failoverSource(choices, f))
                }
            }
        }
        .buttonStyle(FillRowStyle(selected: isMoving))
        .focused($focus, equals: "f:" + f)
        .disabled(moving != nil && !isMoving)
        .id("f:" + f)
        .contextMenu {
            Button(role: .destructive) {
                moving = nil
                let rest = choices.failovers.filter { $0 != f }
                save(rest)
                focusSoon((rest.indices.contains(index) ? rest[index] : rest.last).map { "f:" + $0 } ?? "add")
            } label: { Text(model.strings.r("backup_failover_remove")) }
        }
    }

    /// A etiqueta pequena com a lista de um canal ("Backup", "Esta lista").
    private func sourceTag(_ text: String, dim: Bool = false) -> some View {
        Text(text)
            .font(.system(size: 20, weight: .medium))
            .foregroundStyle(.secondary)
            .opacity(dim ? 0.5 : 1)
            .padding(.horizontal, 12).padding(.vertical, 4)
            .background(Color.secondary.opacity(0.15), in: Capsule())
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
