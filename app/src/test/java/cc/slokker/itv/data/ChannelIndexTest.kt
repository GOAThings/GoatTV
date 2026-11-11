package cc.slokker.itv.data

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChannelIndexTest {
    /** O mesmo hash da Apple TV (CoreTests.channelIndex) para a mesma lista. */
    @Test
    fun hashMatchesAppleTv() {
        val playlist = Playlist("p1", PlaylistType.M3U, "Demo", url = "https://example.com/list.m3u")
        val content = PlaylistContent(
            playlist,
            listOf(Category("1", "News"), Category("Outros", "Outros")),
            listOf(
                Channel(0, "101", 1, "RTP 1", "https://x/rtp1.png", "1", epgId = "rtp1.pt"),
                Channel(1, "u0a1b2c", 2, "Canal ç", "logo.png", "Outros", epgId = " "),
            ),
        )
        val index = ChannelIndex.of(content)!!
        assertEquals("e208f7063796947eba103d5ae4d842c8ab8397b7", index.hash)
        val json = GZIPInputStream(ByteArrayInputStream(index.gzipBody(1761300000))).readBytes().toString(Charsets.UTF_8)
        val body = JsonParser.parseString(json).asJsonObject
        assertEquals(1761300000L, body.get("generated_at").asLong)
        assertEquals(2, body.getAsJsonArray("channels").size())
        assertFalse(body.getAsJsonArray("channels")[1].asJsonObject.has("logo"))
        // O id do guia vai quando existe (e não vai vazio).
        assertEquals("rtp1.pt", body.getAsJsonArray("channels")[0].asJsonObject.get("epg").asString)
        assertFalse(body.getAsJsonArray("channels")[1].asJsonObject.has("epg"))
    }
}
