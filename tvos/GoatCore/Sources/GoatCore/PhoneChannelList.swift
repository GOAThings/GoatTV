import Foundation
import GoatShared

/// Os canais que o GoatRemote vê (GET /channels da porta dos telemóveis). O estado é tirado no thread principal
/// (ver `AppModel.phoneChannelList`) e o JSON é montado fora dele, pelo núcleo partilhado com o GoatTV Android
/// (PhoneList).
///
/// Nunca aparecem grupos ou canais escondidos, nem grupos bloqueados pelo controlo parental tal como está nas
/// definições, mesmo que tenham sido desbloqueados com o PIN nesta sessão: o telemóvel não pede PIN.
public struct PhoneChannelList: Sendable {
    public struct Group: Sendable {
        public let category: Category
        public let channels: [Channel]

        public init(_ category: Category, _ channels: [Channel]) {
            self.category = category
            self.channels = channels
        }
    }

    public let playlist: Playlist
    /// Os grupos pela ordem do ecrã, cada um com os seus canais pela ordem do ecrã.
    public let groups: [Group]
    public let prefs: Prefs
    /// Os favoritos desta lista (channelKeys).
    public let favorites: Set<String>
    /// O canal a ver (channelKey), ou nil.
    public let current: String?
    /// Os logótipos que o ecrã mostra quando não são os da lista (channelKey → URL; "" = nenhum).
    public let logos: [String: String]
    /// O canal de cada channelKey (o primeiro da lista com essa chave): é esse que o /play e o /logo usam.
    public let byKey: [String: Channel]

    public init(playlist: Playlist, groups: [Group], prefs: Prefs, favorites: Set<String>, current: String?,
                logos: [String: String], byKey: [String: Channel]) {
        self.playlist = playlist
        self.groups = groups
        self.prefs = prefs
        self.favorites = favorites
        self.current = current
        self.logos = logos
        self.byKey = byKey
    }

    public static let maxBytes = Int(PhoneList.shared.MAX_BYTES)

    /// O JSON do /now: o canal a ver e, com guia, o programa e o seguinte. É pequeno, para o telemóvel perguntar a
    /// cada poucos segundos sem pedir a lista inteira.
    public static func nowJSON(current: String?, now: EpgEntry?, next: EpgEntry?) -> Data {
        Data(PhoneList.shared.nowJson(current: current, now: now?.shared, next: next?.shared).utf8)
    }

    /// O JSON do /channels, com no máximo [maxBytes]: se não couber tudo, os canais cortam-se e vai `"truncated": true`.
    public func json(maxBytes: Int = PhoneChannelList.maxBytes) -> Data {
        var firstUid: [String: KotlinInt] = [:]
        for group in groups {
            for ch in group.channels { if let first = byKey[ch.key] { firstUid[ch.key] = KotlinInt(int: Int32(clamping: first.uid)) } }
        }
        let shared = groups.map { GoatShared.PhoneGroup(category: GoatShared.Category(id: $0.category.id, name: $0.category.name),
                                                        channels: $0.channels.map(\.shared)) }
        let text = PhoneList.shared.json(playlistId: playlist.id, playlistName: playlist.name, groups: shared, rules: Self.rules(prefs),
                                         favorites: favorites, current: current.flatMap { byKey[$0] }?.shared, logos: logos,
                                         firstUid: firstUid, maxBytes: Int32(clamping: maxBytes))
        return Data(text.utf8)
    }

    private static func rules(_ prefs: Prefs) -> PhoneRules {
        PhoneRules(hiddenGroups: prefs.hiddenGroups, hiddenChannels: prefs.hiddenChannels, lockedGroups: prefs.lockedGroups)
    }

    /// Bloqueado nas definições, desbloqueado ou não com o PIN (como no Android: conta o que está em `locked_groups`).
    public static func isLockedSetting(_ prefs: Prefs, _ playlistId: String, _ groupId: String) -> Bool {
        rules(prefs).isLockedSetting(playlistId: playlistId, groupId: groupId)
    }

    /// O telemóvel pode ver e abrir [channel]: nem escondido (ele ou o grupo) nem bloqueado.
    public static func allowed(_ prefs: Prefs, _ playlistId: String, _ channel: Channel) -> Bool {
        rules(prefs).allowed(playlistId: playlistId, channel: channel.shared)
    }

    /// O logótipo do canal tal como o ecrã o mostra (o de [logos], ou o da lista), se for um endereço http(s).
    public static func logoURL(_ channel: Channel, logos: [String: String]) -> String? {
        PhoneList.shared.logoUrl(channel: channel.shared, logos: logos)
    }

    /// O tipo a mandar com um logótipo: o do fornecedor se for `image/…`, senão `image/png` se os bytes forem PNG.
    /// nil se não for uma imagem.
    public static func logoType(contentType: String?, bytes: Data) -> String? {
        if let type = contentType?.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false).first?
            .trimmingCharacters(in: .whitespaces).lowercased(),
           type.hasPrefix("image/"), type.count > "image/".count {
            return type
        }
        let png: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        return bytes.count >= png.count && Array(bytes.prefix(png.count)) == png ? "image/png" : nil
    }

    /// A chave de um caminho (`/logo/<chave>`), que o telemóvel manda em percent-encoding. O pedido foi lido byte a
    /// byte (cada byte um carácter), por isso os bytes de fora do ASCII também contam. nil se estiver mal escrita.
    public static func decodeKey(_ encoded: String) -> String? { PhoneList.shared.decodeKey(encoded: encoded) }

    /// Um texto em JSON (o resto do UTF-8 vai tal como está, e < > & também: é JSON para uma app, não para HTML).
    static func quote(_ s: String) -> String { PhoneList.shared.quote(s: s) }
}
