import SwiftUI

/// Primeira vez: ligar uma conta GOAT (recomendado) ou usar listas só nesta TV (como no GoatTV Android).
struct WelcomeView: View {
    @Environment(AppModel.self) private var model
    @FocusState private var focus: String?

    var body: some View {
        let s = model.strings
        VStack(alignment: .leading, spacing: 30) {
            BrandMark(scale: 0.5)
            Text(s.t("Welcome to GoatTV", "Bem-vindo à GoatTV")).font(.system(size: 52, weight: .bold))
            Text(s.t("How do you want to add your channels?", "Como queres juntar os teus canais?"))
                .font(.system(size: 30)).foregroundStyle(Theme.textDim)
            HStack(alignment: .top, spacing: 34) {
                card(
                    id: "account",
                    badge: s.t("RECOMMENDED", "RECOMENDADO"),
                    title: s.t("GOAT account", "Conta GOAT"),
                    body: s.t("Your playlists, favourites and settings stay in your account and are the same on every device. You add playlists at thegoat.systems.",
                              "As listas, os favoritos e as definições ficam na conta e são os mesmos em todos os aparelhos. As listas juntam-se em thegoat.systems.")
                ) { model.connectAccount() }
                card(
                    id: "local",
                    badge: nil,
                    title: s.t("Playlists on this TV", "Listas nesta TV"),
                    body: s.t("No account: add Xtream or M3U playlists here with the remote. Everything stays on this TV only.",
                              "Sem conta: junta listas Xtream ou M3U aqui com o comando. Fica tudo só nesta TV.")
                ) { model.startLocal() }
            }
        }
        .padding(70)
        .frame(maxWidth: 1600, alignment: .leading)
        .glass(radius: 40)
        .padding(80)
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = "account"
        }
    }

    private func card(id: String, badge: String?, title: String, body: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 16) {
                if let badge {
                    Text(badge)
                        .font(.system(size: 20, weight: .heavy))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(Theme.accent))
                }
                Text(title).font(.system(size: 38, weight: .bold))
                Text(body).font(.system(size: 26)).opacity(0.8).fixedSize(horizontal: false, vertical: true)
            }
            .frame(width: 640, alignment: .topLeading)
        }
        .buttonStyle(CardButtonStyle())
        .focused($focus, equals: id)
    }
}

/// Cartão de escolha: com foco fica branco com texto escuro, como as linhas das listas.
struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Card(configuration: configuration) }

    private struct Card: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Color.black : Theme.text)
                .padding(36)
                .frame(minHeight: 300, alignment: .topLeading)
                .background(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(focused ? Color.white : Color.white.opacity(0.08)))
                .scaleEffect(focused ? 1.03 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}
