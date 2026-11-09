package cc.slokker.itv.data.recording

import cc.slokker.itv.data.StreamCandidate

/**
 * Uma gravação no disco: o `<id>.ts` e esta ficha ao lado, `<id>.json` (ver [RecordingFiles]). As horas são ms desde
 * 1970; os cortes [mediaStartMs]/[mediaEndMs] são do relógio do vídeo do anel (os PCR do TS, ver TimeshiftRing), que
 * é por onde se corta.
 */
data class RecordingInfo(
    val version: Int = VERSION,
    val id: String,
    val playlistId: String?,
    val channelKey: String?,
    val channelName: String,
    val logo: String?,
    val title: String,
    /** O guia desta app não guarda descrições: fica null (o campo existe para quando houver). */
    val description: String? = null,
    /** O programa no guia (sem guia, o que se pediu: do carregar no botão até ao máximo). */
    val programmeStartMs: Long,
    val programmeEndMs: Long,
    /** O que se quis gravar: o programa com as margens antes e depois. */
    val startMs: Long,
    val endMs: Long,
    /** A hora real do primeiro e do último byte do ficheiro (o fim vai avançando enquanto grava). */
    val fileStartMs: Long,
    val fileEndMs: Long,
    val mediaStartMs: Long,
    val mediaEndMs: Long,
    val bytes: Long,
    /** Ver [RecordingState]. */
    val state: String,
    /** Porque parou antes do fim (ver [RecordingReason]); null quando acabou no fim ou ainda grava. */
    val reason: String? = null,
    /** Quantas vezes faltou um bocado (a ligação caiu e voltou, ou a escrita ficou para trás). */
    val gaps: Int = 0,
    /** A gravação marcada que a fez (ver [ScheduledRecording]); null quando foi "gravar agora". */
    val scheduledId: String? = null,
) {
    val durationMs: Long get() = (fileEndMs - fileStartMs).coerceAtLeast(0)
    val recording: Boolean get() = state == RecordingState.RECORDING

    companion object {
        const val VERSION = 1
    }
}

object RecordingState {
    const val RECORDING = "recording"
    /** Chegou ao fim que se queria. */
    const val DONE = "done"
    /** Parou antes (à mão, por falta de espaço, porque a ligação acabou ou a app morreu): ver o [RecordingInfo.reason]. */
    const val STOPPED = "stopped"
    /** Não chegou a gravar nada. */
    const val FAILED = "failed"
}

object RecordingReason {
    const val USER = "user"
    const val LOW_SPACE = "low_space"
    /** A ligação fechou de vez (outro canal precisou dela, ou o servidor deixou de dar). */
    const val CONNECTION = "connection"
    /** A app terminou a meio (a ficha ficou como "a gravar"). */
    const val APP_ENDED = "app_ended"
}

/**
 * Uma gravação marcada no guia. Guarda o que é preciso para gravar sem a app aberta: as tentativas do canal (com as
 * credenciais da lista no URL, por isso esta lista fica na memória interna da app, não ao lado das gravações) e o
 * User-Agent. [seriesId]: veio de uma regra "gravar a série" ([SeriesRule]).
 */
data class ScheduledRecording(
    val id: String,
    val playlistId: String,
    val channelKey: String,
    val channelName: String,
    val logo: String?,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val candidates: List<StreamCandidate>,
    val userAgent: String,
    val referrer: String? = null,
    val seriesId: String? = null,
) {
    /** O programa (lista, canal e início): é assim que se reconhece no guia e nas séries. */
    val instanceKey: String get() = instanceKey(playlistId, channelKey, startMs)

    /** O endereço que se grava (o primeiro TS: o HLS não se grava, ver RecordingPlan). */
    val url: String? get() = candidates.firstOrNull { !it.isHls }?.url

    fun sameChannel(playlistId: String, channelKey: String) = this.playlistId == playlistId && this.channelKey == channelKey

    companion object {
        fun instanceKey(playlistId: String, channelKey: String, startMs: Long) = "$playlistId|$channelKey|$startMs"
    }
}

/** "Gravar a série": todos os programas com este título neste canal. */
data class SeriesRule(
    val id: String,
    val playlistId: String,
    val channelKey: String,
    val channelName: String,
    val logo: String?,
    val title: String,
)

/**
 * O que está marcado. [handled]: programas que já não se voltam a marcar pela série (já gravados, cancelados ou
 * saltados), com a hora de início para se esquecerem ao fim de uns dias (ver RecordingPlan.prune).
 */
data class Schedule(
    val version: Int = VERSION,
    val recordings: List<ScheduledRecording> = emptyList(),
    val series: List<SeriesRule> = emptyList(),
    val handled: Map<String, Long> = emptyMap(),
) {
    companion object {
        const val VERSION = 1
    }
}
