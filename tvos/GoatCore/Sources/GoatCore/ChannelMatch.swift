import Foundation

/// O "mesmo canal" noutra lista (a lista de reserva, quando o fornecedor principal não responde). As mesmas regras do
/// GoatTV Android (data/ChannelMatch.kt), conferidas pelos mesmos casos (app/src/test/resources/channel_match.json).
///
/// 1. O id do guia (`tvg-id` / `epg_channel_id`), quando os dois têm e são iguais (sem contar maiúsculas).
/// 2. O nome normalizado ([parse]): sem o país/grupo à frente (`PT|`, `PT:`, `[PT]`, `|PT|`…), sem a qualidade (HD, FHD,
///    UHD, 4K, ᴴᴰ…), sem acentos, pontuação, espaços nem maiúsculas. O `+1` fica: SIC e SIC +1 são canais diferentes.
/// 3. Vários candidatos: o do mesmo país e depois o de melhor qualidade. Se continuar a haver dúvida (países diferentes,
///    ou nomes diferentes com o mesmo id do guia), não se escolhe: nunca se põe a dar um canal errado.
public enum ChannelMatch {
    /// Um nome partido: a [base] que se compara, o país (código de 2 letras), a qualidade ([Quality]) e o `+N`.
    public struct Parsed: Equatable, Sendable {
        public let base: String
        public let country: String?
        public let quality: Int
        public let shift: Int

        public init(base: String, country: String?, quality: Int, shift: Int) {
            self.base = base
            self.country = country
            self.quality = quality
            self.shift = shift
        }
    }

    /// Um canal da lista de reserva (ou o que se procura). [group]: o nome do grupo dele, para desempatar cópias iguais
    /// do mesmo canal em grupos diferentes (ex.: "SIC K" em "Portugal" e em "PT Backup").
    public struct Entry: Equatable, Sendable {
        public let key: String
        public let name: String
        public let epgId: String?
        public let group: String?

        public init(key: String, name: String, epgId: String?, group: String? = nil) {
            self.key = key
            self.name = name
            self.epgId = epgId
            self.group = group
        }

        /// O canal, com o nome do grupo dele em [content] (para desempatar cópias).
        public init(_ c: Channel, in content: PlaylistContent? = nil) {
            self.init(key: c.key, name: c.name, epgId: c.epgId, group: content?.categoryName(c.categoryId))
        }
    }

    public enum Result: Equatable, Sendable {
        /// O canal [index] da lista de reserva.
        case match(Int)
        /// Há [count] candidatos e nada que os desempate: não se muda de lista.
        case unsure(Int)
        case missing

        public var index: Int? {
            if case .match(let i) = self { return i }
            return nil
        }
    }

    /// Escolha à mão canal a canal: a chave do canal na reserva, ou [off] para nunca mudar.
    public static let off = "off"

    public enum Quality {
        public static let sd = 1
        public static let none = 2
        public static let hd = 3
        public static let fhd = 4
        public static let uhd = 5
        public static let q8k = 6
    }

    static let quality: [String: Int] = [
        "sd": Quality.sd, "480p": Quality.sd, "576p": Quality.sd,
        "hd": Quality.hd, "720p": Quality.hd, "hq": Quality.hd,
        "fhd": Quality.fhd, "1080p": Quality.fhd, "1080i": Quality.fhd, "fullhd": Quality.fhd,
        "uhd": Quality.uhd, "4k": Quality.uhd, "2160p": Quality.uhd,
        "8k": Quality.q8k,
    ]

    /// Marcas técnicas que se tiram sem contar para a qualidade.
    static let tech: Set<String> = ["hevc", "h265", "h264", "x265", "x264", "hdr", "hdr10", "sdr", "50fps", "60fps", "25fps", "30fps", "fps", "vip", "raw"]

    /// Palavras de 2 letras que não são países.
    static let notCountry: Set<String> = ["tv", "hd", "sd", "fm", "am", "hq", "lq"]

