import Foundation
import GoatShared

/// Gravar as definições sem desfazer o que mudou noutro sítio (site ou outra TV): manda-se só o que mudou, como
/// JSON Merge Patch (RFC 7396) em `settings_merge` (ver PATCH /api/goattv/profile). Igual ao do Android.
public enum SettingsMerge {
    // As regras são as do núcleo partilhado com o Android (shared/…/data/SettingsPatch.kt); aqui passa-se pelo texto.
    private static func text(_ o: [String: JSON]) -> String { String(decoding: JSON.object(o).encoded(), as: UTF8.self) }
    private static func object(_ s: String) -> [String: JSON] { (try? JSON.parse(Data(s.utf8)))?.object ?? [:] }

    /// O patch que leva de [base] a [target]: as chaves mudadas; nos objetos, só as sub-chaves mudadas; as chaves
    /// apagadas vão a null. Arrays e valores vão inteiros.
    public static func diff(_ base: [String: JSON], _ target: [String: JSON]) -> [String: JSON] {
        object(AppleBridge.shared.settingsDiff(base: text(base), target: text(target)))
    }

    /// RFC 7396: os objetos juntam-se chave a chave, null apaga, o resto substitui.
    public static func apply(_ base: [String: JSON], _ patch: [String: JSON]) -> [String: JSON] {
        object(AppleBridge.shared.settingsApply(base: text(base), patch: text(patch)))
    }

    /// O patch tem arrays no primeiro nível (ex.: hidden_groups): esses vão inteiros, por isso pede-se `settings_rev`.
    public static func hasTopLevelArray(_ patch: [String: JSON]) -> Bool {
        patch.values.contains { if case .array = $0 { return true } else { return false } }
    }

    /// As alterações locais ([local] em relação a [base]) postas por cima do que o servidor tem agora ([server]),
    /// juntando os conjuntos de ids do primeiro nível em vez de os substituir.
    public static func rebase(server: [String: JSON], base: [String: JSON], local: [String: JSON]) -> [String: JSON] {
        object(AppleBridge.shared.settingsRebase(server: text(server), base: text(base), local: text(local)))
    }
}
