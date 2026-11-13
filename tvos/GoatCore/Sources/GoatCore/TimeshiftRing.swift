import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Lê os pacotes MPEG-TS (188 bytes) de um fluxo que chega aos bocados e avisa de cada PCR (o relógio do emissor).
/// Volta a sincronizar sozinho quando perde o alinhamento (bytes a mais ou a menos depois de uma falha).
/// Decide também, logo nos primeiros KB, se o fluxo é mesmo TS ([isTs]). O mesmo que o TsScanner do GoatTV Android.
public final class TsScanner {
    public static let packet = 188
    private static let sync: UInt8 = 0x47
    private static let notTsBytes: Int64 = 4_096

    private var pending: [UInt8] = []
    /// Posição absoluta (no anel) do primeiro byte de [pending].
    private var pendingStart: Int64 = 0
    private var segmentStart: Int64 = 0
    private var synced = false
    private var pcrPid = -1

    /// Cada PCR: onde começa o pacote (posição no anel) e o relógio em ms.
    var onPcr: ((_ offset: Int64, _ pcrMs: Int64) -> Void)?

    /// nil: ainda não se sabe; true: TS; false: outra coisa (MP4, MKV…), não serve para o anel.
    public private(set) var isTs: Bool?

    /// Início do último pacote completo (onde um leitor pode começar quando não há PCR).
    public private(set) var lastPacket: Int64 = 0

    public init(onPcr: ((_ offset: Int64, _ pcrMs: Int64) -> Void)? = nil) {
        self.onPcr = onPcr
    }

    /// Nova ligação a partir de [at]: o que sobrou da anterior já não continua.
    public func reset(at: Int64) {
        pending.removeAll(keepingCapacity: true)
        pendingStart = at
        segmentStart = at
        lastPacket = at
        synced = false
        pcrPid = -1
    }

    public func feed(_ data: UnsafeRawBufferPointer) {
        guard !data.isEmpty else { return }
        pending.append(contentsOf: data.bindMemory(to: UInt8.self))
        let count = pending.count
        let packet = Self.packet
        var i = 0
        pending.withUnsafeBufferPointer { p in
            while true {
                if !synced {
                    // Um 0x47 com mais três à distância de um pacote: é o início de um pacote.
                    while i + packet * 3 < count {
                        if p[i] == Self.sync, p[i + packet] == Self.sync, p[i + packet * 2] == Self.sync, p[i + packet * 3] == Self.sync {
                            synced = true
                            if isTs == nil { isTs = true }
                            break
                        }
                        i += 1
                    }
                    if !synced { break }
                }
                if i + packet > count { break }
                if p[i] != Self.sync {
                    synced = false
                    continue
                }
                self.packet(p, i)
                i += packet
            }
        }
        // Muitos KB logo no início sem um único pacote: não é TS.
        if isTs == nil, pendingStart + Int64(i) - segmentStart >= Self.notTsBytes { isTs = false }
        if i > 0 {
            pending.removeSubrange(0..<i)
            pendingStart += Int64(i)
        }
    }

    public func feed(_ bytes: [UInt8]) {
        bytes.withUnsafeBytes { feed($0) }
    }

    private func packet(_ p: UnsafeBufferPointer<UInt8>, _ i: Int) {
        let offset = pendingStart + Int64(i)
        lastPacket = offset
        let pid = (Int(p[i + 1] & 0x1F) << 8) | Int(p[i + 2])
        let adaptation = (p[i + 3] >> 4) & 3
        guard adaptation == 2 || adaptation == 3 else { return }
        let length = Int(p[i + 4])
        guard length >= 7, p[i + 5] & 0x10 != 0 else { return }
        // Um programa tem um só PID com o relógio; fica-se com o primeiro que aparece.
        if pcrPid == -1 { pcrPid = pid }
        guard pid == pcrPid else { return }
        let base = (Int64(p[i + 6]) << 25) | (Int64(p[i + 7]) << 17) | (Int64(p[i + 8]) << 9) | (Int64(p[i + 9]) << 1) | (Int64(p[i + 10]) >> 7)
        onPcr?(offset, base / 90)
    }
}

