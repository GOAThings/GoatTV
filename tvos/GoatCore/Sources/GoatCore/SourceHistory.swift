import Foundation
import GoatShared

/// Quão fiável tem sido cada fonte (a lista e o canal nela) neste aparelho: as aberturas, as que falharam, as quedas e
/// o tempo a ver, por dia, nos últimos dias. Só neste aparelho (UserDefaults, não vai para a conta). A regra, a nota e o
/// texto guardado são os do núcleo partilhado com o Android (SourceHistory.kt). Serve para escolher a fonte a abrir
/// primeiro e para dizer à pessoa o que se passou ("3 quedas em 2 h esta semana").
@MainActor
public final class SourceHistory {
    private static let defaultsKey = "source_history"
    private let history: GoatShared.SourceHistory

    public init() {
        history = AppleBridge.shared.sourceHistoryDecode(text: UserDefaults.standard.string(forKey: Self.defaultsKey), nowMs: Self.nowMs())
    }

    /// A chave de uma fonte: a lista e o canal nela (a mesma do ProHealth).
    public nonisolated static func key(_ playlistId: String, _ channelKey: String) -> String {
        AppleBridge.shared.sourceKey(playlistId: playlistId, channelKey: channelKey)
    }

    /// A fonte abriu ([ok]) ou não abriu.
    public func recordOpen(_ key: String, ok: Bool) {
        if history.recordOpen(key: key, ok: ok, nowMs: Self.nowMs()) { save() }
    }

    /// A ligação da fonte caiu depois de dar (ou o direto ficou preso).
    public func recordDrop(_ key: String) {
        if history.recordDrop(key: key, nowMs: Self.nowMs()) { save() }
    }

    /// A pessoa viu [seconds] s da fonte.
    public func recordWatched(_ key: String, seconds: Int64) {
        if history.recordWatched(key: key, seconds: seconds, nowMs: Self.nowMs()) { save() }
    }

    /// A fonte tem uma nota claramente má (as quedas por hora a ver, as aberturas falhadas): vai para trás das boas.
    public func isBad(_ key: String) -> Bool { history.isBad(key: key, nowMs: Self.nowMs()) }

    /// As posições de [keys] ("" = sem chave) pela ordem em que se abrem: as más no fim e, entre vizinhas da mesma
    /// [tiers] (a qualidade), as mais fiáveis primeiro. Sem historial, é a ordem de entrada.
    public func order(keys: [String], tiers: [Int]) -> [Int] {
        guard keys.count > 1 else { return Array(keys.indices) }
        let order = AppleBridge.shared.sourceOrder(history: history, keys: keys, tiers: tiers.map { KotlinInt(int: Int32(clamping: $0)) },
                                                   nowMs: Self.nowMs())
        return order.map { $0.intValue }
    }

    /// A linha da fiabilidade de uma fonte esta semana ("3 quedas em 2 h esta semana · 1 de 4 aberturas falharam esta
    /// semana"), ou nil sem nada para dizer. [r] dá o texto de uma chave de `strings_ui.xml` (com argumentos).
    public func line(_ key: String, _ r: (String, [String]) -> String) -> String? {
        guard let st = AppleBridge.shared.sourceStats(history: history, key: key, nowMs: Self.nowMs()) else { return nil }
        let watched = AppleBridge.shared.sourceWatchedLabel(seconds: st.watchedSec)
        var parts: [String] = []
        if st.drops == 1 {
            parts.append(r("source_history_drop_one", [watched]))
        } else if st.drops > 1 {
            parts.append(r("source_history_drops", ["\(st.drops)", watched]))
        }
        if st.failedOpens > 0 { parts.append(r("source_history_failed", ["\(st.failedOpens)", "\(st.opens + st.failedOpens)"])) }
        if !parts.isEmpty { return parts.joined(separator: " · ") }
        return st.watchedSec >= AppleBridge.shared.sourceStableMinSec() ? r("source_history_stable", [watched]) : nil
    }

    private func save() {
        UserDefaults.standard.set(history.encode(nowMs: Self.nowMs()), forKey: Self.defaultsKey)
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
