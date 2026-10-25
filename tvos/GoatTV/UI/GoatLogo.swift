import SwiftUI

/*
 * O logo GOAT TV animado, igual ao do GoatTV Android: feito com as camadas do logo original (1376×768 px): o fundo,
 * as letras G-O-A-T e o T e o V grandes, cada uma com o seu brilho. As letras são "luz" sobre o fundo e desenham-se a
 * somar (plusLighter). As máscaras *_core são só a forma do T e do V, para a faixa de luz passar só pelas letras.
 */

private let frameW: CGFloat = 1376
private let frameH: CGFloat = 768
/// Parte da largura do ecrã ocupada pelo logo original.
private let frameFraction: CGFloat = 0.72
/// Onde acaba o TV (no logo original): a linha de progresso e o texto ficam por baixo.
private let tvBottom: CGFloat = 543
/// A abertura acaba aqui (em segundos): o logo fica parado, igual ao do ecrã de carregamento.
let introDuration: Double = 2.2
/// Quanto tempo a luz da linha de progresso leva a ir de uma ponta à outra.
private let progressLoop: Double = 1.6

/// [intro]: a abertura da app (as letras abrem a partir do centro, o T e o V sobem e acendem, passa uma luz pelo TV).
/// Senão, o ecrã de carregamento: a luz a passar de vez em quando, uma linha de progresso e [message] por baixo.
struct GoatLogoView: View {
    let intro: Bool
    var message: String?
    var onDone: (() -> Void)?

    @State private var start = Date()

