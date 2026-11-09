package cc.slokker.itv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogTest {
    private val playlist = Playlist("p1", PlaylistType.M3U, "Lista", url = "https://example.com/list.m3u")
    private val channels = listOf(
        Channel(0, "n1", 1, "RTP 1", null, "news"),
        Channel(1, "n2", 2, "CNN", null, "news"),
        Channel(2, "s1", 3, "Sport TV", null, "sport"),
        Channel(3, "a1", 4, "Privado", null, "adult"),
        Channel(4, "k1", 5, "Panda", null, "kids"),
        Channel(5, "k2", 6, "Baby", null, "kids"),
        Channel(6, "x1", 7, "Sem grupo", null, "nenhum"),
    )
    private val content = PlaylistContent.build(
        playlist,
        listOf(Category("news", "Notícias"), Category("sport", "Desporto"), Category("adult", "Adultos"), Category("kids", "Infantis")),
        channels,
    )
    private val names = GroupNames("★ Favoritos", "Recentes", "Todos", "Outros (traduzido)")
    private val prefs = Prefs(
        hiddenGroups = setOf("p1:sport"),
        hiddenChannels = setOf("p1:k2"),
        pinHash = "0".repeat(64),
        lockedGroups = setOf("p1:adult"),
    )

    private fun catalog(
        prefs: Prefs = this.prefs,
        favorites: Map<String, List<String>> = mapOf("p1" to listOf("k1", "a1", "n2")),
        recents: Map<String, List<String>> = mapOf("p1" to listOf("x1", "s1", "n1")),
        unlocked: Set<String> = emptySet(),
    ) = ChannelCatalog(content, prefs, favorites, recents, unlocked)

    private fun List<Channel>.keys() = map { it.key }

    @Test
    fun `all, favourites and recents leave out locked and hidden channels`() {
        val c = catalog()
        assertEquals(listOf("n1", "n2", "k1", "x1"), c.channelsOf(SpecialCategory.ALL).keys())
        assertEquals(listOf("k1", "n2"), c.channelsOf(SpecialCategory.FAVORITES).keys())
        // Pela ordem em que foram vistos; o do grupo escondido não aparece.
        assertEquals(listOf("x1", "n1"), c.channelsOf(SpecialCategory.RECENT).keys())
    }

    @Test
    fun `a group unlocked with the pin shows its channels for this session`() {
        val c = catalog(unlocked = setOf("p1:adult"))
        assertFalse(c.isGroupLocked("adult"))
        assertTrue(c.isGroupLockedSetting("adult"))
        assertEquals(listOf("k1", "a1", "n2"), c.channelsOf(SpecialCategory.FAVORITES).keys())
    }

    @Test
    fun `what lives outside the session keeps locked groups out, unlocked or not, pin or not`() {
        val c = catalog(unlocked = setOf("p1:adult"))
        assertTrue(c.isGroupLocked("adult", Audience.PERSISTENT))
        assertEquals(listOf("k1", "n2"), c.channelsOf(SpecialCategory.FAVORITES, Audience.PERSISTENT).keys())
        assertFalse(c.isVisible(channels[3], Audience.PERSISTENT))
        assertTrue(c.isVisible(channels[3]))
        val noPin = catalog(prefs = prefs.copy(pinHash = null))
        assertFalse(noPin.isVisible(channels[3], Audience.PERSISTENT))
        // O telemóvel usa a mesma regra.
        channels.forEach { ch ->
            assertEquals(ch.key, PhoneChannelList.allowed(prefs, "p1", ch), c.isVisible(ch, Audience.PERSISTENT))
        }
    }

    @Test
    fun `without a pin nothing is locked`() {
        val c = catalog(prefs = prefs.copy(pinHash = null))
        assertFalse(c.isGroupLocked("adult"))
        assertTrue(c.channelsOf(SpecialCategory.ALL).keys().contains("a1"))
    }

    @Test
    fun `sort by name, except recents and a group with its own order`() {
        val p = prefs.copy(sort = Prefs.Sort.NAME, channelOrder = mapOf("p1:kids" to listOf("k2", "k1")))
        val c = catalog(prefs = p.copy(hiddenChannels = emptySet()))
        assertEquals(listOf("k2", "n2", "k1", "n1"), c.channelsOf(SpecialCategory.ALL).take(4).keys())
        assertEquals(listOf("x1", "n1"), c.channelsOf(SpecialCategory.RECENT).keys())
        assertEquals(listOf("k2", "k1"), c.channelsOf("kids").keys())
        assertEquals(listOf("n2", "n1"), c.channelsOf("news").keys())
        assertTrue(c.hasChannelOrder("kids"))
        assertFalse(c.hasChannelOrder("news"))
    }

    @Test
    fun `chosen channel order keeps new channels at the end`() {
        val c = catalog(prefs = prefs.copy(channelOrder = mapOf("p1:news" to listOf("n2", "gone"))))
        assertEquals(listOf("n2", "n1"), c.orderedChannels("news", includeHidden = false).keys())
        assertEquals(listOf("k1"), c.orderedChannels("kids", includeHidden = false).keys())
        assertEquals(listOf("k1", "k2"), c.orderedChannels("kids", includeHidden = true).keys())
    }

    @Test
    fun `groups follow the chosen order and the guide skips hidden and locked ones`() {
        val c = catalog(prefs = prefs.copy(groupOrder = mapOf("p1" to listOf("kids", "news"))))
        assertEquals(listOf("kids", "news", "adult", PlaylistContent.OTHERS), c.orderedGroups(includeHidden = false).map { it.id })
        assertEquals(listOf("kids", "news", "sport", "adult", PlaylistContent.OTHERS), c.orderedGroups(includeHidden = true).map { it.id })
        val guide = c.guideGroups(names)
        assertEquals(
            listOf(SpecialCategory.FAVORITES, SpecialCategory.RECENT, SpecialCategory.ALL, "kids", "news", PlaylistContent.OTHERS),
            guide.map { it.id },
        )
        assertEquals("Outros (traduzido)", guide.last().name)
        assertEquals("★ Favoritos", guide.first().name)
    }

    @Test
    fun `the guide hides favourites and recents when switched off or empty`() {
        val off = catalog(prefs = prefs.copy(showFavoritesGroup = false, showRecentGroup = false))
        assertEquals(SpecialCategory.ALL, off.guideGroups(names).first().id)
        val empty = catalog(favorites = emptyMap(), recents = mapOf("p1" to listOf("s1")))
        assertEquals(SpecialCategory.ALL, empty.guideGroups(names).first().id)
    }

    @Test
    fun `the group list (second channel) follows the same settings as the apple tv`() {
        val c = catalog(prefs = prefs.copy(groupOrder = mapOf("p1" to listOf("kids"))))
        assertEquals(
            listOf(SpecialCategory.FAVORITES, SpecialCategory.RECENT, SpecialCategory.ALL, "kids", "news", PlaylistContent.OTHERS),
            c.groups(names).map { it.id },
        )
        assertEquals(listOf("kids", "news", "adult", PlaylistContent.OTHERS), c.groups(names, includeLocked = true).drop(3).map { it.id })
        // "★ Favoritos" fica mesmo vazio (fora do guia); "Todos" só se ligado; "Recentes" só com canais.
        val empty = catalog(prefs = prefs.copy(showAllGroup = false), favorites = emptyMap(), recents = emptyMap())
        assertEquals(listOf(SpecialCategory.FAVORITES, "news", "kids", PlaylistContent.OTHERS), empty.groups(names).map { it.id })
        assertEquals(listOf(SpecialCategory.ALL, "news", "kids", PlaylistContent.OTHERS), empty.guideGroups(names).map { it.id })
    }

    @Test
    fun `number keys skip locked and hidden channels and hidden groups`() {
        val c = catalog()
        assertEquals("n1", c.channelByNumber(1)?.key)
        assertNull(c.channelByNumber(3)) // grupo escondido
        assertNull(c.channelByNumber(4)) // grupo bloqueado
        assertNull(c.channelByNumber(6)) // canal escondido
        assertNull(c.channelByNumber(99))
    }

    @Test
    fun `nothing open means nothing visible`() {
        val c = ChannelCatalog(null, prefs, emptyMap(), emptyMap(), emptySet())
        assertEquals(emptyList<Channel>(), c.channelsOf(SpecialCategory.ALL))
        assertEquals(emptyList<Category>(), c.orderedGroups(includeHidden = true))
        assertFalse(c.isGroupLocked("adult"))
        assertFalse(c.isFavorite(channels[0]))
        assertNull(c.channelOrderKey("news"))
    }
}
