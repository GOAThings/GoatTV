import GoatCore
import SwiftUI
import UIKit

/// Uma linha da lista de canais: número, logótipo, nome e o programa a dar (com a barra de progresso).
struct ChannelRow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.isFocused) private var focused
    @Environment(\.panelLook) private var look
    let channel: Channel
    let now: Int64
    let guideVersion: Int

    var body: some View {
        let prefs = model.prefs
        // Seletor branco: a linha com foco tem a placa branca, e o texto e a barra ficam escuros.
        let onPlate = focused && (look == Prefs.PanelLook.shadowWhite || look == Prefs.PanelLook.solid)
        let playing = model.current?.key == channel.key
        let programme = prefs.epgInList ? Guide.nowNext(model.programmes(for: channel), now: now).now : nil
        HStack(spacing: 18) {
            if prefs.showNumbers {
                Text("\(channel.num)")
                    .font(Fonts.rowValue.monospacedDigit())
                    .foregroundStyle(onPlate ? Color.black.opacity(0.6) : Theme.textFaint)
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
                        .foregroundStyle(onPlate ? Color.black.opacity(0.65) : Theme.textDim)
                        .lineLimit(1)
                    ProgressLine(value: programme?.progress(now: now) ?? 0, height: 3, tint: onPlate ? .black : .white)
                        .opacity(programme == nil ? 0 : 1)
                }
            }
            Spacer(minLength: 0)
            if model.isFavorite(channel) {
                Image(systemName: "star.fill").font(.caption).foregroundStyle(onPlate ? Color.black.opacity(0.6) : Theme.textFaint)
            }
            if playing {
                Circle().fill(Theme.sky).frame(width: 12, height: 12)
            }
            // Modo TV Pro: a qualidade da fonte que vai dar, como na box ("FHD").
            if let quality = model.proQualityLabel(channel) {
                Text(quality)
                    .font(.system(size: 17 * Fonts.scale, weight: .semibold))
                    .foregroundStyle(onPlate ? Color.black.opacity(0.7) : Theme.textDim)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(onPlate ? Color.black.opacity(0.4) : Color.white.opacity(0.35), lineWidth: 1.5))
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
                if model.prefs.showLogos {
                    ChannelLogo(url: model.logo(for: channel), name: channel.name, size: CGSize(width: 84, height: 52))
                }
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

/// Os passos da velocidade no catch-up e atrás do direto (os mesmos do Android): ↑ ↓ na TV, o botão no iPhone.
enum PlaybackSpeed {
    static let steps: [Float] = [1, 1.25, 1.5, 2]
    static func faster(_ speed: Float) -> Float { steps.first { $0 > speed } ?? steps.last! }
    static func slower(_ speed: Float) -> Float { steps.last { $0 < speed } ?? steps.first! }
    /// Dá a volta: 1× → 1,25× → 1,5× → 2× → 1×.
    static func next(_ speed: Float) -> Float { steps.first { $0 > speed } ?? steps.first! }
}

/// "1,25×" (com a vírgula ou o ponto da língua).
func speedLabel(_ speed: Float) -> String {
    Double(speed).formatted(.number.precision(.fractionLength(0...2))) + "×"
}

/// "1 dia", "1½ dias", "7 dias" (as janelas do guia, em horas; como no Android).
func guideDays(_ s: Strings, _ hours: Int) -> String {
    hours == 24 ? s.r("guide_one_day") : s.r("guide_days", hours % 24 == 0 ? "\(hours / 24)" : "\(hours / 24)½")
}

/// "−1:05": quanto a pausa do direto vai atrás do direto (a cápsula no canto, a barra, o botão do direto no iPhone).
func behindLabel(_ ms: Int64) -> String {
    let total = max(0, ms / 1000)
    let h = total / 3600
    let m = (total % 3600) / 60
    let sec = total % 60
    return "−" + (h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec))
}

/// O vídeo do VLC dentro do SwiftUI.
struct VideoSurface: UIViewRepresentable {
    let view: UIView

    func makeUIView(context: Context) -> UIView {
        let container = VideoContainer()
        container.backgroundColor = .black
        container.video = view
        container.attach()
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        (uiView as? VideoContainer)?.attach()
    }
}

/// Segura a vista do vídeo, que é sempre a mesma (o ecrã grande e o mini-leitor do telemóvel passam-na de um para o
/// outro). Só lhe dá tamanho quando já tem: a 0×0, enquanto mudava de sítio, o VLC deixava de desenhar.
final class VideoContainer: UIView {
    weak var video: UIView?

