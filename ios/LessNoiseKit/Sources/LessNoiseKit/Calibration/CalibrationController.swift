import Foundation

public enum CalMode: Equatable { case REFERENCE, CALIBRATOR, NOISE_FLOOR }

/**
 * One calibration measurement and the rules that keep it trustworthy (CalibrationRun.kt): a run is
 * aborted — and its result can never be saved — when the app goes to the background, when iOS
 * interrupts the microphone, or when digital silence shows up.
 */
public final class CalibrationRun {
    public static let REASON_BACKGROUND = "Abgebrochen: App war im Hintergrund. Bitte nochmals."
    public static let REASON_SILENCED = "Abgebrochen: Mikrofon war stumm (z. B. Anruf). Bitte nochmals."
    public static let REASON_INCOMPLETE = "Kalibrierung abgebrochen."

    public enum Outcome: Equatable {
        case done(CalibrationResult)
        case aborted(String)
    }

    public let mode: CalMode
    public let seconds: Int
    public let measurement: CalibrationMeasurement
    private let _reason = Locked<String?>(nil)

    public init(_ mode: CalMode, _ seconds: Int) {
        self.mode = mode
        self.seconds = seconds
        measurement = CalibrationMeasurement(durationSeconds: seconds, analyzeTone: mode == .CALIBRATOR)
    }

    public var abortReason: String? { _reason.get }

    /** Capture thread. */
    public func onBlock(_ samples: [Float]) {
        if abortReason != nil { return }
        measurement.process(samples)
        if measurement.invalidated { abort(CalibrationRun.REASON_SILENCED) }
    }

    public func onMicSilenced(_ silenced: Bool) { if silenced { abort(CalibrationRun.REASON_SILENCED) } }

    public func onAppStopped() { abort(CalibrationRun.REASON_BACKGROUND) }

    public func abort(_ reason: String) {
        let first = _reason.update { r -> Bool in if r == nil { r = reason; return true }; return false }
        if first { measurement.invalidate() }
    }

    public var shouldStop: Bool { abortReason != nil || measurement.isComplete }

    public func outcome() -> Outcome {
        if let r = abortReason { return .aborted(r) }
        if !measurement.isComplete || measurement.invalidated { return .aborted(CalibrationRun.REASON_INCOMPLETE) }
        return .done(measurement.result())
    }
}

/** The German texts of «Kalibrieren» (CalibrateScreen.kt, CalibrationTexts.kt). */
public enum CalibrationTexts {
    public static func saved(_ a: EventFloor.Adjustment) -> String { "Kalibrierung gespeichert (\(EventFloor.signed(a.deltaDb)) dB)." }
    public static let RESET = "Zurückgesetzt. Die Werte sind wieder Richtwerte."
    public static func minutes(_ n: Int) -> String { n == 1 ? "1 Minute" : "\(n) Minuten" }
    public static let RECAL_QUESTION = "Alte Messungen anpassen?"
    public static func recalScope(_ minutes: Int) -> String {
        "\(Self.minutes(minutes)) \(minutes == 1 ? "wird" : "werden") mit der neuen Kalibrierung umgerechnet. Die Originalwerte bleiben."
    }
    public static func recalDone(_ minutes: Int) -> String { "\(Self.minutes(minutes)) angepasst." }
    public static let STOP_FIRST = "Zuerst die Messung stoppen."
    public static let KEEP_OPEN = "App offen lassen, sonst bricht die Messung ab."
    public static let INTRO = "Nur nötig, wenn du ein Messgerät hast. Ohne Kalibrierung sind die Werte Richtwerte."

    public static func warning(_ w: CalibrationWarning) -> String {
        switch w {
        case .UNSTEADY: return "Zu unruhig. Nimm ein gleichmässigeres Geräusch."
        case .TOO_QUIET: return "Zu leise (unter 50 dB(A)). Nimm ein lauteres Geräusch."
        case .FREQUENCY_OFF: return "Kein 1-kHz-Ton. Sitzt der Kalibrator richtig?"
        case .NOT_TONAL: return "Signal ist kein sauberer Ton (Ankopplung prüfen, Umgebung leiser)."
        case .IMPLAUSIBLE_OFFSET: return "Korrektur über 20 dB – vermutlich ein Fehler."
        case .CLIPPING: return "Zu laut für das Telefon. Nimm 94 statt 114 dB."
        }
    }

