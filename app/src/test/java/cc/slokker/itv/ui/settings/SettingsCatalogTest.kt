package cc.slokker.itv.ui.settings

import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.state.TestStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCatalogTest {
    private val s: Strings = TestStrings.install()
    private val defaults = SettingValues(Prefs(), null)

    private fun tv(page: TvPage) = SettingsCatalog.tv(page).map { (section, rows) -> section to rows.map { it.key } }
    private fun phone(page: PhonePage) = SettingsCatalog.phone(page).map { (section, rows) -> section to rows.map { it.key } }

    @Test
    fun `every setting is listed once and has a place`() {
        val all = SettingsCatalog.ALL
        assertEquals(all.size, all.toSet().size)
        all.forEach { assertTrue("$it não aparece em nenhuma plataforma", it.tv != null || it.phone != null) }
    }

    @Test
    fun `no two rows share a position on the same page`() {
        val tv = SettingsCatalog.ALL.mapNotNull { st -> st.tv?.let { Triple(it.page, it.section, it.order) to st } }
        val phone = SettingsCatalog.ALL.mapNotNull { st -> st.phone?.let { Triple(it.page, it.section, it.order) to st } }
        for (placed in listOf(tv, phone)) {
            val clash = placed.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            assertEquals("posições repetidas: $clash", emptyMap<Any, Any>(), clash)
        }
    }

    @Test
    fun `keys are real Prefs keys`() {
        val prefKeys = Prefs.Key::class.java.declaredFields.filter { it.type == String::class.java }.map { it.get(null) as String }.toSet()
        // "format" é o formato preferido (fora das Prefs); "visible_groups" é só uma linha que abre outra página.
        val others = setOf(SettingsCatalog.STREAM_FORMAT.key, SettingsCatalog.VISIBLE_GROUPS.key)
        SettingsCatalog.ALL.filter { it.key !in others }.forEach { assertTrue("${it.key} não é uma Prefs.Key", it.key in prefKeys) }
    }

    @Test
    fun `the default value of every choice is one of its options`() {
        SettingsCatalog.ALL.filterIsInstance<Setting.Choice>().forEach { c ->
            val value = c.read(defaults)
            assertTrue("${c.key}: $value não está nas opções", c.options(s).any { it.first == value })
            assertTrue("${c.key}: $value não está nas opções do telemóvel", c.phoneOptions(s).any { it.first == value })
        }
    }

    @Test
    fun `the TV pages keep their rows and order`() {
        val k = Prefs.Key
        assertEquals(
            listOf(
                TvSection.MAIN to listOf("format", k.BUFFER, k.DECODER, k.DEINTERLACE, k.ASPECT, k.FREEZE_FRAME, k.TIMESHIFT, k.PROTECT_PAUSED, k.RECONNECT_SECONDS, k.AFR, k.AFR_FALLBACK, k.HDR),
                TvSection.AUDIO to listOf(k.AUDIO_LANGUAGE, k.AUDIO_PASSTHROUGH, k.SURROUND_DEFAULT, k.AUDIO_DELAY, k.AUDIO_DECODER),
                TvSection.SUBTITLES to listOf(k.SUBTITLE_LANGUAGE),
                TvSection.RECORDINGS to listOf(k.RECORD_PADDING_BEFORE, k.RECORD_PADDING_AFTER),
            ),
            tv(TvPage.PLAYBACK),
        )
        assertEquals(listOf(TvSection.MAIN to listOf(k.AUTO_START, k.EXIT_CONFIRM, k.USER_AGENT)), tv(TvPage.GENERAL))
        assertEquals(listOf(TvSection.MAIN to listOf(k.UP_DOWN, k.REVERSE_ZAP, k.ZAP_DELAY, k.INFO_SECONDS, k.STARTUP, k.BACK_ACTION, k.KEY_RED, k.KEY_GREEN, k.KEY_YELLOW, k.KEY_BLUE)), tv(TvPage.ZAPPING))
        assertEquals(
            listOf(
                TvSection.LANGUAGE_TEXT to listOf(k.LANGUAGE, k.UI_SCALE, k.TEXT_SIZE),
                TvSection.CHANNEL_LIST to listOf(k.SHOW_NUMBERS, k.SHOW_LOGOS, k.LOGO_SOURCE, k.SORT),
                TvSection.PANELS_COLOUR to listOf(k.PANEL_WIDTH, k.ACCENT, k.OUTLINE_COLOR, k.GLASS_OPACITY, k.PANEL_TIMEOUT),
            ),
            tv(TvPage.APPEARANCE),
        )
        assertEquals(listOf(TvSection.MAIN to listOf(k.EPG_REFRESH, k.EPG_OFFSET, k.CATCHUP_OFFSET, k.EPG_IN_LIST, k.EPG_PAST_HOURS, k.EPG_FUTURE_HOURS, k.REMINDER_SWITCH)), tv(TvPage.GUIDE))
    }

    @Test
    fun `the phone pages keep their rows and order`() {
        val k = Prefs.Key
        assertEquals(
            listOf(
                PhoneSection.PICTURE_SOUND to listOf(k.ASPECT, k.PIP_ENABLED, k.AUDIO_LANGUAGE, k.SURROUND_DEFAULT, k.AUDIO_DELAY, k.SUBTITLE_LANGUAGE),
                PhoneSection.CONNECTION to listOf(k.BUFFER, k.RECONNECT_SECONDS, k.TIMESHIFT, k.PROTECT_PAUSED, k.ZAP_DELAY),
                PhoneSection.ADVANCED to listOf("format", k.DECODER, k.DEINTERLACE, k.AUDIO_DECODER, k.USER_AGENT),
                PhoneSection.RECORDINGS to listOf(k.RECORD_PADDING_BEFORE, k.RECORD_PADDING_AFTER),
            ),
            phone(PhonePage.PLAYBACK),
        )
        assertEquals(
            listOf(PhoneSection.MAIN to listOf(k.PHONE_STARTUP, k.SORT, k.SHOW_NUMBERS, k.SHOW_LOGOS, k.EPG_IN_LIST, k.LOGO_SOURCE, "visible_groups")),
            phone(PhonePage.CHANNELS),
        )
        assertEquals(listOf(PhoneSection.MAIN to listOf(k.EPG_REFRESH, k.PLAYLIST_REFRESH, k.EPG_OFFSET, k.CATCHUP_OFFSET, k.EPG_PAST_HOURS, k.EPG_FUTURE_HOURS, k.REMINDER_SWITCH)), phone(PhonePage.GUIDE))
        assertEquals(listOf(PhoneSection.MAIN to listOf(k.LANGUAGE, k.ACCENT)), phone(PhonePage.APPEARANCE))
    }

    @Test
    fun `which platform has each setting is written down`() {
        val tvOnly = SettingsCatalog.ALL.filter { it.phone == null }.map { it.key }.toSet()
        val phoneOnly = SettingsCatalog.ALL.filter { it.tv == null }.map { it.key }.toSet()
        assertTrue(Prefs.Key.AFR in tvOnly && Prefs.Key.AFR_FALLBACK in tvOnly && Prefs.Key.HDR in tvOnly && Prefs.Key.UI_SCALE in tvOnly)
        // O atraso ao mudar de canal vale nos dois (o telemóvel também muda de canal a deslizar).
        assertTrue(SettingsCatalog.ZAP_DELAY.tv != null && SettingsCatalog.ZAP_DELAY.phone != null)
        assertEquals(setOf(Prefs.Key.PIP_ENABLED, Prefs.Key.PHONE_STARTUP, Prefs.Key.PLAYLIST_REFRESH, "visible_groups"), phoneOnly)
    }

    @Test
    fun `labels read the current value and the offset texts differ on purpose`() {
        val values = SettingValues(Prefs(buffer = Prefs.Buffer.LARGE, epgOffsetMin = 90), StreamFormat.HLS)
        assertEquals("HLS (m3u8)", SettingsCatalog.STREAM_FORMAT.label(s, values))
        assertEquals(s.bufferLarge, SettingsCatalog.BUFFER.label(s, values))
        assertEquals("+90 min", SettingsCatalog.EPG_OFFSET.label(s, values, phone = true))
        assertNotEquals(SettingsCatalog.EPG_OFFSET.label(s, values), SettingsCatalog.EPG_OFFSET.label(s, values, phone = true))
        assertEquals(s.auto, SettingsCatalog.STREAM_FORMAT.label(s, defaults))
    }
}
