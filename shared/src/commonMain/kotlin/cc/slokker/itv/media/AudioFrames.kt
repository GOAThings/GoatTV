package cc.slokker.itv.media

/**
 * Um bloco de áudio comprimido, inteiro, como o descodificador do sistema o quer (AAC sem o cabeçalho ADTS nem o
 * LATM; AC-3, E-AC-3 e MPEG áudio tal como vêm). [pts] no relógio do TS (90 kHz); [samples] por canal neste bloco.
 */
class AudioFrame(val data: ByteArray, val pts: Long, val samples: Int)

/** Um bloco de áudio dentro de um pedaço ([AudioFrameReader.rawFrames]). */
internal class RawAudioFrame(val offset: Int, val size: Int, val samples: Int, val sampleRate: Int)

/**
 * O formato de uma faixa de áudio, tirado dos próprios blocos (o PMT não diz a frequência nem os canais).
 * [config] é o AudioSpecificConfig do AAC (o que o AudioToolbox e o MediaCodec pedem), vazio nos outros.
 */
data class AudioFormat(val codec: Codec, val sampleRate: Int, val channels: Int, val config: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is AudioFormat && codec == other.codec && sampleRate == other.sampleRate && channels == other.channels && config.contentEquals(other.config)

    override fun hashCode(): Int = (codec.hashCode() * 31 + sampleRate) * 31 + channels
}

/**
 * Parte o áudio de uma faixa em blocos ([AudioFrame]) e diz o formato. Os PES não têm de acabar no fim de um bloco:
 * o que sobra junta-se ao seguinte. O PTS de cada bloco conta-se a partir do do PES, pelas amostras.
 */
class AudioFrameReader(private val codec: Codec) {
    private var rest = ByteArray(0)
    private var restPts = -1L

    /** O último formato visto (muda quando o canal muda de som, por exemplo de estéreo para 5.1). */
    var format: AudioFormat? = null
        private set

    /** Os blocos inteiros de [data] (o PES com o [pts]); [discontinuity] deita fora o que tinha ficado do anterior. */
    fun frames(data: ByteArray, pts: Long, discontinuity: Boolean): List<AudioFrame> {
        val carried = if (discontinuity) ByteArray(0) else rest
        val buf = if (carried.isEmpty()) data else carried + data
        // O que sobrou do PES anterior continua o tempo de lá; o primeiro bloco que começa neste tem o PTS dele.
        var clock = if (carried.isNotEmpty()) restPts else pts
        var resynced = false
        val out = ArrayList<AudioFrame>()
        var i = 0
        while (i + MAX_HEADER <= buf.size) {
            val h = header(buf, i)
            if (h == null) {
                // Sem cabeçalho aqui: procura o seguinte (lixo, ou um bloco partido).
                i++
                continue
            }
            if (i + h.size > buf.size) break
            if (!resynced && i >= carried.size && pts >= 0) {
                clock = pts
                resynced = true
            }
            val payload = h.payload ?: if (codec == Codec.AAC_ADTS) buf.copyOfRange(i + h.skip, i + h.size) else buf.copyOfRange(i, i + h.size)
            // Um LATM que reusa a configuração e ainda não se viu nenhuma: salta-se (não se sabe o que é).
            val f = h.format
            if (f == null || payload.isEmpty()) {
                i += h.size
                continue
            }
            format = f
            out += AudioFrame(payload, clock, h.samples)
            if (clock >= 0) clock += h.samples * 90_000L / f.sampleRate
            i += h.size
        }
        rest = buf.copyOfRange(i, buf.size)
        restPts = clock
        return out
    }

    /**
     * Os blocos inteiros de [data] como vêm (com o cabeçalho), sem os tirar: onde começa cada um, o tamanho, as
     * amostras e a frequência. É o que o áudio "empacotado" do HLS precisa para voltar a ser PES (um por bloco).
     */
    internal fun rawFrames(data: ByteArray): List<RawAudioFrame> {
        val out = ArrayList<RawAudioFrame>()
        var i = 0
        while (i + MAX_HEADER <= data.size) {
            val h = header(data, i)
            if (h == null) {
                i++
                continue
            }
            if (i + h.size > data.size) break
            h.format?.let { out += RawAudioFrame(i, h.size, h.samples, it.sampleRate) }
            i += h.size
        }
        return out
    }

    fun reset() {
        rest = ByteArray(0)
        restPts = -1
        format = null
        latmConfig = null
    }

