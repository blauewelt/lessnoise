import Foundation

/** The database as the uploader reads it (RoomUploadSource of Android). */
public final class DatabaseUploadSource: UploadSource {
    private let repo: MeasurementRepository
    private let calibrations: CalibrationRepository
    private let audioSource: String

    public init(_ repo: MeasurementRepository, _ calibrations: CalibrationRepository, audioSource: String = AudioSource.IOS_MEASUREMENT) {
        self.repo = repo; self.calibrations = calibrations; self.audioSource = audioSource
    }

    public func minutesAfter(_ afterMs: Int, _ limit: Int) async -> [MinuteRecord] { (try? repo.minutesAfter(afterMs, limit)) ?? [] }
    public func eventsAfter(_ afterMs: Int, _ limit: Int) async -> [NoiseEvent] { (try? repo.eventsAfter(afterMs, limit)) ?? [] }

    public func measurements() async -> [MeasurementRow] {
        ((try? repo.allMeasurements()) ?? []).map { MeasurementRow(id: $0.id, startMs: $0.startMs, endMs: $0.endMs, point: $0.point) }
    }

    public func siteExtras() async -> SiteExtras {
        let a = (try? calibrations.active(audioSource)) ?? CalibrationRepository.Active(entity: nil)
        return SiteExtras(deviceModel: calibrations.deviceModel, audioSource: audioSource, calibrated: a.calibrated, calibrationOffsetDb: a.offsetDb)
    }
}

/** The network as the upload sees it (NWPathMonitor on iOS). */
public protocol NetworkStatus: AnyObject {
    /** Any path to the internet. */
    var online: Bool { get }
    /** The current path is cellular / a hotspot / Low Data Mode (`isExpensive || isConstrained`). */
    var expensive: Bool { get }
}

public final class AlwaysOnline: NetworkStatus {
    public init() {}
    public var online: Bool { true }
    public var expensive: Bool { false }
}

/**
 * The hourly fallback outside a measurement (iOS: a BGAppRefreshTask whose earliest begin date is
 * [UploadCadence.delayToNextHourMs]; iOS decides when, or whether, it runs).
 */
public protocol BackgroundUploadScheduler: AnyObject {
    func schedule(earliestInMs: Int)
    func cancel()
}

/** Keeps the app alive for a short upload when it goes to the background (UIApplication.beginBackgroundTask). */
public protocol BackgroundActivity: AnyObject {
    func begin(_ name: String) -> Int
    func end(_ token: Int)
}

public final class NoBackgroundActivity: BackgroundActivity {
    public init() {}
    public func begin(_ name: String) -> Int { 0 }
    public func end(_ token: Int) {}
}

/**
 * When the app uploads (UploadModule of Android 0.6.3, server/DESIGN.md §4.6):
 * - while a SHARED measurement runs (it has its point, sharing is on, the token works): the live
 *   uploads of [LiveUploadPlan] — first 30 s after the point, then every 2 min, a loud event 30 s
 *   after it ended, back-off after 429 — in this process, which iOS keeps running through the
 *   audio background mode;
 * - once 15 s after «Stoppen»; soon after switching sharing on, a report, a placement change;
 * - every app start, and the hourly BGAppRefreshTask fallback (when iOS grants it).
 * «Nur über WLAN»: nothing is sent over an expensive or constrained path.
 * Nothing is lost when a run does not happen: the markers move only on acknowledgement.
 */
public final class UploadCoordinator {
    public let uploader: Uploader
    public let store: PersistentUploadStore
    private let network: NetworkStatus
    private let clock: AppClock
    private let scheduler: BackgroundUploadScheduler?
    private let activity: BackgroundActivity
    private let zone: () -> TimeZone

    private let lock = NSLock()
    private var soonTask: Task<Void, Never>?
    private var soonAtMs = Int.max
    private var liveTask: Task<Void, Never>?
    private var liveStart: Int?
    private var plan: LiveUploadPlan?
    private var lastEventEnd: Int?
    private var wasRunning = false

    /** For tests and the screen: the runs and their results. */
    public private(set) var runLog = Locked<[(atMs: Int, kind: String, result: String)]>([])

    public init(
        uploader: Uploader, store: PersistentUploadStore, network: NetworkStatus, clock: AppClock = SystemClock(),
        scheduler: BackgroundUploadScheduler? = nil, activity: BackgroundActivity = NoBackgroundActivity(),
        zone: @escaping () -> TimeZone = { .current }
    ) {
        self.uploader = uploader; self.store = store; self.network = network; self.clock = clock
        self.scheduler = scheduler; self.activity = activity; self.zone = zone
    }

    /** Whether an upload may go now: a connection, and no expensive path with «Nur über WLAN». */
    public func networkAllowed() -> Bool {
        network.online && !(store.state.wifiOnly && network.expensive)
    }

    /** One run now (also the BGAppRefreshTask's); [withMinutes] false: the events-only run of the live map. */
    @discardableResult
    public func runNow(withMinutes: Bool = true, kind: String = "now") async -> Uploader.Outcome {
        if !store.state.enabled { return .disabled }
        if !networkAllowed() {
            if !network.online { store.update { var n = $0; n.lastError = UploadRules.NO_CONNECTION_TEXT; return n } }
            log(kind, "no network")
            return .retry("no network", rateLimited: false)
        }
        let token = activity.begin("upload")
        defer { activity.end(token) }
        let o = await uploader.run(withMinutes: withMinutes)
        log(kind, "\(o)")
        if case let .done(_, _, _, more, _) = o, more { uploadSoon(delayMs: 30 * 60_000) }
        return o
    }

