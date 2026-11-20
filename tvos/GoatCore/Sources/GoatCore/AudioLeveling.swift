import AVFoundation
import CoreMedia
import GoatShared
import os

/// Nivelar o volume (Definições → Nivelar volume) no motor GoatCore da Apple. O som sai do MediaPipeline comprimido
/// (AAC, MP2, AC-3…) e o AVSampleBufferAudioRenderer descodifica-o sozinho, sem deixar mexer no PCM: por isso, com
/// a definição ligada, descodifica-se aqui antes (AVAudioConverter) para PCM float, nivela-se no núcleo partilhado
/// (AudioLeveler, o mesmo do Android) e o bloco entra no renderizador já em PCM.
///
/// O mesmo caminho serve as vozes mais claras (Definições → Vozes mais claras; VoiceDownmix, o mesmo do Android): com a
/// saída em estéreo, o som com canal central mistura-se aqui com os diálogos mais altos, antes do nivelamento, e sai
/// em PCM estéreo; no som estéreo reforçam-se as vozes.
///
/// Com o passthrough ligado, o AC-3/E-AC-3 segue tal como vem (o recetor descodifica e não se lhe mexe), como no
/// Android. O que aqui não se descodifica (4 ou 7 canais, de que não se sabe a disposição; um formato que o conversor
/// não abre) também segue como vem. Com mais de 2 canais pede-se ao descodificador o PCM pela ordem do Android
/// (5.1: L R C LFE Ls Rs), que é a que o núcleo partilhado espera, e é com essa disposição que ele segue para o
/// leitor. Uma instância por canal, sempre na mesma fila (a do StreamSession).
public final class AudioLeveling {
    private let level: Bool
    private let night: Bool
    private let voices: Bool
    private let stereoOutput: Bool
    private let passthrough: Bool
    private let key: String
    private var source: CMFormatDescription?
    private var converter: AVAudioConverter?
    private var input: AVAudioFormat?
    private var output: AVAudioFormat?
    /// O PCM estéreo que sai quando as vozes mais claras misturam (nil = sai como o descodificador o deu, [output]).
    private var mixed: AVAudioFormat?
    private var leveler: AppleAudioLeveler?
    /// O PCM passa por aqui para o Kotlin (um só NSMutableData, reutilizado).
    private let scratch = NSMutableData()

    /// O ganho com que cada canal ficou (pelo endereço), enquanto a app está aberta: o zap seguinte começa nele.
    private static let gains = OSAllocatedUnfairLock(initialState: [String: Float]())

    /// nil com as duas definições desligadas ([mode] "off" e sem [voices]). [channel]: o endereço do canal (o ganho
    /// guarda-se por ele). [voices]: vozes mais claras; [stereoOutput]: a saída de som só leva estéreo (só então o som
    /// com canal central se mistura aqui; um recetor que aceita os canais todos fica com eles).
    public init?(mode: String, channel: String, passthrough: Bool, voices: Bool = false, stereoOutput: Bool = true) {
        let levelling = mode == "level" || mode == "night"
        guard levelling || voices else { return nil }
        level = levelling
        night = mode == "night"
        self.voices = voices
        self.stereoOutput = stereoOutput
        self.passthrough = passthrough
        key = channel
    }

