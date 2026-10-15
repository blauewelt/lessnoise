import Foundation

/** One 125 ms LAF sample (Records.kt). */
public struct LafTick: Equatable {
    public let endSample: Int
    public let epochMs: Int
    public let lafDb: Double
    public let lafMaxDb: Double
    public let leqDb: Double
    /** False while the microphone is silenced (or recovering from it); such ticks are not evaluated. */
    public let valid: Bool
}

/** Per-second result. */
public struct SecondResult {
    public let endSample: Int
    public let epochMs: Int
    public let laeqDb: Double
    public let lafMaxDb: Double
    /** Unweighted (Z) Leq over the second, for diagnostics. */
    public let lzeqDb: Double
    /** Energetic average of the last ≤ 60 per-second LAeq values. */
    public let laeqRunning60sDb: Double
    /** Current event background (L90 of trailing window), NaN until enough history. */
    public let backgroundDb: Double
    /** Fraction of this second's 125 ms ticks that were valid (levels above use only those). */
    public let validFraction: Double
    /** Local floor (L90 of LAF over the trailing local window); NaN until enough history. */
    public let localFloorDb: Double
}

/** The calibration-dependent level fields of a minute (dB, NaN where missing). */
public struct MinuteLevels: Equatable {
    public var laeqDb: Double, lafMaxDb: Double, lafMinDb: Double, l1Db: Double, l10Db: Double, l50Db: Double, l90Db: Double

    public init(_ laeqDb: Double, _ lafMaxDb: Double, _ lafMinDb: Double, _ l1Db: Double, _ l10Db: Double, _ l50Db: Double, _ l90Db: Double) {
        self.laeqDb = laeqDb; self.lafMaxDb = lafMaxDb; self.lafMinDb = lafMinDb; self.l1Db = l1Db
        self.l10Db = l10Db; self.l50Db = l50Db; self.l90Db = l90Db
    }

    /** Every level shifted by [deltaDb]; NaN stays NaN. */
    public func shifted(_ deltaDb: Double) -> MinuteLevels {
        MinuteLevels(laeqDb + deltaDb, lafMaxDb + deltaDb, lafMinDb + deltaDb, l1Db + deltaDb, l10Db + deltaDb, l50Db + deltaDb, l90Db + deltaDb)
    }

    public static func == (a: MinuteLevels, b: MinuteLevels) -> Bool {
        sameDouble(a.laeqDb, b.laeqDb) && sameDouble(a.lafMaxDb, b.lafMaxDb) && sameDouble(a.lafMinDb, b.lafMinDb) &&
            sameDouble(a.l1Db, b.l1Db) && sameDouble(a.l10Db, b.l10Db) && sameDouble(a.l50Db, b.l50Db) && sameDouble(a.l90Db, b.l90Db)
    }
}

/** The calibration-dependent level fields of an event (dB). */
public struct EventLevels: Equatable {
    public var lafMaxDb: Double, selDb: Double, backgroundDb: Double
    public init(_ lafMaxDb: Double, _ selDb: Double, _ backgroundDb: Double) {
        self.lafMaxDb = lafMaxDb; self.selDb = selDb; self.backgroundDb = backgroundDb
    }
    public func shifted(_ deltaDb: Double) -> EventLevels { EventLevels(lafMaxDb + deltaDb, selDb + deltaDb, backgroundDb + deltaDb) }
}

/**
 * Per-minute aggregate — the main stored record (MinuteRecord in Records.kt). All level fields are
 * computed from valid audio only (see [validSeconds]); NaN when the minute had no valid audio.
 */
public struct MinuteRecord: Equatable {
    public var startEpochMs: Int
    /** ISO-8601 with offset, e.g. 2026-10-06T22:01:00+02:00. */
    public var startIso: String
    public var durationSeconds: Double
    public var laeqDb: Double
    public var lafMaxDb: Double
    public var lafMinDb: Double
    public var l1Db: Double
    public var l10Db: Double
    public var l50Db: Double
    public var l90Db: Double
    /** Events (bursts, without wind) that started in this minute and passed the floor in force. */
    public var eventCount: Int
    /** Bucket with the largest time share (incl. "unclassified"), nil if the classifier was off. */
    public var dominantCategory: String?
    /** Fraction of classifier frames per bucket id (sums to 1 if any frames), in bucket order. */
    public var categoryShares: OrderedDict<Double>
    public var classifierFrames: Int
    public var calibrationId: Int?
    public var calibrationOffsetDb: Double
    public var audioSource: String
    public var calibrated: Bool
    /** Seconds of valid audio in this minute. */
    public var validSeconds: Double
    /** Number of times the sample clock was re-anchored to the wall clock in this minute. */
    public var clockCorrections: Int
    /** Levels as originally measured, once re-evaluated with a later calibration; nil otherwise. */
    public var original: MinuteLevels?
    /** Calibration (id or "default") the [original] levels were measured with; nil if never re-evaluated. */
    public var recalibratedFromId: String?
    /** Offset the [original] levels were measured with; nil if never re-evaluated. */
    public var recalibrationOffsetDb: Double?
    /** Median over the minute's valid 125 ms ticks of the local floor; NaN without valid ticks. */
    public var localFloorDb: Double
    /** Events flagged as wind on the microphone that started in this minute (not in [eventCount]). */
    public var windEventCount: Int

