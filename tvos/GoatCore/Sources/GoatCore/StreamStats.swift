import Foundation
import GoatShared

/// "Informação do stream": as linhas e as unidades são as do núcleo partilhado (StreamStats, o mesmo do Android); aqui
/// só se embrulham em tipos Swift, para a app não precisar do GoatShared. Quem lê os números (o leitor) fica na app.
public enum StreamStatsRules {
    /// Os números do canal a dar. -1 / "" = não se sabe (ver StreamStatsInput no Kotlin).
    public struct Measure: Sendable {
        public var container = ""
        public var hlsVariant = ""
        public var hlsPinned = false
        /// Pelo nome do núcleo partilhado ("H264", "HEVC", "MPEG2_VIDEO") ou já por extenso.
        public var videoCodec = ""
        public var videoProfile = -1
        public var videoLevel = -1
        public var bitDepth = -1
        public var width = 0
        public var height = 0
        public var fps: Float = 0
        /// -1 = não se sabe; 0 = progressivo; 1 = entrelaçado.
        public var interlaced = -1
        public var deinterlacing = false
        public var upscaling = false
        public var toneMapping = false
        /// 16 = HDR10 (PQ), 18 = HLG, -1 = não se sabe.
        public var hdrTransfer = -1
        public var audioCodec = ""
        public var audioChannels = 0
        public var audioSampleRate = 0
        public var audioTrack = ""
        public var bitrateKbps = -1
        public var bufferMs: Int64 = -1
        public var droppedFrames = -1
        public var renderedFrames = -1
        public var decodeErrors = -1
        public var concealedFrames = -1
        public var reconnects = 0
        public var engine = ""
        public var fallback = false
        public var source = ""
        public var failover = false

        public init() {}
    }

    /// As palavras dos valores, já traduzidas (as chaves stats_* do Android).
    public struct Words: Sendable {
        public var progressive: String
        public var interlaced: String
        public var deinterlacing: String
        public var upscaling: String
        public var toneMapping: String
        public var nothing: String
        public var automatic: String
        public var fallback: String
        public var failover: String

        public init(progressive: String, interlaced: String, deinterlacing: String, upscaling: String, toneMapping: String,
                    nothing: String, automatic: String, fallback: String, failover: String) {
            self.progressive = progressive
            self.interlaced = interlaced
            self.deinterlacing = deinterlacing
            self.upscaling = upscaling
            self.toneMapping = toneMapping
            self.nothing = nothing
            self.automatic = automatic
            self.fallback = fallback
            self.failover = failover
        }
    }

    /// Uma linha: [key] é o nome da string do título no Android (ler com `s.r(key)`), [value] o valor.
    public struct Row: Identifiable, Sendable {
        public let key: String
        public let value: String
        public var id: String { key }
    }

    /// As linhas do painel, sempre as mesmas e pela mesma ordem ("—" no que não se sabe).
    public static func rows(_ m: Measure, words w: Words) -> [Row] {
        let input = StreamStatsInput(
            container: m.container, hlsVariant: m.hlsVariant, hlsPinned: m.hlsPinned, videoCodec: m.videoCodec,
            videoProfile: Int32(m.videoProfile), videoLevel: Int32(m.videoLevel), bitDepth: Int32(m.bitDepth),
            width: Int32(m.width), height: Int32(m.height), fps: m.fps, interlaced: Int32(m.interlaced),
            deinterlacing: m.deinterlacing, upscaling: m.upscaling, toneMapping: m.toneMapping, hdrTransfer: Int32(m.hdrTransfer),
            audioCodec: m.audioCodec, audioChannels: Int32(m.audioChannels), audioSampleRate: Int32(m.audioSampleRate),
            audioTrack: m.audioTrack, bitrateKbps: Int32(m.bitrateKbps), bufferMs: m.bufferMs,
            droppedFrames: Int32(m.droppedFrames), renderedFrames: Int32(m.renderedFrames), decodeErrors: Int32(m.decodeErrors),
            concealedFrames: Int32(m.concealedFrames), reconnects: Int32(m.reconnects), engine: m.engine, fallback: m.fallback,
            source: m.source, failover: m.failover)
        let words = StreamStatsWords(
            progressive: w.progressive, interlaced: w.interlaced, deinterlacing: w.deinterlacing, upscaling: w.upscaling,
            toneMapping: w.toneMapping, nothing: w.nothing, automatic: w.automatic, fallback: w.fallback, failover: w.failover)
        return AppleStreamStats().rows(i: input, w: words).map { Row(key: $0.key, value: $0.value) }
    }

    /// O débito a rolar (ver RollingBitrate no Kotlin): uma amostra por segundo com os bytes desde sempre.
    public final class Bitrate {
        private let rolling = RollingBitrate()

        public init() {}

        /// [atMs]: o relógio; [totalBytes]: os bytes desde sempre; [mediaMs]: o tempo do vídeo recebido, ou -1.
        public func add(atMs: Int64, totalBytes: Int64, mediaMs: Int64 = -1) {
            rolling.add(atMs: atMs, totalBytes: totalBytes, mediaMs: mediaMs)
        }

        public func clear() { rolling.clear() }

        /// Kbit/s, ou -1 enquanto não há medições que cheguem.
        public var kbps: Int { Int(rolling.kbps()) }
    }
}
