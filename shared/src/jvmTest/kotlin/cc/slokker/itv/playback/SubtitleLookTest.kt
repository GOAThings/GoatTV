package cc.slokker.itv.playback

import cc.slokker.itv.data.AppleBridge
import cc.slokker.itv.data.Prefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleLookTest {
    private val size = Prefs.SubtitleSize
    private val style = Prefs.SubtitleStyle
    private val position = Prefs.SubtitlePosition

    @Test
    fun defaultsKeepTheOldLook() {
        val p = Prefs()
        assertEquals(size.NORMAL, p.subtitleSize)
        assertEquals(style.WHITE_BOX, p.subtitleStyle)
        assertEquals(position.NORMAL, p.subtitlePosition)
        assertEquals(1f, SubtitleLook.scale(p.subtitleSize))
        assertEquals(0.08f, SubtitleLook.gap(p.subtitlePosition))
        assertTrue(SubtitleLook.boxed(p.subtitleStyle))
        assertEquals(SubtitleLook.WHITE, SubtitleLook.textColor(p.subtitleStyle))
    }

    @Test
    fun keysAreReadAndUnknownValuesFallBack() {
        val s = JsonObject(
            mapOf(
                "subtitle_size" to JsonPrimitive("extra_large"),
                "subtitle_style" to JsonPrimitive("yellow_shadow"),
                "subtitle_position" to JsonPrimitive("raised"),
            ),
        )
        val p = Prefs.from(s)
        assertEquals(size.EXTRA_LARGE, p.subtitleSize)
        assertEquals(style.YELLOW_SHADOW, p.subtitleStyle)
        assertEquals(position.RAISED, p.subtitlePosition)
        val bad = Prefs.from(JsonObject(mapOf("subtitle_size" to JsonPrimitive("huge"), "subtitle_style" to JsonPrimitive(3))))
        assertEquals(size.NORMAL, bad.subtitleSize)
        assertEquals(style.WHITE_BOX, bad.subtitleStyle)
    }

    @Test
    fun sizesGrowInOrder() {
        val scales = listOf(size.SMALL, size.NORMAL, size.LARGE, size.EXTRA_LARGE).map(SubtitleLook::scale)
        assertEquals(scales.sorted(), scales)
        assertEquals(4, scales.toSet().size)
        assertEquals(1f, SubtitleLook.scale("nonsense"))
    }

    @Test
    fun stylesMapToColourAndBox() {
        assertFalse(SubtitleLook.boxed(style.WHITE_SHADOW))
        assertTrue(SubtitleLook.boxed(style.WHITE_BOX))
        assertFalse(SubtitleLook.boxed(style.YELLOW_SHADOW))
        assertTrue(SubtitleLook.boxed(style.YELLOW_BOX))
        assertEquals(SubtitleLook.WHITE, SubtitleLook.textColor(style.WHITE_SHADOW))
        assertEquals(SubtitleLook.YELLOW, SubtitleLook.textColor(style.YELLOW_SHADOW))
        assertEquals(SubtitleLook.YELLOW, SubtitleLook.textColor(style.YELLOW_BOX))
    }

    @Test
    fun raisedIsHigher() {
        assertTrue(SubtitleLook.gap(position.RAISED) > SubtitleLook.gap(position.NORMAL))
    }

    @Test
    fun bitmapsStayPutByDefault() {
        val r = SubtitleLook.place(0.1234f, 0.8321f, 0.5f, 0.07f, size.NORMAL, position.NORMAL)
        assertEquals(SubtitleRect(0.1234f, 0.8321f, 0.5f, 0.07f), r)
    }

    @Test
    fun bitmapsGrowFromTheBottomCentre() {
        // Uma legenda centrada em baixo: cresce para cima e para os lados, o centro fica.
        val r = SubtitleLook.place(0.3f, 0.85f, 0.4f, 0.1f, size.EXTRA_LARGE, position.NORMAL)
        assertEquals(0.64f, r.width, 1e-5f)
        assertEquals(0.16f, r.height, 1e-5f)
        assertEquals(0.5f, r.x + r.width / 2, 1e-5f)
        assertEquals(1f + (0.85f - 1f) * 1.6f, r.y, 1e-5f)
        // Duas linhas (duas imagens) continuam uma em cima da outra, sem se sobreporem.
        val top = SubtitleLook.place(0.3f, 0.75f, 0.4f, 0.1f, size.EXTRA_LARGE, position.NORMAL)
        assertEquals(r.y, top.y + top.height, 1e-5f)
    }

    @Test
    fun bitmapsAtTheTopGrowDownAndAreNotRaised() {
        val r = SubtitleLook.place(0.3f, 0.05f, 0.4f, 0.1f, size.LARGE, position.RAISED)
        assertEquals(0.05f * 1.3f, r.y, 1e-5f)
    }

    @Test
    fun raisedLiftsBottomBitmapsAndNeverLeavesTheVideo() {
        val r = SubtitleLook.place(0.3f, 0.85f, 0.4f, 0.1f, size.NORMAL, position.RAISED)
        assertEquals(0.85f - (SubtitleLook.RAISED_GAP - SubtitleLook.NORMAL_GAP), r.y, 1e-5f)
        val wide = SubtitleLook.place(0f, 0.9f, 1f, 0.1f, size.EXTRA_LARGE, position.NORMAL)
        assertTrue(wide.x >= 0f && wide.y >= 0f && wide.y + wide.height <= 1f + 1e-5f)
    }

    @Test
    fun bridgeMatches() {
        assertEquals(1.6, AppleBridge.subtitleScale(size.EXTRA_LARGE), 1e-6)
        assertEquals(SubtitleLook.RAISED_GAP.toDouble(), AppleBridge.subtitleGap(position.RAISED), 1e-6)
        assertTrue(AppleBridge.subtitleBoxed(style.YELLOW_BOX))
        assertEquals(SubtitleLook.YELLOW, AppleBridge.subtitleTextColor(style.YELLOW_BOX))
        assertEquals(SubtitleRect(0.25f, 0.5f, 0.5f, 0.25f), AppleBridge.subtitlePlace(0.25, 0.5, 0.5, 0.25, size.NORMAL, position.NORMAL))
    }
}
