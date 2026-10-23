package cc.slokker.itv.data

import cc.slokker.itv.ui.i18n.Strings
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * Definições do player, guardadas no `settings` da conta (iguais em todas as TVs).
 * Cada campo tem a chave JSON indicada em [Key]; valores em falta ficam no padrão.
 */
data class Prefs(
    // Reprodução
    /** O player: o ExoPlayer (o normal) ou o VLC, para streams que o ExoPlayer não abra. */
    val engine: String = Engine.EXO,
    val buffer: String = Buffer.NORMAL,
    val decoder: String = Decoder.HARDWARE,
    val aspect: String = Aspect.FIT,
    val freezeFrame: Boolean = true,
    /** Segundos até voltar a ligar quando a stream falha (0 = não tentar). */
    val reconnectSeconds: Int = 5,
    /** Auto frame rate: a TV muda de refresh rate para a cadência do canal. */
    val afr: Boolean = false,
    /** Passthrough de áudio (Dolby/DTS para o recetor ou a TV). */
    val audioPassthrough: Boolean = true,
    val surroundDefault: Boolean = false,
    val audioDecoder: String = Decoder.HARDWARE,
    /** Canais HDR: em HDR quando a TV aceita, ou sempre convertidos para SDR (ver HdrType). */
    val hdr: String = Hdr.AUTO,
    // Geral
    /** User-Agent para os servidores IPTV; vazio = o predefinido. */
    val userAgent: String = "",
    val autoStart: Boolean = false,
    val exitConfirm: Boolean = true,
    /** Paleta da cor de destaque (ver Accent). */
    val accent: String = "itv",
    /** Opacidade do vidro, em % (0 = só o vídeo desfocado, 100 = opaco). */
    val glassOpacity: Int = 60,
    /** Mostrar "★ Favoritos" e "Todos" na lista de grupos (para todas as listas). */
    val showFavoritesGroup: Boolean = true,
    val showAllGroup: Boolean = true,
    /** Grupo "Recentes" (últimos canais vistos). */
    val showRecentGroup: Boolean = true,
    /** Grupos escondidos: "playlistId:groupId". */
    val hiddenGroups: Set<String> = emptySet(),
    /** Ordem dos grupos por lista: playlistId → groupIds. */
    val groupOrder: Map<String, List<String>> = emptyMap(),
    /** Canais escondidos: "playlistId:channelKey". */
    val hiddenChannels: Set<String> = emptySet(),
    /** Ordem dos canais por grupo: "playlistId:groupId" → channelKeys. */
    val channelOrder: Map<String, List<String>> = emptyMap(),
    // Mudança de canal
    val upDown: String = UpDown.ZAP,
    val reverseZap: Boolean = false,
    val infoSeconds: Int = 4,
    val startup: String = Startup.LAST,
    /** Voltar no player, com tudo fechado: abre o guia ou sai da app. */
    val backAction: String = BackAction.GUIDE,
    // Aspeto
    val showNumbers: Boolean = true,
    val showLogos: Boolean = true,
    val panelTimeout: Int = 15,
    val sort: String = Sort.NUMBER,
    /** Escala de toda a interface, em % (100 = normal). */
    val uiScale: Int = 100,
    /** Largura dos painéis laterais, em dp. */
    val panelWidth: Int = 340,
    val textSize: String = TextSize.NORMAL,
    /** "auto" (sistema), "pt" ou "en". */
    val language: String = "auto",
    /** De quantas em quantas horas voltar a descarregar os canais (0 = sempre que se abre a app). */
    val playlistRefreshHours: Int = 0,
    // Guia
    /** Igual, para o guia XMLTV. */
    val epgRefreshHours: Int = 12,
    val epgOffsetMin: Int = 0,
    val epgInList: Boolean = true,
    // Controlo parental
    val pinHash: String? = null,
    /** "playlistId:groupId". */
    val lockedGroups: Set<String> = emptySet(),
    /** As Definições também pedem o PIN. */
    val settingsPin: Boolean = false,
) {
    fun isLocked(playlistId: String, groupId: String) = pinHash != null && "$playlistId:$groupId" in lockedGroups

    fun isHidden(playlistId: String, groupId: String) = "$playlistId:$groupId" in hiddenGroups

    fun isChannelHidden(playlistId: String, channelKey: String) = "$playlistId:$channelKey" in hiddenChannels

    object Key {
        const val ENGINE = "engine"
        const val BUFFER = "buffer"
        const val DECODER = "decoder"
        const val ASPECT = "aspect"
        const val FREEZE_FRAME = "freeze_frame"
        const val RECONNECT_SECONDS = "reconnect_seconds"
        const val AFR = "afr"
        const val AUDIO_PASSTHROUGH = "audio_passthrough"
        const val SURROUND_DEFAULT = "surround_default"
        const val AUDIO_DECODER = "audio_decoder"
        const val HDR = "hdr"
        const val USER_AGENT = "user_agent"
        const val AUTO_START = "auto_start"
        const val EXIT_CONFIRM = "exit_confirm"
        const val GLASS_OPACITY = "glass_opacity"
        const val ACCENT = "accent"
        const val HIDDEN_GROUPS = "hidden_groups"
        const val SHOW_FAVORITES_GROUP = "show_favorites_group"
        const val SHOW_ALL_GROUP = "show_all_group"
        const val SHOW_RECENT_GROUP = "show_recent_group"
        const val BACK_ACTION = "back_action"
        const val SETTINGS_PIN = "settings_pin"
        const val GROUP_ORDER = "group_order"
        const val HIDDEN_CHANNELS = "hidden_channels"
        const val CHANNEL_ORDER = "channel_order"
        const val UP_DOWN = "up_down"
        const val REVERSE_ZAP = "reverse_zap"
        const val INFO_SECONDS = "info_seconds"
        const val STARTUP = "startup"
        const val SHOW_NUMBERS = "show_numbers"
        const val SHOW_LOGOS = "show_logos"
        const val PANEL_TIMEOUT = "panel_timeout"
        const val SORT = "sort"
        const val PANEL_WIDTH = "panel_width"
        const val UI_SCALE = "ui_scale"
        const val TEXT_SIZE = "text_size"
        const val LANGUAGE = "language"
        const val PLAYLIST_REFRESH = "playlist_refresh_hours"
        const val EPG_REFRESH = "epg_refresh_hours"
        const val EPG_OFFSET = "epg_offset_min"
        const val EPG_IN_LIST = "epg_in_list"
        const val PIN_HASH = "parental_pin_sha256"
        const val LOCKED_GROUPS = "locked_groups"
    }

    object Buffer { const val LIVE = "live"; const val SMALL = "small"; const val NORMAL = "normal"; const val LARGE = "large" }
    object Decoder { const val HARDWARE = "hw"; const val SOFTWARE = "sw" }
    object Engine { const val EXO = "exo"; const val VLC = "vlc" }
    object Hdr { const val AUTO = "auto"; const val SDR = "sdr" }
    object Aspect { const val FIT = "fit"; const val ZOOM = "zoom"; const val FILL = "fill" }
    object UpDown { const val ZAP = "zap"; const val LIST = "list" }
    object Startup { const val LAST = "last"; const val LIST = "list" }
    object BackAction { const val GUIDE = "guide"; const val EXIT = "exit" }
    object Sort { const val NUMBER = "number"; const val NAME = "name" }
    object TextSize {
        const val SMALL = "small"; const val NORMAL = "normal"; const val LARGE = "large"

        fun scale(size: String) = when (size) { SMALL -> 0.9f; LARGE -> 1.15f; else -> 1f }
    }

    val textScale: Float get() = TextSize.scale(textSize)

    companion object {
        fun from(s: JsonObject): Prefs {
            val d = Prefs()
            fun str(k: String, def: String, allowed: Set<String>) =
                s.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it in allowed } ?: def
            fun bool(k: String, def: Boolean) =
                s.get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: def
            fun int(k: String, def: Int) =
                s.get(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() } ?: def
            return Prefs(
                engine = str(Key.ENGINE, d.engine, setOf(Engine.EXO, Engine.VLC)),
                buffer = str(Key.BUFFER, d.buffer, setOf(Buffer.LIVE, Buffer.SMALL, Buffer.NORMAL, Buffer.LARGE)),
                decoder = str(Key.DECODER, d.decoder, setOf(Decoder.HARDWARE, Decoder.SOFTWARE)),
                aspect = str(Key.ASPECT, d.aspect, setOf(Aspect.FIT, Aspect.ZOOM, Aspect.FILL)),
                freezeFrame = bool(Key.FREEZE_FRAME, d.freezeFrame),
                reconnectSeconds = int(Key.RECONNECT_SECONDS, d.reconnectSeconds).coerceIn(0, 120),
                afr = bool(Key.AFR, d.afr),
                audioPassthrough = bool(Key.AUDIO_PASSTHROUGH, d.audioPassthrough),
                surroundDefault = bool(Key.SURROUND_DEFAULT, d.surroundDefault),
                audioDecoder = str(Key.AUDIO_DECODER, d.audioDecoder, setOf(Decoder.HARDWARE, Decoder.SOFTWARE)),
                hdr = str(Key.HDR, d.hdr, setOf(Hdr.AUTO, Hdr.SDR)),
                userAgent = s.get(Key.USER_AGENT)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty(),
                autoStart = bool(Key.AUTO_START, d.autoStart),
                exitConfirm = bool(Key.EXIT_CONFIRM, d.exitConfirm),
                glassOpacity = int(Key.GLASS_OPACITY, d.glassOpacity).coerceIn(0, 100),
                accent = str(Key.ACCENT, d.accent, setOf("itv", "violet", "mint", "sunset", "gold", "white")),
                showFavoritesGroup = bool(Key.SHOW_FAVORITES_GROUP, d.showFavoritesGroup),
                showAllGroup = bool(Key.SHOW_ALL_GROUP, d.showAllGroup),
                showRecentGroup = bool(Key.SHOW_RECENT_GROUP, d.showRecentGroup),
                backAction = str(Key.BACK_ACTION, d.backAction, setOf(BackAction.GUIDE, BackAction.EXIT)),
                settingsPin = bool(Key.SETTINGS_PIN, d.settingsPin),
                hiddenGroups = stringSet(s, Key.HIDDEN_GROUPS),
                groupOrder = stringListMap(s, Key.GROUP_ORDER),
                hiddenChannels = stringSet(s, Key.HIDDEN_CHANNELS),
                channelOrder = stringListMap(s, Key.CHANNEL_ORDER),
                upDown = str(Key.UP_DOWN, d.upDown, setOf(UpDown.ZAP, UpDown.LIST)),
                reverseZap = bool(Key.REVERSE_ZAP, d.reverseZap),
                infoSeconds = int(Key.INFO_SECONDS, d.infoSeconds).coerceIn(0, 30),
                startup = str(Key.STARTUP, d.startup, setOf(Startup.LAST, Startup.LIST)),
                showNumbers = bool(Key.SHOW_NUMBERS, d.showNumbers),
                showLogos = bool(Key.SHOW_LOGOS, d.showLogos),
                panelTimeout = int(Key.PANEL_TIMEOUT, d.panelTimeout).coerceIn(0, 300),
                sort = str(Key.SORT, d.sort, setOf(Sort.NUMBER, Sort.NAME)),
                panelWidth = int(Key.PANEL_WIDTH, d.panelWidth).coerceIn(260, 520),
                uiScale = int(Key.UI_SCALE, d.uiScale).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                textSize = str(Key.TEXT_SIZE, d.textSize, setOf(TextSize.SMALL, TextSize.NORMAL, TextSize.LARGE)),
                language = Strings.normalize(s.get(Key.LANGUAGE)?.takeIf { it.isJsonPrimitive }?.asString),
                playlistRefreshHours = int(Key.PLAYLIST_REFRESH, d.playlistRefreshHours).takeIf { it in RefreshPolicy.OPTIONS } ?: d.playlistRefreshHours,
                epgRefreshHours = int(Key.EPG_REFRESH, d.epgRefreshHours).takeIf { it in RefreshPolicy.OPTIONS } ?: d.epgRefreshHours,
                epgOffsetMin = int(Key.EPG_OFFSET, d.epgOffsetMin).coerceIn(-12 * 60, 12 * 60),
                epgInList = bool(Key.EPG_IN_LIST, d.epgInList),
                pinHash = s.get(Key.PIN_HASH)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.length == 64 },
                lockedGroups = stringSet(s, Key.LOCKED_GROUPS),
            )
        }

        private fun stringListMap(s: JsonObject, key: String): Map<String, List<String>> =
            s.get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                ?.associate { (k, v) -> k to (if (v.isJsonArray) v.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString } else emptyList()) }
                .orEmpty()

        private fun stringSet(s: JsonObject, key: String): Set<String> =
            s.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }?.toSet().orEmpty()

        const val UI_SCALE_MIN = 80
        const val UI_SCALE_MAX = 120

        /** O PIN nunca é guardado em claro, nem na conta. */
        fun hashPin(pin: String): String =
            // "i-tv:" é o sal de sempre (do nome antigo): mudá-lo invalidava os PINs já gravados na conta.
            MessageDigest.getInstance("SHA-256").digest("i-tv:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