    public static func methodName(_ m: String) -> String {
        switch m {
        case CalibrationEntity.METHOD_REFERENCE: return "Messgerät"
        case CalibrationEntity.METHOD_CALIBRATOR: return "Kalibrator"
        case CalibrationEntity.METHOD_MANUAL: return "von Hand"
        case CalibrationEntity.METHOD_RESET: return "zurückgesetzt"
        default: return m
        }
    }

    /** «Nicht kalibriert (Standardwert 112,35 dB).» / «Kalibriert am 15.10.2026 21:30: 121,90 dB (Messgerät)». */
    public static func activeLine(_ a: CalibrationEntity?, _ zone: TimeZone = .current) -> String {
        guard let a = a, a.isCalibrated else { return "Nicht kalibriert (Standardwert \(Fmt.db(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, 2)) dB)." }
        return "Kalibriert am \(Fmt.dateTime(a.createdEpochMs, zone)): \(Fmt.db(a.offsetDb, 2)) dB (\(methodName(a.method)))"
    }

    public static func recalFailed(_ m: String) -> String { "Anpassen fehlgeschlagen, nichts geändert: \(m)" }
    public static func recalRunning(_ done: Int, _ total: Int) -> String { total == 0 ? "Wird angepasst …" : "Wird angepasst … \(done) von \(total)" }
}

/** «Kalibrieren» state (CalUiState). */
public struct CalibrationUiState: Equatable {
    public var running: CalMode? = nil
    public var progress: Double = 0
    public var remainingSeconds = 0
    /** Last 1 s level with the currently active offset (live feedback). */
    public var currentDb: Double? = nil
    public var referenceResult: CalibrationResult? = nil
    /** Fixed at 30 s since 0.6.1. */
    public var referenceWindowSeconds = 30
    public var calibratorResult: CalibrationResult? = nil
    public var calibratorNominalDb = 94.0
    public var noiseFloorDb: Double? = nil
    public var message: String? = nil
    /** After saving: offer to re-evaluate earlier measurements with it. */
    public var recalOffer: RecalOffer? = nil
    public init() {}
}

public struct RecalOffer: Equatable {
    public let target: Recalibration.Target
    public let minutes: Int
    public let events: Int
}

/**
 * «Einstellungen › Erweitert › Kalibrieren» (CalibrationViewModel.kt): the three methods (sound
 * level meter 30 s, calibrator 10 s, by hand), reset, history, the event floor following the
 * calibration, and the offer to re-evaluate older measurements. Stored per phone model with the one
 * iOS audio source.
 */
public final class CalibrationController: AudioInputHandler {
    private let calibrations: CalibrationRepository
    private let settings: SettingsStore
    private let recalibrator: Recalibrator
    private let input: AudioInput
    private let isMeasuring: () -> Bool
    public let source: String

    private let _state = Locked(CalibrationUiState())
    public let changes = Observers()
    private var run: CalibrationRun?
    private let runLock = NSLock()
    private var lastPosted = -1
    /** True while a calibration holds the microphone («Starten» waits). */
    public private(set) var capturing = false

    public init(
        calibrations: CalibrationRepository, settings: SettingsStore, recalibrator: Recalibrator, input: AudioInput,
        isMeasuring: @escaping () -> Bool, source: String = AudioSource.IOS_MEASUREMENT
    ) {
        self.calibrations = calibrations; self.settings = settings; self.recalibrator = recalibrator; self.input = input
        self.isMeasuring = isMeasuring; self.source = source
    }

    public var state: CalibrationUiState { _state.get }
    private func update(_ body: (inout CalibrationUiState) -> Void) { _state.update(body); changes.notify() }

    public var active: CalibrationEntity? { (try? calibrations.active(source))?.entity }
    public var history: [CalibrationEntity] { (try? calibrations.history()) ?? [] }
    private var activeOffset: Double { (try? calibrations.active(source))?.offsetDb ?? Acoustics.DEFAULT_CALIBRATION_OFFSET_DB }