    private func log(_ kind: String, _ result: String) {
        let now = clock.nowMs()
        runLog.update { l in l.append((now, kind, result)); if l.count > 200 { l.removeFirst(l.count - 200) } }
    }

    /** One upload soon; an earlier pending one stays (Android: the unique «upload_now» job). */
    public func uploadSoon(delayMs: Int = 15_000) {
        if !store.state.enabled { return }
        let at = clock.nowMs() + delayMs
        lock.lock()
        if soonTask != nil && soonAtMs <= at { lock.unlock(); return }
        soonTask?.cancel()
        soonAtMs = at
        soonTask = Task { [weak self] in
            guard let self = self else { return }
            // The app may go to the background meanwhile (e.g. right after «Stoppen»): ask iOS for the time.
            let token = self.activity.begin("upload-soon")
            defer { self.activity.end(token) }
            do { try await self.clock.sleep(ms: delayMs) } catch { return }
            self.locked { self.soonTask = nil; self.soonAtMs = Int.max }
            _ = await self.runNow(kind: "soon")
        }
        lock.unlock()
    }

    /** The hourly fallback in line with the switch (on: scheduled; off: cancelled; and the pending runs go). */
    public func applySchedule() {
        if store.state.enabled {
            scheduler?.schedule(earliestInMs: UploadCadence.delayToNextHourMs(clock.nowMs(), zone()))
        } else {
            scheduler?.cancel()
            lock.lock(); soonTask?.cancel(); soonTask = nil; soonAtMs = Int.max; lock.unlock()
        }
    }

    /** App start (and app coming to the foreground): the schedule, and one run if anything waits. */
    public func appStarted() {
        applySchedule()
        if store.state.enabled { uploadSoon(delayMs: 2_000) }
    }

    /** Sharing on (consent, switch, report consent): registers soon so «Vom Server löschen» works from the first minute. */
    public func enable() {
        uploader.enable()
        applySchedule()
        uploadSoon(delayMs: 5_000)
    }

    public func disable() {
        uploader.disable()
        applySchedule()
        stopLive()
    }

    public func pointAttached(_ measurementStartMs: Int) async {
        await uploader.pointAttached(measurementStartMs)
    }

    /**
     * Called on every change of the live state (and of the upload state): starts and stops the
     * live loop, feeds it the loud events, and uploads once after «Stoppen».
     */
    public func observe(_ l: LiveState) {
        let s = store.state
        lock.lock()
        let stopped = wasRunning && !l.running
        wasRunning = l.running
        let want: Int? = (l.running && l.sharedPoint != nil && s.enabled && !s.authFailed) ? l.measurementStartMs : nil
        var startLoop = false
        if want != liveStart {
            liveTask?.cancel()
            liveTask = nil
            plan = nil
            liveStart = want
            startLoop = want != nil
        }
        if let e = l.lastEvent, !e.wind, e.endEpochMs != lastEventEnd {
            lastEventEnd = e.endEpochMs
            plan?.onEvent(e.endEpochMs)
        }
        if startLoop {
            let p = LiveUploadPlan(pointAtMs: clock.nowMs())
            plan = p
            liveTask = Task { [weak self] in await self?.liveLoop(p) }
        }
        lock.unlock()
        if stopped { uploadSoon(delayMs: UploadCadence.AFTER_STOP_MS) }
    }

    private func stopLive() {
        lock.lock()
        liveTask?.cancel(); liveTask = nil; plan = nil; liveStart = nil
        lock.unlock()
    }

    private func next(_ p: LiveUploadPlan) -> LiveUploadPlan.Run { locked { p.next() } }

    private func locked<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }

    private func liveLoop(_ p: LiveUploadPlan) async {
        while !Task.isCancelled {
            var run = next(p)
            // Wait for the run's time, looking once a second for an earlier events run.
            while true {
                let now = clock.nowMs()
                run = next(p)
                if run.atMs <= now { break }
                do { try await clock.sleep(ms: Swift.min(run.atMs - now, 1000)) } catch { return }
            }
            if Task.isCancelled { return }
            let result: LiveUploadPlan.Result
            switch await runNow(withMinutes: run.kind == .FULL, kind: run.kind == .FULL ? "live" : "live-events") {
            case .done: result = .OK
            case let .retry(_, rateLimited): result = rateLimited ? .RATE_LIMITED : .FAILED
            default: result = .FAILED
            }
            let now = clock.nowMs()
            locked { p.done(run, result, now) }
        }
    }

    /** «Vom Server löschen» / «Neu starten»: the live loop stops first. */
    public func deleteServerData() async -> Uploader.DeleteOutcome {
        stopLive()
        let r = await uploader.deleteServerData()
        applySchedule()
        return r
    }

    public func newIdentity() async -> Uploader.DeleteOutcome {
        let r = await uploader.newIdentity()
        applySchedule()
        return r
    }
}
