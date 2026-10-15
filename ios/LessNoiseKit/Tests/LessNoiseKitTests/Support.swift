import Foundation
import XCTest
@testable import LessNoiseKit

/**
 * A clock only the test moves ([advance]). [sleep] does not move it: it waits a short real time
 * (1000× faster, at most 1 ms), so a loop that waits for a simulated time sees it when the test
 * has advanced the clock that far.
 */
final class FakeClock: AppClock {
    private let lock = NSLock()
    private var now: Int
    init(_ start: Int = 1_760_000_000_000) { now = start }
    func nowMs() -> Int { lock.lock(); defer { lock.unlock() }; return now }
    func advance(_ ms: Int) { lock.lock(); now += ms; lock.unlock() }
    func sleep(ms: Int) async throws {
        try await Task.sleep(nanoseconds: UInt64(Swift.min(Swift.max(ms, 1), 1000)) * 1_000)
    }
}

/** A microphone the test feeds by hand (48 kHz mono blocks). */
final class FakeAudioInput: AudioInput {
    var handler: AudioInputHandler?
    var startError: Error?
    var starts = 0
    var stops = 0
    func start(_ handler: AudioInputHandler) throws -> String {
        if let e = startError { throw e }
        starts += 1
        self.handler = handler
        return "fake 48000 Hz"
    }
    func stop() { stops += 1; handler = nil }
}

/** Deterministic noise (SplitMix64), amplitude [amp]. */
struct Noise {
    var state: UInt64
    init(_ seed: UInt64 = 42) { state = seed }
    mutating func next() -> Float {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z = z ^ (z >> 31)
        return Float(Double(z >> 11) / Double(1 << 53)) * 2 - 1
    }
    mutating func block(_ n: Int, amp: Float) -> [Float] { (0..<n).map { _ in next() * amp } }
}

/**
 * Feeds [seconds] of noise in 100 ms blocks to the rig's microphone, advancing the clock by 100 ms
 * per block and waiting for the engine after each (so the engine reads exactly the block's
 * delivery time); [amp] may vary per block (loud bursts).
 */
func feed(_ rig: Rig, seconds: Double, noise: inout Noise, amp: (Int) -> Float = { _ in 0.01 }) {
    let blocks = Int(seconds * 10)
    for b in 0..<blocks {
        rig.clock.advance(100)
        let samples = noise.block(4800, amp: amp(b))
        samples.withUnsafeBufferPointer { rig.input.handler?.audioBlock($0) }
        rig.services.session.drain()
    }
}

/** A minimal stand-in for the server of server/DESIGN.md §4 (register, site, minutes, events, reports, delete). */
final class FakeServer: UploadTransport {
    struct Recorded { let method: String; let path: String; let json: String? }
    private let lock = NSLock()
    private var _requests: [Recorded] = []
    var hosts: [String] = []
    var status429 = false
    private var tokens: [String: String] = [:]
    private var next = 1
    var minutes: [String: JSONValue] = [:]
    var events: [String: JSONValue] = [:]
    var reports: [JSONValue] = []

    var requests: [Recorded] { lock.lock(); defer { lock.unlock() }; return _requests }

    func send(_ r: UploadRequest) async throws -> UploadResponse { try handle(r) }