    public func setCalibratorNominal(_ db: Double) { update { $0.calibratorNominalDb = db } }
    public func clearMessage() { update { $0.message = nil } }

    public func start(_ mode: CalMode) {
        if state.running != nil { return }
        if isMeasuring() { update { $0.message = CalibrationTexts.STOP_FIRST }; return }
        let seconds: Int
        switch mode {
        case .REFERENCE: seconds = state.referenceWindowSeconds
        case .CALIBRATOR: seconds = 10
        case .NOISE_FLOOR: seconds = 20
        }
        let r = CalibrationRun(mode, seconds)
        runLock.lock(); run = r; lastPosted = -1; runLock.unlock()
        update {
            switch mode {
            case .REFERENCE: $0.referenceResult = nil
            case .CALIBRATOR: $0.calibratorResult = nil
            case .NOISE_FLOOR: $0.noiseFloorDb = nil
            }
            $0.running = mode; $0.progress = 0; $0.remainingSeconds = seconds; $0.currentDb = nil; $0.message = nil
        }
        do {
            capturing = true
            _ = try input.start(self)
        } catch {
            capturing = false
            runLock.lock(); run = nil; runLock.unlock()
            update { $0.running = nil; $0.message = "Mikrofon konnte nicht geöffnet werden: \((error as? LocalizedError)?.errorDescription ?? String(describing: error))" }
        }
    }

    // AudioInputHandler (capture thread).
    public func audioBlock(_ samples: UnsafeBufferPointer<Float>) {
        runLock.lock(); let r = run; runLock.unlock()
        guard let r = r else { return }
        r.onBlock(Array(samples))
        let m = r.measurement
        let elapsed = Int(m.progress * Double(r.seconds))
        var post = false
        runLock.lock()
        if elapsed != lastPosted || m.isComplete { lastPosted = elapsed; post = true }
        runLock.unlock()
        if post {
            let raw = m.lastSecondRawDb
            let offset = activeOffset
            update {
                $0.progress = Swift.min(Swift.max(m.progress, 0), 1)
                $0.remainingSeconds = Swift.max(r.seconds - elapsed, 0)
                $0.currentDb = raw.isNaN ? nil : raw + offset
            }
        }
        if r.shouldStop { DispatchQueue.global().async { self.finish(r.mode) } }
    }

    public func audioInterrupted(_ began: Bool) {
        runLock.lock(); let r = run; runLock.unlock()
        guard let r = r else { return }
        r.onMicSilenced(began)
        if r.shouldStop { DispatchQueue.global().async { self.finish(r.mode) } }
    }

    public func audioFailed(_ message: String) { abort("Mikrofon-Fehler: \(message)") }

    private let finishLock = NSLock()

    /** Ends the run (once): stops the microphone and keeps the result or the reason. */
    public func finish(_ mode: CalMode) {
        finishLock.lock(); defer { finishLock.unlock() }
        guard state.running == mode else { return }
        runLock.lock(); let current = run; run = nil; runLock.unlock()
        stopCapture()
        guard let r = current else { return }
        switch r.outcome() {
        case let .aborted(reason):
            update { $0.running = nil; $0.progress = 0; $0.message = reason }
        case let .done(result):
            let offset = activeOffset
            update {
                $0.running = nil
                switch mode {
                case .REFERENCE: $0.referenceResult = result
                case .CALIBRATOR: $0.calibratorResult = result
                case .NOISE_FLOOR: $0.noiseFloorDb = result.rawLaeqDb + offset
                }
            }
        }
    }

    private func stopCapture() {
        if capturing { input.stop(); capturing = false }
    }

    /** The app left the foreground: a calibration must not continue in the background. */
    public func onAppStopped() {
        runLock.lock(); let r = run; runLock.unlock()
        guard let r = r else { return }
        r.onAppStopped()
        finish(r.mode)
    }

    public func cancel() { abort(nil) }

    private func abort(_ message: String?) {
        runLock.lock(); run = nil; runLock.unlock()
        stopCapture()
        update { $0.running = nil; $0.progress = 0; $0.message = message }
    }

