import Foundation
import LessNoiseKit
import Security

/** Non-secret settings and the upload state's JSON: UserDefaults of the app. */
final class UserDefaultsStore: KeyValueStore {
    private let defaults: UserDefaults
    init(_ defaults: UserDefaults = .standard) { self.defaults = defaults }
    func string(_ key: String) -> String? { defaults.string(forKey: key) }
    func set(_ key: String, _ value: String?) {
        if let v = value { defaults.set(v, forKey: key) } else { defaults.removeObject(forKey: key) }
    }
}

/**
 * The upload token and the location-noise secret: Keychain items of class generic password,
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (the app uploads while the phone is locked;
 * never in a backup, never on another device), not synchronisable. Keychain items survive deleting
 * the app, so [wipeIfFreshInstall] removes them at the first launch of a new installation.
 */
final class KeychainStore: SecretStore {
    private let service: String
    init(service: String = "ch.lessnoise.app") { self.service = service }

    private func base(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }

    func secret(_ key: String) -> String? {
        var q = base(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    @discardableResult
    func setSecret(_ key: String, _ value: String?) -> Bool {
        SecItemDelete(base(key) as CFDictionary)
        guard let v = value else { return true }
        var q = base(key)
        q[kSecValueData as String] = Data(v.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        q[kSecAttrSynchronizable as String] = false
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    /** Deletes every item of this app's service. */
    func wipe() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }

    /** First launch after (re)installing (UserDefaults are new): old Keychain items go. */
    func wipeIfFreshInstall(_ defaults: UserDefaults = .standard) {
        let key = "installed.v1"
        if defaults.string(forKey: key) == nil {
            wipe()
            defaults.set("1", forKey: key)
        }
    }
}

/**
 * Pending reports (exact coordinates until the server has them): a small JSON file in Application
 * Support with complete file protection, excluded from backup, deleted with the app — neither in
 * UserDefaults (backed up) nor in the Keychain (survives deleting the app).
 */
final class ProtectedFileStore: SecretStore {
    private let dir: URL
    private let lock = NSLock()

    init(directory: URL) {
        dir = directory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
    }

    private func url(_ key: String) -> URL { dir.appendingPathComponent(key.replacingOccurrences(of: "/", with: "_") + ".json") }

    func secret(_ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return try? String(contentsOf: url(key), encoding: .utf8)
    }

    @discardableResult
    func setSecret(_ key: String, _ value: String?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let u = url(key)
        guard let v = value else {
            if FileManager.default.fileExists(atPath: u.path) { return (try? FileManager.default.removeItem(at: u)) != nil }
            return true
        }
        do {
            // .completeUntilFirstUserAuthentication: the upload runs while the phone is locked.
            try Data(v.utf8).write(to: u, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }
}

/**
 * The two stores PersistentReportStore uses: sent reports in UserDefaults, pending ones in the
 * protected file (keys «reports.pending»); the token and the noise secret in the Keychain.
 */
final class RoutingSecretStore: SecretStore {
    private let keychain: KeychainStore
    private let files: ProtectedFileStore
    init(keychain: KeychainStore, files: ProtectedFileStore) { self.keychain = keychain; self.files = files }
    private func isReport(_ key: String) -> Bool { key.hasPrefix("reports.") }
    func secret(_ key: String) -> String? { isReport(key) ? files.secret(key) : keychain.secret(key) }
    @discardableResult func setSecret(_ key: String, _ value: String?) -> Bool {
        isReport(key) ? files.setSecret(key, value) : keychain.setSecret(key, value)
    }
}

enum DeviceInfo {
    /** `utsname.machine`, e.g. «iPhone16,1» (Android: Build.MODEL); the simulator reports its model. */
    static var model: String {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return sim }
        var u = utsname()
        uname(&u)
        let machine = u.machine
        return withUnsafeBytes(of: machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
    }

    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }
    static var build: Int { Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0 }
}
