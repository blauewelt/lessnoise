import Foundation

/**
 * Detects audio that cannot come from a working microphone (SilenceDetector.kt).
 *
 * A run of ≥ [ZERO_RUN_SAMPLES] exactly-zero samples (10 ms at 48 kHz) or a block mean square
 * below [FLOOR_DBFS] marks the block as invalid. On Android this catches the system silencing the
 * app's capture during a call; on iOS an interruption stops the engine instead (AVAudioSession
 * interruption), but a muted or broken input still delivers zeros, so the same rule applies.
 */
public enum SilenceDetector {
    public static let ZERO_RUN_SAMPLES = 480
    public static let FLOOR_DBFS = -130.0
    public static let FLOOR_MEAN_SQUARE: Double = pow(10.0, FLOOR_DBFS / 10.0)

    /** Stateless check of a single block. */
    public static func isDigitalSilence(_ block: [Float], count: Int? = nil) -> Bool {
        let n = count ?? block.count
        if n == 0 { return false }
        var run = 0
        var maxRun = 0
        var sum = 0.0
        for i in 0..<n {
            let x = block[i]
            if x == 0 { run += 1; if run > maxRun { maxRun = run } } else { run = 0 }
            sum += Double(x) * Double(x)
        }
        return maxRun >= Swift.min(ZERO_RUN_SAMPLES, n) || sum / Double(n) < FLOOR_MEAN_SQUARE
    }
}
