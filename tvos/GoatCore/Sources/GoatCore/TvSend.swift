import Foundation
import GoatShared

/// "Enviar para a TV" (o `SendToTv` do núcleo partilhado): o telemóvel diz à GoatTV de uma TV da rede de casa que
/// canal abrir, e a TV abre-o das listas dela. Só ids e nomes, nunca endereços nem credenciais do fornecedor.
///
///   GET /hello   {"app":"GoatTV","name":"<aparelho>","version":"…","package":"…"}
///   POST /open   {"playlist","channel","name","pro","from"} → 200 {"name":"<canal aberto>"}, 404, 409, 503
public enum TvSend {
    private static var k: GoatShared.SendToTv { GoatShared.SendToTv.shared }

    public static var port: Int { Int(k.PORT) }
    /// O serviço que a TV anuncia na rede (Bonjour) e que o telemóvel procura.
    public static var service: String { k.SERVICE }
    /// A porta do GoatLauncher (só Android TV), que traz uma app para a frente.
    public static var launcherPort: Int { Int(k.LAUNCHER_PORT) }
    public static var maxBodyBytes: Int { Int(k.MAX_BODY_BYTES) }
    public static var retryMs: Int { Int(k.RETRY_MS) }

    public static var ok: Int { Int(k.OK) }
    public static var notFound: Int { Int(k.NOT_FOUND) }
    public static var notInFront: Int { Int(k.NOT_IN_FRONT) }
    public static var noLists: Int { Int(k.NO_LISTS) }

    /// O que a TV faz com um pedido (ver `route`).
    public enum Route: Equatable, Sendable { case here, switchList, byName }

    /// O passo seguinte do telemóvel depois de uma resposta (ver `step`).
    public enum Step: Equatable, Sendable { case done, launch, wait }

    /// O que o telemóvel diz à pessoa.
    public enum Outcome: Equatable, Sendable { case playing, missing, closed, failed }

    /// O que chega no POST /open (já limpo).
    public struct Request: Equatable, Sendable {
        public let playlist: String
        public let channel: String
        public let name: String
        public let pro: String
        public let from: String
    }

    /// Quem respondeu ao /hello ([pkg] vazio: sem GoatLauncher, ex. Apple TV).
    public struct Hello: Equatable, Sendable {
        public let name: String
        public let version: String
        public let pkg: String
    }

    public static func body(playlist: String, channel: String, name: String, pro: String, from: String) -> String {
        AppleBridge.shared.sendBody(playlist: playlist, channel: channel, name: name, pro: pro, from: from)
    }

    public static func parse(_ body: String) -> Request? {
        guard let r = AppleBridge.shared.sendParse(body: body) else { return nil }
        return Request(playlist: r.playlist, channel: r.channel, name: r.name, pro: r.pro, from: r.from)
    }

    public static func hello(name: String, version: String, pkg: String) -> String {
        AppleBridge.shared.sendHello(name: name, version: version, pkg: pkg)
    }

    public static func parseHello(_ body: String) -> Hello? {
        guard let h = AppleBridge.shared.sendParseHello(body: body) else { return nil }
        return Hello(name: h.name, version: h.version, pkg: h.pkg)
    }

    public static func route(_ request: Request, openId: String, playlists: [String], proOnTv: Bool) -> Route {
        let shared = GoatShared.SendRequest(playlist: request.playlist, channel: request.channel, name: request.name, pro: request.pro, from: request.from)
        switch AppleBridge.shared.sendRoute(request: shared, openId: openId, playlists: playlists, proOnTv: proOnTv) {
        case k.ROUTE_HERE: return .here
        case k.ROUTE_SWITCH: return .switchList
        default: return .byName
        }
    }

    /// A chave do canal de [pool] que é o [name] do telemóvel (o mesmo nome, ou o `ChannelMatch` das reservas), ou nil.
    public static func byName(_ name: String, pool: [ChannelMatch.Entry]) -> String? {
        AppleBridge.shared.sendByName(name: name, pool: ChannelMatch.entries(pool))
    }

    /// A linha de estado HTTP ("409 Conflict").
    public static func statusLine(_ code: Int) -> String { AppleBridge.shared.sendStatusLine(code: Int32(clamping: code)) }

    public static func step(status: Int, attempt: Int, launched: Bool, canLaunch: Bool) -> Step {
        switch AppleBridge.shared.sendStep(status: Int32(clamping: status), attempt: Int32(clamping: attempt), launched: launched, canLaunch: canLaunch) {
        case k.STEP_LAUNCH: return .launch
        case k.STEP_WAIT: return .wait
        default: return .done
        }
    }

    public static func outcome(_ status: Int) -> Outcome {
        switch AppleBridge.shared.sendOutcome(status: Int32(clamping: status)) {
        case k.PLAYING: return .playing
        case k.MISSING: return .missing
        case k.CLOSED: return .closed
        default: return .failed
        }
    }

    /// As posições de [names] pela ordem da folha: a última TV usada primeiro, depois pelo nome.
    public static func order(_ names: [String], last: String?) -> [Int] {
        AppleBridge.shared.sendOrder(names: names, last: last).map { Int($0.int32Value) }
    }

    /// O corpo da resposta 200 ao /open.
    public static func opened(_ name: String) -> String { AppleBridge.shared.sendOpened(name: name) }

    public static func parseOpened(_ body: String) -> String { AppleBridge.shared.sendParseOpened(body: body) }

    public static func launcherPath(_ pkg: String) -> String { AppleBridge.shared.sendLauncherPath(pkg: pkg) }

    /// Um texto vindo da rede, para mostrar: sem caracteres de controlo e curto.
    public static func clean(_ text: String) -> String { AppleBridge.shared.sendClean(text: text) }
}
