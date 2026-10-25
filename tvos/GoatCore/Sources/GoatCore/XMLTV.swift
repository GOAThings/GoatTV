import Foundation

/// Parser XMLTV byte a byte (como o do Android): aguenta guias de dezenas de MB e só percebe o que o guia precisa,
/// `<channel id>` com `<display-name>` e `<programme start stop channel>` com `<title>`.
public struct XMLTVParser {
    public var onChannel: (_ id: String, _ names: [String]) -> Void
    /// Devolve false para não ler o título (programa que não interessa).
    public var wantsProgramme: (_ channel: String, _ startMs: Int64, _ endMs: Int64) -> Bool
    public var onProgramme: (_ channel: String, _ startMs: Int64, _ endMs: Int64, _ title: String) -> Void

    public init(onChannel: @escaping (String, [String]) -> Void,
                wantsProgramme: @escaping (String, Int64, Int64) -> Bool,
                onProgramme: @escaping (String, Int64, Int64, String) -> Void) {
        self.onChannel = onChannel
        self.wantsProgramme = wantsProgramme
        self.onProgramme = onProgramme
    }

    public func parse(_ data: Data) {
        data.withUnsafeBytes { raw in
            parse(raw.bindMemory(to: UInt8.self))
        }
    }

    private func parse(_ b: UnsafeBufferPointer<UInt8>) {
        let n = b.count
        var i = 0
        var capture: [UInt8]? // texto do elemento atual (display-name ou title)
        var channelId: String?
        var names: [String] = []
        var progChannel: String?
        var progStart: Int64 = 0
        var progEnd: Int64 = 0
        var title: String?

        while i < n {
            let c = b[i]
            if c != 0x3C { // '<'
                if capture != nil {
                    // Copia o texto até ao próximo '<' de uma vez.
                    var j = i
                    while j < n, b[j] != 0x3C { j += 1 }
                    capture!.append(contentsOf: UnsafeBufferPointer(rebasing: b[i..<j]))
                    i = j
                } else {
                    i += 1
                }
                continue
            }
            i += 1
            // Comentários, CDATA, <?xml ?> e <!DOCTYPE>.
            if i < n, b[i] == 0x21 { // '!'
                if matches(b, i, "!--") {
                    i = skip(b, from: i + 3, until: "-->")
                } else if matches(b, i, "![CDATA[") {
                    let start = i + 8
                    let end = find(b, from: start, "]]>")
                    if capture != nil {
                        // O texto do CDATA conta como texto do elemento, sem entidades.
                        let text = String(decoding: UnsafeBufferPointer(rebasing: b[start..<min(end, n)]), as: UTF8.self)
                        capture!.append(contentsOf: Array(text.replacingOccurrences(of: "&", with: "&amp;").utf8))
                    }
                    i = min(end + 3, n)
                } else {
                    i = skip(b, from: i, until: ">")
                }
                continue
            }
            if i < n, b[i] == 0x3F { // '?'
                i = skip(b, from: i, until: ">")
                continue
            }
            // Fim da etiqueta: o primeiro '>' fora de aspas.
            let tagStart = i
            var quote: UInt8 = 0
            while i < n {
                let d = b[i]
                if quote != 0 {
                    if d == quote { quote = 0 }
                } else if d == 0x22 || d == 0x27 {
                    quote = d
                } else if d == 0x3E {
                    break
                }
                i += 1
            }
            guard i < n else { break }
            let tag = Tag(UnsafeBufferPointer(rebasing: b[tagStart..<i]))
            i += 1

            if tag.closing {
                switch tag.name {
                case "display-name":
                    if let cap = capture { names.append(Self.decode(String(decoding: cap, as: UTF8.self)).trimmed) }
                case "title":
                    if let cap = capture, title == nil { title = Self.decode(String(decoding: cap, as: UTF8.self)).trimmed }
                case "channel":
                    if let id = channelId { onChannel(id, names.filter { !$0.isEmpty }) }
                    channelId = nil
                    names.removeAll()
                case "programme":
                    if let ch = progChannel, let t = title, !t.isEmpty { onProgramme(ch, progStart, progEnd, t) }
                    progChannel = nil
                    title = nil
                default:
                    break
                }
                if tag.name == "display-name" || tag.name == "title" { capture = nil }
                continue
            }
            switch tag.name {
            case "channel":
                channelId = tag.selfClosing ? nil : tag.attr("id")?.trimmed
                names.removeAll()
            case "display-name":
                if channelId != nil, !tag.selfClosing { capture = [] }
            case "programme":
                let ch = tag.attr("channel")?.trimmed
                let start = tag.attr("start").flatMap(Self.parseTime)
                let stop = tag.attr("stop").flatMap(Self.parseTime)
                title = nil
                if !tag.selfClosing, let ch, let start, let stop, stop > start, wantsProgramme(ch, start, stop) {
                    progChannel = ch
                } else {
                    progChannel = nil
                }
                progStart = start ?? 0
                progEnd = stop ?? 0
            case "title":
                if progChannel != nil, title == nil, !tag.selfClosing { capture = [] }
            default:
                break
            }
        }
    }

