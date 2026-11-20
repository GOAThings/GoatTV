package cc.slokker.itv.playback

import cc.slokker.itv.data.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals

class IdleWatchTest {
    private val h2 = IdleWatch.limitMs(Prefs.IdleStop.H2)

    @Test fun limitsFollowTheSetting() {
        assertEquals(0L, IdleWatch.limitMs(Prefs.IdleStop.OFF))
        assertEquals(7_200_000L, h2)
        assertEquals(14_400_000L, IdleWatch.limitMs(Prefs.IdleStop.H4))
        assertEquals(21_600_000L, IdleWatch.limitMs(Prefs.IdleStop.H6))
        assertEquals(0L, IdleWatch.limitMs("3h"))
    }

    @Test fun offNeverPrompts() {
        val w = IdleWatch()
        w.tick(1_000, 0, true)
        assertEquals(IdleWatch.WATCHING, w.tick(1_000 + 100 * 3_600_000L, 0, true))
    }

    @Test fun promptsAfterTheLimitThenStopsAfterSixtySeconds() {
        val w = IdleWatch()
        w.tick(1_000, h2, true)
        assertEquals(IdleWatch.WATCHING, w.tick(1_000 + h2 - 1, h2, true))
        assertEquals(IdleWatch.PROMPT, w.tick(1_000 + h2, h2, true))
        assertEquals(60, w.secondsLeft(1_000 + h2))
        assertEquals(30, w.secondsLeft(1_000 + h2 + 30_000))
        assertEquals(IdleWatch.PROMPT, w.tick(1_000 + h2 + 59_999, h2, true))
        assertEquals(1, w.secondsLeft(1_000 + h2 + 59_999))
        assertEquals(IdleWatch.STOPPED, w.tick(1_000 + h2 + 60_000, h2, true))
        assertEquals(0, w.secondsLeft(1_000 + h2 + 60_000))
    }

    @Test fun inputRestartsTheCountBeforeAndDuringThePrompt() {
        val w = IdleWatch()
        w.tick(0 + 1, h2, true)
        w.onInput(h2 - 10)
        assertEquals(IdleWatch.WATCHING, w.tick(h2 + 5, h2, true))
        assertEquals(IdleWatch.PROMPT, w.tick(h2 - 10 + h2, h2, true))
        w.onInput(h2 - 10 + h2 + 20_000)
        assertEquals(IdleWatch.WATCHING, w.phase())
        assertEquals(IdleWatch.WATCHING, w.tick(h2 - 10 + h2 + 90_000, h2, true))
        assertEquals(IdleWatch.PROMPT, w.tick(h2 - 10 + h2 + 20_000 + h2, h2, true))
    }

    @Test fun stoppedStaysStoppedUntilWatchAgain() {
        val w = IdleWatch()
        w.tick(1, h2, true)
        w.tick(1 + h2, h2, true)
        val stopAt = 1 + h2 + 60_000
        assertEquals(IdleWatch.STOPPED, w.tick(stopAt, h2, true))
        w.onInput(stopAt + 5)
        assertEquals(IdleWatch.STOPPED, w.tick(stopAt + 10, h2, true))
        w.watchAgain(stopAt + 20)
        assertEquals(IdleWatch.WATCHING, w.tick(stopAt + 30, h2, true))
        assertEquals(IdleWatch.PROMPT, w.tick(stopAt + 20 + h2, h2, true))
    }

    @Test fun inactiveTimeDoesNotCount() {
        val w = IdleWatch()
        w.tick(1, h2, true)
        // Sem canal (ou a gravar nele) durante muito tempo: ao voltar, a contagem começa do zero.
        w.tick(1 + h2 - 1_000, h2, false)
        assertEquals(IdleWatch.WATCHING, w.tick(1 + h2 + 5_000, h2, true))
        assertEquals(IdleWatch.PROMPT, w.tick(1 + h2 - 1_000 + h2, h2, true))
    }

    @Test fun turningTheSettingOffClearsThePromptAndTheStoppedScreen() {
        val w = IdleWatch()
        w.tick(1, h2, true)
        assertEquals(IdleWatch.PROMPT, w.tick(1 + h2, h2, true))
        assertEquals(IdleWatch.WATCHING, w.tick(2 + h2, 0, true))
        w.tick(3 + h2, h2, true)
        w.tick(3 + 2 * h2, h2, true)
        assertEquals(IdleWatch.STOPPED, w.tick(3 + 2 * h2 + 60_000, h2, true))
        assertEquals(IdleWatch.WATCHING, w.tick(3 + 2 * h2 + 61_000, 0, true))
    }

    @Test fun clockGoingBackwardsRestarts() {
        val w = IdleWatch()
        w.tick(10 * h2, h2, true)
        assertEquals(IdleWatch.WATCHING, w.tick(5 * h2, h2, true))
        assertEquals(IdleWatch.PROMPT, w.tick(6 * h2, h2, true))
    }
}
