import Foundation

/// As traduções dos textos que não existem no GoatTV Android (Resources/translations.json; os outros vêm de
/// android_strings.json). língua → texto inglês → tradução.
enum Translations {
    static let table: [String: [String: String]] = {
        guard let url = Bundle.main.url(forResource: "translations", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONDecoder().decode([String: [String: String]].self, from: data) else { return [:] }
        return json
    }()
}