    /** [payload]: o bloco já tirado (o LATM não está alinhado aos bytes); null = os bytes do cabeçalho em diante. */
    private class Header(val size: Int, val skip: Int, val samples: Int, val format: AudioFormat?, val payload: ByteArray? = null)

    private fun header(d: ByteArray, at: Int): Header? = when (codec) {
        Codec.AAC_ADTS -> adts(d, at)
        Codec.AC3, Codec.EAC3 -> ac3(d, at)
        Codec.MPEG_AUDIO -> mpeg(d, at)
        Codec.AAC_LATM -> loas(d, at)
        else -> null
    }

    // ---- LATM (AAC no DVB-T/T2 e nos IPTV que o reencaminham) ---------------------------------------

    /** A última StreamMuxConfig (os blocos com useSameStreamMux reusam-na). */
    private var latmConfig: AudioFormat? = null

    /**
     * LOAS: a sincronização 0x2B7 (11 bits) e o tamanho (13 bits), depois um AudioMuxElement com a configuração (ou
     * o "usa a mesma") e o bloco AAC. Só um programa, uma camada e um sub-bloco (o que os emissores mandam).
     */
    private fun loas(d: ByteArray, at: Int): Header? {
        if (at + 3 > d.size || u(d, at) != 0x56 || (u(d, at + 1) and 0xE0) != 0xE0) return null
        val length = ((u(d, at + 1) and 0x1F) shl 8) or u(d, at + 2)
        val size = 3 + length
        if (at + size > d.size) return Header(size, 0, 1024, null)
        val bits = BitReader(d, at + 3)
        if (bits.read(1) == 0) {
            latmConfig = streamMuxConfig(bits) ?: return Header(size, 0, 1024, null)
        }
        val config = latmConfig ?: return Header(size, 0, 1024, null)
        // PayloadLengthInfo (frameLengthType 0): bytes de 255 somados até um que não seja.
        var payloadLength = 0
        do {
            val b = bits.read(8)
            payloadLength += b
        } while (b == 255)
        if (payloadLength <= 0 || payloadLength > length) return Header(size, 0, 1024, null)
        val payload = ByteArray(payloadLength) { bits.read(8).toByte() }
        return Header(size, 0, 1024, config, payload)
    }

    private fun latmValue(bits: BitReader): Int {
        val bytes = bits.read(2)
        var v = 0
        repeat(bytes + 1) { v = (v shl 8) or bits.read(8) }
        return v
    }

    private fun streamMuxConfig(bits: BitReader): AudioFormat? {
        val version = bits.read(1)
        if (version == 1 && bits.read(1) != 0) return null // audioMuxVersionA
        if (version == 1) latmValue(bits) // taraBufferFullness
        bits.read(1) // allStreamsSameTimeFraming
        if (bits.read(6) != 0) return null // numSubFrames
        if (bits.read(4) != 0) return null // numProgram
        if (bits.read(3) != 0) return null // numLayer
        val asc: AudioFormat
        if (version == 0) {
            asc = audioSpecificConfig(bits) ?: return null
        } else {
            val ascBits = latmValue(bits)
            val start = bits.position
            asc = audioSpecificConfig(bits) ?: return null
            bits.skip(ascBits - (bits.position - start))
        }
        if (bits.read(3) != 0) return null // frameLengthType: só o 0 (tamanho variável)
        bits.read(8) // latmBufferFullness
        if (bits.read(1) == 1) {
            // otherDataPresent
            if (version == 1) {
                latmValue(bits)
            } else {
                do {
                    val esc = bits.read(1)
                    bits.read(8)
                } while (esc == 1)
            }
        }
        if (bits.read(1) == 1) bits.read(8) // crcCheckPresent
        return asc
    }

    /**
     * O AudioSpecificConfig lido dos bits (copiado tal qual para o descodificador): o tipo, a frequência e os canais; no
     * AAC (tipos 1–4, também por baixo do SBR/PS) o GASpecificConfig, para saber onde acaba.
     */
    private fun audioSpecificConfig(bits: BitReader): AudioFormat? {
        val start = bits.position
        fun objectType(): Int {
            val t = bits.read(5)
            return if (t == 31) 32 + bits.read(6) else t
        }
        fun rate(): Int {
            val index = bits.read(4)
            return if (index == 15) bits.read(24) else AAC_RATES.getOrNull(index) ?: 0
        }
        var type = objectType()
        val rate = rate()
        val channelConfig = bits.read(4)
        if (type == 5 || type == 29) {
            rate() // a frequência do SBR (o descodificador acha-a sozinho)
            type = objectType()
        }
        if (type !in 1..4 || rate == 0 || channelConfig == 0) return null
        bits.read(1) // frameLengthFlag
        if (bits.read(1) == 1) bits.read(14) // dependsOnCoreCoder: coreCoderDelay
        bits.read(1) // extensionFlag
        val used = bits.position - start
        val config = ByteArray((used + 7) / 8)
        val copy = bits.copy(start)
        for (k in 0 until used) {
            if (copy.read(1) == 1) config[k / 8] = (config[k / 8].toInt() or (0x80 ushr (k % 8))).toByte()
        }
        val channels = if (channelConfig == 7) 8 else channelConfig
        return AudioFormat(Codec.AAC_LATM, rate, channels, config)
    }

