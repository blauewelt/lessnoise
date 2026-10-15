import Foundation

/** The engine's settings (EngineConfig in MeasurementEngine.kt; same defaults). */
public struct EngineConfig {
    public static let DEFAULT_CLASSIFIER_INTERVAL_SECONDS = 1.0
    /** Filters noise at the phone itself (typing, breathing); quiet pass-bys (≈ 35–40 dB) stay. */
    public static let DEFAULT_EVENT_MIN_LEVEL_DB = 30.0
    /** Vehicle passes: ≥ 6.5 dB over the local floor. */
    public static let DEFAULT_EVENT_EXCESS_DB = 6.5
    public static let DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS = 30.0

    public var sampleRate: Int
    public var calibrationOffsetDb: Double
    public var calibrated: Bool
    public var calibrationId: Int?
    public var audioSource: String
    public var eventExcessDb: Double
    public var eventHysteresisDb: Double
    public var eventMinDurationSeconds: Double
    public var eventMaxDurationSeconds: Double
    public var eventMinLevelDb: Double
    public var backgroundWindowSeconds: Double
    public var backgroundMinHistorySeconds: Double
    public var localFloorWindowSeconds: Double
    public var localFloorMinHistorySeconds: Double
    public var windLfShareMin: Double
    public var windFlutterMinDb: Double
    public var eventTailSeconds: Double
    public var classifierEnabled: Bool
    public var classifierIntervalSeconds: Double
    public var eventClassificationTimeoutSeconds: Double
    public var invalidGuardSeconds: Double
    public var maxClockDriftMs: Int
    public var zone: TimeZone

    public init(
        sampleRate: Int = Acoustics.SAMPLE_RATE,
        calibrationOffsetDb: Double = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB,
        calibrated: Bool = false,
        calibrationId: Int? = nil,
        audioSource: String = "unknown",
        eventExcessDb: Double = EngineConfig.DEFAULT_EVENT_EXCESS_DB,
        eventHysteresisDb: Double = 3.0,
        eventMinDurationSeconds: Double = 0.5,
        eventMaxDurationSeconds: Double = 300.0,
        eventMinLevelDb: Double = EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB,
        backgroundWindowSeconds: Double = 300.0,
        backgroundMinHistorySeconds: Double = 30.0,
        localFloorWindowSeconds: Double = EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS,
        localFloorMinHistorySeconds: Double = 5.0,
        windLfShareMin: Double = WindRule.DEFAULT_LF_SHARE,
        windFlutterMinDb: Double = WindRule.DEFAULT_FLUTTER_DB,
        eventTailSeconds: Double = 5.0,
        classifierEnabled: Bool = true,
        classifierIntervalSeconds: Double = EngineConfig.DEFAULT_CLASSIFIER_INTERVAL_SECONDS,
        eventClassificationTimeoutSeconds: Double = 3.0,
        invalidGuardSeconds: Double = 0.5,
        maxClockDriftMs: Int = 500,
        zone: TimeZone = .current
    ) {
        precondition(sampleRate % 8 == 0, "sample rate must be divisible by 8 (125 ms ticks)")
        precondition(sampleRate % 3 == 0, "sample rate must be divisible by 3 (classifier decimation)")
        precondition(localFloorWindowSeconds > 0 && localFloorMinHistorySeconds > 0)
        precondition(eventTailSeconds >= 0)
        self.sampleRate = sampleRate; self.calibrationOffsetDb = calibrationOffsetDb; self.calibrated = calibrated
        self.calibrationId = calibrationId; self.audioSource = audioSource; self.eventExcessDb = eventExcessDb
        self.eventHysteresisDb = eventHysteresisDb; self.eventMinDurationSeconds = eventMinDurationSeconds
        self.eventMaxDurationSeconds = eventMaxDurationSeconds; self.eventMinLevelDb = eventMinLevelDb
        self.backgroundWindowSeconds = backgroundWindowSeconds; self.backgroundMinHistorySeconds = backgroundMinHistorySeconds
        self.localFloorWindowSeconds = localFloorWindowSeconds; self.localFloorMinHistorySeconds = localFloorMinHistorySeconds
        self.windLfShareMin = windLfShareMin; self.windFlutterMinDb = windFlutterMinDb; self.eventTailSeconds = eventTailSeconds
        self.classifierEnabled = classifierEnabled; self.classifierIntervalSeconds = classifierIntervalSeconds
        self.eventClassificationTimeoutSeconds = eventClassificationTimeoutSeconds; self.invalidGuardSeconds = invalidGuardSeconds
        self.maxClockDriftMs = maxClockDriftMs; self.zone = zone
    }
}

