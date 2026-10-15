import BackgroundTasks
import LessNoiseKit
import SwiftUI
import UIKit
import UserNotifications

/**
 * App start: the services are created once (AppModel.shared) before the first scene, because the
 * BGAppRefreshTask handler must be registered while launching; the notification delegate turns a
 * tapped «Sehr laut» into a prefilled «Laut! Melden».
 */
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BackgroundUploads.register { task in
            let model = AppModel.shared
            let run = Task {
                let o = await model.services.uploads.runNow(kind: "background")
                if case .done = o { task.setTaskCompleted(success: true) } else { task.setTaskCompleted(success: false) }
            }
            task.expirationHandler = { run.cancel() }
            // The next one, an hour later (iOS decides when it really runs).
            model.services.uploads.applySchedule()
        }
        _ = AppModel.shared
        return true
    }

    /** «Sehr laut» also shows while the app is open. */
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    /** The tap: «Laut! Melden» with the event's time and the suggested categories. */
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              let preset = LoudAlertWatcher.preset(response.notification.request.content.userInfo) else { return }
        await MainActor.run { AppModel.shared.openReport(preset) }
    }
}

@main
struct WenigerLaermApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(AppModel.shared)
        }
        .onChange(of: scenePhase) { phase in
            let s = AppModel.shared.services
            switch phase {
            case .active: s.foreground()
            case .background: s.background()
            default: break
            }
        }
    }
}
