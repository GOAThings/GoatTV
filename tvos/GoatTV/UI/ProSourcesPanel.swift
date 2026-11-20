import GoatCore
import SwiftUI

/// Modo TV Pro (como no Android: ProSources.kt): as fontes do canal a ver (o mesmo canal nas várias listas), da melhor
/// para a pior. É a ordem em que se tentam quando uma falha; OK numa abre-a já (e as seguintes ficam como failovers dela).
struct ProSourcesPanel: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let onPick: (Int) -> Void

    @FocusState private var focus: Int?

    var body: some View {
        let s = model.strings
        let sources = model.proSources(channel)
        VStack(alignment: .leading, spacing: 16) {
            Text("\(channel.name) · \(s.r("pro_sources"))").font(Fonts.panelTitle).lineLimit(1).padding(.horizontal, 16)
            Text(s.r("pro_sources_intro")).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim).padding(.horizontal, 16)
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(sources) { src in
                        Button {
                            onPick(src.index)
                        } label: {
                            HStack(spacing: 18) {
                                Text("\(src.index + 1)").font(Fonts.rowValue.monospacedDigit()).opacity(0.6).frame(width: 40, alignment: .leading)
                                RowContent(title: [AppModel.qualityLabel(src.quality), src.playlistName].compactMap { $0 }.joined(separator: " · "),
                                           subtitle: [src.channelName, src.history].compactMap { $0 }.joined(separator: " · ")) {
                                    if src.down {
                                        Text(s.r("pro_source_down")).font(Fonts.rowSubtitle).foregroundStyle(Color(red: 1, green: 0.42, blue: 0.42))
                                    }
                                }
                            }
                        }
                        .buttonStyle(FillRowStyle())
                        .focused($focus, equals: src.index)
                    }
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 6)
            }
        }
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .frame(width: model.panelPoints(model.prefs.panelWidth + 60))
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .focusSection()
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = sources.first?.index
        }
    }
}
