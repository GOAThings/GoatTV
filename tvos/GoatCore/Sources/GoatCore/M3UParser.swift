import Foundation

/// Parser M3U/M3U8, igual ao do GoatTV Android (os `channelKey` têm de sair iguais, por causa dos favoritos).
/// Só guarda canais de TV em direto; ignora filmes e séries.
public enum M3UParser {
    public struct Result: Sendable {
        public var categories: [Category]
        public var channels: [Channel]
        public var epgUrl: String?
    }

    public static func parse(_ data: Data, channelName: (Int) -> String = { "Channel \($0)" }) -> Result {
        var channels: [Channel] = []
        var groups: [String] = []
        var seenGroups = Set<String>()
        var usedKeys = Set<String>()
        var epgUrl: String?

        var pending: (attrs: [String: String], name: String)?
        var group: String?
        var userAgent: String?
        var referrer: String?

        forEachLine(data) { raw in
            var line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("\u{FEFF}") { line.removeFirst() }
            if line.isEmpty { return }
            if line.hasPrefix("#EXTM3U") {
                let attrs = attributes(Substring(line))
                if let tvg = attrs["x-tvg-url"] ?? attrs["url-tvg"],
                   let first = tvg.split(separator: ",").first?.trimmingCharacters(in: .whitespaces), !first.isEmpty {
                    epgUrl = first
                }
            } else if line.hasPrefix("#EXTINF") {
                pending = parseExtinf(line)
                group = nil
                userAgent = nil
                referrer = nil
            } else if line.hasPrefix("#EXTGRP:") {
                group = String(line.dropFirst("#EXTGRP:".count)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("#EXTVLCOPT:") {
                let opt = line.dropFirst("#EXTVLCOPT:".count)
                if opt.hasPrefix("http-user-agent=") {
                    userAgent = String(opt.dropFirst("http-user-agent=".count)).trimmingCharacters(in: .whitespaces)
                } else if opt.hasPrefix("http-referrer=") {
                    referrer = String(opt.dropFirst("http-referrer=".count)).trimmingCharacters(in: .whitespaces)
                }
            } else if line.hasPrefix("#") {
                return
            } else {
                guard let info = pending else { return }
                pending = nil
                if isVod(line) { return }

                // group-title; vazio ou em falta, o último #EXTGRP deste canal; sem nenhum, "Outros".
                let category = nonEmpty(info.attrs["group-title"]) ?? nonEmpty(group) ?? PlaylistContent.others
                if seenGroups.insert(category).inserted { groups.append(category) }

                // channelKey: tvg-id; se faltar (ou se repetir), hash curto do URL.
                let tvgId = info.attrs["tvg-id"]?.trimmingCharacters(in: .whitespaces) ?? ""
                let key: String
                if !tvgId.isEmpty, usedKeys.insert(tvgId).inserted {
                    key = tvgId
                } else {
                    key = urlKey(line)
                }
                usedKeys.insert(key)

                let index = channels.count
                var name = info.name
                if name.isEmpty { name = info.attrs["tvg-name"] ?? "" }
                if name.isEmpty { name = channelName(index + 1) }
                let logo = info.attrs["tvg-logo"].flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
                channels.append(Channel(
                    uid: index, key: key, num: index + 1, name: name, logo: logo, categoryId: category,
                    url: line, userAgent: userAgent, referrer: referrer, epgId: tvgId.isEmpty ? nil : tvgId
                ))
            }
        }
        return Result(categories: groups.map { Category(id: $0, name: $0) }, channels: channels, epgUrl: epgUrl)
    }

    /// Heurística para filmes e séries: `/movie/`, `/series/`, `.mp4` ou `.mkv`.
    public static func isVod(_ url: String) -> Bool {
        let path = url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
            .split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0].lowercased()
        return path.contains("/movie/") || path.contains("/series/") || path.hasSuffix(".mp4") || path.hasSuffix(".mkv")
    }

    /// "u" + 12 hexadecimais do SHA-1 do URL (o mesmo que no Android).
    public static func urlKey(_ url: String) -> String {
        "u" + SHA.hex(Array(SHA.sha1(Array(url.utf8)).prefix(6)))
    }

    private static func nonEmpty(_ s: String?) -> String? {
        guard let t = s?.trimmingCharacters(in: .whitespaces), !t.isEmpty else { return nil }
        return t
    }

    private static func parseExtinf(_ line: String) -> (attrs: [String: String], name: String) {
        // O nome vem depois da primeira vírgula fora de aspas (os atributos podem ter vírgulas).
        var inQuotes = false
        var comma: String.Index?
        var i = line.startIndex
        while i < line.endIndex {
            let c = line[i]
            if c == "\"" {
                inQuotes.toggle()
            } else if c == ",", !inQuotes {
                comma = i
                break
            }
            i = line.index(after: i)
        }
        guard let comma else { return (attributes(Substring(line)), "") }
        let head = line[..<comma]
        let name = line[line.index(after: comma)...].trimmingCharacters(in: .whitespaces)
        return (attributes(head), name)
    }

    /// `chave="valor"` (chave em minúsculas), como a expressão `([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"` do Android.
    static func attributes(_ s: Substring) -> [String: String] {
        let bytes = Array(s.utf8)
        var result: [String: String] = [:]
        var i = 0
        func isKey(_ b: UInt8) -> Bool {
            (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A) || b == 0x5F || b == 0x2D
        }
        func isSpace(_ b: UInt8) -> Bool { b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0B || b == 0x0C }
        while i < bytes.count {
            guard isKey(bytes[i]) else { i += 1; continue }
            let keyStart = i
            while i < bytes.count, isKey(bytes[i]) { i += 1 }
            let keyEnd = i
            var j = i
            while j < bytes.count, isSpace(bytes[j]) { j += 1 }
            guard j < bytes.count, bytes[j] == 0x3D else { continue } // '='
            j += 1
            while j < bytes.count, isSpace(bytes[j]) { j += 1 }
            guard j < bytes.count, bytes[j] == 0x22 else { continue } // '"'
            j += 1
            let valueStart = j
            while j < bytes.count, bytes[j] != 0x22 { j += 1 }
            guard j < bytes.count else { continue }
            // Repetido, fica o último (como o `associate` do Kotlin).
            let key = String(decoding: bytes[keyStart..<keyEnd], as: UTF8.self).lowercased()
            result[key] = String(decoding: bytes[valueStart..<j], as: UTF8.self)
            i = j + 1
        }
        return result
    }

    /// Chama [body] para cada linha (separadas por \n, com ou sem \r), sem copiar o ficheiro todo para uma String.
    private static func forEachLine(_ data: Data, _ body: (String) -> Void) {
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let buf = raw.bindMemory(to: UInt8.self)
            var start = 0
            let n = buf.count
            var i = 0
            while i <= n {
                if i == n || buf[i] == 0x0A {
                    if i > start {
                        body(String(decoding: UnsafeBufferPointer(rebasing: buf[start..<i]), as: UTF8.self))
                    }
                    start = i + 1
                }
                i += 1
            }
        }
    }
}
