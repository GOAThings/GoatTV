import Foundation
import GoatCore
import Observation

/// O que o PlayerEngine tem do canal a dar para "Testar este canal": os números do GoatCore, sem abrir nenhuma ligação.
struct ChannelProbeSnapshot {
    var counters: ProbeCounters
    /// Os bytes de todas as ligações (as que caíram e a atual), e quantas vezes se abriu o canal.
    var totalBytes: Int64
    var opens: Int
    /// O servidor do canal (o do anel da pausa do direto é local: aí vale o do endereço do canal).
    var host: String
    var videoCodec: String
    var details: VideoDetails?
    var audio: [String]
    var showing: Bool
    /// O GoatCore recusou um formato que não toca (vídeo MPEG-2 sem descodificador, por exemplo).
    var unsupported: Bool
}

/// "Testar este canal": observa a reprodução que já está a dar durante [window] segundos e lê as contas do GoatCore
/// (bytes, primeiro byte, primeira imagem, resposta HTTP). Não abre outra ligação: os fornecedores só deixam uma.
/// As regras do veredicto são as do núcleo partilhado (StreamProbeRules). Igual no Apple TV e no iPhone.
@MainActor
@Observable
final class ChannelProbe {
    enum Phase {
        case idle
        /// Os segundos já observados.
        case running(Int)
        case done(StreamProbeRules.Report)
        /// O motor a tocar não deixa medir (o VLC, o da Apple, o experimental).
        case unsupported
    }

    static let window = 10

    private(set) var phase: Phase = .idle
    @ObservationIgnored private var task: Task<Void, Never>?

    var isRunning: Bool {
        if case .running = phase { return true }
        return false
    }

    func clear() {
        task?.cancel()
        task = nil
        phase = .idle
    }

    /// [interlacedWord] e [hdrWord]: as palavras da linha do formato, já traduzidas.
    func start(_ engine: PlayerEngine, interlacedWord: String, hdrWord: String) {
        guard !isRunning else { return }
        guard let first = engine.probeSnapshot() else {
            phase = .unsupported
            return
        }
        phase = .running(0)
        task = Task {
            let t0 = ProbeCounters.nowMs()
            for second in 1...Self.window {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                phase = .running(second)
            }
            guard let last = engine.probeSnapshot() else {
                phase = .unsupported
                return
            }
            phase = .done(Self.report(first: first, last: last, windowMs: ProbeCounters.nowMs() - t0,
                                      interlacedWord: interlacedWord, hdrWord: hdrWord))
        }
    }

    /// As contas da janela: o que mudou entre o princípio e o fim.
    private static func report(first: ChannelProbeSnapshot, last: ChannelProbeSnapshot, windowMs: Int64,
                               interlacedWord: String, hdrWord: String) -> StreamProbeRules.Report {
        var m = StreamProbeRules.Measure()
        let c = last.counters
        m.host = last.host
        m.status = c.status
        m.redirects = c.status < 0 ? -1 : c.redirects
        m.firstByteMs = c.firstByteMs
        m.firstPictureMs = c.firstPictureMs
        m.container = c.hls ? "HLS" : "TS"
        m.videoCodec = readable(last.videoCodec)
        if let d = last.details {
            m.width = d.width
            m.height = d.height
            m.fps = Float(d.fps)
            m.interlaced = d.interlaced
            m.hdr = d.hdr10 || d.hlg
        }
        m.audio = last.audio.joined(separator: ", ")
        let bytes = last.totalBytes - first.totalBytes
        m.windowMs = windowMs
        m.measuredKbps = windowMs > 0 && bytes > 0 ? Int(bytes * 8 / windowMs) : -1
        // O GoatCore da Apple não conta imagens deitadas fora (a camada da Apple mostra o que lhe chega): não se sabe.
        m.dropped = -1
        m.rendered = -1
        // Cada vez que o canal voltou a abrir na janela é uma ligação que caiu.
        m.reconnects = max(0, last.opens - first.opens)
        m.cannotDecode = last.unsupported ? (m.videoCodec.isEmpty ? "video" : m.videoCodec) : ""
        m.showing = last.showing
        return StreamProbeRules.report(m, interlacedWord: interlacedWord, hdrWord: hdrWord)
    }

    /// O nome do formato como o núcleo partilhado o dá ("H264", "AAC_ADTS"…), por extenso.
    static func readable(_ codec: String) -> String {
        switch codec {
        case "H264": return "H.264"
        case "HEVC": return "HEVC"
        case "MPEG2_VIDEO": return "MPEG-2"
        case "AAC_ADTS", "AAC_LATM": return "AAC"
        case "AC3": return "AC-3"
        case "EAC3": return "E-AC-3"
        case "MPEG_AUDIO": return "MP2"
        default: return codec
        }
    }
}

/// Os textos dos painéis, nas línguas da app (as chaves probe_* do Android): os mesmos no Apple TV e no iPhone.
extension ChannelProbe {
    struct Lines {
        /// A frase do veredicto ("Provider OK", "Provider slow (first byte 4.3 s)"…).
        let verdict: String
        let good: Bool
        /// (título, valor), sem endereços: só o nome do servidor.
        let rows: [(String, String)]
    }

    static func lines(_ r: StreamProbeRules.Report, _ s: Strings) -> Lines {
        let verdict: String
        switch r.verdict {
        case .ok: verdict = s.r("probe_v_ok")
        case .slow: verdict = s.r("probe_v_slow", r.arg)
        case .refused: verdict = s.r("probe_v_refused", r.arg)
        case .drops: verdict = s.r("probe_v_drops")
        case .cannotDecode: verdict = s.r("probe_v_decode", r.arg)
        case .noPicture: verdict = s.r("probe_v_nopicture")
        }
        let status = r.status < 0 ? "—" : s.r("probe_http", "\(r.status)") + (r.redirected ? " · " + s.r("probe_redirected") : "")
        let answer = [status, r.host.isEmpty ? nil : r.host].compactMap { $0 }.joined(separator: " · ")
        func secs(_ v: String?) -> String { v.map { s.r("probe_seconds", $0) } ?? "—" }
        var rows: [(String, String)] = [
            (s.r("probe_answer"), answer),
            (s.r("probe_first_byte"), secs(r.firstByte)),
            (s.r("probe_first_picture"), secs(r.firstPicture)),
            (s.r("probe_format"), r.format),
        ]
        if !r.audio.isEmpty { rows.append((s.r("probe_audio"), r.audio)) }
        rows.append((s.r("probe_bitrate"), r.bitrate))
        rows.append((s.r("probe_dropped"), r.dropped < 0 ? "—" : "\(r.dropped)"))
        rows.append((s.r("probe_reconnects"), "\(r.reconnects)"))
        return Lines(verdict: verdict, good: r.verdict == .ok, rows: rows)
    }
}
