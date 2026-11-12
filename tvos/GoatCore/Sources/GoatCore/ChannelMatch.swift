import Foundation
import GoatShared

/// O "mesmo canal" noutra lista (a lista de reserva, quando o fornecedor principal não responde). As regras são as do
/// núcleo partilhado com o Android (shared/…/data/ChannelMatch.kt, aqui pelo GoatShared.AppleBridge): este ficheiro só
/// põe os tipos do Swift por cima. Conferidas pelos casos de app/src/test/resources/channel_match.json.
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

    /// Escolha à mão canal a canal: "nenhum" (este canal nunca muda de lista). Gravado como o valor de uma lista vazia.
    public static let off = "off"

    /// Um failover que é outro canal da própria lista (não da reserva): "main:" e a chave dele. Os da reserva são só a
    /// chave, como eram quando cada canal tinha um só.
    public static let mainPrefix = "main:"

    /// A chave do canal [key] da lista principal como failover.
    public static func mainFailover(_ key: String) -> String { mainPrefix + key }

    /// A chave na lista principal, se [failover] for um canal dela; nil se for um da reserva.
    public static func mainKey(of failover: String) -> String? {
        failover.hasPrefix(mainPrefix) ? String(failover.dropFirst(mainPrefix.count)) : nil
    }

    public enum Quality {
        public static let sd = 1
        public static let none = 2
        public static let hd = 3
        public static let fhd = 4
        public static let uhd = 5
        public static let q8k = 6
    }

    /// A parecença mínima para um canal aparecer nas sugestões ([ChannelMatcher.ranked]).
    public static let minScore = 40

    /// O nome partido: sem o país, a qualidade e as marcas técnicas à frente e atrás.
    public static func parse(_ name: String) -> Parsed {
        let p = AppleBridge.shared.parse(name: name)
        return Parsed(base: p.base, country: p.country, quality: Int(p.quality), shift: Int(p.shift))
    }

    /// O id do guia para comparar (sem espaços nem maiúsculas), ou nil se não servir ("none", "0"…).
    public static func epgKey(_ id: String?) -> String? { AppleBridge.shared.epgKey(id: id) }

    public static func levenshtein(_ a: String, _ b: String) -> Int { Int(AppleBridge.shared.levenshtein(a: a, b: b)) }

    /// O nome de um grupo para comparar (sem acentos, maiúsculas, espaços nem pontuação).
    public static func groupKey(_ name: String) -> String { AppleBridge.shared.groupKey(name: name) }

    /// Os modos da reserva de uma lista (`backup_mode`): automático (sem entrada) ou só os canais escolhidos à mão.
    public static let modeManual = "manual"

    /// A decisão de passar para a reserva: o canal [main] (com a escolha à mão [override]). Em [manual], só uma escolha
    /// que ainda exista na reserva passa; os outros mostram o erro como sempre.
    public static func decide(_ main: Entry, _ matcher: ChannelMatcher, override: String?, manual: Bool) -> Result {
        Result(AppleBridge.shared.decide(key: main.key, name: main.name, epgId: main.epgId, group: main.group, matcher: matcher.shared,
                                         chosen: override, manual: manual))
    }

    /// Os failovers do canal [main], pela ordem em que se tentam: as chaves (as da própria lista com [mainPrefix]).
    /// [chosen]: a lista escolhida à mão (vazia = "nenhum"; nil = automático: o que o [matcher] encontrar, e nada no
    /// [manual]). Os escolhidos que já não existem (na reserva, ou na principal segundo [inMain]) ficam de fora, e o
    /// próprio canal também; se não sobrar nenhum, conta como sem escolha (como na lista "Canais de reserva").
    /// No automático, primeiro as [variants] do canal na própria lista (chaves; ver ChannelVariants), também no [manual].
    public static func failovers(_ main: Entry, _ matcher: ChannelMatcher, chosen: [String]?, manual: Bool, variants: [String] = [],
                                 inMain: (String) -> Bool) -> [String] {
        // O núcleo quer as chaves da principal que contam: só as que o canal pode usar (as escolhidas e as versões).
        var keys = Set<String>()
        for f in chosen ?? [] { if let k = mainKey(of: f), inMain(k) { keys.insert(k) } }
        for k in variants where inMain(k) { keys.insert(k) }
        return AppleBridge.shared.failovers(key: main.key, name: main.name, epgId: main.epgId, group: main.group, matcher: matcher.shared,
                                            chosen: chosen, manual: manual, variants: variants, mainKeys: keys)
    }

    /// Como fica cada canal da lista principal na reserva (a lista "Canais de reserva").
    public enum State: String, Sendable {
        /// Encontrado sozinho.
        case auto
        /// Escolhido à mão (um ou mais failovers).
        case chosen
        /// "Nenhum": escolhido à mão para não mudar.
        case off
        case unsure
        case missing
        /// Modo manual, sem escolha: não muda (a sugestão fica em [ReviewRow.suggestion]).
        case notSet = "not_set"
    }

    /// Um canal da lista principal ([main], a posição) e o da reserva ([backup]) ou a [suggestion] automática.
    /// Escolhidos à mão: [failovers], as chaves pela ordem (ver [failovers]); [backup] é o primeiro, se for da reserva.
    public struct ReviewRow: Equatable, Sendable {
        public let main: Int
        public let state: State
        public let backup: Int?
        public let suggestion: Int?
        public var failovers: [String] = []

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

    /// Cada canal de [main] na reserva [matcher], com as escolhas à mão [overrides] (chave → failovers pela ordem; lista
    /// vazia = "nenhum") e o modo.
    public static func review(_ main: [Entry], _ matcher: ChannelMatcher, overrides: [String: [String]] = [:], manual: Bool = false) -> [ReviewRow] {
        AppleBridge.shared.review(main: entries(main), matcher: matcher.shared, overrides: overrides, manual: manual).map { r in
            ReviewRow(main: Int(r.main), state: State(rawValue: r.state) ?? .missing, backup: r.backup < 0 ? nil : Int(r.backup),
                      suggestion: r.suggestion < 0 ? nil : Int(r.suggestion), failovers: r.failovers)
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
    public static func coverage(_ main: [Entry], _ matcher: ChannelMatcher, overrides: [String: [String]] = [:], manual: Bool = false,
                                rows: [ReviewRow]? = nil) -> Coverage {
        let rows = rows ?? review(main, matcher, overrides: overrides, manual: manual)
        let matched = rows.filter { $0.state == .auto || $0.state == .chosen }.count
        let unsure = rows.filter { $0.state == .unsure }.map { main[$0.main].name }
        let missing = rows.filter { $0.state == .missing || $0.state == .off || $0.state == .notSet }.map { main[$0.main].name }
        return Coverage(total: main.count, matched: matched, unsure: unsure.count, missing: missing.count, unsureNames: unsure, missingNames: missing)
    }

    /// Os canais para o núcleo, um a um.
    static func entries(_ list: [Entry]) -> AppleEntries {
        let out = AppleEntries()
        for e in list { out.add(key: e.key, name: e.name, epgId: e.epgId, group: e.group) }
        return out
    }
}

extension ChannelMatch.Result {
    init(_ r: AppleResult) {
        switch r.kind {
        case "match": self = .match(Int(r.value))
        case "unsure": self = .unsure(Int(r.value))
        default: self = .missing
        }
    }
}

/// Os canais de uma lista de reserva, já partidos e indexados no núcleo (para procurar muitos sem voltar a partir).
public final class ChannelMatcher: @unchecked Sendable {
    public let pool: [ChannelMatch.Entry]
    let shared: AppleMatcher

    public init(_ pool: [ChannelMatch.Entry]) {
        self.pool = pool
        shared = AppleMatcher(entries: ChannelMatch.entries(pool))
    }

    /// A posição do canal [key] nesta lista, ou nil.
    public func indexOfKey(_ key: String) -> Int? {
        let i = shared.indexOfKey(key: key)
        return i < 0 ? nil : Int(i)
    }

    /// O canal [name] (id do guia [epgId], no grupo [group]) nesta lista; [override]: a escolha à mão (off ou uma chave daqui).
    public func find(_ name: String, epgId: String?, override: String? = nil, group: String? = nil) -> ChannelMatch.Result {
        ChannelMatch.Result(AppleBridge.shared.find(matcher: shared, name: name, epgId: epgId, chosen: override, group: group))
    }

    /// Os canais daqui mais parecidos com [name] (para escolher à mão), do mais para o menos parecido.
    public func ranked(_ name: String, epgId: String?, limit: Int = 8) -> [Int] {
        AppleBridge.shared.ranked(matcher: shared, name: name, epgId: epgId, limit: Int32(limit)).map { Int($0.int32Value) }
    }
}
