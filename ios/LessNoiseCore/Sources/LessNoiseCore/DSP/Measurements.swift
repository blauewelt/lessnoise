import Foundation

/** One measurement: a run from «Start» to «Stop» (Measurements.kt). [endMs] is exclusive. */
public struct MeasurementSpan: Equatable {
    public let startMs: Int
    public let endMs: Int
    public init(_ startMs: Int, _ endMs: Int) { self.startMs = startMs; self.endMs = endMs }
    public var durationMs: Int { endMs - startMs }
    public func contains(_ t: Int) -> Bool { t >= startMs && t < endMs }
}

/** Summary of one measurement for the «Messungen» list. */
public struct MeasurementSummary {
    public let span: MeasurementSpan
    public let measuredSeconds: Double
    public let laeqDb: Double
    public let eventCount: Int
    public let strongEventCount: Int
    public let categoryShares: OrderedDict<Double>
    public let eventsByCategory: OrderedDict<Int>
    public let loudestEvent: NoiseEvent?
    public let allCalibrated: Bool
    public let excludedMinutes: Int
    public let eventsPerHour: Double
    public let dynamicsDb: Double
    public let anyRecalibrated: Bool
    public let humDb: Double
    public let windEventCount: Int

    public var startMs: Int { span.startMs }
    public var endMs: Int { span.endMs }

    /** Share of the measurement's duration covered by valid audio (0…1). */
    public var coverage: Double {
        span.durationMs > 0 ? Swift.min(Swift.max(measuredSeconds * 1000.0 / Double(span.durationMs), 0.0), 1.0) : 0.0
    }
}

/** Burstiness figures shared by the measurement list and the chart. */
public enum Dynamics {
    /** The hum: median over [minutes] of their L90 (minutes without L90 are skipped). */
    public static func medianL90(_ minutes: [MinuteRecord]) -> Double {
        median(minutes.compactMap { $0.l90Db.isNaN ? nil : $0.l90Db })
    }

    public static func eventsPerHour(_ events: Int, _ validSeconds: Double) -> Double {
        validSeconds > 0 ? Double(events) / (validSeconds / 3600.0) : .nan
    }

    /** Median over [minutes] of L10 − L90. */
    public static func medianSpread(_ minutes: [MinuteRecord]) -> Double {
        median(minutes.compactMap { m in let d = m.l10Db - m.l90Db; return d.isNaN ? nil : d })
    }

    private static func median(_ values: [Double]) -> Double {
        let d = values.sorted()
        if d.isEmpty { return .nan }
        let n = d.count
        return n % 2 == 1 ? d[n / 2] : (d[n / 2 - 1] + d[n / 2]) / 2
    }
}

public enum MeasurementSummarizer {
    public static let STRONG_MARGIN_DB = 15.0
    /** Minutes with less valid audio than this fraction are left out of summaries and charts. */
    public static let MIN_MINUTE_COVERAGE = 0.5

    /** Summary of the measurement [span] (see Measurements.kt for the rules). */
    public static func summarize(
        _ span: MeasurementSpan, _ minutes: [MinuteRecord], _ events: [NoiseEvent], eventMinLevelDb: Double = -.infinity
    ) -> MeasurementSummary {
        let allMinutes = minutes.filter { span.contains($0.startEpochMs) }
        let ms = allMinutes.filter { $0.coverage >= MIN_MINUTE_COVERAGE && !$0.laeqDb.isNaN }
        let inSpan = events.filter { span.contains($0.startEpochMs) && $0.reachesFloor(eventMinLevelDb) }
        let wind = inSpan.filter { $0.wind }
        let evs = inSpan.filter { !$0.wind }
        var energy = 0.0
        var dur = 0.0
        var shareSum = OrderedDict<Double>()
        var classifiedDur = 0.0
        for m in ms {
            // Levels of a minute describe its valid seconds only, so weight by those.
            energy += pow(10.0, m.laeqDb / 10.0) * m.validSeconds
            dur += m.validSeconds
            if !m.categoryShares.isEmpty {
                classifiedDur += m.validSeconds
                for (k, v) in m.categoryShares.entries { shareSum[k] = (shareSum[k] ?? 0.0) + v * m.validSeconds }
            }
        }
        let shares = classifiedDur > 0 ? shareSum.mapValues { $0 / classifiedDur } : OrderedDict<Double>()
        var byCat = OrderedDict<Int>()
        for e in evs { let k = e.dominantCategory ?? "n/a"; byCat[k] = (byCat[k] ?? 0) + 1 }
        // Kotlin's maxByOrNull: the first of equal maxima.
        var loudest: NoiseEvent? = nil
        for e in evs where loudest == nil || e.lafMaxDb > loudest!.lafMaxDb { loudest = e }
        return MeasurementSummary(
            span: span,
            measuredSeconds: dur,
            laeqDb: dur > 0 ? 10 * log10(energy / dur) : .nan,
            eventCount: evs.count,
            strongEventCount: evs.filter { $0.lafMaxDb >= $0.backgroundDb + STRONG_MARGIN_DB }.count,
            categoryShares: shares,
            eventsByCategory: byCat,
            loudestEvent: loudest,
            allCalibrated: !ms.isEmpty && ms.allSatisfy { $0.calibrated },
            excludedMinutes: allMinutes.count - ms.count,
            eventsPerHour: Dynamics.eventsPerHour(evs.count, dur),
            dynamicsDb: Dynamics.medianSpread(ms),
            anyRecalibrated: allMinutes.contains { $0.recalibrated },
            humDb: Dynamics.medianL90(ms),
            windEventCount: wind.count
        )
    }

