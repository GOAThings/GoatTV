package cc.slokker.itv.playback

/** O que fazer quando a tentativa atual de dar um canal falha (ver [PlaybackRules.nextStep]). */
enum class NextStep {
    /** O formato seguinte do mesmo canal (ts ⇄ m3u8). */
    NEXT_FORMAT,

    /** Os failovers do canal (a lista de reserva ou outra versão na própria lista), pela ordem. */
    FAILOVER,

    /** Já num failover: o seguinte ou, sem mais, o erro da lista principal. */
    NEXT_FAILOVER,

    /** Caiu depois de dar: volta a ligar daqui a pouco, desde o primeiro formato. */
    RECONNECT,

    /** Mostra o erro (e, conforme a plataforma, mais umas tentativas mais tarde). */
    GIVE_UP,
}

/**
 * Como estava a tentativa que falhou. [started]: esta tentativa chegou a dar; [wasPlaying]: o canal já deu desde que
 * se escolheu; [failoverAllowed]: há failovers ligados e ainda não se foi procurá-los nesta volta; [reconnect]:
 * "voltar a ligar" ligado; [noAnswer]: o servidor não respondeu a tempo (não é uma recusa nem um erro rápido).
 */
class AttemptFailure(
    val live: Boolean,
    val started: Boolean,
    val wasPlaying: Boolean,
    val onBackup: Boolean,
    val hasNextFormat: Boolean,
    val failoverAllowed: Boolean,
    val reconnect: Boolean,
    val noAnswer: Boolean,
)

/**
 * O cérebro das tentativas, igual no Android (ChannelPlayback) e na Apple (PlayerEngine): os tempos e o que fazer
 * quando um canal falha. Cada plataforma trata do que é dela (o motor, o catch-up, o VLC para o que não descodifica).
 */
object PlaybackRules {
    /** Quanto um direto tem para começar a dar: sem imagem até lá, o servidor não respondeu. */
    const val OPEN_TIMEOUT_MS = 8_000L

    /** Num motor que se junta ao direto com mais calma (o AetherEngine). */
    const val SLOW_ENGINE_OPEN_TIMEOUT_MS = 10_000L

    /** Mais isto com o buffer grande (espera 4 s de vídeo para arrancar). */
    const val LARGE_BUFFER_EXTRA_MS = 2_000L

    /** Parado a carregar há tanto tempo no direto (o fornecedor deixou de mandar sem fechar): volta a ligar. */
    const val STALL_MS = 12_000L

    /** Tantas paragens destas dentro de [STALL_WINDOW_MS]: passa-se aos failovers. */
    const val STALL_WINDOW_MS = 180_000L
    const val STALLS_FOR_FAILOVER = 2

    /** Mudança de canal automática: duas mudanças com menos do que isto entre elas = a passar canais. */
    const val ZAP_FLIP_WINDOW_MS = 500L

    /** A passar canais, o canal abre quando não há outra mudança durante isto. */
    const val ZAP_SETTLE_MS = 350L

    /** "Atraso ao mudar de canal": automático e desligado (os outros valores são o tempo fixo, em ms). */
    const val ZAP_AUTO = -1
    const val ZAP_OFF = 0

    /** O tempo para um direto começar a dar ([OPEN_TIMEOUT_MS] e os extras). */
    fun openTimeoutMs(largeBuffer: Boolean, slowEngine: Boolean): Long =
        (if (slowEngine) SLOW_ENGINE_OPEN_TIMEOUT_MS else OPEN_TIMEOUT_MS) + (if (largeBuffer) LARGE_BUFFER_EXTRA_MS else 0L)

    /**
     * O passo seguinte quando a tentativa [f] falha.
     *
     * 1. Um direto que não respondeu: os failovers já (um servidor que não responde num formato raramente responde no
     *    outro), também a voltar a ligar depois de cair; num failover, o seguinte.
     * 2. Uma tentativa que ainda não deu: o outro formato (há canais que só dão em m3u8).
     * 3. Caiu depois de dar: volta a ligar (com "voltar a ligar" ligado).
     * 4. Um direto da lista principal: os failovers.
     * 5. Num failover: o seguinte. Senão, o erro.
     *
     * Quando os failovers se procuram e não há nenhum, pergunta-se outra vez com [AttemptFailure.failoverAllowed] a falso.
     */
    fun nextStep(f: AttemptFailure): NextStep {
        if (f.noAnswer && f.live) {
            if (f.onBackup) return NextStep.NEXT_FAILOVER
            if (f.failoverAllowed) return NextStep.FAILOVER
        }
        if (!f.started && f.hasNextFormat) return NextStep.NEXT_FORMAT
        if (f.wasPlaying && f.reconnect) return NextStep.RECONNECT
        if (f.live && !f.onBackup && f.failoverAllowed) return NextStep.FAILOVER
        if (f.onBackup) return NextStep.NEXT_FAILOVER
        return NextStep.GIVE_UP
    }

    /**
     * Mudou-se de canal [sinceLastMs] depois da mudança anterior (-1 = a primeira) com a definição [setting]: quanto
     * esperar antes de o abrir. Automático: uma tecla abre já; a passar canais, só quando se pára.
     */
    fun zapDelayMs(sinceLastMs: Long, setting: Int): Long = when (setting) {
        ZAP_OFF -> 0L
        ZAP_AUTO -> if (sinceLastMs in 0 until ZAP_FLIP_WINDOW_MS) ZAP_SETTLE_MS else 0L
        else -> setting.coerceAtLeast(0).toLong()
    }
}

/** As vezes que o direto de um canal ficou preso a carregar (ver [PlaybackRules.STALL_MS]). */
class StallCounter {
    private val times = ArrayDeque<Long>()

    /** Quantas nos últimos [PlaybackRules.STALL_WINDOW_MS] (contando a última). */
    val count: Int get() = times.size

    /**
     * Ficou preso em [nowMs]: true = passar aos failovers (a segunda vez dentro da janela, com [failoverAllowed]; o
     * contador recomeça), false = voltar a ligar.
     */
    fun stalled(nowMs: Long, failoverAllowed: Boolean): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() >= PlaybackRules.STALL_WINDOW_MS) times.removeFirst()
        times.addLast(nowMs)
        if (times.size >= PlaybackRules.STALLS_FOR_FAILOVER && failoverAllowed) {
            times.clear()
            return true
        }
        return false
    }

    /** Outro canal: as paragens do anterior não contam. */
    fun clear() = times.clear()
}
