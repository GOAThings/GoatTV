import GoatShared

/// O núcleo partilhado com o Android (GoatShared.xcframework, feito do módulo `shared/` em Kotlin pelo CI). Por agora
/// só se confirma que está ligado; a lógica passa para ele aos poucos, no lugar do Swift equivalente do GoatCore.
enum SharedCore {
    /// Uma chamada ao núcleo partilhado, para o registo: "PT: RTP 1" com a regra "PT: " → "RTP 1".
    static func check() -> String {
        GoatShared.ChannelNames.shared.apply(name: "PT: RTP 1", rules: [GoatShared.NameRule(find: "PT: ", replace: "")])
    }
}
