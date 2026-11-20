package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O arranque de um canal apanhado a meio (os streams do ffmpeg em `resources/ts`, cortados e sem o primeiro PAT/PMT,
 * como um servidor que começa a mandar onde calha): o que chega antes do PMT não se perde (ver TsDemuxer.early), e a
 * primeira imagem que se pode mostrar é uma imagem-chave com os parâmetros, em todos os formatos de vídeo.
 */
class FastStartTest {
    private class Collect : TsListener {
        var tracks: List<TsTrack> = emptyList()
        val samples = ArrayList<Pair<TsTrack, TsSample>>()
        override fun onTracks(tracks: List<TsTrack>) {
            this.tracks = tracks
        }

        override fun onSample(track: TsTrack, sample: TsSample) {
            samples += track to sample
        }

        fun of(kind: TrackKind) = samples.filter { it.first.kind == kind }.map { it.second }
    }

    private fun file(name: String) = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()

    private fun pid(d: ByteArray, i: Int) = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)

    private fun start(d: ByteArray, i: Int) = d[i + 1].toInt() and 0x40 != 0

    /**
     * Os pacotes de [d] a partir do pacote [from], sem os de PSI (PAT, PMT, SDT: tudo o que não é das [media]) até ao
     * pacote [psiFrom]: o primeiro PMT que o leitor vê é o de depois dele.
     */
    private fun cut(d: ByteArray, from: Int, psiFrom: Int, media: Set<Int>, drop: Set<Int> = emptySet()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var n = from
        while (n * 188 < d.size) {
            val at = n * 188
            val p = pid(d, at)
            val keep = p !in drop && (p in media || n >= psiFrom)
            if (keep) out.write(d, at, 188)
            n++
        }
        return out.toByteArray()
    }

    /** O n.º do pacote do [k]-ésimo PAT do ficheiro (0 = o primeiro). */
    private fun patPacket(d: ByteArray, k: Int): Int = (0 until d.size / 188).filter { pid(d, it * 188) == 0 }[k]

    /** O n.º do pacote onde começa o [k]-ésimo PES da faixa [pid]. */
    private fun pesStart(d: ByteArray, pid: Int, k: Int): Int = (0 until d.size / 188).filter { pid(d, it * 188) == pid && start(d, it * 188) }[k]

    private fun demux(bytes: ByteArray): Collect {
        val c = Collect()
        val d = TsDemuxer(c)
        var at = 0
        var step = 1
        while (at < bytes.size) {
            val n = minOf(step, bytes.size - at)
            d.feed(bytes, at, n)
            at += n
            step = step * 7 % 3001 + 1
        }
        d.flush()
        return c
    }

    private fun mediaPids(name: String): Map<TrackKind, Int> =
        demux(file(name)).tracks.filter { it.kind == TrackKind.VIDEO || it.kind == TrackKind.AUDIO }.associate { it.kind to it.pid }

    /** As imagens que iam para o descodificador (como no GoatCorePlayer e no MediaPipeline): (pts, imagem-chave, parâmetros). */
    private fun decodable(c: Collect): List<VideoUnit> {
        val track = c.tracks.first { it.kind == TrackKind.VIDEO }
        val start = VideoStart()
        val nal = when (track.codec) {
            Codec.H264 -> NalReader(hevc = false).apply { pairFields = false }
            Codec.HEVC -> NalReader(hevc = true)
            else -> null
        }
        val mpeg2 = if (track.codec == Codec.MPEG2_VIDEO) Mpeg2Reader() else null
        var sets: List<ByteArray> = emptyList()
        val out = ArrayList<VideoUnit>()
        for (s in c.of(TrackKind.VIDEO)) {
            val u = (if (mpeg2 != null) mpeg2.next(s.data, s.pts, s.dts) else nal!!.next(s.data, s.pts, s.dts)) ?: continue
            if (u.parameterSets.isNotEmpty()) sets = u.parameterSets
            if (sets.isEmpty() || !start.pass(u.keyframe, u.pts)) continue
            out += u
        }
        return out
    }

    /** O PMT chega depois da primeira imagem-chave: ela não se perde (sem guardar, a imagem só começava no GOP seguinte). */
    private fun keyframeBeforePmt(name: String) {
        val d = file(name)
        val pids = mediaPids(name)
        val video = pids.getValue(TrackKind.VIDEO)
        val whole = decodable(demux(d))
        // Sem o primeiro PAT/PMT: o seguinte vem depois do início da primeira imagem-chave.
        val second = patPacket(d, 1)
        assertTrue(second > pesStart(d, video, 0), name)
        val late = demux(cut(d, 0, second, pids.values.toSet()))
        val units = decodable(late)
        assertEquals(whole.size, units.size, "$name: imagens")
        assertEquals(whole.first().pts, units.first().pts, "$name: a primeira imagem é a mesma")
        assertTrue(units.first().keyframe, name)
        // O som que veio antes do PMT também.
        assertEquals(demux(d).of(TrackKind.AUDIO).size, late.of(TrackKind.AUDIO).size, "$name: som")
        assertTrue(late.samples.none { it.second.discontinuity }, name)
    }

    @Test
    fun h264KeyframeBeforePmt() = keyframeBeforePmt("h264_aac.ts")

    @Test
    fun hevcKeyframeBeforePmt() = keyframeBeforePmt("hevc_ac3.ts")

    @Test
    fun mpeg2KeyframeBeforePmt() = keyframeBeforePmt("mpeg2_mp2.ts")

    /** Sem som (576i entrelaçado, só vídeo): a imagem-chave antes do PMT vem na mesma, e o resto todo atrás. */
    @Test
    fun videoOnlyInterlacedKeyframeBeforePmt() {
        val name = "h264_576i.ts"
        val d = file(name)
        val pids = mediaPids(name)
        assertNull(pids[TrackKind.AUDIO], "o ficheiro é só de vídeo")
        val late = demux(cut(d, 0, patPacket(d, 1), pids.values.toSet()))
        val units = decodable(late)
        assertEquals(decodable(demux(d)).size, units.size)
        assertTrue(units.first().keyframe)
        assertTrue(!units.first().progressive, "entrelaçado")
    }

    /**
     * Apanhado a meio de um GOP (o corte cai entre a primeira e a segunda imagem-chave): as imagens de antes da
     * imagem-chave seguinte não vão para o descodificador, e dela em diante vai tudo.
     */
    private fun midGop(name: String) {
        val d = file(name)
        val pids = mediaPids(name)
        val video = pids.getValue(TrackKind.VIDEO)
        val whole = decodable(demux(d))
        val keys = whole.withIndex().filter { it.value.keyframe }.map { it.index }
        assertTrue(keys.size >= 2, name)
        // O corte no início do 3.º PES de vídeo: já depois da primeira imagem-chave.
        val from = pesStart(d, video, 2)
        val units = decodable(demux(cut(d, from, from, pids.values.toSet())))
        assertTrue(units.first().keyframe, "$name: começa numa imagem-chave")
        assertTrue(units.first().parameterSets.isNotEmpty() || name.startsWith("mpeg2"), "$name: com os parâmetros")
        val key = whole[keys[1]]
        assertEquals(key.pts, units.first().pts, "$name: a imagem-chave seguinte")
        // Dela em diante, tudo menos as que se mostram antes dela (num open GOP, HEVC com CRA, apontam para trás).
        val next = keys.getOrNull(2) ?: whole.size
        val expected = whole.drop(keys[1]).filterIndexed { i, u -> i == 0 || keys[1] + i >= next || u.pts >= key.pts }
        assertEquals(expected.map { it.pts }, units.map { it.pts }, "$name: dela em diante")
    }

    @Test
    fun h264MidGop() = midGop("h264_aac.ts")

    @Test
    fun hevcMidGop() = midGop("hevc_ac3.ts")

    @Test
    fun mpeg2MidGop() = midGop("mpeg2_mp2.ts")

    /** Open GOP (I não IDR): começa na imagem I seguinte, sem as B que se mostram antes dela. */
    @Test
    fun openGopMidGop() {
        val name = "h264_opengop.ts"
        val d = file(name)
        val pids = mediaPids(name)
        val from = pesStart(d, pids.getValue(TrackKind.VIDEO), 2)
        val units = decodable(demux(cut(d, from, from, pids.values.toSet())))
        val first = units.first()
        assertTrue(first.keyframe)
        assertTrue(units.drop(1).takeWhile { !it.keyframe }.all { it.pts > first.pts }, "nenhuma imagem antes da de entrada")
    }

    /**
     * O som antes do vídeo: o corte cai a meio de uma imagem (o PES dela perde-se) e o PMT chega mais tarde. O som de
     * antes do PMT sai primeiro, com o tempo de antes da primeira imagem; quem toca acerta-os pelo relógio.
     */
    @Test
    fun audioBeforeVideo() {
        val name = "h264_aac.ts"
        val d = file(name)
        val pids = mediaPids(name)
        // O corte no primeiro bloco de som, já a meio do primeiro GOP; o PMT só no 4.º PAT.
        val from = pesStart(d, pids.getValue(TrackKind.AUDIO), 0)
        val late = demux(cut(d, from, patPacket(d, 3), pids.values.toSet()))
        val first = late.samples.first()
        assertEquals(TrackKind.AUDIO, first.first.kind, "o som vem primeiro")
        val units = decodable(late)
        assertTrue(units.first().keyframe)
        assertTrue(first.second.pts < units.first().pts, "o som começa antes da primeira imagem")
    }

    /** Muitos pacotes antes do PMT (mais do que se guarda): fica o mais recente, e o canal arranca na mesma. */
    @Test
    fun longWaitForPmtKeepsTheNewest() {
        val name = "h264_aac.ts"
        val d = file(name)
        val pids = mediaPids(name)
        val video = pids.getValue(TrackKind.VIDEO)
        // 4 MB de pacotes de vídeo sem início de PES (não se juntam a nada), e depois o canal sem o primeiro PAT/PMT.
        val junk = ByteArray(188 * 22_000)
        for (n in 0 until 22_000) {
            val at = n * 188
            junk[at] = 0x47
            junk[at + 1] = (video shr 8).toByte()
            junk[at + 2] = (video and 0xFF).toByte()
            junk[at + 3] = (0x10 or (n and 0x0F)).toByte()
        }
        val c = demux(junk + cut(d, 0, patPacket(d, 1), pids.values.toSet()))
        val units = decodable(c)
        assertEquals(decodable(demux(d)).size, units.size)
        assertTrue(units.first().keyframe)
    }

    /** Depois do primeiro PMT não se guarda mais nada: um PID que não está no PMT continua a não passar. */
    @Test
    fun unknownPidsAfterPmtAreDropped() {
        val name = "h264_aac.ts"
        val d = file(name)
        val pids = mediaPids(name)
        val audio = pids.getValue(TrackKind.AUDIO)
        val c = demux(cut(d, 0, 0, pids.values.toSet()))
        assertEquals(c.of(TrackKind.AUDIO).size, demux(d).of(TrackKind.AUDIO).size)
        // Sem o vídeo no stream (só o PMT o anuncia): nada dele, e o som passa.
        val noVideo = demux(cut(d, 0, 0, pids.values.toSet(), drop = setOf(pids.getValue(TrackKind.VIDEO))))
        assertTrue(noVideo.of(TrackKind.VIDEO).isEmpty())
        assertTrue(noVideo.samples.all { it.first.pid == audio })
    }

    @Test
    fun zapStagesCountEachStageOnce() {
        val z = ZapStages(1_000)
        assertEquals("open +5 ms (5 ms)", z.mark("open", 1_005))
        assertEquals("tracks +120 ms (125 ms) H264 AAC_ADTS", z.mark("tracks", 1_125, "H264 AAC_ADTS"))
        assertNull(z.mark("open", 1_200))
        assertTrue(z.reached("tracks"))
        assertNotNull(z.mark("picture", 1_400))
        assertEquals("audio +50 ms (450 ms)", z.mark("audio", 1_450))
    }
}
