import Foundation

/** A measurement row as the upload needs it: start, end (nil while running) and its shifted point. */
public struct MeasurementRow: Equatable {
    public let id: Int
    public let startMs: Int
    public let endMs: Int?
    /** The shifted point (nil: not shared). */
    public let point: LocationNoise.Point?
    public init(id: Int, startMs: Int, endMs: Int?, point: LocationNoise.Point?) {
        self.id = id; self.startMs = startMs; self.endMs = endMs; self.point = point
    }
}

/**
 * The place of a record (PlaceIndex in Uploader.kt): the shifted point of the measurement it
 * belongs to (the one that started last before it, [MeasurementSplitter.ownerIndex]), nil if that
 * measurement is not shared or the record lies after its end. Records without a point are never sent.
 */
public struct PlaceIndex {
    private let sorted: [MeasurementRow]
    private let starts: [Int]

    public init(_ measurements: [MeasurementRow]) {
        sorted = measurements.enumerated().sorted { a, b in
            (a.element.startMs, a.element.id, a.offset) < (b.element.startMs, b.element.id, b.offset)
        }.map { $0.element }
        starts = sorted.map { $0.startMs }
    }

    public func pointAt(_ t: Int) -> LocationNoise.Point? {
        let i = MeasurementSplitter.ownerIndex(starts, t)
        if i < 0 { return nil }
        let m = sorted[i]
        if let end = m.endMs, t >= end { return nil }
        return m.point
    }

    /** The point of the newest shared measurement (sent with the placement details). */
    public var latest: LocationNoise.Point? { sorted.last { $0.point != nil }?.point }
}

/**
 * The marker rules of the upload (Uploader.kt), as pure functions of the snapshot and the clock,
 * so the iOS uploader and its tests share them.
 */
public enum UploadRules {
    /** The server refuses records older than 7 days (DESIGN.md §8, `MAX_RECORD_AGE_DAYS`). */
    public static let MAX_AGE_MS = 7 * 24 * 3600 * 1000
    /** Records this close to the limit are left out: they could be refused by the time they arrive. */
    public static let AGE_MARGIN_MS = 3600 * 1000

    /** Shown while the server refuses the token (401). */
    public static let AUTH_FAILED_TEXT =
        "Nach 60 Tagen ohne Werte hat der Server deine Daten gelöscht. Tippe auf «Neu starten», um wieder zu teilen."
    /** One message for the failures of the server that are retried by themselves (429, 5xx). */
    public static let RETRY_TEXT = "Senden klappt gerade nicht. Neuer Versuch folgt."
    /** No connection to the server. */
    public static let NO_CONNECTION_TEXT = "Keine Verbindung zum Server. Neuer Versuch folgt."

    /** Switch sharing on («Ja, auf die Karte», the switch in «Teilen», the consent of «Laut! Melden»). */
    public static func enable(_ s: UploadSnapshot, nowMs: Int) -> UploadSnapshot {
        // The very first time, shared measurements of the last 7 days are included; after that,
        // nothing measured while sharing was off is ever sent (except the running measurement).
        let floor = (s.sendFloorMs == 0 && !s.registered) ? nowMs - MAX_AGE_MS : nowMs
        var n = s
        n.enabled = true
        n.shareDecision = .YES
        n.sendFloorMs = Swift.max(s.sendFloorMs, floor)
        n.lastError = nil
        return n
    }

    /** Switch it off: records NO; the caller also drops pending reports ([ReportQueue.dropPending]). */
    public static func disable(_ s: UploadSnapshot) -> UploadSnapshot {
        var n = s
        n.enabled = false
        n.shareDecision = .NO
        return n
    }

    /**
     * The running measurement that started at [measurementStartMs] has just got its shifted point:
     * move both markers and the opt-in floor back to just before its start (Uploader.pointAttached).
     */
    public static func pointAttached(_ s: UploadSnapshot, measurementStartMs: Int) -> UploadSnapshot {
        let back = measurementStartMs - MeasurementSplitter.START_TOLERANCE_MS - 1
        var n = s
        n.minutesSentUpToMs = Swift.min(s.minutesSentUpToMs, back)
        n.eventsSentUpToMs = Swift.min(s.eventsSentUpToMs, back)
        n.sendFloorMs = Swift.min(s.sendFloorMs, back)
        return n
    }

