import AVFoundation
import AVKit
import CoreMedia
import Foundation
import GoatCore
import Observation
import TVVLCKit
import UIKit

/// O leitor de vídeo, com dois motores:
/// - o da Apple (AVPlayer) para HLS: descodificação por hardware, HDR10, Dolby Vision, HLG e Dolby Atmos;
/// - o VLC para o resto (`.ts`, MPEG-2, áudio MP2/DTS…), que é o que muitas listas IPTV dão.
/// Tenta as formas de abrir o canal por ordem (ts ⇄ m3u8; num HLS, primeiro o da Apple e depois o VLC) e, se a
/// stream cair, volta a ligar ao fim de uns segundos. Com "Igualar a frame rate", a TV muda para a cadência e o
/// HDR do canal (Definições do Apple TV → Vídeo e áudio → Igualar conteúdo).
@MainActor
@Observable
final class PlayerEngine: NSObject {
    enum State: Equatable {
        case idle
        case opening
        case playing
        /// Nenhuma forma de abrir funcionou; volta a tentar sozinho.
        case failed
    }

    enum Backend: Equatable {
        case vlc
        case apple
    }

    private(set) var state: State = .idle
    private(set) var paused = false
    /// O formato da forma que está a dar ("ts" ou "m3u8").
    private(set) var format: String?
    /// O motor que está a dar.
    private(set) var backend: Backend = .vlc
    /// Já esteve a dar neste canal (então uma falha é a stream que caiu: volta a ligar sempre).
    private(set) var wasPlaying = false
    /// Tentativas falhadas de abrir o canal (no máximo [maxStartRetries], como no Android).
    private(set) var retries = 0
    static let maxStartRetries = 3
    /// O código HTTP da última falha, quando se sabe (leitor da Apple).
    private(set) var lastHTTPStatus: Int?

    /// Não abriu (ou caiu): quem usa o leitor explica o erro.
    @ObservationIgnored var onFailed: (() -> Void)?
    /// Caiu a meio: devolve true se quem usa o leitor trata disso (ex.: o catch-up pede de novo daquele ponto).
    @ObservationIgnored var onDropped: (() -> Bool)?
    /// A forma que funcionou ("ts" ou "m3u8").
    @ObservationIgnored var onFormatWorked: ((String) -> Void)?

    struct Track: Identifiable, Equatable {
        let id: Int32
        let name: String
    }

    // MARK: Definições (vêm da conta)

    /// Segundos até voltar a ligar quando a stream falha (0 = não tentar).
    @ObservationIgnored var reconnectSeconds = 5
    /// Buffer de rede em ms.
    @ObservationIgnored var cachingMs = 1500
    /// Dolby/DTS seguem tal como vêm para o recetor ou a TV (no VLC; o da Apple trata disso sozinho).
    @ObservationIgnored var passthrough = true
    /// "exo" (o normal: o da Apple nos HLS, o VLC no resto) ou "vlc" (sempre o VLC). As mesmas chaves do Android.
    @ObservationIgnored var engine = "exo"
    /// Descodificar em software no VLC (para streams que o hardware não abre bem).
    @ObservationIgnored var softwareDecoder = false
    /// Desentrelaçar no VLC: "yadif2x" (50/60 imagens por segundo, mais suave), "yadif" ou "off".
    @ObservationIgnored var deinterlace = "yadif2x"
    /// A TV muda de frame rate (e de HDR) para a do canal.
    @ObservationIgnored var matchFrameRate = false {
        didSet { if matchFrameRate != oldValue { applyDisplayCriteria() } }
    }
    /// "auto" (HDR quando o canal é HDR) ou "sdr" (a TV fica sempre em SDR).
    @ObservationIgnored var hdr = "auto"
    /// Só o leitor principal muda o modo da TV (o segundo canal não).
    @ObservationIgnored var controlsDisplay = true
    /// Mantém a última imagem do canal anterior até o novo começar.
    @ObservationIgnored var freezeFrame = true
    /// Escolhe a faixa de áudio com mais canais (5.1) quando o canal começa.
    @ObservationIgnored var preferSurround = false
    /// "fit", "zoom" (corta) ou "fill" (estica).
    @ObservationIgnored var aspect = "fit" {
        didSet { if aspect != oldValue { applyAspect() } }
    }

