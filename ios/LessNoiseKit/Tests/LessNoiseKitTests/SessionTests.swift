import Foundation
import XCTest
@testable import LessNoiseKit

final class SessionTests: XCTestCase {
    func testMeasuresStoresMinutesAndClosesTheRow() throws {
        let rig = try Rig()
        let s = rig.services.session
        // Start on a minute boundary + 5 s.
        rig.clock.advance(60_000 - rig.clock.nowMs() % 60_000 + 5_000)
        XCTAssertTrue(s.start())
        XCTAssertFalse(s.start(), "one measurement at a time")
        XCTAssertTrue(s.live.running)
        XCTAssertEqual(s.live.measurementStartMs, rig.clock.nowMs())
        XCTAssertFalse(s.live.calibrated)
        XCTAssertEqual(s.live.calibrationOffsetDb, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB)
        var noise = Noise()
        feed(rig, seconds: 130, noise: &noise)
        s.drain()
        XCTAssertNotNil(s.live.lafDb)
        XCTAssertNotNil(s.live.backgroundDb)
        s.stop()
        XCTAssertFalse(s.live.running)
        XCTAssertEqual(rig.input.stops, 1)
        let minutes = try rig.services.measurements.allMinutes()
        // 55 s + 60 s + the partial 15 s.
        XCTAssertEqual(minutes.count, 3)
        XCTAssertTrue(minutes.allSatisfy { $0.audioSource == "IOS" && !$0.calibrated && $0.calibrationId == nil })
        XCTAssertEqual(minutes[1].durationSeconds, 60, accuracy: 0.2)
        XCTAssertGreaterThan(minutes[1].coverage, 0.95)
        XCTAssertNil(minutes[1].dominantCategory, "no classifier: categories nil, as Android's replay")
        let rows = try rig.services.measurements.allMeasurements()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].endMs, rig.clock.nowMs())
    }

    func testInterruptionIsRecordedAsInvalidTimeAndTheTimelineStaysOnTheWallClock() throws {
        let rig = try Rig()
        let s = rig.services.session
        rig.clock.advance(60_000 - rig.clock.nowMs() % 60_000)
        let start = rig.clock.nowMs()
        XCTAssertTrue(s.start())
        var noise = Noise()
        feed(rig, seconds: 70, noise: &noise)
        // A phone call: iOS stops the input for 50 s.
        rig.input.handler?.audioInterrupted(true)
        XCTAssertTrue(s.live.micSilenced)
        rig.clock.advance(50_000)
        rig.input.handler?.audioInterrupted(false)
        feed(rig, seconds: 60, noise: &noise)
        s.drain()
        s.stop()
        let minutes = try rig.services.measurements.allMinutes()
        // 3 minutes: [0,60) full, [60,120) with the call, [120,180) with the end of the call.
        XCTAssertEqual(minutes.map { $0.startEpochMs }, [start + 500, start + 60_000, start + 120_000], "the first minute starts after the 0.5 s warm-up")
        XCTAssertGreaterThan(minutes[0].coverage, 0.98)
        // 10 s of audio before the call, 50 s gap.
        XCTAssertEqual(minutes[1].validSeconds, 10, accuracy: 1.0)
        XCTAssertEqual(minutes[2].validSeconds, 60, accuracy: 1.0)
        let total = minutes.reduce(0.0) { $0 + $1.validSeconds }
        XCTAssertEqual(total, 130, accuracy: 2.0, "valid time = audio fed, the call is excluded")
        XCTAssertEqual(minutes.map { $0.clockCorrections }.reduce(0, +), 0, "the gap fill kept the sample clock on the wall clock")
    }

    func testLoudBurstBecomesAnEventAndTheLiveState() throws {
        let rig = try Rig()
        let s = rig.services.session
        XCTAssertTrue(s.start())
        var noise = Noise()
        // 40 s quiet, a 3 s burst 30 dB louder, 20 s quiet.
        feed(rig, seconds: 63, noise: &noise) { b in (400..<430).contains(b) ? 0.3 : 0.01 }
        s.drain()
        guard let e = s.live.lastEvent else { return XCTFail("no event") }
        XCTAssertFalse(e.wind)
        XCTAssertGreaterThan(e.lafMaxDb, 90)
        s.stop()
        let events = try rig.services.measurements.allEvents()
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].audioSource, "IOS")
        XCTAssertEqual(try rig.services.measurements.recentEvents(since: s.live.measurementStartMs).count, 1)
    }

    func testStartFailsCleanlyWhenTheMicrophoneCannotBeOpened() throws {
        struct Refused: LocalizedError { var errorDescription: String? { "Mikrofon nicht verfügbar." } }
        let rig = try Rig()
        rig.input.startError = Refused()
        XCTAssertFalse(rig.services.session.start())
        XCTAssertFalse(rig.services.session.live.running)
        XCTAssertEqual(rig.services.session.live.error, "Mikrofon nicht verfügbar.")
        // The row is closed again (no endless «läuft» measurement).
        XCTAssertNotNil(try rig.services.measurements.allMeasurements().first?.endMs)
        rig.input.startError = nil
        XCTAssertTrue(rig.services.session.start())
    }

    func testCalibratedPhoneMeasuresWithItsOffset() throws {
        let rig = try Rig()
        let id = try rig.services.calibrations.save("IOS", 120.0, CalibrationEntity.METHOD_MANUAL, "")
        XCTAssertTrue(rig.services.session.start())
        XCTAssertTrue(rig.services.session.live.calibrated)
        XCTAssertEqual(rig.services.session.live.calibrationId, id)
        var noise = Noise()
        feed(rig, seconds: 61, noise: &noise)
        rig.services.session.stop()
        let m = try rig.services.measurements.allMinutes()
        XCTAssertTrue(m.allSatisfy { $0.calibrated && $0.calibrationId == id && $0.calibrationOffsetDb == 120.0 })
    }

    /** A classifier that always says «Motorcycle»: the pipeline delivers frames and categories. */
    final class FixedClassifier: SoundClassifier {
        let labels: [String]
        init(_ labels: [String]) { self.labels = labels }
        var inputSampleRate: Int { 16_000 }
        var inputLength: Int { ClassifierPreprocessor.YAMNET_INPUT_SAMPLES }
        var calls = Locked(0)
        func classify(_ window: [Float]) -> [Float] {
            precondition(window.count == inputLength)
            calls.update { $0 += 1 }
            return labels.map { $0 == "Motorcycle" ? 0.9 : 0.01 }
        }
    }

    func testClassifierPipelineFillsKlingtNach() throws {
        let labels = ["Speech", "Motorcycle", "Car"]
        let json = #"{"categories":[{"id":"loud_vehicle","name_de":"Töff & Poser","audioset":["Motorcycle"]},{"id":"road_traffic","name_de":"Strassenverkehr","audioset":["Car"]}]}"#
        let mapper = try CategoryMapper.fromJson(json, labels: labels)
        let cls = FixedClassifier(labels)
        let rig = try Rig(classifier: cls, mapper: mapper)
        XCTAssertTrue(rig.services.session.start())
        XCTAssertTrue(rig.services.session.live.classifierEnabled)
        var noise = Noise()
        for _ in 0..<5 {
            feed(rig, seconds: 2, noise: &noise)
            rig.services.session.drain()
        }
        XCTAssertGreaterThan(cls.calls.get, 0)
        XCTAssertTrue(eventually { rig.services.session.live.dominantCategory == "loud_vehicle" })
        XCTAssertEqual(Categories.name("loud_vehicle", mapper), "Töff & Poser")
        rig.services.session.stop()
    }
}
