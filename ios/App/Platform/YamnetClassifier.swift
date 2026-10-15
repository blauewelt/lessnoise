import Foundation
import LessNoiseKit

#if canImport(TensorFlowLite)
import TensorFlowLite

/**
 * YAMNet through TensorFlow Lite (YamnetClassifier.kt): the same yamnet.tflite as Android
 * (MediaPipe float32 build): input `waveform_binary` float32 [15600] (0.975 s at 16 kHz, range
 * [-1, 1]), output float32 [1, 521] AudioSet scores. Compiled only when the TensorFlowLite module
 * is available (not yet: ios/DESIGN.md §9 — add TensorFlowLiteSwift and the model to the target,
 * see ios/README.md «Classifier»). One thread; the input copy is cleared after each inference.
 */
final class YamnetClassifier: SoundClassifier {
    let labels: [String]
    var inputSampleRate: Int { ClassifierPreprocessor.YAMNET_SAMPLE_RATE }
    var inputLength: Int { ClassifierPreprocessor.YAMNET_INPUT_SAMPLES }
    private let interpreter: Interpreter
    private let lock = NSLock()

    init(modelPath: String, labels: [String]) throws {
        precondition(labels.count == 521, "expected 521 labels")
        self.labels = labels
        var options = Interpreter.Options()
        options.threadCount = 1
        interpreter = try Interpreter(modelPath: modelPath, options: options)
        try interpreter.resizeInput(at: 0, to: Tensor.Shape([ClassifierPreprocessor.YAMNET_INPUT_SAMPLES]))
        try interpreter.allocateTensors()
        let out = try interpreter.output(at: 0)
        guard out.shape.dimensions.reduce(1, *) == 521 else { throw NSError(domain: "yamnet", code: 1) }
    }

    func classify(_ window: [Float]) -> [Float] {
        precondition(window.count == inputLength)
        lock.lock(); defer { lock.unlock() }
        do {
            try interpreter.copy(window.withUnsafeBufferPointer { Data(buffer: $0) }, toInputAt: 0)
            try interpreter.invoke()
            let out = try interpreter.output(at: 0)
            let scores = out.data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
            // Clear the model's copy of the audio window right away (privacy hygiene).
            try interpreter.copy(Data(count: inputLength * MemoryLayout<Float>.size), toInputAt: 0)
            return scores
        } catch {
            return []
        }
    }
}

enum Yamnet {
    static func make(_ mapper: CategoryMapper) -> SoundClassifier? {
        guard let url = Bundle.main.url(forResource: "yamnet", withExtension: "tflite") else { return nil }
        return try? YamnetClassifier(modelPath: url.path, labels: mapper.labels)
    }
}
#endif
