import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif
@testable import GoatCore

/// A pausa do direto: os mesmos casos do GoatTV Android (TimeshiftRingTest) e o servidor local que dá o anel ao VLC.
extension CoreTests {
    /// O relógio dos testes (só anda quando se manda).
    private final class TestClock {
        var now: Int64 = 1_000
    }

    private static func ringDir() -> String {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ring-test-\(UUID().uuidString)").path
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func makeRing(_ clock: TestClock, capacity: Int64 = 1 << 20, maxAgeMs: Int64 = 30 * 60_000, name: String = "ring.ts") throws -> (TimeshiftRing, String) {
        let path = ringDir() + "/" + name
        return (try TimeshiftRing(path: path, capacity: capacity, maxAgeMs: maxAgeMs) { clock.now }, path)
    }

    /// Um pacote TS de 188 bytes; com [pcrMs], leva o PCR no campo de adaptação. [fill]: o resto do pacote.
    private static func tsPacket(pid: Int = 0x100, pcrMs: Int64? = nil, fill: UInt8 = 0x11) -> [UInt8] {
        var p = [UInt8](repeating: fill, count: 188)
        p[0] = 0x47
        p[1] = UInt8((pid >> 8) & 0x1F)
        p[2] = UInt8(pid & 0xFF)
        if let pcrMs {
            p[3] = 0x30
            p[4] = 7
            p[5] = 0x10
            let base = pcrMs * 90
            p[6] = UInt8(truncatingIfNeeded: base >> 25)
            p[7] = UInt8(truncatingIfNeeded: base >> 17)
            p[8] = UInt8(truncatingIfNeeded: base >> 9)
            p[9] = UInt8(truncatingIfNeeded: base >> 1)
            p[10] = UInt8(truncatingIfNeeded: ((base & 1) << 7) | 0x7E)
            p[11] = 0
        } else {
            p[3] = 0x10
        }
        return p
    }

    /// [seconds] de "vídeo": um PCR a cada 100 ms e [perPcr] pacotes sem PCR entre eles.
    private static func tsStream(_ fromMs: Int64, _ seconds: Int, perPcr: Int = 4) -> [UInt8] {
        var out: [UInt8] = []
        for i in 0..<(seconds * 10) {
            out += tsPacket(pcrMs: fromMs + Int64(i) * 100)
            for _ in 0..<perPcr { out += tsPacket() }
        }
        return out
    }

    private static func writeAll(_ ring: TimeshiftRing, _ data: [UInt8], chunk: Int = 7_000) throws {
        var o = 0
        while o < data.count {
            let n = min(chunk, data.count - o)
            try ring.write(Array(data[o..<(o + n)]))
            o += n
        }
    }

    private static func read(_ ring: TimeshiftRing, _ pos: Int64, _ segment: Int, max: Int, waitMs: Int = 1) throws -> TimeshiftRing.Read {
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: max, alignment: 1)
        defer { buffer.deallocate() }
        return try ring.read(at: pos, segment: segment, into: buffer, waitMs: waitMs)
    }

