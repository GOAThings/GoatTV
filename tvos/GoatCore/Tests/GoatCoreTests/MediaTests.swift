import CoreMedia
import Foundation
import VideoToolbox
@testable import GoatCore

/// O motor GoatCore com canais a sério (feitos com o ffmpeg, ver Fixtures): as amostras que saem e se o VideoToolbox as
/// descodifica.
extension CoreTests {
    private struct Run {
        var tracks: [MediaTrack] = []
        var video: [CMSampleBuffer] = []
        var audio: [CMSampleBuffer] = []
        var unsupported: [String] = []
    }

    private static func fixture(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: "ts", subdirectory: "Fixtures") else {
            throw Failure(description: "falta a fixture \(name)")
        }
        return try Data(contentsOf: url)
    }

    private static func run(_ name: String, from offset: Int = 0, pipeline: MediaPipeline = MediaPipeline()) throws -> Run {
        let bytes = try fixture(name).dropFirst(offset)
        var r = Run()
        pipeline.onTracks = { r.tracks = $0 }
        pipeline.onVideo = { r.video.append($0) }
        pipeline.onAudio = { r.audio.append($0) }
        pipeline.onUnsupported = { r.unsupported.append($0) }
        // Aos bocados, como chega da rede.
        var at = 0
        var step = 1
        let data = Data(bytes)
        while at < data.count {
            let n = min(step, data.count - at)
            pipeline.feed(data.subdata(in: at..<at + n))
            at += n
            step = step * 7 % 3001 + 1
        }
        pipeline.flush()
        return r
    }

    private static func isSync(_ b: CMSampleBuffer) -> Bool {
        guard let a = CMSampleBufferGetSampleAttachmentsArray(b, createIfNecessary: false) as? [[CFString: Any]], let first = a.first else { return true }
        return (first[kCMSampleAttachmentKey_NotSync] as? Bool) != true
    }

    /// A primeira imagem pelo VideoToolbox: tem de dar uma imagem com o tamanho do canal.
    private static func decodeFirst(_ buffers: [CMSampleBuffer]) throws -> (Int, Int) {
        guard let first = buffers.first, let format = CMSampleBufferGetFormatDescription(first) else { throw Failure(description: "sem vídeo") }
        var session: VTDecompressionSession?
        let created = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil,
                                                   imageBufferAttributes: nil, outputCallback: nil, decompressionSessionOut: &session)
        guard created == noErr, let session else { throw Failure(description: "VTDecompressionSessionCreate \(created)") }
        defer { VTDecompressionSessionInvalidate(session) }
        var size: (Int, Int)?
        var frameStatus: OSStatus = noErr
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: first, flags: [], infoFlagsOut: nil) { s, _, image, _, _ in
            frameStatus = s
            if let image { size = (CVPixelBufferGetWidth(image), CVPixelBufferGetHeight(image)) }
        }
        VTDecompressionSessionWaitForAsynchronousFrames(session)
        guard status == noErr, frameStatus == noErr, let size else { throw Failure(description: "descodificar: \(status) / \(frameStatus)") }
        return size
    }

    /// Todas as imagens pelo VideoToolbox, pela ordem em que chegam (como o leitor faz): nenhuma pode dar erro.
    private static func decodeAll(_ buffers: [CMSampleBuffer]) throws -> Int {
        guard let format = buffers.first.flatMap(CMSampleBufferGetFormatDescription) else { throw Failure(description: "sem vídeo") }
        var session: VTDecompressionSession?
        guard VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil, imageBufferAttributes: nil,
                                           outputCallback: nil, decompressionSessionOut: &session) == noErr, let session else {
            throw Failure(description: "VTDecompressionSessionCreate")
        }
        defer { VTDecompressionSessionInvalidate(session) }
        var images = 0
        var errors: [OSStatus] = []
        for b in buffers {
            let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: b, flags: [], infoFlagsOut: nil) { s, _, image, _, _ in
                if s != noErr { errors.append(s) } else if image != nil { images += 1 }
            }
            if status != noErr { errors.append(status) }
        }
        VTDecompressionSessionWaitForAsynchronousFrames(session)
        if !errors.isEmpty { throw Failure(description: "erros ao descodificar: \(errors.prefix(5))") }
        return images
    }

    /// Entrar num canal a meio (como sempre acontece num direto): a primeira imagem que sai é uma imagem-chave, e
    /// tudo o que sai descodifica sem erros. Num open GOP, as imagens I que não são IDR também servem para entrar.
    static func goatCoreJoinsMidStream() throws {
        let size = try fixture("h264_opengop").count
        for offset in [size / 4, size / 2, size * 2 / 3] {
            let r = try run("h264_opengop", from: offset - offset % 188 + 57)
            guard let first = r.video.first else { throw Failure(description: "sem vídeo a partir de \(offset)") }
            try expect(isSync(first), true, "a partir de \(offset)")
            let decoded = try decodeAll(r.video)
            if decoded == 0 { throw Failure(description: "nenhuma imagem a partir de \(offset)") }
        }
    }

    /// Mudar de canal: o mesmo pipeline, reposto, com outro canal (outro formato e outro relógio).
    static func goatCoreChannelChange() throws {
        let pipeline = MediaPipeline()
        let a = try run("h264_aac", pipeline: pipeline)
        pipeline.reset()
        let b = try run("hevc_ac3", from: 188 * 300, pipeline: pipeline)
        try expect(a.video.isEmpty, false)
        guard let first = b.video.first else { throw Failure(description: "sem vídeo depois de mudar de canal") }
        try expect(isSync(first), true)
        try expect(CMFormatDescriptionGetMediaSubType(CMSampleBufferGetFormatDescription(first)!), kCMVideoCodecType_HEVC)
        _ = try decodeAll(b.video)
        try expect(b.audio.isEmpty, false)
    }

    static func goatCoreH264Aac() throws {
        let r = try run("h264_aac")
        try expect(r.tracks.map(\.codec), ["H264", "AAC_ADTS"])
        try expect(r.tracks.last?.language, "por")
        try expect(r.unsupported, [])
        try expect(r.video.count, 50)
        try expect(isSync(r.video[0]), true)
        try expect(r.video.filter(isSync).count, 2)
        try expect(r.audio.count, 95)
        let size = try decodeFirst(r.video)
        try expect(size.0, 320)
        try expect(size.1, 180)
        // O áudio a 48 kHz: cada bloco AAC são 1024 amostras.
        try expect(CMSampleBufferGetDuration(r.audio[0]).seconds, 1024.0 / 48000.0)
    }

    static func goatCoreHevcAc3() throws {
        let r = try run("hevc_ac3")
        try expect(r.tracks.map(\.codec), ["HEVC", "AC3"])
        try expect(r.unsupported, [])
        try expect(r.video.count, 50)
        try expect(r.audio.count, 63)
        let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(r.audio[0])!)!.pointee
        try expect(asbd.mChannelsPerFrame, 6)
        try expect(asbd.mFormatID, kAudioFormatAC3)
        let size = try decodeFirst(r.video)
        try expect(size.0, 320)
    }

    static func goatCoreEac3() throws {
        let r = try run("h264_eac3")
        try expect(r.audio.count, 63)
        let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(CMSampleBufferGetFormatDescription(r.audio[0])!)!.pointee
        try expect(asbd.mFormatID, kAudioFormatEnhancedAC3)
    }

    /// O MPEG-2 ainda não: diz que não toca (para o leitor passar ao VLC), e o áudio MP2 sai na mesma.
    static func goatCoreMpeg2IsReported() throws {
        let r = try run("mpeg2_mp2")
        try expect(r.unsupported, ["video MPEG2_VIDEO"])
        try expect(r.video.count, 0)
        try expect(r.audio.count, 84)
    }
}

