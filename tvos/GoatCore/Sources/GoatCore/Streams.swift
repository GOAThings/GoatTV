import Foundation

/// Uma forma de abrir um canal. O player tenta-as por ordem (ts ⇄ m3u8).
public struct StreamCandidate: Equatable, Sendable {
    public var url: String
    public var format: String
    public var isHLS: Bool { format == StreamFormat.hls }
}

/// Tentativas para [channel], como no Android. [preferred] é o `stream_format` da conta (nil = automático);
/// [allowedFormats] é o `allowed_output_formats` da conta Xtream.
public func streamCandidates(playlist: Playlist, channel: Channel, preferred: String?,
                             allowedFormats: Set<String> = []) -> [StreamCandidate] {
    let order = preferred == StreamFormat.hls ? [StreamFormat.hls, StreamFormat.ts] : [StreamFormat.ts, StreamFormat.hls]

    // O link pede-se ao portal ao abrir o canal (StalkerClient.candidates).
    if playlist.type == .stalker { return [] }

    if playlist.type == .xtream {
        guard let id = channel.streamId else { return [] }
        // Se o servidor diz que formatos permite, não vale a pena tentar os outros.
        let allowed = order.filter { allowedFormats.contains($0) }
        return (allowed.isEmpty ? order : allowed).compactMap { format in
            Xtream.streamURL(playlist, streamId: id, ext: format).map { StreamCandidate(url: $0, format: format) }
        }
    }

    guard let url = channel.url else { return [] }
    let path = String(url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
        .split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0])
    let rest = String(url.dropFirst(path.count))
    let lower = path.lowercased()
    if lower.hasSuffix(".m3u8") {
        return [StreamCandidate(url: url, format: StreamFormat.hls),
                StreamCandidate(url: String(path.dropLast(5)) + ".ts" + rest, format: StreamFormat.ts)]
    }
    if lower.hasSuffix(".ts") {
        return [StreamCandidate(url: url, format: StreamFormat.ts),
                StreamCandidate(url: String(path.dropLast(3)) + ".m3u8" + rest, format: StreamFormat.hls)]
    }
    // Sem extensão: o mesmo URL, primeiro como HLS (o mais comum em M3U) a não ser que se prefira TS.
    if preferred == StreamFormat.ts {
        return [StreamCandidate(url: url, format: StreamFormat.ts), StreamCandidate(url: url, format: StreamFormat.hls)]
    }
    return [StreamCandidate(url: url, format: StreamFormat.hls), StreamCandidate(url: url, format: StreamFormat.ts)]
}

/// Um programa que já passou (ou o atual desde o início), a ver em catch-up a partir de [positionMs].
public struct Catchup: Equatable, Sendable {
    public var channel: Channel
    public var title: String
    public var startMs: Int64
    public var endMs: Int64
    /// Onde o vídeo começa, em ms desde [startMs] (avançar/recuar = pedir de novo daí).
    public var positionMs: Int64

    public init(channel: Channel, title: String, startMs: Int64, endMs: Int64, positionMs: Int64 = 0) {
        self.channel = channel
        self.title = title
        self.startMs = startMs
        self.endMs = endMs
        self.positionMs = positionMs
    }

    public var durationMs: Int64 { endMs - startMs }

    /// O programa ainda está no arquivo do servidor? (Xtream com `tv_archive`, M3U com `catchup`, Stalker com o
    /// arquivo do portal, dentro dos dias que guarda, e pelo menos 2 minutos depois de começar.)
    public static func available(playlist: Playlist, channel: Channel, entry: EpgEntry, now: Int64 = nowMs()) -> Bool {
        guard channel.archiveDays > 0 else { return false }
        switch playlist.type {
        case .xtream, .stalker: break
        case .m3u: guard channel.catchupType != nil else { return false }
        }
        // O valor pode vir de uma cópia em cache, de antes de ser limitado ao ler a lista.
        return entry.startMs < now - minAgeMs && entry.startMs > now - Int64(min(channel.archiveDays, SafeInput.maxArchiveDays)) * 86_400_000
    }

    public static let minAgeMs: Int64 = 2 * 60_000
}

/// Onde começa o catch-up (ao minuto, que é como o servidor trabalha) e as formas de o pedir.
public struct CatchupStart: Sendable {
    public var startMs: Int64
    public var candidates: [StreamCandidate]
}

/// Tentativas para ver [catchup] (Xtream e M3U). O Xtream trabalha ao minuto (e o M3U conforme o tipo, ver
/// M3uCatchup.precisionMs): pede-se a partir do minuto (ou do segundo) da posição e devolve-se esse início em
/// [CatchupStart.startMs], para o leitor saber onde está. [correctionMin] (Definições → Guia) soma-se só à hora pedida
/// ao servidor: o vídeo que vem é o do programa.
public func catchupCandidates(playlist: Playlist, catchup: Catchup, timezone: String?, correctionMin: Int = 0,
                              nowMs now: Int64 = nowMs(), zone: TimeZone = .current) -> CatchupStart? {
    let wanted = catchup.startMs + catchup.positionMs
    let shift = Int64(correctionMin) * 60_000
    func floorTo(_ v: Int64, _ unit: Int64) -> Int64 { v - ((v % unit) + unit) % unit }
    switch playlist.type {
    case .xtream:
        guard let id = catchup.channel.streamId else { return nil }
        let start = floorTo(wanted, 60_000)
        let minutes = max(1, Int((catchup.endMs - start + 59_999) / 60_000))
        let urls = Xtream.catchupURLs(playlist, streamId: id, startMs: start + shift, minutes: minutes, timezone: timezone)
        return CatchupStart(startMs: start, candidates: urls.map { StreamCandidate(url: $0, format: StreamFormat.ts) })
    case .m3u:
        guard catchup.channel.catchupType != nil else { return nil }
        let start = floorTo(wanted, M3uCatchup.precisionMs(catchup.channel))
        let candidates = M3uCatchup.candidates(catchup.channel, start: floorTo(start + shift, 1000) / 1000,
                                               end: floorTo(catchup.endMs + shift, 1000) / 1000, now: now / 1000, zone: zone)
        return candidates.isEmpty ? nil : CatchupStart(startMs: start, candidates: candidates)
    default:
        return nil
    }
}
