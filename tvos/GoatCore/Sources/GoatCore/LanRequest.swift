import Foundation

/// O início de um pedido à porta dos telemóveis (6471, os canais para o GoatRemote), lido com limite de tamanho.
/// Só se responde a pedidos diretos, como os do GoatRemote (um IP ou um nome de casa em Host, e sem Origin): o que
/// vier de uma página web é recusado. As mesmas regras da porta 6470 do GoatLauncher e do GoatTV Android.
public struct LanRequest: Equatable, Sendable {
    public let method: String
    public let path: String
    let hasOrigin: Bool
    let hosts: [String]

    public static let maxHeadBytes = 8 * 1024

    public var allowed: Bool {
        !hasOrigin && hosts.count == 1 && (Self.isIpHost(hosts[0]) || Self.isHomeName(hosts[0]))
    }

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
        let lines = head.split(separator: 0x0A, omittingEmptySubsequences: false).map { raw -> String in
            var line = raw
            while line.last == 0x0D { line = line.dropLast() }
            return String(String.UnicodeScalarView(line.map { Unicode.Scalar($0) }))
        }
        let requestLine = (lines.first ?? "").split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        guard let method = requestLine.first, !method.isEmpty else { return nil }
        guard requestLine.count > 1, !requestLine[1].isEmpty else { return nil }
        let headers = lines.dropFirst().compactMap { line -> (String, String)? in
            guard let colon = line.firstIndex(of: ":") else { return nil }
            return (trim(line[..<colon]).lowercased(), trim(line[line.index(after: colon)...]))
        }
        return LanRequest(method: method, path: requestLine[1], hasOrigin: headers.contains { $0.0 == "origin" },
                          hosts: headers.filter { $0.0 == "host" }.map { $0.1 })
    }

    private static func trim(_ s: Substring) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Um IPv4 ou um IPv6 entre parênteses retos, com ou sem porta. Nunca um nome (nem se tenta resolvê-lo).
    public static func isIpHost(_ host: String) -> Bool {
        if host.hasPrefix("[") {
            guard let end = host.firstIndex(of: "]") else { return false }
            guard isIpv6(String(host[host.index(after: host.startIndex)..<end])) else { return false }
            let rest = host[host.index(after: end)...]
            return rest.isEmpty || (rest.hasPrefix(":") && isPort(String(rest.dropFirst())))
        }
        let parts = split(host, ":")
        switch parts.count {
        case 1: return isIpv4(parts[0])
        case 2: return isIpv4(parts[0]) && isPort(parts[1])
        default: return false
        }
    }

    /// Um nome que só existe dentro de casa (uma TV juntada à mão pelo nome): um site não o consegue ter.
    public static func isHomeName(_ host: String) -> Bool {
        let parts = split(host.lowercased(), ":")
        if parts.count > 2 || (parts.count == 2 && !isPort(parts[1])) { return false }
        let name = parts[0]
        guard !name.isEmpty, name.allSatisfy({ c in
            guard let v = c.asciiValue else { return false }
            return (v >= 0x61 && v <= 0x7A) || isDigit(c) || c == "-" || c == "."
        }) else { return false }
        return !name.contains(".") || homeSuffixes.contains { name.hasSuffix($0) }
    }

    private static let homeSuffixes = [".local", ".lan", ".home", ".home.arpa", ".internal"]

    /// Como o split do Kotlin: as partes vazias contam.
    private static func split(_ s: String, _ separator: Character) -> [String] {
        s.split(separator: separator, omittingEmptySubsequences: false).map(String.init)
    }

    private static func isDigit(_ c: Character) -> Bool {
        guard let v = c.asciiValue else { return false }
        return v >= 0x30 && v <= 0x39
    }

    private static func isHex(_ c: Character) -> Bool {
        guard let v = c.asciiValue else { return false }
        return isDigit(c) || (v >= 0x61 && v <= 0x66) || (v >= 0x41 && v <= 0x46)
    }

    private static func isPort(_ text: String) -> Bool {
        (1...5).contains(text.count) && text.allSatisfy(isDigit) && (Int(text) ?? Int.max) <= 65535
    }

    private static func isIpv4(_ text: String) -> Bool {
        let parts = split(text, ".")
        return parts.count == 4 && parts.allSatisfy { p in
            (1...3).contains(p.count) && p.allSatisfy(isDigit) && (Int(p) ?? Int.max) <= 255
        }
    }

    private static func isIpv6(_ text: String) -> Bool {
        var address = text
        if let percent = text.firstIndex(of: "%") {
            address = String(text[..<percent])
            let zone = text[text.index(after: percent)...]
            guard !zone.isEmpty, zone.allSatisfy({ c in
                guard let v = c.asciiValue else { return false }
                return isDigit(c) || (v >= 0x41 && v <= 0x5A) || (v >= 0x61 && v <= 0x7A) || "._~-".contains(c)
            }) else { return false }
        }
        guard let gap = address.range(of: "::") else { return groups(address, last: true) == 8 }
        guard address.range(of: "::", options: .backwards)?.lowerBound == gap.lowerBound else { return false }
        let before = groups(String(address[..<gap.lowerBound]), last: false)
        let after = groups(String(address[gap.upperBound...]), last: true)
        return before >= 0 && after >= 0 && before + after <= 7
    }

    /// Quantos grupos de 16 bits tem [part] (um IPv4 no fim conta 2), ou -1 se não estiver bem escrito.
    private static func groups(_ part: String, last: Bool) -> Int {
        if part.isEmpty { return 0 }
        let items = split(part, ":")
        var count = 0
        for (i, item) in items.enumerated() {
            if (1...4).contains(item.count), item.allSatisfy(isHex) {
                count += 1
            } else if last, i == items.count - 1, isIpv4(item) {
                count += 2
            } else {
                return -1
            }
        }
        return count
    }
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
