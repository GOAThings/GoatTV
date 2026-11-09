@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.engine

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.SafeInput
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.ui.player.HdrType
import cc.slokker.itv.ui.player.Timeshift
import cc.slokker.itv.ui.player.VlcPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer

// O motor dos players (TV, telemóvel e segundo canal): criar o ExoPlayer ou o VLC e abrir uma stream.

private const val NEAR_LIVE_OFFSET_MS = 4_000L

/** O buffer para arrancar com a opção Normal (ver [buildPlayer]). */
private const val NORMAL_START_MS = 650

/** O player das definições: o ExoPlayer, ou o [VlcPlayer] (Definições → Reprodução → Player). */
internal fun buildPlayer(context: Context, prefs: Prefs): Player {
    val softwareVideo = isEmulator || prefs.decoder == Prefs.Decoder.SOFTWARE
    if (prefs.engine == Prefs.Engine.VLC) {
        val caching = when (prefs.buffer) {
            Prefs.Buffer.LIVE -> 1_000
            Prefs.Buffer.SMALL -> 1_500
            Prefs.Buffer.LARGE -> 8_000
            else -> 3_000
        }
        // Se o VLC não arrancar (biblioteca nativa, box sem suporte…), fica o ExoPlayer em vez de fechar a app.
        try {
            return VlcPlayer(context, passthrough = prefs.audioPassthrough, softwareVideo = softwareVideo, networkCachingMs = caching).apply {
                playWhenReady = true
            }
        } catch (e: Throwable) {
            Log.w("itv", "O VLC não arrancou; fica o ExoPlayer", e)
        }
    }
    val softwareAudio = prefs.audioDecoder == Prefs.Decoder.SOFTWARE
    val renderers = ItvRenderersFactory(context, passthrough = prefs.audioPassthrough, hdr = prefs.hdr, ffmpegFirst = softwareAudio)
        .setEnableDecoderFallback(true)
    if (softwareVideo || softwareAudio) renderers.setMediaCodecSelector(decoderSelector(softwareVideo, softwareAudio))
    // Pequeno: arranca depressa; grande: aguenta melhor quebras de rede.
    // Mínimo = máximo e alto em todos: a ligação lê-se sempre. Com um teto baixo (o antigo "Ao vivo", 10 s) a app
    // parava de ler; muitos servidores Xtream abrandam então e o buffer esvaziava até parar (visto na SHIELD).
    // A diferença entre as opções está em quanto se espera para arrancar e depois de uma paragem.
    val (minMs, maxMs, startMs, rebufferMs) = when (prefs.buffer) {
        Prefs.Buffer.LIVE -> listOf(30_000, 30_000, 500, 2_500)
        Prefs.Buffer.SMALL -> listOf(30_000, 30_000, 1_000, 3_000)
        Prefs.Buffer.LARGE -> listOf(120_000, 120_000, 4_000, 8_000)
        // Normal: arranca com 650 ms (o Media3 usa 1 s): a imagem começa a mexer 0,35 s mais cedo num servidor sem
        // rajada; depois de uma paragem espera o mesmo de sempre.
        else -> listOf(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            NORMAL_START_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    }
    val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(minMs, maxMs, startMs, rebufferMs).build()
    return ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).build().apply {
        playWhenReady = true
        if (PLAYER_LOGS) {
            addAnalyticsListener(EventLogger())
            addAnalyticsListener(ZapTrace.listener)
        }
    }
}

private val isEmulator = Build.HARDWARE in setOf("ranchu", "goldfish")

/** Registos do player (buffer de 2 em 2 s e EventLogger): no debug e no staging, para medir na TV. */
internal val PLAYER_LOGS = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "staging"

/**
 * Põe os descodificadores de software primeiro para vídeo e/ou áudio, conforme as definições
 * (o vídeo sempre no emulador: lá o H.264 "goldfish" bloqueia em 1080p).
 */
private fun decoderSelector(softwareVideo: Boolean, softwareAudio: Boolean) = MediaCodecSelector { mimeType, secure, tunneling ->
    val infos = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
    val software = if (MimeTypes.isAudio(mimeType)) softwareAudio else softwareVideo
    if (software) infos.sortedBy { if (it.softwareOnly) 0 else 1 } else infos
}

/**
 * Sem passthrough, o áudio Dolby/DTS é sempre descodificado na box (só PCM sai para a TV).
 * O vídeo usa o [HdrVideoRenderer] (HDR → SDR quando a TV não aceita o HDR do canal).
 * O áudio tem também o FFmpeg: depois dos descodificadores da box (só entra quando ela não sabe descodificar
 * o áudio do canal: MP2, AC-3, E-AC-3, DTS…), ou primeiro com o áudio por software ([ffmpegFirst]).
 */
private class ItvRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val hdr: String,
    private val ffmpegFirst: Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        val ffmpeg = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        if (ffmpegFirst) out.add(0, ffmpeg) else out.add(ffmpeg)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Igual ao do DefaultRenderersFactory (a app não tem extensões de vídeo).
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out.add(HdrVideoRenderer(builder, context.applicationContext, hdr))
        // FFmpeg depois do descodificador da box: abre por software o vídeo que ela não descodifica (MPEG-2, HEVC 10-bit, AV1…).
        out.add(FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY))
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        if (passthrough) {
            super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        } else {
            // Capacidades "só PCM": obsoleto no Media3 1.11, mas a alternativa (AudioOutputProvider) não tem esta opção simples.
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .build()
        }
}

