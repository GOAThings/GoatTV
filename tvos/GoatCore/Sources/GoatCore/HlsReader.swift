import CommonCrypto
import Foundation
import GoatShared
import os

/// Um canal HLS lido como um só stream para o motor GoatCore, como o HlsInputStream do Android:
/// - a playlist (a principal escolhe a qualidade mais alta) e os segmentos, um atrás do outro, uma ligação de cada vez;
/// - num direto começa três segmentos antes do fim e vai relendo a playlist;
/// - os segmentos cifrados com AES-128 decifram-se aqui;
/// - nos fMP4, o segmento de inicialização vai à frente sempre que muda.
/// As outras cifras (SAMPLE-AES…) dão [Failure.unsupported] e o leitor passa a outro motor.
public final class HlsReader: @unchecked Sendable {
    public enum Failure: Error, CustomStringConvertible {
        case unsupported(String)
        case http(Int)
        case network(String)

        public var description: String {
            switch self {
            case .unsupported(let why): "unsupported HLS: \(why)"
            case .http(let code): "HTTP \(code)"
            case .network(let why): "network: \(why)"
            }
        }
    }

    /// O que chegou (na thread do leitor), pela ordem do stream.
    public var onData: ((Data) -> Void)?
    /// O fim: nil quando a playlist acabou (um VOD), o erro quando caiu.
    public var onEnd: ((Error?) -> Void)?
    /// Enquanto disser que sim, o leitor espera antes do segmento seguinte (o motor tem muito à frente).
    public var shouldWait: (() -> Bool)?
    /// Num VOD aberto a meio ([startMs]): onde começa (ms) o segmento por onde se começou.
    public var onStart: ((Int64) -> Void)?
    /// Num VOD (catch-up em HLS), o ponto onde começar: o segmento que o tem.
    public var startMs: Int64 = 0

    private let url: URL
    private let headers: [String: String]
    private let session: URLSession
    private let stopped = OSAllocatedUnfairLock(initialState: false)

    public init(url: URL, headers: [String: String]) {
        self.url = url
        self.headers = headers
        let config = URLSessionConfiguration.ephemeral
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        config.httpMaximumConnectionsPerHost = 1
        session = URLSession(configuration: config)
    }

    public func start() {
        DispatchQueue.global(qos: .userInitiated).async { self.run() }
    }

    public func stop() {
        stopped.withLock { $0 = true }
        session.invalidateAndCancel()
    }

    private var isStopped: Bool { stopped.withLock { $0 } }

    // MARK: Leitura

    private var mediaUrl: String?
    private var nextSequence = Int64.min
    private var pending: [HlsSegment] = []
    private var ended = false
    private var targetMs: Int64 = 6_000
    private var lastReload: Date = .distantPast
    private var sentMap: HlsRange?
    private var keyUri: String?
    private var keyBytes: Data?
    /// As qualidades da playlist principal (da mais baixa à mais alta) e a que se lê; ver [measured].
    private var variants: [HlsVariant] = []
    private var level = -1
    private var slow = 0
    private var fast = 0
    /// Mudou-se de qualidade: se a nova não continuar a numeração, volta-se ao ponto do direto.
    private var resync = false

    private func run() {
        do {
            while !isStopped {
                if pending.isEmpty {
                    if ended, nextSequence != Int64.min { break }
                    // Um direto: a playlist volta a ler-se a meio da duração de um segmento.
                    let wait = Double(targetMs) / 2000 - Date().timeIntervalSince(lastReload)
                    if wait > 0, nextSequence != Int64.min { Thread.sleep(forTimeInterval: wait) }
                    try reload()
                    continue
                }
                while shouldWait?() == true, !isStopped { Thread.sleep(forTimeInterval: 0.1) }
                guard !isStopped, let segment = pending.first else { break }
                // O moov antes dos segmentos que o usam.
                if let map = segment.map, !map.sameAs(other: sentMap) {
                    sentMap = map
                    deliver(try fetch(map.uri, offset: map.offset, length: map.length))
                    continue
                }
                pending.removeFirst()
                nextSequence = segment.sequence + 1
                let started = Date()
                var data = try fetch(segment.range.uri, offset: segment.range.offset, length: segment.range.length)
                measured(bytes: data.count, seconds: Date().timeIntervalSince(started), duration: segment.duration)
                if let key = segment.key {
                    // A chave lê-se antes de o segmento seguinte abrir: uma ligação de cada vez.
                    let bytes = try keyFor(key.uri)
                    guard let plain = Self.decrypt(data, key: bytes, iv: AppleMedia.shared.iv(key: key) as Data) else {
                        throw Failure.network("AES-128 decrypt")
                    }
                    data = plain
                }
                deliver(data)
            }
            if !isStopped { onEnd?(nil) }
        } catch {
            if !isStopped { onEnd?(error) }
        }
    }

    /// Um segmento de [duration] s chegou em [seconds]: a rede não chega (mais do que dura, ou mais de 80 % duas
    /// vezes) → a qualidade que caiba; folga grande quatro vezes seguidas → uma acima. Como no Android.
    private func measured(bytes: Int, seconds: Double, duration: Double) {
        guard variants.count > 1, duration > 0 else { return }
        let ratio = seconds / duration
        let bps = Double(bytes) * 8 / max(seconds, 0.001)
        if ratio > 0.8 { slow += 1 }
        if ratio > 1.0 || (ratio > 0.8 && slow >= 2) {
            let fits = max(0, variants.lastIndex(where: { Double($0.bandwidth) <= bps * 0.7 }) ?? 0)
            if fits < level { switchTo(fits) } else { slow = 0 }
            return
        }
        if ratio <= 0.8 { slow = 0 }
        if level < variants.count - 1, ratio < 0.3, bps > Double(variants[level + 1].bandwidth) * 1.5 {
            fast += 1
            if fast >= 4 { switchTo(level + 1) }
        } else {
            fast = 0
        }
    }

