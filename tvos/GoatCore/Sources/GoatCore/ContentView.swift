import Foundation
import GoatShared

/// Uma regra de limpar nomes: [find] passa a [replace] ("" = tirar), sem olhar a maiúsculas.
public struct NameRule: Equatable, Sendable {
    public let find: String
    public let replace: String

    public init(find: String, replace: String) {
        self.find = find
        self.replace = replace
    }
}

/// Os nomes dos canais como se mostram: as regras da lista (Prefs.nameRules), pela ordem, e por cima o nome escolhido
/// canal a canal (Prefs.channelNames). As regras são as do núcleo partilhado (ChannelNames.kt), com a tabela
/// channel_match.json ("names").
public enum ChannelNames {
    /// [name] depois das [rules]: os espaços repetidos ficam um, sem espaços nas pontas. Se não sobrar nada, fica [name].
    public static func apply(_ name: String, _ rules: [NameRule]) -> String {
        if rules.isEmpty { return name }
        return AppleBridge.shared.names(name: name, finds: rules.map(\.find), replaces: rules.map(\.replace))
    }
}

/// As versões do mesmo canal numa lista ("RTP 1 HD", "RTP 1 SD", "RTP 1 FHD"…): o mesmo nome, país e atraso (+1)
/// depois de tirar a qualidade (ChannelMatch.parse). Com "Juntar versões" (Prefs.mergeVariants), cada canal fica uma
/// linha só: a de melhor qualidade (a primeira da lista, em empate), e as outras passam a failovers dela, da melhor para
/// a pior. É o núcleo partilhado (ChannelVariants.kt) que as encontra, com a tabela channel_match.json ("variants").
public enum ChannelVariants {
    /// Os canais da lista (chave e nome), pela ordem dela → a melhor versão de cada canal → as outras, pela ordem.
    public static func of(_ channels: [(key: String, name: String)]) -> [String: [String]] {
        AppleBridge.shared.variants(keys: channels.map(\.key), names: channels.map(\.name))
    }
}

/// Os canais de uma lista como se mostram: os nomes limpos (ChannelNames) e, com "Juntar versões", cada canal numa
/// linha só (ChannelVariants). As chaves não mudam. Igual no Android (ContentView.kt).
public enum ContentView {
    public struct Options: Equatable, Sendable {
        public let merge: Bool
        public let rules: [NameRule]
        public let names: [String: String]
        public var none: Bool { !merge && rules.isEmpty && names.isEmpty }
    }

    public static func options(_ p: Prefs, playlistId: String) -> Options {
        let prefix = "\(playlistId):"
        var names: [String: String] = [:]
        for (k, v) in p.channelNames where k.hasPrefix(prefix) { names[String(k.dropFirst(prefix.count))] = v }
        return Options(merge: p.mergeVariants.contains(playlistId), rules: p.nameRules[playlistId] ?? [], names: names)
    }

    public static func apply(_ raw: PlaylistContent, _ o: Options) -> PlaylistContent {
        if o.none { return raw }
        let renamed = raw.channels.map { c -> Channel in
            let name = o.names[c.key] ?? ChannelNames.apply(c.name, o.rules)
            guard name != c.name else { return c }
            var r = c
            r.name = name
            return r
        }
        func plain() -> PlaylistContent {
            PlaylistContent(playlist: raw.playlist, categories: raw.categories, channels: renamed, account: raw.account,
                            guideUrl: raw.guideUrl, fetchedAt: raw.fetchedAt)
        }
        guard o.merge else { return plain() }
        // As versões pelos nomes da lista (as regras podiam tirar o que as distingue).
        let variants = ChannelVariants.of(raw.channels.map { ($0.key, $0.name) })
        guard !variants.isEmpty else { return plain() }
        let hiddenKeys = Set(variants.values.joined())
        var byKey: [String: Channel] = [:]
        for c in renamed where byKey[c.key] == nil { byKey[c.key] = c }
        // A linha que fica leva o guia e o logótipo de uma versão quando não tem os seus.
        let shown = renamed.filter { !hiddenKeys.contains($0.key) }.map { c -> Channel in
            guard let others = variants[c.key]?.compactMap({ byKey[$0] }) else { return c }
            var r = c
            if (r.epgId ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
                r.epgId = others.lazy.compactMap { $0.epgId }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            }
            if (r.logo ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
                r.logo = others.lazy.compactMap { $0.logo }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            }
            return r
        }
        let used = Set(shown.map(\.categoryId))
        return PlaylistContent(playlist: raw.playlist, categories: raw.categories.filter { used.contains($0.id) }, channels: shown,
                               account: raw.account, guideUrl: raw.guideUrl, fetchedAt: raw.fetchedAt,
                               hidden: renamed.filter { hiddenKeys.contains($0.key) }, variants: variants)
    }
}
