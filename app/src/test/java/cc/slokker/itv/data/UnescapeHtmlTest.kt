package cc.slokker.itv.data

import cc.slokker.itv.data.api.unescapeHtml
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nomes do IPTV-EPG.org com entidades HTML. */
class UnescapeHtmlTest {
    @Test
    fun entidades() {
        assertEquals("Trinidad & Tobago", "Trinidad &amp; Tobago".unescapeHtml())
        assertEquals("Bosnia & Herzegovina", "Bosnia &amp; Herzegovina".unescapeHtml())
        assertEquals("\"Isto\" <é> d'aqui", "&quot;Isto&quot; &lt;é&gt; d&#39;aqui".unescapeHtml())
    }

    @Test
    fun soUmaVez() {
        // "&amp;lt;" é o texto "&lt;", não "<".
        assertEquals("&lt;", "&amp;lt;".unescapeHtml())
        assertEquals("SIC", "SIC".unescapeHtml())
    }
}
