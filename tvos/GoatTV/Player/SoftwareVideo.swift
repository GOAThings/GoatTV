import AetherLibavcodec
import AetherLibavfilter
import AetherLibavutil
import CoreMedia
import CoreVideo
import Foundation
import GoatCore

/// O vídeo MPEG-2 do GoatCore (os canais SD de cabo e satélite: o VideoToolbox não o descodifica), pelo FFmpeg que a
/// app já traz (o do AetherEngine). Cada imagem passa por um filtro que a desentrelaça (bwdif, a dois campos por
/// imagem: 50 imagens por segundo num canal de 25, como a TV mostra) e lhe põe os pixels quadrados (o SD tem 720 de
/// largura para 4:3 ou 16:9), e sai em CMSampleBuffer já descodificado, para o mesmo AVSampleBufferDisplayLayer.
///
/// Não é seguro entre threads: quem usa chama tudo da mesma fila.
final class SoftwareVideo {
    private var codec: UnsafeMutablePointer<AVCodecContext>?
    private var packet: UnsafeMutablePointer<AVPacket>?
    private var frame: UnsafeMutablePointer<AVFrame>?
    private var filtered: UnsafeMutablePointer<AVFrame>?
    private var graph: UnsafeMutablePointer<AVFilterGraph>?
    private var source: UnsafeMutablePointer<AVFilterContext>?
    private var sink: UnsafeMutablePointer<AVFilterContext>?
    /// O que o filtro espera (tamanho, formato dos pixels, proporção): outro, e ele refaz-se.
    private var graphKey: [Int32] = []
    private var format: CMVideoFormatDescription?
    private var formatKey: [Int32] = []

    /// nil se o FFmpeg da app não tiver o descodificador.
    init?() {
        guard let decoder = avcodec_find_decoder(AV_CODEC_ID_MPEG2VIDEO), let ctx = avcodec_alloc_context3(decoder) else { return nil }
        ctx.pointee.pkt_timebase = AVRational(num: 1, den: 90_000)
        ctx.pointee.thread_count = 2
        var c: UnsafeMutablePointer<AVCodecContext>? = ctx
        guard avcodec_open2(ctx, decoder, nil) >= 0 else {
            avcodec_free_context(&c)
            return nil
        }
        codec = ctx
        packet = av_packet_alloc()
        frame = av_frame_alloc()
        filtered = av_frame_alloc()
    }

    deinit {
        avcodec_free_context(&codec)
        av_packet_free(&packet)
        av_frame_free(&frame)
        av_frame_free(&filtered)
        avfilter_graph_free(&graph)
    }

    /// Descodifica uma imagem; devolve as que ficaram prontas (pela ordem em que se mostram; 0, 1 ou 2 por imagem).
    func decode(_ v: CodedVideo) -> [CMSampleBuffer] {
        guard let codec, let packet, let frame else { return [] }
        guard av_new_packet(packet, Int32(v.data.count)) >= 0 else { return [] }
        v.data.withUnsafeBytes { raw in
            if let base = raw.baseAddress { memcpy(packet.pointee.data, base, v.data.count) }
        }
        packet.pointee.pts = v.pts
        packet.pointee.dts = v.dts
        if v.keyframe { packet.pointee.flags |= AV_PKT_FLAG_KEY }
        let sent = avcodec_send_packet(codec, packet)
        av_packet_unref(packet)
        guard sent >= 0 else { return [] }
        var out: [CMSampleBuffer] = []
        while avcodec_receive_frame(codec, frame) >= 0 {
            filter(frame, into: &out)
            av_frame_unref(frame)
        }
        return out
    }

    /// Outra ligação ou um salto: o que estava no descodificador e no filtro já não serve.
    func reset() {
        if let codec { avcodec_flush_buffers(codec) }
        source = nil
        sink = nil
        avfilter_graph_free(&graph)
        graphKey = []
    }

    // MARK: Filtro

    private func filter(_ frame: UnsafeMutablePointer<AVFrame>, into out: inout [CMSampleBuffer]) {
        let f = frame.pointee
        let key = [f.width, f.height, f.format, f.sample_aspect_ratio.num, f.sample_aspect_ratio.den]
        if key != graphKey {
            source = nil
            sink = nil
            avfilter_graph_free(&graph)
            graphKey = key
            guard buildGraph(f) else { return }
        }
        guard let source, let sink, let filtered else { return }
        if frame.pointee.pts == Int64.min { frame.pointee.pts = frame.pointee.best_effort_timestamp }
        guard av_buffersrc_add_frame(source, frame) >= 0 else { return }
        let base = av_buffersink_get_time_base(sink)
        while av_buffersink_get_frame(sink, filtered) >= 0 {
            let pts = av_rescale_q(filtered.pointee.pts, base, AVRational(num: 1, den: 90_000))
            if let b = sampleBuffer(filtered, pts: pts) { out.append(b) }
            av_frame_unref(filtered)
        }
    }

