import Foundation
import GoatShared

// As regras do leitor que não dependem do motor (as mesmas do GoatTV Android, ui/player/engine): quanto esperar para
// voltar a abrir uma stream recusada, quando passar ao outro formato, o atraso ao mudar de canal e quando o AFR muda o
// modo da TV.

/// Quanto esperar para voltar a abrir uma stream que ainda não deu nada (o gravador da pausa do direto).
///
/// O caso mais comum ao mudar de canal: o fornecedor só deixa uma ligação e ainda não largou a do canal anterior, e
/// recusa a nova ("max connections": 403, 503, 509…). Liberta-a em centenas de ms, por isso tenta-se umas vezes em
/// pouco tempo ([busy]). Um 429 ("demasiados pedidos") pede calma: espera-se a sério ([rateLimited]). Um 404 é quase
/// sempre "este formato não existe aqui": só mais uma tentativa rápida antes de passar ao outro formato. O resto
/// (rede) fica perto do que o ExoPlayer faz, um pouco mais curto.
public enum OpenRetry {
    /// Os códigos com que os painéis Xtream recusam uma ligação a mais (ver [isBusy]).
    public static var busyCodes: Set<Int> { Set(GoatShared.OpenRetry.shared.BUSY_CODES.map { $0.intValue }) }

    /// A espera (ms) antes da repetição [retry] (1 = a primeira) de uma abertura que falhou com o código HTTP
    /// [httpCode] (nil = sem resposta HTTP); nil = desistir. As esperas são as do núcleo partilhado com o Android.
    public static func delayMs(httpCode: Int?, retry: Int) -> Int? {
        GoatShared.OpenRetry.shared.delayMs(httpCode: code(httpCode), retry: Int32(clamping: retry)).map { Int($0.int64Value) }
    }

    /// O fornecedor recusou a ligação como quem está ocupado (403, 503…; a ligação anterior pode ainda contar).
    public static func isBusy(_ httpCode: Int?) -> Bool { GoatShared.OpenRetry.shared.isBusy(httpCode: code(httpCode)) }

    /// Uma recusa do fornecedor: ocupado ou "demasiados pedidos" (429).
    public static func isRefusal(_ httpCode: Int?) -> Bool { GoatShared.OpenRetry.shared.isRefusal(httpCode: code(httpCode)) }

    private static func code(_ httpCode: Int?) -> KotlinInt? { httpCode.map { KotlinInt(int: Int32(clamping: $0)) } }
}

/// Ao abrir um canal, as formas tentam-se por ordem (ts ⇄ m3u8, como no GoatTV Android, ChannelPlayback): a que não
/// começa a dar passa à seguinte; nenhuma deu, mostra-se o erro e, mais tarde, outra volta desde a primeira.
///
/// Uma recusa de "ocupado" (403, 503…) já foi tentada uns 3 s (ver [OpenRetry]): a ligação anterior já fechou, por
/// isso é este formato que o fornecedor recusa neste canal (há canais que só dão em m3u8). Passa-se ao outro, como
/// antes da 0.10, mas o formato a que se chegou assim (ou depois de um 429) não fica aprendido para a conta: noutro
/// canal, o primeiro pode dar.
public struct FormatFallback: Sendable {
    /// A tentativa atual (0 = a primeira, o formato preferido).
    public private(set) var index = 0
    /// Chegou-se à tentativa atual porque uma anterior foi recusada (ver [OpenRetry.isRefusal]).
    public private(set) var fellBackOnRefusal = false

    public init() {}

    /// Começa-se outra vez: desde a primeira tentativa (canal novo, a volta seguinte depois do erro, OK no erro) ou
    /// desde [index] (o canal que uma gravação está a gravar abre pela ligação dela).
    public mutating func restart(at index: Int = 0) {
        self.index = index
        fellBackOnRefusal = false
    }

    /// A tentativa atual não começou a dar (o servidor respondeu [httpCode], nil = sem resposta HTTP): a seguinte das
    /// [count], ou nil = nenhuma deu (mostra-se o erro).
    public mutating func next(after httpCode: Int?, count: Int) -> Int? {
        if OpenRetry.isRefusal(httpCode) { fellBackOnRefusal = true }
        index += 1
        return index < count ? index : nil
    }

    /// Começou a dar na forma do formato [candidateIndex] (0 = o preferido): o outro formato é que deu (no direto,
    /// [live]) e fica a ser o preferido da conta? Não se chegou lá por uma recusa.
    public func learns(candidateIndex: Int, live: Bool) -> Bool {
        live && candidateIndex > 0 && !fellBackOnRefusal
    }
}

