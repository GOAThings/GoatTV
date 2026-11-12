import Foundation
import GoatShared

/// O que fazer quando a tentativa atual de dar um canal falha (ver [PlaybackRules.nextStep]).
public enum NextStep: Sendable {
    /// O formato seguinte do mesmo canal (ts ⇄ m3u8).
    case nextFormat
    /// Os failovers do canal (a lista de reserva ou outra versão na própria lista), pela ordem.
    case failover
    /// Já num failover: o seguinte ou, sem mais, o erro da lista principal.
    case nextFailover
    /// Caiu depois de dar: volta a ligar daqui a pouco, desde o primeiro formato.
    case reconnect
    /// Mostra o erro (e mais umas tentativas mais tarde).
    case giveUp
}

/// Como estava a tentativa que falhou (ver o núcleo partilhado, shared/…/playback/PlaybackRules.kt).
public struct AttemptFailure: Sendable {
    public var live: Bool
    public var started: Bool
    public var wasPlaying: Bool
    public var onBackup: Bool
    public var hasNextFormat: Bool
    public var failoverAllowed: Bool
    public var reconnect: Bool
    public var noAnswer: Bool

    public init(live: Bool, started: Bool, wasPlaying: Bool, onBackup: Bool, hasNextFormat: Bool, failoverAllowed: Bool,
                reconnect: Bool, noAnswer: Bool) {
        self.live = live
        self.started = started
        self.wasPlaying = wasPlaying
        self.onBackup = onBackup
        self.hasNextFormat = hasNextFormat
        self.failoverAllowed = failoverAllowed
        self.reconnect = reconnect
        self.noAnswer = noAnswer
    }
}

/// O cérebro das tentativas, o mesmo do Android (ChannelPlayback): vem do núcleo partilhado (GoatShared).
public enum PlaybackRules {
    /// Parado a carregar há tanto tempo no direto: volta a ligar.
    public static var stallSeconds: TimeInterval { TimeInterval(GoatShared.PlaybackRules.shared.STALL_MS) / 1000 }

    /// O tempo (s) para um direto começar a dar: 8 s, 10 s num motor mais calmo (o AetherEngine), +2 s com o buffer grande.
    public static func openTimeout(largeBuffer: Bool, slowEngine: Bool) -> TimeInterval {
        TimeInterval(GoatShared.PlaybackRules.shared.openTimeoutMs(largeBuffer: largeBuffer, slowEngine: slowEngine)) / 1000
    }

    /// O passo seguinte quando a tentativa [f] falha.
    public static func nextStep(_ f: AttemptFailure) -> NextStep {
        let step = GoatShared.PlaybackRules.shared.nextStep(f: GoatShared.AttemptFailure(
            live: f.live, started: f.started, wasPlaying: f.wasPlaying, onBackup: f.onBackup, hasNextFormat: f.hasNextFormat,
            failoverAllowed: f.failoverAllowed, reconnect: f.reconnect, noAnswer: f.noAnswer))
        switch step.name {
        case "NEXT_FORMAT": return .nextFormat
        case "FAILOVER": return .failover
        case "NEXT_FAILOVER": return .nextFailover
        case "RECONNECT": return .reconnect
        default: return .giveUp
        }
    }

    /// Mudou-se de canal [sinceLastMs] depois da anterior (nil = a primeira): quanto esperar (ms) antes de o abrir.
    static func zapDelayMs(sinceLastMs: Int64?, setting: Int) -> Int64 {
        GoatShared.PlaybackRules.shared.zapDelayMs(sinceLastMs: sinceLastMs ?? -1, setting: Int32(clamping: setting))
    }
}

/// As vezes que o direto de um canal ficou preso a carregar (à segunda em pouco tempo, vai-se aos failovers).
public final class StallCounter: @unchecked Sendable {
    private let shared = GoatShared.StallCounter()

    public init() {}

    /// Quantas nos últimos 3 minutos (contando a última).
    public var count: Int { Int(shared.count) }

    /// Ficou preso em [nowMs]: true = passar aos failovers (com [failoverAllowed]), false = voltar a ligar.
    public func stalled(nowMs: Int64, failoverAllowed: Bool) -> Bool { shared.stalled(nowMs: nowMs, failoverAllowed: failoverAllowed) }

    /// Outro canal: as paragens do anterior não contam.
    public func clear() { shared.clear() }
}
