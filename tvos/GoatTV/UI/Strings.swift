import Foundation

/// Os textos da app. Por agora em inglês e português (o idioma das definições da conta, ou o do Apple TV).
struct Strings {
    /// As línguas do GoatTV Android (os mesmos códigos na conta).
    static let languages: [(code: String, name: String)] = [
        ("en", "English"), ("es", "Español"), ("pt-PT", "Português (PT)"), ("pt-BR", "Português (BR)"),
        ("fr", "Français"), ("de", "Deutsch"), ("it", "Italiano"), ("ru", "Русский"), ("tr", "Türkçe"),
        ("id", "Indonesia"), ("hi", "हिन्दी"), ("ar", "العربية"), ("zh-CN", "简体中文"), ("ja", "日本語"), ("ko", "한국어"),
    ]

    /// O código em uso (um de [languages]).
    let lang: String
    /// Português (os textos em português estão escritos ao lado dos ingleses, no código).
    var pt: Bool { lang == "pt-PT" }

    init(language: String) {
        lang = Strings.resolve(language)
    }

    /// "auto" segue o Apple TV; o antigo "pt" é pt-PT; uma língua que não há fica em inglês.
    static func resolve(_ language: String) -> String {
        if language == "pt" { return "pt-PT" }
        if languages.contains(where: { $0.code == language }) { return language }
        for preferred in Locale.preferredLanguages {
            let l = preferred.lowercased()
            if l.hasPrefix("pt") { return l.contains("br") ? "pt-BR" : "pt-PT" }
            if l.hasPrefix("zh") { return "zh-CN" }
            if let match = languages.first(where: { l.hasPrefix($0.code.lowercased()) }) { return match.code }
        }
        return "en"
    }

    /// O texto na língua em uso: o português e o inglês estão no código; as outras línguas vêm da tabela
    /// (Translations.swift, com as traduções do GoatTV Android). [args] substituem `%@` (ou `%1$@`, `%2$@`…).
    func t(_ en: String, _ pt: String, _ args: String...) -> String {
        let text: String
        switch lang {
        case "en": text = en
        case "pt-PT": text = pt
        default:
            if let key = AndroidStrings.keyForEnglish[en], let translated = AndroidStrings.table[lang]?[key] {
                text = translated
            } else {
                text = Translations.table[lang]?[en] ?? (lang == "pt-BR" ? pt : en)
            }
        }
        return Strings.fill(text, args)
    }

    /// Um texto do GoatTV Android pelo nome do recurso (`err_playlist_bad_login_2`), na língua em uso.
    func r(_ key: String, _ args: String...) -> String {
        let text = AndroidStrings.table[lang]?[key] ?? AndroidStrings.table["en"]?[key] ?? key
        return Strings.fill(text, args)
    }

    /// Um plural do Android (`streams_limit`): "one" com 1, senão "other".
    func plural(_ key: String, _ n: Int) -> String {
        let table = AndroidStrings.table[lang] ?? AndroidStrings.table["en"] ?? [:]
        let text = (n == 1 ? table[key + "_one"] : nil) ?? table[key + "_other"] ?? table[key + "_one"] ?? key
        return Strings.fill(text, ["\(n)"])
    }

    static func fill(_ text: String, _ args: [String]) -> String {
        guard !args.isEmpty else { return text }
        var out = text
        for (i, a) in args.enumerated() { out = out.replacingOccurrences(of: "%\(i + 1)$@", with: a) }
        var next = 0
        while let r = out.range(of: "%@"), next < args.count {
            out.replaceSubrange(r, with: args[next])
            next += 1
        }
        return out
    }

    var locale: Locale { Locale(identifier: lang.replacingOccurrences(of: "-", with: "_")) }