    /// bwdif (só nas imagens entrelaçadas), a escala para pixels quadrados e NV12 (o que o ecrã aceita).
    private func buildGraph(_ f: AVFrame) -> Bool {
        guard let g = avfilter_graph_alloc() else { return false }
        graph = g
        let sar = f.sample_aspect_ratio.num > 0 && f.sample_aspect_ratio.den > 0 ? f.sample_aspect_ratio : AVRational(num: 1, den: 1)
        let args = "video_size=\(f.width)x\(f.height):pix_fmt=\(f.format):time_base=1/90000:pixel_aspect=\(sar.num)/\(sar.den)"
        var src: UnsafeMutablePointer<AVFilterContext>?
        var dst: UnsafeMutablePointer<AVFilterContext>?
        guard avfilter_graph_create_filter(&src, avfilter_get_by_name("buffer"), "in", args, nil, g) >= 0,
              avfilter_graph_create_filter(&dst, avfilter_get_by_name("buffersink"), "out", nil, nil, g) >= 0 else {
            avfilter_graph_free(&graph)
            return false
        }
        var outputs = avfilter_inout_alloc()
        var inputs = avfilter_inout_alloc()
        defer {
            avfilter_inout_free(&outputs)
            avfilter_inout_free(&inputs)
        }
        outputs?.pointee.name = av_strdup("in")
        outputs?.pointee.filter_ctx = src
        outputs?.pointee.pad_idx = 0
        outputs?.pointee.next = nil
        inputs?.pointee.name = av_strdup("out")
        inputs?.pointee.filter_ctx = dst
        inputs?.pointee.pad_idx = 0
        inputs?.pointee.next = nil
        let chain = "bwdif=mode=send_field:parity=auto:deint=interlaced,scale=w='trunc(iw*sar/2)*2':h=ih,setsar=1,format=nv12"
        guard avfilter_graph_parse_ptr(g, chain, &inputs, &outputs, nil) >= 0, avfilter_graph_config(g, nil) >= 0 else {
            DebugLog.add("GoatCore: MPEG-2 filter did not build")
            avfilter_graph_free(&graph)
            return false
        }
        source = src
        sink = dst
        return true
    }

    // MARK: Para o ecrã

    private func sampleBuffer(_ f: UnsafeMutablePointer<AVFrame>, pts: Int64) -> CMSampleBuffer? {
        let w = Int(f.pointee.width), h = Int(f.pointee.height)
        guard w > 0, h > 0, let y = f.pointee.data.0, let uv = f.pointee.data.1 else { return nil }
        var pixel: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()] as CFDictionary
        guard CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs, &pixel) == kCVReturnSuccess,
              let pixel else { return nil }
        CVPixelBufferLockBaseAddress(pixel, [])
        copyPlane(from: y, stride: Int(f.pointee.linesize.0), rows: h, bytes: w, to: pixel, plane: 0)
        copyPlane(from: uv, stride: Int(f.pointee.linesize.1), rows: (h + 1) / 2, bytes: (w + 1) / 2 * 2, to: pixel, plane: 1)
        CVPixelBufferUnlockBaseAddress(pixel, [])
        // A cor do SD (BT.601) ou do HD (BT.709), para o ecrã converter bem.
        let sd = h <= 576
        CVBufferSetAttachment(pixel, kCVImageBufferYCbCrMatrixKey, sd ? kCVImageBufferYCbCrMatrix_ITU_R_601_4 : kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(pixel, kCVImageBufferColorPrimariesKey, sd ? kCVImageBufferColorPrimaries_EBU_3213 : kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(pixel, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
        let key: [Int32] = [Int32(w), Int32(h), sd ? 1 : 0]
        if format == nil || key != formatKey {
            var created: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pixel, formatDescriptionOut: &created)
            format = created
            formatKey = key
        }
        guard let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: pts, timescale: 90_000), decodeTimeStamp: .invalid)
        var buffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pixel, formatDescription: format,
                                                       sampleTiming: &timing, sampleBufferOut: &buffer) == noErr else { return nil }
        return buffer
    }

    private func copyPlane(from src: UnsafeMutablePointer<UInt8>, stride: Int, rows: Int, bytes: Int, to pixel: CVPixelBuffer, plane: Int) {
        guard let dst = CVPixelBufferGetBaseAddressOfPlane(pixel, plane) else { return }
        let dstStride = CVPixelBufferGetBytesPerRowOfPlane(pixel, plane)
        let n = min(bytes, dstStride, abs(stride))
        for row in 0..<rows {
            memcpy(dst + row * dstStride, src + row * stride, n)
        }
    }
}
