import CoreImage
import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

/// "Guardar imagem": a imagem do canal que está no ecrã, sem nada da app por cima. O AVSampleBufferDisplayLayer não a
/// dá (descodifica dentro do sistema), por isso refaz-se com as últimas amostras que foram para ele (ver
/// StreamSession.remember):
/// - já descodificadas (o vídeo desentrelaçado ou com ajustes da imagem, e o MPEG-2): é só a que está no ecrã;
/// - comprimidas (o caminho normal): descodificam-se uma vez pelo VideoToolbox, da imagem-chave até à que está no
///   ecrã (as referências de umas são as outras), e fica a última.
/// O HDR sai como o VideoToolbox o converter para 8 bits. Corre fora da fila do stream (um GOP longo leva um bocado).
enum VideoSnapshot {
    /// A imagem de [frames] (pela ordem em que foram para a camada) que se vê às [now] do relógio do leitor.
    static func image(_ frames: [CMSampleBuffer], at now: CMTime) -> CGImage? {
        guard !frames.isEmpty else { return nil }
        // A que está no ecrã: a de tempo mais alto até agora; sem relógio (ainda a arrancar), a primeira.
        var target = 0
        var best = CMTime.invalid
        if now.isValid {
            for (i, f) in frames.enumerated() {
                let t = CMSampleBufferGetPresentationTimeStamp(f)
                if t.isValid, t <= now, !best.isValid || t >= best {
                    best = t
                    target = i
                }
            }
        }
        let wanted = frames[target]
        guard let format = CMSampleBufferGetFormatDescription(wanted) else { return nil }
        if let image = CMSampleBufferGetImageBuffer(wanted) { return cgImage(image, format: format) }
        var start = target
        while start > 0, !isSync(frames[start]) { start -= 1 }
        guard let decoded = decode(Array(frames[start...target]), want: CMSampleBufferGetPresentationTimeStamp(wanted)) else { return nil }
        return cgImage(decoded, format: format)
    }

    private static func isSync(_ b: CMSampleBuffer) -> Bool {
        guard let a = CMSampleBufferGetSampleAttachmentsArray(b, createIfNecessary: false) as? [[CFString: Any]], let first = a.first else { return true }
        return (first[kCMSampleAttachmentKey_NotSync] as? Bool) != true
    }

    /// O que o descodificador deu (pode ser noutra thread).
    private final class Output {
        let lock = NSLock()
        var exact: CVPixelBuffer?
        var last: CVPixelBuffer?
        var lastTime = CMTime.invalid
    }

    /// Descodifica [frames] (a começar numa imagem-chave) e devolve a imagem com o tempo [want]; sem ela (um erro a
    /// meio), a mais tardia que saiu.
    private static func decode(_ frames: [CMSampleBuffer], want: CMTime) -> CVPixelBuffer? {
        guard let first = frames.first, let format = CMSampleBufferGetFormatDescription(first) else { return nil }
        let attrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ]
        var created: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil,
                                                  imageBufferAttributes: attrs as CFDictionary, outputCallback: nil,
                                                  decompressionSessionOut: &created)
        guard status == noErr, let session = created else { return nil }
        defer { VTDecompressionSessionInvalidate(session) }
        let output = Output()
        for frame in frames {
            // Os parâmetros mudaram a meio e este descodificador não os aceita: fica o que já saiu.
            if let f = CMSampleBufferGetFormatDescription(frame), !CMFormatDescriptionEqual(f, otherFormatDescription: format),
               !VTDecompressionSessionCanAcceptFormatDescription(session, formatDescription: f) { break }
            VTDecompressionSessionDecodeFrame(session, sampleBuffer: frame, flags: [], infoFlagsOut: nil) { result, _, image, time, _ in
                guard result == noErr, let image else { return }
                output.lock.lock()
                if time.isValid, want.isValid, time == want { output.exact = image }
                if !output.lastTime.isValid || !time.isValid || time >= output.lastTime {
                    output.last = image
                    output.lastTime = time
                }
                output.lock.unlock()
            }
        }
        VTDecompressionSessionWaitForAsynchronousFrames(session)
        output.lock.lock()
        defer { output.lock.unlock() }
        return output.exact ?? output.last
    }

    /// A imagem com os píxeis quadrados (um 1440×1080 de 16:9 fica 1920×1080), pela proporção do píxel de [format].
    private static func cgImage(_ buffer: CVPixelBuffer, format: CMFormatDescription) -> CGImage? {
        var image = CIImage(cvPixelBuffer: buffer)
        let width = CGFloat(CVPixelBufferGetWidth(buffer))
        let height = CGFloat(CVPixelBufferGetHeight(buffer))
        let shown = CMVideoFormatDescriptionGetPresentationDimensions(format, usePixelAspectRatio: true, useCleanAperture: false)
        if width > 0, height > 0, shown.width > 0, shown.height > 0,
           abs(shown.width - width) > 1 || abs(shown.height - height) > 1 {
            image = image.transformed(by: CGAffineTransform(scaleX: shown.width / width, y: shown.height / height))
        }
        return CIContext().createCGImage(image, from: image.extent.integral)
    }
}
