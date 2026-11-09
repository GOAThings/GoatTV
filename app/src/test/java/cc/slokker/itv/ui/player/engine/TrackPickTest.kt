package cc.slokker.itv.ui.player.engine

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A escolha automática das faixas: a língua preferida, o surround e as legendas. */
class TrackPickTest {

    private fun t(lang: String?, label: String? = null, channels: Int = 2, selected: Boolean = false, supported: Boolean = true, forced: Boolean = false) =
        TrackInfo(lang, label, channels, supported, selected, forced)

    @Test
    fun `the preferred language wins over the stream's default`() {
        val tracks = listOf(t("eng", selected = true), t("por"), t("spa"))
        assertEquals(1, TrackPick.audio(tracks, "pt", surround = false))
    }

    @Test
    fun `nothing changes when the language is already playing or missing`() {
        assertNull(TrackPick.audio(listOf(t("pt", selected = true), t("en")), "pt", surround = false))
        assertNull(TrackPick.audio(listOf(t("eng", selected = true), t("spa")), "pt", surround = false))
        assertNull(TrackPick.audio(listOf(t("eng", selected = true), t("spa")), null, surround = false))
    }

    @Test
    fun `quirky tags and labels still match`() {
        // TS com "qaa" (versão original) e a dobragem marcada só na etiqueta (HLS NAME / VLC).
        val tracks = listOf(t("qaa", selected = true), t(null, "Faixa 2 - [Portuguese]"))
        assertEquals(1, TrackPick.audio(tracks, "pt", surround = false))
        assertEquals(1, TrackPick.audio(listOf(t("und", selected = true), t("und", "Português")), "pt", surround = false))
    }

    @Test
    fun `surround picks the most channels, inside the preferred language`() {
        val tracks = listOf(t("en", channels = 6), t("pt", channels = 2, selected = true), t("pt", channels = 6), t("en", channels = 8))
        assertEquals(2, TrackPick.audio(tracks, "pt", surround = true))
        // Sem a língua no canal, o surround de sempre (a de mais canais).
        assertEquals(3, TrackPick.audio(tracks, "fr", surround = true))
        // Só estéreo na língua preferida: fica essa (não se troca por um 5.1 noutra língua).
        assertNull(TrackPick.audio(listOf(t("en", channels = 6), t("pt", selected = true)), "pt", surround = true))
    }

    @Test
    fun `surround alone keeps the old behaviour`() {
        assertEquals(1, TrackPick.audio(listOf(t("pt", selected = true), t("pt", channels = 6)), null, surround = true))
        assertNull(TrackPick.audio(listOf(t("pt", selected = true), t("en")), null, surround = true))
    }

    @Test
    fun `unsupported tracks are never picked`() {
        assertNull(TrackPick.audio(listOf(t("en", selected = true), t("pt", supported = false)), "pt", surround = false))
        assertNull(TrackPick.audio(emptyList(), "pt", surround = true))
    }

    @Test
    fun `subtitles in the preferred language, full before forced`() {
        val tracks = listOf(t("en"), t("pt", forced = true), t("por"))
        assertEquals(2, TrackPick.text(tracks, "pt"))
        assertEquals(1, TrackPick.text(listOf(t("en"), t("pt", forced = true)), "pt"))
        assertNull(TrackPick.text(tracks, "fr"))
        // Sem língua escolhida (desligadas) não se mexe: o player mostra só as que o canal marca.
        assertNull(TrackPick.text(tracks, null))
    }

    @Test
    fun `track choice keeps a manual pick until the channel changes`() {
        val c = TrackChoice()
        c.onOpen("a")
        assertTrue(c.pending(C.TRACK_TYPE_AUDIO))
        c.done(C.TRACK_TYPE_AUDIO)
        assertFalse(c.pending(C.TRACK_TYPE_AUDIO))
        assertTrue(c.pending(C.TRACK_TYPE_TEXT))
        c.onManual(C.TRACK_TYPE_TEXT)
        // O mesmo canal outra vez (catch-up, salto): o áudio volta a escolher-se, as legendas à mão ficam.
        c.onOpen("a")
        assertTrue(c.pending(C.TRACK_TYPE_AUDIO))
        assertFalse(c.pending(C.TRACK_TYPE_TEXT))
        assertTrue(c.isManual(C.TRACK_TYPE_TEXT))
        c.onOpen("b")
        assertTrue(c.pending(C.TRACK_TYPE_TEXT))
        assertFalse(c.isManual(C.TRACK_TYPE_TEXT))
    }
}
