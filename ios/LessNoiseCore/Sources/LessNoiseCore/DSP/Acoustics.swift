import Foundation

/**
 * Shared constants and level helpers (port of android/dsp Acoustics.kt).
 *
 * Levels are computed as `10·log10(meanSquare) + calibrationOffsetDb`, where `meanSquare` is the
 * mean square of the (weighted) signal in digital full-scale units (float samples in [-1, 1]).
 */
public enum Acoustics {
    public static let SAMPLE_RATE = 48_000

    /** Android CDD sensitivity guideline: 90 dB SPL at 1 kHz should yield RMS 2500 of 32768. */
    public static let CDD_REFERENCE_SPL_DB = 90.0
    public static let CDD_REFERENCE_RMS = 2500.0 / 32768.0

    /**
     * Default (uncalibrated) offset ≈ 112.35 dB: 90 dB SPL − 20·log10(2500/32768). The same
     * default is used on iOS (see ios/DESIGN.md «Calibration»): data measured with it is flagged
     * "uncalibrated" exactly as on Android.
     */
    public static let DEFAULT_CALIBRATION_OFFSET_DB: Double = CDD_REFERENCE_SPL_DB - 20.0 * log10(CDD_REFERENCE_RMS)

    /** Floor used to avoid log(0); corresponds to roughly −200 dBFS. */
    private static let MIN_MEAN_SQUARE = 1e-20

    public static func db(_ meanSquare: Double, _ offsetDb: Double = 0.0) -> Double {
        10.0 * log10(Swift.max(meanSquare, MIN_MEAN_SQUARE)) + offsetDb
    }

    public static func meanSquareFromDb(_ levelDb: Double, _ offsetDb: Double = 0.0) -> Double {
        pow(10.0, (levelDb - offsetDb) / 10.0)
    }

    /** Energetic (power) average of levels weighted by durations. */
    public static func energyAverage(_ levelsDb: [Double], _ weights: [Double]? = nil) -> Double {
        precondition(weights == nil || weights!.count == levelsDb.count)
        if levelsDb.isEmpty { return .nan }
        var sum = 0.0
        var wsum = 0.0
        for i in levelsDb.indices {
            let w = weights?[i] ?? 1.0
            sum += w * pow(10.0, levelsDb[i] / 10.0)
            wsum += w
        }
        return wsum > 0 ? 10.0 * log10(sum / wsum) : .nan
    }
}
