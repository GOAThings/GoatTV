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
    /// Auto frame rate: a TV muda de refresh rate para a cadência do canal. Continua a ser o ligado/desligado (as
    /// versões antigas só leem esta); o modo ([afrMode]) diz quando muda, e [afrModeValues] grava as duas a condizer.
    public var afr = false
    /// Quando o AFR muda o modo da TV ([AfrMode]); desligado sempre que [afr] for false.
    public var afrMode = AfrMode.off
    /// A cadência a usar quando a do canal não se consegue medir ([AfrFallback]).
    public var afrFallback = AfrFallback.none
    /// "auto" (HDR quando o canal é HDR) ou "sdr".
    public var hdr = "auto"
    /// Desentrelaçar no VLC (só tvOS): "yadif2x", "yadif" ou "off".
    public var deinterlace = "yadif2x"
    /// Passthrough de áudio (Dolby/DTS para o recetor ou a TV).
    public var audioPassthrough = true
    /// Manter a imagem ao mudar de canal.
    public var freezeFrame = true
    /// Pausar o direto: o programa a ver guardado na cache, para pausar e recuar (ver Timeshift).
    public var timeshift = true
    /// Em pausa ou atrás do direto, mudar de canal pergunta primeiro (o que está guardado perde-se).
    public var protectPaused = true
    /// Atraso do som em ms (sincronia labial): + o som mais tarde, − mais cedo. Só no VLC (o AVPlayer não deixa).
    public var audioDelayMs = 0
    /// As opções do atraso do som (as mesmas do Android).
    public static let audioDelayOptions = [-300, -200, -150, -100, -50, 0, 50, 100, 150, 200, 300, 400, 500]
    /// Faixa surround por defeito.
    public var surroundDefault = false
    /// A língua preferida do áudio (ISO 639-1); vazio = automático (a da app, quando o canal a tem).
    public var audioLanguage = ""
    /// A língua das legendas (ISO 639-1); vazio = sem legendas além das que o stream marca.
    public var subtitleLanguage = ""
    /// Escala da interface, em % (80–120).
    public var uiScale = 100
    // Mudança de canal
    public var upDown = UpDown.zap
    public var reverseZap = false
    /// O atraso ao mudar de canal, em ms ([ZapDelay]: automático, desligado ou um tempo fixo).
    public var zapDelayMs = ZapDelay.auto
    public var infoSeconds = 4
    public var startup = Startup.last
    /// Telemóvel: ao abrir, o ecrã inicial (recentes e grupos) ou já o último canal a dar.
    public var phoneStartup = PhoneStartup.home
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
    /// Quantas horas do guia se guardam para trás (catch-up) e para a frente (ver GuideWindow).
    public var epgPastHours = GuideWindow.pastDefault
    public var epgFutureHours = GuideWindow.futureDefault
    public var epgInList = true
    /// Um lembrete muda sozinho para o canal ao fim de uns segundos (sem isto, só com "Ver").
    public var reminderSwitch = false
    /// Correção da hora pedida no catch-up, em minutos (o relógio ou o fuso do fornecedor errado).
    public var catchupOffsetMin = 0
    // Gravações
    public var recordPaddingBeforeMin = 1
    public var recordPaddingAfterMin = 2
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
    /// Quando o AFR muda o modo da TV: nunca, só no primeiro canal (depois fica, sem o ecrã apagar a cada zap; volta
    /// ao normal ao sair do leitor) ou em cada canal. Desligado não se grava: é `afr` a false (ver [afrModeValues]).
    public enum AfrMode {
        public static let off = "off", start = "start", always = "always"
        public static let all = [off, start, always]
    }

    /// A cadência assumida quando a do canal não se mede (nenhuma, 50 ou 59,94 imagens por segundo).
    public enum AfrFallback {
        public static let none = "none", hz50 = "50", hz5994 = "59.94"
        public static let all = [none, hz50, hz5994]
        public static func fps(_ value: String) -> Double? {
            switch value {
            case hz50: return 50
            case hz5994: return 59.94
            default: return nil
            }
        }
    }

    /// O atraso ao mudar de canal (ver ZapPacer): automático, desligado, ou sempre 1 ou 2 s (em ms).
    public enum ZapDelay {
        public static let auto = -1, off = 0
        public static let all = [auto, off, 1_000, 2_000]
    }

    /// As margens das gravações, em minutos (as mesmas opções do Android).
    public static let paddingOptions = [0, 1, 2, 5, 10]

    public enum UpDown { public static let zap = "zap", list = "list" }
    public enum Startup { public static let last = "last", list = "list" }
    public enum PhoneStartup { public static let home = "home", last = "last" }
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
        timeshift = bool("timeshift", d.timeshift)
        protectPaused = bool("protect_paused", d.protectPaused)
        audioDelayMs = min(max(int("audio_delay_ms", d.audioDelayMs), -1_000), 1_000)
        surroundDefault = bool("surround_default", d.surroundDefault)
        audioLanguage = Prefs.language(s["audio_language"])
        subtitleLanguage = Prefs.language(s["subtitle_language"])
        uiScale = clamp(int("ui_scale", d.uiScale), 80, 120)
        engine = str("engine", d.engine, ["exo", "vlc"])
        decoder = str("decoder", d.decoder, ["hw", "sw"])
        afr = bool("afr", d.afr)
        afrMode = Prefs.readAfrMode(s)
        afrFallback = str("afr_fallback", d.afrFallback, Set(AfrFallback.all))
        hdr = str("hdr", d.hdr, ["auto", "sdr"])
        deinterlace = str("deinterlace", d.deinterlace, ["yadif2x", "yadif", "off"])
        upDown = str("up_down", d.upDown, [UpDown.zap, UpDown.list])
        reverseZap = bool("reverse_zap", d.reverseZap)
        let zapDelay = int("zap_delay_ms", d.zapDelayMs)
        zapDelayMs = ZapDelay.all.contains(zapDelay) ? zapDelay : d.zapDelayMs
        infoSeconds = clamp(int("info_seconds", d.infoSeconds), 0, 30)
        startup = str("startup", d.startup, [Startup.last, Startup.list])
        phoneStartup = str("phone_startup", d.phoneStartup, [PhoneStartup.home, PhoneStartup.last])
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
        epgPastHours = Optional(int("epg_past_hours", d.epgPastHours)).filter { GuideWindow.pastHours.contains($0) } ?? d.epgPastHours
        epgFutureHours = Optional(int("epg_future_hours", d.epgFutureHours)).filter { GuideWindow.futureHours.contains($0) } ?? d.epgFutureHours
        epgInList = bool("epg_in_list", d.epgInList)
        reminderSwitch = bool("reminder_switch", d.reminderSwitch)
        catchupOffsetMin = clamp(int("catchup_offset_min", d.catchupOffsetMin), -12 * 60, 12 * 60)
        let before = int("record_padding_before_min", d.recordPaddingBeforeMin)
        recordPaddingBeforeMin = Prefs.paddingOptions.contains(before) ? before : d.recordPaddingBeforeMin
        let after = int("record_padding_after_min", d.recordPaddingAfterMin)
        recordPaddingAfterMin = Prefs.paddingOptions.contains(after) ? after : d.recordPaddingAfterMin
        pinHash = s["parental_pin_sha256"]?.string.flatMap { $0.count == 64 ? $0 : nil }
        lockedGroups = stringSet("locked_groups")
        settingsPin = bool("settings_pin", d.settingsPin)
    }

    /// O modo do AFR, como no Android: `afr` a false desliga sempre (as versões antigas só mexem nela); ligada, vale
    /// `afr_mode` e, sem ele (gravado antes de haver modos), "sempre", que é o que o sim/não fazia. Sem nenhuma, desligado.
    static func readAfrMode(_ s: JSON) -> String {
        // Como o Gson do Android: um texto "true" vale, um número não.
        let raw = s["afr"]
        let on: Bool?
        switch raw {
        case .bool(let b)?: on = b
        case .string(let t)?: on = t.lowercased() == "true"
        case .number?: on = false
        default: on = nil
        }
        let mode = s["afr_mode"]?.string.flatMap { $0 == AfrMode.start || $0 == AfrMode.always ? $0 : nil }
        if on == false { return AfrMode.off }
        if let mode { return mode }
        return on == true ? AfrMode.always : AfrMode.off
    }

    /// O que gravar para o modo [mode] do AFR: as duas chaves a condizer (desligado apaga o modo).
    public static func afrModeValues(_ mode: String) -> [String: JSON?] {
        mode == AfrMode.start || mode == AfrMode.always
            ? ["afr": .bool(true), "afr_mode": .string(mode)]
            : ["afr": .bool(false), "afr_mode": nil]
    }

    /// Uma língua das Definições: um código de 2 ou 3 letras (minúsculas), ou vazio.
    private static func language(_ v: JSON?) -> String {
        guard case .string(let raw)? = v else { return "" }
        let code = raw.trimmingCharacters(in: .whitespaces).lowercased()
        return (2...3).contains(code.count) && code.allSatisfy({ $0 >= "a" && $0 <= "z" }) ? code : ""
    }

    private static func isCountryCode(_ s: String) -> Bool {
        (2...3).contains(s.count) && s.allSatisfy { $0.isASCII && $0.isLetter }
    }

    /// O PIN nunca é guardado em claro, nem na conta ("i-tv:" é o sal de sempre, igual no Android).
    public static func hashPin(_ pin: String) -> String {
        SHA.hex(SHA.sha256(Array("i-tv:\(pin)".utf8)))
    }
}
