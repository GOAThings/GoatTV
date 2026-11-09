package cc.slokker.itv.ui.mobile

import cc.slokker.itv.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNavTest {
    private val home = PhoneNav.start(Prefs.PhoneStartup.HOME)
    private val watching = home.played()

    @Test
    fun `startup opens home with nothing playing or the last channel big`() {
        assertEquals(PhoneNav(PhoneTab.Tv, home = true, stopped = true), home)
        assertFalse(home.video)
        val last = PhoneNav.start(Prefs.PhoneStartup.LAST)
        assertTrue(last.big)
    }

    @Test
    fun `playing from any list shows the channel big on the TV tab`() {
        val fromGuide = home.tabTapped(PhoneTab.Guide).played()
        assertEquals(PhoneNav(PhoneTab.Tv, home = false, stopped = false, homeGroup = null), fromGuide)
        assertEquals(fromGuide, home.openGroup("news").played())
        assertTrue(fromGuide.big)
    }

    @Test
    fun `back closes the topmost thing first`() {
        val group = watching.copy(home = true).openGroup("news").tabTapped(PhoneTab.Search)
        assertEquals(PhoneBack.CLOSE_OPTIONS, group.back(optionsOpen = true, fullscreen = true, epgOpen = true))
        assertEquals(PhoneBack.LEAVE_FULLSCREEN, group.back(optionsOpen = false, fullscreen = true, epgOpen = true))
        assertEquals(PhoneBack.CLOSE_EPG, group.back(optionsOpen = false, fullscreen = false, epgOpen = true))
        assertEquals(PhoneBack.TV_TAB, group.back(false, false, false))
        val onTv = group.after(PhoneBack.TV_TAB)
        assertEquals(PhoneBack.LEAVE_GROUP, onTv.back(false, false, false))
        val onHome = onTv.after(PhoneBack.LEAVE_GROUP)
        assertNull(onHome.back(false, false, false))
    }

    @Test
    fun `back from the channel goes home and the channel keeps playing in the mini player`() {
        assertEquals(PhoneBack.HOME, watching.back(false, false, false))
        val after = watching.after(PhoneBack.HOME)
        assertTrue(after.home)
        assertTrue(after.video)
        assertTrue(after.mini(fullscreen = false))
        // Folhas e rotação mudam-se no ecrã: o estado fica igual.
        assertEquals(watching, watching.after(PhoneBack.CLOSE_EPG))
    }

    @Test
    fun `tapping TV again goes home and closes the group`() {
        val inGroup = watching.copy(home = true).openGroup("news")
        assertEquals(inGroup.copy(home = true, homeGroup = null), inGroup.tabTapped(PhoneTab.Tv))
        assertEquals(PhoneTab.Favorites, inGroup.tabTapped(PhoneTab.Favorites).tab)
        assertEquals("news", inGroup.tabTapped(PhoneTab.Favorites).homeGroup)
    }

    @Test
    fun `settings close the video and a playlist switch there returns to TV`() {
        val settings = watching.tabTapped(PhoneTab.Menu)
        assertFalse(settings.video)
        assertFalse(settings.mini(fullscreen = false))
        assertEquals(PhoneTab.Tv, settings.channelChanged().tab)
        assertEquals(watching, watching.channelChanged())
    }

    @Test
    fun `fullscreen needs the channel big and rotated, or PiP`() {
        assertTrue(watching.fullscreen(pip = false, rotated = true))
        assertFalse(watching.fullscreen(pip = false, rotated = false))
        val homeWithMini = watching.copy(home = true)
        assertFalse(homeWithMini.fullscreen(pip = false, rotated = true))
        assertTrue(homeWithMini.fullscreen(pip = true, rotated = false))
        assertFalse(home.fullscreen(pip = true, rotated = true))
    }

    @Test
    fun `mini player opens big and its close stops the video`() {
        val mini = watching.tabTapped(PhoneTab.Favorites)
        assertTrue(mini.mini(fullscreen = false))
        assertEquals(watching, mini.openPlayer())
        assertFalse(mini.closePlayer().video)
    }

    @Test
    fun `a group from search opens on the TV home`() {
        assertEquals(PhoneNav(PhoneTab.Tv, home = true, stopped = false, homeGroup = "kids"), watching.tabTapped(PhoneTab.Search).groupFromSearch("kids"))
    }

    @Test
    fun `survives being saved and restored`() {
        val state = watching.copy(home = true).openGroup("news").tabTapped(PhoneTab.Guide)
        assertEquals(state, PhoneNav.fromList(state.toList()))
        assertEquals(home, PhoneNav.fromList(home.toList()))
    }
}