    static let countryAliases: [String: String] = [
        "uk": "gb", "gbr": "gb", "eng": "gb", "por": "pt", "prt": "pt", "portugal": "pt",
        "bra": "br", "brasil": "br", "brazil": "br", "esp": "es", "espana": "es", "spain": "es",
        "fra": "fr", "france": "fr", "ger": "de", "deu": "de", "germany": "de", "ita": "it", "italia": "it",
        "italy": "it", "usa": "us", "ned": "nl", "nld": "nl", "bel": "be", "pol": "pl", "rou": "ro",
        "tur": "tr", "arg": "ar", "mex": "mx", "can": "ca", "irl": "ie", "che": "ch", "aut": "at",
    ]

    /// As letras pequenas por cima (ᴴᴰ, ᵁᴴᴰ, ⁴ᴷ…) que os fornecedores colam ao nome.
    static let superscript: [Unicode.Scalar: Unicode.Scalar] = [
        "ᴬ": "A", "ᴮ": "B", "ᶜ": "C", "ᴰ": "D", "ᴱ": "E", "ᶠ": "F", "ᴳ": "G", "ᴴ": "H", "ᴵ": "I",
        "ᴶ": "J", "ᴷ": "K", "ᴸ": "L", "ᴹ": "M", "ᴺ": "N", "ᴼ": "O", "ᴾ": "P", "ᴿ": "R", "ˢ": "S",
        "ᵀ": "T", "ᵁ": "U", "ⱽ": "V", "ᵂ": "W", "ˣ": "X", "ʰ": "H", "ᵈ": "D", "ᵏ": "K", "ˡ": "L",
        "ᵖ": "P", "ᵗ": "T", "ᵘ": "U", "ᵛ": "V", "ᵐ": "M", "ⁿ": "N", "ᵒ": "O", "ᵉ": "E", "ᵃ": "A",
        "⁰": "0", "¹": "1", "²": "2", "³": "3", "⁴": "4", "⁵": "5", "⁶": "6", "⁷": "7", "⁸": "8", "⁹": "9",
    ]

    /// O que separa o prefixo do nome: `PT|`, `PT:`, `PT ▎`…
    static let prefixSeparators: Set<Unicode.Scalar> = ["|", ":", "▎", "┃", "│", "¦", "»"]

    static let tagMax = 12

    public static let minScore = 40

    // MARK: O nome

    /// O texto como lista de "letras" (escalares Unicode), para as mesmas contas que o Android faz por carácter.
    typealias Text = [Unicode.Scalar]

    public static func parse(_ name: String) -> Parsed {
        var s = Text(replace(replace(fold(spaceSuperscripts(name)), "h.265", "h265"), "h.264", "h264").unicodeScalars)
        var country: String?
        var quality = 0
        func take(_ tag: Text) {
            for w in words(tag) {
                if let q = Self.quality[w] {
                    quality = max(quality, q)
                } else if tech.contains(w) {
                    continue
                } else if let c = countryOf(w) {
                    if country == nil { country = c }
                }
            }
        }
        // À frente: até 3 etiquetas ("[PT] VIP| RTP 1"); um grupo desconhecido também sai, mas sem país.
        for _ in 0..<3 {
            guard let cut = leadingTag(trimStart(s)) else { break }
            take(cut.tag)
            s = cut.rest
        }
        // No fim: "(PT)", "[FHD]"; só se o que lá está for país ou qualidade (senão é parte do nome).
        for _ in 0..<3 {
            guard let cut = trailingTag(trimEnd(s)), isKnownTag(cut.tag) else { break }
            take(cut.tag)
            s = cut.rest
        }
        let shifted = shift(s)
        var base = ""
        for w in words(shifted.rest) {
            if let q = Self.quality[w] {
                quality = max(quality, q)
            } else if !tech.contains(w) {
                base += w
            }
        }
        return Parsed(base: base, country: country, quality: quality == 0 ? Quality.none : quality, shift: shifted.shift)
    }