    /**
     * [summarize] for every span, newest first, each with its own minutes and events only. (Kotlin
     * slices sorted lists by binary search; filtering by the span gives the same records in the
     * same order once both are sorted by start.)
     */
    public static func summarizeAll(
        _ spans: [MeasurementSpan], _ minutes: [MinuteRecord], _ events: [NoiseEvent], eventMinLevelDb: Double = -.infinity
    ) -> [MeasurementSummary] {
        let sortedMinutes = stableSorted(minutes) { $0.startEpochMs }
        let sortedEvents = stableSorted(events) { $0.startEpochMs }
        let newestFirst = spans.enumerated().sorted { a, b in
            a.element.startMs > b.element.startMs || (a.element.startMs == b.element.startMs && a.offset < b.offset)
        }.map { $0.element }
        return newestFirst.map { summarize($0, sortedMinutes, sortedEvents, eventMinLevelDb: eventMinLevelDb) }
    }

    private static func stableSorted<T>(_ xs: [T], by key: (T) -> Int) -> [T] {
        xs.enumerated().sorted { a, b in
            let ka = key(a.element), kb = key(b.element)
            return ka < kb || (ka == kb && a.offset < b.offset)
        }.map { $0.element }
    }
}

/** Splitting stored minutes into measurements, and finding the measurement a record belongs to. */
public enum MeasurementSplitter {
    /** A gap longer than this between two minutes starts a new measurement. */
    public static let MAX_GAP_MS = 10 * 60_000
    /** A record belongs to a measurement that starts at most this much after it (clock jitter at the start). */
    public static let START_TOLERANCE_MS = 5_000

    /** Runs of minutes ((start epoch ms, duration s), any order), oldest first. */
    public static func split(_ minutes: [(Int, Double)], maxGapMs: Int = MAX_GAP_MS) -> [MeasurementSpan] {
        if minutes.isEmpty { return [] }
        // Kotlin's sortedBy is stable.
        let sorted = minutes.enumerated().sorted { a, b in a.element.0 < b.element.0 || (a.element.0 == b.element.0 && a.offset < b.offset) }.map { $0.element }
        var out: [MeasurementSpan] = []
        var start = sorted[0].0
        var end = endOf(sorted[0])
        for i in 1..<sorted.count {
            let m = sorted[i]
            if m.0 - end > maxGapMs {
                out.append(MeasurementSpan(start, end))
                start = m.0
            }
            end = Swift.max(end, endOf(m))
        }
        out.append(MeasurementSpan(start, end))
        return out
    }

    private static func endOf(_ m: (Int, Double)) -> Int { m.0 + Swift.max(1, JavaMath.round(m.1 * 1000)) }

    /**
     * Index into [starts] (measurement starts, ascending) of the measurement a record starting at
     * [t] belongs to: the latest one that started at or before [t] + [START_TOLERANCE_MS]; -1 if none.
     */
    public static func ownerIndex(_ starts: [Int], _ t: Int) -> Int {
        var lo = 0
        var hi = starts.count
        let key = t + START_TOLERANCE_MS
        while lo < hi {
            let mid = Int(UInt(bitPattern: lo + hi) >> 1)
            if starts[mid] <= key { lo = mid + 1 } else { hi = mid }
        }
        return lo - 1
    }
}