    /// Lê até [max] bytes a partir de [from]; devolve o que leu e como acabou (`.bytes` se chegou a [max]).
    private static func readAll(_ ring: TimeshiftRing, from: Int64, segment: Int, max: Int) throws -> ([UInt8], TimeshiftRing.Read) {
        var out: [UInt8] = []
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: 4_096, alignment: 1)
        defer { buffer.deallocate() }
        var pos = from
        while out.count < max {
            let slice = UnsafeMutableRawBufferPointer(rebasing: buffer[0..<min(buffer.count, max - out.count)])
            let r = try ring.read(at: pos, segment: segment, into: slice, waitMs: 10)
            guard case .bytes(let n) = r, n > 0 else { return (out, r) }
            out += slice[0..<n]
            pos += Int64(n)
        }
        return (out, .bytes(out.count))
    }

    // MARK: TsScanner

    static func tsScannerFindsPcrsAcrossChunksAndResyncs() throws {
        var pcrs: [(Int64, Int64)] = []
        let scanner = TsScanner { offset, pcr in pcrs.append((offset, pcr)) }
        scanner.reset(at: 0)
        // Lixo no início (desalinhado), depois pacotes, partidos em bocados que cortam os pacotes ao meio.
        let data = [UInt8](repeating: 0x22, count: 77) + tsStream(10_000, 1)
        var o = 0
        while o < data.count {
            let n = min(101, data.count - o)
            scanner.feed(Array(data[o..<(o + n)]))
            o += n
        }
        try expect(scanner.isTs, true)
        try expect(pcrs.count, 10)
        try expect(pcrs.first?.0, 77)
        try expect(pcrs.first?.1, 10_000)
        try expect(pcrs.last?.1, 10_900)
        // Cada PCR está no início de um pacote: 5 pacotes entre um e o seguinte.
        try expect(pcrs.last?.0, 77 + 9 * 5 * 188)
    }

    static func tsScannerRejectsNonTs() throws {
        var pcrs = 0
        let scanner = TsScanner { _, _ in pcrs += 1 }
        scanner.reset(at: 0)
        // Um MP4 começa por "ftyp" e não tem 0x47 de 188 em 188 bytes.
        var mp4 = (0..<8_192).map { UInt8(truncatingIfNeeded: $0 * 31 % 251) }
        mp4.replaceSubrange(0..<12, with: Array("....ftypisom".utf8))
        scanner.feed(mp4)
        try expect(scanner.isTs, false)
        try expect(pcrs, 0)
    }

    static func tsScannerUndecidedWithFewBytes() throws {
        let scanner = TsScanner()
        scanner.reset(at: 0)
        scanner.feed(tsPacket())
        try expect(scanner.isTs, nil)
    }

    // MARK: TimeshiftRing

    static func ringReadsBackWhatWasWritten() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.beginSegment()
        let data = tsStream(0, 2)
        try writeAll(r, data)
        let (back, _) = try readAll(r, from: 0, segment: 0, max: data.count)
        try expect(back, data)
        try expect(r.writtenBytes, Int64(data.count))
    }

    static func ringWaitsAtTheLiveEdgeAndWakesUpWithNewData() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.beginSegment()
        let first = tsStream(0, 1)
        try r.write(first)
        // No fim do que há: espera e devolve .wait.
        try expect(try read(r, Int64(first.count), 0, max: 1_000, waitMs: 5), .wait)
        // Outra thread escreve: o leitor acorda logo com os dados.
        let more = tsStream(1_000, 1)
        let writer = Thread {
            usleep(50_000)
            try? r.write(more)
        }
        writer.start()
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: 1_000, alignment: 1)
        defer { buffer.deallocate() }
        let result = try r.read(at: Int64(first.count), segment: 0, into: buffer, waitMs: 5_000)
        guard case .bytes(let n) = result, n > 0 else { throw Failure(description: "esperava dados, veio \(result)") }
        try expect(Array(buffer[0..<n]), Array(more[0..<n]))
    }

    static func ringWrapsAroundAndReportsOverrun() throws {
        let clock = TestClock()
        let capacity: Int64 = 64 * 1024
        let (r, _) = try makeRing(clock, capacity: capacity)
        defer { r.close() }
        r.beginSegment()
        let data = tsStream(0, 20) // ~94 KB: dá a volta ao anel
        try writeAll(r, data, chunk: 4_000)
        try expect(Int64(data.count) > capacity, true)
        // O início já foi reescrito.
        try expect(try read(r, 0, 0, max: 188), .overrun)
        // O fim ainda está lá (e lê-se bem através da volta do ficheiro).
        let from = Int64(data.count) - capacity + 1_000
        let (back, _) = try readAll(r, from: from, segment: 0, max: Int(capacity - 2_000))
        try expect(back, Array(data[Int(from)..<Int(from + capacity - 2_000)]))
        // O índice só aponta para o que ainda se pode ler.
        guard let oldest = r.oldest else { throw Failure(description: "sem índice") }
        try expect(oldest.offset >= Int64(data.count) - capacity, true)
    }

    static func ringIndexFollowsThePcrClock() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.beginSegment()
        // O servidor manda 10 s de vídeo de rajada (o relógio real quase não anda): conta o PCR.
        try writeAll(r, tsStream(50_000, 10))
        try expect(r.liveMediaMs, 9_900)
        // Um ponto a cada meio segundo.
        guard let e = r.entry(atOrBefore: 4_200) else { throw Failure(description: "sem ponto") }
        try expect(e.mediaMs, 4_000)
        try expect(e.offset, 40 * 5 * 188)
        try expect(r.entry(atOrBefore: -5)?.offset, 0)
        try expect(r.media(at: e.offset + 10), 4_000)
    }

    static func ringPcrJumpUsesWallClock() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.beginSegment()
        try writeAll(r, tsStream(0, 2))
        let before = r.liveMediaMs
        // O relógio do emissor salta (outra fonte no servidor); passaram 3 s reais.
        clock.now += 3_000
        try writeAll(r, tsStream(-3_600_000 + 7_200_000, 1))
        try expect(r.liveMediaMs, before + 3_000 + 900)
    }

    static func ringSegmentsDoNotMixAndReportBoundary() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.beginSegment()
        let a = tsStream(0, 2)
        try writeAll(r, a)
        // A ligação caiu; voltou 5 s depois (o PCR do servidor continua daí).
        r.endSegment(final: false)
        try expect(try read(r, Int64(a.count), 0, max: 4_096), .wait)
        clock.now += 5_000
        r.beginSegment()
        let b = tsStream(7_000, 2)
        try writeAll(r, b)
        // Quem lia o primeiro segmento chega ao fim dele: .boundary, e reabre no seguinte.
        let (backA, end) = try readAll(r, from: 0, segment: 0, max: Int.max)
        try expect(backA, a)
        try expect(end, .boundary)
        try expect(r.segment(at: Int64(a.count)), 1)
        guard let next = r.entry(from: Int64(a.count)) else { throw Failure(description: "sem ponto no segmento novo") }
        try expect(next.offset, Int64(a.count))
        // O tempo do segundo segmento continua o do primeiro mais a espera.
        try expect(next.mediaMs, 1_900 + 5_000)
        let (backB, _) = try readAll(r, from: next.offset, segment: r.segment(at: next.offset), max: b.count)
        try expect(backB, b)
    }

    private struct Dropped: Error, Equatable {
        let code: Int
    }

    static func ringFinishedStreamEndsOrThrowsAfterTheData() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.beginSegment()
        let a = tsStream(0, 1)
        try writeAll(r, a)
        r.endSegment(final: true, error: Dropped(code: 1))
        let (back, _) = try readAll(r, from: 0, segment: 0, max: a.count)
        try expect(back, a)
        do {
            _ = try read(r, Int64(a.count), 0, max: 10)
            throw Failure(description: "devia lançar o erro da ligação")
        } catch let e as Dropped {
            try expect(e, Dropped(code: 1))
        }
        // Sem erro: só o fim.
        let (r2, _) = try makeRing(clock, capacity: 1 << 20, maxAgeMs: 60_000, name: "ring2.ts")
        defer { r2.close() }
        r2.beginSegment()
        try writeAll(r2, a)
        r2.endSegment(final: true)
        try expect(r2.isFinished, true)
        try expect(try read(r2, Int64(a.count), 0, max: 10), .end)
    }

    static func ringFailureBeforeAnyDataIsThrownRightAway() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        r.endSegment(final: true, error: Dropped(code: 403))
        do {
            _ = try read(r, 0, r.segment(at: 0), max: 10)
            throw Failure(description: "devia lançar")
        } catch let e as Dropped {
            try expect(e, Dropped(code: 403))
        }
    }

    static func ringKeepsAtMostMaxAge() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock, capacity: 8 << 20, maxAgeMs: 5_000)
        defer { r.close() }
        r.beginSegment()
        try writeAll(r, tsStream(0, 12))
        guard let oldest = r.oldest else { throw Failure(description: "sem índice") }
        try expect(r.liveMediaMs - oldest.mediaMs <= 5_000, true, "o mais antigo tem \(r.liveMediaMs - oldest.mediaMs) ms")
        try expect(oldest.mediaMs >= 6_000, true)
    }

    static func ringDropsThePreviousProgramme() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock, capacity: 8 << 20)
        defer { r.close() }
        r.beginSegment()
        try writeAll(r, tsStream(0, 12))
        // O programa seguinte começou aos 7 s: antes disso já não se pode recuar.
        r.dropBefore(7_000)
        let oldest = r.oldest?.mediaMs ?? -1
        try expect((7_000...7_500).contains(oldest), true, "o mais antigo está em \(oldest) ms")
        try expect((r.entry(atOrBefore: 2_000)?.mediaMs ?? 0) >= 7_000, true)
        // Só avança: um início mais antigo (guia recarregado) não traz nada de volta.
        r.dropBefore(3_000)
        try expect((r.oldest?.mediaMs ?? 0) >= 7_000, true)
        // Nunca passa do direto: fica pelo menos o último ponto.
        r.dropBefore(60_000)
        try expect(r.oldest != nil, true)
    }

    static func ringShorterMaxAgeTrimsRightAway() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock, capacity: 8 << 20, maxAgeMs: 60_000)
        defer { r.close() }
        r.beginSegment()
        try writeAll(r, tsStream(0, 12))
        try expect(r.oldest?.mediaMs, 0)
        r.setMaxAge(5_000)
        try expect(r.liveMediaMs - (r.oldest?.mediaMs ?? 0) <= 5_000, true)
    }

    static func ringShrinksToTheChannelBitrateAfterAMinute() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock, capacity: 64 << 20, maxAgeMs: 2 * 60_000)
        defer { r.close() }
        r.beginSegment()
        let minute = tsStream(0, 61, perPcr: 1)
        try writeAll(r, minute, chunk: 32_000)
        // ~376 bytes por 100 ms: 2 min com 15 % de folga ficam muito abaixo de 64 MB (mas nunca abaixo do mínimo).
        try expect(r.capacity, TimeshiftRing.minCapacity)
        // E continua a ler-se bem.
        let (back, _) = try readAll(r, from: 0, segment: 0, max: 10_000)
        try expect(back, Array(minute[0..<10_000]))
    }

    static func ringCloseWakesReadersAndDeletesTheFile() throws {
        let clock = TestClock()
        let (r, path) = try makeRing(clock)
        r.beginSegment()
        let a = tsStream(0, 1)
        try writeAll(r, a)
        let done = DispatchSemaphore(value: 0)
        var result: TimeshiftRing.Read?
        let reader = Thread {
            result = try? read(r, Int64(a.count), 0, max: 10, waitMs: 10_000)
            done.signal()
        }
        reader.start()
        usleep(50_000)
        r.close()
        try expect(done.wait(timeout: .now() + 2) == .success, true, "o leitor não acordou")
        try expect(result, .end)
        try expect(FileManager.default.fileExists(atPath: path), false)
        do {
            try r.write(a)
            throw Failure(description: "escrever num anel fechado")
        } catch let e as TimeshiftRing.Failure {
            try expect(e, .closed)
        }
    }

    // MARK: RingServer

    /// Um pedido ao servidor local, como o VLC: devolve o cabeçalho e os bytes do corpo até [max] (ou até fechar).
    private final class RingClient {
        let fd: Int32

        init(port: UInt16, path: String) throws {
            #if canImport(Darwin)
            fd = socket(AF_INET, SOCK_STREAM, 0)
            #else
            fd = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
            #endif
            RingServer.noSigPipe(fd)
            var timeout = timeval(tv_sec: 3, tv_usec: 0)
            _ = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var address = RingServer.loopback(port: port)
            let ok = withUnsafePointer(to: &address) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            guard ok == 0 else { throw Failure(description: "não ligou ao servidor (\(errno))") }
            let request = Array("GET \(path) HTTP/1.1\r\nHost: 127.0.0.1\r\nUser-Agent: test\r\n\r\n".utf8)
            _ = request.withUnsafeBytes { RingServer.sendAll(fd, $0.baseAddress!, $0.count) }
        }

        deinit { _ = Glue.close(fd) }

        /// Lê até ter [count] bytes (ou fechar, ou passar o tempo).
        func read(_ count: Int) -> [UInt8] {
            var out: [UInt8] = []
            var chunk = [UInt8](repeating: 0, count: 4_096)
            while out.count < count {
                let n = chunk.withUnsafeMutableBytes { recv(fd, $0.baseAddress, min($0.count, count - out.count), 0) }
                if n <= 0 { break }
                out += chunk[0..<n]
            }
            return out
        }

        /// O cabeçalho da resposta (até à linha em branco).
        func head() -> String {
            var out: [UInt8] = []
            while !out.suffix(4).elementsEqual([13, 10, 13, 10]) {
                let b = read(1)
                if b.isEmpty { break }
                out += b
            }
            return String(decoding: out, as: UTF8.self)
        }
    }

    static func ringServerServesFromAnOffsetAndWaitsAtTheLiveEdge() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        let server = RingServer(ring: r)
        let port = try server.start()
        defer { server.stop() }
        r.beginSegment()
        let a = tsStream(0, 2)
        try writeAll(r, a)
        let url = server.url(at: 1_880)
        try expect(url.host, "127.0.0.1")
        let client = try RingClient(port: port, path: url.path + "?" + (url.query ?? ""))
        try expect(client.head().hasPrefix("HTTP/1.1 200"), true)
        try expect(client.read(a.count - 1_880), Array(a[1_880...]))
        // No direto a resposta não acaba: o que chega depois segue pela mesma ligação.
        let b = tsStream(2_000, 1)
        try writeAll(r, b)
        try expect(client.read(b.count), b)
        try expect(server.takeEnding(), nil)
    }

    static func ringServerEndsAtTheSegmentBoundary() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        let server = RingServer(ring: r)
        let port = try server.start()
        defer { server.stop() }
        r.beginSegment()
        let a = tsStream(0, 1)
        try writeAll(r, a)
        r.endSegment(final: false)
        r.beginSegment()
        try writeAll(r, tsStream(5_000, 1))
        let url = server.url(at: 0)
        let client = try RingClient(port: port, path: url.path + "?" + (url.query ?? ""))
        _ = client.head()
        // Só o primeiro segmento, e a ligação fecha.
        try expect(client.read(a.count + 10_000), a)
        usleep(50_000)
        try expect(server.takeEnding(), RingServer.Ending(reason: .boundary, at: Int64(a.count)))
    }

    static func ringServerRefusesOtherPaths() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        let server = RingServer(ring: r)
        let port = try server.start()
        defer { server.stop() }
        r.beginSegment()
        try writeAll(r, tsStream(0, 1))
        // Sem o segredo, nada (outra app do aparelho não lê o anel).
        let client = try RingClient(port: port, path: "/ring.ts?at=0")
        try expect(client.head().hasPrefix("HTTP/1.1 404"), true)
        try expect(RingServer.offset(path: "/\(server.secret)/ring.ts?at=42", secret: server.secret), 42)
        try expect(RingServer.offset(path: "/\(server.secret)/ring.ts", secret: server.secret), 0)
        try expect(RingServer.offset(path: "/\(server.secret)/ring.ts?at=-1", secret: server.secret), nil)
        try expect(RingServer.offset(path: "/outro/ring.ts?at=1", secret: server.secret), nil)
    }

    static func ringServerAnswers502WhenTheStreamNeverCame() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        let server = RingServer(ring: r)
        let port = try server.start()
        defer { server.stop() }
        let url = server.url(at: 0)
        let client = try RingClient(port: port, path: url.path + "?" + (url.query ?? ""))
        usleep(50_000)
        r.endSegment(final: true, error: Dropped(code: 404))
        try expect(client.head().hasPrefix("HTTP/1.1 502"), true)
    }

    static func ringServerStopCutsAWaitingResponse() throws {
        let clock = TestClock()
        let (r, _) = try makeRing(clock)
        defer { r.close() }
        let server = RingServer(ring: r)
        let port = try server.start()
        r.beginSegment()
        let a = tsStream(0, 1)
        try writeAll(r, a)
        let url = server.url(at: 0)
        let client = try RingClient(port: port, path: url.path + "?" + (url.query ?? ""))
        _ = client.head()
        try expect(client.read(a.count), a)
        // À espera no direto; ao parar, a resposta acaba (o VLC vê o fim) em vez de ficar presa.
        let started = Date()
        server.stop()
        try expect(client.read(1), [])
        try expect(Date().timeIntervalSince(started) < 2, true, "a resposta não acabou")
    }
}
