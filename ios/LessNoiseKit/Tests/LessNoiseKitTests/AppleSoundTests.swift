import Foundation
import XCTest
@testable import LessNoiseKit

/** The iOS category mapping (Apple SoundAnalysis labels → the app's categories) with fake label streams. */
final class AppleSoundTests: XCTestCase {
    static let fakeLabels = ["speech", "motorcycle", "car_horn", "engine", "race_car", "train", "airplane", "jackhammer", "music", "silence", "wind"]
    static let json = """
        {"top1_threshold": 0.1, "category_threshold": 0.2, "tie_margin": 0.0, "fallback": {"id": "unclassified", "name_de": "Sonstiges"},
         "categories": [
          {"id": "loud_vehicle", "name_de": "Töff & Poser", "apple": ["motorcycle", "race_car"]},
          {"id": "road_traffic", "name_de": "Strassenverkehr", "apple": ["engine", "car_horn", "motorcycle"]},
          {"id": "rail_tram", "name_de": "Tram & Bahn", "apple": ["train"]},
          {"id": "aircraft", "name_de": "Flugzeug", "apple": ["airplane"]},
          {"id": "construction", "name_de": "Baustelle", "apple": ["jackhammer"]},
          {"id": "voices", "name_de": "Stimmen", "apple": ["speech"]},
          {"id": "music", "name_de": "Musik", "apple": ["music"]}]}
        """

    private func mapper() throws -> CategoryMapper { try AppleSoundCategories.mapper(json: Self.json, labels: Self.fakeLabels) }

    private func decide(_ result: [(String, Double)]) throws -> CategoryDecision {
        let m = try mapper()
        return m.decide(LabelScores(m.labels).scores(result.map { (identifier: $0.0, confidence: $0.1) }))
    }

    func testDecisionRulesAsTheKotlinCategoryMapper() throws {
        // (a) The top-1 label decides if it is mapped and ≥ 0.1.
        XCTAssertEqual(try decide([("motorcycle", 0.6), ("engine", 0.5)]).dominant, "loud_vehicle")
        XCTAssertEqual(try decide([("engine", 0.6), ("motorcycle", 0.5)]).dominant, "road_traffic")
        // A label in two categories belongs to the earlier one (loud_vehicle before road_traffic).
        XCTAssertEqual(try decide([("motorcycle", 0.3)]).dominant, "loud_vehicle")
        // Tie between two top-1 labels of different categories: the earlier category wins.
        XCTAssertEqual(try decide([("engine", 0.4), ("race_car", 0.4)]).dominant, "loud_vehicle")
        // (b) Unmapped top-1: the best category if ≥ 0.2.
        XCTAssertEqual(try decide([("silence", 0.9), ("train", 0.25)]).dominant, "rail_tram")
        // Category tie under (b): the earlier category wins.
        XCTAssertEqual(try decide([("silence", 0.9), ("engine", 0.3), ("race_car", 0.3)]).dominant, "loud_vehicle")
        // (c) Nothing reaches the thresholds.
        XCTAssertEqual(try decide([("silence", 0.9), ("train", 0.15)]).dominant, CategoryMapper.UNCLASSIFIED)
        XCTAssertEqual(try decide([("speech", 0.05)]).dominant, CategoryMapper.UNCLASSIFIED)
        XCTAssertEqual(try decide([]).dominant, CategoryMapper.UNCLASSIFIED)
        // The detector, not the classifier, decides wind: «wind» is not a category here.
        XCTAssertEqual(try decide([("wind", 0.9)]).dominant, CategoryMapper.UNCLASSIFIED)
    }

    func testScoresAlignWithTheLabelsAndClamp() {
        let s = LabelScores(["a", "b", "c"]).scores([(identifier: "c", confidence: 1.4), (identifier: "x", confidence: 0.9), (identifier: "a", confidence: 0.25)])
        XCTAssertEqual(s, [0.25, 0, 1])
    }

    func testUnknownIdentifierIsRefused() {
        let bad = Self.json.replacingOccurrences(of: "\"train\"]", with: "\"tram_bell\"]")
        XCTAssertThrowsError(try AppleSoundCategories.mapper(json: bad, labels: Self.fakeLabels)) { e in
            XCTAssertEqual(e as? CategoryMapperError, .unknownLabels(["rail_tram: 'tram_bell'"]))
        }
    }

    func testNamesOnlyKeepsIdsNamesAndCsvColumns() throws {
        let m = try AppleSoundCategories.namesOnly(json: Self.json)
        XCTAssertEqual(m.bucketIds, ["loud_vehicle", "road_traffic", "rail_tram", "aircraft", "construction", "voices", "music", "unclassified"])
        XCTAssertEqual(m.nameDe("rail_tram"), "Tram & Bahn")
    }

