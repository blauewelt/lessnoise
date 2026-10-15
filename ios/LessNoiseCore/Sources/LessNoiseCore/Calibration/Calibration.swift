import Foundation

/** In-place iterative radix-2 FFT (calibration/Calibration.kt). */
public enum Fft {
    public static func transform(_ re: inout [Double], _ im: inout [Double]) {
        let n = re.count
        precondition(n == im.count && n > 0 && (n & (n - 1)) == 0, "size must be a power of two")
        var j = 0
        for i in 1..<n {
            var bit = n >> 1
            while j & bit != 0 { j ^= bit; bit >>= 1 }
            j ^= bit
            if i < j {
                re.swapAt(i, j)
                im.swapAt(i, j)
            }
        }
        var len = 2
        while len <= n {
            let ang = -2 * Double.pi / Double(len)
            let wr = cos(ang), wi = sin(ang)
            var i = 0
            while i < n {
                var cr = 1.0, ci = 0.0
                for k in 0..<(len / 2) {
                    let ur = re[i + k], ui = im[i + k]
                    let vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    let vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    let ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len <<= 1
        }
    }
}

/**
 * Tone analysis of one frame: dominant frequency (parabolic interpolation on the log power
 * spectrum, Hann window) and tonality = fraction of the power within ±4 bins of the peak.
 */
public enum ToneAnalyzer {
    public struct Tone: Equatable {
        public let frequencyHz: Double
        public let tonality: Double
    }

    public static func analyze(_ frame: [Double], _ sampleRate: Int) -> Tone {
        let n = frame.count
        var re = (0..<n).map { frame[$0] * (0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n - 1))) }
        var im = [Double](repeating: 0, count: n)
        Fft.transform(&re, &im)
        let half = n / 2
        let p = (0...half).map { re[$0] * re[$0] + im[$0] * im[$0] }
        let minBin = Swift.max(3, JavaMath.toInt(20.0 * Double(n) / Double(sampleRate)))
        var k = minBin
        for i in minBin...half where p[i] > p[k] { k = i }
        var total = 0.0
        for i in minBin...half { total += p[i] }
        var peak = 0.0
        for i in Swift.max(minBin, k - 4)...Swift.min(half, k + 4) { peak += p[i] }
        var delta = 0.0
        if k > minBin && k < half && p[k - 1] > 0 && p[k + 1] > 0 && p[k] > 0 {
            let a = log(p[k - 1]), b = log(p[k]), c = log(p[k + 1])
            let den = a - 2 * b + c
            if den != 0.0 { delta = 0.5 * (a - c) / den }
        }
        let freq = (Double(k) + delta) * Double(sampleRate) / Double(n)
        return Tone(frequencyHz: freq, tonality: total > 0 ? peak / total : 0.0)
    }
}

/**
 * Measures the raw (offset 0) A- and Z-weighted equivalent level of the microphone signal over a
 * fixed duration, plus the 1 s level series and (optionally) a tone analysis for calibrator use.
 * The first [settleSeconds] are discarded. Privacy: holds at most one 8192-sample frame of audio.
 */
public final class CalibrationMeasurement {
    public let durationSeconds: Int
    public let analyzeTone: Bool
    public let sampleRate: Int
    public let settleSeconds: Double

    private let aw: AWeighting
    private let settleSamples: Int
    private let targetSamples: Int
    private var seen = 0
    private var measured = 0
    private var sumA = 0.0
    private var sumZ = 0.0
    private var clipped = 0
    private var secSumA = 0.0
    private var secCount = 0
    private var secondLevels: [Double] = []

    private let frameSize = 8192
    private var frame: [Double]
    private var framePos = 0
    private var tones: [ToneAnalyzer.Tone] = []

    private let lock = NSLock()
    private var _invalidated = false
    /** Set when the input contained digital silence or the platform reported an interruption. */
    public var invalidated: Bool { lock.lock(); defer { lock.unlock() }; return _invalidated }

