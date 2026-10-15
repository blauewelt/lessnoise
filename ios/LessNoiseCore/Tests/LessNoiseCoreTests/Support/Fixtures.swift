import Foundation
import Testing
@testable import LessNoiseCore

/**
 * Golden fixtures written by the Kotlin generators (android/dsp …/golden/, android/app
 * …/testPublic/…/golden/) into Tests/Fixtures/ — see ../../README.md «Golden tests».
 */
enum Fixtures {
    static let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    static let dir = testsDir.appendingPathComponent("Fixtures")
    /** The repository root (ios/LessNoiseCore/Tests → ../../..). */
    static let repo = testsDir.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func load(_ name: String) throws -> JSONValue {
        let url = dir.appendingPathComponent(name)
        let text = try String(contentsOf: url, encoding: .utf8)
        return try JSONValue.parse(text)
    }

    static func repoFile(_ path: String) -> URL { repo.appendingPathComponent(path) }
}

/** Largest deviations seen per quantity, printed at the end of each golden test (for the report). */
final class Deviations {
    private var max: [String: Double] = [:]
    private var order: [String] = []
    let name: String

    init(_ name: String) { self.name = name }

    /** Records |a − b| under [key]; NaN == NaN and equal infinities count as 0. */
    @discardableResult
    func diff(_ key: String, _ a: Double, _ b: Double) -> Double {
        let d: Double
        if a.isNaN && b.isNaN { d = 0 } else if a == b { d = 0 } else { d = abs(a - b) }
        if max[key] == nil { order.append(key) }
        let v = d.isNaN ? Double.infinity : d
        if v > (max[key] ?? 0) || max[key] == nil { max[key] = v }
        return v
    }

    func report() {
        let parts = order.map { "\($0)=\(String(format: "%.3g", max[$0]!))" }
        print("[golden] \(name) max |Δ|: " + parts.joined(separator: ", "))
    }
}

extension JSONValue {
    /** A fixture double: JSON number, or "NaN" / "Infinity" / "-Infinity". */
    var dbl: Double {
        switch self {
        case .double(let d): return d
        case .int(let i): return Double(i)
        case .string("NaN"): return .nan
        case .string("Infinity"): return .infinity
        case .string("-Infinity"): return -.infinity
        default: fatalError("not a number: \(self)")
        }
    }

    var dblOrNil: Double? { isNull ? nil : dbl }
    var int: Int { intValue! }
    var str: String { stringValue! }
    var strOrNil: String? { isNull ? nil : stringValue! }
    var bool: Bool { boolValue! }
    var arr: [JSONValue] { arrayValue! }
    var dbls: [Double] { arr.map { $0.dbl } }

    /** Test access: the member [k] (traps if missing — a fixture/generator mismatch). */
    subscript(k: String) -> JSONValue {
        guard let v = member(k) else { fatalError("fixture has no member \(k)") }
        return v
    }
    subscript(i: Int) -> JSONValue { element(i)! }
}

/** Expect |a − b| ≤ tol (NaN matches NaN), recording the deviation. */
func expectClose(_ a: Double, _ b: Double, _ tol: Double, _ what: @autoclosure () -> String, _ dev: Deviations? = nil, key: String = "value",
                 sourceLocation: SourceLocation = #_sourceLocation) {
    let d = dev?.diff(key, a, b) ?? ((a.isNaN && b.isNaN) || a == b ? 0 : abs(a - b))
    #expect(d <= tol, "\(what()): \(a) vs \(b) (|Δ| = \(d), tolerance \(tol))", sourceLocation: sourceLocation)
}

// ---- Records from fixtures ----------------------------------------------------------------------

func decodeFeatures(_ j: JSONValue) -> EventFeatures? {
    if j.isNull { return nil }
    return EventFeatures(
        localFloorDb: j["local_floor_db"].dbl, excessDb: j["excess_db"].dbl, riseS: j["rise_s"].dbl, decayS: j["decay_s"].dbl,
        jaggedness: j["jaggedness"].dbl, midBandRiseDb: j["mid_band_rise_db"].dbl, lfShare: j["lf_share"].dbl,
        lfFlutterDb: j["lf_flutter_db"].dbl, wind: j["wind"].bool, shape: j["shape"].str
    )
}