    // MARK: Vistas e motores

    /// A vista onde o vídeo aparece (a mesma durante toda a app; lá dentro, a do motor em uso).
    let view: UIView = {
        let v = UIView()
        v.backgroundColor = .black
        return v
    }()

    private let vlcView = UIView()
    private let appleView = AppleVideoView()
    /// A imagem congelada do canal anterior (por cima do vídeo, até o novo começar).
    private let freezeView: UIImageView = {
        let v = UIImageView()
        v.contentMode = .scaleAspectFit
        v.backgroundColor = .clear
        v.isHidden = true
        return v
    }()
    @ObservationIgnored private var videoOutput: AVPlayerItemVideoOutput?
    @ObservationIgnored private var pendingStart: Task<Void, Never>?
    private let vlc = VLCMediaPlayer()
    private let avPlayer = AVPlayer()

    @ObservationIgnored private var muted = false
    @ObservationIgnored private var surroundChosen = false
    @ObservationIgnored private var observers: [NSKeyValueObservation] = []
    @ObservationIgnored private var notifications: [NSObjectProtocol] = []

    private struct Attempt {
        let candidate: StreamCandidate
        let backend: Backend
    }

    @ObservationIgnored private var attempts: [Attempt] = []
    @ObservationIgnored private var index = 0
    @ObservationIgnored private var userAgent = iptvUserAgent
    @ObservationIgnored private var referrer: String?
    @ObservationIgnored private var watchdog: Task<Void, Never>?
    @ObservationIgnored private var retry: Task<Void, Never>?
    @ObservationIgnored private var criteriaTask: Task<Void, Never>?

    override init() {
        super.init()
        for v in [vlcView, appleView] {
            v.frame = view.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            v.backgroundColor = .black
            view.addSubview(v)
        }
        freezeView.frame = view.bounds
        freezeView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(freezeView)
        appleView.isHidden = true
        appleView.playerLayer.player = avPlayer
        avPlayer.allowsExternalPlayback = false
        vlc.delegate = self
        vlc.drawable = vlcView
    }

