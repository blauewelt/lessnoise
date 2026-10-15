import Foundation
@testable import LessNoiseCore

/** SplitMix64 (the Kotlin golden generator's PRNG, …/golden/Golden.kt). */
struct SplitMix64 {
    private var x: UInt64
    init(_ seed: Int) { x = UInt64(bitPattern: Int64(seed)) }

    mutating func next() -> UInt64 {
        x &+= 0x9E37_79B9_7F4A_7C15
        var z = x
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    /** Uniform in [0, 1): the top 53 bits. */
    mutating func nextDouble() -> Double { Double(next() >> 11) * (1.0 / Double(UInt64(1) << 53)) }
}

/** Rebuilds a GoldenSignal (…/golden/GoldenSignals.kt) from its spec: same operations, same order. */
func buildSignal(_ spec: JSONValue) -> [Float] {
    let fs = spec["fs"].int
    let total = spec["total_samples"].int
    var buf = [Double](repeating: 0, count: total)
    func range(_ p: JSONValue) -> Range<Int> {
        let a = JavaMath.toInt(p["start_s"].dbl * Double(fs))
        let n = JavaMath.toInt(p["seconds"].dbl * Double(fs))
        return a..<Swift.min(a + n, total)
    }
    for p in spec["parts"].arr {
        let r = range(p)
        switch p["type"].str {
        case "noise":
            var g = SplitMix64(p["seed"].int)
            let a = p["rms"].dbl * 3.0.squareRoot()
            for i in r { buf[i] += (g.nextDouble() * 2 - 1) * a }
        case "sine":
            let c = p["c"].dbl, s = p["s"].dbl, amp = p["amp"].dbl
            var x = 1.0, y = 0.0
            for i in r {
                buf[i] += amp * y
                let nx = x * c - y * s
                y = x * s + y * c
                x = nx
            }
        case "burst":
            let c = p["c"].dbl, s = p["s"].dbl
            var g = SplitMix64(p["seed"].int)
            let a = p["rms"].dbl * 3.0.squareRoot()
            var x = 1.0, y = 0.0
            for i in r {
                let env = (1 - x) / 2
                buf[i] += (g.nextDouble() * 2 - 1) * a * env
                let nx = x * c - y * s
                y = x * s + y * c
                x = nx
            }
        case "square":
            let amp = p["amp"].dbl, period = p["period"].int
            var k = 0
            for i in r {
                buf[i] = k % period < period / 2 ? amp : -amp
                k += 1
            }
        case "zeros":
            for i in r { buf[i] = 0 }
        default:
            fatalError("unknown part \(p)")
        }
    }
    return buf.map { Float($0) }
}

/** The simulated wall clock of the Kotlin tests (SimClock in TestSignals.kt). */
final class SimClock {
    private let startMs: Int
    private let rate: Double
    private let fs: Int
    private var elapsedMs = 0.0
    init(_ startMs: Int, rate: Double = 1.0, fs: Int = Acoustics.SAMPLE_RATE) { self.startMs = startMs; self.rate = rate; self.fs = fs }
    func advance(_ samples: Int) { elapsedMs += Double(samples) * 1000.0 / Double(fs) * rate }
    func now() -> Int { startMs + JavaMath.round(elapsedMs) }
}

/** Minimal mono 16-bit PCM WAV reader (Wav.read in Replay.kt): samples / 32768. */
func readWav(_ url: URL) throws -> ([Float], Int) {
    let b = [UInt8](try Data(contentsOf: url))
    func u32(_ p: Int) -> Int { Int(b[p]) | Int(b[p + 1]) << 8 | Int(b[p + 2]) << 16 | Int(b[p + 3]) << 24 }
    func i16(_ p: Int) -> Int16 { Int16(bitPattern: UInt16(b[p]) | UInt16(b[p + 1]) << 8) }
    precondition(String(bytes: b[0..<4], encoding: .ascii) == "RIFF" && String(bytes: b[8..<12], encoding: .ascii) == "WAVE")
    var pos = 12
    var rate = 0
    while pos + 8 <= b.count {
        let id = String(bytes: b[pos..<(pos + 4)], encoding: .ascii)!
        let size = u32(pos + 4)
        let body = pos + 8
        if id == "fmt " {
            precondition(i16(body) == 1 && i16(body + 2) == 1 && i16(body + 14) == 16, "only mono 16-bit PCM")
            rate = u32(body + 4)
        } else if id == "data" {
            let n = Swift.min(size, b.count - body) / 2
            return ((0..<n).map { Float(i16(body + 2 * $0)) / 32768 }, rate)
        }
        pos = body + size + (size & 1)
    }
    fatalError("no data chunk")
}

/** 16 → 48 kHz polyphase interpolation of the Kotlin replay (Upsampler3 in Replay.kt). */
enum Upsampler3 {
    static let taps = DecimatingResampler().taps

