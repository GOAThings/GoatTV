import Foundation
import GoatShared

/// "Testar este canal" e "Testar esta lista": as regras do veredicto e os números por extenso são as do núcleo
/// partilhado (StreamProbe, o mesmo do Android); aqui só se embrulham em tipos Swift, para a app não precisar do
/// GoatShared. Quem mede (o leitor, o pedido da lista) fica na app.
public enum StreamProbeRules {
    /// O que se mediu a ver o canal (sem abrir outra ligação). -1 / "" = não se sabe.
    public struct Measure: Sendable {
        public var host = ""
        public var status = -1
        public var redirects = -1
        public var firstByteMs: Int64 = -1
        public var firstPictureMs: Int64 = -1
        public var container = ""
        public var videoCodec = ""
        public var width = 0
        public var height = 0
        public var fps: Float = 0
        public var interlaced = false
        public var hdr = false
        public var audio = ""
        public var measuredKbps = -1
        public var windowMs: Int64 = 0
        public var dropped = -1
        public var rendered = -1
        public var reconnects = 0
        public var cannotDecode = ""
        public var showing = false

        public init() {}
    }

    public enum Verdict: String, Sendable {
        case ok = "OK"
        case slow = "SLOW"
        case refused = "REFUSED"
        case drops = "DROPS"
        case cannotDecode = "CANNOT_DECODE"
        case noPicture = "NO_PICTURE"
    }

    /// O painel já formatado (os textos traduzidos vêm de quem chama, pelas chaves probe_* do Android).
    public struct Report: Sendable {
        public let verdict: Verdict
        /// O número da frase: o código HTTP, os segundos ou o codec.
        public let arg: String
        public let status: Int
        public let redirected: Bool
        public let host: String
        public let firstByte: String?
        public let firstPicture: String?
        public let format: String
        public let audio: String
        public let bitrate: String
        public let dropped: Int
        public let reconnects: Int
    }

    public static func report(_ m: Measure, interlacedWord: String, hdrWord: String) -> Report {
        let input = ChannelProbeInput(
            host: m.host, httpStatus: Int32(m.status), redirects: Int32(m.redirects), firstByteMs: m.firstByteMs,
            firstPictureMs: m.firstPictureMs, container: m.container, videoCodec: m.videoCodec, width: Int32(m.width),
            height: Int32(m.height), fps: m.fps, interlaced: m.interlaced, hdr: m.hdr, audio: m.audio,
            measuredKbps: Int32(m.measuredKbps), windowMs: m.windowMs, droppedFrames: Int32(m.dropped),
            renderedFrames: Int32(m.rendered), reconnects: Int32(m.reconnects), cannotDecode: m.cannotDecode, showing: m.showing)
        let p = AppleStreamProbe()
        return Report(
            verdict: Verdict(rawValue: p.verdict(i: input)) ?? .ok,
            arg: p.verdictArg(i: input),
            status: m.status,
            redirected: m.redirects > 0,
            host: m.host,
            firstByte: m.firstByteMs >= 0 ? p.seconds(ms: m.firstByteMs) : nil,
            firstPicture: m.firstPictureMs >= 0 ? p.seconds(ms: m.firstPictureMs) : nil,
            format: p.format(i: input, interlacedWord: interlacedWord, hdrWord: hdrWord),
            audio: m.audio,
            bitrate: p.bitrate(kbps: Int32(m.measuredKbps)),
            dropped: m.dropped,
            reconnects: m.reconnects)
    }

    /// "OK", "SLOW", "REFUSED", "UNREACHABLE" ou "EMPTY".
    public static func listVerdict(status: Int, ms: Int64, count: Int) -> String {
        AppleStreamProbe().listVerdict(status: Int32(status), ms: ms, count: Int32(count))
    }

    public static func seconds(_ ms: Int64) -> String { AppleStreamProbe().seconds(ms: ms) }
    public static func size(_ bytes: Int64) -> String { AppleStreamProbe().size(bytes: bytes) }
    /// Só o nome do servidor de um endereço (sem utilizador, porta, caminho nem parâmetros).
    public static func host(_ url: String) -> String { AppleStreamProbe().host(url: url) }
}

/// Conta quantas vezes aparece um marcador num texto que chega aos pedaços ("#EXTINF", "\"stream_id\"", "<programme").
public final class TokenTally {
    private let counter: TokenCounter

    public init(_ token: String) { counter = TokenCounter(token: token) }

    public func feed(_ data: Data) {
        // Latin-1: um byte, um carácter; os marcadores são ASCII, e um carácter cortado a meio não os parte.
        counter.feed(chunk: String(data: data, encoding: .isoLatin1) ?? "")
    }

    public var count: Int { Int(counter.count) }
}
