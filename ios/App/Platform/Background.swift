import BackgroundTasks
import Foundation
import LessNoiseKit
import UIKit
import UserNotifications

/**
 * The hourly fallback upload outside a measurement: a BGAppRefreshTask «ch.lessnoise.upload» whose
 * earliest begin date is five minutes after the next full hour. iOS decides when (or whether) it
 * runs, from how the phone is used; nothing is lost when it does not (ios/DESIGN.md §5).
 */
final class BackgroundUploads: BackgroundUploadScheduler {
    static let identifier = "ch.lessnoise.upload"

    /** Must run before the app finishes launching (AppDelegate); the handler runs on the main queue. */
    static func register(_ run: @escaping (BGAppRefreshTask) -> Void) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: DispatchQueue.main) { task in
            guard let t = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            run(t)
        }
    }

    func schedule(earliestInMs: Int) {
        let r = BGAppRefreshTaskRequest(identifier: BackgroundUploads.identifier)
        r.earliestBeginDate = Date(timeIntervalSinceNow: Double(earliestInMs) / 1000)
        try? BGTaskScheduler.shared.submit(r)
    }

    func cancel() { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: BackgroundUploads.identifier) }
}

/** A short background task so that an upload that has started (or waits 15 s after «Stoppen») can finish. */
final class UIKitBackgroundActivity: BackgroundActivity {
    private let lock = NSLock()
    private var tokens: [Int: UIBackgroundTaskIdentifier] = [:]
    private var next = 0

    func begin(_ name: String) -> Int {
        lock.lock()
        next += 1
        let token = next
        lock.unlock()
        let id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in self?.end(token) }
        lock.lock(); tokens[token] = id; lock.unlock()
        return token
    }

    func end(_ token: Int) {
        lock.lock()
        let id = tokens.removeValue(forKey: token)
        lock.unlock()
        if let id = id, id != .invalid { UIApplication.shared.endBackgroundTask(id) }
    }
}

/**
 * «Sehr laut» (LoudAlertNotifier.kt): a local notification, one identifier (a new one replaces the
 * previous), interruption level time-sensitive (it shows through a Focus only if the person
 * allowed time-sensitive notifications for the app), default sound; the tap carries the event's
 * time and category in userInfo and opens the prefilled «Laut! Melden». Without permission it
 * simply does not show. The permission is asked when «Unterwegs» is switched on, not at launch.
 */
final class LocalNotifications: LoudAlertNotifier {
    static let identifier = "ch.lessnoise.loud_alert"
    static let categoryId = "loud_alert"

    static func requestPermission(_ done: @escaping (Bool) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { done(granted) }
        }
    }

    static func content(_ e: LiveEvent) -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        c.title = LoudAlertRule.title(e)
        c.body = LoudAlertRule.TEXT
        c.sound = .default
        c.categoryIdentifier = categoryId
        c.userInfo = LoudAlertWatcher.userInfo(e)
        c.interruptionLevel = .timeSensitive
        return c
    }

    func show(_ e: LiveEvent) {
        let r = UNNotificationRequest(identifier: LocalNotifications.identifier, content: LocalNotifications.content(e), trigger: nil)
        UNUserNotificationCenter.current().add(r, withCompletionHandler: nil)
    }
}
