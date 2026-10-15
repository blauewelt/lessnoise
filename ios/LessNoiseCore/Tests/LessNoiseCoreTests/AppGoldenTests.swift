import Foundation
import Testing
@testable import LessNoiseCore

// Golden tests of the app-side pure logic (fixtures from android/app/src/testPublic/…/golden/AppGoldenTest.kt).
// JSON bodies, texts, decisions and markers must be identical (exact string comparison).

private let zurich = TimeZone(identifier: "Europe/Zurich")!

private func point(_ j: JSONValue) -> LocationNoise.Point? {
    j.isNull ? nil : LocationNoise.Point(e: j[0].int, n: j[1].int, sigmaM: j[2].int, clamped: j[3].bool)
}

@Suite struct PayloadsGoldenTests {
    @Test func bodiesAreByteIdentical() throws {
        let f = try Fixtures.load("payloads.json")
        let pts = f["points"].arr.map { point($0)! }
        #expect(f["point_json"].arr.map { $0.str } == pts.map { Payloads.pointJson($0).description })
        for s in f["sites"].arr {
            let site = SiteSettings(placement: s["placement"].str, floor: s["floor"].isNull ? nil : s["floor"].int,
                                    streetFacing: s["street_facing"].isNull ? nil : s["street_facing"].bool, note: s["note"].str)
            let extras = SiteExtras(deviceModel: s["device_model"].str, audioSource: s["audio_source"].str, calibrated: s["calibrated"].bool,
                                    calibrationOffsetDb: s["calibration_offset_db"].dbl)
            #expect(Payloads.site(point(s["point"])!, site, extras).description == s["json"].str)
        }
        for m in f["minutes"].arr {
            #expect(Payloads.minute(decodeMinute(m["record"]), pts[0]).description == m["json"].str)
        }
        for e in f["events"].arr {
            let ev = decodeEvent(e["record"])
            #expect(Payloads.isShared(ev) == e["shared"].bool)
            #expect(Payloads.event(ev, pts[1]).description == e["json"].str)
        }
        for r in f["reports"].arr {
            let p = PendingReport(epochMs: r["epoch_ms"].int, timeIso: r["time_iso"].str, lat: r["lat"].dbl, lon: r["lon"].dbl,
                                  accuracyM: r["accuracy_m"].dbl, category: r["category"].str, cell: r["cell"].strOrNil)
            #expect(Payloads.report(p).description == r["json"].str)
        }
        for c in f["iso_seconds"].arr {
            #expect(PendingReport.isoSeconds(c[1].int, TimeZone(identifier: c[0].str)!) == c[2].str, "isoSeconds \(c[0].str) \(c[1].int)")
        }
        for c in f["levels"].arr {
            let v = c[0].dbl
            #expect(Payloads.level(v).description == c[1].str && Payloads.num(v, 3).description == c[2].str && Payloads.num(v, 5).description == c[3].str, "num \(v)")
        }
        // gzip: header and trailer as java.util.zip.GZIPOutputStream writes them, CRC-32 as java.util.zip.CRC32.
        let g = f["gzip"]
        let body = Array(g["body"].str.utf8)
        #expect(body.count == g["size"].int)
        #expect(Int(Gzip.crc32(body)) == g["crc32"].int)
        let gz = Gzip.compress(body)
        #expect(gz.prefix(10).map { String(format: "%02x", $0) }.joined() == g["header_hex"].str)
        #expect(gz.suffix(8).map { String(format: "%02x", $0) }.joined() == g["trailer_hex"].str)
        #expect(try inflateStoredOrNil(gz) == body) // Linux: stored blocks; on Apple the deflate stream differs (checked on the Mac)
        #expect(Payloads.register("0.6.3", 16).description == #"{"app_version":"0.6.3","app_build":16}"#)
    }
}

/** The records of the Kotlin scenario, served like Room (start, then insertion order). */
private final class ScenarioSource: UploadSource {
    var minutes: [MinuteRecord] = []
    var events: [NoiseEvent] = []
    var rows: [MeasurementRow] = []

    func minutesAfter(_ afterMs: Int, _ limit: Int) async -> [MinuteRecord] {
        Array(minutes.enumerated().filter { $0.element.startEpochMs > afterMs }
            .sorted { ($0.element.startEpochMs, $0.offset) < ($1.element.startEpochMs, $1.offset) }.map { $0.element }.prefix(limit))
    }

