package cc.slokker.itv.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSMutableData
import platform.posix.memcpy

/**
 * O [VoiceDownmix] e o [AudioLeveler] para o Swift: o PCM float intercalado passa num NSMutableData, de uma só vez
 * (amostra a amostra pela ponte, cada bloco levava milhares de chamadas). [level]: com nivelamento ([night]: modo
 * noite; [startGainDb]: NaN = canal sem ganho guardado). [voices]: vozes mais claras (mistura para estéreo o som com
 * canal central e reforça as vozes do estéreo; só se pede quando a saída é estéreo).
 */
class AppleAudioLeveler(sampleRate: Int, private val channels: Int, level: Boolean, night: Boolean, startGainDb: Float, voices: Boolean) {
    private val downmix = if (voices && VoiceDownmix.applies(channels)) VoiceDownmix(sampleRate, channels) else null

    /** Os canais do PCM que fica em [process] (2 quando se mistura para estéreo, senão os que entraram). */
    val outChannels: Int = downmix?.outChannels ?: channels

    private val leveler = if (level) AudioLeveler(sampleRate, outChannels, night, startGainDb) else null
    private var scratch = FloatArray(0)

    /** O ganho do nivelamento (dB), para guardar com o canal; só vale se [measured]. */
    val gainDb: Float get() = leveler?.gainDb ?: 0f

    /** Já se ouviu som (o ganho que [gainDb] dá foi medido). */
    val measured: Boolean get() = leveler?.measuredLufs?.isNaN() == false

    /**
     * Trata no próprio [data] o PCM float32 intercalado que lá está (todo). Quando [outChannels] é menos do que os
     * canais que entraram, o resultado fica no início de [data] (as mesmas amostras por canal, com [outChannels]
     * canais) e o resto não se usa.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun process(data: NSMutableData) {
        if (channels <= 0) return
        val frames = data.length.toInt() / 4 / channels
        val n = frames * channels
        if (n == 0) return
        val bytes = data.mutableBytes ?: return
        if (scratch.size < n) scratch = FloatArray(n)
        scratch.usePinned { memcpy(it.addressOf(0), bytes, (n * 4).toULong()) }
        downmix?.process(scratch, 0, frames, limit = leveler == null)
        leveler?.process(scratch, 0, frames)
        scratch.usePinned { memcpy(bytes, it.addressOf(0), (frames * outChannels * 4).toULong()) }
    }
}
