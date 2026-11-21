import Foundation
import GoatShared

/// O canal "congelado" com a ligação a dar (a imagem ou o som parados, bytes sem nada que se descodifique, a playlist
/// do HLS em direto parada): as mesmas regras do Android (FreezeWatch.kt no núcleo partilhado). Quem a usa chama
/// [tick] de segundo a segundo com os contadores do GoatCore e [reset] a cada abertura.
public final class FreezeWatch: @unchecked Sendable {
    public enum Kind: Sendable { case none, video, audio, undecodable, playlist }

    private let core = GoatShared.FreezeWatch()

    public init() {}

    /// Abriu-se (outro canal, ou voltou a ligar): tudo de novo, com o tempo de arranque.
    public func reset() { core.reset() }

    /// O que a playlist do HLS em direto do canal principal leva sem segmentos novos (ms; -1 = a avançar, ou sem
    /// nenhuma) e a duração dos segmentos dela (ver HlsReader).
    public static func playlistStuckMs(_ nowMs: Int64) -> Int64 { GoatShared.HlsLiveStall.shared.stuckMs(nowMs: nowMs) }
    public static var playlistTargetMs: Int64 { GoatShared.HlsLiveStall.shared.targetMs() }

    /// Mais um segundo (ver o núcleo partilhado): [videoFrames] -1 = conta só o [videoPtsMs]; [hold] em pausa, atrás do
    /// direto, a esconder uma falha de dados ou à espera de voltar a ligar; [buffering] o relógio parado à espera.
    public func tick(nowMs: Int64, videoFrames: Int64, videoPtsMs: Int64, audioFrames: Int64, bytes: Int64, samples: Int64,
                     hasVideo: Bool, hasAudio: Bool, hold: Bool, buffering: Bool, playlistStuckMs: Int64, playlistTargetMs: Int64) -> Kind {
        let kind = core.tick(nowMs: nowMs, videoFrames: videoFrames, videoPtsMs: videoPtsMs, audioFrames: audioFrames, bytes: bytes,
                             samples: samples, hasVideo: hasVideo, hasAudio: hasAudio, hold: hold, buffering: buffering,
                             playlistStuckMs: playlistStuckMs, playlistTargetMs: playlistTargetMs)
        switch kind {
        case GoatShared.FreezeWatch.companion.VIDEO: return .video
        case GoatShared.FreezeWatch.companion.AUDIO: return .audio
        case GoatShared.FreezeWatch.companion.UNDECODABLE: return .undecodable
        case GoatShared.FreezeWatch.companion.PLAYLIST: return .playlist
        default: return .none
        }
    }

    /// O nome para os registos (GoatFreeze).
    public static func label(_ kind: Kind) -> String {
        let companion = GoatShared.FreezeWatch.companion
        switch kind {
        case .video: return companion.label(kind: companion.VIDEO)
        case .audio: return companion.label(kind: companion.AUDIO)
        case .undecodable: return companion.label(kind: companion.UNDECODABLE)
        case .playlist: return companion.label(kind: companion.PLAYLIST)
        case .none: return companion.label(kind: companion.NONE)
        }
    }
}
