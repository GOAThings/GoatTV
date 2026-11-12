package cc.slokker.itv.data

import cc.slokker.itv.data.m3u.M3uCatchup
import cc.slokker.itv.data.m3u.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.datetime.TimeZone

/**
 * O catch-up das listas M3U: os atributos (por canal e no `#EXTM3U`) e o URL de cada tipo, com exemplos como os do
 * README do Kodi IPTV Simple e de listas reais (os endereços são inventados).
 */
class M3uCatchupTest {

    // 8 nov. 2026, 20:30:15 UTC; o programa dura 1 h e "agora" é 3 h depois do início.
    private val start = 1_794_169_815L
    private val end = start + 3_600
    private val now = start + 10_800
    private val utc = TimeZone.UTC
    private val paris = TimeZone.of("Europe/Paris")

    private fun parse(text: String) = M3uParser.parse(text.trimIndent().lineSequence()).channels

    private fun channel(url: String, type: String?, source: String? = null) =
        Channel(uid = 0, key = "k", num = 1, name = "C", logo = null, categoryId = "g", url = url, archiveDays = 3, catchupType = type, catchupSource = source)

    private fun urls(c: Channel, zone: TimeZone = utc) = M3uCatchup.candidates(c, start, end, now, zone).map { it.url }.distinct()

    // ---- Atributos ----