    static func isKnownTag(_ tag: Text) -> Bool {
        let w = words(tag)
        return !w.isEmpty && w.allSatisfy { quality[$0] != nil || tech.contains($0) || countryOf($0) != nil }
    }

    static func countryOf(_ word: String) -> String? {
        if let alias = countryAliases[word] { return alias }
        if word.unicodeScalars.count == 2, word.unicodeScalars.allSatisfy({ isAsciiLower($0) }), !notCountry.contains(word) { return word }
        return nil
    }

    static func isAsciiLower(_ c: Unicode.Scalar) -> Bool { c.value >= 97 && c.value <= 122 }

    static func isDigit(_ c: Unicode.Scalar) -> Bool { c.value >= 48 && c.value <= 57 }

    /// Como o Character.isLetterOrDigit do Java: letras (qualquer categoria L) ou algarismos decimais.
    static func isLetterOrDigit(_ c: Unicode.Scalar) -> Bool {
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
        default: return false
        }
    }

    static func isWhitespace(_ c: Unicode.Scalar) -> Bool { c.properties.isWhitespace }

    static func trimStart(_ s: Text) -> Text { Text(s.drop { isWhitespace($0) }) }

    static func trimEnd(_ s: Text) -> Text {
        var end = s.count
        while end > 0, isWhitespace(s[end - 1]) { end -= 1 }
        return Text(s[0..<end])
    }

    static func trim(_ s: Text) -> Text { trimEnd(trimStart(s)) }

    static func replace(_ s: String, _ a: String, _ b: String) -> String { s.replacingOccurrences(of: a, with: b) }

    /// "ᴴᴰ" colado ao nome passa a ser uma palavra à parte ("SICᴴᴰ" → "SIC HD").
    static func spaceSuperscripts(_ name: String) -> String {
        var out = String.UnicodeScalarView()
        var inRun = false
        for c in name.unicodeScalars {
            if let mapped = superscript[c] {
                if !inRun { out.append(" ") }
                out.append(mapped)
                inRun = true
            } else {
                if inRun { out.append(" ") }
                out.append(c)
                inRun = false
            }
        }
        return String(out)
    }

    /// Sem acentos (e com as formas de compatibilidade: ｜ → |), em minúsculas.
    static func fold(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for c in s.decomposedStringWithCompatibilityMapping.unicodeScalars where c.properties.generalCategory != .nonspacingMark {
            out.append(c)
        }
        return String(out).lowercased()
    }

    /// As palavras: o que é letra ou algarismo, separado pelo resto.
    static func words(_ s: Text) -> [String] {
        var out: [String] = []
        var cur = String.UnicodeScalarView()
        for c in s {
            if isLetterOrDigit(c) {
                cur.append(c)
            } else if !cur.isEmpty {
                out.append(String(cur))
                cur = String.UnicodeScalarView()
            }
        }
        if !cur.isEmpty { out.append(String(cur)) }
        return out
    }

    static func firstIndex(_ s: Text, of c: Unicode.Scalar, from: Int) -> Int? {
        guard from < s.count else { return nil }
        for i in from..<s.count where s[i] == c { return i }
        return nil
    }

    static func dropSeparator(_ r: Text) -> Text {
        if let first = r.first, prefixSeparators.contains(first) { return Text(r.dropFirst()) }
        return r
    }

    /// A etiqueta à frente de [s] e o resto: `[pt] …`, `(pt) …`, `|pt| …`, `pt: …`, `pt| …`, `pt - …`.
    static func leadingTag(_ s: Text) -> (tag: Text, rest: Text)? {
        guard let open = s.first else { return nil }
        let close: Unicode.Scalar? = open == "[" ? "]" : open == "(" ? ")" : open == "|" ? "|" : nil
        if let close {
            guard let end = firstIndex(s, of: close, from: 1), end - 1 <= tagMax else { return nil }
            let rest = dropSeparator(trimStart(Text(s[(end + 1)...])))
            return (trim(Text(s[1..<end])), rest)
        }
        if let sep = s.firstIndex(where: { prefixSeparators.contains($0) }), sep >= 1, sep <= tagMax + 1 {
            let tag = trim(Text(s[0..<sep]))
            let parts = String(String.UnicodeScalarView(tag)).split(separator: " ", omittingEmptySubsequences: false)
            if !tag.isEmpty, parts.count <= 2, tag.allSatisfy({ $0 == " " || isLetterOrDigit($0) }) {
                let rest = dropSeparator(Text(s[(sep + 1)...]))
                // "Hollywood: FHD": o que vem depois é só a qualidade, por isso o nome é o que está antes.
                if words(rest).contains(where: { quality[$0] == nil && !tech.contains($0) }) { return (tag, rest) }
            }
        }
        // "pt - rtp 1": só um país de 2–3 letras antes do traço (um traço a meio do nome fica).
        if let dash = indexOf(s, Text(" - ".unicodeScalars)), dash >= 2, dash <= 3 {
            let tag = Text(s[0..<dash])
            let word = String(String.UnicodeScalarView(tag))
            if tag.allSatisfy({ isAsciiLower($0) }), countryOf(word) != nil { return (tag, Text(s[(dash + 3)...])) }
        }
        return nil
    }

    static func indexOf(_ s: Text, _ needle: Text) -> Int? {
        guard !needle.isEmpty, s.count >= needle.count else { return nil }
        for i in 0...(s.count - needle.count) where Array(s[i..<(i + needle.count)]) == needle { return i }
        return nil
    }

    /// A etiqueta entre parênteses no fim de [s] e o que fica antes.
    static func trailingTag(_ s: Text) -> (tag: Text, rest: Text)? {
        guard let last = s.last else { return nil }
        let open: Unicode.Scalar
        switch last {
        case "]": open = "["
        case ")": open = "("
        default: return nil
        }
        guard s.count >= 2 else { return nil }
        var start: Int?
        var i = s.count - 2
        while i >= 0 {
            if s[i] == open { start = i; break }
            i -= 1
        }
        guard let start, s.count - start - 2 <= tagMax else { return nil }
        return (trim(Text(s[(start + 1)..<(s.count - 1)])), Text(s[0..<start]))
    }

    /// O `+N` (o mesmo canal N horas mais tarde) e o nome sem ele. Só quando o `+` está colado ao número ("SIC+1",
    /// "SIC (+1)") ou tem um espaço antes ("SIC + 1"): "Canal+ 1" não é um +1.
    static func shift(_ s: Text) -> (shift: Int, rest: Text) {
        var from = 0
        while let i = firstIndex(s, of: "+", from: from) {
            var j = i + 1
            let spaceAfter = j < s.count && s[j] == " "
            if spaceAfter { j += 1 }
            var k = j
            while k < s.count, k - j < 3, isDigit(s[k]) { k += 1 }
            let digits = k - j
            let spaceBefore = i > 0 && s[i - 1] == " "
            let endsNumber = k >= s.count || !isDigit(s[k])
            if (1...2).contains(digits), endsNumber, !spaceAfter || spaceBefore {
                // "+1h": o "h" (de horas) vai com ele.
                if k < s.count, s[k] == "h", k + 1 >= s.count || !isLetterOrDigit(s[k + 1]) { k += 1 }
                let n = Int(String(String.UnicodeScalarView(s[j..<(j + digits)]))) ?? 0
                return (n, Text(s[0..<i]) + [" "] + Text(s[k...]))
            }
            from = i + 1
        }
        return (0, s)
    }

    /// O id do guia que serve para comparar (sem os vazios e os "none" de algumas listas).
    public static func epgKey(_ id: String?) -> String? {
        guard let id else { return nil }
        let k = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return k.unicodeScalars.count >= 2 && !["none", "null", "n/a", "0"].contains(k) ? k : nil
    }

    /// Quantas letras é preciso mudar para ir de [a] a [b].
    public static func levenshtein(_ a: String, _ b: String) -> Int {
        let a = Array(a.unicodeScalars), b = Array(b.unicodeScalars)
        if a == b { return 0 }
        if a.isEmpty { return b.count }
        if b.isEmpty { return a.count }
        var prev = Array(0...b.count)
        var cur = [Int](repeating: 0, count: b.count + 1)
        for i in 1...a.count {
            cur[0] = i
            for j in 1...b.count {
                let cost = a[i - 1] == b[j - 1] ? 0 : 1
                cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            swap(&prev, &cur)
        }
        return prev[b.count]
    }

    /// O nome de um grupo para comparar (sem acentos, maiúsculas, espaços nem pontuação).
    public static func groupKey(_ name: String) -> String { words(Text(fold(name).unicodeScalars)).joined() }

    /// Os modos da reserva de uma lista (`backup_mode`): automático (sem entrada) ou só os canais escolhidos à mão.
    public static let modeManual = "manual"

    /// A decisão de passar para a reserva: o canal [main] (com a escolha à mão [override]). Em [manual], só uma escolha
    /// que ainda exista na reserva passa; os outros mostram o erro como sempre.
    public static func decide(_ main: Entry, _ matcher: ChannelMatcher, override: String?, manual: Bool) -> Result {
        guard manual else { return matcher.find(main.name, epgId: main.epgId, override: override, group: main.group) }
        guard let override, override != off, let index = matcher.indexOfKey(override) else { return .missing }
        return .match(index)
    }

    /// Como fica cada canal da lista principal na reserva (a lista "Canais de reserva").
    public enum State: String, Sendable {
        /// Encontrado sozinho.
        case auto
        /// Escolhido à mão.
        case chosen
        /// "Nenhum": escolhido à mão para não mudar.
        case off
        case unsure
        case missing
        /// Modo manual, sem escolha: não muda (a sugestão fica em [ReviewRow.suggestion]).
        case notSet = "not_set"
    }

    /// Um canal da lista principal ([main], a posição) e o da reserva ([backup]) ou a [suggestion] automática.
    public struct ReviewRow: Equatable, Sendable {
        public let main: Int
        public let state: State
        public let backup: Int?
        public let suggestion: Int?

        public func passes(_ filter: Filter) -> Bool {
            switch filter {
            case .all: return true
            case .matched: return state == .auto || state == .chosen
            case .unsure: return state == .unsure
            case .missing: return state == .missing || state == .off || state == .notSet
            case .chosen: return state == .chosen || state == .off
            }
        }
    }

    /// Os filtros da lista "Canais de reserva".
    public enum Filter: String, CaseIterable, Sendable {
        case all, matched, unsure, missing, chosen
    }

    /// Cada canal de [main] na reserva [matcher], com as escolhas à mão [overrides] (chave → escolha) e o modo.
    public static func review(_ main: [Entry], _ matcher: ChannelMatcher, overrides: [String: String] = [:], manual: Bool = false) -> [ReviewRow] {
        main.enumerated().map { i, c in
            let override = overrides[c.key]
            if override == off { return ReviewRow(main: i, state: .off, backup: nil, suggestion: nil) }
            if let override, let chosen = matcher.indexOfKey(override) { return ReviewRow(main: i, state: .chosen, backup: chosen, suggestion: nil) }
            // Uma escolha que já não existe na reserva conta como sem escolha.
            let auto = matcher.find(c.name, epgId: c.epgId, override: nil, group: c.group)
            if manual { return ReviewRow(main: i, state: .notSet, backup: nil, suggestion: auto.index) }
            switch auto {
            case .match(let index): return ReviewRow(main: i, state: .auto, backup: index, suggestion: nil)
            case .unsure: return ReviewRow(main: i, state: .unsure, backup: nil, suggestion: nil)
            case .missing: return ReviewRow(main: i, state: .missing, backup: nil, suggestion: nil)
            }
        }
    }

    // MARK: A cobertura

    /// Quantos canais de uma lista têm o seu na reserva, quantos ficam em dúvida e quantos faltam (com os nomes).
    public struct Coverage: Equatable, Sendable {
        public let total: Int
        public let matched: Int
        public let unsure: Int
        public let missing: Int
        public let unsureNames: [String]
        public let missingNames: [String]
    }

    /// A cobertura da reserva [matcher] para os canais [main]; [overrides]: chave → escolha à mão. Em [manual], os
    /// encontrados são os escolhidos à mão. Com [rows] já feitas ([review]), não se procura outra vez.
    public static func coverage(_ main: [Entry], _ matcher: ChannelMatcher, overrides: [String: String] = [:], manual: Bool = false,
                                rows: [ReviewRow]? = nil) -> Coverage {
        let rows = rows ?? review(main, matcher, overrides: overrides, manual: manual)
        let matched = rows.filter { $0.state == .auto || $0.state == .chosen }.count
        let unsure = rows.filter { $0.state == .unsure }.map { main[$0.main].name }
        let missing = rows.filter { $0.state == .missing || $0.state == .off || $0.state == .notSet }.map { main[$0.main].name }
        return Coverage(total: main.count, matched: matched, unsure: unsure.count, missing: missing.count, unsureNames: unsure, missingNames: missing)
    }
}