func decodeMinute(_ j: JSONValue) -> MinuteRecord {
    var shares = OrderedDict<Double>()
    for p in j["category_shares"].arr { shares[p[0].str] = p[1].dbl }
    let o = j["original"]
    return MinuteRecord(
        startEpochMs: j["start_ms"].int, startIso: j["start_iso"].str, durationSeconds: j["duration_s"].dbl,
        laeqDb: j["laeq_db"].dbl, lafMaxDb: j["lafmax_db"].dbl, lafMinDb: j["lafmin_db"].dbl, l1Db: j["l1_db"].dbl,
        l10Db: j["l10_db"].dbl, l50Db: j["l50_db"].dbl, l90Db: j["l90_db"].dbl, eventCount: j["event_count"].int,
        dominantCategory: j["dominant_category"].strOrNil, categoryShares: shares, classifierFrames: j["classifier_frames"].int,
        calibrationId: j["calibration_id"].isNull ? nil : j["calibration_id"].int, calibrationOffsetDb: j["calibration_offset_db"].dbl,
        audioSource: j["audio_source"].str, calibrated: j["calibrated"].bool, validSeconds: j["valid_s"].dbl,
        clockCorrections: j["clock_corrections"].int,
        original: o.isNull ? nil : { let v = o.dbls; return MinuteLevels(v[0], v[1], v[2], v[3], v[4], v[5], v[6]) }(),
        recalibratedFromId: j["recalibrated_from_id"].strOrNil, recalibrationOffsetDb: j["recalibration_offset_db"].dblOrNil,
        localFloorDb: j["local_floor_db"].dbl, windEventCount: j["wind_event_count"].int
    )
}

func decodeEvent(_ j: JSONValue) -> NoiseEvent {
    let o = j["original"]
    return NoiseEvent(
        startEpochMs: j["start_ms"].int, startIso: j["start_iso"].str, durationSeconds: j["duration_s"].dbl,
        lafMaxDb: j["lafmax_db"].dbl, selDb: j["sel_db"].dbl, backgroundDb: j["background_db"].dbl, thresholdDb: j["threshold_db"].dbl,
        dominantCategory: j["dominant_category"].strOrNil, dominantScore: Float(j["dominant_score"].dbl),
        topLabels: j["top_labels"].arr.map { LabelScore(label: $0[0].str, score: Float($0[1].dbl)) },
        classifierFrames: j["classifier_frames"].int,
        calibrationId: j["calibration_id"].isNull ? nil : j["calibration_id"].int, audioSource: j["audio_source"].str,
        calibrated: j["calibrated"].bool, minLevelDb: j["min_level_db"].dbl,
        original: o.isNull ? nil : { let v = o.dbls; return EventLevels(v[0], v[1], v[2]) }(),
        recalibratedFromId: j["recalibrated_from_id"].strOrNil, recalibrationOffsetDb: j["recalibration_offset_db"].dblOrNil,
        features: decodeFeatures(j["features"])
    )
}

/** Field-by-field comparison of a minute with its fixture (levels within [tol] dB, everything else exact). */
func expectMinute(_ m: MinuteRecord, _ j: JSONValue, _ tol: Double, _ dev: Deviations, _ what: String,
                  sourceLocation: SourceLocation = #_sourceLocation) {
    let e = decodeMinute(j)
    #expect(m.startEpochMs == e.startEpochMs, "\(what) start_ms", sourceLocation: sourceLocation)
    #expect(m.startIso == e.startIso, "\(what) start_iso \(m.startIso) vs \(e.startIso)", sourceLocation: sourceLocation)
    expectClose(m.durationSeconds, e.durationSeconds, 0, "\(what) duration", dev, key: "seconds", sourceLocation: sourceLocation)
    expectClose(m.validSeconds, e.validSeconds, 0, "\(what) valid_s", dev, key: "seconds", sourceLocation: sourceLocation)
    for (name, a, b) in [("laeq", m.laeqDb, e.laeqDb), ("lafmax", m.lafMaxDb, e.lafMaxDb), ("lafmin", m.lafMinDb, e.lafMinDb),
                         ("l1", m.l1Db, e.l1Db), ("l10", m.l10Db, e.l10Db), ("l50", m.l50Db, e.l50Db), ("l90", m.l90Db, e.l90Db),
                         ("local_floor", m.localFloorDb, e.localFloorDb)] {
        expectClose(a, b, tol, "\(what) \(name)", dev, key: "level_dB", sourceLocation: sourceLocation)
    }
    #expect(m.eventCount == e.eventCount, "\(what) event_count \(m.eventCount) vs \(e.eventCount)", sourceLocation: sourceLocation)
    #expect(m.windEventCount == e.windEventCount, "\(what) wind_event_count", sourceLocation: sourceLocation)
    #expect(m.dominantCategory == e.dominantCategory, "\(what) dominant", sourceLocation: sourceLocation)
    #expect(m.categoryShares.keys == e.categoryShares.keys, "\(what) share keys", sourceLocation: sourceLocation)
    for k in e.categoryShares.keys {
        expectClose(m.categoryShares[k] ?? .nan, e.categoryShares[k]!, 0, "\(what) share \(k)", dev, key: "share", sourceLocation: sourceLocation)
    }
    #expect(m.classifierFrames == e.classifierFrames, "\(what) frames", sourceLocation: sourceLocation)
    #expect(m.calibrationId == e.calibrationId && m.calibrated == e.calibrated && m.audioSource == e.audioSource, "\(what) calibration", sourceLocation: sourceLocation)
    #expect(m.calibrationOffsetDb == e.calibrationOffsetDb, "\(what) offset", sourceLocation: sourceLocation)
    #expect(m.clockCorrections == e.clockCorrections, "\(what) clock corrections", sourceLocation: sourceLocation)
    #expect(m.original == e.original && m.recalibratedFromId == e.recalibratedFromId, "\(what) original", sourceLocation: sourceLocation)
    #expect(sameDouble(m.recalibrationOffsetDb, e.recalibrationOffsetDb), "\(what) recalibration offset", sourceLocation: sourceLocation)
    expectClose(m.coverage, j["coverage"].dbl, 0, "\(what) coverage", dev, key: "seconds", sourceLocation: sourceLocation)
}

