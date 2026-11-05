import Foundation

/// Tentativas erradas do PIN, para não dar para as ir experimentando todas: depois de umas quantas, cada PIN tem de
/// esperar um tempo que vai crescendo. Quem usa guarda [failures] e [lastFailureAt] neste aparelho, para contar entre
/// pedidos do PIN e ao reabrir a app. Igual ao PinAttempts do GoatTV Android.
public struct PinAttempts: Equatable, Sendable {
    public private(set) var failures: Int
    public private(set) var lastFailureAt: Int64

    public init(failures: Int = 0, lastFailureAt: Int64 = 0) {
        self.failures = failures
        self.lastFailureAt = lastFailureAt
    }

    /// Quanto falta para se poder tentar outra vez (0 = já).
    public mutating func waitMs(now: Int64) -> Int64 {
        // O relógio andou para trás: a espera conta a partir de agora, senão podia nunca mais acabar.
        if now < lastFailureAt { lastFailureAt = now }
        return max(0, lastFailureAt + Self.delay(failures: failures) - now)
    }

    /// A tentativa conta só se não estiver a esperar: aí, nem o PIN certo entra. Devolve se entrou.
    public mutating func check(now: Int64, correct: Bool) -> Bool {
        if waitMs(now: now) > 0 { return false }
        if correct {
            reset()
            return true
        }
        failures += 1
        lastFailureAt = now
        return false
    }

    public mutating func reset() {
        failures = 0
        lastFailureAt = 0
    }

    /// Sem espera nas primeiras; depois 30 s, 1 min, 2 min… até 1 hora.
    public static func delay(failures: Int) -> Int64 {
        guard failures >= 3 else { return 0 }
        let shift = min(failures - 3, 10)
        return min(Int64(30_000) << shift, 3_600_000)
    }
}
