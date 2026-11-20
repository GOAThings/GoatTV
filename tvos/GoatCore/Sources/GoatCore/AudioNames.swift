import Foundation
import GoatShared

/// Os textos (já traduzidos) dos nomes das faixas de áudio; [audioN] é o "Áudio %d" (o %d fica por preencher).
public struct AudioNameWords: Sendable {
    public var original: String
    public var audioDescription: String
    public var hearingImpaired: String
    public var cleanEffects: String
    public var mono: String
    public var stereo: String
    public var audioN: String

    public init(original: String, audioDescription: String, hearingImpaired: String, cleanEffects: String, mono: String, stereo: String, audioN: String) {
        self.original = original
        self.audioDescription = audioDescription
        self.hearingImpaired = hearingImpaired
        self.cleanEffects = cleanEffects
        self.mono = mono
        self.stereo = stereo
        self.audioN = audioN
    }
}

/// Uma faixa de áudio a nomear: a língua já escrita ("" = desconhecida), o papel (AudioRole), os canais (0 = não se
/// sabe) e o formato ("AC-3").
public struct AudioNameInput: Sendable {
    public var language: String
    public var role: Int
    public var channels: Int
    public var codec: String

    public init(language: String, role: Int, channels: Int, codec: String) {
        self.language = language
        self.role = role
        self.channels = channels
        self.codec = codec
    }
}

/// O nome de cada faixa de áudio ("Português · 5.1", "English · Original", "Português · Audiodescrição"), igual ao do
/// Android (AudioTrackLabel.kt no núcleo partilhado).
public enum AudioNames {
    public static func labels(_ items: [AudioNameInput], words: AudioNameWords) -> [String] {
        let shared = GoatShared.AudioLabelWords(original: words.original, audioDescription: words.audioDescription,
                                                hearingImpaired: words.hearingImpaired, cleanEffects: words.cleanEffects,
                                                mono: words.mono, stereo: words.stereo, audioN: words.audioN)
        let inputs = items.map {
            GoatShared.AudioLabelInput(language: $0.language, role: Int32(clamping: $0.role), channels: Int32(clamping: $0.channels), codec: $0.codec)
        }
        return AudioTrackLabel.shared.labels(items: inputs, words: shared)
    }

    /// O que distingue a faixa na memória do canal (TrackInfo.detail, igual ao do Android): "ad", "hi", "sup", "orig".
    public static func detail(role: Int) -> String? {
        AudioRole.shared.detail(role: Int32(clamping: role))
    }

    /// O formato como se escreve no nome ("AAC", "AC-3", "E-AC-3", "MP2"), a partir do nome do núcleo partilhado.
    public static func codecName(_ codec: String) -> String {
        switch codec {
        case "AAC_ADTS", "AAC_LATM": return "AAC"
        case "AC3": return "AC-3"
        case "EAC3": return "E-AC-3"
        case "MPEG_AUDIO": return "MP2"
        default: return ""
        }
    }
}