    private func handle(_ r: UploadRequest) throws -> UploadResponse {
        lock.lock(); defer { lock.unlock() }
        _requests.append(Recorded(method: r.method, path: r.path, json: r.json))
        hosts.append(URL(string: r.url)?.host ?? "?")
        if status429 { return UploadResponse(status: 429, body: #"{"detail":"slow down"}"#, retryAfter: "60") }
        if r.method == "POST" && r.path == "/v1/devices" {
            let id = "dev-\(next)", token = "tok-\(next)"
            next += 1
            tokens[id] = token
            return UploadResponse(status: 201, body: #"{"device_id":"\#(id)","token":"\#(token)"}"#)
        }
        let parts = r.path.split(separator: "/").map(String.init) // v1 devices id kind
        guard parts.count >= 3, let t = tokens[parts[2]], r.headers.contains(where: { $0.0 == "Authorization" && $0.1 == "Bearer \(t)" }) else {
            return UploadResponse(status: 401, body: #"{"detail":"invalid"}"#)
        }
        let kind = parts.count > 3 ? parts[3] : ""
        let body = try r.json.map { try JSONValue.parse($0) }
        switch (r.method, kind) {
        case ("PUT", "site"): return UploadResponse(status: 204, body: "")
        case ("POST", "minutes"):
            for m in body?.arrayValue ?? [] { minutes[m.member("start")!.stringValue!] = m }
            return UploadResponse(status: 200, body: #"{"accepted":\#(body?.arrayValue?.count ?? 0),"duplicates":0,"rejected":[]}"#)
        case ("POST", "events"):
            for e in body?.arrayValue ?? [] { events[e.member("start")!.stringValue!] = e }
            return UploadResponse(status: 200, body: #"{"accepted":\#(body?.arrayValue?.count ?? 0),"duplicates":0,"rejected":[]}"#)
        case ("POST", "reports"):
            reports += body?.arrayValue ?? []
            return UploadResponse(status: 200, body: #"{"accepted":\#(body?.arrayValue?.count ?? 0),"duplicates":0,"rejected":[]}"#)
        case ("DELETE", ""):
            tokens[parts[2]] = nil
            return UploadResponse(status: 204, body: "")
        default: return UploadResponse(status: 404, body: #"{"detail":"not found"}"#)
        }
    }
}

final class FakeLocator: Locator {
    var authorization: LocationAuthorization = .precise
    var afterPrompt: LocationAuthorization = .precise
    var result: FixResult = .fix(RawFix(lat: 47.37690, lon: 8.54170, accuracyM: 12))
    var fixes = 0
    var prompts = 0
    func requestAuthorization(_ done: @escaping () -> Void) {
        prompts += 1
        if authorization == .notDetermined { authorization = afterPrompt }
        done()
    }
    func oneFix(timeoutMs: Int, goodEnoughM: Double) async -> FixResult { fixes += 1; return result }
}

final class FakeNotifier: LoudAlertNotifier {
    var shown: [LiveEvent] = []
    func show(_ e: LiveEvent) { shown.append(e) }
}

final class FakeNetwork: NetworkStatus {
    var online = true
    var expensive = false
}

final class FakeScheduler: BackgroundUploadScheduler {
    var scheduled: [Int] = []
    var cancels = 0
    func schedule(earliestInMs: Int) { scheduled.append(earliestInMs) }
    func cancel() { cancels += 1 }
}

/** Everything wired with fakes. */
final class Rig {
    let clock = FakeClock()
    let input = FakeAudioInput()
    let calInput = FakeAudioInput()
    let locator = FakeLocator()
    let server = FakeServer()
    let network = FakeNetwork()
    let notifier = FakeNotifier()
    let scheduler = FakeScheduler()
    let kv = MemoryKeyValueStore()
    let secrets = MemorySecretStore()
    let services: AppServices

    init(classifier: SoundClassifier? = nil, mapper: CategoryMapper? = nil) throws {
        services = try AppServices(PlatformAdapters(
            database: try AppDatabase.inMemory(), keyValues: kv, secrets: secrets, measureInput: input, calibrationInput: calInput,
            locator: locator, transport: server, network: network, notifier: notifier, scheduler: scheduler,
            activity: NoBackgroundActivity(), classifier: classifier, mapper: mapper, deviceModel: "iPhone16,1",
            appVersion: "0.1.0", appBuild: 1, clock: clock
        ))
    }
}

/** Waits (really) until [condition] holds, at most [seconds]. */
func eventually(_ seconds: Double = 5, _ condition: () -> Bool) -> Bool {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end {
        if condition() { return true }
        Thread.sleep(forTimeInterval: 0.01)
    }
    return condition()
}

/** A minute with plain values (tests). */
func minute(_ start: Int, laeq: Double = 50, calibrationId: Int? = nil, offset: Double = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB,
            source: String = AudioSource.IOS_MEASUREMENT, valid: Double = 60) -> MinuteRecord {
    MinuteRecord(
        startEpochMs: start, startIso: Iso.format(start, TimeZone(identifier: "Europe/Zurich")!), durationSeconds: 60, laeqDb: laeq,
        lafMaxDb: laeq + 15, lafMinDb: laeq - 8, l1Db: laeq + 12, l10Db: laeq + 5, l50Db: laeq - 1, l90Db: laeq - 5, eventCount: 2,
        dominantCategory: "road_traffic", categoryShares: OrderedDict([("road_traffic", 0.75), ("unclassified", 0.25)]), classifierFrames: 58,
        calibrationId: calibrationId, calibrationOffsetDb: offset, audioSource: source, calibrated: calibrationId != nil,
        validSeconds: valid, localFloorDb: laeq - 4, windEventCount: 1
    )
}

func event(_ start: Int, lafMax: Double = 80, category: String? = "loud_vehicle", wind: Bool = false, calibrationId: Int? = nil) -> NoiseEvent {
    NoiseEvent(
        startEpochMs: start, startIso: Iso.format(start, TimeZone(identifier: "Europe/Zurich")!, millis: true), durationSeconds: 4.5,
        lafMaxDb: lafMax, selDb: lafMax + 3, backgroundDb: .nan, thresholdDb: 6.5, dominantCategory: category, dominantScore: 0.62,
        topLabels: [LabelScore(label: "Motorcycle", score: 0.62), LabelScore(label: "Vehicle", score: 0.31)], classifierFrames: 4,
        calibrationId: calibrationId, audioSource: AudioSource.IOS_MEASUREMENT, calibrated: calibrationId != nil, minLevelDb: 30,
        features: EventFeatures(localFloorDb: 52, excessDb: lafMax - 52, riseS: 1.25, decayS: .nan, jaggedness: 0.4, midBandRiseDb: 9,
                                lfShare: 0.3, lfFlutterDb: 1.5, wind: wind, shape: "hump")
    )
}
