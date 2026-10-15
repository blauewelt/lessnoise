import Foundation

/** Live values shown on «Messen» (LiveState in MeasurementService.kt). Only aggregates; never audio. */
public struct LiveState: Equatable {
    public var running = false
    public var starting = false
    public var startedAtMs = 0
    public var calibrated = false
    public var calibrationId: Int? = nil
    public var calibrationOffsetDb: Double? = nil
    /** LAF of the last valid 125 ms tick; nil while the audio is not evaluated. */
    public var lafDb: Double? = nil
    public var laeq60sDb: Double? = nil
    /** «Grundrauschen»: the 5-min background (L90), nil until enough history. */
    public var backgroundDb: Double? = nil
    public var localFloorDb: Double? = nil
    /** «Klingt nach»: the last classifier decision (nil: classifier off or nothing yet). */
    public var dominantCategory: String? = nil
    public var classifierEnabled = false
    /** The running measurement's shifted point if it is shared, else nil (attached after the fix). */
    public var sharedPoint: LocationNoise.Point? = nil
    /** Start of the running measurement's row; 0 when none runs. */
    public var measurementStartMs = 0
    /** iOS interrupted the microphone (call, Siri, another app): the time does not count. */
    public var micSilenced = false
    /** The current audio is not evaluated (interrupted, digital silence or the 0.5 s after it). */
    public var invalidAudio = false
    public var error: String? = nil
    /** The newest event of the running measurement (live upload of loud events, «Sehr laut»). */
    public var lastEvent: LiveEvent? = nil
    /** Hardware input description (diagnostics on «Einstellungen › Erweitert»). */
    public var inputInfo: String? = nil

    public init() {}
}

/** What the microphone adapter tells the session (ios/App/Audio/AudioCapture.swift implements the input). */
public protocol AudioInputHandler: AnyObject {
    /** One block of mono Float32 samples at 48 kHz, in order, from any one thread. */
    func audioBlock(_ samples: UnsafeBufferPointer<Float>)
    /** [began] true: iOS took the microphone (call, Siri, alarm …); false: capture runs again. */
    func audioInterrupted(_ began: Bool)
    /** Capture cannot continue (the measurement is stopped). */
    func audioFailed(_ message: String)
}

/** The microphone: AVAudioSession + AVAudioEngine on iOS, a scripted fake in the tests. */
public protocol AudioInput: AnyObject {
    /** Starts delivering blocks to [handler]; returns a short description of the input route. Throws a German message. */
    func start(_ handler: AudioInputHandler) throws -> String
    func stop()
}

public enum MeasureTexts {
    /** «Messen» before «Starten» (0.6.1). */
    public static let PLACEMENT_HINT = "Leg das Telefon ans offene Fenster. Für lange Messungen: ans Ladegerät."
    /** The microphone line (public edition: the moment of the microphone consent). */
    public static let MIC_LINE = "Die App braucht das Mikrofon. Es wird nie Audio gespeichert oder gesendet."
    public static let ABOUT_AUDIO = "Es wird nie Audio gespeichert oder gesendet."
    public static let SILENCED = "Mikrofon ist stumm (z. B. Anruf). Diese Zeit zählt nicht."
    public static let NO_SIGNAL = "Kein Mikrofonsignal. Diese Zeit zählt nicht."
    public static let NO_MIC_PERMISSION = "Ohne Mikrofon kann die App nicht messen."
    /** iOS: «Der Bildschirm darf aus sein.» holds with the audio background mode. */
    public static func runningSince(_ hm: String) -> String { "Läuft seit \(hm). Der Bildschirm darf aus sein." }
    public static let CALIBRATING = "Erst nach dem Kalibrieren möglich."
    public static let NONE_YET = "Noch keine."
}