/// O atraso ao mudar de canal (Definições → Mudança de canal → Atraso ao mudar de canal, `zap_delay_ms`).
///
/// Automático: uma tecla muda já. A passar canais depressa (outra mudança a menos de [flipWindowMs] da anterior) só
/// mudam o número e a informação no ecrã; o canal abre quando se pára ([settleMs] sem outra mudança). Assim não se
/// abre e fecha uma ligação por cada canal por onde se passa. Com um tempo fixo, espera-se sempre esse tempo.
///
/// Quem chama espera o tempo devolvido e só então abre; uma mudança nova pelo meio cancela essa espera.
public struct ZapPacer: Sendable {
    private var lastZapAt: Int64?

    public init() {}

    /// Mudou-se de canal em [nowMs] (um relógio que só anda para a frente): quanto esperar (ms) antes de o abrir.
    public mutating func delayFor(nowMs: Int64, setting: Int) -> Int64 {
        let since = lastZapAt.map { nowMs - $0 }
        lastZapAt = nowMs
        return PlaybackRules.zapDelayMs(sinceLastMs: since, setting: setting)
    }

    /// Duas mudanças com menos do que isto entre elas: está-se a passar canais.
    public static var flipWindowMs: Int64 { GoatShared.PlaybackRules.shared.ZAP_FLIP_WINDOW_MS }
    /// A passar canais, o canal abre quando não há outra mudança durante isto.
    public static var settleMs: Int64 { GoatShared.PlaybackRules.shared.ZAP_SETTLE_MS }
}

/// Quando o AFR muda o modo da TV (`afr_mode`): uma vez por canal, quando a cadência dele fica conhecida; "ao
/// começar" só no primeiro canal do leitor (o modo fica nos seguintes, sem o ecrã apagar a cada zap) até [reset] (ao
/// sair do leitor, quando a TV volta ao modo normal).
public struct AfrSession: @unchecked Sendable {
    // A regra é a do núcleo partilhado com o Android.
    private let shared = GoatShared.AfrSession()

    public init() {}

    /// Outro canal: ainda não se decidiu nada para ele.
    public mutating func newChannel() { shared.channelChanged() }

    /// A TV voltou ao modo normal (saiu-se do leitor, ou o AFR foi desligado).
    public mutating func reset() { shared.reset() }

    /// Já se sabe a cadência [fps] do canal: devolve a que se pede à TV, ou nil para não mexer.
    public mutating func onFps(mode: String, fps: Double) -> Double? {
        let target = shared.onFps(mode: mode, fps: fps)
        return target > 0 ? target : nil
    }
}

/// As cadências de vídeo: aproximar uma medida das habituais e medir a do VLC pelas imagens mostradas.
public enum FrameRate {
    /// As cadências de TV e de cinema (as do núcleo partilhado).
    public static var standard: [Double] { GoatShared.FrameRates.shared.STANDARD.map { $0.doubleValue } }

    /// A cadência habitual mais perto de [fps] (até 1 % de diferença); fora disso, [fps] como está (como no Android).
    public static func snap(_ fps: Double) -> Double { GoatShared.FrameRates.shared.snap(fps: fps) }

    /// Uma medida por contagem (imagens num intervalo) não separa 29,97 de 30: entre as duas fica a da TV (29,97,
    /// 59,94, 23,976), que é o que as emissões usam. Até [tolerance] de diferença; fora disso, nil.
    public static func snapCounted(_ fps: Double, tolerance: Double = 0.03) -> Double? {
        let snapped = GoatShared.FrameRates.shared.snapCounted(fps: fps, tolerance: tolerance)
        return snapped > 0 ? snapped : nil
    }

    /// Mede a cadência do que o VLC mostra: o número de imagens mostradas (que só cresce) a cada leitura, com o
    /// relógio. O VLC não dá o tempo de cada imagem (o Android mede pelos tempos de apresentação); aqui conta-se ao
    /// longo de [windowMs] e aproxima-se das habituais. Um contador que volta atrás (outro canal, reabrir o anel)
    /// recomeça a medida.
    public struct Counter: Sendable {
        private var first: (ms: Int64, frames: Int)?
        private var last: (ms: Int64, frames: Int)?
        public let windowMs: Int64

        public init(windowMs: Int64 = 2_500) {
            self.windowMs = windowMs
        }

        public mutating func reset() {
            first = nil
            last = nil
        }

        /// Mais uma leitura: [frames] imagens mostradas até [ms]. Devolve a cadência quando já há tempo que chegue.
        public mutating func add(ms: Int64, frames: Int) -> Double? {
            if let l = last, frames < l.frames || ms < l.ms { reset() }
            // Enquanto não aparece a primeira imagem, a contagem ainda não começou.
            guard frames > 0 else { return nil }
            if first == nil {
                first = (ms, frames)
                last = (ms, frames)
                return nil
            }
            // O contador anda aos saltos: conta-se só entre leituras em que mudou.
            if frames != last!.frames { last = (ms, frames) }
            guard let f = first, let l = last, l.ms - f.ms >= windowMs, l.frames > f.frames else { return nil }
            let fps = Double(l.frames - f.frames) * 1000 / Double(l.ms - f.ms)
            return snapCounted(fps)
        }
    }
}
