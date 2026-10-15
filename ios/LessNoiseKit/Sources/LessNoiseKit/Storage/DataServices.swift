import Foundation

/** A measurement as «Messungen» shows it: its stored row with a resolved end (Measurement in Measurements.kt). */
public struct Measurement: Equatable, Identifiable {
    public let id: Int
    public let span: MeasurementSpan
    /** The shifted point that is sent if this measurement is shared, else nil. */
    public let point: LocationNoise.Point?
    /** The measurement that is running right now (its end is "now"). */
    public let running: Bool

    public var cell: String? { point?.cell }
}

/** Pure rules for turning stored rows into measurements (MeasurementList.resolve). */
public enum MeasurementList {
    /**
     * [rows] in any order; [minutes] = (start, end) of the stored minutes. End of a row: its end_ms;
     * for the newest open row while a measurement is [running], [nowMs]; for any other open row the
     * end of its last minute. Rows without a minute are left out, except the running one. Oldest first.
     */
    public static func resolve(_ rows: [MeasurementEntity], _ minutes: [(Int, Int)], nowMs: Int, running: Bool) -> [Measurement] {
        if rows.isEmpty { return [] }
        let sorted = rows.sorted { ($0.startMs, $0.id) < ($1.startMs, $1.id) }
        let starts = sorted.map { $0.startMs }
        var lastEnd = [Int](repeating: Int.min, count: sorted.count)
        var count = [Int](repeating: 0, count: sorted.count)
        for (s, e) in minutes {
            let i = MeasurementSplitter.ownerIndex(starts, s)
            if i < 0 { continue }
            count[i] += 1
            if e > lastEnd[i] { lastEnd[i] = e }
        }
        var out: [Measurement] = []
        for (i, r) in sorted.enumerated() {
            let isRunning = running && r.endMs == nil && i == sorted.count - 1
            if count[i] == 0 && !isRunning { continue }
            let end = r.endMs ?? (isRunning ? nowMs : lastEnd[i])
            out.append(Measurement(id: r.id, span: MeasurementSpan(r.startMs, Swift.max(end, r.startMs + 1)), point: r.point, running: isRunning))
        }
        return out
    }
}

/** One row of «Messungen»: the measurement and its summary. */
public struct MeasurementItem: Identifiable {
    public let measurement: Measurement
    public let summary: MeasurementSummary
    public var id: Int { measurement.id }
}

/** The data behind «Messungen»: the list (newest first), and one measurement's minutes and events for the chart. */
public final class HistoryQueries {
    private let repo: MeasurementRepository

    public init(_ repo: MeasurementRepository) { self.repo = repo }

    public func items(nowMs: Int, running: Bool, eventMinLevelDb: Double) throws -> [MeasurementItem] {
        let ms = MeasurementList.resolve(try repo.allMeasurements(), try repo.minuteSpans(), nowMs: nowMs, running: running)
        if ms.isEmpty { return [] }
        let minutes = try repo.allMinutes()
        let events = try repo.allEvents()
        let summaries = ms.map { MeasurementSummarizer.summarize($0.span, minutes, events, eventMinLevelDb: eventMinLevelDb) }
        return zip(ms, summaries).map { MeasurementItem(measurement: $0, summary: $1) }.reversed()
    }

    public func data(_ m: Measurement) throws -> (minutes: [MinuteRecord], events: [NoiseEvent]) {
        let from = m.span.startMs - MeasurementSplitter.START_TOLERANCE_MS
        return (try repo.minutesBetween(from, m.span.endMs), try repo.eventsBetween(from, m.span.endMs))
    }
}

/** The CSV export of «Deine Messungen» (MeasurementRepository.exportCsv): two files, the same columns as Android. */
public enum CsvExport {
    /** «wenigerlaerm-minuten-20261015-2130.csv», «wenigerlaerm-ereignisse-…». */
    public static func fileNames(_ nowMs: Int, _ zone: TimeZone) -> (minutes: String, events: String) {
        let iso = Iso.format(nowMs, zone) // yyyy-MM-ddTHH:mm:ss…
        let c = Array(iso)
        let stamp = String(c[0..<4]) + String(c[5..<7]) + String(c[8..<10]) + "-" + String(c[11..<13]) + String(c[14..<16])
        return ("wenigerlaerm-minuten-\(stamp).csv", "wenigerlaerm-ereignisse-\(stamp).csv")
    }

    /** Writes both files into [dir] (emptied first) and returns their URLs. */
    public static func write(_ repo: MeasurementRepository, bucketIds: [String], dir: URL, nowMs: Int, zone: TimeZone = .current) throws -> [URL] {
        let fm = FileManager.default
        if fm.fileExists(atPath: dir.path) {
            for f in try fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) { try? fm.removeItem(at: f) }
        } else {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        let names = fileNames(nowMs, zone)
        let m = dir.appendingPathComponent(names.minutes)
        let e = dir.appendingPathComponent(names.events)
        try Data(Csv.minutes(try repo.allMinutes(), bucketIds).utf8).write(to: m)
        try Data(Csv.events(try repo.allEvents()).utf8).write(to: e)
        return [m, e]
    }

    /** Deletes the export folder (after «Alle löschen»). */
    public static func clear(_ dir: URL) { try? FileManager.default.removeItem(at: dir) }
}

/**
 * «Alte Messungen anpassen»: re-evaluates stored minutes and events with a newer calibration in
 * one transaction, reporting progress (Recalibrator.kt).
 */
public final class Recalibrator {
    public enum State: Equatable {
        case idle
        case running(done: Int, total: Int)
        case done(minutes: Int, events: Int, target: Recalibration.Target)
        case failed(String)
    }

    private let repo: MeasurementRepository
    private let calibrations: CalibrationRepository
    private let _state = Locked<State>(.idle)
    public let changes = Observers()

    public init(_ repo: MeasurementRepository, _ calibrations: CalibrationRepository) {
        self.repo = repo; self.calibrations = calibrations
    }

    public var state: State { _state.get }
    public var running: Bool { if case .running = state { return true }; return false }

    private func set(_ s: State) { _state.set(s); changes.notify() }

    public func counts(_ target: Recalibration.Target) -> (minutes: Int, events: Int) {
        (try? repo.countsToRecalibrate(target)) ?? (0, 0)
    }

    /** Runs the re-evaluation on a background queue; false if one is already running. */
    @discardableResult
    public func start(_ target: Recalibration.Target, queue: DispatchQueue = .global(qos: .userInitiated), completion: (() -> Void)? = nil) -> Bool {
        let ok = _state.update { s -> Bool in
            if case .running = s { return false }
            s = .running(done: 0, total: 0)
            return true
        }
        if !ok { return false }
        changes.notify()
        queue.async {
            self.runNow(target)
            completion?()
        }
        return true
    }

    /** The same synchronously (tests). */
    public func runNow(_ target: Recalibration.Target) {
        do {
            let offsets = try calibrations.offsets()
            let r = try repo.recalibrate(target, offsets: offsets) { done, total in self.set(.running(done: done, total: total)) }
            set(.done(minutes: r.minutes, events: r.events, target: target))
        } catch {
            set(.failed(String(describing: error)))
        }
    }

    public func acknowledge() { if !running { set(.idle) } }
}
