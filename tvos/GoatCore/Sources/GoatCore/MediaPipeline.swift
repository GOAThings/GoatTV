import AudioToolbox
import CoreMedia
import Foundation
import GoatShared
import os

/// Uma faixa do canal aberto no motor GoatCore, como o núcleo partilhado a viu no PMT.
public struct MediaTrack: Equatable, Sendable {
    public enum Kind: Sendable { case video, audio, subtitle, data }

    public let pid: Int
    /// O formato pelo nome do núcleo partilhado ("H264", "HEVC", "MPEG2_VIDEO", "AAC_ADTS", "AC3", "EAC3", "MPEG_AUDIO"…).
    public let codec: String
    public let kind: Kind
    /// A língua que o canal diz (ISO 639-2, em minúsculas), se disser.
    public let language: String?
    /// O papel da faixa de áudio (GoatShared.AudioRole: 0 normal, 1 só efeitos, 2 para quem ouve mal, 3 audiodescrição,
    /// 4 original), pelo PMT (ou pelo HLS).
    public var role = 0
    /// Os canais da faixa: os que o PMT anuncia e, na que está a tocar, os que os blocos descodificados têm; 0 = não se sabe.
    public var channels = 0
    /// O NAME da faixa de um HLS, se disser.
    public var name: String?
}

/// Umas legendas do canal: uma faixa DVB (imagens; [page] 0) ou uma página do teletexto (a faixa [pid], a página
/// como se escreve: 0x888 = 888).
public struct TextTrack: Equatable, Sendable {
    public let pid: Int
    public let page: Int
    /// A língua que o canal diz (ISO 639-2, em minúsculas), se disser.
    public let language: String?
    /// Legendas para surdos (o tipo 5 do teletexto).
    public let hearingImpaired: Bool
    /// Legendas WebVTT de um HLS (cada PES uma fala com a duração).
    public var isWebVtt = false

    /// Um número só para a faixa (DVB: o PID) ou para a faixa e a página (teletexto, como no Android: acima de
    /// qualquer PID).
    public var key: Int { page == 0 ? pid : pid * 0x1000 + page }
    public var isTeletext: Bool { page != 0 }
    /// As legendas fechadas (CEA-608) que vêm dentro do vídeo.
    public var isCaptions: Bool { pid == TextTrack.captions }

    /// A faixa das legendas fechadas (CC1): um número que não é nenhum PID (como no Android).
    public static let captions = 0x7FFF_FFF0
}

/// O vídeo do canal pelo SPS: o tamanho, a cadência (0 se não disser), o HDR e a descrição do formato (que leva a cor
/// do VUI: é com ela que a TV muda para HDR).
public struct VideoDetails: @unchecked Sendable {
    public let width: Int
    public let height: Int
    public let fps: Double
    public let hdr10: Bool
    public let hlg: Bool
    public let format: CMVideoFormatDescription
    /// O SPS diz que as imagens podem vir entrelaçadas (os 576i e 1080i): a cadência do ecrã dobra com o
    /// desentrelaçamento "suave" (cada campo uma imagem).
    public var interlaced = false
}

/// Uma imagem de vídeo comprimida que o hardware não descodifica (MPEG-2): vai para um descodificador em software.
/// Tempos em 90 kHz, seguidos como os das outras amostras.
public struct CodedVideo: @unchecked Sendable {
    public let data: Data
    public let pts: Int64
    public let dts: Int64
    public let keyframe: Bool
}

/// Uma imagem das legendas DVB: onde fica no ecrã do emissor e os pixels (RGBA pré-multiplicado).
public struct SubtitleImage: Sendable {
    public let x: Int
    public let y: Int
    public let width: Int
    public let height: Int
    public let rgba: Data
}

/// As imagens a mostrar (vazio limpa) no ecrã do emissor ([displayWidth]×[displayHeight]) durante, no máximo,
/// [timeoutSeconds] (0 = até às seguintes).
public struct SubtitleImages: Sendable {
    public let displayWidth: Int
    public let displayHeight: Int
    public let images: [SubtitleImage]
    public let timeoutSeconds: Int
}

