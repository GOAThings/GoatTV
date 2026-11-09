import Foundation

/// As línguas das faixas de áudio e de legendas (o `Languages` do GoatTV Android). Os canais marcam-nas de muitas
/// maneiras: ISO 639-1 ("pt"), 639-2 nas duas formas ("por", "ger"/"deu"), com região ("pt-BR", "pt_PT"), em
/// maiúsculas, pelo nome em inglês ou na própria língua ("Portuguese", "Português") ou dentro de uma etiqueta ("Faixa 1
/// - [Portuguese]", "POR AC3"). [code] reduz tudo ao código ISO 639-1 (o das Definições); os códigos que não dizem a
/// língua ("und", "mul", "qaa"…) dão nil.
public enum Languages {
    /// As línguas a escolher nas Definições (ISO 639-1); o ecrã ordena-as pelo nome na língua da app.
    public static let choices = [
        "ar", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hr", "hu",
        "hy", "id", "it", "ja", "ka", "kk", "ko", "ku", "lt", "lv", "mk", "ml", "ms", "nl", "no", "pa", "pl", "pt", "ro",
        "ru", "sk", "sl", "sq", "sr", "sv", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh",
    ]

    /// O nome de [code] na língua [locale] (um identificador: "pt-PT", "ja"…), com maiúscula (como nas faixas).
    public static func displayName(_ code: String, locale: String) -> String {
        let where_ = Locale(identifier: locale.replacingOccurrences(of: "-", with: "_"))
        let name = where_.localizedString(forLanguageCode: code) ?? code
        guard let first = name.first else { return code }
        return String(first).uppercased(with: where_) + name.dropFirst()
    }

