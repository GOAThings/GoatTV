import CoreMedia
import CoreVideo
import Foundation
import GoatCore
import Metal
import VideoToolbox

/// O H.264 entrelaçado do GoatCore (os 576i e 1080i de cabo e satélite). O AVSampleBufferDisplayLayer descodifica as
/// imagens comprimidas mas não as desentrelaça (via-se o "pente" no movimento): aqui descodificam-se pelo VideoToolbox
/// (o hardware, como antes) para CVPixelBuffer, passam por um compute shader de Metal e vão já prontas para a mesma
/// camada (o Picture in Picture continua igual).
///
/// [fieldRate] ("Suave"): cada campo passa a ser uma imagem (50 por segundo num canal de 25), com as linhas do outro
/// campo interpoladas na direção da aresta (ELA, presa entre a linha de cima e a de baixo); senão ("Normal") os dois
/// misturam-se numa (½ a linha, ¼ cada vizinha). As imagens que o canal diz progressivas vão como vêm. É o mesmo que o
/// Android faz (ver VideoGpuStage.kt); o VideoToolbox nunca desentrelaça, por isso não há nada a medir.
///
/// Os ajustes da imagem ([picture]: o brilho, o contraste, a saturação e a nitidez de Definições → Imagem) fazem-se
/// aqui também, noutro compute shader sobre a imagem já desentrelaçada: com algum fora do 0, o vídeo progressivo de
/// 8 bits SDR (H.264 ou HEVC, até 1080 linhas, ver PictureAdjust.maxHeight) passa por este caminho só para isso. As
/// contas são as do Android (shared/…/media/PictureLook.kt), aqui sobre o Y e o CbCr.
///
/// Não é seguro entre threads: quem usa chama tudo da mesma fila.
final class InterlacedVideo {
    private let fieldRate: Bool
    private let commands: MTLCommandQueue
    private let kernel: MTLComputePipelineState
    private let adjustKernel: MTLComputePipelineState
    /// Os ajustes da imagem; mudam com o canal a dar (valem na imagem seguinte).
    var picture = PictureAdjust()
    private let textureCache: CVMetalTextureCache
    private var session: VTDecompressionSession?
    private var sessionFormat: CMFormatDescription?
    private var bufferPool: CVPixelBufferPool?
    private var poolKey: [Int] = []
    private var outFormat: CMVideoFormatDescription?

    /// O que o VideoToolbox já deu (pode ser noutra thread), à espera de ir por ordem.
    private let decodedLock = NSLock()
    private var decoded: [(pts: CMTime, image: CVPixelBuffer)] = []
    /// Descodificadas, pela ordem em que se mostram (com imagens B, a de descodificação é outra).
    private var pending: [(pts: CMTime, image: CVPixelBuffer)] = []
    /// Como foi captada cada imagem que entrou (pelo PTS, 90 kHz).
    private var scans: [Int64: (bottomFirst: Bool, progressive: Bool)] = [:]
    /// O PTS (90 kHz) da última que saiu, e o intervalo entre imagens (o segundo campo sai a meio).
    private var lastOut: Int64 = .min
    private var frameTicks: Int64 = 3_600
    /// Quantas imagens podem esperar pela que falta (as B à frente das P).
    private static let reorder = 4

    /// nil se o aparelho não tiver Metal (ou o shader não compilar): o vídeo vai como vinha.
    init?(fieldRate: Bool) {
        guard let device = MTLCreateSystemDefaultDevice(), let commands = device.makeCommandQueue(),
              let library = try? device.makeLibrary(source: Self.shader, options: nil),
              let function = library.makeFunction(name: "deinterlace"),
              let kernel = try? device.makeComputePipelineState(function: function),
              let adjustFunction = library.makeFunction(name: "adjust"),
              let adjustKernel = try? device.makeComputePipelineState(function: adjustFunction) else { return nil }
        var cache: CVMetalTextureCache?
        guard CVMetalTextureCacheCreate(nil, nil, device, nil, &cache) == kCVReturnSuccess, let cache else { return nil }
        self.fieldRate = fieldRate
        self.commands = commands
        self.kernel = kernel
        self.adjustKernel = adjustKernel
        self.textureCache = cache
    }

