import GoatCore
import SwiftUI

/// Um erro explicado (como no GoatTV Android): o que aconteceu, a causa provável, os passos para resolver e o
/// detalhe técnico.
struct ErrorInfo: Equatable {
    var title: String
    var cause: String?
    var fixes: [String] = []
    var detail: String?
}

/// Os erros, com os textos do Android (as mesmas traduções).
extension Strings {
    private var settingsWord: String { r("settings") }
    /// "Definições → Listas → <lista> → Editar" (em árabe, a seta ao contrário).
    private func path(_ parts: String...) -> String {
        ([settingsWord] + parts).joined(separator: lang == "ar" ? " ← " : " → ")
    }
    private func editPath(_ name: String) -> String { path(r("playlists"), name, r("edit")) }
    func technical(_ detail: String) -> String { r("technical", detail) }

    func errNoPlaylists() -> ErrorInfo {
        ErrorInfo(title: r("err_no_playlists"), cause: r("err_no_playlists_2"), fixes: [r("err_no_playlists_3"), r("err_no_playlists_4")])
    }

    func errBackend(_ code: Int) -> ErrorInfo {
        ErrorInfo(title: r("err_backend"), cause: r("err_backend_2"), fixes: [r("err_backend_3"), r("err_backend_4")], detail: technical("HTTP \(code)"))
    }

    func errBackendOffline() -> ErrorInfo {
        ErrorInfo(title: r("err_backend_offline"), cause: r("err_backend_offline_2"),
                  fixes: [r("err_backend_offline_3"), r("err_backend_offline_4"), r("err_backend_offline_5")])
    }

    /// Porque é que a lista [name] não carregou.
    func explainPlaylistError(_ name: String, _ error: Error) -> ErrorInfo {
        guard let e = error as? SourceError else {
            if let backend = error as? Backend.Failure, case .http(let code) = backend { return errBackend(code) }
            return ErrorInfo(title: r("err_playlist_other", name), cause: r("err_playlist_other_2"),
                             fixes: [r("err_playlist_other_3"), r("err_playlist_other_4", editPath(name))],
                             detail: technical(String(describing: error)))
        }
        switch e {
        case .badLogin:
            return ErrorInfo(title: r("err_playlist_bad_login"), cause: r("err_playlist_bad_login_2", name),
                             fixes: [r("err_playlist_bad_login_3", editPath(name)), r("err_playlist_bad_login_4")])
        case .inactive(let status):
            return ErrorInfo(title: r("err_playlist_inactive"), cause: r("err_playlist_inactive_2", name, status),
                             fixes: [r("err_playlist_inactive_3"), r("err_playlist_inactive_4", path(r("playlists")))])
        case .http(let code):
            return errPlaylistHttp(name, code)
        case .badResponse:
            return ErrorInfo(title: r("err_playlist_bad_response"), cause: r("err_playlist_bad_response_2", name),
                             fixes: [r("err_playlist_bad_response_3", editPath(name)), r("err_playlist_bad_response_4")])
        case .noURL, .badURL:
            return ErrorInfo(title: r("err_playlist_bad_url"), cause: r("err_playlist_bad_url_2", name),
                             fixes: [r("err_playlist_bad_url_3", editPath(name))])
        case .offline:
            return ErrorInfo(title: r("err_playlist_unreachable"), cause: r("err_playlist_unreachable_2", name),
                             fixes: [r("err_playlist_unreachable_3"), r("err_playlist_unreachable_4"), r("err_playlist_unreachable_5", path(r("playlists")))])
        }
    }

    func errPlaylistEmpty(_ name: String) -> ErrorInfo {
        ErrorInfo(title: r("err_playlist_empty"), cause: r("err_playlist_empty_2", name),
                  fixes: [r("err_playlist_empty_3"), r("err_playlist_empty_4", editPath(name)), r("err_playlist_empty_5", path(r("playlists")))])
    }

