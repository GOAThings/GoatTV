package cc.slokker.itv.playback

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "Informação do stream": os números do canal a dar neste momento, como cada leitor os tem. Os que faltam vão a -1 (a
 * ponte para o Swift não tem `Int?`) e as strings vazias querem dizer "não se sabe". Os codecs podem vir pelo nome do
 * núcleo partilhado ("H264", "AAC_ADTS"…) ou já por extenso (o VLC).
 */
class StreamStatsInput(
    /** "TS", "HLS" ou "". */
    val container: String,
    /** A qualidade do HLS a dar ("1080p · 5.2 Mbit/s"), ou "". */
    val hlsVariant: String,
    /** A qualidade do HLS foi escolhida à mão (senão é a automática). */
    val hlsPinned: Boolean,
    val videoCodec: String,
    /** O profile_idc / level_idc do SPS (ver VideoInfo.profile), ou -1. */
    val videoProfile: Int,
    val videoLevel: Int,
    /** 8, 10…, ou -1. */
    val bitDepth: Int,
    val width: Int,
    val height: Int,
    val fps: Float,
    /** -1 = não se sabe; 0 = progressivo; 1 = entrelaçado. */
    val interlaced: Int,
    /** O que se está a fazer agora à imagem: desentrelaçar, aumentar, converter o HDR para SDR. */
    val deinterlacing: Boolean,
    val upscaling: Boolean,
    val toneMapping: Boolean,
    /** A transferência do VUI (16 = PQ/HDR10, 18 = HLG), ou -1. */
    val hdrTransfer: Int,
    val audioCodec: String,
    val audioChannels: Int,
    val audioSampleRate: Int,
    /** O nome da faixa de áudio a tocar, como o menu o mostra, ou "". */
    val audioTrack: String,
    /** O débito que está a chegar (ver [RollingBitrate]), em kbit/s, ou -1. */
    val bitrateKbps: Int,
    /** Vídeo já recebido à frente do que se vê (ms), ou -1. */
    val bufferMs: Long,
    /** Imagens deitadas fora por chegarem tarde e imagens mostradas, ou -1 se o motor não conta. */
    val droppedFrames: Int,
    val renderedFrames: Int,
    /** Erros do descodificador e imagens remendadas (dados que faltavam), ou -1. */
    val decodeErrors: Int,
    val concealedFrames: Int,
    /** Ligações que caíram e voltaram a abrir desde que se abriu o canal. */
    val reconnects: Int,
    /** "GoatCore", "VLC"…; [fallback]: é o motor de reserva (o GoatCore não tocou o canal). */
    val engine: String,
    val fallback: Boolean,
    /** A fonte que está a dar ("RTP 1 HD · Lista"), ou ""; [failover]: é um failover, não a da lista principal. */
    val source: String,
    val failover: Boolean,
)

/** As palavras dos valores, já traduzidas por quem chama (as chaves stats_* do Android). */
class StreamStatsWords(
    val progressive: String,
    val interlaced: String,
    val deinterlacing: String,
    val upscaling: String,
    val toneMapping: String,
    val none: String,
    val auto: String,
    val fallback: String,
    val failover: String,
)

/** Uma linha do painel: [key] é o nome da string do título (o mesmo no Android e na Apple), [value] o valor. */
class StreamStatsRow(val key: String, val value: String)

object StreamStats {
    /** As linhas do painel, sempre as mesmas e pela mesma ordem ("—" no que não se sabe). */
    fun rows(i: StreamStatsInput, w: StreamStatsWords): List<StreamStatsRow> {
        val rows = ArrayList<StreamStatsRow>()
        fun add(key: String, value: String) {
            rows += StreamStatsRow(key, value.ifEmpty { DASH })
        }
        add(KEY_ENGINE, if (i.engine.isEmpty()) "" else if (i.fallback) "${i.engine} (${w.fallback})" else i.engine)
        add(KEY_SOURCE, if (i.source.isEmpty()) "" else if (i.failover) "${i.source} (${w.failover})" else i.source)
        add(KEY_FORMAT, container(i, w))
        add(KEY_VIDEO, video(i))
        add(KEY_RESOLUTION, if (i.width > 0 && i.height > 0) "${i.width}×${i.height}" else "")
        add(KEY_FRAME_RATE, fps(i.fps))
        add(KEY_SCAN, when (i.interlaced) { 0 -> w.progressive; 1 -> w.interlaced; else -> "" })
        add(KEY_RANGE, hdrName(i.hdrTransfer, i.videoCodec.isNotEmpty()))
        add(KEY_PROCESSING, processing(i, w))
        add(KEY_AUDIO, audio(i))
        add(KEY_AUDIO_TRACK, i.audioTrack)
        add(KEY_BITRATE, if (i.bitrateKbps < 0) "" else StreamProbe.bitrate(i.bitrateKbps))
        add(KEY_BUFFER, if (i.bufferMs < 0) "" else seconds(i.bufferMs))
        add(KEY_DROPPED, frames(i.droppedFrames, i.renderedFrames))
        add(KEY_DECODE_ERRORS, if (i.decodeErrors < 0) "" else "${i.decodeErrors}")
        add(KEY_CONCEALED, if (i.concealedFrames < 0) "" else "${i.concealedFrames}")
        add(KEY_RECONNECTS, "${i.reconnects}")
        return rows
    }

