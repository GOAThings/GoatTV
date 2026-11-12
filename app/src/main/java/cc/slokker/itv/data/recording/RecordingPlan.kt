package cc.slokker.itv.data.recording

import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.EpgEntry
import java.text.Normalizer

/**
 * As regras das gravações, sem Android: as margens, o que fazer quando chega a hora de uma gravação marcada, as
 * séries e o espaço. O fornecedor só deixa uma ligação: grava-se um canal de cada vez e, a ver outro canal, ou se
 * muda para o da gravação ou não se grava.
 */
object RecordingPlan {
    /** As margens a escolher (minutos), antes e depois do programa. */
    val PADDING_OPTIONS = Prefs.Padding.OPTIONS
    const val PADDING_BEFORE_DEFAULT = Prefs.Padding.BEFORE_DEFAULT
    const val PADDING_AFTER_DEFAULT = Prefs.Padding.AFTER_DEFAULT

    /** O aviso "Mudar para X para gravar" aparece este tanto antes de a gravação começar (como os lembretes). */
    const val PROMPT_LEAD_MS = 60_000L

    /**
     * Um alarme pode chegar uns ms antes da hora: o que começa até este tanto depois já se trata como na hora (a
     * gravação começa logo, enquanto o Android ainda deixa o serviço arrancar por causa do alarme).
     */
    const val ALARM_SLACK_MS = 3_000L

    /** Sem guia, "gravar agora" grava até isto (ou até se parar). */
    const val MAX_NO_EPG_MS = 4 * 3600_000L

    /** As séries marcam os programas desta janela do guia. */
    const val SERIES_HORIZON_MS = 7 * 24 * 3600_000L

    /** Os programas já tratados esquecem-se depois disto (o guia já não os tem). */
    const val HANDLED_KEEP_MS = 3 * 24 * 3600_000L

    /** De [startMs] a [endMs] com as margens (em minutos). */
    data class Window(val fromMs: Long, val toMs: Long)

    fun window(startMs: Long, endMs: Long, beforeMin: Int, afterMin: Int) =
        Window(startMs - beforeMin * 60_000L, endMs + afterMin * 60_000L)

    fun window(r: ScheduledRecording, beforeMin: Int, afterMin: Int) = window(r.startMs, r.endMs, beforeMin, afterMin)

    /** O que o player está a usar: o canal em direto (null = nada, a app em segundo plano, ou uma gravação no disco). */
    data class Watching(val playlistId: String, val channelKey: String, val live: Boolean = true)

    /** Uma gravação a decorrer (o canal dela). */
    data class Busy(val playlistId: String, val channelKey: String)

    /** A resposta ao aviso. */
    enum class Answer { SWITCH, SKIP }

    sealed interface Decision {
        /** Ainda falta. */
        data object Wait : Decision
        /** A ver outro canal: perguntar se muda (falta pouco para começar). */
        data object Prompt : Decision
        /** Gravar agora: o canal já está aberto (a ver ou a gravar), ou não se está a ver nada. */
        data object Start : Decision
        /** Mudar o player para o canal da gravação (respondeu que sim, ou ninguém respondeu até à hora). */
        data object Switch : Decision
        /** Não gravar: [reason] é [SKIP_USER], [SKIP_BUSY] (já se grava outro canal), [SKIP_HLS] ou [SKIP_SPACE]. */
        data class Skip(val reason: String) : Decision
        /** A hora já passou sem se ter gravado. */
        data object Expired : Decision
    }

    const val SKIP_USER = "user"
    const val SKIP_BUSY = "busy"
    const val SKIP_HLS = "hls"
    const val SKIP_SPACE = "space"

    /**
     * O que fazer agora ([now]) com [r], que grava em [w]. [watching]: o canal no player; [busy]: as gravações a
     * decorrer; [answer]: o que se respondeu ao aviso (null = nada).
     *
     * Sem resposta até à hora, muda-se para o canal da gravação (como uma box com um só sintonizador): quem marcou
     * quer o programa, e quem está mesmo a ver tem um minuto para dizer que não. Uma gravação de outro canal a
     * decorrer ganha (a que já começou não se corta).
     */
    fun decide(
        r: ScheduledRecording,
        w: Window,
        now: Long,
        watching: Watching?,
        busy: List<Busy>,
        answer: Answer?,
    ): Decision {
        if (now >= w.toMs) return Decision.Expired
        if (now < w.fromMs - PROMPT_LEAD_MS) return Decision.Wait
        if (r.url == null) return if (now >= w.fromMs) Decision.Skip(SKIP_HLS) else Decision.Wait
        if (answer == Answer.SKIP) return Decision.Skip(SKIP_USER)
        val recordingThis = busy.any { r.sameChannel(it.playlistId, it.channelKey) }
        if (busy.isNotEmpty() && !recordingThis) return if (now >= w.fromMs) Decision.Skip(SKIP_BUSY) else Decision.Wait
        val watchingThis = watching != null && watching.live && r.sameChannel(watching.playlistId, watching.channelKey)
        if (watching == null || watchingThis || recordingThis) return if (now >= w.fromMs) Decision.Start else Decision.Wait
        if (answer == Answer.SWITCH || now >= w.fromMs) return Decision.Switch
        return Decision.Prompt
    }

