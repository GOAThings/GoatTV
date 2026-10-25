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

    /// O programa ainda está no arquivo do servidor? (Xtream com `tv_archive`, dentro dos dias que guarda,
    /// e pelo menos 2 minutos depois de começar.)
    public static func available(playlist: Playlist, channel: Channel, entry: EpgEntry, now: Int64 = nowMs()) -> Bool {
        guard playlist.type == .xtream, channel.archiveDays > 0 else { return false }
        return entry.startMs < now - minAgeMs && entry.startMs > now - Int64(channel.archiveDays) * 86_400_000
    }

    public static let minAgeMs: Int64 = 2 * 60_000
}

/// Onde começa o catch-up (ao minuto, que é como o servidor trabalha) e as formas de o pedir.
public struct CatchupStart: Sendable {
    public var startMs: Int64
    public var candidates: [StreamCandidate]
}

public func catchupCandidates(playlist: Playlist, catchup: Catchup, timezone: String?) -> CatchupStart? {
    guard playlist.type == .xtream, let id = catchup.channel.streamId else { return nil }
    let wanted = catchup.startMs + catchup.positionMs
    let start = wanted - ((wanted % 60_000) + 60_000) % 60_000
    let minutes = max(1, Int((catchup.endMs - start + 59_999) / 60_000))
    let urls = Xtream.catchupURLs(playlist, streamId: id, startMs: start, minutes: minutes, timezone: timezone)
    return CatchupStart(startMs: start, candidates: urls.map { StreamCandidate(url: $0, format: StreamFormat.ts) })
}