    /** After «Alte Messungen neu bewerten»: send the last 7 days again. */
    public static func rewindAfterReevaluation(_ s: UploadSnapshot) -> UploadSnapshot {
        var n = s
        n.minutesSentUpToMs = 0
        n.eventsSentUpToMs = 0
        return n
    }

    /** Oldest start that is still sent: the opt-in floor, and nothing the server would refuse as too old. */
    public static func effectiveFloorMs(_ s: UploadSnapshot, nowMs: Int) -> Int {
        Swift.max(s.sendFloorMs, nowMs - MAX_AGE_MS + AGE_MARGIN_MS)
    }

    /** `afterMs` for counting the minutes still to send (settings screen). */
    public static func minutesPendingAfterMs(_ s: UploadSnapshot, nowMs: Int) -> Int {
        Swift.max(s.minutesSentUpToMs, effectiveFloorMs(s, nowMs: nowMs) - 1)
    }

    /** Forget the device («Neu starten», «Vom Server löschen»): markers, id, token and the noise secret go. */
    public static func forget(_ s: UploadSnapshot, nowMs: Int) -> UploadSnapshot {
        var n = s
        n.deviceId = nil; n.token = nil; n.lastSiteJson = nil; n.authFailed = false
        n.minutesSentUpToMs = 0; n.eventsSentUpToMs = 0; n.sendFloorMs = nowMs
        n.lastSuccessAtMs = nil; n.lastError = nil; n.refused = []; n.noiseSecret = nil
        return n
    }

    /**
     * One batch out of [raw] (fetched with one row more than [batchSize]): a full batch must not
     * end in the middle of several records with the same start (the marker means "start > marker"),
     * so such a tail is cut off and goes with the next batch — unless the whole batch has one start.
     * Returns the rows and whether the batch was full (more may follow).
     */
    public static func batchRows<T>(_ raw: [T], batchSize: Int, start: (T) -> Int) -> (rows: [T], full: Bool) {
        let full = raw.count > batchSize
        if !full { return (raw, false) }
        let head = Array(raw.prefix(batchSize))
        let nextStart = start(raw[batchSize])
        if start(head.last!) != nextStart { return (head, true) }
        var cut = head
        while let last = cut.last, start(last) == nextStart { cut.removeLast() }
        return (cut.isEmpty ? head : cut, true)
    }

    /** Records refused for good are kept (bounded) and counted. */
    public static func recordRefused(_ s: UploadSnapshot, kind: String, starts: [Int], rejected: [UploadRejected]) -> UploadSnapshot {
        if rejected.isEmpty { return s }
        let add = rejected.compactMap { r -> RefusedRecord? in
            (r.index >= 0 && r.index < starts.count) ? RefusedRecord(kind: kind, startEpochMs: starts[r.index], reason: r.reasons) : nil
        }
        var n = s
        n.refused = Array((s.refused + add).suffix(UploadSnapshot.REFUSED_KEEP))
        n.refusedTotal = s.refusedTotal + add.count
        return n
    }
}

/** Uploads outside a running shared measurement (UploadCadence in UploadWorker.kt). */
public enum UploadCadence {
    /** One upload this long after «Stoppen». */
    public static let AFTER_STOP_MS = 15_000

    /**
     * Five minutes after the next full hour (local time), from [nowMs] (UploadModule.delayToNextHourMs).
     * On iOS the hourly fallback is a BGAppRefreshTask whose earliest begin date is this; the
     * system decides when it really runs (ios/DESIGN.md «Background»).
     */
    public static func delayToNextHourMs(_ nowMs: Int, _ zone: TimeZone) -> Int {
        let offset = Iso.offsetSeconds(nowMs, zone) * 1000
        let local = nowMs + offset
        let hourStartLocal = JavaMath.floorDiv(local, 3_600_000) * 3_600_000
        let next = hourStartLocal - offset + 3_600_000 + 5 * 60_000
        return Swift.max(next - nowMs, 0)
    }

