package cc.slokker.itv.media

import cc.slokker.itv.playback.TrackMemory
import cc.slokker.itv.ui.player.engine.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Os nomes das faixas de áudio: o papel e os canais que o PMT (e o HLS) dizem, e o nome que sai disso. */
class AudioTrackNamesTest {
    // ---- PMT feito à mão -----------------------------------------------------------------------

    private fun section(pid: Int, table: Int, id: Int, body: ByteArray): ByteArray {
        val length = 5 + body.size + 4
        val s = ByteArray(3 + length)
        s[0] = table.toByte()
        s[1] = (0xB0 or (length shr 8)).toByte()
        s[2] = length.toByte()
        s[3] = (id shr 8).toByte()
        s[4] = id.toByte()
        s[5] = 0xC1.toByte()
        body.copyInto(s, 8)
        val crc = TsDemuxer.crc32(s, 0, s.size - 4)
        for (k in 0 until 4) s[s.size - 4 + k] = (crc ushr (24 - 8 * k)).toByte()
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or (pid shr 8)).toByte()
        packet[2] = pid.toByte()
        packet[3] = 0x10
        packet[4] = 0
        s.copyInto(packet, 5)
        return packet
    }

    /** Um PMT com [streams] (tipo, PID, descritores) e o PAT que aponta para ele. */
    private fun tracksOf(vararg streams: Triple<Int, Int, ByteArray>): List<TsTrack> {
        val pat = section(0, 0x00, 1, byteArrayOf(0x00, 0x01, 0xE1.toByte(), 0x00))
        val body = ArrayList<Byte>()
        body += listOf(0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00)
        for ((type, pid, desc) in streams) {
            body += type.toByte()
            body += (0xE0 or (pid shr 8)).toByte()
            body += pid.toByte()
            body += (0xF0 or (desc.size shr 8)).toByte()
            body += desc.size.toByte()
            body += desc.toList()
        }
        val pmt = section(0x100, 0x02, 1, body.toByteArray())
        var found: List<TsTrack> = emptyList()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) { found = tracks }
            override fun onSample(track: TsTrack, sample: TsSample) {}
        })
        d.feed(pat + pmt + pat, 0, 188 * 3)
        d.flush()
        return found
    }

    private fun iso(lang: String, type: Int) = byteArrayOf(0x0A, 4, lang[0].code.toByte(), lang[1].code.toByte(), lang[2].code.toByte(), type.toByte())

    @Test
    fun `the ISO 639 audio type gives the role`() {
        val t = tracksOf(
            Triple(0x0F, 0x101, iso("por", 0)),
            Triple(0x0F, 0x102, iso("por", 3)),
            Triple(0x0F, 0x103, iso("eng", 2)),
            Triple(0x0F, 0x104, iso("eng", 1)),
        )
        assertEquals(listOf(AudioRole.MAIN, AudioRole.DESCRIPTION, AudioRole.HEARING_IMPAIRED, AudioRole.CLEAN_EFFECTS), t.map { it.role })
        assertEquals(listOf("por", "por", "eng", "eng"), t.map { it.language })
    }

    @Test
    fun `the supplementary audio descriptor gives the role and, without another, the language`() {
        // extensão 0x06: mix_type 0, editorial 1 (audiodescrição), língua presente.
        val ad = byteArrayOf(0x7F, 5, 0x06, ((1 shl 2) or 0x01).toByte(), 'f'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte())
        val clean = byteArrayOf(0x7F, 2, 0x06, (2 shl 2).toByte()) + iso("deu", 0)
        val main = byteArrayOf(0x7F, 2, 0x06, 0x00) + iso("spa", 0)
        val t = tracksOf(Triple(0x06, 0x101, ad + byteArrayOf(0x6A, 1, 0)), Triple(0x06, 0x102, clean), Triple(0x06, 0x103, main))
        assertEquals(AudioRole.DESCRIPTION, t[0].role)
        assertEquals("fra", t[0].language)
        assertEquals(Codec.AC3, t[0].codec)
        assertEquals(AudioRole.HEARING_IMPAIRED, t[1].role)
        assertEquals(AudioRole.ORIGINAL, t[2].role)
    }

    @Test
    fun `the AC-3 descriptors give the channels and the service type`() {
        // component_type: full service, tipo 0, 5.1 (number_of_channels 4).
        val surround = byteArrayOf(0x6A, 2, 0x80.toByte(), (0x40 or 4).toByte()) + iso("por", 0)
        val stereo = byteArrayOf(0x7A, 2, 0x80.toByte(), (0x80 or 0x40 or 2).toByte()) + iso("eng", 0)
        // tipo de serviço 2 = audiodescrição; mono.
        val described = byteArrayOf(0x6A, 2, 0x80.toByte(), ((2 shl 3) or 0).toByte()) + iso("eng", 0)
        val none = byteArrayOf(0x6A, 1, 0x00) + iso("eng", 0)
        val t = tracksOf(Triple(0x06, 0x101, surround), Triple(0x06, 0x102, stereo), Triple(0x06, 0x103, described), Triple(0x81, 0x104, none))
        assertEquals(listOf(6, 2, 1, 0), t.map { it.channels })
        assertEquals(listOf(Codec.AC3, Codec.EAC3, Codec.AC3, Codec.AC3), t.map { it.codec })
        assertEquals(AudioRole.DESCRIPTION, t[2].role)
        assertEquals(AudioRole.MAIN, t[0].role)
    }

    @Test
    fun `a broken descriptor does not hide the track`() {
        val t = tracksOf(Triple(0x0F, 0x101, byteArrayOf(0x7F, 9, 0x06)), Triple(0x0F, 0x102, iso("por", 0)))
        assertEquals(2, t.size)
        assertEquals("por", t[1].language)
    }

    // ---- O que o TsMuxer escreve volta a ler-se (é o caminho do HLS) --------------------------------

    @Test
    fun `role channels and name survive the muxer and the demuxer`() {
        val packets = ArrayList<ByteArray>()
        val muxer = TsMuxer { packets += it }
        val video = TsTrack(0x100, 0x1B, Codec.H264, TrackKind.VIDEO, null)
        val ad = TsTrack(0x10100, 0x0F, Codec.AAC_ADTS, TrackKind.AUDIO, "por", role = AudioRole.DESCRIPTION, channels = 2, name = "Audiodescrição")
        val original = TsTrack(0x20100, 0x81, Codec.AC3, TrackKind.AUDIO, "eng", role = AudioRole.ORIGINAL, channels = 6, name = "Original")
        muxer.onTracks(listOf(video, ad, original))
        muxer.onSample(video, TsSample(0, 0, ByteArray(10), true, false))
        var found: List<TsTrack> = emptyList()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) { found = tracks }
            override fun onSample(track: TsTrack, sample: TsSample) {}
        })
        val bytes = packets.fold(ByteArray(0)) { a, b -> a + b }
        d.feed(bytes, 0, bytes.size)
        d.flush()
        val a = found.first { it.language == "por" }
        assertEquals(AudioRole.DESCRIPTION, a.role)
        assertEquals(2, a.channels)
        assertEquals("Audiodescrição", a.name)
        val o = found.first { it.language == "eng" }
        assertEquals(AudioRole.ORIGINAL, o.role)
        assertEquals(6, o.channels)
        assertEquals(Codec.AC3, o.codec)
    }

    // ---- HLS ------------------------------------------------------------------------------------

    @Test
    fun `HLS characteristics, name and channels give the role`() {
        assertEquals(AudioRole.DESCRIPTION, AudioRole.fromHls("public.accessibility.describes-video", "Português"))
        assertEquals(AudioRole.DESCRIPTION, AudioRole.fromHls(null, "English Audio Description"))
        assertEquals(AudioRole.HEARING_IMPAIRED, AudioRole.fromHls("public.accessibility.enhances-speech-intelligibility", null))
        assertEquals(AudioRole.ORIGINAL, AudioRole.fromHls(null, "Original"))
        assertEquals(AudioRole.MAIN, AudioRole.fromHls(null, "Português"))
        assertEquals(AudioRole.MAIN, AudioRole.fromHls(null, "Madrid"))
        assertEquals(AudioRole.MAIN, AudioRole.fromHls(null, null))
    }

    @Test
    fun `the playlist reader keeps the media attributes`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Português",LANGUAGE="pt",DEFAULT=YES,CHANNELS="6",URI="pt.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Português AD",LANGUAGE="pt",CHARACTERISTICS="public.accessibility.describes-video",CHANNELS="2",URI="ptad.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",LANGUAGE="en",CHANNELS="16/JOC",URI="en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="a"
            video.m3u8
        """.trimIndent()
        val audio = HlsPlaylist.parse(master, "https://x/master.m3u8").variants.single().audio
        assertEquals(listOf(AudioRole.MAIN, AudioRole.DESCRIPTION, AudioRole.MAIN), audio.map { it.role })
        assertEquals(listOf(6, 2, 16), audio.map { it.channels })
        assertEquals("Português AD", audio[1].name)
        assertEquals("por", audio[0].code)
    }

    // ---- O nome --------------------------------------------------------------------------------

    private val words = AudioLabelWords("Original", "Audio description", "Hearing impaired", "Effects only", "Mono", "Stereo", "Audio %d")

    private fun label(vararg t: AudioLabelInput) = AudioTrackLabel.labels(t.toList(), words)

    @Test
    fun `names carry the language, the role and the channels`() {
        assertEquals(listOf("Portuguese · 5.1"), label(AudioLabelInput("Portuguese", AudioRole.MAIN, 6, "AC-3")))
        assertEquals(listOf("English · Original"), label(AudioLabelInput("English", AudioRole.ORIGINAL, 0, "AAC")))
        assertEquals(listOf("Portuguese · Audio description · Stereo"), label(AudioLabelInput("Portuguese", AudioRole.DESCRIPTION, 2, "AAC")))
        assertEquals(listOf("Portuguese"), label(AudioLabelInput("Portuguese", AudioRole.MAIN, 0, "")))
    }

    @Test
    fun `an unknown language is called Audio and its position`() {
        val l = label(AudioLabelInput("Portuguese", AudioRole.MAIN, 2, "AAC"), AudioLabelInput("", AudioRole.MAIN, 6, "AC-3"))
        assertEquals(listOf("Portuguese · Stereo", "Audio 2 · 5.1"), l)
    }

    @Test
    fun `the codec only appears when two names would be the same`() {
        val l = label(
            AudioLabelInput("Spanish", AudioRole.MAIN, 2, "AC-3"), AudioLabelInput("Spanish", AudioRole.MAIN, 2, "AAC"),
            AudioLabelInput("English", AudioRole.MAIN, 2, "AAC"),
        )
        assertEquals(listOf("Spanish · Stereo · AC-3", "Spanish · Stereo · AAC", "English · Stereo"), l)
    }

    @Test
    fun `identical tracks are told apart by their order`() {
        val l = label(AudioLabelInput("Spanish", AudioRole.MAIN, 2, "AAC"), AudioLabelInput("Spanish", AudioRole.MAIN, 2, "AAC"))
        assertEquals(listOf("Spanish · Stereo · AAC · 1", "Spanish · Stereo · AAC · 2"), l)
    }

    // ---- A memória do canal --------------------------------------------------------------------

    @Test
    fun `channel memory tells an audio description from the main track`() {
        val tracks = listOf(
            TrackInfo("por", null, 2, codec = "aac", detail = AudioRole.detail(AudioRole.DESCRIPTION)),
            TrackInfo("por", null, 2, codec = "aac", detail = AudioRole.detail(AudioRole.MAIN)),
        )
        // Quem escolheu a normal (a segunda) volta a ter a normal, e o contrário.
        assertEquals(1, TrackMemory.find(tracks, TrackMemory.memo(tracks, 1)))
        assertEquals(0, TrackMemory.find(tracks, TrackMemory.memo(tracks, 0)))
        // Mesmo com a ordem trocada hoje.
        val swapped = tracks.reversed()
        assertEquals(1, TrackMemory.find(swapped, TrackMemory.memo(tracks, 0)))
        assertNull(AudioRole.detail(AudioRole.MAIN))
    }
}