/** What the engine reports (MeasurementEngine.Listener). Every method has a no-op default. */
public protocol MeasurementEngineListener: AnyObject {
    func onTick(_ tick: LafTick)
    func onSecond(_ second: SecondResult)
    func onMinute(_ minute: MinuteRecord)
    func onEvent(_ event: NoiseEvent)
    func onClassification(_ endEpochMs: Int, _ decision: CategoryDecision, _ top: [LabelScore])
    /** The sample clock was re-anchored by [correctionMs] (wall − sample time). */
    func onClockCorrection(_ correctionMs: Int)
    func onClockAnchored(_ anchorSample: Int, _ anchorEpochMs: Int)
    func onEventCandidate(_ startSample: Int)
    func onEventConfirmed(_ startSample: Int)
    func onEventDiscarded(_ startSample: Int)
    func onEventClosed(_ startSample: Int, _ endSample: Int, _ features: EventFeatures)
    /** The event with its input-sample span; by default forwards to [onEvent]. */
    func onEventEmitted(_ event: NoiseEvent, _ startSample: Int, _ endSample: Int)
    func onClassifierResult(_ result: ClassifierResult)
}

public extension MeasurementEngineListener {
    func onTick(_ tick: LafTick) {}
    func onSecond(_ second: SecondResult) {}
    func onMinute(_ minute: MinuteRecord) {}
    func onEvent(_ event: NoiseEvent) {}
    func onClassification(_ endEpochMs: Int, _ decision: CategoryDecision, _ top: [LabelScore]) {}
    func onClockCorrection(_ correctionMs: Int) {}
    func onClockAnchored(_ anchorSample: Int, _ anchorEpochMs: Int) {}
    func onEventCandidate(_ startSample: Int) {}
    func onEventConfirmed(_ startSample: Int) {}
    func onEventDiscarded(_ startSample: Int) {}
    func onEventClosed(_ startSample: Int, _ endSample: Int, _ features: EventFeatures) {}
    func onEventEmitted(_ event: NoiseEvent, _ startSample: Int, _ endSample: Int) { onEvent(event) }
    func onClassifierResult(_ result: ClassifierResult) {}
}

/**
 * The measurement pipeline: a line-by-line port of android/dsp MeasurementEngine.kt (see that
 * file for the full description). Input: mono float blocks at [EngineConfig.sampleRate] (48 kHz)
 * in any block size. Per sample: A-weighting → square → Fast (125 ms) exponential averaging; Z
 * mean square; the feature band splits; (classifier on) decimation to a 1 s, 16 kHz ring. Every
 * 125 ms tick: validity, LAF, event detection; every second: LAeq,1s; at each wall-clock minute:
 * a [MinuteRecord] (held back while an event that started in it is still undecided).
 *
 * Privacy: the only audio held is the decimator history (241 samples) and the 1 s classifier ring.
 * Nothing here writes audio anywhere.
 *
 * Not thread-safe except for [setMicSilenced]: call everything else from the audio thread.
 */
public final class MeasurementEngine {
    public let config: EngineConfig
    private let mapper: CategoryMapper?
    private let listener: MeasurementEngineListener
    private let wallClock: () -> Int

    private let fs: Int
    private let offset: Double
    private let tickLen: Int
    private let warmupTicks = 4 // 0.5 s: lets the A-filter and the Fast integrator settle
    private let guardSamples: Int

    private let aWeighting: AWeighting
    private let fast: ExponentialTimeWeighting
    private let decimator: DecimatingResampler
    private let classifierRing: FloatRingBuffer
    private let classifierWindowSamples48k: Int

    private let classifierActive: Bool
    private let classifierIntervalSamples: Int
    private var lastClassifierSample = Int.min / 2
    private var forceClassification = false

    public private(set) var totalSamples = 0
    private var tickCount = 0

    // Clock
    private var anchored = false
    private var anchorSample = 0
    private var anchorMs = 0
    private var minDriftMs = Int.max

    // Validity
    private let silencedLock = NSLock()
    private var _micSilenced = false
    private var micSilenced: Bool {
        silencedLock.lock(); defer { silencedLock.unlock() }
        return _micSilenced
    }
    private var silencedSeenInTick = false
    private var zeroRun = 0
    private var tickMaxZeroRun = 0
    private var guardUntilSample = Int.min
    /** Recent invalid intervals [start, end) in samples, oldest first (pruned to ~10 s). */
    private var invalidIntervals: [(start: Int, end: Int)] = []

    // Tick accumulators
    private var tickPos = 0
    private var tickSumA = 0.0
    private var tickSumZ = 0.0
    private var tickMaxY = 0.0
    private let featureFilters: FeatureFilters
    private var tickSumBand = 0.0
    private var tickSumLow = 0.0
    private var tickSumTotal = 0.0

