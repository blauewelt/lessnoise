import Foundation

/** The dialogs of the share flow. */
public enum ShareDialog: Equatable {
    /** «Auf die Lärmkarte?»; [startMeasurement]: from «Starten» (else from the switch / status line). */
    case consent(startMeasurement: Bool)
    /** No fix for the running measurement: the options. */
    case locationOptions
    /** «Koordinaten einfügen». */
    case paste
}

/**
 * «Starten» never waits for the location (ShareFlow.kt of Android 0.6.3; server/DESIGN.md §2 rules
 * 2–3, §9):
 * 1. The first «Starten» asks ONE question, «Auf die Lärmkarte?» (asked once). Either answer starts
 *    the measurement at once; dismissing starts nothing.
 * 2. With sharing on, the measurement starts WITHOUT a point; while the app is in the foreground ONE
 *    fix runs in parallel and is moved at once by the location noise; the shifted point is attached
 *    to the running measurement, so all its minutes and events are sent. Never in the background:
 *    the fix is cancelled when the app leaves the foreground and tried again when it comes back.
 * 3. No fix: no dialog; the status line says «Kein Standort – tippen» and offers the options.
 * 4. Switching sharing on during a measurement shares that whole measurement the same way.
 */
public final class ShareController {
    public static let NO_POINT_HINT = "Für die Karte braucht die Messung einen Standort."

    private let session: MeasurementSession
    private let uploads: UploadCoordinator
    private let locator: Locator
    private let clock: AppClock

    private let _dialog = Locked<ShareDialog?>(nil)
    private let _locate = Locked<LocateState>(.idle)
    private let lock = NSLock()
    private var fixTask: Task<Void, Never>?
    private var appActive = true
    private var wasRunning = false
    public let changes = Observers()

    public init(session: MeasurementSession, uploads: UploadCoordinator, locator: Locator, clock: AppClock = SystemClock()) {
        self.session = session; self.uploads = uploads; self.locator = locator; self.clock = clock
    }

    public var dialog: ShareDialog? { _dialog.get }
    public var locate: LocateState { _locate.get }
    private var store: PersistentUploadStore { uploads.store }

    private func setDialog(_ d: ShareDialog?) { _dialog.set(d); changes.notify() }
    private func setLocate(_ s: LocateState) { _locate.set(s); changes.notify() }

    /** «Starten» (microphone allowed): the question once, then the measurement at once. */
    public func start() {
        let s = store.state
        if s.shareDecision == .UNDECIDED {
            setDialog(.consent(startMeasurement: true))
        } else if s.enabled {
            if session.start() { wantPoint(prompt: true) }
        } else {
            session.start()
        }
    }

    /** Sharing on from the switch ([ask] false: the question only while never answered) or the status line ([ask] true). */
    public func switchOn(ask: Bool) {
        if ask || store.state.shareDecision == .UNDECIDED {
            setDialog(.consent(startMeasurement: false))
        } else {
            enableAndLocate()
        }
    }

    /** Sharing off: uploads stop at once, and no fix is wanted any more. */
    public func switchOff() {
        cancelFix()
        uploads.disable()
        setLocate(.idle)
    }

    public func consentYes(startMeasurement: Bool) {
        setDialog(nil)
        if startMeasurement {
            uploads.enable()
            if session.start() { wantPoint(prompt: true) }
        } else {
            enableAndLocate()
        }
    }

    public func consentNo(startMeasurement: Bool) {
        setDialog(nil)
        let s = store.state
        if s.shareDecision != .NO || s.enabled { uploads.disable() }
        if startMeasurement { session.start() }
    }

    public func dismiss() { setDialog(nil) }

    /** A tap on the status line (the tab switches are handled by the screen). */
    public func onStatus(_ action: ShareAction) {
        switch action {
        case .TURN_ON: switchOn(ask: true)
        case .LOCATION_OPTIONS: setDialog(.locationOptions)
        case .NONE, .OPEN_MAP, .OPEN_SHARE: break
        }
    }

    public func showPaste() { setDialog(.paste) }

    private func enableAndLocate() {
        uploads.enable()
        let l = session.live
        if (l.running || l.starting) && l.sharedPoint == nil { wantPoint(prompt: true) }
    }

