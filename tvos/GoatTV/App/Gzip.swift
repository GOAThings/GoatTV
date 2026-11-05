import Compression
import Foundation
import GoatCore

/// Descomprime gzip (guias `.xml.gz`) com o Compression da Apple, que só sabe DEFLATE puro: salta-se o
/// cabeçalho do gzip à mão. Um resultado maior do que [maxOutput] é um guia falhado (nil), sem o guardar todo.
enum Gzip {
    static func decompress(_ data: Data, maxOutput: Int = SafeInput.maxGuideBytes) -> Data? {
        let bytes = [UInt8](data)
        guard bytes.count > 18, bytes[0] == 0x1F, bytes[1] == 0x8B, bytes[2] == 8 else { return nil }
        let flags = bytes[3]
        var pos = 10
        if flags & 0x04 != 0 { // FEXTRA
            guard pos + 2 <= bytes.count else { return nil }
            pos += 2 + (Int(bytes[pos]) | Int(bytes[pos + 1]) << 8)
        }
        if flags & 0x08 != 0 { // FNAME
            while pos < bytes.count, bytes[pos] != 0 { pos += 1 }
            pos += 1
        }
        if flags & 0x10 != 0 { // FCOMMENT
            while pos < bytes.count, bytes[pos] != 0 { pos += 1 }
            pos += 1
        }
        if flags & 0x02 != 0 { pos += 2 } // FHCRC
        guard pos < bytes.count else { return nil }
        return inflate(bytes[pos...], maxOutput: maxOutput)
    }

    private static func inflate(_ input: ArraySlice<UInt8>, maxOutput: Int) -> Data? {
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
        defer { compression_stream_destroy(stream) }

        let chunk = 1 << 18
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        var output = Data()
        output.reserveCapacity(min(input.count * 6, maxOutput))

        return input.withUnsafeBufferPointer { src -> Data? in
            guard let base = src.baseAddress else { return nil }
            stream.pointee.src_ptr = base
            stream.pointee.src_size = src.count
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let inputBefore = stream.pointee.src_size
                let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let produced = chunk - stream.pointee.dst_size
                if output.count + produced > maxOutput { return nil }
                output.append(buffer, count: produced)
                switch status {
                case COMPRESSION_STATUS_OK:
                    // Sem avançar nada (entrada cortada) não vale a pena continuar: é como um erro.
                    if produced == 0, stream.pointee.src_size == inputBefore { return output.isEmpty ? nil : output }
                    continue
                case COMPRESSION_STATUS_END:
                    return output
                default:
                    return output.isEmpty ? nil : output
                }
            }
        }
    }
}