    func play(_ candidates: [StreamCandidate], userAgent: String?, referrer: String?) {
        retry?.cancel()
        pendingStart?.cancel()
        let wasShowing = state == .playing
        self.userAgent = (userAgent?.isEmpty ?? true) ? iptvUserAgent : userAgent!
        self.referrer = referrer
        // Num HLS, primeiro o leitor da Apple e, se não abrir, o VLC com o mesmo endereço.
        attempts = candidates.flatMap { c -> [Attempt] in
            engine != "vlc" && c.isHLS ? [Attempt(candidate: c, backend: .apple), Attempt(candidate: c, backend: .vlc)] : [Attempt(candidate: c, backend: .vlc)]
        }
        index = 0
        paused = false
        wasPlaying = false
        retries = 0
        surroundChosen = false
        lastHTTPStatus = nil
        guard freezeFrame, wasShowing else {
            hideFreeze()
            start()
            return
        }
        // A última imagem fica por cima até o canal novo começar.
        switch backend {
        case .apple:
            showFreeze(appleSnapshot())
            start()
        case .vlc:
            // O VLC grava a imagem num ficheiro; espera-se um instante antes de parar o canal antigo.
            let path = NSTemporaryDirectory() + "goattv-freeze.png"
            try? FileManager.default.removeItem(atPath: path)
            vlc.saveVideoSnapshot(at: path, withWidth: 1280, andHeight: 720)
            pendingStart = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(120))
                guard !Task.isCancelled, let self else { return }
                self.showFreeze(UIImage(contentsOfFile: path))
                self.start()
            }
        }
    }

    /// Volta a tentar já (OK no erro).
    func retryNow() {
        retry?.cancel()
        index = 0
        start()
    }

    private func showFreeze(_ image: UIImage?) {
        freezeView.image = image
        freezeView.isHidden = image == nil
        if let image { view.bringSubviewToFront(freezeView); _ = image }
    }

    private func hideFreeze() {
        freezeView.isHidden = true
        freezeView.image = nil
    }

    private func appleSnapshot() -> UIImage? {
        guard let output = videoOutput, let buffer = output.copyPixelBuffer(forItemTime: avPlayer.currentTime(), itemTimeForDisplay: nil) else { return nil }
        let image = CIImage(cvPixelBuffer: buffer)
        guard let cg = CIContext().createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    func stop() {
        watchdog?.cancel()
        retry?.cancel()
        pendingStart?.cancel()
        hideFreeze()
        stopBackends()
        state = .idle
        clearDisplayCriteria()
    }

    private func stopBackends() {
        criteriaTask?.cancel()
        observers.removeAll()
        for n in notifications { NotificationCenter.default.removeObserver(n) }
        notifications.removeAll()
        vlc.stop()
        avPlayer.replaceCurrentItem(with: nil)
    }

    private func start() {
        watchdog?.cancel()
        guard index < attempts.count, let url = URL(string: attempts[index].candidate.url) else {
            fail()
            return
        }
        stopBackends()
        let attempt = attempts[index]
        backend = attempt.backend
        vlcView.isHidden = backend != .vlc
        appleView.isHidden = backend != .apple
        state = .opening
        switch backend {
        case .vlc: startVLC(url)
        case .apple: startApple(url)
        }
        // Se em 15 s não começar a dar, tenta a forma seguinte.
        watchdog = Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard !Task.isCancelled, let self, self.state == .opening else { return }
            self.next()
        }
    }

    private func next() {
        index += 1
        if index < attempts.count {
            start()
        } else {
            fail()
        }
    }

    /// Nenhuma forma abriu. Caiu a meio: volta a ligar de N em N segundos, sempre. Não chegou a abrir: mostra o
    /// erro e tenta mais umas vezes (sem martelar o servidor).
    private func fail() {
        watchdog?.cancel()
        if lastHTTPStatus == nil, backend == .apple { lastHTTPStatus = appleHTTPStatus }
        stopBackends()
        if wasPlaying {
            if onDropped?() == true { return }
            guard reconnectSeconds > 0 else {
                state = .failed
                hideFreeze()
                onFailed?()
                return
            }
            state = .opening
            scheduleRetry()
            return
        }
        state = .failed
        hideFreeze()
        let again = reconnectSeconds > 0 && retries < Self.maxStartRetries
        if again { retries += 1 }
        onFailed?()
        if again { scheduleRetry() }
    }

    private func scheduleRetry() {
        retry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Double(max(1, self?.reconnectSeconds ?? 5))))
            guard !Task.isCancelled, let self else { return }
            self.index = 0
            self.start()
        }
    }

    /// O código HTTP do último erro do leitor da Apple.
    private var appleHTTPStatus: Int? {
        guard let event = avPlayer.currentItem?.errorLog()?.events.last, event.errorStatusCode != 0 else { return nil }
        return event.errorStatusCode
    }

    /// Começou a dar: a forma que funcionou passa a ser a primeira (ao voltar a ligar, começa por ela).
    private func becamePlaying() {
        guard state != .playing else { return }
        watchdog?.cancel()
        state = .playing
        wasPlaying = true
        retries = 0
        lastHTTPStatus = nil
        hideFreeze()
        if let format = attempts.indices.contains(index) ? attempts[index].candidate.format : nil { onFormatWorked?(format) }
        format = attempts.indices.contains(index) ? attempts[index].candidate.format : nil
        applyAspect()
        applyAudio()
        if index > 0 {
            attempts.insert(attempts.remove(at: index), at: 0)
            index = 0
        }
        // A frame rate e as faixas só se sabem uns instantes depois de começar.
        criteriaTask?.cancel()
        criteriaTask = Task { [weak self] in
            for delay in [0.8, 2.5] {
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.applyDisplayCriteria()
                self?.chooseSurround()
            }
        }
    }

    // MARK: VLC

    private func startVLC(_ url: URL) {
        let media = VLCMedia(url: url)
        media.addOption(":http-user-agent=\(userAgent)")
        if let referrer, !referrer.isEmpty { media.addOption(":http-referrer=\(referrer)") }
        media.addOption(":network-caching=\(cachingMs)")
        media.addOption(":http-reconnect")
        if softwareDecoder { media.addOption(":codec=avcodec") }
        vlc.media = media
        if deinterlace == "off" {
            vlc.setDeinterlaceFilter(nil)
        } else {
            vlc.setDeinterlace(.auto, withFilter: deinterlace)
        }
        vlc.play()
    }

    fileprivate func vlcStateChanged() {
        guard backend == .vlc else { return }
        switch vlc.state {
        case .playing:
            becamePlaying()
        case .error:
            next()
        case .ended:
            // Uma stream em direto que "acaba" caiu: volta a ligar.
            if state == .playing { fail() } else { next() }
        default:
            break
        }
    }

    /// A frame rate e o tamanho do vídeo que o VLC está a dar.
    private var vlcVideoInfo: (fps: Double, width: Int, height: Int)? {
        guard let tracks = vlc.media?.tracksInformation as? [[String: Any]] else { return nil }
        for t in tracks where (t[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeVideo {
            let num = (t[VLCMediaTracksInformationFrameRate] as? NSNumber)?.doubleValue ?? 0
            let den = (t[VLCMediaTracksInformationFrameRateDenominator] as? NSNumber)?.doubleValue ?? 0
            let w = (t[VLCMediaTracksInformationVideoWidth] as? NSNumber)?.intValue ?? 0
            let h = (t[VLCMediaTracksInformationVideoHeight] as? NSNumber)?.intValue ?? 0
            guard num > 0, den > 0 else { continue }
            return (num / den, w, h)
        }
        return nil
    }

    // MARK: Leitor da Apple

    private func startApple(_ url: URL) {
        var options: [String: Any] = [AVURLAssetHTTPUserAgentKey: userAgent]
        if let referrer, !referrer.isEmpty { options["AVURLAssetHTTPHeaderFieldsKey"] = ["Referer": referrer] }
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: options))
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        item.add(output)
        videoOutput = output
        if cachingMs <= 500 { item.preferredForwardBufferDuration = 2 }
        observers.append(item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in self?.appleStatus(item) }
        })
        observers.append(avPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            Task { @MainActor in
                guard let self, player.currentItem === item, player.timeControlStatus == .playing else { return }
                self.becamePlaying()
            }
        })
        let center = NotificationCenter.default
        notifications.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.appleFailed() }
        })
        notifications.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.appleFailed() }
        })
        avPlayer.isMuted = muted
        avPlayer.replaceCurrentItem(with: item)
        avPlayer.play()
    }

    private func appleStatus(_ item: AVPlayerItem) {
        guard backend == .apple, item === avPlayer.currentItem, item.status == .failed else { return }
        lastHTTPStatus = appleHTTPStatus
        next()
    }

    private func appleFailed() {
        guard backend == .apple else { return }
        lastHTTPStatus = appleHTTPStatus
        if state == .playing { fail() } else { next() }
    }

    // MARK: Modo da TV (frame rate e HDR)

    /// Pede à TV a cadência (e o HDR) do canal. Só funciona com "Igualar conteúdo" ligado no Apple TV.
    private func applyDisplayCriteria() {
        guard controlsDisplay, let manager = displayManager else { return }
        guard matchFrameRate, state == .playing else {
            manager.preferredDisplayCriteria = nil
            return
        }
        switch backend {
        case .apple:
            guard let item = avPlayer.currentItem else { return }
            if hdr == "sdr" {
                let track = item.tracks.first { $0.assetTrack?.mediaType == .video }
                let fps = Double(track?.currentVideoFrameRate ?? 0)
                let size = item.presentationSize
                manager.preferredDisplayCriteria = Self.criteria(fps: fps, width: Int(size.width), height: Int(size.height))
            } else {
                manager.preferredDisplayCriteria = item.asset.preferredDisplayCriteria
            }
        case .vlc:
            // O VLC desenha sempre em SDR: só a frame rate.
            guard let info = vlcVideoInfo else { return }
            var fps = info.fps
            // Emissões entrelaçadas (25/30 imagens) ficam a 50/60 com o desentrelaçamento "suave".
            if deinterlace == "yadif2x", abs(fps - 25) < 0.5 || abs(fps - 29.97) < 0.1 || abs(fps - 30) < 0.1 { fps *= 2 }
            manager.preferredDisplayCriteria = Self.criteria(fps: fps, width: info.width, height: info.height)
        }
    }

    private func clearDisplayCriteria() {
        guard controlsDisplay else { return }
        displayManager?.preferredDisplayCriteria = nil
    }

    /// O gestor do modo da TV; nil se não houver (o simulador não o tem e rebentava ao pedi-lo).
    private var displayManager: AVDisplayManager? {
        guard let window = view.window, window.responds(to: NSSelectorFromString("avDisplayManager")) else { return nil }
        return window.avDisplayManager
    }

    /// Um pedido de modo SDR com esta frame rate.
    private static func criteria(fps: Double, width: Int, height: Int) -> AVDisplayCriteria? {
        guard fps > 1 else { return nil }
        var description: CMVideoFormatDescription?
        let status = CMVideoFormatDescriptionCreate(allocator: kCFAllocatorDefault, codecType: kCMVideoCodecType_H264,
                                                    width: Int32(max(width, 16)), height: Int32(max(height, 16)),
                                                    extensions: nil, formatDescriptionOut: &description)
        guard status == noErr, let description else { return nil }
        return AVDisplayCriteria(refreshRate: Float(fps), formatDescription: description)
    }
}