    private func switchTo(_ next: Int) {
        level = next
        mediaUrl = variants[next].uri
        slow = 0
        fast = 0
        pending.removeAll()
        resync = true
        lastReload = .distantPast
    }

    private func deliver(_ data: Data) {
        guard !isStopped, !data.isEmpty else { return }
        onData?(data)
    }

    private func reload() throws {
        var base = mediaUrl ?? url.absoluteString
        var playlist = HlsPlaylist.companion.parse(text: try text(base), base: base)
        if playlist.isMaster {
            if let why = playlist.unsupported { throw Failure.unsupported(why) }
            // Começa na qualidade mais alta (uma TV, ou um telemóvel na Wi-Fi de casa); desce e sobe com a rede.
            variants = playlist.variants.sorted { $0.bandwidth < $1.bandwidth }
            guard !variants.isEmpty else { throw Failure.unsupported("empty master") }
            level = variants.count - 1
            base = variants[level].uri
            playlist = HlsPlaylist.companion.parse(text: try text(base), base: base)
        }
        mediaUrl = base
        if let why = playlist.unsupported { throw Failure.unsupported(why) }
        targetMs = min(max(Int64(playlist.targetDuration * 1000), 1_000), 20_000)
        ended = playlist.ended
        let segments = playlist.segments
        if nextSequence == Int64.min, !segments.isEmpty {
            var first = ended ? 0 : max(0, segments.count - 3)
            if ended, startMs > 0 {
                // O segmento onde fica o ponto pedido (pela soma das durações).
                var at: Int64 = 0
                for (i, s) in segments.enumerated() {
                    let d = Int64(s.duration * 1000)
                    if at + d > startMs || i == segments.count - 1 {
                        first = i
                        break
                    }
                    at += d
                }
                onStart?(at)
            }
            nextSequence = segments[first].sequence
        }
        if resync {
            resync = false
            if !ended, !segments.isEmpty, !segments.contains(where: { $0.sequence == nextSequence }) {
                nextSequence = segments[max(0, segments.count - 3)].sequence
            }
        }
        for s in segments where s.sequence >= nextSequence && !pending.contains(where: { $0.sequence == s.sequence }) {
            pending.append(s)
        }
        lastReload = Date()
    }

    private func keyFor(_ uri: String) throws -> Data {
        if uri == keyUri, let keyBytes { return keyBytes }
        let bytes = try fetch(uri, offset: 0, length: -1)
        guard bytes.count == 16 else { throw Failure.unsupported("AES-128 key of \(bytes.count) bytes") }
        keyUri = uri
        keyBytes = bytes
        return bytes
    }

    private func text(_ uri: String) throws -> String {
        String(decoding: try fetch(uri, offset: 0, length: -1), as: UTF8.self)
    }

    /// [uri] inteiro, ou [length] bytes a partir de [offset] ([length] -1 = até ao fim), à espera da resposta.
    private func fetch(_ uri: String, offset: Int64, length: Int64) throws -> Data {
        guard let u = URL(string: uri) else { throw Failure.network("bad URL") }
        var request = URLRequest(url: u)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        if length >= 0 {
            request.setValue("bytes=\(offset)-\(offset + length - 1)", forHTTPHeaderField: "Range")
        } else if offset > 0 {
            request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
        }
        let done = DispatchSemaphore(value: 0)
        let box = OSAllocatedUnfairLock<Result<Data, Error>?>(initialState: nil)
        session.dataTask(with: request) { data, response, error in
            let result: Result<Data, Error>
            if let error {
                result = .failure(Failure.network(error.localizedDescription))
            } else if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                result = .failure(Failure.http(http.statusCode))
            } else {
                result = .success(data ?? Data())
            }
            box.withLock { $0 = result }
            done.signal()
        }.resume()
        done.wait()
        guard let result = box.withLock({ $0 }) else { throw Failure.network("no answer") }
        return try result.get()
    }

    /// AES-128 em CBC com o enchimento PKCS7 (o segmento inteiro, como o HLS manda).
    static func decrypt(_ data: Data, key: Data, iv: Data) -> Data? {
        guard key.count == kCCKeySizeAES128, iv.count == kCCBlockSizeAES128 else { return nil }
        var out = Data(count: data.count + kCCBlockSizeAES128)
        let capacity = out.count
        var moved = 0
        let status: CCCryptorStatus = out.withUnsafeMutableBytes { (o: UnsafeMutableRawBufferPointer) -> CCCryptorStatus in
            data.withUnsafeBytes { (d: UnsafeRawBufferPointer) -> CCCryptorStatus in
                key.withUnsafeBytes { (k: UnsafeRawBufferPointer) -> CCCryptorStatus in
                    iv.withUnsafeBytes { (v: UnsafeRawBufferPointer) -> CCCryptorStatus in
                        CCCrypt(CCOperation(kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                                k.baseAddress, kCCKeySizeAES128, v.baseAddress, d.baseAddress, data.count,
                                o.baseAddress, capacity, &moved)
                    }
                }
            }
        }
        guard status == CCCryptorStatus(kCCSuccess) else { return nil }
        return out.prefix(moved)
    }
}

/// Os segmentos de um HLS sempre em TS, para o anel da pausa e as gravações: um fMP4 sai remultiplexado (o TsRemuxer
/// partilhado com o Android); um TS, ou um fMP4 cifrado, sai como vem.
public final class HlsToTs {
    private let buffer = GoatShared.TsRemuxBuffer()

    public init() {}

    public func feed(_ data: Data) -> Data {
        buffer.feed(data: data)
    }
}