    private struct Tag {
        var name: String
        var closing: Bool
        var selfClosing: Bool
        private var attrBytes: [UInt8]

        init(_ body: UnsafeBufferPointer<UInt8>) {
            var start = 0
            var end = body.count
            closing = end > 0 && body[0] == 0x2F
            if closing { start = 1 }
            selfClosing = end > start && body[end - 1] == 0x2F
            if selfClosing { end -= 1 }
            while start < end, Self.isSpace(body[start]) { start += 1 }
            var nameEnd = start
            while nameEnd < end, !Self.isSpace(body[nameEnd]) { nameEnd += 1 }
            name = String(decoding: UnsafeBufferPointer(rebasing: body[start..<nameEnd]), as: UTF8.self)
            attrBytes = Array(body[nameEnd..<end])
        }

        /// Um atributo `nome="valor"` ou `nome='valor'`, já com as entidades trocadas.
        func attr(_ wanted: String) -> String? {
            let key = Array(wanted.utf8)
            let b = attrBytes
            var i = 0
            while i < b.count {
                while i < b.count, Self.isSpace(b[i]) { i += 1 }
                let nameStart = i
                while i < b.count, b[i] != 0x3D, !Self.isSpace(b[i]) { i += 1 }
                let nameEnd = i
                while i < b.count, Self.isSpace(b[i]) { i += 1 }
                guard i < b.count, b[i] == 0x3D else { i += 1; continue }
                i += 1
                while i < b.count, Self.isSpace(b[i]) { i += 1 }
                guard i < b.count, b[i] == 0x22 || b[i] == 0x27 else { continue }
                let q = b[i]
                i += 1
                let valueStart = i
                while i < b.count, b[i] != q { i += 1 }
                let valueEnd = i
                i += 1
                if nameEnd - nameStart == key.count, Array(b[nameStart..<nameEnd]) == key {
                    return XMLTVParser.decode(String(decoding: b[valueStart..<min(valueEnd, b.count)], as: UTF8.self))
                }
            }
            return nil
        }

        static func isSpace(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D }
    }

    private func matches(_ b: UnsafeBufferPointer<UInt8>, _ at: Int, _ s: String) -> Bool {
        let u = Array(s.utf8)
        guard at + u.count <= b.count else { return false }
        for k in 0..<u.count where b[at + k] != u[k] { return false }
        return true
    }

    private func find(_ b: UnsafeBufferPointer<UInt8>, from: Int, _ s: String) -> Int {
        var i = from
        while i < b.count {
            if matches(b, i, s) { return i }
            i += 1
        }
        return b.count
    }

    private func skip(_ b: UnsafeBufferPointer<UInt8>, from: Int, until s: String) -> Int {
        let at = find(b, from: from, s)
        return min(at + s.utf8.count, b.count)
    }

    // MARK: Entidades e datas

