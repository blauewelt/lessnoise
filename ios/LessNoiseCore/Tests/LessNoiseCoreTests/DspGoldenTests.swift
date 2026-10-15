import Foundation
import Testing
@testable import LessNoiseCore

// Golden tests of the measuring core against the Kotlin implementation (fixtures from
// android/dsp/src/test/kotlin/ch/lessnoise/dsp/golden/). Tolerances (see ../../README.md):
// levels LEVEL_TOL dB, filter coefficients and responses COEF_TOL (relative to 1), times and
// counts exact, texts (ISO, CSV, categories, shapes) exact.

/** dB levels: everything the engine reports (LAF, LAeq, percentiles, SEL, floors). */
let LEVEL_TOL = 1e-6
/** Filter coefficients, gains and per-sample filter outputs. */
let COEF_TOL = 1e-12

@Suite struct FiltersGoldenTests {
    @Test func aWeightingButterworthDecimator() throws {
        let f = try Fixtures.load("filters.json")
        let dev = Deviations("filters")
        let freqs = f["frequencies"].dbls
        for (i, fr) in freqs.enumerated() {
            expectClose(AWeighting.analogDb(fr), f["analog_db"].dbls[i], COEF_TOL, "analog \(fr)", dev, key: "analog_dB")
        }
        let noise = buildSignal(f["noise_spec"])
        for r in f["a_weighting"].arr {
            let fs = r["fs"].int
            let a = AWeighting(sampleRate: fs)
            #expect(a.highPoleEffectiveHz == r["high_pole_effective_hz"].dbl, "fs \(fs): effective high pole \(a.highPoleEffectiveHz) vs \(r["high_pole_effective_hz"].dbl)")
            let (sections, gain) = a.coefficients
            for (k, s) in r["sections"].arr.enumerated() {
                for (c, v) in s.dbls.enumerated() { expectClose(sections[k][c], v, COEF_TOL, "fs \(fs) section \(k)[\(c)]", dev, key: "coefficient") }
            }
            expectClose(gain, r["gain"].dbl, COEF_TOL * abs(gain), "fs \(fs) gain", dev, key: "gain_rel")
            let mags = r["magnitude_db"].dbls
            for (i, m) in mags.enumerated() { expectClose(a.magnitudeDb(freqs[i]), m, 1e-9, "fs \(fs) |A(\(freqs[i]))|", dev, key: "magnitude_dB") }
            var imp = [Double](repeating: 0, count: 512)
            for i in 0..<512 { imp[i] = a.process(i == 0 ? 1 : 0) }
            for (i, v) in r["impulse_response"].dbls.enumerated() { expectClose(imp[i], v, COEF_TOL, "fs \(fs) impulse[\(i)]", dev, key: "impulse") }
            if fs == 48_000 {
                a.reset()
                for (i, v) in r["noise_output"].dbls.enumerated() {
                    expectClose(a.process(Double(noise[i])), v, COEF_TOL, "noise out[\(i)]", dev, key: "noise_out")
                }
            }
        }
        #expect(ZWeighting().magnitudeDb(1000) == f["z_weighting_magnitude_1k"].dbl)
        let fast = ExponentialTimeWeighting(tauSeconds: ExponentialTimeWeighting.FAST_TAU)
        var step: [Double] = []
        for i in 0..<12_000 { let v = fast.process(1.0); if i % 50 == 0 { step.append(v) } }
        for (i, v) in f["fast_alpha_step"].dbls.enumerated() { expectClose(step[i], v, COEF_TOL, "fast step \(i)", dev, key: "time_weighting") }
        let slow = ExponentialTimeWeighting(tauSeconds: ExponentialTimeWeighting.SLOW_TAU)
        var sstep: [Double] = []
        for i in 0..<4800 { let v = slow.process(i < 2400 ? 1.0 : 0.0); if i % 40 == 0 { sstep.append(v) } }
        for (i, v) in f["slow_step"].dbls.enumerated() { expectClose(sstep[i], v, COEF_TOL, "slow step \(i)", dev, key: "time_weighting") }
        let bw = f["butterworth"]
        let bws: [(String, Biquad)] = [
            ("lp_200_48k", Butterworth.lowpass(200, 48_000)), ("hp_20_48k", Butterworth.highpass(20, 48_000)),
            ("hp_250_48k", Butterworth.highpass(250, 48_000)), ("lp_4500_48k", Butterworth.lowpass(4500, 48_000)),
            ("lp_8000_48k", Butterworth.lowpass(8000, 48_000)), ("lp_3000_16k", Butterworth.lowpass(3000, 16_000)),
        ]
        for (name, b) in bws {
            for (c, v) in bw[name].dbls.enumerated() { expectClose([b.b0, b.b1, b.b2, b.a1, b.a2][c], v, COEF_TOL, "\(name)[\(c)]", dev, key: "coefficient") }
        }
        let ff = FeatureFilters(sampleRate: 48_000)
        let aw = AWeighting(sampleRate: 48_000)
        var k = 0
        let feat = f["feature_filters_every_7th"].arr
        for i in noise.indices {
            let x = Double(noise[i])
            ff.process(x, aw.process(x))
            if i % 7 == 0 {
                let e = feat[k].dbls
                expectClose(ff.band, e[0], COEF_TOL, "band[\(i)]", dev, key: "feature_filters")
                expectClose(ff.low, e[1], COEF_TOL, "low[\(i)]", dev, key: "feature_filters")
                expectClose(ff.total, e[2], COEF_TOL, "total[\(i)]", dev, key: "feature_filters")
                k += 1
            }
        }
        let dec = DecimatingResampler()
        for (i, v) in f["decimator_taps"].dbls.enumerated() { expectClose(dec.taps[i], v, COEF_TOL, "tap \(i)", dev, key: "fir_tap") }
        for (i, fr) in [1000.0, 7000.0, 7500.0, 8000.0, 12000.0].enumerated() {
            expectClose(dec.magnitudeDb(fr), f["decimator_magnitude_db"].dbls[i], 1e-9, "decimator |H(\(fr))|", dev, key: "magnitude_dB")
        }
        let dIn = buildSignal(f["decimator_input_spec"])
        let dOut = dec.processAll(dIn)
        let eOut = f["decimator_output"].dbls
        #expect(dOut.count == eOut.count)
        for (i, v) in eOut.enumerated() { expectClose(Double(dOut[i]), v, 1e-7, "decimated[\(i)]", dev, key: "decimated_float") }
        for c in f["classifier_normalize"].arr {
            var w = (0..<64).map { Float(Double(dIn[$0]) * c["scale"].dbl) }
            let g = ClassifierPreprocessor.normalize(&w)
            expectClose(g, c["gain_db"].dbl, 1e-9, "normalize gain", dev, key: "gain_dB")
            for (i, v) in c["window"].dbls.enumerated() { expectClose(Double(w[i]), v, 1e-7, "normalized[\(i)]", dev, key: "decimated_float") }
        }
        let silence: [[Float]] = [
            [Float](repeating: 0, count: 480),
            (0..<1000).map { (100..<579).contains($0) ? 0 : 1e-3 },
            (0..<1000).map { (100..<580).contains($0) ? 0 : 1e-3 },
            [Float](repeating: 1e-8, count: 100), [Float](repeating: 1e-6, count: 100), [],
        ]
        for (i, b) in f["silence_detector"].arr.enumerated() { #expect(SilenceDetector.isDigitalSilence(silence[i]) == b.bool, "silence case \(i)") }
        dev.report()
    }
}

@Suite struct PercentilesGoldenTests {
    @Test func percentilesQuickselectFloors() throws {
        let f = try Fixtures.load("percentiles.json")
        let ps = [0.0, 1.0, 10.0, 25.0, 50.0, 90.0, 99.0, 100.0]
        for c in f["cases"].arr {
            let a = c["values"].dbls
            let n = a.count
            for (i, p) in ps.enumerated() {
                #expect(Percentiles.percentileSorted(a.sorted(), p) == c["percentile_sorted"].dbls[i], "n=\(n) p=\(p)")
                #expect(Percentiles.exceedanceLevel(a, p) == c["exceedance"].dbls[i], "n=\(n) L\(p)")
                var b = a
                #expect(QuickSelect.percentile(&b, n, p) == c["quickselect"].dbls[i], "n=\(n) quickselect p=\(p)")
            }
            let ks = Array(stride(from: 0, to: n, by: Swift.max(1, n / 9)))
            for (i, k) in ks.enumerated() {
                var b = a
                #expect(QuickSelect.select(&b, n, k) == c["select_k"].dbls[i], "n=\(n) select \(k)")
            }
            let s = Percentiles.stats(a)
            #expect([s.min, s.max, s.l1, s.l10, s.l50, s.l90] == c["stats"].dbls, "n=\(n) stats")
        }
        let seq = f["floor_input"].dbls
        let lf = LocalFloorEstimator(windowSeconds: 30, minHistorySeconds: 5, tickSeconds: 0.125)
        let bg = BackgroundEstimator(windowSeconds: 10, minHistorySeconds: 2, tickSeconds: 0.125)
        for (i, v) in seq.enumerated() {
            lf.add(v); bg.add(v)
            #expect(sameDouble(lf.value, f["local_floor_30s_5s"].dbls[i]), "local floor \(i)")
            #expect(sameDouble(bg.l90(), f["background_10s_2s"].dbls[i]), "background \(i)")
        }
        #expect(abs(Acoustics.energyAverage([40, 50, 60], [1, 2, 0.5]) - f["energy_average"].dbl) <= 1e-12)
        #expect(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB == f["default_offset"].dbl)
    }
}

/** Runs one engine case of engine.json exactly as DspGoldenTest.runCase does. */
func runEngineCase(_ c: JSONValue) throws -> (Recorder, [(Int, Double, Float, Float)]) {
    let cfg = c["config"]
    let zone = TimeZone(identifier: cfg["zone"].str)!
    let classifier = c["classifier"].bool
    let mapper = classifier ? try appMapper() : nil
    let config = EngineConfig(
        sampleRate: cfg["sample_rate"].int, calibrationOffsetDb: cfg["calibration_offset_db"].dbl, calibrated: cfg["calibrated"].bool,
        calibrationId: cfg["calibration_id"].isNull ? nil : cfg["calibration_id"].int, audioSource: cfg["audio_source"].str,
        eventExcessDb: cfg["event_excess_db"].dbl, eventMaxDurationSeconds: cfg["event_max_duration_s"].dbl,
        eventMinLevelDb: cfg["event_min_level_db"].dbl, classifierEnabled: cfg["classifier_enabled"].bool, zone: zone
    )
    let clock = SimClock(c["start_ms"].int, rate: c["clock_rate"].dbl)
    let rec = Recorder(keepTicks: c.member("ticks") != nil)
    let engine = MeasurementEngine(config: config, mapper: mapper, listener: rec, wallClock: clock.now)
    let x = buildSignal(c["signal"])
    let block = c["block"].int
    let silenced = c["silenced"].arr.map { $0[0].int..<$0[1].int }
    var windows: [(Int, Double, Float, Float)] = []
    var p = 0
    var frame = 0
    var buf = [Float](repeating: 0, count: block)
    while p < x.count {
        let n = Swift.min(block, x.count - p)
        for i in 0..<n { buf[i] = x[p + i] }
        engine.setMicSilenced(silenced.contains { $0.contains(p) })
        clock.advance(n)
        engine.process(buf, n)
        if let m = mapper, engine.classifierDue() {
            var w = [Float](repeating: 0, count: ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
            let end = engine.copyClassifierWindow(&w)
            var e = 0.0
            for v in w { e += Double(v) * Double(v) }
            windows.append((end, e, w[0], w[w.count - 1]))
            engine.onClassifierFrame(ClassifierFrame(endSample: end, windowSamples48k: engine.classifierWindowLength48k, scores: fakeScores(m, frame)))
            frame += 1
        }
        p += n
    }
    engine.stop()
    return (rec, windows)
}

@Suite struct EngineGoldenTests {
    @Test(arguments: ["tone_on_noise", "bursts", "invalid_audio", "clipping_dst", "clock_drift", "classifier", "max_duration"])
    func engineCase(_ name: String) throws {
        let f = try Fixtures.load("engine.json")
        guard let c = f["cases"].arr.first(where: { $0["name"].str == name }) else { Issue.record("case \(name) missing"); return }
        let dev = Deviations("engine/\(name)")
        let (rec, windows) = try runEngineCase(c)
        let em = c["minutes"].arr
        #expect(rec.minutes.count == em.count, "\(name): \(rec.minutes.count) minutes vs \(em.count)")
        for (i, m) in zip(rec.minutes, em).enumerated() { expectMinute(m.0, m.1, LEVEL_TOL, dev, "\(name) minute \(i)") }
        let ee = c["events"].arr
        #expect(rec.events.count == ee.count, "\(name): \(rec.events.count) events vs \(ee.count)")
        for (i, e) in zip(rec.events, ee).enumerated() {
            expectEvent(e.0.0, e.1["event"], LEVEL_TOL, dev, "\(name) event \(i)")
            #expect(e.0.1 == e.1["start_sample"].int && e.0.2 == e.1["end_sample"].int, "\(name) event \(i) samples")
        }
        #expect(rec.log == c["log"].arr.map { $0.str }, "\(name): lifecycle log (candidates, confirmations, closes, clock anchors)")
        let ew = c["classifier_windows"].arr
        #expect(windows.count == ew.count, "\(name): classifier windows")
        for (w, e) in zip(windows, ew) {
            #expect(w.0 == e["end_sample"].int, "window end")
            expectClose(w.1, e["energy"].dbl, 1e-9 * Swift.max(1, abs(e["energy"].dbl)), "window energy", dev, key: "window_energy")
            expectClose(Double(w.2), e["first"].dbl, 1e-7, "window first", dev, key: "decimated_float")
            expectClose(Double(w.3), e["last"].dbl, 1e-7, "window last", dev, key: "decimated_float")
        }
        if let ticks = c.member("ticks") {
            #expect(rec.ticks.count == ticks.arr.count, "\(name) ticks")
            for (t, e) in zip(rec.ticks, ticks.arr) {
                #expect(t.endSample == e[0].int && t.epochMs == e[1].int && t.valid == e[5].bool, "tick \(t.endSample)")
                expectClose(t.lafDb, e[2].dbl, LEVEL_TOL, "LAF \(t.endSample)", dev, key: "level_dB")
                expectClose(t.lafMaxDb, e[3].dbl, LEVEL_TOL, "LAFmax \(t.endSample)", dev, key: "level_dB")
                expectClose(t.leqDb, e[4].dbl, LEVEL_TOL, "Leq \(t.endSample)", dev, key: "level_dB")
            }
            let secs = c["seconds"].arr
            #expect(rec.seconds.count == secs.count, "\(name) seconds")
            for (s, e) in zip(rec.seconds, secs) {
                let v = e.dbls
                #expect(s.endSample == Int(v[0]) && s.epochMs == Int(v[1]), "second \(s.endSample)")
                for (k, a) in [s.laeqDb, s.lafMaxDb, s.lzeqDb, s.laeqRunning60sDb, s.backgroundDb].enumerated() {
                    expectClose(a, v[2 + k], LEVEL_TOL, "second \(s.endSample) [\(k)]", dev, key: "level_dB")
                }
                #expect(s.validFraction == v[7])
                expectClose(s.localFloorDb, v[8], LEVEL_TOL, "second floor", dev, key: "level_dB")
            }
        }
        let buckets = c["classifier"].bool ? try appMapper().bucketIds : []
        #expect(Csv.minutes(rec.minutes, buckets) == c["csv_minutes"].str, "\(name): minutes CSV")
        #expect(Csv.events(rec.events.map { $0.0 }) == c["csv_events"].str, "\(name): events CSV")
        dev.report()
    }
}

@Suite struct ReplayGoldenTests {
    /** The 0.05 tolerance of ReplayTest.assertCsvMatches (rounding of the last digit), text exact. */
    func csvMatches(_ expected: String, _ actual: String) -> Bool {
        let e = expected.split(separator: "\n", omittingEmptySubsequences: false).map(String.init).filter { !$0.isEmpty }
        let a = actual.split(separator: "\n", omittingEmptySubsequences: false).map(String.init).filter { !$0.isEmpty }
        guard e.count == a.count, e.first == a.first else { return false }
        for (x, y) in zip(e.dropFirst(), a.dropFirst()) {
            let xf = x.split(separator: ",", omittingEmptySubsequences: false), yf = y.split(separator: ",", omittingEmptySubsequences: false)
            guard xf.count == yf.count else { return false }
            for (p, q) in zip(xf, yf) {
                if let u = Double(p), let v = Double(q) { if abs(u - v) > 0.05 + 1e-9 { return false } } else if p != q { return false }
            }
        }
        return true
    }

    @Test(arguments: ["hump", "wind_thump", "steady_tone"])
    func replay(_ name: String) throws {
        let f = try Fixtures.load("replay.json")
        let item = f["items"].arr.first { $0["name"].str == name }!
        let dev = Deviations("replay/\(name)")
        let wavURL = Fixtures.repoFile("android/dsp/src/test/resources/replay/\(name).wav")
        let digest = SHA256Hex.of([UInt8](try Data(contentsOf: wavURL)))
        #expect(digest == item["wav_sha256"].str, "\(name).wav differs from the one the fixture was made from")
        let (x, rate) = try readWav(wavURL)
        #expect(rate == 16_000)
        let up = Upsampler3.process(x)
        #expect(up.count == item["upsampled_count"].int)
        for (i, v) in item["upsampled_head"].dbls.enumerated() { expectClose(Double(up[i]), v, 1e-7, "up[\(i)]", dev, key: "upsampled_float") }
        for (i, v) in item["upsampled_mid"].dbls.enumerated() { expectClose(Double(up[up.count / 2 + i]), v, 1e-7, "up[mid+\(i)]", dev, key: "upsampled_float") }

        // Replay.config(): zone Europe/Zurich, classifier off, defaults otherwise.
        let config = EngineConfig(classifierEnabled: false, zone: TimeZone(identifier: "Europe/Zurich")!)
        let clock = SimClock(item["start_ms"].int)
        let rec = Recorder(keepTicks: true)
        let engine = MeasurementEngine(config: config, mapper: nil, listener: rec, wallClock: clock.now)
        var p = 0
        while p < up.count {
            let n = Swift.min(6000, up.count - p)
            clock.advance(n)
            engine.process(Array(up[p..<(p + n)]), n)
            p += n
        }
        engine.stop()
        let minutes = rec.minutes.sorted { $0.startEpochMs < $1.startEpochMs }
        let events = rec.events.map { $0.0 }
        #expect(events.count == item["events"].arr.count)
        for (i, e) in zip(events, item["events"].arr).enumerated() { expectEvent(e.0, e.1, LEVEL_TOL, dev, "\(name) event \(i)") }
        #expect(minutes.count == item["minutes"].arr.count)
        for (i, m) in zip(minutes, item["minutes"].arr).enumerated() { expectMinute(m.0, m.1, LEVEL_TOL, dev, "\(name) minute \(i)") }
        #expect(rec.closed.count == item["closed"].arr.count)
        for (c, e) in zip(rec.closed, item["closed"].arr) {
            #expect(c.0 == e["start_sample"].int && c.1 == e["end_sample"].int)
            expectFeatures(c.2, e["features"], LEVEL_TOL, dev, "\(name) closed")
        }
        #expect(rec.ticks.count == item["ticks"].arr.count)
        for (t, e) in zip(rec.ticks, item["ticks"].arr) {
            #expect(t.endSample == e[0].int && t.epochMs == e[1].int && t.valid == e[5].bool)
            expectClose(t.lafDb, e[2].dbl, LEVEL_TOL, "LAF", dev, key: "level_dB")
        }
        // The Kotlin run's CSV text, exactly; and the committed expected CSVs of ReplayTest (±0.05).
        #expect(Csv.events(events) == item["csv_events"].str, "\(name) events CSV")
        #expect(Csv.minutes(minutes, []) == item["csv_minutes"].str, "\(name) minutes CSV")
        let expDir = Fixtures.repoFile("android/dsp/src/test/resources/replay/expected")
        let expEvents = try String(contentsOf: expDir.appendingPathComponent("\(name).events.csv"), encoding: .utf8)
        let expMinutes = try String(contentsOf: expDir.appendingPathComponent("\(name).minutes.csv"), encoding: .utf8)
        #expect(csvMatches(expEvents, Csv.events(events)), "\(name): replay/expected/\(name).events.csv")
        #expect(csvMatches(expMinutes, Csv.minutes(minutes, [])), "\(name): replay/expected/\(name).minutes.csv")
        dev.report()
    }
}

@Suite struct FeaturesGoldenTests {
    @Test func trackerOnCraftedTicks() throws {
        let f = try Fixtures.load("features.json")
        let dev = Deviations("features")
        let laf = f["laf"].dbls
        var r = SplitMix64(f["seed"].int)
        for _ in laf { _ = r.nextDouble() } // the 150 base draws of the generator
        let t = EventFeatureTracker(capacityTicks: 200, preTicks: 80)
        for v in laf {
            if v.isNaN { t.pushInvalid() } else {
                let a = 1e-7 * (1 + r.nextDouble()), b = 2e-8 * (1 + r.nextDouble()), c = 5e-8 * (1 + r.nextDouble()), d = 1e-7 * (1 + r.nextDouble())
                t.push(v, a, b, c, d)
            }
        }
        for c in f["cases"].arr {
            let s = c["start"].int, e = c["end"].int
            let got = t.compute(start: s, end: e, tailEnd: c["tail_end"].int, floorDb: 40.5, lafMaxDb: 56.0, durationS: Double(e - s) * 0.125, wind: WindRule())
            expectFeatures(got, c["features"], LEVEL_TOL, dev, "span \(s)..<\(e)")
        }
        let shapes: [(Double, Double, Double)] = [(31, 1, 0.1), (1.5, 0.2, 0.5), (3, 0.2, 0.5), (3, 1, 0.1), (2, 0.35, 0.3), (2, .nan, .nan)]
        #expect(shapes.map { EventShape.classify($0.0, $0.1, $0.2) } == f["shapes"].arr.map { $0.str })
        let w = WindRule()
        let winds: [(Double, Double)] = [(0.93, 0), (0.92, 4.5), (0.5, 4.4), (.nan, .nan), (.nan, 5)]
        #expect(winds.map { w.isWind($0.0, $0.1) } == f["wind"].arr.map { $0.bool })
        dev.report()
    }
}

/** SHA-256 hex via the same Crypto API the core uses. */
enum SHA256Hex {
    static func of(_ b: [UInt8]) -> String { sha256Hex(b) }
}
