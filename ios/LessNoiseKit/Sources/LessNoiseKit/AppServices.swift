import Foundation

/** What the platform provides (ios/App builds these from AVFoundation, CoreLocation, URLSession …). */
public struct PlatformAdapters {
    public var database: AppDatabase
    public var keyValues: KeyValueStore
    public var secrets: SecretStore
    /** The microphone for measuring. */
    public var measureInput: AudioInput
    /** The microphone for calibrating (may be the same object: the two never run at once). */
    public var calibrationInput: AudioInput
    public var locator: Locator
    public var transport: UploadTransport
    public var network: NetworkStatus
    public var notifier: LoudAlertNotifier
    public var scheduler: BackgroundUploadScheduler?
    public var activity: BackgroundActivity
    public var classifier: SoundClassifier?
    public var mapper: CategoryMapper?
    /** `utsname.machine`, e.g. «iPhone16,1». */
    public var deviceModel: String
    public var appVersion: String
    public var appBuild: Int
    public var clock: AppClock

    public init(
        database: AppDatabase, keyValues: KeyValueStore, secrets: SecretStore, measureInput: AudioInput, calibrationInput: AudioInput,
        locator: Locator, transport: UploadTransport, network: NetworkStatus, notifier: LoudAlertNotifier,
        scheduler: BackgroundUploadScheduler?, activity: BackgroundActivity, classifier: SoundClassifier?, mapper: CategoryMapper?,
        deviceModel: String, appVersion: String, appBuild: Int, clock: AppClock = SystemClock()
    ) {
        self.database = database; self.keyValues = keyValues; self.secrets = secrets; self.measureInput = measureInput
        self.calibrationInput = calibrationInput; self.locator = locator; self.transport = transport; self.network = network
        self.notifier = notifier; self.scheduler = scheduler; self.activity = activity; self.classifier = classifier
        self.mapper = mapper; self.deviceModel = deviceModel; self.appVersion = appVersion; self.appBuild = appBuild; self.clock = clock
    }
}

/**
 * Everything of the app below the screens, wired once (AppContainer of Android): storage, the
 * measurement, sharing, reports, uploads, «Unterwegs», calibration. The SwiftUI layer observes
 * [changes] and calls the controllers.
 */
public final class AppServices {
    public let db: AppDatabase
    public let measurements: MeasurementRepository
    public let calibrations: CalibrationRepository
    public let history: HistoryQueries
    public let settings: SettingsStore
    public let uploadStore: PersistentUploadStore
    public let reportStore: PersistentReportStore
    public let client: UploadClient
    public let uploads: UploadCoordinator
    public let session: MeasurementSession
    public let share: ShareController
    public let reports: ReportFlow
    public let alerts: LoudAlertWatcher
    public let recalibrator: Recalibrator
    public let calibration: CalibrationController
    public let tabs: TabNavigator
    public let mapper: CategoryMapper?
    public let classifierAvailable: Bool
    public let clock: AppClock

    /** Fires (on any thread) after any change the screens show. */
    public let changes = Observers()

    public init(_ p: PlatformAdapters, tabHistory: [String] = []) throws {
        clock = p.clock
        db = p.database
        mapper = p.mapper
        classifierAvailable = p.classifier != nil && p.mapper != nil
        measurements = MeasurementRepository(db)
        calibrations = CalibrationRepository(db, deviceModel: p.deviceModel, clock: p.clock)
        history = HistoryQueries(measurements)
        settings = SettingsStore(kv: p.keyValues)
        uploadStore = PersistentUploadStore(kv: p.keyValues, secrets: p.secrets)
        reportStore = PersistentReportStore(kv: p.keyValues, secrets: p.secrets)
        // The one host (https://api.lessnoise.ch); the iOS transport refuses any other.
        client = try UploadClient(baseUrl: UploadClient.DEFAULT_BASE, transport: p.transport)
        let uploader = Uploader(
            client: client, store: uploadStore, source: DatabaseUploadSource(measurements, calibrations), reports: reportStore,
            appVersion: p.appVersion, appBuild: p.appBuild, clock: { [clock = p.clock] in clock.nowMs() }
        )
        uploads = UploadCoordinator(uploader: uploader, store: uploadStore, network: p.network, clock: p.clock, scheduler: p.scheduler, activity: p.activity)
        session = MeasurementSession(
            repo: measurements, calibrations: calibrations, settings: settings, input: p.measureInput,
            classifier: p.classifier, mapper: p.mapper, clock: p.clock
        )
        share = ShareController(session: session, uploads: uploads, locator: p.locator, clock: p.clock)
        reports = ReportFlow(uploads: uploads, reports: reportStore, locator: p.locator, clock: p.clock)
        alerts = LoudAlertWatcher(settings: settings, notifier: p.notifier, clock: p.clock)
        recalibrator = Recalibrator(measurements, calibrations)
        let session = self.session
        calibration = CalibrationController(
            calibrations: calibrations, settings: settings, recalibrator: recalibrator, input: p.calibrationInput,
            isMeasuring: { let l = session.live; return l.running || l.starting }
        )
        tabs = TabNavigator(restore: tabHistory)
        wire()
    }

    private var lastRecalDone = false

    private func wire() {
        session.changes.add { [weak self] in
            guard let self = self else { return }
            let l = self.session.live
            self.uploads.observe(l)
            self.share.observe(l)
            self.alerts.observe(l)
            self.changes.notify()
        }
        uploadStore.changes.add { [weak self] in
            guard let self = self else { return }
            self.uploads.observe(self.session.live)
            self.changes.notify()
        }
        // After «Alte Messungen anpassen»: send the last 7 days again with the new levels.
        recalibrator.changes.add { [weak self] in
            guard let self = self else { return }
            var done = false
            if case .done = self.recalibrator.state { done = true }
            if done && !self.lastRecalDone && self.uploadStore.state.registered {
                self.uploads.uploader.rewindAfterReevaluation()
                self.uploads.uploadSoon()
            }
            self.lastRecalDone = done
            self.changes.notify()
        }
        for o in [reportStore.changes, settings.changes, share.changes, reports.changes, calibration.changes, tabs.changes, db.changes] {
            o.add { [weak self] in self?.changes.notify() }
        }
    }

    /** The app was launched or came to the foreground. */
    public func foreground() {
        share.onForeground()
        uploads.appStarted()
    }

    /** The app went to the background (the measurement goes on; location and calibration do not). */
    public func background() {
        share.onBackground()
        calibration.onAppStopped()
    }

    /** «Alle löschen» (not while measuring or re-evaluating). */
    public func clearAll(exportDir: URL) throws {
        try measurements.clear()
        CsvExport.clear(exportDir)
    }

    /** Bucket ids for the CSV columns (categories.json + «unclassified»), as Android writes them. */
    public var bucketIds: [String] { mapper?.bucketIds ?? [CategoryMapper.UNCLASSIFIED] }

    /** The point the map opens at: the running measurement's, else the newest shared one's. */
    public func mapPoint() -> LocationNoise.Point? {
        session.live.sharedPoint ?? (try? measurements.latestSharedPoint()) ?? nil
    }
}
