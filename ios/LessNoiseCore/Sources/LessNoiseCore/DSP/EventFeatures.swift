import Foundation

/**
 * Second-order Butterworth sections (bilinear transform with pre-warping, Q = 1/√2), used for the
 * band splits of the event features (EventFeatures.kt). Exactly −3 dB at the corner frequency.
 */
public enum Butterworth {
    private static let Q = 0.7071067811865476

    public static func lowpass(_ cornerHz: Double, _ sampleRate: Int) -> Biquad {
        let w0 = 2 * Double.pi * cornerHz / Double(sampleRate)
        let c = cos(w0)
        let alpha = sin(w0) / (2 * Q)
        let a0 = 1 + alpha
        return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    public static func highpass(_ cornerHz: Double, _ sampleRate: Int) -> Biquad {
        let w0 = 2 * Double.pi * cornerHz / Double(sampleRate)
        let c = cos(w0)
        let alpha = sin(w0) / (2 * Q)
        let a0 = 1 + alpha
        return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }
}

/**
 * The per-sample band splits behind the event features: mid band (A-weighted, 250 Hz–4.5 kHz),
 * total (unweighted, 20 Hz–8 kHz) and low frequencies (unweighted, 20–200 Hz).
 */
public final class FeatureFilters {
    private let bandHp: Biquad
    private let bandLp: Biquad
    private let hp20: Biquad
    private let lp200: Biquad
    private let lp8k: Biquad

    public private(set) var band = 0.0
    public private(set) var low = 0.0
    public private(set) var total = 0.0

    public init(sampleRate: Int) {
        bandHp = Butterworth.highpass(250.0, sampleRate)
        bandLp = Butterworth.lowpass(4500.0, sampleRate)
        hp20 = Butterworth.highpass(20.0, sampleRate)
        lp200 = Butterworth.lowpass(200.0, sampleRate)
        lp8k = Butterworth.lowpass(8000.0, sampleRate)
    }

    /** [x]: unweighted sample; [a]: the same sample A-weighted. Results in [band], [low], [total]. */
    @inline(__always)
    public func process(_ x: Double, _ a: Double) {
        band = bandLp.process(bandHp.process(a))
        let h = hp20.process(x)
        low = lp200.process(h)
        total = lp8k.process(h)
    }

    public func reset() {
        bandHp.reset(); bandLp.reset(); hp20.reset(); lp200.reset(); lp8k.reset()
    }
}

/**
 * Local floor: L90 of the LAF samples over a trailing window (default 30 s), recomputed with every
 * 125 ms sample by quickselect (same percentile definition as [Percentiles]). NaN until
 * [minHistorySeconds] of samples are available.
 */
public final class LocalFloorEstimator {
    public let windowSeconds: Double
    public let minHistorySeconds: Double
    public let tickSeconds: Double
    private let capacity: Int
    private var ring: [Double]
    private var scratch: [Double]
    private var next = 0
    private let minSamples: Int

    public private(set) var size = 0
    /** The current local floor (dB), NaN until enough history. */
    public private(set) var value: Double = .nan

    public init(windowSeconds: Double = 30.0, minHistorySeconds: Double = 5.0, tickSeconds: Double = 0.125) {
        self.windowSeconds = windowSeconds
        self.minHistorySeconds = minHistorySeconds
        self.tickSeconds = tickSeconds
        capacity = Swift.max(JavaMath.toInt(Double(JavaMath.round(windowSeconds / tickSeconds))), 1)
        ring = [Double](repeating: 0, count: capacity)
        scratch = [Double](repeating: 0, count: capacity)
        minSamples = Swift.max(JavaMath.toInt(ceil(Swift.min(minHistorySeconds, windowSeconds) / tickSeconds - 1e-9)), 1)
    }

    public func add(_ lafDb: Double) {
        ring[next] = lafDb
        next = (next + 1) % capacity
        if size < capacity { size += 1 }
        value = size < minSamples ? .nan : l90()
    }

