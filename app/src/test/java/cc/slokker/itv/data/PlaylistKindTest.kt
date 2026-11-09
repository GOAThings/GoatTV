package cc.slokker.itv.data

import cc.slokker.itv.data.api.PlaylistDto
import cc.slokker.itv.data.api.accountBody
import cc.slokker.itv.data.api.toDto
import cc.slokker.itv.data.api.toPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistKindTest {

    private val xtream = Playlist("p1", PlaylistType.XTREAM, "Casa", server = "http://x.example.com:8080", username = "u", password = "p", epgUrl = "http://x.example.com/epg.xml")
    private val m3u = Playlist("p2", PlaylistType.M3U, "Lista", url = "http://m.example.com/list.m3u")
    private val stalker = Playlist("p3", PlaylistType.STALKER, "Portal", server = "http://portal.example.com/c", username = "00:1A:79:12:34:56", serial = "SN", deviceId = "D1", deviceId2 = "D2", signature = "S")

    @Test
    fun `every type has its kind and the wire names stay the same`() {
        assertEquals(PlaylistType.entries.toList(), PlaylistKind.ALL.map { it.type })
        PlaylistType.entries.forEach { assertEquals(it, it.kind.type) }
        // O site e a Apple TV leem estes nomes (e o LocalStore guarda-os).
        assertEquals(listOf("xtream", "m3u", "stalker"), PlaylistKind.ALL.map { it.wireName })
        assertEquals(PlaylistKind.XTREAM, PlaylistKind.fromWire("Xtream"))
        assertNull(PlaylistKind.fromWire("vod"))
        assertNull(PlaylistKind.fromWire(null))
    }

    @Test
    fun `capabilities are where the app used to check the type`() {
        // Cada canal diz se tem e quantos dias (Channel.archiveDays).
        assertEquals(listOf(PlaylistType.XTREAM, PlaylistType.M3U), PlaylistKind.ALL.filter { it.supportsCatchup }.map { it.type })
        assertEquals(listOf(PlaylistType.XTREAM), PlaylistKind.ALL.filter { it.knowsConnectionLimit }.map { it.type })
        assertEquals(listOf(PlaylistType.XTREAM), PlaylistKind.ALL.filter { it.remembersFormat }.map { it.type })
        assertEquals(listOf(PlaylistType.XTREAM, PlaylistType.STALKER), PlaylistKind.ALL.filter { it.shortEpg }.map { it.type })
        assertEquals(listOf(PlaylistType.STALKER), PlaylistKind.ALL.filter { it.linkAtPlayTime }.map { it.type })
        assertEquals(listOf(PlaylistType.STALKER), PlaylistKind.ALL.filter { it.singleConnection }.map { it.type })
        assertEquals(listOf(PlaylistType.XTREAM, PlaylistType.M3U), PlaylistKind.ALL.filter { it.supportsAccount }.map { it.type })
    }

    @Test
    fun `a playlist survives the trip to json and back`() {
        listOf(xtream, m3u, stalker).forEach { assertEquals(it, it.toDto().toPlaylist()) }
    }

    @Test
    fun `account json with empty fields reads as missing, and a nameless playlist gets the type`() {
        val dto = PlaylistDto("p9", "M3U", " ", "", "", "", "http://m/list.m3u", "")
        val p = dto.toPlaylist()!!
        assertEquals(PlaylistType.M3U, p.type)
        assertEquals("M3U", p.name)
        assertEquals(listOf(null, null, null, null), listOf(p.server, p.username, p.password, p.epgUrl))
        assertNull(dto.copy(type = "vod").toPlaylist())
        assertNull(dto.copy(id = null).toPlaylist())
    }

    @Test
    fun `account body has the seven fields, the other type's empty`() {
        val body = xtream.accountBody()
        assertEquals(setOf("type", "name", "server", "username", "password", "url", "epg_url"), body.keySet())
        assertEquals("xtream", body.get("type").asString)
        assertEquals("", body.get("url").asString)
        val m = m3u.accountBody()
        assertEquals("m3u", m.get("type").asString)
        assertEquals(listOf("", "", ""), listOf("server", "username", "password").map { m.get(it).asString })
        assertEquals("", m.get("epg_url").asString)
    }

    @Test
    fun `two playlists share connections with the same rule as same source`() {
        // A barra no fim e os espaços no utilizador já não fazem parecer duas contas (o segundo canal abria uma 2.ª ligação).
        assertTrue(xtream.sharesConnectionsWith(xtream.copy(id = "o", server = "http://x.example.com:8080/", username = "u ")))
        assertFalse(xtream.sharesConnectionsWith(xtream.copy(id = "o", username = "v")))
        // Nas M3U não se sabe: só a própria lista.
        assertTrue(m3u.sharesConnectionsWith(m3u))
        assertFalse(m3u.sharesConnectionsWith(m3u.copy(id = "o")))
    }

    /** O relatório de erros tira estes valores do registo: um campo novo da lista sem entrar aqui vazava (30b926b). */
    @Test
    fun `every playlist field but id and name is a secret`() {
        val fields = Playlist::class.java.declaredFields.filter { it.type == String::class.java }.map { it.name } - setOf("id", "name")
        val marked = Playlist::class.java.getDeclaredConstructor(
            String::class.java, PlaylistType::class.java, String::class.java,
            *Array(fields.size) { String::class.java },
        ).newInstance("id", PlaylistType.XTREAM, "name", *fields.map { "v:$it" }.toTypedArray())
        assertEquals(fields.map { "v:$it" }.sorted(), marked.secrets().sorted())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a stalker playlist never goes to the account as m3u`() {
        stalker.accountBody()
    }

    @Test
    fun `same source follows each kind's rule`() {
        assertTrue(xtream.sameSourceAs(xtream.copy(id = "o", server = " HTTP://X.example.com:8080/ ", username = " u ")))
        assertFalse(xtream.sameSourceAs(xtream.copy(username = "v")))
        assertTrue(m3u.sameSourceAs(m3u.copy(id = "o", url = " http://m.example.com/list.m3u")))
        assertFalse(m3u.sameSourceAs(m3u.copy(url = "http://m.example.com/other.m3u")))
        assertTrue(stalker.sameSourceAs(stalker.copy(id = "o", server = "HTTP://portal.example.com/c")))
        // Tipos diferentes nunca são a mesma lista, mesmo com o mesmo servidor.
        assertFalse(xtream.sameSourceAs(stalker.copy(server = xtream.server, username = xtream.username)))
    }
}