/// O motor GoatCore, do stream às amostras da Apple: o MPEG-TS que chega da rede é separado pelo núcleo partilhado
/// (TsDemuxer, NalReader, AudioFrameReader, os mesmos do Android) e sai em CMSampleBuffer comprimidos, prontos para o
/// AVSampleBufferDisplayLayer (que descodifica o H.264 e o HEVC pelo hardware) e o AVSampleBufferAudioRenderer.
///
/// Não é seguro entre threads: quem usa chama tudo da mesma fila. Os tempos vêm seguidos (sem as voltas dos 33 bits
/// nem os saltos do canal) e em 90 kHz.
public final class MediaPipeline {
    /// As faixas do canal (a primeira vez e sempre que mudam).
    public var onTracks: (([MediaTrack]) -> Void)?
    public var onVideo: ((CMSampleBuffer) -> Void)?
    public var onAudio: ((CMSampleBuffer) -> Void)?
    /// O canal saltou no tempo (outro encoder, o stream recomeçou). Os tempos que saem continuam seguidos (TsClock),
    /// por isso o leitor não precisa de fazer nada; serve para o registo.
    public var onJump: (() -> Void)?
    /// As legendas do teletexto que o canal tem (a primeira vez e sempre que mudam).
    public var onTextTracks: (([TextTrack]) -> Void)?
    /// O texto das legendas escolhidas quando muda: o tempo (90 kHz, no relógio do vídeo) e as linhas ("" limpa).
    public var onText: ((Int64, String) -> Void)?
    /// As imagens das legendas DVB escolhidas quando mudam, com o tempo (90 kHz, no relógio do vídeo).
    public var onImages: ((Int64, SubtitleImages) -> Void)?
    /// O vídeo (tamanho, cadência, HDR): a primeira vez e quando os parâmetros mudam.
    public var onVideoDetails: ((VideoDetails) -> Void)?
    /// O vídeo MPEG-2 (os canais SD de cabo e satélite), comprimido: o VideoToolbox não o descodifica, quem usa o
    /// pipeline descodifica-o com o FFmpeg. Sem quem o faça, o MPEG-2 conta como formato que este motor não toca.
    public var onCodedVideo: ((CodedVideo) -> Void)?
    /// O H.264 entrelaçado de 8 bits (os 576i e 1080i de cabo e satélite), comprimido, com o campo que foi captado
    /// primeiro e se a imagem é progressiva: o AVSampleBufferDisplayLayer não desentrelaça, quem usa o pipeline
    /// descodifica-o e desentrelaça-o. Sem quem o faça (a definição desligada), vai para [onVideo] como o resto.
    public var onInterlacedVideo: ((CMSampleBuffer, _ bottomFirst: Bool, _ progressive: Bool) -> Void)?
    /// Os canais entrelaçados vão para [onInterlacedVideo] para serem desentrelaçados (Definições → Desentrelaçar).
    /// Desligado, só lá vai o vídeo com ajustes da imagem ([adjustVideo]), e sempre como progressivo.
    public var deinterlaceVideo = true
    /// Há ajustes da imagem (Definições → Imagem): o H.264/HEVC de 8 bits SDR até [PictureAdjust.maxHeight] linhas
    /// vai também para [onInterlacedVideo] (como progressivo, se não for para desentrelaçar), para quem usa o pipeline
    /// o descodificar e ajustar. Pode mudar com o canal a dar: o caminho só troca numa imagem-chave.
    public var adjustVideo = false
    /// Aumentar a imagem (Definições → Imagem): diz, pelo tamanho do vídeo (largura, linhas), se vale a pena aumentá-lo.
    /// Com true, o H.264/HEVC de 8 bits SDR até [PictureAdjust.maxHeight] linhas vai também para [onInterlacedVideo]
    /// (como os ajustes); decide-se em cada imagem-chave, por isso o vídeo volta direto à camada se deixar de valer.
    public var upscaleVideo: ((Int, Int) -> Bool)?
    /// Uma faixa escolhida num formato que este motor ainda não toca (por exemplo vídeo MPEG-2): quem usa passa ao seguinte.
    public var onUnsupported: ((String) -> Void)?

    /// A faixa de áudio a tocar (o PID); nil = a primeira.
    public var audioPid: Int?

