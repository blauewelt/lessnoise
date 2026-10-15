import Foundation
import XCTest
@testable import LessNoiseKit

final class ShareTests: XCTestCase {
    func testFirstStartAsksOnceAndYesSharesTheRunningMeasurementWithAShiftedPoint() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.share.start()
        XCTAssertEqual(sv.share.dialog, .consent(startMeasurement: true))
        XCTAssertFalse(sv.session.live.running, "nothing starts before the answer")
        sv.share.consentYes(startMeasurement: true)
        XCTAssertNil(sv.share.dialog)
        XCTAssertTrue(sv.session.live.running)
        XCTAssertTrue(sv.uploadStore.state.enabled)
        XCTAssertEqual(sv.uploadStore.state.shareDecision, .YES)
        XCTAssertTrue(eventually { sv.session.live.sharedPoint != nil })
        let p = sv.session.live.sharedPoint!
        XCTAssertEqual(p.sigmaM, 50)
        // The true point is not stored: the row holds the shifted one, deterministic per place.
        let row = try sv.measurements.allMeasurements().last!
        XCTAssertEqual(row.point, p)
        let secret = sv.uploadStore.noiseSecret()
        let ll = CellInput.fromLatLon(47.37690, 8.54170)
        guard case let .ok(_, _, e, n) = ll else { return XCTFail() }
        XCTAssertEqual(try LocationNoise.shift(secret, e, n, 50), p)
        XCTAssertTrue(eventually { sv.share.locate == .idle })
        XCTAssertEqual(rig.locator.fixes, 1)
        // The status line while waiting for the first upload.
        XCTAssertEqual(sv.share.status(online: true, hm: { _ in "14:05" })?.text, ShareTexts.STATUS_FIRST_VALUES)
        sv.session.stop()

        // The next «Starten» does not ask again.
        sv.share.start()
        XCTAssertNil(sv.share.dialog)
        XCTAssertTrue(sv.session.live.running)
        sv.session.stop()
    }

    func testNoOnlyMeasuresOnThePhone() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.share.start()
        sv.share.consentNo(startMeasurement: true)
        XCTAssertTrue(sv.session.live.running)
        XCTAssertFalse(sv.uploadStore.state.enabled)
        XCTAssertEqual(sv.uploadStore.state.shareDecision, .NO)
        XCTAssertEqual(rig.locator.fixes, 0, "no location without sharing")
        XCTAssertEqual(sv.share.status(online: true, hm: { _ in "" }), ShareStatus(ShareTexts.STATUS_OFF, .TURN_ON))
        sv.session.stop()
        XCTAssertEqual(rig.server.requests.count, 0, "nothing sent")
    }

    func testDeniedLocationGivesTheStatusLineAndTheOptions() throws {
        let rig = try Rig()
        rig.locator.authorization = .notDetermined
        rig.locator.afterPrompt = .denied
        let sv = rig.services
        sv.share.start()
        sv.share.consentYes(startMeasurement: true)
        XCTAssertEqual(rig.locator.prompts, 1)
        XCTAssertEqual(sv.share.locate, .failed(.permissionDenied))
        let st = sv.share.status(online: true, hm: { _ in "" })
        XCTAssertEqual(st, ShareStatus(ShareTexts.STATUS_NO_POINT, .LOCATION_OPTIONS, problem: true))
        sv.share.onStatus(.LOCATION_OPTIONS)
        XCTAssertEqual(sv.share.dialog, .locationOptions)
        XCTAssertEqual(LocationSettingsHint.forOutcome(.permissionDenied), .appSettings)
        // «Koordinaten einfügen»: moved like a fix.
        sv.share.pasted(CellInput.parse("47.3769, 8.5417"))
        XCTAssertTrue(eventually { sv.session.live.sharedPoint != nil })
        sv.session.stop()
    }

    func testNoFixInTheBackgroundAndARetryInTheForeground() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.share.consentNo(startMeasurement: false)
        sv.background()
        sv.session.start()
        sv.share.switchOn(ask: false)
        XCTAssertTrue(sv.uploadStore.state.enabled)
        XCTAssertEqual(sv.share.locate, .pending)
        XCTAssertEqual(rig.locator.fixes, 0, "never in the background")
        sv.foreground()
        XCTAssertTrue(eventually { sv.session.live.sharedPoint != nil })
        XCTAssertEqual(rig.locator.fixes, 1)
        sv.session.stop()
        XCTAssertEqual(sv.share.locate, .idle)
    }

    func testOutsideSwitzerlandAndApproximate() throws {
        let rig = try Rig()
        rig.locator.result = .fix(RawFix(lat: 48.8566, lon: 2.3522, accuracyM: 10))
        let sv = rig.services
        sv.share.consentYes(startMeasurement: true)
        XCTAssertTrue(eventually { if case .failed(.outsideSwitzerland) = sv.share.locate { return true }; return false })
        sv.session.stop()
        rig.locator.authorization = .approximate
        sv.session.start()
        sv.share.wantPoint(prompt: true)
        XCTAssertEqual(sv.share.locate, .failed(.approximateOnly))
        XCTAssertEqual(LocationPolicy.message(.approximateOnly), "Bitte «Genauer Standort» erlauben (App-Einstellungen).")
        sv.session.stop()
    }

    func testSwitchOffStopsUploadsAndDropsPendingReports() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.share.consentYes(startMeasurement: false)
        sv.reportStore.update { ReportQueue.add($0, PendingReport(epochMs: 1, timeIso: "x", lat: 47, lon: 8, accuracyM: 5, category: "auto", cell: nil)) }
        sv.share.switchOff()
        XCTAssertFalse(sv.uploadStore.state.enabled)
        XCTAssertEqual(sv.reportStore.state.pending, [])
        XCTAssertEqual(rig.secrets.secret("reports.pending"), nil, "the coordinates are gone from storage")
        XCTAssertGreaterThan(rig.scheduler.cancels, 0)
    }
}

