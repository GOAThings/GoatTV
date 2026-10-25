import Foundation

/// Gravar as definições sem desfazer o que mudou noutro sítio (site ou outra TV): manda-se só o que mudou, como
/// JSON Merge Patch (RFC 7396) em `settings_merge` (ver PATCH /api/goattv/profile). Igual ao do Android.
public enum SettingsMerge {
    /// O patch que leva de [base] a [target]: as chaves mudadas; nos objetos, só as sub-chaves mudadas; as chaves
    /// apagadas vão a null. Arrays e valores vão inteiros.
    public static func diff(_ base: [String: JSON], _ target: [String: JSON]) -> [String: JSON] {
        var patch: [String: JSON] = [:]
        for (key, value) in target {
            let old = base[key]
            if old == value { continue }
            if case .object(let o)? = old, case .object(let v) = value {
                let sub = diff(o, v)
                if !sub.isEmpty { patch[key] = .object(sub) }
            } else {
                patch[key] = value
            }
        }
        for key in base.keys where target[key] == nil { patch[key] = .null }
        return patch
    }

    /// RFC 7396: os objetos juntam-se chave a chave, null apaga, o resto substitui.
    public static func apply(_ base: [String: JSON], _ patch: [String: JSON]) -> [String: JSON] {
        var result = base
        for (key, value) in patch {
            switch value {
            case .null:
                result.removeValue(forKey: key)
            case .object(let o):
                result[key] = .object(apply(result[key]?.object ?? [:], o))
            default:
                result[key] = value
            }
        }
        return result
    }

    /// O patch tem arrays no primeiro nível (ex.: hidden_groups): esses vão inteiros, por isso pede-se `settings_rev`.
    public static func hasTopLevelArray(_ patch: [String: JSON]) -> Bool {
        patch.values.contains { if case .array = $0 { return true } else { return false } }
    }

    /// As alterações locais ([local] em relação a [base]) postas por cima do que o servidor tem agora ([server]).
    /// Os arrays de valores simples do primeiro nível são conjuntos de ids: juntam-se só os ids que mudaram aqui.
    public static func rebase(server: [String: JSON], base: [String: JSON], local: [String: JSON]) -> [String: JSON] {
        var patch = diff(base, local)
        for (key, value) in patch {
            guard case .array(let new) = value else { continue }
            // Uma chave que ainda não existia conta como conjunto vazio (ex.: o primeiro canal escondido).
            let oldValue = base[key] ?? .array([])
            guard case .array(let old) = oldValue else { continue }
            guard new.allSatisfy(\.isPrimitive), old.allSatisfy(\.isPrimitive) else { continue }
            let added = new.filter { !old.contains($0) }
            let removed = old.filter { !new.contains($0) }
            var merged = (server[key]?.array ?? []).filter { !removed.contains($0) }
            for item in added where !merged.contains(item) { merged.append(item) }
            patch[key] = .array(merged)
        }
        return apply(server, patch)
    }
}
