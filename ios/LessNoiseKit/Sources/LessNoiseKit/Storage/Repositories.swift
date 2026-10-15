import Foundation
import GRDB

/** One stored measurement row (MeasurementEntity in Database.kt). */
public struct MeasurementEntity: Equatable {
    public let id: Int
    public let startMs: Int
    /** nil while running, or if the app was ended before «Stoppen» (then its last minute ends it). */
    public let endMs: Int?
    /** The shifted point of a shared measurement (exactly what is sent); nil: not shared. */
    public let point: LocationNoise.Point?

    public init(id: Int, startMs: Int, endMs: Int?, point: LocationNoise.Point?) {
        self.id = id; self.startMs = startMs; self.endMs = endMs; self.point = point
    }

    /** The hectare the map shows this measurement in, if it is shared. */
    public var cell: String? { point?.cell }
}

/** A stored event with its row id (for list identity). */
public struct StoredEvent: Equatable, Identifiable {
    public let id: Int
    public let event: NoiseEvent
}

/**
 * Stored aggregates (minutes, events, measurements) and their queries (MeasurementDao and
 * MeasurementRepository of Android). Every method is synchronous and thread-safe (GRDB's queue
 * serialises); call it off the main thread for anything that can be large.
 */
public final class MeasurementRepository {
    public let db: AppDatabase

    public init(_ db: AppDatabase) { self.db = db }

    // ---- Records ----------------------------------------------------------------------------

    @discardableResult
    public func insert(_ m: MinuteRecord) throws -> Int {
        try db.write { d in
            try d.execute(sql: RowMapping.insertSql("minutes", RowMapping.minuteColumns), arguments: RowMapping.minuteArguments(m))
            return Int(d.lastInsertedRowID)
        }
    }

    @discardableResult
    public func insert(_ e: NoiseEvent) throws -> Int {
        try db.write { d in
            try d.execute(sql: RowMapping.insertSql("events", RowMapping.eventColumns), arguments: RowMapping.eventArguments(e))
            return Int(d.lastInsertedRowID)
        }
    }

    public func allMinutes() throws -> [MinuteRecord] {
        try db.read { try Row.fetchAll($0, sql: "SELECT * FROM minutes ORDER BY startEpochMs ASC, id ASC").map(RowMapping.minute) }
    }

    public func allEvents() throws -> [NoiseEvent] {
        try db.read { try Row.fetchAll($0, sql: "SELECT * FROM events ORDER BY startEpochMs ASC, id ASC").map(RowMapping.event) }
    }

