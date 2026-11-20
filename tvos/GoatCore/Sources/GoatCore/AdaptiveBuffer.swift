import Foundation
import GoatShared

/// O buffer automático do direto (Prefs.Buffer.auto) no GoatCore da Apple: as regras são as do núcleo partilhado
/// (shared/…/playback/AdaptiveBuffer.kt), iguais às do Android. Uma paragem (ou a ligação a dar menos do que o canal
/// gasta) sobe o vídeo a juntar antes de voltar a dar; 10 minutos sem nada disso descem-no devagar, gastando o que está
/// a mais a tocar 2 % mais depressa. O nível aprendido fica por fornecedor (o servidor), para a app inteira.
///
/// Não é seguro entre threads: quem usa chama tudo da mesma fila (a memória dos fornecedores tem o seu cadeado).
public final class AdaptiveBuffer {
    /// A memória da app inteira (ver AdaptiveBufferMemory no núcleo): os leitores do multi-vista correm em filas suas.
    private static let memory = GoatShared.AdaptiveBufferMemory()
    private static let lock = NSLock()

    private let core: GoatShared.AdaptiveBuffer
    private let key: String

    /// O buffer de [url] (o endereço do canal no fornecedor, não o do anel): começa no nível que o fornecedor aprendeu.
    public init(url: String) {
        key = GoatShared.AdaptiveBuffer.companion.providerKey(url: url)
        Self.lock.lock()
        let start = Self.memory.startLevel(key: key)
        Self.lock.unlock()
        core = GoatShared.AdaptiveBuffer(startLevel: start)
    }

    /// Quanto vídeo à frente juntar depois de uma paragem antes de voltar a dar (segundos).
    public var targetSeconds: Double { Double(core.targetMs()) / 1000 }

    /// O nível agora (0 = Ao vivo … 3 = Grande), para o registo.
    public var level: Int { Int(core.level) }

    /// Uma paragem a meio (o que estava à frente acabou).
    public func onStall(_ nowMs: Int64) {
        if core.onStall(nowMs: nowMs) { remember() }
    }

    /// A dar à velocidade normal com [leadSeconds] à frente; [dips]: fora do HLS (que chega aos segmentos).
    public func onPlaying(_ nowMs: Int64, leadSeconds: Double, dips: Bool) {
        if core.onPlaying(nowMs: nowMs, leadMs: Int64(leadSeconds * 1000), dips: dips) { remember() }
    }

    /// A velocidade a tocar (1, ou um pouco mais para gastar o que está a mais depois de descer); [canDrain]: pode
    /// acelerar-se sem se notar.
    public func rate(leadSeconds: Double, canDrain: Bool) -> Float {
        core.rate(leadMs: Int64(leadSeconds * 1000), canDrain: canDrain)
    }

    private func remember() {
        Self.lock.lock()
        Self.memory.remember(key: key, level: core.level)
        Self.lock.unlock()
    }
}