/// Os canais de uma lista de reserva, já partidos e indexados (para procurar muitos sem voltar a partir).
public final class ChannelMatcher: @unchecked Sendable {
    public let pool: [ChannelMatch.Entry]
    private let parsed: [ChannelMatch.Parsed]
    private var byEpg: [String: [Int]] = [:]
    private var byBase: [String: [Int]] = [:]
    private var byKey: [String: Int] = [:]
    private let groups: [String?]

    public init(_ pool: [ChannelMatch.Entry]) {
        self.pool = pool
        parsed = pool.map { ChannelMatch.parse($0.name) }
        groups = pool.map { $0.group.map(ChannelMatch.groupKey) }
        for (i, e) in pool.enumerated() {
            if let k = ChannelMatch.epgKey(e.epgId) { byEpg[k, default: []].append(i) }
            if !parsed[i].base.isEmpty { byBase[parsed[i].base, default: []].append(i) }
            if byKey[e.key] == nil { byKey[e.key] = i }
        }
    }

    /// A posição do canal [key] nesta lista, ou nil.
    public func indexOfKey(_ key: String) -> Int? { byKey[key] }

    /// O canal [name] (id do guia [epgId], no grupo [group]) nesta lista; [override]: a escolha à mão (off ou uma chave daqui).
    public func find(_ name: String, epgId: String?, override: String? = nil, group: String? = nil) -> ChannelMatch.Result {
        if override == ChannelMatch.off { return .missing }
        if let override, let i = byKey[override] { return .match(i) }
        let t = ChannelMatch.parse(name)
        if t.base.isEmpty { return .missing }
        func compatible(_ i: Int) -> Bool {
            let c = parsed[i]
            return c.shift == t.shift && (t.country == nil || c.country == nil || c.country == t.country)
        }
        let g = group.map(ChannelMatch.groupKey)
        let byName = (byBase[t.base] ?? []).filter(compatible)
        let named = choose(t, byName, g)
        let epg = (ChannelMatch.epgKey(epgId).flatMap { byEpg[$0] } ?? []).filter(compatible)
        if !epg.isEmpty {
            let sameName = epg.filter { parsed[$0].base == t.base }
            if !sameName.isEmpty { return choose(t, sameName, g) }
            // O mesmo id do guia e o nome a uma letra de distância (um erro de escrita: "TVI REALLITY"): conta como o
            // mesmo nome, mas só em nomes com 4 letras ou mais.
            if byName.isEmpty, t.base.unicodeScalars.count >= 4 {
                let near = epg.filter { ChannelMatch.levenshtein(parsed[$0].base, t.base) <= 1 }
                if !near.isEmpty { return choose(t, near, g) }
            }
            // O id do guia aponta para um canal com outro nome: só vale se não houver nenhum com o mesmo nome.
            if byName.isEmpty { return choose(t, epg, g) }
            return .unsure(byName.count + epg.count)
        }
        return named
    }

