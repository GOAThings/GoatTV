import GoatCore
import SwiftUI

/// O painel "Canal de reserva" do canal a ver (como no Android): o canal que a lista de reserva dá quando este não
/// abre. O automático (ChannelMatch) ou "sem correspondência"; os mais parecidos; a pesquisa em toda a reserva; e
/// "nenhum" (este canal não muda de lista). Escolher grava (ganha ao automático); "Automático" apaga a escolha.
/// "Experimentar agora" dá já o escolhido (a ligação à lista principal fecha antes). Voltar fecha o painel.
struct BackupChannelPanel: View {
    @Environment(\.panelActivity) private var panelActivity
    @Environment(AppModel.self) private var model
    let channel: Channel
    /// A lista principal (nil = a em uso).
    var mainId: String? = nil
    /// "Experimentar agora" (nil nas Definições, onde nada está a dar).
    var onTry: ((Channel) -> Void)?
    /// Dentro das Definições: sem o título nem o vidro próprios.
    var embedded = false

    @State private var choices: BackupChoices?
    @State private var loading = true
    @State private var query = ""
    @State private var results: [Channel] = []
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
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        let effective = choices.effective
                        if let onTry {
                            Button { if let effective { onTry(effective) } } label: {
                                RowContent(title: s.r("backup_channel_try"), subtitle: effective?.name) {
                                    Image(systemName: "play.fill").font(.system(size: 22))
                                }
                            }
                            .buttonStyle(FillRowStyle())
                            .focused($focus, equals: "try")
                        }
                        if choices.manual {
                            // Manual: o automático é uma sugestão, que se escolhe com um toque.
                            if let auto = choices.autoChannel {
                                checkRow("auto", s.r("backup_suggestion", auto.name), choices.content.categoryName(auto.categoryId), choices.choice == auto.key) { pick(auto.key) }
                            } else {
                                checkRow("auto", model.backupAutoLabel(choices), nil, false) {}
                            }
                        } else {
                            checkRow("auto", s.r("backup_channel_auto"), model.backupAutoLabel(choices), choices.choice == nil) { pick(nil) }
                        }
                        checkRow("off", s.r("backup_channel_off"), nil, choices.none) { pick(ChannelMatch.off) }
                        let chosen = choices.choice.flatMap { choices.content.byKey[$0] }
                        let similar = uniqueByKey([chosen].compactMap { $0 } + choices.similar)
                        if !similar.isEmpty { header(s.r("backup_channel_similar")) }
                        ForEach(similar, id: \.key) { c in channelRow("s:", c, choices) }
                        TextField(s.r("backup_channel_search", choices.backup.name), text: $query)
                            .font(Fonts.rowTitle)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focus, equals: "field")
                            .padding(.top, 10)
                        if !results.isEmpty { header(s.r("backup_channel_results")) }
                        ForEach(results, id: \.key) { c in channelRow("r:", c, choices) }
                    }
                    .padding(.vertical, 6)
                    .padding(.horizontal, 6)
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
            results = model.searchBackup(choices, query)
        }
        .task {
            choices = await model.backupChoices(channel, mainId: mainId)
            loading = false
            try? await Task.sleep(for: .milliseconds(80))
            focus = onTry != nil ? "try" : "auto"
        }
    }

    private func pick(_ value: String?) {
        guard let main = choices?.mainId else { return }
        choices?.choice = value
        model.setBackupChoice(main, channel.key, value)
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
    }

    private func channelRow(_ prefix: String, _ c: Channel, _ choices: BackupChoices) -> some View {
        let auto = c.key == choices.autoChannel?.key ? model.strings.r("backup_channel_auto") : nil
        let subtitle = [choices.content.categoryName(c.categoryId), auto].compactMap { $0 }.joined(separator: " · ")
        return checkRow(prefix + c.key, c.name, subtitle, choices.choice == c.key) { pick(c.key) }
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
