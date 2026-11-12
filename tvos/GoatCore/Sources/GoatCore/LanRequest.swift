import Foundation
import GoatShared

/// O início de um pedido à porta dos telemóveis (6471, os canais para o GoatRemote), lido com limite de tamanho.
/// Só se responde a pedidos diretos, como os do GoatRemote (um IP ou um nome de casa em Host, e sem Origin): o que
/// vier de uma página web é recusado. As mesmas regras da porta 6470 do GoatLauncher, no núcleo partilhado com o GoatTV Android.
public struct LanRequest: Equatable, Sendable {
    public let method: String
    public let path: String
    /// Sem Origin e com um só Host, que é um IP ou um nome de casa (a regra do núcleo partilhado).
    public let allowed: Bool

    public static let maxHeadBytes = Int(GoatShared.LanRequest.companion.MAX_HEAD_BYTES)

    /// O que já chegou de uma ligação: falta mais, ou acabou (com o pedido, ou nil se não se percebe ou passou do limite).
    public enum Scan: Equatable, Sendable {
        case needMore
        case done(LanRequest?)
    }

    /// Vê os bytes recebidos até à linha em branco, no máximo [maxHeadBytes] (o que vier depois não conta).
    public static func scan(_ bytes: [UInt8]) -> Scan {
        var emptyLine = true
        for (i, byte) in bytes.prefix(maxHeadBytes).enumerated() {
            if byte == 0x0A {
                if emptyLine { return .done(parse(bytes[...i])) }
                emptyLine = true
            } else if byte != 0x0D {
                emptyLine = false
            }
        }
        return bytes.count >= maxHeadBytes ? .done(nil) : .needMore
    }

    /// O pedido em [bytes], que é tudo o que a ligação mandou. nil: fechou antes da linha em branco, passou de
    /// [maxHeadBytes] ou não se percebe.
    public static func read(_ bytes: [UInt8]) -> LanRequest? {
        if case .done(let request) = scan(bytes) { return request }
        return nil
    }

    private static func parse(_ head: ArraySlice<UInt8>) -> LanRequest? {
        // Cada byte um carácter (como no Android): os bytes de fora do ASCII ficam para o decodeKey.
        let text = String(String.UnicodeScalarView(head.map { Unicode.Scalar($0) }))
        guard let request = GoatShared.LanRequest.companion.parse(head: text) else { return nil }
        return LanRequest(method: request.method, path: request.path, allowed: request.allowed)
    }

    /// Um IPv4 ou um IPv6 entre parênteses retos, com ou sem porta. Nunca um nome (nem se tenta resolvê-lo).
    public static func isIpHost(_ host: String) -> Bool { GoatShared.LanRequest.companion.isIpHost(host: host) }

    /// Um nome que só existe dentro de casa (uma TV juntada à mão pelo nome): um site não o consegue ter.
    public static func isHomeName(_ host: String) -> Bool { GoatShared.LanRequest.companion.isHomeName(host: host) }
}

/// Quem pode falar com a porta dos telemóveis: só a rede de casa (endereços privados, de ligação local ou ULA), e
/// nunca a própria TV. Os endereços vão em bytes: 4 (IPv4) ou 16 (IPv6).
public enum LanPeer {
    /// Um IPv4 dentro de um IPv6 (::ffff:a.b.c.d) passa a IPv4; no fe80::/10 tira-se o índice da interface que o
    /// sistema da Apple põe nos bytes 2 e 3. nil se não for um endereço.
    public static func normalized(_ bytes: [UInt8]) -> [UInt8]? {
        switch bytes.count {
        case 4:
            return bytes
        case 16:
            if bytes[0..<10].allSatisfy({ $0 == 0 }), bytes[10] == 0xFF, bytes[11] == 0xFF { return Array(bytes[12...]) }
            var b = bytes
            if b[0] == 0xFE, b[1] & 0xC0 == 0x80 {
                b[2] = 0
                b[3] = 0
            }
            return b
        default:
            return nil
        }
    }

    /// Só a rede de casa: nunca quem venha de fora.
    public static func isHome(_ bytes: [UInt8]) -> Bool {
        guard let b = normalized(bytes) else { return false }
        if b.count == 4 {
            return b[0] == 10 || (b[0] == 172 && b[1] & 0xF0 == 16) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254)
        }
        // fe80::/10 (ligação local), fec0::/10 (o antigo site-local) e fc00::/7 (ULA).
        return (b[0] == 0xFE && b[1] & 0x80 == 0x80) || b[0] & 0xFE == 0xFC
    }

    public static func isLoopback(_ bytes: [UInt8]) -> Bool {
        guard let b = normalized(bytes) else { return false }
        return b.count == 4 ? b[0] == 127 : b == [UInt8](repeating: 0, count: 15) + [1]
    }

    /// A porta é para os telemóveis da rede de casa: uma ligação da própria TV (pelo endereço da ligação, [local], ou
    /// por qualquer dos seus endereços, [own]) não conta.
    public static func allowed(peer: [UInt8], local: [UInt8]?, own: [[UInt8]]) -> Bool {
        guard let p = normalized(peer), isHome(p), !isLoopback(p) else { return false }
        if let local, normalized(local) == p { return false }
        return !own.contains { normalized($0) == p }
    }
}