    private func l90() -> Double {
        let n = size
        // The order of the ring does not matter for a percentile.
        for i in 0..<n { scratch[i] = ring[i] }
        return QuickSelect.percentile(&scratch, n, 10.0)
    }

    public func clear() { next = 0; size = 0; value = .nan }
}

/** In-place selection on a prefix of an array (no allocation). */
public enum QuickSelect {
    /**
     * The [p]th percentile (0…100) of `a[0 ..< n]` with linear interpolation between the
     * neighbouring order statistics, exactly like [Percentiles.percentileSorted]. Reorders `a`.
     */
    public static func percentile(_ a: inout [Double], _ n: Int, _ p: Double) -> Double {
        if n <= 0 { return .nan }
        if n == 1 { return a[0] }
        let pos = p / 100.0 * Double(n - 1)
        let lo = JavaMath.toInt(pos)
        let frac = pos - Double(lo)
        let vLo = select(&a, n, lo)
        if frac == 0.0 || lo + 1 >= n { return vLo }
        // After select(), everything right of lo is ≥ a[lo]: the next order statistic is their minimum.
        var vHi = a[lo + 1]
        var i = lo + 2
        while i < n { if a[i] < vHi { vHi = a[i] }; i += 1 }
        return vLo + (vHi - vLo) * frac
    }

    /** Rearranges `a[0 ..< n]` so that a[k] is the k-th smallest; returns it. */
    public static func select(_ a: inout [Double], _ n: Int, _ k: Int) -> Double {
        var left = 0
        var right = n - 1
        while right > left {
            // Median of three as pivot, moved to the right end.
            let mid = Int(UInt(bitPattern: left + right) >> 1)
            if a[mid] < a[left] { a.swapAt(mid, left) }
            if a[right] < a[left] { a.swapAt(right, left) }
            if a[mid] < a[right] { a.swapAt(mid, right) }
            let pivot = a[right]
            var store = left
            var i = left
            while i < right {
                if a[i] < pivot { a.swapAt(i, store); store += 1 }
                i += 1
            }
            a.swapAt(store, right)
            if store == k { return a[k] }
            if k < store { right = store - 1 } else { left = store + 1 }
        }
        return a[k]
    }
}

/** Shape and wind features of one event (detector v2); see [EventFeatureTracker] for the definitions. */
public struct EventFeatures: Equatable {
    /** L90 of LAF over the trailing local-floor window, frozen at the event's start (dB(A)). */
    public var localFloorDb: Double
    /** LAFmax − [localFloorDb] (dB). */
    public var excessDb: Double
    /** Rise time 10 → 90 % of the excess on the 125 ms LAF curve (s); NaN if not measurable. */
    public var riseS: Double
    /** Decay time 90 → 10 % after the peak (s); NaN if the level did not fall to 10 % in time. */
    public var decayS: Double
    /** RMS second difference of the 125 ms LAF curve during the event / [excessDb]. */
    public var jaggedness: Double
    /** A-weighted 250 Hz–4.5 kHz level in the loudest 1 s minus the 5 s before the start (dB). */
    public var midBandRiseDb: Double
    /** Unweighted energy share 20–200 Hz of 20 Hz–8 kHz over the event (0…1). */
    public var lfShare: Double
    /** RMS of the detrended 125 ms level of 20–200 Hz over the event (dB). */
    public var lfFlutterDb: Double
    /** Wind on the microphone ([WindRule]); such events are excluded from the event counts. */
    public var wind: Bool
    /** [EventShape] id: hump, jagged, impulse or long. */
    public var shape: String

    public init(
        localFloorDb: Double, excessDb: Double, riseS: Double, decayS: Double, jaggedness: Double,
        midBandRiseDb: Double, lfShare: Double, lfFlutterDb: Double, wind: Bool, shape: String
    ) {
        self.localFloorDb = localFloorDb; self.excessDb = excessDb; self.riseS = riseS; self.decayS = decayS
        self.jaggedness = jaggedness; self.midBandRiseDb = midBandRiseDb; self.lfShare = lfShare
        self.lfFlutterDb = lfFlutterDb; self.wind = wind; self.shape = shape
    }

