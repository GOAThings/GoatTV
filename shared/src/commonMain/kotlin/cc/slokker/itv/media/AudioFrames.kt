package cc.slokker.itv.media

/**
 * Um bloco de áudio comprimido, inteiro, como o descodificador do sistema o quer (AAC sem o cabeçalho ADTS; AC-3,
 * E-AC-3 e MPEG áudio tal como vêm). [pts] no relógio do TS (90 kHz); [samples] por canal neste bloco.
 */
class AudioFrame(val data: ByteArray, val pts: Long, val samples: Int)

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
            val payload = if (codec == Codec.AAC_ADTS) buf.copyOfRange(i + h.skip, i + h.size) else buf.copyOfRange(i, i + h.size)
            format = h.format
            out += AudioFrame(payload, clock, h.samples)
            if (clock >= 0) clock += h.samples * 90_000L / h.format.sampleRate
            i += h.size
        }
        rest = buf.copyOfRange(i, buf.size)
        restPts = clock
        return out
    }

    fun reset() {
        rest = ByteArray(0)
        restPts = -1
        format = null
    }

    private class Header(val size: Int, val skip: Int, val samples: Int, val format: AudioFormat)

    private fun header(d: ByteArray, at: Int): Header? = when (codec) {
        Codec.AAC_ADTS -> adts(d, at)
        Codec.AC3, Codec.EAC3 -> ac3(d, at)
        Codec.MPEG_AUDIO -> mpeg(d, at)
        else -> null
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
