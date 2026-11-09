package cc.slokker.itv.ui.state

import cc.slokker.itv.data.PinAttempts
import cc.slokker.itv.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentalControlTest {
    private val prefs = MutableStateFlow(Prefs())
    private val written = mutableListOf<Pair<String, Any?>>()
    private var locksChanged = 0
    private var clock = 1_000_000L
    private var saved = 0 to 0L
    private lateinit var s: cc.slokker.itv.ui.i18n.Strings

    /** Grava como o ProfileStore: a definição muda logo nas preferências. */
    private fun setPref(key: String, value: Any?) {
        written += key to value
        prefs.value = when (key) {
            Prefs.Key.PIN_HASH -> prefs.value.copy(pinHash = value as String?)
            Prefs.Key.LOCKED_GROUPS -> prefs.value.copy(lockedGroups = (value as List<*>).map { it.toString() }.toSet())
            else -> prefs.value
        }
    }

    private val parental = ParentalControl(prefs, ::setPref, { PinAttempts(0, 0L) { f, at -> saved = f to at } }, { locksChanged++ }, { clock })

    @Before
    fun setUp() {
        s = TestStrings.install()
    }

    @Test
    fun `without a pin everything opens`() {
        assertTrue(parental.checkPin("0000"))
        assertFalse(parental.logoutNeedsPin())
        // Sem PIN não há grupos bloqueados a desbloquear.
        assertFalse(parental.unlockGroup("p1", "adult", "0000"))
        assertFalse(parental.settingsNeedPin())
    }

    @Test
    fun `the right pin unlocks a group for the session, a wrong one does not`() {
        parental.setPin("1234")
        assertEquals(1, locksChanged)
        assertFalse(parental.unlockGroup("p1", "adult", "9999"))
        assertTrue(parental.unlockGroup("p1", "adult", "1234"))
        assertEquals(setOf("p1:adult"), parental.unlocked.value)
        parental.reset()
        assertEquals(emptySet<String>(), parental.unlocked.value)
    }

    @Test
    fun `wrong pins make the next try wait, even the right one`() {
        parental.setPin("1234")
        assertFalse(parental.checkPin("0000"))
        assertEquals(s.wrongPin, parental.pinRejected(s.wrongPin))
        repeat(2) { assertFalse(parental.checkPin("0000")) }
        assertEquals(s.pinWait("30 s"), parental.pinRejected(s.wrongPin))
        assertFalse(parental.checkPin("1234"))
        clock += 30_000
        assertTrue(parental.checkPin("1234"))
        assertEquals(0 to 0L, saved)
    }

    @Test
    fun `removing the pin clears the locked groups and the session unlocks`() {
        parental.setPin("1234")
        parental.toggleGroupLock("p1", "adult")
        assertEquals(setOf("p1:adult"), prefs.value.lockedGroups)
        parental.unlockGroup("p1", "adult", "1234")
        parental.setPin(null)
        assertEquals(emptySet<String>(), prefs.value.lockedGroups)
        assertEquals(emptySet<String>(), parental.unlocked.value)
        assertEquals(3, locksChanged)
        assertEquals(listOf(Prefs.Key.PIN_HASH, Prefs.Key.LOCKED_GROUPS, Prefs.Key.PIN_HASH, Prefs.Key.LOCKED_GROUPS), written.map { it.first })
    }

    @Test
    fun `settings ask for the pin once per session`() {
        parental.setPin("1234")
        prefs.value = prefs.value.copy(settingsPin = true)
        assertTrue(parental.settingsNeedPin())
        assertFalse(parental.unlockSettings("0000"))
        assertTrue(parental.unlockSettings("1234"))
        assertFalse(parental.settingsNeedPin())
        parental.reset()
        assertTrue(parental.settingsNeedPin())
    }
}
