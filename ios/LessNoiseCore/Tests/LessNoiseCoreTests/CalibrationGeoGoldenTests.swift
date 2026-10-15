import Foundation
import Testing
@testable import LessNoiseCore

/** Coordinates before rounding: metres or degrees. */
let COORD_TOL = 1e-6

@Suite struct CalibrationGoldenTests {
    @Test func measurementsWarningsRecalibration() throws {
        let f = try Fixtures.load("calibration.json")
        let dev = Deviations("calibration")
        for r in f["runs"].arr {
            let name = r["name"].str
            let m = CalibrationMeasurement(durationSeconds: r["duration_s"].int, analyzeTone: r["analyze_tone"].bool)
            let x = buildSignal(r["signal"])
            let invalidateAt = r["invalidate_at"].isNull ? nil : r["invalidate_at"].int
            var p = 0
            while p < x.count {
                let n = Swift.min(4800, x.count - p)
                if let at = invalidateAt, p >= at { m.invalidate() }
                m.process(Array(x[p..<(p + n)]), n)
                p += n
            }
            let res = m.result()
            #expect(m.invalidated == r["invalidated"].bool && m.isComplete == r["complete"].bool, "\(name) state")
            expectClose(res.rawLaeqDb, r["raw_laeq_db"].dbl, LEVEL_TOL, "\(name) raw LAeq", dev, key: "level_dB")
            expectClose(res.rawLzeqDb, r["raw_lzeq_db"].dbl, LEVEL_TOL, "\(name) raw LZeq", dev, key: "level_dB")
            let sl = r["second_levels"].dbls
            #expect(res.secondLevelsRawDb.count == sl.count, "\(name) seconds")
            for (a, b) in zip(res.secondLevelsRawDb, sl) { expectClose(a, b, LEVEL_TOL, "\(name) second level", dev, key: "level_dB") }
            expectClose(res.stdDevDb, r["std_dev_db"].dbl, LEVEL_TOL, "\(name) std dev", dev, key: "level_dB")
            #expect(res.measuredSeconds == r["measured_s"].dbl)
            if let t = res.toneFrequencyHz, let e = r["tone_hz"].dblOrNil { expectClose(t, e, 1e-9, "\(name) tone", dev, key: "tone_Hz") } else {
                #expect(res.toneFrequencyHz == nil && r["tone_hz"].isNull, "\(name) tone presence")
            }
            if let t = res.tonality, let e = r["tonality"].dblOrNil { expectClose(t, e, 1e-12, "\(name) tonality", dev, key: "ratio") } else {
                #expect(res.tonality == nil && r["tonality"].isNull, "\(name) tonality presence")
            }
            #expect(res.clippedFraction == r["clipped_fraction"].dbl, "\(name) clipped")
            #expect(CalibrationMath.referenceWarnings(res, 94).map { $0.rawValue } == r["reference_warnings_94"].arr.map { $0.str }, "\(name) ref 94")
            #expect(CalibrationMath.referenceWarnings(res, 45).map { $0.rawValue } == r["reference_warnings_45"].arr.map { $0.str }, "\(name) ref 45")
            #expect(CalibrationMath.calibratorWarnings(res, 94).map { $0.rawValue } == r["calibrator_warnings_94"].arr.map { $0.str }, "\(name) calibrator")
            expectClose(CalibrationMath.referenceOffset(94, res.rawLaeqDb), r["reference_offset_94"].dbl, LEVEL_TOL, "\(name) offset", dev, key: "level_dB")
        }
        for t in f["tone_frames"].arr {
            let frame = buildSignal(t["signal"]).map { Double($0) }
            let tone = ToneAnalyzer.analyze(frame, 48_000)
            expectClose(tone.frequencyHz, t["frequency_hz"].dbl, 1e-9, "tone frequency", dev, key: "tone_Hz")
            expectClose(tone.tonality, t["tonality"].dbl, 1e-12, "tonality", dev, key: "ratio")
        }
        var re = f["fft_in"][0].dbls, im = f["fft_in"][1].dbls
        Fft.transform(&re, &im)
        for (a, b) in zip(re + im, f["fft_out"][0].dbls + f["fft_out"][1].dbls) { expectClose(a, b, 1e-12, "fft", dev, key: "fft") }
        #expect(CalibrationMath.stdDev([40.1, 41.3, 39.8, 40.0]) == f["std_dev"].dbl)
        #expect([92.0, 92.36, 132.0, 132.4, 112.35].map { CalibrationMath.implausible($0) } == f["implausible"].arr.map { $0.bool })

        let rc = f["recalibration"]
        func target(_ j: JSONValue) -> Recalibration.Target { Recalibration.Target(calibrationId: j[0].int, offsetDb: j[1].dbl, audioSource: j[2].str) }
        let tA = target(rc["target_a"]), tB = target(rc["target_b"])
        let m0 = decodeMinute(rc["minute_default"])
        let mA = Recalibration.recalibrate(m0, tA)
        expectMinute(mA, rc["minute_default_a"], 1e-12, dev, "default→A")
        expectMinute(Recalibration.recalibrate(mA, tB), rc["minute_default_a_b"], 1e-12, dev, "default→A→B")
        expectMinute(Recalibration.recalibrate(m0, tB), rc["minute_default_b"], 1e-12, dev, "default→B")
        let m1 = decodeMinute(rc["minute_cal11"])
        let m1B = Recalibration.recalibrate(m1, tB)
        expectMinute(m1B, rc["minute_cal11_b"], 1e-12, dev, "11→B")
        expectMinute(Recalibration.recalibrate(m1B, tA), rc["minute_cal11_b_a"], 1e-12, dev, "11→B→A (restore)")
        let e0 = decodeEvent(rc["event_default"])
        let eA = Recalibration.recalibrate(e0, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, tA)
        expectEvent(eA, rc["event_default_a"], 1e-12, dev, "event default→A")
        expectEvent(Recalibration.recalibrate(eA, 999.0, tB), rc["event_default_a_b"], 1e-12, dev, "event default→A→B")
        expectEvent(Recalibration.recalibrate(decodeEvent(rc["event_cal11"]), 121.9, tA), rc["event_cal11_a"], 1e-12, dev, "event 11→A")
        #expect([
            Recalibration.inScope("UNPROCESSED", nil, tA), Recalibration.inScope("UNPROCESSED", 11, tA),
            Recalibration.inScope("VOICE_RECOGNITION", 3, tA), Recalibration.inScope("UNPROCESSED", 12, tA),
        ] == rc["in_scope"].arr.map { $0.bool })
        for c in f["event_floor"].arr {
            let i = c["in"].dbls
            let a = EventFloor.adjust(i[0], i[1], i[2], i[3], i[4])
            #expect(a.unclampedFloorDb == c["unclamped"].dbl && a.newFloorDb == c["new"].dbl, "floor \(i)")
            #expect(a.deltaDb == c["delta"].dbl && a.clamped == c["clamped"].bool && a.changed == c["changed"].bool, "floor flags \(i)")
        }
        for s in f["signed"].arr {
            #expect(EventFloor.signed(s[0].dbl) == s[1].str, "signed \(s[0].dbl)")
            #expect(EventFloor.signed(s[0].dbl, decimals: 2) == s[2].str, "signed2 \(s[0].dbl)")
        }
        #expect([30.24, 30.25, 30.74, 30.75, -1.25, 0.0].map { EventFloor.roundToHalf($0) } == f["round_to_half"].dbls)
        dev.report()
    }
}

