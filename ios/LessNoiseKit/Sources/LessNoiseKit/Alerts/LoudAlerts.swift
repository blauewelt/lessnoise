import Foundation

/** Posts the «Sehr laut» notification (UNUserNotificationCenter on iOS). */
public protocol LoudAlertNotifier: AnyObject {
    /** Title [LoudAlertRule.title], body [LoudAlertRule.TEXT]; the tap carries the event's time and category. */
    func show(_ e: LiveEvent)
}

/**
 * «Unterwegs» (LoudAlerts.kt / LoudAlertWatcher of Android 0.6.3): while it is on and a measurement
 * runs, a very loud vehicle (≥ 75 dB(A), «Töff & Poser» or «Strassenverkehr», not wind) posts one
 * local notification, at most one every 2 minutes; its tap opens «Laut! Melden» with the event's
 * time. Off by default: a phone on the windowsill must not wake its owner at every loud pass.
 */
public final class LoudAlertWatcher {
    public static let SWITCH_LABEL = "Unterwegs"
    public static let SWITCH_HINT = "Sehr laut (ab 75 dB): eine Mitteilung, antippen zum Melden."

    private let settings: SettingsStore
    private let notifier: LoudAlertNotifier
    private let clock: AppClock
    private let lock = NSLock()
    private var lastEvent: LiveEvent?
    private var lastAlertAtMs: Int?

    public init(settings: SettingsStore, notifier: LoudAlertNotifier, clock: AppClock = SystemClock()) {
        self.settings = settings; self.notifier = notifier; self.clock = clock
    }

    /** Every live-state change; a new event that passes the rule posts the notification. */
    public func observe(_ l: LiveState) {
        lock.lock()
        guard let e = l.lastEvent, e != lastEvent else { lock.unlock(); return }
        lastEvent = e
        let now = clock.nowMs()
        let alert = LoudAlertRule.shouldAlert(e, unterwegs: settings.state.unterwegs, running: l.running, lastAlertAtMs: lastAlertAtMs, nowMs: now)
        if alert { lastAlertAtMs = now }
        lock.unlock()
        if alert { notifier.show(e) }
    }

    /** The notification's userInfo (iOS has no intents: the same keys as Android's extras). */
    public static func userInfo(_ e: LiveEvent) -> [String: Any] {
        var u: [String: Any] = ["action": LoudAlertIntent.ACTION, LoudAlertIntent.EXTRA_EVENT_MS: e.startEpochMs]
        if let c = e.category { u[LoudAlertIntent.EXTRA_CATEGORY] = c }
        return u
    }

    /** The prefilled report from a tapped notification's userInfo, or nil. */
    public static func preset(_ userInfo: [AnyHashable: Any]) -> ReportPreset? {
        let ms = (userInfo[LoudAlertIntent.EXTRA_EVENT_MS] as? Int) ?? (userInfo[LoudAlertIntent.EXTRA_EVENT_MS] as? NSNumber)?.intValue ?? 0
        return LoudAlertIntent.preset(userInfo["action"] as? String, ms, userInfo[LoudAlertIntent.EXTRA_CATEGORY] as? String)
    }
}