    public init(durationSeconds: Int, analyzeTone: Bool = false, sampleRate: Int = Acoustics.SAMPLE_RATE, settleSeconds: Double = 0.5) {
        self.durationSeconds = durationSeconds
        self.analyzeTone = analyzeTone
        self.sampleRate = sampleRate
        self.settleSeconds = settleSeconds
        aw = AWeighting(sampleRate: sampleRate)
        settleSamples = JavaMath.toLong(settleSeconds * Double(sampleRate))
        targetSamples = durationSeconds * sampleRate
        frame = [Double](repeating: 0, count: frameSize)
    }

    /** Marks the measurement unusable (interruption, app went to the background, …). */
    public func invalidate() { lock.lock(); _invalidated = true; lock.unlock() }

    public var isComplete: Bool { measured >= targetSamples }
    public var progress: Double { Double(measured) / Double(targetSamples) }

    /** Most recent completed 1 s raw A level (offset 0), or NaN. */
    public var lastSecondRawDb: Double { secondLevels.last ?? .nan }

    public func process(_ block: [Float], _ count: Int? = nil) {
        let n = count ?? block.count
        if invalidated { return }
        // Some devices deliver zeros while the input starts up: only check after the settle time.
        if seen >= settleSamples && !isComplete && SilenceDetector.isDigitalSilence(block, count: n) {
            invalidate()
            return
        }
        for i in 0..<n {
            let x = Double(block[i])
            let a = aw.process(x)
            seen += 1
            if seen <= settleSamples || isComplete { continue }
            sumA += a * a; sumZ += x * x; measured += 1
            if abs(x) >= CalibrationMath.CLIP_LEVEL { clipped += 1 }
            secSumA += a * a; secCount += 1
            if secCount == sampleRate {
                secondLevels.append(Acoustics.db(secSumA / Double(secCount)))
                secSumA = 0.0; secCount = 0
            }
            if analyzeTone {
                frame[framePos] = x
                framePos += 1
                if framePos == frameSize {
                    tones.append(ToneAnalyzer.analyze(frame, sampleRate))
                    framePos = 0
                }
            }
        }
    }

    public func result() -> CalibrationResult {
        let n = Swift.max(1, measured)
        let freqs = tones.map { $0.frequencyHz }.sorted()
        var tonality: Double? = nil
        if !tones.isEmpty {
            var s = 0.0
            for t in tones { s += t.tonality }
            tonality = s / Double(tones.count)
        }
        return CalibrationResult(
            rawLaeqDb: Acoustics.db(sumA / Double(n)),
            rawLzeqDb: Acoustics.db(sumZ / Double(n)),
            secondLevelsRawDb: secondLevels,
            stdDevDb: CalibrationMath.stdDev(secondLevels),
            measuredSeconds: Double(measured) / Double(sampleRate),
            toneFrequencyHz: freqs.isEmpty ? nil : freqs[freqs.count / 2],
            tonality: tonality,
            clippedFraction: Double(clipped) / Double(n)
        )
    }
}

public struct CalibrationResult: Equatable {
    /** A-weighted Leq with offset 0 (i.e. dB re full scale mean square). */
    public let rawLaeqDb: Double
    public let rawLzeqDb: Double
    public let secondLevelsRawDb: [Double]
    public let stdDevDb: Double
    public let measuredSeconds: Double
    public let toneFrequencyHz: Double?
    public let tonality: Double?
    /** Fraction of measured samples at or above [CalibrationMath.CLIP_LEVEL] (digital full scale). */
    public let clippedFraction: Double

    public init(
        rawLaeqDb: Double, rawLzeqDb: Double, secondLevelsRawDb: [Double], stdDevDb: Double, measuredSeconds: Double,
        toneFrequencyHz: Double?, tonality: Double?, clippedFraction: Double = 0.0
    ) {
        self.rawLaeqDb = rawLaeqDb; self.rawLzeqDb = rawLzeqDb; self.secondLevelsRawDb = secondLevelsRawDb
        self.stdDevDb = stdDevDb; self.measuredSeconds = measuredSeconds; self.toneFrequencyHz = toneFrequencyHz
        self.tonality = tonality; self.clippedFraction = clippedFraction
    }
}

