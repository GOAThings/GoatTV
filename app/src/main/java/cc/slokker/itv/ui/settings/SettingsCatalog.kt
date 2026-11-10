package cc.slokker.itv.ui.settings

import androidx.compose.ui.graphics.Brush
import cc.slokker.itv.data.Languages
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.RefreshPolicy
import cc.slokker.itv.data.StreamFormat
import cc.slokker.itv.data.recording.RecordingPlan
import cc.slokker.itv.ui.AppViewModel
import cc.slokker.itv.ui.i18n.Strings
import cc.slokker.itv.ui.theme.Accent
import cc.slokker.itv.ui.theme.outlineBrush
import kotlin.math.abs

/**
 * O catálogo das definições: cada uma uma vez, com o título, as opções (e os textos delas) e onde aparece na TV e
 * no telemóvel. As Definições da TV (SettingsScreen) e do telemóvel (MobileSettings) desenham estas linhas cada uma
 * à sua maneira; o desenho e a arrumação das páginas continuam a ser de cada plataforma.
 *
 * Uma definição nova é uma entrada em [ALL]: com [Setting.tv] e/ou [Setting.phone] aparece nessa página, na
 * secção e na posição ([Place.order]) indicadas. Sem um deles, é só da outra plataforma (e fica escrito aqui).
 * As linhas que não são um valor a escolher (abrir outra página, pedir uma permissão, escrever um texto) são
 * [Setting.Custom]: o catálogo diz onde ficam e cada plataforma desenha-as. Uma plataforma também pode desenhar à
 * sua maneira uma escolha ou um sim/não (ex.: o HDR na TV, a janela pequena só nos telemóveis que a têm).
 */
object SettingsCatalog {

    // ---- Reprodução ----