/// O anel do "pausar o direto": os últimos minutos de um canal num ficheiro de tamanho fixo, escritos por uma
/// thread (a única ligação ao servidor) e lidos por outra (quem serve o vídeo ao player), que pode ir atrás do direto.
/// O mesmo que o TimeshiftRing do GoatTV Android.
///
/// As posições são absolutas (bytes desde o início); no ficheiro, a posição p fica em p % [capacity]. Quando a
/// escrita dá a volta, o mais antigo vai sendo reescrito: abaixo de `clobbered` já não se pode ler.
/// Cada ligação ao servidor é um segmento: um leitor não passa de um para o outro (os tempos do vídeo recomeçam),
/// recebe `.boundary` e reabre no seguinte.
///
/// O índice liga o tempo do vídeo (ms, calculado pelos PCR do TS, sempre a crescer) às posições no anel,
/// de [entryMs] em [entryMs]: é por ele que se recua "10 segundos".
public final class TimeshiftRing: @unchecked Sendable {
    public struct Entry: Equatable, Sendable {
        public let mediaMs: Int64
        public let offset: Int64

        public init(mediaMs: Int64, offset: Int64) {
            self.mediaMs = mediaMs
            self.offset = offset
        }
    }

    /// O resultado de uma leitura: os bytes lidos, ou porque não há.
    public enum Read: Equatable, Sendable {
        case bytes(Int)
        /// Ainda não chegou mais nada (o leitor está no direto).
        case wait
        /// Acabou o segmento do leitor: há outro a seguir (a ligação caiu e voltou).
        case boundary
        /// O ponto do leitor já foi reescrito (ou saiu do anel).
        case overrun
        /// Não vem mais nada (a ligação acabou de vez, ou o anel fechou).
        case end
    }

    public enum Failure: Error, Equatable {
        case closed
        case noSegment
        case io(Int32)
    }

    /// Um ponto no índice a cada meio segundo de vídeo.
    public static let entryMs: Int64 = 500
    /// Sem PCR há mais do que isto: o relógio passa a ser o tempo real.
    static let noPcrMs: Int64 = 1_500
    /// Entre dois PCR seguidos nunca há mais do que isto (o normal é 40–100 ms).
    static let maxPcrStepMs: Int64 = 5_000
    /// O PCR tem 33 bits a 90 kHz: dá a volta a cada ~26,5 h.
    static let pcrWrapMs: Int64 = (Int64(1) << 33) / 90
    /// O direto estimado ([liveEdgeMs]) esquece-se 1 ms por cada tantos ms (2 %).
    static let peakDecay: Int64 = 50
    static let fitAfterMs: Int64 = 60_000
    static let fitMargin = 1.15
    public static let minCapacity: Int64 = 32 * 1024 * 1024

    private let path: String
    private let clock: () -> Int64
    private let lock = NSCondition()
    /// O ficheiro: lido e escrito com pread/pwrite (sem posição partilhada), fechado só com [fileLock].
    private var fd: Int32
    private let fileLock = NSLock()

    // Protegido por lock.
    private var size: Int64
    private var written: Int64 = 0
    private var clobbered: Int64 = 0
    /// O índice, por ordem; os primeiros [head] já saíram (tirar do início de um Array é caro).
    private var entries: [Entry] = []
    private var head = 0
    private var segments: [Int64] = []
    private var closed = false
    private var finished = false
    private var failure: Error?
    private var fitted = false
    private var maxAgeMs: Int64
    /// Abaixo disto (tempo do vídeo) já não se guarda: o programa anterior (ver [dropBefore]).
    private var floorMs = Int64.min
    /// Segmentos já tirados do início: os ids não mudam (um leitor guarda o do seu).
    private var removed = 0

    // Relógio do vídeo.
    private var media: Int64 = 0
    private var lastPcr: Int64 = -1
    private var lastTick: Int64 = 0
    private var lastPcrAt: Int64 = 0
    /// O tempo só pelo PCR: o tempo real contado sem PCR é provisório e [media] espera que o PCR o alcance.
    private var pcrMedia: Int64 = 0
    /// O maior avanço do vídeo sobre o relógio (ver [liveEdgeMs]) e quando foi.
    private var peakOffset = Int64.min
    private var peakAt: Int64 = 0
    private var newSegment = false
    private let scanner = TsScanner()

    /// [clock]: um relógio em ms que só anda para a frente (não a hora do dia).
    public init(path: String, capacity: Int64, maxAgeMs: Int64, clock: @escaping () -> Int64) throws {
        let fd = open(path, O_RDWR | O_CREAT | O_TRUNC, mode_t(0o600))
        guard fd >= 0 else { throw Failure.io(errno) }
        self.fd = fd
        self.path = path
        self.size = capacity
        self.maxAgeMs = maxAgeMs
        self.clock = clock
        scanner.onPcr = { [unowned self] offset, pcr in self.onPcr(offset, pcr) }
    }