    /** Minutes starting in [fromMs, toMs). */
    public func minutesBetween(_ fromMs: Int, _ toMs: Int) throws -> [MinuteRecord] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT * FROM minutes WHERE startEpochMs >= ? AND startEpochMs < ? ORDER BY startEpochMs ASC, id ASC",
                             arguments: [fromMs, toMs]).map(RowMapping.minute)
        }
    }

    /** Events starting in [fromMs, toMs). */
    public func eventsBetween(_ fromMs: Int, _ toMs: Int) throws -> [NoiseEvent] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT * FROM events WHERE startEpochMs >= ? AND startEpochMs < ? ORDER BY startEpochMs ASC, id ASC",
                             arguments: [fromMs, toMs]).map(RowMapping.event)
        }
    }

    /** The newest [limit] events, newest first («Letzte Lärmspitzen»). */
    public func recentEvents(_ limit: Int = 30) throws -> [StoredEvent] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT * FROM events ORDER BY startEpochMs DESC, id DESC LIMIT ?", arguments: [limit])
                .map { StoredEvent(id: $0["id"], event: RowMapping.event($0)) }
        }
    }

    /** Events of the measurement starting at [sinceMs] (with the split tolerance), newest first. */
    public func recentEvents(since sinceMs: Int, limit: Int = 30) throws -> [StoredEvent] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT * FROM events WHERE startEpochMs >= ? ORDER BY startEpochMs DESC, id DESC LIMIT ?",
                             arguments: [sinceMs - MeasurementSplitter.START_TOLERANCE_MS, limit])
                .map { StoredEvent(id: $0["id"], event: RowMapping.event($0)) }
        }
    }

    public func minuteCount() throws -> Int { try db.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM minutes") ?? 0 } }
    public func eventCount() throws -> Int { try db.read { try Int.fetchOne($0, sql: "SELECT COUNT(*) FROM events") ?? 0 } }

    /** «Alle löschen»: minutes, events and measurements (calibrations stay). */
    public func clear() throws {
        try db.write { d in
            try d.execute(sql: "DELETE FROM minutes")
            try d.execute(sql: "DELETE FROM events")
            try d.execute(sql: "DELETE FROM measurements")
        }
    }

    // ---- Upload (read-only, oldest first after a marker) ---------------------------------------

    public func minutesAfter(_ afterMs: Int, _ limit: Int) throws -> [MinuteRecord] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT * FROM minutes WHERE startEpochMs > ? ORDER BY startEpochMs ASC, id ASC LIMIT ?",
                             arguments: [afterMs, limit]).map(RowMapping.minute)
        }
    }

    public func eventsAfter(_ afterMs: Int, _ limit: Int) throws -> [NoiseEvent] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT * FROM events WHERE startEpochMs > ? ORDER BY startEpochMs ASC, id ASC LIMIT ?",
                             arguments: [afterMs, limit]).map(RowMapping.event)
        }
    }

    /** Minutes after the marker that belong to a shared measurement (a point), i.e. that will be sent. */
    public func sharedMinuteCountAfter(_ afterMs: Int) throws -> Int {
        try db.read {
            try Int.fetchOne($0, sql: """
                SELECT COUNT(*) FROM minutes AS m WHERE m.startEpochMs > ? AND
                (SELECT point_e FROM measurements WHERE start_ms <= m.startEpochMs + \(MeasurementSplitter.START_TOLERANCE_MS)
                 ORDER BY start_ms DESC LIMIT 1) IS NOT NULL
                """, arguments: [afterMs]) ?? 0
        }
    }

    // ---- Measurements -------------------------------------------------------------------------

    private static func entity(_ r: Row) -> MeasurementEntity {
        let e: Int? = r["point_e"], n: Int? = r["point_n"], s: Int? = r["sigma_m"]
        let point = (e != nil && n != nil && s != nil) ? LocationNoise.Point(e: e!, n: n!, sigmaM: s!) : nil
        return MeasurementEntity(id: r["id"], startMs: r["start_ms"], endMs: r["end_ms"], point: point)
    }

    public func allMeasurements() throws -> [MeasurementEntity] {
        try db.read { try Row.fetchAll($0, sql: "SELECT * FROM measurements ORDER BY start_ms ASC, id ASC").map(Self.entity) }
    }

    /**
     * «Starten»: a new measurement row with its shifted [point] (nil = not shared). Open rows left
     * by a measurement that ended without «Stoppen» are closed first, at the end of their last minute.
     */
    @discardableResult
    public func beginMeasurement(_ startMs: Int, _ point: LocationNoise.Point?) throws -> Int {
        try db.write { d in
            let open = try Row.fetchAll(d, sql: "SELECT * FROM measurements WHERE end_ms IS NULL ORDER BY start_ms ASC, id ASC").map(Self.entity)
            for (i, m) in open.enumerated() {
                let next = i + 1 < open.count ? open[i + 1].startMs : startMs
                let end = try Int.fetchOne(d, sql: """
                    SELECT MAX(startEpochMs + CAST(durationSeconds * 1000 AS INTEGER)) FROM minutes WHERE startEpochMs >= ? AND startEpochMs < ?
                    """, arguments: [m.startMs - MeasurementSplitter.START_TOLERANCE_MS, next]) ?? m.startMs
                try d.execute(sql: "UPDATE measurements SET end_ms = ? WHERE id = ?", arguments: [end, m.id])
            }
            try d.execute(sql: "INSERT INTO measurements (start_ms, end_ms, cell, point_e, point_n, sigma_m) VALUES (?, NULL, ?, ?, ?, ?)",
                          arguments: [startMs, point?.cell, point?.e, point?.n, point?.sigmaM])
            return Int(d.lastInsertedRowID)
        }
    }

    /** «Stoppen». */
    public func endMeasurement(_ id: Int, _ endMs: Int) throws {
        try db.write { try $0.execute(sql: "UPDATE measurements SET end_ms = ? WHERE id = ?", arguments: [endMs, id]) }
    }

    /**
     * The RUNNING measurement that started at [startMs] gets its shifted [point] after the fact.
     * Only a row that is still open and has no point yet: one that already ended unshared stays
     * unshared. False if nothing changed.
     */
    public func attachPoint(_ startMs: Int, _ point: LocationNoise.Point) throws -> Bool {
        try db.write { d in
            try d.execute(sql: """
                UPDATE measurements SET cell = ?, point_e = ?, point_n = ?, sigma_m = ?
                WHERE start_ms = ? AND end_ms IS NULL AND point_e IS NULL
                """, arguments: [point.cell, point.e, point.n, point.sigmaM, startMs])
            return d.changesCount > 0
        }
    }

    /** The shifted point of the newest shared measurement (the «Karte» tab opens there), or nil. */
    public func latestSharedPoint() throws -> LocationNoise.Point? {
        try db.read {
            try Row.fetchOne($0, sql: "SELECT * FROM measurements WHERE point_e IS NOT NULL ORDER BY start_ms DESC, id DESC LIMIT 1")
                .map(Self.entity)?.point
        }
    }

    /** (start, end) of every stored minute, for [MeasurementList.resolve]. */
    public func minuteSpans() throws -> [(Int, Int)] {
        try db.read {
            try Row.fetchAll($0, sql: "SELECT startEpochMs, durationSeconds FROM minutes").map { r in
                let s: Int = r[0]
                let d: Double = r[1]
                return (s, s + Int((d * 1000).rounded(.towardZero)))
            }
        }
    }

    // ---- Re-evaluation with a later calibration -----------------------------------------------

    static let scope = "audioSource = ? AND (calibrationId IS NULL OR calibrationId != ?)"

    public func countsToRecalibrate(_ target: Recalibration.Target) throws -> (minutes: Int, events: Int) {
        try db.read { d in
            let m = try Int.fetchOne(d, sql: "SELECT COUNT(*) FROM minutes WHERE \(Self.scope)", arguments: [target.audioSource, target.calibrationId]) ?? 0
            let e = try Int.fetchOne(d, sql: "SELECT COUNT(*) FROM events WHERE \(Self.scope)", arguments: [target.audioSource, target.calibrationId]) ?? 0
            return (m, e)
        }
    }

    /**
     * Re-evaluates every minute and event in scope with [target] in ONE transaction (all or
     * nothing); [offsets]: calibration id → offset (events store none of their own).
     */
    public func recalibrate(_ target: Recalibration.Target, offsets: [Int: Double], progress: (Int, Int) -> Void) throws -> (minutes: Int, events: Int) {
        try db.write { d in
            let minutes = try Row.fetchAll(d, sql: "SELECT * FROM minutes WHERE \(Self.scope) ORDER BY id", arguments: [target.audioSource, target.calibrationId])
            let events = try Row.fetchAll(d, sql: "SELECT * FROM events WHERE \(Self.scope) ORDER BY id", arguments: [target.audioSource, target.calibrationId])
            let total = minutes.count + events.count
            var done = 0
            progress(0, total)
            for r in minutes {
                let id: Int = r["id"]
                let m = Recalibration.recalibrate(RowMapping.minute(r), target)
                var args = RowMapping.minuteArguments(m)
                args += [id]
                try d.execute(sql: RowMapping.updateSql("minutes", RowMapping.minuteColumns), arguments: args)
                done += 1
                if done % 500 == 0 { progress(done, total) }
            }
            for r in events {
                let id: Int = r["id"]
                let e = RowMapping.event(r)
                let current = e.calibrationId.flatMap { offsets[$0] } ?? Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
                var args = RowMapping.eventArguments(Recalibration.recalibrate(e, current, target))
                args += [id]
                try d.execute(sql: RowMapping.updateSql("events", RowMapping.eventColumns), arguments: args)
                done += 1
                if done % 500 == 0 { progress(done, total) }
            }
            progress(total, total)
            return (minutes.count, events.count)
        }
    }
}

