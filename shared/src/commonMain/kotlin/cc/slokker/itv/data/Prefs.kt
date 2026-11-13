package cc.slokker.itv.data

// As definições (o `settings` da conta ou desta TV), as mesmas no Android e na Apple: o formato é o contrato com o
// site, por isso lê-se e escreve-se aqui, uma vez. O que é só de uma plataforma fica com o padrão na outra.

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

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
    /** Desentrelaçar no VLC (no ExoPlayer é a TV que o faz). */
    val deinterlace: String = Deinterlace.SMOOTH,
    val aspect: String = Aspect.FIT,
    val freezeFrame: Boolean = true,
    /** Segundos até voltar a ligar quando a stream falha (0 = não tentar). */
    val reconnectSeconds: Int = 5,
    /**
     * Auto frame rate: a TV muda de refresh rate para a cadência do canal. É a chave antiga (sim/não), que a Apple
     * TV também lê; o modo ([afrMode]) diz quando muda, e [afrModeValues] grava as duas a condizer.
     */
    val afr: Boolean = false,
    /** Quando o AFR muda o modo da TV ([AfrMode]); desligado sempre que [afr] for false. */
    val afrMode: String = AfrMode.OFF,
    /** A cadência a usar quando a do canal não se consegue medir ([AfrFallback]). */
    val afrFallback: String = AfrFallback.NONE,
    /** Passthrough de áudio (Dolby/DTS para o recetor ou a TV). */
    val audioPassthrough: Boolean = true,
    val surroundDefault: Boolean = false,
    /** A língua do áudio (ISO 639-1, ver Languages); vazio = Automático (a da app, se o canal a tiver). */
    val audioLanguage: String = "",
    /** A língua das legendas (ISO 639-1); vazio = desligadas (menos as que o canal marca como forçadas). */
    val subtitleLanguage: String = "",
    /** Telemóvel: o vídeo continua numa janela pequena ao sair da app. */
    val pipEnabled: Boolean = false,
    val audioDecoder: String = Decoder.HARDWARE,
    /** Atraso do som em ms (sincronia labial): + o som mais tarde, − mais cedo; 0 = nenhum. */
    val audioDelayMs: Int = 0,
    /** Canais HDR: em HDR quando a TV aceita, ou sempre convertidos para SDR (ver HdrType). */
    val hdr: String = Hdr.AUTO,
    /** Velocidade e buffer num canto do vídeo (Definições → Canal atual → Ligação). */
    val netGraph: Boolean = false,
    /** Avisar quando o canal está sempre a parar, com a causa e o que experimentar. */
    val netWarnings: Boolean = true,
    /** Pausar o direto: os últimos minutos do canal guardados na cache, para pausar e recuar (só no ExoPlayer). */
    val timeshift: Boolean = true,
    /** Em pausa ou atrás do direto, mudar de canal pergunta primeiro (o que está guardado perde-se). */
    val protectPaused: Boolean = true,
    // Geral
    /** User-Agent para os servidores IPTV; vazio = o predefinido. */
    val userAgent: String = "",
    /** O formato preferido da conta (`stream_format`): ts, m3u8 ou null (automático). */
    val streamFormat: String? = null,
    val autoStart: Boolean = false,
    val exitConfirm: Boolean = true,
    /** Paleta da cor de destaque (ver Accent). */
    val accent: String = Accents.DEFAULT,
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
    /** O que fazem os botões de cor do comando no player (ver KeyAction). */
    val keyRed: String = KeyAction.RECORD,
    val keyGreen: String = KeyAction.GUIDE,
    val keyYellow: String = KeyAction.AUDIO,
    val keyBlue: String = KeyAction.SUBTITLES,
    /** O atraso ao mudar de canal, em ms ([ZapDelay]: automático, desligado ou um tempo fixo). */
    val zapDelayMs: Int = ZapDelay.AUTO,
    val infoSeconds: Int = 4,
    val startup: String = Startup.LAST,
    /** Telemóvel: ao abrir, o ecrã inicial (recentes e grupos) ou já o último canal a dar. */
    val phoneStartup: String = PhoneStartup.HOME,
    /** Voltar no player, com tudo fechado: abre o guia ou sai da app. */
    val backAction: String = BackAction.GUIDE,
    // Aspeto
    val showNumbers: Boolean = true,
    val showLogos: Boolean = true,
    /** De onde vêm os logótipos: os da lista, os do guia de cada canal, ou os de um país do guia (ver LogoSource). */
    val logoSource: String = LogoSource.PLAYLIST,
    /** Logótipo escolhido canal a canal: "playlistId:channelKey" → URL ("" = o da lista). Ganha a [logoSource]. */
    val channelLogos: Map<String, String> = emptyMap(),
    /** Guia XMLTV extra por lista: playlistId → URL (para os canais que o guia da lista não tem). */
    val extraEpg: Map<String, String> = emptyMap(),
    /**
     * As listas com as variantes do mesmo canal juntas ("RTP 1 HD", "RTP 1 SD"… numa linha só; ver ChannelVariants):
     * playlistId → true. Desligado por defeito.
     */
    val mergeVariants: Set<String> = emptySet(),
    /** Regras para limpar os nomes dos canais, por lista: playlistId → localizar/substituir, pela ordem (ver ChannelNames). */
    val nameRules: Map<String, List<NameRule>> = emptyMap(),
    /** Nome escolhido canal a canal: "playlistId:channelKey" → nome. Ganha às [nameRules]. */
    val channelNames: Map<String, String> = emptyMap(),
    /**
     * Lista de reserva por lista: playlistId → playlistId. Quando um canal em direto não abre na lista, dá o mesmo
     * canal da reserva (ver ChannelMatch). Fica no `settings` da conta como o guia extra: um mapa que as versões
     * antigas, o site e a Apple TV guardam intacto (o `settings` grava-se inteiro e só se manda o que mudou).
     */
    val backupPlaylist: Map<String, String> = emptyMap(),
    /**
     * Os failovers escolhidos à mão: "playlistId:channelKey" → os canais a tentar, pela ordem (lista vazia = "nenhum",
     * este canal não muda de lista). Cada um é uma chave na reserva, ou "main:" e a chave de outro canal da própria
     * lista (ver ChannelMatch.MAIN_PREFIX). Ganha ao que o ChannelMatch encontra. Apaga-se ao mudar de reserva.
     * Gravado como era quando cada canal tinha um só (a 0.12.0 e o site percebem-no): "off", uma chave, ou um array
     * quando há mais de um.
     */
    val backupChannels: Map<String, List<String>> = emptyMap(),
    /**
     * Como se escolhe o canal da reserva, por lista: "manual" = só os canais escolhidos à mão ([backupChannels]) passam
     * para a reserva; sem entrada = automático. Sai ao mudar ou tirar a reserva, e com a lista.
     */
    val backupMode: Map<String, String> = emptyMap(),
    /** Cor do contorno da linha com foco nas listas (ver OutlineColors): "white", "accent" ou uma cor. */
    val outlineColor: String = OutlineColors.WHITE,
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
    /** Quantas horas do guia se guardam para trás (catch-up) e para a frente (ver GuideWindow). */
    val epgPastHours: Int = GuideHours.PAST_DEFAULT,
    val epgFutureHours: Int = GuideHours.FUTURE_DEFAULT,
    /**
     * Correção da hora pedida no catch-up, em minutos (para o relógio ou o fuso errado do fornecedor): soma-se à hora
     * do programa só no pedido ao servidor, em todas as listas (como a "catchup correction" do Kodi).
     */
    val catchupOffsetMin: Int = 0,
    val epgInList: Boolean = true,
    /** Um lembrete muda sozinho para o canal ao fim de uns segundos (sem isto, só com OK). */
    val reminderSwitch: Boolean = false,
    // Gravações
    /** Minutos a gravar antes do início e depois do fim do programa no guia (ver RecordingPlan.PADDING_OPTIONS). */
    val recordPaddingBeforeMin: Int = Padding.BEFORE_DEFAULT,
    val recordPaddingAfterMin: Int = Padding.AFTER_DEFAULT,
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

    /** Grupo de [playlistId] marcado como bloqueado (com ou sem PIN definido). */
    fun isGroupMarkedLocked(playlistId: String, groupId: String) = "$playlistId:$groupId" in lockedGroups

    /** Quantos grupos de [playlistId] estão bloqueados / escondidos (as chaves são "playlistId:groupId"). */
    fun lockedGroupCount(playlistId: String) = lockedGroups.count { it.startsWith("$playlistId:") }

    fun hiddenGroupCount(playlistId: String) = hiddenGroups.count { it.startsWith("$playlistId:") }

    object Key {
        const val ENGINE = "engine"
        const val BUFFER = "buffer"
        const val DECODER = "decoder"
        const val DEINTERLACE = "deinterlace"
        const val ASPECT = "aspect"
        const val FREEZE_FRAME = "freeze_frame"
        const val RECONNECT_SECONDS = "reconnect_seconds"
        const val AFR = "afr"
        const val AFR_MODE = "afr_mode"
        const val AFR_FALLBACK = "afr_fallback"
        const val AUDIO_PASSTHROUGH = "audio_passthrough"
        const val SURROUND_DEFAULT = "surround_default"
        const val AUDIO_LANGUAGE = "audio_language"
        const val SUBTITLE_LANGUAGE = "subtitle_language"
        const val PIP_ENABLED = "pip_enabled"
        const val AUDIO_DECODER = "audio_decoder"
        const val AUDIO_DELAY = "audio_delay_ms"
        const val HDR = "hdr"
        const val NET_GRAPH = "net_graph"
        const val NET_WARNINGS = "net_warnings"
        const val TIMESHIFT = "timeshift"
        const val PROTECT_PAUSED = "protect_paused"
        const val USER_AGENT = "user_agent"
        const val STREAM_FORMAT = "stream_format"
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
        const val KEY_RED = "key_red"
        const val KEY_GREEN = "key_green"
        const val KEY_YELLOW = "key_yellow"
        const val KEY_BLUE = "key_blue"
        const val ZAP_DELAY = "zap_delay_ms"
        const val INFO_SECONDS = "info_seconds"
        const val STARTUP = "startup"
        const val PHONE_STARTUP = "phone_startup"
        const val SHOW_NUMBERS = "show_numbers"
        const val SHOW_LOGOS = "show_logos"
        const val LOGO_SOURCE = "logo_source"
        const val CHANNEL_LOGOS = "channel_logos"
        const val EXTRA_EPG = "extra_epg"
        const val MERGE_VARIANTS = "merge_variants"
        const val NAME_RULES = "name_rules"
        const val CHANNEL_NAMES = "channel_names"
        const val BACKUP_PLAYLIST = "backup_playlist"
        const val BACKUP_CHANNELS = "backup_channels"
        const val BACKUP_MODE = "backup_mode"
        const val OUTLINE_COLOR = "outline_color"
        const val PANEL_TIMEOUT = "panel_timeout"
        const val SORT = "sort"
        const val PANEL_WIDTH = "panel_width"
        const val UI_SCALE = "ui_scale"
        const val TEXT_SIZE = "text_size"
        const val LANGUAGE = "language"
        const val PLAYLIST_REFRESH = "playlist_refresh_hours"
        const val EPG_REFRESH = "epg_refresh_hours"
        const val EPG_OFFSET = "epg_offset_min"
        const val EPG_PAST_HOURS = "epg_past_hours"
        const val EPG_FUTURE_HOURS = "epg_future_hours"
        const val CATCHUP_OFFSET = "catchup_offset_min"
        const val EPG_IN_LIST = "epg_in_list"
        const val REMINDER_SWITCH = "reminder_switch"
        const val RECORD_PADDING_BEFORE = "record_padding_before_min"
        const val RECORD_PADDING_AFTER = "record_padding_after_min"
        const val PIN_HASH = "parental_pin_sha256"
        const val LOCKED_GROUPS = "locked_groups"
    }

    // Os valores que cada opção aceita ficam ao lado dos nomes (ALL): o que não estiver lá volta ao padrão em [from].

    object Buffer {
        const val LIVE = "live"; const val SMALL = "small"; const val NORMAL = "normal"; const val LARGE = "large"
        val ALL = setOf(LIVE, SMALL, NORMAL, LARGE)
    }
    object Decoder {
        const val HARDWARE = "hw"; const val SOFTWARE = "sw"
        val ALL = setOf(HARDWARE, SOFTWARE)
    }
    /** Os nomes do VLC (iguais aos da Apple TV). */
    /** As janelas do guia que se podem escolher; mais dias pesam mais na memória da box. Iguais na Apple TV. */
    object GuideHours {
        const val PAST_DEFAULT = 24
        const val FUTURE_DEFAULT = 36
        val PAST = listOf(24, 48, 72, 168)
        val FUTURE = listOf(36, 72, 168)
    }

    /** Atraso do som (as opções das definições vão de −300 a +500). */
    object AudioDelay {
        const val MAX_MS = 1_000
    }

    object Deinterlace {
        const val SMOOTH = "yadif2x"; const val NORMAL = "yadif"; const val OFF = "off"
        val ALL = setOf(SMOOTH, NORMAL, OFF)
    }
    object Engine {
        const val EXO = "exo"; const val VLC = "vlc"
        /** Só na Apple (o AetherEngine). */
        const val AETHER = "aether"
        /** O motor próprio (experimental). */
        const val GOATCORE = "goatcore"
        /** Os do Android (a Apple passa os seus a [from]). */
        val ALL = setOf(EXO, VLC, GOATCORE)
        val APPLE = setOf(EXO, VLC, AETHER, GOATCORE)
    }

    /** As margens das gravações, em minutos (antes e depois do programa). */
    object Padding {
        val OPTIONS = listOf(0, 1, 2, 5, 10)
        const val BEFORE_DEFAULT = 1
        const val AFTER_DEFAULT = 2
    }

    /** De quantas em quantas horas se atualizam as listas e o guia (0 = sempre que se abre a app). */
    object Refresh {
        val OPTIONS = listOf(0, 3, 6, 8, 12, 24)
    }

    /** O formato preferido da conta (`stream_format`; null = automático). */
    object StreamFormats {
        val ALL = setOf(StreamFormat.TS, StreamFormat.HLS)
    }

    /** As cores de destaque, pela ordem das Definições (as cores de cada uma estão em ui/theme Accent.ALL). */
    object Accents {
        const val DEFAULT = "itv"
        val IDS = listOf(DEFAULT, "violet", "mint", "sunset", "gold", "white")
    }

    /** As cores do contorno do seletor; "accent" segue a cor de destaque (com o gradiente dela). */
    object OutlineColors {
        const val WHITE = "white"
        const val ACCENT = "accent"
        val IDS = listOf(WHITE, ACCENT, "blue", "violet", "pink", "red", "orange", "yellow", "green")
    }

    /** Origem dos logótipos; fora estes dois valores, é o código de um país do guia (ex.: "pt"). */
    object LogoSource {
        const val PLAYLIST = "playlist"
        const val GUIDE = "guide"
        fun isCountry(source: String) = source != PLAYLIST && source != GUIDE
    }
    object Hdr {
        const val AUTO = "auto"; const val SDR = "sdr"
        val ALL = setOf(AUTO, SDR)
    }
    object Aspect {
        const val FIT = "fit"; const val ZOOM = "zoom"; const val FILL = "fill"
        val ALL = setOf(FIT, ZOOM, FILL)
    }
    /**
     * Quando o AFR muda o modo da TV: nunca, só no primeiro canal (depois fica, sem o ecrã apagar a cada zap; volta
     * ao normal ao sair do player) ou em cada canal que precise de outra frequência. Desligado não se grava: é
     * [Key.AFR] a false (ver [afrModeValues]).
     */
    object AfrMode {
        const val OFF = "off"; const val START = "start"; const val ALWAYS = "always"
        val ALL = setOf(OFF, START, ALWAYS)
    }

    /** A cadência assumida quando a do canal não se mede (nenhuma, 50 ou 59,94 imagens por segundo). */
    object AfrFallback {
        const val NONE = "none"; const val HZ50 = "50"; const val HZ5994 = "59.94"
        val ALL = setOf(NONE, HZ50, HZ5994)

        fun fps(value: String): Float? = when (value) {
            HZ50 -> 50f
            HZ5994 -> 59.94f
            else -> null
        }
    }

    /** O atraso ao mudar de canal (ver ZapPacer): automático, desligado, ou sempre 1 ou 2 s (em ms). */
    object ZapDelay {
        const val AUTO = -1; const val OFF = 0
        val ALL = setOf(AUTO, OFF, 1_000, 2_000)
    }

    /** O que um botão de cor faz no player. */
    object KeyAction {
        const val NONE = "none"; const val RECORD = "record"; const val GUIDE = "guide"; const val CHANNELS = "channels"
        const val FAVORITE = "favorite"; const val PREVIOUS = "previous"; const val RECORDINGS = "recordings"
        const val SEARCH = "search"; const val AUDIO = "audio"; const val SUBTITLES = "subtitles"; const val SLEEP = "sleep"
        /** Por esta ordem nas definições. */
        val OPTIONS = listOf(NONE, RECORD, GUIDE, CHANNELS, FAVORITE, PREVIOUS, RECORDINGS, SEARCH, AUDIO, SUBTITLES, SLEEP)
        val ALL = OPTIONS.toSet()
    }
    object UpDown {
        const val ZAP = "zap"; const val LIST = "list"
        val ALL = setOf(ZAP, LIST)
    }
    object Startup {
        const val LAST = "last"; const val LIST = "list"
        val ALL = setOf(LAST, LIST)
    }
    object PhoneStartup {
        const val HOME = "home"; const val LAST = "last"
        val ALL = setOf(HOME, LAST)
    }
    object BackAction {
        const val GUIDE = "guide"; const val EXIT = "exit"
        val ALL = setOf(GUIDE, EXIT)
    }
    object Sort {
        const val NUMBER = "number"; const val NAME = "name"
        val ALL = setOf(NUMBER, NAME)
    }
    object TextSize {
        const val SMALL = "small"; const val NORMAL = "normal"; const val LARGE = "large"
        val ALL = setOf(SMALL, NORMAL, LARGE)

        fun scale(size: String) = when (size) { SMALL -> 0.9f; LARGE -> 1.15f; else -> 1f }
    }

    val textScale: Float get() = TextSize.scale(textSize)

    companion object {
        /** O valor [k] de [s], se for um primitivo (texto, número ou true/false). */
        private fun prim(s: JsonObject, k: String): JsonPrimitive? = (s[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

        /** O texto de um primitivo (também um número ou true/false, como o asString do Gson). */
        private val JsonPrimitive.text: String get() = content

        /**
         * As definições em [s]. [engines]: os motores desta plataforma ([Engine.ALL] ou [Engine.APPLE]);
         * [normalizeLanguage]: a língua da app como esta plataforma a conhece ("auto" = a do sistema).
         */
        fun from(s: JsonObject, engines: Set<String> = Engine.ALL, normalizeLanguage: (String?) -> String = { it ?: "auto" }): Prefs {
            val d = Prefs()
            fun str(k: String, def: String, allowed: Collection<String>) = prim(s, k)?.text?.takeIf { it in allowed } ?: def
            // true/false, um número (0 = não) ou o texto "true"/"false"; o resto fica no padrão.
            fun bool(k: String, def: Boolean): Boolean {
                val p = prim(s, k) ?: return def
                return when {
                    !p.isString && p.booleanOrNull != null -> p.booleanOrNull!!
                    !p.isString && p.doubleOrNull != null -> p.doubleOrNull != 0.0
                    p.text == "true" -> true
                    p.text == "false" -> false
                    else -> def
                }
            }
            fun int(k: String, def: Int): Int {
                val p = prim(s, k) ?: return def
                return p.intOrNull ?: p.doubleOrNull?.takeIf { it == kotlin.math.floor(it) && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt() ?: def
            }
            return Prefs(
                engine = str(Key.ENGINE, d.engine, engines),
                buffer = str(Key.BUFFER, d.buffer, Buffer.ALL),
                decoder = str(Key.DECODER, d.decoder, Decoder.ALL),
                deinterlace = str(Key.DEINTERLACE, d.deinterlace, Deinterlace.ALL),
                aspect = str(Key.ASPECT, d.aspect, Aspect.ALL),
                freezeFrame = bool(Key.FREEZE_FRAME, d.freezeFrame),
                reconnectSeconds = int(Key.RECONNECT_SECONDS, d.reconnectSeconds).coerceIn(0, 120),
                afr = bool(Key.AFR, d.afr),
                afrMode = afrMode(s),
                afrFallback = str(Key.AFR_FALLBACK, d.afrFallback, AfrFallback.ALL),
                audioPassthrough = bool(Key.AUDIO_PASSTHROUGH, d.audioPassthrough),
                surroundDefault = bool(Key.SURROUND_DEFAULT, d.surroundDefault),
                audioLanguage = language(s, Key.AUDIO_LANGUAGE),
                subtitleLanguage = language(s, Key.SUBTITLE_LANGUAGE),
                pipEnabled = bool(Key.PIP_ENABLED, d.pipEnabled),
                audioDecoder = str(Key.AUDIO_DECODER, d.audioDecoder, Decoder.ALL),
                audioDelayMs = int(Key.AUDIO_DELAY, d.audioDelayMs).coerceIn(-AudioDelay.MAX_MS, AudioDelay.MAX_MS),
                hdr = str(Key.HDR, d.hdr, Hdr.ALL),
                netGraph = bool(Key.NET_GRAPH, d.netGraph),
                netWarnings = bool(Key.NET_WARNINGS, d.netWarnings),
                timeshift = bool(Key.TIMESHIFT, d.timeshift),
                protectPaused = bool(Key.PROTECT_PAUSED, d.protectPaused),
                userAgent = prim(s, Key.USER_AGENT)?.text?.trim().orEmpty(),
                streamFormat = prim(s, Key.STREAM_FORMAT)?.text?.takeIf { it in StreamFormats.ALL },
                autoStart = bool(Key.AUTO_START, d.autoStart),
                exitConfirm = bool(Key.EXIT_CONFIRM, d.exitConfirm),
                glassOpacity = int(Key.GLASS_OPACITY, d.glassOpacity).coerceIn(0, 100),
                accent = str(Key.ACCENT, d.accent, Accents.IDS),
                showFavoritesGroup = bool(Key.SHOW_FAVORITES_GROUP, d.showFavoritesGroup),
                showAllGroup = bool(Key.SHOW_ALL_GROUP, d.showAllGroup),
                showRecentGroup = bool(Key.SHOW_RECENT_GROUP, d.showRecentGroup),
                backAction = str(Key.BACK_ACTION, d.backAction, BackAction.ALL),
                settingsPin = bool(Key.SETTINGS_PIN, d.settingsPin),
                hiddenGroups = stringSet(s, Key.HIDDEN_GROUPS),
                groupOrder = stringListMap(s, Key.GROUP_ORDER),
                hiddenChannels = stringSet(s, Key.HIDDEN_CHANNELS),
                channelOrder = stringListMap(s, Key.CHANNEL_ORDER),
                upDown = str(Key.UP_DOWN, d.upDown, UpDown.ALL),
                reverseZap = bool(Key.REVERSE_ZAP, d.reverseZap),
                keyRed = str(Key.KEY_RED, d.keyRed, KeyAction.ALL),
                keyGreen = str(Key.KEY_GREEN, d.keyGreen, KeyAction.ALL),
                keyYellow = str(Key.KEY_YELLOW, d.keyYellow, KeyAction.ALL),
                keyBlue = str(Key.KEY_BLUE, d.keyBlue, KeyAction.ALL),
                zapDelayMs = int(Key.ZAP_DELAY, d.zapDelayMs).takeIf { it in ZapDelay.ALL } ?: d.zapDelayMs,
                infoSeconds = int(Key.INFO_SECONDS, d.infoSeconds).coerceIn(0, 30),
                startup = str(Key.STARTUP, d.startup, Startup.ALL),
                phoneStartup = str(Key.PHONE_STARTUP, d.phoneStartup, PhoneStartup.ALL),
                showNumbers = bool(Key.SHOW_NUMBERS, d.showNumbers),
                showLogos = bool(Key.SHOW_LOGOS, d.showLogos),
                logoSource = prim(s, Key.LOGO_SOURCE)?.text
                    ?.takeIf { it == LogoSource.PLAYLIST || it == LogoSource.GUIDE || COUNTRY_CODE.matches(it) } ?: d.logoSource,
                channelLogos = stringMap(s, Key.CHANNEL_LOGOS),
                outlineColor = str(Key.OUTLINE_COLOR, d.outlineColor, OutlineColors.IDS),
                extraEpg = stringMap(s, Key.EXTRA_EPG).filterValues { it.startsWith("http://") || it.startsWith("https://") },
                mergeVariants = (s[Key.MERGE_VARIANTS] as? JsonObject)
                    ?.filter { (_, v) -> v is JsonPrimitive && !v.isString && v.booleanOrNull == true }?.keys.orEmpty(),
                nameRules = nameRules(s),
                channelNames = stringMap(s, Key.CHANNEL_NAMES).mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
                backupPlaylist = stringMap(s, Key.BACKUP_PLAYLIST).filter { (k, v) -> v.isNotEmpty() && v != k },
                backupChannels = failoverMap(s, Key.BACKUP_CHANNELS),
                backupMode = stringMap(s, Key.BACKUP_MODE).filterValues { it == ChannelMatch.MODE_MANUAL },
                panelTimeout = int(Key.PANEL_TIMEOUT, d.panelTimeout).coerceIn(0, 300),
                sort = str(Key.SORT, d.sort, Sort.ALL),
                panelWidth = int(Key.PANEL_WIDTH, d.panelWidth).coerceIn(260, 520),
                uiScale = int(Key.UI_SCALE, d.uiScale).coerceIn(UI_SCALE_MIN, UI_SCALE_MAX),
                textSize = str(Key.TEXT_SIZE, d.textSize, TextSize.ALL),
                language = normalizeLanguage(prim(s, Key.LANGUAGE)?.text),
                playlistRefreshHours = int(Key.PLAYLIST_REFRESH, d.playlistRefreshHours).takeIf { it in Refresh.OPTIONS } ?: d.playlistRefreshHours,
                epgRefreshHours = int(Key.EPG_REFRESH, d.epgRefreshHours).takeIf { it in Refresh.OPTIONS } ?: d.epgRefreshHours,
                epgOffsetMin = int(Key.EPG_OFFSET, d.epgOffsetMin).coerceIn(-12 * 60, 12 * 60),
                epgPastHours = int(Key.EPG_PAST_HOURS, d.epgPastHours).takeIf { it in GuideHours.PAST } ?: d.epgPastHours,
                epgFutureHours = int(Key.EPG_FUTURE_HOURS, d.epgFutureHours).takeIf { it in GuideHours.FUTURE } ?: d.epgFutureHours,
                catchupOffsetMin = int(Key.CATCHUP_OFFSET, d.catchupOffsetMin).coerceIn(-12 * 60, 12 * 60),
                epgInList = bool(Key.EPG_IN_LIST, d.epgInList),
                reminderSwitch = bool(Key.REMINDER_SWITCH, d.reminderSwitch),
                recordPaddingBeforeMin = int(Key.RECORD_PADDING_BEFORE, d.recordPaddingBeforeMin).takeIf { it in Padding.OPTIONS } ?: d.recordPaddingBeforeMin,
                recordPaddingAfterMin = int(Key.RECORD_PADDING_AFTER, d.recordPaddingAfterMin).takeIf { it in Padding.OPTIONS } ?: d.recordPaddingAfterMin,
                pinHash = prim(s, Key.PIN_HASH)?.text?.takeIf { it.length == 64 },
                lockedGroups = stringSet(s, Key.LOCKED_GROUPS),
            )
        }

        /**
         * O modo do AFR: a chave antiga [Key.AFR] a false desliga sempre (as versões antigas só mexem nela); ligada,
         * vale [Key.AFR_MODE] e, sem ele (gravado antes de haver modos), "sempre", que é o que o sim/não fazia. Sem
         * nenhuma das duas, desligado. (Aqui, como sempre: true/false ou o texto "true"; um número não conta.)
         */
        private fun afrMode(s: JsonObject): String {
            val p = prim(s, Key.AFR)
            val on = when {
                p == null -> null
                !p.isString && p.booleanOrNull != null -> p.booleanOrNull
                p.isString -> p.text.lowercase() == "true"
                else -> false
            }
            val mode = prim(s, Key.AFR_MODE)?.text?.takeIf { it == AfrMode.START || it == AfrMode.ALWAYS }
            return when {
                on == false -> AfrMode.OFF
                mode != null -> mode
                on == true -> AfrMode.ALWAYS
                else -> AfrMode.OFF
            }
        }

        /** O que gravar para o modo [mode] do AFR: as duas chaves a condizer (desligado apaga o modo). */
        fun afrModeValues(mode: String): Map<String, Any?> = when (mode) {
            AfrMode.START, AfrMode.ALWAYS -> mapOf(Key.AFR to true, Key.AFR_MODE to mode)
            else -> mapOf(Key.AFR to false, Key.AFR_MODE to null)
        }

        /**
         * [settings] com [value] na chave [key], na forma que [from] lê (e que o site espera): String, Boolean, Int,
         * lista de Strings, mapa de Strings, de listas ou de mapas, ou null para repor o padrão (tira a chave).
         */
        fun put(settings: JsonObject, key: String, value: Any?): JsonObject {
            val out = LinkedHashMap(settings)
            if (value == null) out.remove(key) else out[key] = json(value)
            return JsonObject(out)
        }

        /** [value] em JSON: em mapas e listas, listas, mapas e true/false ficam como são; o resto, texto. */
        fun json(value: Any): JsonElement = when (value) {
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Collection<*> -> array(value)
            is Map<*, *> -> obj(value)
            else -> error("Tipo não suportado: ${value::class}")
        }

        private fun obj(map: Map<*, *>): JsonObject = JsonObject(
            map.entries.associate { (k, v) ->
                k.toString() to when (v) {
                    is Collection<*> -> array(v)
                    is Map<*, *> -> obj(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        )

        private fun array(list: Collection<*>): JsonArray = JsonArray(list.map { if (it is Map<*, *>) obj(it) else JsonPrimitive(it.toString()) })

        /**
         * [settings] sem o que é só da lista [playlistId] (grupos e canais escondidos, bloqueados e ordenados,
         * logótipos escolhidos, guia extra, reserva e canais de reserva, versões e nomes), quando ela é apagada; e sem
         * a reserva das listas que a tinham como reserva. null se não mudou nada.
         */
        fun removePlaylist(settings: JsonObject, playlistId: String): JsonObject? {
            val prefix = "$playlistId:"
            val out = LinkedHashMap(settings)
            var changed = false
            for (key in listOf(Key.HIDDEN_GROUPS, Key.HIDDEN_CHANNELS, Key.LOCKED_GROUPS)) {
                val array = out[key] as? JsonArray ?: continue
                val kept = array.filterNot { it is JsonPrimitive && it.content.startsWith(prefix) }
                if (kept.size != array.size) {
                    out[key] = JsonArray(kept)
                    changed = true
                }
            }
            for ((key, perGroup) in listOf(
                Key.GROUP_ORDER to false, Key.EXTRA_EPG to false, Key.BACKUP_PLAYLIST to false, Key.BACKUP_MODE to false,
                Key.MERGE_VARIANTS to false, Key.NAME_RULES to false,
                Key.CHANNEL_ORDER to true, Key.CHANNEL_LOGOS to true, Key.BACKUP_CHANNELS to true, Key.CHANNEL_NAMES to true,
            )) {
                val obj = out[key] as? JsonObject ?: continue
                val kept = obj.filterKeys { if (perGroup) !it.startsWith(prefix) else it != playlistId }
                if (kept.size != obj.size) {
                    out[key] = JsonObject(kept)
                    changed = true
                }
            }
            // As listas que a tinham como reserva ficam sem reserva (e sem os canais escolhidos nela).
            val backups = out[Key.BACKUP_PLAYLIST] as? JsonObject
            val orphans = backups?.filter { (_, v) -> v is JsonPrimitive && v.content == playlistId }?.keys.orEmpty()
            if (orphans.isNotEmpty()) {
                out[Key.BACKUP_PLAYLIST] = JsonObject(backups!!.filterKeys { it !in orphans })
                (out[Key.BACKUP_MODE] as? JsonObject)?.let { m -> out[Key.BACKUP_MODE] = JsonObject(m.filterKeys { it !in orphans }) }
                (out[Key.BACKUP_CHANNELS] as? JsonObject)?.let { ch ->
                    out[Key.BACKUP_CHANNELS] = JsonObject(ch.filterKeys { k -> orphans.none { k.startsWith("$it:") } })
                }
                changed = true
            }
            return if (changed) JsonObject(out) else null
        }

        private fun strings(v: JsonElement?): List<String> =
            (v as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content }.orEmpty()

        private fun stringListMap(s: JsonObject, key: String): Map<String, List<String>> =
            (s[key] as? JsonObject)?.mapValues { (_, v) -> strings(v) }.orEmpty()

        private fun stringMap(s: JsonObject, key: String): Map<String, String> =
            (s[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { k to it } }?.toMap().orEmpty()

        /** Os failovers de cada canal: "off" = nenhum, uma chave, ou um array delas; um valor vazio fica de fora. */
        private fun failoverMap(s: JsonObject, key: String): Map<String, List<String>> =
            (s[key] as? JsonObject)?.mapNotNull { (k, v) ->
                val list = when {
                    v is JsonArray -> strings(v).filter { it.isNotEmpty() && it != ChannelMatch.OFF }.distinct()
                    v is JsonPrimitive && v !is JsonNull -> v.content.takeIf { it.isNotEmpty() }?.let { if (it == ChannelMatch.OFF) emptyList() else listOf(it) }
                    else -> null
                }
                list?.let { k to it }
            }?.toMap().orEmpty()

        /** As regras de cada lista: um array de {"find", "replace"}; uma regra sem "find" fica de fora. */
        private fun nameRules(s: JsonObject): Map<String, List<NameRule>> =
            (s[Key.NAME_RULES] as? JsonObject)?.mapNotNull { (id, v) ->
                val rules = (v as? JsonArray)?.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    val find = prim(o, "find")?.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    NameRule(find, prim(o, "replace")?.content.orEmpty())
                }.orEmpty()
                rules.takeIf { it.isNotEmpty() }?.let { id to it }
            }?.toMap().orEmpty()

        /** Os failovers [list] como se gravam (ver [backupChannels]). */
        fun failoverValue(list: List<String>): Any = when (list.size) {
            0 -> ChannelMatch.OFF
            1 -> list[0]
            else -> list
        }

        private val COUNTRY_CODE = Regex("[A-Za-z]{2,3}")

        /** Uma língua preferida: um código ISO 639 (as Definições gravam o 639-1); outra coisa volta a Automático. */
        private fun language(s: JsonObject, key: String): String =
            (s[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()?.takeIf { LANGUAGE_CODE.matches(it) }.orEmpty()

        private val LANGUAGE_CODE = Regex("[a-z]{2,3}")

        private fun stringSet(s: JsonObject, key: String): Set<String> = strings(s[key]).toSet()

        const val UI_SCALE_MIN = 80
        const val UI_SCALE_MAX = 120

        /** O PIN nunca é guardado em claro, nem na conta ("i-tv:" é o sal de sempre: mudá-lo invalidava os PINs). */
        fun hashPin(pin: String): String = Sha256.hex(Sha256.digest("i-tv:$pin".encodeToByteArray()))
    }
}
