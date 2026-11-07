import Foundation

/// Versões novas (como no Updater do GoatTV Android): as releases do GOAThings/GoatTV levam a APK e a IPA, por isso
/// https://github.com/GOAThings/GoatTV/releases/latest/download/GoatTV.ipa é sempre a da Apple TV mais recente.
public enum AppUpdate {
    public static let latestRelease = URL(string: "https://api.github.com/repos/GOAThings/GoatTV/releases/latest")!
    public static let ipaName = "GoatTV.ipa"
    public static let iPhoneIPA = URL(string: "https://github.com/GOAThings/GoatTV/releases/latest/download/GoatTV-iPhone.ipa")!
    /// A página da versão mais recente, onde o iPhone vai buscar a sua IPA.
    public static let releasePage = URL(string: "https://github.com/GOAThings/GoatTV/releases/latest")!

    /// A versão da release mais recente (`tag_name` sem o "v"), se trouxer a IPA.
    public static func version(fromLatest json: JSON) -> String? {
        guard let tag = json["tag_name"]?.text,
              (json["assets"]?.array ?? []).contains(where: { $0["name"]?.text == ipaName }) else { return nil }
        return tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
    }

    /// As notas da release (o texto em Markdown do GitHub), ou "" se não as tiver.
    public static func notes(fromLatest json: JSON) -> String {
        json["body"]?.text ?? ""
    }

    /// As novidades das notas da versão (como o releaseHighlights do Android): os primeiros pontos de lista, sem
    /// negrito. Notas sem lista dão as primeiras linhas que não são títulos ("Android TV:").
    public static func highlights(_ notes: String, max: Int = 3) -> [String] {
        let lines = notes.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let bullets = lines.filter { $0.hasPrefix("- ") || $0.hasPrefix("* ") }.map { String($0.dropFirst(2)) }
        let picked = bullets.isEmpty ? lines.filter { !$0.hasSuffix(":") && !$0.hasPrefix("#") } : bullets
        return Array(picked
            .map { $0.replacingOccurrences(of: "**", with: "").trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .prefix(max))
    }

    public static func isNewer(_ remote: String, than current: String) -> Bool {
        func parts(_ v: String) -> [Int] {
            (v.hasPrefix("v") ? String(v.dropFirst()) : v).split(whereSeparator: { $0 == "." || $0 == "-" }).map { Int($0) ?? 0 }
        }
        let a = parts(remote), b = parts(current)
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }
}
