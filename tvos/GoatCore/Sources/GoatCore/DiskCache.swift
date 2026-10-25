import Foundation

/// Cópias em disco para arrancar logo com o que se viu da última vez (listas, configuração da conta, guias).
/// No tvOS a pasta Caches pode ser limpa pelo sistema: o que estiver aqui é só para ir mais depressa.
public struct DiskCache: Sendable {
    public let directory: URL

    public init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public static func caches(_ name: String = "GoatTV") -> DiskCache {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return DiskCache(directory: base.appendingPathComponent(name, isDirectory: true))
    }

    public func save<T: Encodable>(_ value: T, as key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: file(key), options: .atomic)
    }

    public func load<T: Decodable>(_ type: T.Type, key: String) -> T? {
        guard let data = try? Data(contentsOf: file(key)) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    public func remove(_ key: String) {
        try? FileManager.default.removeItem(at: file(key))
    }

    public func clear() {
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func file(_ key: String) -> URL {
        let safe = key.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "_" }
        return directory.appendingPathComponent(String(safe) + ".json")
    }
}
