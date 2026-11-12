import Foundation
import GoatShared

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
        GoatShared.Versions.shared.highlights(notes: notes, max: Int32(clamping: max))
    }

    /// [remote] é mais nova do que [current] (a regra do núcleo partilhado com o Android).
    public static func isNewer(_ remote: String, than current: String) -> Bool {
        GoatShared.Versions.shared.isNewer(remote: remote, current: current)
    }
}
