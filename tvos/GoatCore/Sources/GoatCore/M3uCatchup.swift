import Foundation

/// O catch-up das listas M3U, como os outros players o leem (o `M3uCatchup` do GoatTV Android; a referência é o
/// README e o código do Kodi IPTV Simple). Na lista, por canal ou no `#EXTM3U` para todos:
/// - `catchup` (ou `catchup-type`): default, append, shift, flussonic (fs, flussonic-hls, flussonic-ts) ou xc;
/// - `catchup-source`: o URL (default) ou o que se junta ao do canal (append), com marcadores (ver [expand]);
/// - `catchup-days` (ou `timeshift`, `tvg-rec`): quantos dias o servidor guarda.
/// Só os dias, sem tipo (`timeshift="3"`, `tvg-rec="3"`), é o "shift" (`?utc=…&lutc=…`), ou o xc se o canal for de
/// um painel Xtream (`/live/U/P/123.ts`). O tipo vod (que precisa do `catchup-id` do guia) não se suporta.
public enum M3uCatchup {
    public static let `default` = "default"
    public static let append = "append"
    public static let shift = "shift"
    public static let flussonic = "flussonic"
    public static let flussonicTs = "flussonic-ts"
    public static let xc = "xc"

    /// Os dias quando a lista dá o tipo mas não os dias (o "Catchup days" do Kodi, que vem a 5).
    public static let defaultDays = 5
    private static let maxDays = 31

    /// Os nomes que as listas dão aos tipos.
    private static let types: [String: String] = [
        "default": `default`, "append": append, "shift": shift, "timeshift": shift,
        "flussonic": flussonic, "flussonic-hls": flussonic, "flussonic-ts": flussonicTs, "fs": flussonicTs,
        "xc": xc, "xtream": xc, "xtreamcodes": xc,
    ]

    /// O catch-up de um canal: o tipo, o modelo do URL e os dias.
    public struct Spec: Equatable, Sendable {
        public var type: String
        public var source: String?
        public var days: Int
    }

    /// O catch-up do canal de [url] com os atributos [attrs] do `#EXTINF` (os do `#EXTM3U`, [header], valem para os
    /// canais que não dizem), ou nil se não tiver (ou se for de um tipo que não se sabe fazer).
    public static func spec(_ attrs: [String: String], header: [String: String], url: String) -> Spec? {
        func value(_ names: String...) -> String {
            for from in [attrs, header] {
                for n in names {
                    if let v = from[n]?.trimmingCharacters(in: .whitespaces), !v.isEmpty { return v }
                }
            }
            return ""
        }
        let named = value("catchup", "catchup-type").lowercased()
        let source = value("catchup-source")
        let days = Double(value("catchup-days", "timeshift", "tvg-rec")).flatMap { $0.isFinite && abs($0) < 1e9 ? Int($0) : nil } ?? 0
        let type: String
        if !named.isEmpty {
            guard let t = types[named] else { return nil }
            type = t
        } else if !source.isEmpty {
            type = `default`
        } else if days > 0 {
            // Como o QiTV: um painel Xtream reconhece-se pelo id numérico no fim do URL.
            let id = xtreamParts(url)?.id ?? ""
            type = !id.isEmpty && id.allSatisfy({ $0.isASCII && $0.isNumber }) ? xc : shift
        } else {
            return nil
        }
        // O default e o append são o modelo: sem ele não há URL.
        if type == `default` || type == append {
            if source.isEmpty || expand(source, start: 0, end: 0, now: 0, zone: TimeZone(identifier: "UTC")!) == nil { return nil }
        }
        // O xc e o Flussonic fazem-se a partir do URL do canal: um URL que não é desses não tem catch-up.
        if type == xc && xtreamParts(url) == nil { return nil }
        if (type == flussonic || type == flussonicTs) && flussonicTemplate(url, ts: type == flussonicTs) == nil { return nil }
        let keepsSource = !source.isEmpty && (type == `default` || type == append)
        return Spec(type: type, source: keepsSource ? source : nil, days: min(days > 0 ? days : defaultDays, maxDays))
    }

