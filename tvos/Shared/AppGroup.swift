import Foundation

/// O App Group que a app e a Top Shelf partilham. Quem reassina a app pode mudar-lhe o nome; o
/// verdadeiro está no perfil embutido em cada uma (embedded.mobileprovision). Compilado na app e na extensão.
enum AppGroup {
    static let original = "group.cc.slokker.goattv"

    /// Os App Groups do perfil embutido em [bundle]; nil se não houver perfil (ex.: assinatura ad hoc).
    static func profileGroups(_ bundle: Bundle = .main) -> [String]? {
        guard let url = bundle.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data[start.lowerBound..<end.upperBound], format: nil),
              let profile = plist as? [String: Any] else { return nil }
        let entitlements = profile["Entitlements"] as? [String: Any]
        return entitlements?["com.apple.security.application-groups"] as? [String] ?? []
    }

    /// Por esta ordem (a mesma na app e na extensão, para escolherem o mesmo): o original, os do perfil com
    /// "goattv" no nome, os outros do perfil.
    static func candidates(_ bundle: Bundle = .main) -> [String] {
        let profile = profileGroups(bundle) ?? []
        var seen = Set<String>()
        return ([original] + profile.filter { $0.lowercased().contains("goattv") } + profile)
            .filter { !$0.contains("*") && seen.insert($0).inserted }
    }

    /// A pasta partilhada e o grupo dela; nil se a assinatura não deixar nenhuma.
    static func container(_ bundle: Bundle = .main) -> (id: String, url: URL)? {
        for id in candidates(bundle) {
            if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) { return (id, url) }
        }
        return nil
    }

    /// O que a extensão fez da última vez (para o diagnóstico nas Definições).
    static let statusFile = "topshelf-status.json"
}
