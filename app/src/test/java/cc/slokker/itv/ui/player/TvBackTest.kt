package cc.slokker.itv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TvBackTest {
    private val long = DOUBLE_BACK_MS + 1
    private val short = DOUBLE_BACK_MS - 1

    @Test
    fun `each open thing is closed before the next one, in order`() {
        // Tudo aberto: cada Voltar fecha o de cima; depois de o fechar, o seguinte é o próximo da lista.
        val order = listOf(
            TvBack.DISMISS_REMINDER, TvBack.CLEAR_NUMBER, TvBack.CLOSE_NET_HINT, TvBack.CLOSE_PIN, TvBack.CLOSE_PLAYLIST_MENU,
            TvBack.CLOSE_SLEEP, TvBack.SECOND_PICKER, TvBack.CLOSE_TRACKS, TvBack.CLOSE_SEARCH, TvBack.CLOSE_RECORDINGS, TvBack.CLOSE_GUIDE,
            TvBack.CLOSE_EPG_PANEL, TvBack.CLOSE_PANEL, TvBack.HIDE_INFO, TvBack.EXIT_CATCHUP, TvBack.GO_LIVE, TvBack.CLOSE_SECOND,
        )
        val open = order.toMutableSet()
        for (expected in order) {
            assertEquals(expected, tvBack(stateWith(open), long))
            open -= expected
        }
        assertEquals(TvBack.WARN_EXIT, tvBack(stateWith(open), long))
    }

    @Test
    fun `with nothing open back asks for a second press, then exits`() {
        assertEquals(TvBack.WARN_EXIT, tvBack(TvBackState(), long))
        assertEquals(TvBack.EXIT, tvBack(TvBackState(), short))
        assertEquals(TvBack.EXIT, tvBack(TvBackState(exitConfirm = false), long))
    }

    @Test
    fun `back opens the guide when set, unless it was just pressed`() {
        assertEquals(TvBack.OPEN_GUIDE, tvBack(TvBackState(backOpensGuide = true), long))
        // Fechar o guia que o Voltar abriu conta como o 1.º Voltar: o seguinte sai.
        assertEquals(TvBack.EXIT, tvBack(TvBackState(backOpensGuide = true), short))
        assertEquals(TvBack.CLOSE_GUIDE, tvBack(TvBackState(guide = true, backOpensGuide = true), short))
    }

    @Test
    fun `the info stays while the channel shows an error`() {
        // info=false quando há erro (quem chama junta as duas coisas): o Voltar passa ao seguinte.
        assertEquals(TvBack.EXIT_CATCHUP, tvBack(TvBackState(info = false, catchup = true), long))
    }

    private fun stateWith(open: Set<TvBack>) = TvBackState(
        reminder = TvBack.DISMISS_REMINDER in open,
        numberEntry = TvBack.CLEAR_NUMBER in open,
        netHint = TvBack.CLOSE_NET_HINT in open,
        pin = TvBack.CLOSE_PIN in open,
        playlistMenu = TvBack.CLOSE_PLAYLIST_MENU in open,
        sleep = TvBack.CLOSE_SLEEP in open,
        secondPicker = TvBack.SECOND_PICKER in open,
        tracks = TvBack.CLOSE_TRACKS in open,
        search = TvBack.CLOSE_SEARCH in open,
        recordings = TvBack.CLOSE_RECORDINGS in open,
        guide = TvBack.CLOSE_GUIDE in open,
        epgPanel = TvBack.CLOSE_EPG_PANEL in open,
        panel = TvBack.CLOSE_PANEL in open,
        info = TvBack.HIDE_INFO in open,
        catchup = TvBack.EXIT_CATCHUP in open,
        shifted = TvBack.GO_LIVE in open,
        second = TvBack.CLOSE_SECOND in open,
    )
}