    private let listener = Listener()
    /// TS ou MP4 fragmentado (o fMP4 do HLS), escolhido pelos primeiros bytes.
    private var demuxer: StreamDemuxer
    private var tracks: [MediaTrack] = []
    private var videoPid: Int?
    private var nal: NalReader?
    private var mpeg2: Mpeg2Reader?
    private var videoFormat: CMVideoFormatDescription?
    /// O vídeo é HDR ou de mais de 8 bits: nunca vai para o desentrelaçador (não há emissões entrelaçadas assim).
    private var videoDeep = false
    /// As linhas do vídeo, pelo SPS (os ajustes da imagem têm um limite, ver [adjustVideo]).
    private var videoLines = 0
    private var videoWidth = 0
    /// O vídeo está a ir para [onInterlacedVideo] por causa dos ajustes da imagem (decide-se em cada imagem-chave).
    private var adjusting = false
    private var videoClock = TsClock()
    /// Só se manda vídeo a partir de uma imagem-chave, sem as imagens que dependem de antes dela (o verde e os borrões).
    private let videoStart = VideoStart()
    /// Faltaram dados: fica a última imagem boa até à próxima imagem-chave (ver ErrorConcealment). Sempre ligado.
    private let conceal = ErrorConcealment(capTicks: ErrorConcealment.companion.CAP_TICKS)
    /// Imagens que não foram para o descodificador por faltarem dados (para o painel das estatísticas).
    public var concealedFrames: Int { Int(conceal.dropped) }
    private var audio: AudioFrameReader?
    private var audioReaderPid: Int?
    private var audioFormat: CMAudioFormatDescription?
    private var audioFormatKey: GoatShared.AudioFormat?
    private var audioClock = TsClock()
    /// O áudio contado às amostras: onde começou a sequência sem buracos (em amostras, na [audioRunRate]) e quantas já
    /// foram. Ver [audioTime].
    private var audioRunStart: Int64 = -1
    private var audioRunSamples: Int64 = 0
    private var audioRunRate: Int32 = 0
    private var reportedUnsupported = false
    private var textTracks: [TextTrack] = []
    /// A faixa de legendas escolhida é WebVTT.
    private var webVtt = false
    private var teletext: TeletextDecoder?
    private var dvb: DvbSubtitleDecoder?
    private var rawTracks: [TsTrack] = []
    private var textPid: Int?
    /// Os últimos PES de cada faixa de legendas: ao escolher uma, o que já passou mostra-se logo (ver [selectText]).
    private var recentText: [Int: [TsSample]] = [:]
    private static let recentTextCount = 800

    // As legendas fechadas: descodificam-se sempre, pela ordem em que as imagens se mostram (com imagens B, a de
    // descodificação é outra); ao escolher, o ecrã de agora já se sabe.
    private var captions = GoatShared.CaptionDecoder()
    private var captionQueue: [(pts: Int64, pairs: KotlinByteArray)] = []
    private var captionsOn = false
    private var captionText = ""
    private var captionAt: Int64 = -1
    private static let captionReorder = 6

    /// As legendas a mostrar ([TextTrack.key]), ou nil para nenhumas.
    public func selectText(_ key: Int?) {
        teletext = nil
        dvb = nil
        webVtt = false
        textPid = nil
        captionsOn = key == TextTrack.captions
        if captionsOn {
            if captionAt >= 0 { onText?(captionAt, captionText) }
            return
        }
        guard let key, let t = textTracks.first(where: { $0.key == key }) else { return }
        webVtt = t.isWebVtt
        if t.isTeletext {
            teletext = TeletextDecoder(page: Int32(t.page), language: t.language)
        } else if t.isWebVtt {
            // Lê-se pelo PES (ver text()).
        } else {
            guard let raw = rawTracks.first(where: { Int($0.pid) == t.pid }) else { return }
            dvb = AppleMedia.shared.dvbDecoder(track: raw)
        }
        textPid = t.pid
        for s in recentText[t.pid] ?? [] { text(s) }
    }

    public init() {
        demuxer = StreamDemuxer(listener: listener)
        listener.owner = self
    }

    /// O que chegou da rede.
    public func feed(_ data: Data) {
        if let why = AppleMedia.shared.feedStream(demuxer: demuxer, data: data) { unsupported(why) }
    }

    /// O fim do stream: o que estava a meio sai.
    public func flush() { demuxer.flush() }

    /// Num TS, os pacotes que chegaram antes do PMT e se leram depois dele (para o registo do arranque, o GoatZap).
    public var replayedPackets: Int { Int(demuxer.replayedPackets) }