    // Second accumulators (valid ticks only, except the tick counter)
    private var secTicks = 0
    private var secValidTicks = 0
    private var secSumA = 0.0
    private var secSumZ = 0.0
    private var secMaxY = 0.0
    private let last60s = DoubleRing(capacity: 60)

    // Minute accumulators
    private var minuteStartMs = 0
    private var nextMinuteBoundaryMs = 0
    private var minuteSumA = 0.0
    private var minuteSamples = 0
    private var minuteValidTicks = 0
    private var minuteMaxY = 0.0
    private var minuteLafs: [Double] = []
    /** Local floor of every valid tick of the minute (for its median). */
    private var minuteFloors: [Double] = []
    private var minuteClockCorrections = 0
    private let bucketIds: [String]
    private var minuteBucketCounts: [Int]
    private var minuteFrames = 0
    private var recording = false

    /** Event counts of a minute (see MeasurementEngine.kt: minutes are held until their events are decided). */
    private final class MinuteCounts {
        var events = 0
        var wind = 0
        var undecided = 0
        var record: MinuteRecord?
    }

    private var curMinute = MinuteCounts()
    private var heldMinutes: [MinuteCounts] = []

    private let background: BackgroundEstimator
    private let localFloor: LocalFloorEstimator
    private let windRule: WindRule
    private let tailTicks: Int
    private let tracker: EventFeatureTracker
    /** Index (in [tracker]) of the tick being evaluated. */
    private var currentTickIndex = -1

    // Event classification bookkeeping
    private final class EventAcc {
        let startSample: Int
        var sum: [Float]
        var frames = 0
        var detected: DetectedEvent?
        let minute: MinuteCounts
        let startTick: Int
        /** Tick index after the event's last tick (exclusive). */
        var endTick = -1
        /** Highest LAF tick value of the event (for the tail's 10 % level). */
        var curvePeakDb = Double.nan
        /** Set at the end of the tail; the event is emitted only then. */
        var features: EventFeatures?

        init(_ startSample: Int, _ labelCount: Int, _ minute: MinuteCounts, _ startTick: Int) {
            self.startSample = startSample
            sum = [Float](repeating: 0, count: labelCount)
            self.minute = minute
            self.startTick = startTick
        }
    }

    /** The closed event whose tail (decay) is still being followed, if any. */
    private var tailAcc: EventAcc?

    // Recent tick levels (diagnostics only: LAF of classifier windows in onClassifierResult).
    private let recentTicks = 96 // 12 s
    private var recentTickEnd: [Int]
    private var recentTickLaf: [Double]
    private var recentTickLafMax: [Double]
    private var recentTickPos = 0

    private var activeAcc: EventAcc?
    private var pending: [EventAcc] = []
    private let labelCount: Int

    private var detector: EventDetector!
    private var detectorAdapter: DetectorAdapter!

    /** Bridges the detector's callbacks into the engine (Kotlin: an anonymous object). */
    private final class DetectorAdapter: EventDetectorListener {
        unowned let engine: MeasurementEngine
        init(_ engine: MeasurementEngine) { self.engine = engine }
        func onCandidateStart(_ startSample: Int) { engine.detectorCandidateStart(startSample) }
        func onConfirmed(_ startSample: Int) { engine.listener.onEventConfirmed(startSample) }
        func onDiscarded(_ startSample: Int) { engine.detectorDiscarded(startSample) }
        func onClosed(_ event: DetectedEvent) { engine.detectorClosed(event) }
    }

