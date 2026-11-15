import Foundation
import GoatShared

/// O país de cada grupo pelo nome, para o filtro de países (o núcleo partilhado, GroupCountries.kt, dá os mesmos do
/// Android): ISO 3166 em minúsculas, as regiões M.49 ("419", "002", "142", "029"), "arab", "exyu" e "none".
public enum GroupCountry {
    /// Os grupos sem país.
    public static let none = "none"

    /// O país de cada nome, lado a lado.
    public static func codes(_ names: [String]) -> [String] { AppleBridge.shared.groupCountryCodes(names: names) }

    /// Os países dos grupos com quantos grupos tem cada um, do que tem mais para o que tem menos; os sem país no fim.
    public static func counts(_ names: [String]) -> [(code: String, count: Int)] {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for c in codes(names) {
            if counts[c] == nil { order.append(c) }
            counts[c, default: 0] += 1
        }
        let sorted = order.filter { $0 != none }.enumerated()
            .sorted { (counts[$0.element]!, -$0.offset) > (counts[$1.element]!, -$1.offset) }
            .map { (code: $0.element, count: counts[$0.element]!) }
        return sorted + (counts[none].map { [(code: none, count: $0)] } ?? [])
    }

    /// O nome do país ou da região na língua [locale]; [noneName] para os sem país.
    public static func name(_ code: String, locale: Locale, noneName: String) -> String {
        switch code {
        case none: return noneName
        case "exyu": return "Ex-YU"
        case "arab": return locale.localizedString(forLanguageCode: "ar")?.capitalized(with: locale) ?? "Arabic"
        default: return locale.localizedString(forRegionCode: code.uppercased()) ?? code.uppercased()
        }
    }
}
