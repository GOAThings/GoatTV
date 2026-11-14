import GoatCore
import SwiftUI

/// As cores do GoatTV (as mesmas do Android).
enum Theme {
    static let sky = Color(red: 0x38 / 255, green: 0xBD / 255, blue: 0xF8 / 255)
    static let indigo = Color(red: 0x81 / 255, green: 0x8C / 255, blue: 0xF8 / 255)
    static let background = Color(red: 0x05 / 255, green: 0x07 / 255, blue: 0x0D / 255)
    static let text = Color(red: 0xF5 / 255, green: 0xF5 / 255, blue: 0xF7 / 255)
    static let textDim = Color(red: 0xEB / 255, green: 0xEB / 255, blue: 0xF5 / 255).opacity(0.85)
    static let textFaint = Color(red: 0xEB / 255, green: 0xEB / 255, blue: 0xF5 / 255).opacity(0.6)
    static let error = Color(red: 1, green: 0x69 / 255, blue: 0x61 / 255)
    /// Âmbar dos avisos (GoatPalette.Warning no Android): a pílula "A reconectar…" do telemóvel.
    static let warning = Color(red: 0xE5 / 255, green: 0xB4 / 255, blue: 0x54 / 255)

    static let accent = LinearGradient(colors: [sky, indigo], startPoint: .leading, endPoint: .trailing)

    /// Tema Sombra: o fundo da linha com foco (sem placa branca nem contorno).
    static let shadowFocus = Color.white.opacity(0.22)
    /// Tema Sombra: as caixas são um cartão escuro, sem vidro.
    static let shadowCard = hex(0x121317).opacity(0.92)
    /// Tema Sombra: a sombra à esquerda do ecrã por baixo das listas e das Definições (some antes do meio).
    static let panelShadow = LinearGradient(
        stops: [
            .init(color: .black.opacity(0.92), location: 0),
            .init(color: .black.opacity(0.85), location: 0.28),
            .init(color: .black.opacity(0.55), location: 0.44),
            .init(color: .clear, location: 0.62),
        ],
        startPoint: .leading, endPoint: .trailing
    )

    static let backdrop = RadialGradient(
        colors: [Color(red: 0x16 / 255, green: 0x20 / 255, blue: 0x48 / 255), background],
        center: .center, startRadius: 0, endRadius: 1100
    )

    /// A cor de destaque escolhida na conta (`accent`).
    static func accent(_ id: String) -> LinearGradient {
        let pair: (Color, Color)
        switch id {
        case "violet": pair = (hex(0xA78BFA), hex(0xF472B6))
        case "mint": pair = (hex(0x34D399), hex(0x22D3EE))
        case "sunset": pair = (hex(0xFB923C), hex(0xF43F5E))
        case "gold": pair = (hex(0xFDE68A), hex(0xF59E0B))
        case "white": pair = (hex(0xFFFFFF), hex(0xCBD5E1))
        default: pair = (sky, indigo)
        }
        return LinearGradient(colors: [pair.0, pair.1], startPoint: .leading, endPoint: .trailing)
    }

    /// O contorno da linha com foco (`outline_color`).
    static func outline(_ id: String, accent accentId: String) -> AnyShapeStyle {
        switch id {
        case "accent": return AnyShapeStyle(accent(accentId))
        case "blue": return AnyShapeStyle(hex(0x7DD3FC))
        case "violet": return AnyShapeStyle(hex(0xC4B5FD))
        case "pink": return AnyShapeStyle(hex(0xF9A8D4))
        case "red": return AnyShapeStyle(hex(0xFCA5A5))
        case "orange": return AnyShapeStyle(hex(0xFDBA74))
        case "yellow": return AnyShapeStyle(hex(0xFDE047))
        case "green": return AnyShapeStyle(hex(0x86EFAC))
        default: return AnyShapeStyle(Color.white)
        }
    }