    /** NaN-aware equality (NaN == NaN), for tests and change detection. */
    public static func == (a: EventFeatures, b: EventFeatures) -> Bool {
        sameDouble(a.localFloorDb, b.localFloorDb) && sameDouble(a.excessDb, b.excessDb) && sameDouble(a.riseS, b.riseS) &&
            sameDouble(a.decayS, b.decayS) && sameDouble(a.jaggedness, b.jaggedness) &&
            sameDouble(a.midBandRiseDb, b.midBandRiseDb) && sameDouble(a.lfShare, b.lfShare) &&
            sameDouble(a.lfFlutterDb, b.lfFlutterDb) && a.wind == b.wind && a.shape == b.shape
    }
}

@inline(__always) func sameDouble(_ a: Double, _ b: Double) -> Bool { a == b || (a.isNaN && b.isNaN) }
@inline(__always) func sameDouble(_ a: Double?, _ b: Double?) -> Bool {
    switch (a, b) {
    case (nil, nil): return true
    case let (x?, y?): return sameDouble(x, y)
    default: return false
    }
}

/**
 * Wind on the microphone: `lfShare ≥ lfShareMin || lfFlutterDb ≥ flutterMinDb` (defaults fitted on
 * one recorded night, see EventFeatures.kt).
 */
public struct WindRule: Equatable {
    public static let DEFAULT_LF_SHARE = 0.93
    public static let DEFAULT_FLUTTER_DB = 4.5
    /** Category id of wind events (not part of the classifier mapping). */
    public static let CATEGORY = "wind"
    public static let NAME_DE = "Wind"

    public let lfShareMin: Double
    public let flutterMinDb: Double

    public init(lfShareMin: Double = DEFAULT_LF_SHARE, flutterMinDb: Double = DEFAULT_FLUTTER_DB) {
        self.lfShareMin = lfShareMin
        self.flutterMinDb = flutterMinDb
    }

    /** NaN never triggers. */
    public func isWind(_ lfShare: Double, _ lfFlutterDb: Double) -> Bool { lfShare >= lfShareMin || lfFlutterDb >= flutterMinDb }
}

/** Pass shape (informational, not a filter): long, impulse, jagged, hump — in this order. */
public enum EventShape {
    public static let HUMP = "hump"
    public static let JAGGED = "jagged"
    public static let IMPULSE = "impulse"
    public static let LONG = "long"

    public static let LONG_MIN_S = 30.0
    public static let IMPULSE_MAX_RISE_S = 0.35
    public static let IMPULSE_MAX_DURATION_S = 2.0
    public static let JAGGED_MIN = 0.3

    public static func classify(_ durationS: Double, _ riseS: Double, _ jaggedness: Double) -> String {
        if durationS >= LONG_MIN_S { return LONG }
        if riseS < IMPULSE_MAX_RISE_S && durationS <= IMPULSE_MAX_DURATION_S { return IMPULSE }
        if jaggedness >= JAGGED_MIN { return JAGGED }
        return HUMP
    }

    public static func nameDe(_ id: String?) -> String {
        switch id {
        case HUMP?: return "Buckel"
        case JAGGED?: return "zackig"
        case IMPULSE?: return "Impuls"
        case LONG?: return "lang"
        case nil: return "–"
        case let x?: return x
        }
    }
}

/**
 * Keeps the last [capacityTicks] 125 ms ticks (LAF and the mean squares of the A-weighted signal,
 * the A-weighted mid band, the 20–200 Hz band and the 20 Hz–8 kHz total; NaN for invalid ticks) and
 * computes [EventFeatures] for a span of them. Line-by-line port of EventFeatureTracker in
 * EventFeatures.kt; see there for the exact definitions.
 */
public final class EventFeatureTracker {
    /** Half width (ticks) of the running median that detrends the LF level for the flutter. */
    public static let FLUTTER_HALF_WIDTH = 8
    /** The pre-event span for [EventFeatures.midBandRiseDb] (5 s). */
    public static let MID_BAND_PRE_TICKS = 40
    /** The "top 1 s" of the event for [EventFeatures.midBandRiseDb]. */
    public static let MID_BAND_TOP_TICKS = 8

