import Foundation
import OSLog

/// Registo de diagnóstico: os passos da conta, das listas e do player (sem URLs nem credenciais). Fica em memória
/// (as últimas [limit] linhas) e vai no relatório (Definições → Conta → Enviar relatório), junto com o registo do
/// sistema deste processo (AVFoundation, rede…).
enum DebugLog {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var lines: [String] = []
    private static let logger = Logger(subsystem: "cc.slokker.goattv", category: "debug")
    private static let limit = 300

    static func add(_ message: String) {
        let line = Date().formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute().second()) + "  " + message
        lock.lock()
        lines.append(line)
        if lines.count > limit { lines.removeFirst(lines.count - limit) }
        lock.unlock()
        logger.info("\(message, privacy: .public)")
    }

    static var text: String {
        lock.lock()
        defer { lock.unlock() }
        return lines.joined(separator: "\n")
    }

    /// O registo do sistema deste processo nos últimos [minutes] minutos (sem as linhas do próprio DebugLog).
    static func system(minutes: Double = 20) -> String {
        guard let store = try? OSLogStore(scope: .currentProcessIdentifier),
              let entries = try? store.getEntries(at: store.position(date: Date().addingTimeInterval(-minutes * 60))) else {
            return "(system log unavailable)"
        }
        let time = Date.FormatStyle().hour(.twoDigits(amPM: .omitted)).minute().second()
        return entries.compactMap { entry -> String? in
            guard let log = entry as? OSLogEntryLog, log.subsystem != "cc.slokker.goattv" else { return nil }
            let level = log.level == .error || log.level == .fault ? "E" : "I"
            return "\(log.date.formatted(time)) \(level) [\(log.subsystem)/\(log.category)] \(log.composedMessage)"
        }.joined(separator: "\n")
    }
}
