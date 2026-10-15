import Foundation

/** Windowed-sinc FIR design helpers (Resampler.kt). */
public enum FirDesign {
    /** Zeroth-order modified Bessel function of the first kind (series expansion). */
    public static func besselI0(_ x: Double) -> Double {
        var sum = 1.0
        var term = 1.0
        let half = x / 2.0
        var k = 1
        while k < 200 {
            term *= (half / Double(k)) * (half / Double(k))
            sum += term
            if term < 1e-17 * sum { break }
            k += 1
        }
        return sum
    }

    /** Linear-phase low-pass, Kaiser window, unity DC gain. */
    public static func kaiserLowpass(_ numTaps: Int, _ cutoffHz: Double, _ sampleRate: Double, _ beta: Double) -> [Double] {
        precondition(numTaps % 2 == 1, "use an odd number of taps (type I FIR)")
        let m = Double(numTaps - 1) / 2.0
        let fc = cutoffHz / sampleRate
        let i0b = besselI0(beta)
        var h = (0..<numTaps).map { n -> Double in
            let t = Double(n) - m
            let sinc = t == 0.0 ? 2 * fc : sin(2 * Double.pi * fc * t) / (Double.pi * t)
            let r = t / m
            let w = besselI0(beta * sqrt(Swift.max(0.0, 1 - r * r))) / i0b
            return sinc * w
        }
        // Kotlin's DoubleArray.sum(): left to right.
        var s = 0.0
        for v in h { s += v }
        for i in h.indices { h[i] /= s }
        return h
    }

    /** Magnitude response in dB of FIR [h] at [freqHz]. */
    public static func magnitudeDb(_ h: [Double], _ freqHz: Double, _ sampleRate: Double) -> Double {
        let w = 2 * Double.pi * freqHz / sampleRate
        var re = 0.0
        var im = 0.0
        for n in h.indices {
            re += h[n] * cos(w * Double(n))
            im -= h[n] * sin(w * Double(n))
        }
        return 20 * log10(Swift.max(hypot(re, im), 1e-30))
    }
}

/**
 * Streaming anti-aliasing low-pass + decimation by an integer factor (48 kHz → 16 kHz for the
 * classifier). Default filter: 241 taps, Kaiser β = 7, cutoff 7.5 kHz at 48 kHz.
 */
public final class DecimatingResampler {
    public let inputRate: Int
    public let factor: Int
    public let outputRate: Int
    public let taps: [Double]
    private let n: Int
    // Each sample is stored twice (at pos and pos + n) so the filter window is always contiguous.
    private var history: [Double]
    private var pos = 0
    private var phase = 0

    public init(inputRate: Int = Acoustics.SAMPLE_RATE, factor: Int = 3, numTaps: Int = 241, cutoffHz: Double = 7500.0, beta: Double = 7.0) {
        precondition(inputRate % factor == 0)
        self.inputRate = inputRate
        self.factor = factor
        outputRate = inputRate / factor
        taps = FirDesign.kaiserLowpass(numTaps, cutoffHz, Double(inputRate), beta)
        n = taps.count
        history = [Double](repeating: 0, count: 2 * n)
    }

    /** Processes [count] input samples and appends the decimated output to [out]. */
    public func process(_ input: [Float], _ count: Int, _ out: FloatRingBuffer) {
        for i in 0..<count {
            let x = Double(input[i])
            history[pos] = x
            history[pos + n] = x
            pos += 1
            if pos == n { pos = 0 }
            phase += 1
            if phase == factor {
                phase = 0
                // history[pos ..< pos+n] holds the last n samples, oldest first.
                var acc = 0.0
                var k = n - 1
                var j = pos
                while k >= 0 {
                    acc += taps[k] * history[j]
                    k -= 1; j += 1
                }
                out.write(Float(acc))
            }
        }
    }

    /** Convenience for tests: decimates a whole array. */
    public func processAll(_ input: [Float]) -> [Float] {
        let ring = FloatRingBuffer(capacity: input.count / factor + 1)
        process(input, input.count, ring)
        var out = [Float](repeating: 0, count: ring.available)
        _ = ring.copyLatest(&out, out.count)
        return out
    }

    public func reset() {
        for i in history.indices { history[i] = 0 }
        pos = 0; phase = 0
    }

    public func magnitudeDb(_ freqHz: Double) -> Double { FirDesign.magnitudeDb(taps, freqHz, Double(inputRate)) }

    /** Group delay in input samples. */
    public var delaySamples: Int { (n - 1) / 2 }
}

/**
 * Ring buffer holding the most recent decimated samples. Its capacity bounds how much audio is
 * ever held in memory for classification (privacy: ~1 s at 16 kHz, never written to disk).
 */
public final class FloatRingBuffer {
    public let capacity: Int
    private var data: [Float]
    private var next = 0
    public private(set) var available = 0

    public init(capacity: Int) {
        self.capacity = capacity
        data = [Float](repeating: 0, count: capacity)
    }

    public func write(_ v: Float) {
        data[next] = v
        next += 1
        if next == capacity { next = 0 }
        if available < capacity { available += 1 }
    }

    /** Copies the latest [count] samples (oldest first) into [dest]; false if not enough data. */
    public func copyLatest(_ dest: inout [Float], _ count: Int) -> Bool {
        if count > available || count > dest.count { return false }
        var src = next - count
        if src < 0 { src += capacity }
        for i in 0..<count {
            dest[i] = data[src]
            src += 1
            if src == capacity { src = 0 }
        }
        return true
    }

    public func clear() {
        for i in data.indices { data[i] = 0 }
        next = 0; available = 0
    }
}