/**
 * Pede ao descodificador que converta o vídeo HDR para SDR quando é preciso (ver [HdrType.needsSdr]).
 * Só existe no Android 13+; os descodificadores que não o fazem ignoram o pedido e o vídeo segue em HDR.
 */
private class HdrVideoRenderer(builder: Builder, private val context: Context, private val hdr: String) : MediaCodecVideoRenderer(builder) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && HdrType.needsSdr(context, format, hdr)) {
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        return mediaFormat
    }
}

/** Escolhe a faixa de áudio com mais canais (5.1/7.1), se houver. Devolve true se decidiu. */
internal fun selectSurroundTrack(player: Player, tracks: Tracks): Boolean {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audio.isEmpty()) return false
    val best = audio.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        .maxByOrNull { (g, i) -> g.getTrackFormat(i).channelCount }
        ?: return true
    val (group, index) = best
    if (group.getTrackFormat(index).channelCount > 2 && !group.isTrackSelected(index)) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }
    return true
}

/**
 * Carrega a stream no player, seja o ExoPlayer ou o VLC (o equivalente ao `setMediaSource`).
 * [transfer]: quem conta os bytes que chegam (a página Ligação); só no ExoPlayer.
 * [timeshift]: abrir pelo anel do "pausar o direto" (só no ExoPlayer); [reconnectMs] é de quanto em quanto
 * tempo o gravador dele volta a ligar se a ligação cair. [gate]: as ligações desta tentativa (ver [StreamGate.next]).
 * [startMs]: onde começar dentro da stream (o catch-up do Stalker abre a gravação do programa e salta para o ponto).
 */
internal fun Player.loadStream(
    candidate: StreamCandidate,
    channel: Channel?,
    nearLive: Boolean,
    userAgent: String,
    transfer: TransferListener? = null,
    timeshift: Timeshift? = null,
    reconnectMs: Long = 0L,
    gate: StreamGate? = null,
    startMs: Long = 0L,
) {
    when (this) {
        is VlcPlayer -> setStream(candidate.url, channel?.userAgent ?: userAgent, channel?.referrer, startMs)
        is ExoPlayer -> {
            val direct = httpFactory(channel, userAgent, transfer)
            // Uma ligação de cada vez: a desta tentativa só abre depois de fechar a anterior (ver StreamGate).
            val http = gate?.factory(direct, exclusive = !candidate.isHls) ?: direct
            val source = timeshift?.open(candidate, http, tsExtractors(), reconnectMs) ?: mediaSourceFor(candidate, nearLive, http)
            if (startMs > 0) setMediaSource(source, startMs) else setMediaSource(source)
        }
    }
}

/** Há áudio ou vídeo, mas nenhuma faixa desse tipo que este player consiga descodificar. */
internal fun Tracks.cannotDecode(): Boolean {
    fun none(type: Int) = groups.filter { it.type == type }.let { g -> g.isNotEmpty() && g.none { it.isSupported(true) } }
    return none(C.TRACK_TYPE_AUDIO) || none(C.TRACK_TYPE_VIDEO)
}

/** Erros de descodificação: o canal abre, mas este player não sabe descodificar o que lá vem. */
private val DECODER_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

/** Erro de descodificação (o mesmo conjunto na TV, no telemóvel e no segundo canal). */
internal fun isDecoderError(code: Int): Boolean = code in DECODER_ERRORS

/** A proporção da imagem (com os píxeis não quadrados), ou null enquanto não se sabe o tamanho. */
internal fun VideoSize.aspectOrNull(): Float? = if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else null

/** Como a imagem enche o ecrã ([AspectRatioFrameLayout]), conforme a definição Proporção ([Prefs.aspect]). */
internal fun resizeModeFor(aspect: String): Int = when (aspect) {
    Prefs.Aspect.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    Prefs.Aspect.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** Tamanho da última imagem guardada ao mudar de canal (fica no ecrã até o canal novo mostrar a sua). */
internal const val FREEZE_WIDTH = 1280
internal const val FREEZE_HEIGHT = 720

/** O formato do vídeo a tocar (só o ExoPlayer o dá; com o VLC não há AFR nem a cadência do vidro). */
internal fun Player.videoFormat(): Format? = (this as? ExoPlayer)?.videoFormat

/** A ligação HTTP ao servidor do canal. */
private fun httpFactory(channel: Channel?, userAgent: String, transfer: TransferListener?): DefaultHttpDataSource.Factory {
    // Muitos servidores redirecionam de http para https ou para outro host.
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(SafeInput.stripControl(channel?.userAgent ?: userAgent))
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setTransferListener(transfer)
    channel?.referrer?.let(SafeInput::stripControl)?.takeIf { it.isNotEmpty() }?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
    return http
}

/** Extratores para streams progressivas (.ts e o resto). */
private fun tsExtractors() = DefaultExtractorsFactory().setTsExtractorFlags(
    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
)

/** [nearLive]: nas streams HLS, tocar o mais perto possível do direto. */
private fun mediaSourceFor(candidate: StreamCandidate, nearLive: Boolean, http: DataSource.Factory): MediaSource {
    val item = MediaItem.Builder()
        .setUri(candidate.url)
        .apply {
            if (nearLive) {
                setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(NEAR_LIVE_OFFSET_MS).build())
            }
        }
        .build()
    return if (candidate.isHls) {
        HlsMediaSource.Factory(http).createMediaSource(item)
    } else {
        // Recusas "ocupado" do fornecedor: tentativas rápidas (ver OpenRetry); o resto como no Media3.
        ProgressiveMediaSource.Factory(http, tsExtractors()).setLoadErrorHandlingPolicy(OpenRetry.Policy()).createMediaSource(item)
    }
}
