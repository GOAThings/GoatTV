import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Serve um [TimeshiftRing] ao VLC por HTTP, só em 127.0.0.1: `GET /<segredo>/ring.ts?at=<posição>` dá o anel a partir
/// dessa posição e, no direto, fica à espera do que a ligação ao servidor for escrevendo (como o RingDataSource do
/// GoatTV Android, que o ExoPlayer lê sem HTTP). O VLC nunca fala com o fornecedor: a única ligação é a do gravador.
///
/// Sockets BSD com uma thread por pedido (o VLC abre um de cada vez; dois por instantes ao saltar): o envio bloqueia
/// quando o VLC está em pausa e não lê, que é o que se quer. O segredo no caminho é para mais nenhuma app do aparelho
/// ler o anel pela porta local. Uma resposta acaba no fim do segmento (`.boundary`), quando o ponto já foi reescrito
/// (`.overrun`) ou quando não vem mais nada (`.end`): quem usa o servidor vê porquê em [takeEnding] e reabre o
/// player no sítio certo.
public final class RingServer: @unchecked Sendable {
    /// Porque acabou a última resposta, e em que posição do anel.
    public struct Ending: Equatable, Sendable {
        public let reason: TimeshiftRing.Read
        public let at: Int64
    }

    public let ring: TimeshiftRing
    public let secret: String
    public private(set) var port: UInt16 = 0

    private let lock = NSLock()
    private var listener: Int32 = -1
    private var stopped = false
    private var clients: Set<Int32> = []
    private var ending: Ending?

    static let bufferBytes = 64 * 1024
    static let waitMs = 250
    static let kindPollMs: UInt32 = 20

