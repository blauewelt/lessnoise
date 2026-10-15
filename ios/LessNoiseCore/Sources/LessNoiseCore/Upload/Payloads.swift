import Foundation

/** What the site call needs besides the contributor's own answers (server/DESIGN.md §4.2). */
public struct SiteExtras: Equatable {
    /** The phone model (iOS: the `utsname.machine` identifier, e.g. "iPhone16,1"; Android: Build.MODEL). */
    public let deviceModel: String
    /** UNPROCESSED | VOICE_RECOGNITION (Android); iOS: always [AudioSource.IOS_MEASUREMENT] = "IOS". */
    public let audioSource: String
    public let calibrated: Bool
    public let calibrationOffsetDb: Double
    public init(deviceModel: String, audioSource: String, calibrated: Bool, calibrationOffsetDb: Double) {
        self.deviceModel = deviceModel; self.audioSource = audioSource; self.calibrated = calibrated
        self.calibrationOffsetDb = calibrationOffsetDb
    }
}

/**
 * The exact request bodies of server/DESIGN.md §4.1–§4.4 and the reports call (upload/Payloads.kt),
 * byte-identical to the Android app's (golden-tested). Every minute and event carries the shifted
 * `point` of its measurement; records of a measurement without a point are never sent. Left out
 * on purpose: original levels, the raw AudioSet labels, classifier frame counts, row ids, the
 * detector-v2 features and wind counts. Levels rounded to 0.1 dB.
 *
 * The Android code reads Room rows (NaN stored as NULL); here the inputs are the engine's records,
 * whose NaN levels become JSON null exactly like the NULL columns do.
 */
public enum Payloads {
    /** Category id the server stores for an event that has none (classifier was off). */
    public static let NO_CATEGORY = "unclassified"

    /** `POST /v1/devices` body (§4.1). */
    public static func register(_ appVersion: String, _ appBuild: Int) -> JSONValue {
        .object(JSONObject([("app_version", .string(appVersion)), ("app_build", .int(appBuild))]))
    }

    /** `{"e": …, "n": …, "sigma_m": …}`: a shifted point (DESIGN.md §4.3). */
    public static func pointJson(_ p: LocationNoise.Point) -> JSONValue {
        .object(JSONObject([("e", .int(p.e)), ("n", .int(p.n)), ("sigma_m", .int(p.sigmaM))]))
    }

    /** The placement details, with the shifted point of the newest shared measurement ([point]). */
    public static func site(_ point: LocationNoise.Point, _ site: SiteSettings, _ extras: SiteExtras) -> JSONValue {
        var o = JSONObject()
        o.put("point", pointJson(point))
        o.put("placement", .string(site.placement))
        o.put("floor", site.floor.map { .int($0) } ?? .null)
        o.put("street_facing", site.streetFacing.map { .bool($0) } ?? .null)
        let model = String(kotlinTrim(extras.deviceModel).prefixUtf16(64))
        o.put("device_model", model.isEmpty ? .null : .string(model))
        o.put("audio_source", .string(extras.audioSource))
        o.put("calibrated", .bool(extras.calibrated))
        o.put("calibration_offset_db", num(extras.calibrationOffsetDb, 2))
        let note = String(kotlinTrim(site.note).prefixUtf16(SiteSettings.NOTE_MAX))
        o.put("note", note.isEmpty ? .null : .string(note))
        return .object(o)
    }

    /** Whether an event is uploaded at all: wind-flagged events are not (DESIGN.md §4.4). */
    public static func isShared(_ e: NoiseEvent) -> Bool { !e.wind }