    deinit {
        if fd >= 0 { _ = Glue.close(fd) }
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    /// Tamanho do anel; pode diminuir uma vez (ver [fitToAge]) enquanto a escrita ainda não deu a volta.
    public var capacity: Int64 { locked { size } }

    /// Muda o tempo máximo guardado; o tamanho do anel só se acerta por ele no primeiro minuto (ver [fitToAge]).
    public func setMaxAge(_ ms: Int64) {
        locked {
            guard ms != maxAgeMs else { return }
            maxAgeMs = ms
            trim()
        }
    }

    /// Esquece tudo antes de [mediaMs] (o início do programa que se está a ver): já não se pode recuar até lá e o
    /// espaço volta a ser usado. Só avança; um leitor que já esteja atrás continua até ao próximo recomeço.
    public func dropBefore(_ mediaMs: Int64) {
        locked {
            let floor = min(mediaMs, media)
            guard floor > floorMs else { return }
            floorMs = floor
            trim()
        }
    }

    /// Ver [TsScanner.isTs].
    public var isTs: Bool? { locked { scanner.isTs } }

    /// Bytes escritos desde o início.
    public var writtenBytes: Int64 { locked { written } }

    /// Tempo do vídeo no direto (o último byte que chegou).
    public var liveMediaMs: Int64 { locked { media } }

    /// O direto como o espectador o vê: num servidor que manda às rajadas, [liveMediaMs] salta o tamanho de cada rajada
    /// e fica parado até à seguinte. Aqui anda com o relógio desde a última rajada (o maior avanço do vídeo sobre o
    /// relógio, a esquecer devagar), sem saltos e sem nunca ficar atrás do último byte.
    public var liveEdgeMs: Int64 {
        locked {
            guard peakOffset != Int64.min else { return media }
            let now = clock()
            return max(media, now + decayedPeak(now))
        }
    }

    private func decayedPeak(_ now: Int64) -> Int64 { peakOffset - (now - peakAt) / Self.peakDecay }

    private func notePeak(_ now: Int64) {
        let offset = media - now
        if peakOffset == Int64.min || offset >= decayedPeak(now) {
            peakOffset = offset
            peakAt = now
        }
    }

    /// O ponto mais antigo para onde se pode recuar.
    public var oldest: Entry? { locked { head < entries.count ? entries[head] : nil } }

    /// Já não vem mais nada (a ligação acabou e não se volta a ligar, ou o anel fechou).
    public var isFinished: Bool { locked { finished || closed } }

    /// Quantos segmentos (ligações com dados) já houve.
    public var segmentCount: Int { locked { segments.count } }

    // MARK: Escrita (só a thread da ligação)

    /// Começa uma ligação nova (a primeira, ou depois de cair): os dados seguintes não continuam os anteriores.
    public func beginSegment() {
        locked {
            segments.append(written)
            scanner.reset(at: written)
            lastPcr = -1
            newSegment = true
            lock.broadcast()
        }
    }

    public func write(_ data: UnsafeRawBufferPointer) throws {
        let len = Int64(data.count)
        guard len > 0, let base = data.baseAddress else { return }
        lock.lock()
        if closed {
            lock.unlock()
            throw Failure.closed
        }
        guard !segments.isEmpty else {
            lock.unlock()
            throw Failure.noSegment
        }
        precondition(len <= size, "bocado maior do que o anel")
        let start = written
        let cap = size
        // Antes de escrever: o que estiver por baixo deixa de se poder ler.
        clobbered = max(clobbered, start + len - cap)
        lock.unlock()

        var pos = start % cap
        var done: Int64 = 0
        while done < len {
            let n = min(len - done, cap - pos)
            let r: Int = Glue.locked(fileLock) {
                fd < 0 ? -1 : pwrite(fd, base + Int(done), Int(n), off_t(pos))
            }
            if r < 0 {
                if errno == EINTR { continue }
                throw isFinished ? Failure.closed : Failure.io(errno)
            }
            done += Int64(r)
            pos += Int64(r)
            if pos == cap { pos = 0 }
        }

        locked {
            written = start + len
            scanner.feed(data)
            let now = clock()
            // Sem PCR (ou ainda sem ele) o relógio anda com o tempo real em que os dados chegam.
            if scanner.isTs != false, now - lastPcrAt > Self.noPcrMs {
                if lastTick != 0 { media += max(0, now - lastTick) }
                lastTick = now
                addEntry(newSegment ? segments[segments.count - 1] : scanner.lastPacket)
            }
            notePeak(now)
            trim()
            fitToAge()
            lock.broadcast()
        }
    }

    public func write(_ bytes: [UInt8]) throws {
        try bytes.withUnsafeBytes { try write($0) }
    }

    public func write(_ data: Data) throws {
        for region in data.regions {
            try region.withUnsafeBytes { try write($0) }
        }
    }

    /// A ligação caiu ou acabou; [final]: não vem outra, com o erro que a fez cair (nil = fim normal).
    public func endSegment(final: Bool, error: Error? = nil) {
        locked {
            if final, !finished {
                finished = true
                failure = error
            }
            lock.broadcast()
        }
    }

    private func onPcr(_ offset: Int64, _ pcrMs: Int64) {
        let now = clock()
        if lastPcr >= 0 {
            var d = pcrMs - lastPcr
            if d < -Self.pcrWrapMs / 2 { d += Self.pcrWrapMs }
            // O tempo do PCR desde o anterior, e não somado ao tempo real que entretanto se contou sem PCR (num
            // servidor que manda às rajadas contava-se duas vezes). Um salto (canal trocado no servidor, relógio
            // reposto): conta o tempo real que passou.
            if (0...Self.maxPcrStepMs).contains(d) {
                pcrMedia += d
                media = max(media, pcrMedia)
            } else {
                media += arrival(now)
                pcrMedia = media
            }
        } else {
            media += arrival(now)
            pcrMedia = media
        }
        lastPcr = pcrMs
        lastTick = now
        lastPcrAt = now
        addEntry(newSegment ? segments[segments.count - 1] : offset)
    }

    private func arrival(_ now: Int64) -> Int64 { lastTick == 0 ? 0 : max(0, now - lastTick) }

    private func addEntry(_ offset: Int64) {
        let last = head < entries.count ? entries[entries.count - 1] : nil
        if newSegment || last == nil || media - last!.mediaMs >= Self.entryMs {
            entries.append(Entry(mediaMs: media, offset: max(offset, last?.offset ?? 0)))
            newSegment = false
        }
    }

    private func removeFirstEntry() {
        head += 1
        if head == entries.count {
            entries.removeAll(keepingCapacity: true)
            head = 0
        } else if head >= 4_096, head * 2 >= entries.count {
            entries.removeSubrange(0..<head)
            head = 0
        }
    }

    /// Tira do índice o que já foi reescrito, passou do tempo máximo ou é do programa anterior.
    private func trim() {
        while entries.count - head > 1, entries[head].mediaMs < floorMs { removeFirstEntry() }
        while head < entries.count, entries[head].offset < clobbered || media - entries[head].mediaMs > maxAgeMs {
            removeFirstEntry()
        }
        let floor = head < entries.count ? entries[head].offset : clobbered
        while segments.count > 1, segments[1] <= floor {
            segments.removeFirst()
            removed += 1
        }
    }

    /// Ao fim do primeiro minuto já se sabe o débito do canal: o anel fica com o tamanho para [maxAgeMs]
    /// (com folga), para um canal leve não ocupar o máximo do disco. Só antes de a escrita dar a volta.
    private func fitToAge() {
        guard !fitted, media >= Self.fitAfterMs else { return }
        fitted = true
        let wanted = max(Self.minCapacity, Int64(Double(written) / Double(media) * Double(maxAgeMs) * Self.fitMargin))
        if wanted < size, written <= wanted { size = wanted }
    }

    // MARK: Leitura (quem serve o vídeo ao player)

    /// O segmento que contém [pos] (o último que começa até lá).
    public func segment(at pos: Int64) -> Int {
        locked {
            var id = 0
            for (i, start) in segments.enumerated() where start <= pos { id = i }
            return id + removed
        }
    }

    /// Onde começa o segmento [id], ou nil se já saiu do anel.
    public func segmentStart(_ id: Int) -> Int64? {
        locked {
            let i = id - removed
            return segments.indices.contains(i) ? segments[i] : nil
        }
    }

    /// Lê a partir de [pos], dentro do segmento [segment], até `buffer.count` bytes. Se ainda não há nada para ler,
    /// espera até [waitMs]. Uma ligação que acabou com erro (e sem outra a seguir) lança esse erro quando se chega ao
    /// fim dela.
    public func read(at pos: Int64, segment: Int, into buffer: UnsafeMutableRawBufferPointer, waitMs: Int) throws -> Read {
        guard let base = buffer.baseAddress, !buffer.isEmpty else { return .bytes(0) }
        var n: Int64 = 0
        var cap: Int64 = 1
        lock.lock()
        var waited = false
        while true {
            if closed {
                lock.unlock()
                return .end
            }
            // O segmento do leitor já saiu do anel (passou do tempo máximo): é como se tivesse sido reescrito.
            if pos < clobbered || segment < removed {
                lock.unlock()
                return .overrun
            }
            let i = segment - removed + 1
            let next: Int64? = i < segments.count ? segments[i] : nil
            let available = (next ?? written) - pos
            if available > 0 {
                n = min(Int64(buffer.count), available)
                cap = size
                break
            }
            if next != nil {
                lock.unlock()
                return .boundary
            }
            if finished {
                let failure = self.failure
                lock.unlock()
                if let failure { throw failure }
                return .end
            }
            if waited {
                lock.unlock()
                return .wait
            }
            _ = lock.wait(until: Date(timeIntervalSinceNow: Double(waitMs) / 1000))
            waited = true
        }
        lock.unlock()

        // Fora do lock: a escrita não espera pela leitura.
        var p = pos % cap
        var done: Int64 = 0
        while done < n {
            let chunk = min(n - done, cap - p)
            let r: Int = Glue.locked(fileLock) {
                fd < 0 ? -1 : pread(fd, base + Int(done), Int(chunk), off_t(p))
            }
            if r < 0, errno == EINTR { continue }
            if r <= 0 {
                if isFinished { return .end }
                throw Failure.io(r < 0 ? errno : 0)
            }
            done += Int64(r)
            p += Int64(r)
            if p == cap { p = 0 }
        }
        // Se a escrita passou por cima enquanto se lia, o que se leu já não é o que lá estava.
        return locked { pos < clobbered ? .overrun : .bytes(Int(n)) }
    }

    /// O ponto do índice mais perto de [mediaMs] sem passar dele (ou o mais antigo).
    public func entry(atOrBefore mediaMs: Int64) -> Entry? {
        locked {
            guard head < entries.count else { return nil }
            // O índice cresce no tempo: o último com mediaMs <= pedido.
            var lo = head, hi = entries.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if entries[mid].mediaMs <= mediaMs { lo = mid + 1 } else { hi = mid }
            }
            return lo > head ? entries[lo - 1] : entries[head]
        }
    }