    func eventsAfter(_ afterMs: Int, _ limit: Int) async -> [NoiseEvent] {
        Array(events.enumerated().filter { $0.element.startEpochMs > afterMs }
            .sorted { ($0.element.startEpochMs, $0.offset) < ($1.element.startEpochMs, $1.offset) }.map { $0.element }.prefix(limit))
    }

    func measurements() async -> [MeasurementRow] { rows }
    func siteExtras() async -> SiteExtras { SiteExtras(deviceModel: "Pixel 8", audioSource: "UNPROCESSED", calibrated: true, calibrationOffsetDb: 121.9) }
}

/** Kotlin's toString() of the outcomes, for comparison with the fixture. */
private func kotlinString(_ o: Uploader.Outcome) -> String {
    switch o {
    case .disabled: return "Disabled"
    case .authFailed: return "AuthFailed"
    case let .done(m, e, r, more, rep): return "Done(minutes=\(m), events=\(e), refused=\(r), more=\(more), reports=\(rep))"
    case let .retry(msg, rl): return "Retry(message=\(msg), rateLimited=\(rl))"
    case let .failed(msg): return "Failed(message=\(msg))"
    }
}

private func kotlinString(_ o: Uploader.DeleteOutcome) -> String {
    switch o {
    case .deleted: return "Deleted"
    case .unknownToServer: return "UnknownToServer"
    case let .failed(m): return "Failed(message=\(m))"
    }
}