    public static func minute(_ m: MinuteRecord, _ point: LocationNoise.Point) -> JSONValue {
        var o = JSONObject()
        o.put("start", .string(m.startIso))
        o.put("point", pointJson(point))
        o.put("duration_s", num(m.durationSeconds, 3))
        o.put("valid_s", num(m.validSeconds, 3))
        o.put("coverage", num(m.coverage, 3))
        o.put("laeq_db", level(m.laeqDb))
        o.put("lafmax_db", level(m.lafMaxDb))
        o.put("lafmin_db", level(m.lafMinDb))
        o.put("l1_db", level(m.l1Db))
        o.put("l10_db", level(m.l10Db))
        o.put("l50_db", level(m.l50Db))
        o.put("l90_db", level(m.l90Db))
        // Without wind events (detector v2); windEventCount is not sent.
        o.put("event_count", .int(m.eventCount))
        o.put("dominant_category", m.dominantCategory.map { .string($0) } ?? .null)
        var shares = JSONObject()
        for (k, v) in m.categoryShares.entries where !v.isNaN && !v.isInfinite { shares.put(k, num(v, 3)) }
        o.put("category_shares", .object(shares))
        o.put("calibration_id", m.calibrationId.map { .string(String($0)) } ?? .null)
        o.put("calibration_offset_db", num(m.calibrationOffsetDb, 2))
        o.put("calibrated", .bool(m.calibrated))
        o.put("audio_source", .string(m.audioSource))
        o.put("clock_corrections", .int(m.clockCorrections))
        return .object(o)
    }

    public static func event(_ e: NoiseEvent, _ point: LocationNoise.Point) -> JSONValue {
        var o = JSONObject()
        o.put("start", .string(e.startIso))
        o.put("point", pointJson(point))
        o.put("duration_s", num(e.durationSeconds, 3))
        o.put("lafmax_db", level(e.lafMaxDb))
        o.put("sel_db", level(e.selDb))
        o.put("background_db", level(e.backgroundDb))
        o.put("threshold_db", num(e.thresholdDb, 1))
        o.put("min_level_db", level(e.minLevelDb))
        o.put("category", .string(e.dominantCategory ?? NO_CATEGORY))
        o.put("category_score", num(Double(e.dominantScore), 3))
        o.put("calibration_id", e.calibrationId.map { .string(String($0)) } ?? .null)
        o.put("calibrated", .bool(e.calibrated))
        return .object(o)
    }

    /** One «Laut! Melden» report: time (ISO-8601 with offset, seconds), exact place (5 decimals), accuracy, category. */
    public static func report(_ r: PendingReport) -> JSONValue {
        var o = JSONObject()
        o.put("time", .string(r.timeIso))
        o.put("lat", num(r.lat, 5))
        o.put("lon", num(r.lon, 5))
        o.put("accuracy_m", num(r.accuracyM, 1))
        o.put("category", .string(r.category))
        return .object(o)
    }

    /** One decimal; NaN/∞/nil become JSON null. */
    public static func level(_ v: Double?) -> JSONValue { num(v, 1) }

    public static func num(_ v: Double?, _ decimals: Int) -> JSONValue {
        guard let v = v, !v.isNaN, !v.isInfinite else { return .null }
        var f = 1.0
        for _ in 0..<decimals { f *= 10 }
        return .double(Double(JavaMath.round(v * f)) / f)
    }

    /** Kotlin's `String.trim()`: drops leading and trailing chars for which `Char.isWhitespace()` is true. */
    static func kotlinTrim(_ s: String) -> String {
        let scalars = Array(s.unicodeScalars)
        var a = 0
        var b = scalars.count
        while a < b && kotlinIsWhitespace(scalars[a]) { a += 1 }
        while b > a && kotlinIsWhitespace(scalars[b - 1]) { b -= 1 }
        var out = String.UnicodeScalarView()
        out.append(contentsOf: scalars[a..<b])
        return String(out)
    }

    /** Kotlin `Char.isWhitespace()`: Character.isWhitespace || Character.isSpaceChar. */
    static func kotlinIsWhitespace(_ u: Unicode.Scalar) -> Bool {
        switch u.value {
        case 0x09...0x0D, 0x1C...0x20: return true
        default: break
        }
        // Unicode space separators (Zs), line separator (Zl), paragraph separator (Zp).
        switch u.properties.generalCategory {
        case .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
        default: return false
        }
    }
}

extension String {
    /**
     * Kotlin's `take(n)`: the first n UTF-16 code units (Kotlin strings are UTF-16). Deviation: a
     * character that would be cut in half (a surrogate pair or a combined grapheme at the limit)
     * is left out entirely instead of being split.
     */
    func prefixUtf16(_ n: Int) -> Substring {
        var count = 0
        var end = startIndex
        for ch in self {
            let w = ch.utf16.count
            if count + w > n { break }
            count += w
            end = index(after: end)
        }
        return self[startIndex..<end]
    }
}
