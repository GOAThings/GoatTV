package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O que decide o desentrelaçamento: se o canal é entrelaçado (o SPS, o cabeçalho da sequência do MPEG-2), como foi
 * captada cada imagem (que campo primeiro), o que fazer pela definição, e a heurística do "pente" à saída do
 * descodificador. Os vídeos são os de resources/ts (com o que o ffprobe diz deles).
 */
class DeinterlacingTest {
    private fun videoSamples(name: String, each: (ByteArray) -> Unit) {
        val bytes = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {}
            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.kind == TrackKind.VIDEO) each(sample.data)
            }
        })
        d.feed(bytes, 0, bytes.size)
        d.flush()
    }

    private fun h264Units(name: String, pairFields: Boolean = true): Pair<NalReader, List<VideoUnit>> {
        val reader = NalReader(hevc = false).apply { this.pairFields = pairFields }
        val units = ArrayList<VideoUnit>()
        videoSamples(name) { data -> reader.next(data, 0, 0)?.let { units += it } }
        return reader to units
    }

    private fun mpeg2(name: String): Pair<Mpeg2Reader, List<VideoUnit>> {
        val reader = Mpeg2Reader()
        val units = ArrayList<VideoUnit>()
        videoSamples(name) { data -> units += reader.next(data, 0, 0) }
        return reader to units
    }

    @Test
    fun h264InterlacedFromSps() {
        // 576i (field_order tt pelo ffprobe).
        val (reader, units) = h264Units("h264_576i.ts")
        assertTrue(reader.interlaced)
        assertTrue(units.isNotEmpty())
        assertTrue(units.none { it.progressive })
        // Codificado em imagens inteiras (MBAFF) ou em campos com o de cima primeiro: nunca o de baixo primeiro.
        assertTrue(units.none { it.bottomFirst })
        val sps = units.first { it.parameterSets.isNotEmpty() }.parameterSets.first { (it[0].toInt() and 0x1F) == 7 }
        assertTrue(VideoInfo.h264(sps)!!.interlaced)
    }

    @Test
    fun h264ProgressiveFromSps() {
        val (reader, units) = h264Units("h264_aac.ts")
        assertFalse(reader.interlaced)
        assertTrue(units.all { it.progressive && !it.bottomFirst })
        val sps = units.first { it.parameterSets.isNotEmpty() }.parameterSets.first { (it[0].toInt() and 0x1F) == 7 }
        assertFalse(VideoInfo.h264(sps)!!.interlaced)
    }

    @Test
    fun hevcIsProgressive() {
        val reader = NalReader(hevc = true)
        val units = ArrayList<VideoUnit>()
        videoSamples("hevc_hdr10.ts") { data -> reader.next(data, 0, 0)?.let { units += it } }
        assertTrue(units.isNotEmpty() && units.all { it.progressive })
        val sps = units.first { it.parameterSets.isNotEmpty() }.parameterSets.first { ((it[0].toInt() shr 1) and 0x3F) == 33 }
        assertFalse(VideoInfo.hevc(sps)!!.interlaced)
    }

    @Test
    fun mpeg2TopFieldFirst() {
        val (reader, units) = mpeg2("mpeg2_b_aac.ts")
        assertTrue(reader.interlaced)
        assertTrue(units.isNotEmpty() && units.all { !it.progressive && !it.bottomFirst })
    }

    @Test
    fun mpeg2BottomFieldFirst() {
        val (reader, units) = mpeg2("mpeg2_mp2.ts")
        assertTrue(reader.interlaced)
        assertTrue(units.isNotEmpty() && units.all { !it.progressive && it.bottomFirst })
    }

    @Test
    fun mpeg2Progressive() {
        val (reader, units) = mpeg2("mpeg2_576_169.ts")
        assertFalse(reader.interlaced)
        assertTrue(units.isNotEmpty() && units.all { it.progressive })
    }

    @Test
    fun mpeg2PictureScanBits() {
        // picture_structure 3 (imagem), top_field_first, progressive_frame.
        assertEquals(Mpeg2Reader.SCAN_TOP_FIRST, Mpeg2Reader.pictureScan(0x03, 0x80, 0x00))
        assertEquals(Mpeg2Reader.SCAN_BOTTOM_FIRST, Mpeg2Reader.pictureScan(0x03, 0x00, 0x00))
        assertEquals(Mpeg2Reader.SCAN_PROGRESSIVE, Mpeg2Reader.pictureScan(0x03, 0x80, 0x80))
        // Campos soltos: o campo diz qual é, seja qual for o resto.
        assertEquals(Mpeg2Reader.SCAN_TOP_FIRST, Mpeg2Reader.pictureScan(0x01, 0x00, 0x80))
        assertEquals(Mpeg2Reader.SCAN_BOTTOM_FIRST, Mpeg2Reader.pictureScan(0x02, 0x80, 0x00))
    }

    @Test
    fun modeFollowsTheSetting() {
        assertEquals(Deinterlacing.FIELDS, Deinterlacing.mode(Prefs.Deinterlace.SMOOTH, interlaced = true))
        assertEquals(Deinterlacing.BLEND, Deinterlacing.mode(Prefs.Deinterlace.NORMAL, interlaced = true))
        assertEquals(Deinterlacing.NONE, Deinterlacing.mode(Prefs.Deinterlace.OFF, interlaced = true))
        assertEquals(Deinterlacing.NONE, Deinterlacing.mode("something else", interlaced = true))
        // Progressivo, HDR ou 10 bits: nunca.
        assertEquals(Deinterlacing.NONE, Deinterlacing.mode(Prefs.Deinterlace.SMOOTH, interlaced = false))
        assertEquals(Deinterlacing.NONE, Deinterlacing.mode(Prefs.Deinterlace.SMOOTH, interlaced = true, hdr = true))
        assertEquals(Deinterlacing.NONE, Deinterlacing.mode(Prefs.Deinterlace.SMOOTH, interlaced = true, bitDepth = 10))
    }

    @Test
    fun knownDecoders() {
        assertEquals(Deinterlacing.DECODER_DEINTERLACES, Deinterlacing.decoder("OMX.Nvidia.h264.decode"))
        assertEquals(Deinterlacing.DECODER_DEINTERLACES, Deinterlacing.decoder("c2.nvidia.mpeg2.decoder"))
        assertEquals(Deinterlacing.DECODER_WOVEN, Deinterlacing.decoder("c2.android.avc.decoder"))
        assertEquals(Deinterlacing.DECODER_WOVEN, Deinterlacing.decoder("OMX.google.h264.decoder"))
        assertEquals(Deinterlacing.DECODER_UNKNOWN, Deinterlacing.decoder("OMX.amlogic.avc.decoder.awesome2"))
        assertEquals(Deinterlacing.DECODER_UNKNOWN, Deinterlacing.decoder("c2.mtk.avc.decoder"))
    }

    // A grelha de um desenho: 2304 pontos (32 × 18, 4 linhas cada).
    private val total = 2304

    @Test
    fun combingDecidesWovenForGood() {
        val v = InterlaceVerdict()
        assertTrue(v.deinterlace)
        assertTrue(v.measuring)
        repeat(InterlaceVerdict.COMBED_NEEDED) { v.sample(combedPoints = 200, detailPoints = 600, total = total) }
        assertTrue(v.woven)
        assertFalse(v.measuring)
        // Depois de decidido, nada o muda.
        repeat(1000) { v.sample(0, 900, total) }
        assertTrue(v.deinterlace)
    }

    @Test
    fun cleanOutputStopsDeinterlacingOnlyAfterLong() {
        val v = InterlaceVerdict()
        repeat(InterlaceVerdict.CLEAN_NEEDED - 1) { v.sample(0, 500, total) }
        assertTrue(v.deinterlace)
        v.sample(0, 500, total)
        assertFalse(v.deinterlace)
        assertTrue(v.measuring)
        // O primeiro pente volta logo a desentrelaçar; com mais, fica decidido.
        v.sample(150, 500, total)
        assertTrue(v.deinterlace)
        repeat(1000) { v.sample(0, 500, total) }
        assertTrue(v.deinterlace)
    }

    @Test
    fun staticOrFlatPicturesDecideNothing() {
        val v = InterlaceVerdict()
        // Sem detalhe (um ecrã preto, uma imagem lisa): não conta para nada.
        repeat(1000) { v.sample(0, 10, total) }
        assertTrue(v.deinterlace)
        assertFalse(v.woven)
        // Uns poucos pontos com "pente" (riscas finas, texto) no meio de muito detalhe também não.
        repeat(1000) { v.sample(4, 800, total) }
        assertFalse(v.woven)
    }

    @Test
    fun oneCombAnywhereBlocksPassThrough() {
        val v = InterlaceVerdict()
        v.sample(100, 300, total)
        repeat(1000) { v.sample(0, 500, total) }
        assertTrue(v.deinterlace)
    }

    @Test
    fun knownWovenNeedsNoMeasuring() {
        val v = InterlaceVerdict(woven = true)
        assertFalse(v.measuring)
        repeat(1000) { v.sample(0, 500, total) }
        assertTrue(v.deinterlace)
    }
}