    static func hex(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

/// Painel de vidro (desfoca o vídeo por trás). A opacidade é a das definições (0 = só desfocado, 100 = escuro).
/// No tema Sombra é um cartão escuro sólido com um contorno muito leve.
struct Glass: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(\.panelLook) private var look
    var radius: CGFloat = 28

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        if look != Prefs.PanelLook.glass {
            content
                .background(Theme.shadowCard, in: shape)
                .overlay(shape.strokeBorder(Color.white.opacity(0.07), lineWidth: 1))
        } else {
            content
                // O material escuro da Apple já escurece: a tinta do Android (#0A0D16) vai mais leve para dar o mesmo.
                .background(Theme.hex(0x0A0D16).opacity(Double(model.prefs.glassOpacity) / 100 * 0.5), in: shape)
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.strokeBorder(Color.white.opacity(0.12), lineWidth: 1))
        }
    }
}

/// O fundo dos painéis laterais (listas, Definições): vidro no tema Vidro; no Sombra nada (ficam por cima da sombra)
/// e o texto ganha uma sombra leve para se ler sobre o vídeo.
struct PanelBackground: ViewModifier {
    @Environment(\.panelLook) private var look

    func body(content: Content) -> some View {
        if look == Prefs.PanelLook.glass {
            content.glass()
        } else {
            content.shadow(color: .black.opacity(0.5), radius: 2, y: 1)
        }
    }
}

extension View {
    func glass(radius: CGFloat = 28) -> some View { modifier(Glass(radius: radius)) }
    func panelBackground() -> some View { modifier(PanelBackground()) }
}

/// Linha de uma lista: sem fundo, com contorno quando tem foco (como no GoatTV Android).
struct RowButtonStyle: ButtonStyle {
    var outline: AnyShapeStyle = AnyShapeStyle(Color.white)
    /// A lista desenha um só destaque que desliza entre as linhas ([slidingHighlight]): a linha não desenha o seu.
    var sliding = false

    func makeBody(configuration: Configuration) -> some View {
        RowButton(configuration: configuration, outline: outline, sliding: sliding)
    }

    private struct RowButton: View {
        let configuration: Configuration
        let outline: AnyShapeStyle
        let sliding: Bool
        @Environment(\.isFocused) private var focused
        @Environment(\.panelLook) private var look

        var body: some View {
            if sliding {
                configuration.label
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .rowFocusAnchor(focused)
            } else {
                plain
            }
        }

        private var plain: some View {
            let soft = look == Prefs.PanelLook.shadow
            return configuration.label
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(focused ? (soft ? Theme.shadowFocus : Color.white.opacity(0.12)) : Color.white.opacity(configuration.isPressed ? 0.2 : 0))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(outline, lineWidth: focused && !soft ? 3 : 0)
                )
                .scaleEffect(focused && look == Prefs.PanelLook.glass ? 1.02 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}

/// Botão normal da app (ecrãs de erro, ativação).
struct PillButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PillButton(configuration: configuration)
    }

    private struct PillButton: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.headline)
                .foregroundStyle(focused ? Color.black : Theme.text)
                .padding(.horizontal, 40)
                .padding(.vertical, 18)
                .background(Capsule().fill(focused ? Color.white : Color.white.opacity(0.12)))
                .scaleEffect(focused ? 1.06 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}

/// Logótipo de um canal (vem da lista; sem logótipo, as iniciais).
struct ChannelLogo: View {
    let url: URL?
    let name: String
    var size = CGSize(width: 96, height: 60)
    /// Por cima do vídeo (HUD, zapping): uma sombra suave com a forma do logótipo, para se separar da imagem.
    var shadow = false