    public init(
        startEpochMs: Int, startIso: String, durationSeconds: Double, laeqDb: Double, lafMaxDb: Double, lafMinDb: Double,
        l1Db: Double, l10Db: Double, l50Db: Double, l90Db: Double, eventCount: Int, dominantCategory: String?,
        categoryShares: OrderedDict<Double>, classifierFrames: Int, calibrationId: Int?, calibrationOffsetDb: Double,
        audioSource: String, calibrated: Bool, validSeconds: Double? = nil, clockCorrections: Int = 0,
        original: MinuteLevels? = nil, recalibratedFromId: String? = nil, recalibrationOffsetDb: Double? = nil,
        localFloorDb: Double = .nan, windEventCount: Int = 0
    ) {
        self.startEpochMs = startEpochMs; self.startIso = startIso; self.durationSeconds = durationSeconds
        self.laeqDb = laeqDb; self.lafMaxDb = lafMaxDb; self.lafMinDb = lafMinDb
        self.l1Db = l1Db; self.l10Db = l10Db; self.l50Db = l50Db; self.l90Db = l90Db
        self.eventCount = eventCount; self.dominantCategory = dominantCategory; self.categoryShares = categoryShares
        self.classifierFrames = classifierFrames; self.calibrationId = calibrationId
        self.calibrationOffsetDb = calibrationOffsetDb; self.audioSource = audioSource; self.calibrated = calibrated
        self.validSeconds = validSeconds ?? durationSeconds; self.clockCorrections = clockCorrections
        self.original = original; self.recalibratedFromId = recalibratedFromId
        self.recalibrationOffsetDb = recalibrationOffsetDb; self.localFloorDb = localFloorDb; self.windEventCount = windEventCount
    }

    /** Fraction of the minute's duration with valid audio (0…1). */
    public var coverage: Double { durationSeconds > 0 ? Swift.min(Swift.max(validSeconds / durationSeconds, 0.0), 1.0) : 0.0 }

    /** True if the levels were re-evaluated with a calibration made after the measurement. */
    public var recalibrated: Bool { recalibratedFromId != nil }

    /** The level fields of this record (NaN where missing). */
    public var levels: MinuteLevels { MinuteLevels(laeqDb, lafMaxDb, lafMinDb, l1Db, l10Db, l50Db, l90Db) }

    public static func == (a: MinuteRecord, b: MinuteRecord) -> Bool {
        a.startEpochMs == b.startEpochMs && a.startIso == b.startIso && sameDouble(a.durationSeconds, b.durationSeconds) &&
            a.levels == b.levels && a.eventCount == b.eventCount && a.dominantCategory == b.dominantCategory &&
            a.categoryShares == b.categoryShares && a.classifierFrames == b.classifierFrames && a.calibrationId == b.calibrationId &&
            sameDouble(a.calibrationOffsetDb, b.calibrationOffsetDb) && a.audioSource == b.audioSource && a.calibrated == b.calibrated &&
            sameDouble(a.validSeconds, b.validSeconds) && a.clockCorrections == b.clockCorrections && a.original == b.original &&
            a.recalibratedFromId == b.recalibratedFromId && sameDouble(a.recalibrationOffsetDb, b.recalibrationOffsetDb) &&
            sameDouble(a.localFloorDb, b.localFloorDb) && a.windEventCount == b.windEventCount
    }
}

/** A detected noise event with its classification (NoiseEvent in Records.kt). */
public struct NoiseEvent: Equatable {
    public var startEpochMs: Int
    public var startIso: String
    public var durationSeconds: Double
    public var lafMaxDb: Double
    /** Sound exposure level LAE re 1 s. */
    public var selDb: Double
    /** 5-min background (L90 of LAF over the trailing 5 min) at the start; not used for detection. */
    public var backgroundDb: Double
    /** Start threshold in force: the excess over the local floor (dB). */
    public var thresholdDb: Double
    /** Category id, "unclassified", "wind", or nil if the classifier was off. */
    public var dominantCategory: String?
    public var dominantScore: Float
    public var topLabels: [LabelScore]
    public var classifierFrames: Int
    public var calibrationId: Int?
    public var audioSource: String
    public var calibrated: Bool
    /** Absolute LAFmax floor in force when the event was detected (setting «Mindestpegel»). */
    public var minLevelDb: Double
    public var original: EventLevels?
    public var recalibratedFromId: String?
    public var recalibrationOffsetDb: Double?
    /** Detector v2 features (local floor, excess, shape, wind). */
    public var features: EventFeatures?

