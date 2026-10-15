import Foundation

// The classifier's platform-free parts (android/dsp classify/*.kt): the mapping of AudioSet scores
// to the project's categories, the classifier interface and the input conditioning. The model
// itself (YAMNet) is NOT ported: phase 2 decides between Core ML and TensorFlow Lite (ios/DESIGN.md).
// Without a mapper the engine runs exactly like the Android replay: classifier off, categories null.

/** A project category: a named group of raw classifier labels. */
public struct CategoryDef: Equatable {
    public let id: String
    public let nameDe: String
    public let members: [String]
    public init(id: String, nameDe: String, members: [String]) { self.id = id; self.nameDe = nameDe; self.members = members }
}

public struct LabelScore: Equatable {
    public let label: String
    public let score: Float
    public init(label: String, score: Float) { self.label = label; self.score = score }
}

public struct CategoryDecision: Equatable {
    /** Category id, or [CategoryMapper.UNCLASSIFIED]. */
    public let dominant: String
    public let dominantScore: Float
    /** Score per category id (same order as [CategoryMapper.categories]). */
    public let categoryScores: OrderedDict<Float>
}

public enum CategoryMapperError: Error, Equatable {
    case unknownLabels([String])
    case duplicateIds([String])
    case reservedId
    case badJson(String)
}

/**
 * Maps raw AudioSet scores to project categories (CategoryMapper.kt): category score = max of its
 * member label scores; dominant = (a) the category of the top-1 label if mapped and ≥
 * [top1Threshold], else (b) the best category if ≥ [threshold], else (c) "unclassified"; ties: the
 * earlier category wins.
 */
public final class CategoryMapper {
    public static let UNCLASSIFIED = "unclassified"
    public static let DEFAULT_CATEGORY_THRESHOLD: Float = 0.2
    public static let DEFAULT_TOP1_THRESHOLD: Float = 0.1

    public let labels: [String]
    public let categories: [CategoryDef]
    public let threshold: Float
    public let tieMargin: Float
    public let top1Threshold: Float
    public let fallbackNameDe: String
    private let memberIdx: [[Int]]
    /** Category index of each label, −1 if unmapped. */
    private let labelCategory: [Int]

    public init(
        labels: [String], categories: [CategoryDef], threshold: Float = DEFAULT_CATEGORY_THRESHOLD,
        tieMargin: Float = 0.0, top1Threshold: Float = DEFAULT_TOP1_THRESHOLD, fallbackNameDe: String = "Sonstiges"
    ) throws {
        var labelIndex: [String: Int] = [:]
        // Kotlin's associate keeps the LAST index of a duplicated label name.
        for (i, l) in labels.enumerated() { labelIndex[l] = i }
        let unknown = categories.flatMap { c in c.members.filter { labelIndex[$0] == nil }.map { "\(c.id): '\($0)'" } }
        guard unknown.isEmpty else { throw CategoryMapperError.unknownLabels(unknown) }
        let ids = categories.map { $0.id }
        guard Set(ids).count == ids.count else { throw CategoryMapperError.duplicateIds(ids) }
        guard !ids.contains(CategoryMapper.UNCLASSIFIED) else { throw CategoryMapperError.reservedId }
        self.labels = labels
        self.categories = categories
        self.threshold = threshold
        self.tieMargin = tieMargin
        self.top1Threshold = top1Threshold
        self.fallbackNameDe = fallbackNameDe
        memberIdx = categories.map { c in c.members.map { labelIndex[$0]! } }
        var lc = [Int](repeating: -1, count: labels.count)
        // A label listed in several categories belongs to the earliest (highest priority).
        for c in categories.indices.reversed() { for i in memberIdx[c] { lc[i] = c } }
        labelCategory = lc
    }

    /** From the app's `categories.json` and the label list (same rules as CategoryMapper.fromJson). */
    public static func fromJson(_ json: String, labels: [String]) throws -> CategoryMapper {
        let root = try JSONValue.parse(json)
        guard let cats = root.member("categories")?.arrayValue else { throw CategoryMapperError.badJson("categories") }
        let defs = try cats.map { el -> CategoryDef in
            guard let id = el.member("id")?.stringValue, let name = el.member("name_de")?.stringValue,
                  let members = el.member("audioset")?.arrayValue?.compactMap({ $0.stringValue })
            else { throw CategoryMapperError.badJson("category") }
            return CategoryDef(id: id, nameDe: name, members: members)
        }
        // "threshold" is the pre-0.3.0 name of category_threshold.
        let threshold = (root.member("category_threshold") ?? root.member("threshold"))?.doubleValue ?? Double(DEFAULT_CATEGORY_THRESHOLD)
        return try CategoryMapper(
            labels: labels,
            categories: defs,
            threshold: Float(threshold),
            tieMargin: Float(root.member("tie_margin")?.doubleValue ?? 0.0),
            top1Threshold: Float(root.member("top1_threshold")?.doubleValue ?? Double(DEFAULT_TOP1_THRESHOLD)),
            fallbackNameDe: root.member("fallback")?.member("name_de")?.stringValue ?? "Sonstiges"
        )
    }