    var body: some View {
        ZStack {
            if let url {
                AsyncImage(url: url, transaction: Transaction(animation: nil)) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFit()
                            .shadow(color: .black.opacity(shadow ? 0.7 : 0), radius: shadow ? 4 : 0, y: shadow ? 1 : 0)
                    default: initials
                    }
                }
            } else {
                initials
            }
        }
        .frame(width: size.width, height: size.height)
    }

    private var initials: some View {
        Text(String(name.split(separator: " ").prefix(2).compactMap(\.first)).uppercased())
            .font(.system(size: size.height * 0.36, weight: .bold))
            .foregroundStyle(Theme.textFaint)
    }
}

/// Barra de progresso do programa.
struct ProgressLine: View {
    let value: Double
    var height: CGFloat = 4
    /// A cor da barra (escura numa linha com a placa branca do seletor branco).
    var tint: Color = .white

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(tint.opacity(0.18))
                Capsule().fill(tint.opacity(0.85)).frame(width: geo.size.width * value)
            }
        }
        .frame(height: height)
    }
}

/// Tamanhos de letra dos painéis (os do Android a dobrar: o tvOS desenha 1920×1080 pontos).
enum Fonts {
    /// Tamanho do texto das definições (pequeno 0,9, normal 1, grande 1,15).
    static var scale: CGFloat = 1

    static var panelTitle: Font { .system(size: 42 * scale, weight: .bold) }
    static var panelSubtitle: Font { .system(size: 23 * scale) }
    static var rowTitle: Font { .system(size: 29 * scale, weight: .semibold) }
    static var rowSubtitle: Font { .system(size: 23 * scale) }
    static var rowValue: Font { .system(size: 25 * scale) }
    static var section: Font { .system(size: 22 * scale, weight: .bold) }
}

/// Linha dos painéis como no Android: com foco fica branca e o texto escuro; a selecionada (ex.: o grupo em que se
/// está) tem um fundo claro translúcido.
struct FillRowStyle: ButtonStyle {
    var selected = false
    /// A lista desenha um só destaque que desliza ([slidingHighlight]): a linha só diz onde está quando tem foco.
    var sliding = false

    func makeBody(configuration: Configuration) -> some View {
        FillRow(configuration: configuration, selected: selected, sliding: sliding)
    }

    private struct FillRow: View {
        let configuration: Configuration
        let selected: Bool
        let sliding: Bool
        @Environment(\.isFocused) private var focused
        @Environment(\.panelLook) private var look

        /// Tema Sombra suave: o foco é um fundo claro e o item atual não leva fundo (pareceria um segundo foco).
        private var soft: Bool { look == Prefs.PanelLook.shadow }

        var body: some View {
            if sliding {
                configuration.label
                    .foregroundStyle(Theme.text)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white.opacity(selected && !focused && !soft ? 0.16 : 0))
                    )
                    .contentShape(Rectangle())
                    .rowFocusAnchor(focused)
            } else {
                plain
            }
        }

        private var plain: some View {
            configuration.label
                .foregroundStyle(focused && !soft ? Color.black : Theme.text)
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(focused ? (soft ? Theme.shadowFocus : Color.white) : Color.white.opacity(selected && !soft ? 0.16 : 0))
                )
                // No tema Sombra as linhas não crescem com o foco: só muda a cor.
                .scaleEffect(focused && look == Prefs.PanelLook.glass ? 1.02 : 1)
                .animation(.easeOut(duration: 0.12), value: focused)
        }
    }
}

/// O conteúdo de uma linha: título, subtítulo opcional por baixo e algo à direita.
struct RowContent<Trailing: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 20) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(Fonts.rowTitle).lineLimit(1)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle).font(Fonts.rowSubtitle).opacity(0.82).lineLimit(1)
                }
            }
            Spacer(minLength: 12)
            trailing()
        }
    }
}

extension RowContent where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// Interruptor desenhado (o Toggle do tvOS ocupa a linha toda).
struct SwitchMark: View {
    let on: Bool

