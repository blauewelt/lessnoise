import Foundation

/**
 * The upload state on the phone (UploadState.kt): the non-secret fields as one JSON value in the
 * [KeyValueStore] (UserDefaults), the token and the location-noise secret in the [SecretStore]
 * (Keychain, this device only, never synchronised). Updates are atomic and written before the
 * new value is visible.
 */
public final class PersistentUploadStore: UploadStore {
    static let KEY = "upload.state.v1"
    static let TOKEN = "upload.token"
    static let SECRET = "location.noise.secret"

    private let kv: KeyValueStore
    private let secrets: SecretStore
    private let lock = NSLock()
    private var value: UploadSnapshot
    public let changes = Observers()

    public init(kv: KeyValueStore, secrets: SecretStore) {
        self.kv = kv
        self.secrets = secrets
        value = PersistentUploadStore.read(kv, secrets)
    }

    public var state: UploadSnapshot { lock.lock(); defer { lock.unlock() }; return value }

    @discardableResult
    public func update(_ transform: (UploadSnapshot) -> UploadSnapshot) -> UploadSnapshot {
        lock.lock()
        let old = value
        let n = transform(value)
        if n != old {
            PersistentUploadStore.write(n, kv, secrets)
            value = n
        }
        lock.unlock()
        if n != old { changes.notify() }
        return n
    }

    static func read(_ kv: KeyValueStore, _ secrets: SecretStore) -> UploadSnapshot {
        var s = UploadSnapshot()
        guard let text = kv.string(KEY), let o = (try? JSONValue.parse(text))?.objectValue else {
            s.noiseSecret = secrets.secret(SECRET)
            return s
        }
        s.enabled = o["enabled"]?.boolValue ?? false
        s.shareDecision = ShareDecision.fromStored(o["decision"]?.stringValue) ?? .UNDECIDED
        s.wifiOnly = o["wifi_only"]?.boolValue ?? false
        s.explanationSeen = o["explanation_seen"]?.boolValue ?? false
        if let site = o["site"]?.objectValue {
            let placement = site["placement"]?.stringValue ?? SiteSettings.PLACEMENT_OPEN_WINDOW
            s.site = SiteSettings(
                placement: SiteSettings.PLACEMENTS.contains(placement) ? placement : SiteSettings.PLACEMENT_OTHER,
                floor: site["floor"]?.intValue, streetFacing: site["street_facing"]?.boolValue, note: site["note"]?.stringValue ?? ""
            )
        }
        s.deviceId = o["device_id"]?.stringValue
        let token = secrets.secret(TOKEN)
        s.token = token
        s.sendFloorMs = o["send_floor_ms"]?.intValue ?? 0
        s.minutesSentUpToMs = o["minutes_sent_ms"]?.intValue ?? 0
        s.eventsSentUpToMs = o["events_sent_ms"]?.intValue ?? 0
        s.lastSiteJson = o["last_site_json"]?.stringValue
        s.lastSuccessAtMs = o["last_success_ms"]?.intValue
        s.lastError = o["last_error"]?.stringValue
        // A token that is gone from the Keychain (e.g. a backup restored onto another phone: the
        // Keychain item is «this device only») is as good as none: «Neu starten» (as on Android).
        s.authFailed = (o["auth_failed"]?.boolValue ?? false) || (o["has_token"]?.boolValue == true && token == nil)
        s.refused = (o["refused"]?.arrayValue ?? []).compactMap { el in
            guard let k = el.member("kind")?.stringValue, let t = el.member("start")?.intValue else { return nil }
            return RefusedRecord(kind: k, startEpochMs: t, reason: el.member("reason")?.stringValue ?? "")
        }
        s.refusedTotal = o["refused_total"]?.intValue ?? 0
        let sigma = o["sigma_m"]?.intValue ?? LocationNoise.DEFAULT_SIGMA_M
        s.sigmaM = LocationNoise.isSigma(sigma) ? sigma : LocationNoise.DEFAULT_SIGMA_M
        s.noiseSecret = secrets.secret(SECRET)
        return s
    }

