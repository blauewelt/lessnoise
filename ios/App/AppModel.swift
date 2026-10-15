import AVFoundation
import Foundation
import LessNoiseKit
import SwiftUI
import UIKit

/**
 * The classifier (owner's decision 5: TensorFlow Lite with the same yamnet.tflite as Android).
 * Not built in yet: TensorFlow Lite has no official Swift Package Manager distribution (Google
 * ships TensorFlowLiteSwift/TensorFlowLiteC for iOS through CocoaPods; the SwiftPM wrappers on
 * GitHub are unofficial re-packagings of a prebuilt binary). Until that is settled (ios/DESIGN.md
 * §9) the app runs without one: categories stay empty («–»), exactly like Android with the
 * classifier off; [SoundClassifier] is the protocol a TFLite or Core ML implementation plugs into.
 */
enum ClassifierFactory {
    static func make(mapper: CategoryMapper?) -> SoundClassifier? { nil }
}

/** The sheets of the app (one at a time). */
enum AppSheet: Identifiable, Equatable {
    case consent(startMeasurement: Bool)
    case locationOptions
    case paste
    case report
    var id: String {
        switch self {
        case let .consent(s): return "consent-\(s)"
        case .locationOptions: return "location"
        case .paste: return "paste"
        case .report: return "report"
        }
    }
}

/**
 * The SwiftUI side of [AppServices]: one object for the whole app, created at launch (the
 * BGAppRefreshTask needs it before a scene exists). Changes of the services arrive on any thread;
 * they are coalesced to at most ~10 screen refreshes per second on the main thread.
 */
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    let services: AppServices
    let network: NetworkMonitor
    let map: MapWebController
    /** «Messungen»'s data (kept for the app's lifetime: one database observer). */
    lazy var history = HistoryModel(services)
    /** Set when the database could not be opened (the app then works in memory, nothing is kept). */
    let storageError: String?
    @Published var micDenied = false
    @Published var notificationsDenied = false
    @Published var toast: String?
    private var refreshPending = false

    private init() {
        let keychain = KeychainStore()
        keychain.wipeIfFreshInstall()
        let support = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        let dir = support.appendingPathComponent("LessNoise", isDirectory: true)
        var db: AppDatabase
        var error: String? = nil
        do {
            db = try AppDatabase.open(directory: dir)
        } catch {
            db = try! AppDatabase.inMemory()
            error = "Speicher nicht verfügbar – Messungen werden nicht behalten."
        }
        storageError = error
        let mapper = AppModel.loadMapper()
        let capture = AudioCapture()
        let net = NetworkMonitor()
        network = net
        let adapters = PlatformAdapters(
            database: db, keyValues: UserDefaultsStore(),
            secrets: RoutingSecretStore(keychain: keychain, files: ProtectedFileStore(directory: dir.appendingPathComponent("queue", isDirectory: true))),
            measureInput: capture, calibrationInput: capture, locator: OneShotLocator(), transport: URLSessionTransport(),
            network: net, notifier: LocalNotifications(), scheduler: BackgroundUploads(), activity: UIKitBackgroundActivity(),
            classifier: ClassifierFactory.make(mapper: mapper), mapper: mapper, deviceModel: DeviceInfo.model,
            appVersion: DeviceInfo.version, appBuild: DeviceInfo.build
        )
        // UploadClient refuses a non-https base only; the base is the constant https://api.lessnoise.ch.
        services = try! AppServices(adapters, tabHistory: UserDefaults.standard.stringArray(forKey: "tabs.history") ?? [])
        map = MapWebController()
        services.changes.add { [weak self] in Task { @MainActor in self?.scheduleRefresh() } }
        net.changes.add { [weak self] in Task { @MainActor in self?.scheduleRefresh() } }
    }

    /** categories.json and yamnet_labels.txt from the bundle (the Android assets). */
    static func loadMapper() -> CategoryMapper? {
        guard let c = Bundle.main.url(forResource: "categories", withExtension: "json"),
              let l = Bundle.main.url(forResource: "yamnet_labels", withExtension: "txt"),
              let cj = try? String(contentsOf: c, encoding: .utf8), let lt = try? String(contentsOf: l, encoding: .utf8) else { return nil }
        return Categories.mapper(categoriesJson: cj, labelsText: lt)
    }

    private func scheduleRefresh() {
        if refreshPending { return }
        refreshPending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
            guard let self = self else { return }
            self.refreshPending = false
            self.objectWillChange.send()
            UserDefaults.standard.set(self.services.tabs.history.save(), forKey: "tabs.history")
        }
    }

    // ---- Shortcuts for the screens --------------------------------------------------------------

    var live: LiveState { services.session.live }
    var upload: UploadSnapshot { services.uploadStore.state }
    var settings: AppSettings { services.settings.state }
    var online: Bool { network.online }

    var shareStatus: ShareStatus? { services.share.status(online: online, hm: { Fmt.hm($0) }) }

    /** The one sheet shown now: the share dialog, else the report flow. */
    var sheet: AppSheet? {
        if let d = services.share.dialog {
            switch d {
            case let .consent(s): return .consent(startMeasurement: s)
            case .locationOptions: return .locationOptions
            case .paste: return .paste
            }
        }
        return services.reports.step != nil ? .report : nil
    }

    func dismissSheet() {
        if services.share.dialog != nil { services.share.dismiss() }
        if services.reports.step != nil { services.reports.cancel() }
    }

    // ---- Actions ---------------------------------------------------------------------------------

    /** «Starten»: the microphone permission first (asked at the first start), then the share flow. */
    func start() {
        switch AudioCapture.permission {
        case .granted:
            micDenied = false
            services.session.showError(nil)
            services.share.start()
        case .undetermined:
            AudioCapture.requestPermission { granted in
                if granted { self.start() } else { self.micDenied = true; self.services.session.showError(MeasureTexts.NO_MIC_PERMISSION) }
            }
        default:
            micDenied = true
            services.session.showError(MeasureTexts.NO_MIC_PERMISSION)
        }
    }

    func stop() { services.session.stop() }

    /** What a tap on the status line or the banner does. */
    func handle(_ a: ShareAction) {
        switch a {
        case .OPEN_MAP: services.tabs.open(.MAP)
        case .OPEN_SHARE: services.tabs.open(.SHARE)
        default: services.share.onStatus(a)
        }
    }

    /** «Unterwegs» on asks for the notification permission (not at launch). */
    func setUnterwegs(_ on: Bool) {
        services.settings.update { var n = $0; n.unterwegs = on; return n }
        if on {
            LocalNotifications.requestPermission { granted in self.notificationsDenied = !granted }
        }
    }

    /** A tapped «Sehr laut» notification. */
    func openReport(_ preset: ReportPreset) {
        services.tabs.select(.MEASURE)
        services.reports.begin(preset)
    }

    func openSettingsApp() {
        if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
    }

    func show(_ message: String) {
        toast = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in if self?.toast == message { self?.toast = nil } }
    }

    var exportDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true) }
}
