import Foundation
import GoatShared

/// As etapas do arranque de um canal (o GoatZap do Android, ver ZapStages.kt): cada etapa conta uma vez, com os ms
/// desde a anterior e desde o início. Só para o registo das versões de testes; usa-se numa só fila.
public final class ZapTimeline {
    private let stages: ZapStages

    public init(startMs: Int64) {
        stages = ZapStages(startMs: startMs)
    }

    /// A linha a registar para a etapa [stage] às [nowMs] (uptime, ms), ou nil se ela já tinha chegado.
    public func mark(_ stage: String, nowMs: Int64, detail: String = "") -> String? {
        stages.mark(stage: stage, nowMs: nowMs, detail: detail)
    }
}
