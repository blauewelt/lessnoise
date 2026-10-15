import Foundation

// The seams between the platform-independent app logic (this package, tested on Linux) and the
// iOS adapters in ios/App (UserDefaults, Keychain, the system clock). Nothing here touches an
// Apple-only framework.

/** Wall clock in epoch milliseconds, and sleeping (the tests drive both). */
public protocol AppClock: AnyObject {
    func nowMs() -> Int
    /** Sleeps [ms] (or until the task is cancelled: then throws CancellationError). */
    func sleep(ms: Int) async throws
}

public final class SystemClock: AppClock {
    public init() {}
    public func nowMs() -> Int { Int(Date().timeIntervalSince1970 * 1000) }
    public func sleep(ms: Int) async throws {
        if ms <= 0 { try Task.checkCancellation(); return }
        try await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
    }
}

/**
 * Small non-secret key/value storage (iOS: UserDefaults of the app, whose file iOS protects
 * «until first unlock»; tests: memory). Values are strings; callers encode what they need.
 */
public protocol KeyValueStore: AnyObject {
    func string(_ key: String) -> String?
    func set(_ key: String, _ value: String?)
}

/**
 * Secrets (iOS: Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synchronised;
 * tests: memory): the upload token and the location-noise secret.
 */
public protocol SecretStore: AnyObject {
    func secret(_ key: String) -> String?
    /** nil deletes. Returns false if the store refused (the caller then keeps working in memory). */
    @discardableResult func setSecret(_ key: String, _ value: String?) -> Bool
}

public final class MemoryKeyValueStore: KeyValueStore {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    public init(_ initial: [String: String] = [:]) { values = initial }
    public func string(_ key: String) -> String? { lock.lock(); defer { lock.unlock() }; return values[key] }
    public func set(_ key: String, _ value: String?) { lock.lock(); values[key] = value; lock.unlock() }
    public var all: [String: String] { lock.lock(); defer { lock.unlock() }; return values }
}

public final class MemorySecretStore: SecretStore {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    public init() {}
    public func secret(_ key: String) -> String? { lock.lock(); defer { lock.unlock() }; return values[key] }
    @discardableResult public func setSecret(_ key: String, _ value: String?) -> Bool {
        lock.lock(); values[key] = value; lock.unlock()
        return true
    }
    public var all: [String: String] { lock.lock(); defer { lock.unlock() }; return values }
}

/** A list of change callbacks (the app's ObservableObjects subscribe; this package has no Combine on Linux). */
public final class Observers {
    private let lock = NSLock()
    private var next = 0
    private var callbacks: [Int: () -> Void] = [:]

    public init() {}

    /** Returns a token for [remove]. */
    @discardableResult public func add(_ callback: @escaping () -> Void) -> Int {
        lock.lock(); defer { lock.unlock() }
        next += 1
        callbacks[next] = callback
        return next
    }

    public func remove(_ token: Int) { lock.lock(); callbacks[token] = nil; lock.unlock() }

    public func notify() {
        lock.lock(); let cs = Array(callbacks.values); lock.unlock()
        for c in cs { c() }
    }
}

/** A value guarded by a lock. */
public final class Locked<T> {
    private let lock = NSLock()
    private var value: T
    public init(_ v: T) { value = v }
    public var get: T { lock.lock(); defer { lock.unlock() }; return value }
    @discardableResult public func update<R>(_ body: (inout T) -> R) -> R { lock.lock(); defer { lock.unlock() }; return body(&value) }
    public func set(_ v: T) { lock.lock(); value = v; lock.unlock() }
}