/// A vista do leitor da Apple (a camada é a do vídeo).
final class AppleVideoView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

// MARK: Faixas, imagem e controlo

extension PlayerEngine {
    /// Onde vai o vídeo, em ms desde o início do que se pediu (no catch-up, desde o minuto pedido).
    var positionMs: Int64 {
        switch backend {
        case .vlc: return Int64(vlc.time.intValue)
        case .apple:
            let t = CMTimeGetSeconds(avPlayer.currentTime())
            return t.isFinite ? Int64(t * 1000) : 0
        }
    }

    /// Sem som (o canal sem foco quando há dois a dar).
    func setMuted(_ muted: Bool) {
        self.muted = muted
        vlc.audio?.isMuted = muted
        avPlayer.isMuted = muted
    }

    private func applyAudio() {
        switch backend {
        case .vlc:
            if muted { vlc.audio?.isMuted = true }
            vlc.audio?.passthrough = passthrough
        case .apple:
            avPlayer.isMuted = muted
        }
    }

    func togglePause() {
        guard state == .playing else { return }
        switch backend {
        case .vlc: if paused { vlc.play() } else { vlc.pause() }
        case .apple: if paused { avPlayer.play() } else { avPlayer.pause() }
        }
        paused.toggle()
    }

    /// Altura do vídeo ("1080p"), quando já se sabe.
    var resolution: String? {
        let h: Int
        switch backend {
        case .vlc: h = Int(vlc.videoSize.height)
        case .apple: h = Int(avPlayer.currentItem?.presentationSize.height ?? 0)
        }
        return h > 0 ? "\(h)p" : nil
    }