    /** "HLS · 1080p · 5.2 Mbit/s · Automático", "TS". */
    fun container(i: StreamStatsInput, w: StreamStatsWords): String {
        if (i.container.isEmpty()) return ""
        if (i.hlsVariant.isEmpty()) return i.container
        return listOfNotNull(i.container, i.hlsVariant, w.auto.takeIf { !i.hlsPinned }).joinToString(" · ")
    }

    /** "H.264 High@L4.0 · 8-bit", "HEVC Main 10@L5.1 · 10-bit", "MPEG-2". */
    fun video(i: StreamStatsInput): String {
        val codec = codecName(i.videoCodec)
        if (codec.isEmpty()) return ""
        val profile = profileName(i.videoCodec, i.videoProfile)
        val level = levelName(i.videoCodec, i.videoLevel)
        var name = codec
        if (profile.isNotEmpty()) name += " $profile"
        if (level.isNotEmpty()) name += "@L$level"
        return if (i.bitDepth > 0) "$name · ${i.bitDepth}-bit" else name
    }

    /** "AC-3 · 5.1 · 48 kHz". */
    fun audio(i: StreamStatsInput): String =
        listOf(codecName(i.audioCodec), channels(i.audioChannels), sampleRate(i.audioSampleRate)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** O que se está a fazer à imagem ("Desentrelaçar · Aumentar"), ou "Nenhum". */
    fun processing(i: StreamStatsInput, w: StreamStatsWords): String {
        val on = listOfNotNull(
            w.deinterlacing.takeIf { i.deinterlacing },
            w.upscaling.takeIf { i.upscaling },
            w.toneMapping.takeIf { i.toneMapping },
        )
        return if (on.isEmpty()) w.none else on.joinToString(" · ")
    }

    /** O nome por extenso de um codec do núcleo partilhado; o resto fica como vem. */
    fun codecName(codec: String): String = when (codec) {
        "H264" -> "H.264"
        "HEVC" -> "HEVC"
        "MPEG2_VIDEO" -> "MPEG-2"
        "AAC_ADTS", "AAC_LATM" -> "AAC"
        "AC3" -> "AC-3"
        "EAC3" -> "E-AC-3"
        "MPEG_AUDIO" -> "MP2"
        else -> codec
    }

    /** O perfil pelo profile_idc (H.264) ou general_profile_idc (HEVC); "" se não se conhece. */
    fun profileName(codec: String, profile: Int): String = when (codecName(codec)) {
        "H.264" -> when (profile) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4"
            else -> ""
        }
        "HEVC" -> when (profile) {
            1 -> "Main"
            2 -> "Main 10"
            3 -> "Main Still"
            4 -> "RExt"
            else -> ""
        }
        else -> ""
    }

    /** O nível: no H.264 o level_idc é 10× ("40" → "4.0"); no HEVC é 30× ("153" → "5.1", "120" → "4"). */
    fun levelName(codec: String, level: Int): String {
        if (level <= 0) return ""
        return when (codecName(codec)) {
            "H.264" -> if (level == 9) "1b" else "${level / 10}.${level % 10}"
            "HEVC" -> {
                val tenths = level / 3
                if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
            }
            else -> ""
        }
    }

    /** "SDR", "HDR10", "HLG"; "" sem vídeo. */
    fun hdrName(transfer: Int, hasVideo: Boolean): String = when {
        transfer == ToneMappingTransfer.PQ -> "HDR10"
        transfer == ToneMappingTransfer.HLG -> "HLG"
        hasVideo -> "SDR"
        else -> ""
    }

    /** "25 fps", "29.97 fps"; "" sem cadência. */
    fun fps(fps: Float): String {
        if (fps <= 0f) return ""
        val whole = fps.roundToLong()
        if (abs(fps - whole) < 0.01f) return "$whole fps"
        val hundredths = (fps * 100).roundToLong()
        val cents = hundredths % 100
        return "${hundredths / 100}.${if (cents < 10) "0" else ""}$cents fps"
    }

