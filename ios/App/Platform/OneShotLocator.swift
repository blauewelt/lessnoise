import CoreLocation
import Foundation
import LessNoiseKit

/**
 * ONE location fix «when in use» (ios/DESIGN.md §4): CLLocationManager with
 * `requestWhenInUseAuthorization` only — no «Always», no background location mode,
 * `allowsBackgroundLocationUpdates` stays false. `oneFix` runs standard updates until a fix is good
 * enough or the timeout, then stops them. The callers (ShareController, ReportFlow) only ask while
 * the app is in the foreground. Reduced accuracy («Genauer Standort» off) is reported as
 * `.approximate` (Android's «Ungefähr»). Created and used on the main thread.
 */
final class OneShotLocator: NSObject, Locator, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var authWaiters: [() -> Void] = []
    private var fixContinuation: CheckedContinuation<FixResult, Never>?
    private var best: CLLocation?
    private var goodEnough = 25.0
    private var startedAt = Date()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
    }

    var authorization: LocationAuthorization {
        switch manager.authorizationStatus {
        case .notDetermined: return .notDetermined
        case .authorizedWhenInUse, .authorizedAlways:
            return manager.accuracyAuthorization == .fullAccuracy ? .precise : .approximate
        default: return .denied
        }
    }

    func requestAuthorization(_ done: @escaping () -> Void) {
        onMain {
            if self.manager.authorizationStatus != .notDetermined { done(); return }
            self.authWaiters.append(done)
            self.manager.requestWhenInUseAuthorization()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .notDetermined { return }
        let waiters = authWaiters
        authWaiters = []
        for w in waiters { w() }
    }

    func oneFix(timeoutMs: Int, goodEnoughM: Double) async -> FixResult {
        let result: FixResult = await withCheckedContinuation { (c: CheckedContinuation<FixResult, Never>) in
            onMain {
                // A second request while one runs: the first one ends with what it has.
                self.finish(nil)
                guard CLLocationManager.locationServicesEnabled() else { c.resume(returning: .locationOff); return }
                self.fixContinuation = c
                self.best = nil
                self.goodEnough = goodEnoughM
                self.startedAt = Date()
                self.manager.startUpdatingLocation()
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { [weak self] in
                    guard let self = self, self.fixContinuation != nil, Date().timeIntervalSince(self.startedAt) >= Double(timeoutMs) / 1000 - 0.05 else { return }
                    self.finish(self.best)
                }
            }
        }
        // Cancelled meanwhile (the app left the foreground): the caller ignores the result.
        return result
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for l in locations {
            // A cached location older than 2 minutes is not this place now (LocationPolicy.LAST_KNOWN_MAX_AGE_MS).
            let ageMs = Int(-l.timestamp.timeIntervalSinceNow * 1000)
            if ageMs > LocationPolicy.LAST_KNOWN_MAX_AGE_MS || l.horizontalAccuracy < 0 { continue }
            if best == nil || LocationPolicy.better(l.horizontalAccuracy, best!.horizontalAccuracy) { best = l }
        }
        if let b = best, LocationPolicy.goodEnough(b.horizontalAccuracy) || b.horizontalAccuracy <= goodEnough { finish(b) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let e = error as? CLError, e.code == .locationUnknown { return } // keeps trying until the timeout
        if let e = error as? CLError, e.code == .denied { finish(nil); return }
        finish(best)
    }

    private func finish(_ l: CLLocation?) {
        guard let c = fixContinuation else { return }
        fixContinuation = nil
        manager.stopUpdatingLocation()
        if let l = l {
            c.resume(returning: .fix(RawFix(lat: l.coordinate.latitude, lon: l.coordinate.longitude, accuracyM: l.horizontalAccuracy)))
        } else {
            c.resume(returning: .noFix)
        }
        best = nil
    }

    private func onMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread { body() } else { DispatchQueue.main.async(execute: body) }
    }
}
