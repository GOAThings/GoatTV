package cc.slokker.itv.ui.state

import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.ChannelCatalog
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.SpecialCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {
    private val playlist = Playlist("p1", PlaylistType.M3U, "Lista", url = "https://example.com/list.m3u")
    private val channels = listOf(
        Channel(0, "n1", 1, "RTP 1", null, "news"),
        Channel(1, "n2", 2, "CNN", null, "news"),
        Channel(2, "a1", 3, "Privado", null, "adult"),
        Channel(3, "n3", 4, "SIC", null, "news"),
        Channel(4, "k1", 5, "Panda", null, "kids"),
    )
    private var content = PlaylistContent.build(
        playlist,
        listOf(Category("news", "Notícias"), Category("adult", "Adultos"), Category("kids", "Infantis")),
        channels,
    )
    private var prefs = Prefs(pinHash = "0".repeat(64), lockedGroups = setOf("p1:adult"))
    private var recents = mapOf("p1" to listOf("k1", "a1", "n2"))
    private var unlocked = emptySet<String>()
    private var clock = 1_000_000L
    private val tuned = mutableListOf<String>()

    private fun catalogFor(c: PlaylistContent?) = ChannelCatalog(c, prefs, emptyMap(), recents, unlocked)
    private val state = PlaybackState({ catalogFor(content) }, { catalogFor(it) }, { tuned += it.key }, { clock })

    private fun ch(key: String) = channels.first { it.key == key }

    @Test
    fun `zap goes round the group and skips what is locked now`() {
        state.playIn(ch("n1"), SpecialCategory.ALL)
        state.zap(1)
        assertEquals("n2", state.playing.value?.key)
        // O bloqueado está fora de "Todos"; um grupo desbloqueado nesta sessão volta a contar.
        state.zap(1)
        assertEquals("n3", state.playing.value?.key)
        state.zap(1)
        state.zap(1)
        assertEquals("n1", state.playing.value?.key)
        state.zap(-1)
        assertEquals("k1", state.playing.value?.key)
        assertEquals(listOf("n1", "n2", "n3", "k1", "n1", "k1"), tuned)
    }

    @Test
    fun `a group locked after the zap list was made is skipped`() {
        unlocked = setOf("p1:adult")
        state.playIn(ch("n2"), SpecialCategory.ALL)
        unlocked = emptySet()
        state.zap(1)
        assertEquals("n3", state.playing.value?.key)
    }

    @Test
    fun `previous is the channel watched a few seconds, or any explicit jump`() {
        state.playIn(ch("n1"), "news")
        clock += 1_000
        state.zap(1) // n2, passou-se só 1 s em n1
        clock += 1_000
        state.zap(1) // n3
        assertNull(state.previousChannel()?.takeIf { it.key == "n2" })
        clock += PlaybackState.WATCHED_MS
        state.zap(-1) // n2: ficou-se em n3 o bastante
        assertEquals("n3", state.previousChannel()?.key)
        assertTrue(state.watchedCurrent().not())
        clock += PlaybackState.WATCHED_MS
        assertTrue(state.watchedCurrent())
        // Um salto de propósito conta logo.
        state.playIn(ch("k1"), "kids")
        assertEquals("n2", state.previousChannel()?.key)
    }

    @Test
    fun `without a previous channel the recents give one, never a locked one`() {
        state.playIn(ch("k1"), "kids")
        // Recentes: k1 (o atual), a1 (bloqueado), n2.
        assertEquals("n2", state.previousChannel()?.key)
    }

    @Test
    fun `group for a channel keeps the current group when it has it`() {
        state.playIn(ch("n1"), "news")
        assertEquals("news", state.groupFor(ch("n3")))
        assertEquals("kids", state.groupFor(ch("k1")))
    }

    @Test
    fun `a refreshed playlist keeps the channel by key and drops a group that is gone`() {
        state.playIn(ch("k1"), "kids")
        content = PlaylistContent.build(playlist, listOf(Category("news", "Notícias")), channels.filter { it.categoryId == "news" } + ch("k1").copy(uid = 9, categoryId = "news"))
        state.onContentRefreshed(content)
        assertEquals(9, state.playing.value?.uid)
        assertEquals(SpecialCategory.ALL, state.selectedCategory.value)
        state.zap(1)
        assertEquals("n1", state.playing.value?.key)
    }

    @Test
    fun `catch-up seeks inside the programme and goes live past the edge`() {
        val entry = EpgEntry("Telejornal", clock - 3_600_000L, clock - 600_000L)
        state.playIn(ch("n1"), "news")
        state.startCatchup(ch("n1"), entry, -5)
        assertEquals(0L, state.catchup.value?.positionMs)
        state.seekCatchup(60_000)
        assertEquals(60_000L, state.catchup.value?.positionMs)
        state.seekCatchup(entry.endMs - entry.startMs)
        assertNull(state.catchup.value)
        state.startCatchup(ch("n1"), entry, 0)
        state.zap(1)
        assertNull(state.catchup.value)
    }

    /** Uma gravação do disco no lugar do catch-up: o canal a dar fica, e só o fim dela volta ao direto. */
    @Test
    fun `a recording plays over the live channel and only its end goes live`() {
        state.playIn(ch("n1"), "news")
        val rec = cc.slokker.itv.data.Catchup(Channel(-1, "rec:x", 0, "X", null, ""), "Gravado", clock - 60_000L, clock, 0L, recording = "file:///r.ts")
        state.startRecording(rec)
        assertEquals("n1", state.playing.value?.key)
        assertEquals("file:///r.ts", state.catchup.value?.recording)
        // Mesmo perto de "agora" (o catch-up do servidor voltava ao direto): numa gravação fica.
        state.seekCatchup(59_000)
        assertEquals(59_000L, state.catchup.value?.positionMs)
        state.seekCatchup(60_000)
        assertNull(state.catchup.value)
    }

    @Test
    fun `second view zaps its own list and skips locked groups`() {
        val other = PlaylistContent.build(Playlist("p2", PlaylistType.M3U, "Outra", url = "u"), listOf(Category("g", "G")), listOf(
            Channel(0, "o1", 1, "Um", null, "g"),
            Channel(1, "o2", 2, "Dois", null, "g"),
        ))
        state.openSecond(other, other.channels[0], "g")
        state.zapSecond(1)
        assertEquals("o2", state.second.value?.channel?.key)
        prefs = prefs.copy(lockedGroups = prefs.lockedGroups + "p2:g")
        state.zapSecond(1)
        assertEquals("o2", state.second.value?.channel?.key)
        state.closeSecond()
        assertNull(state.second.value)
    }

    @Test
    fun `the panel request is consumed once and reset clears everything`() {
        state.openPanelOnStart(true)
        assertTrue(state.consumeOpenPanelOnStart())
        assertFalse(state.consumeOpenPanelOnStart())
        state.playIn(ch("n1"), "news")
        state.reset()
        assertNull(state.playing.value)
        assertNull(state.previousChannel()?.takeIf { it.key == "n1" })
        assertEquals(SpecialCategory.ALL, state.selectedCategory.value)
    }
}
