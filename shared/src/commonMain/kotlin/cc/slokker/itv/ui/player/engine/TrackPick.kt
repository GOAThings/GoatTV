package cc.slokker.itv.ui.player.engine

import cc.slokker.itv.data.Languages

// A escolha automática das faixas ao abrir um canal: a língua preferida do áudio e das legendas (Definições) e, na
// TV, o áudio surround. Sem o player: cada plataforma traduz as faixas do seu para [TrackInfo]. O mesmo no Android e na
// Apple.

/** Uma faixa de áudio ou de legendas, como a escolha automática a vê. */
data class TrackInfo(
    /** A língua que o stream diz (o `language` do Format: "pt", "por", "qaa"…). */
    val language: String?,
    /** A etiqueta (HLS NAME, ou a descrição do VLC: "Faixa 1 - [Portuguese]"). */
    val label: String?,
    val channels: Int = 0,
    val supported: Boolean = true,
    val selected: Boolean = false,
    /** Legendas "forçadas": só as falas noutra língua, não o programa todo. */
    val forced: Boolean = false,
) {
    /** A língua em ISO 639-1: a do campo da língua ou, sem ela, a que a etiqueta diz. */
    val code: String? get() = Languages.code(language) ?: Languages.code(label)
}

/** O que se quer das faixas ao abrir um canal (null = deixar o que o player escolheu). */
data class TrackWish(
    /** A língua do áudio (ISO 639-1); null = a do stream. */
    val audioLanguage: String?,
    /** A língua das legendas; null = sem legendas além das que o stream marca (as forçadas). */
    val textLanguage: String?,
    /** A faixa com mais canais (5.1/7.1), dentro da língua preferida se a houver. */
    val surround: Boolean,
    /** Decidir o áudio / as legendas agora (já decididos, ou escolhidos à mão neste canal, ficam). */
    val audio: Boolean = true,
    val text: Boolean = true,
)

object TrackPick {

    /**
     * A faixa de áudio a escolher (índice em [tracks]), ou null para ficar a que está. Com [language], só as dessa
     * língua (se houver alguma; sem nenhuma, todas); com [surround], a de mais canais se tiver mais de dois.
     */
    fun audio(tracks: List<TrackInfo>, language: String?, surround: Boolean): Int? {
        val usable = tracks.indices.filter { tracks[it].supported }
        if (usable.isEmpty()) return null
        val wanted = if (language == null) emptyList() else usable.filter { tracks[it].code == language }
        val pool = wanted.ifEmpty { usable }
        var pick: Int? = null
        if (surround) {
            val best = pool.maxBy { tracks[it].channels }
            if (tracks[best].channels > 2) pick = best
        }
        if (pick == null && wanted.isNotEmpty()) pick = wanted.firstOrNull { tracks[it].selected } ?: wanted.first()
        return pick?.takeIf { !tracks[it].selected }
    }

    /**
     * As legendas a ligar (índice em [tracks]) para [language], ou null para não mexer: sem língua escolhida, ou sem
     * legendas nessa língua. Entre as dessa língua, as completas antes das forçadas.
     */
    fun text(tracks: List<TrackInfo>, language: String?): Int? {
        if (language == null) return null
        val wanted = tracks.indices.filter { tracks[it].supported && tracks[it].code == language }
        if (wanted.isEmpty()) return null
        val full = wanted.filter { !tracks[it].forced }.ifEmpty { wanted }
        return full.firstOrNull { tracks[it].selected } ?: full.first()
    }
}

/**
 * Que tipos de faixa (C.TRACK_TYPE_AUDIO/TEXT) ainda falta escolher no canal aberto. Escolhe-se uma vez por abertura
 * (as faixas chegam aos poucos: o áudio e as legendas decidem-se cada um quando aparece). Uma escolha à mão no menu
 * ganha até se mudar de canal: o catch-up, os saltos e as reconexões do mesmo canal não a desfazem.
 */
class TrackChoice {
    private var channel: String? = null
    private val decided = HashSet<Int>()
    private val manual = HashSet<Int>()

    /** Abriu-se [channelKey] (outro canal, ou o mesmo noutro ponto): volta a escolher, menos o que se escolheu à mão nele. */
    fun onOpen(channelKey: String) {
        if (channelKey != channel) {
            channel = channelKey
            manual.clear()
        }
        decided.clear()
    }

    fun onManual(type: Int) {
        manual += type
    }

    fun isManual(type: Int): Boolean = type in manual

    fun pending(type: Int): Boolean = type !in decided && type !in manual

    fun done(type: Int) {
        decided += type
    }
}
