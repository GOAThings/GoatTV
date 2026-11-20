package cc.slokker.itv.playback

import cc.slokker.itv.data.m3u.M3uParser
import cc.slokker.itv.media.Codec
import cc.slokker.itv.media.TrackKind
import cc.slokker.itv.media.TsTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** As rádios: o que a lista diz só vale até o stream dizer as faixas; o stream é que manda. */
class RadioRulesTest {
    @Test
    fun m3uAttributes() {
        assertTrue(RadioRules.m3uHint(mapOf("radio" to "true")))
        assertTrue(RadioRules.m3uHint(mapOf("radio" to "1")))
        assertTrue(RadioRules.m3uHint(mapOf("tvg-type" to "Radio")))
        assertFalse(RadioRules.m3uHint(mapOf("radio" to "false")))
        assertFalse(RadioRules.m3uHint(emptyMap()))
    }

    @Test
    fun xtreamStreamType() {
        assertTrue(RadioRules.xtreamHint("radio_streams"))
        assertFalse(RadioRules.xtreamHint("live"))
        assertFalse(RadioRules.xtreamHint(null))
    }

    @Test
    fun namesAndGroups() {
        assertTrue(RadioRules.nameHint("Rádio Comercial", "Portugal"))
        assertTrue(RadioRules.nameHint("Antena 1", "PT | Rádios"))
        assertTrue(RadioRules.nameHint("Europa Plus", "Радио"))
        assertTrue(RadioRules.nameHint("RFM", "RADIO"))
        // Canais de televisão com "rádio" no nome.
        assertFalse(RadioRules.nameHint("Radio Bremen TV", "Deutschland"))
        assertFalse(RadioRules.nameHint("Radio Italia TV", "Radio"))
        // "radio" dentro de outra palavra não conta.
        assertFalse(RadioRules.nameHint("Radiofreccia Visual", "Italia"))
        assertFalse(RadioRules.nameHint("RTP 1", "Portugal"))
        assertFalse(RadioRules.nameHint("Kiss FM", null))
        assertTrue(RadioRules.hint(true, "Kiss FM", null))
    }

    @Test
    fun theStreamDecides() {
        // Antes das faixas, o que a lista diz.
        assertTrue(RadioRules.isRadio(hint = true, tracksKnown = false, hasVideo = false, hasAudio = false))
        assertFalse(RadioRules.isRadio(hint = false, tracksKnown = false, hasVideo = false, hasAudio = false))
        // Depois, o stream: só som é rádio, com imagem não (mesmo que a lista diga rádio).
        assertTrue(RadioRules.isRadio(hint = false, tracksKnown = true, hasVideo = false, hasAudio = true))
        assertFalse(RadioRules.isRadio(hint = true, tracksKnown = true, hasVideo = true, hasAudio = true))
        assertFalse(RadioRules.isRadio(hint = true, tracksKnown = true, hasVideo = false, hasAudio = false))
    }

    @Test
    fun tsTracks() {
        val audio = TsTrack(0x101, 0x0F, Codec.AAC_ADTS, TrackKind.AUDIO, "por")
        val video = TsTrack(0x100, 0x1B, Codec.H264, TrackKind.VIDEO, null)
        val teletext = TsTrack(0x102, 0x06, Codec.TELETEXT, TrackKind.SUBTITLE, null)
        assertTrue(RadioRules.audioOnlyTracks(listOf(audio, teletext)))
        assertFalse(RadioRules.audioOnlyTracks(listOf(video, audio)))
        assertFalse(RadioRules.audioOnlyTracks(emptyList()))
    }

    @Test
    fun m3uParserKeepsTheHint() {
        val m3u = listOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"rc\" radio=\"true\" group-title=\"Música\",Rádio Comercial",
            "http://radio.example.com/rc.mp3",
            "#EXTINF:-1 tvg-id=\"rtp1\" group-title=\"Portugal\",RTP 1",
            "http://tv.example.com/rtp1.ts",
        )
        val channels = M3uParser.parse(m3u.asSequence()).channels
        assertEquals(listOf(true, null), channels.map { it.radio })
    }
}
