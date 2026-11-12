import AVFoundation
import AVKit
import GoatCore
import UIKit

/// A vista do motor GoatCore: a própria camada é o AVSampleBufferDisplayLayer (que descodifica pelo hardware e é a
/// que o Picture in Picture usa).
final class GoatCoreView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }

    var displayLayer: AVSampleBufferDisplayLayer { layer as! AVSampleBufferDisplayLayer }
}

/// O motor próprio (experimental, Definições → Player → GoatCore): o stream lê-se aqui, separa-se no núcleo partilhado
/// (MediaPipeline) e as amostras vão para as camadas da Apple, que descodificam pelo hardware. Por ser uma camada de
/// amostras, o Picture in Picture funciona em todos os canais, .ts incluídos.
///
/// Como o AetherPlayback: quem decide o que abrir, as tentativas, a reconexão e a pausa do direto é o PlayerEngine;
/// aqui só se abre, se fecha e se conta o que acontece. Um formato que ainda não toca (vídeo MPEG-2) é uma falha, e o
/// PlayerEngine passa ao VLC.
@MainActor
final class GoatCorePlayback: NSObject {
    enum Event: Equatable {
        /// Primeira imagem (ou, num canal só de som, o primeiro som).
        case playing
        /// Não abriu, ou caiu.
        case failed
        /// O stream acabou.
        case ended
    }

    let view = GoatCoreView()
    var onEvent: ((Event) -> Void)?
    /// O código HTTP com que o fornecedor recusou o stream, quando se sabe.
    private(set) var httpStatus: Int?
    /// Porque falhou (para o registo).
    private(set) var failure: String?
    /// A altura do vídeo e a cadência, quando já se sabem.
    private(set) var videoHeight = 0
    private(set) var tracks: [MediaTrack] = []

    private let synchronizer = AVSampleBufferRenderSynchronizer()
    private let audioRenderer = AVSampleBufferAudioRenderer()
    private var session: StreamSession?
    private var generation = 0
    private var reportedPlaying = false
    private var reportedEnd = false
    private var userRate: Float = 1
    private var pip: AVPictureInPictureController?
    /// O Picture in Picture pergunta de outra thread se está em pausa.
    nonisolated(unsafe) private var pausedForPip = false

    override init() {
        super.init()
        view.backgroundColor = .black
        view.displayLayer.videoGravity = .resizeAspect
        synchronizer.addRenderer(view.displayLayer)
        synchronizer.addRenderer(audioRenderer)
    }

    /// Abre [url] com estes cabeçalhos (o User-Agent e o Referer da lista).
    func open(_ url: URL, headers: [String: String]) {
        stop()
        generation += 1
        let g = generation
        reportedPlaying = false
        reportedEnd = false
        httpStatus = nil
        failure = nil
        videoHeight = 0
        tracks = []
        let s = StreamSession(display: view.displayLayer, audio: audioRenderer, synchronizer: synchronizer)
        s.rate = userRate
        selectedAudio = nil
        s.onEvent = { [weak self] event in
            Task { @MainActor in self?.sessionEvent(event, generation: g) }
        }
        session = s
        s.start(url, headers: headers)
    }

    func stop() {
        generation += 1
        session?.cancel()
        session = nil
        synchronizer.rate = 0
        view.displayLayer.flushAndRemoveImage()
        audioRenderer.flush()
    }

    private func sessionEvent(_ event: StreamSession.Event, generation g: Int) {
        guard g == generation else { return }
        switch event {
        case .tracks(let list):
            tracks = list
        case .videoSize(let height):
            videoHeight = height
        case .started:
            guard !reportedPlaying else { return }
            reportedPlaying = true
            onEvent?(.playing)
        case .httpStatus(let code):
            httpStatus = code
        case .failed(let why):
            guard !reportedEnd else { return }
            reportedEnd = true
            failure = why
            DebugLog.add("Player: GoatCore failed: \(why)")
            onEvent?(.failed)
        case .ended:
            guard !reportedEnd else { return }
            reportedEnd = true
            onEvent?(.ended)
        }
    }

    // MARK: Controlo

    var paused: Bool { synchronizer.rate == 0 && reportedPlaying }

    func play() {
        pausedForPip = false
        session?.resume()
    }

    func pause() {
        pausedForPip = true
        session?.pause()
    }

    var rate: Float {
        get { userRate }
        set {
            userRate = newValue
            session?.rate = newValue
        }
    }

    var isMuted: Bool {
        get { audioRenderer.isMuted }
        set { audioRenderer.isMuted = newValue }
    }