    /// Ao minuto ou ao segundo: o xc e os modelos só com {Y}{m}{d}{H}{M} pedem minutos certos (o vídeo começa no
    /// minuto), os outros o segundo pedido.
    public static func precisionMs(_ channel: Channel) -> Int64 {
        switch channel.catchupType {
        case xc: return 60_000
        case `default`, append: return secondsPrecise(channel.catchupSource ?? "") ? 1_000 : 60_000
        default: return 1_000
        }
    }

    /// As tentativas para ver [channel] de [start] a [end] (segundos Unix, já com a correção da hora), agora [now].
    /// As datas ({Y}, {H}…, o xc) vão na hora de [zone] (a deste aparelho, como no Kodi). Vazia se o URL do canal não
    /// servir para o tipo.
    public static func candidates(_ channel: Channel, start: Int64, end: Int64, now: Int64, zone: TimeZone = .current) -> [StreamCandidate] {
        guard let url = channel.url else { return [] }
        var urls: [String] = []
        switch channel.catchupType {
        case `default`:
            if let s = channel.catchupSource.flatMap({ expand($0, start: start, end: end, now: now, zone: zone) }) { urls = [s] }
        case append:
            if let s = channel.catchupSource.flatMap({ expand($0, start: start, end: end, now: now, zone: zone) }) { urls = [url + s] }
        case shift:
            urls = [url + (url.contains("?") ? "&" : "?") + "utc=\(start)&lutc=\(now)"]
        case flussonic, flussonicTs:
            if let t = flussonicTemplate(url, ts: channel.catchupType == flussonicTs), let s = expand(t, start: start, end: end, now: now, zone: zone) { urls = [s] }
        case xc:
            urls = xtream(url, start: start, end: end, zone: zone)
        default:
            break
        }
        return urls.filter { $0.hasPrefix("http://") || $0.hasPrefix("https://") }.flatMap { formats($0, live: url) }
    }

    /// [template] com os marcadores preenchidos, ou nil se tiver algum que não se conhece (o pedido sairia errado):
    /// - {utc} ${start} {start}: o início; {utcend} ${end} {end}: o fim; {lutc} ${now} {now} ${timestamp}: agora
    ///   (segundos Unix);
    /// - {duration} ${duration}: a duração em segundos, {duration:60} dividida (em minutos); {offset} ${offset}
    ///   {offset:N}: há quanto tempo começou;
    /// - {Y} {m} {d} {H} {M} {S}: o início; {utc:Y-m-d:H-M} ${start:YmdHMS} (e os outros nomes): uma hora escrita
    ///   com essas letras.
    public static func expand(_ template: String, start: Int64, end: Int64, now: Int64, zone: TimeZone) -> String? {
        var unknown = false
        var out = ""
        for piece in placeholders(template) {
            switch piece {
            case .text(let t):
                out += t
            case .placeholder(let whole, let name, let arg):
                let key = name.lowercased()
                let moment: Int64?
                switch key {
                case "utc", "start": moment = start
                case "utcend", "end": moment = end
                case "lutc", "now", "timestamp": moment = now
                default: moment = nil
                }
                if arg == nil, name.count == 1, parts.contains(name) {
                    out += format(start, name, zone)
                } else if let moment, arg == nil {
                    out += "\(moment)"
                } else if let moment, let arg {
                    out += format(moment, arg, zone)
                } else if key == "duration" || key == "offset" {
                    let seconds = max(0, key == "duration" ? end - start : now - start)
                    if let arg {
                        if let divisor = Int64(arg), divisor > 0 {
                            out += "\(seconds / divisor)"
                        } else {
                            unknown = true
                            out += whole
                        }
                    } else {
                        out += "\(seconds)"
                    }
                } else {
                    unknown = true
                    out += whole
                }
            }
        }
        return unknown || out.contains("{") ? nil : out
    }

    private static let parts: Set<String> = ["Y", "m", "d", "H", "M", "S"]

    private enum Piece {
        case text(String)
        /// O marcador inteiro, o nome e o que vem depois de ":".
        case placeholder(String, String, String?)
    }

