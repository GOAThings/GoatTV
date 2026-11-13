@file:OptIn(UnstableApi::class)

package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * O descodificador de áudio do FFmpeg do nextlib (o mesmo que o ExoPlayer da app usa) para o motor GoatCore, quando o
 * aparelho não tem descodificador para o som (a SHIELD não tem AC-3; muitas boxes não têm MP2 nem DTS). Fica neste
 * pacote porque o descodificador do nextlib não é público, só o construtor.
 */
class GoatCoreFfmpegAudio(mime: String, sampleRate: Int, channels: Int, config: ByteArray?) {
    private val decoder = FfmpegAudioDecoder(
        Format.Builder()
            .setSampleMimeType(mime)
            .setSampleRate(sampleRate)
            .setChannelCount(channels)
            .setInitializationData(config?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList())
            .build(),
        16, 16, 8192, false,
    )

    /** A frequência e os canais do PCM que sai (sabem-se depois do primeiro bloco). */
    val sampleRate: Int get() = decoder.sampleRate
    val channels: Int get() = decoder.channelCount

    /** Põe [data] (com [ptsUs]) a descodificar; false se ainda não há onde (tirar o que já saiu e tentar outra vez). */
    fun queue(data: ByteArray, ptsUs: Long): Boolean {
        val input = decoder.dequeueInputBuffer() ?: return false
        input.ensureSpaceForWrite(data.size)
        input.data!!.put(data)
        input.flip()
        input.timeUs = ptsUs
        decoder.queueInputBuffer(input)
        return true
    }

    /** O próximo PCM (16 bits) já descodificado, com o tempo dele, ou null. */
    fun take(): Pair<ByteArray, Long>? {
        val out = decoder.dequeueOutputBuffer() ?: return null
        try {
            if (out.isEndOfStream || out.shouldBeSkipped) return Pair(ByteArray(0), out.timeUs)
            val data = out.data ?: return Pair(ByteArray(0), out.timeUs)
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            return Pair(bytes, out.timeUs)
        } finally {
            out.release()
        }
    }

    fun release() = decoder.release()

    companion object {
        /** O FFmpeg da app descodifica [mime]? */
        fun supports(mime: String): Boolean = runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)
    }
}