    // Ativação
    var activateTitle: String { t("Connect this Apple TV to your GOAT account", "Liga esta Apple TV à tua conta GOAT") }
    func activateStep1(_ url: String) -> String { t("On your phone or computer, open %@", "No telemóvel ou no computador, abre %@", "\(url)") }
    var activateStep2: String { t("Sign in and enter this code:", "Entra na conta e escreve este código:") }
    var activateScan: String { t("Or scan with your phone", "Ou lê com o telemóvel") }
    var activateWaiting: String { t("Waiting for approval…", "À espera de aprovação…") }
    var activateOffline: String { t("No internet connection. Trying again…", "Sem ligação à internet. A tentar de novo…") }
    func activateServerError(_ code: Int) -> String { t("The server answered %@. Trying again…", "O servidor respondeu %@. A tentar de novo…", "\(code)") }
    var activatePlaylistsHint: String { t("Your playlists, favourites and settings are the same on every TV.", "As listas, favoritos e definições são os mesmos em todas as TVs.") }

    // Carregar e erros
    func loading(_ name: String) -> String { t("Loading %@…", "A carregar %@…", "\(name)") }
    var noPlaylistsTitle: String { t("No playlists yet", "Ainda não há listas") }
    var noPlaylistsBody: String { t("Add a playlist at thegoat.systems and it will show up here.", "Junta uma lista em thegoat.systems e ela aparece aqui.") }
    var retry: String { t("Try again", "Tentar de novo") }
    var signOut: String { t("Sign out", "Terminar sessão") }
    var sessionEnded: String { t("This TV was signed out of the account.", "Esta TV saiu da conta.") }
    var cancel: String { t("Cancel", "Cancelar") }

    func sourceError(_ e: Error) -> String {
        explainPlaylistError("", e).title
    }

    // Grupos e canais
    var favorites: String { t("★ Favourites", "★ Favoritos") }
    var recents: String { t("Recent", "Recentes") }
    var all: String { t("All", "Todos") }
    var others: String { t("Others", "Outros") }
    var groups: String { t("Groups", "Grupos") }
    var playlists: String { t("Playlists", "Listas") }
    func channelCount(_ n: Int) -> String { n == 1 ? t("1 channel", "1 canal") : t("%@ channels", "%@ canais", "\(n)") }
    func channelName(_ n: String) -> String { t("Channel %@", "Canal %@", "\(n)") }
    var noGuideInfo: String { t("No information", "Sem informação") }
    var watchingNow: String { t("Watching now", "A ver agora") }
    var next: String { t("Next", "A seguir") }
    var now: String { t("Now", "Agora") }
    var addFavorite: String { t("Add to favourites", "Juntar aos favoritos") }
    var removeFavorite: String { t("Remove from favourites", "Tirar dos favoritos") }
    func favoriteAdded(_ name: String) -> String { t("%@ added to favourites", "%@ juntado aos favoritos", "\(name)") }
    func favoriteRemoved(_ name: String) -> String { t("%@ removed from favourites", "%@ tirado dos favoritos", "\(name)") }
    var noChannels: String { t("No channels in this group", "Não há canais neste grupo") }
    var account: String { t("Account", "Conta") }

    // Player
    var streamFailed: String { t("This channel isn't working right now. Trying again…", "Este canal não está a dar agora. A tentar de novo…") }
    var saveOffline: String { t("No internet: the change wasn't saved to the account.", "Sem internet: a alteração não ficou gravada na conta.") }
    func saveFailed(_ code: Int) -> String { t("Couldn't save to the account (%@).", "Não foi possível gravar na conta (%@).", "\(code)") }
}

/// Os textos do GoatTV Android (Resources/android_strings.json, gerado por scripts/import-android-strings.py).
enum AndroidStrings {
    static let table: [String: [String: String]] = {
        guard let url = Bundle.main.url(forResource: "android_strings", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONDecoder().decode([String: [String: String]].self, from: data) else { return [:] }
        return json
    }()

    /// Texto inglês (com %@ no lugar dos valores) → nome do recurso: os textos iguais aos do Android usam as
    /// traduções dele.
    static let keyForEnglish: [String: String] = {
        var map: [String: String] = [:]
        for (key, value) in table["en"] ?? [:] {
            let normalized = value.replacingOccurrences(of: #"%\d\$@"#, with: "%@", options: .regularExpression)
            if map[normalized] == nil { map[normalized] = key }
        }
        return map
    }()
}