    private fun u(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

    /** ADTS: 0xFFF, o perfil, a frequência e os canais; o tamanho inclui o cabeçalho (7 bytes, ou 9 com CRC). */
    private fun adts(d: ByteArray, at: Int): Header? {
        if (at + 7 > d.size || u(d, at) != 0xFF || u(d, at + 1) and 0xF6 != 0xF0) return null
        val crc = u(d, at + 1) and 0x01 == 0
        val profile = (u(d, at + 2) shr 6) + 1
        val rateIndex = (u(d, at + 2) shr 2) and 0x0F
        val channels = ((u(d, at + 2) and 0x01) shl 2) or (u(d, at + 3) shr 6)
        val size = ((u(d, at + 3) and 0x03) shl 11) or (u(d, at + 4) shl 3) or (u(d, at + 5) shr 5)
        val blocks = (u(d, at + 6) and 0x03) + 1
        if (rateIndex >= AAC_RATES.size || size < 7) return null
        val skip = if (crc) 9 else 7
        if (size <= skip) return null
        val config = byteArrayOf(((profile shl 3) or (rateIndex shr 1)).toByte(), (((rateIndex and 1) shl 7) or (channels shl 3)).toByte())
        // Canais 0: vêm na configuração do programa dentro do bloco; quase sempre é estéreo.
        return Header(size, skip, 1024 * blocks, AudioFormat(Codec.AAC_ADTS, AAC_RATES[rateIndex], if (channels == 7) 8 else if (channels == 0) 2 else channels, config))
    }

    /** AC-3 (bsid ≤ 10) e E-AC-3 (bsid 11–16): 0x0B77 e o tamanho do bloco. */
    private fun ac3(d: ByteArray, at: Int): Header? {
        if (at + 8 > d.size || u(d, at) != 0x0B || u(d, at + 1) != 0x77) return null
        val bsid = u(d, at + 5) shr 3
        return if (bsid <= 10) {
            val fscod = u(d, at + 4) shr 6
            val frmsizecod = u(d, at + 4) and 0x3F
            if (fscod == 3 || frmsizecod >= 38) return null
            val rate = intArrayOf(48000, 44100, 32000)[fscod]
            val words = AC3_FRAME_WORDS[frmsizecod][fscod]
            Header(words * 2, 0, 1536, AudioFormat(Codec.AC3, rate, ac3Channels(d, at), ByteArray(0)))
        } else if (bsid <= 16) {
            val size = (((u(d, at + 2) and 0x07) shl 8) or u(d, at + 3)) * 2 + 2
            val fscod = u(d, at + 4) shr 6
            val numblkscod = if (fscod == 3) 3 else (u(d, at + 4) shr 4) and 0x03
            val rate = if (fscod == 3) intArrayOf(24000, 22050, 16000, 0)[(u(d, at + 4) shr 4) and 0x03] else intArrayOf(48000, 44100, 32000)[fscod]
            if (rate == 0 || size < 8) return null
            val acmod = (u(d, at + 4) shr 1) and 0x07
            val lfe = u(d, at + 4) and 0x01
            Header(size, 0, intArrayOf(1, 2, 3, 6)[numblkscod] * 256, AudioFormat(Codec.EAC3, rate, ACMOD_CHANNELS[acmod] + lfe, ByteArray(0)))
        } else {
            null
        }
    }

    /** Os canais de um bloco AC-3: o acmod e o LFE, que vem depois de bits que dependem do acmod. */
    private fun ac3Channels(d: ByteArray, at: Int): Int {
        val bits = BitReader(d, at + 6)
        val acmod = bits.read(3)
        if (acmod and 0x01 != 0 && acmod != 1) bits.read(2) // cmixlev
        if (acmod and 0x04 != 0) bits.read(2) // surmixlev
        if (acmod == 2) bits.read(2) // dsurmod
        return ACMOD_CHANNELS[acmod] + bits.read(1)
    }

    /** MPEG-1/2 áudio (camadas I, II e III): o MP2 dos canais DVB antigos e algum MP3. */
    private fun mpeg(d: ByteArray, at: Int): Header? {
        if (at + 4 > d.size || u(d, at) != 0xFF || u(d, at + 1) and 0xE0 != 0xE0) return null
        val version = (u(d, at + 1) shr 3) and 0x03 // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        val layer = (u(d, at + 1) shr 1) and 0x03 // 3 = I, 2 = II, 1 = III
        val bitrateIndex = u(d, at + 2) shr 4
        val rateIndex = (u(d, at + 2) shr 2) and 0x03
        val padding = (u(d, at + 2) shr 1) and 0x01
        val mode = u(d, at + 3) shr 6
        if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val mpeg1 = version == 3
        val rate = intArrayOf(44100, 48000, 32000)[rateIndex] / when (version) {
            3 -> 1
            2 -> 2
            else -> 4
        }
        val kbps = when {
            mpeg1 && layer == 3 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            mpeg1 && layer == 2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            mpeg1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            layer == 3 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            else -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        }[bitrateIndex]
        val samples = when {
            layer == 3 -> 384
            layer == 2 || mpeg1 -> 1152
            else -> 576
        }
        val size = if (layer == 3) (12 * kbps * 1000 / rate + padding) * 4 else samples / 8 * kbps * 1000 / rate + padding
        if (size < 4) return null
        return Header(size, 0, samples, AudioFormat(Codec.MPEG_AUDIO, rate, if (mode == 3) 1 else 2, ByteArray(0)))
    }

    private class BitReader(private val d: ByteArray, private var byte: Int) {
        private var bit = 0
        private val origin = byte

        /** Os bits já lidos desde o início. */
        val position: Int get() = (byte - origin) * 8 + bit

        fun skip(n: Int) {
            if (n > 0) repeat(n) { read(1) }
        }

        /** Um leitor novo no bit [from] (contado como [position]). */
        fun copy(from: Int): BitReader = BitReader(d, origin).also { it.skip(from) }

        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val b = if (byte < d.size) (d[byte].toInt() shr (7 - bit)) and 1 else 0
                v = (v shl 1) or b
                bit++
                if (bit == 8) {
                    bit = 0
                    byte++
                }
            }
            return v
        }
    }

    companion object {
        /** O que é preciso ter à frente para ler qualquer cabeçalho (o AC-3 lê os canais no byte 7). */
        private const val MAX_HEADER = 10
        private val AAC_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        private val ACMOD_CHANNELS = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

        /** O tamanho de um bloco AC-3 em palavras de 16 bits, por frmsizecod e frequência (48, 44,1 e 32 kHz). */
        private val AC3_FRAME_WORDS = arrayOf(
            intArrayOf(64, 69, 96), intArrayOf(64, 70, 96), intArrayOf(80, 87, 120), intArrayOf(80, 88, 120),
            intArrayOf(96, 104, 144), intArrayOf(96, 105, 144), intArrayOf(112, 121, 168), intArrayOf(112, 122, 168),
            intArrayOf(128, 139, 192), intArrayOf(128, 140, 192), intArrayOf(160, 174, 240), intArrayOf(160, 175, 240),
            intArrayOf(192, 208, 288), intArrayOf(192, 209, 288), intArrayOf(224, 243, 336), intArrayOf(224, 244, 336),
            intArrayOf(256, 278, 384), intArrayOf(256, 279, 384), intArrayOf(320, 348, 480), intArrayOf(320, 349, 480),
            intArrayOf(384, 417, 576), intArrayOf(384, 418, 576), intArrayOf(448, 487, 672), intArrayOf(448, 488, 672),
            intArrayOf(512, 557, 768), intArrayOf(512, 558, 768), intArrayOf(640, 696, 960), intArrayOf(640, 697, 960),
            intArrayOf(768, 835, 1152), intArrayOf(768, 836, 1152), intArrayOf(896, 975, 1344), intArrayOf(896, 976, 1344),
            intArrayOf(1024, 1114, 1536), intArrayOf(1024, 1115, 1536), intArrayOf(1152, 1253, 1728), intArrayOf(1152, 1254, 1728),
            intArrayOf(1280, 1393, 1920), intArrayOf(1280, 1394, 1920),
        )
    }
}