    deinit {
        if let session { VTDecompressionSessionInvalidate(session) }
    }

    /// Descodifica uma imagem; devolve as que ficaram prontas (0, 1 ou 2 por imagem), pela ordem em que se mostram.
    /// nil: o VideoToolbox não descodifica este formato (quem usa manda o vídeo como vinha).
    func decode(_ sample: CMSampleBuffer, bottomFirst: Bool, progressive: Bool) -> [CMSampleBuffer]? {
        guard let format = CMSampleBufferGetFormatDescription(sample) else { return [] }
        if session == nil || sessionFormat.map({ !CMFormatDescriptionEqual($0, otherFormatDescription: format) }) ?? true {
            if let s = session, VTDecompressionSessionCanAcceptFormatDescription(s, formatDescription: format) {
                sessionFormat = format
            } else if !makeSession(format) {
                return nil
            }
        }
        guard let session else { return nil }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if pts.isValid { scans[Self.ticks(pts)] = (bottomFirst, progressive) }
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [._EnableTemporalProcessing],
                                                       infoFlagsOut: nil) { [weak self] result, _, image, time, _ in
            guard result == noErr, let image, time.isValid, let self else { return }
            self.decodedLock.lock()
            self.decoded.append((time, image))
            self.decodedLock.unlock()
        }
        // A sessão morreu (a app foi para segundo plano): faz-se outra na imagem seguinte.
        if status == kVTInvalidSessionErr {
            VTDecompressionSessionInvalidate(session)
            self.session = nil
        }
        return drain()
    }

    /// O vídeo deixa de passar por aqui (os ajustes da imagem voltaram ao 0): as imagens que estavam à espera saem
    /// todas, por ordem, e o descodificador fecha (se voltar, começa de novo numa imagem-chave).
    func finish() -> [CMSampleBuffer] {
        if let session { VTDecompressionSessionWaitForAsynchronousFrames(session) }
        let out = drain(all: true)
        reset()
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        sessionFormat = nil
        return out
    }

    /// Outra ligação ou um salto: o que estava à espera já não serve.
    func reset() {
        if let session { VTDecompressionSessionWaitForAsynchronousFrames(session) }
        decodedLock.lock()
        decoded = []
        decodedLock.unlock()
        pending = []
        scans = [:]
        lastOut = .min
    }

    private func makeSession(_ format: CMFormatDescription) -> Bool {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        sessionFormat = nil
        let attrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferMetalCompatibilityKey as String: true,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ]
        var created: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil,
                                                  imageBufferAttributes: attrs as CFDictionary, outputCallback: nil,
                                                  decompressionSessionOut: &created)
        guard status == noErr, let created else {
            DebugLog.add("GoatCore: deinterlace decoder failed (\(status))")
            return false
        }
        session = created
        sessionFormat = format
        return true
    }

    // MARK: Ordem

    /// Uma imagem sai quando é a seguinte da última (um intervalo depois), ou quando já há [reorder] à espera.
    /// [all]: saem todas as que há (ver [finish]).
    private func drain(all: Bool = false) -> [CMSampleBuffer] {
        decodedLock.lock()
        let fresh = decoded
        decoded = []
        decodedLock.unlock()
        for f in fresh {
            let at = pending.firstIndex { $0.pts > f.pts } ?? pending.count
            pending.insert(f, at: at)
        }
        var out: [CMSampleBuffer] = []
        while let first = pending.first {
            let t = Self.ticks(first.pts)
            let next = lastOut == .min || abs(t - (lastOut + frameTicks)) <= frameTicks / 4
            guard all || next || pending.count > Self.reorder else { break }
            pending.removeFirst()
            let scan = scans.removeValue(forKey: t)
            // Chegou depois de uma mais à frente: já não tem lugar.
            if lastOut != .min, t <= lastOut { continue }
            if lastOut != .min {
                let d = t - lastOut
                if d >= 900, d <= frameTicks * 3 / 2 { frameTicks = d }
            }
            lastOut = t
            out += process(first.image, pts: first.pts, scan: scan)
        }
        if scans.count > 64, lastOut != .min { scans = scans.filter { $0.key > lastOut } }
        return out
    }

    private static func ticks(_ t: CMTime) -> Int64 { t.convertScale(90_000, method: .roundHalfAwayFromZero).value }

    // MARK: Desentrelaçar

    private func process(_ image: CVPixelBuffer, pts: CMTime, scan: (bottomFirst: Bool, progressive: Bool)?) -> [CMSampleBuffer] {
        let whole = CMTime(value: frameTicks, timescale: 90_000)
        let half = CMTime(value: frameTicks / 2, timescale: 90_000)
        let format = CVPixelBufferGetPixelFormatType(image)
        let nv12 = format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange || format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        if scan?.progressive == true || !nv12 {
            let shown = nv12 ? adjusted([image])[0] : image
            return [sampleBuffer(shown, pts: pts, duration: whole)].compactMap { $0 }
        }
        if fieldRate {
            let first: UInt32 = scan?.bottomFirst == true ? 1 : 0
            let fields = adjusted(render(image, passes: [(first, 0), (1 - first, 0)]))
            guard fields.count == 2 else { return [] }
            return [sampleBuffer(fields[0], pts: pts, duration: half), sampleBuffer(fields[1], pts: pts + half, duration: half)].compactMap { $0 }
        }
        guard let blended = adjusted(render(image, passes: [(0, 1)])).first else { return [] }
        return [sampleBuffer(blended, pts: pts, duration: whole)].compactMap { $0 }
    }

    // MARK: Ajustes da imagem

    /// As [images] com os ajustes de [picture] (cada uma numa imagem nova); com tudo no 0, ou se a GPU falhar, como vêm.
    /// O Y leva a nitidez, o contraste e o brilho; o CbCr o contraste e a saturação, à volta do meio (128).
    private func adjusted(_ images: [CVPixelBuffer]) -> [CVPixelBuffer] {
        let look = picture
        guard !look.neutral, let first = images.first, let pool = outputPool(for: first),
              let command = commands.makeCommandBuffer() else { return images }
        let full = CVPixelBufferGetPixelFormatType(first) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        var keep: [CVMetalTexture] = []
        var outputs: [CVPixelBuffer] = []
        for image in images {
            var created: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &created) == kCVReturnSuccess, let out = created,
                  let srcY = texture(image, plane: 0, format: .r8Unorm, keep: &keep),
                  let srcC = texture(image, plane: 1, format: .rg8Unorm, keep: &keep),
                  let dstY = texture(out, plane: 0, format: .r8Unorm, keep: &keep),
                  let dstC = texture(out, plane: 1, format: .rg8Unorm, keep: &keep),
                  let encoder = command.makeComputeCommandEncoder() else { return images }
            encoder.setComputePipelineState(adjustKernel)
            // O ganho, a soma, o meio à volta do qual se multiplica, e a nitidez.
            let luma: [Float] = [look.lumaGain, full ? look.lumaBiasFull : look.lumaBiasVideo, 0, look.sharpen]
            let chroma: [Float] = [look.chromaGain, 0, 128.0 / 255.0, 0]
            for (src, dst, values) in [(srcY, dstY, luma), (srcC, dstC, chroma)] {
                var params = values
                encoder.setTexture(src, index: 0)
                encoder.setTexture(dst, index: 1)
                encoder.setBytes(&params, length: MemoryLayout<Float>.stride * 4, index: 0)
                let group = MTLSize(width: 16, height: 16, depth: 1)
                let groups = MTLSize(width: (dst.width + 15) / 16, height: (dst.height + 15) / 16, depth: 1)
                encoder.dispatchThreadgroups(groups, threadsPerThreadgroup: group)
            }
            encoder.endEncoding()
            CVBufferPropagateAttachments(image, out)
            outputs.append(out)
        }
        command.commit()
        withExtendedLifetime(keep) { command.waitUntilCompleted() }
        return command.status == .completed ? outputs : images
    }

    /// Cada passagem (o campo que fica, 0 = linhas pares; o modo, 0 = campo com ELA, 1 = mistura) numa imagem nova, as
    /// duas metades (Y e CbCr) pelo mesmo shader. Espera pela GPU (~1 ms num 1080).
    private func render(_ image: CVPixelBuffer, passes: [(field: UInt32, mode: UInt32)]) -> [CVPixelBuffer] {
        guard let pool = outputPool(for: image), let command = commands.makeCommandBuffer() else { return [] }
        var keep: [CVMetalTexture] = []
        guard let srcY = texture(image, plane: 0, format: .r8Unorm, keep: &keep),
              let srcC = texture(image, plane: 1, format: .rg8Unorm, keep: &keep) else { return [] }
        var outputs: [CVPixelBuffer] = []
        for pass in passes {
            var created: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &created) == kCVReturnSuccess, let out = created,
                  let dstY = texture(out, plane: 0, format: .r8Unorm, keep: &keep),
                  let dstC = texture(out, plane: 1, format: .rg8Unorm, keep: &keep),
                  let encoder = command.makeComputeCommandEncoder() else { return [] }
            encoder.setComputePipelineState(kernel)
            var params: [UInt32] = [pass.field, pass.mode]
            for (src, dst) in [(srcY, dstY), (srcC, dstC)] {
                encoder.setTexture(src, index: 0)
                encoder.setTexture(dst, index: 1)
                encoder.setBytes(&params, length: MemoryLayout<UInt32>.stride * 2, index: 0)
                // Grupos inteiros (o Apple TV HD não tem grupos de tamanho livre): o shader ignora o que passa da borda.
                let group = MTLSize(width: 16, height: 16, depth: 1)
                let groups = MTLSize(width: (dst.width + 15) / 16, height: (dst.height + 15) / 16, depth: 1)
                encoder.dispatchThreadgroups(groups, threadsPerThreadgroup: group)
            }
            encoder.endEncoding()
            // A cor (BT.601/709), a proporção do píxel e o resto; os campos já não.
            CVBufferPropagateAttachments(image, out)
            CVBufferRemoveAttachment(out, kCVImageBufferFieldCountKey)
            CVBufferRemoveAttachment(out, kCVImageBufferFieldDetailKey)
            outputs.append(out)
        }
        command.commit()
        withExtendedLifetime(keep) { command.waitUntilCompleted() }
        return command.status == .completed ? outputs : []
    }

    private func texture(_ buffer: CVPixelBuffer, plane: Int, format: MTLPixelFormat, keep: inout [CVMetalTexture]) -> MTLTexture? {
        let attrs = [kCVMetalTextureUsage as String: NSNumber(value: MTLTextureUsage([.shaderRead, .shaderWrite]).rawValue)] as CFDictionary
        var created: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(nil, textureCache, buffer, attrs, format,
                                                        CVPixelBufferGetWidthOfPlane(buffer, plane), CVPixelBufferGetHeightOfPlane(buffer, plane),
                                                        plane, &created) == kCVReturnSuccess,
              let created, let texture = CVMetalTextureGetTexture(created) else { return nil }
        keep.append(created)
        return texture
    }

    private func outputPool(for image: CVPixelBuffer) -> CVPixelBufferPool? {
        let format = CVPixelBufferGetPixelFormatType(image)
        let key = [CVPixelBufferGetWidth(image), CVPixelBufferGetHeight(image), Int(format)]
        if key == poolKey, let bufferPool { return bufferPool }
        let attrs: [String: Any] = [
            kCVPixelBufferWidthKey as String: key[0],
            kCVPixelBufferHeightKey as String: key[1],
            kCVPixelBufferPixelFormatTypeKey as String: format,
            kCVPixelBufferMetalCompatibilityKey as String: true,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ]
        var created: CVPixelBufferPool?
        guard CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &created) == kCVReturnSuccess else { return nil }
        bufferPool = created
        poolKey = key
        return created
    }

    // MARK: Para o ecrã

    private func sampleBuffer(_ image: CVPixelBuffer, pts: CMTime, duration: CMTime) -> CMSampleBuffer? {
        if outFormat.map({ !CMVideoFormatDescriptionMatchesImageBuffer($0, imageBuffer: image) }) ?? true {
            var created: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: image, formatDescriptionOut: &created)
            outFormat = created
        }
        guard let outFormat else { return nil }
        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var buffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: image, formatDescription: outFormat,
                                                       sampleTiming: &timing, sampleBufferOut: &buffer) == noErr else { return nil }
        return buffer
    }

    /// p.x: o campo que fica (0 = linhas pares, as de cima); p.y: 0 = esse campo com as outras linhas interpoladas
    /// (ELA: das três direções, a em que a linha de cima e a de baixo mais se parecem), 1 = a linha com as vizinhas.
    /// O mesmo para o Y (r8) e para o CbCr (rg8): no 4:2:0 entrelaçado, as linhas de cor também alternam de campo.
    private static let shader = """
    #include <metal_stdlib>
    using namespace metal;

    kernel void deinterlace(texture2d<float, access::read> src [[texture(0)]],
                            texture2d<float, access::write> dst [[texture(1)]],
                            constant uint2 &p [[buffer(0)]],
                            uint2 gid [[thread_position_in_grid]]) {
        uint w = dst.get_width();
        uint h = dst.get_height();
        if (gid.x >= w || gid.y >= h) return;
        int x = int(gid.x);
        int y = int(gid.y);
        int xm = int(w) - 1;
        int ym = int(h) - 1;
        float4 c = src.read(gid);
        if (p.y == 1u) {
            float4 a = src.read(uint2(gid.x, uint(max(y - 1, 0))));
            float4 b = src.read(uint2(gid.x, uint(min(y + 1, ym))));
            dst.write(0.5 * c + 0.25 * (a + b), gid);
            return;
        }
        if ((gid.y & 1u) == p.x) {
            dst.write(c, gid);
            return;
        }
        uint ya = uint(y > 0 ? y - 1 : min(y + 1, ym));
        uint yb = uint(y < ym ? y + 1 : max(y - 1, 0));
        uint xl = uint(max(x - 1, 0));
        uint xr = uint(min(x + 1, xm));
        float4 a0 = src.read(uint2(xl, ya));
        float4 a1 = src.read(uint2(gid.x, ya));
        float4 a2 = src.read(uint2(xr, ya));
        float4 b0 = src.read(uint2(xl, yb));
        float4 b1 = src.read(uint2(gid.x, yb));
        float4 b2 = src.read(uint2(xr, yb));
        float3 one = float3(1.0);
        float dl = dot(abs(a0.rgb - b2.rgb), one);
        float dc = dot(abs(a1.rgb - b1.rgb), one);
        float dr = dot(abs(a2.rgb - b0.rgb), one);
        float4 o = 0.5 * (a1 + b1);
        if (dl < dc && dl <= dr) {
            o = 0.5 * (a0 + b2);
        } else if (dr < dc && dr < dl) {
            o = 0.5 * (a2 + b0);
        }
        dst.write(clamp(o, min(a1, b1), max(a1, b1)), gid);
    }

    // Os ajustes da imagem, num plano (o Y ou o CbCr). q.x: o ganho; q.y: o que se soma; q.z: o meio à volta do qual
    // se multiplica (0 no Y, 128/255 no CbCr); q.w: a nitidez (a imagem mais q.w vezes a diferença para a média dos
    // quatro vizinhos; 0 = sem ela). O que sai fica preso ao 0–1 pelo formato do destino.
    kernel void adjust(texture2d<float, access::read> src [[texture(0)]],
                       texture2d<float, access::write> dst [[texture(1)]],
                       constant float4 &q [[buffer(0)]],
                       uint2 gid [[thread_position_in_grid]]) {
        uint w = dst.get_width();
        uint h = dst.get_height();
        if (gid.x >= w || gid.y >= h) return;
        float4 c = src.read(gid);
        if (q.w > 0.0) {
            int x = int(gid.x);
            int y = int(gid.y);
            float4 a = src.read(uint2(uint(max(x - 1, 0)), gid.y)) + src.read(uint2(uint(min(x + 1, int(w) - 1)), gid.y))
                     + src.read(uint2(gid.x, uint(max(y - 1, 0)))) + src.read(uint2(gid.x, uint(min(y + 1, int(h) - 1))));
            c = clamp(c + q.w * (c - 0.25 * a), float4(0.0), float4(1.0));
        }
        dst.write((c - q.z) * q.x + q.z + q.y, gid);
    }
    """
}