    /// O índice que ainda está no anel, do mais antigo ao direto (para cortar uma gravação, ver RecordingCut).
    public var index: [Entry] { locked { Array(entries[head...]) } }

    /// O primeiro ponto do índice com o tempo a partir de [mediaMs], ou nil se o vídeo ainda não chegou lá.
    public func entry(atOrAfter mediaMs: Int64) -> Entry? {
        locked {
            var lo = head, hi = entries.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if entries[mid].mediaMs < mediaMs { lo = mid + 1 } else { hi = mid }
            }
            return lo < entries.count ? entries[lo] : nil
        }
    }

    /// O primeiro ponto do índice a partir de [offset] (para reabrir no início de um segmento).
    public func entry(from offset: Int64) -> Entry? {
        locked {
            var lo = head, hi = entries.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if entries[mid].offset < offset { lo = mid + 1 } else { hi = mid }
            }
            return lo < entries.count ? entries[lo] : nil
        }
    }

    /// Tempo do vídeo em [offset] (o ponto do índice antes dele).
    public func media(at offset: Int64) -> Int64? {
        locked {
            var lo = head, hi = entries.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if entries[mid].offset <= offset { lo = mid + 1 } else { hi = mid }
            }
            return lo > head ? entries[lo - 1].mediaMs : nil
        }
    }

    /// Fecha e apaga o ficheiro; quem estiver à espera recebe `.end`.
    public func close() {
        let first: Bool = locked {
            guard !closed else { return false }
            closed = true
            lock.broadcast()
            return true
        }
        guard first else { return }
        Glue.locked(fileLock) {
            if fd >= 0 { _ = Glue.close(fd) }
            fd = -1
        }
        unlink(path)
    }
}

/// As chamadas do sistema com o mesmo nome que métodos (close) ou que mudam entre o Darwin e o Linux.
enum Glue {
    static func close(_ fd: Int32) -> Int32 {
        #if canImport(Darwin)
        return Darwin.close(fd)
        #else
        return Glibc.close(fd)
        #endif
    }

    /// [body] com [lock] fechado.
    @discardableResult
    static func locked<T>(_ lock: NSLock, _ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}