    public init(ring: TimeshiftRing) {
        self.ring = ring
        var rng = SystemRandomNumberGenerator()
        secret = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &rng)) }.joined()
    }

    /// O endereço para o VLC ler o anel a partir de [offset].
    public func url(at offset: Int64) -> URL {
        URL(string: "http://127.0.0.1:\(port)/\(secret)/ring.ts?at=\(max(0, offset))")!
    }

    /// Abre a porta (uma qualquer, livre) e devolve-a.
    @discardableResult
    public func start() throws -> UInt16 {
        let fd = socket(AF_INET, Self.stream, 0)
        guard fd >= 0 else { throw TimeshiftRing.Failure.io(errno) }
        Self.noSigPipe(fd)
        var yes: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
        var address = Self.loopback(port: 0)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0, listen(fd, 8) == 0 else {
            let e = errno
            _ = Glue.close(fd)
            throw TimeshiftRing.Failure.io(e)
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        port = UInt16(bigEndian: address.sin_port)
        Glue.locked(lock) { listener = fd }
        let thread = Thread { [self] in acceptLoop(fd) }
        thread.name = "timeshift-server"
        thread.start()
        return port
    }

    /// Fecha a porta e corta as respostas a meio (o VLC vê o fim do stream).
    public func stop() {
        // Desbloqueia um envio parado (o VLC em pausa). Cada thread fecha o seu socket, mas só depois de o tirar
        // da lista (com o lock): assim nunca se corta um número de socket já reaproveitado.
        Glue.locked(lock) {
            stopped = true
            for fd in clients { _ = shutdown(fd, Self.shutBoth) }
        }
    }

    /// Porque acabou a última resposta (e esquece-o): nil se não acabou nenhuma desde a última vez.
    public func takeEnding() -> Ending? {
        Glue.locked(lock) {
            defer { ending = nil }
            return ending
        }
    }

    private var isStopped: Bool { Glue.locked(lock) { stopped } }

    // MARK: Ligações

    private func acceptLoop(_ fd: Int32) {
        defer {
            Glue.locked(lock) { listener = -1 }
            _ = Glue.close(fd)
        }
        while !isStopped {
            var poller = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            let ready = poll(&poller, 1, 300)
            if isStopped { return }
            if ready < 0 {
                if errno == EINTR { continue }
                return
            }
            if ready == 0 { continue }
            if poller.revents & Int16(POLLERR | POLLHUP | POLLNVAL) != 0 { return }
            let client = accept(fd, nil, nil)
            if client < 0 {
                if errno == EINTR || errno == EAGAIN || errno == ECONNABORTED { continue }
                return
            }
            Self.noSigPipe(client)
            let added: Bool = Glue.locked(lock) {
                guard !stopped else { return false }
                clients.insert(client)
                return true
            }
            guard added else {
                _ = Glue.close(client)
                return
            }
            let thread = Thread { [self] in
                serve(client)
                Glue.locked(lock) { _ = clients.remove(client) }
                _ = Glue.close(client)
            }
            thread.name = "timeshift-client"
            thread.start()
        }
    }

    private func serve(_ fd: Int32) {
        guard let offset = readRequest(fd) else {
            send(fd, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        }
        // Se é TS só se sabe com os primeiros KB.
        while ring.isTs == nil, !ring.isFinished {
            if isStopped || Self.peerClosed(fd) { return }
            usleep(Self.kindPollMs * 1_000)
        }
        guard ring.isTs == true else {
            if ring.isTs == nil {
                // A ligação ao servidor acabou sem dar nada: o VLC passa à forma seguinte de abrir o canal.
                send(fd, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            }
            // Não é TS: o anel não serve; fica-se à espera de que o player desista (abre a ligação direta).
            while !isStopped, !Self.peerClosed(fd) { usleep(UInt32(Self.waitMs) * 1_000) }
            return
        }
        guard send(fd, "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n") else { return }
        var pos = offset
        let segment = ring.segment(at: pos)
        let buffer = UnsafeMutableRawBufferPointer.allocate(byteCount: Self.bufferBytes, alignment: 16)
        defer { buffer.deallocate() }
        while true {
            let result: TimeshiftRing.Read
            do {
                result = try ring.read(at: pos, segment: segment, into: buffer, waitMs: Self.waitMs)
            } catch {
                result = .end
            }
            switch result {
            case .bytes(let n):
                guard Self.sendAll(fd, UnsafeRawPointer(buffer.baseAddress!), n) else { return }
                pos += Int64(n)
            case .wait:
                if isStopped || Self.peerClosed(fd) { return }
            case .boundary, .overrun, .end:
                Glue.locked(lock) { ending = Ending(reason: result, at: pos) }
                return
            }
        }
    }

    /// Lê o pedido (até à linha em branco) e devolve a posição pedida, ou nil se não for para o anel.
    private func readRequest(_ fd: Int32) -> Int64? {
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        _ = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var head: [UInt8] = []
        var chunk = [UInt8](repeating: 0, count: 2_048)
        while true {
            if case .done(let request) = LanRequest.scan(head) {
                guard let request, request.method == "GET" else { return nil }
                return Self.offset(path: request.path, secret: secret)
            }
            let n = chunk.withUnsafeMutableBytes { recv(fd, $0.baseAddress, $0.count, 0) }
            if n < 0, errno == EINTR { continue }
            guard n > 0 else { return nil }
            head.append(contentsOf: chunk[0..<n])
        }
    }

    /// A posição em `/<segredo>/ring.ts?at=<posição>`; nil para outro caminho.
    static func offset(path: String, secret: String) -> Int64? {
        guard let parts = URLComponents(string: path), parts.path == "/\(secret)/ring.ts" else { return nil }
        let at = parts.queryItems?.first { $0.name == "at" }?.value
        guard let at else { return 0 }
        guard let value = Int64(at), value >= 0 else { return nil }
        return value
    }

    @discardableResult
    private func send(_ fd: Int32, _ text: String) -> Bool {
        let bytes = Array(text.utf8)
        return bytes.withUnsafeBytes { Self.sendAll(fd, $0.baseAddress!, $0.count) }
    }

    // MARK: Sistema

    #if canImport(Darwin)
    private static let stream = SOCK_STREAM
    private static let sendFlags: Int32 = 0
    #else
    private static let stream = Int32(SOCK_STREAM.rawValue)
    private static let sendFlags = Int32(MSG_NOSIGNAL)
    #endif
    private static let shutBoth = Int32(SHUT_RDWR)

    /// Escrever para um socket que o VLC já fechou não pode matar a app com um SIGPIPE.
    static func noSigPipe(_ fd: Int32) {
        #if canImport(Darwin)
        var yes: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout<Int32>.size))
        #endif
    }

    static func loopback(port: UInt16) -> sockaddr_in {
        var address = sockaddr_in()
        #if canImport(Darwin)
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        #endif
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = UInt32(0x7F00_0001).bigEndian
        return address
    }

    static func sendAll(_ fd: Int32, _ data: UnsafeRawPointer, _ count: Int) -> Bool {
        var sent = 0
        while sent < count {
            let n = Glue.send(fd, data + sent, count - sent, sendFlags)
            if n < 0, errno == EINTR { continue }
            guard n > 0 else { return false }
            sent += n
        }
        return true
    }

    /// O VLC fechou a ligação (ao saltar, ou ao mudar de canal). Ele não manda nada depois do pedido: se há algo
    /// para ler, é o fim.
    static func peerClosed(_ fd: Int32) -> Bool {
        var byte: UInt8 = 0
        let n = recv(fd, &byte, 1, Int32(MSG_PEEK) | Int32(MSG_DONTWAIT))
        if n == 0 { return true }
        if n < 0 { return errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR }
        return false
    }
}

extension Glue {
    static func send(_ fd: Int32, _ data: UnsafeRawPointer, _ count: Int, _ flags: Int32) -> Int {
        #if canImport(Darwin)
        return Darwin.send(fd, data, count, flags)
        #else
        return Glibc.send(fd, data, count, flags)
        #endif
    }
}
