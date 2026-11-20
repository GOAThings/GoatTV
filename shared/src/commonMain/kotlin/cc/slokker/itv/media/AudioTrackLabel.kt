package cc.slokker.itv.media

/**
 * O papel de uma faixa de áudio, como o stream o diz: o descritor ISO 639 do PMT (audio_type), o supplementary_audio
 * (DVB), o component_type do AC-3, ou o HLS (CHARACTERISTICS, NAME). Números simples (e não um enum) para chegarem
 * ao Swift sem surpresas.
 */
object AudioRole {
    /** Sem papel especial (o som normal do programa). */
    const val MAIN = 0
    /** Só música e efeitos (sem diálogo). */
    const val CLEAN_EFFECTS = 1
    /** Diálogo mais claro, para quem ouve mal. */
    const val HEARING_IMPAIRED = 2
    /** Audiodescrição: um comentário para quem vê mal. */
    const val DESCRIPTION = 3
    /** O som original do programa (a língua em que foi feito). */
    const val ORIGINAL = 4

    /** O audio_type do descritor ISO 639: 1 efeitos, 2 para quem ouve mal, 3 comentário para quem vê mal. */
    fun fromIsoAudioType(type: Int): Int = when (type) {
        1 -> CLEAN_EFFECTS
        2 -> HEARING_IMPAIRED
        3 -> DESCRIPTION
        else -> MAIN
    }

    /** A editorial_classification do supplementary_audio_descriptor (DVB, extensão 0x06). */
    fun fromEditorial(classification: Int): Int = when (classification) {
        0x00 -> ORIGINAL // main audio
        0x01 -> DESCRIPTION
        0x02 -> HEARING_IMPAIRED // clean audio
        else -> MAIN
    }

    /** O service_type_flags do component_type do AC-3 (EN 300 468, anexo D): 2 audiodescrição, 3 para quem ouve mal. */
    fun fromAc3Service(service: Int): Int = when (service) {
        1 -> CLEAN_EFFECTS
        2 -> DESCRIPTION
        3 -> HEARING_IMPAIRED
        else -> MAIN
    }

    /** Os canais do number_of_channels do component_type do AC-3 (0 mono, 1 dual mono, 2 e 3 estéreo, 4 até 5.1, 5 mais); 0 = não diz. */
    fun channelsOfComponent(code: Int): Int = when (code) {
        0 -> 1
        1, 2, 3 -> 2
        4 -> 6
        5 -> 8
        else -> 0
    }

    /**
     * O papel pelo HLS: CHARACTERISTICS ("public.accessibility.describes-video" é a audiodescrição,
     * "public.accessibility.transcribes-spoken-dialog" é de quem ouve mal, "public.main-program-content" e "original" o
     * som original) e, sem isso, o NAME ("Audio Description", "AD", "Original"…).
     */
    fun fromHls(characteristics: String?, name: String?): Int {
        val c = characteristics.orEmpty().lowercase()
        if ("describes-video" in c) return DESCRIPTION
        if ("enhances-speech-intelligibility" in c || "transcribes-spoken-dialog" in c) return HEARING_IMPAIRED
        if ("describes-music-and-sound" in c) return CLEAN_EFFECTS
        if ("original" in c) return ORIGINAL
        val n = name.orEmpty().lowercase().trim()
        return when {
            "audio description" in n || "audiodescri" in n || "descriptive" in n || n == "ad" || n.endsWith(" ad") -> DESCRIPTION
            "hearing" in n || "clean audio" in n -> HEARING_IMPAIRED
            n == "original" || n.startsWith("original ") || n.endsWith(" original") || n == "ov" || n == "vo" -> ORIGINAL
            else -> MAIN
        }
    }

    /** O que distingue a faixa na memória do canal (TrackInfo.detail, igual nas duas plataformas); null no som normal. */
    fun detail(role: Int): String? = when (role) {
        DESCRIPTION -> "ad"
        HEARING_IMPAIRED -> "hi"
        CLEAN_EFFECTS -> "sup"
        ORIGINAL -> "orig"
        else -> null
    }
}

/** Os textos (já traduzidos) de que os nomes das faixas precisam; [audioN] é o "Áudio %d" (o %d fica como está). */
class AudioLabelWords(
    val original: String,
    val audioDescription: String,
    val hearingImpaired: String,
    val cleanEffects: String,
    val mono: String,
    val stereo: String,
    val audioN: String,
)

/** Uma faixa de áudio a nomear: a língua já escrita ("" = desconhecida), o papel, os canais (0 = não se sabe) e o formato ("AC-3"). */
class AudioLabelInput(val language: String, val role: Int, val channels: Int, val codec: String)

/**
 * O nome de cada faixa de áudio, igual no Android e na Apple: "Português · 5.1", "English · Original", "Português ·
 * Audiodescrição", "Español · Estéreo · AC-3" (o formato só quando duas faixas ficariam com o mesmo nome). Sem língua:
 * "Áudio 2 · 5.1" (a posição na lista).
 */
object AudioTrackLabel {
    /** Os canais em texto: "Mono", "Estéreo", "5.1", "7.1"…; "" quando não se sabe. */
    fun channelsText(channels: Int, words: AudioLabelWords): String = when (channels) {
        0 -> ""
        1 -> words.mono
        2 -> words.stereo
        3 -> "2.1"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "$channels ch"
    }

    fun roleText(role: Int, words: AudioLabelWords): String = when (role) {
        AudioRole.DESCRIPTION -> words.audioDescription
        AudioRole.HEARING_IMPAIRED -> words.hearingImpaired
        AudioRole.CLEAN_EFFECTS -> words.cleanEffects
        AudioRole.ORIGINAL -> words.original
        else -> ""
    }

    /** Os nomes de [items] (pela mesma ordem). */
    fun labels(items: List<AudioLabelInput>, words: AudioLabelWords): List<String> {
        fun base(i: Int, codec: Boolean): String {
            val t = items[i]
            val head = t.language.ifEmpty { words.audioN.replace("%d", (i + 1).toString()) }
            return listOf(head, roleText(t.role, words), channelsText(t.channels, words), if (codec) t.codec else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val plain = items.indices.map { base(it, codec = false) }
        // O formato só onde o nome se repete.
        val withCodec = items.indices.map { if (plain.count { p -> p == plain[it] } > 1) base(it, codec = true) else plain[it] }
        // Se ainda se repete (mesma língua, formato e canais), a ordem entre as iguais.
        return withCodec.mapIndexed { i, label ->
            if (withCodec.count { it == label } > 1) "$label · ${(0..i).count { withCodec[it] == label }}" else label
        }
    }
}
