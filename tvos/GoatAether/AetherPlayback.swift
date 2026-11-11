internal import AetherEngine
import AVFoundation
import Combine
import Foundation
import UIKit

/// O AetherEngine (experimental, Definições → Player) atrás de uma interface pequena, só com tipos da Apple.
///
/// Vive numa framework à parte (GoatAether) porque o VLC traz o seu próprio FFmpeg ligado estaticamente na app: se o
/// motor fosse ligado na app, as chamadas `avcodec_*` dele iam parar ao FFmpeg do VLC (ver docs/api.md do
/// AetherEngine, "Holding a second player beside the engine"). Aqui, ligado contra as AetherLib*.framework, cada um
/// fica com o seu.
///
/// Quem decide o que abrir, as tentativas, a reconexão e a pausa do direto continua a ser o PlayerEngine; aqui só se
/// abre, se fecha e se conta o que acontece.
@MainActor
public final class AetherPlayback {
    public enum Event: Equatable {
        /// Primeira imagem no ecrã.
        case playing
        /// Não abriu, ou caiu (inclui o pedido de "sintonizar outra vez" do direto).
        case failed
        /// Chegou ao fim (um filme, ou um direto que acabou).
        case ended
    }

    public struct Track: Equatable {
        public let id: Int
        public let name: String
        public let language: String?
        public let channels: Int
        public let forced: Bool
        public let selected: Bool
    }

    /// O que abrir e como.
    public struct Request {
        public var url: URL
        public var live: Bool
        /// Janela da pausa do direto (s); nil = só direto.
        public var dvrSeconds: Double?
        /// Onde começar (s), no catch-up do Stalker.
        public var startSeconds: Double?
        public var headers: [String: String] = [:]
        public var audioLanguages: [String] = []
        public var subtitleLanguages: [String] = []
        public var audioDelaySeconds: Double = 0
        /// Desentrelaçar a 50/60 imagens por segundo ("yadif2x") em vez de 25/30.
        public var fieldRate = true
        /// Descodificar em software.
        public var software = false
        /// Um só pedido de cada vez ao fornecedor (o leitor principal).
        public var singleConnection = true
        /// O segundo canal: não mexe no modo da TV nem no "A reproduzir".
        public var secondary = false
        /// O motor muda a TV para a frame rate e o HDR do vídeo.
        public var displayCriteria = true
        /// "Igualar conteúdo" ligado no Apple TV.
        public var matchContent = true
        /// A faixa de áudio a abrir (id do motor), para mudar de faixa num direto.
        public var audioStreamIndex: Int?

        public init(url: URL, live: Bool) {
            self.url = url
            self.live = live
        }
    }

    /// A vista do vídeo, com as legendas por cima.
    public let view: UIView
    public var onEvent: ((Event) -> Void)?
    /// O código HTTP com que o fornecedor recusou o stream, quando se sabe.
    public private(set) var httpStatus: Int?

    private var engine: AetherEngine?
    private let surface = AetherPlayerView(frame: .zero)
    private let subtitleLabel: UILabel = {
        let l = UILabel()
        l.numberOfLines = 0
        l.textAlignment = .center
        l.textColor = .white
        l.shadowColor = .black
        l.shadowOffset = CGSize(width: 1, height: 1)
        l.isHidden = true
        return l
    }()
    private let subtitleImage: UIImageView = {
        let v = UIImageView()
        v.contentMode = .scaleAspectFit
        v.isHidden = true
        return v
    }()
    private var bag = Set<AnyCancellable>()
    private var loadTask: Task<Void, Never>?
    private var trackTask: Task<Void, Never>?
    private var request: Request?
    /// Há um pedido aberto (o que chega depois de [stop] já não conta).
    private var active = false
    /// Já se disse "a dar" ou "falhou" neste pedido.
    private var reportedPlaying = false
    private var reportedFailure = false
    private var muted = false
    private var gravity: AVLayerVideoGravity = .resizeAspect