    /// Outra ligação: esquece tudo.
    public func reset() {
        demuxer = StreamDemuxer(listener: listener)
        tracks = []
        videoPid = nil
        nal = nil
        mpeg2 = nil
        videoFormat = nil
        videoDeep = false
        videoLines = 0
        videoWidth = 0
        adjusting = false
        videoClock.reset()
        videoStart.restart()
        conceal.reset()
        audio = nil
        audioReaderPid = nil
        audioFormat = nil
        audioFormatKey = nil
        audioClock.reset()
        audioRunStart = -1
        reportedUnsupported = false
        textTracks = []
        rawTracks = []
        recentText = [:]
        captions = GoatShared.CaptionDecoder()
        captionQueue = []
        captionsOn = false
        captionText = ""
        captionAt = -1
        teletext = nil
        dvb = nil
        textPid = nil
    }

    // MARK: Do núcleo partilhado

    private final class Listener: NSObject, TsListener {
        weak var owner: MediaPipeline?

        func onTracks(tracks: [TsTrack]) { owner?.tracksChanged(tracks) }

        func onSample(track: TsTrack, sample: TsSample) { owner?.sample(track, sample) }
    }

    private func tracksChanged(_ list: [TsTrack]) {
        tracks = list.map { t in
            let kind: MediaTrack.Kind = switch t.kind.name {
            case "VIDEO": .video
            case "AUDIO": .audio
            case "SUBTITLE": .subtitle
            default: .data
            }
            return MediaTrack(pid: Int(t.pid), codec: t.codec.name, kind: kind, language: t.language,
                              role: Int(t.role), channels: Int(t.channels), name: t.name)
        }
        rawTracks = list
        let hadCaptions = textTracks.contains { $0.isCaptions }
        textTracks = list.flatMap { t -> [TextTrack] in
            if t.codec.name == "DVB_SUBTITLE" {
                return [TextTrack(pid: Int(t.pid), page: 0, language: t.language, hearingImpaired: false)]
            }
            if t.codec.name == "WEBVTT" {
                return [TextTrack(pid: Int(t.pid), page: 0, language: t.language, hearingImpaired: false, isWebVtt: true)]
            }
            return AppleMedia.shared.teletextPages(track: t).map { p in
                TextTrack(pid: Int(t.pid), page: Int(p.page), language: p.language ?? t.language, hearingImpaired: p.type == 5)
            }
        }
        if hadCaptions { textTracks.append(TextTrack(pid: TextTrack.captions, page: 0, language: nil, hearingImpaired: true)) }
        onTextTracks?(textTracks)
        let video = tracks.first { $0.kind == .video }
        if video?.pid != videoPid {
            videoPid = video?.pid
            videoFormat = nil
            nal = nil
            mpeg2 = nil
            if let video {
                switch video.codec {
                case "H264": nal = NalReader(hevc: false)
                case "HEVC": nal = NalReader(hevc: true)
                case "MPEG2_VIDEO" where onCodedVideo != nil: mpeg2 = Mpeg2Reader()
                default: unsupported("video \(video.codec)")
                }
            }
        }
        onTracks?(tracks)
    }

    private func unsupported(_ what: String) {
        guard !reportedUnsupported else { return }
        reportedUnsupported = true
        onUnsupported?(what)
    }

    private func sample(_ track: TsTrack, _ sample: TsSample) {
        let pid = Int(track.pid)
        if track.kind.name == "SUBTITLE" {
            var recent = recentText[pid] ?? []
            recent.append(sample)
            if recent.count > Self.recentTextCount { recent.removeFirst(recent.count - Self.recentTextCount) }
            recentText[pid] = recent
            if pid == textPid { text(sample) }
            return
        }
        if pid == videoPid {
            video(sample)
        } else if pid == (audioPid ?? tracks.first { $0.kind == .audio }?.pid) {
            audio(track, sample)
        }
    }

    // MARK: Legendas

    /// O cc_data desta imagem (608 e 708), por ordem de apresentação (umas imagens de folga chegam para as B), ao
    /// descodificador, que escolhe o 608 ou o 708.
    private func readCaptions(_ data: KotlinByteArray, pts: Int64) {
        let hevc = videoPid.flatMap { p in tracks.first { $0.pid == p } }?.codec == "HEVC"
        let cc = ClosedCaptions.shared.ccData(data: data, hevc: hevc, mpeg2: false)
        guard cc.size > 0 || !captionQueue.isEmpty else { return }
        queueCaptions(cc, pts: pts)
    }

