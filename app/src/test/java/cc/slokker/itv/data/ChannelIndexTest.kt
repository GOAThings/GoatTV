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
                Channel(0, "101", 1, "RTP 1", "https://x/rtp1.png", "1"),
                Channel(1, "u0a1b2c", 2, "Canal ç", "logo.png", "Outros"),
            ),
        )
        val index = ChannelIndex.of(content)!!
        assertEquals("ea9f8cc27edc2e37f03ba70062966b66b503be16", index.hash)
        val json = GZIPInputStream(ByteArrayInputStream(index.gzipBody(1761300000))).readBytes().toString(Charsets.UTF_8)
        val body = JsonParser.parseString(json).asJsonObject
        assertEquals(1761300000L, body.get("generated_at").asLong)
        assertEquals(2, body.getAsJsonArray("channels").size())
        assertFalse(body.getAsJsonArray("channels")[1].asJsonObject.has("logo"))
    }
}
