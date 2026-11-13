package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

class WebVttTest {
    private val segment = """
        WEBVTT
        X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000

        1
        00:00:10.000 --> 00:00:12.500 align:center
        <i>Olá</i> &amp; bem-vindos

        00:12.500 --> 00:15.000
        Segunda
        linha
    """.trimIndent()

    @Test
    fun cuesWithTheTimestampMap() {
        val f = WebVtt.parse(segment)
        assertEquals(900_000L, f.mpegts)
        assertEquals(listOf("Olá & bem-vindos", "Segunda\nlinha"), f.cues.map { it.text })
        assertEquals(listOf(10_000L to 12_500L, 12_500L to 15_000L), f.cues.map { it.startMs to it.endMs })
    }

    @Test
    fun theDemuxerGivesOneSampleACueOnTheTsClockOnce() {
        val got = ArrayList<TsSample>()
        val d = WebVttDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) = assertEquals(listOf(Codec.WEBVTT to "por"), tracks.map { it.codec to it.language })
            override fun onSample(track: TsTrack, sample: TsSample) {
                got += sample
            }
        }, "por")
        d.feed(segment.encodeToByteArray())
        // A fala que passa para o segmento seguinte vem repetida: sai uma vez.
        d.feed(segment.encodeToByteArray())
        assertEquals(listOf(900_000L + 900_000, 900_000L + 1_125_000), got.map { it.pts })
        assertEquals(2_500L, WebVtt.durationMs(got[0].data))
        assertEquals("Olá & bem-vindos", WebVtt.text(got[0].data))
    }

    @Test
    fun subtitlePlaylistsInTheMaster() {
        val p = HlsPlaylist.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="Português",LANGUAGE="pt",DEFAULT=YES,URI="subs/pt.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,SUBTITLES="s"
            v/720.m3u8
            """.trimIndent(),
            "http://h/master.m3u8",
        )
        val v = p.variants.single()
        assertEquals(listOf("http://h/subs/pt.m3u8", "http://h/subs/en.m3u8"), v.subtitles.map { it.uri })
        assertEquals(listOf("por", "eng"), v.subtitles.map { it.code })
        assertEquals(emptyList(), v.audio)
    }
}