    private func queueCaptions(_ cc: KotlinByteArray, pts: Int64) {
        captionQueue.append((pts, cc))
        guard captionQueue.count > Self.captionReorder,
              let i = captionQueue.indices.min(by: { captionQueue[$0].pts < captionQueue[$1].pts }) else { return }
        let next = captionQueue.remove(at: i)
        let lines = captions.feed(triples: next.pairs)
        if captions.found, !textTracks.contains(where: { $0.isCaptions }) {
            textTracks.append(TextTrack(pid: TextTrack.captions, page: 0, language: nil, hearingImpaired: true))
            onTextTracks?(textTracks)
        }
        guard let lines else { return }
        captionText = lines.joined(separator: "\n")
        captionAt = next.pts
        if captionsOn { onText?(next.pts, captionText) }
    }

    /// Um PES das legendas escolhidas: o texto (teletexto) ou as imagens (DVB), quando mudam, no tempo do vídeo (ou
    /// do som, sem vídeo).
    private func text(_ sample: TsSample) {
        let clock = videoPid != nil ? videoClock : audioClock
        if webVtt {
            // Uma fala: aparece no início e sai no fim (a fala seguinte, se vier antes, toma o lugar dela).
            let pts = clock.map(ts: sample.pts)
            guard pts >= 0 else { return }
            onText?(pts, GoatShared.WebVtt.shared.text(data: sample.data))
            onText?(pts + GoatShared.WebVtt.shared.durationMs(data: sample.data) * 90, "")
        } else if let decoder = teletext {
            guard let text = AppleMedia.shared.teletextText(decoder: decoder, sample: sample) else { return }
            let pts = clock.map(ts: sample.pts)
            if pts >= 0 { onText?(pts, text) }
        } else if let decoder = dvb {
            guard let page = AppleMedia.shared.dvbPage(decoder: decoder, sample: sample) else { return }
            let pts = clock.map(ts: sample.pts)
            guard pts >= 0 else { return }
            let images = page.images.map { i in
                SubtitleImage(x: Int(i.x), y: Int(i.y), width: Int(i.width), height: Int(i.height), rgba: AppleMedia.shared.pixels(image: i) as Data)
            }
            onImages?(pts, SubtitleImages(displayWidth: Int(page.displayWidth), displayHeight: Int(page.displayHeight),
                                          images: images, timeoutSeconds: Int(page.timeoutSeconds)))
        }
    }

    // MARK: Vídeo

