package cc.slokker.itv.playback

import cc.slokker.itv.ui.player.engine.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelMemoryTest {
    private fun t(lang: String?, codec: String? = null, detail: String? = null, channels: Int = 0, supported: Boolean = true, label: String? = null, forced: Boolean = false) =
        TrackInfo(lang, label, channels, supported, forced = forced, codec = codec, detail = detail)

    // ---- A faixa guardada e a de hoje ------------------------------------------------------------

    @Test
    fun aMesmaFaixaNoutroDiaComOutraOrdem() {
        val memo = TrackMemory.memo(listOf(t("por", "aac"), t("eng", "ac3", channels = 6)), 1)
        assertEquals("en;ac3;;6;0", memo)
        // Os PID e a ordem mudaram, e a língua vem escrita de outra maneira.
        assertEquals(0, TrackMemory.find(listOf(t("en", "ac3", channels = 6), t("pt", "aac")), memo))
        assertEquals(2, TrackMemory.find(listOf(t("pt", "aac"), t("es", "aac"), t(null, "ac3", channels = 6, label = "English")), memo))
    }

    @Test
    fun duasFaixasDaMesmaLinguaDistinguemSePeloFormato() {
        val tracks = listOf(t("pt", "aac", channels = 2), t("pt", "ac3", channels = 6), t("en", "aac"))
        val memo = TrackMemory.memo(tracks, 1)
        assertEquals(1, TrackMemory.find(tracks, memo))
        assertEquals(2, TrackMemory.find(listOf(t("en", "aac"), t("pt", "aac"), t("pt", "ac3", channels = 6)), memo))
        // O formato já não existe: fica a língua.
        assertEquals(1, TrackMemory.find(listOf(t("en", "aac"), t("pt", "aac")), memo))
    }

    @Test
    fun oPapelDistingueAAudiodescricao() {
        val tracks = listOf(t("pt", "aac"), t("pt", "aac", detail = "ad"))
        val memo = TrackMemory.memo(tracks, 1)
        assertEquals(0, TrackMemory.find(tracks.reversed(), memo))
        assertEquals(1, TrackMemory.find(tracks, memo))
    }

    @Test
    fun faixasIguaisEmTudoDistinguemSePelaPosicao() {
        val tracks = listOf(t("pt", "aac"), t("pt", "aac"), t("pt", "aac"))
        assertEquals(2, TrackMemory.find(tracks, TrackMemory.memo(tracks, 2)))
        // Hoje há menos uma: a primeira das iguais.
        assertEquals(0, TrackMemory.find(tracks.take(2), TrackMemory.memo(tracks, 2)))
    }

    @Test
    fun semALinguaGuardadaNaoHaFaixa() {
        val memo = TrackMemory.memo(listOf(t("pt"), t("en", "ac3")), 1)
        assertNull(TrackMemory.find(listOf(t("pt", "ac3"), t("es", "ac3")), memo))
        // A que há não toca neste aparelho.
        assertNull(TrackMemory.find(listOf(t("pt"), t("en", "ac3", supported = false)), memo))
        assertNull(TrackMemory.find(emptyList(), memo))
        assertNull(TrackMemory.find(listOf(t("en")), ""))
        assertNull(TrackMemory.find(listOf(t("en")), TrackMemory.OFF))
        assertNull(TrackMemory.find(listOf(t("en")), "lixo"))
    }

    @Test
    fun umaFaixaSemLinguaSoCorrespondeAOutraSemLinguaDoMesmoFormato() {
        val tracks = listOf(t("und", "aac"), t(null, "mp2"), t("qaa", "ac3"))
        val memo = TrackMemory.memo(tracks, 1)
        assertEquals(";mp2;;0;0", memo)
        assertEquals(0, TrackMemory.find(listOf(t(null, "mp2"), t("pt", "mp2")), memo))
        assertNull(TrackMemory.find(listOf(t(null, "aac"), t("pt", "mp2")), memo))
    }

    @Test
    fun asLegendasGuardamSePelaLinguaEPeloTipo() {
        val tracks = listOf(t("por", "dvb"), t("por", "teletext", "888"), t("por", "teletext", "889"), t(null, "cc"))
        assertEquals(2, TrackMemory.find(tracks, TrackMemory.memo(tracks, 2)))
        assertEquals(0, TrackMemory.find(tracks, TrackMemory.memo(tracks, 0)))
        assertEquals(3, TrackMemory.find(tracks, TrackMemory.memo(tracks, 3)))
        // Hoje só há DVB nessa língua: ficam essas.
        assertEquals(1, TrackMemory.find(listOf(t("eng", "dvb"), t("por", "dvb")), TrackMemory.memo(tracks, 2)))
        assertEquals(TrackMemory.OFF, TrackMemory.memo(tracks, -1))
    }

    // ---- Escolher outra vez o que as regras de sempre davam limpa a memória --------------------------

    @Test
    fun escolherAFaixaDeSempreNaoGuardaNada() {
        val tracks = listOf(t("en", "aac", channels = 2), t("pt", "aac", channels = 2), t("pt", "ac3", channels = 6))
        assertEquals("", TrackMemory.audioChoice(tracks, 1, "pt", surround = false))
        assertEquals("", TrackMemory.audioChoice(tracks, 2, "pt", surround = true))
        assertEquals("", TrackMemory.audioChoice(tracks, 0, null, surround = false))
        assertEquals("", TrackMemory.audioChoice(tracks, 0, "fr", surround = false))
        assertEquals("pt;ac3;;6;0", TrackMemory.audioChoice(tracks, 2, "pt", surround = false))
        assertEquals("en;aac;;2;0", TrackMemory.audioChoice(tracks, 0, "pt", surround = false))
        // A que está a dar agora não conta: é a regra que diz qual é a de sempre.
        val playing = listOf(tracks[0], tracks[1].copy(selected = true), tracks[2])
        assertEquals("", TrackMemory.audioChoice(playing, 1, "pt", surround = false))
        assertEquals("", TrackMemory.audioChoice(tracks, 9, "pt", surround = false))
    }

    @Test
    fun asLegendasDeSempreSaoAsDaLinguaPreferidaOuDesligadas() {
        val tracks = listOf(t("en", "dvb"), t("pt", "dvb"))
        assertEquals("", TrackMemory.textChoice(tracks, -1, null))
        assertEquals("", TrackMemory.textChoice(tracks, -1, "fr"))
        assertEquals("", TrackMemory.textChoice(tracks, 1, "pt"))
        assertEquals(TrackMemory.OFF, TrackMemory.textChoice(tracks, -1, "pt"))
        assertEquals("pt;dvb;;0;0", TrackMemory.textChoice(tracks, 1, null))
        assertEquals("en;dvb;;0;0", TrackMemory.textChoice(tracks, 0, "pt"))
    }

    // ---- A memória por canal -------------------------------------------------------------------

    @Test
    fun guardaPorListaECanalELimpaComVazio() {
        val m = ChannelMemory()
        assertTrue(m.setAudio("l1", "rtp1", "en;ac3;;6;0"))
        assertFalse(m.setAudio("l1", "rtp1", "en;ac3;;6;0"))
        assertTrue(m.setText("l1", "rtp1", TrackMemory.OFF))
        assertEquals("en;ac3;;6;0", m.audio("l1", "rtp1"))
        assertEquals(TrackMemory.OFF, m.text("l1", "rtp1"))
        assertEquals("", m.aspect("l1", "rtp1"))
        assertEquals("", m.audio("l2", "rtp1"))
        assertEquals("", m.audio("l1", "sic"))
        assertTrue(m.setAudio("l1", "rtp1", ""))
        assertTrue(m.setText("l1", "rtp1", ""))
        assertFalse(m.setText("l1", "rtp1", ""))
        assertEquals("", m.encode())
    }

    @Test
    fun aProporcaoSoSeGuardaQuandoEDiferenteDaDasDefinicoes() {
        val m = ChannelMemory()
        assertFalse(m.setAspect("l1", "rtp1", "fit", global = "fit"))
        assertTrue(m.setAspect("l1", "rtp1", "zoom", global = "fit"))
        assertEquals("zoom", m.aspect("l1", "rtp1"))
        assertTrue(m.setAspect("l1", "rtp1", "fit", global = "fit"))
        assertEquals("", m.aspect("l1", "rtp1"))
        assertEquals("", m.encode())
    }

    @Test
    fun oTextoGuardadoVoltaIgual() {
        val m = ChannelMemory()
        m.setAudio("l1", "http://x/a|b\tc", "pt;aac;;2;0")
        m.setText("l1", "sic", "pt;teletext;888;0;0")
        m.setAspect("pro:pt", "rtp1", "fill", global = "fit")
        val back = ChannelMemory.decode(m.encode())
        assertEquals(m.encode(), back.encode())
        assertEquals("pt;aac;;2;0", back.audio("l1", "http://x/a|b\tc"))
        assertEquals("pt;teletext;888;0;0", back.text("l1", "sic"))
        assertEquals("fill", back.aspect("pro:pt", "rtp1"))
        assertEquals("", ChannelMemory.decode(null).encode())
        assertEquals("", ChannelMemory.decode("lixo\n\t\t\t\nsem\ttabs").encode())
        // Uma chave com quebra de linha não se guarda.
        assertFalse(m.setAudio("l1", "a\nb", "pt;aac;;2;0"))
    }

    @Test
    fun ficamOsCanaisUsadosHaMenosTempo() {
        val m = ChannelMemory()
        for (i in 0 until ChannelMemory.MAX) m.setAspect("l1", "c$i", "zoom", global = "fit")
        // O mais antigo abre-se outra vez: passa para o fim da fila.
        assertTrue(m.touch("l1", "c0"))
        assertFalse(m.touch("l1", "c0"))
        assertFalse(m.touch("l1", "sem-memoria"))
        m.setAspect("l1", "novo", "zoom", global = "fit")
        assertEquals("zoom", m.aspect("l1", "c0"))
        assertEquals("", m.aspect("l1", "c1"))
        assertEquals("zoom", m.aspect("l1", "novo"))
        assertEquals(ChannelMemory.MAX, m.encode().lines().size)
        assertEquals(ChannelMemory.MAX, ChannelMemory.decode(m.encode() + "\nzoom\t\t\tl1|mais").encode().lines().size)
    }
}
