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

    private static func run(_ name: String) throws -> Run {
        guard let url = Bundle.module.url(forResource: name, withExtension: "ts", subdirectory: "Fixtures") else {
            throw Failure(description: "falta a fixture \(name)")
        }
        let bytes = try Data(contentsOf: url)
        let pipeline = MediaPipeline()
        var r = Run()
        pipeline.onTracks = { r.tracks = $0 }
        pipeline.onVideo = { r.video.append($0) }
        pipeline.onAudio = { r.audio.append($0) }
        pipeline.onUnsupported = { r.unsupported.append($0) }
        // Aos bocados, como chega da rede.
        var at = 0
        var step = 1
        while at < bytes.count {
            let n = min(step, bytes.count - at)
            pipeline.feed(bytes.subdata(in: at..<at + n))
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