    func setAspect(_ aspect: String) {
        switch aspect {
        case "zoom": view.displayLayer.videoGravity = .resizeAspectFill
        case "fill": view.displayLayer.videoGravity = .resize
        default: view.displayLayer.videoGravity = .resizeAspect
        }
    }

    /// O tempo do vídeo (s) no relógio do canal.
    var positionSeconds: Double { synchronizer.currentTime().seconds }

    // MARK: Faixas

    /// As faixas de áudio do canal, pela ordem do PMT.
    var audioTracks: [MediaTrack] { tracks.filter { $0.kind == .audio } }

    /// A faixa de áudio a tocar (o PID); nil = a primeira.
    private(set) var selectedAudio: Int?

    func selectAudio(pid: Int) {
        selectedAudio = pid
        session?.selectAudio(pid)
    }

    // MARK: Picture in Picture

    /// O Picture in Picture deste motor (o sistema pode não o ter, por exemplo num simulador sem ele).
    var pictureInPicture: AVPictureInPictureController? {
        if let pip { return pip }
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return nil }
        let source = AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer: view.displayLayer, playbackDelegate: self)
        let controller = AVPictureInPictureController(contentSource: source)
        #if os(iOS)
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        #endif
        pip = controller
        return controller
    }
}

extension GoatCorePlayback: AVPictureInPictureSampleBufferPlaybackDelegate {
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, setPlaying playing: Bool) {
        Task { @MainActor in playing ? self.play() : self.pause() }
    }

    /// Um direto: sem princípio nem fim (o sistema não mostra a barra do tempo).
    nonisolated func pictureInPictureControllerTimeRangeForPlayback(_ controller: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
    }

    nonisolated func pictureInPictureControllerIsPlaybackPaused(_ controller: AVPictureInPictureController) -> Bool {
        pausedForPip
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, skipByInterval skipInterval: CMTime,
                                                completion completionHandler: @escaping () -> Void) {
        completionHandler()
    }
}

/// Uma ligação ao stream e o que se faz com ela, numa fila própria: o pedido HTTP, o pipeline do núcleo partilhado e o
/// arranque do relógio (o vídeo começa na primeira imagem-chave; o som acerta por ela).
private final class StreamSession: NSObject, URLSessionDataDelegate {
    enum Event {
        case tracks([MediaTrack])
        case videoSize(Int)
        case started
        case httpStatus(Int)
        case failed(String)
        case ended
    }

    var onEvent: ((Event) -> Void)?
    /// A velocidade pedida (o catch-up pode andar mais depressa); só se aplica depois de arrancar.
    var rate: Float = 1 {
        didSet { queue.async { if self.running { self.synchronizer.setRate(self.rate, time: self.synchronizer.currentTime()) } } }
    }

    private let display: AVSampleBufferDisplayLayer
    private let audio: AVSampleBufferAudioRenderer
    private let synchronizer: AVSampleBufferRenderSynchronizer
    private let queue = DispatchQueue(label: "cc.slokker.goattv.goatcore", qos: .userInteractive)
    private let pipeline = MediaPipeline()
    private var urlSession: URLSession?
    private var cancelled = false
    private var hasAudio = false
    private var hasVideo = false
    /// O primeiro tempo do vídeo (ou do som, sem vídeo) e o último que já está no leitor.
    private var startTime: CMTime?
    private var lastVideo: CMTime = .invalid
    private var lastAudio: CMTime = .invalid
    private var running = false
    private var userPaused = false
    private var stalled = false
    private var watch: DispatchSourceTimer?
    private var reportedSize = false

    init(display: AVSampleBufferDisplayLayer, audio: AVSampleBufferAudioRenderer, synchronizer: AVSampleBufferRenderSynchronizer) {
        self.display = display
        self.audio = audio
        self.synchronizer = synchronizer
        super.init()
        pipeline.onTracks = { [weak self] tracks in
            guard let self else { return }
            self.hasVideo = tracks.contains { $0.kind == .video }
            self.hasAudio = tracks.contains { $0.kind == .audio }
            self.onEvent?(.tracks(tracks))
        }
        pipeline.onVideo = { [weak self] b in self?.video(b) }
        pipeline.onAudio = { [weak self] b in self?.audioSample(b) }
        pipeline.onUnsupported = { [weak self] what in self?.fail("unsupported \(what)") }
        pipeline.onJump = { [weak self] in self?.restartClock() }
    }

