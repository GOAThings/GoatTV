import Foundation

/// As definições do player guardadas no `settings` da conta (as mesmas chaves do GoatTV Android). Só as que esta
/// versão usa; as outras ficam intactas no JSON.
public struct Prefs: Equatable, Sendable {
    // Geral
    public var userAgent = ""
    public var language = "auto"
    public var accent = "itv"
    public var showFavoritesGroup = true
    public var showAllGroup = true
    public var showRecentGroup = true
    public var hiddenGroups: Set<String> = []
    public var groupOrder: [String: [String]] = [:]
    public var hiddenChannels: Set<String> = []
    public var channelOrder: [String: [String]] = [:]
    // Reprodução
    public var reconnectSeconds = 5
    /// `stream_format`: "ts", "m3u8" ou nil (automático).
    public var streamFormat: String?
    public var aspect = Aspect.fit
    public var buffer = Buffer.normal
    /// O motor: "exo" (o normal; no Apple TV, o leitor da Apple nos HLS e o VLC no resto) ou "vlc".
    public var engine = "exo"
    /// "hw" ou "sw" (descodificador de vídeo).
    public var decoder = "hw"
    /// Auto frame rate: a TV muda de refresh rate para a cadência do canal.
    public var afr = false
    /// "auto" (HDR quando o canal é HDR) ou "sdr".
    public var hdr = "auto"
    /// Desentrelaçar no VLC (só tvOS): "yadif2x", "yadif" ou "off".
    public var deinterlace = "yadif2x"
    /// Passthrough de áudio (Dolby/DTS para o recetor ou a TV).
    public var audioPassthrough = true
    /// Manter a imagem ao mudar de canal.
    public var freezeFrame = true
    /// Faixa surround por defeito.
    public var surroundDefault = false
    /// Escala da interface, em % (80–120).
    public var uiScale = 100
    // Mudança de canal
    public var upDown = UpDown.zap
    public var reverseZap = false
    public var infoSeconds = 4
    public var startup = Startup.last
    /// Voltar no player, com tudo fechado: abre o guia ou sai da app.
    public var backAction = BackAction.guide
    public var exitConfirm = true
    // Aspeto
    public var showNumbers = true
    public var showLogos = true
    public var logoSource = LogoSource.playlist
    public var channelLogos: [String: String] = [:]
    public var extraEpg: [String: String] = [:]
    public var outlineColor = "white"
    public var panelTimeout = 15
    /// Opacidade do vidro, em % (0 = só o vídeo desfocado, 100 = opaco).
    public var glassOpacity = 60
    /// Largura dos painéis laterais, em dp do Android (o tvOS usa o dobro em pontos).
    public var panelWidth = 340
    public var textSize = TextSize.normal
    public var sort = Sort.number
    // Guia
    public var playlistRefreshHours = 0
    public var epgRefreshHours = 12
    public var epgOffsetMin = 0
    public var epgInList = true
    // Controlo parental
    public var pinHash: String?
    public var lockedGroups: Set<String> = []
    /// As Definições também pedem o PIN.
    public var settingsPin = false

    public init() {}

    public enum Aspect { public static let fit = "fit", zoom = "zoom", fill = "fill" }
    public enum Buffer {
        public static let live = "live", small = "small", normal = "normal", large = "large"
        /// O buffer de rede do VLC, em ms.
        public static func milliseconds(_ b: String) -> Int {
            switch b {
            case live: return 500
            case small: return 1000
            case large: return 4000
            default: return 2000
            }
        }
    }
    public enum UpDown { public static let zap = "zap", list = "list" }
    public enum Startup { public static let last = "last", list = "list" }
    public enum Sort { public static let number = "number", name = "name" }
    public enum TextSize {
        public static let small = "small", normal = "normal", large = "large"
        public static func scale(_ size: String) -> Double { size == small ? 0.9 : (size == large ? 1.15 : 1) }
    }
    public enum BackAction { public static let guide = "guide", exit = "exit" }
    public enum LogoSource {
        public static let playlist = "playlist", guide = "guide"
        public static func isCountry(_ s: String) -> Bool { s != playlist && s != guide }
    }

    /// 0 = sempre que se abre a app; o resto em horas (as mesmas opções do Android).
    public static let refreshOptions: Set<Int> = [0, 3, 6, 8, 12, 24]
    public static let refreshChoices = [0, 3, 6, 8, 12, 24]

    public func isLocked(_ playlistId: String, _ groupId: String) -> Bool {
        pinHash != nil && lockedGroups.contains("\(playlistId):\(groupId)")
    }

    public func isHidden(_ playlistId: String, _ groupId: String) -> Bool { hiddenGroups.contains("\(playlistId):\(groupId)") }

    public func isChannelHidden(_ playlistId: String, _ channelKey: String) -> Bool {
        hiddenChannels.contains("\(playlistId):\(channelKey)")
    }