    val STREAM_FORMAT = Setting.Choice(
        "format", { it.streamFormat },
        options = { s -> listOf(null to s.auto, StreamFormat.TS to "TS (MPEG-TS)", StreamFormat.HLS to "HLS (m3u8)") },
        read = { it.streamFormat },
        write = Write.STREAM_FORMAT,
        tv = Place(TvPage.PLAYBACK, 10),
        phone = Place(PhonePage.PLAYBACK, 10, PhoneSection.ADVANCED),
    )
    val BUFFER = Setting.Choice(
        Prefs.Key.BUFFER, { it.buffer },
        options = { s ->
            listOf(
                Prefs.Buffer.LIVE to s.bufferLive,
                Prefs.Buffer.SMALL to s.bufferSmall,
                Prefs.Buffer.NORMAL to s.bufferNormal,
                Prefs.Buffer.LARGE to s.bufferLarge,
            )
        },
        read = { it.prefs.buffer },
        tv = Place(TvPage.PLAYBACK, 20),
        phone = Place(PhonePage.PLAYBACK, 10, PhoneSection.CONNECTION),
    )
    val ENGINE = Setting.Choice(
        Prefs.Key.ENGINE, { it.engine },
        options = { s -> listOf(Prefs.Engine.EXO to s.engineExo, Prefs.Engine.VLC to s.engineVlc) },
        read = { it.prefs.engine },
        tv = Place(TvPage.PLAYBACK, 30),
        phone = Place(PhonePage.PLAYBACK, 20, PhoneSection.ADVANCED),
    )
    val DECODER = Setting.Choice(
        Prefs.Key.DECODER, { it.decoder },
        options = ::decoders,
        read = { it.prefs.decoder },
        tv = Place(TvPage.PLAYBACK, 40),
        phone = Place(PhonePage.PLAYBACK, 30, PhoneSection.ADVANCED),
    )
    val DEINTERLACE = Setting.Choice(
        Prefs.Key.DEINTERLACE, { it.deinterlace },
        options = { s -> listOf(Prefs.Deinterlace.SMOOTH to s.deinterlaceSmooth, Prefs.Deinterlace.NORMAL to s.deinterlaceNormal, Prefs.Deinterlace.OFF to s.off) },
        read = { it.prefs.deinterlace },
        subtitle = { it.deinterlaceSub },
        tv = Place(TvPage.PLAYBACK, 45),
        phone = Place(PhonePage.PLAYBACK, 35, PhoneSection.ADVANCED),
    )
    val ASPECT = Setting.Choice(
        Prefs.Key.ASPECT, { it.aspect },
        options = { s -> listOf(Prefs.Aspect.FIT to s.aspectFit, Prefs.Aspect.ZOOM to s.aspectZoom, Prefs.Aspect.FILL to s.aspectFill) },
        read = { it.prefs.aspect },
        tv = Place(TvPage.PLAYBACK, 50),
        phone = Place(PhonePage.PLAYBACK, 10, PhoneSection.PICTURE_SOUND),
    )
    val FREEZE_FRAME = Setting.Toggle(
        Prefs.Key.FREEZE_FRAME, { it.freezeFrame }, { it.freezeFrameSub },
        read = { it.freezeFrame },
        tv = Place(TvPage.PLAYBACK, 60),
    )
    val TIMESHIFT = Setting.Toggle(
        Prefs.Key.TIMESHIFT, { it.timeshift }, { it.timeshiftSub },
        read = { it.timeshift },
        tv = Place(TvPage.PLAYBACK, 70),
        phone = Place(PhonePage.PLAYBACK, 30, PhoneSection.CONNECTION),
    )
    val PROTECT_PAUSED = Setting.Toggle(
        Prefs.Key.PROTECT_PAUSED, { it.protectPaused }, { it.protectPausedSub },
        read = { it.protectPaused },
        tv = Place(TvPage.PLAYBACK, 75),
        phone = Place(PhonePage.PLAYBACK, 35, PhoneSection.CONNECTION),
    )
    val RECONNECT = Setting.Choice(
        Prefs.Key.RECONNECT_SECONDS, { it.reconnect },
        options = { s -> listOf(0 to s.no) + listOf(2, 5, 10, 30).map { it to s.afterSeconds(it) } },
        read = { it.prefs.reconnectSeconds },
        tv = Place(TvPage.PLAYBACK, 80),
        phone = Place(PhonePage.PLAYBACK, 20, PhoneSection.CONNECTION),
    )
    /** Era um sim/não: a chave "afr" continua a dizer se está ligado, o modo vai ao lado (ver Prefs.afrModeValues). */
    val AFR = Setting.Choice(
        Prefs.Key.AFR, { it.afr },
        options = { s -> listOf(Prefs.AfrMode.OFF to s.off, Prefs.AfrMode.START to s.afrOnStart, Prefs.AfrMode.ALWAYS to s.afrAlways) },
        read = { it.prefs.afrMode },
        write = Write.AFR_MODE,
        subtitle = { it.afrSub },
        tv = Place(TvPage.PLAYBACK, 90),
    )
    val AFR_FALLBACK = Setting.Choice(
        Prefs.Key.AFR_FALLBACK, { it.afrFallback },
        options = { s -> listOf(Prefs.AfrFallback.NONE to s.off, Prefs.AfrFallback.HZ50 to "50 Hz", Prefs.AfrFallback.HZ5994 to "59.94 Hz") },
        read = { it.prefs.afrFallback },
        subtitle = { it.afrFallbackSub },
        tv = Place(TvPage.PLAYBACK, 95),
    )
    /** Na TV, o subtítulo diz o HDR que o ecrã aceita (a página da TV acrescenta-o). */
    val HDR = Setting.Choice(
        Prefs.Key.HDR, { it.hdr },
        options = { s -> listOf(Prefs.Hdr.AUTO to s.hdrAuto, Prefs.Hdr.SDR to s.hdrSdr) },
        read = { it.prefs.hdr },
        tv = Place(TvPage.PLAYBACK, 100),
    )
    val PASSTHROUGH = Setting.Toggle(
        Prefs.Key.AUDIO_PASSTHROUGH, { it.passthrough }, { it.passthroughSub },
        read = { it.audioPassthrough },
        tv = Place(TvPage.PLAYBACK, 10, TvSection.AUDIO),
    )
    val SURROUND = Setting.Toggle(
        Prefs.Key.SURROUND_DEFAULT, { it.surroundDefault }, { it.surroundDefaultSub },
        read = { it.surroundDefault },
        tv = Place(TvPage.PLAYBACK, 20, TvSection.AUDIO),
    )
    /** Automático: a língua da app, quando o canal a tem (ver ChannelPlayback). */
    val AUDIO_LANGUAGE = Setting.Choice(
        Prefs.Key.AUDIO_LANGUAGE, { it.audioLanguage },
        options = { s -> listOf<Pair<Any?, String>>("" to s.auto) + languages(s) },
        read = { it.prefs.audioLanguage },
        subtitle = { it.audioLanguageSub },
        tv = Place(TvPage.PLAYBACK, 5, TvSection.AUDIO),
        phone = Place(PhonePage.PLAYBACK, 30, PhoneSection.PICTURE_SOUND),
    )
    val SUBTITLE_LANGUAGE = Setting.Choice(
        Prefs.Key.SUBTITLE_LANGUAGE, { it.subtitleLanguage },
        options = { s -> listOf<Pair<Any?, String>>("" to s.subtitlesOff) + languages(s) },
        read = { it.prefs.subtitleLanguage },
        subtitle = { it.subtitleLanguageSub },
        tv = Place(TvPage.PLAYBACK, 10, TvSection.SUBTITLES),
        phone = Place(PhonePage.PLAYBACK, 40, PhoneSection.PICTURE_SOUND),
    )
    // ---- Gravações ----