/// Saltar dentro de um TS gravado (TsSeek): o mesmo que o Android faz com as gravações e o catch-up do Stalker.
extension CoreTests {
    /// [seconds] de "vídeo": um pacote por cada 10 ms, cada um um PES de vídeo com o seu PTS (a começar em [firstPts]).
    private static func pesStream(seconds: Int, firstPts: Int64) -> Data {
        var out = Data()
        for i in 0..<(seconds * 100) {
            var p = [UInt8](repeating: 0xFF, count: 188)
            p[0] = 0x47
            p[1] = 0x41
            p[2] = 0x00
            p[3] = 0x10
            let pts = (firstPts + Int64(i) * 900) & 0x1_FFFF_FFFF
            let pes: [UInt8] = [0, 0, 1, 0xE0, 0, 0, 0x80, 0x80, 5,
                                UInt8(0x21 | ((pts >> 29) & 0x0E)), UInt8((pts >> 22) & 0xFF), UInt8(0x01 | ((pts >> 14) & 0xFE)),
                                UInt8((pts >> 7) & 0xFF), UInt8(0x01 | ((pts << 1) & 0xFE))]
            p.replaceSubrange(4..<(4 + pes.count), with: pes)
            out.append(contentsOf: p)
        }
        return out
    }

    static func tsSeekLandsNearThePoint() throws {
        let file = pesStream(seconds: 200, firstPts: 5 * 90_000)
        var reads = 0
        let plan = TsSeek.plan(length: Int64(file.count), startMs: 100_000) { at, n in
            reads += 1
            return file.subdata(in: Int(at)..<min(file.count, Int(at) + n))
        }
        guard let plan else { throw Failure(description: "sem plano") }
        try expect(plan.firstPts, 5 * 90_000)
        // 100 s de 200: o pacote 10 000 (um por 10 ms), a menos de meio segundo.
        try expect(abs(plan.position / 188 - 10_000) < 50, true, "posição \(plan.position / 188)")
        try expect(plan.position % 188, 0)
        try expect(reads, 3)
        // A posição conta desde o início do ficheiro, também quando o relógio dá a volta aos 33 bits.
        try expect(TsSeek.positionMs(pts: 5 * 90_000 + 90_000 * 42, firstPts: 5 * 90_000), 42_000)
        try expect(TsSeek.positionMs(pts: 90_000, firstPts: 0x1_FFFF_FFFF - 89_999), 2_000)
    }

    static func tsSeekFromTheStartNeedsOneRead() throws {
        let file = pesStream(seconds: 10, firstPts: 0)
        var reads = 0
        let plan = TsSeek.plan(length: Int64(file.count), startMs: 0) { at, n in
            reads += 1
            return file.subdata(in: Int(at)..<min(file.count, Int(at) + n))
        }
        try expect(plan, TsSeek.Plan(position: 0, firstPts: 0))
        try expect(reads, 1)
    }
}