    public static func decode(_ s: String) -> String {
        guard s.contains("&") else { return s }
        var out = ""
        out.reserveCapacity(s.count)
        var i = s.startIndex
        while i < s.endIndex {
            let c = s[i]
            if c == "&", let semi = s[i...].firstIndex(of: ";"), s.distance(from: i, to: semi) <= 10 {
                let entity = s[s.index(after: i)..<semi]
                var replacement: String?
                switch entity {
                case "amp": replacement = "&"
                case "lt": replacement = "<"
                case "gt": replacement = ">"
                case "quot": replacement = "\""
                case "apos": replacement = "'"
                default:
                    if entity.hasPrefix("#x") || entity.hasPrefix("#X"), let v = UInt32(entity.dropFirst(2), radix: 16) {
                        replacement = Unicode.Scalar(v).map { String(Character($0)) } ?? ""
                    } else if entity.hasPrefix("#"), let v = UInt32(entity.dropFirst()) {
                        replacement = Unicode.Scalar(v).map { String(Character($0)) } ?? ""
                    }
                }
                if let replacement {
                    out += replacement
                    i = s.index(after: semi)
                    continue
                }
            }
            out.append(c)
            i = s.index(after: i)
        }
        return out
    }

    /// "20261019120000 +0100" → ms desde 1970 (sem fuso, assume-se UTC).
    public static func parseTime(_ raw: String) -> Int64? {
        let t = Array(raw.trimmingCharacters(in: .whitespaces).utf8)
        guard t.count >= 12 else { return nil }
        func num(_ from: Int, _ len: Int) -> Int? {
            var v = 0
            for k in from..<(from + len) {
                guard k < t.count, t[k] >= 0x30, t[k] <= 0x39 else { return nil }
                v = v * 10 + Int(t[k] - 0x30)
            }
            return v
        }
        guard let year = num(0, 4),
              let month = num(4, 2), (1...12).contains(month),
              let day = num(6, 2), (1...31).contains(day),
              let hour = num(8, 2), (0...23).contains(hour),
              let minute = num(10, 2), (0...59).contains(minute) else { return nil }
        let second = t.count >= 14 ? (num(12, 2) ?? 0) : 0
        var offsetMin = 0
        let zone = String(decoding: t[min(14, t.count)...], as: UTF8.self).trimmed
        let z = Array(zone.utf8)
        if z.count >= 5, z[0] == 0x2B || z[0] == 0x2D {
            guard let hh = Int(String(decoding: z[1..<3], as: UTF8.self)),
                  let mm = Int(String(decoding: z[3..<5], as: UTF8.self)) else { return nil }
            offsetMin = (hh * 60 + mm) * (z[0] == 0x2D ? -1 : 1)
        }
        let days = daysFromCivil(year, month, day)
        return ((days * 86400 + Int64(hour) * 3600 + Int64(minute) * 60 + Int64(second)) - Int64(offsetMin) * 60) * 1000
    }

