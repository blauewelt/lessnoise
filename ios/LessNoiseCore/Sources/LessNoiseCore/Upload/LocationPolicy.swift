import Foundation

/**
 * One location fix: the rules (location/LocationPolicy.kt). The Android side asks the
 * LocationManager; the iOS app will ask CLLocationManager once, «when in use» only
 * (ios/DESIGN.md). What leaves [decide] holds at most the SHIFTED point; [decideReport] is the one
 * result that carries exact coordinates (for «Laut! Melden», deleted once the server has the report).
 *
 * Accuracy: Android reports the 68 % radius; CLLocation.horizontalAccuracy is documented as the
 * radius of uncertainty (also ≈ 68 %), so the same thresholds apply. Negative horizontalAccuracy
 * (invalid fix) is treated like "no accuracy".
 */
public enum LocationPolicy {
    /** Worst accepted accuracy at «Start». */
    public static let MAX_ACCURACY_M = 75.0
    /** Worst accepted accuracy of a «Laut! Melden» report. */
    public static let REPORT_MAX_ACCURACY_M = 50.0
    /** Stop waiting once a fix is at least this good. */
    public static let GOOD_ENOUGH_M = 25.0
    /** How long to wait for a fix before giving up. */
    public static let TIMEOUT_MS = 30_000
    /** A last-known location is used only if it is this fresh … */
    public static let LAST_KNOWN_MAX_AGE_MS = 2 * 60_000
    /** … and at least this accurate. */
    public static let LAST_KNOWN_MAX_ACCURACY_M = 50.0

    /** What the person allowed. iOS 14+: «Genauer Standort» off = reduced accuracy (APPROXIMATE_ONLY). */
    public enum Permission: Equatable { case PRECISE, APPROXIMATE_ONLY, DENIED }

    public static func permission(fineGranted: Bool, coarseGranted: Bool) -> Permission {
        if fineGranted { return .PRECISE }
        if coarseGranted { return .APPROXIMATE_ONLY }
        return .DENIED
    }

    /** The phone's location-noise secret and the setting «Standort verschieben». */
    public struct Noise {
        public let secret: [UInt8]
        public let sigmaM: Int
        public init?(secret: [UInt8], sigmaM: Int) {
            guard secret.count == LocationNoise.SECRET_BYTES && LocationNoise.isSigma(sigmaM) else { return nil }
            self.secret = secret; self.sigmaM = sigmaM
        }
    }

    /** The result of the fix at «Start»: the shifted point, or why there is none. Holds no true coordinates. */
    public enum Outcome: Equatable {
        case shifted(point: LocationNoise.Point, accuracyM: Int?)
        case approximateOnly
        case tooInaccurate(accuracyM: Int?)
        case outsideSwitzerland(String)
        case locationOff
        case noFix
        case permissionDenied
    }

    public static func acceptLastKnown(ageMs: Int, accuracyM: Double?) -> Bool {
        guard let a = accuracyM, !a.isNaN else { return false }
        return ageMs >= 0 && ageMs <= LAST_KNOWN_MAX_AGE_MS && a <= LAST_KNOWN_MAX_ACCURACY_M
    }

    public static func goodEnough(_ accuracyM: Double?) -> Bool {
        guard let a = accuracyM, !a.isNaN else { return false }
        return a <= GOOD_ENOUGH_M
    }

    public static func better(_ a: Double?, _ b: Double?) -> Bool {
        let x = (a.flatMap { $0.isNaN ? nil : $0 }) ?? .infinity
        let y = (b.flatMap { $0.isNaN ? nil : $0 }) ?? .infinity
        return x < y
    }

    /** The decision for one fix (see LocationPolicy.kt). The true point exists only inside this function. */
    public static func decide(lat: Double, lon: Double, accuracyM: Double?, precise: Bool, noise: Noise) -> Outcome {
        if !precise { return .approximateOnly }
        let acc = accuracyM.flatMap { ($0.isNaN || $0 < 0) ? nil : $0 }
        guard let a = acc, a <= MAX_ACCURACY_M else { return .tooInaccurate(accuracyM: acc.map(roundUp)) }
        switch CellInput.fromLatLon(lat, lon, how: "Standort") {
        case let .ok(_, _, e, n):
            guard let p = try? LocationNoise.shift(noise.secret, e, n, noise.sigmaM) else { return .noFix }
            return .shifted(point: p, accuracyM: roundUp(a))
        case let .error(message):
            return .outsideSwitzerland(message)
        }
    }

    /** «Koordinaten einfügen»: the pasted place, moved by the location noise like a fix. */
    public static func fromPasted(_ parsed: CellInput.Parsed, noise: Noise) -> Outcome? {
        guard case let .ok(_, _, e, n) = parsed, let p = try? LocationNoise.shift(noise.secret, e, n, noise.sigmaM) else { return nil }
        return .shifted(point: p, accuracyM: nil)
    }

    /** The result of the fix for a report: the coordinates, or why there are none. */
    public enum ReportOutcome: Equatable {
        case fix(lat: Double, lon: Double, accuracyM: Double, cell: String)
        case problem(Outcome)
    }

    /** «Laut! Melden»: precise permission, accuracy ≤ [REPORT_MAX_ACCURACY_M] and a place in Switzerland. */
    public static func decideReport(lat: Double, lon: Double, accuracyM: Double?, precise: Bool) -> ReportOutcome {
        if !precise { return .problem(.approximateOnly) }
        let acc = accuracyM.flatMap { ($0.isNaN || $0 < 0) ? nil : $0 }
        guard let a = acc, a <= REPORT_MAX_ACCURACY_M else { return .problem(.tooInaccurate(accuracyM: acc.map(roundUp))) }
        switch CellInput.fromLatLon(lat, lon, how: "Standort") {
        case let .ok(cell, _, _, _): return .fix(lat: lat, lon: lon, accuracyM: a, cell: cell)
        case let .error(message): return .problem(.outsideSwitzerland(message))
        }
    }

    static func roundUp(_ m: Double) -> Int { Swift.max(1, JavaMath.toInt(ceil(m))) }

    /** «Gefunden (± 12 m), verschoben um etwa 50 m». */
    public static func shiftedLine(point: LocationNoise.Point, accuracyM: Int?) -> String {
        (accuracyM.map { "Gefunden (± \($0) m)" } ?? "Erkannt") + ", verschoben um etwa \(point.sigmaM) m"
    }

    /** Shown while waiting for the fix. */
    public static let PROGRESS_TEXT = "Standort wird bestimmt …"

    /** Why there is no usable fix, one short sentence. */
    public static func message(_ o: Outcome) -> String {
        switch o {
        case let .shifted(point, accuracyM): return shiftedLine(point: point, accuracyM: accuracyM)
        case .approximateOnly: return "Bitte «Genauer Standort» erlauben (App-Einstellungen)."
        case let .tooInaccurate(accuracyM):
            return "Zu ungenau" + (accuracyM.map { " (± \($0) m)" } ?? "") + " – am Fenster mit WLAN klappt es meist besser."
        case let .outsideSwitzerland(message): return message
        case .locationOff: return "Der Standort des Telefons ist ausgeschaltet."
        case .noFix: return "Kein Standort gefunden – versuch es am Fenster nochmals."
        case .permissionDenied: return "Ohne Erlaubnis für «Genauer Standort» geht es nicht."
        }
    }
}