    @Test
    fun `reads type, source and days per channel`() {
        val c = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" catchup="default" catchup-days="7" catchup-source="http://cu.example.com/a?start={utc}&end={utcend}",A
            http://live.example.com/a.m3u8
            #EXTINF:-1 tvg-id="b" catchup-type="flussonic" catchup-days="2",B
            http://fs.example.com/b/index.m3u8?token=t
            #EXTINF:-1 tvg-id="c",C sem catch-up
            http://live.example.com/c.ts
            """,
        )
        assertEquals(listOf(7, 2, 0), c.map { it.archiveDays })
        assertEquals(listOf("default", "flussonic", null), c.map { it.catchupType })
        assertEquals("http://cu.example.com/a?start={utc}&end={utcend}", c[0].catchupSource)
        assertNull(c[1].catchupSource)
    }

    @Test
    fun `the EXTM3U line sets every channel, and a channel overrides it`() {
        val c = parse(
            """
            #EXTM3U url-tvg="http://epg.example.com/e.xml" catchup="append" catchup-days="5" catchup-source="?utc={utc}&lutc={lutc}"
            #EXTINF:-1 tvg-id="a",A
            http://live.example.com/a.m3u8
            #EXTINF:-1 tvg-id="b" catchup="shift" catchup-days="1",B
            http://live.example.com/b.m3u8
            """,
        )
        assertEquals(listOf("append", "shift"), c.map { it.catchupType })
        assertEquals(listOf(5, 1), c.map { it.archiveDays })
        assertEquals("?utc={utc}&lutc={lutc}", c[0].catchupSource)
        // O shift não usa modelo.
        assertNull(c[1].catchupSource)
    }

    @Test
    fun `days alone are shift, or xc for an xtream panel's address`() {
        val c = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" timeshift="3",A
            http://ott.example.com/ch/a/video.m3u8?token=x
            #EXTINF:-1 tvg-id="b" tvg-rec="4",B
            http://panel.example.com:8080/live/user/pass/1477.ts
            """,
        )
        assertEquals(listOf("shift", "xc"), c.map { it.catchupType })
        assertEquals(listOf(3, 4), c.map { it.archiveDays })
    }

    @Test
    fun `type aliases, the default days and what has no catch-up`() {
        fun spec(attrs: Map<String, String>, url: String = "http://h.example.com/live/u/p/1.ts") = M3uCatchup.spec(attrs, emptyMap(), url)
        assertEquals(M3uCatchup.FLUSSONIC_TS, spec(mapOf("catchup" to "fs"))?.type)
        assertEquals(M3uCatchup.FLUSSONIC_TS, spec(mapOf("catchup" to "flussonic-ts"))?.type)
        assertEquals(M3uCatchup.FLUSSONIC, spec(mapOf("catchup" to "Flussonic-HLS"))?.type)
        assertEquals(M3uCatchup.XC, spec(mapOf("catchup" to "xtream"))?.type)
        // Sem dias, os do Kodi.
        assertEquals(M3uCatchup.DEFAULT_DAYS, spec(mapOf("catchup" to "shift"))?.days)
        // Só o modelo: é o default.
        assertEquals(M3uCatchup.DEFAULT, spec(mapOf("catchup-source" to "http://x.example.com/{utc}.ts"))?.type)
        // Tipos que não se fazem, default sem modelo, um marcador desconhecido, xc num URL que não é de painel.
        assertNull(spec(mapOf("catchup" to "vod", "catchup-source" to "{catchup-id}")))
        assertNull(spec(mapOf("catchup" to "default")))
        assertNull(spec(mapOf("catchup" to "append", "catchup-source" to "?id={catchup-id}")))
        assertNull(spec(mapOf("catchup" to "0")))
        assertNull(spec(mapOf("catchup" to "xc"), url = "http://h.example.com/a.m3u8"))
        assertNull(spec(emptyMap()))
    }

    // ---- URLs ----

    @Test
    fun `default fills the template`() {
        val c = channel(
            "http://live.example.com/325/mono.m3u8?token=x", "default",
            "http://cu.example.com/325/mono.m3u8?token=x&utc={utc}&lutc={lutc}&end=\${end}&d={duration}&min={duration:60}&ago={offset:60}",
        )
        assertEquals(
            listOf("http://cu.example.com/325/mono.m3u8?token=x&utc=$start&lutc=$now&end=$end&d=3600&min=60&ago=180"),
            urls(c),
        )
    }

    @Test
    fun `date parts and formatted times use the device zone`() {
        val c = channel("http://live.example.com/a.ts", "default", "http://cu.example.com/{Y}/{m}/{d}/{H}-{M}-{S}/a.ts?s=\${start:YmdHMS}&e={utcend:Y-m-d:H-M}")
        assertEquals(listOf("http://cu.example.com/2026/11/08/20-30-15/a.ts?s=20261108203015&e=2026-11-08:21-30"), urls(c, utc))
        assertEquals(listOf("http://cu.example.com/2026/11/08/21-30-15/a.ts?s=20261108213015&e=2026-11-08:22-30"), urls(c, paris))
    }

    @Test
    fun `append and shift go after the channel's address`() {
        assertEquals(
            listOf("http://live.example.com/325/mono.m3u8?token=x&utc=$start&lutc=$now"),
            urls(channel("http://live.example.com/325/mono.m3u8?token=x", "append", "&utc={utc}&lutc={lutc}")),
        )
        assertEquals(listOf("http://live.example.com/a.ts?utc=$start&lutc=$now"), urls(channel("http://live.example.com/a.ts", "shift")))
        assertEquals(listOf("http://live.example.com/a.m3u8?t=1&utc=$start&lutc=$now"), urls(channel("http://live.example.com/a.m3u8?t=1", "shift")))
    }

    @Test
    fun `flussonic builds the archive from the channel's address`() {
        assertEquals(listOf("http://ch01.example.com/151/timeshift_abs-$start.ts?token=my_token"), urls(channel("http://ch01.example.com/151/mpegts?token=my_token", "flussonic")))
        assertEquals(listOf("http://fs.example.com:8888/325/index-$start-3600.m3u8?token=secret"), urls(channel("http://fs.example.com:8888/325/index.m3u8?token=secret", "flussonic")))
        assertEquals(listOf("http://fs.example.com:8888/325/mono-$start-3600.m3u8"), urls(channel("http://fs.example.com:8888/325/mono.m3u8", "flussonic")))
        // Outro URL: o último bocado troca-se (TS com fs / flussonic-ts).
        assertEquals(listOf("http://fs.example.com/325/timeshift_abs-$start.ts?token=t"), urls(channel("http://fs.example.com/325/live?token=t", "flussonic-ts")))
        assertEquals(listOf("http://fs.example.com/325/timeshift_abs-$start.ts"), urls(channel("http://fs.example.com/325", "flussonic-ts")))
    }

    @Test
    fun `xc asks the panel's timeshift by the minute in the device zone`() {
        val c = channel("http://panel.example.com:8080/live/my@account.xc/my_password/1477.ts", "xc")
        assertEquals(
            listOf(
                "http://panel.example.com:8080/timeshift/my@account.xc/my_password/60/2026-11-08:21-30/1477.ts",
                "http://panel.example.com:8080/streaming/timeshift.php?username=my@account.xc&password=my_password&stream=1477&start=2026-11-08:21-30&duration=60",
            ),
            urls(c, paris),
        )
        // Sem /live/ e sem extensão (como nas listas m3u_plus), e em HLS.
        assertEquals(
            "http://panel.example.com/timeshift/u/p/60/2026-11-08:20-30/9.ts",
            urls(channel("http://panel.example.com/u/p/9", "xc")).first(),
        )
        assertEquals(
            "http://panel.example.com/timeshift/u/p/60/2026-11-08:20-30/9.m3u8",
            urls(channel("http://panel.example.com/live/u/p/9.m3u8", "xc")).first(),
        )
    }

    @Test
    fun `the format follows the extension, else the channel's`() {
        val hls = M3uCatchup.candidates(channel("http://live.example.com/a.ts", "default", "http://cu.example.com/a.m3u8?s={utc}"), start, end, now, utc)
        assertEquals(listOf(StreamFormat.HLS), hls.map { it.format })
        val unknown = M3uCatchup.candidates(channel("http://live.example.com/a.m3u8", "default", "http://cu.example.com/archive?s={utc}"), start, end, now, utc)
        assertEquals(listOf(StreamFormat.HLS, StreamFormat.TS), unknown.map { it.format })
        val ts = M3uCatchup.candidates(channel("http://live.example.com/play/1", "shift"), start, end, now, utc)
        assertEquals(listOf(StreamFormat.TS, StreamFormat.HLS), ts.map { it.format })
    }

    // ---- Pedido (Streams.catchupCandidates) ----

    private val m3u = Playlist(id = "p", type = PlaylistType.M3U, name = "M3U", url = "http://list.example.com/l.m3u")

    private fun catchup(c: Channel, positionMs: Long = 0L) = Catchup(c, "Programa", start * 1000, end * 1000, positionMs)

    @Test
    fun `m3u catch-up asks from the second, or the minute for xc and minute templates`() {
        val shift = channel("http://live.example.com/a.ts", "shift")
        val cu = catchupCandidates(m3u, catchup(shift, positionMs = 61_500L), null, nowMs = now * 1000)!!
        assertEquals((start + 61) * 1000, cu.startMs)
        assertEquals("http://live.example.com/a.ts?utc=${start + 61}&lutc=$now", cu.candidates.first().url)

        val xc = channel("http://panel.example.com/live/u/p/9.ts", "xc")
        assertEquals((start - 15) * 1000, catchupCandidates(m3u, catchup(xc), null, nowMs = now * 1000)!!.startMs)
        val minutes = channel("http://live.example.com/a.ts", "default", "http://cu.example.com/{Y}{m}{d}{H}{M}/a.ts")
        assertEquals((start - 15) * 1000, catchupCandidates(m3u, catchup(minutes), null, nowMs = now * 1000)!!.startMs)
        // Sem tipo (lista guardada antes desta versão): sem catch-up.
        assertNull(catchupCandidates(m3u, catchup(shift.copy(catchupType = null)), null))
    }

    @Test
    fun `the time correction moves only what is asked`() {
        val shift = channel("http://live.example.com/a.ts", "shift")
        val cu = catchupCandidates(m3u, catchup(shift), null, correctionMin = -60, nowMs = now * 1000)!!
        // O vídeo que vem é o do programa: o player conta do início verdadeiro.
        assertEquals(start * 1000, cu.startMs)
        assertEquals("http://live.example.com/a.ts?utc=${start - 3600}&lutc=$now", cu.candidates.first().url)

        val xtream = Playlist(id = "x", type = PlaylistType.XTREAM, name = "X", server = "http://panel.example.com", username = "u", password = "p")
        val ch = channel("", null).copy(streamId = "9")
        val asked = catchupCandidates(xtream, catchup(ch), "UTC", correctionMin = 120)!!
        assertEquals((start - 15) * 1000, asked.startMs)
        assertTrue(asked.candidates.first().url, asked.candidates.first().url.endsWith("/timeshift/u/p/61/2026-11-08:22-30/9.ts"))
    }

    @Test
    fun `stalker has no url to build here`() {
        val stalker = Playlist(id = "s", type = PlaylistType.STALKER, name = "S", server = "http://portal.example.com", username = "00:1A:79:00:00:01")
        assertNull(catchupCandidates(stalker, catchup(channel("http://portal.example.com/ch/1", null)), null))
    }
}