    var body: some View {
        Capsule()
            .fill(on ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.gray.opacity(0.45)))
            .frame(width: 64, height: 36)
            .overlay(alignment: on ? .trailing : .leading) {
                Circle().fill(Color.white).frame(width: 28, height: 28).padding(4).shadow(radius: 2)
            }
            .animation(.easeOut(duration: 0.15), value: on)
    }
}

extension EnvironmentValues {
    /// O aspeto dos painéis (Prefs.panelLook): vidro, sombra, ou sombra com o seletor branco.
    @Entry var panelLook: String = Prefs.PanelLook.glass

    /// Os painéis do player fecham sozinhos sem mexer: cada tecla lá dentro avisa (true enquanto se escreve num
    /// campo, para não fechar a meio).
    @Entry var panelActivity: (_ typing: Bool) -> Void = { _ in }
}

/// Visível (olho) ou escondido (olho riscado, esbatido), como no Android.
struct EyeMark: View {
    let visible: Bool

    var body: some View {
        Image(systemName: visible ? "eye" : "eye.slash")
            .font(.system(size: 28, weight: .semibold))
            .opacity(visible ? 1 : 0.4)
            .frame(width: 64, height: 36)
    }
}

/// Teclado numérico para o PIN parental (4 dígitos).
struct PinPad: View {
    @Environment(AppModel.self) private var model
    @Environment(\.panelActivity) private var panelActivity
    let title: String
    var subtitle: String?
    /// Devolve false se o PIN estiver errado (limpa e avisa).
    let onPin: (String) -> Bool

    @State private var digits = ""
    @State private var wrong = false
    /// Quanto falta para se poder tentar outra vez, depois de demasiadas tentativas.
    @State private var waitMessage: String?
    @FocusState private var focus: String?

    init(title: String, subtitle: String? = nil, onPin: @escaping (String) -> Bool) {
        self.title = title
        self.subtitle = subtitle
        self.onPin = onPin
    }

    var body: some View {
        VStack(spacing: 28) {
            Text(title).font(Fonts.rowTitle)
            if let subtitle { Text(subtitle).font(Fonts.rowSubtitle).foregroundStyle(Theme.textDim) }
            HStack(spacing: 22) {
                ForEach(0..<4, id: \.self) { i in
                    Circle()
                        .fill(i < digits.count ? Color.white : Color.white.opacity(0.18))
                        .frame(width: 22, height: 22)
                }
            }
            .padding(.vertical, 8)
            if wrong {
                Text(waitMessage ?? "✕").font(Fonts.rowTitle).foregroundStyle(Theme.error)
            }
            VStack(spacing: 14) {
                ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["", "0", "⌫"]], id: \.self) { row in
                    HStack(spacing: 14) {
                        ForEach(row, id: \.self) { key in
                            if key.isEmpty {
                                Color.clear.frame(width: 110, height: 80)
                            } else {
                                Button { press(key) } label: {
                                    Text(key).font(.system(size: 34, weight: .semibold)).frame(width: 110, height: 80)
                                }
                                .buttonStyle(KeyButtonStyle())
                                .focused($focus, equals: key)
                            }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity)
        .onChange(of: digits) { _, _ in panelActivity(false) }
        .task {
            try? await Task.sleep(for: .milliseconds(60))
            focus = "5"
        }
    }

    private func press(_ key: String) {
        wrong = false
        if key == "⌫" {
            if !digits.isEmpty { digits.removeLast() }
            return
        }
        guard digits.count < 4 else { return }
        digits += key
        if digits.count == 4 {
            if !onPin(digits) {
                wrong = true
                waitMessage = model.pinWaitMessage()
                digits = ""
            }
        }
    }

    private struct KeyButtonStyle: ButtonStyle {
        func makeBody(configuration: Configuration) -> some View { Key(configuration: configuration) }

        private struct Key: View {
            let configuration: Configuration
            @Environment(\.isFocused) private var focused

            var body: some View {
                configuration.label
                    .foregroundStyle(focused ? Color.black : Theme.text)
                    .background(RoundedRectangle(cornerRadius: 18).fill(focused ? Color.white : Color.white.opacity(0.1)))
                    .scaleEffect(focused ? 1.06 : 1)
                    .animation(.easeOut(duration: 0.1), value: focused)
            }
        }
    }
}

// MARK: Destaque que desliza

/// Onde está a linha com foco de uma lista (só a que tem foco publica).
struct RowFocusKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) { value = value ?? nextValue() }
}

