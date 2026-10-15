import Foundation

/** Second-order IIR section, transposed direct form II, double precision state (FrequencyWeighting.kt). */
public final class Biquad {
    public let b0: Double, b1: Double, b2: Double, a1: Double, a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    public init(_ b0: Double, _ b1: Double, _ b2: Double, _ a1: Double, _ a2: Double) {
        self.b0 = b0; self.b1 = b1; self.b2 = b2; self.a1 = a1; self.a2 = a2
    }

    @inline(__always)
    public func process(_ x: Double) -> Double {
        let y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    public func reset() {
        z1 = 0.0; z2 = 0.0
    }

    /** Complex response at normalised angular frequency w (rad/sample), returned as (re, im). */
    public func response(_ w: Double) -> (Double, Double) {
        let c1 = cos(w), s1 = -sin(w)
        let c2 = cos(2 * w), s2 = -sin(2 * w)
        let nr = b0 + b1 * c1 + b2 * c2
        let ni = b1 * s1 + b2 * s2
        let dr = 1.0 + a1 * c1 + a2 * c2
        let di = a1 * s1 + a2 * s2
        let den = dr * dr + di * di
        return ((nr * dr + ni * di) / den, (ni * dr - nr * di) / den)
    }
}

/** A frequency weighting filter operating sample by sample. */
public protocol FrequencyWeighting: AnyObject {
    var sampleRate: Int { get }
    func process(_ x: Double) -> Double
    func reset()
    /** Magnitude response of the digital filter in dB at [freqHz]. */
    func magnitudeDb(_ freqHz: Double) -> Double
}

/** Z-weighting: flat (identity), used for calibration diagnostics. */
public final class ZWeighting: FrequencyWeighting {
    public let sampleRate: Int
    public init(sampleRate: Int = Acoustics.SAMPLE_RATE) { self.sampleRate = sampleRate }
    public func process(_ x: Double) -> Double { x }
    public func reset() {}
    public func magnitudeDb(_ freqHz: Double) -> Double { 0.0 }
}

/**
 * A-weighting per IEC 61672-1 (port of AWeighting in FrequencyWeighting.kt; see that file for the
 * design). Analog prototype poles at 20.598997 Hz (double), 107.65265 Hz, 737.86223 Hz and
 * 12194.217 Hz (double), bilinear transform into three biquads, normalised to 0 dB at 1 kHz; the
 * high pole pair is moved to an effective frequency found by the same 5 Hz grid search
 * (≈ 13.86 kHz at 48 kHz) to compensate the bilinear warping. Works at any sample rate (iPhones
 * record at 48 kHz; 44.1 kHz and 16 kHz are golden-tested too).
 */
public final class AWeighting: FrequencyWeighting {
    public static let F1 = 20.598997
    public static let F2 = 107.65265
    public static let F3 = 737.86223
    public static let F4 = 12194.217

    /** IEC 61672-1 analog A-weighting in dB, normalised to 0 dB at 1 kHz. */
    public static func analogDb(_ f: Double) -> Double { rawAnalogDb(f) - rawAnalogDb(1000.0) }

    private static func rawAnalogDb(_ f: Double) -> Double {
        let f2 = f * f
        let ra = (F4 * F4 * f2 * f2) /
            ((f2 + F1 * F1) * sqrt((f2 + F2 * F2) * (f2 + F3 * F3)) * (f2 + F4 * F4))
        return 20.0 * log10(ra)
    }

    static let THIRD_OCTAVES: [Double] = [
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0,
        400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0,
        5000.0, 6300.0, 8000.0, 10000.0,
    ]

    private static func bilinearPole(_ fHz: Double, _ fs: Double) -> Double {
        let w = 2.0 * Double.pi * fHz
        return (2.0 * fs - w) / (2.0 * fs + w)
    }

    static func buildSections(_ fs: Double, _ f4Effective: Double) -> [Biquad] {
        let p1 = bilinearPole(F1, fs)
        let p2 = bilinearPole(F2, fs)
        let p3 = bilinearPole(F3, fs)
        let p4 = bilinearPole(f4Effective, fs)
        return [
            // Double pole at F1 with two zeros at z = 1.
            Biquad(1.0, -2.0, 1.0, -2.0 * p1, p1 * p1),
            // Poles at F2 and F3 with two zeros at z = 1.
            Biquad(1.0, -2.0, 1.0, -(p2 + p3), p2 * p3),
            // Double pole at F4 with two zeros at z = −1 (bilinear image of s = ∞).
            Biquad(1.0, 2.0, 1.0, -2.0 * p4, p4 * p4),
        ]
    }

    static func cascadeMagnitude(_ sections: [Biquad], _ f: Double, _ fs: Double) -> Double {
        let w = 2.0 * Double.pi * f / fs
        var mag = 1.0
        for s in sections {
            let (re, im) = s.response(w)
            mag *= hypot(re, im)
        }
        return mag
    }

    private static func maxError(_ fs: Double, _ f4e: Double) -> Double {
        let sec = buildSections(fs, f4e)
        let g = 1.0 / cascadeMagnitude(sec, 1000.0, fs)
        var worst = 0.0
        for f in THIRD_OCTAVES {
            if f > 0.42 * fs { continue }
            let digital = 20.0 * log10(g * cascadeMagnitude(sec, f, fs))
            worst = Swift.max(worst, abs(digital - analogDb(f)))
        }
        return worst
    }

    /** Effective analog frequency for the high pole pair (see the type doc). */
    public static func effectiveHighPole(_ fs: Double) -> Double {
        var best = F4
        var bestErr = maxError(fs, F4)
        var f = F4
        let upper = F4 * 1.6
        while f <= upper {
            let e = maxError(fs, f)
            if e < bestErr { bestErr = e; best = f }
            f += 5.0
        }
        return best
    }

    public let sampleRate: Int
    public let highPoleEffectiveHz: Double
    private let sections: [Biquad]
    private let gain: Double
    // Hot path: direct references to the three sections.
    private let s0: Biquad
    private let s1: Biquad
    private let s2: Biquad

    public init(sampleRate: Int = Acoustics.SAMPLE_RATE) {
        self.sampleRate = sampleRate
        highPoleEffectiveHz = AWeighting.effectiveHighPole(Double(sampleRate))
        sections = AWeighting.buildSections(Double(sampleRate), highPoleEffectiveHz)
        gain = 1.0 / AWeighting.cascadeMagnitude(sections, 1000.0, Double(sampleRate))
        s0 = sections[0]; s1 = sections[1]; s2 = sections[2]
    }

    /** The three sections' coefficients (b0, b1, b2, a1, a2) and the overall gain, for the golden tests. */
    public var coefficients: (sections: [[Double]], gain: Double) {
        (sections.map { [$0.b0, $0.b1, $0.b2, $0.a1, $0.a2] }, gain)
    }

    @inline(__always)
    public func process(_ x: Double) -> Double { s2.process(s1.process(s0.process(x * gain))) }

    public func reset() { sections.forEach { $0.reset() } }

    public func magnitudeDb(_ freqHz: Double) -> Double {
        20.0 * log10(gain * AWeighting.cascadeMagnitude(sections, freqHz, Double(sampleRate)))
    }
}

/** Convenience: dB of an amplitude ratio. */
func ampDb(_ x: Double) -> Double { 20.0 * log10(x) }