func expectFeatures(_ f: EventFeatures?, _ j: JSONValue, _ tol: Double, _ dev: Deviations, _ what: String,
                    sourceLocation: SourceLocation = #_sourceLocation) {
    let e = decodeFeatures(j)
    #expect((f == nil) == (e == nil), "\(what) features presence", sourceLocation: sourceLocation)
    guard let f = f, let e = e else { return }
    expectClose(f.localFloorDb, e.localFloorDb, tol, "\(what) local_floor", dev, key: "level_dB", sourceLocation: sourceLocation)
    expectClose(f.excessDb, e.excessDb, tol, "\(what) excess", dev, key: "level_dB", sourceLocation: sourceLocation)
    expectClose(f.midBandRiseDb, e.midBandRiseDb, tol, "\(what) mid_band_rise", dev, key: "level_dB", sourceLocation: sourceLocation)
    expectClose(f.lfFlutterDb, e.lfFlutterDb, tol, "\(what) lf_flutter", dev, key: "level_dB", sourceLocation: sourceLocation)
    expectClose(f.riseS, e.riseS, 1e-6, "\(what) rise", dev, key: "time_s", sourceLocation: sourceLocation)
    expectClose(f.decayS, e.decayS, 1e-6, "\(what) decay", dev, key: "time_s", sourceLocation: sourceLocation)
    expectClose(f.jaggedness, e.jaggedness, 1e-6, "\(what) jaggedness", dev, key: "ratio", sourceLocation: sourceLocation)
    expectClose(f.lfShare, e.lfShare, 1e-9, "\(what) lf_share", dev, key: "ratio", sourceLocation: sourceLocation)
    #expect(f.wind == e.wind, "\(what) wind", sourceLocation: sourceLocation)
    #expect(f.shape == e.shape, "\(what) shape \(f.shape) vs \(e.shape)", sourceLocation: sourceLocation)
}

func expectEvent(_ ev: NoiseEvent, _ j: JSONValue, _ tol: Double, _ dev: Deviations, _ what: String,
                 sourceLocation: SourceLocation = #_sourceLocation) {
    let e = decodeEvent(j)
    #expect(ev.startEpochMs == e.startEpochMs, "\(what) start_ms \(ev.startEpochMs) vs \(e.startEpochMs)", sourceLocation: sourceLocation)
    #expect(ev.startIso == e.startIso, "\(what) start_iso", sourceLocation: sourceLocation)
    expectClose(ev.durationSeconds, e.durationSeconds, 0, "\(what) duration", dev, key: "seconds", sourceLocation: sourceLocation)
    for (name, a, b) in [("lafmax", ev.lafMaxDb, e.lafMaxDb), ("sel", ev.selDb, e.selDb), ("background", ev.backgroundDb, e.backgroundDb)] {
        expectClose(a, b, tol, "\(what) \(name)", dev, key: "level_dB", sourceLocation: sourceLocation)
    }
    #expect(ev.thresholdDb == e.thresholdDb && sameDouble(ev.minLevelDb, e.minLevelDb), "\(what) threshold/min level", sourceLocation: sourceLocation)
    #expect(ev.dominantCategory == e.dominantCategory, "\(what) category \(String(describing: ev.dominantCategory)) vs \(String(describing: e.dominantCategory))", sourceLocation: sourceLocation)
    #expect(ev.dominantScore == e.dominantScore, "\(what) score \(ev.dominantScore) vs \(e.dominantScore)", sourceLocation: sourceLocation)
    #expect(ev.topLabels == e.topLabels, "\(what) top labels", sourceLocation: sourceLocation)
    #expect(ev.classifierFrames == e.classifierFrames, "\(what) frames", sourceLocation: sourceLocation)
    #expect(ev.calibrationId == e.calibrationId && ev.calibrated == e.calibrated && ev.audioSource == e.audioSource, "\(what) calibration", sourceLocation: sourceLocation)
    #expect(ev.original == e.original && ev.recalibratedFromId == e.recalibratedFromId, "\(what) original", sourceLocation: sourceLocation)
    expectFeatures(ev.features, j["features"], tol, dev, what, sourceLocation: sourceLocation)
}