    public init(config: EngineConfig, mapper: CategoryMapper?, listener: MeasurementEngineListener, wallClock: @escaping () -> Int = { Int(Date().timeIntervalSince1970 * 1000) }) {
        self.config = config
        self.mapper = mapper
        self.listener = listener
        self.wallClock = wallClock
        fs = config.sampleRate
        offset = config.calibrationOffsetDb
        tickLen = fs / 8
        guardSamples = JavaMath.toLong(config.invalidGuardSeconds * Double(fs))
        aWeighting = AWeighting(sampleRate: fs)
        fast = ExponentialTimeWeighting(tauSeconds: ExponentialTimeWeighting.FAST_TAU, sampleRate: fs)
        decimator = DecimatingResampler(inputRate: fs, factor: 3)
        classifierRing = FloatRingBuffer(capacity: fs / 3) // 1 s at 16 kHz
        classifierWindowSamples48k = ClassifierPreprocessor.YAMNET_INPUT_SAMPLES * 3
        classifierActive = config.classifierEnabled && mapper != nil
        classifierIntervalSamples = JavaMath.toLong(config.classifierIntervalSeconds * Double(fs))
        featureFilters = FeatureFilters(sampleRate: fs)
        minuteLafs.reserveCapacity(1024)
        minuteFloors.reserveCapacity(1024)
        bucketIds = mapper?.bucketIds ?? []
        minuteBucketCounts = [Int](repeating: 0, count: bucketIds.count)
        background = BackgroundEstimator(windowSeconds: config.backgroundWindowSeconds, minHistorySeconds: config.backgroundMinHistorySeconds, tickSeconds: 0.125)
        localFloor = LocalFloorEstimator(windowSeconds: config.localFloorWindowSeconds, minHistorySeconds: config.localFloorMinHistorySeconds, tickSeconds: 0.125)
        windRule = WindRule(lfShareMin: config.windLfShareMin, flutterMinDb: config.windFlutterMinDb)
        tailTicks = JavaMath.toInt(ceil(config.eventTailSeconds * 8 - 1e-9))
        tracker = EventFeatureTracker(
            capacityTicks: 80 + JavaMath.toInt(config.eventMaxDurationSeconds * 8) + tailTicks + 16, tickSeconds: 0.125, preTicks: 80
        )
        recentTickEnd = [Int](repeating: Int.min, count: recentTicks)
        recentTickLaf = [Double](repeating: 0, count: recentTicks)
        recentTickLafMax = [Double](repeating: 0, count: recentTicks)
        labelCount = mapper?.labels.count ?? 0
        detectorAdapter = DetectorAdapter(self)
        detector = EventDetector(
            excessDb: config.eventExcessDb,
            hysteresisDb: config.eventHysteresisDb,
            minDurationSeconds: config.eventMinDurationSeconds,
            maxDurationSeconds: config.eventMaxDurationSeconds,
            minLevelDb: config.eventMinLevelDb,
            tickSamples: tickLen,
            sampleRate: fs,
            listener: detectorAdapter
        )
    }

    // ---- Detector callbacks ---------------------------------------------------------------

    fileprivate func detectorCandidateStart(_ startSample: Int) {
        // The previous event's tail ends where the next candidate starts.
        if tailAcc != nil { finishTail(currentTickIndex) }
        activeAcc = EventAcc(startSample, labelCount, curMinute, currentTickIndex)
        curMinute.undecided += 1
        listener.onEventCandidate(startSample)
    }

    fileprivate func detectorDiscarded(_ startSample: Int) {
        if let acc = activeAcc {
            acc.minute.undecided -= 1
            releaseIfDecided(acc.minute)
        }
        activeAcc = nil
        listener.onEventDiscarded(startSample)
    }

    fileprivate func detectorClosed(_ event: DetectedEvent) {
        let acc: EventAcc
        if let a = activeAcc {
            acc = a
        } else {
            acc = EventAcc(event.startSample, labelCount, curMinute, currentTickIndex)
            curMinute.undecided += 1
        }
        activeAcc = nil
        acc.detected = event
        acc.endTick = acc.startTick + JavaMath.round(event.durationSeconds * 8)
        var peak = -Double.infinity
        var i = acc.startTick
        while i < acc.endTick { let v = tracker.lafAt(i); if v > peak { peak = v }; i += 1 }
        acc.curvePeakDb = peak
        pending.append(acc)
        // No classifier result overlapped (short event between two classifications): request one now.
        if classifierActive && acc.frames == 0 { forceClassification = true }
        // The features wait for the decay after the end (the tail); see updateTail().
        tailAcc = acc
    }

    /** Ends the running tail at tick [tailEnd] (exclusive): features, counts, then emission. */
    private func finishTail(_ tailEnd: Int) {
        guard let acc = tailAcc else { return }
        tailAcc = nil
        let ev = acc.detected!
        let f = tracker.compute(
            start: acc.startTick, end: acc.endTick, tailEnd: Swift.max(tailEnd, acc.endTick), floorDb: ev.localFloorDb,
            lafMaxDb: ev.lafMaxDb, durationS: ev.durationSeconds, wind: windRule
        )
        acc.features = f
        if f.wind { acc.minute.wind += 1 } else { acc.minute.events += 1 }
        acc.minute.undecided -= 1
        listener.onEventClosed(ev.startSample, ev.endSample, f)
        _ = tryEmit(acc, force: false)
        releaseIfDecided(acc.minute)
    }

    /** After each valid tick: the tail ends when LAF is back at 10 % of the excess, or after the maximum. */
    private func updateTail() {
        guard let acc = tailAcc else { return }
        if currentTickIndex < acc.endTick { return }
        let floor = acc.detected!.localFloorDb
        let lo = floor + 0.1 * (acc.curvePeakDb - floor)
        let tail = currentTickIndex + 1 - acc.endTick
        if tracker.lafAt(currentTickIndex) <= lo || tail >= tailTicks { finishTail(currentTickIndex + 1) }
    }

