import Foundation

/** What the person allowed in iOS's location prompt. */
public enum LocationAuthorization: Equatable {
    /** Not asked yet. */
    case notDetermined
    /** «Beim Verwenden der App» with «Genauer Standort». */
    case precise
    /** «Genauer Standort» switched off (reduced accuracy) = Android's «Ungefähr». */
    case approximate
    case denied

    /** The core's view (a prompt that has not been answered counts as denied). */
    public var permission: LocationPolicy.Permission {
        switch self {
        case .precise: return .PRECISE
        case .approximate: return .APPROXIMATE_ONLY
        case .notDetermined, .denied: return .DENIED
        }
    }
}

/** One raw fix as CoreLocation gave it. Lives only until [LocationPolicy.decide] / [decideReport] has used it. */
public struct RawFix: Equatable {
    public let lat: Double
    public let lon: Double
    /** CLLocation.horizontalAccuracy (68 % radius, m); negative or nil: unknown. */
    public let accuracyM: Double?
    public init(lat: Double, lon: Double, accuracyM: Double?) { self.lat = lat; self.lon = lon; self.accuracyM = accuracyM }
}

public enum FixResult: Equatable {
    case fix(RawFix)
    /** Location services are off for the whole phone. */
    case locationOff
    case noFix
}

/**
 * ONE location fix, «when in use» only (ios/App/Location/OneShotLocator.swift with CLLocationManager;
 * never «Always», no background location mode). The caller makes sure the app is in the foreground.
 */
public protocol Locator: AnyObject {
    var authorization: LocationAuthorization { get }
    /** Shows iOS's prompt if it was never answered; calls [done] once answered (or at once). */
    func requestAuthorization(_ done: @escaping () -> Void)
    /** The best fix within [timeoutMs], returning early once one is at least [goodEnoughM] accurate. */
    func oneFix(timeoutMs: Int, goodEnoughM: Double) async -> FixResult
}

public extension LocationPolicy {
    /** A fix result → the outcome at «Starten» (only the shifted point leaves this function). */
    static func outcome(_ r: FixResult, precise: Bool, noise: Noise) -> Outcome {
        switch r {
        case let .fix(f): return decide(lat: f.lat, lon: f.lon, accuracyM: f.accuracyM, precise: precise, noise: noise)
        case .locationOff: return .locationOff
        case .noFix: return .noFix
        }
    }

    /** A fix result → the outcome of «Laut! Melden». */
    static func reportOutcome(_ r: FixResult, precise: Bool) -> ReportOutcome {
        switch r {
        case let .fix(f): return decideReport(lat: f.lat, lon: f.lon, accuracyM: f.accuracyM, precise: precise)
        case .locationOff: return .problem(.locationOff)
        case .noFix: return .problem(.noFix)
        }
    }
}

/** Which settings button fits a failed fix: «App-Einstellungen» (permission) or none (iOS cannot open the location switch). */
public enum LocationSettingsHint: Equatable {
    case appSettings
    case none

    public static func forOutcome(_ o: LocationPolicy.Outcome) -> LocationSettingsHint {
        switch o {
        case .approximateOnly, .permissionDenied: return .appSettings
        default: return .none
        }
    }
}
