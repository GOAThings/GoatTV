import Foundation
import GoatCore
import Security

/// O login desta TV: o token do dispositivo (no Keychain; se o Keychain falhar, por causa da assinatura, nas
/// preferências) e quem é. Nunca a password.
struct SessionStore {
    struct Session: Codable {
        var token: String
        var userId: String?
        var email: String?
        var deviceId: String?
        var deviceName: String?
    }

    private let defaults = UserDefaults.standard
    private let service = "cc.slokker.goattv.session"
    private let infoKey = "session.info"
    private let fallbackTokenKey = "session.token"

    func load() -> Session? {
        guard let data = defaults.data(forKey: infoKey),
              var session = try? JSONDecoder().decode(Session.self, from: data) else { return nil }
        guard let token = keychainToken() ?? defaults.string(forKey: fallbackTokenKey) else { return nil }
        session.token = token
        return session
    }

    func save(_ session: Session) {
        var info = session
        info.token = ""
        if let data = try? JSONEncoder().encode(info) { defaults.set(data, forKey: infoKey) }
        saveToken(session.token)
    }

    func saveToken(_ token: String) {
        if setKeychainToken(token) {
            defaults.removeObject(forKey: fallbackTokenKey)
        } else {
            defaults.set(token, forKey: fallbackTokenKey)
        }
    }

    func clear() {
        defaults.removeObject(forKey: infoKey)
        defaults.removeObject(forKey: fallbackTokenKey)
        SecItemDelete(query() as CFDictionary)
    }

    // MARK: Último canal (cópia local; a conta tem o mesmo em settings)

    var lastPlaylist: String? {
        get { defaults.string(forKey: "last.playlist") }
        nonmutating set { defaults.set(newValue, forKey: "last.playlist") }
    }

    var lastChannel: String? {
        get { defaults.string(forKey: "last.channel") }
        nonmutating set { defaults.set(newValue, forKey: "last.channel") }
    }

    // MARK: Keychain

    private func query() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "token"]
    }

    private func keychainToken() -> String? {
        var q = query()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func setKeychainToken(_ token: String) -> Bool {
        SecItemDelete(query() as CFDictionary)
        var q = query()
        q[kSecValueData as String] = Data(token.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }
}
