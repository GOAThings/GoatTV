package cc.slokker.itv.data

import cc.slokker.itv.data.epg.XmltvGuide
import cc.slokker.itv.data.epg.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class XmltvTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <!-- comentário com <tags> e "aspas -->
          <channel id="RTP1.pt">
            <display-name lang="pt">RTP 1</display-name>
            <icon src="https://example.com/rtp1.png"/>
          </channel>
          <channel id="sic.pt"><display-name>SIC HD</display-name></channel>
          <channel id="other.es"><display-name>Otro</display-name></channel>
          <programme start="20261019120000 +0100" stop="20261019130000 +0100" channel="RTP1.pt">
            <title lang="pt">Jornal da Tarde</title>
            <title lang="en">Afternoon News</title>
            <desc>Notícias &amp; mais</desc>
          </programme>
          <programme start="20261019130000 +0100" stop="20261019140000 +0100" channel="RTP1.pt">
            <title><![CDATA[Praça & Companhia]]></title>
          </programme>
          <programme start="20261019120000 +0000" stop="20261019123000 +0000" channel="sic.pt">
            <title>Primeiro Jornal &#8211; edição</title>
          </programme>
          <programme start="20261019120000 +0000" stop="20261019123000 +0000" channel="other.es">
            <title>Fuera</title>
          </programme>
          <programme start="20261001120000 +0000" stop="20261001123000 +0000" channel="sic.pt">
            <title>Antigo</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun channel(uid: Int, name: String, epgId: String? = null) =
        Channel(uid = uid, key = "k$uid", num = uid + 1, name = name, logo = null, categoryId = "g", epgId = epgId)

    @Test
    fun `parses times with and without zone`() {
        assertEquals(1792407600000L, XmltvParser.parseTime("20261019110000"))
        assertEquals(1792407600000L, XmltvParser.parseTime("20261019120000 +0100"))
        assertEquals(1792407600000L, XmltvParser.parseTime("20261019060000 -0500"))
        assertEquals(1792407600000L, XmltvParser.parseTime("202610191100"))
        assertNull(XmltvParser.parseTime("garbage"))
        assertNull(XmltvParser.parseTime("20261340110000"))
    }

    @Test
    fun `normalizes channel names`() {
        assertEquals("rtp1", XmltvGuide.normalizeName("PT: RTP 1 HD"))
        assertEquals("rtp1", XmltvGuide.normalizeName("RTP1"))
        assertEquals("sicnoticias", XmltvGuide.normalizeName("SIC Notícias FHD"))
        assertEquals("sporttv1", XmltvGuide.normalizeName("PT | Sport TV 1 4K"))
    }

    @Test
    fun `keeps only the playlist channels and the window, matching by id or name`() {
        val from = XmltvParser.parseTime("20261018000000")!!
        val to = XmltvParser.parseTime("20261021000000")!!
        val rtp = channel(0, "RTP 1", epgId = "rtp1.PT") // id com maiúsculas diferentes
        val sic = channel(1, "PT: SIC") // sem tvg-id: casa pelo nome
        val none = channel(2, "Canal Sem Guia")
        val guide = XmltvGuide.build("u", listOf(rtp, sic, none), from, to, fetchedAt = 1L) { it.parse(StringReader(xml)) }

        val rtpEpg = guide.entriesFor(rtp)
        assertEquals(listOf("Jornal da Tarde", "Praça & Companhia"), rtpEpg.map { it.title })
        assertEquals(XmltvParser.parseTime("20261019110000"), rtpEpg[0].startMs)

        val sicEpg = guide.entriesFor(sic)
        assertEquals(listOf("Primeiro Jornal – edição"), sicEpg.map { it.title }) // "Antigo" está fora da janela
        assertTrue(guide.entriesFor(none).isEmpty())
        assertFalse(guide.programmes.containsKey("other.es"))
    }

    @Test
    fun `refresh policy`() {
        val h = 3600_000L
        // Nunca descarregado: sempre.
        assertTrue(RefreshPolicy.isDue(12, 0L, 10 * h, checkedSinceOpen = true))
        // "Sempre que abrir a app": uma vez por abertura.
        assertTrue(RefreshPolicy.isDue(0, 9 * h, 10 * h, checkedSinceOpen = false))
        assertFalse(RefreshPolicy.isDue(0, 9 * h, 10 * h, checkedSinceOpen = true))
        // Por horas: só quando passa o intervalo.
        assertFalse(RefreshPolicy.isDue(6, 5 * h, 10 * h, checkedSinceOpen = false))
        assertTrue(RefreshPolicy.isDue(6, 4 * h, 10 * h, checkedSinceOpen = true))
    }
}