    /// O código ISO 639-1 da língua de [raw] (um código, um nome ou uma etiqueta com um deles), ou nil se não disser a
    /// língua. Os códigos antigos ("iw", "in", "ji") e as variantes do norueguês dão o mesmo que os atuais.
    public static func code(_ raw: String?) -> String? {
        let text = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return nil }
        if let t = tag(text) { return t == unknown ? nil : t }
        if let c = names[fold(text)] { return c }
        // Uma etiqueta: primeiro o que está entre parênteses retos ou curvos ("Faixa 1 - [Portuguese]"), depois as palavras.
        for inner in bracketed(text) {
            if let c = code(inner) { return c }
        }
        let ws = words(text)
        for (i, w) in ws.enumerated() {
            if w.count >= 4, let c = names[fold(w)] { return c }
            // Nomes de duas palavras ("Scottish Gaelic", "Norwegian Bokmål").
            if i + 1 < ws.count, let c = names[fold(w + " " + ws[i + 1])] { return c }
            // Os códigos de três letras numa etiqueta ("POR 5.1"), menos os que também são palavras comuns.
            if w.count == 3, !commonWords.contains(fold(w)), let c = iso3[fold(w)] { return c }
        }
        return nil
    }

    /// [raw] diz a língua [wanted] (um código ISO 639-1)?
    public static func matches(_ raw: String?, _ wanted: String) -> Bool { code(raw) == wanted }

    // MARK: Por dentro

    private static let unknown = ""

    /// Sem língua: indeterminada, várias, sem fala, outras; "qaa"–"qtz" são de uso local (o DVB põe "qaa" na versão original).
    private static let undetermined: Set<String> = ["und", "mul", "zxx", "mis", "unk", "nar", "xx"]

    /// Códigos de três letras que numa etiqueta costumam ser palavras, não línguas.
    private static let commonWords: Set<String> = ["may", "sun", "her", "mon", "son", "new", "tel", "nor", "art", "aac", "pcm", "dts", "mp2", "mp3"]

    /// Um código ("pt", "por", "pt-BR"): o ISO 639-1, [unknown] se for um que não diz a língua, nil se não for um código.
    private static func tag(_ text: String) -> String? {
        guard isTag(text) else { return nil }
        let primary = String(text.split(whereSeparator: { $0 == "-" || $0 == "_" }).first ?? "").lowercased()
        if undetermined.contains(primary) || (primary.count == 3 && primary >= "qaa" && primary <= "qtz") { return unknown }
        if primary.count == 2 {
            let c = canonical(primary)
            return iso2.contains(c) ? c : nil
        }
        return iso3[primary]
    }

    /// `[A-Za-z]{2,3}([-_][A-Za-z0-9]{1,8})*`
    private static func isTag(_ text: String) -> Bool {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false).flatMap { $0.split(separator: "_", omittingEmptySubsequences: false) }
        guard let first = parts.first, (2...3).contains(first.count), first.allSatisfy({ $0.isASCII && $0.isLetter }) else { return false }
        return parts.dropFirst().allSatisfy { p in (1...8).contains(p.count) && p.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) } }
    }

    /// O que está dentro de `[…]` ou `(…)`, por ordem (`[\[(]([^\])]+)[\])]`).
    private static func bracketed(_ text: String) -> [String] {
        var out: [String] = []
        var open = false
        var current = ""
        for ch in text {
            if ch == "[" || ch == "(" {
                // Um novo começo antes de fechar recomeça (como a expressão do Android, que não aceita "]" nem ")" dentro).
                open = true
                current = ""
            } else if ch == "]" || ch == ")" {
                if open, !current.isEmpty { out.append(current) }
                open = false
                current = ""
            } else if open {
                current.append(ch)
            }
        }
        return out
    }

    /// As sequências de letras (`\p{L}+`).
    private static func words(_ text: String) -> [String] {
        var out: [String] = []
        var current = ""
        for ch in text {
            if ch.isLetter {
                current.append(ch)
            } else if !current.isEmpty {
                out.append(current)
                current = ""
            }
        }
        if !current.isEmpty { out.append(current) }
        return out
    }

    private static func canonical(_ code: String) -> String {
        switch code {
        case "iw": return "he"
        case "in": return "id"
        case "ji": return "yi"
        case "nb", "nn": return "no"
        default: return code
        }
    }

    private static func fold(_ s: String) -> String {
        s.lowercased().folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX"))
            .trimmingCharacters(in: .whitespaces)
    }

    /// ISO 639-1 → 639-2/T, como o Java os dá (Locale.getISOLanguages e getISO3Language; os mesmos códigos do Android).
    private static let isoTable = "aaaarababkaeaveafafrakakaamamhanargararaasasmavavaayaymazazebabakbebelbgbulbhbihbibisbmbambnbenbobodbrbrebsboscacatcechechchacocoscrcrecscescuchucvchvcycymdadandedeudvdivdzdzoeeeweelellenengeoepoesspaetesteueusfafasfffulfifinfjfijfofaofrfrafyfrygaglegdglaglglggngrngugujgvglvhahauhehebhihinhohmohrhrvhthathuhunhyhyehzheriainaidindieileigiboiiiiiikipkinindioidoisislititaiuikuiwhebjajpnjiyidjvjavkakatkgkonkikikkjkuakkkazklkalkmkhmknkankokorkrkaukskaskukurkvkomkwcorkykirlalatlbltzlgluglilimlnlinlolaoltlitlulublvlavmgmlgmhmahmimrimkmkdmlmalmnmonmomolmrmarmsmsamtmltmymyananaunbnobndndenenepngndonlnldnnnnononornrnblnvnavnynyaocociojojiomormororiososspapanpipliplpolpspusptporququermrohrnrunroronrurusrwkinsasanscsrdsdsndsesmesgsagsisinskslkslslvsmsmosnsnasosomsqsqisrsrpsssswstsotsusunsvsweswswatatamteteltgtgkththatitirtktuktltgltntsntotontrturtstsotttattwtwitytahuguigukukrururduzuzbvevenvivievovolwawlnwowolxhxhoyiyidyoyorzazhazhzhozuzul"

    /// Os códigos ISO 639-1 (2 letras) e o 639-2/T de cada um.
    private static let isoPairs: [(String, String)] = {
        let chars = Array(isoTable)
        return stride(from: 0, to: chars.count - 4, by: 5).map { (String(chars[$0..<$0 + 2]), String(chars[$0 + 2..<$0 + 5])) }
    }()

    private static let iso2: Set<String> = Set(isoPairs.map { canonical($0.0) }).union(["he", "id", "yi"])

    /// ISO 639-2: a forma "T", a "B" (bibliográfica, que o DVB e muitas listas usam) e outras que se veem nos canais.
    private static let iso3: [String: String] = {
        var map: [String: String] = [:]
        for (two, three) in isoPairs { map[three] = canonical(two) }
        let extra: [String: String] = [
            "alb": "sq", "arm": "hy", "baq": "eu", "bur": "my", "chi": "zh", "cze": "cs", "dut": "nl",
            "fre": "fr", "geo": "ka", "ger": "de", "gre": "el", "ice": "is", "mac": "mk", "mao": "mi",
            "may": "ms", "per": "fa", "rum": "ro", "slo": "sk", "tib": "bo", "wel": "cy",
            "scc": "sr", "scr": "hr", "nob": "no", "nno": "no", "cmn": "zh", "esp": "es", "jap": "ja",
            "pob": "pt", "ptb": "pt", "chs": "zh", "cht": "zh",
        ]
        map.merge(extra) { _, new in new }
        return map
    }()

    /// Os nomes das línguas em inglês, na própria língua e nas línguas da app (sem acentos nem maiúsculas).
    private static let names: [String: String] = {
        var map: [String: String] = [:]
        let shown = ["en", "es", "pt", "fr", "de", "it", "ru", "tr", "id", "hi", "ar", "zh", "ja", "ko"].map { Locale(identifier: $0) }
        let english = Locale(identifier: "en")
        for (code, _) in isoPairs {
            let c = canonical(code)
            for where_ in [english, Locale(identifier: code)] + shown {
                guard let raw = where_.localizedString(forLanguageCode: code) else { continue }
                let name = fold(raw)
                // Sem nome conhecido devolve-se o próprio código: esses já os trata o [tag].
                if name.count >= 3, name != code, map[name] == nil { map[name] = c }
            }
        }
        // Nomes que se veem nas listas e não são os do sistema.
        let extra = ["portugues": "pt", "castellano": "es", "farsi": "fa", "mandarin": "zh", "cantonese": "zh", "brazilian": "pt"]
        map.merge(extra) { _, new in new }
        return map
    }()
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
}

