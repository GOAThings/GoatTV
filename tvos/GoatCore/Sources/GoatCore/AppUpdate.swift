import Foundation

/// Versões novas (como no Updater do GoatTV Android): as releases do GOAThings/GoatTV levam a APK e a IPA, por isso
/// https://github.com/GOAThings/GoatTV/releases/latest/download/GoatTV.ipa é sempre a da Apple TV mais recente.
public enum AppUpdate {
    public static let latestRelease = URL(string: "https://api.github.com/repos/GOAThings/GoatTV/releases/latest")!
    public static let ipaName = "GoatTV.ipa"

    /// A versão da release mais recente (`tag_name` sem o "v"), se trouxer a IPA.
    public static func version(fromLatest json: JSON) -> String? {
        guard let tag = json["tag_name"]?.text,
              (json["assets"]?.array ?? []).contains(where: { $0["name"]?.text == ipaName }) else { return nil }
        return tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
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