    /** "2.0", "5.1", "7.1", "3 ch"; "" sem canais. */
    fun channels(n: Int): String = when {
        n <= 0 -> ""
        n == 1 -> "1.0"
        n == 2 -> "2.0"
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> "$n ch"
    }

    /** "48 kHz", "44.1 kHz"; "" sem frequência. */
    fun sampleRate(hz: Int): String {
        if (hz <= 0) return ""
        return if (hz % 1000 == 0) "${hz / 1000} kHz" else StreamProbe.oneDecimal(hz / 1000.0) + " kHz"
    }

    /** "12.3 s". */
    fun seconds(ms: Long): String = StreamProbe.seconds(ms) + " s"

    /** "3 / 4512" (deitadas fora / mostradas), "3", ou "" se o motor não conta. */
    fun frames(dropped: Int, rendered: Int): String = when {
        dropped < 0 -> ""
        rendered < 0 -> "$dropped"
        else -> "$dropped / $rendered"
    }

    const val DASH = "—"

    // Os títulos das linhas: as strings do Android (a Apple lê-as pelo mesmo nome).
    const val KEY_ENGINE = "stats_engine"
    const val KEY_SOURCE = "stats_source"
    const val KEY_FORMAT = "probe_format"
    const val KEY_VIDEO = "video"
    const val KEY_RESOLUTION = "resolution"
    const val KEY_FRAME_RATE = "stats_frame_rate"
    const val KEY_SCAN = "stats_scan"
    const val KEY_RANGE = "dynamic_range"
    const val KEY_PROCESSING = "stats_processing"
    const val KEY_AUDIO = "audio"
    const val KEY_AUDIO_TRACK = "stats_audio_track"
    const val KEY_BITRATE = "probe_bitrate"
    const val KEY_BUFFER = "buffer"
    const val KEY_DROPPED = "probe_dropped"
    const val KEY_DECODE_ERRORS = "stats_decode_errors"
    const val KEY_CONCEALED = "stats_concealed"
    const val KEY_RECONNECTS = "probe_reconnects"
}

/** As transferências do VUI que são HDR (as mesmas de ToneMapping, sem depender do pacote media). */
private object ToneMappingTransfer {
    const val PQ = 16
    const val HLG = 18
}

/**
 * O débito do canal a dar, a rolar: guarda os últimos [WINDOW_MS] de amostras (os bytes que chegaram desde sempre e,
 * se o leitor souber, o tempo de vídeo que chegou) e divide. Com o tempo do vídeo, é o débito do próprio canal (um HLS
 * chega aos solavancos, um segmento de cada vez, e um buffer cheio pára a leitura); sem ele, os bytes pelo tempo do
 * relógio. Um contador que volta para trás (o motor foi refeito) recomeça a conta.
 */
class RollingBitrate {
    private class Sample(val atMs: Long, val bytes: Long, val mediaMs: Long)

    private val samples = ArrayDeque<Sample>()

    /** [atMs]: o relógio; [totalBytes]: os bytes desde sempre; [mediaMs]: o tempo do último vídeo recebido, ou -1. */
    fun add(atMs: Long, totalBytes: Long, mediaMs: Long) {
        val last = samples.lastOrNull()
        if (last != null && (totalBytes < last.bytes || atMs < last.atMs)) samples.clear()
        samples.addLast(Sample(atMs, totalBytes, mediaMs))
        while (samples.size > 2 && atMs - samples.first().atMs > WINDOW_MS) samples.removeFirst()
    }

    fun clear() = samples.clear()

    /** Kbit/s na janela, ou -1 enquanto não há [MIN_MS] de amostras. */
    fun kbps(): Int {
        val first = samples.firstOrNull() ?: return -1
        val last = samples.last()
        val wall = last.atMs - first.atMs
        if (wall < MIN_MS) return -1
        val bytes = last.bytes - first.bytes
        val media = if (first.mediaMs >= 0 && last.mediaMs >= 0) last.mediaMs - first.mediaMs else -1L
        // O tempo do vídeo só vale se for razoável (um salto no relógio do canal, um buffer a encher de repente).
        val ms = if (media >= MIN_MS && media <= wall * 10) media else wall
        return (bytes * 8 / ms).toInt()
    }

    companion object {
        const val WINDOW_MS = 10_000L
        const val MIN_MS = 2_000L
    }
}

/** A mesma conta para o Swift (sem `object` nem listas de Kotlin por montar): ver StreamStatsRules na Apple. */
class AppleStreamStats {
    fun rows(i: StreamStatsInput, w: StreamStatsWords): List<StreamStatsRow> = StreamStats.rows(i, w)
}