    private func releaseIfDecided(_ m: MinuteCounts) {
        guard let r = m.record else { return }
        if m.undecided > 0 { return }
        m.record = nil
        if let i = heldMinutes.firstIndex(where: { $0 === m }) { heldMinutes.remove(at: i) }
        var out = r
        out.eventCount = m.events
        out.windEventCount = m.wind
        listener.onMinute(out)
    }

    /** Wall-clock time (epoch ms) of input sample index [sample]. */
    public func epochMsAt(_ sample: Int) -> Int { anchorMs + JavaMath.floorDiv((sample - anchorSample) * 1000, fs) }

    /** Called by the platform when the system silences / un-silences the microphone (any thread). */
    public func setMicSilenced(_ silenced: Bool) {
        silencedLock.lock(); _micSilenced = silenced; silencedLock.unlock()
    }

    /** Feeds [count] samples of [block]; [wallClock] is read once, as the block's delivery time. */
    public func process(_ block: [Float], _ count: Int? = nil) {
        let n = count ?? block.count
        block.withUnsafeBufferPointer { process($0, n) }
    }

    /** Same as [process] on a raw buffer (the AVAudioEngine tap delivers `UnsafePointer<Float>`). */
    public func process(_ block: UnsafeBufferPointer<Float>, _ count: Int) {
        let deliveredMs = wallClock()
        let blockEnd = totalSamples + count
        if !anchored {
            anchored = true
            anchorSample = blockEnd
            anchorMs = deliveredMs
            listener.onClockAnchored(anchorSample, anchorMs)
        }
        let drift = deliveredMs - epochMsAt(blockEnd)
        if drift < minDriftMs { minDriftMs = drift }
        if micSilenced { silencedSeenInTick = true }

        for i in 0..<count {
            let xf = block[i]
            if xf == 0 {
                zeroRun += 1
                if zeroRun > tickMaxZeroRun { tickMaxZeroRun = zeroRun }
            } else {
                zeroRun = 0
            }
            let x = Double(xf)
            let a = aWeighting.process(x)
            let a2 = a * a
            tickSumA += a2
            tickSumZ += x * x
            featureFilters.process(x, a)
            let fb = featureFilters.band
            let fl = featureFilters.low
            let ft = featureFilters.total
            tickSumBand += fb * fb
            tickSumLow += fl * fl
            tickSumTotal += ft * ft
            let y = fast.process(a2)
            if y > tickMaxY { tickMaxY = y }
            tickPos += 1
            totalSamples += 1
            if tickPos == tickLen {
                endTick()
                if micSilenced { silencedSeenInTick = true }
            }
        }
        if classifierActive { decimator.process(Array(block.prefix(count)), count, classifierRing) }
    }

    private func resetTick() {
        tickPos = 0; tickSumA = 0.0; tickSumZ = 0.0; tickMaxY = 0.0
        tickSumBand = 0.0; tickSumLow = 0.0; tickSumTotal = 0.0
        tickMaxZeroRun = zeroRun // a zero run continuing from the previous tick still counts
        silencedSeenInTick = false
    }

    private func tickIsInvalid(_ endSample: Int) -> Bool {
        let digitalSilence = tickMaxZeroRun >= SilenceDetector.ZERO_RUN_SAMPLES ||
            tickSumZ / Double(tickLen) < SilenceDetector.FLOOR_MEAN_SQUARE
        let start = endSample - tickLen
        if silencedSeenInTick || micSilenced || digitalSilence {
            guardUntilSample = endSample + guardSamples
            addInvalid(start, guardUntilSample)
            return true
        }
        return endSample <= guardUntilSample
    }

    private func addInvalid(_ start: Int, _ end: Int) {
        if let last = invalidIntervals.last, start <= last.end {
            invalidIntervals[invalidIntervals.count - 1].end = Swift.max(last.end, end)
        } else {
            invalidIntervals.append((start, end))
        }
        while invalidIntervals.count > 1 && invalidIntervals[0].end < totalSamples - 10 * fs { invalidIntervals.removeFirst() }
    }

    private func overlapsInvalid(_ start: Int, _ end: Int) -> Bool {
        invalidIntervals.contains { $0.start < end && start < $0.end }
    }