@Suite struct UploadScenarioGoldenTests {
    @Test func sameRequestsSameMarkers() async throws {
        let f = try Fixtures.load("upload_scenario.json")
        let allMinutes = f["minutes"].arr.map(decodeMinute)
        let allEvents = f["events"].arr.map(decodeEvent)
        let source = ScenarioSource()
        source.rows = f["measurements"].arr.map { MeasurementRow(id: $0[0].int, startMs: $0[1].int, endMs: $0[2].isNull ? nil : $0[2].int, point: point($0[3])) }
        // Three minutes and one event are added during the scenario (before «events-only run»).
        source.minutes = Array(allMinutes.dropLast(3))
        source.events = Array(allEvents.dropLast(1))
        let reports = MemoryReportStore()
        let pending = f["reports"].arr.map {
            PendingReport(epochMs: $0[0].int, timeIso: $0[1].str, lat: $0[2].dbl, lon: $0[3].dbl, accuracyM: $0[4].dbl, category: $0[5].str, cell: $0[6].strOrNil)
        }
        for p in pending { reports.update { ReportQueue.add($0, p) } }
        var initial = UploadSnapshot()
        initial.site = SiteSettings(placement: "open_window", floor: 3, streetFacing: true, note: "2. OG")
        let store = MemoryUploadStore(initial)
        let transport = FakeTransport()
        transport.refuseStarts = Set(f["refuse_starts"].arr.map { $0.str })
        let client = try UploadClient(baseUrl: UploadClient.DEFAULT_BASE, transport: transport)
        var now = 0
        let up = Uploader(client: client, store: store, source: source, reports: reports, appVersion: f["app_version"].str,
                          appBuild: f["app_build"].int, clock: { now }, minuteBatch: 7, eventBatch: 4)
        let m3Start = source.rows[3].startMs
        var seen = 0
        for step in f["steps"].arr {
            let name = step["step"].str
            now = step["now_ms"].int
            let outcome: String
            switch name {
            case "run while off", "run 1", "run 2", "run 3", "run 4", "run after point attached", "run after point attached 2",
                 "run after point attached 3", "full run", "run after rewind", "run after rewind 2", "run while disabled",
                 "run after delete (registers anew)":
                outcome = kotlinString(await up.run())
            case "enable", "enable again": outcome = String(up.enable())
            case "point attached to the running measurement": await up.pointAttached(m3Start); outcome = "kotlin.Unit"
            case "events-only run":
                source.minutes = allMinutes
                source.events = allEvents
                outcome = kotlinString(await up.run(withMinutes: false))
            case "server error":
                transport.failNext.append(("POST", "/minutes", 503))
                outcome = kotlinString(await up.run())
            case "rate limited":
                transport.failNext.append(("POST", "/minutes", 429))
                outcome = kotlinString(await up.run())
            case "site changed":
                store.update { var s = $0; s.site.note = "neu: 3. OG"; return s }
                outcome = kotlinString(await up.run())
            case "rewind after re-evaluation": up.rewindAfterReevaluation(); outcome = "kotlin.Unit"
            case "disable drops the pending report":
                let p = pending[0]
                reports.update { ReportQueue.add($0, PendingReport(epochMs: now, timeIso: PendingReport.isoSeconds(now, zurich), lat: p.lat, lon: p.lon,
                                                                   accuracyM: p.accuracyM, category: p.category, cell: p.cell)) }
                up.disable(); outcome = "kotlin.Unit"
            case "delete server data": outcome = kotlinString(await up.deleteServerData())
            default: Issue.record("unknown step \(name)"); continue
            }
            #expect(outcome == step["outcome"].str, "\(name): outcome")
            let reqs = Array(transport.requests[seen...])
            seen = transport.requests.count
            let expected = step["requests"].arr
            #expect(reqs.count == expected.count, "\(name): \(reqs.count) requests vs \(expected.count)")
            for (r, e) in zip(reqs, expected) {
                let path = r.path.replacingOccurrences(of: #"dev-[0-9]+"#, with: "{device}", options: .regularExpression)
                #expect(r.method == e[0].str && path == e[1].str, "\(name): \(r.method) \(path) vs \(e[0].str) \(e[1].str)")
                #expect(r.json == e[2].strOrNil, "\(name): body of \(r.method) \(path)")
            }
            let s = store.state
            let es = step["snapshot"]
            #expect(s.enabled == es["enabled"].bool && s.shareDecision.stored == es["decision"].str && s.registered == es["registered"].bool, "\(name): switches")
            #expect(s.sendFloorMs == es["send_floor_ms"].int && s.minutesSentUpToMs == es["minutes_sent_ms"].int && s.eventsSentUpToMs == es["events_sent_ms"].int,
                    "\(name): markers \(s.sendFloorMs) \(s.minutesSentUpToMs) \(s.eventsSentUpToMs)")
            #expect(s.lastSiteJson == es["last_site_json"].strOrNil, "\(name): site json")
            #expect(s.lastSuccessAtMs == (es["last_success_ms"].isNull ? nil : es["last_success_ms"].int), "\(name): last success")
            #expect(s.lastError == es["last_error"].strOrNil && s.authFailed == es["auth_failed"].bool, "\(name): error")
            #expect(s.refusedTotal == es["refused_total"].int, "\(name): refused total")
            #expect(s.refused.map { "\($0.kind)|\($0.startEpochMs)|\($0.reason)" } == es["refused"].arr.map { "\($0[0].str)|\($0[1].int)|\($0[2].str)" }, "\(name): refused")
            #expect(reports.state.pending.count == step["reports_pending"].int, "\(name): pending reports")
            #expect(reports.state.sent.map { "\($0.epochMs)|\($0.category)|\($0.cell ?? "-")" } ==
                    step["reports_sent"].arr.map { "\($0[0].int)|\($0[1].str)|\($0[2].strOrNil ?? "-")" }, "\(name): sent reports")
            #expect(up.effectiveFloorMs() == step["effective_floor_ms"].int && up.minutesPendingAfterMs() == step["minutes_pending_after_ms"].int, "\(name): floors")
        }
    }
}