    public func referenceWarnings(_ referenceDb: Double?) -> [CalibrationWarning] {
        state.referenceResult.map { CalibrationMath.referenceWarnings($0, referenceDb) } ?? []
    }

    public func calibratorWarnings() -> [CalibrationWarning] {
        state.calibratorResult.map { CalibrationMath.calibratorWarnings($0, state.calibratorNominalDb) } ?? []
    }

    /** A calibration with [newOffset] is active now: the event floor follows; for a real one, offer the re-evaluation. */
    private func activated(_ id: Int, _ previousOffset: Double, _ newOffset: Double, calibrated: Bool) {
        var adjustment: EventFloor.Adjustment?
        settings.update { s in
            let a = EventFloor.adjust(s.eventMinLevelDb, previousOffset, newOffset, AppSettings.EVENT_MIN_LEVEL_MIN, AppSettings.EVENT_MIN_LEVEL_MAX)
            adjustment = a
            var n = s
            n.eventMinLevelDb = a.newFloorDb
            return n
        }
        var offer: RecalOffer? = nil
        if calibrated {
            let target = Recalibration.Target(calibrationId: id, offsetDb: newOffset, audioSource: source)
            let c = recalibrator.counts(target)
            if c.minutes + c.events > 0 { offer = RecalOffer(target: target, minutes: c.minutes, events: c.events) }
        }
        let message = calibrated ? CalibrationTexts.saved(adjustment!) : CalibrationTexts.RESET
        update { $0.message = message; $0.recalOffer = offer }
    }

    public func saveReference(_ referenceDb: Double, notes: String) {
        guard let r = state.referenceResult else { return }
        let offset = CalibrationMath.referenceOffset(referenceDb, r.rawLaeqDb)
        let previous = activeOffset
        guard let id = try? calibrations.save(
            source, offset, CalibrationEntity.METHOD_REFERENCE, notes, referenceDb: referenceDb, measuredRawDb: r.rawLaeqDb,
            stdDevDb: r.stdDevDb, durationSeconds: r.measuredSeconds, warnings: referenceWarnings(referenceDb).map { $0.rawValue }
        ) else { return }
        update { $0.referenceResult = nil }
        activated(id, previous, offset, calibrated: true)
    }

    public func saveCalibrator(notes: String) {
        guard let r = state.calibratorResult else { return }
        let nominal = state.calibratorNominalDb
        let offset = CalibrationMath.calibratorOffset(nominal, r.rawLaeqDb)
        let previous = activeOffset
        guard let id = try? calibrations.save(
            source, offset, CalibrationEntity.METHOD_CALIBRATOR, notes, measuredRawDb: r.rawLaeqDb, stdDevDb: r.stdDevDb,
            durationSeconds: r.measuredSeconds, calibratorNominalDb: nominal, toneFrequencyHz: r.toneFrequencyHz, tonality: r.tonality,
            warnings: calibratorWarnings().map { $0.rawValue }
        ) else { return }
        update { $0.calibratorResult = nil }
        activated(id, previous, offset, calibrated: true)
    }

    public func saveManual(_ offsetDb: Double, notes: String) {
        let previous = activeOffset
        guard let id = try? calibrations.save(
            source, offsetDb, CalibrationEntity.METHOD_MANUAL, notes,
            warnings: CalibrationMath.implausible(offsetDb) ? [CalibrationWarning.IMPLAUSIBLE_OFFSET.rawValue] : []
        ) else { return }
        activated(id, previous, offsetDb, calibrated: true)
    }

    public func resetToDefault() {
        let previous = activeOffset
        guard let id = try? calibrations.save(source, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, CalibrationEntity.METHOD_RESET, "") else { return }
        // Back to uncalibrated: the floor follows, nothing is re-evaluated.
        activated(id, previous, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, calibrated: false)
    }

    public func confirmRecalibration(completion: (() -> Void)? = nil) {
        guard let offer = state.recalOffer else { return }
        update { $0.recalOffer = nil }
        recalibrator.start(offer.target, completion: completion)
    }

    public func dismissRecalibration() { update { $0.recalOffer = nil } }
}
