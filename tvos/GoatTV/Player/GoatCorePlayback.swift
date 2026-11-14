import AVFoundation
import AVKit
import GoatCore
import UIKit
import os

/// A vista do motor GoatCore, com o AVSampleBufferDisplayLayer do canal a dar (que descodifica pelo hardware e é o que o
/// Picture in Picture usa). Cada canal tem uma camada nova: o que ainda chegue do anterior não a estraga.
final class GoatCoreView: UIView {
    private(set) var displayLayer = AVSampleBufferDisplayLayer()
    var gravity: AVLayerVideoGravity = .resizeAspect {
        didSet { displayLayer.videoGravity = gravity }
    }

    /// As legendas (teletexto) por cima do vídeo, em baixo e ao centro, como numa TV.
    let subtitle: UILabel = {
        let l = UILabel()
        l.numberOfLines = 0
        l.textAlignment = .center
        l.textColor = .white
        l.backgroundColor = UIColor.black.withAlphaComponent(0.75)
        l.isHidden = true
        return l
    }()

    /// As imagens das legendas DVB, no ecrã do emissor (ver [showSubtitleImages]).
    private let imageLayer = UIView()
    private var images: SubtitleImages?
    /// O tamanho do vídeo (para pôr as imagens em cima dele, e não das barras pretas).
    var videoSize: CGSize = .zero {
        didSet { if videoSize != oldValue { setNeedsLayout() } }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.addSublayer(displayLayer)
        imageLayer.isUserInteractionEnabled = false
        addSubview(imageLayer)
        addSubview(subtitle)
    }

    /// Mostra as imagens das legendas DVB (nil ou vazio limpa).
    func showSubtitleImages(_ page: SubtitleImages?) {
        images = page
        imageLayer.subviews.forEach { $0.removeFromSuperview() }
        for i in page?.images ?? [] {
            guard let image = Self.cgImage(i) else { continue }
            let v = UIImageView(image: UIImage(cgImage: image))
            v.tag = imageLayer.subviews.count
            imageLayer.addSubview(v)
        }
        setNeedsLayout()
    }