final class UploadTests: XCTestCase {
    func testSharedMeasurementGoesOutLiveAndOnlyToTheOneHost() async throws {
        let rig = try Rig()
        let sv = rig.services
        sv.share.consentYes(startMeasurement: true)
        XCTAssertTrue(eventually { sv.session.live.sharedPoint != nil })
        var noise = Noise()
        feed(rig, seconds: 150, noise: &noise)
        sv.session.drain()
        // The live loop runs on the fake clock: full runs 30 s after the point, then every 2 min.
        // The clock moves on (10 s per 10 ms) until the next full run has sent the minutes.
        XCTAssertTrue(eventually(15) { rig.clock.advance(10_000); Thread.sleep(forTimeInterval: 0.01); return !rig.server.minutes.isEmpty })
        sv.session.stop()
        let paths = rig.server.requests.map { "\($0.method) \($0.path)" }
        XCTAssertEqual(paths.first, "POST /v1/devices")
        XCTAssertTrue(paths.contains { $0.hasSuffix("/site") })
        XCTAssertTrue(paths.contains { $0.hasSuffix("/minutes") })
        XCTAssertEqual(Set(rig.server.hosts), ["api.lessnoise.ch"])
        let m = try XCTUnwrap(rig.server.minutes.values.first)
        XCTAssertEqual(m.member("audio_source")?.stringValue, "IOS")
        XCTAssertEqual(m.member("point")?.member("sigma_m")?.intValue, 50)
        XCTAssertTrue(sv.uploadStore.state.registered)
        XCTAssertEqual(rig.secrets.secret("upload.token"), sv.uploadStore.state.token)
        XCTAssertFalse(rig.kv.all.values.contains { $0.contains(sv.uploadStore.state.token!) }, "the token is only in the Keychain")
    }

    func testWifiOnlyWaitsOnAnExpensivePath() async throws {
        let rig = try Rig()
        let sv = rig.services
        sv.uploadStore.update { var n = $0; n.wifiOnly = true; return n }
        sv.uploads.enable()
        rig.network.expensive = true
        let o = await sv.uploads.runNow()
        XCTAssertEqual(o, .retry("no network", rateLimited: false))
        XCTAssertEqual(rig.server.requests.count, 0)
        rig.network.expensive = false
        let o2 = await sv.uploads.runNow()
        if case .done = o2 {} else { XCTFail("\(o2)") }
        XCTAssertEqual(rig.server.requests.first?.path, "/v1/devices")
    }

    func testDeleteServerDataForgetsTheIdentity() async throws {
        let rig = try Rig()
        let sv = rig.services
        sv.uploads.enable()
        _ = await sv.uploads.runNow()
        XCTAssertTrue(sv.uploadStore.state.registered)
        let r = await sv.uploads.deleteServerData()
        XCTAssertEqual(r, .deleted)
        XCTAssertFalse(sv.uploadStore.state.registered)
        XCTAssertFalse(sv.uploadStore.state.enabled)
        XCTAssertNil(rig.secrets.secret("upload.token"))
        XCTAssertNil(rig.secrets.secret("location.noise.secret"), "a new secret after deleting")
        XCTAssertEqual(rig.server.requests.last?.method, "DELETE")
    }

    func testApplyScheduleUsesTheHourlyFallback() throws {
        let rig = try Rig()
        rig.services.uploads.enable()
        XCTAssertEqual(rig.scheduler.scheduled.count, 1)
        XCTAssertLessThanOrEqual(rig.scheduler.scheduled[0], 65 * 60_000)
        rig.services.uploads.disable()
        XCTAssertEqual(rig.scheduler.cancels, 1)
    }
}