/**
 * One measurement from «Starten» to «Stoppen» (MeasurementService.kt without the Android service):
 * the microphone feeds the [MeasurementEngine] on ONE serial queue (the core is single-threaded by
 * contract); minutes and events go to the database on a second queue; the live state is updated
 * for the screen. Privacy: the only audio held is the engine's own small buffers and, with a
 * classifier, one 0.975 s window during inference; nothing is written anywhere.
 *
 * Interruptions (iOS stops delivering audio during a call, Siri, an alarm): the engine is told the
 * microphone is silenced; when capture resumes, the missing time is filled with silenced zeros
 * (at most [MAX_GAP_FILL_MS]) so that the engine's timeline stays on the wall clock and the gap is
 * recorded as invalid time (coverage < 1) exactly like Android's silenced microphone.
 */
public final class MeasurementSession: AudioInputHandler {
    /** Longer gaps are left to the engine's clock re-anchoring (the minutes in between are missing). */
    public static let MAX_GAP_FILL_MS = 15 * 60_000
    /** Smaller differences between the sample clock and the wall clock are left to the engine. */
    public static let MIN_GAP_FILL_MS = 250

    private let repo: MeasurementRepository
    private let calibrations: CalibrationRepository
    private let settings: SettingsStore
    private let input: AudioInput
    private let classifier: SoundClassifier?
    private let mapper: CategoryMapper?
    private let clock: AppClock
    public let audioSource: String
    private let zone: () -> TimeZone

    private let engineQueue = DispatchQueue(label: "ch.lessnoise.engine")
    private let dbQueue = DispatchQueue(label: "ch.lessnoise.db")
    private let inferenceQueue = DispatchQueue(label: "ch.lessnoise.classifier", qos: .utility)

    private let _live = Locked(LiveState())
    public let changes = Observers()