    /** As margens das gravações (o "gravar agora" e as marcadas): antes do início e depois do fim do guia. */
    val RECORD_PADDING_BEFORE = Setting.Choice(
        Prefs.Key.RECORD_PADDING_BEFORE, { it.recordingPaddingBefore },
        options = ::paddingOptions,
        read = { it.prefs.recordPaddingBeforeMin },
        subtitle = { it.recordingPaddingSub },
        tv = Place(TvPage.PLAYBACK, 10, TvSection.RECORDINGS),
        phone = Place(PhonePage.PLAYBACK, 10, PhoneSection.RECORDINGS),
    )
    val RECORD_PADDING_AFTER = Setting.Choice(
        Prefs.Key.RECORD_PADDING_AFTER, { it.recordingPaddingAfter },
        options = ::paddingOptions,
        read = { it.prefs.recordPaddingAfterMin },
        subtitle = { it.recordingPaddingSub },
        tv = Place(TvPage.PLAYBACK, 20, TvSection.RECORDINGS),
        phone = Place(PhonePage.PLAYBACK, 20, PhoneSection.RECORDINGS),
    )

    val AUDIO_DECODER = Setting.Choice(
        Prefs.Key.AUDIO_DECODER, { it.audioDecoder },
        options = ::decoders,
        read = { it.prefs.audioDecoder },
        tv = Place(TvPage.PLAYBACK, 30, TvSection.AUDIO),
        phone = Place(PhonePage.PLAYBACK, 40, PhoneSection.ADVANCED),
    )
    /** + atrasa o som, − adianta-o (ver AvSyncVideoRenderer). */
    val AUDIO_DELAY = Setting.Choice(
        Prefs.Key.AUDIO_DELAY, { it.audioDelay },
        options = { s -> AUDIO_DELAYS.map { it to (if (it == 0) s.off else "%+d ms".format(it)) } },
        read = { it.prefs.audioDelayMs },
        subtitle = { it.audioDelaySub },
        tv = Place(TvPage.PLAYBACK, 25, TvSection.AUDIO),
        phone = Place(PhonePage.PLAYBACK, 35, PhoneSection.PICTURE_SOUND),
    )
    /** Telemóvel: só aparece quando o aparelho tem janela pequena (o telemóvel esconde-a sem ela). */
    val PIP = Setting.Toggle(
        Prefs.Key.PIP_ENABLED, { it.pictureInPicture },
        read = { it.pipEnabled },
        phone = Place(PhonePage.PLAYBACK, 20, PhoneSection.PICTURE_SOUND),
    )
    /** Escreve-se num campo de texto (outra página). */
    val USER_AGENT = Setting.Custom(
        Prefs.Key.USER_AGENT,
        tv = Place(TvPage.GENERAL, 30),
        phone = Place(PhonePage.PLAYBACK, 50, PhoneSection.ADVANCED),
    )