    private func endTick() {
        tickCount += 1
        if tickCount <= warmupTicks {
            resetTick()
            if tickCount == warmupTicks { startRecording() }
            return
        }
        let endSample = totalSamples
        let nowMs = epochMsAt(endSample)
        let laf = Acoustics.db(fast.value, offset)
        let lafMaxTick = Acoustics.db(tickMaxY, offset)
        let leqTick = Acoustics.db(tickSumA / Double(tickLen), offset)
        let valid = !tickIsInvalid(endSample)

        secTicks += 1
        minuteSamples += tickLen
        if valid {
            let tl = Double(tickLen)
            currentTickIndex = tracker.push(laf, tickSumA / tl, tickSumBand / tl, tickSumLow / tl, tickSumTotal / tl)
            secValidTicks += 1
            secSumA += tickSumA; secSumZ += tickSumZ
            if tickMaxY > secMaxY { secMaxY = tickMaxY }
            minuteSumA += tickSumA; minuteValidTicks += 1
            if tickMaxY > minuteMaxY { minuteMaxY = tickMaxY }
            minuteLafs.append(laf)
            background.add(laf)
            // The floor of the trailing window before this tick; frozen by the detector for a candidate.
            detector.floorDb = localFloor.value
            detector.onTick(endSample, laf, lafMaxTick, leqTick)
            localFloor.add(laf)
            let floorNow = localFloor.value
            if !floorNow.isNaN { minuteFloors.append(floorNow) }
            updateTail()
        } else {
            currentTickIndex = tracker.pushInvalid()
            detector.interrupt(endSample - tickLen)
            // A tail cannot continue over invalid audio.
            if tailAcc != nil { finishTail(currentTickIndex) }
        }
        listener.onTick(LafTick(endSample: endSample, epochMs: nowMs, lafDb: laf, lafMaxDb: lafMaxTick, leqDb: leqTick, valid: valid))
        recentTickEnd[recentTickPos] = endSample
        recentTickLaf[recentTickPos] = valid ? laf : .nan
        recentTickLafMax[recentTickPos] = valid ? lafMaxTick : .nan
        recentTickPos = (recentTickPos + 1) % recentTicks
        resetTick()

        if secTicks == 8 { endSecond(endSample, nowMs) }
        if nowMs >= nextMinuteBoundaryMs { endMinute(nextMinuteBoundaryMs) }
        finalizePending(force: false)
    }

    private func startRecording() {
        recording = true
        let t = epochMsAt(totalSamples)
        minuteStartMs = t
        nextMinuteBoundaryMs = (t / 60_000 + 1) * 60_000
    }

    private func endSecond(_ endSample: Int, _ nowMs: Int) {
        let validSamples = secValidTicks * tickLen
        let msA = validSamples > 0 ? secSumA / Double(validSamples) : Double.nan
        if validSamples > 0 { last60s.add(msA) }
        let running: Double
        if last60s.size > 0 {
            // Kotlin's DoubleArray.average(): sum left to right, divided by the count.
            let arr = last60s.toArray()
            var s = 0.0
            for v in arr { s += v }
            running = s / Double(arr.count)
        } else {
            running = .nan
        }
        let bg = background.l90()
        detector.backgroundDb = bg
        listener.onSecond(
            SecondResult(
                endSample: endSample,
                epochMs: nowMs,
                laeqDb: validSamples > 0 ? Acoustics.db(msA, offset) : .nan,
                lafMaxDb: validSamples > 0 ? Acoustics.db(secMaxY, offset) : .nan,
                lzeqDb: validSamples > 0 ? Acoustics.db(secSumZ / Double(validSamples), offset) : .nan,
                laeqRunning60sDb: running.isNaN ? .nan : Acoustics.db(running, offset),
                backgroundDb: bg,
                validFraction: Double(secValidTicks) / 8.0,
                localFloorDb: localFloor.value
            )
        )
        secTicks = 0; secValidTicks = 0; secSumA = 0.0; secSumZ = 0.0; secMaxY = 0.0
    }

