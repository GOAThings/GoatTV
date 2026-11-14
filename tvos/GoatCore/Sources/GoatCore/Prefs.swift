import Foundation
import GoatShared

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
    /// O motor: "exo" (o normal; no Apple TV, o leitor da Apple nos HLS e o VLC no resto), "vlc" ou "aether" (o
    /// AetherEngine, experimental, só no Apple TV e no iPhone: o Android lê-o como "exo").
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
    /// As listas com as versões do mesmo canal juntas (`merge_variants`: playlistId → true; ver ChannelVariants).
    /// Desligado por defeito.
    public var mergeVariants: Set<String> = []
    /// Regras para limpar os nomes dos canais (`name_rules`: playlistId → [{find, replace}], pela ordem; ver ChannelNames).
    public var nameRules: [String: [NameRule]] = [:]
    /// Nome escolhido canal a canal (`channel_names`: "playlistId:channelKey" → nome). Ganha às [nameRules].
    public var channelNames: [String: String] = [:]
    /// Lista de reserva por lista (`backup_playlist`: playlistId → playlistId): o mesmo canal de outra lista quando um
    /// canal em direto não abre (ver ChannelMatch). Um mapa no `settings`, como o guia extra.
    public var backupPlaylist: [String: String] = [:]
    /// Os failovers escolhidos à mão (`backup_channels`: "playlistId:channelKey" → os canais a tentar, pela ordem; lista
    /// vazia = "nenhum"). Cada um é uma chave na reserva, ou "main:" e a chave de outro canal da própria lista (ver
    /// ChannelMatch.mainPrefix). Gravado como era quando cada canal tinha um só (a 0.12.0 e o site percebem-no): "off",
    /// uma chave, ou um array quando há mais de um (ver [failoverValue]).
    public var backupChannels: [String: [String]] = [:]
    /// Como se escolhe o canal da reserva (`backup_mode`: playlistId → "manual"; sem entrada = automático).
    public var backupMode: [String: String] = [:]
    public var outlineColor = "white"
    /// O tema dos painéis (`panel_style`): "glass" (o vidro original) ou "shadow" (sem painel, sobre uma sombra).
    public var panelStyle = PanelLook.glass
    /// No tema Sombra, a linha com foco fica branca com o texto escuro (`white_selector`).
    public var whiteSelector = false
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

    /// O aspeto dos painéis: o tema e, no Sombra, com ou sem o seletor branco (como Prefs.panelLook no Android).
    public enum PanelLook {
        public static let glass = "glass", shadow = "shadow", shadowWhite = "shadow_white"
    }

    public var panelLook: String {
        if panelStyle == PanelLook.glass { return PanelLook.glass }
        return whiteSelector ? PanelLook.shadowWhite : PanelLook.shadow
    }

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

    /// As definições em [s] (o `settings` da conta ou desta TV): lidas pelo núcleo partilhado com o Android
    /// (shared/…/data/Prefs.kt), que é quem sabe o formato; aqui só se copiam os campos que a Apple usa.
    public init(settings s: JSON) {
        self.init()
        let p = AppleBridge.shared.prefs(json: String(decoding: s.encoded(), as: UTF8.self))
        userAgent = p.userAgent
        language = p.language
        accent = p.accent
        showFavoritesGroup = p.showFavoritesGroup
        showAllGroup = p.showAllGroup
        showRecentGroup = p.showRecentGroup
        hiddenGroups = p.hiddenGroups
        groupOrder = p.groupOrder
        hiddenChannels = p.hiddenChannels
        channelOrder = p.channelOrder
        reconnectSeconds = Int(p.reconnectSeconds)
        streamFormat = p.streamFormat
        aspect = p.aspect
        buffer = p.buffer
        engine = p.engine
        decoder = p.decoder
        afr = p.afr
        afrMode = p.afrMode
        afrFallback = p.afrFallback
        hdr = p.hdr
        deinterlace = p.deinterlace
        audioPassthrough = p.audioPassthrough
        freezeFrame = p.freezeFrame
        timeshift = p.timeshift
        protectPaused = p.protectPaused
        audioDelayMs = Int(p.audioDelayMs)
        surroundDefault = p.surroundDefault
        audioLanguage = p.audioLanguage
        subtitleLanguage = p.subtitleLanguage
        uiScale = Int(p.uiScale)
        upDown = p.upDown
        reverseZap = p.reverseZap
        zapDelayMs = Int(p.zapDelayMs)
        infoSeconds = Int(p.infoSeconds)
        startup = p.startup
        phoneStartup = p.phoneStartup
        backAction = p.backAction
        exitConfirm = p.exitConfirm
        showNumbers = p.showNumbers
        showLogos = p.showLogos
        logoSource = p.logoSource
        channelLogos = p.channelLogos
        extraEpg = p.extraEpg
        mergeVariants = p.mergeVariants
        nameRules = p.nameRules.mapValues { $0.map { NameRule(find: $0.find, replace: $0.replace) } }
        channelNames = p.channelNames
        backupPlaylist = p.backupPlaylist
        backupChannels = p.backupChannels
        backupMode = p.backupMode
        outlineColor = p.outlineColor
        panelStyle = p.panelStyle
        whiteSelector = p.whiteSelector
        panelTimeout = Int(p.panelTimeout)
        glassOpacity = Int(p.glassOpacity)
        panelWidth = Int(p.panelWidth)
        textSize = p.textSize
        sort = p.sort
        playlistRefreshHours = Int(p.playlistRefreshHours)
        epgRefreshHours = Int(p.epgRefreshHours)
        epgOffsetMin = Int(p.epgOffsetMin)
        epgPastHours = Int(p.epgPastHours)
        epgFutureHours = Int(p.epgFutureHours)
        epgInList = p.epgInList
        reminderSwitch = p.reminderSwitch
        catchupOffsetMin = Int(p.catchupOffsetMin)
        recordPaddingBeforeMin = Int(p.recordPaddingBeforeMin)
        recordPaddingAfterMin = Int(p.recordPaddingAfterMin)
        pinHash = p.pinHash
        lockedGroups = p.lockedGroups
        settingsPin = p.settingsPin
    }

    /// O que gravar para o modo [mode] do AFR: as duas chaves a condizer (desligado apaga o modo).
    public static func afrModeValues(_ mode: String) -> [String: JSON?] {
        mode == AfrMode.start || mode == AfrMode.always
            ? ["afr": .bool(true), "afr_mode": .string(mode)]
            : ["afr": .bool(false), "afr_mode": nil]
    }

    /// Os failovers [list] como se gravam (ver [backupChannels]).
    public static func failoverValue(_ list: [String]) -> JSON {
        switch list.count {
        case 0: return .string(ChannelMatch.off)
        case 1: return .string(list[0])
        default: return .array(list.map(JSON.string))
        }
    }

    /// O PIN nunca é guardado em claro, nem na conta ("i-tv:" é o sal de sempre, igual no Android).
    public static func hashPin(_ pin: String) -> String {
        AppleBridge.shared.prefsHashPin(pin: pin)
    }
}
