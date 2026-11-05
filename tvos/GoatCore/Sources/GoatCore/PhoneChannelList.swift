import Foundation

/// Os canais que o GoatRemote vê (GET /channels da porta dos telemóveis). O estado é tirado no thread principal
/// (ver `AppModel.phoneChannelList`) e o JSON é montado fora dele. As mesmas regras do GoatTV Android.
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

    public static let maxBytes = 8 * 1024 * 1024
    private static let end = "]}"
    private static let truncatedEnd = "],\"truncated\":true}"

    /// O JSON do /channels, com no máximo [maxBytes]: se não couber tudo, os canais cortam-se e vai `"truncated": true`.
    public func json(maxBytes: Int = PhoneChannelList.maxBytes) -> Data {
        let id = playlist.id
        var seen = Set<String>()
        let shown = groups
            .filter { !prefs.isHidden(id, $0.category.id) && !Self.isLockedSetting(prefs, id, $0.category.id) }
            .map { (group: Group) -> Group in
                // Cada canal uma só vez, no seu grupo, e só o que o /play abriria com essa chave.
                let channels = group.channels.filter { (ch: Channel) -> Bool in
                    guard ch.categoryId == group.category.id, Self.allowed(prefs, id, ch), byKey[ch.key]?.uid == ch.uid else { return false }
                    return seen.insert(ch.key).inserted
                }
                return Group(group.category, channels)
            }
            .filter { !$0.channels.isEmpty }

        let currentKey = current.flatMap { byKey[$0] }.flatMap { Self.allowed(prefs, id, $0) ? $0.key : nil }
        var head = "{\"playlist\":{\"id\":\(Self.quote(id)),\"name\":\(Self.quote(playlist.name))},"
        let currentJSON = currentKey.map { Self.quote($0) } ?? "null"
        head += "\"current\":\(currentJSON),\"groups\":["
        head += shown.map { "{\"id\":\(Self.quote($0.category.id)),\"name\":\(Self.quote($0.category.name))}" }.joined(separator: ",")
        // O objeto fica aberto para os canais entrarem um a um, a contar os bytes.
        head += "],\"channels\":["
        var out = [UInt8](head.utf8)
        let budget = maxBytes - out.count - Self.truncatedEnd.utf8.count
        var used = 0
        var count = 0
        var truncated = false
        channels: for group in shown {
            for ch in group.channels {
                let item = "{\"key\":\(Self.quote(ch.key)),\"num\":\(ch.num),\"name\":\(Self.quote(ch.name))," +
                    "\"group\":\(Self.quote(group.category.id)),\"logo\":\(Self.logoURL(ch, logos: logos) != nil)," +
                    "\"favorite\":\(favorites.contains(ch.key))}"
                let bytes = Array(item.utf8)
                let need = bytes.count + (count > 0 ? 1 : 0)
                if used + need > budget {
                    truncated = true
                    break channels
                }
                if count > 0 { out.append(UInt8(ascii: ",")) }
                out += bytes
                used += need
                count += 1
            }
        }
        out += Array((truncated ? Self.truncatedEnd : Self.end).utf8)
        return Data(out)
    }

    /// Bloqueado nas definições, desbloqueado ou não com o PIN (como no Android: conta o que está em `locked_groups`).
    public static func isLockedSetting(_ prefs: Prefs, _ playlistId: String, _ groupId: String) -> Bool {
        prefs.lockedGroups.contains("\(playlistId):\(groupId)")
    }

    /// O telemóvel pode ver e abrir [channel]: nem escondido (ele ou o grupo) nem bloqueado.
    public static func allowed(_ prefs: Prefs, _ playlistId: String, _ channel: Channel) -> Bool {
        !prefs.isHidden(playlistId, channel.categoryId) && !prefs.isChannelHidden(playlistId, channel.key) &&
            !isLockedSetting(prefs, playlistId, channel.categoryId)
    }

    /// O logótipo do canal tal como o ecrã o mostra (o de [logos], ou o da lista), se for um endereço http(s).
    public static func logoURL(_ channel: Channel, logos: [String: String]) -> String? {
        guard let text = (logos[channel.key] ?? channel.logo)?.trimmingCharacters(in: .whitespacesAndNewlines),
              let url = URL(string: text), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else { return nil }
        return text
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
    public static func decodeKey(_ encoded: String) -> String? {
        let scalars = Array(encoded.unicodeScalars)
        var bytes: [UInt8] = []
        var i = 0
        while i < scalars.count {
            let scalar = scalars[i]
            if scalar == "%" {
                guard i + 2 < scalars.count, let high = hexValue(scalars[i + 1]), let low = hexValue(scalars[i + 2]) else { return nil }
                bytes.append(high << 4 | low)
                i += 3
            } else {
                guard scalar.value <= 0xFF else { return nil }
                bytes.append(UInt8(scalar.value))
                i += 1
            }
        }
        // UTF-8 a sério: sequências partidas ou inválidas recusam a chave.
        var decoder = UTF8()
        var iterator = bytes.makeIterator()
        var key = String.UnicodeScalarView()
        decoding: while true {
            switch decoder.decode(&iterator) {
            case .scalarValue(let s): key.append(s)
            case .emptyInput: break decoding
            case .error: return nil
            }
        }
        return key.isEmpty ? nil : String(key)
    }

    private static func hexValue(_ s: Unicode.Scalar) -> UInt8? {
        switch s.value {
        case 0x30...0x39: return UInt8(s.value - 0x30)
        case 0x41...0x46: return UInt8(s.value - 0x41 + 10)
        case 0x61...0x66: return UInt8(s.value - 0x61 + 10)
        default: return nil
        }
    }

    /// Um texto em JSON (o resto do UTF-8 vai tal como está, e < > & também: é JSON para uma app, não para HTML).
    static func quote(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case _ where scalar.value < 0x20:
                let hex = String(scalar.value, radix: 16)
                out += "\\u" + String(repeating: "0", count: 4 - hex.count) + hex
            default:
                out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }
}
