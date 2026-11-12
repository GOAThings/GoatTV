import Foundation
import GoatShared

/// Parser M3U/M3U8: o do núcleo partilhado com o Android (shared/…/data/m3u/M3uParser.kt), por isso os `channelKey`
/// saem iguais (os favoritos). Só guarda canais de TV em direto; ignora filmes e séries. Aqui só se partem as linhas.
public enum M3UParser {
    public struct Result: Sendable {
        public var categories: [Category]
        public var channels: [Channel]
        public var epgUrl: String?
    }

    public static func parse(_ data: Data, channelName: @escaping (Int) -> String = { "Channel \($0)" }) -> Result {
        let reader = AppleM3u(channelName: { channelName(Int($0.int32Value)) })
        forEachLine(data) { reader.line(raw: $0) }
        return Result(categories: reader.categories().map { Category(id: $0.id, name: $0.name) },
                      channels: reader.channels().map { Channel($0) }, epgUrl: reader.epgUrl())
    }

    /// O número que a lista dá ao canal (tvg-chno, ou os nomes que outras listas usam).
    static func channelNumber(_ attrs: [String: String]) -> Int? {
        let n = AppleBridge.shared.m3uChannelNumber(attrs: attrs)
        return n < 0 ? nil : Int(n)
    }

    /// Heurística para filmes e séries: `/movie/`, `/series/`, `.mp4` ou `.mkv`.
    public static func isVod(_ url: String) -> Bool { AppleBridge.shared.m3uIsVod(url: url) }

    /// "u" + 12 hexadecimais do SHA-1 do URL.
    public static func urlKey(_ url: String) -> String { AppleBridge.shared.m3uUrlKey(url: url) }

    /// `chave="valor"` (chave em minúsculas; repetida, fica a última).
    static func attributes(_ s: Substring) -> [String: String] { AppleBridge.shared.m3uAttributes(s: String(s)) }

    /// Chama [body] para cada linha (fins de linha \n, \r\n ou \r, como no Android), sem copiar o ficheiro todo para
    /// uma String.
    private static func forEachLine(_ data: Data, _ body: (String) -> Void) {
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let buf = raw.bindMemory(to: UInt8.self)
            var start = 0
            let n = buf.count
            var i = 0
            while i <= n {
                // \r\n conta como um fim só (a linha vazia entre os dois não faz nada).
                if i == n || buf[i] == 0x0A || buf[i] == 0x0D {
                    // Uma linha sem fim não é um canal: fica de fora (o corpo inteiro já tem um limite).
                    if i > start, i - start <= SafeInput.maxM3ULine {
                        body(String(decoding: UnsafeBufferPointer(rebasing: buf[start..<i]), as: UTF8.self))
                    }
                    start = i + 1
                }
                i += 1
            }
        }
    }
}