    /// Entre candidatos: o mesmo país, depois a mesma qualidade (se o nome a disser) ou a melhor; países ou nomes
    /// diferentes que sobrem = dúvida. Cópias
    /// iguais do mesmo canal não são dúvida: fica a do grupo com o nome do grupo do canal principal, ou a primeira.
    private func choose(_ t: ChannelMatch.Parsed, _ candidates: [Int], _ group: String? = nil) -> ChannelMatch.Result {
        if candidates.isEmpty { return .missing }
        var set = candidates
        if let country = t.country {
            let same = set.filter { parsed[$0].country == country }
            if !same.isEmpty { set = same }
        }
        if Set(set.compactMap { parsed[$0].country }).count > 1 { return .unsure(set.count) }
        if Set(set.map { parsed[$0].base }).count > 1 { return .unsure(set.count) }
        // A mesma qualidade do canal principal, quando o nome a diz (HD com HD, 4K com 4K); senão, a melhor.
        let sameQuality = set.filter { parsed[$0].quality == t.quality }
        let top: [Int]
        if t.quality != ChannelMatch.Quality.none, !sameQuality.isEmpty {
            top = sameQuality
        } else {
            let best = set.map { parsed[$0].quality }.max() ?? 0
            top = set.filter { parsed[$0].quality == best }
        }
        if let group, let same = top.first(where: { groups[$0] == group }) { return .match(same) }
        return .match(top[0])
    }