    func testWindowChoice() {
        XCTAssertEqual(AnalysisWindow.choose(enumerated: nil, range: 0.5...15), 0.975, accuracy: 1e-12)
        XCTAssertEqual(AnalysisWindow.choose(enumerated: nil, range: 1.5...15), 1.5)
        XCTAssertEqual(AnalysisWindow.choose(enumerated: [0.5, 1.0, 3.0], range: nil), 1.0)
        XCTAssertEqual(AnalysisWindow.choose(enumerated: nil, range: nil), 0.975, accuracy: 1e-12)
    }

    /** The committed mapping file: same ids, order and thresholds as Android's categories.json. */
    func testTheAppsFileMatchesAndroidsCategories() throws {
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let ios = try String(contentsOf: here.appendingPathComponent("../../../App/categories-ios.json").standardized, encoding: .utf8)
        let android = try String(contentsOf: here.appendingPathComponent("../../../../android/app/src/main/assets/categories.json").standardized, encoding: .utf8)
        let a = try JSONValue.parse(android)
        let i = try JSONValue.parse(ios)
        for k in ["top1_threshold", "category_threshold", "tie_margin"] {
            XCTAssertEqual(i.member(k)?.doubleValue, a.member(k)?.doubleValue, k)
        }
        let ids = { (v: JSONValue) in v.member("categories")?.arrayValue?.compactMap { $0.member("id")?.stringValue } ?? [] }
        let names = { (v: JSONValue) in v.member("categories")?.arrayValue?.compactMap { $0.member("name_de")?.stringValue } ?? [] }
        XCTAssertEqual(ids(i), ids(a))
        XCTAssertEqual(names(i), names(a))
        XCTAssertEqual(ids(i).first, "loud_vehicle", "tie priority")
        // Every identifier is one of Apple's (the list CI dumped from knownClassifications, committed).
        let listURL = here.appendingPathComponent("../Fixtures/apple-sound-labels.txt").standardized
        let known = CategoryMapper.parseLabels(try String(contentsOf: listURL, encoding: .utf8))
        XCTAssertEqual(known.count, 303)
        let m = try AppleSoundCategories.mapper(json: ios, labels: known)
        let counts = try AppleSoundCategories.memberIdentifiers(json: ios).mapValues { $0.count }
        XCTAssertEqual(counts, ["loud_vehicle": 4, "road_traffic": 10, "rail_tram": 7, "aircraft": 3, "construction": 5, "voices": 9, "music": 2])
        // A motorbike pass on the real label set: loud_vehicle; wind only by the detector.
        let sc = LabelScores(m.labels)
        XCTAssertEqual(m.decide(sc.scores([(identifier: "motorcycle", confidence: 0.7), (identifier: "traffic_noise", confidence: 0.5)])).dominant, "loud_vehicle")
        XCTAssertEqual(m.decide(sc.scores([(identifier: "wind_noise_microphone", confidence: 0.9)])).dominant, CategoryMapper.UNCLASSIFIED)
    }

    /** End to end: Apple-like results through the session's classifier pipeline → a «Töff & Poser» event and the «Sehr laut» alert. */
    final class ScriptedClassifier: SoundClassifier {
        let labels: [String]
        var result: [(identifier: String, confidence: Double)] = [(identifier: "motorcycle", confidence: 0.8)]
        init(_ labels: [String]) { self.labels = labels }
        var inputSampleRate: Int { 16_000 }
        var inputLength: Int { ClassifierPreprocessor.YAMNET_INPUT_SAMPLES }
        func classify(_ window: [Float]) -> [Float] { LabelScores(labels).scores(result) }
    }

    func testLoudMotorcycleBecomesAnAlertAgain() throws {
        let m = try mapper()
        let rig = try Rig(classifier: ScriptedClassifier(m.labels), mapper: m)
        rig.services.settings.update { var n = $0; n.unterwegs = true; return n }
        XCTAssertTrue(rig.services.session.start())
        var noise = Noise()
        // Quiet, then a 3 s pass 30 dB louder (≈ 95 dB with the default offset: above 75 dB).
        feed(rig, seconds: 63, noise: &noise) { b in (400..<430).contains(b) ? 0.3 : 0.01 }
        rig.services.session.stop()
        let events = try rig.services.measurements.allEvents()
        XCTAssertEqual(events.map { $0.dominantCategory }, ["loud_vehicle"])
        XCTAssertGreaterThan(events[0].classifierFrames, 0)
        XCTAssertEqual(events[0].topLabels.first?.label, "motorcycle")
        XCTAssertEqual(rig.notifier.shown.count, 1)
        XCTAssertEqual(rig.notifier.shown.first?.category, "loud_vehicle")
        let minutes = try rig.services.measurements.allMinutes()
        XCTAssertTrue(minutes.contains { $0.categoryShares["loud_vehicle"] ?? 0 > 0 })
    }
}