/** One stored calibration (CalibrationEntity). */
public struct CalibrationEntity: Equatable, Identifiable {
    public static let METHOD_REFERENCE = "reference_meter"
    public static let METHOD_CALIBRATOR = "acoustic_calibrator"
    public static let METHOD_MANUAL = "manual"
    public static let METHOD_RESET = "reset_default"

    public var id: Int = 0
    public var deviceModel: String
    public var audioSource: String
    public var offsetDb: Double
    /** reference_meter | acoustic_calibrator | manual | reset_default */
    public var method: String
    public var createdEpochMs: Int
    public var createdIso: String
    public var notes: String
    public var referenceDb: Double? = nil
    public var measuredRawDb: Double? = nil
    public var stdDevDb: Double? = nil
    public var durationSeconds: Double? = nil
    public var calibratorNominalDb: Double? = nil
    public var toneFrequencyHz: Double? = nil
    public var tonality: Double? = nil
    public var warnings: String = ""

    public var isCalibrated: Bool { method != CalibrationEntity.METHOD_RESET }
}

/**
 * Calibration offsets per (phone model, audio source) with the full history (CalibrationRepository).
 * iOS has one audio source ([AudioSource.IOS_MEASUREMENT]), so in practice one calibration per phone.
 */
public final class CalibrationRepository {
    public struct Active: Equatable {
        public let entity: CalibrationEntity?
        public var offsetDb: Double { entity?.offsetDb ?? Acoustics.DEFAULT_CALIBRATION_OFFSET_DB }
        public var calibrated: Bool { entity?.isCalibrated == true }
        public var id: Int? { entity?.id }
    }

