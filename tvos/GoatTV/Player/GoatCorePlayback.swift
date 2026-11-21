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
        didSet {
            displayLayer.videoGravity = gravity
            // O corte das barras pretas só vale no modo Ajustar.
            setNeedsLayout()
        }
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

    /// O aspeto das legendas (Definições → Legendas; ver Prefs.SubtitleSize, SubtitleStyle e SubtitlePosition). O
    /// tamanho e o sítio valem para todas; a cor e o fundo só para as de texto (as imagens DVB trazem as cores delas).
    private var subtitleSize = Prefs.SubtitleSize.normal
    private var subtitleStyle = Prefs.SubtitleStyle.whiteBox
    private var subtitlePosition = Prefs.SubtitlePosition.normal

    /// Muda o aspeto das legendas. No padrão fica o de sempre: branco numa caixa preta, 1/22 da altura, 8 % acima do fundo.
    func setSubtitleLook(size: String, style: String, position: String) {
        guard size != subtitleSize || style != subtitleStyle || position != subtitlePosition else { return }
        subtitleSize = size
        subtitleStyle = style
        subtitlePosition = position
        let argb = Prefs.SubtitleStyle.textColor(style)
        subtitle.textColor = UIColor(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
                                     blue: CGFloat(argb & 0xFF) / 255, alpha: 1)
        if Prefs.SubtitleStyle.boxed(style) {
            subtitle.backgroundColor = UIColor.black.withAlphaComponent(0.75)
            subtitle.layer.shadowOpacity = 0
        } else {
            // Sem fundo: uma sombra preta à volta das letras, para se lerem em qualquer imagem.
            subtitle.backgroundColor = .clear
            subtitle.layer.shadowColor = UIColor.black.cgColor
            subtitle.layer.shadowOffset = CGSize(width: 0, height: 1)
            subtitle.layer.shadowRadius = 3
            subtitle.layer.shadowOpacity = 1
        }
        setNeedsLayout()
    }

    /// As imagens das legendas DVB, no ecrã do emissor (ver [showSubtitleImages]).
    private let imageLayer = UIView()
    private var images: SubtitleImages?
    /// O tamanho do vídeo (para pôr as imagens em cima dele, e não das barras pretas).
    var videoSize: CGSize = .zero {
        didSet { if videoSize != oldValue { setNeedsLayout() } }
    }

    /// Cortar as barras pretas (Definições → Cortar barras pretas): ligado neste canal. Só vale no modo Ajustar.
    var autoCrop = false {
        didSet { if autoCrop != oldValue { setNeedsLayout() } }
    }

    /// A parte do quadro com imagem (ver BlackBarDetector): muda com um movimento curto da camada.
    var activeArea: VideoCrop = .full {
        didSet {
            guard activeArea != oldValue else { return }
            cropMoves = true
            setNeedsLayout()
        }
    }
    private var cropMoves = false

    /// O que se mostra do quadro: só no modo Ajustar, o mínimo para a parte com imagem encher a vista (ver
    /// VideoCrop.window); a imagem toda sem corte.
    private var cropWindow: VideoCrop {
        guard autoCrop, gravity == .resizeAspect, videoSize.width > 0, videoSize.height > 0, bounds.width > 0, bounds.height > 0 else {
            return .full
        }
        return activeArea.window(frameAspect: Double(videoSize.width / videoSize.height), screenAspect: Double(bounds.width / bounds.height))
    }

    /// Onde fica, na vista, o que se mostra do quadro com o corte [w].
    private func shownRect(_ w: VideoCrop) -> CGRect {
        AVMakeRect(aspectRatio: CGSize(width: videoSize.width * CGFloat(w.width), height: videoSize.height * CGFloat(w.height)), insideRect: bounds)
    }

    /// A camada do vídeo com o corte [w]: o quadro todo, maior do que a vista e deslocado para o rectângulo [w] cair
    /// no [shownRect]; a vista corta o resto (clipsToBounds).
    private func layerFrame(_ w: VideoCrop) -> CGRect {
        guard !w.isFull else { return bounds }
        let shown = shownRect(w)
        let fullWidth = shown.width / CGFloat(w.width)
        let fullHeight = shown.height / CGFloat(w.height)
        return CGRect(x: shown.minX - CGFloat(w.left) * fullWidth, y: shown.minY - CGFloat(w.top) * fullHeight,
                      width: fullWidth, height: fullHeight)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        // A camada fica maior do que a vista ao cortar as barras pretas.
        clipsToBounds = true
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
        default:
            // Com as barras cortadas, as legendas vão para cima do que se vê (como no Android).
            let w = cropWindow
            return w.isFull ? AVMakeRect(aspectRatio: videoSize, insideRect: bounds) : shownRect(w)
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
        if cropMoves {
            // O corte das barras pretas muda devagar (um terço de segundo); o resto (rodar, PiP) muda já.
            cropMoves = false
            CATransaction.setAnimationDuration(0.35)
        } else {
            CATransaction.setDisableActions(true)
        }
        displayLayer.frame = layerFrame(cropWindow)
        CATransaction.commit()
        // O tamanho da letra acompanha o do vídeo (no ecrã inteiro e no Picture in Picture da app).
        subtitle.font = .systemFont(ofSize: max(12, bounds.height / 22 * CGFloat(Prefs.SubtitleSize.scale(subtitleSize))), weight: .medium)
        let maxWidth = bounds.width * 0.9
        let size = subtitle.sizeThatFits(CGSize(width: maxWidth, height: .greatestFiniteMagnitude))
        let width = min(maxWidth, size.width + 16)
        let bottom = bounds.height * (1 - CGFloat(Prefs.SubtitlePosition.gap(subtitlePosition)))
        subtitle.frame = CGRect(x: (bounds.width - width) / 2, y: max(0, bottom - size.height), width: width, height: size.height)
        // As imagens DVB: o ecrã do emissor (720×576, ou o que ele disser) é a imagem do vídeo.
        imageLayer.frame = bounds
        if let page = images, page.displayWidth > 0, page.displayHeight > 0 {
            let rect = videoRect
            let sx = rect.width / CGFloat(page.displayWidth)
            let sy = rect.height / CGFloat(page.displayHeight)
            let w = Double(page.displayWidth), h = Double(page.displayHeight)
            let asSent = subtitleSize == Prefs.SubtitleSize.normal && subtitlePosition == Prefs.SubtitlePosition.normal
            for (v, i) in zip(imageLayer.subviews, page.images.filter { Self.cgImage($0) != nil }) {
                if asSent {
                    v.frame = CGRect(x: rect.minX + CGFloat(i.x) * sx, y: rect.minY + CGFloat(i.y) * sy,
                                     width: CGFloat(i.width) * sx, height: CGFloat(i.height) * sy)
                    continue
                }
                // Com o tamanho e o sítio escolhidos (ver SubtitleLook.place no núcleo partilhado).
                let r = Prefs.SubtitlePosition.place(x: Double(i.x) / w, y: Double(i.y) / h, width: Double(i.width) / w,
                                                     height: Double(i.height) / h, size: subtitleSize, position: subtitlePosition)
                v.frame = CGRect(x: rect.minX + CGFloat(r.x) * rect.width, y: rect.minY + CGFloat(r.y) * rect.height,
                                 width: CGFloat(r.width) * rect.width, height: CGFloat(r.height) * rect.height)
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
    /// Definições → Desentrelaçar ("yadif2x", "yadif" ou "off"): vale para o canal que se abrir a seguir.
    var deinterlace = "yadif2x"
    /// Chegaram (ou mudaram) as faixas do canal: o leitor vê se é só de som (uma rádio).
    var onTracks: (() -> Void)?
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
    /// O leitor do canal principal: o HLS dele tem o menu da qualidade (ver HlsQualityControl); o segundo canal não.
    var primary = false
    private var pip: AVPictureInPictureController?
    /// O canal está (ou vai ficar) no Picture in Picture: ao sair da app, o vídeo continua.
    private(set) var pipActive = false
    /// O PiP está a fechar porque se voltou à app (ver [onPipEnded]).
    private var restoring = false
    /// O Picture in Picture fechou (com a app em segundo plano, quem usa o leitor pára-o e liberta a ligação).
    var onPipEnded: (() -> Void)?
    /// O Picture in Picture pergunta de outra thread se está em pausa.
    nonisolated(unsafe) private var pausedForPip = false
    /// Nivelar o volume (Prefs.audioLevel: "off", "level", "night"; ver AudioLeveling). Vale no canal seguinte.
    var audioLevel = "off"
    /// Vozes mais claras (Prefs.audioVoices; ver AudioLeveling). Vale no canal seguinte.
    var audioVoices = false
    /// O atraso do som pedido nas definições (Prefs.audioDelayMs): + o som mais tarde, − mais cedo. Vale no canal
    /// seguinte. A latência da saída (o HDMI, o Bluetooth, o AirPlay) não é para aqui: o relógio é o do
    /// AVSampleBufferRenderSynchronizer, que já a desconta.
    var audioDelayMs = 0
    /// O passthrough (Prefs.audioPassthrough): o AC-3/E-AC-3 não se nivela, segue para o recetor como vem.
    var passthrough = true
    /// Os ajustes da imagem (Definições → Imagem; ver PictureAdjust e InterlacedVideo): mudam com o canal a dar (o
    /// caminho do vídeo troca na imagem-chave seguinte).
    var picture = PictureAdjust() {
        didSet { if picture != oldValue { session?.setPicture(picture) } }
    }
    /// Aumentar a imagem (Prefs.upscale: "off", "sharp", "ai"; na Apple os dois são o MetalFX, ver InterlacedVideo).
    /// Só na imagem principal; vale no canal seguinte.
    var upscale = "off"
    /// O buffer automático (Prefs.Buffer.auto, só no direto; ver AdaptiveBuffer), se o canal abre no direto ou atrás
    /// dele no anel, e se o que está à frente diz bem como vai a ligação (não num HLS, mesmo pelo anel, que chega aos
    /// segmentos). Valem no canal seguinte.
    var adaptiveBuffer = false
    var adaptiveAtLive = true
    var adaptiveDips = true
    /// Cortar as barras pretas (Prefs.autoCrop; ver BlackBarDetector e GoatCoreView.activeArea). Só na imagem principal;
    /// vale no canal seguinte.
    var autoCrop = false

    /// A imagem que está no ecrã, sem nada da app por cima ("Guardar imagem"); nil se não houver vídeo. A resposta vem
    /// noutra thread.
    func snapshot(_ completion: @escaping @Sendable (CGImage?) -> Void) {
        guard let session else { return completion(nil) }
        session.snapshot(completion)
    }

    override init() {
        super.init()
        view.backgroundColor = .black
    }

    /// Abre [url] com estes cabeçalhos (o User-Agent e o Referer da lista). [startMs]: num TS gravado (as gravações,
    /// o catch-up do Stalker), o ponto onde começar (ver TsSeek). [channel]: o endereço do canal, para o ganho do
    /// nivelamento do volume (o anel da pausa tem outro); nil = o próprio [url].
    func open(_ url: URL, headers: [String: String], startMs: Int64 = 0, hls: Bool = false, channel: String? = nil) {
        closedBytes += session?.probe.bytes ?? 0
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
        // As barras medem-se de novo em cada canal.
        view.autoCrop = autoCrop
        view.activeArea = .full
        // Tudo novo: a ligação anterior pode ainda entregar uma imagem antes de fechar, e ia parar ao leitor deste canal
        // (com outro relógio, a camada deixava de mostrar e só o som continuava).
        let display = view.freshLayer()
        synchronizer = AVSampleBufferRenderSynchronizer()
        audioRenderer = AVSampleBufferAudioRenderer()
        audioRenderer.isMuted = muted
        synchronizer.addRenderer(display)
        synchronizer.addRenderer(audioRenderer)
        pip = nil
        // O ecrã em píxeis, com o lado maior na largura (o do iPhone vem ao alto).
        let bounds = (view.window?.screen ?? UIScreen.main).nativeBounds.size
        let screen = (width: Int(max(bounds.width, bounds.height)), height: Int(min(bounds.width, bounds.height)))
        let s = StreamSession(display: display, audio: audioRenderer, synchronizer: synchronizer, deinterlace: deinterlace, picture: picture,
                              upscale: upscale, screen: screen, autoCrop: autoCrop)
        s.rate = userRate
        // As vozes mais claras só misturam o som com canal central quando a saída é estéreo (a TV sem recetor, uns
        // auscultadores); a que leva mais canais fica com eles.
        let stereoOutput = AVAudioSession.sharedInstance().maximumOutputNumberOfChannels <= 2
        s.leveling = AudioLeveling(mode: audioLevel, channel: channel ?? url.absoluteString, passthrough: passthrough,
                                   voices: audioVoices, stereoOutput: stereoOutput)
        s.audioDelay = CMTime(value: CMTimeValue(audioDelayMs), timescale: 1000)
        // O nível aprendido é do fornecedor: o endereço do canal, não o do anel.
        s.adaptive = adaptiveBuffer ? AdaptiveBuffer(url: channel ?? url.absoluteString) : nil
        s.adaptiveAtLive = adaptiveAtLive
        s.adaptiveDips = adaptiveDips
        s.primary = primary
        selectedAudio = nil
        selectedText = nil
        s.onEvent = { [weak self] event in
            Task { @MainActor in self?.sessionEvent(event, generation: g) }
        }
        session = s
        s.start(url, headers: headers, startMs: startMs, hls: hls)
    }

    /// Os números da ligação a dar, e os bytes de todas as ligações desde o primeiro canal (a soma das que já fecharam
    /// e da atual: uma ligação que cai e volta a abrir não os perde). Ver ChannelProbe.
    var probe: ProbeCounters { session?.probe ?? ProbeCounters() }
    /// O teletexto do canal a dar (novo em cada canal); nil sem ligação.
    var teletextPages: TeletextFeed? { session?.teletextPages }
    var probeTotalBytes: Int64 { closedBytes + (session?.probe.bytes ?? 0) }
    /// As imagens que não se mostraram depois de faltarem dados (ver ErrorConcealment), na ligação a dar.
    var concealedFrames: Int { session?.probe.concealed ?? 0 }
    private var closedBytes: Int64 = 0

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
            onTracks?()
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
        case .crop(let time, let area):
            // Muda quando a imagem dessa hora chegar ao ecrã (a camada tem as imagens à frente do relógio).
            let now = synchronizer.currentTime()
            let wait = now.isValid && startTime != nil && time.isValid ? (time - now).seconds : 0
            guard wait.isFinite, wait > 0.02 else {
                view.activeArea = area
                return
            }
            Timer.scheduledTimer(withTimeInterval: min(wait, 10), repeats: false) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.generation == g else { return }
                    self.view.activeArea = area
                }
            }
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

/// O que se viu da ligação do canal a dar, para "Testar este canal" (ver ChannelProbe): só se conta, não se abre nada.
struct ProbeCounters: Sendable {
    var host = ""
    var hls = false
    /// A hora em que se abriu (uptime, ms).
    var openedAt: Int64 = 0
    /// O código da resposta (-1 = ainda não respondeu), os redirecionamentos e os bytes que chegaram.
    var status = -1
    var redirects = 0
    var bytes: Int64 = 0
    var firstByteMs: Int64 = -1
    var firstPictureMs: Int64 = -1
    /// Para "Informação do stream": o vídeo já no leitor à frente do relógio (ms, -1 = ainda não se sabe), e o que se
    /// está a fazer à imagem (desentrelaçar, aumentar).
    var bufferMs: Int64 = -1
    var deinterlacing = false
    var upscaling = false
    /// As imagens que não se mostraram depois de faltarem dados (ver ErrorConcealment).
    var concealed = 0

    static func nowMs() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }
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
        /// Cortar as barras pretas: a parte do quadro com imagem a partir deste tempo.
        case crop(CMTime, VideoCrop)
        case httpStatus(Int)
        case failed(String)
        case ended
    }

    var onEvent: ((Event) -> Void)?
    /// Ver GoatCorePlayback.primary.
    var primary = false
    /// A velocidade pedida (o catch-up pode andar mais depressa); só se aplica depois de arrancar.
    var rate: Float = 1 {
        didSet {
            queue.async {
                self.drain = 1
                if self.running { self.synchronizer.setRate(self.rate, time: self.synchronizer.currentTime()) }
            }
        }
    }
    /// O buffer automático (Prefs.Buffer.auto; nil = um dos fixos, que voltam a dar com meio segundo à frente):
    /// definido antes de [start], depois só na fila.
    var adaptive: AdaptiveBuffer?
    /// Aberto no direto (não atrás dele no anel): só aí o buffer automático aprende e gasta o que está a mais.
    var adaptiveAtLive = true
    /// O que está à frente diz se a ligação acompanha (ver AdaptiveBuffer.onPlaying): não num HLS.
    var adaptiveDips = true

    /// Nivelar o volume e as vozes mais claras (nil = desligados): definido antes de [start], depois só na fila.
    var leveling: AudioLeveling?
    /// O atraso do som pedido nas definições (ver GoatCorePlayback.audioDelayMs): definido antes de [start].
    var audioDelay: CMTime = .zero

    private let display: AVSampleBufferDisplayLayer
    private let audio: AVSampleBufferAudioRenderer
    private let synchronizer: AVSampleBufferRenderSynchronizer
    private let queue = DispatchQueue(label: "cc.slokker.goattv.goatcore", qos: .userInteractive)
    private let pipeline = MediaPipeline()
    /// Ver MediaPipeline.teletextPages (com o seu lock: lê-se da fila principal).
    var teletextPages: TeletextFeed { pipeline.teletextPages }
    /// Ver [ProbeCounters]; escreve-se das filas da rede e do vídeo, lê-se da principal.
    private let probeLock = OSAllocatedUnfairLock(initialState: ProbeCounters())
    var probe: ProbeCounters { probeLock.withLock { $0 } }

    private func noteBytes(_ count: Int) {
        probeLock.withLock {
            $0.bytes += Int64(count)
            if $0.firstByteMs < 0, $0.openedAt > 0 { $0.firstByteMs = ProbeCounters.nowMs() - $0.openedAt }
            // Um HLS não diz o código de cada segmento aqui: se chegam bytes, respondeu bem.
            if $0.hls, $0.status < 0 { $0.status = 200 }
        }
    }
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
    /// Já esteve em pausa: o que se vê ficou atrás do direto (o buffer automático deixa de contar até outro canal).
    private var wasPaused = false
    /// A velocidade a mais do buffer automático para gastar o que está à frente (1 = nenhuma; ver [adapt]).
    private var drain: Float = 1
    /// O som que vai para o leitor é AC-3/E-AC-3 em bruto: esse não se acelera sem se notar.
    private var bitstreamAudio = false
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

    #if DEBUG
    /// As etapas do arranque do canal (o GoatZap do Android): só nas versões de testes, só na fila.
    private var zap: ZapTimeline?
    #endif

    /// Regista a etapa [stage] do arranque, com os ms desde a anterior (só nas versões de testes; na fila).
    private func zapMark(_ stage: String, _ detail: @autoclosure () -> String = "") {
        #if DEBUG
        let now = ProbeCounters.nowMs()
        if zap == nil { zap = ZapTimeline(startMs: now) }
        if let line = zap?.mark(stage, nowMs: now, detail: detail()) { DebugLog.add("GoatZap: \(line)") }
        #endif
    }

    init(display: AVSampleBufferDisplayLayer, audio: AVSampleBufferAudioRenderer, synchronizer: AVSampleBufferRenderSynchronizer,
         deinterlace: String, picture: PictureAdjust, upscale: String, screen: (width: Int, height: Int), autoCrop: Bool = false) {
        self.display = display
        cropper = autoCrop ? BlackBarDetector() : nil
        self.audio = audio
        self.synchronizer = synchronizer
        self.deinterlace = deinterlace
        self.picture = picture
        self.screen = screen
        if upscale != "off" {
            if InterlacedVideo.metalFXSupported {
                upscaler = VideoUpscale(setting: upscale)
            } else {
                DebugLog.add("GoatCore: upscale: this device has no MetalFX spatial scaler, no upscaling")
            }
        }
        super.init()
        // Com o aumento: o vídeo mais pequeno do que o ecrã vai pelo mesmo caminho (ver MediaPipeline.upscaleVideo).
        if let up = upscaler {
            pipeline.upscaleVideo = { w, h in up.wanted(width: w, height: h, screenWidth: screen.width, screenHeight: screen.height) }
        }
        // O H.264 entrelaçado passa pelo desentrelaçador (ver [interlacedVideo]); desligado, vai como o resto. Com
        // ajustes da imagem, o vídeo de 8 bits SDR vai pelo mesmo caminho (ver MediaPipeline.adjustVideo).
        pipeline.deinterlaceVideo = deinterlace != "off"
        // Cortar as barras pretas: o vídeo de 8 bits SDR até 1080 linhas vai também pela GPU (para se ver a imagem), e o
        // AFD lê-se de todo o vídeo (o resto, o 4K e o HDR, só pelo AFD).
        pipeline.adjustVideo = !picture.neutral || autoCrop
        if autoCrop {
            pipeline.onAfd = { [weak self] pts, code in self?.afd(code, pts: pts) }
        }
        pipeline.onInterlacedVideo = { [weak self] b, bottomFirst, progressive in
            self?.interlacedVideo(b, bottomFirst: bottomFirst, progressive: progressive)
        }
        pipeline.onTracks = { [weak self] tracks in
            guard let self else { return }
            self.zapMark("tracks", tracks.map(\.codec).joined(separator: " ") + " (\(self.pipeline.replayedPackets) early packets replayed)")
            self.hasVideo = tracks.contains { $0.kind == .video }
            self.hasAudio = tracks.contains { $0.kind == .audio }
            self.onEvent?(.tracks(tracks))
        }
        pipeline.onVideo = { [weak self] b in self?.compressedVideo(b) }
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
        queue.async {
            self.zapMark("open")
            self.startWatch()
        }
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
        probeLock.withLock { $0 = ProbeCounters(host: url.host ?? "", hls: true, openedAt: ProbeCounters.nowMs()) }
        let reader = HlsReader(url: url, headers: headers)
        reader.startMs = startMs
        reader.primary = primary
        reader.onStart = { [weak self] ms in self?.onEvent?(.offset(ms)) }
        let hold = self.hold
        reader.shouldWait = { hold.withLock { $0 } }
        reader.onData = { [weak self] data in
            guard let self else { return }
            self.noteBytes(data.count)
            self.queue.async {
                guard !self.closed else { return }
                self.zapMark("first byte")
                self.pipeline.feed(data)
            }
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
        probeLock.withLock { $0 = ProbeCounters(host: url.host ?? "", openedAt: ProbeCounters.nowMs()) }
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
            self.heldTimer?.cancel()
            self.heldTimer = nil
            self.held = []
            try? self.file?.close()
            self.file = nil
            self.hls?.stop()
            self.hls = nil
            self.leveling?.finish()
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
            self.leveling?.reset()
            self.lastAudio = .invalid
        }
    }

    func pause() {
        queue.async {
            self.userPaused = true
            self.wasPaused = true
            if self.running { self.synchronizer.rate = 0 }
        }
    }

    func resume() {
        queue.async {
            self.userPaused = false
            self.drain = 1
            if self.running, !self.stalled { self.synchronizer.rate = self.rate }
        }
    }

    // MARK: Rede

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse { probeLock.withLock { $0.status = http.statusCode } }
        zapMark("connected", "HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
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
        noteBytes(data.count)
        zapMark("first byte")
        pipeline.feed(data)
    }

    /// O servidor passou a ligação para outro endereço: segue-se (como antes), e conta-se.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        probeLock.withLock { $0.redirects += 1 }
        completionHandler(request)
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

    /// Definições → Desentrelaçar, para o MPEG-2 (o bwdif do SoftwareVideo) e o H.264 entrelaçado (InterlacedVideo).
    private let deinterlace: String

    /// O H.264 entrelaçado (os 576i e 1080i), descodificado aqui e desentrelaçado na GPU: a camada não o faz. E o
    /// vídeo com ajustes da imagem, que se fazem no mesmo sítio.
    private var interlaced: InterlacedVideo?
    private var interlacedFailed = false
    /// Os ajustes da imagem (só na fila, ver [setPicture]).
    private var picture: PictureAdjust
    /// O aumento da imagem (nil = desligado, ou sem MetalFX) e o ecrã em píxeis.
    private var upscaler: VideoUpscale?
    private let screen: (width: Int, height: Int)
    /// Com o aumento, as imagens comprimidas para o [interlaced] esperam aqui até faltar [upscaleAhead] para a hora
    /// delas (pela ordem em que chegam): as imagens aumentadas são grandes (~12 MB uma 4K), e a camada guardava todas as
    /// que recebe (até 20 s à frente). Assim ficam lá poucas (~15 a 50 por segundo). [heldLast]: o último tempo à espera.
    private var held: [(sample: CMSampleBuffer, bottomFirst: Bool, progressive: Bool)] = []
    private var heldLast: CMTime = .invalid
    private var heldTimer: DispatchSourceTimer?
    private static let upscaleAhead = 0.3
    /// A última imagem passou pelo [interlaced] (ver [compressedVideo]).
    private var processing = false

    /// Outros ajustes da imagem: valem já nas imagens que passam pela GPU; o vídeo que ia direto para a camada (ou o
    /// contrário, ao voltar tudo ao 0) troca de caminho na imagem-chave seguinte.
    func setPicture(_ picture: PictureAdjust) {
        queue.async {
            self.picture = picture
            self.interlaced?.picture = picture
            self.pipeline.adjustVideo = !picture.neutral || self.cropper != nil
        }
    }

    /// Cortar as barras pretas (nil = desligado; só na fila, ver BlackBarDetector).
    private let cropper: BlackBarDetector?

    /// O AFD de uma imagem ([pts]: 90 kHz).
    private func afd(_ code: Int, pts: Int64) {
        guard let cropper, !closed, cropper.afd(code, ms: pts / 90) else { return }
        onEvent?(.crop(CMTime(value: pts, timescale: 90_000), cropper.active))
    }

    /// Uma imagem já descodificada que vai para a camada: as barras medem-se nela (uma por segundo de vídeo).
    private func measureBars(_ b: CMSampleBuffer, pts: CMTime) {
        guard let cropper, pts.isValid, let image = CMSampleBufferGetImageBuffer(b) else { return }
        let ms = Int64(pts.seconds * 1000)
        if cropper.measure(image, ms: ms) { onEvent?(.crop(pts, cropper.active)) }
    }

    /// O vídeo comprimido, direto para a camada. Se até aqui passava pela GPU (os ajustes voltaram ao 0), primeiro
    /// saem as imagens que lá estavam à espera.
    private func compressedVideo(_ b: CMSampleBuffer) {
        if processing {
            probeLock.withLock {
                $0.deinterlacing = false
                $0.upscaling = false
            }
            // As que esperavam pelo aumento saem primeiro (pela ordem).
            releaseHeld(all: true)
            processing = false
            for f in interlaced?.finish() ?? [] { video(f) }
        }
        video(b)
    }

    private func interlacedVideo(_ b: CMSampleBuffer, bottomFirst: Bool, progressive: Bool) {
        guard !closed else { return }
        // Com o aumento, à espera da hora (ver [held]); o relógio ainda parado (a primeira imagem) não espera.
        if upscaler != nil, !interlacedFailed {
            processing = true
            held.append((sample: b, bottomFirst: bottomFirst, progressive: progressive))
            let pts = CMSampleBufferGetPresentationTimeStamp(b)
            if pts.isValid, !heldLast.isValid || pts > heldLast { heldLast = pts }
            if heldTimer == nil {
                let t = DispatchSource.makeTimerSource(queue: queue)
                t.schedule(deadline: .now() + 0.02, repeating: 0.02)
                t.setEventHandler { [weak self] in self?.releaseHeld(all: false) }
                t.resume()
                heldTimer = t
            }
            releaseHeld(all: false)
            return
        }
        decodeOnGPU(b, bottomFirst: bottomFirst, progressive: progressive)
    }

    /// As imagens à espera cuja hora está a menos de [upscaleAhead] (ou todas, com [all]) vão para o [interlaced].
    private func releaseHeld(all: Bool) {
        guard !closed else {
            held = []
            heldTimer?.cancel()
            heldTimer = nil
            return
        }
        let now = synchronizer.currentTime()
        while let first = held.first {
            let pts = CMSampleBufferGetPresentationTimeStamp(first.sample)
            if !all, running, now.isValid, pts.isValid, (pts - now).seconds > Self.upscaleAhead { break }
            held.removeFirst()
            decodeOnGPU(first.sample, bottomFirst: first.bottomFirst, progressive: first.progressive)
        }
        if held.isEmpty { heldLast = .invalid }
    }

    private func decodeOnGPU(_ b: CMSampleBuffer, bottomFirst: Bool, progressive: Bool) {
        guard !closed else { return }
        if interlaced == nil, !interlacedFailed {
            interlaced = InterlacedVideo(fieldRate: deinterlace != "yadif")
            interlaced?.picture = picture
            interlaced?.upscale = upscaler
            interlaced?.screen = screen
            interlacedFailed = interlaced == nil
            DebugLog.add(interlaced == nil ? "GoatCore: no GPU video stage (interlaced video or picture adjustments)"
                : "GoatCore: video through the GPU (deinterlace \(deinterlace), picture \(picture.neutral ? "as is" : "adjusted"))")
        }
        guard let deinterlacer = interlaced else { return video(b) }
        processing = true
        guard let frames = deinterlacer.decode(b, bottomFirst: bottomFirst, progressive: progressive) else {
            // O VideoToolbox não o descodifica à parte: vai como vinha (a camada recomeça na imagem-chave seguinte).
            interlaced = nil
            interlacedFailed = true
            probeLock.withLock {
                $0.deinterlacing = false
                $0.upscaling = false
            }
            return video(b)
        }
        let deinterlacing = !progressive && deinterlace != "off"
        let upscaling = deinterlacer.isUpscaling
        probeLock.withLock {
            $0.deinterlacing = deinterlacing
            $0.upscaling = upscaling
        }
        for f in frames { video(f) }
    }

    private func codedVideo(_ v: CodedVideo) {
        guard !closed, !softwareFailed else { return }
        if software == nil {
            software = SoftwareVideo(deinterlace: deinterlace)
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
            recentVideo.removeAll()
        }
        if waitSync {
            guard Self.isSync(b) else { return }
            waitSync = false
        }
        if !reportedSize, let f = CMSampleBufferGetFormatDescription(b) {
            reportedSize = true
            // Com a proporção do píxel (1440×1080 com píxeis 4:3 é 16:9): sem ela a imagem saía apertada, com barras.
            let d = CMVideoFormatDescriptionGetPresentationDimensions(f, usePixelAspectRatio: true, useCleanAperture: false)
            onEvent?(.videoSize(width: Int(d.width.rounded()), height: Int(d.height.rounded())))
            if let cropper, cropper.frame(width: Int(d.width.rounded()), height: Int(d.height.rounded())) {
                onEvent?(.crop(CMSampleBufferGetPresentationTimeStamp(b), cropper.active))
            }
        }
        display.enqueue(b)
        remember(b)
        let pts = CMSampleBufferGetPresentationTimeStamp(b)
        if cropper != nil { measureBars(b, pts: pts) }
        if pts.isValid, !lastVideo.isValid || pts > lastVideo { lastVideo = pts }
        if startTime == nil, pts.isValid { begin(at: pts) }
    }

    // MARK: Guardar imagem

    /// As últimas imagens que foram para a camada, pela ordem em que foram (ver [snapshot]). A camada não dá a imagem
    /// que mostra, por isso guarda-se aqui com que a refazer: das comprimidas, tudo desde a imagem-chave da que está no
    /// ecrã (uns MB); das já descodificadas (desentrelaçadas, ajustadas ou MPEG-2), a que está no ecrã e as que ainda
    /// vão dar, que a camada guarda de qualquer maneira. Só no iPhone: o Apple TV não tem onde guardar imagens.
    private var recentVideo: [CMSampleBuffer] = []
    private var recentDecoded = false
    private static let maxRecent = 1_500

    private func remember(_ b: CMSampleBuffer) {
        #if os(iOS)
        let decoded = CMSampleBufferGetImageBuffer(b) != nil
        if decoded != recentDecoded {
            recentDecoded = decoded
            recentVideo.removeAll()
        }
        let now = synchronizer.currentTime()
        func shown(_ f: CMSampleBuffer) -> Bool {
            let t = CMSampleBufferGetPresentationTimeStamp(f)
            return t.isValid && now.isValid && t <= now
        }
        if decoded {
            if let at = recentVideo.lastIndex(where: shown), at > 0 { recentVideo.removeFirst(at) }
        } else if Self.isSync(b) {
            if let key = recentVideo.lastIndex(where: { Self.isSync($0) && shown($0) }), key > 0 { recentVideo.removeFirst(key) }
        }
        recentVideo.append(b)
        if recentVideo.count > Self.maxRecent { recentVideo.removeFirst(recentVideo.count - Self.maxRecent) }
        #endif
    }

    /// A imagem que está no ecrã (ver VideoSnapshot), refeita fora desta fila; nil sem vídeo.
    func snapshot(_ completion: @escaping @Sendable (CGImage?) -> Void) {
        queue.async {
            let frames = self.recentVideo
            let now = self.synchronizer.currentTime()
            DispatchQueue.global(qos: .userInitiated).async { completion(VideoSnapshot.image(frames, at: now)) }
        }
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
            leveling?.reset()
        }
        var b = b
        if let leveling {
            // Nivelado (já em PCM); nil enquanto o descodificador ainda não deu som.
            guard let levelled = leveling.process(b) else { return }
            b = levelled
        }
        b = delayed(b)
        if adaptive != nil, let fd = CMSampleBufferGetFormatDescription(b),
           let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(fd)?.pointee {
            bitstreamAudio = asbd.mFormatID == kAudioFormatAC3 || asbd.mFormatID == kAudioFormatEnhancedAC3
        }
        audio.enqueue(b)
        zapMark("audio")
        let pts = CMSampleBufferGetPresentationTimeStamp(b)
        if pts.isValid, !lastAudio.isValid || pts > lastAudio { lastAudio = pts }
        // Um canal só de som (rádio): o relógio começa no primeiro som.
        if startTime == nil, !hasVideo, pts.isValid { begin(at: pts) }
    }

    /// O som com o atraso pedido nas definições: o mesmo bloco com o tempo dele mais [audioDelay] (o relógio é o
    /// mesmo para a imagem e o som, por isso o som ouve-se esse tanto depois, ou antes, da imagem dele).
    private func delayed(_ b: CMSampleBuffer) -> CMSampleBuffer {
        guard audioDelay != .zero else { return b }
        var timing = CMSampleTimingInfo()
        guard CMSampleBufferGetSampleTimingInfo(b, at: 0, timingInfoOut: &timing) == noErr else { return b }
        timing.presentationTimeStamp = timing.presentationTimeStamp + audioDelay
        if timing.decodeTimeStamp.isValid { timing.decodeTimeStamp = timing.decodeTimeStamp + audioDelay }
        var out: CMSampleBuffer?
        guard CMSampleBufferCreateCopyWithNewTiming(allocator: kCFAllocatorDefault, sampleBuffer: b, sampleTimingEntryCount: 1,
                                                    sampleTimingArray: &timing, sampleBufferOut: &out) == noErr, let out else { return b }
        return out
    }

    /// O relógio começa no primeiro tempo que se pode mostrar: o vídeo na primeira imagem-chave (o pipeline só as
    /// manda depois de ter os parâmetros), o som anterior a ele o leitor salta.
    private func begin(at time: CMTime) {
        startTime = time
        running = true
        synchronizer.setRate(userPaused ? 0 : rate, time: time)
        zapMark(hasVideo ? "picture" : "sound", "first sample to the renderer, clock started")
        probeLock.withLock { if $0.firstPictureMs < 0, $0.openedAt > 0 { $0.firstPictureMs = ProbeCounters.nowMs() - $0.openedAt } }
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
        // O vídeo à espera do aumento (ver [held]) conta como já estando no leitor.
        let ahead = hasVideo ? (heldLast.isValid && (!lastVideo.isValid || heldLast > lastVideo) ? heldLast : lastVideo) : lastAudio
        guard ahead.isValid, now.isValid else { return }
        let lead = (ahead - now).seconds
        let concealed = pipeline.concealedFrames
        probeLock.withLock {
            if lead.isFinite { $0.bufferMs = Int64(max(0, lead) * 1000) }
            $0.concealed = concealed
        }
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
            drain = 1
            if adaptiveAtLive, !wasPaused { adaptive?.onStall(ProbeCounters.nowMs()) }
        } else if stalled, lead > (adaptive?.targetSeconds ?? 0.5) {
            // Volta a dar com o que o buffer pede à frente (o automático junta mais num fornecedor que falha).
            stalled = false
            synchronizer.setRate(rate, time: now)
        } else if !stalled, let adaptive {
            adapt(adaptive, lead: lead)
        }
    }

    /// O buffer automático a dar: aprende com o que está à frente e, depois de descer de nível, gasta o que está a mais
    /// a tocar um pouco mais depressa (o leitor da Apple mantém o tom do som). Só no direto, à velocidade normal.
    private func adapt(_ adaptive: AdaptiveBuffer, lead: Double) {
        var want: Float = 1
        if adaptiveAtLive, !wasPaused, rate == 1 {
            adaptive.onPlaying(ProbeCounters.nowMs(), leadSeconds: lead, dips: adaptiveDips)
            want = adaptive.rate(leadSeconds: lead, canDrain: !bitstreamAudio)
        }
        guard want != drain else { return }
        drain = want
        synchronizer.setRate(rate * want, time: synchronizer.currentTime())
    }
}
