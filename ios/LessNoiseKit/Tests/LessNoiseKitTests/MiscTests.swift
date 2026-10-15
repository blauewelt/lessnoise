import Foundation
import XCTest
@testable import LessNoiseKit

final class PreferencesTests: XCTestCase {
    func testUploadSnapshotRoundTripWithSecretsInTheKeychainOnly() {
        let kv = MemoryKeyValueStore()
        let secrets = MemorySecretStore()
        let a = PersistentUploadStore(kv: kv, secrets: secrets)
        a.update {
            var n = $0
            n.enabled = true; n.shareDecision = .YES; n.wifiOnly = true; n.explanationSeen = true
            n.site = SiteSettings(placement: SiteSettings.PLACEMENT_BALCONY, floor: 3, streetFacing: false, note: "Hof «A»")
            n.deviceId = "dev-1"; n.token = "secret-token"; n.sendFloorMs = 11; n.minutesSentUpToMs = 22; n.eventsSentUpToMs = 33
            n.lastSiteJson = #"{"a":1}"#; n.lastSuccessAtMs = 44; n.lastError = "x"; n.authFailed = false
            n.refused = [RefusedRecord(kind: "minute", startEpochMs: 55, reason: "too old")]; n.refusedTotal = 1; n.sigmaM = 100
            n.noiseSecret = "c2VjcmV0"
            return n
        }
        let b = PersistentUploadStore(kv: kv, secrets: secrets)
        XCTAssertEqual(b.state, a.state)
        XCTAssertFalse(kv.all.values.joined().contains("secret-token"))
        XCTAssertFalse(kv.all.values.joined().contains("c2VjcmV0"))
        XCTAssertEqual(secrets.secret("upload.token"), "secret-token")

        // The Keychain item is gone (e.g. a backup restored onto another phone): «Neu starten».
        secrets.setSecret("upload.token", nil)
        let c = PersistentUploadStore(kv: kv, secrets: secrets)
        XCTAssertNil(c.state.token)
        XCTAssertTrue(c.state.authFailed)
        XCTAssertEqual(shareStatus(c.state, running: false, measurementStartMs: 0, hasPoint: false, locate: .idle, online: true, hm: { _ in "" })?.text,
                       ShareTexts.STATUS_AUTH)
    }

    func testNoiseSecretIsCreatedOnceAnd32Bytes() {
        let store = PersistentUploadStore(kv: MemoryKeyValueStore(), secrets: MemorySecretStore())
        let s1 = store.noiseSecret()
        XCTAssertEqual(s1.count, 32)
        XCTAssertEqual(store.noiseSecret(), s1)
    }