    public init() {
        let container = UIView()
        container.backgroundColor = .black
        view = container
        for v in [surface, subtitleImage] as [UIView] {
            v.frame = container.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(v)
        }
        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(subtitleLabel)
        NSLayoutConstraint.activate([
            subtitleLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 60),
            subtitleLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -60),
            subtitleLabel.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -50),
        ])
    }

    /// O motor só se cria quando se escolhe o AetherEngine: com os outros, nada disto corre.
    private func makeEngine() -> AetherEngine? {
        if let engine { return engine }
        guard let e = try? AetherEngine() else { return nil }
        e.bind(view: surface)
        e.videoGravity = gravity
        e.volume = muted ? 0 : 1
        e.$state.receive(on: DispatchQueue.main).sink { [weak self] state in self?.stateChanged(state) }.store(in: &bag)
        e.$hasFirstFrameReadyForDisplay.receive(on: DispatchQueue.main).sink { [weak self] ready in
            if ready { self?.firstFrame() }
        }.store(in: &bag)
        // O direto parou e só um pedido novo o recupera: é o PlayerEngine que volta a ligar.
        e.liveSourceReset.receive(on: DispatchQueue.main).sink { [weak self] in self?.failed() }.store(in: &bag)
        e.clock.$sourceTime.combineLatest(e.$subtitleCues).receive(on: DispatchQueue.main).sink { [weak self] time, cues in
            self?.showSubtitle(at: time, cues: cues)
        }.store(in: &bag)
        engine = e
        return e
    }

    // MARK: Abrir e fechar

    /// Abre [request]. O pedido anterior fecha primeiro (uma ligação de cada vez).
    public func open(_ request: Request) {
        stop()
        guard let engine = makeEngine() else {
            active = true
            failed()
            return
        }
        self.request = request
        active = true
        var o = LoadOptions()
        o.isLive = request.live
        if request.live {
            // Para passar canais depressa: o direto é MPEG-TS ou HLS, que se analisam em pouco (o motor lia até 50 MB
            // e 60 s antes de abrir), e começa-se com um só segmento de pelo menos 1 s em vez de esperar pelo segundo.
            o.probesize = 2_000_000
            o.maxAnalyzeDuration = 2_000_000
            o.liveStartupSingleSegmentMinimumSeconds = 1
            // E sem a espera extra (0,5 a 2 s) depois de já haver o mínimo: mostra assim que pode.
            o.liveStartupGraceSeconds = 0
        }
        o.dvrWindowSeconds = request.dvrSeconds
        o.httpHeaders = request.headers
        if request.singleConnection { o.maxConcurrentSourceRequests = 1 }
        // Sem miniaturas ao percorrer: era mais um leitor a pedir ao fornecedor.
        o.generatesBackgroundScrubPreviews = false
        o.preferredAudioLanguages = request.audioLanguages
        o.preferredSubtitleLanguages = request.subtitleLanguages
        o.audioDelaySeconds = request.audioDelaySeconds
        o.deinterlaceFieldRate = request.fieldRate ? .field : .frame
        if request.software { o.preferredDecodePath = .software }
        if request.secondary { o.sharedOutputRole = .secondary }
        if !request.displayCriteria { o.displayCriteriaOwner = .host }
        o.matchContentEnabled = request.matchContent
        let start = request.startSeconds.flatMap { $0 > 0 ? $0 : nil }
        let audio = request.audioStreamIndex.map { Int32($0) }
        loadTask = Task { [weak self] in
            do {
                _ = try await engine.load(url: request.url, startPosition: start, options: o, audioSourceStreamIndex: audio)
            } catch is CancellationError {
                // Um pedido novo (ou um stop) tomou o lugar deste: não é uma falha.
            } catch {
                self?.failed()
            }
        }
    }

    /// Fecha o stream (e a ligação ao fornecedor).
    public func stop() {
        loadTask?.cancel()
        trackTask?.cancel()
        active = false
        reportedPlaying = false
        reportedFailure = false
        httpStatus = nil
        request = nil
        subtitleLabel.isHidden = true
        subtitleImage.isHidden = true
        engine?.stop()
    }

    private func stateChanged(_ state: PlaybackState) {
        guard active else { return }
        switch state {
        case .error:
            failed()
        case .ended:
            onEvent?(.ended)
        case .playing:
            if engine?.hasFirstFrameReadyForDisplay == true { firstFrame() }
        default:
            break
        }
    }

    private func firstFrame() {
        guard active, !reportedPlaying, !reportedFailure else { return }
        reportedPlaying = true
        if rate != 1 { engine?.setRate(rate) }
        onEvent?(.playing)
    }

    private func failed() {
        guard active, !reportedFailure else { return }
        reportedFailure = true
        // Recusado pelo fornecedor: o código HTTP vem no erro (sem domínio por baixo).
        if let info = engine?.errorInfo, info.kind == .sourceRefused || info.kind == .sourceRateLimited, info.underlyingDomain == nil {
            httpStatus = info.underlyingCode
        }
        onEvent?(.failed)
    }

    // MARK: Controlo

    public var paused: Bool { engine?.state == .paused }

    public func play() { engine?.play() }
    public func pause() { engine?.pause() }

    /// A velocidade (catch-up e atrás do direto); 1 = normal.
    /// O motor volta a 1 em cada pedido novo: repõe-se quando começa a dar.
    public var rate: Float = 1 {
        didSet { engine?.setRate(rate) }
    }

    public var isMuted: Bool {
        get { muted }
        set {
            muted = newValue
            engine?.volume = newValue ? 0 : 1
        }
    }

    /// "fit", "zoom" (corta) ou "fill" (estica).
    public func setAspect(_ aspect: String) {
        switch aspect {
        case "zoom": gravity = .resizeAspectFill
        case "fill": gravity = .resize
        default: gravity = .resizeAspect
        }
        engine?.videoGravity = gravity
    }

    /// Onde vai (s, no eixo do motor).
    public var positionSeconds: Double { engine?.currentTime ?? 0 }

    /// A dar normalmente (nem a carregar, nem a saltar, nem à espera de rede).
    public var settled: Bool {
        guard let engine else { return false }
        return engine.state == .playing && !engine.isSeeking && !engine.isBuffering
    }

    /// A janela da pausa do direto: o mais antigo, o direto e onde vai (ms, no eixo do motor); nil sem ela.
    public var liveWindow: (oldest: Int64, end: Int64, position: Int64)? {
        guard let engine, engine.isLive, let range = engine.clock.seekableLiveRange else { return nil }
        let at = engine.currentTime
        guard range.lowerBound.isFinite, range.upperBound.isFinite, at.isFinite else { return nil }
        return (Int64(range.lowerBound * 1000), Int64(range.upperBound * 1000), Int64(at * 1000))
    }

    /// Salta para [ms] (no eixo do motor) e continua a dar.
    public func seek(toMs ms: Int64, done: @escaping () -> Void) {
        guard let engine else { return done() }
        Task {
            await engine.seek(to: Double(max(0, ms)) / 1000)
            engine.play()
            done()
        }
    }

    // MARK: Informação

    public var videoHeight: Int { Int(engine?.sourceVideoHeight ?? 0) }

    public var frameRate: Double? { engine?.sourceVideoFrameRate }

    /// Linhas da "Informação do stream" (título, valor).
    public var details: [(String, String)] {
        guard let engine else { return [] }
        var rows: [(String, String)] = []
        var video: [String] = []
        if let codec = engine.sourceVideoCodecName { video.append(codec.uppercased()) }
        if engine.sourceVideoWidth > 0 { video.append("\(engine.sourceVideoWidth)×\(engine.sourceVideoHeight)") }
        if let fps = engine.sourceVideoFrameRate, fps > 0 { video.append(String(format: "%.2f fps", fps)) }
        if !video.isEmpty { rows.append(("video", video.joined(separator: " · "))) }
        if let track = engine.audioTracks.first(where: { $0.id == engine.activeAudioTrackIndex }) {
            var audio = [track.codec.uppercased()]
            if track.channels > 0 { audio.append(track.channels == 6 ? "5.1" : (track.channels == 8 ? "7.1" : "\(track.channels) ch")) }
            if track.sampleRate > 0 { audio.append("\(track.sampleRate / 1000) kHz") }
            rows.append(("audio", audio.joined(separator: " · ")))
        }
        if engine.sourceVideoBitrate > 0 { rows.append(("bitrate", String(format: "%.1f Mb/s", Double(engine.sourceVideoBitrate) / 1_000_000))) }
        return rows
    }

    /// O caminho por onde o vídeo vai (para a informação do stream e o registo).
    public var route: String {
        guard let engine else { return "" }
        return "\(engine.videoRoute)"
    }

    // MARK: Faixas

    public var audioTracks: [Track] {
        guard let engine else { return [] }
        return engine.audioTracks.map {
            Track(id: $0.id, name: $0.name, language: $0.language, channels: $0.channels, forced: false,
                  selected: $0.id == engine.activeAudioTrackIndex)
        }
    }

    public var subtitleTracks: [Track] {
        guard let engine else { return [] }
        return engine.subtitleTracks.map {
            Track(id: $0.id, name: $0.name, language: $0.language, channels: 0, forced: $0.isForced,
                  selected: engine.isSubtitleActive && $0.id == engine.activeSubtitleTrackIndex)
        }
    }

    /// Muda a faixa de áudio. No direto o motor pode não conseguir mudar no sítio: abre-se outra vez com ela.
    public func selectAudio(_ id: Int) {
        guard let engine, engine.audioTracks.contains(where: { $0.id == id }), engine.activeAudioTrackIndex != id else { return }
        engine.selectAudioTrack(index: id)
        guard engine.isLive, var again = request else { return }
        trackTask?.cancel()
        trackTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self, let engine = self.engine, engine.activeAudioTrackIndex != id else { return }
            again.audioStreamIndex = id
            self.open(again)
        }
    }

    /// Muda as legendas (id < 0 = desligadas).
    public func selectSubtitle(_ id: Int) {
        guard let engine else { return }
        if id < 0 {
            engine.clearSubtitle()
        } else {
            engine.selectSubtitleTrack(index: id)
        }
    }

    // MARK: Legendas

    /// O motor dá as legendas; quem as desenha é a app (texto em baixo, imagens no sítio delas).
    private func showSubtitle(at time: Double, cues: [SubtitleCue]) {
        let now = cues.filter { $0.startTime <= time && time < $0.endTime }
        let text = now.compactMap(\.text).joined(separator: "\n")
        let image = now.lazy.compactMap { cue -> SubtitleImage? in
            if case .image(let image) = cue.body { return image }
            return nil
        }.first
        if subtitleLabel.text != text { subtitleLabel.text = text }
        subtitleLabel.font = .systemFont(ofSize: max(24, view.bounds.height / 22), weight: .semibold)
        subtitleLabel.isHidden = text.isEmpty
        if let image {
            // A posição vem em [0, 1] do vídeo: aqui, sobre a vista toda (o vídeo "ajustado" a 16:9 é quase sempre ela).
            let b = view.bounds
            subtitleImage.frame = CGRect(x: image.position.minX * b.width, y: image.position.minY * b.height,
                                         width: image.position.width * b.width, height: image.position.height * b.height)
            subtitleImage.image = UIImage(cgImage: image.cgImage)
            subtitleImage.isHidden = false
        } else if !subtitleImage.isHidden {
            subtitleImage.isHidden = true
            subtitleImage.image = nil
        }
    }
}