@Suite struct LivePlanGoldenTests {
    @Test func scriptedRuns() throws {
        let f = try Fixtures.load("live_plan.json")
        for sc in f["scenarios"].arr {
            let pointAt = sc["point_at_ms"].int
            let results = sc["results"].arr.map { LiveUploadPlan.Result(rawValue: $0.str)! }
            let events = sc["events"].arr.map { $0.int }
            let plan = LiveUploadPlan(pointAtMs: pointAt)
            var e = 0
            var i = 0
            var got: [String] = []
            while true {
                var run = plan.next()
                while e < events.count && events[e] <= run.atMs { plan.onEvent(events[e]); e += 1; run = plan.next() }
                if run.atMs > pointAt + 4 * 3_600_000 { break }
                let r = results[i % results.count]
                plan.done(run, r, run.atMs + 2_000)
                got.append("\(run.atMs) \(run.kind.rawValue) \(r.rawValue) \(plan.backOffLevel)")
                i += 1
            }
            #expect(got == sc["runs"].arr.map { "\($0[0].int) \($0[1].str) \($0[2].str) \($0[3].int)" }, "plan from \(pointAt)")
        }
        for h in f["hourly"].arr {
            let from = h[1].int
            #expect(UploadCadence.hourlyRunTimes(from, from + 5 * 3_600_000, TimeZone(identifier: h[0].str)!) == h[2].arr.map { $0.int }, "hourly \(h[0].str)")
        }
    }
}

@Suite struct ShareStatusGoldenTests {
    @Test func everyCombination() throws {
        let f = try Fixtures.load("share_status.json")
        let t0 = f["t0"].int
        let errors = f["errors"].arr.map { $0.strOrNil }
        let texts = f["texts"].arr.map { $0.str }
        let locates: [LocateState] = [.idle, .pending, .locating, .failed(.noFix)]
        let hm: (Int) -> String = { let s = Iso.format($0, zurich); return String(s[s.index(s.startIndex, offsetBy: 11)..<s.index(s.startIndex, offsetBy: 16)]) }
        var n = 0
        for r in f["rows"].arr {
            var s = UploadSnapshot()
            s.enabled = r[0].int == 1
            s.shareDecision = ShareDecision.fromStored(r[1].str)!
            s.authFailed = r[2].int == 1
            s.lastSuccessAtMs = r[3].int == 1 ? t0 : nil
            s.lastError = errors[r[4].int]
            s.minutesSentUpToMs = r[5].int == 1 ? t0 : 0
            let running = r[6].int == 1, hasPoint = r[7].int == 1, loc = locates[r[8].int], online = r[9].int == 1
            let anyPoint = r[10].int == 1, nowMs = r[11].int
            let st = shareStatus(s, running: running, measurementStartMs: t0 - 600_000, hasPoint: hasPoint, locate: loc, online: online, hm: hm)
            let b = mapBanner(s, running: running, measurementStartMs: t0 - 600_000, hasPoint: hasPoint, hasAnyPoint: anyPoint, locate: loc,
                              online: online, nowMs: nowMs, hm: hm)
            let eText = r[12].isNull ? nil : texts[r[12].int]
            let eBanner = r[15].isNull ? nil : texts[r[15].int]
            #expect(st?.text == eText && (st?.action.rawValue ?? "") == r[13].str && ((st?.problem ?? false) ? 1 : 0) == r[14].int, "status row \(n)")
            #expect(b?.text == eBanner && (b?.action.rawValue ?? "") == r[16].str, "banner row \(n)")
            n += 1
        }
        #expect(n == 4096)
    }
}

@Suite struct UiLocationGoldenTests {
    private func outcome(_ o: LocationPolicy.Outcome) -> String {
        let kind: String
        switch o {
        case let .shifted(p, acc): kind = "shifted|\(p.e),\(p.n),\(p.sigmaM),\(p.clamped)|\(acc.map(String.init) ?? "null")"
        case .approximateOnly: kind = "approximateOnly"
        case let .tooInaccurate(acc): kind = "tooInaccurate|\(acc.map(String.init) ?? "null")"
        case let .outsideSwitzerland(m): kind = "outsideSwitzerland|\(m)"
        case .locationOff: kind = "locationOff"
        case .noFix: kind = "noFix"
        case .permissionDenied: kind = "permissionDenied"
        }
        return kind + "|" + LocationPolicy.message(o)
    }

    private func outcome(_ j: JSONValue) -> String {
        let a = j.arr
        switch a[0].str {
        case "shifted": return "shifted|\(a[1][0].int),\(a[1][1].int),\(a[1][2].int),\(a[1][3].bool)|\(a[2].isNull ? "null" : String(a[2].int))|\(a[3].str)"
        case "tooInaccurate": return "tooInaccurate|\(a[1].isNull ? "null" : String(a[1].int))|\(a[2].str)"
        case "outsideSwitzerland": return "outsideSwitzerland|\(a[1].str)|\(a[2].str)"
        default: return "\(a[0].str)|\(a[1].str)"
        }
    }

