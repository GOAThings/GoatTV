import AVFoundation
import AVKit
import CoreMedia
import Foundation
import GoatAether
import GoatCore
import Observation
#if os(tvOS)
import TVVLCKit
#else
import MobileVLCKit
#endif
import UIKit

/// O leitor de vídeo, com dois motores:
/// - o da Apple (AVPlayer) para HLS: descodificação por hardware, HDR10, Dolby Vision, HLG e Dolby Atmos;
/// - o VLC para o resto (`.ts`, MPEG-2, áudio MP2/DTS…), que é o que muitas listas IPTV dão.
/// Tenta as formas de abrir o canal por ordem (ts ⇄ m3u8; num HLS, primeiro o da Apple e depois o VLC) e, se a
/// stream cair, volta a ligar ao fim de uns segundos. Com "Igualar a frame rate", a TV muda para a cadência e o
/// HDR do canal (Definições do Apple TV → Vídeo e áudio → Igualar conteúdo).
/// Em direto, com "Pausar o direto", um .ts é gravado num anel em disco e o VLC lê-o de lá (ver Timeshift).
/// Experimental: com o motor "aether" (Definições → Player), os HLS abrem no da Apple e o resto no AetherEngine (ver
/// AetherPlayback), com a pausa do direto dele; o VLC fica para o que nenhum dos dois abrir.
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
        /// O AetherEngine (experimental).
        case aether
    }

    private(set) var state: State = .idle
    private(set) var paused = false
    /// A última imagem do canal está parada no ecrã, à espera que volte a dar.
    private(set) var frozen = false
    /// O vídeo já tem imagem. No VLC, o som e o tempo chegam primeiro: a imagem espera pela primeira imagem completa.
    private(set) var hasPicture = false
    /// O formato da forma que está a dar ("ts" ou "m3u8").
    private(set) var format: String?
    /// O motor que está a dar.
    private(set) var backend: Backend = .vlc
    /// Já esteve a dar neste canal (então uma falha é a stream que caiu: volta a ligar sempre).
    private(set) var wasPlaying = false
    /// Tentativas falhadas de abrir o canal (no máximo [maxStartRetries], como no Android).
    private(set) var retries = 0
    static let maxStartRetries = 3
    /// Caiu depois de estar a dar: a tentativa de voltar a ligar (1, 2…; 0 = não caiu). Volta a 0 quando dá ou se muda
    /// de canal (a cápsula no canto mostra-a, como no Android).
    private(set) var reconnects = 0
    /// Quando é a próxima tentativa de voltar a ligar (nil = a tentar agora) e quanto se espera por ela.
    private(set) var nextTryAt: Date?
    private(set) var retryDelay: TimeInterval = 0
    /// O código HTTP da última falha, quando se sabe (leitor da Apple).
    private(set) var lastHTTPStatus: Int?

    /// Não abriu (ou caiu): quem usa o leitor explica o erro.
    @ObservationIgnored var onFailed: (() -> Void)?
    /// Caiu a meio: devolve true se quem usa o leitor trata disso (ex.: o catch-up pede de novo daquele ponto).
    @ObservationIgnored var onDropped: (() -> Bool)?
    /// O direto abriu noutro formato que não o primeiro ("ts" ou "m3u8"): passa a ser o preferido (como no Android,
    /// só quando foi preciso o outro formato; o catch-up não conta).
    @ObservationIgnored var onFormatWorked: ((String) -> Void)?
    /// Começou a dar (ou voltou a dar depois de cair).
    @ObservationIgnored var onPlaying: (() -> Void)?
    /// O direto da lista principal não abriu depois das tentativas de sempre (os formatos, e os pedidos de cada um):
    /// devolve true se quem usa o leitor vai procurar o canal na lista de reserva (e chama depois [playBackup] ou
    /// [backupUnavailable]); false mostra o erro como sempre. Recebe o código HTTP da recusa, quando se sabe.
    @ObservationIgnored var onStartFailed: ((Int?) -> Bool)?
    /// Um direto novo da lista principal: devolve true se ela esteve em baixo há pouco e quem usa o leitor vai logo à
    /// reserva, sem a tentar (e chama depois [playBackup] ou, sem o canal na reserva, [openMain]).
    @ObservationIgnored var skipMain: (() -> Bool)?
    /// O canal da reserva começou a dar (o aviso "<lista> não responde. A dar da <reserva>.").
    @ObservationIgnored var onBackupPlaying: (() -> Void)?
    /// Um failover também não abriu: devolve true se quem usa o leitor passa ao seguinte (e chama [playBackup] outra
    /// vez); false volta à lista principal e mostra o erro.
    @ObservationIgnored var onBackupFailed: (() -> Bool)?

    // MARK: Lista de reserva

    /// A dar o canal da lista de reserva no lugar do da principal (o pedido da principal fica guardado para voltar).
    private(set) var onBackup = false
    /// A reserva também não abriu este canal: o cartão de erro diz as duas listas.
    private(set) var backupFailed = false
    @ObservationIgnored private var mainRequest: (candidates: [StreamCandidate], userAgent: String, referrer: String?, linkResolver: (() async -> [StreamCandidate]?)?)?
    /// Já se perguntou pela reserva nesta volta de tentativas (cada volta, desde a principal, pode passar a ela uma vez).
    @ObservationIgnored private var backupAsked = false

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
    /// "aether": o da Apple nos HLS, o AetherEngine no resto (experimental, só aqui; o Android lê-o como "exo").
    @ObservationIgnored var engine = "exo"
    /// Descodificar em software no VLC (para streams que o hardware não abre bem).
    @ObservationIgnored var softwareDecoder = false
    /// Desentrelaçar no VLC: "yadif2x" (50/60 imagens por segundo, mais suave), "yadif" ou "off".
    @ObservationIgnored var deinterlace = "yadif2x"
    /// O atraso do som (Prefs.audioDelayMs), no VLC.
    @ObservationIgnored var audioDelayMs = 0
    /// A velocidade no catch-up e atrás do direto (1; 1,25; 1,5; 2): volta a 1 noutro canal e ao chegar ao direto.
    var speed: Float = 1 {
        didSet {
            guard speed != oldValue else { return }
            // O relógio do anel conta o que já deu à velocidade antiga.
            if let since = ringPlayingSince {
                let now = Self.uptimeMs()
                ringPlayedMs += Int64(Double(now - since) * Double(oldValue))
                ringPlayingSince = now
            }
            vlc.rate = speed
            aetherPlayback?.rate = speed
            avPlayer.defaultRate = speed
            if avPlayer.rate != 0 { avPlayer.rate = speed }
        }
    }
    /// Quando a TV muda de frame rate (e de HDR) para a do canal (`afr_mode`): desligado, só no primeiro canal ou em
    /// cada canal (ver AfrSession).
    @ObservationIgnored var afrMode = Prefs.AfrMode.off {
        didSet { if afrMode != oldValue { afrModeChanged() } }
    }
    /// A cadência a pedir quando a do canal não se mede ao fim de [afrMeasureMs] (`afr_fallback`; "none" = esperar).
    @ObservationIgnored var afrFallback = Prefs.AfrFallback.none
    @ObservationIgnored private var afr = AfrSession()
    @ObservationIgnored private var afrTask: Task<Void, Never>?
    /// Ao fim disto sem cadência medida, usa-se a de recurso (como no Android).
    static let afrMeasureMs: Int64 = 3_000

    // MARK: Mudar de canal

    /// O atraso ao mudar de canal (`zap_delay_ms`, ver ZapPacer).
    @ObservationIgnored var zapDelayMs = Prefs.ZapDelay.auto
    @ObservationIgnored private var pacer = ZapPacer()
    /// O canal do último pedido (o direto ou o catch-up dele) e se era o direto, com as formas de o abrir.
    @ObservationIgnored private var openedKey: String?
    @ObservationIgnored private var openedLive = false
    @ObservationIgnored private var openedCandidates: [StreamCandidate] = []
    /// Parado a meio de uma passagem de canais (a imagem fica, a ligação foi largada), à espera de onde se parar.
    @ObservationIgnored private var held = false
    /// Largou a ligação para uma gravação abrir a sua (ver [releaseForRecording]).
    @ObservationIgnored private var waitingForRecording = false

    // MARK: Faixas (língua preferida)

    /// A língua do áudio das Definições (ISO 639-1; vazio = automático, a da app em [appLanguage]).
    @ObservationIgnored var audioLanguage = ""
    /// A língua da app (ISO 639-1), para o áudio em "Automático".
    @ObservationIgnored var appLanguage: String?
    /// A língua das legendas (vazio = sem legendas além das que o stream marca).
    @ObservationIgnored var subtitleLanguage = ""
    /// Este leitor liga as legendas na língua preferida (o segundo canal não).
    @ObservationIgnored var subtitlesByLanguage = true
    @ObservationIgnored private var trackChoice = TrackChoice()
    /// A faixa escolhida à mão neste canal (a língua e o nome), para a voltar a pôr ao reabrir o mesmo canal (catch-up,
    /// saltos, reconexões). Nas legendas, nil com a escolha feita = desligadas.
    @ObservationIgnored private var manualAudio: (code: String?, name: String)?
    @ObservationIgnored private var manualSubtitle: (code: String?, name: String)?
    /// "auto" (HDR quando o canal é HDR) ou "sdr" (a TV fica sempre em SDR).
    @ObservationIgnored var hdr = "auto"
    /// O leitor principal: a sua ligação é a única ao fornecedor (com a das gravações, ver ChannelFeed). O segundo canal
    /// tem a sua, à parte.
    @ObservationIgnored var ownsProviderConnection = true
    /// Só o leitor principal muda o modo da TV (o segundo canal não).
    @ObservationIgnored var controlsDisplay = true
    /// Mantém a última imagem do canal anterior até o novo começar.
    @ObservationIgnored var freezeFrame = true
    /// O tempo do leitor já é o do stream novo. Antes disso, é o do anterior.
    @ObservationIgnored private(set) var positionValid = false
    @ObservationIgnored private var savingSnapshot = false
    @ObservationIgnored private var movingSince: Date?
    /// O direto a dar mas com a posição parada (ver [watchProgress]).
    @ObservationIgnored private var stallTask: Task<Void, Never>?
    /// As vezes que o direto deste canal ficou preso (à segunda em pouco tempo, vai-se aos failovers).
    @ObservationIgnored private let stalls = StallCounter()
    /// Como foi a última falha: "sem resposta", e se a tentativa tinha chegado a dar (para decidir outra vez quando a
    /// procura dos failovers não der nada; ver [backupUnavailable]).
    @ObservationIgnored private var lastNoAnswer = false
    @ObservationIgnored private var lastStarted = false
    /// Escolhe a faixa de áudio com mais canais (5.1) quando o canal começa.
    @ObservationIgnored var preferSurround = false
    /// "fit", "zoom" (corta) ou "fill" (estica).
    @ObservationIgnored var aspect = "fit" {
        didSet { if aspect != oldValue { applyAspect() } }
    }

    // MARK: Pausar o direto

    /// O anel do canal a dar (ou a janela do HLS): pausar, recuar e voltar ao direto (ver Timeshift).
    let timeshift = Timeshift()
    /// A pausa do direto vale para este leitor: o principal, com a definição ligada (o segundo canal não).
    @ObservationIgnored var timeshiftEnabled = false
    /// Quando começou o programa do guia que dá a uma hora (ms desde 1970), ou nil sem guia: só se guarda o programa
    /// que se está a ver.
    @ObservationIgnored var programmeStart: ((Int64) -> Int64?)?
    /// O que se está a abrir é o direto (não o catch-up): pode ir pelo anel.
    @ObservationIgnored private var pausable = false
    /// Onde começar dentro do vídeo pedido (ms; 0 = no início).
    @ObservationIgnored private var openStartMs: Int64 = 0
    @ObservationIgnored private var timeshiftTicker: Task<Void, Never>?
    /// Quanto vídeo o VLC já mostrou desde que começou a ler o anel (ms), contado no relógio enquanto está a dar com
    /// imagem: o tempo do próprio VLC segue o PCR do emissor, que pode saltar (codificador reiniciado, canais feitos de
    /// ficheiros em ciclo), e vai à frente do que se vê quando lê do disco mais depressa do que em direto.
    @ObservationIgnored private var ringPlayedMs: Int64 = 0
    /// Desde quando (relógio do sistema, ms) está a dar sem parar; nil em pausa, a carregar ou ainda sem imagem.
    @ObservationIgnored private var ringPlayingSince: Int64?
    /// À espera de que a ligação do gravador anterior feche, antes de o leitor abrir a sua.
    @ObservationIgnored private var openTask: Task<Void, Never>?

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
    /// O AetherEngine só se cria na primeira vez que é preciso (com os outros motores, nunca).
    @ObservationIgnored private var aetherPlayback: AetherPlayback?
    private var aether: AetherPlayback {
        if let aetherPlayback { return aetherPlayback }
        let a = AetherPlayback()
        a.view.frame = view.bounds
        a.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        a.view.isHidden = true
        view.insertSubview(a.view, belowSubview: freezeView)
        a.onEvent = { [weak self] event in self?.aetherEvent(event) }
        aetherPlayback = a
        return a
    }

    @ObservationIgnored private var muted = false
    /// As escolhas à mão já repostas nesta abertura.
    @ObservationIgnored private var manualApplied: Set<TrackChoice.Kind> = []
    @ObservationIgnored private var observers: [NSKeyValueObservation] = []
    @ObservationIgnored private var notifications: [NSObjectProtocol] = []

    private struct Attempt {
        let candidate: StreamCandidate
        let backend: Backend
        /// A posição da forma na lista pedida (0 = o formato preferido).
        let candidateIndex: Int
    }

    @ObservationIgnored private var attempts: [Attempt] = []

    // MARK: Canais HDR no AetherEngine

    /// O canal aberto é HDR: o AetherEngine já lá viu HDR (ou o VLC deu-o em HEVC 4K).
    @ObservationIgnored private var openedHDR = false
    /// Os canais onde o AetherEngine viu HDR (ou que o VLC deu em HEVC 4K): abrem logo nele. Só neste aparelho.
    @ObservationIgnored private var hdrChannels = Set(UserDefaults.standard.stringArray(forKey: "aether_hdr_channels") ?? [])

    private func rememberHDR(_ key: String, _ hdr: Bool) {
        guard hdrChannels.contains(key) != hdr else { return }
        if hdr { hdrChannels.insert(key) } else { hdrChannels.remove(key) }
        UserDefaults.standard.set(Array(hdrChannels), forKey: "aether_hdr_channels")
        DebugLog.add("Player: \(hdr ? "HDR" : "SDR") channel, \(hdr ? "AetherEngine" : "VLC") from now on")
    }

    /// O VLC está a dar HEVC em 4K: quase sempre HDR, que ele mostra em SDR.
    private var vlcLooksHDR: Bool {
        let tracks = vlc.media?.tracksInformation as? [[String: Any]] ?? []
        return tracks.contains { t in
            guard (t[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeVideo else { return false }
            let fourcc = (t[VLCMediaTracksInformationCodec] as? NSNumber)?.uint32Value ?? 0
            let codec = VLCMedia.codecName(forFourCC: fourcc, trackType: VLCMediaTracksInformationTypeVideo).lowercased()
            let h = (t[VLCMediaTracksInformationVideoHeight] as? NSNumber)?.intValue ?? 0
            return (codec.contains("hevc") || codec.contains("265")) && h >= 2000
        }
    }

    /// Qual das [attempts] se está a abrir e se se chegou lá por uma recusa (então o formato não se aprende).
    @ObservationIgnored private var fallback = FormatFallback()
    private var index: Int { fallback.index }
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
        // O gravador da pausa do direto volta a ligar sozinho quando a ligação cai: a cápsula conta as tentativas.
        timeshift.onReconnect = { [weak self] attempt, at, wait in
            guard let self else { return }
            self.reconnects = attempt
            self.retryDelay = wait
            self.nextTryAt = at
        }
        timeshift.onConnected = { [weak self] in
            self?.reconnects = 0
            self?.nextTryAt = nil
        }
        timeshift.onNotTs = { [weak self] in self?.openDirect() }
    }

    /// Stalker: cada tentativa pede um link novo ao portal (os antigos caducam).
    @ObservationIgnored private var linkResolver: (() async -> [StreamCandidate]?)?

    private func makeAttempts(_ candidates: [StreamCandidate]) -> [Attempt] {
        // AetherEngine: um HLS abre primeiro no da Apple (o mais rápido a mudar de canal) e, se não der, no AetherEngine;
        // o resto (.ts…) abre no AetherEngine e, em último caso, no VLC.
        if engine == "aether" {
            return candidates.enumerated().flatMap { i, c -> [Attempt] in
                c.isHLS
                    ? [Attempt(candidate: c, backend: .apple, candidateIndex: i), Attempt(candidate: c, backend: .aether, candidateIndex: i)]
                    : [Attempt(candidate: c, backend: .aether, candidateIndex: i), Attempt(candidate: c, backend: .vlc, candidateIndex: i)]
            }
        }
        // Normal: um canal HDR que não é HLS abre no AetherEngine (o VLC dá sempre SDR) e, se não der, no VLC.
        return candidates.enumerated().flatMap { i, c -> [Attempt] in
            if engine != "vlc" && c.isHLS {
                return [Attempt(candidate: c, backend: .apple, candidateIndex: i), Attempt(candidate: c, backend: .vlc, candidateIndex: i)]
            }
            if engine == "exo" && openedHDR {
                return [Attempt(candidate: c, backend: .aether, candidateIndex: i), Attempt(candidate: c, backend: .vlc, candidateIndex: i)]
            }
            return [Attempt(candidate: c, backend: .vlc, candidateIndex: i)]
        }
    }

    /// Abre [channelKey]: o direto ([live]) ou outro pedido do mesmo canal (catch-up). [pausable]: o direto pode ir
    /// pelo anel da pausa do direto (só o leitor principal).
    ///
    /// Outro canal em direto passa pelo atraso ao mudar de canal ([ZapPacer]): a passar canais depressa, o canal que
    /// estava a dar pára (fica a última imagem) e larga a ligação, para o fornecedor a libertar enquanto se escolhe; só
    /// abre onde se parar, e uma mudança nova pelo meio cancela a espera. Voltar ao canal que ainda está a dar (a mesma
    /// lista outra vez, a lista que deu a volta) não abre nada.
    /// [startMs]: abrir já tanto depois do início do vídeo (o catch-up do Stalker, que é a gravação do programa todo).
    func play(_ candidates: [StreamCandidate], channelKey: String, live: Bool, userAgent: String?, referrer: String?,
              pausable: Bool = false, startMs: Int64 = 0, linkResolver: (() async -> [StreamCandidate]?)? = nil) {
        let same = openedKey == channelKey
        if !same || live != openedLive { speed = 1 }
        // Parado a meio de uma passagem de canais, voltar ao mesmo canal também é mais uma tecla.
        let zap = live && openedKey != nil && (!same || held)
        let back = !zap && live && openedLive && same && openedCandidates == candidates && (state == .opening || state == .playing)
        let pause: Int64 = zap || back ? pacer.delayFor(nowMs: Self.uptimeMs(), setting: zapDelayMs) : 0
        if back { return }
        openedKey = channelKey
        openedLive = live
        openedCandidates = candidates
        if !same {
            manualAudio = nil
            manualSubtitle = nil
            stalls.clear()
        }
        afr.newChannel()
        afrTask?.cancel()
        self.linkResolver = linkResolver
        self.pausable = pausable
        openStartMs = max(0, startMs)
        retry?.cancel()
        pendingStart?.cancel()
        positionValid = false
        savingSnapshot = false
        let wasShowing = state == .playing
        // Cada um vai numa opção do VLC ou num cabeçalho: só texto, sem nada que a possa partir em duas.
        let agent = userAgent.map(SafeInput.stripControl) ?? ""
        self.userAgent = agent.isEmpty ? iptvUserAgent : agent
        self.referrer = referrer.map(SafeInput.stripControl)
        openedHDR = hdrChannels.contains(channelKey)
        // Num HLS, primeiro o leitor da Apple e, se não abrir, o VLC com o mesmo endereço.
        attempts = makeAttempts(candidates)
        fallback.restart()
        paused = false
        wasPlaying = false
        retries = 0
        reconnects = 0
        nextTryAt = nil
        lastHTTPStatus = nil
        // Outro canal (ou o mesmo outra vez) começa sempre pela lista principal.
        onBackup = false
        backupFailed = false
        backupAsked = false
        mainRequest = nil
        // A lista principal esteve em baixo há pouco (ver ProviderOutage): espera-se pelo canal da reserva.
        if live, skipMain?() == true {
            watchdog?.cancel()
            stopBackends()
            hideFreeze()
            backupAsked = true
            state = .opening
            return
        }
        guard freezeFrame, wasShowing else {
            // Ainda a abrir (ex.: dois canais seguidos): a última imagem fica até o novo começar.
            if !freezeFrame { hideFreeze() }
            begin(after: pause)
            return
        }
        // A última imagem fica por cima até o canal novo começar.
        switch backend {
        case .apple:
            showFreeze(appleSnapshot())
            begin(after: pause)
        case .vlc:
            // Sem saída de vídeo (ainda a abrir, ou só som), o VLC lança uma exceção em vez de gravar a imagem.
            guard vlc.hasVideoOut else {
                begin(after: pause)
                return
            }
            // O VLC grava a imagem num ficheiro; espera-se um instante antes de parar o canal antigo.
            let path = NSTemporaryDirectory() + "goattv-freeze.png"
            try? FileManager.default.removeItem(atPath: path)
            vlc.saveVideoSnapshot(at: path, withWidth: 1280, andHeight: 720)
            savingSnapshot = true
            pendingStart = Task { [weak self] in
                // A gravação demora: sem a imagem pronta, o ecrã ficava preto enquanto o canal novo abre.
                var image: UIImage?
                for _ in 0..<15 {
                    try? await Task.sleep(for: .milliseconds(40))
                    guard !Task.isCancelled else { return }
                    image = UIImage(contentsOfFile: path)
                    if image != nil { break }
                }
                guard let self else { return }
                self.savingSnapshot = false
                self.showFreeze(image)
                self.begin(after: pause)
            }
        case .aether:
            // O motor apaga a imagem ao fechar: tira-se antes uma cópia do que está no ecrã (como as outras).
            showFreezeView(aetherPlayback?.view.snapshotView(afterScreenUpdates: false))
            begin(after: pause)
        }
    }

    /// Abre o pedido já, ou (a passar canais) depois de [pause] ms, com o canal anterior parado e a ligação largada.
    private func begin(after pause: Int64) {
        guard pause > 0 else {
            held = false
            return start()
        }
        DebugLog.add("Player: zap delay \(pause) ms")
        watchdog?.cancel()
        stopBackends()
        state = .opening
        held = true
        pendingStart = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(pause))
            guard !Task.isCancelled, let self else { return }
            self.held = false
            self.start()
        }
    }

    /// Uma gravação marcada deste canal vai abrir a sua ligação (com a pausa do direto desligada, o leitor tinha a sua):
    /// o leitor larga a dele primeiro (uma ligação de cada vez), e [reopenAfterRecording] volta a abrir o canal pelo anel
    /// da gravação.
    func releaseForRecording() {
        guard ownsProviderConnection, state != .idle, openedLive, !timeshift.usesRing else { return }
        retry?.cancel()
        watchdog?.cancel()
        stopBackends()
        state = .opening
        waitingForRecording = true
    }

    func reopenAfterRecording() {
        guard waitingForRecording else { return }
        waitingForRecording = false
        retryNow()
    }

    /// Volta a tentar já (OK no erro).
    func retryNow() {
        retry?.cancel()
        pendingStart?.cancel()
        backupAsked = false
        held = false
        nextTryAt = nil
        lastHTTPStatus = nil
        fallback.restart()
        start()
    }

    private func showFreeze(_ image: UIImage?) {
        freezeView.image = image
        freezeView.isHidden = image == nil
        frozen = image != nil
        if let image { view.bringSubviewToFront(freezeView); _ = image }
    }

    /// A última imagem do AetherEngine: uma cópia do ecrã (não há ficheiro nem imagem a pedir ao motor).
    @ObservationIgnored private var freezeCopy: UIView?

    private func showFreezeView(_ copy: UIView?) {
        freezeCopy?.removeFromSuperview()
        freezeCopy = copy
        guard let copy else { return }
        copy.frame = view.bounds
        copy.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.insertSubview(copy, aboveSubview: freezeView)
    }

    private func hideFreeze() {
        freezeCopy?.removeFromSuperview()
        freezeCopy = nil
        freezeView.isHidden = true
        freezeView.image = nil
        frozen = false
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
        afrTask?.cancel()
        held = false
        reconnects = 0
        nextTryAt = nil
        hideFreeze()
        stopBackends()
        hasPicture = false
        state = .idle
        // A TV volta ao modo normal: o AFR "ao começar" volta a mudar no próximo canal.
        clearDisplayCriteria()
        afr.reset()
    }

    private func stopBackends() {
        stallTask?.cancel()
        criteriaTask?.cancel()
        openTask?.cancel()
        timeshiftTicker?.cancel()
        // O anel apaga-se e a ligação do gravador fecha (outro canal, catch-up, erro, segundo plano).
        timeshift.clear()
        observers.removeAll()
        for n in notifications { NotificationCenter.default.removeObserver(n) }
        notifications.removeAll()
        vlc.stop()
        aetherPlayback?.stop()
        // O leitor da Apple fecha as ligações dele sozinho (e sem avisar quando): cancela-se já o que está a carregar.
        (avPlayer.currentItem?.asset as? AVURLAsset)?.cancelLoading()
        avPlayer.replaceCurrentItem(with: nil)
    }

    private func start() {
        // Uma gravação deste canal: abre-se o endereço que ela grava (é a mesma ligação).
        if index == 0, pausable, let feed = ChannelFeed.current, !feed.isClosed,
           let i = attempts.firstIndex(where: { $0.candidate.url == feed.url && $0.backend == .vlc }), i > 0 {
            fallback.restart(at: i)
        }
        guard let linkResolver, index == 0 else {
            open()
            return
        }
        watchdog?.cancel()
        pendingStart?.cancel()
        stopBackends()
        state = .opening
        pendingStart = Task { [weak self] in
            let list = await linkResolver()
            guard !Task.isCancelled, let self else { return }
            self.attempts = self.makeAttempts(list ?? [])
            self.open()
        }
    }

    private func open() {
        watchdog?.cancel()
        guard index < attempts.count, let url = URL(string: attempts[index].candidate.url) else {
            fail()
            return
        }
        stopBackends()
        resetTrackChoice()
        let attempt = attempts[index]
        backend = attempt.backend
        DebugLog.add("Player: opening \(attempt.candidate.format) with \(backend) (\(index + 1)/\(attempts.count))")
        vlcView.isHidden = backend != .vlc
        appleView.isHidden = backend != .apple
        if backend == .aether { aether.view.isHidden = false } else { aetherPlayback?.view.isHidden = true }
        hasPicture = false
        movingSince = nil
        state = .opening
        switch backend {
        case .vlc:
            // Pausar o direto: a ligação é do gravador, o VLC lê do anel. Sem ele, o VLC liga-se ao servidor. O canal que
            // uma gravação está a gravar abre sempre pelo anel dela (é a mesma ligação), mesmo sem a pausa do direto.
            if pausable, timeshiftEnabled || ChannelFeed.find(attempt.candidate.url) != nil, !attempt.candidate.isHLS,
               let local = timeshift.open(url, userAgent: userAgent, referrer: referrer, reconnectSeconds: reconnectSeconds) {
                startVLC(local, ring: true)
                startTimeshiftTicker()
            } else {
                whenConnectionFree { [weak self] in self?.startVLC(url) }
            }
        case .apple:
            whenConnectionFree { [weak self] in
                guard let self else { return }
                self.startApple(url)
                // HLS: a janela do próprio leitor da Apple, sem disco.
                if self.pausable, self.timeshiftEnabled {
                    self.timeshift.openHls()
                    self.startTimeshiftTicker()
                }
            }
        case .aether:
            // O canal que uma gravação está a gravar lê-se do anel dela (é a mesma ligação), sem pausa do direto. Nos
            // outros, a ligação é do motor e a pausa do direto é a dele (a janela em disco do AetherEngine).
            if pausable, ChannelFeed.find(attempt.candidate.url) != nil, !attempt.candidate.isHLS,
               let local = timeshift.open(url, userAgent: userAgent, referrer: referrer, reconnectSeconds: reconnectSeconds) {
                startAether(local, ring: true)
            } else {
                whenConnectionFree { [weak self] in
                    guard let self else { return }
                    self.startAether(url)
                    if self.pausable, self.timeshiftEnabled {
                        self.timeshift.openHls()
                        self.startTimeshiftTicker()
                    }
                }
            }
        }
        // Se não começar a dar em 8 s (10 no AetherEngine, que se junta ao direto com mais calma, ou com o buffer
        // grande), o servidor não respondeu: os failovers, ou a forma seguinte (como no Android).
        let patience = PlaybackRules.openTimeout(largeBuffer: cachingMs >= 3000, slowEngine: backend == .aether)
        watchdog = Task { [weak self] in
            try? await Task.sleep(for: .seconds(patience))
            guard !Task.isCancelled, let self, self.state == .opening else { return }
            DebugLog.add("Player: no picture after \(Int(patience)) s (\(self.backend), waiting: \(self.avPlayer.reasonForWaitingToPlay?.rawValue ?? "-"))")
            self.noAnswer()
        }
    }

    /// O direto não respondeu a tempo (ver [afterFailure]).
    private func noAnswer() { afterFailure(noAnswer: true) }

    /// A tentativa atual não começou a dar (ver [afterFailure]).
    private func next() { afterFailure() }

    /// Os failovers ainda se podem procurar: um direto da lista principal, sem os ter procurado nesta volta.
    private var canFailOver: Bool { openedLive && !onBackup && !backupAsked && onStartFailed != nil }

    /// A tentativa atual falhou: o passo seguinte é o das regras partilhadas com o Android (o outro formato, os
    /// failovers, voltar a ligar ou o erro; ver GoatCore PlaybackRules). Sem failovers quando se procuram, decide-se
    /// outra vez sem eles ([backupUnavailable]).
    private func afterFailure(noAnswer: Bool = false, started: Bool? = nil, failoverAllowed: Bool? = nil) {
        watchdog?.cancel()
        let started = started ?? (state == .playing)
        let status = currentHTTPStatus
        let hasNext = index + 1 < attempts.count
        // O catch-up caiu a meio (sem outro formato por tentar): quem usa o leitor pede de novo daquele ponto.
        if wasPlaying, !openedLive, started || !hasNext {
            stopBackends()
            if onDropped?() == true { return }
        }
        let step = PlaybackRules.nextStep(AttemptFailure(
            live: openedLive, started: started, wasPlaying: wasPlaying, onBackup: onBackup, hasNextFormat: hasNext,
            failoverAllowed: failoverAllowed ?? canFailOver, reconnect: reconnectSeconds > 0, noAnswer: noAnswer))
        lastNoAnswer = noAnswer
        lastStarted = started
        if step != .nextFormat {
            DebugLog.add("Player: failed (\(backend), was playing: \(wasPlaying), HTTP \(status.map(String.init) ?? "-")) → \(step)")
            if lastHTTPStatus == nil { lastHTTPStatus = status }
            stopBackends()
        }
        switch step {
        case .nextFormat:
            // Uma recusa de "ocupado" (403, 503…) já foi tentada uns 3 s pelo gravador (ver OpenRetry): é este formato
            // que o fornecedor recusa neste canal. Passa-se ao outro, mas sem o aprender para a conta (FormatFallback).
            if OpenRetry.isRefusal(status) { DebugLog.add("Player: refused (HTTP \(status.map(String.init) ?? "-")), trying the next form") }
            _ = fallback.next(after: status, count: attempts.count)
            open()
        case .failover:
            backupAsked = true
            if onStartFailed?(noAnswer ? nil : lastHTTPStatus) == true {
                state = .opening
            } else {
                afterFailure(noAnswer: noAnswer, started: started, failoverAllowed: false)
            }
        case .nextFailover:
            if onBackupFailed?() == true {
                state = .opening
                return
            }
            restoreMain()
            showFailure()
        case .reconnect:
            state = .opening
            reconnects += 1
            scheduleRetry(reconnecting: true)
        case .giveUp:
            showFailure()
        }
    }

    /// O direto a dar: se a posição não andar durante [PlaybackRules.stallSeconds] (sem estar em pausa), o fornecedor deixou de mandar
    /// sem fechar a ligação (nenhum leitor dá erro: a imagem ficava parada para sempre). Como no Android.
    private func watchProgress() {
        stallTask?.cancel()
        guard openedLive else { return }
        stallTask = Task { [weak self] in
            var last = Int64.min
            var since = Date()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled, let self, self.state == .playing, self.openedLive else { return }
                let position = self.positionMs
                if self.paused || position != last {
                    last = position
                    since = Date()
                    continue
                }
                if Date().timeIntervalSince(since) >= PlaybackRules.stallSeconds {
                    self.stalled()
                    return
                }
            }
        }
    }

    /// Preso: volta a ligar; se voltar a prender pouco depois, passa-se aos failovers do canal (se houver; ver StallCounter).
    private func stalled() {
        let failover = stalls.stalled(nowMs: Self.uptimeMs(), failoverAllowed: openedLive && !onBackup && onStartFailed != nil)
        DebugLog.add("Player: live stuck for \(Int(PlaybackRules.stallSeconds)) s (\(failover ? "failovers" : "reconnecting"))")
        if failover, let ask = onStartFailed {
            stopBackends()
            backupAsked = true
            lastNoAnswer = false
            lastStarted = true
            if ask(nil) {
                state = .opening
                return
            }
        }
        afterFailure(started: true)
    }

    /// Nenhuma forma abriu ou caiu a meio (ver [afterFailure]).
    private func fail() { afterFailure() }

    /// Não abriu: o erro e mais umas tentativas (desde a lista principal).
    private func showFailure() {
        state = .failed
        hideFreeze()
        // Sem nada para abrir (o catch-up do Stalker sem gravação deste programa): diz-se, sem voltar a tentar.
        let again = reconnectSeconds > 0 && retries < Self.maxStartRetries && (!attempts.isEmpty || linkResolver != nil)
        if again { retries += 1 }
        onFailed?()
        if again { scheduleRetry() }
    }

    /// A próxima tentativa daqui a [reconnectSeconds]. A voltar a ligar ([reconnecting]), a cápsula conta os segundos.
    private func scheduleRetry(reconnecting: Bool = false) {
        retry?.cancel()
        let delay = Double(max(1, reconnectSeconds))
        if reconnecting {
            retryDelay = delay
            nextTryAt = Date().addingTimeInterval(delay)
        }
        retry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.nextTryAt = nil
            // Outra volta desde o primeiro formato: o código da volta anterior já não conta.
            self.lastHTTPStatus = nil
            if !reconnecting { self.backupAsked = false }
            self.fallback.restart()
            self.start()
        }
    }

    /// O código HTTP com que o servidor recusou a tentativa atual, quando se sabe: o do leitor da Apple ou, pelo anel,
    /// o do gravador (que é quem fala com o servidor).
    private var currentHTTPStatus: Int? {
        if let lastHTTPStatus { return lastHTTPStatus }
        switch backend {
        case .apple: return appleHTTPStatus
        case .aether: return aetherPlayback?.httpStatus
        case .vlc:
            guard let status = timeshift.httpStatus, !(200..<300).contains(status) else { return nil }
            return status
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
        DebugLog.add("Player: playing (\(backend))")
        watchdog?.cancel()
        state = .playing
        watchProgress()
        wasPlaying = true
        retries = 0
        reconnects = 0
        nextTryAt = nil
        lastHTTPStatus = nil
        onPlaying?()
        // No VLC, "a dar" chega antes da primeira imagem: a imagem congelada sai quando o vídeo aparecer.
        if backend == .apple {
            positionValid = true
            hasPicture = true
            hideFreeze()
            checkApplePicture()
        } else if backend == .aether {
            positionValid = true
            hasPicture = true
            hideFreeze()
            DebugLog.add("Player: AetherEngine route \(aether.route)")
            // Normal: o AetherEngine diz se o canal é mesmo HDR; um SDR volta a abrir no VLC da próxima vez.
            if engine == "exo", !onBackup, let key = openedKey {
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(3))
                    guard let self, self.openedKey == key, self.backend == .aether, let hdr = self.aetherPlayback?.isHDR else { return }
                    self.rememberHDR(key, hdr)
                }
            }
        }
        let attempt = attempts.indices.contains(index) ? attempts[index] : nil
        format = attempt?.candidate.format
        // O outro formato é que deu (no direto, sem ser por uma recusa): fica a ser o preferido. A ordem das tentativas
        // não muda: ao voltar a ligar, começa-se sempre pela primeira (um .ts em baixo uns minutos volta a ser tentado
        // primeiro).
        // O formato da reserva é de outra conta: não se aprende.
        if let attempt, !onBackup, fallback.learns(candidateIndex: attempt.candidateIndex, live: openedLive) {
            onFormatWorked?(attempt.candidate.format)
        }
        if onBackup { onBackupPlaying?() }
        applyAspect()
        applyAudio()
        scheduleTrackChoice()
        startAfr()
    }

    /// Abre um failover (o mesmo canal da lista de reserva, ou outro da própria lista) no lugar do da principal (depois
    /// de [onStartFailed], de [onBackupFailed] para o seguinte, ou "experimentar agora"). A ligação anterior fecha antes
    /// ([open] pára o motor e larga o anel; ver [whenConnectionFree]); o ecrã continua no canal da principal e o próximo
    /// canal (ou [play] outra vez) volta a ela.
    func playBackup(_ candidates: [StreamCandidate], userAgent: String?, referrer: String?, linkResolver: (() async -> [StreamCandidate]?)? = nil) {
        guard openedKey != nil else { return }
        if !onBackup {
            mainRequest = (openedCandidates, self.userAgent, self.referrer, self.linkResolver)
        }
        onBackup = true
        backupAsked = true
        retry?.cancel()
        pendingStart?.cancel()
        let agent = userAgent.map(SafeInput.stripControl) ?? ""
        self.userAgent = agent.isEmpty ? iptvUserAgent : agent
        self.referrer = referrer.map(SafeInput.stripControl)
        self.linkResolver = linkResolver
        attempts = makeAttempts(candidates)
        fallback.restart()
        wasPlaying = false
        reconnects = 0
        nextTryAt = nil
        lastHTTPStatus = nil
        state = .opening
        start()
    }

    /// A lista principal estava em baixo mas a reserva não tem este canal: tenta-se a principal como sempre.
    func openMain() {
        guard state == .opening, !onBackup, openedKey != nil else { return }
        begin(after: 0)
    }

    /// A procura na reserva não deu (sem reserva, sem o canal ou na dúvida): decide-se outra vez, sem failovers (o
    /// outro formato, voltar a ligar ou o erro).
    func backupUnavailable() {
        guard state == .opening, !onBackup else { return }
        afterFailure(noAnswer: lastNoAnswer, started: lastStarted, failoverAllowed: false)
    }

    /// A reserva também falhou: as tentativas seguintes voltam a começar pela lista principal.
    private func restoreMain() {
        guard let main = mainRequest else { return }
        onBackup = false
        backupFailed = true
        mainRequest = nil
        userAgent = main.userAgent
        referrer = main.referrer
        linkResolver = main.linkResolver
        attempts = makeAttempts(main.candidates)
        fallback.restart()
    }

    // MARK: VLC

    /// [ring]: [url] é o anel local da pausa do direto (TS de certeza; no fim, quem reabre é o motor, não o VLC).
    private func startVLC(_ url: URL, ring: Bool = false) {
        let media = VLCMedia(url: url)
        media.addOption(":network-caching=\(cachingMs)")
        if ring {
            media.addOption(":demux=ts")
        } else {
            media.addOption(":http-user-agent=\(userAgent)")
            if let referrer, !referrer.isEmpty { media.addOption(":http-referrer=\(referrer)") }
            media.addOption(":http-reconnect")
            if openStartMs > 0 { media.addOption(":start-time=\(Double(openStartMs) / 1000)") }
        }
        if softwareDecoder { media.addOption(":codec=avcodec") }
        if audioDelayMs != 0 { media.addOption(":audio-desync=\(audioDelayMs)") }
        // A língua preferida (o libVLC entende ISO 639-1 e 639-2); as etiquetas e o surround escolhem-se depois
        // ([chooseTracks]). Uma escolha feita à mão neste canal ganha.
        let audio = audioLanguage.isEmpty ? (appLanguage ?? "") : audioLanguage
        if !audio.isEmpty, !trackChoice.isManual(.audio) { media.addOption(":audio-language=\(audio)") }
        if subtitlesByLanguage, !subtitleLanguage.isEmpty, !trackChoice.isManual(.text) { media.addOption(":sub-language=\(subtitleLanguage)") }
        vlc.media = media
        if deinterlace == "off" {
            vlc.setDeinterlaceFilter(nil)
        } else {
            vlc.setDeinterlace(.auto, withFilter: deinterlace)
        }
        // O som chega antes da imagem: só se ouve quando ela aparecer.
        vlc.audio?.isMuted = true
        vlc.play()
        // Cada stream novo (ex.: um salto no catch-up) começa a 1×: fica a velocidade escolhida.
        if speed != 1 { vlc.rate = speed }
    }

    fileprivate func vlcStateChanged() {
        guard backend == .vlc else { return }
        switch vlc.state {
        case .playing:
            becamePlaying()
        case .error:
            DebugLog.add("Player: VLC error")
            if continueRing() { return }
            next()
        case .ended:
            // Uma stream em direto que "acaba" caiu: volta a ligar. Pelo anel pode ser só o fim de uma ligação (há outra
            // a seguir) ou o ponto já ter sido reescrito: continua no sítio certo.
            DebugLog.add("Player: VLC stream ended")
            if continueRing() { return }
            if state == .playing { fail() } else { next() }
        default:
            break
        }
    }

    /// O estado lê-se depois de o VLC o mudar e o "playing" pode já ter passado: com o tempo a andar, está a dar.
    /// Sem isto, o vídeo aparece mas para a app continua a abrir, e ao fim de 15 s fecha uma ligação que funcionava.
    fileprivate func vlcTimeChanged() {
        guard backend == .vlc, !savingSnapshot else { return }
        if state == .playing {
            positionValid = true
            checkPicture()
        }
        guard state == .opening, vlc.isPlaying else { return }
        DebugLog.add("Player: VLC time moving without a playing state")
        becamePlaying()
    }

    private var picturesShown: Int { Int(vlc.media?.numberOfDisplayedPictures ?? 0) }

    /// A imagem congelada e o silêncio ficam até o VLC desenhar a primeira imagem.
    /// Se o canal só tiver som, ou se a imagem não chegar em 5 segundos, não se espera mais.
    private func checkPicture() {
        guard !hasPicture else { return }
        let since = movingSince ?? Date()
        movingSince = since
        guard picturesShown > 0 || soundOnly || Date().timeIntervalSince(since) > 5 else { return }
        hasPicture = true
        timeshift.seeking = false
        ringClock()
        vlc.audio?.isMuted = muted
        hideFreeze()
        // Normal: o VLC está a dar HEVC 4K (quase sempre HDR, que ele mostra em SDR): abre-se outra vez no AetherEngine.
        if engine == "exo", !openedHDR, !onBackup, let key = openedKey, vlcLooksHDR {
            rememberHDR(key, true)
            openedHDR = true
            attempts = makeAttempts(openedCandidates)
            fallback.restart()
            start()
        }
    }

    private var soundOnly: Bool {
        let types = (vlc.media?.tracksInformation as? [[String: Any]] ?? []).compactMap { $0[VLCMediaTracksInformationType] as? String }
        return types.contains(VLCMediaTracksInformationTypeAudio) && !types.contains(VLCMediaTracksInformationTypeVideo)
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

    /// Para o registo: há canais em que o leitor da Apple dá som sem imagem.
    private func checkApplePicture() {
        let item = avPlayer.currentItem
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard let self, self.backend == .apple, self.state == .playing, self.avPlayer.currentItem === item else { return }
            let size = item?.presentationSize ?? .zero
            DebugLog.add("Player: picture ready \(self.appleView.playerLayer.isReadyForDisplay), size \(Int(size.width))x\(Int(size.height))")
        }
    }

    private func appleStatus(_ item: AVPlayerItem) {
        guard backend == .apple, item === avPlayer.currentItem else { return }
        if item.status == .readyToPlay, openStartMs > 0, !item.duration.isIndefinite {
            item.seek(to: CMTime(value: openStartMs, timescale: 1000), completionHandler: nil)
        }
        guard item.status == .failed else { return }
        lastHTTPStatus = appleHTTPStatus
        DebugLog.add("Player: AVPlayer item failed: \(item.error.map { "\($0)" } ?? "-")")
        next()
    }

    private func appleFailed() {
        guard backend == .apple else { return }
        lastHTTPStatus = appleHTTPStatus
        DebugLog.add("Player: AVPlayer stopped with an error (HTTP \(lastHTTPStatus.map(String.init) ?? "-"))")
        if state == .playing { fail() } else { next() }
    }

    // MARK: AetherEngine

    /// [ring]: [url] é o anel local de uma gravação deste canal (sem pausa do direto, sem cabeçalhos).
    private func startAether(_ url: URL, ring: Bool = false) {
        var r = AetherPlayback.Request(url: url, live: openedLive)
        if !ring {
            var headers = ["User-Agent": userAgent]
            if let referrer, !referrer.isEmpty { headers["Referer"] = referrer }
            r.headers = headers
            if openStartMs > 0 { r.startSeconds = Double(openStartMs) / 1000 }
            // A pausa do direto é a janela do motor, com o mesmo limite que o anel tem sem guia.
            if openedLive, pausable, timeshiftEnabled { r.dvrSeconds = Double(Timeshift.noEpgAgeMs / 1000) }
        }
        let audio = audioLanguage.isEmpty ? (appLanguage ?? "") : audioLanguage
        if !audio.isEmpty, !trackChoice.isManual(.audio) { r.audioLanguages = [audio] }
        if subtitlesByLanguage, !subtitleLanguage.isEmpty, !trackChoice.isManual(.text) { r.subtitleLanguages = [subtitleLanguage] }
        r.audioDelaySeconds = Double(audioDelayMs) / 1000
        // O motor desentrelaça sozinho o que é entrelaçado (não dá para desligar): "normal" é a 25/30 imagens.
        r.fieldRate = deinterlace != "yadif"
        r.singleConnection = ownsProviderConnection
        r.secondary = !controlsDisplay
        // O modo da TV (frame rate e HDR) muda-o o próprio motor, quando o AFR está ligado e o HDR não está em "SDR".
        r.displayCriteria = controlsDisplay && afrMode != Prefs.AfrMode.off && hdr != "sdr"
        r.matchContent = displayMatchingEnabled ?? true
        let a = aether
        a.isMuted = muted
        a.setAspect(aspect)
        a.rate = speed
        a.open(r)
    }

    private func aetherEvent(_ event: AetherPlayback.Event) {
        guard backend == .aether else { return }
        switch event {
        case .playing:
            becamePlaying()
        case .failed, .ended:
            lastHTTPStatus = aetherPlayback?.httpStatus
            DebugLog.add("Player: AetherEngine \(event == .ended ? "ended" : "failed") (HTTP \(lastHTTPStatus.map(String.init) ?? "-"))")
            if state == .playing { fail() } else { next() }
        }
    }

    // MARK: Modo da TV (frame rate e HDR)

    /// O canal começou a dar: quando se souber a cadência dele, a TV muda para ela (conforme o modo, ver AfrSession).
    /// Sem medida ao fim de [afrMeasureMs], a cadência de recurso das Definições; sem ela, espera-se pela medida.
    private func startAfr() {
        afrTask?.cancel()
        // No AetherEngine é o motor que muda o modo da TV (ver startAether).
        guard controlsDisplay, backend != .aether, afrMode != Prefs.AfrMode.off, displayManager != nil else { return }
        let since = Self.uptimeMs()
        afrTask = Task { [weak self] in
            var counter = FrameRate.Counter()
            var usedFallback = false
            while !Task.isCancelled {
                guard let self, self.state == .playing else { return }
                if let fps = self.measuredFps(&counter) {
                    DebugLog.add("Player: frame rate \(fps) fps")
                    self.matchDisplay(fps: fps)
                    return
                }
                if !usedFallback, Self.uptimeMs() - since >= Self.afrMeasureMs {
                    usedFallback = true
                    if let fps = Prefs.AfrFallback.fps(self.afrFallback) {
                        DebugLog.add("Player: frame rate unknown, using \(fps)")
                        self.matchDisplay(fps: fps)
                        return
                    }
                }
                try? await Task.sleep(for: .milliseconds(250))
            }
        }
    }

    /// Mudou o modo nas Definições: desligado, a TV volta ao normal; ligado, vale já para o canal a dar.
    private func afrModeChanged() {
        afr.reset()
        if afrMode == Prefs.AfrMode.off {
            afrTask?.cancel()
            clearDisplayCriteria()
        } else if state == .playing {
            startAfr()
        }
    }

    /// A cadência do canal a dar, quando já se sabe (nil = ainda não). No leitor da Apple com HDR automático conta só
    /// que já se sabe: o pedido à TV vem do próprio stream, com o HDR.
    private func measuredFps(_ counter: inout FrameRate.Counter) -> Double? {
        switch backend {
        case .apple:
            guard let item = avPlayer.currentItem else { return nil }
            let track = item.tracks.first { $0.assetTrack?.mediaType == .video }
            let fps = Double(track?.currentVideoFrameRate ?? 0)
            return fps > 1 ? FrameRate.snap(fps) : nil
        case .aether:
            return nil
        case .vlc:
            // A que o stream diz (como no Android, a do formato ganha); sem ela, conta-se as imagens que o VLC mostra.
            if let info = vlcVideoInfo, info.fps > 1 {
                var fps = FrameRate.snap(info.fps)
                // Emissões entrelaçadas (25/30 imagens) ficam a 50/60 com o desentrelaçamento "suave".
                if deinterlace == "yadif2x", abs(fps - 25) < 0.5 || abs(fps - 29.97) < 0.1 || abs(fps - 30) < 0.1 { fps *= 2 }
                return fps
            }
            guard hasPicture else { return nil }
            return counter.add(ms: Self.uptimeMs(), frames: picturesShown)
        }
    }

    #if os(tvOS)
    /// Pede à TV a cadência [fps] (e, no leitor da Apple, o HDR do canal). Só funciona com "Igualar conteúdo" →
    /// "Igualar a frame rate" ligado no Apple TV.
    private func matchDisplay(fps: Double) {
        guard controlsDisplay, let manager = displayManager, let target = afr.onFps(mode: afrMode, fps: fps) else { return }
        switch backend {
        case .apple:
            guard let item = avPlayer.currentItem else { return }
            let size = item.presentationSize
            if hdr == "sdr" {
                manager.preferredDisplayCriteria = Self.criteria(fps: target, width: Int(size.width), height: Int(size.height))
            } else {
                manager.preferredDisplayCriteria = item.asset.preferredDisplayCriteria
            }
        case .vlc:
            // O VLC desenha sempre em SDR: só a frame rate.
            let size = vlcVideoInfo
            manager.preferredDisplayCriteria = Self.criteria(fps: target, width: size?.width ?? 1920, height: size?.height ?? 1080)
        case .aether:
            break
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

    /// "Igualar a frame rate" está ligado no Apple TV (sem ele a TV não muda de modo); nil se não se sabe.
    var displayMatchingEnabled: Bool? { displayManager?.isDisplayCriteriaMatchingEnabled }

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
    #else
    // O iPhone não muda o modo do ecrã.
    private func matchDisplay(fps: Double) {}
    private func clearDisplayCriteria() {}
    private var displayManager: AnyObject? { nil }
    var displayMatchingEnabled: Bool? { nil }
    #endif
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
        case .aether:
            let t = aetherPlayback?.positionSeconds ?? 0
            return t.isFinite ? Int64(t * 1000) : 0
        }
    }

    /// Sem som (o canal sem foco quando há dois a dar).
    func setMuted(_ muted: Bool) {
        self.muted = muted
        vlc.audio?.isMuted = muted
        avPlayer.isMuted = muted
        aetherPlayback?.isMuted = muted
    }

    private func applyAudio() {
        switch backend {
        case .vlc:
            if muted { vlc.audio?.isMuted = true }
            vlc.audio?.passthrough = passthrough
        case .apple:
            avPlayer.isMuted = muted
        case .aether:
            aetherPlayback?.isMuted = muted
        }
    }

    func togglePause() {
        guard state == .playing else { return }
        // Pausa do direto: se o ponto da pausa já saiu do anel, continua do mais antigo que há.
        if paused, backend == .vlc, let entry = timeshift.resumeEntry(positionMs: ringPosition) {
            reopenRing(entry, live: false)
            return
        }
        switch backend {
        case .vlc: if paused { vlc.play() } else { vlc.pause() }
        case .apple: if paused { avPlayer.play() } else { avPlayer.pause() }
        case .aether: if paused { aetherPlayback?.play() } else { aetherPlayback?.pause() }
        }
        paused.toggle()
        ringClock()
    }

    /// Altura do vídeo ("1080p"), quando já se sabe.
    var resolution: String? {
        let h: Int
        switch backend {
        case .vlc: h = Int(vlc.videoSize.height)
        case .apple: h = Int(avPlayer.currentItem?.presentationSize.height ?? 0)
        case .aether: h = aetherPlayback?.videoHeight ?? 0
        }
        return h > 0 ? "\(h)p" : nil
    }

    // MARK: Escolha automática das faixas

    /// Uma faixa como a escolha automática a vê, com o id do motor (o do VLC, ou a posição no grupo da Apple).
    private struct TrackOption {
        let id: Int32
        let name: String
        let info: TrackInfo
    }

    /// O áudio e as legendas que faltam escolher nesta abertura (a língua preferida e o surround), cada um quando as
    /// faixas dele aparecem. Uma escolha feita à mão no menu ([selectAudio], [selectSubtitle]) ganha às Definições e
    /// volta a pôr-se ao reabrir o mesmo canal (catch-up, saltos, reconexões), até se mudar de canal.
    fileprivate func chooseTracks() {
        guard state == .playing else { return }
        chooseAudio()
        if subtitlesByLanguage { chooseSubtitle() }
    }

    private func chooseAudio() {
        let options = audioOptions()
        guard !options.isEmpty else { return }
        if trackChoice.isManual(.audio) {
            guard !manualApplied.contains(.audio), let m = manualAudio else { return }
            manualApplied.insert(.audio)
            let pick = options.first { $0.name == m.name } ?? options.first { m.code != nil && $0.info.code == m.code }
            if let pick, !pick.info.selected { setAudio(pick.id) }
            return
        }
        guard trackChoice.pending(.audio) else { return }
        trackChoice.done(.audio)
        let language = audioLanguage.isEmpty ? appLanguage : audioLanguage
        if let i = TrackPick.audio(options.map(\.info), language: language, surround: preferSurround) { setAudio(options[i].id) }
    }

    private func chooseSubtitle() {
        let options = subtitleOptions()
        guard !options.isEmpty else { return }
        if trackChoice.isManual(.text) {
            guard !manualApplied.contains(.text) else { return }
            manualApplied.insert(.text)
            guard let m = manualSubtitle else {
                if options.contains(where: { $0.info.selected }) { setSubtitle(-1) }
                return
            }
            let pick = options.first { $0.name == m.name } ?? options.first { m.code != nil && $0.info.code == m.code }
            if let pick, !pick.info.selected { setSubtitle(pick.id) }
            return
        }
        guard trackChoice.pending(.text) else { return }
        trackChoice.done(.text)
        if let i = TrackPick.text(options.map(\.info), language: subtitleLanguage.isEmpty ? nil : subtitleLanguage), !options[i].info.selected {
            setSubtitle(options[i].id)
        }
    }

    /// Volta a escolher as faixas uns instantes depois de abrir (as faixas só se sabem quando o vídeo começa).
    private func scheduleTrackChoice() {
        criteriaTask?.cancel()
        criteriaTask = Task { [weak self] in
            for delay in [0.8, 2.5] {
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.chooseTracks()
            }
        }
    }

    /// Uma nova abertura do mesmo pedido (outra tentativa, uma reconexão, um salto no anel): as faixas escolhem-se
    /// outra vez, menos as que se escolheram à mão neste canal (essas repõem-se).
    private func resetTrackChoice() {
        trackChoice.onOpen(openedKey ?? "")
        manualApplied.removeAll()
    }

    private static let surroundTypes: Set<UInt32> = [0x6163_2D33, 0x6563_2D33] // 'ac-3', 'ec-3'

    private func audioOptions() -> [TrackOption] {
        switch backend {
        case .vlc:
            let names = Dictionary(audioTracks.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
            let current = vlc.currentAudioTrackIndex
            return vlcTracks(VLCMediaTracksInformationTypeAudio).map { t in
                let name = names[t.id] ?? t.description ?? ""
                return TrackOption(id: t.id, name: name, info: TrackInfo(language: t.language, label: name, channels: t.channels, selected: t.id == current))
            }
        case .apple:
            guard let group = appleGroup(.audible), let item = avPlayer.currentItem else { return [] }
            let selected = item.currentMediaSelection.selectedMediaOption(in: group)
            return group.options.enumerated().map { i, o in
                // O leitor da Apple não diz quantos canais tem cada faixa: Dolby (AC-3/E-AC-3) é o surround.
                let surround = o.mediaSubTypes.contains { Self.surroundTypes.contains($0.uint32Value) }
                return TrackOption(id: Int32(i), name: o.displayName,
                                   info: TrackInfo(language: o.extendedLanguageTag ?? o.locale?.identifier, label: o.displayName,
                                                   channels: surround ? 6 : 2, supported: o.isPlayable, selected: o == selected))
            }
        case .aether:
            return (aetherPlayback?.audioTracks ?? []).map { t in
                TrackOption(id: Int32(truncatingIfNeeded: t.id), name: t.name,
                            info: TrackInfo(language: t.language, label: t.name, channels: t.channels, selected: t.selected))
            }
        }
    }

    private func subtitleOptions() -> [TrackOption] {
        switch backend {
        case .vlc:
            let names = Dictionary(subtitleTracks.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
            let current = vlc.currentVideoSubTitleIndex
            return vlcTracks(VLCMediaTracksInformationTypeText).map { t in
                let name = names[t.id] ?? t.description ?? ""
                return TrackOption(id: t.id, name: name, info: TrackInfo(language: t.language, label: name, selected: t.id == current))
            }
        case .apple:
            guard let group = appleGroup(.legible), let item = avPlayer.currentItem else { return [] }
            let selected = item.currentMediaSelection.selectedMediaOption(in: group)
            return group.options.enumerated().map { i, o in
                TrackOption(id: Int32(i), name: o.displayName,
                            info: TrackInfo(language: o.extendedLanguageTag ?? o.locale?.identifier, label: o.displayName,
                                            supported: o.isPlayable, selected: o == selected,
                                            forced: o.hasMediaCharacteristic(.containsOnlyForcedSubtitles)))
            }
        case .aether:
            return (aetherPlayback?.subtitleTracks ?? []).map { t in
                TrackOption(id: Int32(truncatingIfNeeded: t.id), name: t.name,
                            info: TrackInfo(language: t.language, label: t.name, selected: t.selected, forced: t.forced))
            }
        }
    }

    /// As faixas de [type] que o VLC conhece: o id, a língua, a descrição e (no áudio) os canais.
    private func vlcTracks(_ type: String) -> [(id: Int32, language: String?, description: String?, channels: Int)] {
        let infos = vlc.media?.tracksInformation as? [[String: Any]] ?? []
        return infos.compactMap { t in
            guard (t[VLCMediaTracksInformationType] as? String) == type,
                  let id = (t[VLCMediaTracksInformationId] as? NSNumber)?.int32Value else { return nil }
            return (id: id, language: t[VLCMediaTracksInformationLanguage] as? String,
                    description: t[VLCMediaTracksInformationDescription] as? String,
                    channels: (t[VLCMediaTracksInformationAudioChannelsNumber] as? NSNumber)?.intValue ?? 0)
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
        case .aether:
            rows = aetherPlayback?.details ?? []
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
    var backendName: String {
        switch backend {
        case .apple: return "Apple (AVPlayer)"
        case .vlc: return "VLC"
        case .aether: return "AetherEngine"
        }
    }

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
        case .aether:
            return (aetherPlayback?.audioTracks ?? []).map { Track(id: Int32(truncatingIfNeeded: $0.id), name: $0.name) }
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
        case .aether:
            return (aetherPlayback?.audioTracks.first { $0.selected }).map { Int32(truncatingIfNeeded: $0.id) } ?? -1
        }
    }

    /// O menu das faixas: a escolha ganha às Definições até mudar de canal.
    func selectAudio(_ id: Int32) {
        trackChoice.onManual(.audio)
        manualApplied.insert(.audio)
        manualAudio = audioOptions().first { $0.id == id }.map { (code: $0.info.code, name: $0.name) }
        setAudio(id)
    }

    private func setAudio(_ id: Int32) {
        switch backend {
        case .vlc: vlc.currentAudioTrackIndex = id
        case .apple:
            guard let group = appleGroup(.audible), group.options.indices.contains(Int(id)) else { return }
            avPlayer.currentItem?.select(group.options[Int(id)], in: group)
        case .aether:
            aetherPlayback?.selectAudio(Int(id))
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
        case .aether:
            let tracks = aetherPlayback?.subtitleTracks ?? []
            guard !tracks.isEmpty else { return [] }
            return [Track(id: -1, name: "")] + tracks.map { Track(id: Int32(truncatingIfNeeded: $0.id), name: $0.name) }
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
        case .aether:
            return (aetherPlayback?.subtitleTracks.first { $0.selected }).map { Int32(truncatingIfNeeded: $0.id) } ?? -1
        }
    }

    /// O menu das legendas (-1 = desligadas): a escolha ganha às Definições até mudar de canal.
    func selectSubtitle(_ id: Int32) {
        trackChoice.onManual(.text)
        manualApplied.insert(.text)
        manualSubtitle = id < 0 ? nil : subtitleOptions().first { $0.id == id }.map { (code: $0.info.code, name: $0.name) }
        setSubtitle(id)
    }

    private func setSubtitle(_ id: Int32) {
        switch backend {
        case .vlc: vlc.currentVideoSubTitleIndex = id
        case .apple:
            guard let group = appleGroup(.legible) else { return }
            let option = group.options.indices.contains(Int(id)) ? group.options[Int(id)] : nil
            avPlayer.currentItem?.select(option, in: group)
        case .aether:
            aetherPlayback?.selectSubtitle(Int(id))
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
        aetherPlayback?.setAspect(aspect)
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

// MARK: Pausar o direto

extension PlayerEngine {
    /// Vai para [behindMs] atrás do direto (e continua a dar); a menos de 3 s, volta ao direto.
    func timeshiftSeek(behindMs: Int64) {
        guard state == .playing, timeshift.available else { return }
        if behindMs <= Timeshift.liveSnapMs { return timeshiftGoLive() }
        if timeshift.usesRing {
            if let entry = timeshift.entry(behind: behindMs) { reopenRing(entry, live: false) }
        } else if timeshift.hls, backend == .apple {
            seekApple(to: timeshift.hlsPosition(behind: behindMs))
        } else if timeshift.hls, backend == .aether {
            seekAether(to: timeshift.hlsPosition(behind: behindMs))
        }
    }

    /// Volta ao direto.
    func timeshiftGoLive() {
        guard state == .playing else { return }
        if timeshift.usesRing {
            if let entry = timeshift.liveEntry() {
                reopenRing(entry, live: true)
            } else if paused {
                togglePause()
            }
        } else if timeshift.hls, backend == .apple {
            seekApple(to: timeshift.hlsLivePosition())
        } else if timeshift.hls, backend == .aether {
            seekAether(to: timeshift.hlsLivePosition())
        }
    }

    /// O VLC volta a ler o anel a partir de [entry] (recuar, avançar, voltar ao direto, continuar depois do fim de
    /// uma ligação). O canal continua "a dar": sem a cápsula de sintonizar, nem a imagem congelada.
    fileprivate func reopenRing(_ entry: TimeshiftRing.Entry, live: Bool) {
        guard backend == .vlc, let url = timeshift.prepare(at: entry, live: live) else { return }
        DebugLog.add("Player: timeshift jump (\(live ? "live" : "behind"))")
        watchdog?.cancel()
        vlc.stop()
        ringPlayedMs = 0
        ringPlayingSince = nil
        paused = false
        positionValid = false
        hasPicture = false
        movingSince = nil
        startVLC(url, ring: true)
        resetTrackChoice()
        scheduleTrackChoice()
        // Se o VLC não voltar a dar, é como se o canal tivesse caído (volta a ligar, no direto).
        watchdog = Task { [weak self] in
            try? await Task.sleep(for: .seconds(15))
            guard !Task.isCancelled, let self, self.state == .playing, !self.positionValid else { return }
            DebugLog.add("Player: no picture after a timeshift jump")
            self.fail()
        }
    }

    /// O VLC chegou ao fim do que lia no anel: se há onde continuar, continua (e devolve true).
    fileprivate func continueRing() -> Bool {
        guard backend == .vlc, timeshift.usesRing, let next = timeshift.entryAfterEnd() else { return false }
        reopenRing(next.entry, live: next.live)
        return true
    }

    /// O stream afinal não é TS: o gravador já largou a ligação; o VLC abre-o diretamente, sem a pausa do direto.
    fileprivate func openDirect() {
        if backend == .aether, attempts.indices.contains(index), let url = URL(string: attempts[index].candidate.url) {
            // O mesmo no AetherEngine: larga o anel e liga-se ele.
            aetherPlayback?.stop()
            timeshift.clear()
            whenConnectionFree { [weak self] in self?.startAether(url) }
            return
        }
        guard backend == .vlc, attempts.indices.contains(index), let url = URL(string: attempts[index].candidate.url) else { return }
        // O VLC larga primeiro o anel (o fim da resposta local já não lhe interessa); depois fecha-se o resto.
        vlc.stop()
        timeshiftTicker?.cancel()
        timeshift.clear()
        whenConnectionFree { [weak self] in self?.startVLC(url) }
    }

    /// Uma ligação de cada vez: se a do gravador da pausa do direto ainda está a fechar, espera por ela (até 3 s).
    fileprivate func whenConnectionFree(_ work: @escaping () -> Void) {
        // Uma ligação sua (sem anel): a do anel (o direto anterior, ou uma gravação) fecha primeiro. Um ficheiro do
        // disco (uma gravação a ver) não liga ao fornecedor; o segundo canal tem a sua.
        let local = attempts.indices.contains(index) && URL(string: attempts[index].candidate.url)?.isFileURL == true
        if ownsProviderConnection, !local { ChannelFeed.yieldConnection() }
        guard ownsProviderConnection, !local, timeshift.closing else { return work() }
        let attempt = index
        openTask = Task { [weak self] in
            await self?.timeshift.waitClosed()
            guard !Task.isCancelled, let self, self.index == attempt else { return }
            work()
        }
    }

    fileprivate func startTimeshiftTicker() {
        timeshiftTicker?.cancel()
        timeshiftTicker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.updateTimeshift()
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
    }

    /// O estado da pausa do direto, de meio em meio segundo.
    private func updateTimeshift() {
        defer { if openedLive, timeshift.atLive, speed != 1 { speed = 1 } }
        if timeshift.usesRing {
            guard backend == .vlc else { return timeshift.unavailable() }
            ringClock()
            timeshift.update(positionMs: ringPosition, paused: paused, settled: state == .playing && hasPicture && !timeshift.seeking,
                             programmeStart: programmeStart ?? { _ in nil })
        } else if timeshift.hls, backend == .apple, let item = avPlayer.currentItem, item.duration.isIndefinite,
                  let range = item.seekableTimeRanges.last?.timeRangeValue {
            // Só um HLS em direto (sem duração): um filme em HLS já se pausa e anda como sempre.
            let oldest = CMTimeGetSeconds(range.start)
            let live = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
            let at = CMTimeGetSeconds(avPlayer.currentTime())
            guard oldest.isFinite, live.isFinite, at.isFinite, state == .playing else { return timeshift.unavailable() }
            timeshift.updateHls(end: Int64(live * 1000), oldest: Int64(oldest * 1000), position: Int64(at * 1000), paused: paused,
                                settled: avPlayer.timeControlStatus == .playing && !timeshift.seeking)
        } else if timeshift.hls, backend == .aether, state == .playing, let a = aetherPlayback, let w = a.liveWindow {
            // A janela em disco do AetherEngine, como a do HLS no leitor da Apple.
            timeshift.updateHls(end: w.end, oldest: w.oldest, position: w.position, paused: paused,
                                settled: a.settled && !timeshift.seeking)
        } else {
            timeshift.unavailable()
        }
    }

    /// Quanto vídeo o VLC já mostrou desde que começou a ler o anel (ver [ringPlayedMs]).
    private var ringPosition: Int64 {
        ringPlayedMs + (ringPlayingSince.map { Int64(Double(Self.uptimeMs() - $0) * Double(speed)) } ?? 0)
    }

    /// Acerta o relógio do anel com o estado do VLC: conta só a dar, com imagem e sem pausa.
    private func ringClock() {
        let running = backend == .vlc && state == .playing && hasPicture && !paused && vlc.isPlaying
        let now = Self.uptimeMs()
        if running {
            if ringPlayingSince == nil { ringPlayingSince = now }
        } else if let since = ringPlayingSince {
            ringPlayedMs += Int64(Double(now - since) * Double(speed))
            ringPlayingSince = nil
        }
    }

    private static func uptimeMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

    /// AetherEngine: salta para [ms] (o tempo dele) e continua a dar.
    private func seekAether(to ms: Int64) {
        paused = false
        aetherPlayback?.seek(toMs: ms) { [weak self] in self?.timeshift.seeking = false }
    }

    /// HLS: salta no AVPlayer para [ms] (o tempo dele) e continua a dar.
    private func seekApple(to ms: Int64) {
        paused = false
        avPlayer.seek(to: CMTime(value: max(0, ms), timescale: 1000)) { [weak self] _ in
            Task { @MainActor in self?.timeshift.seeking = false }
        }
        avPlayer.play()
    }
}

extension PlayerEngine: VLCMediaPlayerDelegate {
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor in self.vlcStateChanged() }
    }

    nonisolated func mediaPlayerTimeChanged(_ aNotification: Notification) {
        Task { @MainActor in self.vlcTimeChanged() }
    }
}