    // ---- Geral ----

    /** Pede a permissão de abrir por cima das outras apps: a linha é da TV. */
    val AUTO_START = Setting.Custom(Prefs.Key.AUTO_START, tv = Place(TvPage.GENERAL, 10))
    val EXIT_CONFIRM = Setting.Toggle(
        Prefs.Key.EXIT_CONFIRM, { it.exitConfirm },
        read = { it.exitConfirm },
        tv = Place(TvPage.GENERAL, 20),
    )

    // ---- Mudança de canal ----

    val UP_DOWN = Setting.Choice(
        Prefs.Key.UP_DOWN, { it.upDownKeys },
        options = { s -> listOf(Prefs.UpDown.ZAP to s.upDownZap, Prefs.UpDown.LIST to s.upDownList) },
        read = { it.prefs.upDown },
        tv = Place(TvPage.ZAPPING, 10),
    )
    val REVERSE_ZAP = Setting.Toggle(
        Prefs.Key.REVERSE_ZAP, { it.reverseUpDown }, { it.reverseUpDownSub },
        read = { it.reverseZap },
        tv = Place(TvPage.ZAPPING, 20),
    )
    /** Automático: uma tecla muda já; a passar canais depressa, abre o canal onde se parou (ver ZapPacer). */
    val ZAP_DELAY = Setting.Choice(
        Prefs.Key.ZAP_DELAY, { it.zapDelay },
        options = { s -> listOf(Prefs.ZapDelay.AUTO to s.auto, Prefs.ZapDelay.OFF to s.off) + listOf(1, 2).map { it * 1_000 to s.secondsShort(it) } },
        read = { it.prefs.zapDelayMs },
        subtitle = { it.zapDelaySub },
        tv = Place(TvPage.ZAPPING, 25),
        phone = Place(PhonePage.PLAYBACK, 40, PhoneSection.CONNECTION),
    )
    val INFO_SECONDS = Setting.Choice(
        Prefs.Key.INFO_SECONDS, { it.infoOnZap },
        options = { s -> listOf(0 to s.dontShow) + listOf(3, 4, 6, 10).map { it to s.seconds(it) } },
        read = { it.prefs.infoSeconds },
        tv = Place(TvPage.ZAPPING, 30),
    )
    val STARTUP = Setting.Choice(
        Prefs.Key.STARTUP, { it.onStartup },
        options = { s -> listOf(Prefs.Startup.LAST to s.startupLast, Prefs.Startup.LIST to s.startupList) },
        read = { it.prefs.startup },
        tv = Place(TvPage.ZAPPING, 40),
    )
    /** O arranque do telemóvel: o ecrã inicial ou já o último canal (a TV tem o seu, [STARTUP]). */
    val PHONE_STARTUP = Setting.Choice(
        Prefs.Key.PHONE_STARTUP, { it.onStartup },
        options = { s -> listOf(Prefs.PhoneStartup.HOME to s.homeScreen, Prefs.PhoneStartup.LAST to s.startupLast) },
        read = { it.prefs.phoneStartup },
        phone = Place(PhonePage.CHANNELS, 10),
    )
    val BACK_ACTION = Setting.Choice(
        Prefs.Key.BACK_ACTION, { it.backKey },
        options = { s -> listOf(Prefs.BackAction.GUIDE to s.backOpensGuide, Prefs.BackAction.EXIT to s.backExits) },
        read = { it.prefs.backAction },
        tv = Place(TvPage.ZAPPING, 50),
    )

    // ---- Aspeto ----

