package cc.slokker.itv.data

import cc.slokker.itv.data.epg.AccountGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class AccountGuideTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="GOAT">
          <channel id="1001"><display-name>SIC HD</display-name></channel>
          <channel id="abc.PT"><display-name>RTP 1</display-name></channel>
          <programme start="20261022190000 +0000" stop="20261022200000 +0000" channel="1001"><title>Jornal da Noite</title></programme>
          <programme start="20261022180000 +0000" stop="20261022190000 +0000" channel="1001"><title>Casados à Primeira Vista</title></programme>
          <programme start="20261022180000 +0000" stop="20261022190000 +0000" channel="abc.PT"><title>Telejornal</title></programme>
        </tv>"""

    @Test
    fun `programmes are keyed by the playlist channelKey, in order`() {
        val guide = AccountGuide.parse("p1", "\"etag\"", OCT_22_18H, StringReader(xml))
        val sic = guide.programmes.getValue("1001")
        assertEquals(listOf("Casados à Primeira Vista", "Jornal da Noite"), sic.map { it.title })
        // As chaves são exatas (não se passam a minúsculas, como os ids do XMLTV das listas).
        assertEquals("Telejornal", guide.programmes.getValue("abc.PT").single().title)
        assertNull(guide.programmes["abc.pt"])
        assertEquals("\"etag\"", guide.etag)
    }

    @Test
    fun `only keeps programmes around now`() {
        val guide = AccountGuide.parse("p1", null, OCT_22_18H + 3 * 86_400_000L, StringReader(xml))
        assertEquals(emptyMap<String, Any>(), guide.programmes)
        val later = AccountGuide.parse("p1", null, OCT_22_18H, StringReader(xml)).trimmed(OCT_22_18H + 25 * 3600_000L)
        assertEquals(listOf("Jornal da Noite"), later.programmes.getValue("1001").map { it.title })
    }

    private companion object {
        /** 2026-10-22 18:00 UTC. */
        const val OCT_22_18H = 1_792_692_000_000L
    }
}