final class ReportTests: XCTestCase {
    func testReportWithConsentFixConfirmAndSend() async throws {
        let rig = try Rig()
        let sv = rig.services
        let zurich = TimeZone(identifier: "Europe/Zurich")!
        let flow = ReportFlow(uploads: sv.uploads, reports: sv.reportStore, locator: rig.locator, clock: rig.clock, zone: { zurich })
        flow.begin()
        XCTAssertEqual(flow.step, .consent, "sharing is off: the consent first")
        flow.consentYes()
        XCTAssertTrue(sv.uploadStore.state.enabled)
        XCTAssertEqual(flow.step, .category)
        flow.choose(.MOTORRAD)
        XCTAssertTrue(eventually { if case .confirm = flow.step { return true }; return false })
        let text = flow.confirmText!
        XCTAssertTrue(text.hasPrefix("Meldung: Töff, "), text)
        XCTAssertTrue(text.contains(", Standort ± 12 m – senden?\n" + ReportTexts.SPOT_WARNING), text)
        XCTAssertTrue(flow.send())
        XCTAssertNil(flow.step)
        let pending = sv.reportStore.state.pending
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending[0].category, "motorrad")
        XCTAssertEqual(pending[0].lat, 47.37690)
        XCTAssertNotNil(rig.secrets.secret("reports.pending"))
        XCTAssertNil(rig.kv.string("reports.pending"), "coordinates never in UserDefaults")
        // The upload sends it and the coordinates are gone.
        _ = await sv.uploads.runNow()
        XCTAssertEqual(rig.server.reports.count, 1)
        XCTAssertEqual(sv.reportStore.state.pending, [])
        XCTAssertEqual(sv.reportStore.state.sent.count, 1)
        XCTAssertNil(rig.secrets.secret("reports.pending"))
        XCTAssertFalse(rig.kv.string("reports.sent")!.contains("47.3769"))
    }

    func testFromTheNotificationTheEventTimeAndSuggestions() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.uploads.enable()
        let preset = LoudAlertIntent.preset(LoudAlertIntent.ACTION, 1_792_092_600_000, "loud_vehicle")!
        sv.reports.begin(preset)
        XCTAssertEqual(sv.reports.step, .category, "sharing is on: no consent")
        XCTAssertEqual(sv.reports.timeMs, 1_792_092_600_000)
        XCTAssertEqual(sv.reports.categoryOrder, [.MOTORRAD, .POSER, .AUTO, .LASTWAGEN, .ANDERE])
        rig.locator.result = .fix(RawFix(lat: 47.3769, lon: 8.5417, accuracyM: 80))
        sv.reports.choose(.POSER)
        XCTAssertTrue(eventually { sv.reports.step == .failed(.POSER, .tooInaccurate(accuracyM: 80)) })
        sv.reports.cancel()
        XCTAssertNil(sv.reports.step)
    }

    func testLoudAlertRuleAndRateLimit() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.settings.update { var n = $0; n.unterwegs = true; return n }
        var l = LiveState()
        l.running = true
        let loud = LiveEvent(startEpochMs: 1, endEpochMs: 5_000, lafMaxDb: 84, category: "loud_vehicle", wind: false, calibrated: false)
        l.lastEvent = loud
        sv.alerts.observe(l)
        XCTAssertEqual(rig.notifier.shown, [loud])
        sv.alerts.observe(l)
        XCTAssertEqual(rig.notifier.shown.count, 1, "the same event once")
        rig.clock.advance(60_000)
        l.lastEvent = LiveEvent(startEpochMs: 60_000, endEpochMs: 62_000, lafMaxDb: 90, category: "road_traffic", wind: false, calibrated: false)
        sv.alerts.observe(l)
        XCTAssertEqual(rig.notifier.shown.count, 1, "at most one per 2 minutes")
        rig.clock.advance(61_000)
        l.lastEvent = LiveEvent(startEpochMs: 130_000, endEpochMs: 132_000, lafMaxDb: 76, category: "road_traffic", wind: false, calibrated: false)
        sv.alerts.observe(l)
        XCTAssertEqual(rig.notifier.shown.count, 2)
        XCTAssertEqual(LoudAlertRule.title(l.lastEvent!), "Sehr laut: Strassenverkehr, 76 dB")
        let info = LoudAlertWatcher.userInfo(l.lastEvent!)
        XCTAssertEqual(LoudAlertWatcher.preset(info), ReportPreset(epochMs: 130_000, suggested: [.AUTO, .LASTWAGEN]))
        sv.settings.update { var n = $0; n.unterwegs = false; return n }
        rig.clock.advance(300_000)
        l.lastEvent = LiveEvent(startEpochMs: 500_000, endEpochMs: 502_000, lafMaxDb: 95, category: "loud_vehicle", wind: false, calibrated: false)
        sv.alerts.observe(l)
        XCTAssertEqual(rig.notifier.shown.count, 2, "«Unterwegs» off")
    }
}