    private func endMinute(_ boundaryMs: Int) {
        if minuteSamples > 0 {
            let stats = Percentiles.stats(minuteLafs)
            let validSamples = minuteValidTicks * tickLen
            var shares = OrderedDict<Double>()
            var dominant: String? = nil
            if classifierActive && minuteFrames > 0 {
                var best = -1
                for b in bucketIds.indices {
                    shares[bucketIds[b]] = Double(minuteBucketCounts[b]) / Double(minuteFrames)
                    if best < 0 || minuteBucketCounts[b] > minuteBucketCounts[best] { best = b }
                }
                dominant = bucketIds[best]
            }
            var floors = minuteFloors
            let record = MinuteRecord(
                startEpochMs: minuteStartMs,
                startIso: Iso.format(minuteStartMs, config.zone),
                durationSeconds: Double(minuteSamples) / Double(fs),
                laeqDb: validSamples > 0 ? Acoustics.db(minuteSumA / Double(validSamples), offset) : .nan,
                lafMaxDb: validSamples > 0 ? Acoustics.db(minuteMaxY, offset) : .nan,
                lafMinDb: stats.min,
                l1Db: stats.l1,
                l10Db: stats.l10,
                l50Db: stats.l50,
                l90Db: stats.l90,
                eventCount: curMinute.events,
                dominantCategory: dominant,
                categoryShares: shares,
                classifierFrames: minuteFrames,
                calibrationId: config.calibrationId,
                calibrationOffsetDb: offset,
                audioSource: config.audioSource,
                calibrated: config.calibrated,
                validSeconds: Double(validSamples) / Double(fs),
                clockCorrections: minuteClockCorrections,
                localFloorDb: floors.isEmpty ? .nan : QuickSelect.percentile(&floors, floors.count, 50.0),
                windEventCount: curMinute.wind
            )
            // Events that started in this minute and are not yet complete must be counted here.
            if curMinute.undecided > 0 {
                curMinute.record = record
                heldMinutes.append(curMinute)
            } else {
                listener.onMinute(record)
            }
            curMinute = MinuteCounts()
        }
        minuteSumA = 0.0; minuteSamples = 0; minuteValidTicks = 0; minuteMaxY = 0.0; minuteLafs.removeAll(keepingCapacity: true)
        minuteFloors.removeAll(keepingCapacity: true); minuteClockCorrections = 0
        for i in minuteBucketCounts.indices { minuteBucketCounts[i] = 0 }
        minuteFrames = 0

        checkClock()
        // Align the next minute to the wall clock (also after a clock correction or a gap).
        let nowMinute = JavaMath.floorDiv(epochMsAt(totalSamples), 60_000) * 60_000
        minuteStartMs = Swift.max(nowMinute, boundaryMs)
        nextMinuteBoundaryMs = minuteStartMs + 60_000
    }

    /** Once per minute: re-anchor the sample clock if it drifted from the wall clock. */
    private func checkClock() {
        let drift = minDriftMs
        minDriftMs = Int.max
        if drift == Int.max || abs(drift) <= config.maxClockDriftMs { return }
        anchorMs += drift
        minuteClockCorrections += 1
        listener.onClockCorrection(drift)
        listener.onClockAnchored(anchorSample, anchorMs)
    }

    // ---- Classifier interface -------------------------------------------------------------

    /** True when a new classifier window should be taken now. */
    public func classifierDue() -> Bool {
        if !classifierActive || !recording { return false }
        if classifierRing.available < ClassifierPreprocessor.YAMNET_INPUT_SAMPLES { return false }
        if overlapsInvalid(totalSamples - classifierWindowSamples48k, totalSamples) { return false }
        return forceClassification || totalSamples - lastClassifierSample >= classifierIntervalSamples
    }

    /**
     * Copies the latest 0.975 s of 16 kHz audio (unweighted, anti-aliased) into [dest]. Returns the
     * window end as an input sample index, or −1 if not enough data.
     */
    public func copyClassifierWindow(_ dest: inout [Float]) -> Int {
        if !classifierRing.copyLatest(&dest, ClassifierPreprocessor.YAMNET_INPUT_SAMPLES) { return -1 }
        lastClassifierSample = totalSamples
        forceClassification = false
        return totalSamples
    }

    public var classifierWindowLength48k: Int { classifierWindowSamples48k }

    /** Delivers a classifier result (from the inference thread, via the audio thread). */
    public func onClassifierFrame(_ frame: ClassifierFrame) {
        guard let m = mapper else { return }
        let windowStart = frame.endSample - frame.windowSamples48k
        // Windows touching silenced/invalid audio say nothing about the real sound: ignore them.
        if overlapsInvalid(windowStart, frame.endSample) {
            reportClassifierResult(frame, m, nil)
            return
        }
        let decision = m.decide(frame.scores)
        reportClassifierResult(frame, m, decision)
        listener.onClassification(epochMsAt(frame.endSample), decision, m.topLabels(frame.scores, 3))
        if recording {
            if let b = bucketIds.firstIndex(of: decision.dominant) { minuteBucketCounts[b] += 1 }
            minuteFrames += 1
        }
        var accs = pending
        if let a = activeAcc { accs.append(a) }
        for acc in accs {
            let evEnd = acc.detected?.endSample ?? Int.max
            if windowStart < evEnd && frame.endSample > acc.startSample {
                for i in 0..<labelCount { acc.sum[i] += frame.scores[i] }
                acc.frames += 1
            }
        }
        var i = 0
        while i < pending.count {
            if !tryEmit(pending[i], force: false) { i += 1 }
        }
    }

