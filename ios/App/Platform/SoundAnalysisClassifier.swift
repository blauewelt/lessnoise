import AVFoundation
import CoreMedia
import Foundation
import LessNoiseKit
import SoundAnalysis

/**
 * The on-device sound classifier of iOS (planner's decision for milestone 7): Apple's built-in
 * SoundAnalysis classifier `SNClassifySoundRequest(classifierIdentifier: .version1)` — part of
 * iOS, no model file, no download, nothing leaves the phone. It plugs into the engine's classifier
 * plumbing exactly where YAMNet does on Android: the engine hands over the latest 0.975 s of
 * 16 kHz audio about once a second (the same window as Android, already anti-aliased and level
 * normalised by [ClassifierPreprocessor]); this class feeds it to ONE long-lived stream analyzer
 * (a continuous stream at 16 kHz, window ≈ 0.975 s, no overlap) and returns the newest result's
 * confidences as scores aligned with [labels] (= `knownClassifications`). The audio is not kept:
 * the analyzer's own buffer and the 16 kHz copy for the call only.
 */
final class SoundAnalysisClassifier: NSObject, SoundClassifier, SNResultsObserving {
    let labels: [String]
    var inputSampleRate: Int { ClassifierPreprocessor.YAMNET_SAMPLE_RATE }
    var inputLength: Int { ClassifierPreprocessor.YAMNET_INPUT_SAMPLES }
    /** The window SoundAnalysis really uses (s). */
    let windowSeconds: Double

    private let format: AVAudioFormat
    private let analyzer: SNAudioStreamAnalyzer
    private let scorer: LabelScores
    private let lock = NSLock()
    private var latest: [Float]?
    private let arrived = DispatchSemaphore(value: 0)
    private var position: AVAudioFramePosition = 0

    /** Apple's label identifiers of the version-1 classifier, or nil if SoundAnalysis cannot make the request. */
    static func knownLabels() -> [String]? {
        (try? SNClassifySoundRequest(classifierIdentifier: .version1))?.knownClassifications
    }

    /** nil when the request cannot be created (the app then runs without categories). */
    init?(labels: [String]) {
        guard let f = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(ClassifierPreprocessor.YAMNET_SAMPLE_RATE), channels: 1, interleaved: false),
              let request = try? SNClassifySoundRequest(classifierIdentifier: .version1) else { return nil }
        self.labels = labels
        format = f
        scorer = LabelScores(labels)
        var enumerated: [Double]? = nil
        var range: ClosedRange<Double>? = nil
        // In Swift SNTimeDurationConstraint is an enum with the values as payloads (CI run 38810186677).
        switch request.windowDurationConstraint {
        case let .enumeratedDurations(ds): enumerated = ds.map { $0.seconds }
        case let .durationRange(r):
            if r.start.seconds.isFinite && r.end.seconds.isFinite && r.end.seconds >= r.start.seconds { range = r.start.seconds...r.end.seconds }
        @unknown default: break
        }
        windowSeconds = AnalysisWindow.choose(enumerated: enumerated, range: range)
        request.windowDuration = CMTime(seconds: windowSeconds, preferredTimescale: CMTimeScale(ClassifierPreprocessor.YAMNET_SAMPLE_RATE))
        request.overlapFactor = 0
        analyzer = SNAudioStreamAnalyzer(format: f)
        super.init()
        do { try analyzer.add(request, withObserver: self) } catch { return nil }
    }

    /** Called on the session's inference queue, one window at a time. */
    func classify(_ window: [Float]) -> [Float] {
        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(window.count)) else { return [] }
        buf.frameLength = AVAudioFrameCount(window.count)
        window.withUnsafeBufferPointer { buf.floatChannelData![0].update(from: $0.baseAddress!, count: window.count) }
        while arrived.wait(timeout: .now()) == .success {}
        lock.lock(); latest = nil; lock.unlock()
        analyzer.analyze(buf, atAudioFramePosition: position)
        position += AVAudioFramePosition(window.count)
        // The result for this window (or, with a longer window, the newest one) comes on the
        // analyzer's queue; without one in time this window gives no frame (the engine copes).
        _ = arrived.wait(timeout: .now() + 1.5)
        lock.lock(); defer { lock.unlock() }
        let s = latest ?? []
        latest = nil
        // Clear the 16 kHz copy (privacy hygiene, as on Android).
        memset(buf.floatChannelData![0], 0, window.count * MemoryLayout<Float>.size)
        return s
    }

    // SNResultsObserving (the analyzer's queue).

    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let r = result as? SNClassificationResult else { return }
        let s = scorer.scores(r.classifications.map { (identifier: $0.identifier, confidence: $0.confidence) })
        lock.lock(); latest = s; lock.unlock()
        arrived.signal()
    }

    func request(_ request: SNRequest, didFailWithError error: Error) {}
    func requestDidComplete(_ request: SNRequest) {}
}

/** The categories mapping bundled with the app (ios/App/categories-ios.json). */
enum CategoriesFile {
    static func json() -> String? {
        guard let u = Bundle.main.url(forResource: "categories-ios", withExtension: "json") else { return nil }
        return try? String(contentsOf: u, encoding: .utf8)
    }
}