    /// Faixa surround por defeito (uma vez por canal, para não desfazer uma escolha manual).
    fileprivate func chooseSurround() {
        guard preferSurround, !surroundChosen, state == .playing else { return }
        switch backend {
        case .vlc:
            guard let tracks = vlc.media?.tracksInformation as? [[String: Any]] else { return }
            let audio = tracks.filter { ($0[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeAudio }
            guard !audio.isEmpty else { return }
            surroundChosen = true
            let best = audio.max { (($0[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.intValue ?? 0) < (($1[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.intValue ?? 0) }
            if let best, ((best[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.intValue ?? 0) > 2,
               let id = (best[VLCMediaTracksInformationId] as? NSNumber)?.int32Value, id != vlc.currentAudioTrackIndex {
                vlc.currentAudioTrackIndex = id
            }
        case .apple:
            guard let group = appleGroup(.audible), let item = avPlayer.currentItem else { return }
            surroundChosen = true
            let surround: Set<UInt32> = [0x6163_2D33, 0x6563_2D33] // 'ac-3', 'ec-3'
            if let option = group.options.first(where: { $0.mediaSubTypes.contains { surround.contains($0.uint32Value) } }),
               item.currentMediaSelection.selectedMediaOption(in: group) != option {
                item.select(option, in: group)
            }
        }
    }

    /// Linhas da "Informação do stream" (título, valor).
    var streamDetails: [(String, String)] {
        var rows: [(String, String)] = []
        switch backend {
        case .vlc:
            let tracks = vlc.media?.tracksInformation as? [[String: Any]] ?? []
            for t in tracks {
                let type = t[VLCMediaTracksInformationType] as? String
                let fourcc = (t[VLCMediaTracksInformationCodec] as? NSNumber)?.uint32Value ?? 0
                let codec = fourcc == 0 ? "" : VLCMedia.codecName(forFourCC: fourcc, trackType: type ?? "")
                if type == VLCMediaTracksInformationTypeVideo {
                    let w = (t[VLCMediaTracksInformationVideoWidth] as? NSNumber)?.intValue ?? 0
                    let h = (t[VLCMediaTracksInformationVideoHeight] as? NSNumber)?.intValue ?? 0
                    let num = (t[VLCMediaTracksInformationFrameRate] as? NSNumber)?.doubleValue ?? 0
                    let den = (t[VLCMediaTracksInformationFrameRateDenominator] as? NSNumber)?.doubleValue ?? 0
                    var parts = [codec]
                    if w > 0 { parts.append("\(w)×\(h)") }
                    if num > 0, den > 0 { parts.append(String(format: "%.2f fps", num / den)) }
                    rows.append(("video", parts.filter { !$0.isEmpty }.joined(separator: " · ")))
                } else if type == VLCMediaTracksInformationTypeAudio {
                    let ch = (t[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.intValue ?? 0
                    let rate = (t[VLCMediaTracksInformationAudioRate] as? NSNumber)?.intValue ?? 0
                    var parts = [codec]
                    if ch > 0 { parts.append(ch == 6 ? "5.1" : (ch == 8 ? "7.1" : "\(ch) ch")) }
                    if rate > 0 { parts.append("\(rate / 1000) kHz") }
                    rows.append(("audio", parts.filter { !$0.isEmpty }.joined(separator: " · ")))
                }
            }
        case .apple:
            guard let item = avPlayer.currentItem else { break }
            for track in item.tracks {
                guard let asset = track.assetTrack else { continue }
                let subtype = (asset.formatDescriptions.first).map { CMFormatDescriptionGetMediaSubType($0 as! CMFormatDescription) } ?? 0
                let codec = Self.fourCC(subtype)
                if asset.mediaType == .video {
                    let size = item.presentationSize
                    var parts = [codec, "\(Int(size.width))×\(Int(size.height))"]
                    if track.currentVideoFrameRate > 0 { parts.append(String(format: "%.2f fps", track.currentVideoFrameRate)) }
                    rows.append(("video", parts.joined(separator: " · ")))
                } else if asset.mediaType == .audio {
                    rows.append(("audio", codec))
                }
            }
            if let event = item.accessLog()?.events.last, event.indicatedBitrate > 0 {
                rows.append(("bitrate", String(format: "%.1f Mb/s", event.indicatedBitrate / 1_000_000)))
            }
        }
        return rows
    }

    private static func fourCC(_ code: FourCharCode) -> String {
        let chars = [24, 16, 8, 0].map { Character(UnicodeScalar(UInt8((code >> $0) & 0xFF))) }
        switch String(chars) {
        case "avc1", "avc3": return "H.264"
        case "hvc1", "hev1": return "HEVC"
        case "dvh1", "dvhe": return "Dolby Vision"
        case "mp4a": return "AAC"
        case "ac-3": return "Dolby Digital"
        case "ec-3": return "Dolby Digital Plus"
        default: return String(chars).trimmingCharacters(in: .whitespaces)
        }
    }

    /// O motor em uso, para a informação do stream.
    var backendName: String { backend == .apple ? "Apple (AVPlayer)" : "VLC" }

    // MARK: Faixas

    private func appleGroup(_ characteristic: AVMediaCharacteristic) -> AVMediaSelectionGroup? {
        avPlayer.currentItem?.asset.mediaSelectionGroup(forMediaCharacteristic: characteristic)
    }

    var audioTracks: [Track] {
        switch backend {
        case .vlc:
            return tracks(names: vlc.audioTrackNames, ids: vlc.audioTrackIndexes).filter { $0.id >= 0 }
        case .apple:
            return (appleGroup(.audible)?.options ?? []).enumerated().map { Track(id: Int32($0.offset), name: $0.element.displayName) }
        }
    }

    var currentAudio: Int32 {
        switch backend {
        case .vlc: return vlc.currentAudioTrackIndex
        case .apple:
            guard let group = appleGroup(.audible), let item = avPlayer.currentItem,
                  let selected = item.currentMediaSelection.selectedMediaOption(in: group),
                  let i = group.options.firstIndex(of: selected) else { return -1 }
            return Int32(i)
        }
    }

    func selectAudio(_ id: Int32) {
        switch backend {
        case .vlc: vlc.currentAudioTrackIndex = id
        case .apple:
            guard let group = appleGroup(.audible), group.options.indices.contains(Int(id)) else { return }
            avPlayer.currentItem?.select(group.options[Int(id)], in: group)
        }
    }

    /// Inclui "Desligadas" (id -1) em primeiro.
    var subtitleTracks: [Track] {
        switch backend {
        case .vlc:
            return tracks(names: vlc.videoSubTitlesNames, ids: vlc.videoSubTitlesIndexes)
        case .apple:
            guard let group = appleGroup(.legible) else { return [] }
            return [Track(id: -1, name: "")] + group.options.enumerated().map { Track(id: Int32($0.offset), name: $0.element.displayName) }
        }
    }

    var currentSubtitle: Int32 {
        switch backend {
        case .vlc: return vlc.currentVideoSubTitleIndex
        case .apple:
            guard let group = appleGroup(.legible), let item = avPlayer.currentItem,
                  let selected = item.currentMediaSelection.selectedMediaOption(in: group),
                  let i = group.options.firstIndex(of: selected) else { return -1 }
            return Int32(i)
        }
    }

    func selectSubtitle(_ id: Int32) {
        switch backend {
        case .vlc: vlc.currentVideoSubTitleIndex = id
        case .apple:
            guard let group = appleGroup(.legible) else { return }
            let option = group.options.indices.contains(Int(id)) ? group.options[Int(id)] : nil
            avPlayer.currentItem?.select(option, in: group)
        }
    }

    private func tracks(names: [Any], ids: [Any]) -> [Track] {
        zip(names, ids).compactMap { name, id in
            guard let n = id as? NSNumber else { return nil }
            return Track(id: n.int32Value, name: (name as? String) ?? "\(n)")
        }
    }

    // MARK: Imagem

    /// Ajustar: o vídeo inteiro; zoom: enche o ecrã cortando as pontas; esticar: enche deformando.
    func applyAspect() {
        switch aspect {
        case "zoom": appleView.playerLayer.videoGravity = .resizeAspectFill
        case "fill": appleView.playerLayer.videoGravity = .resize
        default: appleView.playerLayer.videoGravity = .resizeAspect
        }
        let ratio = "16:9"
        switch aspect {
        case "zoom":
            vlc.videoAspectRatio = nil
            vlc.videoCropGeometry = strdup(ratio)
        case "fill":
            vlc.videoCropGeometry = nil
            vlc.videoAspectRatio = strdup(ratio)
        default:
            vlc.videoCropGeometry = nil
            vlc.videoAspectRatio = nil
        }
    }
}

extension PlayerEngine: VLCMediaPlayerDelegate {
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in self.vlcStateChanged() }
    }
}