    public let capacityTicks: Int
    public let tickSeconds: Double
    public let preTicks: Int

    private var laf: [Double]
    private var eA: [Double]
    private var eBand: [Double]
    private var eLow: [Double]
    private var eTotal: [Double]
    private var lfLevel: [Double]
    private var window: [Double]

    /** Number of ticks pushed so far = index of the next tick. */
    public private(set) var count = 0

    public init(capacityTicks: Int, tickSeconds: Double = 0.125, preTicks: Int = 80) {
        self.capacityTicks = capacityTicks
        self.tickSeconds = tickSeconds
        self.preTicks = preTicks
        laf = [Double](repeating: 0, count: capacityTicks)
        eA = laf; eBand = laf; eLow = laf; eTotal = laf; lfLevel = laf
        window = [Double](repeating: 0, count: 2 * EventFeatureTracker.FLUTTER_HALF_WIDTH + 1)
    }

    @inline(__always) private func slot(_ i: Int) -> Int { i % capacityTicks }

    /** Oldest tick index still held. */
    public var oldest: Int { Swift.max(0, count - capacityTicks) }

    @discardableResult
    public func push(_ lafDb: Double, _ msA: Double, _ msBand: Double, _ msLow: Double, _ msTotal: Double) -> Int {
        let s = slot(count)
        laf[s] = lafDb; eA[s] = msA; eBand[s] = msBand; eLow[s] = msLow; eTotal[s] = msTotal
        count += 1
        return count - 1
    }

    @discardableResult
    public func pushInvalid() -> Int { push(.nan, .nan, .nan, .nan, .nan) }

    public func lafAt(_ i: Int) -> Double { (i < oldest || i >= count) ? .nan : laf[slot(i)] }

    /** Crossing time (in ticks) of [level] between ticks a and a + 1, linearly interpolated. */
    private func cross(_ a: Int, _ level: Double) -> Double {
        let la = lafAt(a)
        let lb = lafAt(a + 1)
        if la.isNaN || lb.isNaN || lb == la { return Double(a + 1) }
        return Double(a) + Swift.min(Swift.max((level - la) / (lb - la), 0.0), 1.0)
    }