    /** Parses a plain label file (one display name per line). */
    public static func parseLabels(_ text: String) -> [String] {
        var lines = text.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            var s = String(line)
            while s.hasSuffix("\r") { s.removeLast() }
            return s
        }
        if !lines.isEmpty && lines.last!.isEmpty { lines.removeLast() }
        return lines
    }

    public var categoryIds: [String] { categories.map { $0.id } }

    /** All bucket ids used for time shares: the categories plus "unclassified". */
    public var bucketIds: [String] { categoryIds + [CategoryMapper.UNCLASSIFIED] }

    public func nameDe(_ id: String) -> String { categories.first { $0.id == id }?.nameDe ?? fallbackNameDe }

    public func categoryScores(_ scores: [Float]) -> [Float] {
        precondition(scores.count == labels.count, "expected \(labels.count) scores, got \(scores.count)")
        return categories.indices.map { c in
            var m: Float = 0
            for i in memberIdx[c] where scores[i] > m { m = scores[i] }
            return m
        }
    }

    public func decide(_ scores: [Float]) -> CategoryDecision {
        let cs = categoryScores(scores)
        var map = OrderedDict<Float>()
        for (i, c) in categories.enumerated() { map[c.id] = cs[i] }
        // (a) The top-1 label decides if it is mapped and scores ≥ top1Threshold.
        var top = -Float.infinity
        for s in scores where s > top { top = s }
        if top >= top1Threshold {
            var cat = -1
            for i in scores.indices where scores[i] == top && labelCategory[i] >= 0 && (cat < 0 || labelCategory[i] < cat) {
                cat = labelCategory[i]
            }
            if cat >= 0 { return CategoryDecision(dominant: categories[cat].id, dominantScore: top, categoryScores: map) }
        }
        // (b) Otherwise the best category if it reaches the category threshold.
        var best = -1
        for c in cs.indices {
            if best < 0 { best = c; continue }
            // Earlier (higher-priority) category keeps the lead on ties / within the margin.
            if cs[c] > cs[best] + tieMargin { best = c }
        }
        if best >= 0 && cs[best] >= threshold {
            return CategoryDecision(dominant: categories[best].id, dominantScore: cs[best], categoryScores: map)
        }
        return CategoryDecision(dominant: CategoryMapper.UNCLASSIFIED, dominantScore: best >= 0 ? cs[best] : 0, categoryScores: map)
    }

    /** The [k] best labels, highest first; equal scores keep label order (Kotlin's stable sort). */
    public func topLabels(_ scores: [Float], _ k: Int = 3) -> [LabelScore] {
        let order = scores.indices.sorted { a, b in scores[a] > scores[b] || (scores[a] == scores[b] && a < b) }
        return order.prefix(k).map { LabelScore(label: labels[$0], score: scores[$0]) }
    }
}

/** A sound-source classifier operating on a mono waveform window (implemented in the app, phase 2). */
public protocol SoundClassifier: AnyObject {
    /** Raw output label names, index-aligned with [classify] scores. */
    var labels: [String] { get }
    /** Expected input sample rate (Hz). */
    var inputSampleRate: Int { get }
    /** Expected number of samples per window. */
    var inputLength: Int { get }
    /** Returns one score per label for a window of exactly [inputLength] float samples in [-1, 1]. */
    func classify(_ window: [Float]) -> [Float]
}

/** Classifier output tied to the measurement timeline (end of window in 48 kHz input samples). */
public struct ClassifierFrame {
    public let endSample: Int
    public let windowSamples48k: Int
    public let scores: [Float]
    public let inputGainDb: Double
    public init(endSample: Int, windowSamples48k: Int, scores: [Float], inputGainDb: Double = 0.0) {
        self.endSample = endSample; self.windowSamples48k = windowSamples48k; self.scores = scores; self.inputGainDb = inputGainDb
    }
}

/** One classifier run as seen by the engine, for diagnostics (numbers and label names only). */
public struct ClassifierResult {
    public let endSample: Int
    public let windowSamples48k: Int
    public let endEpochMs: Int
    public let top: [LabelScore]
    public let decision: CategoryDecision?
    public let inputGainDb: Double
    public let lafDb: Double
    public let lafMaxDb: Double
    public let ignoredInvalid: Bool
    public var startSample: Int { endSample - windowSamples48k }
}

/**
 * Input conditioning for the classifier (SoundClassifier.kt): the window is amplified so its RMS
 * reaches [targetRmsDbfs] (gain 0…[maxGainDb] dB), then clipped to [-1, 1]. Levels are never
 * computed from this signal.
 */
public enum ClassifierPreprocessor {
    public static let YAMNET_SAMPLE_RATE = 16_000
    public static let YAMNET_INPUT_SAMPLES = 15_600 // 0.975 s

    /** Applies the gain in place and returns the gain in dB that was applied. */
    @discardableResult
    public static func normalize(_ window: inout [Float], targetRmsDbfs: Double = -30.0, maxGainDb: Double = 40.0) -> Double {
        var s = 0.0
        for v in window { s += Double(v) * Double(v) }
        let r = sqrt(s / Double(window.count))
        if r <= 0.0 { return 0.0 }
        let currentDb = 20 * log10(r)
        let gainDb = Swift.min(Swift.max(targetRmsDbfs - currentDb, 0.0), maxGainDb)
        if gainDb == 0.0 { return 0.0 }
        let g = Float(pow(10.0, gainDb / 20.0))
        for i in window.indices { window[i] = Swift.min(Swift.max(window[i] * g, -1), 1) }
        return gainDb
    }
}
