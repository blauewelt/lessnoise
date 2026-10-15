import AVFoundation
import Foundation
import LessNoiseKit
import SwiftUI
import UIKit

/**
 * The classifier (milestone 7, planner's decision): Apple's built-in SoundAnalysis classifier
 * (Platform/SoundAnalysisClassifier.swift) with ios/App/categories-ios.json mapping its labels to
 * the app's categories. The TensorFlow Lite variant was removed (no supported SwiftPM package).
 * Without SoundAnalysis results, or if the mapping names an identifier the classifier does not
 * know, the app runs without categories (names and CSV columns from the same file).
 */
enum ClassifierFactory {
    struct Setup {
        let mapper: CategoryMapper?
        let classifier: SoundClassifier?
    }

    static func make() -> Setup {
        guard let json = CategoriesFile.json() else { return Setup(mapper: nil, classifier: nil) }
        if let labels = SoundAnalysisClassifier.knownLabels(), let m = try? AppleSoundCategories.mapper(json: json, labels: labels) {
            return Setup(mapper: m, classifier: SoundAnalysisClassifier(labels: m.labels))
        }
        return Setup(mapper: try? AppleSoundCategories.namesOnly(json: json), classifier: nil)
    }
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
    private var savedTabs: [String] = []

    private init() {
        let keychain = KeychainStore()
        keychain.wipeIfFreshInstall()
        let support = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        let dir = support.appendingPathComponent("LessNoise", isDirectory: true)
        var db: AppDatabase
        var problem: String? = nil
        do {
            db = try AppDatabase.open(directory: dir)
        } catch {
            db = try! AppDatabase.inMemory()
            problem = "Speicher nicht verfügbar – Messungen werden nicht behalten."
        }
        storageError = problem
        let sound = ClassifierFactory.make()
        let capture = AudioCapture()
        let net = NetworkMonitor()
        network = net
        let adapters = PlatformAdapters(
            database: db, keyValues: UserDefaultsStore(),
            secrets: RoutingSecretStore(keychain: keychain, files: ProtectedFileStore(directory: dir.appendingPathComponent("queue", isDirectory: true))),
            measureInput: capture, calibrationInput: capture, locator: OneShotLocator(), transport: URLSessionTransport(),
            network: net, notifier: LocalNotifications(), scheduler: BackgroundUploads(), activity: UIKitBackgroundActivity(),
            classifier: sound.classifier, mapper: sound.mapper, deviceModel: DeviceInfo.model,
            appVersion: DeviceInfo.version, appBuild: DeviceInfo.build
        )
        // UploadClient refuses a non-https base only; the base is the constant https://api.lessnoise.ch.
        services = try! AppServices(adapters, tabHistory: UserDefaults.standard.stringArray(forKey: "tabs.history") ?? [])
        map = MapWebController()
        services.changes.add { [weak self] in Task { @MainActor in self?.scheduleRefresh() } }
        net.changes.add { [weak self] in Task { @MainActor in self?.scheduleRefresh() } }
    }

    private func scheduleRefresh() {
        if refreshPending { return }
        refreshPending = true
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 100_000_000)
            guard let self = self else { return }
            self.refreshPending = false
            self.objectWillChange.send()
            let tabs = self.services.tabs.history.save()
            if tabs != self.savedTabs { self.savedTabs = tabs; UserDefaults.standard.set(tabs, forKey: "tabs.history") }
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
            case let .consent(start): return .consent(startMeasurement: start)
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
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if self?.toast == message { self?.toast = nil }
        }
    }

    var exportDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true) }
}