    static func write(_ s: UploadSnapshot, _ kv: KeyValueStore, _ secrets: SecretStore) {
        func opt(_ v: Int?) -> JSONValue { v.map { .int($0) } ?? .null }
        func opt(_ v: String?) -> JSONValue { v.map { .string($0) } ?? .null }
        var site = JSONObject()
        site.put("placement", .string(s.site.placement))
        site.put("floor", opt(s.site.floor))
        site.put("street_facing", s.site.streetFacing.map { .bool($0) } ?? .null)
        site.put("note", .string(s.site.note))
        var o = JSONObject()
        o.put("enabled", .bool(s.enabled))
        o.put("decision", .string(s.shareDecision.stored))
        o.put("wifi_only", .bool(s.wifiOnly))
        o.put("explanation_seen", .bool(s.explanationSeen))
        o.put("site", .object(site))
        o.put("device_id", opt(s.deviceId))
        o.put("has_token", .bool(s.token != nil))
        o.put("send_floor_ms", .int(s.sendFloorMs))
        o.put("minutes_sent_ms", .int(s.minutesSentUpToMs))
        o.put("events_sent_ms", .int(s.eventsSentUpToMs))
        o.put("last_site_json", opt(s.lastSiteJson))
        o.put("last_success_ms", opt(s.lastSuccessAtMs))
        o.put("last_error", opt(s.lastError))
        o.put("auth_failed", .bool(s.authFailed))
        o.put("refused", .array(s.refused.map {
            .object(JSONObject([("kind", .string($0.kind)), ("start", .int($0.startEpochMs)), ("reason", .string($0.reason))]))
        }))
        o.put("refused_total", .int(s.refusedTotal))
        o.put("sigma_m", .int(s.sigmaM))
        // Secrets first: a JSON saying «has_token» must never point to a token that was not stored.
        if secrets.secret(TOKEN) != s.token { secrets.setSecret(TOKEN, s.token) }
        if secrets.secret(SECRET) != s.noiseSecret { secrets.setSecret(SECRET, s.noiseSecret) }
        kv.set(KEY, JSONValue.object(o).description)
    }

    /** The location-noise secret, created on first use (NoiseSecret.getOrCreate). */
    public func noiseSecret() -> [UInt8] {
        if let b64 = state.noiseSecret, let d = Data(base64Encoded: b64), d.count == LocationNoise.SECRET_BYTES { return [UInt8](d) }
        let fresh = LocationNoise.newSecret()
        update { var n = $0; n.noiseSecret = Data(fresh).base64EncodedString(); return n }
        return fresh
    }
}

/**
 * The «Laut! Melden» queue (ReportState.kt). Pending reports hold exact coordinates until the
 * server acknowledged them; they are written to the [SecretStore] (Keychain) rather than to
 * UserDefaults, so that they are never part of a backup. Sent ones (time, category, cell, no
 * coordinates) go to the [KeyValueStore].
 */
public final class PersistentReportStore: ReportStore {
    static let PENDING = "reports.pending"
    static let SENT = "reports.sent"

    private let kv: KeyValueStore
    private let secrets: SecretStore
    private let lock = NSLock()
    private var value: ReportSnapshot
    public let changes = Observers()

    public init(kv: KeyValueStore, secrets: SecretStore) {
        self.kv = kv
        self.secrets = secrets
        value = ReportSnapshot(pending: ReportJson.parsePending(secrets.secret(Self.PENDING)), sent: ReportJson.parseSent(kv.string(Self.SENT)))
    }

    public var state: ReportSnapshot { lock.lock(); defer { lock.unlock() }; return value }

    @discardableResult
    public func update(_ transform: (ReportSnapshot) -> ReportSnapshot) -> ReportSnapshot {
        lock.lock()
        let n = transform(value)
        // Written before the uploader goes on: a sent report's coordinates are gone from storage first.
        secrets.setSecret(Self.PENDING, n.pending.isEmpty ? nil : ReportJson.pending(n.pending))
        kv.set(Self.SENT, ReportJson.sent(n.sent))
        value = n
        lock.unlock()
        changes.notify()
        return n
    }
}