    val LANGUAGE = Setting.Choice(
        Prefs.Key.LANGUAGE, { it.language },
        options = { s -> listOf("auto" to s.languageAuto) + Strings.LANGUAGES },
        read = { it.prefs.language },
        tv = Place(TvPage.APPEARANCE, 10, TvSection.LANGUAGE_TEXT),
        phone = Place(PhonePage.APPEARANCE, 10),
    )
    val UI_SCALE = Setting.Choice(
        Prefs.Key.UI_SCALE, { it.uiScale },
        options = { s -> (Prefs.UI_SCALE_MIN..Prefs.UI_SCALE_MAX step 5).map { it to s.percent(it) } },
        read = { it.prefs.uiScale },
        tv = Place(TvPage.APPEARANCE, 20, TvSection.LANGUAGE_TEXT),
    )
    val TEXT_SIZE = Setting.Choice(
        Prefs.Key.TEXT_SIZE, { it.textSize },
        options = { s -> listOf(Prefs.TextSize.SMALL to s.sizeSmall, Prefs.TextSize.NORMAL to s.sizeNormal, Prefs.TextSize.LARGE to s.sizeLarge) },
        read = { it.prefs.textSize },
        tv = Place(TvPage.APPEARANCE, 30, TvSection.LANGUAGE_TEXT),
    )
    val SHOW_NUMBERS = Setting.Toggle(
        Prefs.Key.SHOW_NUMBERS, { it.channelNumbers },
        read = { it.showNumbers },
        tv = Place(TvPage.APPEARANCE, 10, TvSection.CHANNEL_LIST),
        phone = Place(PhonePage.CHANNELS, 30),
    )
    val SHOW_LOGOS = Setting.Toggle(
        Prefs.Key.SHOW_LOGOS, { it.channelLogos },
        read = { it.showLogos },
        tv = Place(TvPage.APPEARANCE, 20, TvSection.CHANNEL_LIST),
        phone = Place(PhonePage.CHANNELS, 40),
    )
    /** Abre a página da origem dos logótipos (no telemóvel, só com conta). */
    val LOGO_SOURCE = Setting.Custom(
        Prefs.Key.LOGO_SOURCE,
        tv = Place(TvPage.APPEARANCE, 30, TvSection.CHANNEL_LIST),
        phone = Place(PhonePage.CHANNELS, 60),
    )
    val SORT = Setting.Choice(
        Prefs.Key.SORT, { it.sortChannels },
        options = { s -> listOf(Prefs.Sort.NUMBER to s.sortNumber, Prefs.Sort.NAME to s.sortName) },
        read = { it.prefs.sort },
        tv = Place(TvPage.APPEARANCE, 40, TvSection.CHANNEL_LIST),
        phone = Place(PhonePage.CHANNELS, 20),
    )
    /** Telemóvel: abre os grupos à vista (na TV é em Listas → a lista → Grupos). */
    val VISIBLE_GROUPS = Setting.Custom("visible_groups", phone = Place(PhonePage.CHANNELS, 70))
    val PANEL_WIDTH = Setting.Choice(
        Prefs.Key.PANEL_WIDTH, { it.panelWidth },
        options = { s -> listOf(300 to s.widthNarrow, 340 to s.widthNormal, 400 to s.widthWide, 460 to s.widthExtraWide) },
        read = { it.prefs.panelWidth },
        tv = Place(TvPage.APPEARANCE, 10, TvSection.PANELS_COLOUR),
    )
    val ACCENT = Setting.Choice(
        Prefs.Key.ACCENT, { it.accent },
        options = { s -> Accent.ALL.map { it.id to s.accentName(it.id) } },
        read = { it.prefs.accent },
        swatches = { Accent.ALL.associate { it.id to it.brush } },
        tv = Place(TvPage.APPEARANCE, 20, TvSection.PANELS_COLOUR),
        phone = Place(PhonePage.APPEARANCE, 20),
    )
    val OUTLINE_COLOR = Setting.Choice(
        Prefs.Key.OUTLINE_COLOR, { it.outlineColor },
        options = { s -> Prefs.OutlineColors.IDS.map { it to s.outlineColorName(it) } },
        read = { it.prefs.outlineColor },
        swatches = { prefs ->
            val accent = Accent.byId(prefs.accent)
            Prefs.OutlineColors.IDS.associate { id: String -> (id as Any?) to outlineBrush(id, accent) }
        },
        tv = Place(TvPage.APPEARANCE, 30, TvSection.PANELS_COLOUR),
    )
    val GLASS_OPACITY = Setting.Choice(
        Prefs.Key.GLASS_OPACITY, { it.glassOpacity },
        options = { s -> (0..100 step 10).map { it to s.percent(it) } },
        read = { it.prefs.glassOpacity },
        tv = Place(TvPage.APPEARANCE, 40, TvSection.PANELS_COLOUR),
    )
    val PANEL_TIMEOUT = Setting.Choice(
        Prefs.Key.PANEL_TIMEOUT, { it.autoClosePanels },
        options = { s -> listOf(10, 15, 30).map { it to s.afterSeconds(it) } + listOf(0 to s.never) },
        read = { it.prefs.panelTimeout },
        tv = Place(TvPage.APPEARANCE, 50, TvSection.PANELS_COLOUR),
    )