    /** Diagnostics: the frame's top-5 labels, decision, input gain and the LAF during the window. */
    private func reportClassifierResult(_ frame: ClassifierFrame, _ m: CategoryMapper, _ decision: CategoryDecision?) {
        let windowStart = frame.endSample - frame.windowSamples48k
        var lafAtEnd = Double.nan
        var lafAtEndSample = Int.min
        var lafMax = Double.nan
        for i in 0..<recentTicks {
            let end = recentTickEnd[i]
            if end == Int.min { continue }
            if end <= frame.endSample && end > lafAtEndSample { lafAtEndSample = end; lafAtEnd = recentTickLaf[i] }
            // Tick [end − tickLen, end) overlaps the window [windowStart, endSample).
            let mx = recentTickLafMax[i]
            if end > windowStart && end - tickLen < frame.endSample && !mx.isNaN && (lafMax.isNaN || mx > lafMax) { lafMax = mx }
        }
        listener.onClassifierResult(
            ClassifierResult(
                endSample: frame.endSample,
                windowSamples48k: frame.windowSamples48k,
                endEpochMs: epochMsAt(frame.endSample),
                top: m.topLabels(frame.scores, 5),
                decision: decision,
                inputGainDb: frame.inputGainDb,
                lafDb: lafAtEnd,
                lafMaxDb: lafMax,
                ignoredInvalid: decision == nil
            )
        )
    }

    private func finalizePending(force: Bool) {
        if pending.isEmpty { return }
        var i = 0
        while i < pending.count {
            if !tryEmit(pending[i], force: force) { i += 1 }
        }
        var waitingForFrame = false
        for acc in pending where acc.frames == 0 { waitingForFrame = true }
        if !waitingForFrame { forceClassification = false }
    }

    /**
     * Emits a closed event once its features are known and it has a classification (or the
     * classifier is off, or none arrived within the timeout, or [force]). True if emitted.
     */
    private func tryEmit(_ acc: EventAcc, force: Bool) -> Bool {
        if acc.features == nil { return false }
        let timeout = JavaMath.toLong(config.eventClassificationTimeoutSeconds * Double(fs))
        if force || !classifierActive || acc.frames > 0 || totalSamples - acc.detected!.endSample > timeout {
            if let i = pending.firstIndex(where: { $0 === acc }) { pending.remove(at: i) }
            emitEvent(acc)
            return true
        }
        return false
    }

    private func emitEvent(_ acc: EventAcc) {
        let ev = acc.detected!
        let features = acc.features
        var dominant: String? = nil
        var score: Float = 0
        var top: [LabelScore] = []
        if classifierActive, let m = mapper {
            if acc.frames > 0 {
                let mean = (0..<labelCount).map { acc.sum[$0] / Float(acc.frames) }
                let d = m.decide(mean)
                dominant = d.dominant; score = d.dominantScore
                top = m.topLabels(mean, 3)
            } else {
                dominant = CategoryMapper.UNCLASSIFIED
            }
        }
        // Wind on the microphone is the detector's decision, not the classifier's: own category.
        if features?.wind == true {
            dominant = WindRule.CATEGORY
            score = 0
        }
        let startMs = epochMsAt(ev.startSample)
        listener.onEventEmitted(
            NoiseEvent(
                startEpochMs: startMs,
                startIso: Iso.format(startMs, config.zone, millis: true),
                durationSeconds: ev.durationSeconds,
                lafMaxDb: ev.lafMaxDb,
                selDb: ev.selDb,
                backgroundDb: ev.backgroundDb,
                thresholdDb: ev.thresholdDb,
                dominantCategory: dominant,
                dominantScore: score,
                topLabels: top,
                classifierFrames: acc.frames,
                calibrationId: config.calibrationId,
                audioSource: config.audioSource,
                calibrated: config.calibrated,
                minLevelDb: ev.minLevelDb,
                features: features
            ),
            ev.startSample,
            ev.endSample
        )
    }

    /** Ends the measurement: closes a running event and emits the partial minute (if ≥ 1 s). */
    public func stop() {
        detector.flush(totalSamples)
        // The tail ends with the audio there is.
        if tailAcc != nil { finishTail(tracker.count) }
        finalizePending(force: true)
        if recording && minuteSamples >= fs { endMinute(nextMinuteBoundaryMs) }
        // Every event is decided now; nothing can stay held.
        for h in heldMinutes { h.undecided = 0; releaseIfDecided(h) }
        recording = false
        classifierRing.clear()
        decimator.reset()
        featureFilters.reset()
    }
}
