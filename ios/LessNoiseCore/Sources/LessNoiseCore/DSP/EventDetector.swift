import Foundation

/**
 * Trailing background estimate: L90 of the LAF samples over the last [windowSeconds] (EventDetector.kt).
 * NaN until at least [minHistorySeconds] of samples are available.
 */
public final class BackgroundEstimator {
    public let windowSeconds: Double
    public let minHistorySeconds: Double
    public let tickSeconds: Double
    private let ring: DoubleRing
    private let minSamples: Int

    public init(windowSeconds: Double = 300.0, minHistorySeconds: Double = 30.0, tickSeconds: Double = 0.125) {
        self.windowSeconds = windowSeconds
        self.minHistorySeconds = minHistorySeconds
        self.tickSeconds = tickSeconds
        ring = DoubleRing(capacity: JavaMath.toInt(windowSeconds / tickSeconds))
        minSamples = JavaMath.toInt(minHistorySeconds / tickSeconds)
    }

    public func add(_ lafDb: Double) { ring.add(lafDb) }

    public func l90() -> Double {
        if ring.size < minSamples { return .nan }
        return Percentiles.exceedanceLevel(ring.toArray(), 90.0)
    }

    public func clear() { ring.clear() }
}

/** An event as delimited by the detector (timing in input samples, levels in dB). */
public struct DetectedEvent: Equatable {
    public let startSample: Int
    public let endSample: Int
    public let durationSeconds: Double
    public let lafMaxDb: Double
    /** Sound exposure level LAE (re 1 s). */
    public let selDb: Double
    /** 5-min background (L90) at the start, for continuity with v0.3 (not used for detection). */
    public let backgroundDb: Double
    /** The excess setting in force (dB above the local floor). */
    public let thresholdDb: Double
    /** Absolute floor the event's LAFmax had to reach. */
    public let minLevelDb: Double
    /** Local floor (L90 of LAF over the trailing local window) at the start, frozen for the event. */
    public let localFloorDb: Double

    public init(
        startSample: Int, endSample: Int, durationSeconds: Double, lafMaxDb: Double, selDb: Double,
        backgroundDb: Double, thresholdDb: Double, minLevelDb: Double = -.infinity, localFloorDb: Double = .nan
    ) {
        self.startSample = startSample; self.endSample = endSample; self.durationSeconds = durationSeconds
        self.lafMaxDb = lafMaxDb; self.selDb = selDb; self.backgroundDb = backgroundDb
        self.thresholdDb = thresholdDb; self.minLevelDb = minLevelDb; self.localFloorDb = localFloorDb
    }
}

public protocol EventDetectorListener: AnyObject {
    /** LAF crossed the start threshold (event not yet confirmed). */
    func onCandidateStart(_ startSample: Int)
    /** Candidate reached the minimum duration and the level floor: it will be reported as an event. */
    func onConfirmed(_ startSample: Int)
    /** Candidate ended before reaching the minimum duration or the level floor. */
    func onDiscarded(_ startSample: Int)
    func onClosed(_ event: DetectedEvent)
}

public extension EventDetectorListener {
    func onCandidateStart(_ startSample: Int) {}
    func onConfirmed(_ startSample: Int) {}
    func onDiscarded(_ startSample: Int) {}
}

/**
 * Single-event detector working on 125 ms LAF ticks (detector v2, engine 0.4.0; EventDetector.kt).
 *
 * - Starts when LAF ≥ local floor + [excessDb] (the local floor is frozen at the start).
 * - Continues while LAF ≥ floor + excess − [hysteresisDb].
 * - Kept only if it lasted ≥ [minDurationSeconds] **and** its LAFmax reached [minLevelDb];
 *   force-closed after [maxDurationSeconds].
 * - SEL = 10·log10(Σ 10^(Leq_i/10)·Δt / 1 s) over the un-time-weighted per-tick LAeq.
 */
public final class EventDetector {
    /** Start threshold above the local floor (dB). Read at event start. */
    public var excessDb: Double
    public let hysteresisDb: Double
    public let minDurationSeconds: Double
    public let maxDurationSeconds: Double
    /** Absolute floor for LAFmax in dB; −∞ disables it. Read at event start. */
    public var minLevelDb: Double
    public let tickSamples: Int
    public let sampleRate: Int
    private let listener: EventDetectorListener