    // ---- Guia e listas ----

    val EPG_REFRESH = Setting.Choice(
        Prefs.Key.EPG_REFRESH, { it.epgRefresh },
        options = ::refreshOptions,
        read = { it.prefs.epgRefreshHours },
        tv = Place(TvPage.GUIDE, 10),
        phone = Place(PhonePage.GUIDE, 10),
    )
    /** Na TV também está em Listas → Atualizações (ver SettingsScreen). */
    val PLAYLIST_REFRESH = Setting.Choice(
        Prefs.Key.PLAYLIST_REFRESH, { it.playlistRefresh },
        options = ::refreshOptions,
        read = { it.prefs.playlistRefreshHours },
        phone = Place(PhonePage.GUIDE, 20),
    )
    /** Os textos são diferentes de propósito: "+1 h 30" e "Sem desvio" na TV, "+90 min" e "0" no telemóvel. */
    val EPG_OFFSET = Setting.Choice(
        Prefs.Key.EPG_OFFSET, { it.timeOffset },
        options = { s -> OFFSETS.map { it to offsetLabel(it, s) } },
        phoneOptions = { _ -> OFFSETS.map { m -> m to (if (m == 0) "0" else "%+d min".format(m)) } },
        read = { it.prefs.epgOffsetMin },
        tv = Place(TvPage.GUIDE, 20),
        phone = Place(PhonePage.GUIDE, 30),
    )
    /** Em horas: o fuso ou o relógio errado do fornecedor costuma ser de horas certas. */
    val CATCHUP_OFFSET = Setting.Choice(
        Prefs.Key.CATCHUP_OFFSET, { it.catchupOffset },
        options = { s -> CATCHUP_OFFSETS.map { it to offsetLabel(it, s) } },
        read = { it.prefs.catchupOffsetMin },
        subtitle = { it.catchupOffsetSub },
        tv = Place(TvPage.GUIDE, 25),
        phone = Place(PhonePage.GUIDE, 35),
    )
    val EPG_IN_LIST = Setting.Toggle(
        Prefs.Key.EPG_IN_LIST, { it.nowInList }, { it.nowInListSub },
        read = { it.epgInList },
        tv = Place(TvPage.GUIDE, 30),
        phone = Place(PhonePage.CHANNELS, 50),
    )

    /** Por esta ordem não quer dizer nada: cada página ordena pelas suas posições. */
    val ALL: List<Setting> = listOf(
        STREAM_FORMAT, BUFFER, ENGINE, DECODER, DEINTERLACE, ASPECT, FREEZE_FRAME, TIMESHIFT, PROTECT_PAUSED, RECONNECT, AFR, AFR_FALLBACK, HDR, PASSTHROUGH,
        SURROUND, AUDIO_DELAY, AUDIO_LANGUAGE, SUBTITLE_LANGUAGE, AUDIO_DECODER, PIP, USER_AGENT, AUTO_START, EXIT_CONFIRM, UP_DOWN, REVERSE_ZAP, ZAP_DELAY, INFO_SECONDS, STARTUP, PHONE_STARTUP,
        BACK_ACTION, LANGUAGE, UI_SCALE, TEXT_SIZE, SHOW_NUMBERS, SHOW_LOGOS, LOGO_SOURCE, SORT, VISIBLE_GROUPS, PANEL_WIDTH,
        ACCENT, OUTLINE_COLOR, GLASS_OPACITY, PANEL_TIMEOUT, EPG_REFRESH, PLAYLIST_REFRESH, EPG_OFFSET, CATCHUP_OFFSET,
        EPG_IN_LIST, RECORD_PADDING_BEFORE, RECORD_PADDING_AFTER,
    )