    public init(
        startEpochMs: Int, startIso: String, durationSeconds: Double, lafMaxDb: Double, selDb: Double, backgroundDb: Double,
        thresholdDb: Double, dominantCategory: String?, dominantScore: Float, topLabels: [LabelScore], classifierFrames: Int,
        calibrationId: Int?, audioSource: String, calibrated: Bool, minLevelDb: Double = .nan, original: EventLevels? = nil,
        recalibratedFromId: String? = nil, recalibrationOffsetDb: Double? = nil, features: EventFeatures? = nil
    ) {
        self.startEpochMs = startEpochMs; self.startIso = startIso; self.durationSeconds = durationSeconds
        self.lafMaxDb = lafMaxDb; self.selDb = selDb; self.backgroundDb = backgroundDb; self.thresholdDb = thresholdDb
        self.dominantCategory = dominantCategory; self.dominantScore = dominantScore; self.topLabels = topLabels
        self.classifierFrames = classifierFrames; self.calibrationId = calibrationId; self.audioSource = audioSource
        self.calibrated = calibrated; self.minLevelDb = minLevelDb; self.original = original
        self.recalibratedFromId = recalibratedFromId; self.recalibrationOffsetDb = recalibrationOffsetDb; self.features = features
    }

    public var recalibrated: Bool { recalibratedFromId != nil }
    /** Wind on the microphone: excluded from event counts, events/h and the category highlights. */
    public var wind: Bool { features?.wind == true }
    public var levels: EventLevels { EventLevels(lafMaxDb, selDb, backgroundDb) }
    /** Read-time filter with the *current* floor. */
    public func reachesFloor(_ floorDb: Double) -> Bool { lafMaxDb >= floorDb }

    public static func == (a: NoiseEvent, b: NoiseEvent) -> Bool {
        a.startEpochMs == b.startEpochMs && a.startIso == b.startIso && sameDouble(a.durationSeconds, b.durationSeconds) &&
            sameDouble(a.lafMaxDb, b.lafMaxDb) && sameDouble(a.selDb, b.selDb) && sameDouble(a.backgroundDb, b.backgroundDb) &&
            sameDouble(a.thresholdDb, b.thresholdDb) && a.dominantCategory == b.dominantCategory && a.dominantScore == b.dominantScore &&
            a.topLabels == b.topLabels && a.classifierFrames == b.classifierFrames && a.calibrationId == b.calibrationId &&
            a.audioSource == b.audioSource && a.calibrated == b.calibrated && sameDouble(a.minLevelDb, b.minLevelDb) &&
            a.original == b.original && a.recalibratedFromId == b.recalibratedFromId &&
            sameDouble(a.recalibrationOffsetDb, b.recalibrationOffsetDb) && a.features == b.features
    }
}

/**
 * ISO-8601 with zone offset, as java.time prints it: `yyyy-MM-dd'T'HH:mm:ss[.SSS]XXX` (Records.kt
 * `Iso`; offset 0 is printed as "Z") and, for reports, `…xxx` (offset 0 as "+00:00").
 */
public enum Iso {
    public static func format(_ epochMs: Int, _ zone: TimeZone, millis: Bool = false) -> String {
        render(epochMs, zone, millis: millis, zeroAsZ: true)
    }

    /** The reports' format (`yyyy-MM-dd'T'HH:mm:ssxxx`): like [format] without millis, but "+00:00" for UTC. */
    public static func formatNumericOffset(_ epochMs: Int, _ zone: TimeZone) -> String {
        render(epochMs, zone, millis: false, zeroAsZ: false)
    }

    /** Offset of [zone] from UTC at [epochMs], in seconds (from the system's tz database). */
    public static func offsetSeconds(_ epochMs: Int, _ zone: TimeZone) -> Int {
        zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(JavaMath.floorDiv(epochMs, 1000))))
    }

    private static func render(_ epochMs: Int, _ zone: TimeZone, millis: Bool, zeroAsZ: Bool) -> String {
        let offset = offsetSeconds(epochMs, zone)
        let localMs = epochMs + offset * 1000
        let days = JavaMath.floorDiv(localMs, 86_400_000)
        let msOfDay = localMs - days * 86_400_000
        let (y, m, d) = civil(days)
        let h = msOfDay / 3_600_000
        let mi = (msOfDay / 60_000) % 60
        let s = (msOfDay / 1000) % 60
        let ms = msOfDay % 1000
        var out = pad(y, 4) + "-" + pad(m, 2) + "-" + pad(d, 2) + "T" + pad(h, 2) + ":" + pad(mi, 2) + ":" + pad(s, 2)
        if millis { out += "." + pad(ms, 3) }
        if offset == 0 && zeroAsZ { return out + "Z" }
        let a = abs(offset)
        out += (offset < 0 ? "-" : "+") + pad(a / 3600, 2) + ":" + pad((a / 60) % 60, 2)
        if a % 60 != 0 { out += ":" + pad(a % 60, 2) }
        return out
    }

    private static func pad(_ v: Int, _ width: Int) -> String {
        let s = String(v)
        return s.count >= width ? s : String(repeating: "0", count: width - s.count) + s
    }

    /** Days since 1970-01-01 → (year, month, day) in the proleptic Gregorian calendar. */
    static func civil(_ days: Int) -> (Int, Int, Int) {
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        var y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        if m <= 2 { y += 1 }
        return (y, m, d)
    }
}
