package cc.slokker.itv.ui.player

/**
 * O que o Voltar faz no player da TV. Recua um nível, por esta ordem: avisos → PIN → janelas (pesquisa, guia…) →
 * grupos → canais → informação → catch-up (volta ao direto) → pausa do direto → segundo canal → guia ou sair,
 * conforme as definições.
 */
internal enum class TvBack {
    DISMISS_REMINDER, CLEAR_NUMBER, CLOSE_NET_HINT, CLOSE_PIN, CLOSE_PLAYLIST_MENU, CLOSE_SLEEP,
    /** Recua no seletor do segundo canal (da lista de canais para os grupos) ou fecha-o. */
    SECOND_PICKER,
    CLOSE_TRACKS, CLOSE_SEARCH,
    /** O painel "Canal de reserva". */
    CLOSE_BACKUP, CLOSE_RECORDINGS, CLOSE_GUIDE, CLOSE_EPG_PANEL, CLOSE_PANEL, HIDE_INFO, EXIT_CATCHUP, GO_LIVE, CLOSE_SECOND,
    /** Com "Voltar abre o guia": abre-o (o Voltar seguinte, ao fechá-lo, conta como o primeiro para sair). */
    OPEN_GUIDE,
    EXIT,
    /** Pede um segundo Voltar para sair. */
    WARN_EXIT,
}

/** O que está aberto (ou a decorrer) no player quando se carrega no Voltar. */
internal class TvBackState(
    val reminder: Boolean = false,
    val numberEntry: Boolean = false,
    val netHint: Boolean = false,
    val pin: Boolean = false,
    val playlistMenu: Boolean = false,
    val sleep: Boolean = false,
    val secondPicker: Boolean = false,
    val tracks: Boolean = false,
    val search: Boolean = false,
    val backup: Boolean = false,
    val recordings: Boolean = false,
    val guide: Boolean = false,
    val epgPanel: Boolean = false,
    val panel: Boolean = false,
    /** A informação do canal à vista, sem erro (com erro fica: explica porque não dá). */
    val info: Boolean = false,
    val catchup: Boolean = false,
    /** Na pausa do direto, atrás do direto ou em pausa. */
    val shifted: Boolean = false,
    val second: Boolean = false,
    /** Definição "Voltar no vídeo": abre o guia (senão sai). */
    val backOpensGuide: Boolean = false,
    val exitConfirm: Boolean = true,
)

/** Dois Voltar seguidos, dentro deste tempo, saem da app. */
internal const val DOUBLE_BACK_MS = 2_500L

/** O que o Voltar faz com [state] aberto, [sinceLastBackMs] depois do último Voltar que contou para sair. */
internal fun tvBack(state: TvBackState, sinceLastBackMs: Long): TvBack = with(state) {
    when {
        reminder -> TvBack.DISMISS_REMINDER
        numberEntry -> TvBack.CLEAR_NUMBER
        netHint -> TvBack.CLOSE_NET_HINT
        pin -> TvBack.CLOSE_PIN
        playlistMenu -> TvBack.CLOSE_PLAYLIST_MENU
        sleep -> TvBack.CLOSE_SLEEP
        secondPicker -> TvBack.SECOND_PICKER
        tracks -> TvBack.CLOSE_TRACKS
        search -> TvBack.CLOSE_SEARCH
        backup -> TvBack.CLOSE_BACKUP
        recordings -> TvBack.CLOSE_RECORDINGS
        guide -> TvBack.CLOSE_GUIDE
        epgPanel -> TvBack.CLOSE_EPG_PANEL
        // Voltar fecha o painel lateral (canais ou grupos) e volta ao vídeo. Antes os grupos voltavam
        // aos canais e os canais aos grupos: vindo de um grupo, o painel nunca chegava a fechar.
        panel -> TvBack.CLOSE_PANEL
        info -> TvBack.HIDE_INFO
        catchup -> TvBack.EXIT_CATCHUP
        shifted -> TvBack.GO_LIVE
        second -> TvBack.CLOSE_SECOND
        backOpensGuide && sinceLastBackMs >= DOUBLE_BACK_MS -> TvBack.OPEN_GUIDE
        !exitConfirm || sinceLastBackMs < DOUBLE_BACK_MS -> TvBack.EXIT
        else -> TvBack.WARN_EXIT
    }
}
