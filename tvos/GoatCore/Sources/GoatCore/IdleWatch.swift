import Foundation
import GoatShared

/// "Ainda estás a ver?" (definição `idle_stop`): a contagem do tempo sem comandos, a mesma do Android (IdleWatch.kt no
/// núcleo partilhado). Quem a usa chama [tick] de segundo a segundo e [onInput] em cada comando.
public final class IdleWatch: @unchecked Sendable {
    public enum Phase: Sendable { case watching, prompt, stopped }

    private let core = GoatShared.IdleWatch()

    public init() {}

    /// O limite da definição ("off", "2h", "4h", "6h") em ms; 0 = desligado.
    public static func limitMs(_ setting: String) -> Int64 {
        GoatShared.IdleWatch.companion.limitMs(setting: setting)
    }

    public var phase: Phase {
        switch core.phase() {
        case GoatShared.IdleWatch.companion.PROMPT: return .prompt
        case GoatShared.IdleWatch.companion.STOPPED: return .stopped
        default: return .watching
        }
    }

    /// Um comando do utilizador: recomeça a contagem e fecha o aviso (depois de parar, só [watchAgain] volta).
    public func onInput(_ nowMs: Int64) { core.onInput(nowMs: nowMs) }

    /// "Ver outra vez": sai do ecrã de parado e recomeça a contagem.
    public func watchAgain(_ nowMs: Int64) { core.watchAgain(nowMs: nowMs) }

    /// Avança até [nowMs]; [active]: há um canal a dar e a contagem faz sentido.
    public func tick(_ nowMs: Int64, limitMs: Int64, active: Bool) {
        _ = core.tick(nowMs: nowMs, limitMs: limitMs, active: active)
    }

    /// Os segundos que faltam no aviso (60 → 1); 0 fora dele.
    public func secondsLeft(_ nowMs: Int64) -> Int { Int(core.secondsLeft(nowMs: nowMs)) }
}