    @Test func alertsLinksTabsCellInputPolicy() throws {
        let f = try Fixtures.load("ui_location.json")
        for a in f["alerts"].arr {
            let e = LiveEvent(startEpochMs: 100_000, endEpochMs: 120_000, lafMaxDb: a[1].dbl, category: a[0].strOrNil, wind: a[2].bool, calibrated: false)
            let last = a[3].isNull ? nil : a[3].int
            #expect(LoudAlertRule.shouldAlert(e, unterwegs: true, running: true, lastAlertAtMs: last, nowMs: 121_000) == a[4].bool, "alert \(a)")
            #expect(LoudAlertRule.shouldAlert(e, unterwegs: false, running: true, lastAlertAtMs: nil, nowMs: 121_000) == a[5].bool)
            #expect(LoudAlertRule.shouldAlert(e, unterwegs: true, running: false, lastAlertAtMs: nil, nowMs: 121_000) == a[6].bool)
            #expect(LoudAlertRule.title(e) == a[7].str && LoudAlertRule.categoryLabel(a[0].strOrNil) == a[8].str)
            #expect(LoudAlertRule.suggested(a[0].strOrNil).map { $0.id } == a[9].arr.map { $0.str })
            #expect(reportCategoryOrder(LoudAlertRule.suggested(a[0].strOrNil)).map { $0.id } == a[10].arr.map { $0.str })
        }
        for p in f["presets"].arr {
            let r = LoudAlertIntent.preset(p[0].strOrNil, p[1].int, p[2].strOrNil)
            if p[3].isNull { #expect(r == nil) } else {
                let e = p[3].arr
                #expect(r?.epochMs == e[0].int && r?.suggested.map { $0.id } == e.dropFirst().map { $0.str })
            }
        }
        for u in f["map_urls"].arr {
            let p = point(u[0])
            #expect(MapLinks.url(p) == u[1].str && MapLinks.viewFragment(p) == u[2].strOrNil, "map url \(u[1].str)")
        }
        for u in f["url_checks"].arr {
            #expect(MapLinks.staysInApp(u[0].str) == u[1].bool, "staysInApp \(u[0].str)")
            #expect(MapLinks.allowedHost(u[0].str) == u[2].bool, "allowedHost \(u[0].str)")
        }
        for fr in f["fragments"].arr {
            #expect((try? MapLinks.recentreScript(fr[0].str)) == fr[1].strOrNil, "fragment \(fr[0].str)")
        }
        let pts = [nil, LocationNoise.Point(e: 2682437, n: 1247391, sigmaM: 50), nil, LocationNoise.Point(e: 2839999, n: 1299999, sigmaM: 200)]
        let mc = MapCentring()
        for c in f["centring"].arr {
            let op = c[0].str
            let got: String?
            if op.hasPrefix("show:") {
                got = mc.show(op.hasSuffix("1") ? pts[1] : op.hasSuffix("2") ? pts[3] : nil).map(stepText)
            } else if op == "finished" { got = mc.pageFinished().map(stepText) } else if op == "started" { mc.pageStarted(); got = nil }
            else if op == "check" { got = mc.check().map(stepText) } else if op == "hidden" { got = String(mc.hidden()) }
            else if op == "failed" { mc.failed(); got = nil } else { Issue.record("unknown op \(op)"); got = nil }
            let e: String? = { if case .bool(let b) = c[1] { return String(b) }; return c[1].strOrNil }()
            #expect(got == e, "centring \(op): \(String(describing: got)) vs \(String(describing: e))")
        }
        var h = TabHistory()
        for t in f["tabs"].arr {
            let op = t[0].str
            h = op == "back" ? h.back() : h.select(Screen(rawValue: op)!)
            #expect(h.current.rawValue == t[1].str && h.canGoBack == t[2].bool && h.stack.count == t[3].int && h.save().joined(separator: ",") == t[4].str, "tabs \(op)")
        }
        for r in f["tab_restore"].arr {
            let names = r[0].str.isEmpty ? [] : r[0].str.split(separator: ",").map(String.init)
            #expect(TabHistory.restore(names).save().joined(separator: ",") == r[1].str)
        }
        for s in f["screens"].arr {
            #expect(appScreens(uploadAvailable: s[0].bool, mapAvailable: s[1].bool).map { $0.rawValue }.joined(separator: ",") == s[2].str)
        }
        for c in f["cell_input"].arr {
            switch CellInput.parse(c[0].str) {
            case let .ok(cell, how, e, n):
                #expect(c[1].str == "ok" && cell == c[2].str && how == c[3].str && e == c[4].dbl && n == c[5].dbl, "parse «\(c[0].str)»")
            case let .error(m):
                #expect(c[1].str == "error" && m == c[2].str, "parse «\(c[0].str)»: \(m)")
            }
        }
        let secret = LocationNoiseGoldenTests.bytes(f["noise_secret_hex"].str)
        let noise = LocationPolicy.Noise(secret: secret, sigmaM: f["noise_sigma"].int)!
        for d in f["decide"].arr {
            let acc = d[2].isNull ? nil : d[2].dbl
            #expect(outcome(LocationPolicy.decide(lat: d[0].dbl, lon: d[1].dbl, accuracyM: acc, precise: d[3].bool, noise: noise)) == outcome(d[4]), "decide \(d)")
            if !d[5].isNull {
                let r = LocationPolicy.decideReport(lat: d[0].dbl, lon: d[1].dbl, accuracyM: acc, precise: d[3].bool)
                let e = d[5].arr
                switch r {
                case let .fix(lat, lon, a, cell):
                    #expect(e[0].str == "fix" && lat == e[1].dbl && lon == e[2].dbl && a == e[3].dbl && cell == e[4].str && LocationPolicy.roundUp(a) == e[5].int)
                case let .problem(o):
                    #expect(e[0].str == "problem" && outcome(o) == outcome(e[1]))
                }
            }
        }
        for p in f["pasted"].arr {
            #expect(LocationPolicy.fromPasted(CellInput.parse(p[0].str), noise: noise).map(outcome) == outcome(p[1]))
        }
        let msgs: [LocationPolicy.Outcome] = [
            .approximateOnly, .tooInaccurate(accuracyM: nil), .tooInaccurate(accuracyM: 80), .locationOff, .noFix, .permissionDenied,
            .shifted(point: LocationNoise.Point(e: 2682437, n: 1247391, sigmaM: 50), accuracyM: nil),
            .shifted(point: LocationNoise.Point(e: 2682437, n: 1247391, sigmaM: 200), accuracyM: 7),
        ]
        #expect(msgs.map(outcome) == f["messages"].arr.map(outcome))
        for c in f["last_known"].arr {
            let acc = c[1].isNull ? nil : c[1].dbl
            #expect(LocationPolicy.acceptLastKnown(ageMs: c[0].int, accuracyM: acc) == c[2].bool && LocationPolicy.goodEnough(acc) == c[3].bool)
        }
        for c in f["better"].arr {
            #expect(LocationPolicy.better(c[0].isNull ? nil : c[0].dbl, c[1].isNull ? nil : c[1].dbl) == c[2].bool)
        }
    }

