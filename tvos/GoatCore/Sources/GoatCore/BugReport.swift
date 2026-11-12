import Foundation
import GoatShared

/// Relatórios para a zona admin do site (`POST /api/reports`): tirar as credenciais do registo e cortá-lo ao
/// tamanho que o servidor aceita. As regras são as do núcleo partilhado com o GoatTV Android (BugReportRules).
public enum BugReport {
    /// O servidor aceita até 64 KB de registo.
    public static let maxBytes = Int(BugReportRules.shared.MAX_BYTES)

    /// O servidor de [value] (um URL, ou só o endereço), para sair do relatório mesmo quando aparece sem o resto.
    public static func host(of value: String) -> String? { BugReportRules.shared.hostOf(value: value) }

    /// Troca por `***` os [secrets] e o que tem cara de credencial (URLs inteiros, parâmetros, caminhos Xtream, tokens, servidores).
    public static func redact(_ text: String, secrets: [String]) -> String {
        BugReportRules.shared.redact(text: text, secrets: secrets)
    }

    /// [head] inteiro e, do [log], as últimas linhas que cabem em [max] bytes.
    public static func trim(head: String, log: String, max: Int = maxBytes) -> String {
        BugReportRules.shared.trim(head: head, log: log, max: Int32(clamping: max))
    }

    /// O relatório a enviar: [head] inteiro e as últimas linhas do [log] que cabem, tudo sem os [secrets]. Só o fim
    /// do registo passa pelo [redact], para um registo grande não demorar.
    public static func report(head: String, log: String, secrets: [String], max: Int = maxBytes) -> String {
        BugReportRules.shared.report(head: head, log: log, secrets: secrets, max: Int32(clamping: max))
    }
}