    func start(_ url: URL, headers: [String: String]) {
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        config.httpMaximumConnectionsPerHost = 1
        let delegateQueue = OperationQueue()
        delegateQueue.underlyingQueue = queue
        delegateQueue.maxConcurrentOperationCount = 1
        let s = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
        urlSession = s
        var request = URLRequest(url: url)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        s.dataTask(with: request).resume()
        queue.async { self.startWatch() }
    }

    func cancel() {
        queue.async {
            self.cancelled = true
            self.watch?.cancel()
            self.urlSession?.invalidateAndCancel()
            self.urlSession = nil
        }
    }

    /// Outra faixa de áudio: o som que já estava no leitor sai, e o novo entra a partir de agora.
    func selectAudio(_ pid: Int) {
        queue.async {
            self.pipeline.audioPid = pid
            self.audio.flush()
            self.lastAudio = .invalid
        }
    }

    func pause() {
        queue.async {
            self.userPaused = true
            if self.running { self.synchronizer.rate = 0 }
        }
    }

    func resume() {
        queue.async {
            self.userPaused = false
            if self.running, !self.stalled { self.synchronizer.rate = self.rate }
        }
    }

    // MARK: Rede

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            onEvent?(.httpStatus(http.statusCode))
            fail("HTTP \(http.statusCode)")
            completionHandler(.cancel)
            return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard !cancelled else { return }
        pipeline.feed(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !cancelled else { return }
        pipeline.flush()
        if let error {
            fail("network: \(error.localizedDescription)")
        } else {
            cancelled = true
            onEvent?(.ended)
        }
    }

    private func fail(_ why: String) {
        guard !cancelled else { return }
        cancelled = true
        watch?.cancel()
        urlSession?.invalidateAndCancel()
        onEvent?(.failed(why))
    }

    // MARK: Amostras e relógio

    private func video(_ b: CMSampleBuffer) {
        guard !cancelled else { return }
        if display.status == .failed {
            fail("video renderer: \(display.error?.localizedDescription ?? "?")")
            return
        }
        if !reportedSize, let f = CMSampleBufferGetFormatDescription(b) {
            reportedSize = true
            onEvent?(.videoSize(Int(CMVideoFormatDescriptionGetDimensions(f).height)))
        }
        display.enqueue(b)
        let pts = CMSampleBufferGetPresentationTimeStamp(b)
        if pts.isValid, !lastVideo.isValid || pts > lastVideo { lastVideo = pts }
        if startTime == nil, pts.isValid { begin(at: pts) }
    }

    private func audioSample(_ b: CMSampleBuffer) {
        guard !cancelled else { return }
        if audio.status == .failed {
            fail("audio renderer: \(audio.error?.localizedDescription ?? "?")")
            return
        }
        audio.enqueue(b)
        let pts = CMSampleBufferGetPresentationTimeStamp(b)
        if pts.isValid, !lastAudio.isValid || pts > lastAudio { lastAudio = pts }
        // Um canal só de som (rádio): o relógio começa no primeiro som.
        if startTime == nil, !hasVideo, pts.isValid { begin(at: pts) }
    }

    /// O relógio começa no primeiro tempo que se pode mostrar: o vídeo na primeira imagem-chave (o pipeline só as
    /// manda depois de ter os parâmetros), o som anterior a ele o leitor salta.
    private func begin(at time: CMTime) {
        startTime = time
        running = true
        synchronizer.setRate(userPaused ? 0 : rate, time: time)
        onEvent?(.started)
    }

    /// O canal saltou no tempo: o que está no leitor não continua o que vem a seguir.
    private func restartClock() {
        display.flush()
        audio.flush()
        synchronizer.rate = 0
        startTime = nil
        running = false
        lastVideo = .invalid
        lastAudio = .invalid
    }

    /// Se a rede não acompanha, o relógio para à espera (em vez de mostrar imagens paradas com o som aos saltos) e
    /// volta quando há meio segundo à frente.
    private func startWatch() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.25, repeating: 0.25)
        t.setEventHandler { [weak self] in self?.checkBuffer() }
        t.resume()
        watch = t
    }

    private func checkBuffer() {
        guard running, !cancelled, !userPaused else { return }
        let now = synchronizer.currentTime()
        let ahead = hasVideo ? lastVideo : lastAudio
        guard ahead.isValid, now.isValid else { return }
        let lead = (ahead - now).seconds
        if !stalled, lead < 0.05 {
            stalled = true
            synchronizer.rate = 0
        } else if stalled, lead > 0.5 {
            stalled = false
            synchronizer.setRate(rate, time: now)
        }
    }
}