    init(intro: Bool, message: String? = nil, onDone: (() -> Void)? = nil) {
        self.intro = intro
        self.message = message
        self.onDone = onDone
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .top) {
                TimelineView(.animation) { timeline in
                    let t = timeline.date.timeIntervalSince(start)
                    Canvas { context, size in
                        GoatLogoView.draw(&context, size: size, t: t, intro: intro)
                    }
                }
                if let message, !message.isEmpty, !intro {
                    let scale = geo.size.width * frameFraction / frameW
                    Text(message)
                        .font(.system(size: 32, weight: .medium))
                        .foregroundStyle(Theme.textDim)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .padding(.horizontal, 96)
                        .padding(.top, (geo.size.height - frameH * scale) / 2 + (tvBottom + 70) * scale + 56)
                }
            }
        }
        .ignoresSafeArea()
        .task {
            guard intro else { return }
            try? await Task.sleep(for: .seconds(introDuration))
            onDone?()
        }
    }

    // MARK: Desenho

    private struct Layer {
        let name: String
        let x: CGFloat
        let y: CGFloat
    }

    // Posições no logo original (as do Android).
    private static let goat = [Layer(name: "goattv_g", x: 424, y: 179), Layer(name: "goattv_o", x: 551, y: 179),
                               Layer(name: "goattv_a", x: 678, y: 179), Layer(name: "goattv_t", x: 805, y: 179)]
    private static let tvT = Layer(name: "goattv_tv_t", x: 423, y: 304)
    private static let tvV = Layer(name: "goattv_tv_v", x: 664, y: 304)
    private static let coreT = Layer(name: "goattv_tv_t_core", x: 423, y: 304)
    private static let coreV = Layer(name: "goattv_tv_v_core", x: 664, y: 304)

    private static func clamp01(_ v: Double) -> Double { min(1, max(0, v)) }
    private static func easeOut(_ t: Double) -> Double { let c = clamp01(t); return 1 - (1 - c) * (1 - c) * (1 - c) }
    private static func easeInOut(_ t: Double) -> Double { let c = clamp01(t); return c * c * (3 - 2 * c) }

    static func draw(_ context: inout GraphicsContext, size: CGSize, t: Double, intro: Bool) {
        let base = context
        base.fill(Path(CGRect(origin: .zero, size: size)), with: .color(.black))

        // Fundo: o do logo, a encher o ecrã (na abertura, acende a partir do preto).
        let bg = base.resolve(Image("goattv_bg"))
        if bg.size.width > 0 {
            let s = max(size.width / bg.size.width, size.height / bg.size.height)
            let w = bg.size.width * s
            let h = bg.size.height * s
            var c = base
            c.opacity = intro ? easeInOut(t / 0.35) : 1
            c.draw(bg, in: CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h))
        }

        let scale = size.width * frameFraction / frameW
        let ox = (size.width - frameW * scale) / 2
        let oy = (size.height - frameH * scale) / 2

        func rect(_ l: Layer, _ img: GraphicsContext.ResolvedImage, dx: CGFloat = 0, dy: CGFloat = 0) -> CGRect {
            CGRect(x: ox + (l.x + dx) * scale, y: oy + (l.y + dy) * scale, width: img.size.width * scale, height: img.size.height * scale)
        }

        // A soma de várias passagens dá o pico de brilho (k > 1) ao acender.
        func layer(_ l: Layer, dx: CGFloat = 0, dy: CGFloat = 0, k: Double = 1) {
            let img = base.resolve(Image(l.name))
            var left = k
            while left > 0.001 {
                var c = base
                c.blendMode = .plusLighter
                c.opacity = min(left, 1)
                c.draw(img, in: rect(l, img, dx: dx, dy: dy))
                left -= 1
            }
        }

        // G-O-A-T: na abertura, abrem a partir do centro, uma de cada vez.
        let centre = frameW / 2
        for (i, l) in goat.enumerated() {
            if intro {
                let e = easeOut((t - 0.25 - Double(i) * 0.07) / 0.7)
                let width = base.resolve(Image(l.name)).size.width
                let letterCentre = l.x + width / 2
                let x = centre + (letterCentre - centre) * (0.6 + 0.4 * e) - width / 2
                layer(l, dx: x - l.x, k: e)
            } else {
                layer(l)
            }
        }
        // T e V: sobem e acendem, um a seguir ao outro, com um pico de brilho.
        for (j, l) in [tvT, tvV].enumerated() {
            if intro {
                let startAt = 0.55 + Double(j) * 0.15
                let e = easeOut((t - startAt) / 0.6)
                let glow = 1 + 0.35 * sin(Double.pi * clamp01((t - startAt) / 0.9))
                layer(l, dy: 30 * (1 - e), k: e * glow)
            } else {
                layer(l)
            }
        }

        if intro {
            if t >= 1.25, t <= 1.95 { shine(base, scale: scale, ox: ox, oy: oy, p: (t - 1.25) / 0.7, strength: 0.8) }
        } else {
            let p = t.truncatingRemainder(dividingBy: 1.8) / 1.8
            if p < 0.6 { shine(base, scale: scale, ox: ox, oy: oy, p: p / 0.6, strength: 0.65) }
            progress(base, scale: scale, ox: ox, oy: oy, t: t)
        }
    }

    /// Uma faixa de luz inclinada que atravessa o T e o V ([p] de 0 a 1), só por cima das letras.
    private static func shine(_ base: GraphicsContext, scale: CGFloat, ox: CGFloat, oy: CGFloat, p: Double, strength: Double) {
        let t = base.resolve(Image(coreT.name))
        let v = base.resolve(Image(coreV.name))
        let left = ox + coreT.x * scale
        let right = ox + (coreV.x + v.size.width) * scale
        let top = oy + coreT.y * scale
        let bottom = oy + (coreT.y + max(t.size.height, v.size.height)) * scale
        let span = right - left
        let centre = left - 0.2 * span + CGFloat(p) * 1.4 * span
        let half = span * 0.12
        // Linhas de igual luz inclinadas: x + 0,35·y constante.
        let dir = CGPoint(x: 1 / 1.1225, y: 0.35 / 1.1225)
        let startPoint = CGPoint(x: centre - half, y: top)
        let endPoint = CGPoint(x: startPoint.x + dir.x * 2 * half, y: startPoint.y + dir.y * 2 * half)
        let light = Color(red: 0.85, green: 0.95, blue: 1)
        let gradient = Gradient(stops: [
            .init(color: light.opacity(0), location: 0),
            .init(color: light.opacity(strength * 0.3), location: 0.3),
            .init(color: light.opacity(strength), location: 0.5),
            .init(color: light.opacity(strength * 0.3), location: 0.7),
            .init(color: light.opacity(0), location: 1),
        ])
        var c = base
        c.blendMode = .plusLighter
        c.drawLayer { layer in
            layer.draw(t, in: CGRect(x: ox + coreT.x * scale, y: oy + coreT.y * scale, width: t.size.width * scale, height: t.size.height * scale))
            layer.draw(v, in: CGRect(x: ox + coreV.x * scale, y: oy + coreV.y * scale, width: v.size.width * scale, height: v.size.height * scale))
            layer.blendMode = .sourceIn
            layer.fill(Path(CGRect(x: left, y: top, width: span, height: bottom - top)),
                       with: .linearGradient(gradient, startPoint: startPoint, endPoint: endPoint))
        }
    }

    /// Linha de progresso fina por baixo do TV: uma luz que anda sempre para a direita e volta a entrar pelo início.
    private static func progress(_ base: GraphicsContext, scale: CGFloat, ox: CGFloat, oy: CGFloat, t: Double) {
        let wordLeft: CGFloat = 468
        let wordRight: CGFloat = 907
        let x0 = ox + (wordLeft + (wordRight - wordLeft) * 0.2) * scale
        let x1 = ox + (wordRight - (wordRight - wordLeft) * 0.2) * scale
        let y = oy + (tvBottom + 70) * scale
        let h = max(2, 4 * scale)
        let span = x1 - x0
        let bar = Path(CGRect(x: x0, y: y, width: span, height: h))
        let light = Color(red: 0.45, green: 0.8, blue: 1)
        let w = span * 0.3
        let a = x0 + span * CGFloat(t.truncatingRemainder(dividingBy: progressLoop) / progressLoop)
        var c = base
        c.blendMode = .plusLighter
        c.drawLayer { layer in
            layer.fill(bar, with: .color(Color.white.opacity(0.10)))
            for centre in [a, a - span] {
                layer.fill(bar, with: .linearGradient(Gradient(colors: [.clear, light, .clear]),
                                                      startPoint: CGPoint(x: centre - w, y: y), endPoint: CGPoint(x: centre + w, y: y)))
            }
            // As pontas desvanecem: a luz entra e sai sem se ver o corte.
            layer.blendMode = .destinationIn
            layer.fill(bar, with: .linearGradient(
                Gradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.2),
                                 .init(color: .black, location: 0.8), .init(color: .clear, location: 1)]),
                startPoint: CGPoint(x: x0, y: y), endPoint: CGPoint(x: x1, y: y)))
        }
    }
}