    private func stepText(_ s: MapCentring.Step) -> String {
        switch s {
        case let .load(u): return "load " + u
        case let .recentre(sc): return "recentre " + sc
        case let .check(sc): return "check " + sc
        }
    }
}

@Suite struct TextsGoldenTests {
    @Test func everyTextIsTheKotlinOne() throws {
        let f = try Fixtures.load("texts.json")
        let T = ShareTexts.self
        let pairs: [(String, String)] = [
            ("SHARE_INTRO", T.SHARE_INTRO), ("LOCATION_NEVER_SENT", T.LOCATION_NEVER_SENT), ("CONSENT_TITLE", T.CONSENT_TITLE),
            ("CONSENT_YES", T.CONSENT_YES), ("CONSENT_NO", T.CONSENT_NO), ("CONSENT_MORE", T.CONSENT_MORE), ("STATUS_OFF", T.STATUS_OFF),
            ("STATUS_LOCATING", T.STATUS_LOCATING), ("STATUS_NO_POINT", T.STATUS_NO_POINT), ("STATUS_FIRST_VALUES", T.STATUS_FIRST_VALUES),
            ("STATUS_ON_IDLE", T.STATUS_ON_IDLE), ("STATUS_NO_INTERNET", T.STATUS_NO_INTERNET), ("STATUS_SERVER_BUSY", T.STATUS_SERVER_BUSY),
            ("STATUS_REJECTED", T.STATUS_REJECTED), ("STATUS_AUTH", T.STATUS_AUTH), ("BANNER_NOT_SHARING", T.BANNER_NOT_SHARING),
            ("BANNER_NO_POINT", T.BANNER_NO_POINT), ("BANNER_FIRST_VALUES", T.BANNER_FIRST_VALUES), ("SIGMA_LABEL", T.SIGMA_LABEL),
            ("SIGMA_EXPLANATION", T.SIGMA_EXPLANATION), ("statusSent", T.statusSent("14:05")), ("bannerSent", T.bannerSent("14:05")),
            ("AUTH_FAILED_TEXT", UploadRules.AUTH_FAILED_TEXT), ("RETRY_TEXT", UploadRules.RETRY_TEXT), ("NO_CONNECTION_TEXT", UploadRules.NO_CONNECTION_TEXT),
            ("PROGRESS_TEXT", LocationPolicy.PROGRESS_TEXT), ("LOUD_TEXT", LoudAlertRule.TEXT), ("LOUD_CHANNEL_NAME", LoudAlertRule.CHANNEL_NAME),
            ("signed_example", EventFloor.signed(-3.25)),
        ]
        for (k, v) in pairs { #expect(v == f[k].str, "text \(k)") }
        #expect(T.SHARE_SENT_BULLETS == f["SHARE_SENT_BULLETS"].arr.map { $0.str })
        #expect(T.SHARE_WHERE_BULLETS == f["SHARE_WHERE_BULLETS"].arr.map { $0.str })
        #expect(T.SHARE_DELETE_BULLETS == f["SHARE_DELETE_BULLETS"].arr.map { $0.str })
        #expect(T.SHARE_WHEN_BULLETS == f["SHARE_WHEN_BULLETS"].arr.map { $0.str })
        for s in f["per_sigma"].arr {
            let sg = s["sigma"].int
            #expect(T.consentLines(sg) == s["consentLines"].arr.map { $0.str }, "consent \(sg)")
            #expect(T.shareLocationBullets(sg) == s["shareLocationBullets"].arr.map { $0.str }, "location \(sg)")
            #expect(T.sigmaChoice(sg) == s["sigmaChoice"].str && T.pastedText(sg) == s["pastedText"].str)
        }
        #expect(Screen.allCases.map { "\($0.rawValue)=\(screenLabel($0))" } == f["screens"].arr.map { "\($0[0].str)=\($0[1].str)" })
        #expect(ReportCategory.allCases.map { "\($0.id)=\($0.label)" } == f["report_categories"].arr.map { "\($0[0].str)=\($0[1].str)" })
        #expect([EventShape.HUMP, EventShape.JAGGED, EventShape.IMPULSE, EventShape.LONG, "other", nil].map { EventShape.nameDe($0) } == f["shapes"].arr.map { $0.str })
        #expect(["UNPROCESSED", "VOICE_RECOGNITION", "x"].map { AudioSource.labelDe($0) } == f["audio_sources"].arr.map { $0.str })
        #expect(Links.all + [Links.MAP] == f["links"].arr.map { $0.str })
        #expect(MapLinks.ALLOWED_HOSTS.sorted() == f["map_hosts"].arr.map { $0.str })
    }
}

@Suite struct UploadRulesGoldenTests {
    private final class EmptySource: UploadSource {
        func minutesAfter(_ afterMs: Int, _ limit: Int) async -> [MinuteRecord] { [] }
        func eventsAfter(_ afterMs: Int, _ limit: Int) async -> [NoiseEvent] { [] }
        func measurements() async -> [MeasurementRow] { [] }
        func siteExtras() async -> SiteExtras { SiteExtras(deviceModel: "x", audioSource: "UNPROCESSED", calibrated: false, calibrationOffsetDb: 112.35) }
    }