    public let db: AppDatabase
    public let deviceModel: String
    private let clock: AppClock

    public init(_ db: AppDatabase, deviceModel: String, clock: AppClock = SystemClock()) {
        self.db = db; self.deviceModel = deviceModel; self.clock = clock
    }

    private static func entity(_ r: Row) -> CalibrationEntity {
        CalibrationEntity(
            id: r["id"], deviceModel: r["deviceModel"], audioSource: r["audioSource"], offsetDb: r["offsetDb"], method: r["method"],
            createdEpochMs: r["createdEpochMs"], createdIso: r["createdIso"], notes: r["notes"], referenceDb: r["referenceDb"],
            measuredRawDb: r["measuredRawDb"], stdDevDb: r["stdDevDb"], durationSeconds: r["durationSeconds"],
            calibratorNominalDb: r["calibratorNominalDb"], toneFrequencyHz: r["toneFrequencyHz"], tonality: r["tonality"],
            warnings: r["warnings"]
        )
    }

    public func active(_ source: String) throws -> Active {
        try db.read {
            Active(entity: try Row.fetchOne($0, sql: """
                SELECT * FROM calibrations WHERE deviceModel = ? AND audioSource = ? ORDER BY createdEpochMs DESC, id DESC LIMIT 1
                """, arguments: [deviceModel, source]).map(Self.entity))
        }
    }

    /** Newest first. */
    public func history() throws -> [CalibrationEntity] {
        try db.read { try Row.fetchAll($0, sql: "SELECT * FROM calibrations ORDER BY createdEpochMs DESC, id DESC").map(Self.entity) }
    }

    /** Id → offset of every calibration. */
    public func offsets() throws -> [Int: Double] {
        var out: [Int: Double] = [:]
        for c in try history() { out[c.id] = c.offsetDb }
        return out
    }

    @discardableResult
    public func save(
        _ source: String, _ offsetDb: Double, _ method: String, _ notes: String, referenceDb: Double? = nil, measuredRawDb: Double? = nil,
        stdDevDb: Double? = nil, durationSeconds: Double? = nil, calibratorNominalDb: Double? = nil, toneFrequencyHz: Double? = nil,
        tonality: Double? = nil, warnings: [String] = [], zone: TimeZone = .current
    ) throws -> Int {
        let now = clock.nowMs()
        return try db.write { d in
            try d.execute(sql: """
                INSERT INTO calibrations (deviceModel, audioSource, offsetDb, method, createdEpochMs, createdIso, notes, referenceDb,
                measuredRawDb, stdDevDb, durationSeconds, calibratorNominalDb, toneFrequencyHz, tonality, warnings)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, arguments: [
                    deviceModel, source, offsetDb, method, now, Iso.format(now, zone), notes, referenceDb, measuredRawDb, stdDevDb,
                    durationSeconds, calibratorNominalDb, toneFrequencyHz, tonality, warnings.joined(separator: ","),
                ])
            return Int(d.lastInsertedRowID)
        }
    }

    /** «Exportieren» of the history (the same JSON as Android's calibration-history-v1). */
    public func exportJson() throws -> String {
        var arr: [JSONValue] = []
        for c in try history().reversed() {
            var o = JSONObject()
            o.put("id", .int(c.id)); o.put("device_model", .string(c.deviceModel)); o.put("audio_source", .string(c.audioSource))
            o.put("offset_db", .double(c.offsetDb)); o.put("method", .string(c.method)); o.put("created", .string(c.createdIso))
            o.put("notes", .string(c.notes))
            if let v = c.referenceDb { o.put("reference_db", .double(v)) }
            if let v = c.measuredRawDb { o.put("measured_raw_db", .double(v)) }
            if let v = c.stdDevDb { o.put("std_dev_1s_db", .double(v)) }
            if let v = c.durationSeconds { o.put("duration_s", .double(v)) }
            if let v = c.calibratorNominalDb { o.put("calibrator_nominal_db", .double(v)) }
            if let v = c.toneFrequencyHz { o.put("tone_frequency_hz", .double(v)) }
            if let v = c.tonality { o.put("tonality", .double(v)) }
            if !c.warnings.isEmpty { o.put("warnings", .string(c.warnings)) }
            arr.append(.object(o))
        }
        var root = JSONObject()
        root.put("app", .string("Weniger Lärm"))
        root.put("format", .string("calibration-history-v1"))
        root.put("default_offset_db", .double(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB))
        root.put("calibrations", .array(arr))
        return JSONValue.object(root).description
    }
}
