import AudioToolbox
import CoreMedia
import Foundation
import GoatShared

/// Uma faixa do canal aberto no motor GoatCore, como o núcleo partilhado a viu no PMT.
public struct MediaTrack: Equatable, Sendable {
    public enum Kind: Sendable { case video, audio, subtitle, data }

    public let pid: Int
    /// O formato pelo nome do núcleo partilhado ("H264", "HEVC", "MPEG2_VIDEO", "AAC_ADTS", "AC3", "EAC3", "MPEG_AUDIO"…).
    public let codec: String
    public let kind: Kind
    /// A língua que o canal diz (ISO 639-2, em minúsculas), se disser.
    public let language: String?
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

    /// Um número só para a faixa (DVB: o PID) ou para a faixa e a página (teletexto, como no Android: acima de
    /// qualquer PID).
    public var key: Int { page == 0 ? pid : pid * 0x1000 + page }
    public var isTeletext: Bool { page != 0 }
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
    private var videoFormat: CMVideoFormatDescription?
    private var videoClock = TsClock()
    /// Só se manda vídeo a partir de uma imagem-chave, sem as imagens que dependem de antes dela (o verde e os borrões).
    private let videoStart = VideoStart()
    private var audio: AudioFrameReader?
    private var audioReaderPid: Int?
    private var audioFormat: CMAudioFormatDescription?
    private var audioFormatKey: GoatShared.AudioFormat?
    private var audioClock = TsClock()
    private var reportedUnsupported = false
    private var textTracks: [TextTrack] = []
    private var teletext: TeletextDecoder?
    private var dvb: DvbSubtitleDecoder?
    private var rawTracks: [TsTrack] = []
    private var textPid: Int?
    /// Os últimos PES de cada faixa de legendas: ao escolher uma, o que já passou mostra-se logo (ver [selectText]).
    private var recentText: [Int: [TsSample]] = [:]
    private static let recentTextCount = 800

    /// As legendas a mostrar ([TextTrack.key]), ou nil para nenhumas.
    public func selectText(_ key: Int?) {
        teletext = nil
        dvb = nil
        textPid = nil
        guard let key, let t = textTracks.first(where: { $0.key == key }) else { return }
        if t.isTeletext {
            teletext = TeletextDecoder(page: Int32(t.page))
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

    /// Outra ligação: esquece tudo.
    public func reset() {
        demuxer = StreamDemuxer(listener: listener)
        tracks = []
        videoPid = nil
        nal = nil
        videoFormat = nil
        videoClock.reset()
        videoStart.restart()
        audio = nil
        audioReaderPid = nil
        audioFormat = nil
        audioFormatKey = nil
        audioClock.reset()
        reportedUnsupported = false
        textTracks = []
        rawTracks = []
        recentText = [:]
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
            return MediaTrack(pid: Int(t.pid), codec: t.codec.name, kind: kind, language: t.language)
        }
        rawTracks = list
        textTracks = list.flatMap { t -> [TextTrack] in
            if t.codec.name == "DVB_SUBTITLE" {
                return [TextTrack(pid: Int(t.pid), page: 0, language: t.language, hearingImpaired: false)]
            }
            return AppleMedia.shared.teletextPages(track: t).map { p in
                TextTrack(pid: Int(t.pid), page: Int(p.page), language: p.language ?? t.language, hearingImpaired: p.type == 5)
            }
        }
        onTextTracks?(textTracks)
        let video = tracks.first { $0.kind == .video }
        if video?.pid != videoPid {
            videoPid = video?.pid
            videoFormat = nil
            nal = nil
            if let video {
                switch video.codec {
                case "H264": nal = NalReader(hevc: false)
                case "HEVC": nal = NalReader(hevc: true)
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

    /// Um PES das legendas escolhidas: o texto (teletexto) ou as imagens (DVB), quando mudam, no tempo do vídeo (ou
    /// do som, sem vídeo).
    private func text(_ sample: TsSample) {
        let clock = videoPid != nil ? videoClock : audioClock
        if let decoder = teletext {
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
        guard let nal else { return }
        let samplePts = videoClock.unwrap(ts: sample.pts)
        if videoClock.jumped {
            videoStart.restart()
            // As legendas guardadas são do tempo de antes do salto: já não se acertam com a imagem.
            recentText = [:]
            onJump?()
        }
        // Faltaram dados (a imagem que vinha não chegou inteira): espera-se pela próxima imagem-chave.
        if sample.discontinuity { videoStart.restart() }
        let sampleDts = sample.dts == sample.pts ? samplePts : videoClock.unwrap(ts: sample.dts)
        // Numa imagem entrelaçada por campos, sai com o segundo campo (os dois juntos, com os tempos do primeiro).
        guard let unit = nal.next(annexB: sample.data, pts: samplePts, dts: sampleDts) else { return }
        if !unit.parameterSets.isEmpty {
            let sets = unit.parameterSets.map { AppleMedia.shared.data(bytes: $0) as Data }
            let hevc = videoPid.flatMap { p in tracks.first { $0.pid == p } }?.codec == "HEVC"
            if let f = Self.videoFormat(sets, hevc: hevc) {
                let changed = videoFormat.map { !CMFormatDescriptionEqual($0, otherFormatDescription: f) } ?? true
                videoFormat = f
                if changed, let info = AppleMedia.shared.videoInfo(sets: unit.parameterSets, hevc: hevc) {
                    onVideoDetails?(VideoDetails(width: Int(info.width), height: Int(info.height), fps: Double(info.fps),
                                                 hdr10: info.isHdr10, hlg: info.isHlg, format: f))
                }
            }
        }
        // Até à primeira imagem-chave com os parâmetros não há nada que se possa mostrar.
        guard let format = videoFormat else { return }
        let pts = unit.pts
        let dts = unit.dts
        guard videoStart.pass(keyframe: unit.keyframe, pts: pts) else { return }
        let data = AppleMedia.shared.data(bytes: unit.data) as Data
        guard let buffer = Self.sampleBuffer(data, format: format, pts: pts, dts: dts, duration: .invalid, samples: 1, packetSizes: nil) else { return }
        if !unit.keyframe, let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: true) as? [NSMutableDictionary] {
            attachments.first?[kCMSampleAttachmentKey_NotSync] = true
            attachments.first?[kCMSampleAttachmentKey_DependsOnOthers] = true
        }
        onVideo?(buffer)
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
        }
        guard let format = audioFormat else { return }
        // Um bloco por amostra (com a descrição do pacote): o renderizador decide como os junta.
        for frame in frames {
            let pts = audioClock.unwrap(ts: frame.pts)
            if audioClock.jumped { onJump?() }
            let data = AppleMedia.shared.data(bytes: frame.data) as Data
            let duration = CMTime(value: CMTimeValue(frame.samples), timescale: CMTimeScale(f.sampleRate))
            if let buffer = Self.sampleBuffer(data, format: format, pts: pts, dts: pts, duration: duration, samples: 1, packetSizes: [data.count]) {
                onAudio?(buffer)
            }
        }
    }

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
            asbd.mFormatID = kAudioFormatMPEGLayer2
            asbd.mFramesPerPacket = 1152
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
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: data.count, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: data.count, flags: 0,
                                                 blockBufferOut: &block) == noErr, let block else { return nil }
        let copied = data.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: data.count)
        }
        guard copied == noErr else { return nil }
        var timing = CMSampleTimingInfo(duration: duration,
                                        presentationTimeStamp: pts >= 0 ? CMTime(value: pts, timescale: 90_000) : .invalid,
                                        decodeTimeStamp: dts >= 0 ? CMTime(value: dts, timescale: 90_000) : .invalid)
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