    private static func cgImage(_ i: SubtitleImage) -> CGImage? {
        guard i.width > 0, i.height > 0, i.rgba.count >= i.width * i.height * 4,
              let provider = CGDataProvider(data: i.rgba as CFData) else { return nil }
        return CGImage(width: i.width, height: i.height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: i.width * 4,
                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    /// Onde fica a imagem do vídeo dentro da vista (com o ajuste escolhido).
    private var videoRect: CGRect {
        guard videoSize.width > 0, videoSize.height > 0 else { return bounds }
        switch gravity {
        case .resize: return bounds
        case .resizeAspectFill:
            let scale = max(bounds.width / videoSize.width, bounds.height / videoSize.height)
            let w = videoSize.width * scale, h = videoSize.height * scale
            return CGRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
        default: return AVMakeRect(aspectRatio: videoSize, insideRect: bounds)
        }
    }

    /// Mostra [text] nas legendas ("" esconde).
    func showSubtitle(_ text: String) {
        subtitle.text = text.isEmpty ? nil : text
        subtitle.isHidden = text.isEmpty
        setNeedsLayout()
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Troca a camada por uma nova (a anterior sai do ecrã com o que tinha).
    func freshLayer() -> AVSampleBufferDisplayLayer {
        let next = AVSampleBufferDisplayLayer()
        next.videoGravity = gravity
        next.frame = bounds
        layer.replaceSublayer(displayLayer, with: next)
        displayLayer = next
        return next
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.frame = bounds
        CATransaction.commit()
        // O tamanho da letra acompanha o do vídeo (no ecrã inteiro e no Picture in Picture da app).
        subtitle.font = .systemFont(ofSize: max(12, bounds.height / 22), weight: .medium)
        let maxWidth = bounds.width * 0.9
        let size = subtitle.sizeThatFits(CGSize(width: maxWidth, height: .greatestFiniteMagnitude))
        let width = min(maxWidth, size.width + 16)
        subtitle.frame = CGRect(x: (bounds.width - width) / 2, y: bounds.height * 0.92 - size.height, width: width, height: size.height)
        // As imagens DVB: o ecrã do emissor (720×576, ou o que ele disser) é a imagem do vídeo.
        imageLayer.frame = bounds
        if let page = images, page.displayWidth > 0, page.displayHeight > 0 {
            let rect = videoRect
            let sx = rect.width / CGFloat(page.displayWidth)
            let sy = rect.height / CGFloat(page.displayHeight)
            for (v, i) in zip(imageLayer.subviews, page.images.filter { Self.cgImage($0) != nil }) {
                v.frame = CGRect(x: rect.minX + CGFloat(i.x) * sx, y: rect.minY + CGFloat(i.y) * sy,
                                 width: CGFloat(i.width) * sx, height: CGFloat(i.height) * sy)
            }
        }
    }
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
    /// As legendas do teletexto do canal.
    private(set) var textTracks: [TextTrack] = []
    /// O vídeo pelo SPS (a cadência e o HDR para a TV mudar de modo); nil até se saber.
    private(set) var videoDetails: VideoDetails?
    /// As legendas escolhidas ([TextTrack.key]); nil = nenhumas.
    private(set) var selectedText: Int?
    /// O que mostrar nas legendas: texto (teletexto; "" limpa) ou imagens (DVB).
    private enum Subtitle {
        case text(String)
        case images(SubtitleImages)
    }

    /// As legendas à espera da sua hora (no relógio das camadas), pela ordem em que chegaram.
    private var pendingText: [(time: CMTime, subtitle: Subtitle)] = []
    /// As imagens no ecrã saem a esta hora (o tempo da página DVB), se antes não vierem outras.
    private var imagesExpire: CMTime?
    private var subtitleTimer: Timer?

    /// O relógio e o som do canal a dar (novos em cada canal, como a camada do vídeo).
    private var synchronizer = AVSampleBufferRenderSynchronizer()
    private var audioRenderer = AVSampleBufferAudioRenderer()
    private var muted = false
    private var session: StreamSession?
    private var generation = 0
    private var reportedPlaying = false
    private var reportedEnd = false
    private var userRate: Float = 1
    private var pip: AVPictureInPictureController?
    /// O canal está (ou vai ficar) no Picture in Picture: ao sair da app, o vídeo continua.
    private(set) var pipActive = false
    /// O PiP está a fechar porque se voltou à app (ver [onPipEnded]).
    private var restoring = false
    /// O Picture in Picture fechou (com a app em segundo plano, quem usa o leitor pára-o e liberta a ligação).
    var onPipEnded: (() -> Void)?
    /// O Picture in Picture pergunta de outra thread se está em pausa.
    nonisolated(unsafe) private var pausedForPip = false

    override init() {
        super.init()
        view.backgroundColor = .black
    }

    /// Abre [url] com estes cabeçalhos (o User-Agent e o Referer da lista). [startMs]: num TS gravado (as gravações,
    /// o catch-up do Stalker), o ponto onde começar (ver TsSeek).
    func open(_ url: URL, headers: [String: String], startMs: Int64 = 0, hls: Bool = false) {
        stop()
        generation += 1
        let g = generation
        reportedPlaying = false
        reportedEnd = false
        httpStatus = nil
        failure = nil
        videoHeight = 0
        tracks = []
        textTracks = []
        videoDetails = nil
        startTime = nil
        firstPts = nil
        offsetMs = 0
        clearSubtitles()
        // Tudo novo: a ligação anterior pode ainda entregar uma imagem antes de fechar, e ia parar ao leitor deste canal
        // (com outro relógio, a camada deixava de mostrar e só o som continuava).
        let display = view.freshLayer()
        synchronizer = AVSampleBufferRenderSynchronizer()
        audioRenderer = AVSampleBufferAudioRenderer()
        audioRenderer.isMuted = muted
        synchronizer.addRenderer(display)
        synchronizer.addRenderer(audioRenderer)
        pip = nil
        let s = StreamSession(display: display, audio: audioRenderer, synchronizer: synchronizer)
        s.rate = userRate
        selectedAudio = nil
        selectedText = nil
        s.onEvent = { [weak self] event in
            Task { @MainActor in self?.sessionEvent(event, generation: g) }
        }
        session = s
        s.start(url, headers: headers, startMs: startMs, hls: hls)
    }

    func stop() {
        generation += 1
        clearSubtitles()
        session?.cancel()
        session = nil
        synchronizer.rate = 0
        audioRenderer.flush()
    }

    private func sessionEvent(_ event: StreamSession.Event, generation g: Int) {
        guard g == generation else { return }
        switch event {
        case .tracks(let list):
            tracks = list
        case .videoSize(let width, let height):
            videoHeight = height
            view.videoSize = CGSize(width: width, height: height)
        case .started(let time):
            if startTime == nil { startTime = time }
            guard !reportedPlaying else { return }
            reportedPlaying = true
            onEvent?(.playing)
        case .httpStatus(let code):
            httpStatus = code
        case .origin(let pts):
            firstPts = pts
        case .offset(let ms):
            offsetMs = ms
        case .textTracks(let list):
            textTracks = list
        case .videoDetails(let details):
            videoDetails = details
        case .text(let time, let text):
            addSubtitle(time, .text(text))
        case .images(let time, let page):
            addSubtitle(time, .images(page))
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
        get { muted }
        set {
            muted = newValue
            audioRenderer.isMuted = newValue
        }
    }

    func setAspect(_ aspect: String) {
        switch aspect {
        case "zoom": view.gravity = .resizeAspectFill
        case "fill": view.gravity = .resize
        default: view.gravity = .resizeAspect
        }
    }

    /// O tempo do vídeo (s): num ficheiro aberto a meio (ver [open]), desde o início dele (como os outros motores);
    /// no resto, desde a primeira imagem deste stream (no catch-up, desde o ponto pedido).
    var positionSeconds: Double {
        if let firstPts, startTime != nil {
            let now = synchronizer.currentTime().convertScale(90_000, method: .roundTowardZero).value
            return Double(TsSeek.positionMs(pts: now, firstPts: firstPts)) / 1000
        }
        return Double(offsetMs + (playedMs ?? 0)) / 1000
    }

    /// Um HLS aberto a meio: onde começa o segmento por onde se começou (ms).
    private var offsetMs: Int64 = 0

    /// O PTS do início do ficheiro, quando se abriu a meio.
    private var firstPts: Int64?

    /// Quanto vídeo já se mostrou desde a primeira imagem (ms), pelo relógio das camadas: não anda em pausa nem
    /// parado à espera de dados, e os saltos do relógio do canal já vêm seguidos (TsClock). nil antes de começar.
    var playedMs: Int64? {
        guard let startTime else { return nil }
        let t = (synchronizer.currentTime() - startTime).seconds
        return t.isFinite ? max(0, Int64(t * 1000)) : nil
    }

    /// O tempo da primeira imagem (ou do primeiro som, sem vídeo) no relógio do canal.
    private var startTime: CMTime?

    // MARK: Faixas

    /// As faixas de áudio do canal, pela ordem do PMT.
    var audioTracks: [MediaTrack] { tracks.filter { $0.kind == .audio } }

    /// A faixa de áudio a tocar (o PID); nil = a primeira.
    private(set) var selectedAudio: Int?

    func selectAudio(pid: Int) {
        selectedAudio = pid
        session?.selectAudio(pid)
    }

    /// As legendas ([TextTrack.key]), ou nil para nenhumas: as que estavam no ecrã saem.
    func selectText(_ key: Int?) {
        selectedText = key
        clearSubtitles()
        session?.selectText(key)
    }

    private func clearSubtitles() {
        pendingText = []
        imagesExpire = nil
        view.showSubtitle("")
        view.showSubtitleImages(nil)
    }

    /// Põe as legendas cuja hora já chegou (a última ganha) e volta quando for a hora da seguinte.
    /// Uma legenda (ou o fim de uma) na fila, pela ordem do tempo: as WebVTT trazem o fim logo a seguir ao início, e a
    /// fala seguinte pode começar antes desse fim.
    private func addSubtitle(_ time: CMTime, _ subtitle: Subtitle) {
        let at = pendingText.firstIndex { $0.time > time } ?? pendingText.count
        pendingText.insert((time, subtitle), at: at)
        showDueSubtitles()
    }

    private func showDueSubtitles() {
        subtitleTimer?.invalidate()
        subtitleTimer = nil
        guard !pendingText.isEmpty || imagesExpire != nil else { return }
        let now = synchronizer.currentTime()
        guard now.isValid, startTime != nil else { return scheduleSubtitles(after: 0.1) }
        while let first = pendingText.first, first.time <= now {
            pendingText.removeFirst()
            switch first.subtitle {
            case .text(let text):
                view.showSubtitle(text)
            case .images(let page):
                view.showSubtitleImages(page)
                imagesExpire = page.timeoutSeconds > 0 && !page.images.isEmpty
                    ? first.time + CMTime(value: CMTimeValue(page.timeoutSeconds), timescale: 1) : nil
            }
        }
        if let expire = imagesExpire, expire <= now {
            imagesExpire = nil
            view.showSubtitleImages(nil)
        }
        let next = [pendingText.first?.time, imagesExpire].compactMap { $0 }.min()
        if let next {
            let wait = (next - now).seconds
            scheduleSubtitles(after: min(max(wait, 0.01), 0.25))
        }
    }

    private func scheduleSubtitles(after seconds: Double) {
        subtitleTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.showDueSubtitles() }
        }
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
        controller.delegate = self
        pip = controller
        return controller
    }
}

extension GoatCorePlayback: AVPictureInPictureControllerDelegate {
    // O sistema chama-as na thread principal: o estado muda já (ir para segundo plano logo a seguir tem de o ver).
    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { self.pipActive = true }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        Task { @MainActor in self.pipActive = false }
    }

    /// Tocar em "voltar à app": o canal continua, não é o PiP a fechar.
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in
            self.restoring = true
            completionHandler(true)
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor in
            self.pipActive = false
            let restored = self.restoring
            self.restoring = false
            if !restored { self.onPipEnded?() }
        }
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
        case videoSize(width: Int, height: Int)
        /// A primeira imagem, com o tempo dela (onde o relógio começou).
        case started(CMTime)
        /// Abriu-se um ficheiro a meio: o PTS do início dele (90 kHz), para a posição contar desde lá.
        case origin(Int64)
        /// Um HLS (VOD) aberto a meio: o tempo (ms) do segmento por onde começou.
        case offset(Int64)
        /// As legendas do teletexto do canal.
        case textTracks([TextTrack])
        /// O vídeo pelo SPS (cadência, HDR, a descrição do formato).
        case videoDetails(VideoDetails)
        /// As legendas escolhidas mudam a este tempo ("" limpa).
        case text(CMTime, String)
        /// As imagens das legendas DVB escolhidas mudam a este tempo.
        case images(CMTime, SubtitleImages)
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
    private let urlSessionLock = OSAllocatedUnfairLock<URLSession?>(initialState: nil)
    /// Já se fechou (de outra thread): nada mais vai para o leitor.
    private let stopped = OSAllocatedUnfairLock(initialState: false)
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
    /// A ligação (HTTP) ou o ficheiro (uma gravação no disco) de onde vem o stream.
    private var task: URLSessionDataTask?
    private var file: FileHandle?
    /// À frente do relógio há tanto que se parou de ler (a camada guarda tudo o que recebe; sem isto, uma gravação
    /// ou o anel atrás do direto, que chegam à velocidade do disco, enchiam a memória).
    private var suspended = false {
        didSet { hold.withLock { $0 = suspended } }
    }
    /// O mesmo, para o leitor do HLS (que corre noutra thread).
    private let hold = OSAllocatedUnfairLock(initialState: false)
    private var hls: HlsReader?
    /// O stream acabou de chegar: o fim conta quando o relógio chegar ao último que está no leitor.
    private var inputEnded = false
    private static let maxAheadSeconds = 20.0
    private static let resumeAheadSeconds = 10.0

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
        pipeline.onCodedVideo = { [weak self] v in self?.codedVideo(v) }
        pipeline.onAudio = { [weak self] b in self?.audioSample(b) }
        pipeline.onUnsupported = { [weak self] what in self?.fail("unsupported \(what)") }
        pipeline.onTextTracks = { [weak self] list in self?.onEvent?(.textTracks(list)) }
        pipeline.onVideoDetails = { [weak self] details in self?.onEvent?(.videoDetails(details)) }
        pipeline.onText = { [weak self] pts, text in
            guard let self, !self.closed else { return }
            self.onEvent?(.text(CMTime(value: pts, timescale: 90_000), text))
        }
        pipeline.onImages = { [weak self] pts, page in
            guard let self, !self.closed else { return }
            self.onEvent?(.images(CMTime(value: pts, timescale: 90_000), page))
        }
        // Um salto no relógio do canal já chega aqui com o tempo seguido (TsClock): o leitor continua sem parar.
        pipeline.onJump = {}
    }

    func start(_ url: URL, headers: [String: String], startMs: Int64, hls: Bool) {
        queue.async { self.startWatch() }
        if hls {
            queue.async { self.openHls(url, headers: headers, startMs: startMs) }
            return
        }
        if url.isFileURL {
            queue.async { self.openFile(url, startMs: startMs) }
            return
        }
        guard startMs > 0 else {
            queue.async { self.connect(url, headers: headers, from: 0) }
            return
        }
        // O ponto pedido num ficheiro do fornecedor: umas leituras pequenas para saber onde fica, depois o stream daí.
        DispatchQueue.global(qos: .userInitiated).async {
            let plan = Self.plan(url, headers: headers, startMs: startMs)
            self.queue.async {
                guard !self.closed else { return }
                if let plan { self.onEvent?(.origin(plan.firstPts)) }
                self.connect(url, headers: headers, from: plan?.position ?? 0)
            }
        }
    }

    /// As leituras do TsSeek num ficheiro HTTP (com Range), uma de cada vez e à espera de cada uma.
    private static func plan(_ url: URL, headers: [String: String], startMs: Int64) -> TsSeek.Plan? {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        func fetch(_ range: String) -> (Data, HTTPURLResponse)? {
            var request = URLRequest(url: url)
            for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
            request.setValue("bytes=\(range)", forHTTPHeaderField: "Range")
            request.timeoutInterval = 10
            let done = DispatchSemaphore(value: 0)
            var result: (Data, HTTPURLResponse)?
            session.dataTask(with: request) { data, response, _ in
                if let data, let http = response as? HTTPURLResponse, http.statusCode == 206 { result = (data, http) }
                done.signal()
            }.resume()
            done.wait()
            return result
        }
        // O tamanho vem no Content-Range da primeira leitura ("bytes 0-1048575/123456789"); sem ele não se salta.
        guard let first = fetch("0-\(TsSeek.probe - 1)"),
              let total = (first.1.value(forHTTPHeaderField: "Content-Range")?.split(separator: "/").last).flatMap({ Int64($0) }) else { return nil }
        return TsSeek.plan(length: total, startMs: startMs) { at, n in
            if at == 0 { return first.0 }
            return fetch("\(at)-\(at + Int64(n) - 1)")?.0 ?? Data()
        }
    }

    /// Um HLS: a playlist e os segmentos pelo HlsReader, que espera enquanto há muito à frente.
    private func openHls(_ url: URL, headers: [String: String], startMs: Int64) {
        guard !closed else { return }
        let reader = HlsReader(url: url, headers: headers)
        reader.startMs = startMs
        reader.onStart = { [weak self] ms in self?.onEvent?(.offset(ms)) }
        let hold = self.hold
        reader.shouldWait = { hold.withLock { $0 } }
        reader.onData = { [weak self] data in
            guard let self else { return }
            self.queue.async { if !self.closed { self.pipeline.feed(data) } }
        }
        reader.onEnd = { [weak self] error in
            guard let self else { return }
            self.queue.async {
                guard !self.closed else { return }
                if let error {
                    if let f = error as? HlsReader.Failure, case .http(let code) = f { self.onEvent?(.httpStatus(code)) }
                    self.fail("\(error)")
                    return
                }
                self.pipeline.flush()
                self.inputEnded = true
                if self.startTime == nil { self.finish() }
            }
        }
        hls = reader
        reader.start()
    }

    /// Uma gravação no disco: lê-se aos bocados na fila (ver [pumpFile]), a começar no ponto pedido.
    private func openFile(_ url: URL, startMs: Int64) {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return fail("file: cannot open") }
        file = handle
        if startMs > 0 {
            let length = (try? handle.seekToEnd()).map { Int64($0) } ?? 0
            let plan = try? TsSeek.plan(length: length, startMs: startMs) { at, n in
                try handle.seek(toOffset: UInt64(at))
                return try handle.read(upToCount: n) ?? Data()
            }
            if let plan { onEvent?(.origin(plan.firstPts)) }
            try? handle.seek(toOffset: UInt64(plan?.position ?? 0))
        }
        pumpFile()
    }

    private func pumpFile() {
        guard !closed, let file else { return }
        if suspended {
            queue.asyncAfter(deadline: .now() + 0.1) { self.pumpFile() }
            return
        }
        guard let data = try? file.read(upToCount: 256 * 1024), !data.isEmpty else {
            pipeline.flush()
            inputEnded = true
            if startTime == nil { finish() }
            return
        }
        pipeline.feed(data)
        queue.async { self.pumpFile() }
    }

    /// Na fila: abre a ligação (a partir de [position], com Range), se entretanto não se fechou.
    private func connect(_ url: URL, headers: [String: String], from position: Int64) {
        guard !closed else { return }
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        config.httpMaximumConnectionsPerHost = 1
        let delegateQueue = OperationQueue()
        delegateQueue.underlyingQueue = queue
        delegateQueue.maxConcurrentOperationCount = 1
        let s = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
        urlSessionLock.withLock { $0 = s }
        var request = URLRequest(url: url)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        if position > 0 { request.setValue("bytes=\(position)-", forHTTPHeaderField: "Range") }
        let t = s.dataTask(with: request)
        task = t
        t.resume()
    }

    /// Fecha já a ligação (o fornecedor pode só deixar uma de cada vez); o que ainda estiver na fila não chega ao leitor.
    func cancel() {
        stopped.withLock { $0 = true }
        urlSessionLock.withLock { $0?.invalidateAndCancel() }
        queue.async {
            self.cancelled = true
            self.watch?.cancel()
            try? self.file?.close()
            self.file = nil
            self.hls?.stop()
            self.hls = nil
        }
    }

    /// Outras legendas ([TextTrack.key], nil = nenhumas): as que já passaram pelo leitor descodificam-se logo.
    func selectText(_ key: Int?) {
        queue.async { self.pipeline.selectText(key) }
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
        guard !closed else { return }
        pipeline.feed(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !cancelled else { return }
        pipeline.flush()
        if let error {
            fail("network: \(error.localizedDescription)")
        } else {
            // O fim conta quando o que está no leitor acabar de passar (ver [checkBuffer]).
            inputEnded = true
            if startTime == nil { finish() }
        }
    }

    /// O stream acabou e já se viu tudo.
    private func finish() {
        guard !cancelled else { return }
        cancelled = true
        watch?.cancel()
        onEvent?(.ended)
    }

    private func fail(_ why: String) {
        guard !cancelled else { return }
        cancelled = true
        watch?.cancel()
        urlSessionLock.withLock { $0?.invalidateAndCancel() }
        hls?.stop()
        onEvent?(.failed(why))
    }

    // MARK: Amostras e relógio

    private var closed: Bool { cancelled || stopped.withLock { $0 } }

    /// O MPEG-2, descodificado aqui pelo FFmpeg (ver SoftwareVideo).
    private var software: SoftwareVideo?
    private var softwareFailed = false

    private func codedVideo(_ v: CodedVideo) {
        guard !closed, !softwareFailed else { return }
        if software == nil {
            software = SoftwareVideo()
            if software == nil {
                softwareFailed = true
                fail("unsupported video MPEG2_VIDEO")
                return
            }
        }
        for b in software?.decode(v) ?? [] { video(b) }
    }

    /// O ecrã foi interrompido (a passagem do Picture in Picture para a app, ou ao contrário): esvazia-se e retoma na
    /// imagem-chave seguinte, sem voltar a ligar. Muitas vezes seguidas, é avaria a sério.
    private var waitSync = false
    private var recoveries = 0

    private func video(_ b: CMSampleBuffer) {
        guard !closed else { return }
        if display.status == .failed || display.requiresFlushToResumeDecoding {
            recoveries += 1
            guard recoveries <= 20 else {
                fail("video renderer: \(display.error?.localizedDescription ?? "?")")
                return
            }
            DebugLog.add("GoatCore: video renderer interrupted, resuming at the next keyframe")
            display.flush()
            waitSync = true
        }
        if waitSync {
            guard Self.isSync(b) else { return }
            waitSync = false
        }
        if !reportedSize, let f = CMSampleBufferGetFormatDescription(b) {
            reportedSize = true
            let d = CMVideoFormatDescriptionGetDimensions(f)
            onEvent?(.videoSize(width: Int(d.width), height: Int(d.height)))
        }
        display.enqueue(b)
        let pts = CMSampleBufferGetPresentationTimeStamp(b)
        if pts.isValid, !lastVideo.isValid || pts > lastVideo { lastVideo = pts }
        if startTime == nil, pts.isValid { begin(at: pts) }
    }

    private static func isSync(_ b: CMSampleBuffer) -> Bool {
        guard let a = CMSampleBufferGetSampleAttachmentsArray(b, createIfNecessary: false) as? [[CFString: Any]], let first = a.first else { return true }
        return (first[kCMSampleAttachmentKey_NotSync] as? Bool) != true
    }

    private func audioSample(_ b: CMSampleBuffer) {
        guard !closed else { return }
        if audio.status == .failed {
            // Interrompido (a passagem do PiP, uma chamada): esvazia-se e continua; muitas vezes seguidas, é avaria.
            recoveries += 1
            guard recoveries <= 20 else {
                fail("audio renderer: \(audio.error?.localizedDescription ?? "?")")
                return
            }
            DebugLog.add("GoatCore: audio renderer interrupted, resuming")
            audio.flush()
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
        onEvent?(.started(time))
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
        guard running, !cancelled else { return }
        let now = synchronizer.currentTime()
        let ahead = hasVideo ? lastVideo : lastAudio
        guard ahead.isValid, now.isValid else { return }
        let lead = (ahead - now).seconds
        // Muito à frente: para de ler (a ligação fica à espera, como as filas do Android); retoma a meio.
        if !suspended, lead > Self.maxAheadSeconds {
            suspended = true
            task?.suspend()
        } else if suspended, lead < Self.resumeAheadSeconds {
            suspended = false
            task?.resume()
        }
        if inputEnded {
            if lead <= 0.1 { finish() }
            return
        }
        guard !userPaused else { return }
        if !stalled, lead < 0.05 {
            stalled = true
            synchronizer.rate = 0
        } else if stalled, lead > 0.5 {
            stalled = false
            synchronizer.setRate(rate, time: now)
        }
    }
}
