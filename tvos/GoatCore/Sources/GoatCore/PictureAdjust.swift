import Foundation
import GoatShared

/// Os ajustes da imagem (Definições → Imagem): o brilho, o contraste e a saturação de −5 a +5, a nitidez de 0 a 5;
/// 0 = a imagem como vem. As contas são as do núcleo partilhado com o Android (shared/…/media/PictureLook.kt), aqui já
/// nos números que o shader de Metal usa sobre o Y e o CbCr (ver InterlacedVideo no leitor).
public struct PictureAdjust: Equatable, Sendable {
    public let brightness: Int
    public let contrast: Int
    public let saturation: Int
    public let sharpness: Int
    /// Quanto se multiplica o Y (o contraste).
    public let lumaGain: Float
    /// O que se soma ao Y, na gama limitada (16–235) e na inteira: o contraste a rodar no cinzento médio e o brilho.
    public let lumaBiasVideo: Float
    public let lumaBiasFull: Float
    /// Quanto se multiplica o Cb e o Cr à volta do meio (o contraste e a saturação).
    public let chromaGain: Float
    /// Quanto da diferença para os vizinhos se junta ao Y (a nitidez).
    public let sharpen: Float

    /// Na Apple os ajustes obrigam a descodificar na app e a passar cada imagem pelo Metal: só até 1080 linhas.
    public static let maxHeight = Int(GoatShared.PictureLook.companion.APPLE_MAX_HEIGHT)
    public static let range = -5...5
    public static let sharpnessRange = 0...5

    public init(brightness: Int = 0, contrast: Int = 0, saturation: Int = 0, sharpness: Int = 0) {
        let look = AppleBridge.shared.pictureLook(brightness: Int32(brightness), contrast: Int32(contrast),
                                                  saturation: Int32(saturation), sharpness: Int32(sharpness))
        self.brightness = Int(look.brightness)
        self.contrast = Int(look.contrast)
        self.saturation = Int(look.saturationStep)
        self.sharpness = Int(look.sharpness)
        lumaGain = look.gain
        lumaBiasVideo = look.lumaBias(fullRange: false)
        lumaBiasFull = look.lumaBias(fullRange: true)
        chromaGain = look.chromaGain
        sharpen = look.sharpen
    }

    /// Os ajustes das definições.
    public init(_ prefs: Prefs) {
        self.init(brightness: prefs.pictureBrightness, contrast: prefs.pictureContrast,
                  saturation: prefs.pictureSaturation, sharpness: prefs.pictureSharpness)
    }

    /// Tudo no 0: a imagem vai como vem.
    public var neutral: Bool { brightness == 0 && contrast == 0 && saturation == 0 && sharpness == 0 }
}

/// O nome (sem extensão) de uma imagem guardada do canal ("Guardar imagem"): o nome do canal e a hora, o mesmo do
/// Android (shared/…/playback/PictureName.kt).
public enum SavedPictureName {
    public static func of(channel: String, at date: Date = Date()) -> String {
        AppleBridge.shared.pictureName(channel: channel, epochMs: Int64(date.timeIntervalSince1970 * 1000))
    }
}
