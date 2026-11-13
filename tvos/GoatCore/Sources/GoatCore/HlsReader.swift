import CommonCrypto
import Foundation
import GoatShared
import os

/// Um canal HLS lido como um só stream para o motor GoatCore, como o HlsInputStream do Android:
/// - a playlist (a principal escolhe a qualidade mais alta) e os segmentos, um atrás do outro, uma ligação de cada vez;
/// - num direto começa três segmentos antes do fim e vai relendo a playlist;
/// - os segmentos cifrados com AES-128 decifram-se aqui;
/// - nos fMP4, o segmento de inicialização vai à frente sempre que muda.
/// - com o áudio numa playlist à parte, lê-se um segmento de cada, à vez, e sai um TS só com os dois (HlsMerger).
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

    /// Uma playlist de segmentos lida em sequência: a do vídeo ([main], que pode ser a principal e muda de qualidade)
    /// ou a do áudio à parte. [seconds]: quanto já se leu (é por aí que se escolhe o lado a ler a seguir).
    private final class Lane {
        var mediaUrl: String
        let main: Bool
        /// A língua da playlist (ISO 639-2), numa faixa de áudio à parte.
        let language: String?
        var nextSequence = Int64.min
        var pending: [HlsSegment] = []
        var ended = false
        var targetMs: Int64 = 6_000
        var lastReload: Date = .distantPast
        /// O segmento de inicialização que já foi (o próximo que for diferente vai antes do seu segmento).
        var sentMap: HlsRange?
        var seconds = 0.0
        /// Mudou-se de qualidade: se a nova não continuar a numeração, volta-se ao ponto do direto.
        var resync = false
        /// Num direto: quanto antes do fim se começou (ms), para o áudio começar no mesmo ponto que o vídeo.
        var liveOffsetMs: Int64 = -1

        init(_ url: String, main: Bool, language: String? = nil) {
            mediaUrl = url
            self.main = main
            self.language = language
        }

        /// Uma playlist de legendas que deixou de responder.
        var failed = false

        /// Um VOD lido até ao fim (ou umas legendas que falharam).
        var isDone: Bool { failed || (ended && pending.isEmpty && nextSequence != Int64.min) }
    }

    private lazy var video = Lane(url.absoluteString, main: true)
    /// As faixas de áudio à parte (EXT-X-MEDIA com URI, uma por língua) e quem as junta ao vídeo; vazio quando vem
    /// dentro dos segmentos.
    private var audios: [Lane] = []
    /// As legendas WebVTT à parte (uma por língua); uma que falhe sai, sem parar o resto.
    private var subtitles: [Lane] = []
    private var merger: GoatShared.HlsMergeBuffer?
    /// Mais línguas à parte seriam mais pedidos por segmento, na única ligação.
    private static let maxAudio = 4
    private static let maxSubtitles = 4
    private var keyUri: String?
    private var keyBytes: Data?
    /// As qualidades da playlist principal (da mais baixa à mais alta) e a que se lê; ver [measured].
    private var variants: [HlsVariant] = []
    private var level = -1
    private var slow = 0
    private var fast = 0

    private func run() {
        do {
            while !isStopped {
                while shouldWait?() == true, !isStopped { Thread.sleep(forTimeInterval: 0.1) }
                guard !isStopped, let chunk = try nextChunk() else { break }
                deliver(chunk)
            }
            if !isStopped { onEnd?(nil) }
        } catch {
            if !isStopped { onEnd?(error) }
        }
    }

    /// O pedaço seguinte do stream: um segmento (ou, com o áudio à parte, o que os dois já juntaram); nil no fim.
    private func nextChunk() throws -> Data? {
        guard let merger else {
            guard let data = try next(video) else { return nil }
            // A playlist principal pode ter dito agora que o áudio vem à parte: este já vai pelo junta-dois.
            if let merger = self.merger {
                let out = merger.video(data: data)
                if out.isEmpty { return try nextChunk() }
                return out
            }
            return data
        }
        while !isStopped {
            // O lado que vai mais atrás lê o segmento seguinte (o vídeo primeiro, num empate); um que acabou deixa os
            // outros irem até ao fim.
            let open = ([video] + audios + subtitles).filter { !$0.isDone }
            guard let lane = open.min(by: { $0.seconds < $1.seconds }), !(video.isDone && audios.allSatisfy({ $0.isDone })) else {
                let out = merger.finish()
                return out.isEmpty ? nil : out
            }
            var out = Data()
            if let i = subtitles.firstIndex(where: { $0 === lane }) {
                do {
                    if let d = try next(lane) { out = merger.subtitles(data: d, lane: Int32(i), language: lane.language) }
                } catch {
                    os_log("GoatCore: HLS subtitles %{public}@: %{public}@", lane.language ?? "?", "\(error)")
                    lane.failed = true
                }
            } else if let d = try next(lane) {
                if lane === video {
                    out = merger.video(data: d)
                } else if let i = audios.firstIndex(where: { $0 === lane }) {
                    out = merger.audio(data: d, lane: Int32(i), language: lane.language)
                }
            }
            if let why = merger.unsupported { throw Failure.unsupported(why) }
            if !out.isEmpty { return out }
        }
        return nil
    }

    /// O segmento seguinte de [lane] (com o init segment à frente, se mudou), decifrado; nil no fim de um VOD.
    private func next(_ lane: Lane) throws -> Data? {
        while !isStopped {
            if lane.pending.isEmpty {
                if lane.ended, lane.nextSequence != Int64.min { return nil }
                // Um direto: a playlist volta a ler-se a meio da duração de um segmento.
                let wait = Double(lane.targetMs) / 2000 - Date().timeIntervalSince(lane.lastReload)
                if wait > 0, lane.nextSequence != Int64.min { Thread.sleep(forTimeInterval: wait) }
                try reload(lane)
                continue
            }
            let segment = lane.pending.removeFirst()
            lane.nextSequence = segment.sequence + 1
            // O moov antes dos segmentos que o usam.
            var map: Data?
            if let m = segment.map, !m.sameAs(other: lane.sentMap) {
                lane.sentMap = m
                map = try fetch(m.uri, offset: m.offset, length: m.length)
            }
            let started = Date()
            var data = try fetch(segment.range.uri, offset: segment.range.offset, length: segment.range.length)
            if lane.main { measured(bytes: data.count, seconds: Date().timeIntervalSince(started), duration: segment.duration) }
            lane.seconds += segment.duration
            if let key = segment.key {
                // A chave lê-se antes de o segmento seguinte abrir: uma ligação de cada vez.
                let bytes = try keyFor(key.uri)
                guard let plain = Self.decrypt(data, key: bytes, iv: AppleMedia.shared.iv(key: key) as Data) else {
                    throw Failure.network("AES-128 decrypt")
                }
                data = plain
            }
            if let map { return map + data }
            return data
        }
        return nil
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
        video.mediaUrl = variants[next].uri
        slow = 0
        fast = 0
        video.pending.removeAll()
        video.resync = true
        video.lastReload = .distantPast
    }

    private func deliver(_ data: Data) {
        guard !isStopped, !data.isEmpty else { return }
        onData?(data)
    }

    private func reload(_ lane: Lane) throws {
        var base = lane.mediaUrl
        var playlist = HlsPlaylist.companion.parse(text: try text(base), base: base)
        if playlist.isMaster {
            if let why = playlist.unsupported { throw Failure.unsupported(why) }
            // Começa na qualidade mais alta (uma TV, ou um telemóvel na Wi-Fi de casa); desce e sobe com a rede.
            variants = playlist.variants.sorted { $0.bandwidth < $1.bandwidth }
            guard !variants.isEmpty else { throw Failure.unsupported("empty master") }
            level = variants.count - 1
            base = variants[level].uri
            // O áudio à parte: as línguas todas (até [maxAudio]), para o menu do áudio as ter como num TS.
            let v = variants[level]
            audios = v.audio.prefix(Self.maxAudio).map { Lane($0.uri, main: false, language: $0.code) }
            subtitles = v.subtitles.prefix(Self.maxSubtitles).map { Lane($0.uri, main: false, language: $0.code) }
            if !audios.isEmpty || !subtitles.isEmpty {
                merger = GoatShared.HlsMergeBuffer(audioInVideo: audios.isEmpty)
            }
            playlist = HlsPlaylist.companion.parse(text: try text(base), base: base)
        }
        lane.mediaUrl = base
        if let why = playlist.unsupported { throw Failure.unsupported(why) }
        lane.targetMs = min(max(Int64(playlist.targetDuration * 1000), 1_000), 20_000)
        lane.ended = playlist.ended
        let segments = playlist.segments
        if lane.nextSequence == Int64.min, !segments.isEmpty {
            lane.nextSequence = segments[firstSegment(lane, segments)].sequence
        }
        if lane.resync {
            lane.resync = false
            if !lane.ended, !segments.isEmpty, !segments.contains(where: { $0.sequence == lane.nextSequence }) {
                lane.nextSequence = segments[max(0, segments.count - 3)].sequence
            }
        }
        for s in segments where s.sequence >= lane.nextSequence && !lane.pending.contains(where: { $0.sequence == s.sequence }) {
            lane.pending.append(s)
        }
        lane.lastReload = Date()
    }

    /// Por onde começar: num VOD o do ponto pedido; num direto três antes do fim (o áudio, à mesma distância).
    private func firstSegment(_ lane: Lane, _ segments: [HlsSegment]) -> Int {
        if lane.ended {
            guard startMs > 0 else { return 0 }
            // O segmento onde fica o ponto pedido (pela soma das durações).
            var at: Int64 = 0
            for (i, s) in segments.enumerated() {
                let d = Int64(s.duration * 1000)
                if at + d > startMs || i == segments.count - 1 {
                    if lane.main { onStart?(at) }
                    return i
                }
                at += d
            }
            return 0
        }
        if lane.main {
            let first = max(0, segments.count - 3)
            lane.liveOffsetMs = segments[first...].reduce(Int64(0)) { $0 + Int64($1.duration * 1000) }
            return first
        }
        guard video.liveOffsetMs >= 0 else { return max(0, segments.count - 3) }
        var fromEnd: Int64 = 0
        for i in segments.indices.reversed() {
            fromEnd += Int64(segments[i].duration * 1000)
            if fromEnd >= video.liveOffsetMs { return i }
        }
        return 0
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
