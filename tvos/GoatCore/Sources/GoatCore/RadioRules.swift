import Foundation
import GoatShared

/// As rádios (canais só de som), as mesmas regras do Android: vêm do núcleo partilhado (shared/…/playback/RadioRules.kt).
/// O que manda é o stream (só faixas de som); o que a lista diz só vale enquanto as faixas não chegam.
public enum RadioRules {
    /// A lista diz que [channel] é rádio: o atributo (M3U `radio="true"`, Xtream `radio_streams`), o grupo [group] ou o nome.
    public static func hint(_ channel: Channel, group: String?) -> Bool {
        GoatShared.RadioRules.shared.hint(listSays: channel.radio == true, name: channel.name, group: group)
    }

    /// É rádio? Com as faixas já conhecidas decide o stream; antes delas, [hint].
    public static func isRadio(hint: Bool, tracksKnown: Bool, hasVideo: Bool, hasAudio: Bool) -> Bool {
        GoatShared.RadioRules.shared.isRadio(hint: hint, tracksKnown: tracksKnown, hasVideo: hasVideo, hasAudio: hasAudio)
    }
}