    private let tickSeconds: Double
    private let minTicks: Int
    private let maxTicks: Int

    /** Current local floor (dB); NaN disables detection. */
    public var floorDb: Double = .nan
    /** Current 5-min background (dB), recorded with the event for continuity; NaN → the floor is recorded. */
    public var backgroundDb: Double = .nan

    private var active = false
    private var startSample = 0
    private var floorAtStart = 0.0
    private var bgAtStart = 0.0
    private var excessAtStart = 0.0
    private var minLevelAtStart = -Double.infinity
    private var ticks = 0
    private var lafMax = -Double.infinity
    private var energy = 0.0
    private var confirmed = false

    public init(
        excessDb: Double = 6.5, hysteresisDb: Double = 3.0, minDurationSeconds: Double = 0.5,
        maxDurationSeconds: Double = 300.0, minLevelDb: Double = -.infinity,
        tickSamples: Int = Acoustics.SAMPLE_RATE / 8, sampleRate: Int = Acoustics.SAMPLE_RATE,
        listener: EventDetectorListener
    ) {
        self.excessDb = excessDb
        self.hysteresisDb = hysteresisDb
        self.minDurationSeconds = minDurationSeconds
        self.maxDurationSeconds = maxDurationSeconds
        self.minLevelDb = minLevelDb
        self.tickSamples = tickSamples
        self.sampleRate = sampleRate
        self.listener = listener
        tickSeconds = Double(tickSamples) / Double(sampleRate)
        minTicks = JavaMath.toInt(ceil(minDurationSeconds / tickSeconds - 1e-9))
        maxTicks = JavaMath.toInt(maxDurationSeconds / tickSeconds)
    }

    public var isActive: Bool { active }
    public var activeStartSample: Int? { active ? startSample : nil }
    /** A candidate that has not (yet) reached the minimum duration. */
    public var isUnconfirmedCandidate: Bool { active && !confirmed }
    /** The local floor frozen for the running candidate (NaN if none). */
    public var activeFloorDb: Double { active ? floorAtStart : .nan }

    /** Ends a running event at [endSample] because the following audio is invalid. */
    public func interrupt(_ endSample: Int) {
        if active { close(endSample) }
    }

    public func onTick(_ tickEndSample: Int, _ lafDb: Double, _ tickLafMaxDb: Double, _ tickLeqDb: Double) {
        if !active {
            let floor = floorDb
            if floor.isNaN { return }
            if lafDb >= floor + excessDb {
                active = true
                startSample = tickEndSample - tickSamples
                floorAtStart = floor
                bgAtStart = backgroundDb.isNaN ? floor : backgroundDb
                excessAtStart = excessDb
                minLevelAtStart = minLevelDb
                ticks = 0
                lafMax = -.infinity
                energy = 0.0
                confirmed = false
                listener.onCandidateStart(startSample)
                accumulate(tickLafMaxDb, tickLeqDb)
            }
            return
        }
        if lafDb < floorAtStart + excessAtStart - hysteresisDb {
            close(tickEndSample - tickSamples)
            return
        }
        accumulate(tickLafMaxDb, tickLeqDb)
        if ticks >= maxTicks { close(tickEndSample) }
    }

    private func accumulate(_ tickLafMaxDb: Double, _ tickLeqDb: Double) {
        ticks += 1
        if tickLafMaxDb > lafMax { lafMax = tickLafMaxDb }
        energy += pow(10.0, tickLeqDb / 10.0) * tickSeconds
        if !confirmed && ticks >= minTicks && lafMax >= minLevelAtStart {
            confirmed = true
            listener.onConfirmed(startSample)
        }
    }

    /** Closes a running event (e.g. when measurement stops). */
    public func flush(_ endSample: Int) {
        if active { close(endSample) }
    }

    private func close(_ endSample: Int) {
        active = false
        if !confirmed {
            listener.onDiscarded(startSample)
            return
        }
        let duration = Double(ticks) * tickSeconds
        listener.onClosed(
            DetectedEvent(
                startSample: startSample,
                endSample: Swift.max(endSample, startSample + ticks * tickSamples),
                durationSeconds: duration,
                lafMaxDb: lafMax,
                selDb: 10.0 * log10(energy),
                backgroundDb: bgAtStart,
                thresholdDb: excessAtStart,
                minLevelDb: minLevelAtStart,
                localFloorDb: floorAtStart
            )
        )
    }
}