    /** The hourly fallback job's runs in [fromMs, toMs). */
    public static func hourlyRunTimes(_ fromMs: Int, _ toMs: Int, _ zone: TimeZone) -> [Int] {
        var out: [Int] = []
        var t = fromMs + delayToNextHourMs(fromMs, zone)
        while t < toMs { out.append(t); t += 3_600_000 }
        return out
    }
}

/**
 * The live map (0.6.3, «every 30 seconds»): when the app uploads while a shared measurement runs
 * (LiveUploadPlan in UploadWorker.kt; same rules, same constants, golden-tested on scripted runs).
 */
public final class LiveUploadPlan {
    public enum Kind: String, Equatable { case FULL, EVENTS }

    public struct Run: Equatable {
        public let atMs: Int
        public let kind: Kind
        public init(atMs: Int, kind: Kind) { self.atMs = atMs; self.kind = kind }
    }

    public enum Result: String, Equatable { case OK, RATE_LIMITED, FAILED }

    /** First full run this long after the running measurement got its point. */
    public static let FIRST_AFTER_POINT_MS = 30_000
    /** Then a full run (minutes and events) every … */
    public static let MINUTES_EVERY_MS = 2 * 60_000
    /** A loud event goes out this long after it ended. */
    public static let EVENT_DELAY_MS = 30_000
    /** At most one run per … */
    public static let MIN_GAP_MS = 30_000
    /** After a 429: slower for this long, then one level faster again. */
    public static let SLOW_FOR_MS = 60 * 60_000
    public static let MAX_LEVEL = 3

    private var nextFullAtMs: Int
    private var eventDueAtMs: Int?
    private var lastRunAtMs = Int.min / 4
    private var level = 0
    private var slowUntilMs = Int.min / 4

    public init(pointAtMs: Int) {
        nextFullAtMs = pointAtMs + LiveUploadPlan.FIRST_AFTER_POINT_MS
    }

    /** The back-off level after 429 answers (0: normal). */
    public var backOffLevel: Int { level }

    private func fullInterval() -> Int { LiveUploadPlan.MINUTES_EVERY_MS << level }

    /** A shared (not wind) event ended at [endMs]. */
    public func onEvent(_ endMs: Int) {
        if endMs <= 0 || level > 0 { return } // backed off: the next full run sends it
        let due = Swift.max(endMs + LiveUploadPlan.EVENT_DELAY_MS, lastRunAtMs + LiveUploadPlan.MIN_GAP_MS)
        eventDueAtMs = eventDueAtMs.map { Swift.min($0, due) } ?? due
    }

    /** The next run. */
    public func next() -> Run {
        guard let ev = eventDueAtMs else { return Run(atMs: nextFullAtMs, kind: .FULL) }
        if ev + LiveUploadPlan.MIN_GAP_MS > nextFullAtMs { return Run(atMs: Swift.min(ev, nextFullAtMs), kind: .FULL) }
        return Run(atMs: ev, kind: .EVENTS)
    }

    /** [run] ended at [nowMs] with [result]. */
    public func done(_ run: Run, _ result: Result, _ nowMs: Int) {
        lastRunAtMs = nowMs
        switch result {
        case .RATE_LIMITED:
            level = Swift.min(level + 1, LiveUploadPlan.MAX_LEVEL)
            slowUntilMs = nowMs + LiveUploadPlan.SLOW_FOR_MS
            eventDueAtMs = nil
            nextFullAtMs = nowMs + fullInterval()
        case .FAILED:
            eventDueAtMs = nil
            nextFullAtMs = nowMs + fullInterval()
        case .OK:
            if level > 0 && nowMs >= slowUntilMs {
                level -= 1
                slowUntilMs = nowMs + LiveUploadPlan.SLOW_FOR_MS
            }
            eventDueAtMs = nil
            nextFullAtMs = run.kind == .FULL ? nowMs + fullInterval() : Swift.max(nextFullAtMs, nowMs + LiveUploadPlan.MIN_GAP_MS)
        }
    }
}
