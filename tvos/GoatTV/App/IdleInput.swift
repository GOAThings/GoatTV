import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Os comandos do utilizador para o "Ainda estás a ver?" (ver AppModel.noteInput): um reconhecedor de gestos na janela
/// que vê todos os toques (iPhone) e todas as teclas e deslizes do comando (Apple TV) e nunca reconhece nada, por isso
/// não tira nada à app. Com o aviso à vista, o primeiro toque ou tecla só serve para o fechar (não chega à app).
final class IdleInputRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
    /// Devolve true se mostrava o aviso.
    private let onInput: () -> Bool

    init(onInput: @escaping () -> Bool) {
        self.onInput = onInput
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    private func handle() {
        let swallow = onInput()
        cancelsTouchesInView = swallow
        state = swallow ? .ended : .failed
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) { handle() }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent) { handle() }

    override func reset() {
        cancelsTouchesInView = false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}

@MainActor
enum IdleInput {
    private static var installed = false

    /// Põe o reconhecedor na janela principal (ainda não existe nos primeiros instantes: volta a tentar).
    static func install(onInput: @escaping () -> Bool) {
        guard !installed else { return }
        Task { @MainActor in
            for _ in 0..<40 where !installed {
                let window = UIApplication.shared.connectedScenes
                    .compactMap { $0 as? UIWindowScene }
                    .flatMap(\.windows)
                    .first { $0.isKeyWindow } ?? UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.windows.first }.first
                if let window {
                    window.addGestureRecognizer(IdleInputRecognizer(onInput: onInput))
                    installed = true
                    return
                }
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }
}