    /// `\$?\{([A-Za-z]+)(?::([^{}]*))?\}`: o texto partido em bocados de texto e marcadores.
    private static func placeholders(_ template: String) -> [Piece] {
        let chars = Array(template)
        var pieces: [Piece] = []
        var text = ""
        var i = 0
        while i < chars.count {
            let dollar = chars[i] == "$" && i + 1 < chars.count && chars[i + 1] == "{"
            let open = dollar ? i + 1 : i
            if chars[open] == "{" {
                var j = open + 1
                var name = ""
                while j < chars.count, chars[j].isASCII, chars[j].isLetter { name.append(chars[j]); j += 1 }
                var arg: String?
                var ok = !name.isEmpty
                if ok, j < chars.count, chars[j] == ":" {
                    j += 1
                    var a = ""
                    while j < chars.count, chars[j] != "{", chars[j] != "}" { a.append(chars[j]); j += 1 }
                    arg = a
                }
                ok = ok && j < chars.count && chars[j] == "}"
                if ok {
                    if !text.isEmpty { pieces.append(.text(text)); text = "" }
                    pieces.append(.placeholder(String(chars[i...j]), name, arg))
                    i = j + 1
                    continue
                }
            }
            text.append(chars[i])
            i += 1
        }
        if !text.isEmpty { pieces.append(.text(text)) }
        return pieces
    }

