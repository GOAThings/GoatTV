package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsSniffTest {
    @Test
    fun playlistsAndOtherStreams() {
        assertTrue(HlsPlaylist.looksLikePlaylist("#EXTM3U\n#EXT-X-VERSION:3\n".encodeToByteArray()))
        assertTrue(HlsPlaylist.looksLikePlaylist(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + " \r\n#EXTM3U".encodeToByteArray()))
        // Uma rádio MP3 (ID3 + tramas), um TS e uma página de erro não são playlists.
        assertFalse(HlsPlaylist.looksLikePlaylist("ID3\u0004\u0000".encodeToByteArray()))
        assertFalse(HlsPlaylist.looksLikePlaylist(byteArrayOf(0x47, 0x40, 0x00, 0x10)))
        assertFalse(HlsPlaylist.looksLikePlaylist("<html>".encodeToByteArray()))
        assertFalse(HlsPlaylist.looksLikePlaylist(ByteArray(0)))
    }
}