/// A escolha automática das faixas ao abrir um canal: a língua preferida do áudio e das legendas (Definições) e, na
/// TV, o áudio surround.
public enum TrackPick {
    /// A faixa de áudio a escolher (índice em [tracks]), ou nil para ficar a que está. Com [language], só as dessa
    /// língua (se houver alguma; sem nenhuma, todas); com [surround], a de mais canais se tiver mais de dois.
    public static func audio(_ tracks: [TrackInfo], language: String?, surround: Bool) -> Int? {
        let usable = tracks.indices.filter { tracks[$0].supported }
        if usable.isEmpty { return nil }
        let wanted = language.map { l in usable.filter { tracks[$0].code == l } } ?? []
        let pool = wanted.isEmpty ? usable : wanted
        var pick: Int?
        if surround, let best = pool.max(by: { tracks[$0].channels < tracks[$1].channels }), tracks[best].channels > 2 {
            // Como o maxBy do Kotlin: em empate, a primeira.
            pick = pool.first { tracks[$0].channels == tracks[best].channels }
        }
        if pick == nil, !wanted.isEmpty { pick = wanted.first { tracks[$0].selected } ?? wanted.first }
        guard let pick, !tracks[pick].selected else { return nil }
        return pick
    }

    /// As legendas a ligar (índice em [tracks]) para [language], ou nil para não mexer: sem língua escolhida, ou sem
    /// legendas nessa língua. Entre as dessa língua, as completas antes das forçadas.
    public static func text(_ tracks: [TrackInfo], language: String?) -> Int? {
        guard let language else { return nil }
        let wanted = tracks.indices.filter { tracks[$0].supported && tracks[$0].code == language }
        if wanted.isEmpty { return nil }
        let notForced = wanted.filter { !tracks[$0].forced }
        let full = notForced.isEmpty ? wanted : notForced
        return full.first { tracks[$0].selected } ?? full.first
    }
}

/// Que tipos de faixa ainda falta escolher no canal aberto. Escolhe-se uma vez por abertura (as faixas chegam aos
/// poucos: o áudio e as legendas decidem-se cada um quando aparece). Uma escolha à mão no menu ganha até se mudar de
/// canal: o catch-up, os saltos e as reconexões do mesmo canal não a desfazem.
public struct TrackChoice: Sendable {
    public enum Kind: Hashable, Sendable { case audio, text }

    private var channel: String?
    private var decided: Set<Kind> = []
    private var manual: Set<Kind> = []

    public init() {}

    /// Abriu-se [channelKey] (outro canal, ou o mesmo noutro ponto): volta a escolher, menos o que se escolheu à mão nele.
    public mutating func onOpen(_ channelKey: String) {
        if channelKey != channel {
            channel = channelKey
            manual.removeAll()
        }
        decided.removeAll()
    }

    public mutating func onManual(_ kind: Kind) { manual.insert(kind) }

    public func isManual(_ kind: Kind) -> Bool { manual.contains(kind) }

    public func pending(_ kind: Kind) -> Bool { !decided.contains(kind) && !manual.contains(kind) }

    public mutating func done(_ kind: Kind) { decided.insert(kind) }
}