    private func errPlaylistHttp(_ name: String, _ code: Int) -> ErrorInfo {
        switch code {
        case 401, 403:
            return ErrorInfo(title: r("err_playlist_http"), cause: r("err_playlist_http_2", name),
                             fixes: [r("err_playlist_http_3"), r("err_playlist_http_4"), r("err_playlist_http_5", path(r("general"), r("user_agent")))],
                             detail: technical("HTTP \(code)"))
        case 404:
            return ErrorInfo(title: r("err_playlist_http_6"), cause: r("err_playlist_http_7", name),
                             fixes: [r("err_playlist_http_8", editPath(name)), r("err_playlist_http_9")], detail: technical("HTTP 404"))
        case 429:
            return ErrorInfo(title: r("err_playlist_http_10"), cause: r("err_playlist_http_11", name),
                             fixes: [r("err_playlist_http_12"), r("err_playlist_http_13")], detail: technical("HTTP 429"))
        case 500...599:
            return ErrorInfo(title: r("err_playlist_http_14"), cause: r("err_playlist_http_15", name),
                             fixes: [r("err_playlist_http_16"), r("err_playlist_http_17")], detail: technical("HTTP \(code)"))
        default:
            return ErrorInfo(title: r("err_playlist_http_18", name), cause: r("err_playlist_http_19"),
                             fixes: [r("err_playlist_http_20"), r("err_playlist_http_21", editPath(name))], detail: technical("HTTP \(code)"))
        }
    }

    // MARK: Player

    func errAccountBusy(limit: Int?, sure: Bool) -> ErrorInfo {
        ErrorInfo(title: sure ? r("err_account_busy") : r("err_account_busy_2"),
                  cause: limit.map { plural("streams_limit", $0) } ?? r("err_account_busy_3"),
                  fixes: [r("err_account_busy_4"), r("err_account_busy_5"), r("err_account_busy_6")])
    }

    func errChannelRefused(_ code: Int) -> ErrorInfo {
        ErrorInfo(title: r("err_channel_refused"), cause: r("err_channel_refused_2"),
                  fixes: [r("err_channel_refused_3"), r("err_channel_refused_4"), r("err_channel_refused_5")], detail: technical("HTTP \(code)"))
    }

    var errChannelGone: ErrorInfo {
        ErrorInfo(title: r("err_channel_gone"), cause: r("err_channel_gone_2"),
                  fixes: [r("err_channel_gone_3", path(r("playlists")), r("reload_channels")), r("err_channel_gone_4")], detail: technical("HTTP 404"))
    }

    var errChannelRateLimited: ErrorInfo {
        ErrorInfo(title: r("err_channel_rate_limited"), cause: r("err_channel_rate_limited_2"),
                  fixes: [r("err_channel_rate_limited_3"), r("err_channel_rate_limited_4")], detail: technical("HTTP 429"))
    }

    func errProviderServer(_ code: Int) -> ErrorInfo {
        ErrorInfo(title: r("err_provider_server"), cause: r("err_provider_server_2"),
                  fixes: [r("err_provider_server_3"), r("err_provider_server_4")], detail: technical("HTTP \(code)"))
    }

    var errChannelUnreachable: ErrorInfo {
        ErrorInfo(title: r("err_channel_unreachable"), cause: r("err_channel_unreachable_2"),
                  fixes: [r("err_channel_unreachable_3"), r("err_channel_unreachable_4")])
    }

    func errStreamFormat(_ format: String?) -> ErrorInfo {
        ErrorInfo(title: r("err_stream_format"), cause: r("err_stream_format_2"),
                  fixes: [r("err_stream_format_3"), r("err_stream_format_4", path(r("playback"), r("stream_format")), format ?? r("automatic_word")),
                          r("err_stream_format_5")])
    }

    var errCantPlay: ErrorInfo {
        ErrorInfo(title: r("err_cant_play"), cause: r("err_cant_play_2"), fixes: [r("err_cant_play_3"), r("err_cant_play_4"), r("err_cant_play_5")])
    }