    /** As marcadas de outros canais que se sobrepõem a [r] (com as margens): só uma delas vai gravar. */
    fun conflicts(r: ScheduledRecording, all: List<ScheduledRecording>, beforeMin: Int, afterMin: Int): List<ScheduledRecording> {
        val w = window(r, beforeMin, afterMin)
        return all.filter { o ->
            o.id != r.id && !o.sameChannel(r.playlistId, r.channelKey) &&
                window(o, beforeMin, afterMin).let { it.fromMs < w.toMs && w.fromMs < it.toMs }
        }
    }

    /**
     * A próxima hora a que a app tem de acordar (o alarme): o início, com a margem, da próxima marcada que ainda não
     * começou; null sem nenhuma. As que já começaram tratam-se com a app aberta (o processo está vivo).
     */
    fun nextWake(schedule: Schedule, beforeMin: Int, afterMin: Int, now: Long): Long? =
        schedule.recordings
            .map { window(it, beforeMin, afterMin) }
            .filter { it.fromMs > now }
            .minOfOrNull { it.fromMs }

    /** O mesmo título, sem maiúsculas, acentos nem espaços a mais (o guia não escreve sempre igual). */
    fun titleKey(title: String): String =
        Normalizer.normalize(title.trim().lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(SPACES, " ")

    /**
     * Os programas de [entries] (o guia do canal da série) que a regra [rule] marca agora: o mesmo título, ainda por
     * começar (o que já está a dar noutro canal não faz mudar de canal de repente), dentro de [SERIES_HORIZON_MS], que
     * não estejam marcados nem já tratados.
     */
    fun seriesEntries(rule: SeriesRule, entries: List<EpgEntry>, schedule: Schedule, now: Long): List<EpgEntry> {
        val key = titleKey(rule.title)
        val marked = schedule.recordings.map { it.instanceKey }.toSet()
        return entries.filter { e ->
            e.startMs > now && e.startMs < now + SERIES_HORIZON_MS && titleKey(e.title) == key &&
                ScheduledRecording.instanceKey(rule.playlistId, rule.channelKey, e.startMs).let { it !in marked && it !in schedule.handled }
        }
    }

    /** Tira o que já passou: marcadas cujo fim (com margem) já foi, e programas tratados há mais de uns dias. */
    fun prune(schedule: Schedule, beforeMin: Int, afterMin: Int, now: Long): Schedule = schedule.copy(
        recordings = schedule.recordings.filter { window(it, beforeMin, afterMin).toMs > now },
        handled = schedule.handled.filterValues { it > now - HANDLED_KEEP_MS },
    )

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")
}

/**
 * O espaço em disco das gravações, com a mesma reserva da pausa do direto (o que fica sempre livre para o sistema).
 * Não se começa com menos do que a reserva e uns minutos de vídeo; a meio, pára-se (guardando o que já está) quando o
 * livre chega à reserva.
 */
object RecordingSpace {
    const val RESERVE_BYTES = 512L * 1024 * 1024

    /** Sem saber o débito do canal: 8 Mbit/s (um canal HD normal). */
    const val DEFAULT_BYTES_PER_SECOND = 1_000_000L

    /** Para começar, tem de caber isto do canal além da reserva. */
    const val MIN_START_MS = 10 * 60_000L

    /** Cabe uma gravação nova? [free]: o livre no disco; [bytesPerSecond]: o débito do canal, se se souber. */
    fun canStart(free: Long, bytesPerSecond: Long?): Boolean =
        free - RESERVE_BYTES >= (bytesPerSecond ?: DEFAULT_BYTES_PER_SECOND) * (MIN_START_MS / 1000)

    /** A meio: pára quando o livre chega à reserva. */
    fun mustStop(free: Long): Boolean = free <= RESERVE_BYTES

    /** O débito de um anel que gravou [bytes] em [mediaMs] de vídeo, ou null com pouco ainda (menos de 5 s). */
    fun rate(bytes: Long, mediaMs: Long): Long? = if (mediaMs < 5_000) null else bytes * 1000 / mediaMs
}
