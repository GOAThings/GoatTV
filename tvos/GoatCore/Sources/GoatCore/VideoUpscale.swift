import Foundation
import GoatShared

/// Aumentar a imagem na Apple (Definições → Imagem, `upscale`: "off", "sharp" ou "ai", os valores do Android). Na
/// Apple os dois modos são o mesmo, o aumento espacial do MetalFX (o leitor faz o resto, ver InterlacedVideo): as
/// decisões são as do núcleo partilhado (shared/…/media/Upscaling.kt), o modo por tamanho do vídeo e do ecrã e a
/// descida quando a GPU não acompanha, e o que cada resolução aguentou fica para o resto da sessão (da app).
///
/// Não é seguro entre threads: quem usa chama tudo da mesma fila.
public final class VideoUpscale {
    /// A memória da app inteira (ver UpscaleMemory no núcleo).
    private static let memory = AppleUpscaleMemory()
    private let setting: String
    private let core: AppleUpscale

    /// [setting]: a definição; "ai" é aqui o mesmo que "sharp" (o MetalFX), por isso uma descida já desliga.
    public init(setting: String) {
        self.setting = setting == "off" ? "off" : "sharp"
        core = AppleUpscale(setting: self.setting, memory: Self.memory)
    }

    /// Vale a pena mandar o vídeo [width]×[height] pelo caminho que o aumenta (ecrã [screenWidth]×[screenHeight]).
    public func wanted(width: Int, height: Int, screenWidth: Int, screenHeight: Int) -> Bool {
        Self.memory.wanted(setting: setting, width: Int32(width), height: Int32(height),
                           screenWidth: Int32(screenWidth), screenHeight: Int32(screenHeight))
    }

    /// Um vídeo novo (ou de outro tamanho): true se se aumenta. [supported]: o aparelho tem o MetalFX.
    public func start(width: Int, height: Int, screenWidth: Int, screenHeight: Int, supported: Bool) -> Bool {
        core.start(width: Int32(width), height: Int32(height), screenWidth: Int32(screenWidth), screenHeight: Int32(screenHeight),
                   bitDepth: 8, hdr: false, supported: supported) != 0
    }

    /// Está a aumentar-se.
    public var active: Bool { core.mode != 0 }

    /// O tamanho da imagem aumentada (cabe no ecrã com a proporção do vídeo; nunca mais do que 3840×2160).
    public func target(screenWidth: Int, screenHeight: Int) -> (width: Int, height: Int) {
        (Int(core.targetWidth(screenWidth: Int32(screenWidth), screenHeight: Int32(screenHeight))),
         Int(core.targetHeight(screenWidth: Int32(screenWidth), screenHeight: Int32(screenHeight))))
    }

    /// Uma imagem aumentada que levou [busyMicros] de GPU com [intervalMicros] para isso: true quando deixou de
    /// acompanhar (desligou-se, e fica assim nesta resolução).
    public func frame(busyMicros: Int64, intervalMicros: Int64) -> Bool {
        let nowMs = Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
        return core.frame(nowMs: nowMs, busyUs: busyMicros, intervalUs: intervalMicros)
    }

    /// Falhou (o MetalFX, a GPU): desliga-se já, e fica assim nesta resolução.
    public func fail() {
        _ = core.fail()
    }

    /// A última janela medida (para o registo): imagens e atrasadas.
    public var lastFrames: Int { Int(core.lastFrames) }
    public var lastLate: Int { Int(core.lastLate) }
}
