package cc.slokker.itv.playback

import cc.slokker.itv.data.nfkd
import cc.slokker.itv.media.TrackKind
import cc.slokker.itv.media.TsTrack

/**
 * Os canais de rádio (só som): o ecrã da rádio em vez de um ecrã preto e, no telemóvel, o som continua com a app em
 * segundo plano. O que manda é o próprio stream (o PMT do TS, os segmentos do HLS, o fMP4: só faixas de som); o que a
 * lista diz (`radio="true"`, o grupo ou o nome com "rádio") só vale enquanto as faixas não chegam. O mesmo no Android
 * e na Apple.
 */
object RadioRules {
    /** A lista diz que é rádio: `radio="true"` (ou "1", "yes") ou `tvg-type="radio"` no `#EXTINF` (chaves em minúsculas). */
    fun m3uHint(attrs: Map<String, String>): Boolean {
        val radio = attrs["radio"]?.trim()?.lowercase()
        return radio == "true" || radio == "1" || radio == "yes" || attrs["tvg-type"]?.trim()?.lowercase() == "radio"
    }

    /** No Xtream, os canais de rádio vêm com `stream_type` "radio_streams". */
    fun xtreamHint(streamType: String?): Boolean = streamType?.trim()?.lowercase() == "radio_streams"

    /**
     * O nome do canal ou do grupo diz rádio ("Rádio Comercial", "Radios", "Радио"), mas não TV ("Radio Bremen TV",
     * "Radio Italia TV" são canais de televisão).
     */
    fun nameHint(name: String, group: String?): Boolean {
        val n = words(name)
        if (TV.any { it in n }) return false
        if (RADIO.any { it in n }) return true
        val g = group?.let(::words) ?: return false
        return RADIO.any { it in g } && TV.none { it in g }
    }

    /** O canal parece rádio pela lista (o atributo, o grupo ou o nome). */
    fun hint(listSays: Boolean, name: String, group: String?): Boolean = listSays || nameHint(name, group)

    /** Só som: há faixas de áudio e nenhuma de vídeo. */
    fun audioOnly(hasVideo: Boolean, hasAudio: Boolean): Boolean = hasAudio && !hasVideo

    /** As faixas do demuxer (PMT, fMP4, áudio solto) são só de som. */
    fun audioOnlyTracks(tracks: List<TsTrack>): Boolean =
        audioOnly(tracks.any { it.kind == TrackKind.VIDEO }, tracks.any { it.kind == TrackKind.AUDIO })

    /**
     * É rádio? Com as faixas já conhecidas ([tracksKnown]) decide o stream; antes delas, o que a lista diz ([hint]).
     * Um canal "rádio" com imagem (um vídeo fixo, um canal de rádio filmado) é vídeo.
     */
    fun isRadio(hint: Boolean, tracksKnown: Boolean, hasVideo: Boolean, hasAudio: Boolean): Boolean =
        if (tracksKnown) audioOnly(hasVideo, hasAudio) else hint

    /** As palavras de [s] sem acentos e em minúsculas (as letras e os números juntos, o resto separa). */
    private fun words(s: String): Set<String> {
        val folded = nfkd(s.lowercase()).filter { it.category != CharCategory.NON_SPACING_MARK }
        val out = HashSet<String>()
        val word = StringBuilder()
        for (c in folded + " ") {
            if (c.isLetterOrDigit()) {
                word.append(c)
            } else if (word.isNotEmpty()) {
                out += word.toString()
                word.clear()
            }
        }
        return out
    }

    private val RADIO = setOf("radio", "radios", "радио", "радіо", "راديو")
    private val TV = setOf("tv", "tele", "television", "televisao", "televizyon", "televisione", "fernsehen", "video", "тв")
}
