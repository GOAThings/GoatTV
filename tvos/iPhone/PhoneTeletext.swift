import GoatCore
import SwiftUI

/// O teletexto no iPhone (nas opções do player), por baixo do vídeo: o número da página a escrever, a página (deslizar
/// para o lado muda de página), as setas das páginas e das subpáginas, as quatro cores, o fundo e o revelar.
struct PhoneTeletextSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let player: PlayerEngine
    @State private var tt = TeletextModel()
    @State private var field = ""
    @FocusState private var typing: Bool

    var body: some View {
        let s = model.strings
        let frame = tt.frame
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button { dismiss() } label: {
                    Image(systemName: "xmark").font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                        .frame(width: 36, height: 36).background(Color.white.opacity(0.12), in: Circle())
                }
                Text(s.r("teletext")).font(.headline).foregroundStyle(.white)
                Spacer()
                Text(s.r("teletext_page")).font(.subheadline).foregroundStyle(.white.opacity(0.7))
                TextField("\(tt.page)", text: $field)
                    .keyboardType(.numberPad)
                    .focused($typing)
                    .multilineTextAlignment(.center)
                    .font(.system(size: 18, weight: .bold).monospacedDigit())
                    .foregroundStyle(.white)
                    .frame(width: 64)
                    .padding(.vertical, 6)
                    .background(Color.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                    .onChange(of: field) { _, value in
                        let digits = String(value.filter(\.isNumber).prefix(3))
                        if digits != value { field = digits }
                        if digits.count == 3 {
                            if let n = Int(digits), (100...899).contains(n) { tt.go(n) }
                            field = ""
                            typing = false
                        }
                    }
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)

            TeletextCanvas(model: tt, waiting: s.r("teletext_waiting", "\(tt.page)"))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 30).onEnded { g in
                    // Para a esquerda: a página seguinte (como virar a folha).
                    guard abs(g.translation.width) > abs(g.translation.height), abs(g.translation.width) > 60 else { return }
                    tt.step(g.translation.width < 0 ? 1 : -1)
                })

            HStack(spacing: 10) {
                arrow("chevron.left") { tt.step(-1) }
                Text("\(tt.page)").font(.system(size: 17, weight: .bold).monospacedDigit()).foregroundStyle(.white)
                arrow("chevron.right") { tt.step(1) }
                Spacer()
                if let frame, frame.subpages > 1 {
                    arrow("chevron.backward.circle") { tt.sub(-1) }
                    Text("\(frame.subpage + 1)/\(frame.subpages)").font(.system(size: 15).monospacedDigit()).foregroundStyle(.white)
                    arrow("chevron.forward.circle") { tt.sub(1) }
                }
            }
            .padding(.horizontal, 14)

            HStack(spacing: 8) {
                ForEach(0..<4, id: \.self) { k in
                    let link = frame?.links[k] ?? -1
                    Button { tt.colour(k) } label: {
                        Text(link > 0 ? "\(link)" : " ")
                            .font(.system(size: 14, weight: .bold).monospacedDigit())
                            .foregroundStyle(k == 2 ? Color.black : Color.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(teletextKeyColours[k].opacity(link > 0 ? 1 : 0.3), in: RoundedRectangle(cornerRadius: 10))
                    }
                    .disabled(link <= 0)
                }
                toggle("circle.lefthalf.filled", on: tt.transparent, label: s.r("teletext_transparent")) { tt.toggleBackground() }
                toggle("questionmark", on: tt.revealed, label: s.r("teletext_reveal")) { tt.toggleReveal() }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 14)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(tt.transparent ? Color.black.opacity(0.45) : Color.black)
        .task {
            while !Task.isCancelled {
                tt.poll(player.teletextPages)
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
        .onDisappear { tt.detach() }
    }

    private func arrow(_ symbol: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 17, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 38, height: 38).background(Color.white.opacity(0.12), in: Circle())
        }
    }

    private func toggle(_ symbol: String, on: Bool, label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Color.white.opacity(on ? 0.35 : 0.12), in: RoundedRectangle(cornerRadius: 10))
        }
        .accessibilityLabel(label)
    }
}