    /** A point for the running measurement is wanted («Nochmals» too). */
    public func wantPoint(prompt: Bool) {
        setLocate(.pending)
        tryLocate(prompt: prompt)
    }

    /** One fix if the permission allows it; [prompt]: show iOS's prompt first if it was never answered. */
    public func tryLocate(prompt: Bool) {
        setDialog(nil)
        switch locator.authorization {
        case .precise:
            runFix()
        case .approximate:
            setLocate(.failed(.approximateOnly))
        case .denied:
            setLocate(.failed(.permissionDenied))
        case .notDetermined:
            if prompt {
                locator.requestAuthorization { [weak self] in self?.tryLocate(prompt: false) }
            } else {
                setLocate(.failed(.permissionDenied))
            }
        }
    }

    private func noise() -> LocationPolicy.Noise {
        LocationPolicy.Noise(secret: store.noiseSecret(), sigmaM: store.state.sigmaM)!
    }

    private func cancelFix() {
        lock.lock(); fixTask?.cancel(); fixTask = nil; lock.unlock()
    }

    private func runFix() {
        lock.lock()
        let active = appActive
        lock.unlock()
        // Only while the app is in the foreground (the audio background mode keeps the process
        // alive behind the lock screen: this check is what keeps the location «when in use»).
        if !active { setLocate(.pending); return }
        cancelFix()
        setLocate(.locating)
        let task = Task { [weak self] in
            guard let self = self else { return }
            let r = await self.locator.oneFix(timeoutMs: LocationPolicy.TIMEOUT_MS, goodEnoughM: LocationPolicy.GOOD_ENOUGH_M)
            if Task.isCancelled { return }
            let o = LocationPolicy.outcome(r, precise: self.locator.authorization == .precise, noise: self.noise())
            if case let .shifted(point, _) = o {
                await self.attach(point)
            } else {
                self.setLocate(.failed(o))
            }
        }
        lock.lock(); fixTask = task; lock.unlock()
    }

    /** «Koordinaten einfügen» for the running measurement: moved by the location noise like a fix. */
    public func pasted(_ parsed: CellInput.Parsed) {
        setDialog(nil)
        guard let o = LocationPolicy.fromPasted(parsed, noise: noise()), case let .shifted(point, _) = o else { return }
        Task { await attach(point) }
    }

    /** Gives the running measurement its shifted point: the row and the live state, then the upload markers. */
    private func attach(_ point: LocationNoise.Point) async {
        let l = session.live
        if l.running && session.attachPoint(l.measurementStartMs, point) {
            await uploads.pointAttached(l.measurementStartMs)
            uploads.observe(session.live)
        }
        // Not attached: the measurement ended meanwhile (it stays unshared) or already had a point.
        setLocate(.idle)
    }

    /** The app came to the foreground: a fix that was wanted (or failed) is tried again, without a prompt. */
    public func onForeground() {
        lock.lock(); appActive = true; lock.unlock()
        let l = session.live
        let s = store.state
        let st = locate
        var retry = false
        switch st { case .pending, .failed: retry = true; default: break }
        if l.running && l.sharedPoint == nil && s.enabled && retry { tryLocate(prompt: false) }
    }

    /** The app left the foreground: no location while it is not open. */
    public func onBackground() {
        lock.lock()
        appActive = false
        let running = fixTask != nil
        fixTask?.cancel(); fixTask = nil
        lock.unlock()
        if running, case .locating = locate { setLocate(.pending) }
    }

    /** Every live-state change: when the measurement stops, nothing more to locate. */
    public func observe(_ l: LiveState) {
        lock.lock()
        let stopped = wasRunning && !(l.running || l.starting)
        wasRunning = l.running || l.starting
        lock.unlock()
        if stopped {
            cancelFix()
            setLocate(.idle)
            if dialog == .locationOptions || dialog == .paste { setDialog(nil) }
        }
    }

    /** The status line for «Messen» and «Teilen» right now. */
    public func status(online: Bool, hm: (Int) -> String) -> ShareStatus? {
        let l = session.live
        return shareStatus(store.state, running: l.running, measurementStartMs: l.measurementStartMs, hasPoint: l.sharedPoint != nil,
                           locate: locate, online: online, hm: hm)
    }
}
