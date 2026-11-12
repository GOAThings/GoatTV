import Foundation
import GoatShared

/// O catch-up das listas M3U, como os outros players o leem (a referência é o README e o código do Kodi IPTV Simple).
/// As regras são as do núcleo partilhado com o Android (shared/…/data/m3u/M3uCatchup.kt); aqui só os tipos do Swift.
/// Na lista, por canal ou no `#EXTM3U` para todos:
/// - `catchup` (ou `catchup-type`): default, append, shift, flussonic (fs, flussonic-hls, flussonic-ts) ou xc;
/// - `catchup-source`: o URL (default) ou o que se junta ao do canal (append), com marcadores (ver [expand]);
/// - `catchup-days` (ou `timeshift`, `tvg-rec`): quantos dias o servidor guarda.
/// Só os dias, sem tipo (`timeshift="3"`, `tvg-rec="3"`), é o "shift" (`?utc=…&lutc=…`), ou o xc se o canal for de
/// um painel Xtream (`/live/U/P/123.ts`). O tipo vod (que precisa do `catchup-id` do guia) não se suporta.
public enum M3uCatchup {
    public static let `default` = "default"
    public static let append = "append"
    public static let shift = "shift"
    public static let flussonic = "flussonic"
    public static let flussonicTs = "flussonic-ts"
    public static let xc = "xc"

    /// Os dias quando a lista dá o tipo mas não os dias (o "Catchup days" do Kodi, que vem a 5).
    public static let defaultDays = 5

    /// O catch-up de um canal: o tipo, o modelo do URL e os dias.
    public struct Spec: Equatable, Sendable {
        public var type: String
        public var source: String?
        public var days: Int
    }

    /// O catch-up do canal de [url] com os atributos [attrs] do `#EXTINF` (os do `#EXTM3U`, [header], valem para os
    /// canais que não dizem), ou nil se não tiver (ou se for de um tipo que não se sabe fazer).
    public static func spec(_ attrs: [String: String], header: [String: String], url: String) -> Spec? {
        AppleBridge.shared.m3uCatchupSpec(attrs: attrs, header: header, url: url).map {
            Spec(type: $0.type, source: $0.source, days: Int($0.days))
        }
    }

    /// Ao minuto ou ao segundo: o xc e os modelos só com {Y}{m}{d}{H}{M} pedem minutos certos (o vídeo começa no
    /// minuto), os outros o segundo pedido.
    public static func precisionMs(_ channel: Channel) -> Int64 {
        AppleBridge.shared.m3uCatchupPrecisionMs(channel: channel.shared)
    }

    /// As tentativas para ver [channel] de [start] a [end] (segundos Unix, já com a correção da hora), agora [now].
    /// As datas ({Y}, {H}…, o xc) vão na hora de [zone] (a deste aparelho, como no Kodi). Vazia se o URL do canal não
    /// servir para o tipo.
    public static func candidates(_ channel: Channel, start: Int64, end: Int64, now: Int64, zone: TimeZone = .current) -> [StreamCandidate] {
        AppleBridge.shared.m3uCatchupCandidates(channel: channel.shared, startS: start, endS: end, nowS: now, zoneId: zone.identifier)
            .map { StreamCandidate(url: $0.url, format: $0.format) }
    }

    /// [template] com os marcadores preenchidos, ou nil se tiver algum que não se conhece (o pedido sairia errado).
    /// Os marcadores são os do Kodi: {utc} ${start}, {utcend} ${end}, {lutc} ${now} ${timestamp}, {duration},
    /// {duration:60}, {offset}, {Y} {m} {d} {H} {M} {S} e {utc:Y-m-d:H-M} (ver o M3uCatchup.kt).
    public static func expand(_ template: String, start: Int64, end: Int64, now: Int64, zone: TimeZone) -> String? {
        AppleBridge.shared.m3uCatchupExpand(template: template, startS: start, endS: end, nowS: now, zoneId: zone.identifier)
    }
}

extension Channel {
    /// O canal para o núcleo partilhado.
    var shared: GoatShared.Channel {
        GoatShared.Channel(uid: Int32(clamping: uid), key: key, num: Int32(clamping: num), name: name, logo: logo, categoryId: categoryId,
                           streamId: streamId, url: url, userAgent: userAgent, referrer: referrer, epgId: epgId,
                           archiveDays: Int32(clamping: archiveDays), catchupType: catchupType, catchupSource: catchupSource)
    }

    /// Um canal que o núcleo partilhado devolveu.
    init(_ c: GoatShared.Channel) {
        self.init(uid: Int(c.uid), key: c.key, num: Int(c.num), name: c.name, logo: c.logo, categoryId: c.categoryId,
                  streamId: c.streamId, url: c.url, userAgent: c.userAgent, referrer: c.referrer, epgId: c.epgId,
                  archiveDays: Int(c.archiveDays), catchupType: c.catchupType, catchupSource: c.catchupSource)
    }
}
