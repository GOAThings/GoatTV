import Foundation
import GoatCore
import Observation

/// "Informação do stream" (Canal atual no Apple TV, opções do player no iPhone): as linhas com os números do canal a
/// dar, refeitas de segundo a segundo enquanto o painel está aberto (quem o mostra chama [update]). As contas e as
/// unidades são as do núcleo partilhado (StreamStatsRules), iguais no Android. Só lê: não abre nada.
@MainActor
@Observable
final class StreamStatsMonitor {
    private(set) var rows: [StreamStatsRules.Row] = []
    @ObservationIgnored private let bitrate = StreamStatsRules.Bitrate()

    func update(_ model: AppModel) {
        let s = model.strings
        var m = model.player.statsMeasure(bitrate: bitrate)
        let source = model.statsSource
        m.source = source.name
        m.failover = source.failover
        let words = StreamStatsRules.Words(
            progressive: s.r("stats_progressive"), interlaced: s.r("stats_interlaced"), deinterlacing: s.r("stats_deinterlacing"),
            upscaling: s.r("stats_upscaling"), toneMapping: s.r("stats_tone_mapping"), nothing: s.r("none"), automatic: s.r("auto"),
            fallback: s.r("stats_fallback"), failover: s.r("stats_failover"))
        rows = StreamStatsRules.rows(m, words: words)
    }

    /// Outro canal ou o painel fechou: o débito recomeça a conta.
    func reset() {
        bitrate.clear()
        rows = []
    }
}

extension AppModel {
    /// A fonte que está a dar ("RTP 1 HD · Lista"): o canal (o do failover, se for um) e a lista dele.
    var statsSource: (name: String, failover: Bool) {
        let failover = player.onBackup
        let channel = failover ? failoverNow?.channel.name : current?.name
        let listId = player.playingSourceKey.flatMap { $0.split(separator: "|", maxSplits: 1).first.map(String.init) }
        let list = listId.flatMap { id in playlists.first { $0.id == id }?.name } ?? (failover ? failoverNow?.playlist.name : nil)
        return ([channel, list].compactMap { $0 }.joined(separator: " · "), failover)
    }
}
