import Foundation
import XCTest
@testable import LessNoiseKit

final class StorageTests: XCTestCase {
    let t0 = 1_760_000_000_000 - (1_760_000_000_000 % 60_000)

    func testMinuteAndEventRoundTripIncludingNaNAndRecalibration() throws {
        let repo = MeasurementRepository(try AppDatabase.inMemory())
        var m = minute(t0)
        m.l1Db = .nan
        m.localFloorDb = .nan
        try repo.insert(m)
        let r = Recalibration.recalibrate(minute(t0 + 60_000), Recalibration.Target(calibrationId: 7, offsetDb: 121.9, audioSource: "IOS"))
        try repo.insert(r)
        let e = event(t0 + 5_000)
        try repo.insert(e)
        var noFeatures = event(t0 + 9_000, category: nil)
        noFeatures.features = nil
        try repo.insert(noFeatures)

        XCTAssertEqual(try repo.allMinutes(), [m, r])
        XCTAssertEqual(try repo.allEvents(), [e, noFeatures])
        XCTAssertTrue(try repo.allMinutes()[1].recalibrated)
        XCTAssertEqual(try repo.minuteCount(), 2)
        XCTAssertEqual(try repo.eventCount(), 2)
        XCTAssertEqual(try repo.recentEvents(1).map { $0.event }, [noFeatures])
        XCTAssertEqual(try repo.minutesAfter(t0, 10), [r])
        XCTAssertEqual(try repo.eventsAfter(t0 + 5_000, 10), [noFeatures])
    }

    func testBeginMeasurementClosesOpenRowsAtTheirLastMinuteAndAttachPointOnlyOnTheOpenRow() throws {
        let repo = MeasurementRepository(try AppDatabase.inMemory())
        let a = try repo.beginMeasurement(t0, nil)
        try repo.insert(minute(t0))
        try repo.insert(minute(t0 + 60_000))
        // The app ended without «Stoppen»; the next «Starten» closes row a at the end of its last minute.
        let b = try repo.beginMeasurement(t0 + 3_600_000, nil)
        let rows = try repo.allMeasurements()
        XCTAssertEqual(rows.map { $0.id }, [a, b])
        XCTAssertEqual(rows[0].endMs, t0 + 120_000)
        XCTAssertNil(rows[1].endMs)

        let p = LocationNoise.Point(e: 2_683_300, n: 1_247_900, sigmaM: 50)
        XCTAssertFalse(try repo.attachPoint(t0, p), "an ended measurement stays unshared")
        XCTAssertTrue(try repo.attachPoint(t0 + 3_600_000, p))
        XCTAssertFalse(try repo.attachPoint(t0 + 3_600_000, p), "a point is attached once")
        XCTAssertEqual(try repo.latestSharedPoint(), p)
        XCTAssertEqual(try repo.allMeasurements()[1].cell, p.cell)

        try repo.insert(minute(t0 + 3_600_000))
        try repo.insert(minute(t0 + 3_660_000))
        // Only the minutes of the shared measurement will be sent.
        XCTAssertEqual(try repo.sharedMinuteCountAfter(0), 2)
        XCTAssertEqual(try repo.sharedMinuteCountAfter(t0 + 3_600_000), 1)

        try repo.endMeasurement(b, t0 + 3_720_000)
        let list = MeasurementList.resolve(try repo.allMeasurements(), try repo.minuteSpans(), nowMs: t0 + 9_999_999, running: false)
        XCTAssertEqual(list.map { $0.span }, [MeasurementSpan(t0, t0 + 120_000), MeasurementSpan(t0 + 3_600_000, t0 + 3_720_000)])
        XCTAssertEqual(list.map { $0.point }, [nil, p])

        try repo.clear()
        XCTAssertEqual(try repo.minuteCount(), 0)
        XCTAssertEqual(try repo.allMeasurements(), [])
    }

    func testResolveKeepsTheRunningMeasurementWithoutMinutes() {
        let rows = [MeasurementEntity(id: 1, startMs: 0, endMs: 100_000, point: nil), MeasurementEntity(id: 2, startMs: 200_000, endMs: nil, point: nil)]
        let r = MeasurementList.resolve(rows, [(0, 60_000)], nowMs: 250_000, running: true)
        XCTAssertEqual(r.map { $0.running }, [false, true])
        XCTAssertEqual(r[1].span, MeasurementSpan(200_000, 250_000))
        XCTAssertEqual(MeasurementList.resolve(rows, [], nowMs: 250_000, running: false), [])
    }