    func attach() {
        guard let video, video.superview !== self else { return }
        video.autoresizingMask = []
        addSubview(video)
        setNeedsLayout()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { attach() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let video, video.superview === self, bounds.width > 0, bounds.height > 0 else { return }
        video.frame = bounds
    }
}

/// Botão sem aspeto nenhum (o ecrã do vídeo).
struct InvisibleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}

/// O ecrã de um canal só de som (uma rádio), no lugar da imagem preta: o logótipo grande, o nome, o programa a dar (se
/// o guia o tiver) e umas barras de equalizador animadas (paradas em pausa), como no Android (RadioView.kt).
/// [compact]: o iPhone ao alto (menos espaço).
struct RadioView: View {
    let channel: Channel
    let logo: URL?
    let programme: String?
    /// O que se escreve sem programa ("Rádio").
    let label: String
    let playing: Bool
    let accent: String
    var compact = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [Theme.sky.opacity(0.2), Theme.background], startPoint: .top, endPoint: .bottom)
            VStack(spacing: compact ? 8 : 22) {
                ChannelLogo(url: logo, name: channel.name, size: compact ? CGSize(width: 112, height: 70) : CGSize(width: 320, height: 200))
                Text(channel.name)
                    .font(.system(size: compact ? 17 : 46, weight: .semibold))
                    .foregroundStyle(Theme.text)
                    .lineLimit(1)
                Text(programme ?? label)
                    .font(.system(size: compact ? 14 : 30))
                    .foregroundStyle(Theme.textDim)
                    .lineLimit(1)
                EqualizerBars(playing: playing, accent: accent)
                    .frame(width: compact ? 56 : 150, height: compact ? 22 : 60)
                    .padding(.top, compact ? 2 : 10)
            }
            .padding(compact ? 12 : 60)
        }
    }
}

/// Barras que sobem e descem (barato: um relógio só, cada barra com a sua fase); em pausa ficam baixas e paradas.
private struct EqualizerBars: View {
    let playing: Bool
    let accent: String
    /// As voltas de cada barra por ciclo (inteiras, para o ciclo fechar sem saltos).
    private static let speeds: [Double] = [2, 3, 1, 4, 2]

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !playing)) { context in
            let t = context.date.timeIntervalSinceReferenceDate / 1.6
            GeometryReader { geo in
                let n = Self.speeds.count
                let gap = geo.size.width / CGFloat(n * 3)
                let w = (geo.size.width - gap * CGFloat(n - 1)) / CGFloat(n)
                HStack(alignment: .bottom, spacing: gap) {
                    ForEach(0..<n, id: \.self) { i in
                        RoundedRectangle(cornerRadius: w / 3)
                            .fill(Theme.accent(accent))
                            .frame(width: w, height: geo.size.height * Self.level(i, t: t, playing: playing))
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .bottom)
            }
        }
    }

    /// A altura da barra [i] (0…1) ao tempo [t].
    private static func level(_ i: Int, t: Double, playing: Bool) -> CGFloat {
        guard playing else { return 0.15 }
        let wave = (sin((t * speeds[i] + Double(i) * 0.37) * 2 * Double.pi) + 1) / 2
        return CGFloat(0.25 + 0.75 * wave)
    }
}

/// "Ainda estás a ver?" e o ecrã de parado (definição idle_stop, ver AppModel.noteInput): o aviso tem a contagem de 60 s e
/// fecha-se com qualquer tecla ou toque; depois de parar, "Ver outra vez" volta a abrir o canal. Igual no Apple TV e no iPhone.
struct IdleOverlay: View {
    @Environment(AppModel.self) private var model
    @FocusState private var focused: Bool

    var body: some View {
        let s = model.strings
        switch model.idlePhase {
        case .watching:
            EmptyView()
        case .prompt:
            card(title: s.r("idle_prompt_title"), text: s.r("idle_prompt_sub", "\(model.idleSecondsLeft)"),
                 button: s.r("idle_keep"), scrim: 0.6) { model.noteInput() }
        case .stopped:
            card(title: s.r("idle_stopped_title"), text: nil, button: s.r("idle_watch_again"), scrim: 0.95) { model.watchAgain() }
        }
    }

    private func card(title: String, text: String?, button: String, scrim: Double, action: @escaping () -> Void) -> some View {
        ZStack {
            Color.black.opacity(scrim).ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "moon.zzz.fill").font(.largeTitle).foregroundStyle(.secondary)
                Text(title).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                if let text {
                    Text(text).font(.headline).foregroundStyle(.secondary).monospacedDigit()
                }
                Button(button, action: action)
                    .focused($focused)
                    .padding(.top, 12)
            }
            .padding(40)
            .frame(maxWidth: 800)
        }
        .onAppear { focused = true }
    }
}
