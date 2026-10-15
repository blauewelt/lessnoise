import Foundation

/**
 * The categories on iOS (planner's decision for milestone 7, 2026-10-15): Apple's built-in sound
 * classifier (SoundAnalysis `SNClassifySoundRequest(classifierIdentifier: .version1)`, on the
 * phone, no download, no dependency) instead of YAMNet. Its label identifiers (e.g. «speech»)
 * are mapped to the app's categories by `ios/App/categories-ios.json`, the analogue of Android's
 * categories.json: same ids, names, order (= tie priority, «loud_vehicle» first) and thresholds;
 * the member lists hold Apple identifiers under the key «apple» instead of AudioSet names under
 * «audioset». The decision itself is the core's [CategoryMapper] (the Kotlin rules: top-1 label if
 * mapped and ≥ top1_threshold, else the best category ≥ category_threshold, else «unclassified»;
 * earlier category wins ties), so only the scores' source differs from Android.
 */
public enum AppleSoundCategories {
    public enum Failure: Error, Equatable {
        case badJson(String)
    }

    /** The member identifiers of every category, in file order (for checks against the classifier's list). */
    public static func memberIdentifiers(json: String) throws -> [String: [String]] {
        guard let cats = (try? JSONValue.parse(json))?.member("categories")?.arrayValue else { throw Failure.badJson("categories") }
        var out: [String: [String]] = [:]
        for c in cats {
            guard let id = c.member("id")?.stringValue, let members = c.member("apple")?.arrayValue else { throw Failure.badJson("category") }
            out[id] = members.compactMap { $0.stringValue }
        }
        return out
    }

    /**
     * The mapper for [labels] (the classifier's `knownClassifications`, in its order). Throws
     * [CategoryMapperError.unknownLabels] if the file names an identifier the classifier does not
     * know (CI checks this against the real list).
     */
    public static func mapper(json: String, labels: [String]) throws -> CategoryMapper {
        let root = try JSONValue.parse(json)
        guard let cats = root.member("categories")?.arrayValue else { throw Failure.badJson("categories") }
        let defs = try cats.map { el -> CategoryDef in
            guard let id = el.member("id")?.stringValue, let name = el.member("name_de")?.stringValue,
                  let members = el.member("apple")?.arrayValue?.compactMap({ $0.stringValue })
            else { throw Failure.badJson("category") }
            return CategoryDef(id: id, nameDe: name, members: members)
        }
        return try CategoryMapper(
            labels: labels, categories: defs,
            threshold: Float(root.member("category_threshold")?.doubleValue ?? Double(CategoryMapper.DEFAULT_CATEGORY_THRESHOLD)),
            tieMargin: Float(root.member("tie_margin")?.doubleValue ?? 0),
            top1Threshold: Float(root.member("top1_threshold")?.doubleValue ?? Double(CategoryMapper.DEFAULT_TOP1_THRESHOLD)),
            fallbackNameDe: root.member("fallback")?.member("name_de")?.stringValue ?? "Sonstiges"
        )
    }

    /**
     * Names and colours without the classifier (e.g. an older iOS without SoundAnalysis results):
     * a mapper whose labels are just the file's own members, so the ids, names and CSV columns
     * stay the same; no scores ever come.
     */
    public static func namesOnly(json: String) throws -> CategoryMapper {
        let members = try memberIdentifiers(json: json).values.flatMap { $0 }
        var seen = Set<String>()
        return try mapper(json: json, labels: members.filter { seen.insert($0).inserted })
    }
}

/**
 * One classification result of SoundAnalysis (identifier → confidence 0…1) as the score vector the
 * engine expects: aligned with the mapper's labels, 0 for identifiers not in the result.
 */
public struct LabelScores {
    public let labels: [String]
    private let index: [String: Int]

    public init(_ labels: [String]) {
        self.labels = labels
        var idx: [String: Int] = [:]
        for (i, l) in labels.enumerated() where idx[l] == nil { idx[l] = i }
        index = idx
    }

    public func scores(_ result: [(identifier: String, confidence: Double)]) -> [Float] {
        var s = [Float](repeating: 0, count: labels.count)
        for (id, c) in result {
            if let i = index[id] { s[i] = Swift.max(s[i], Float(Swift.min(Swift.max(c, 0), 1))) }
        }
        return s
    }
}

/**
 * The window length to ask SoundAnalysis for: Android's 0.975 s if the classifier allows it, else
 * the allowed value closest to it (an enumerated list or a range).
 */
public enum AnalysisWindow {
    public static let PREFERRED_SECONDS = Double(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES) / Double(ClassifierPreprocessor.YAMNET_SAMPLE_RATE)

    public static func choose(enumerated: [Double]?, range: ClosedRange<Double>?) -> Double {
        if let r = range { return Swift.min(Swift.max(PREFERRED_SECONDS, r.lowerBound), r.upperBound) }
        if let e = enumerated, let best = e.min(by: { abs($0 - PREFERRED_SECONDS) < abs($1 - PREFERRED_SECONDS) }) { return best }
        return PREFERRED_SECONDS
    }
}