    static func process(_ x: [Float]) -> [Float] {
        let n = taps.count
        let delay = (n - 1) / 2
        var out = [Float](repeating: 0, count: x.count * 3)
        for o in 0..<out.count {
            let t = o + delay
            var acc = 0.0
            var j = t % 3
            while j < n {
                let m = (t - j) / 3
                if m >= 0 && m < x.count { acc += taps[j] * Double(x[m]) }
                j += 3
            }
            out[o] = Float(3 * acc)
        }
        return out
    }
}

/** Collects everything the engine reports. */
final class Recorder: MeasurementEngineListener {
    var minutes: [MinuteRecord] = []
    var events: [(NoiseEvent, Int, Int)] = []
    var ticks: [LafTick] = []
    var seconds: [SecondResult] = []
    var log: [String] = []
    var closed: [(Int, Int, EventFeatures)] = []
    let keepTicks: Bool
    init(keepTicks: Bool) { self.keepTicks = keepTicks }

    func onMinute(_ minute: MinuteRecord) { minutes.append(minute) }
    func onEventEmitted(_ event: NoiseEvent, _ startSample: Int, _ endSample: Int) { events.append((event, startSample, endSample)) }
    func onTick(_ tick: LafTick) { if keepTicks { ticks.append(tick) } }
    func onSecond(_ second: SecondResult) { if keepTicks { seconds.append(second) } }
    func onClockCorrection(_ correctionMs: Int) { log.append("correction \(correctionMs)") }
    func onClockAnchored(_ anchorSample: Int, _ anchorEpochMs: Int) { log.append("anchor \(anchorSample) \(anchorEpochMs)") }
    func onEventCandidate(_ startSample: Int) { log.append("candidate \(startSample)") }
    func onEventConfirmed(_ startSample: Int) { log.append("confirmed \(startSample)") }
    func onEventDiscarded(_ startSample: Int) { log.append("discarded \(startSample)") }
    func onEventClosed(_ startSample: Int, _ endSample: Int, _ features: EventFeatures) {
        log.append("closed \(startSample) \(endSample)")
        closed.append((startSample, endSample, features))
    }
}

/** The app's classifier mapping (android/app/src/main/assets), as the Kotlin tests load it. */
func appMapper() throws -> CategoryMapper {
    let assets = Fixtures.repoFile("android/app/src/main/assets")
    let labels = CategoryMapper.parseLabels(try String(contentsOf: assets.appendingPathComponent("yamnet_labels.txt"), encoding: .utf8))
    return try CategoryMapper.fromJson(try String(contentsOf: assets.appendingPathComponent("categories.json"), encoding: .utf8), labels: labels)
}

/** The fake classifier scores of the Kotlin generator (DspGoldenTest.fakeScores). */
func fakeScores(_ m: CategoryMapper, _ k: Int) -> [Float] {
    var r = SplitMix64(1000 + k)
    var s = (0..<m.labels.count).map { _ in Float(r.nextDouble() * 0.3) }
    switch k % 3 {
    case 0: s[m.labels.firstIndex(of: "Motorcycle")!] = 0.6
    case 1: s[m.labels.firstIndex(of: "Car")!] = 0.5
    default: break
    }
    return s
}