    /**
     * Features of the event of ticks [start, end) with the tail up to [tailEnd] (exclusive, ≥ end).
     * The ticks must still be held ([capacityTicks] ≥ preTicks + event + tail).
     */
    public func compute(
        start: Int, end: Int, tailEnd: Int, floorDb: Double, lafMaxDb: Double, durationS: Double, wind: WindRule
    ) -> EventFeatures {
        let excess = lafMaxDb - floorDb
        let n = end - start
        var p = start
        var lp = -Double.infinity
        var i = start
        while i < end {
            let v = lafAt(i)
            if v > lp { lp = v; p = i }
            i += 1
        }
        let x = lp - floorDb
        var rise = Double.nan
        var decay = Double.nan
        if n > 0 && x > 0 {
            let hi = floorDb + 0.9 * x
            let lo = floorDb + 0.1 * x
            // Rise.
            var i90 = start
            while i90 < p && !(lafAt(i90) >= hi) { i90 += 1 }
            let t90 = lafAt(i90 - 1) < hi ? cross(i90 - 1, hi) : Double(i90)
            var j = i90 - 1
            let minJ = Swift.max(start - preTicks, oldest)
            var found = false
            while j >= minJ {
                let v = lafAt(j)
                if v.isNaN { break }
                if v <= lo { found = true; break }
                j -= 1
            }
            if found { rise = (t90 - cross(j, lo)) * tickSeconds }
            // Decay.
            let limit = Swift.min(tailEnd, count)
            var k = p + 1
            while k < limit && lafAt(k) >= hi { k += 1 }
            if k < limit && !lafAt(k).isNaN {
                let t90d = cross(k - 1, hi)
                var m = k
                while m < limit && lafAt(m) > lo { m += 1 }
                if m < limit && !lafAt(m).isNaN { decay = (cross(m - 1, lo) - t90d) * tickSeconds }
            }
        }

        // Jaggedness.
        var jag = Double.nan
        if n >= 3 && excess > 0 {
            var sum = 0.0
            var q = start + 1
            while q < end - 1 {
                let d2 = lafAt(q + 1) - 2 * lafAt(q) + lafAt(q - 1)
                sum += d2 * d2
                q += 1
            }
            jag = sqrt(sum / Double(n - 2)) / excess
        }

        // Mid band: loudest 1 s of the event vs the 5 s before it.
        var midRise = Double.nan
        if n > 0 {
            let w = Swift.min(EventFeatureTracker.MID_BAND_TOP_TICKS, n)
            var run = 0.0
            for q in start..<(start + w) { run += eA[slot(q)] }
            var best = run
            var bestStart = start
            var q = start + w
            while q < end {
                run += eA[slot(q)] - eA[slot(q - w)]
                if run > best { best = run; bestStart = q - w + 1 }
                q += 1
            }
            var during = 0.0
            for r in bestStart..<(bestStart + w) { during += eBand[slot(r)] }
            during /= Double(w)
            var pre = 0.0
            var preN = 0
            var r = Swift.max(start - EventFeatureTracker.MID_BAND_PRE_TICKS, oldest)
            while r < start {
                let e = eBand[slot(r)]
                if !e.isNaN { pre += e; preN += 1 }
                r += 1
            }
            if preN > 0 && pre > 0 && during > 0 { midRise = 10 * log10(during / (pre / Double(preN))) }
        }

        // Low-frequency share and flutter.
        var low = 0.0
        var total = 0.0
        var q = start
        while q < end { low += eLow[slot(q)]; total += eTotal[slot(q)]; q += 1 }
        let lfShare = total > 0 ? low / total : Double.nan
        var flutter = Double.nan
        if n >= 4 {
            for k in 0..<n { lfLevel[k] = 10 * log10(Swift.max(eLow[slot(start + k)], 1e-30)) }
            var sum = 0.0
            for k in 0..<n {
                let h = Swift.min(EventFeatureTracker.FLUTTER_HALF_WIDTH, k, n - 1 - k)
                let w = 2 * h + 1
                for t in 0..<w { window[t] = lfLevel[k - h + t] }
                let d = lfLevel[k] - QuickSelect.select(&window, w, h)
                sum += d * d
            }
            flutter = sqrt(sum / Double(n))
        }

        return EventFeatures(
            localFloorDb: floorDb,
            excessDb: excess,
            riseS: rise,
            decayS: decay,
            jaggedness: jag,
            midBandRiseDb: midRise,
            lfShare: lfShare,
            lfFlutterDb: flutter,
            wind: wind.isWind(lfShare, flutter),
            shape: EventShape.classify(durationS, rise, jag)
        )
    }
}

/**
 * Settings migration v0.3 → v0.4 (old threshold over the 5-min background → excess over the local
 * floor). Not needed by a fresh iOS install; ported for completeness of the settings model.
 */
public enum ThresholdMigration {
    public static let LEGACY_DEFAULT_THRESHOLD_DB = 10.0
    public static let EXCESS_MIN = 3.0
    public static let EXCESS_MAX = 20.0
    public static let OFFSET_DB = 3.5

    public static func excessFromLegacyThreshold(_ oldThresholdDb: Double) -> Double {
        Swift.min(Swift.max(oldThresholdDb - OFFSET_DB, EXCESS_MIN), EXCESS_MAX)
    }
}
