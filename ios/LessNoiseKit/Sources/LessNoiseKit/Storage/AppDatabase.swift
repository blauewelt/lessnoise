import Foundation
import GRDB

/**
 * The app's SQLite database (GRDB, MIT): the same four tables as Android's Room database v7
 * (android/app/…/data/Database.kt), with the same column names, so that a CSV or a database dump
 * reads the same on both platforms. Only aggregates are stored; there is deliberately no table or
 * column that could hold audio. NaN levels are stored as NULL (as on Android).
 *
 * iOS: the file lives in Application Support/LessNoise/, with
 * `FileProtectionType.completeUntilFirstUserAuthentication` on the directory (the app writes while
 * the phone is locked) and `isExcludedFromBackup` (Android: `allowBackup=false`); see [open].
 */
public final class AppDatabase {
    public let queue: DatabaseQueue
    /** Called (on the writing thread) after every committed change of minutes, events, measurements or calibrations. */
    public let changes = Observers()

    public init(_ queue: DatabaseQueue) throws {
        self.queue = queue
        try AppDatabase.migrator.migrate(queue)
    }

    /** An in-memory database (tests, previews). */
    public static func inMemory() throws -> AppDatabase { try AppDatabase(DatabaseQueue()) }

    /** The database file in [directory] (created if needed), protected and excluded from backup on iOS. */
    public static func open(directory: URL) throws -> AppDatabase {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        #if os(iOS)
        // New files in the directory (the database and its journal) inherit this class.
        try? fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        var dir = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        #endif
        let url = directory.appendingPathComponent("lessnoise.sqlite")
        let db = try AppDatabase(DatabaseQueue(path: url.path))
        #if os(iOS)
        try? fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
        return db
    }

    /** Runs [body] in a write transaction and tells the observers afterwards. */
    @discardableResult
    public func write<T>(_ body: (Database) throws -> T) throws -> T {
        let r = try queue.write(body)
        changes.notify()
        return r
    }

    public func read<T>(_ body: (Database) throws -> T) throws -> T { try queue.read(body) }