    /** As linhas de uma página da TV, por secção (pela ordem de [TvSection]) e, dentro dela, por [Place.order]. */
    fun tv(page: TvPage): List<Pair<TvSection, List<Setting>>> =
        layout(ALL.mapNotNull { s -> s.tv?.takeIf { it.page == page }?.let { s to it } })

    /** O mesmo para uma página do telemóvel. */
    fun phone(page: PhonePage): List<Pair<PhoneSection, List<Setting>>> =
        layout(ALL.mapNotNull { s -> s.phone?.takeIf { it.page == page }?.let { s to it } })

    private fun <P, S : Enum<S>> layout(placed: List<Pair<Setting, Place<P, S>>>): List<Pair<S, List<Setting>>> =
        placed.groupBy { it.second.section }.toSortedMap(compareBy { it.ordinal })
            .map { (section, rows) -> section to rows.sortedBy { it.second.order }.map { it.first } }

    private val AUDIO_DELAYS = listOf(-300, -200, -150, -100, -50, 0, 50, 100, 150, 200, 300, 400, 500)

    private fun decoders(s: Strings) = listOf<Pair<Any?, String>>(Prefs.Decoder.HARDWARE to s.decoderHw, Prefs.Decoder.SOFTWARE to s.decoderSw)

    private fun paddingOptions(s: Strings) = RecordingPlan.PADDING_OPTIONS.map<Int, Pair<Any?, String>> { it to if (it == 0) s.off else s.minutes(it) }

    private fun refreshOptions(s: Strings) = RefreshPolicy.OPTIONS.map<Int, Pair<Any?, String>> { it to s.refreshLabel(it) }

    private val OFFSETS = (-180..180 step 30).toList()

    private val CATCHUP_OFFSETS = (-12..12).map { it * 60 }

    /** As línguas a escolher, com o nome na língua da app e por ordem alfabética dela. */
    private fun languages(s: Strings): List<Pair<Any?, String>> {
        val collator = java.text.Collator.getInstance(s.locale)
        return Languages.CHOICES.map { it to Languages.displayName(it, s.locale) }.sortedWith(compareBy(collator) { it.second })
    }

    private fun offsetLabel(min: Int, s: Strings): String =
        if (min == 0) s.noOffset else (if (min > 0) "+" else "−") + "${abs(min) / 60} h" + if (abs(min) % 60 != 0) " 30" else ""
}

/** Grava a escolha [value] de [setting] (na conta ou neste aparelho, como as outras definições). */
fun AppViewModel.saveSetting(setting: Setting.Choice, value: Any?) = when (setting.write) {
    Write.PREF -> setPref(setting.key, value)
    Write.STREAM_FORMAT -> setStreamFormat(value as String?)
    Write.AFR_MODE -> setPrefs(Prefs.afrModeValues(value as String))
}

/** O que as definições leem: as Prefs e o formato preferido (que não está nas Prefs, ver ProfileKeys). */
class SettingValues(val prefs: Prefs, val streamFormat: String?)

/** Como se grava uma escolha: numa chave das Prefs, como formato preferido, ou o modo do AFR (duas chaves). */
enum class Write { PREF, STREAM_FORMAT, AFR_MODE }

/** As páginas da TV onde há linhas do catálogo. */
enum class TvPage { PLAYBACK, GENERAL, ZAPPING, APPEARANCE, GUIDE }