    func testRecalibrationAThenBackRestoresTheOriginalsAndCountsScope() throws {
        let db = try AppDatabase.inMemory()
        let repo = MeasurementRepository(db)
        let cals = CalibrationRepository(db, deviceModel: "iPhone16,1", clock: FakeClock())
        let original = minute(t0)
        try repo.insert(original)
        try repo.insert(event(t0 + 1_000))
        let id1 = try cals.save("IOS", 121.0, CalibrationEntity.METHOD_MANUAL, "")
        let target = Recalibration.Target(calibrationId: id1, offsetDb: 121.0, audioSource: "IOS")
        XCTAssertEqual(try repo.countsToRecalibrate(target).minutes, 1)
        XCTAssertEqual(try repo.countsToRecalibrate(target).events, 1)

        let rec = Recalibrator(repo, cals)
        rec.runNow(target)
        XCTAssertEqual(rec.state, .done(minutes: 1, events: 1, target: target))
        let shifted = try repo.allMinutes()[0]
        XCTAssertEqual(shifted.laeqDb, original.laeqDb + (121.0 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB), accuracy: 1e-9)
        XCTAssertTrue(shifted.calibrated)
        XCTAssertEqual(try repo.countsToRecalibrate(target).minutes, 0, "nothing left in scope")
        XCTAssertEqual(try repo.allEvents()[0].lafMaxDb, 80 + (121.0 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB), accuracy: 1e-9)

        // A second calibration B, then the CSV still shows the originals.
        let id2 = try cals.save("IOS", 118.0, CalibrationEntity.METHOD_MANUAL, "")
        rec.runNow(Recalibration.Target(calibrationId: id2, offsetDb: 118.0, audioSource: "IOS"))
        let b = try repo.allMinutes()[0]
        XCTAssertEqual(b.laeqDb, original.laeqDb + (118.0 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB), accuracy: 1e-9)
        XCTAssertEqual(b.original, original.levels)
        XCTAssertEqual(b.recalibratedFromId, Recalibration.FROM_DEFAULT)
        XCTAssertTrue(Csv.minutes(try repo.allMinutes(), ["road_traffic", "unclassified"]).contains(",default,"))
    }

    func testCalibrationRepositoryActiveHistoryAndExport() throws {
        let db = try AppDatabase.inMemory()
        let clock = FakeClock()
        let cals = CalibrationRepository(db, deviceModel: "iPhone16,1", clock: clock)
        XCTAssertNil(try cals.active("IOS").entity)
        XCTAssertEqual(try cals.active("IOS").offsetDb, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB)
        XCTAssertFalse(try cals.active("IOS").calibrated)
        let id = try cals.save("IOS", 120.5, CalibrationEntity.METHOD_REFERENCE, "Notiz", referenceDb: 62, measuredRawDb: -58.5,
                               warnings: ["UNSTEADY"])
        clock.advance(1000)
        let reset = try cals.save("IOS", Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, CalibrationEntity.METHOD_RESET, "")
        XCTAssertEqual(try cals.active("IOS").id, reset)
        XCTAssertFalse(try cals.active("IOS").calibrated)
        XCTAssertEqual(try cals.history().map { $0.id }, [reset, id])
        XCTAssertEqual(try cals.offsets()[id], 120.5)
        let json = try cals.exportJson()
        XCTAssertTrue(json.hasPrefix(#"{"app":"Weniger Lärm","format":"calibration-history-v1","default_offset_db":112.3"#), json)
        XCTAssertTrue(json.contains(#""device_model":"iPhone16,1","audio_source":"IOS","offset_db":120.5,"method":"reference_meter""#), json)
        XCTAssertTrue(json.contains(#""warnings":"UNSTEADY""#))
    }

    func testCsvExportWritesTheTwoFilesWithAndroidsNames() throws {
        let repo = MeasurementRepository(try AppDatabase.inMemory())
        try repo.insert(minute(t0))
        try repo.insert(event(t0 + 1_000))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("csv-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let zurich = TimeZone(identifier: "Europe/Zurich")!
        // 2026-10-15 21:30 in Zürich.
        let files = try CsvExport.write(repo, bucketIds: ["road_traffic", "unclassified"], dir: dir, nowMs: 1_792_092_600_000, zone: zurich)
        XCTAssertEqual(files.map { $0.lastPathComponent }, ["wenigerlaerm-minuten-20261015-2130.csv", "wenigerlaerm-ereignisse-20261015-2130.csv"])
        let minutes = try String(contentsOf: files[0], encoding: .utf8)
        XCTAssertEqual(minutes, Csv.minutes(try repo.allMinutes(), ["road_traffic", "unclassified"]))
        XCTAssertTrue(minutes.hasPrefix("start,duration_s,valid_s,coverage,laeq_db"))
        XCTAssertTrue(minutes.contains(",IOS,false,"), minutes)
        let events = try String(contentsOf: files[1], encoding: .utf8)
        XCTAssertEqual(events, Csv.events(try repo.allEvents()))
        // A second export replaces the first.
        _ = try CsvExport.write(repo, bucketIds: [], dir: dir, nowMs: 1_792_092_660_000, zone: zurich)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path).count, 2)
    }

    func testOpenOnDiskSurvivesReopening() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("db-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        do {
            let repo = MeasurementRepository(try AppDatabase.open(directory: dir))
            try repo.insert(minute(t0))
        }
        let again = MeasurementRepository(try AppDatabase.open(directory: dir))
        XCTAssertEqual(try again.allMinutes(), [minute(t0)])
    }

    func testHistoryItemsNewestFirstWithSummaries() throws {
        let repo = MeasurementRepository(try AppDatabase.inMemory())
        try repo.beginMeasurement(t0, nil)
        try repo.insert(minute(t0, laeq: 50))
        try repo.insert(event(t0 + 1_000, lafMax: 80))
        try repo.endMeasurement(1, t0 + 60_000)
        try repo.beginMeasurement(t0 + 7_200_000, nil)
        try repo.insert(minute(t0 + 7_200_000, laeq: 60))
        let items = try HistoryQueries(repo).items(nowMs: t0 + 7_300_000, running: true, eventMinLevelDb: 30)
        XCTAssertEqual(items.map { $0.measurement.running }, [true, false])
        XCTAssertEqual(items[0].summary.laeqDb, 60, accuracy: 1e-9)
        XCTAssertEqual(items[1].summary.eventCount, 1)
        let data = try HistoryQueries(repo).data(items[1].measurement)
        XCTAssertEqual(data.minutes.count, 1)
        XCTAssertEqual(data.events.count, 1)
    }
}