    var errCatchup: ErrorInfo {
        ErrorInfo(title: r("err_catchup"), cause: r("err_catchup_2"), fixes: [r("err_catchup_3"), r("err_catchup_4")])
    }
}

/// O cartão de erro comum a toda a app (arranque, listas, player). [status] é uma linha viva (ex.: "Nova tentativa
/// em 5 s"); [footer] são botões ou atalhos.
struct ErrorPanel<Footer: View>: View {
    @Environment(AppModel.self) private var model
    let info: ErrorInfo
    var status: String?
    @ViewBuilder var footer: () -> Footer

    init(info: ErrorInfo, status: String? = nil, @ViewBuilder footer: @escaping () -> Footer) {
        self.info = info
        self.status = status
        self.footer = footer
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 28) {
                Text("!")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundStyle(Theme.accent(model.prefs.accent))
                    .frame(width: 84, height: 84)
                    .background(Circle().fill(Theme.sky.opacity(0.12)))
                    .overlay(Circle().strokeBorder(Theme.accent(model.prefs.accent), lineWidth: 3))
                VStack(alignment: .leading, spacing: 6) {
                    Text(info.title).font(.system(size: 38, weight: .semibold)).fixedSize(horizontal: false, vertical: true)
                    if let cause = info.cause {
                        Text(cause).font(.system(size: 28)).foregroundStyle(Theme.textDim).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            if !info.fixes.isEmpty {
                Text(model.strings.r("how_to_fix").uppercased())
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Theme.textFaint)
                    .padding(.top, 40)
                    .padding(.bottom, 16)
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(info.fixes.enumerated()), id: \.offset) { i, fix in
                        HStack(alignment: .firstTextBaseline, spacing: 22) {
                            Text("\(i + 1)")
                                .font(.system(size: 22, weight: .semibold))
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Color.white.opacity(0.10)))
                            Text(fix).font(.system(size: 28)).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
            if let status {
                StatusLine(text: status).padding(.top, 32)
            }
            footer().padding(.top, 40)
            if let detail = info.detail {
                Text(detail).font(.system(size: 20)).foregroundStyle(Theme.textFaint).padding(.top, 30)
            }
        }
        .padding(.horizontal, 64)
        .padding(.vertical, 56)
        .frame(maxWidth: 1360, alignment: .leading)
        .glass(radius: 40)
    }
}

extension ErrorPanel where Footer == EmptyView {
    init(info: ErrorInfo, status: String? = nil) {
        self.init(info: info, status: status) { EmptyView() }
    }
}

/// Linha de estado com um ponto a pulsar ("Nova tentativa (1/3) em 5 s…").
struct StatusLine: View {
    @Environment(AppModel.self) private var model
    let text: String
    @State private var pulse = false

    init(text: String) {
        self.text = text
    }

    var body: some View {
        HStack(spacing: 16) {
            Circle()
                .fill(Theme.accent(model.prefs.accent))
                .frame(width: 16, height: 16)
                .opacity(pulse ? 1 : 0.35)
                .animation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true), value: pulse)
            Text(text).font(.system(size: 26)).foregroundStyle(Theme.textDim)
        }
        .onAppear { pulse = true }
    }
}

/// Cápsula "A sintonizar…" / "A voltar a ligar…" no canto, com um ponto a pulsar.
struct TuningPill: View {
    @Environment(AppModel.self) private var model
    let text: String
    @State private var pulse = false

    init(text: String) {
        self.text = text
    }

    var body: some View {
        HStack(spacing: 14) {
            Circle()
                .fill(Theme.accent(model.prefs.accent))
                .frame(width: 14, height: 14)
                .opacity(pulse ? 1 : 0.3)
                .animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: pulse)
            Text(text).font(.system(size: 25, weight: .semibold))
        }
        .padding(.horizontal, 30)
        .padding(.vertical, 16)
        .glass(radius: 30)
        .onAppear { pulse = true }
    }
}