    /// Os canais daqui mais parecidos com [name] (para escolher à mão), do mais para o menos parecido (ver o Android).
    public func ranked(_ name: String, epgId: String?, limit: Int = 8) -> [Int] {
        let t = ChannelMatch.parse(name)
        if t.base.isEmpty { return [] }
        let epg = ChannelMatch.epgKey(epgId)
        var scored: [(Int, Int)] = []
        for i in pool.indices {
            let s = score(t, epg, i)
            if s >= ChannelMatch.minScore { scored.append((i, s)) }
        }
        scored.sort { a, b in
            if a.1 != b.1 { return a.1 > b.1 }
            let qa = parsed[a.0].quality, qb = parsed[b.0].quality
            // Empates: a mesma qualidade do canal principal, depois a melhor.
            if t.quality != ChannelMatch.Quality.none, (qa == t.quality) != (qb == t.quality) { return qa == t.quality }
            if qa != qb { return qa > qb }
            return a.0 < b.0
        }
        return scored.prefix(limit).map(\.0)
    }

    /// A parecença do canal [i] com [t]; só contas inteiras, iguais nas duas plataformas.
    func score(_ t: ChannelMatch.Parsed, _ epg: String?, _ i: Int) -> Int {
        let c = parsed[i]
        if c.base.isEmpty { return 0 }
        let longest = max(t.base.unicodeScalars.count, c.base.unicodeScalars.count)
        var s = 100 - 100 * ChannelMatch.levenshtein(t.base, c.base) / longest
        if s < 60, c.base.contains(t.base) || t.base.contains(c.base) { s = 60 }
        if let epg, ChannelMatch.epgKey(pool[i].epgId) == epg { s += 50 }
        if c.shift != t.shift { s -= 30 }
        if let tc = t.country, let cc = c.country { s += cc == tc ? 5 : -20 }
        return s
    }
}
