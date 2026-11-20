package cc.slokker.itv.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSMutableData
import platform.posix.memcpy

/**
 * O [AudioLeveler] para o Swift: o PCM float intercalado passa num NSMutableData, de uma só vez (amostra a amostra
 * pela ponte, cada bloco levava milhares de chamadas). [startGainDb]: NaN = canal sem ganho guardado.
 */
class AppleAudioLeveler(sampleRate: Int, private val channels: Int, night: Boolean, startGainDb: Float) {
    private val leveler = AudioLeveler(sampleRate, channels, night, startGainDb)
    private var scratch = FloatArray(0)

    /** O ganho do nivelamento (dB), para guardar com o canal; só vale se [measured]. */
    val gainDb: Float get() = leveler.gainDb

    /** Já se ouviu som (o ganho que [gainDb] dá foi medido). */
    val measured: Boolean get() = !leveler.measuredLufs.isNaN()

    /** Nivela no próprio [data] o PCM float32 intercalado que lá está (todo). */
    @OptIn(ExperimentalForeignApi::class)
    fun process(data: NSMutableData) {
        if (channels <= 0) return
        val frames = data.length.toInt() / 4 / channels
        val n = frames * channels
        if (n == 0) return
        val bytes = data.mutableBytes ?: return
        if (scratch.size < n) scratch = FloatArray(n)
        scratch.usePinned { memcpy(it.addressOf(0), bytes, (n * 4).toULong()) }
        leveler.process(scratch, 0, frames)
        scratch.usePinned { memcpy(bytes, it.addressOf(0), (n * 4).toULong()) }
    }
}
