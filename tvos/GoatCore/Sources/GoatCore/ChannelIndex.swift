import Foundation
import GoatShared

/// Os grupos e canais de uma lista, para o site da conta os poder organizar (o servidor não descarrega as listas:
/// os fornecedores bloqueiam IPs de datacenter). A TV envia-o depois de carregar a lista, só quando o `hash` é
/// diferente do `index_hash` que a conta tem (ver PUT /api/goattv/playlists/{pid}/index). Feito pelo núcleo
/// partilhado com o Android (ChannelIndex.kt): o mesmo hash para a mesma lista nas duas plataformas.
public struct ChannelIndex: @unchecked Sendable {
    private let shared: GoatShared.ChannelIndex
    public var hash: String { shared.hash }

    /// O índice de `content` pela ordem do fornecedor; nil se passar os limites do servidor.
    public init?(_ content: PlaylistContent) {
        guard let index = AppleBridge.shared.channelIndex(categories: content.categories.map { GoatShared.Category(id: $0.id, name: $0.name) },
                                                          channels: content.channels.map(\.shared)) else { return nil }
        shared = index
    }

    /// O corpo do pedido (JSON sem compressão, que o servidor também aceita).
    public func body(generatedAt: Date = Date()) -> Data {
        Data(shared.body(generatedAt: Int64(generatedAt.timeIntervalSince1970)).utf8)
    }
}
