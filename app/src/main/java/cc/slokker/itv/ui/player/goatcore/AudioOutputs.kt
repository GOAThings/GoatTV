package cc.slokker.itv.ui.player.goatcore

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/** O que a saída de som do aparelho aceita (para decidir o que o GoatCore faz ao PCM antes de o entregar). */
internal object AudioOutputs {
    /**
     * A saída de som em uso só leva estéreo (os altifalantes da TV, auscultadores, uma coluna Bluetooth)? Falso só
     * quando ela diz que aceita mais canais em PCM (um recetor ou uma soundbar pelo HDMI). Na dúvida (um aparelho que
     * não diz nada) é estéreo: quem liga as vozes mais claras quer a mistura feita na app.
     *
     * Os canais que o HDMI declara ([AudioDeviceInfo.getChannelCounts]) juntam os do Dolby que a TV descodifica com
     * os do PCM, e uma TV sem recetor costuma dizer "6" por causa do Dolby: por isso pergunta-se pelo PCM, pelos
     * perfis da saída (Android 12) ou por uma saída direta de PCM 5.1 (Android 10 e 11).
     */
    fun stereoOnly(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        var devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        var routed = false
        if (Build.VERSION.SDK_INT >= 33) {
            manager.getAudioDevicesForAttributes(attributes).takeIf { it.isNotEmpty() }?.let {
                devices = it
                routed = true
            }
        }
        if (!routed) {
            // Antes do Android 13 o sistema não diz por onde vai sair: uns auscultadores ou uma coluna ligados ficam
            // com o som, à frente do HDMI.
            if (devices.any { it.type in PERSONAL }) return@runCatching true
        }
        when {
            Build.VERSION.SDK_INT >= 31 -> devices.none { d ->
                d.audioProfiles.any { p -> p.format in PCM && p.channelMasks.any { Integer.bitCount(it) > 2 } }
            }
            Build.VERSION.SDK_INT >= 29 -> !AudioTrack.isDirectPlaybackSupported(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build(),
                attributes,
            )
            // Mais antigo: só o que a saída declara, e só se ela for só de PCM (senão o "6" pode ser do Dolby).
            else -> devices.none { d -> d.encodings.all { it in PCM } && (d.channelCounts.maxOrNull() ?: 0) > 2 }
        }
    }.getOrDefault(true)

    private val PCM = setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_FLOAT, 21, 22)

    private val PERSONAL = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        // Auscultadores e colunas Bluetooth LE (Android 12) e aparelhos auditivos (Android 9).
        26, 27, 23,
    )
}