public enum CalibrationWarning: String, CaseIterable {
    /** Std. deviation of 1 s levels > 2 dB: the noise was not steady enough. */
    case UNSTEADY
    /** Reference level < 50 dB(A): too close to the phone's noise floor. */
    case TOO_QUIET
    /** Calibrator: dominant frequency outside 1 kHz ± 5 %. */
    case FREQUENCY_OFF
    /** Calibrator: signal not tonal (coupling problem or background noise). */
    case NOT_TONAL
    /** Resulting offset deviates > 20 dB from the default: probably a mistake. */
    case IMPLAUSIBLE_OFFSET
    /** Signal reached digital full scale: the measured level is too low and the offset too high. */
    case CLIPPING
}

public enum CalibrationMath {
    public static let MAX_STD_DEV_DB = 2.0
    public static let MIN_REFERENCE_DB = 50.0
    public static let CALIBRATOR_FREQ_HZ = 1000.0
    public static let CALIBRATOR_FREQ_TOLERANCE = 0.05
    public static let MIN_TONALITY = 0.8
    public static let MAX_DEVIATION_FROM_DEFAULT_DB = 20.0
    /** |sample| at or above this counts as clipped (−0.09 dBFS). */
    public static let CLIP_LEVEL = 0.99
    /** More than this fraction of clipped samples invalidates a calibration measurement. */
    public static let MAX_CLIPPED_FRACTION = 1e-4

    /** Reference meter method: offset = reference reading − measured raw LAeq. */
    public static func referenceOffset(_ referenceLaeqDb: Double, _ measuredRawLaeqDb: Double) -> Double { referenceLaeqDb - measuredRawLaeqDb }

    /** Acoustic calibrator: offset = nominal level − measured raw LAeq (A = 0 dB at 1 kHz). */
    public static func calibratorOffset(_ nominalDb: Double, _ measuredRawLaeqDb: Double) -> Double { nominalDb - measuredRawLaeqDb }

    public static func stdDev(_ values: [Double]) -> Double {
        if values.count < 2 { return 0.0 }
        var sum = 0.0
        for v in values { sum += v }
        let m = sum / Double(values.count)
        var s = 0.0
        for v in values { s += (v - m) * (v - m) }
        return sqrt(s / Double(values.count - 1))
    }

    public static func referenceWarnings(_ result: CalibrationResult, _ referenceLaeqDb: Double?) -> [CalibrationWarning] {
        var w: [CalibrationWarning] = []
        if result.stdDevDb > MAX_STD_DEV_DB { w.append(.UNSTEADY) }
        if result.clippedFraction > MAX_CLIPPED_FRACTION { w.append(.CLIPPING) }
        if let ref = referenceLaeqDb {
            if ref < MIN_REFERENCE_DB { w.append(.TOO_QUIET) }
            if implausible(referenceOffset(ref, result.rawLaeqDb)) { w.append(.IMPLAUSIBLE_OFFSET) }
        }
        return w
    }

    public static func calibratorWarnings(_ result: CalibrationResult, _ nominalDb: Double) -> [CalibrationWarning] {
        var w: [CalibrationWarning] = []
        let f = result.toneFrequencyHz
        if f == nil || abs(f! - CALIBRATOR_FREQ_HZ) > CALIBRATOR_FREQ_HZ * CALIBRATOR_FREQ_TOLERANCE { w.append(.FREQUENCY_OFF) }
        if (result.tonality ?? 0.0) < MIN_TONALITY { w.append(.NOT_TONAL) }
        if result.clippedFraction > MAX_CLIPPED_FRACTION { w.append(.CLIPPING) }
        if implausible(calibratorOffset(nominalDb, result.rawLaeqDb)) { w.append(.IMPLAUSIBLE_OFFSET) }
        return w
    }

    public static func implausible(_ offsetDb: Double) -> Bool {
        abs(offsetDb - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB) > MAX_DEVIATION_FROM_DEFAULT_DB
    }
}