    public init(settings s: JSON) {
        let d = Prefs()
        func str(_ k: String, _ def: String, _ allowed: Set<String>) -> String {
            guard let v = s[k]?.text, allowed.contains(v) else { return def }
            return v
        }
        func bool(_ k: String, _ def: Bool) -> Bool { s[k]?.bool ?? def }
        func int(_ k: String, _ def: Int) -> Int { s[k]?.int ?? def }
        func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(hi, max(lo, v)) }
        func stringSet(_ k: String) -> Set<String> { Set(s[k]?.strings ?? []) }
        func stringMap(_ k: String) -> [String: String] {
            (s[k]?.object ?? [:]).compactMapValues { $0.text }
        }
        func stringListMap(_ k: String) -> [String: [String]] {
            (s[k]?.object ?? [:]).mapValues { $0.strings }
        }

        userAgent = s["user_agent"]?.string?.trimmed ?? ""
        language = s["language"]?.string ?? d.language
        accent = str("accent", d.accent, ["itv", "violet", "mint", "sunset", "gold", "white"])
        showFavoritesGroup = bool("show_favorites_group", d.showFavoritesGroup)
        showAllGroup = bool("show_all_group", d.showAllGroup)
        showRecentGroup = bool("show_recent_group", d.showRecentGroup)
        hiddenGroups = stringSet("hidden_groups")
        groupOrder = stringListMap("group_order")
        hiddenChannels = stringSet("hidden_channels")
        channelOrder = stringListMap("channel_order")
        reconnectSeconds = clamp(int("reconnect_seconds", d.reconnectSeconds), 0, 120)
        streamFormat = s["stream_format"]?.string.flatMap { $0 == StreamFormat.ts || $0 == StreamFormat.hls ? $0 : nil }
        aspect = str("aspect", d.aspect, [Aspect.fit, Aspect.zoom, Aspect.fill])
        buffer = str("buffer", d.buffer, [Buffer.live, Buffer.small, Buffer.normal, Buffer.large])
        audioPassthrough = bool("audio_passthrough", d.audioPassthrough)
        freezeFrame = bool("freeze_frame", d.freezeFrame)
        surroundDefault = bool("surround_default", d.surroundDefault)
        uiScale = clamp(int("ui_scale", d.uiScale), 80, 120)
        engine = str("engine", d.engine, ["exo", "vlc"])
        decoder = str("decoder", d.decoder, ["hw", "sw"])
        afr = bool("afr", d.afr)
        hdr = str("hdr", d.hdr, ["auto", "sdr"])
        deinterlace = str("deinterlace", d.deinterlace, ["yadif2x", "yadif", "off"])
        upDown = str("up_down", d.upDown, [UpDown.zap, UpDown.list])
        reverseZap = bool("reverse_zap", d.reverseZap)
        infoSeconds = clamp(int("info_seconds", d.infoSeconds), 0, 30)
        startup = str("startup", d.startup, [Startup.last, Startup.list])
        backAction = str("back_action", d.backAction, [BackAction.guide, BackAction.exit])
        exitConfirm = bool("exit_confirm", d.exitConfirm)
        showNumbers = bool("show_numbers", d.showNumbers)
        showLogos = bool("show_logos", d.showLogos)
        if let source = s["logo_source"]?.string,
           source == LogoSource.playlist || source == LogoSource.guide || Prefs.isCountryCode(source) {
            logoSource = source
        }
        channelLogos = stringMap("channel_logos")
        extraEpg = stringMap("extra_epg").filter { $0.value.hasPrefix("http://") || $0.value.hasPrefix("https://") }
        outlineColor = str("outline_color", d.outlineColor, ["white", "accent", "blue", "violet", "pink", "red", "orange", "yellow", "green"])
        panelTimeout = clamp(int("panel_timeout", d.panelTimeout), 0, 300)
        glassOpacity = clamp(int("glass_opacity", d.glassOpacity), 0, 100)
        panelWidth = clamp(int("panel_width", d.panelWidth), 260, 520)
        textSize = str("text_size", d.textSize, [TextSize.small, TextSize.normal, TextSize.large])
        sort = str("sort", d.sort, [Sort.number, Sort.name])
        let playlistRefresh = int("playlist_refresh_hours", d.playlistRefreshHours)
        playlistRefreshHours = Prefs.refreshOptions.contains(playlistRefresh) ? playlistRefresh : d.playlistRefreshHours
        let epgRefresh = int("epg_refresh_hours", d.epgRefreshHours)
        epgRefreshHours = Prefs.refreshOptions.contains(epgRefresh) ? epgRefresh : d.epgRefreshHours
        epgOffsetMin = clamp(int("epg_offset_min", d.epgOffsetMin), -12 * 60, 12 * 60)
        epgInList = bool("epg_in_list", d.epgInList)
        pinHash = s["parental_pin_sha256"]?.string.flatMap { $0.count == 64 ? $0 : nil }
        lockedGroups = stringSet("locked_groups")
        settingsPin = bool("settings_pin", d.settingsPin)
    }

    private static func isCountryCode(_ s: String) -> Bool {
        (2...3).contains(s.count) && s.allSatisfy { $0.isASCII && $0.isLetter }
    }

    /// O PIN nunca é guardado em claro, nem na conta ("i-tv:" é o sal de sempre, igual no Android).
    public static func hashPin(_ pin: String) -> String {
        SHA.hex(SHA.sha256(Array("i-tv:\(pin)".utf8)))
    }
}
