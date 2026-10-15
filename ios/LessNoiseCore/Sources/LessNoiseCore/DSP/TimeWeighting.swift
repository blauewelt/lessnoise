import Foundation

/**
 * Exponential time weighting applied to the squared (weighted) signal (TimeWeighting.kt):
 * `y[n] = y[n−1] + α·(x²[n] − y[n−1])`, `α = 1 − exp(−1 / (τ·fs))`. Fast: τ = 125 ms; Slow: τ = 1 s.
 */
public final class ExponentialTimeWeighting {
    public static let FAST_TAU = 0.125
    public static let SLOW_TAU = 1.0

    public let tauSeconds: Double
    public let sampleRate: Int
    private let alpha: Double

    /** Current time-weighted mean square. */
    public private(set) var value: Double = 0.0

    public init(tauSeconds: Double, sampleRate: Int = Acoustics.SAMPLE_RATE) {
        self.tauSeconds = tauSeconds
        self.sampleRate = sampleRate
        alpha = 1.0 - exp(-1.0 / (tauSeconds * Double(sampleRate)))
    }

    /** Feeds one already-squared sample and returns the new time-weighted mean square. */
    @inline(__always)
    public func process(_ squared: Double) -> Double {
        value += alpha * (squared - value)
        return value
    }

    public func seed(_ meanSquare: Double) { value = meanSquare }

    public func reset() { value = 0.0 }
}