    private func video(_ sample: TsSample) {
        if mpeg2 != nil {
            mpeg2Video(sample)
            return
        }
        guard let nal else { return }
        let samplePts = videoClock.unwrap(ts: sample.pts)
        if videoClock.jumped {
            videoStart.restart()
            // As legendas guardadas são do tempo de antes do salto: já não se acertam com a imagem.
            recentText = [:]
            onJump?()
        }
        concealLoss(sample)
        let sampleDts = sample.dts == sample.pts ? samplePts : videoClock.unwrap(ts: sample.dts)
        readCaptions(sample.data, pts: samplePts)
        // Um canal com intra refresh (sem imagens I) só diz por onde se pode voltar com o SEI recovery point.
        let recovery = conceal.concealing && ErrorConcealment.companion.recoveryPoint(
            hevc: videoPid.flatMap { p in tracks.first { $0.pid == p } }?.codec == "HEVC", d: sample.data)
        // Numa imagem entrelaçada por campos, sai com o segundo campo (os dois juntos, com os tempos do primeiro).
        guard let unit = nal.next(annexB: sample.data, pts: samplePts, dts: sampleDts) else { return }
        if !unit.parameterSets.isEmpty {
            let sets = unit.parameterSets.map { AppleMedia.shared.data(bytes: $0) as Data }
            let hevc = videoPid.flatMap { p in tracks.first { $0.pid == p } }?.codec == "HEVC"
            if let f = Self.videoFormat(sets, hevc: hevc) {
                let changed = videoFormat.map { !CMFormatDescriptionEqual($0, otherFormatDescription: f) } ?? true
                videoFormat = f
                if changed, let info = AppleMedia.shared.videoInfo(sets: unit.parameterSets, hevc: hevc) {
                    videoDeep = info.bitDepth > 8 || info.isHdr
                    videoLines = Int(info.height)
                    videoWidth = Int(info.width)
                    onVideoDetails?(VideoDetails(width: Int(info.width), height: Int(info.height), fps: Double(info.fps),
                                                 hdr10: info.isHdr10, hlg: info.isHlg, format: f, interlaced: info.interlaced))
                }
            }
        }
        // Até à primeira imagem-chave com os parâmetros não há nada que se possa mostrar.
        guard let format = videoFormat else { return }
        let pts = unit.pts
        let dts = unit.dts
        let deinterlacing = nal.interlaced && deinterlaceVideo && onInterlacedVideo != nil
        if unit.keyframe {
            let upscale = upscaleVideo?(videoWidth, videoLines) ?? false
            let adjust = (adjustVideo || upscale) && onInterlacedVideo != nil && !videoDeep && videoLines <= PictureAdjust.maxHeight
            if adjust != adjusting {
                adjusting = adjust
                // O vídeo muda de descodificador (o da app ou o da camada), que começa nesta imagem-chave: as imagens
                // que dependem de antes dela não passam (ver VideoStart).
                if !deinterlacing { videoStart.restart() }
            }
        }
        guard videoStart.pass(keyframe: unit.keyframe, pts: pts), concealed(keyframe: unit.keyframe || recovery, pts: pts) else { return }
        let data = AppleMedia.shared.data(bytes: unit.data) as Data
        guard let buffer = Self.sampleBuffer(data, format: format, pts: pts, dts: dts, duration: .invalid, samples: 1, packetSizes: nil) else { return }
        if !unit.keyframe, let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: true) as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_NotSync] = true
            attachments.first?[kCMSampleAttachmentKey_DependsOnOthers] = true
        }
        // Entrelaçado: todas as imagens do canal vão para o desentrelaçador (as referências de umas são as outras).
        // Com ajustes da imagem vai o vídeo todo, e o que não é para desentrelaçar vai como progressivo.
        if let interlaced = onInterlacedVideo, !videoDeep, deinterlacing || adjusting {
            interlaced(buffer, unit.bottomFirst, deinterlacing ? unit.progressive : true)
            return
        }
        onVideo?(buffer)
    }

    /// MPEG-2: a imagem sai como vem (com o cabeçalho da sequência nas imagens I), a partir da primeira imagem-chave
    /// com ele, para o descodificador em software.
    private func mpeg2Video(_ sample: TsSample) {
        guard let mpeg2 else { return }
        let samplePts = videoClock.unwrap(ts: sample.pts)
        if videoClock.jumped {
            videoStart.restart()
            recentText = [:]
            onJump?()
        }
        concealLoss(sample)
        let sampleDts = sample.dts == sample.pts ? samplePts : videoClock.unwrap(ts: sample.dts)
        let cc = ClosedCaptions.shared.ccData(data: sample.data, hevc: false, mpeg2: true)
        if cc.size > 0 || !captionQueue.isEmpty { queueCaptions(cc, pts: samplePts) }
        let unit = mpeg2.next(data: sample.data, pts: samplePts, dts: sampleDts)
        guard let header = mpeg2.sequenceHeader, videoStart.pass(keyframe: unit.keyframe, pts: unit.pts),
              concealed(keyframe: unit.keyframe, pts: unit.pts) else { return }
        var data = AppleMedia.shared.data(bytes: unit.data) as Data
        // Uma imagem I sem o cabeçalho da sequência (há emissores que só o mandam de vez em quando): leva-o à frente,
        // para o descodificador poder começar nela.
        if unit.keyframe, data.range(of: Data([0, 0, 1, 0xB3])) == nil {
            data = (AppleMedia.shared.data(bytes: header) as Data) + data
        }
        onCodedVideo?(CodedVideo(data: data, pts: unit.pts, dts: unit.dts, keyframe: unit.keyframe))
    }

    /// Faltaram dados antes desta imagem (não um salto marcado pelo fornecedor, que se descodifica na mesma): ver
    /// ErrorConcealment. Num MP4 fragmentado, a discontinuity é um moov novo: recomeça numa imagem-chave.
    private func concealLoss(_ sample: TsSample) {
        if sample.corrupt { conceal.lost(onlyNonReference: sample.lostNonReference) }
        if sample.discontinuity, !sample.corrupt, demuxer.isMp4 { videoStart.restart() }
    }

    /// ErrorConcealment.pass e, nas versões de testes, o registo de cada espera.
    private func concealed(keyframe: Bool, pts: Int64) -> Bool {
        let waiting = conceal.concealing
        let pass = conceal.pass(keyframe: keyframe, pts: pts)
        #if DEBUG
        if waiting, !conceal.concealing {
            os_log("GoatConceal: held %ld frames, resumed on %{public}@; dropped=%ld capped=%ld",
                   Int(conceal.lastRun), keyframe ? "keyframe" : "cap", Int(conceal.dropped), Int(conceal.capped))
        }
        #endif
        return pass
    }

    static func videoFormat(_ sets: [Data], hevc: Bool) -> CMVideoFormatDescription? {
        var format: CMVideoFormatDescription?
        let pointers = sets.map { d -> UnsafeMutablePointer<UInt8> in
            let p = UnsafeMutablePointer<UInt8>.allocate(capacity: max(d.count, 1))
            d.copyBytes(to: p, count: d.count)
            return p
        }
        defer { pointers.forEach { $0.deallocate() } }
        let sizes = sets.map(\.count)
        let status = pointers.map { UnsafePointer($0) }.withUnsafeBufferPointer { ptrs in
            sizes.withUnsafeBufferPointer { lens in
                hevc
                    ? CMVideoFormatDescriptionCreateFromHEVCParameterSets(allocator: nil, parameterSetCount: sets.count, parameterSetPointers: ptrs.baseAddress!,
                                                                         parameterSetSizes: lens.baseAddress!, nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &format)
                    : CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: nil, parameterSetCount: sets.count, parameterSetPointers: ptrs.baseAddress!,
                                                                         parameterSetSizes: lens.baseAddress!, nalUnitHeaderLength: 4, formatDescriptionOut: &format)
            }
        }
        return status == noErr ? format : nil
    }

    // MARK: Áudio

    private func audio(_ track: TsTrack, _ sample: TsSample) {
        let pid = Int(track.pid)
        if audioReaderPid != pid {
            audioReaderPid = pid
            audioFormat = nil
            audioFormatKey = nil
            audioClock.reset()
            audioRunStart = -1
            switch track.codec.name {
            case "AAC_ADTS", "AAC_LATM", "AC3", "EAC3", "MPEG_AUDIO": audio = AudioFrameReader(codec: track.codec)
            default:
                audio = nil
                unsupported("audio \(track.codec.name)")
            }
        }
        guard let reader = audio else { return }
        let frames = reader.frames(data: sample.data, pts: sample.pts, discontinuity: sample.discontinuity)
        guard !frames.isEmpty, let f = reader.format else { return }
        if audioFormatKey != f || audioFormat == nil {
            audioFormatKey = f
            audioFormat = Self.audioFormat(f)
            if audioFormat == nil { unsupported("audio \(f.codec.name) \(f.sampleRate) Hz \(f.channels) ch") }
            // Os canais de verdade da faixa que toca: o nome dela passa a tê-los (ver AudioNames).
            if let i = tracks.firstIndex(where: { $0.pid == pid }), tracks[i].channels != Int(f.channels) {
                tracks[i].channels = Int(f.channels)
                onTracks?(tracks)
            }
        }
        guard let format = audioFormat else { return }
        // Um bloco por amostra (com a descrição do pacote): o renderizador decide como os junta.
        for frame in frames {
            let pts = audioClock.unwrap(ts: frame.pts)
            if audioClock.jumped {
                onJump?()
                audioRunStart = -1
            }
            let data = AppleMedia.shared.data(bytes: frame.data) as Data
            let rate = CMTimeScale(f.sampleRate)
            let duration = CMTime(value: CMTimeValue(frame.samples), timescale: rate)
            let time = audioTime(pts, rate: rate, samples: Int64(frame.samples))
            if let buffer = Self.sampleBuffer(data, format: format, time: time, duration: duration, packetSizes: [data.count]) {
                onAudio?(buffer)
            }
        }
    }

    /// O tempo de um bloco de áudio às amostras, seguido do anterior. Os PTS dos IPTV tremem uns milissegundos de PES
    /// para PES, e o renderizador da Apple toca cada bloco no tempo exato: um buraco ou uma sobreposição de 1–2 ms
    /// ouvia-se como um estalo (o Android escreve os blocos seguidos e não sofria disto). Só um salto maior do que
    /// [audioSlackTicks] (falta de dados, outro programa) recomeça a conta no PTS do bloco.
    private func audioTime(_ pts: Int64, rate: CMTimeScale, samples: Int64) -> CMTime {
        if pts >= 0, rate > 0 {
            let expected = audioRunStart >= 0 && rate == audioRunRate
                ? (audioRunStart + audioRunSamples) * 90_000 / Int64(rate) : Int64.min
            if expected == Int64.min || abs(pts - expected) > Self.audioSlackTicks {
                audioRunStart = pts * Int64(rate) / 90_000
                audioRunSamples = 0
                audioRunRate = rate
            }
        } else if audioRunStart < 0 || rate != audioRunRate {
            return .invalid
        }
        let time = CMTime(value: audioRunStart + audioRunSamples, timescale: rate)
        audioRunSamples += samples
        return time
    }

    /// 40 ms: mais do que o tremer dos PTS, menos do que se nota entre a imagem e o som.
    private static let audioSlackTicks: Int64 = 3_600

    static func audioFormat(_ f: GoatShared.AudioFormat) -> CMAudioFormatDescription? {
        var asbd = AudioStreamBasicDescription()
        asbd.mSampleRate = Float64(f.sampleRate)
        asbd.mChannelsPerFrame = UInt32(f.channels)
        switch f.codec.name {
        case "AAC_ADTS", "AAC_LATM":
            asbd.mFormatID = kAudioFormatMPEG4AAC
            asbd.mFormatFlags = UInt32(MPEG4ObjectID.AAC_LC.rawValue)
            asbd.mFramesPerPacket = 1024
        case "AC3":
            asbd.mFormatID = kAudioFormatAC3
            asbd.mFramesPerPacket = 1536
        case "EAC3":
            asbd.mFormatID = kAudioFormatEnhancedAC3
            asbd.mFramesPerPacket = 1536
        case "MPEG_AUDIO":
            // A camada vem do cabeçalho dos blocos: o MP2 da TV, o MP3 das rádios (576 amostras no MPEG-2/2.5).
            switch f.mpegLayer {
            case 3:
                asbd.mFormatID = kAudioFormatMPEGLayer3
                asbd.mFramesPerPacket = f.sampleRate < 32000 ? 576 : 1152
            case 1:
                asbd.mFormatID = kAudioFormatMPEGLayer1
                asbd.mFramesPerPacket = 384
            default:
                asbd.mFormatID = kAudioFormatMPEGLayer2
                asbd.mFramesPerPacket = 1152
            }
        default:
            return nil
        }
        let cookie = AppleMedia.shared.data(bytes: f.config) as Data
        var format: CMAudioFormatDescription?
        let status = cookie.withUnsafeBytes { raw in
            CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil,
                                           magicCookieSize: cookie.isEmpty ? 0 : cookie.count,
                                           magicCookie: cookie.isEmpty ? nil : raw.baseAddress,
                                           extensions: nil, formatDescriptionOut: &format)
        }
        return status == noErr ? format : nil
    }

    // MARK: Amostras

    static func sampleBuffer(_ data: Data, format: CMFormatDescription, pts: Int64, dts: Int64, duration: CMTime, samples: Int,
                             packetSizes: [Int]?) -> CMSampleBuffer? {
        sampleBuffer(data, format: format, time: pts >= 0 ? CMTime(value: pts, timescale: 90_000) : .invalid,
                     decode: dts >= 0 ? CMTime(value: dts, timescale: 90_000) : .invalid,
                     duration: duration, samples: samples, packetSizes: packetSizes)
    }

    static func sampleBuffer(_ data: Data, format: CMFormatDescription, time: CMTime, decode: CMTime? = nil, duration: CMTime,
                             samples: Int = 1, packetSizes: [Int]?) -> CMSampleBuffer? {
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: data.count, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: data.count, flags: 0,
                                                 blockBufferOut: &block) == noErr, let block else { return nil }
        let copied = data.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: data.count)
        }
        guard copied == noErr else { return nil }
        var timing = CMSampleTimingInfo(duration: duration, presentationTimeStamp: time, decodeTimeStamp: decode ?? time)
        var buffer: CMSampleBuffer?
        if let packetSizes {
            var descriptions = packetSizes.reduce(into: (0, [AudioStreamPacketDescription]())) { acc, size in
                acc.1.append(AudioStreamPacketDescription(mStartOffset: Int64(acc.0), mVariableFramesInPacket: 0, mDataByteSize: UInt32(size)))
                acc.0 += size
            }.1
            let status = CMAudioSampleBufferCreateReadyWithPacketDescriptions(
                allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: descriptions.count,
                presentationTimeStamp: timing.presentationTimeStamp, packetDescriptions: &descriptions, sampleBufferOut: &buffer)
            return status == noErr ? buffer : nil
        }
        var size = data.count
        let status = CMSampleBufferCreateReady(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: samples,
                                               sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
                                               sampleSizeArray: &size, sampleBufferOut: &buffer)
        return status == noErr ? buffer : nil
    }
}
