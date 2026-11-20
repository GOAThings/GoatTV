import CoreGraphics
import CoreVideo
import Foundation
import GoatShared

/// Um rectângulo em frações da imagem (0…1), a contar de cima e da esquerda: a parte do quadro com imagem, ou o que se
/// mostra dele (ver [window]).
public struct VideoCrop: Equatable, Sendable {
    public let left: Double
    public let top: Double
    public let right: Double
    public let bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public static let full = VideoCrop(left: 0, top: 0, right: 1, bottom: 1)

    public var width: Double { right - left }
    public var height: Double { bottom - top }

    /// A imagem toda (nada a cortar; menos de 0,2 % não conta, como no núcleo).
    public var isFull: Bool { left <= 0.002 && top <= 0.002 && right >= 0.998 && bottom >= 0.998 }

    /// As contas do núcleo partilhado (não guardam nada).
    private static let math = AppleAutoCrop()

    /// O que mostrar do quadro (proporção [frameAspect]) para esta parte com imagem encher um ecrã com a proporção
    /// [screenAspect] sem nada dela ficar de fora (AutoCrop.window no núcleo): um 4:3 com um 16:9 dentro enche o ecrã
    /// 16:9, um 16:9 com barras de cinema fica como está.
    public func window(frameAspect: Double, screenAspect: Double) -> VideoCrop {
        guard !isFull, frameAspect > 0, screenAspect > 0 else { return .full }
        let r = Self.math.window(left: Float(left), top: Float(top), right: Float(right), bottom: Float(bottom),
                                 frameAspect: Float(frameAspect), screenAspect: Float(screenAspect))
        let w = VideoCrop(left: Double(r.left), top: Double(r.top), right: Double(r.right), bottom: Double(r.bottom))
        return w.isFull ? .full : w
    }
}

/// Cortar as barras pretas (Definições → Cortar barras pretas, `auto_crop`): as decisões são as do núcleo partilhado
/// com o Android (shared/…/media/AutoCrop.kt). Primeiro o AFD do canal ([afd], de cada imagem); sem ele, as barras que
/// se veem na imagem ([measure]: uma grelha de 128×72 pontos do Y, lida no CPU uma vez por segundo de vídeo das
/// imagens já descodificadas, ~9 KB). A hora é a do vídeo (ms), a mesma nas duas. Um por canal.
///
/// Não é seguro entre threads: quem usa chama tudo da mesma fila.
public final class BlackBarDetector {
    private let core = AppleAutoCrop()
    private let cols: Int
    private let rows: Int
    private var grid: Data
    private var lastProbe: Int64 = .min

    public init() {
        cols = Int(core.gridCols)
        rows = Int(core.gridRows)
        grid = Data(count: cols * rows)
    }

    /// A parte do quadro com imagem agora.
    public var active: VideoCrop {
        VideoCrop(left: Double(core.left), top: Double(core.top), right: Double(core.right), bottom: Double(core.bottom))
    }

    /// O quadro mostra-se com [width]×[height] (já com a proporção do píxel); true se [active] mudou.
    public func frame(width: Int, height: Int) -> Bool {
        core.frame(width: Int32(width), height: Int32(height))
    }

    /// O AFD de uma imagem (-1 sem ele) à hora [ms] do vídeo; true se [active] mudou.
    public func afd(_ code: Int, ms: Int64) -> Bool {
        core.afd(code: Int32(code), nowMs: ms)
    }

    /// Uma imagem descodificada (NV12 de 8 bits) à hora [ms] do vídeo: mede-se se já passou um segundo desde a última
    /// (ou se o tempo voltou atrás); true se [active] mudou.
    public func measure(_ image: CVPixelBuffer, ms: Int64) -> Bool {
        if lastProbe != .min, ms >= lastProbe, ms - lastProbe < core.probeMs { return false }
        lastProbe = ms
        let format = CVPixelBufferGetPixelFormatType(image)
        let black: Int32
        switch format {
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange: black = 16
        case kCVPixelFormatType_420YpCbCr8BiPlanarFullRange: black = 0
        default: return false
        }
        guard CVPixelBufferLockBaseAddress(image, .readOnly) == kCVReturnSuccess else { return false }
        defer { CVPixelBufferUnlockBaseAddress(image, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(image, 0) else { return false }
        let planeWidth = CVPixelBufferGetWidthOfPlane(image, 0)
        let planeHeight = CVPixelBufferGetHeightOfPlane(image, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(image, 0)
        // Só a parte que se mostra (o 1080 vem muitas vezes em 1088 linhas, com as 8 de baixo fora).
        let clean = CVImageBufferGetCleanRect(image)
        var x0 = 0, y0 = 0, width = planeWidth, height = planeHeight
        if clean.width >= 16, clean.height >= 16 {
            x0 = max(0, Int(clean.minX))
            y0 = max(0, Int(clean.minY))
            width = min(planeWidth - x0, Int(clean.width))
            height = min(planeHeight - y0, Int(clean.height))
        }
        guard width > 0, height > 0 else { return false }
        let src = base.assumingMemoryBound(to: UInt8.self)
        let cols = self.cols, rows = self.rows
        grid.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
            guard let out = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            for r in 0..<rows {
                let line = src + (y0 + (2 * r + 1) * height / (2 * rows)) * stride + x0
                for c in 0..<cols {
                    out[r * cols + c] = line[(2 * c + 1) * width / (2 * cols)]
                }
            }
        }
        return core.luma(grid: grid, black: black, nowMs: ms)
    }
}
