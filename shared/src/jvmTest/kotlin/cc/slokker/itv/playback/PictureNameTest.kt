package cc.slokker.itv.playback

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class PictureNameTest {
    @Test
    fun `the name has the channel and the local time`() {
        // 2026-11-20 21:34:05 UTC
        val at = 1_795_210_445_000L
        assertEquals("RTP 1 2026-11-20 21.34.05", PictureName.of("RTP 1", at, TimeZone.UTC))
        assertEquals("RTP 1 2026-11-20 22.34.05", PictureName.of("RTP 1", at, TimeZone.of("Europe/Paris")))
    }

    @Test
    fun `what a file name cannot have is taken out`() {
        assertEquals("PT SPORT TV 1 FHD", PictureName.clean("PT: SPORT.TV 1 | FHD"))
        assertEquals("a b", PictureName.clean("  a/\\b  "))
        assertEquals("Canal+ Séries (HD)", PictureName.clean("Canal+ Séries (HD)"))
        assertEquals("日本テレビ", PictureName.clean("日本テレビ"))
        assertEquals("GoatTV", PictureName.clean("***"))
        assertEquals(PictureName.MAX_CHANNEL, PictureName.clean("x".repeat(200)).length)
    }
}