/// Como no Android (e na Apple TV): um só destaque que desliza de linha em linha em vez de cada linha acender e apagar.
private let highlightSpring = Animation.spring(response: 0.28, dampingFraction: 1)

struct SlidingHighlight: ViewModifier {
    /// A placa branca dos grupos (pintada em "diferença": o texto por baixo fica escuro); senão contorno e véu.
    let plate: Bool
    let outline: AnyShapeStyle
    let radius: CGFloat
    @Environment(\.panelLook) private var look

    func body(content: Content) -> some View {
        if look == Prefs.PanelLook.shadow {
            // Tema Sombra suave: só um fundo claro por baixo da linha, sem placa nem contorno.
            content
                .backgroundPreferenceValue(RowFocusKey.self) { anchor in
                    GeometryReader { geo in
                        if let anchor {
                            let r = geo[anchor]
                            RoundedRectangle(cornerRadius: radius, style: .continuous)
                                .fill(Theme.shadowFocus)
                                .frame(width: r.width, height: r.height)
                                .offset(x: r.minX, y: r.minY)
                                .animation(highlightSpring, value: r)
                        }
                    }
                    .allowsHitTesting(false)
                }
        } else if plate {
            content
                .overlayPreferenceValue(RowFocusKey.self) { anchor in
                    GeometryReader { geo in
                        if let anchor {
                            let r = geo[anchor]
                            RoundedRectangle(cornerRadius: radius, style: .continuous)
                                .fill(Color.white)
                                .frame(width: r.width, height: r.height)
                                .offset(x: r.minX, y: r.minY)
                                .animation(highlightSpring, value: r)
                        }
                    }
                    .blendMode(.difference)
                    .allowsHitTesting(false)
                }
                .compositingGroup()
        } else {
            content
                .backgroundPreferenceValue(RowFocusKey.self) { anchor in
                    GeometryReader { geo in
                        if let anchor {
                            let r = geo[anchor]
                            RoundedRectangle(cornerRadius: radius, style: .continuous)
                                .fill(Color.white.opacity(0.12))
                                .frame(width: r.width, height: r.height)
                                .offset(x: r.minX, y: r.minY)
                                .animation(highlightSpring, value: r)
                        }
                    }
                    .allowsHitTesting(false)
                }
                .overlayPreferenceValue(RowFocusKey.self) { anchor in
                    GeometryReader { geo in
                        if let anchor {
                            let r = geo[anchor]
                            RoundedRectangle(cornerRadius: radius, style: .continuous)
                                .strokeBorder(outline, lineWidth: 3)
                                .frame(width: r.width, height: r.height)
                                .offset(x: r.minX, y: r.minY)
                                .animation(highlightSpring, value: r)
                        }
                    }
                    .allowsHitTesting(false)
                }
        }
    }
}

extension View {
    /// Esta linha é a do destaque enquanto tiver foco.
    func rowFocusAnchor(_ focused: Bool) -> some View {
        anchorPreference(key: RowFocusKey.self, value: .bounds) { focused ? $0 : nil }
    }

    /// Desenha o destaque da lista (ver [SlidingHighlight]); as linhas usam o estilo com `sliding: true`.
    func slidingHighlight(plate: Bool = false, outline: AnyShapeStyle = AnyShapeStyle(Color.white), radius: CGFloat = 18) -> some View {
        modifier(SlidingHighlight(plate: plate, outline: outline, radius: radius))
    }
}