    /// [seconds] escrito com as letras de [pattern] (Y m d H M S; o resto fica como está), na hora de [zone].
    private static func format(_ seconds: Int64, _ pattern: String, _ zone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: Date(timeIntervalSince1970: TimeInterval(seconds)))
        func two(_ v: Int?) -> String { let n = v ?? 0; return n < 10 ? "0\(n)" : "\(n)" }
        return pattern.map { ch -> String in
            switch ch {
            case "Y":
                let y = c.year ?? 0
                return String(repeating: "0", count: max(0, 4 - "\(y)".count)) + "\(y)"
            case "m": return two(c.month)
            case "d": return two(c.day)
            case "H": return two(c.hour)
            case "M": return two(c.minute)
            case "S": return two(c.second)
            default: return String(ch)
            }
        }.joined()
    }

    /// O modelo diz o segundo (um instante Unix, um {S}, um "há quanto tempo"), ou só o minuto?
    private static func secondsPrecise(_ template: String) -> Bool {
        placeholders(template).contains { piece in
            guard case .placeholder(_, let name, let arg) = piece else { return false }
            let key = name.lowercased()
            if name == "S" || key == "offset" { return true }
            if key == "utc" || key == "start" { return arg == nil || arg!.contains("S") }
            return false
        }
    }

    /// `(https?://[^/?#]+)(/[^?#]*)(\?[^#]*)?`: a origem, o caminho e a query (sem o fragmento).
    private static func urlParts(_ url: String) -> (origin: String, path: String, query: String)? {
        let scheme: String
        if url.hasPrefix("http://") { scheme = "http://" } else if url.hasPrefix("https://") { scheme = "https://" } else { return nil }
        let rest = url.dropFirst(scheme.count)
        guard let slash = rest.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }), rest[slash] == "/", slash > rest.startIndex else { return nil }
        let host = rest[..<slash]
        let afterHost = rest[slash...]
        // Um fragmento: o Android não aceita (a expressão vai até ao fim).
        guard !afterHost.contains("#") else { return nil }
        let path: Substring
        var query = ""
        if let q = afterHost.firstIndex(of: "?") {
            path = afterHost[..<q]
            query = String(afterHost[q...])
        } else {
            path = afterHost
        }
        return (scheme + host, String(path), query)
    }

    /// O modelo do Flussonic feito a partir do URL do canal (como o Kodi): `…/canal/mpegts` (e os .ts) →
    /// `…/canal/timeshift_abs-{utc}.ts`; `…/canal/index.m3u8` (ou mono, video…) → `…/canal/index-{utc}-{duration}.m3u8`,
    /// a lista do arquivo com princípio e fim. Outro URL: o último bocado troca-se pelo do arquivo, TS com o tipo
    /// flussonic-ts/fs e HLS com o flussonic. A query do canal (o token) fica.
    private static func flussonicTemplate(_ url: String, ts: Bool) -> String? {
        guard let (origin, path, query) = urlParts(url) else { return nil }
        let dir: String
        let last: String
        if let slash = path.lastIndex(of: "/") {
            dir = String(path[..<slash])
            last = String(path[path.index(after: slash)...])
        } else {
            dir = ""
            last = path
        }
        if last.hasSuffix(".m3u8") {
            let base = String(last.dropLast(5))
            return "\(origin)\(dir)/\(base.isEmpty ? "index" : base)-{utc}-{duration}.m3u8\(query)"
        }
        if last == "mpegts" || last.hasSuffix(".ts") { return "\(origin)\(dir)/timeshift_abs-{utc}.ts\(query)" }
        if dir.isEmpty { return ts ? "\(origin)\(path)/timeshift_abs-{utc}.ts\(query)" : "\(origin)\(path)/index-{utc}-{duration}.m3u8\(query)" }
        return ts ? "\(origin)\(dir)/timeshift_abs-{utc}.ts\(query)" : "\(origin)\(dir)/index-{utc}-{duration}.m3u8\(query)"
    }

    /// O URL de um canal de um painel Xtream (`…/live/U/P/123.ts` ou `…/U/P/123`): a origem, U, P, o id e a extensão.
    /// A do Kodi: `^(http[s]?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/\.]+)(\.m3u[8]?)?$` (aqui também .ts).
    private static func xtreamParts(_ url: String) -> (origin: String, user: String, pass: String, id: String, ext: String)? {
        let scheme: String
        if url.hasPrefix("http://") { scheme = "http://" } else if url.hasPrefix("https://") { scheme = "https://" } else { return nil }
        let rest = url.dropFirst(scheme.count)
        var segments = rest.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard segments.count >= 4, !segments[0].isEmpty else { return nil }
        let origin = scheme + segments.removeFirst()
        if segments.count == 4, segments[0] == "live" { segments.removeFirst() }
        guard segments.count == 3, !segments[0].isEmpty, !segments[1].isEmpty else { return nil }
        let file = segments[2]
        // ([^/.?]+)(\.m3u8?|\.ts)?
        let id = String(file.prefix { $0 != "." && $0 != "?" })
        let ext = String(file.dropFirst(id.count))
        guard !id.isEmpty, ["", ".m3u", ".m3u8", ".ts"].contains(ext) else { return nil }
        return (origin, segments[0], segments[1], id, ext)
    }

    /// O catch-up xc: o `/timeshift/` do Xtream, ao minuto, e o `timeshift.php` (os servidores aceitam um ou os dois).
    private static func xtream(_ url: String, start: Int64, end: Int64, zone: TimeZone) -> [String] {
        guard let p = xtreamParts(url) else { return [] }
        let minutes = max(1, (end - start + 59) / 60)
        let when = format(start, "Y-m-d:H-M", zone)
        let file = p.id + (p.ext.hasPrefix(".m3u") ? ".m3u8" : ".ts")
        return [
            "\(p.origin)/timeshift/\(p.user)/\(p.pass)/\(minutes)/\(when)/\(file)",
            "\(p.origin)/streaming/timeshift.php?username=\(p.user)&password=\(p.pass)&stream=\(p.id)&start=\(when)&duration=\(minutes)",
        ]
    }

    /// Como abrir [url]: pela extensão; sem ela, primeiro o formato do canal ([live]) e depois o outro.
    private static func formats(_ url: String, live: String) -> [StreamCandidate] {
        func bare(_ s: String) -> String {
            String(s.prefix { $0 != "?" && $0 != "#" }).lowercased()
        }
        let path = bare(url)
        if path.hasSuffix(".m3u8") || path.hasSuffix(".m3u") { return [StreamCandidate(url: url, format: StreamFormat.hls)] }
        if path.hasSuffix(".ts") || path.hasSuffix("/mpegts") || path.hasSuffix(".mpg") { return [StreamCandidate(url: url, format: StreamFormat.ts)] }
        if bare(live).hasSuffix(".m3u8") {
            return [StreamCandidate(url: url, format: StreamFormat.hls), StreamCandidate(url: url, format: StreamFormat.ts)]
        }
        return [StreamCandidate(url: url, format: StreamFormat.ts), StreamCandidate(url: url, format: StreamFormat.hls)]
    }
}