/** JSON of the report queue (the same keys as Android's ReportJson). */
public enum ReportJson {
    public static func pending(_ list: [PendingReport]) -> String {
        JSONValue.array(list.map { r in
            var o = JSONObject()
            o.put("t", .int(r.epochMs)); o.put("iso", .string(r.timeIso)); o.put("lat", .double(r.lat)); o.put("lon", .double(r.lon))
            o.put("acc", .double(r.accuracyM)); o.put("cat", .string(r.category))
            if let c = r.cell { o.put("cell", .string(c)) }
            return .object(o)
        }).description
    }

    public static func sent(_ list: [SentReport]) -> String {
        JSONValue.array(list.map { r in
            var o = JSONObject()
            o.put("t", .int(r.epochMs)); o.put("cat", .string(r.category))
            if let c = r.cell { o.put("cell", .string(c)) }
            return .object(o)
        }).description
    }

    public static func parsePending(_ s: String?) -> [PendingReport] {
        guard let s = s, let a = (try? JSONValue.parse(s))?.arrayValue else { return [] }
        return a.compactMap { el in
            guard let t = el.member("t")?.intValue, let iso = el.member("iso")?.stringValue, let lat = el.member("lat")?.doubleValue,
                  let lon = el.member("lon")?.doubleValue, let acc = el.member("acc")?.doubleValue, let cat = el.member("cat")?.stringValue
            else { return nil }
            return PendingReport(epochMs: t, timeIso: iso, lat: lat, lon: lon, accuracyM: acc, category: cat, cell: el.member("cell")?.stringValue)
        }
    }

    public static func parseSent(_ s: String?) -> [SentReport] {
        guard let s = s, let a = (try? JSONValue.parse(s))?.arrayValue else { return [] }
        return a.compactMap { el in
            guard let t = el.member("t")?.intValue, let cat = el.member("cat")?.stringValue else { return nil }
            return SentReport(epochMs: t, category: cat, cell: el.member("cell")?.stringValue)
        }
    }
}

/** The settings that remain (AppSettings / SettingsStore of Android 0.6.1+), plus «Unterwegs». */
public struct AppSettings: Equatable {
    public static let EVENT_MIN_LEVEL_MIN = 20.0
    public static let EVENT_MIN_LEVEL_MAX = 70.0
    public static let EVENT_EXCESS_MIN = 3.0
    public static let EVENT_EXCESS_MAX = 20.0

    /** «Lärmspitze ab … dB über Grundrauschen» (3–20, 0.5 steps). */
    public var eventExcessDb: Double = EngineConfig.DEFAULT_EVENT_EXCESS_DB
    /** «Lärmspitzen erst ab … dB(A)» (20–70); follows calibrations. */
    public var eventMinLevelDb: Double = EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB
    /** «Unterwegs» (off by default). */
    public var unterwegs: Bool = false

    public init() {}
}

public final class SettingsStore {
    static let KEY = "settings.v1"
    private let kv: KeyValueStore
    private let _value: Locked<AppSettings>
    public let changes = Observers()

    public init(kv: KeyValueStore) {
        self.kv = kv
        var s = AppSettings()
        if let text = kv.string(Self.KEY), let o = (try? JSONValue.parse(text))?.objectValue {
            if let v = o["event_excess_db"]?.doubleValue { s.eventExcessDb = Swift.min(Swift.max(v, AppSettings.EVENT_EXCESS_MIN), AppSettings.EVENT_EXCESS_MAX) }
            if let v = o["event_min_level_db"]?.doubleValue {
                s.eventMinLevelDb = Swift.min(Swift.max(v, AppSettings.EVENT_MIN_LEVEL_MIN), AppSettings.EVENT_MIN_LEVEL_MAX)
            }
            s.unterwegs = o["unterwegs"]?.boolValue ?? false
        }
        _value = Locked(s)
    }

    public var state: AppSettings { _value.get }

    public func update(_ transform: (AppSettings) -> AppSettings) {
        let n = _value.update { v -> AppSettings in v = transform(v); return v }
        var o = JSONObject()
        o.put("event_excess_db", .double(n.eventExcessDb))
        o.put("event_min_level_db", .double(n.eventMinLevelDb))
        o.put("unterwegs", .bool(n.unterwegs))
        kv.set(Self.KEY, JSONValue.object(o).description)
        changes.notify()
    }
}