    static var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()
        // v1 of the iOS schema = Room v7 of Android 0.6.3.
        m.registerMigration("v1") { db in
            try db.execute(sql: """
                CREATE TABLE minutes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    startEpochMs INTEGER NOT NULL,
                    startIso TEXT NOT NULL,
                    durationSeconds REAL NOT NULL,
                    laeqDb REAL, lafMaxDb REAL, lafMinDb REAL, l1Db REAL, l10Db REAL, l50Db REAL, l90Db REAL,
                    eventCount INTEGER NOT NULL,
                    dominantCategory TEXT,
                    categorySharesJson TEXT NOT NULL,
                    classifierFrames INTEGER NOT NULL,
                    calibrationId INTEGER,
                    calibrationOffsetDb REAL NOT NULL,
                    audioSource TEXT NOT NULL,
                    calibrated INTEGER NOT NULL,
                    validSeconds REAL NOT NULL,
                    coverage REAL NOT NULL,
                    clockCorrections INTEGER NOT NULL,
                    orig_laeq_db REAL, orig_lafmax_db REAL, orig_lafmin_db REAL, orig_l1_db REAL,
                    orig_l10_db REAL, orig_l50_db REAL, orig_l90_db REAL,
                    recalibrated_from_id TEXT,
                    recalibration_offset_db REAL,
                    local_floor_db REAL,
                    wind_event_count INTEGER NOT NULL DEFAULT 0
                )
                """)
            try db.execute(sql: "CREATE INDEX index_minutes_startEpochMs ON minutes(startEpochMs)")
            try db.execute(sql: """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    startEpochMs INTEGER NOT NULL,
                    startIso TEXT NOT NULL,
                    durationSeconds REAL NOT NULL,
                    lafMaxDb REAL,
                    selDb REAL,
                    backgroundDb REAL,
                    thresholdDb REAL,
                    dominantCategory TEXT,
                    dominantScore REAL NOT NULL,
                    topLabelsJson TEXT NOT NULL,
                    classifierFrames INTEGER NOT NULL,
                    calibrationId INTEGER,
                    audioSource TEXT NOT NULL,
                    calibrated INTEGER NOT NULL,
                    min_level_db REAL,
                    orig_lafmax_db REAL, orig_sel_db REAL, orig_background_db REAL,
                    recalibrated_from_id TEXT,
                    recalibration_offset_db REAL,
                    local_floor_db REAL, excess_db REAL, rise_s REAL, decay_s REAL, jaggedness REAL,
                    mid_band_rise_db REAL, lf_share REAL, lf_flutter_db REAL,
                    wind INTEGER NOT NULL DEFAULT 0,
                    shape TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX index_events_startEpochMs ON events(startEpochMs)")
            try db.execute(sql: """
                CREATE TABLE measurements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    start_ms INTEGER NOT NULL,
                    end_ms INTEGER,
                    cell TEXT,
                    point_e INTEGER,
                    point_n INTEGER,
                    sigma_m INTEGER
                )
                """)
            try db.execute(sql: "CREATE INDEX index_measurements_start_ms ON measurements(start_ms)")
            try db.execute(sql: """
                CREATE TABLE calibrations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    deviceModel TEXT NOT NULL,
                    audioSource TEXT NOT NULL,
                    offsetDb REAL NOT NULL,
                    method TEXT NOT NULL,
                    createdEpochMs INTEGER NOT NULL,
                    createdIso TEXT NOT NULL,
                    notes TEXT NOT NULL,
                    referenceDb REAL, measuredRawDb REAL, stdDevDb REAL, durationSeconds REAL,
                    calibratorNominalDb REAL, toneFrequencyHz REAL, tonality REAL,
                    warnings TEXT NOT NULL DEFAULT ''
                )
                """)
            try db.execute(sql: "CREATE INDEX index_calibrations_deviceModel_audioSource ON calibrations(deviceModel, audioSource)")
        }
        return m
    }
}

// ---- Mapping between the core's records and rows ------------------------------------------------

/** NaN/∞ → NULL, as Room's entities do. */
@inline(__always) func dbValue(_ v: Double) -> Double? { (v.isNaN || v.isInfinite) ? nil : v }
@inline(__always) func dbValue(_ v: Double?) -> Double? { v.flatMap { dbValue($0) } }

enum RowMapping {
    static let minuteColumns = [
        "startEpochMs", "startIso", "durationSeconds", "laeqDb", "lafMaxDb", "lafMinDb", "l1Db", "l10Db", "l50Db", "l90Db",
        "eventCount", "dominantCategory", "categorySharesJson", "classifierFrames", "calibrationId", "calibrationOffsetDb",
        "audioSource", "calibrated", "validSeconds", "coverage", "clockCorrections",
        "orig_laeq_db", "orig_lafmax_db", "orig_lafmin_db", "orig_l1_db", "orig_l10_db", "orig_l50_db", "orig_l90_db",
        "recalibrated_from_id", "recalibration_offset_db", "local_floor_db", "wind_event_count",
    ]

    static func sharesJson(_ shares: OrderedDict<Double>) -> String {
        JSONValue.object(JSONObject(shares.entries.map { ($0.key, JSONValue.double($0.value)) })).description
    }

    static func parseShares(_ text: String) -> OrderedDict<Double> {
        guard let o = (try? JSONValue.parse(text))?.objectValue else { return OrderedDict() }
        var d = OrderedDict<Double>()
        for (k, v) in o.entries { if let x = v.doubleValue { d[k] = x } }
        return d
    }

    static func minuteArguments(_ m: MinuteRecord) -> StatementArguments {
        let o = m.original
        let values: [(any DatabaseValueConvertible)?] = [
            m.startEpochMs, m.startIso, m.durationSeconds, dbValue(m.laeqDb), dbValue(m.lafMaxDb), dbValue(m.lafMinDb),
            dbValue(m.l1Db), dbValue(m.l10Db), dbValue(m.l50Db), dbValue(m.l90Db),
            m.eventCount, m.dominantCategory, sharesJson(m.categoryShares), m.classifierFrames, m.calibrationId,
            m.calibrationOffsetDb, m.audioSource, m.calibrated, m.validSeconds, m.coverage, m.clockCorrections,
            dbValue(o?.laeqDb), dbValue(o?.lafMaxDb), dbValue(o?.lafMinDb), dbValue(o?.l1Db), dbValue(o?.l10Db),
            dbValue(o?.l50Db), dbValue(o?.l90Db),
            m.recalibratedFromId, m.recalibrationOffsetDb, dbValue(m.localFloorDb), m.windEventCount,
        ]
        return StatementArguments(values)
    }

    static func minute(_ r: Row) -> MinuteRecord {
        func d(_ c: String) -> Double { (r[c] as Double?) ?? .nan }
        let fromId: String? = r["recalibrated_from_id"]
        return MinuteRecord(
            startEpochMs: r["startEpochMs"], startIso: r["startIso"], durationSeconds: r["durationSeconds"],
            laeqDb: d("laeqDb"), lafMaxDb: d("lafMaxDb"), lafMinDb: d("lafMinDb"), l1Db: d("l1Db"), l10Db: d("l10Db"),
            l50Db: d("l50Db"), l90Db: d("l90Db"), eventCount: r["eventCount"], dominantCategory: r["dominantCategory"],
            categoryShares: parseShares(r["categorySharesJson"]), classifierFrames: r["classifierFrames"],
            calibrationId: r["calibrationId"], calibrationOffsetDb: r["calibrationOffsetDb"], audioSource: r["audioSource"],
            calibrated: r["calibrated"], validSeconds: r["validSeconds"], clockCorrections: r["clockCorrections"],
            original: fromId.map { _ in
                MinuteLevels(d("orig_laeq_db"), d("orig_lafmax_db"), d("orig_lafmin_db"), d("orig_l1_db"), d("orig_l10_db"),
                             d("orig_l50_db"), d("orig_l90_db"))
            },
            recalibratedFromId: fromId, recalibrationOffsetDb: r["recalibration_offset_db"],
            localFloorDb: d("local_floor_db"), windEventCount: r["wind_event_count"]
        )
    }

    static let eventColumns = [
        "startEpochMs", "startIso", "durationSeconds", "lafMaxDb", "selDb", "backgroundDb", "thresholdDb",
        "dominantCategory", "dominantScore", "topLabelsJson", "classifierFrames", "calibrationId", "audioSource", "calibrated",
        "min_level_db", "orig_lafmax_db", "orig_sel_db", "orig_background_db", "recalibrated_from_id", "recalibration_offset_db",
        "local_floor_db", "excess_db", "rise_s", "decay_s", "jaggedness", "mid_band_rise_db", "lf_share", "lf_flutter_db",
        "wind", "shape",
    ]

    static func labelsJson(_ labels: [LabelScore]) -> String {
        JSONValue.array(labels.map {
            .object(JSONObject([("label", .string($0.label)), ("score", .double(Double($0.score)))]))
        }).description
    }

    static func parseLabels(_ text: String) -> [LabelScore] {
        guard let a = (try? JSONValue.parse(text))?.arrayValue else { return [] }
        return a.compactMap { el in
            guard let l = el.member("label")?.stringValue, let s = el.member("score")?.doubleValue else { return nil }
            return LabelScore(label: l, score: Float(s))
        }
    }

    static func eventArguments(_ e: NoiseEvent) -> StatementArguments {
        let o = e.original
        let f = e.features
        // Deviation from Room: the four level columns are nullable here (NaN → NULL, e.g. the 5-min
        // background during the first 30 s of a measurement); Room declares them NOT NULL.
        let values: [(any DatabaseValueConvertible)?] = [
            e.startEpochMs, e.startIso, e.durationSeconds, dbValue(e.lafMaxDb), dbValue(e.selDb), dbValue(e.backgroundDb),
            dbValue(e.thresholdDb),
            e.dominantCategory, Double(e.dominantScore), labelsJson(e.topLabels), e.classifierFrames, e.calibrationId,
            e.audioSource, e.calibrated, dbValue(e.minLevelDb),
            dbValue(o?.lafMaxDb), dbValue(o?.selDb), dbValue(o?.backgroundDb), e.recalibratedFromId, e.recalibrationOffsetDb,
            dbValue(f?.localFloorDb), dbValue(f?.excessDb), dbValue(f?.riseS), dbValue(f?.decayS), dbValue(f?.jaggedness),
            dbValue(f?.midBandRiseDb), dbValue(f?.lfShare), dbValue(f?.lfFlutterDb), f?.wind == true, f?.shape,
        ]
        return StatementArguments(values)
    }

    static func event(_ r: Row) -> NoiseEvent {
        func d(_ c: String) -> Double { (r[c] as Double?) ?? .nan }
        let fromId: String? = r["recalibrated_from_id"]
        let shape: String? = r["shape"]
        return NoiseEvent(
            startEpochMs: r["startEpochMs"], startIso: r["startIso"], durationSeconds: r["durationSeconds"],
            lafMaxDb: d("lafMaxDb"), selDb: d("selDb"), backgroundDb: d("backgroundDb"), thresholdDb: d("thresholdDb"),
            dominantCategory: r["dominantCategory"], dominantScore: Float((r["dominantScore"] as Double?) ?? 0),
            topLabels: parseLabels(r["topLabelsJson"]), classifierFrames: r["classifierFrames"], calibrationId: r["calibrationId"],
            audioSource: r["audioSource"], calibrated: r["calibrated"], minLevelDb: d("min_level_db"),
            original: fromId.map { _ in EventLevels(d("orig_lafmax_db"), d("orig_sel_db"), d("orig_background_db")) },
            recalibratedFromId: fromId, recalibrationOffsetDb: r["recalibration_offset_db"],
            features: shape.map {
                EventFeatures(
                    localFloorDb: d("local_floor_db"), excessDb: d("excess_db"), riseS: d("rise_s"), decayS: d("decay_s"),
                    jaggedness: d("jaggedness"), midBandRiseDb: d("mid_band_rise_db"), lfShare: d("lf_share"),
                    lfFlutterDb: d("lf_flutter_db"), wind: r["wind"], shape: $0
                )
            }
        )
    }

    static func insertSql(_ table: String, _ columns: [String]) -> String {
        "INSERT INTO \(table) (\(columns.joined(separator: ", "))) VALUES (\(Array(repeating: "?", count: columns.count).joined(separator: ", ")))"
    }

    static func updateSql(_ table: String, _ columns: [String]) -> String {
        "UPDATE \(table) SET \(columns.map { "\($0) = ?" }.joined(separator: ", ")) WHERE id = ?"
    }
}
