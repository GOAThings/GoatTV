import GoatCore
import SwiftUI
import UIKit

/// Uma linha da lista de canais: número, logótipo, nome e o programa a dar (com a barra de progresso).
struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let now: Int64
    let guideVersion: Int

    var body: some View {
        let prefs = model.prefs
        let playing = model.current?.key == channel.key
        let programme = prefs.epgInList ? Guide.nowNext(model.programmes(for: channel), now: now).now : nil
        HStack(spacing: 18) {
            if prefs.showNumbers {
                Text("\(channel.num)")
                    .font(Fonts.rowValue.monospacedDigit())
                    .foregroundStyle(Theme.textFaint)
                    .frame(width: 64, alignment: .trailing)
            }
            if prefs.showLogos {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 84, height: 52))
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(channel.name).font(Fonts.rowTitle).lineLimit(1)
                if prefs.epgInList {
                    Text(programme?.title ?? (playing ? model.strings.watchingNow : model.strings.noGuideInfo))
                        .font(Fonts.rowSubtitle)
                        .foregroundStyle(Theme.textDim)
                        .lineLimit(1)
                    ProgressLine(value: programme?.progress(now: now) ?? 0, height: 3)
                        .opacity(programme == nil ? 0 : 1)
                }
            }
            Spacer(minLength: 0)
            if model.isFavorite(channel) {
                Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.textFaint)
            }
            if playing {
                Circle().fill(Theme.sky).frame(width: 12, height: 12)
            }
        }
        .frame(height: prefs.epgInList ? 76 : 52)
    }
}

/// Os programas do canal com foco na lista (→), a partir do que está a dar.
struct ChannelGuidePanel: View {
    @Environment(AppModel.self) private var model
    let channel: Channel
    let now: Int64

    var body: some View {
        let _ = model.guideVersion
        let list = model.programmes(for: channel).filter { $0.endMs > now }.prefix(14)
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 16) {
                ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 84, height: 52))
                Text(channel.name).font(.title3.bold()).lineLimit(1)
            }
            .padding(.bottom, 8)
            if list.isEmpty {
                Text(model.strings.noGuideInfo).foregroundStyle(Theme.textFaint)
            }
            ForEach(Array(list.enumerated()), id: \.offset) { _, entry in
                let on = entry.isOn(at: now)
                HStack(alignment: .firstTextBaseline, spacing: 18) {
                    Text(hhmm(entry.start)).font(.callout.monospacedDigit()).foregroundStyle(on ? Theme.text : Theme.textFaint)
                        .frame(width: 100, alignment: .leading)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(entry.title).font(on ? .headline : .callout).lineLimit(1)
                        if on { ProgressLine(value: entry.progress(now: now), height: 3) }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(30)
        .frame(width: 620)
        .frame(maxHeight: .infinity, alignment: .top)
        .glass()
        .padding(.leading, 20)
    }
}

func hhmm(_ date: Date) -> String {
    date.formatted(date: .omitted, time: .shortened)
}

/// O vídeo do VLC dentro do SwiftUI.
struct VideoSurface: UIViewRepresentable {
    let view: UIView

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        view.frame = container.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(view)
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Botão sem aspeto nenhum (o ecrã do vídeo).
struct InvisibleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}