    @Test func markersWithoutNetwork() async throws {
        let f = try Fixtures.load("upload_rules.json")
        var now = f["start_now"].int
        for c in f["cases"].arr {
            let rows = c.arr
            let i = rows[0].arr
            var s = UploadSnapshot()
            s.sendFloorMs = i[0].int
            if let d = i[1].strOrNil { s.deviceId = d; s.token = "t" }
            s.minutesSentUpToMs = i[2].int
            s.eventsSentUpToMs = i[3].int
            let store = MemoryUploadStore(s)
            let up = Uploader(client: try UploadClient(baseUrl: UploadClient.DEFAULT_BASE, transport: FakeTransport()), store: store,
                              source: EmptySource(), reports: MemoryReportStore(), appVersion: "0.6.3", appBuild: 16, clock: { now })
            func snap(_ name: String) -> String {
                let s = store.state
                return "\(name) \(s.enabled) \(s.shareDecision.stored) \(s.sendFloorMs) \(s.minutesSentUpToMs) \(s.eventsSentUpToMs) \(up.effectiveFloorMs()) \(up.minutesPendingAfterMs())"
            }
            var got = [snap("init")]
            up.enable(); got.append(snap("enable"))
            now += 1_000
            await up.pointAttached(now - 3_600_000); got.append(snap("pointAttached"))
            up.disable(); got.append(snap("disable"))
            up.enable(); got.append(snap("enable2"))
            up.rewindAfterReevaluation(); got.append(snap("rewind"))
            let expected = rows.dropFirst().map { r in r.arr.map { v -> String in
                switch v { case .string(let x): return x; case .bool(let b): return String(b); default: return String(v.int) }
            }.joined(separator: " ") }
            #expect(got == expected)
        }
    }
}

@Suite struct UploadClientTests {
    /** Request building and answer parsing (UploadClient.kt has no golden hook for its private parts; cases mirror its code). */
    @Test func requestsAndAnswers() throws {
        let client = try UploadClient(baseUrl: "https://api.lessnoise.ch/", transport: FakeTransport())
        #expect(client.host == "api.lessnoise.ch")
        let r = client.buildRequest("POST", "/v1/devices/a%20b/minutes", token: "tok", body: .array([]))
        #expect(r.url == "https://api.lessnoise.ch/v1/devices/a%20b/minutes" && r.json == "[]")
        #expect(r.headers.map { "\($0.0): \($0.1)" } == [
            "User-Agent: LessNoise", "Accept: application/json", "Authorization: Bearer tok", "Content-Type: application/json", "Content-Encoding: gzip",
        ])
        #expect(UploadClient.seg("a b+c/ä~") == "a%20b%2Bc%2F%C3%A4%7E")
        #expect(throws: (any Error).self) { try UploadClient(baseUrl: "http://api.lessnoise.ch", transport: FakeTransport()) }
        #expect(throws: (any Error).self) { try UploadClient(baseUrl: "https://api.lessnoise.ch/v1", transport: FakeTransport()) }
        #expect((try? UploadClient(baseUrl: "http://127.0.0.1:8080", transport: FakeTransport())) != nil)
        #expect(UploadClient.classify(UploadResponse(status: 401, body: #"{"detail":"x"}"#)) == .unauthorized(#"401 "x""#))
        #expect(UploadClient.classify(UploadResponse(status: 429, body: "busy", retryAfter: " 120 ")) == .rateLimited(retryAfterS: 120, "429 busy"))
        #expect(UploadClient.classify(UploadResponse(status: 413, body: #"{"detail":{"a":1}}"#)) == .rejected(status: 413, #"413 {"a":1}"#))
        #expect(UploadClient.classify(UploadResponse(status: 502, body: "")) == .server(status: 502, "502 "))
        let res = try UploadClient.parseResult(UploadResponse(status: 200, body: #"{"accepted":3,"rejected":[{"index":1,"start":null,"reasons":[{"field":"x","reason":"y"}]},{"index":2,"start":"s"}]}"#))
        #expect(res == UploadResult(accepted: 3, duplicates: 0, rejected: [
            UploadRejected(index: 1, start: nil, reasons: #"[{"field":"x","reason":"y"}]"#), UploadRejected(index: 2, start: "s", reasons: ""),
        ]))
        #expect(throws: UploadFailure.self) { try UploadClient.parseResult(UploadResponse(status: 200, body: "[]")) }
    }
}