    /// Dias desde 1970-01-01 (algoritmo de Howard Hinnant).
    private static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int64 {
        let y = m <= 2 ? y0 - 1 : y0
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return Int64(era) * 146_097 + Int64(doe) - 719_468
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Guia XMLTV de uma lista, reduzido aos canais dela e a uma janela à volta de agora.
public struct XMLTVGuide: Codable, Sendable {
    public let url: String
    public let fetchedAt: Date
    /// id do canal no XMLTV (minúsculas) → programas por ordem.
    public let programmes: [String: [EpgEntry]]
    /// Nome normalizado → id, para canais sem `tvg-id` (ou com um que o guia não tem).
    public let names: [String: String]

    public func entries(for channel: Channel) -> [EpgEntry] {
        if let id = channel.epgId?.lowercased(), let list = programmes[id] { return list }
        guard let id = names[Self.normalizeName(channel.name)] else { return [] }
        return programmes[id] ?? []
    }

    public static let windowBeforeMs: Int64 = 24 * 3_600_000
    public static let windowAfterMs: Int64 = 36 * 3_600_000

    /// Lê o XMLTV guardando só os programas dos [channels] entre [from] e [to].
    public static func build(url: String, data: Data, channels: [Channel], now: Int64 = nowMs()) -> XMLTVGuide {
        let from = now - windowBeforeMs
        let to = now + windowAfterMs
        let wantedIds = Set(channels.compactMap { $0.epgId?.lowercased() })
        var channelNames = Set(channels.map { normalizeName($0.name) })
        channelNames.remove("")
        var names: [String: String] = [:]
        var nameIds = Set<String>()
        var programmes: [String: [EpgEntry]] = [:]
        var titles: [String: String] = [:] // os títulos repetem-se muito: uma só String por título

        XMLTVParser(
            onChannel: { id, displayNames in
                let lower = id.lowercased()
                for n in displayNames {
                    let key = normalizeName(n)
                    if channelNames.contains(key), names[key] == nil {
                        names[key] = lower
                        nameIds.insert(lower)
                    }
                }
            },
            wantsProgramme: { ch, start, end in
                guard end > from, start < to else { return false }
                let lower = ch.lowercased()
                return wantedIds.contains(lower) || nameIds.contains(lower)
            },
            onProgramme: { ch, start, end, title in
                let t = titles[title] ?? title
                titles[title] = t
                programmes[ch.lowercased(), default: []].append(EpgEntry(title: t, startMs: start, endMs: end))
            }
        ).parse(data)

        for key in programmes.keys { programmes[key]!.sort { $0.startMs < $1.startMs } }
        return XMLTVGuide(url: url, fetchedAt: Date(), programmes: programmes,
                          names: names.filter { programmes[$0.value] != nil })
    }

    /// "PT: RTP 1 HD" → "rtp1": o mesmo canal escrito de formas diferentes na lista e no guia.
    public static func normalizeName(_ name: String) -> String {
        var s = name.lowercased().folding(options: .diacriticInsensitive, locale: nil).trimmed
        s = prefix.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
        s = quality.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: "")
        return String(String.UnicodeScalarView(s.unicodeScalars.filter { ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9") }))
    }

    private static let prefix = try! NSRegularExpression(pattern: #"^[a-z]{2,3}\s*[:|]\s*"#)
    private static let quality = try! NSRegularExpression(pattern: #"\b(uhd|fhd|hd|sd|4k|hevc|h265|1080p?|720p?)\b"#)
}

/// Guia da conta de uma lista (os canais que a pessoa associou a um canal de guia). No XMLTV que o servidor devolve,
/// o id de cada canal já é o channelKey da lista.
public struct AccountGuide: Codable, Sendable {
    public let playlistId: String
    public let etag: String?
    public let fetchedAt: Date
    /// channelKey → programas por ordem.
    public let programmes: [String: [EpgEntry]]

    public static func parse(playlistId: String, etag: String?, data: Data, now: Int64 = nowMs()) -> AccountGuide {
        let from = now - XMLTVGuide.windowBeforeMs
        let to = now + XMLTVGuide.windowAfterMs
        var programmes: [String: [EpgEntry]] = [:]
        var titles: [String: String] = [:]
        XMLTVParser(
            onChannel: { _, _ in },
            wantsProgramme: { _, start, end in end > from && start < to },
            onProgramme: { ch, start, end, title in
                let t = titles[title] ?? title
                titles[title] = t
                programmes[ch, default: []].append(EpgEntry(title: t, startMs: start, endMs: end))
            }
        ).parse(data)
        for key in programmes.keys { programmes[key]!.sort { $0.startMs < $1.startMs } }
        return AccountGuide(playlistId: playlistId, etag: etag, fetchedAt: Date(), programmes: programmes)
    }

    /// O mesmo guia (resposta 304), com a hora atualizada.
    public func refreshed() -> AccountGuide {
        AccountGuide(playlistId: playlistId, etag: etag, fetchedAt: Date(), programmes: programmes)
    }
}
