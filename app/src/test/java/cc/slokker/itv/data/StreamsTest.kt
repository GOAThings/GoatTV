package cc.slokker.itv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamsTest {

    private val xtream = Playlist(
        id = "p1", type = PlaylistType.XTREAM, name = "Casa",
        server = "http://host:8080", username = "user", password = "pass",
    )
    private val m3u = Playlist(id = "p2", type = PlaylistType.M3U, name = "PT", url = "https://x/lista.m3u")

    private fun channel(url: String? = null, streamId: String? = null) =
        Channel(uid = 0, key = "k", num = 1, name = "C", logo = null, categoryId = "c", streamId = streamId, url = url)

    @Test
    fun `xtream tries ts then m3u8 by default`() {
        val c = streamCandidates(xtream, channel(streamId = "123"), null)
        assertEquals(
            listOf("http://host:8080/live/user/pass/123.ts", "http://host:8080/live/user/pass/123.m3u8"),
            c.map { it.url },
        )
        assertEquals(listOf(StreamFormat.TS, StreamFormat.HLS), c.map { it.format })
    }

    @Test
    fun `xtream respects preferred format`() {
        val c = streamCandidates(xtream, channel(streamId = "123"), StreamFormat.HLS)
        assertEquals(listOf(StreamFormat.HLS, StreamFormat.TS), c.map { it.format })
    }

    @Test
    fun `xtream skips formats the server does not allow`() {
        val c = streamCandidates(xtream, channel(streamId = "1"), StreamFormat.HLS, setOf("ts"))
        assertEquals(listOf(StreamFormat.TS), c.map { it.format })
    }

    @Test
    fun `xtream server without scheme`() {
        val c = streamCandidates(xtream.copy(server = "host:8080/"), channel(streamId = "9"), null)
        assertEquals("http://host:8080/live/user/pass/9.ts", c.first().url)
    }

    @Test
    fun `m3u swaps extension keeping query`() {
        val c = streamCandidates(m3u, channel(url = "http://h/live/1.m3u8?token=abc"), null)
        assertEquals(listOf("http://h/live/1.m3u8?token=abc", "http://h/live/1.ts?token=abc"), c.map { it.url })
        assertEquals(listOf(StreamFormat.HLS, StreamFormat.TS), c.map { it.format })
    }

    @Test
    fun `m3u without extension tries hls first unless ts preferred`() {
        val url = "http://h/play/42"
        assertEquals(listOf(StreamFormat.HLS, StreamFormat.TS), streamCandidates(m3u, channel(url = url), null).map { it.format })
        assertEquals(
            listOf(StreamFormat.TS, StreamFormat.HLS),
            streamCandidates(m3u, channel(url = url), StreamFormat.TS).map { it.format },
        )
    }

    @Test
    fun `content groups unknown categories into Outros`() {
        val content = PlaylistContent.build(
            playlist = xtream,
            categories = listOf(Category("1", "Notícias"), Category("2", "Vazia")),
            channels = listOf(
                channel(streamId = "a").copy(uid = 0, key = "a", categoryId = "1"),
                channel(streamId = "b").copy(uid = 1, key = "b", categoryId = "99"),
            ),
        )
        assertEquals(listOf("1", PlaylistContent.OTHERS), content.categories.map { it.id })
        assertEquals(PlaylistContent.OTHERS, content.byKey["b"]?.categoryId)
    }
}