@Suite struct GeoGoldenTests {
    @Test func lv95AndCells() throws {
        let f = try Fixtures.load("geo.json")
        let dev = Deviations("geo")
        for r in f["reference"].arr {
            let en = Lv95.wgs84ToLv95(r["lat"].dbl, r["lon"].dbl)
            expectClose(en.e, r["to_lv95"][0].dbl, COORD_TOL, "E", dev, key: "metres")
            expectClose(en.n, r["to_lv95"][1].dbl, COORD_TOL, "N", dev, key: "metres")
            let ll = Lv95.lv95ToWgs84(r["e"].dbl, r["n"].dbl)
            expectClose(ll.lat, r["to_wgs84"][0].dbl, COORD_TOL * 1e-5, "lat", dev, key: "degrees")
            expectClose(ll.lon, r["to_wgs84"][1].dbl, COORD_TOL * 1e-5, "lon", dev, key: "degrees")
            #expect(Lv95.cellId(r["lat"].dbl, r["lon"].dbl) == r["cell"].str)
            // swisstopo's published values (DESIGN.md §3): within 3 m (Genève 2.2 m, the rest < 0.5 m).
            #expect(abs(en.e - r["e"].dbl) < 3 && abs(en.n - r["n"].dbl) < 3)
        }
        for p in f["points"].arr {
            let en = Lv95.wgs84ToLv95(p["lat"].dbl, p["lon"].dbl)
            expectClose(en.e, p["to_lv95"][0].dbl, COORD_TOL, "E", dev, key: "metres")
            expectClose(en.n, p["to_lv95"][1].dbl, COORD_TOL, "N", dev, key: "metres")
            let back = Lv95.lv95ToWgs84(en.e, en.n)
            expectClose(back.lat, p["back"][0].dbl, 1e-11, "lat", dev, key: "degrees")
            expectClose(back.lon, p["back"][1].dbl, 1e-11, "lon", dev, key: "degrees")
            let cell = Lv95.cellId(p["lat"].dbl, p["lon"].dbl)
            #expect(cell == p["cell"].str, "cell \(cell) vs \(p["cell"].str)")
            #expect(Lv95.isInServerRange(cell) == p["in_server_range"].bool)
            for (c, e) in zip(Lv95.cellCorners(cell)!, p["corners"].arr) {
                expectClose(c.lat, e[0].dbl, 1e-11, "corner", dev, key: "degrees"); expectClose(c.lon, e[1].dbl, 1e-11, "corner", dev, key: "degrees")
            }
            let ce = Lv95.cellCenter(cell)!
            expectClose(ce.lat, p["center"][0].dbl, 1e-11, "centre", dev, key: "degrees")
        }
        for c in f["cell_ids"].arr {
            let id = c["id"].str
            #expect(Lv95.isCellId(id) == c["valid"].bool, "valid \(id)")
            #expect(Lv95.isInServerRange(id) == c["in_server_range"].bool, "range \(id)")
            let sw = Lv95.parseCell(id)
            if c["sw"].isNull { #expect(sw == nil) } else { #expect(sw == Lv95.En(c["sw"][0].dbl, c["sw"][1].dbl)) }
        }
        for c in f["lv95_to_cell"].arr { #expect(Lv95.lv95ToCell(c[0].dbl, c[1].dbl) == c[2].str) }
        dev.report()
    }
}

@Suite struct LocationNoiseGoldenTests {
    static func bytes(_ hex: String) -> [UInt8] {
        var out: [UInt8] = []
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            out.append(UInt8(hex[i..<j], radix: 16)!)
            i = j
        }
        return out
    }

    @Test func hmacDrawsAndShiftedPoints() throws {
        let f = try Fixtures.load("location_noise.json")
        let dev = Deviations("location_noise")
        #expect(LocationNoise.SIGMAS_M == f["sigmas"].arr.map { $0.int })
        var n = 0
        for c in f["cases"].arr {
            let secret = Self.bytes(c["secret_hex"].str)
            let sigma = c["sigma_m"].int
            let cell = Lv95.lv95ToCell(c["e"].dbl, c["n"].dbl)
            #expect(cell == c["true_cell"].str)
            let seed = try LocationNoise.seed(secret, cell, sigma)
            #expect(seed == Self.bytes(c["seed_hex"].str), "HMAC seed")
            let (zE, zN) = LocationNoise.standardNormals(seed)
            expectClose(zE, c["z"][0].dbl, 1e-12, "zE", dev, key: "z")
            expectClose(zN, c["z"][1].dbl, 1e-12, "zN", dev, key: "z")
            let off = try LocationNoise.offset(secret, cell, sigma)
            expectClose(off.e, c["offset"][0].dbl, COORD_TOL, "dE", dev, key: "metres")
            expectClose(off.n, c["offset"][1].dbl, COORD_TOL, "dN", dev, key: "metres")
            let s = try LocationNoise.shift(secret, c["e"].dbl, c["n"].dbl, sigma)
            let e = c["shifted"].arr
            #expect(s.e == e[0].int && s.n == e[1].int && s.sigmaM == e[2].int && s.clamped == e[3].bool, "shifted point (exact after rounding)")
            #expect(s.cell == c["shifted_cell"].str)
            n += 1
        }
        #expect(n == 4 * 6 * 4)
        #expect(throws: LocationNoise.Failure.badSigma) { try LocationNoise.seed([UInt8](repeating: 0, count: 32), "h26824_12473", 30) }
        #expect(throws: LocationNoise.Failure.badSecret) { try LocationNoise.seed([1, 2, 3], "h26824_12473", 50) }
        #expect(LocationNoise.newSecret().count == 32)
        dev.report()
    }
}

@Suite struct RecordsGoldenTests {
    @Test func isoSummariesCsvCategories() throws {
        let f = try Fixtures.load("records.json")
        let dev = Deviations("records")
        for c in f["iso"].arr {
            let zone = TimeZone(identifier: c[0].str)!
            #expect(Iso.format(c[1].int, zone) == c[2].str, "iso \(c[0].str) \(c[1].int)")
            #expect(Iso.format(c[1].int, zone, millis: true) == c[3].str, "iso ms \(c[0].str) \(c[1].int)")
        }
        let minutes = f["minutes"].arr.map(decodeMinute)
        let events = f["events"].arr.map(decodeEvent)
        let spans = MeasurementSplitter.split(minutes.map { ($0.startEpochMs, $0.durationSeconds) })
        #expect(spans.map { [$0.startMs, $0.endMs] } == f["spans"].arr.map { [$0[0].int, $0[1].int] })
        let sums = MeasurementSummarizer.summarizeAll(spans, minutes, events, eventMinLevelDb: f["event_min_level_db"].dbl)
        #expect(sums.count == f["summaries"].arr.count)
        for (s, e) in zip(sums, f["summaries"].arr) {
            let span: JSONValue = e["span"]
            #expect(s.startMs == span[0].int && s.endMs == span[1].int)
            expectClose(s.measuredSeconds, e["measured_s"].dbl, 0, "measured", dev, key: "seconds")
            expectClose(s.laeqDb, e["laeq_db"].dbl, LEVEL_TOL, "laeq", dev, key: "level_dB")
            #expect(s.eventCount == e["event_count"].int && s.strongEventCount == e["strong_event_count"].int && s.windEventCount == e["wind_event_count"].int)
            #expect(s.categoryShares.keys == e["category_shares"].arr.map { $0[0].str })
            for p in e["category_shares"].arr { expectClose(s.categoryShares[p[0].str]!, p[1].dbl, 1e-12, "share", dev, key: "share") }
            #expect(s.eventsByCategory.keys == e["events_by_category"].arr.map { $0[0].str })
            #expect(s.eventsByCategory.values == e["events_by_category"].arr.map { $0[1].int })
            #expect(s.loudestEvent?.startEpochMs == (e["loudest_start_ms"].isNull ? nil : e["loudest_start_ms"].int))
            #expect(s.allCalibrated == e["all_calibrated"].bool && s.excludedMinutes == e["excluded_minutes"].int && s.anyRecalibrated == e["any_recalibrated"].bool)
            expectClose(s.eventsPerHour, e["events_per_hour"].dbl, 1e-9, "events/h", dev, key: "rate")
            expectClose(s.dynamicsDb, e["dynamics_db"].dbl, LEVEL_TOL, "dynamics", dev, key: "level_dB")
            expectClose(s.humDb, e["hum_db"].dbl, LEVEL_TOL, "hum", dev, key: "level_dB")
            expectClose(s.coverage, e["coverage"].dbl, 1e-12, "coverage", dev, key: "ratio")
        }
        let starts = spans.map { $0.startMs }
        for c in f["owner_index"].arr { #expect(MeasurementSplitter.ownerIndex(starts, c[0].int) == c[1].int, "owner of \(c[0].int)") }
        expectClose(Dynamics.medianL90(minutes), f["median_l90"].dbl, 0, "median L90", dev, key: "level_dB")
        expectClose(Dynamics.medianSpread(minutes), f["median_spread"].dbl, 0, "median spread", dev, key: "level_dB")
        let buckets = f["csv_bucket_ids"].arr.map { $0.str }
        #expect(Csv.minutes(f["csv_minutes_records"].arr.map(decodeMinute), buckets) == f["csv_minutes"].str, "minutes CSV")
        #expect(Csv.events(f["csv_events_records"].arr.map(decodeEvent)) == f["csv_events"].str, "events CSV")
        let fields: [String?] = [nil, "", "plain", "a,b", "say \"hi\"", "line\nbreak", "cr\rx"]
        #expect(fields.map { Csv.field($0) } == f["csv_fields"].arr.map { $0.str })
        let mapper = try appMapper()
        #expect(mapper.bucketIds == f["bucket_ids"].arr.map { $0.str })
        for c in f["category_decisions"].arr {
            let k = c["k"].int
            var s = fakeScores(mapper, k)
            if k == 9 { s = [Float](repeating: 0.05, count: s.count) }
            if k == 10 { s = [Float](repeating: 0, count: s.count); s[mapper.labels.firstIndex(of: "Speech")!] = 0.15; s[mapper.labels.firstIndex(of: "Truck")!] = 0.25 }
            if k == 11 { s = [Float](repeating: 0, count: s.count); s[mapper.labels.firstIndex(of: "Motorcycle")!] = 0.3; s[mapper.labels.firstIndex(of: "Car")!] = 0.3 }
            let d = mapper.decide(s)
            #expect(d.dominant == c["dominant"].str && Double(d.dominantScore) == c["score"].dbl, "decision \(k)")
            #expect(d.categoryScores.keys == c["category_scores"].arr.map { $0[0].str })
            #expect(d.categoryScores.values.map { Double($0) } == c["category_scores"].arr.map { $0[1].dbl })
            let top = mapper.topLabels(s, 3)
            #expect(top.map { $0.label } == c["top3"].arr.map { $0[0].str } && top.map { Double($0.score) } == c["top3"].arr.map { $0[1].dbl }, "top3 \(k)")
        }
        dev.report()
    }
}