    // Engine-queue state.
    private var engine: MeasurementEngine?
    private var rowId: Int?
    private var fillPending = false
    private let frames = Locked<[ClassifierFrame]>([])
    private let inferenceBusy = Locked(false)
    private var classifierWindow = [Float](repeating: 0, count: ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
    private let stateLock = NSLock()
    private var active = false

    public init(
        repo: MeasurementRepository, calibrations: CalibrationRepository, settings: SettingsStore, input: AudioInput,
        classifier: SoundClassifier?, mapper: CategoryMapper?, clock: AppClock = SystemClock(),
        audioSource: String = AudioSource.IOS_MEASUREMENT, zone: @escaping () -> TimeZone = { .current }
    ) {
        self.repo = repo; self.calibrations = calibrations; self.settings = settings; self.input = input
        self.classifier = classifier; self.mapper = mapper; self.clock = clock; self.audioSource = audioSource; self.zone = zone
    }

    public var live: LiveState { _live.get }

    private func updateLive(_ body: (inout LiveState) -> Void) {
        let changed = _live.update { s -> Bool in
            let old = s
            body(&s)
            return s != old
        }
        if changed { changes.notify() }
    }

    /** Sets an error line (e.g. no microphone permission) without starting. */
    public func showError(_ message: String?) { updateLive { $0.error = message } }

    /**
     * «Starten» (the microphone permission is granted): a new measurement row with [point] (nil:
     * not shared yet), then capture. False if one is already running or the start failed (the
     * live state's error says why).
     */
    @discardableResult
    public func start(point: LocationNoise.Point? = nil) -> Bool {
        stateLock.lock()
        if active { stateLock.unlock(); return false }
        active = true
        stateLock.unlock()
        updateLive { $0 = LiveState(); $0.starting = true }

        let s = settings.state
        let cal = (try? calibrations.active(audioSource)) ?? CalibrationRepository.Active(entity: nil)
        let classify = classifier != nil && mapper != nil
        let config = EngineConfig(
            calibrationOffsetDb: cal.offsetDb, calibrated: cal.calibrated, calibrationId: cal.id, audioSource: audioSource,
            eventExcessDb: s.eventExcessDb, eventMinLevelDb: s.eventMinLevelDb, classifierEnabled: classify, zone: zone()
        )
        let startedAt = clock.nowMs()
        let id: Int
        do {
            id = try dbQueue.sync { try repo.beginMeasurement(startedAt, point) }
        } catch {
            fail("Start fehlgeschlagen: Speicher nicht verfügbar.")
            return false
        }
        let listener = Listener(self)
        let eng = MeasurementEngine(config: config, mapper: classify ? mapper : nil, listener: listener, wallClock: { [clock] in clock.nowMs() })
        engineQueue.sync {
            self.engine = eng
            self.rowId = id
            self.fillPending = false
            self.frames.set([])
            self.inferenceBusy.set(false)
        }
        let route: String
        do {
            route = try input.start(self)
        } catch {
            engineQueue.sync { self.engine = nil }
            try? dbQueue.sync { try repo.endMeasurement(id, startedAt) }
            fail((error as? LocalizedError)?.errorDescription ?? String(describing: error))
            return false
        }
        updateLive {
            $0.starting = false
            $0.running = true
            $0.startedAtMs = startedAt
            $0.measurementStartMs = startedAt
            $0.calibrated = cal.calibrated
            $0.calibrationId = cal.id
            $0.calibrationOffsetDb = cal.offsetDb
            $0.classifierEnabled = classify
            $0.sharedPoint = point
            $0.inputInfo = route
        }
        return true
    }

    private func fail(_ message: String) {
        stateLock.lock(); active = false; stateLock.unlock()
        updateLive { $0 = LiveState(); $0.error = message }
    }

    /** «Stoppen»: capture off, the engine emits the partial minute and the open event, the row is closed. */
    public func stop() {
        stateLock.lock()
        if !active { stateLock.unlock(); return }
        stateLock.unlock()
        input.stop()
        var id: Int? = nil
        engineQueue.sync {
            if let eng = self.engine {
                for f in self.frames.update({ fs -> [ClassifierFrame] in let all = fs; fs = []; return all }) { eng.onClassifierFrame(f) }
                eng.stop()
            }
            self.engine = nil
            id = self.rowId
            self.rowId = nil
        }
        // Wait for the inference queue (a window in flight is dropped) and the database inserts.
        inferenceQueue.sync {}
        let endAt = clock.nowMs()
        dbQueue.sync {
            if let id = id { try? repo.endMeasurement(id, endAt) }
        }
        stateLock.lock(); active = false; stateLock.unlock()
        let err = live.error
        updateLive { $0 = LiveState(); $0.error = err }
    }

    /**
     * The running measurement (started at [startMs]) gets its shifted point: the row, then the live
     * state. False if it already ended, already had a point, or another one runs now.
     */
    public func attachPoint(_ startMs: Int, _ point: LocationNoise.Point) -> Bool {
        let l = live
        guard l.running, l.measurementStartMs == startMs, l.sharedPoint == nil else { return false }
        let ok = (try? dbQueue.sync { try repo.attachPoint(startMs, point) }) ?? false
        if ok { updateLive { if $0.running && $0.measurementStartMs == startMs { $0.sharedPoint = point } } }
        return ok
    }

    /** Waits until queued engine work and database writes are done (tests, and before reading the database). */
    public func drain() {
        engineQueue.sync {}
        inferenceQueue.sync {}
        engineQueue.sync {}
        dbQueue.sync {}
    }

    // ---- AudioInputHandler (any thread) --------------------------------------------------------

    public func audioBlock(_ samples: UnsafeBufferPointer<Float>) {
        let block = Array(samples)
        engineQueue.async { self.process(block) }
    }

    public func audioInterrupted(_ began: Bool) {
        if began {
            engineQueue.sync { self.engine?.setMicSilenced(true) }
            updateLive { $0.micSilenced = true; $0.invalidAudio = true; $0.lafDb = nil }
        } else {
            // The silenced flag stays until the gap is filled with the first block after the resume.
            engineQueue.async { self.fillPending = true }
            updateLive { $0.micSilenced = false }
        }
    }

    public func audioFailed(_ message: String) {
        updateLive { $0.error = "Mikrofon-Fehler: \(message)" }
        DispatchQueue.global().async { self.stop() }
    }

    // ---- Engine queue ---------------------------------------------------------------------------

    private func process(_ block: [Float]) {
        guard let eng = engine else { return }
        if fillPending {
            fillPending = false
            fillGap(eng)
            eng.setMicSilenced(false)
        }
        // Finished classifications first, so an event ending in this block can use them.
        for f in frames.update({ fs -> [ClassifierFrame] in let all = fs; fs = []; return all }) { eng.onClassifierFrame(f) }
        eng.process(block)
        runClassifierIfDue(eng)
    }

    /** Feeds silenced zeros for the time iOS delivered nothing (see the type doc). */
    private func fillGap(_ eng: MeasurementEngine) {
        guard eng.totalSamples > 0 else { return }
        let gapMs = clock.nowMs() - eng.epochMsAt(eng.totalSamples)
        if gapMs < MeasurementSession.MIN_GAP_FILL_MS { return }
        var remaining = Swift.min(gapMs, MeasurementSession.MAX_GAP_FILL_MS) * Acoustics.SAMPLE_RATE / 1000
        eng.setMicSilenced(true)
        let zeros = [Float](repeating: 0, count: 4800)
        while remaining > 0 {
            let n = Swift.min(remaining, zeros.count)
            eng.process(zeros, n)
            remaining -= n
        }
    }

    private func runClassifierIfDue(_ eng: MeasurementEngine) {
        guard let cls = classifier, mapper != nil, eng.classifierDue() else { return }
        let free = inferenceBusy.update { b -> Bool in if b { return false }; b = true; return true }
        if !free { return }
        let end = eng.copyClassifierWindow(&classifierWindow)
        if end < 0 { inferenceBusy.set(false); return }
        var window = classifierWindow
        let length = eng.classifierWindowLength48k
        inferenceQueue.async {
            let gain = ClassifierPreprocessor.normalize(&window)
            let scores = cls.classify(window)
            if !scores.isEmpty { self.frames.update { $0.append(ClassifierFrame(endSample: end, windowSamples48k: length, scores: scores, inputGainDb: gain)) } }
            for i in window.indices { window[i] = 0 }
            self.inferenceBusy.set(false)
        }
    }

    // Engine callbacks (engine queue).

    fileprivate func onTick(_ t: LafTick) {
        updateLive { $0.lafDb = t.valid ? t.lafDb : nil; $0.invalidAudio = !t.valid }
    }

    fileprivate func onSecond(_ s: SecondResult) {
        updateLive {
            $0.laeq60sDb = s.laeqRunning60sDb.isNaN ? nil : s.laeqRunning60sDb
            $0.backgroundDb = s.backgroundDb.isNaN ? nil : s.backgroundDb
            $0.localFloorDb = s.localFloorDb.isNaN ? nil : s.localFloorDb
        }
    }

    fileprivate func onMinute(_ m: MinuteRecord) {
        dbQueue.async { _ = try? self.repo.insert(m) }
    }

    fileprivate func onEvent(_ e: NoiseEvent) {
        dbQueue.async { _ = try? self.repo.insert(e) }
        updateLive {
            $0.lastEvent = LiveEvent(
                startEpochMs: e.startEpochMs, endEpochMs: e.startEpochMs + Int(e.durationSeconds * 1000), lafMaxDb: e.lafMaxDb,
                category: e.dominantCategory, wind: e.wind, calibrated: e.calibrated
            )
        }
    }

    fileprivate func onClassification(_ d: CategoryDecision) {
        updateLive { $0.dominantCategory = d.dominant }
    }

    private final class Listener: MeasurementEngineListener {
        weak var session: MeasurementSession?
        init(_ s: MeasurementSession) { session = s }
        func onTick(_ tick: LafTick) { session?.onTick(tick) }
        func onSecond(_ second: SecondResult) { session?.onSecond(second) }
        func onMinute(_ minute: MinuteRecord) { session?.onMinute(minute) }
        func onEvent(_ event: NoiseEvent) { session?.onEvent(event) }
        func onClassification(_ endEpochMs: Int, _ decision: CategoryDecision, _ top: [LabelScore]) { session?.onClassification(decision) }
    }
}
