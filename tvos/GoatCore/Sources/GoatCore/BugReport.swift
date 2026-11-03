import Foundation

/// Relatórios para a zona admin do site (`POST /api/reports`): tirar as credenciais do registo e cortá-lo ao
/// tamanho que o servidor aceita. Igual ao BugReport do GoatTV Android.
public enum BugReport {
    /// O servidor aceita até 64 KB de registo.
    public static let maxBytes = 60_000

    /// Troca por `***` os [secrets] e o que tem cara de credencial (URLs inteiros, parâmetros, caminhos Xtream, tokens).
    public static func redact(_ text: String, secrets: [String]) -> String {
        var out = text
        for secret in Set(secrets.filter { $0.count >= 3 }).sorted(by: { $0.count > $1.count }) {
            out = out.replacingOccurrences(of: secret, with: "***")
        }
        for (pattern, template) in rules {
            let regex = try! NSRegularExpression(pattern: pattern)
            out = regex.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: template)
        }
        return out
    }

    private static let rules: [(String, String)] = [
        // Os links dos canais podem ter credenciais em qualquer parte do caminho ou do servidor.
        (#"\b([a-zA-Z][a-zA-Z0-9+.-]*)://[^\s"'<>]+"#, "$1://***"),
        (#"(?i)\b(password|passwd|pass|pwd|username|user|token|access_token|auth|key|api_key|apikey|mac|sn|device_id|device_id2|signature|play_token)=([^&\s"']+)"#, "$1=***"),
        (#"/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/"#, "/$1/***/***/"),
        (#"/stalker/[^/\s]+/[^/\s]+/"#, "/stalker/***/***/"),
        (#"(?i)\b[0-9a-f]{2}(?:(?::|%3A)[0-9a-f]{2}){5}\b"#, "***"),
        (#"([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@"#, "$1***@"),
        (#"(?i)(authorization:?|bearer)\s+[^\s,]+"#, "$1 ***"),
    ]

    /// [head] inteiro e, do [log], as últimas linhas que cabem em [max] bytes.
    public static func trim(head: String, log: String, max: Int = maxBytes) -> String {
        let budget = max - head.utf8.count
        var tail = Substring(log)
        // Corta logo o que sobra: contar a cada linha, num registo grande, demora muito.
        if budget > 0, log.utf8.count > budget {
            tail = log[log.utf8.index(log.utf8.endIndex, offsetBy: -budget)...]
            if let cut = tail.firstIndex(of: "\n") { tail = tail[tail.index(after: cut)...] } else { tail = "" }
        }
        while tail.utf8.count > budget, let cut = tail.firstIndex(of: "\n") { tail = tail[tail.index(after: cut)...] }
        if tail.utf8.count > budget { tail = "" }
        return head + tail
    }
}
