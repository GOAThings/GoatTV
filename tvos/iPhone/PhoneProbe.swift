import GoatCore
import SwiftUI

/// "Testar este canal" na folha de opções do player: começa sozinho ao abrir e mostra o veredicto e os números.
struct PhoneChannelTestSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let player: PlayerEngine
    @State private var probe = ChannelProbe()

    var body: some View {
        let s = model.strings
        NavigationStack {
            List {
                switch probe.phase {
                case .idle:
                    EmptyView()
                case .unsupported:
                    Text(s.r("probe_unsupported")).foregroundStyle(Theme.textFaint)
                case .running(let second):
                    Text(s.r("probe_testing", "\(second)")).foregroundStyle(Theme.textFaint)
                case .done(let r):
                    let lines = ChannelProbe.lines(r, s)
                    Label(lines.verdict, systemImage: lines.good ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(lines.good ? Theme.text : Color.orange)
                    ForEach(Array(lines.rows.enumerated()), id: \.offset) { _, row in
                        HStack {
                            Text(row.0).foregroundStyle(Theme.text)
                            Spacer()
                            Text(row.1).foregroundStyle(Theme.textFaint).multilineTextAlignment(.trailing)
                        }
                    }
                }
                if !probe.isRunning {
                    Button(s.r(probe.phase.isDone ? "probe_again" : "probe_test_channel")) { run() }
                }
            }
            .navigationTitle(s.r("probe_test_channel"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .task { run() }
        .onDisappear { probe.clear() }
    }

    private func run() {
        let s = model.strings
        probe.clear()
        probe.start(player, interlacedWord: s.r("probe_interlaced"), hdrWord: s.r("probe_hdr"))
    }
}

/// "Informação do stream" na folha de opções do player: os números do canal a dar, de segundo a segundo.
struct PhoneStreamInfoSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var stats = StreamStatsMonitor()

    var body: some View {
        let s = model.strings
        NavigationStack {
            List {
                ForEach(stats.rows) { row in
                    HStack {
                        Text(s.r(row.key)).foregroundStyle(Theme.text)
                        Spacer()
                        Text(row.value).foregroundStyle(Theme.textFaint).multilineTextAlignment(.trailing)
                    }
                }
            }
            .navigationTitle(s.r("stream_info"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .task {
            stats.reset()
            while !Task.isCancelled {
                stats.update(model)
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
}

private extension ChannelProbe.Phase {
    var isDone: Bool {
        if case .done = self { return true }
        return false
    }
}

/// "Testar esta lista": a lista e o guia respondem? Começa sozinho ao abrir.
struct PhoneListTestSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let playlist: Playlist
    @State private var probe = ListProbe()

    var body: some View {
        let s = model.strings
        NavigationStack {
            List {
                if probe.running && probe.rows.isEmpty { Text(s.r("probe_list_testing")).foregroundStyle(Theme.textFaint) }
                if probe.portal { Text(s.r("probe_list_portal")).foregroundStyle(Theme.textFaint) }
                ForEach(probe.rows) { row in
                    Section(row.title(s)) {
                        HStack {
                            Label(row.verdictText(s), systemImage: row.good ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                                .foregroundStyle(row.good ? Theme.text : Color.orange)
                            Spacer()
                            Text(row.host).foregroundStyle(Theme.textFaint)
                        }
                        if let (detail, count) = row.detail(s) {
                            HStack {
                                Text(detail).foregroundStyle(Theme.text)
                                Spacer()
                                Text(count).foregroundStyle(Theme.textFaint)
                            }
                        }
                    }
                }
                if probe.noGuide { Text(s.r("probe_list_no_guide")).foregroundStyle(Theme.textFaint) }
            }
            .navigationTitle(s.r("probe_test_list"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(s.r("cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
        .task {
            probe.clear()
            probe.start(playlist, knownGuideURL: playlist.id == model.content?.playlist.id ? model.content?.guideUrl : nil)
        }
        .onDisappear { probe.clear() }
    }
}
