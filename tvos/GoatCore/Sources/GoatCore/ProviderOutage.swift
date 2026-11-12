import Foundation
import GoatShared

/// Listas em baixo. Um canal em direto que passou para a lista de reserva por um erro da conta ou do servidor (não só
/// daquele canal: ver [providerWide]) marca a lista principal como em baixo durante [windowMs]: os canais seguintes dela
/// vão logo à reserva, em vez de esperarem em cada um pelas tentativas de sempre. Um canal dela que dê apaga a marca.
/// As mesmas regras no Android (data/ProviderOutage.kt).
public struct ProviderOutage {
    public static var windowMs: Int64 { GoatShared.OutageRules.shared.WINDOW_MS }

    private var until: [String: Int64] = [:]

    public init() {}

    /// O código HTTP [code] diz que é a conta ou o servidor que não responde, não só este canal.
    /// Códigos da conta (401, 429, 456 "linha bloqueada", 458) e do servidor (5xx); a regra é a do núcleo partilhado.
    public static func providerWide(_ code: Int?) -> Bool {
        GoatShared.OutageRules.shared.providerWide(code: code.map { KotlinInt(int: Int32(clamping: $0)) })
    }

    public mutating func markDown(_ playlistId: String, now: Int64) {
        until[playlistId] = now + Self.windowMs
    }

    public mutating func isDown(_ playlistId: String, now: Int64) -> Bool {
        guard let end = until[playlistId] else { return false }
        if now < end { return true }
        until[playlistId] = nil
        return false
    }

    public mutating func clear(_ playlistId: String) {
        until[playlistId] = nil
    }
}
