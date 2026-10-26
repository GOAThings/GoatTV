import Foundation

/// Relatórios para a zona admin do site (`POST /api/reports`): tirar as credenciais do registo e cortá-lo ao
/// tamanho que o servidor aceita. Igual ao BugReport do GoatTV Android.
public enum BugReport {
    /// O servidor aceita até 64 KB de registo.
    public static let maxBytes = 60_000

    /// Troca por `***` os [secrets] e o que tem cara de credencial (parâmetros, caminhos Xtream, user:pass@, tokens).
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
        (#"(?i)\b(password|passwd|pass|pwd|username|user|token|access_token|auth|key|api_key|apikey)=([^&\s"']+)"#, "$1=***"),
        (#"/(live|movie|series|timeshift)/[^/\s]+/[^/\s]+/"#, "/$1/***/***/"),
        (#"([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@"#, "$1***@"),
        (#"(?i)(authorization:?|bearer)\s+[^\s,]+"#, "$1 ***"),
    ]

    /// [head] inteiro e, do [log], as últimas linhas que cabem em [max] bytes.
    public static func trim(head: String, log: String, max: Int = maxBytes) -> String {
        let budget = max - head.utf8.count
        var tail = Substring(log)
        while tail.utf8.count > budget, let cut = tail.firstIndex(of: "\n") { tail = tail[tail.index(after: cut)...] }
        if tail.utf8.count > budget { tail = "" }
        return head + tail
    }
}
