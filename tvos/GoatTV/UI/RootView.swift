import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    /// A abertura (o logo animado) só passa uma vez, ao abrir a app.
    @State private var introDone = false

    var body: some View {
        ZStack {
            Theme.backdrop.ignoresSafeArea()
            switch model.screen {
            case .starting:
                GoatLogoView(intro: false)
            case .welcome:
                WelcomeView()
            case .newPlaylist:
                SettingsPanel(start: .newPlaylistType, onClose: { model.leaveNewPlaylist() })
                    .frame(height: 900)
            case .activation:
                ActivationView()
                    .focusable()
                    .onExitCommand { model.leaveActivation() }
            case .loading(let name):
                // O mesmo logo da abertura, no mesmo sítio, com o que se está a carregar por baixo.
                GoatLogoView(intro: false, message: name.isEmpty ? nil : model.strings.loading(name))
            case .player:
                PlayerScreen()
            case .noPlaylists:
                ErrorScreen(info: model.strings.errNoPlaylists(), addPlaylist: true)
            case .error(let info):
                ErrorScreen(info: info, addPlaylist: false)
            }
            if !introDone {
                GoatLogoView(intro: true) {
                    withAnimation(.easeOut(duration: 0.45)) { introDone = true }
                }
                .transition(.opacity)
            }
            ToastView()
        }
    }
}

/// O logótipo GOAT TV com uma mensagem por baixo (arranque e a carregar).
struct BrandView: View {
    let caption: String?

    var body: some View {
        VStack(spacing: 40) {
            BrandMark()
            if let caption {
                HStack(spacing: 20) {
                    ProgressView()
                    Text(caption).font(.title3).foregroundStyle(Theme.textDim)
                }
            } else {
                ProgressView()
            }
        }
    }
}

struct BrandMark: View {
    var scale: CGFloat = 1

    var body: some View {
        VStack(spacing: -6 * scale) {
            Text("G O A T")
                .font(.system(size: 54 * scale, weight: .bold))
                .foregroundStyle(Color.white)
            Text("TV")
                .font(.system(size: 150 * scale, weight: .heavy))
                .foregroundStyle(Theme.accent)
                .shadow(color: Theme.sky.opacity(0.6), radius: 24 * scale)
        }
    }
}

/// Ecrã de erro (arranque, listas): o erro explicado e os botões do Android (Juntar lista, Tentar de novo,
/// Definições, Terminar sessão). As Definições abrem por cima, à direita.
struct ErrorScreen: View {
    @Environment(AppModel.self) private var model
    let info: ErrorInfo
    let addPlaylist: Bool
    @State private var settings: SettingsPanel.Page?
    @FocusState private var focus: String?

    init(info: ErrorInfo, addPlaylist: Bool) {
        self.info = info
        self.addPlaylist = addPlaylist
    }

    var body: some View {
        let s = model.strings
        ZStack {
            ErrorPanel(info: info) {
                HStack(spacing: 24) {
                    if addPlaylist {
                        Button(s.r("add_playlist")) { settings = .newPlaylistType }.focused($focus, equals: "add")
                    }
                    Button(s.r("retry")) { model.retry() }.focused($focus, equals: "retry")
                    if model.content != nil || !model.playlists.isEmpty {
                        Button(s.r("settings")) { settings = .root }
                    }
                    if model.hasAccount {
                        Button(s.r("logout")) { model.signOut() }
                    }
                }
                .buttonStyle(PillButtonStyle())
                .disabled(settings != nil)
            }
            if let page = settings {
                HStack {
                    Spacer()
                    SettingsPanel(start: page) {
                        settings = nil
                    }
                    .id(page)
                }
                .padding(40)
                .ignoresSafeArea()
            }
        }
        .task {
            try? await Task.sleep(for: .milliseconds(80))
            focus = addPlaylist ? "add" : "retry"
        }
    }
}

struct ToastView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack {
            if let toast = model.toast {
                Text(toast)
                    .font(.headline)
                    .padding(.horizontal, 36)
                    .padding(.vertical, 20)
                    .glass(radius: 40)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            Spacer()
        }
        .padding(.top, 40)
        .animation(.easeOut(duration: 0.2), value: model.toast)
        .allowsHitTesting(false)
    }
}