/** As secções de uma página da TV, por esta ordem; [MAIN] não tem título. */
enum class TvSection(val headerKey: String?, val title: ((Strings) -> String)?) {
    MAIN(null, null),
    AUDIO("audio", { it.audioSection }),
    SUBTITLES("hsubs", { it.subtitles }),
    LANGUAGE_TEXT("hlang", { it.sectionLanguageText }),
    CHANNEL_LIST("hlist", { it.sectionChannelList }),
    PANELS_COLOUR("hpanels", { it.sectionPanelsColour }),
    RECORDINGS("hrec", { it.recordings }),
}

/** As páginas das Definições do telemóvel onde há linhas do catálogo. */
enum class PhonePage { PLAYBACK, CHANNELS, GUIDE, APPEARANCE }

/** As secções de uma página do telemóvel, por esta ordem; [MAIN] não tem título. */
enum class PhoneSection(val title: ((Strings) -> String)?) {
    MAIN(null),
    PICTURE_SOUND({ it.settingsPictureSound }),
    CONNECTION({ it.settingsConnection }),
    ADVANCED({ it.settingsAdvanced }),
    RECORDINGS({ it.recordings }),
}

/** Onde uma linha aparece: a página, a secção e a posição dentro dela (as posições vão de 10 em 10). */
class Place<P, S : Enum<S>>(val page: P, val order: Int, val section: S) {
    override fun toString() = "$page/$section/$order"
}

@Suppress("FunctionName")
fun Place(page: TvPage, order: Int, section: TvSection = TvSection.MAIN) = Place<TvPage, TvSection>(page, order, section)

@Suppress("FunctionName")
fun Place(page: PhonePage, order: Int, section: PhoneSection = PhoneSection.MAIN) = Place<PhonePage, PhoneSection>(page, order, section)

/** Uma definição do catálogo (ver [SettingsCatalog]). [key] é a chave das Prefs (ou "format"); serve também de chave da linha. */
sealed class Setting(
    val key: String,
    val tv: Place<TvPage, TvSection>?,
    val phone: Place<PhonePage, PhoneSection>?,
) {
    /** Uma escolha entre opções. [phoneOptions]: textos só do telemóvel, quando são outros de propósito. */
    class Choice(
        key: String,
        val title: (Strings) -> String,
        private val options: (Strings) -> List<Pair<Any?, String>>,
        val read: (SettingValues) -> Any?,
        private val phoneOptions: ((Strings) -> List<Pair<Any?, String>>)? = null,
        val write: Write = Write.PREF,
        /** A explicação por baixo do título (na TV). */
        val subtitle: ((Strings) -> String)? = null,
        /** Amostras de cor por opção (na TV, ao escolher). */
        val swatches: ((Prefs) -> Map<Any?, Brush>)? = null,
        tv: Place<TvPage, TvSection>? = null,
        phone: Place<PhonePage, PhoneSection>? = null,
    ) : Setting(key, tv, phone) {
        fun options(s: Strings): List<Pair<Any?, String>> = options.invoke(s)
        fun phoneOptions(s: Strings): List<Pair<Any?, String>> = phoneOptions?.invoke(s) ?: options(s)

        /** O texto da opção escolhida (vazio se o valor não estiver nas opções). */
        fun label(s: Strings, values: SettingValues, phone: Boolean = false): String {
            val value = read(values)
            return (if (phone) phoneOptions(s) else options(s)).firstOrNull { it.first == value }?.second.orEmpty()
        }
    }

    /** Sim ou não. [subtitle]: a explicação por baixo (na TV). */
    class Toggle(
        key: String,
        val title: (Strings) -> String,
        val subtitle: ((Strings) -> String)? = null,
        val read: (Prefs) -> Boolean,
        tv: Place<TvPage, TvSection>? = null,
        phone: Place<PhonePage, PhoneSection>? = null,
    ) : Setting(key, tv, phone)

    /** Uma linha que cada plataforma desenha à sua maneira, no sítio que o catálogo diz. */
    class Custom(
        key: String,
        tv: Place<TvPage, TvSection>? = null,
        phone: Place<PhonePage, PhoneSection>? = null,
    ) : Setting(key, tv, phone)

    override fun toString() = key
}
