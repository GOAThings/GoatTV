import GoatCore
import SwiftUI

/// O teletexto no Apple TV, por cima do vídeo (Canal atual → Teletexto). O comando não tem números nem cores: ↑/↓
/// mudam de página, ←/→ de subpágina, OK abre o teclado no ecrã (os algarismos, as quatro cores, o fundo e o revelar;
/// as setas andam pelas teclas e OK carrega), Play/Pausa troca o fundo, Voltar fecha o teclado e depois o teletexto.
struct TeletextTv: View {
    @Environment(AppModel.self) private var model
    let onClose: () -> Void
    @State private var tt = TeletextModel()
    @FocusState private var focused: Bool

    var body: some View {
        let s = model.strings
        let frame = tt.frame
        ZStack(alignment: .bottomTrailing) {
            (tt.transparent || frame?.boxed == true ? Color.clear : Color.black)
                .ignoresSafeArea()
            // É este botão invisível que tem o foco e recebe as teclas todas.
            Button(action: select) {
                Color.black.opacity(0.001).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .buttonStyle(InvisibleButtonStyle())
            .focused($focused)
            .onMoveCommand(perform: move)
            .onPlayPauseCommand { tt.toggleBackground() }
            .onExitCommand(perform: back)
            .ignoresSafeArea()

            TeletextCanvas(model: tt, waiting: s.r("teletext_waiting", "\(tt.page)"))
                .padding(.vertical, 30)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            VStack(alignment: .trailing, spacing: 12) {
                if let frame, frame.subpages > 1 {
                    chip("\(s.r("teletext_subpage")) \(frame.subpage + 1)/\(frame.subpages)")
                }
                if let frame {
                    HStack(spacing: 6) {
                        ForEach(0..<4, id: \.self) { k in
                            let link = frame.links[k]
                            Text(link > 0 ? "\(link)" : "   ")
                                .font(.system(size: 20, weight: .bold).monospacedDigit())
                                .foregroundStyle(k == 2 ? Color.black : Color.white)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(teletextKeyColours[k].opacity(link > 0 ? 0.9 : 0.25), in: RoundedRectangle(cornerRadius: 8))
                        }
                    }
                }
                if tt.padOpen { pad } else { chip(s.r("teletext_hint_tv")) }
            }
            .padding(40)
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
        .task {
            while !Task.isCancelled {
                tt.poll(model.player.teletextPages)
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
        .task {
            // O foco vem do painel das Definições, que acabou de fechar.
            try? await Task.sleep(for: .milliseconds(80))
            focused = true
        }
        .onDisappear { tt.detach() }
    }

    private func chip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 22))
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(Color.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 10))
    }

    /// O teclado no ecrã: 4×4 teclas, a do cursor com uma orla.
    private var pad: some View {
        VStack(spacing: 8) {
            ForEach(0..<(TeletextModel.pad.count / TeletextModel.padColumns), id: \.self) { row in
                HStack(spacing: 8) {
                    ForEach(0..<TeletextModel.padColumns, id: \.self) { column in
                        padKey(row * TeletextModel.padColumns + column)
                    }
                }
            }
        }
        .padding(12)
        .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
    }

    private func padKey(_ index: Int) -> some View {
        let key = TeletextModel.pad[index]
        let fill: Color = switch key {
        case TeletextModel.red, TeletextModel.green, TeletextModel.yellow, TeletextModel.blue: teletextKeyColours[key - TeletextModel.red]
        case TeletextModel.background: Color.white.opacity(tt.transparent ? 0.35 : 0.12)
        case TeletextModel.reveal: Color.white.opacity(tt.revealed ? 0.35 : 0.12)
        default: Color.white.opacity(0.12)
        }
        let label = switch key {
        case 0...9: "\(key)"
        case TeletextModel.background: "◐"
        case TeletextModel.reveal: "?"
        default: ""
        }
        return Text(label)
            .font(.system(size: 26, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 60, height: 60)
            .background(fill, in: RoundedRectangle(cornerRadius: 10))
            .overlay {
                if index == tt.padIndex { RoundedRectangle(cornerRadius: 10).strokeBorder(Color.white, lineWidth: 4) }
            }
    }

    private func select() {
        if tt.padOpen { tt.press(tt.padIndex) } else { tt.padOpen = true }
    }

    private func move(_ direction: MoveCommandDirection) {
        if tt.padOpen {
            switch direction {
            case .up: tt.movePad(dx: 0, dy: -1)
            case .down: tt.movePad(dx: 0, dy: 1)
            case .left: tt.movePad(dx: -1, dy: 0)
            case .right: tt.movePad(dx: 1, dy: 0)
            @unknown default: break
            }
            return
        }
        switch direction {
        case .up: tt.step(1)
        case .down: tt.step(-1)
        case .left: tt.sub(-1)
        case .right: tt.sub(1)
        @unknown default: break
        }
    }

    private func back() {
        if tt.padOpen {
            tt.padOpen = false
        } else if !tt.typed.isEmpty {
            tt.clearTyped()
        } else {
            onClose()
        }
    }
}