    /// O bloco [b] nivelado (em PCM); o próprio [b] se este som não se nivela; nil se ainda não há PCM para tocar.
    public func process(_ b: CMSampleBuffer) -> CMSampleBuffer? {
        guard let fd = CMSampleBufferGetFormatDescription(b) else { return b }
        if source.map({ !CFEqual($0, fd) }) ?? true { setUp(fd) }
        guard let converter, let input, let output, let leveler else { return b }
        // Um pacote por bloco (é assim que o MediaPipeline os faz).
        guard CMSampleBufferGetNumSamples(b) == 1, let block = CMSampleBufferGetDataBuffer(b) else { return nil }
        let size = CMBlockBufferGetDataLength(block)
        guard size > 0 else { return nil }
        let compressed = AVAudioCompressedBuffer(format: input, packetCapacity: 1, maximumPacketSize: size)
        guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: size, destination: compressed.data) == noErr else { return nil }
        compressed.byteLength = UInt32(size)
        compressed.packetCount = 1
        compressed.packetDescriptions?.pointee = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0,
                                                                              mDataByteSize: UInt32(size))
        // Folga para o HE-AAC (2048 por pacote) e o AC-3 (1536).
        let perPacket = max(AVAudioFrameCount(input.streamDescription.pointee.mFramesPerPacket), 1152)
        guard let pcm = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: perPacket * 2) else { return nil }
        var fed = false
        var error: NSError?
        let status = converter.convert(to: pcm, error: &error) { _, inputStatus in
            if fed {
                inputStatus.pointee = .noDataNow
                return nil
            }
            fed = true
            inputStatus.pointee = .haveData
            return compressed
        }
        if status == .error {
            converter.reset()
            return nil
        }
        guard pcm.frameLength > 0, let samples = pcm.floatChannelData?[0] else { return nil }
        // As vozes e o nivelamento, no próprio PCM (intercalado: um só bloco de floats).
        let bytes = Int(pcm.frameLength) * Int(output.streamDescription.pointee.mBytesPerFrame)
        scratch.length = bytes
        scratch.replaceBytes(in: NSRange(location: 0, length: bytes), withBytes: samples)
        leveler.process(data: scratch)
        var result = pcm
        var format = output
        if let mixed {
            // Misturado para estéreo: o resultado está no início do bloco, com 2 canais.
            guard let stereo = AVAudioPCMBuffer(pcmFormat: mixed, frameCapacity: pcm.frameLength),
                  let target = stereo.floatChannelData?[0] else { return nil }
            stereo.frameLength = pcm.frameLength
            memcpy(target, scratch.mutableBytes, Int(pcm.frameLength) * Int(mixed.streamDescription.pointee.mBytesPerFrame))
            result = stereo
            format = mixed
        } else {
            memcpy(samples, scratch.mutableBytes, bytes)
        }
        var out: CMSampleBuffer?
        guard CMAudioSampleBufferCreateWithPacketDescriptions(
            allocator: kCFAllocatorDefault, dataBuffer: nil, dataReady: false, makeDataReadyCallback: nil, refcon: nil,
            formatDescription: format.formatDescription, sampleCount: CMItemCount(result.frameLength),
            presentationTimeStamp: CMSampleBufferGetPresentationTimeStamp(b), packetDescriptions: nil, sampleBufferOut: &out
        ) == noErr, let out else { return nil }
        guard CMSampleBufferSetDataBufferFromAudioBufferList(out, blockBufferAllocator: kCFAllocatorDefault,
                                                             blockBufferMemoryAllocator: kCFAllocatorDefault, flags: 0,
                                                             bufferList: result.audioBufferList) == noErr else { return nil }
        _ = CMSampleBufferSetDataReady(out)
        return out
    }

    /// O som que estava no leitor saiu (outra faixa, uma interrupção): o descodificador recomeça do zero.
    public func reset() {
        converter?.reset()
    }

    /// O canal fecha: guarda o ganho a que chegou (se chegou a ouvir-se).
    public func finish() {
        guard let leveler, leveler.measured else { return }
        let gain = leveler.gainDb
        let k = key
        Self.gains.withLock {
            if $0.count > 500 { $0.removeAll() }
            $0[k] = gain
        }
    }

    /// Um formato novo: o conversor e o nivelamento para ele, ou nada (o som segue comprimido).
    private func setUp(_ fd: CMFormatDescription) {
        finish()
        source = fd
        converter = nil
        input = nil
        output = nil
        mixed = nil
        leveler = nil
        guard let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(fd)?.pointee else { return }
        if passthrough, asbd.mFormatID == kAudioFormatAC3 || asbd.mFormatID == kAudioFormatEnhancedAC3 { return }
        let from = AVAudioFormat(cmAudioFormatDescription: fd)
        let channels = from.channelCount
        guard channels > 0, from.sampleRate > 0 else { return }
        // As vozes mais claras: no estéreo sempre; com canal central (3.0, 5.0, 5.1, 7.1) só se a saída for estéreo.
        let clearer = voices && (channels == 2 || (stereoOutput && [3, 5, 6, 8].contains(Int(channels))))
        guard level || clearer else { return }
        let to: AVAudioFormat?
        if channels <= 2 {
            to = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: from.sampleRate, channels: channels, interleaved: true)
        } else if let layout = Self.layout(channels: Int(channels)) {
            to = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: from.sampleRate, interleaved: true, channelLayout: layout)
        } else {
            to = nil
        }
        guard let to, let c = AVAudioConverter(from: from, to: to) else { return }
        let k = key
        let start = Self.gains.withLock { $0[k] } ?? .nan
        let chain = AppleAudioLeveler(sampleRate: Int32(to.sampleRate), channels: Int32(channels), level: level, night: night,
                                      startGainDb: start, voices: clearer)
        if chain.outChannels != Int32(channels) {
            // Sai misturado: PCM estéreo.
            guard let stereo = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: to.sampleRate, channels: 2, interleaved: true) else { return }
            mixed = stereo
        }
        converter = c
        input = from
        output = to
        leveler = chain
    }

    /// A disposição do PCM de [channels] canais (mais de 2) pela ordem do Android e do FFmpeg, a que o núcleo
    /// partilhado espera: 3 = L R C; 5 = L R C Ls Rs; 6 = L R C LFE Ls Rs; 8 = L R C LFE Ls Rs Rls Rrs. O conversor
    /// reordena o que o descodificador dá (o AAC dá C L R Ls Rs LFE, o AC-3 L C R Ls Rs LFE) para ela. Com 4 ou 7
    /// canais não se sabe quais são: nil.
    private static func layout(channels: Int) -> AVAudioChannelLayout? {
        switch channels {
        case 3: return AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_MPEG_3_0_A)
        case 5: return AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_MPEG_5_0_A)
        case 6: return AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_MPEG_5_1_A)
        case 8: return AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_MPEG_7_1_C)
        default: return nil
        }
    }
}
