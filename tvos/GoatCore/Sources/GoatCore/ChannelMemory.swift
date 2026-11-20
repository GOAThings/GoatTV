import Foundation
import GoatShared

/// O que a pessoa escolheu à mão em cada canal (a faixa de áudio, as legendas e a proporção da imagem), para o canal
/// voltar como ficou. Só neste aparelho (UserDefaults, não vai para a conta), pela lista e pelo canal (no Modo TV Pro,
/// o da lista única, seja qual for a fonte a dar), dos 500 canais usados há menos tempo. A regra e o texto guardado são
/// os do núcleo partilhado com o Android (ChannelMemory.kt).
@MainActor
public final class ChannelMemory {
    private static let key = "channel_memory"
    private let memory: GoatShared.ChannelMemory

    public init() {
        memory = AppleBridge.shared.channelMemoryDecode(text: UserDefaults.standard.string(forKey: Self.key))
    }

    /// A faixa de áudio escolhida à mão no canal (ver [TrackMemory]); "" sem nenhuma.
    public func audio(_ playlistId: String, _ channelKey: String) -> String {
        memory.audio(playlistId: playlistId, channelKey: channelKey)
    }

    /// As legendas escolhidas à mão no canal ([TrackMemory.off] = desligadas); "" sem escolha.
    public func text(_ playlistId: String, _ channelKey: String) -> String {
        memory.text(playlistId: playlistId, channelKey: channelKey)
    }

    /// A proporção do canal: a escolhida à mão nele ou, sem nenhuma, a das definições ([global]).
    public func aspect(_ playlistId: String, _ channelKey: String, global: String) -> String {
        let own = memory.aspect(playlistId: playlistId, channelKey: channelKey)
        return own.isEmpty ? global : own
    }

    /// Guarda o áudio do canal ("" limpa).
    public func setAudio(_ playlistId: String, _ channelKey: String, _ memo: String) {
        if memory.setAudio(playlistId: playlistId, channelKey: channelKey, memo: memo) { save() }
    }

    public func setText(_ playlistId: String, _ channelKey: String, _ memo: String) {
        if memory.setText(playlistId: playlistId, channelKey: channelKey, memo: memo) { save() }
    }

    /// A pessoa escolheu a proporção [aspect] no canal: guarda-se se for diferente da das definições ([global]).
    public func setAspect(_ playlistId: String, _ channelKey: String, _ aspect: String, global: String) {
        if memory.setAspect(playlistId: playlistId, channelKey: channelKey, aspect: aspect, global: global) { save() }
    }

    /// O canal abriu-se: se tem memória, passa a ser o usado há menos tempo.
    public func touch(_ playlistId: String, _ channelKey: String) {
        if memory.touch(playlistId: playlistId, channelKey: channelKey) { save() }
    }

    private func save() {
        UserDefaults.standard.set(memory.encode(), forKey: Self.key)
    }
}
