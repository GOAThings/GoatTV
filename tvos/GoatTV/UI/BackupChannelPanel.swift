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
    let onTry: (Channel) -> Void

    @State private var choices: BackupChoices?
    @State private var loading = true
    @State private var query = ""
    @State private var results: [Channel] = []
    @FocusState private var focus: String?

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(s.r("backup_channel")).font(Fonts.panelTitle)
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
                        Button { if let effective { onTry(effective) } } label: {
                            RowContent(title: s.r("backup_channel_try"), subtitle: effective?.name) {
                                Image(systemName: "play.fill").font(.system(size: 22))
                            }
                        }
                        .buttonStyle(FillRowStyle())
                        .focused($focus, equals: "try")
                        checkRow("auto", s.r("backup_channel_auto"), model.backupAutoLabel(choices), choices.choice == nil) { pick(nil) }
                        checkRow("off", s.r("backup_channel_off"), nil, choices.choice == ChannelMatch.off) { pick(ChannelMatch.off) }
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
                Text(s.r("backup_unavailable", model.content.flatMap { model.backupOf($0.playlist.id)?.name } ?? ""))
                    .font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
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
        .task(id: query) {
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let choices else { return }
            results = model.searchBackup(choices, query)
        }
        .task {
            choices = await model.backupChoices(channel)
            loading = false
            try? await Task.sleep(for: .milliseconds(80))
            focus = "try"
        }
    }

    private func pick(_ value: String?) {
        guard let main = model.content?.playlist.id else { return }
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
