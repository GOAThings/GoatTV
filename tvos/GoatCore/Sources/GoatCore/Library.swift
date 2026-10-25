import Foundation

/// Os grupos e canais que se mostram de uma lista, com as escolhas da conta (escondidos, ordem, bloqueados,
/// favoritos, recentes). As mesmas regras do GoatTV Android.
public struct Library: Sendable {
    public let content: PlaylistContent
    public let prefs: Prefs
    public let favorites: [String]
    public let recents: [String]
    /// Grupos desbloqueados com o PIN nesta sessão ("playlistId:groupId").
    public let unlocked: Set<String>

    public init(content: PlaylistContent, prefs: Prefs, favorites: [String], recents: [String], unlocked: Set<String> = []) {
        self.content = content
        self.prefs = prefs
        self.favorites = favorites
        self.recents = recents
        self.unlocked = unlocked
    }

    private var playlistId: String { content.playlist.id }

    public func isGroupHidden(_ groupId: String) -> Bool { prefs.isHidden(playlistId, groupId) }

    public func isGroupLocked(_ groupId: String) -> Bool {
        prefs.isLocked(playlistId, groupId) && !unlocked.contains("\(playlistId):\(groupId)")
    }

    public func isChannelHidden(_ channel: Channel) -> Bool { prefs.isChannelHidden(playlistId, channel.key) }

    public func isFavorite(_ channel: Channel) -> Bool { favorites.contains(channel.key) }

    /// Grupos da lista pela ordem escolhida (os novos ficam no fim, pela ordem da lista).
    public func orderedGroups(includeHidden: Bool) -> [Category] {
        let order = prefs.groupOrder[playlistId] ?? []
        let position = Dictionary(order.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { a, _ in a })
        let indexed = content.categories.enumerated().map { (index: $0.offset, cat: $0.element) }
        return indexed
            .sorted { (position[$0.cat.id] ?? order.count + $0.index) < (position[$1.cat.id] ?? order.count + $1.index) }
            .map(\.cat)
            .filter { includeHidden || !isGroupHidden($0.id) }
    }

    /// Os grupos que se percorrem: os especiais visíveis e os normais sem os escondidos nem bloqueados.
    /// Os nomes dos especiais (e de "Outros") vêm de quem chama, já traduzidos.
    public func groups(favoritesName: String, recentName: String, allName: String, othersName: String,
                       includeLocked: Bool = false, forGuide: Bool = false) -> [Category] {
        var list: [Category] = []
        // Lista de grupos (como no Android): "Favoritos" aparece sempre que ligado (vazio mostra como juntar),
        // "Recentes" só quando há, "Todos" só se ligado. O guia percorre só os que têm canais e tem sempre "Todos".
        if prefs.showFavoritesGroup, !forGuide || !channels(in: SpecialCategory.favorites).isEmpty {
            list.append(Category(id: SpecialCategory.favorites, name: favoritesName))
        }
        if prefs.showRecentGroup, !channels(in: SpecialCategory.recent).isEmpty {
            list.append(Category(id: SpecialCategory.recent, name: recentName))
        }
        if prefs.showAllGroup || forGuide {
            list.append(Category(id: SpecialCategory.all, name: allName))
        }
        for cat in orderedGroups(includeHidden: false) where includeLocked || !isGroupLocked(cat.id) {
            list.append(cat.id == PlaylistContent.others ? Category(id: cat.id, name: othersName) : cat)
        }
        return list
    }

    /// Canais de um grupo pela ordem escolhida (os novos ficam no fim, pela ordem da lista).
    public func orderedChannels(_ groupId: String, includeHidden: Bool) -> [Channel] {
        let list = content.byCategory[groupId] ?? []
        let order = prefs.channelOrder["\(playlistId):\(groupId)"] ?? []
        var ordered = list
        if !order.isEmpty {
            let position = Dictionary(order.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { a, _ in a })
            ordered = list.enumerated()
                .sorted { (position[$0.element.key] ?? order.count + $0.offset) < (position[$1.element.key] ?? order.count + $1.offset) }
                .map(\.element)
        }
        return includeHidden ? ordered : ordered.filter { !isChannelHidden($0) }
    }

    public func channels(in groupId: String) -> [Channel] {
        func visible(_ ch: Channel) -> Bool {
            !(isGroupLocked(ch.categoryId) || isGroupHidden(ch.categoryId) || isChannelHidden(ch))
        }
        let list: [Channel]
        switch groupId {
        // Os canais de grupos bloqueados não aparecem em "Todos" nem nos favoritos; os escondidos em lado nenhum.
        case SpecialCategory.all:
            list = content.channels.filter(visible)
        case SpecialCategory.favorites:
            list = favorites.compactMap { content.byKey[$0] }.filter(visible)
        // Pela ordem em que foram vistos (não se ordenam).
        case SpecialCategory.recent:
            return recents.compactMap { content.byKey[$0] }.filter(visible)
        default:
            // Um grupo com ordem escolhida fica por essa ordem (mesmo com "Ordenar por nome").
            let ordered = orderedChannels(groupId, includeHidden: false)
            if prefs.channelOrder["\(playlistId):\(groupId)"] != nil { return ordered }
            list = ordered
        }
        if prefs.sort == Prefs.Sort.name {
            // Estável: com nomes iguais fica a ordem da lista.
            return list.enumerated().map { (name: $0.element.name.lowercased(), index: $0.offset, channel: $0.element) }
                .sorted { $0.name != $1.name ? $0.name < $1.name : $0.index < $1.index }
                .map(\.channel)
        }
        return list
    }

    /// O logótipo a mostrar: o escolhido para o canal, senão o da lista. (Os do guia ficam para depois.)
    public func logo(for channel: Channel) -> String? {
        if let chosen = prefs.channelLogos["\(playlistId):\(channel.key)"], !chosen.isEmpty { return chosen }
        return channel.logo
    }
}
