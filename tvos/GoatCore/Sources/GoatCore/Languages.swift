import Foundation
import GoatShared

/// As línguas das faixas de áudio e de legendas (as do núcleo partilhado com o GoatTV Android). Os canais marcam-nas de muitas
/// maneiras: ISO 639-1 ("pt"), 639-2 nas duas formas ("por", "ger"/"deu"), com região ("pt-BR", "pt_PT"), em
/// maiúsculas, pelo nome em inglês ou na própria língua ("Portuguese", "Português") ou dentro de uma etiqueta ("Faixa 1
/// - [Portuguese]", "POR AC3"). [code] reduz tudo ao código ISO 639-1 (o das Definições); os códigos que não dizem a
/// língua ("und", "mul", "qaa"…) dão nil.
public enum Languages {
    /// As línguas a escolher nas Definições (ISO 639-1); o ecrã ordena-as pelo nome na língua da app.
    public static let choices: [String] = GoatShared.Languages.shared.CHOICES

    /// O nome de [code] na língua [locale] (um identificador: "pt-PT", "ja"…), com maiúscula (como nas faixas).
    public static func displayName(_ code: String, locale: String) -> String {
        let where_ = Locale(identifier: locale.replacingOccurrences(of: "-", with: "_"))
        let name = where_.localizedString(forLanguageCode: code) ?? code
        guard let first = name.first else { return code }
        return String(first).uppercased(with: where_) + name.dropFirst()
    }

    /// O código ISO 639-1 da língua de [raw] (um código, um nome ou uma etiqueta com um deles), ou nil se não disser a
    /// língua. A regra e as tabelas são as do núcleo partilhado com o Android.
    public static func code(_ raw: String?) -> String? { GoatShared.Languages.shared.code(raw: raw) }

    /// [raw] diz a língua [wanted] (um código ISO 639-1)?
    public static func matches(_ raw: String?, _ wanted: String) -> Bool { code(raw) == wanted }
}

/// Uma faixa de áudio ou de legendas, como a escolha automática a vê (o TrackInfo do Android).
public struct TrackInfo: Equatable, Sendable {
    /// A língua que o stream diz ("pt", "por", "qaa"…).
    public var language: String?
    /// A etiqueta (o nome HLS, ou a descrição do VLC: "Faixa 1 - [Portuguese]").
    public var label: String?
    public var channels: Int
    public var supported: Bool
    public var selected: Bool
    /// Legendas "forçadas": só as falas noutra língua, não o programa todo.
    public var forced: Bool

    public init(language: String?, label: String? = nil, channels: Int = 2, supported: Bool = true, selected: Bool = false, forced: Bool = false) {
        self.language = language
        self.label = label
        self.channels = channels
        self.supported = supported
        self.selected = selected
        self.forced = forced
    }

    /// A língua em ISO 639-1: a do campo da língua ou, sem ela, a que a etiqueta diz.
    public var code: String? { Languages.code(language) ?? Languages.code(label) }

    var shared: GoatShared.TrackInfo {
        GoatShared.TrackInfo(language: language, label: label, channels: Int32(clamping: channels), supported: supported,
                             selected: selected, forced: forced)
    }
}

/// A escolha automática das faixas ao abrir um canal: a língua preferida do áudio e das legendas (Definições) e, na
/// TV, o áudio surround.
public enum TrackPick {
    /// A faixa de áudio a escolher (índice em [tracks]), ou nil para ficar a que está. Com [language], só as dessa
    /// língua (se houver alguma; sem nenhuma, todas); com [surround], a de mais canais se tiver mais de dois.
    public static func audio(_ tracks: [TrackInfo], language: String?, surround: Bool) -> Int? {
        GoatShared.TrackPick.shared.audio(tracks: tracks.map(\.shared), language: language, surround: surround).map { Int($0.int32Value) }
    }

    /// As legendas a ligar (índice em [tracks]) para [language], ou nil para não mexer: sem língua escolhida, ou sem
    /// legendas nessa língua. Entre as dessa língua, as completas antes das forçadas.
    public static func text(_ tracks: [TrackInfo], language: String?) -> Int? {
        GoatShared.TrackPick.shared.text(tracks: tracks.map(\.shared), language: language).map { Int($0.int32Value) }
    }
}

/// Que tipos de faixa ainda falta escolher no canal aberto. Escolhe-se uma vez por abertura (as faixas chegam aos
/// poucos: o áudio e as legendas decidem-se cada um quando aparece). Uma escolha à mão no menu ganha até se mudar de
/// canal: o catch-up, os saltos e as reconexões do mesmo canal não a desfazem.
public struct TrackChoice: @unchecked Sendable {
    public enum Kind: Hashable, Sendable {
        case audio, text

        /// O número do tipo no núcleo partilhado (os C.TRACK_TYPE_AUDIO/TEXT do Android).
        var shared: Int32 { self == .audio ? 1 : 3 }
    }

    // A regra é a do núcleo partilhado com o Android.
    private let core = GoatShared.TrackChoice()

    public init() {}

    /// Abriu-se [channelKey] (outro canal, ou o mesmo noutro ponto): volta a escolher, menos o que se escolheu à mão nele.
    public mutating func onOpen(_ channelKey: String) { core.onOpen(channelKey: channelKey) }

    public mutating func onManual(_ kind: Kind) { core.onManual(type: kind.shared) }

    public func isManual(_ kind: Kind) -> Bool { core.isManual(type: kind.shared) }

    public func pending(_ kind: Kind) -> Bool { core.pending(type: kind.shared) }

    public mutating func done(_ kind: Kind) { core.done(type: kind.shared) }
}