    func testSettingsAreClampedAndRemembered() {
        let kv = MemoryKeyValueStore()
        let s = SettingsStore(kv: kv)
        XCTAssertEqual(s.state, AppSettings())
        s.update { var n = $0; n.eventExcessDb = 8.5; n.eventMinLevelDb = 35; n.unterwegs = true; return n }
        XCTAssertEqual(SettingsStore(kv: kv).state.eventExcessDb, 8.5)
        XCTAssertTrue(SettingsStore(kv: kv).state.unterwegs)
        kv.set("settings.v1", #"{"event_excess_db":99,"event_min_level_db":1}"#)
        XCTAssertEqual(SettingsStore(kv: kv).state.eventExcessDb, AppSettings.EVENT_EXCESS_MAX)
        XCTAssertEqual(SettingsStore(kv: kv).state.eventMinLevelDb, AppSettings.EVENT_MIN_LEVEL_MIN)
    }

    func testReportJsonKeepsAndroidsKeys() {
        let p = PendingReport(epochMs: 1_000, timeIso: "2026-10-15T21:30:00+02:00", lat: 47.37690, lon: 8.5417, accuracyM: 12.5, category: "auto", cell: "h26833_12479")
        let text = ReportJson.pending([p])
        XCTAssertEqual(text, #"[{"t":1000,"iso":"2026-10-15T21:30:00+02:00","lat":47.3769,"lon":8.5417,"acc":12.5,"cat":"auto","cell":"h26833_12479"}]"#)
        XCTAssertEqual(ReportJson.parsePending(text), [p])
        XCTAssertEqual(ReportJson.parseSent(ReportJson.sent([p.toSent()])), [p.toSent()])
        XCTAssertEqual(ReportJson.parsePending("garbage"), [])
    }
}

final class CalibrationTests: XCTestCase {
    func feedCal(_ rig: Rig, seconds: Double, amp: Float) {
        var noise = Noise(7)
        for _ in 0..<Int(seconds * 10) {
            let b = noise.block(4800, amp: amp)
            b.withUnsafeBufferPointer { rig.calInput.handler?.audioBlock($0) }
        }
    }

    func testReferenceMeterSaveMovesTheFloorAndOffersTheReevaluation() throws {
        let rig = try Rig()
        let sv = rig.services
        try sv.measurements.insert(minute(1_000_000))
        let cal = sv.calibration
        cal.start(.REFERENCE)
        XCTAssertEqual(cal.state.running, .REFERENCE)
        XCTAssertEqual(rig.calInput.starts, 1)
        feedCal(rig, seconds: 31, amp: 0.05)
        XCTAssertTrue(eventually { cal.state.running == nil })
        XCTAssertEqual(rig.calInput.stops, 1)
        guard let r = cal.state.referenceResult else { return XCTFail(cal.state.message ?? "no result") }
        XCTAssertEqual(r.measuredSeconds, 30, accuracy: 0.01)
        // The meter showed 65 dB(A).
        let expectedOffset = 65 - r.rawLaeqDb
        cal.saveReference(65, notes: "Testo 815")
        let active = cal.active!
        XCTAssertEqual(active.offsetDb, expectedOffset, accuracy: 1e-9)
        XCTAssertEqual(active.method, CalibrationEntity.METHOD_REFERENCE)
        XCTAssertEqual(active.audioSource, "IOS")
        XCTAssertEqual(active.deviceModel, "iPhone16,1")
        let delta = expectedOffset - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        XCTAssertEqual(sv.settings.state.eventMinLevelDb, EventFloor.roundToHalf(30 + delta).clamped(20, 70))
        XCTAssertEqual(cal.state.message, CalibrationTexts.saved(EventFloor.adjust(30, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, expectedOffset, 20, 70)))
        XCTAssertEqual(cal.state.recalOffer?.minutes, 1)
        let done = expectation(description: "recal")
        cal.confirmRecalibration { done.fulfill() }
        wait(for: [done], timeout: 5)
        XCTAssertTrue(try sv.measurements.allMinutes()[0].calibrated)
        // The next measurement is calibrated.
        XCTAssertTrue(sv.session.start())
        XCTAssertTrue(sv.session.live.calibrated)
        sv.session.stop()
    }

    func testInterruptionOrBackgroundAbortsAndNothingCanBeSaved() throws {
        let rig = try Rig()
        let cal = rig.services.calibration
        cal.start(.CALIBRATOR)
        feedCal(rig, seconds: 3, amp: 0.05)
        rig.calInput.handler?.audioInterrupted(true)
        XCTAssertTrue(eventually { cal.state.running == nil })
        XCTAssertEqual(cal.state.message, CalibrationRun.REASON_SILENCED)
        XCTAssertNil(cal.state.calibratorResult)
        cal.start(.REFERENCE)
        rig.services.background()
        XCTAssertNil(cal.state.running)
        XCTAssertEqual(cal.state.message, CalibrationRun.REASON_BACKGROUND)
    }

    func testNotWhileMeasuringAndManualAndReset() throws {
        let rig = try Rig()
        let sv = rig.services
        sv.session.start()
        sv.calibration.start(.REFERENCE)
        XCTAssertEqual(sv.calibration.state.message, CalibrationTexts.STOP_FIRST)
        XCTAssertEqual(rig.calInput.starts, 0)
        sv.session.stop()
        sv.calibration.saveManual(140, notes: "")
        XCTAssertEqual(sv.calibration.active?.warnings, "IMPLAUSIBLE_OFFSET")
        sv.calibration.resetToDefault()
        XCTAssertEqual(sv.calibration.state.message, CalibrationTexts.RESET)
        XCTAssertFalse(sv.calibration.active!.isCalibrated)
        XCTAssertEqual(CalibrationTexts.activeLine(sv.calibration.active), "Nicht kalibriert (Standardwert 112,35 dB).")
        XCTAssertEqual(sv.calibration.history.count, 2)
    }
}

private extension Double {
    func clamped(_ lo: Double, _ hi: Double) -> Double { Swift.min(Swift.max(self, lo), hi) }
}

final class MapAndUiTests: XCTestCase {
    func testNavigationDecisions() {
        XCTAssertEqual(MapPolicy.decide("https://wenigerlaerm.ch/karte.html#lat=47.37690&lon=8.54170&z=16", mainFrame: true, userTapped: false), .allow)
        XCTAssertEqual(MapPolicy.decide("https://wenigerlaerm.ch/datenschutz.html", mainFrame: true, userTapped: true),
                       .openExternally("https://wenigerlaerm.ch/datenschutz.html"))
        XCTAssertEqual(MapPolicy.decide("https://evil.example/", mainFrame: true, userTapped: false), .cancel)
        XCTAssertEqual(MapPolicy.decide("javascript:alert(1)", mainFrame: true, userTapped: true), .cancel)
        XCTAssertEqual(MapPolicy.decide("https://wmts.geo.admin.ch/1.0.0/x.png", mainFrame: false, userTapped: false), .allow)
        XCTAssertEqual(MapPolicy.decide("https://tracker.example/x.js", mainFrame: false, userTapped: false), .cancel)
    }

    func testContentRulesAreValidJsonBlockingAllButTheFourHosts() throws {
        let json = MapPolicy.contentRules()
        let rules = try XCTUnwrap(try JSONValue.parse(json).arrayValue)
        XCTAssertEqual(rules.count, 1 + MapLinks.ALLOWED_HOSTS.count + 1)
        XCTAssertEqual(rules[0].member("action")?.member("type")?.stringValue, "block")
        let filters = rules.dropFirst().compactMap { $0.member("trigger")?.member("url-filter")?.stringValue }
        XCTAssertTrue(filters.contains(#"^https://wenigerlaerm\.ch/"#), "\(filters)")
        XCTAssertTrue(filters.contains(#"^https://wmts\.geo\.admin\.ch/"#))
        for f in filters { XCTAssertFalse(f.contains("|"), "WebKit's regex subset has no alternation") }
        // The filter regexes match what they should (NSRegularExpression as a stand-in for WebKit's).
        func matches(_ url: String) -> Bool {
            filters.contains { f in (try? NSRegularExpression(pattern: f))?.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)) != nil }
        }
        XCTAssertTrue(matches("https://api.lessnoise.ch/v1/map/cells.json"))
        XCTAssertFalse(matches("https://api.lessnoise.ch.evil.example/x"))
        XCTAssertFalse(matches("http://wenigerlaerm.ch/karte.html"))
        XCTAssertFalse(matches("https://wenigerlaerm.ch:8443/karte.html"))
    }

    func testTabNavigatorBackOnlyForTabsOpenedFromInside() {
        let t = TabNavigator()
        XCTAssertEqual(t.current, .MEASURE)
        t.select(.SHARE)
        XCTAssertFalse(t.showsBack, "tab bar: no back button")
        t.open(.MAP)
        XCTAssertTrue(t.showsBack)
        t.back()
        XCTAssertEqual(t.current, .SHARE)
        XCTAssertFalse(t.showsBack)
        let saved = t.history.save()
        XCTAssertEqual(TabNavigator(restore: saved).current, .SHARE)
        XCTAssertEqual(appScreens(uploadAvailable: true, mapAvailable: true).map(screenLabel), ["Messen", "Messungen", "Karte", "Teilen", "Einstellungen"])
    }

    func testGermanFormatting() {
        let z = TimeZone(identifier: "Europe/Zurich")!
        let t = 1_792_092_600_000 // Thu 2026-10-15 21:30 CEST
        XCTAssertEqual(Fmt.hm(t, z), "21:30")
        XCTAssertEqual(Fmt.dateTime(t, z), "15.10.2026 21:30")
        XCTAssertEqual(Fmt.weekdayDateTime(t, z), "Do 15.10. 21:30")
        XCTAssertEqual(Fmt.spanLabel(t, t + 3_600_000, z), "Do 15.10. 21:30–22:30")
        XCTAssertEqual(Fmt.spanLabel(t, t + 10 * 3_600_000, z), "Do 15.10. 21:30 – Fr 16.10. 07:30")
        XCTAssertEqual(Fmt.db(45.15), "45,2")
        XCTAssertEqual(Fmt.db(.nan), "–")
        XCTAssertEqual(Fmt.db(nil), "–")
        XCTAssertEqual(Fmt.db(112.3501, 2), "112,35")
        XCTAssertEqual(Fmt.hoursMinutes(185 * 60_000), "3 h 05 min")
        XCTAssertEqual(Fmt.hoursMinutes(45 * 60_000), "45 min")
        XCTAssertEqual(Fmt.duration(12.5), "12,5 s")
        XCTAssertEqual(Fmt.duration(185), "3 min 05 s")
        XCTAssertEqual(Fmt.eventsPerHour(3.25), "3,3")
        XCTAssertEqual(Fmt.eventsPerHour(12.6), "13")
        XCTAssertEqual(Fmt.parseDb(" 65,5 "), 65.5)
        XCTAssertNil(Fmt.parseDb("abc"))
        XCTAssertEqual(MeasureTexts.runningSince(Fmt.hm(t, z)), "Läuft seit 21:30. Der Bildschirm darf aus sein.")
        // Winter time and a date before 1970 are handled by the same arithmetic.
        XCTAssertEqual(Fmt.dateTime(1_798_761_600_000, z), "01.01.2027 01:00")
        XCTAssertEqual(Fmt.weekday(0, TimeZone(identifier: "UTC")!), "Do")
    }

    func testCategoryNamesAndTheBundledFiles() throws {
        // The app bundles android/app/src/main/assets/categories.json and yamnet_labels.txt as they are.
        let assets = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../android/app/src/main/assets").standardized
        let json = try String(contentsOf: assets.appendingPathComponent("categories.json"), encoding: .utf8)
        let labels = try String(contentsOf: assets.appendingPathComponent("yamnet_labels.txt"), encoding: .utf8)
        let mapper = try XCTUnwrap(Categories.mapper(categoriesJson: json, labelsText: labels))
        XCTAssertEqual(mapper.labels.count, 521)
        XCTAssertEqual(Categories.name("loud_vehicle", mapper), "Töff & Poser")
        XCTAssertEqual(Categories.name("road_traffic", mapper), "Strassenverkehr")
        XCTAssertEqual(Categories.name("wind", mapper), "Wind")
        XCTAssertEqual(Categories.name(nil, mapper), "–")
        XCTAssertEqual(mapper.bucketIds.last, "unclassified")
        XCTAssertEqual(Categories.rgb("loud_vehicle"), 0xD1495B)
    }

    func testReportConfirmTextAsAndroid() {
        let z = TimeZone(identifier: "Europe/Zurich")!
        XCTAssertEqual(ReportTexts.confirmText(.MOTORRAD, 1_792_092_600_000, 12, z), "Meldung: Töff, 21:30, Standort ± 12 m – senden?")
        XCTAssertTrue(ReportTexts.CONSENT_TEXT.contains("wer oft vom gleichen Ort meldet, macht ihn sichtbar"))
    }
}
