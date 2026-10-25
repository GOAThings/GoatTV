import Foundation

/// Os grupos e canais de uma lista, para o site da conta os poder organizar (o servidor não descarrega as listas:
/// os fornecedores bloqueiam IPs de datacenter). A TV envia-o depois de carregar a lista, só quando o `hash` é
/// diferente do `index_hash` que a conta tem (ver PUT /api/goattv/playlists/{pid}/index). O hash é o mesmo do
/// GoatTV Android para a mesma lista, para as TVs não estarem sempre a substituir o índice umas das outras.
public struct ChannelIndex: Sendable {
    public let hash: String
    private let groups: [JSON]
    private let channels: [JSON]

    /// Os limites do servidor.
    static let maxChannels = 100_000
    static let maxGroups = 10_000

    /// O índice de `content` pela ordem do fornecedor; nil se passar os limites do servidor.
    public init?(_ content: PlaylistContent) {
        guard content.channels.count <= Self.maxChannels, content.categories.count <= Self.maxGroups else { return nil }
        var canonical = ""
        var groups: [JSON] = []
        for c in content.categories {
            groups.append(.object(["id": .string(c.id), "name": .string(c.name)]))
            canonical += "g\t\(c.id)\t\(c.name)\n"
        }
        var channels: [JSON] = []
        for ch in content.channels {
            let logo = ch.logo.flatMap { $0.hasPrefix("http://") || $0.hasPrefix("https://") ? $0 : nil }
            var item: [String: JSON] = [
                "key": .string(ch.key), "name": .string(ch.name), "group": .string(ch.categoryId), "num": .number(Double(ch.num)),
            ]
            if let logo { item["logo"] = .string(logo) }
            channels.append(.object(item))
            canonical += "c\t\(ch.key)\t\(ch.name)\t\(ch.categoryId)\t\(ch.num)\t\(logo ?? "")\n"
        }
        hash = SHA.hex(SHA.sha1(Array(canonical.utf8)))
        self.groups = groups
        self.channels = channels
    }

    /// O corpo do pedido (JSON sem compressão, que o servidor também aceita).
    public func body(generatedAt: Date = Date()) -> Data {
        JSON.object([
            "hash": .string(hash),
            "generated_at": .number(Double(Int(generatedAt.timeIntervalSince1970))),
            "groups": .array(groups),
            "channels": .array(channels),
        ]).encoded()
    }
}
