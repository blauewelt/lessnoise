import LessNoiseKit
import UserNotifications
import WebKit
import XCTest
@testable import WenigerLaerm

/** Records every request instead of sending it (no network in the tests). */
final class StubProtocol: URLProtocol {
    static var seen: [URLRequest] = []
    static var status = 200
    static var redirectTo: URL?
    static var body = Data(#"{"accepted":1,"duplicates":0,"rejected":[]}"#.utf8)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        StubProtocol.seen.append(request)
        if let to = StubProtocol.redirectTo {
            let r = HTTPURLResponse(url: request.url!, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": to.absoluteString])!
            client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: to), redirectResponse: r)
            client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
            client?.urlProtocolDidFinishLoading(self)
            return
        }
        let r = HTTPURLResponse(url: request.url!, statusCode: StubProtocol.status, httpVersion: "HTTP/1.1", headerFields: ["Set-Cookie": "a=b"])!
        client?.urlProtocol(self, didReceive: r, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: StubProtocol.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class TransportTests: XCTestCase {
    override func setUp() {
        StubProtocol.seen = []
        StubProtocol.status = 200
        StubProtocol.redirectTo = nil
    }

    private func client(_ t: UploadTransport) throws -> UploadClient { try UploadClient(baseUrl: UploadClient.DEFAULT_BASE, transport: t) }

    func testOnlyHttpsToTheOneHost() {
        func req(_ url: String) -> UploadRequest {
            UploadRequest(method: "GET", url: url, path: "/", headers: [], json: nil, body: nil)
        }
        XCTAssertNotNil(URLSessionTransport.urlRequest(req("https://api.lessnoise.ch/v1/devices")))
        XCTAssertNil(URLSessionTransport.urlRequest(req("http://api.lessnoise.ch/v1/devices")))
        XCTAssertNil(URLSessionTransport.urlRequest(req("https://api.lessnoise.ch:8443/v1/devices")))
        XCTAssertNil(URLSessionTransport.urlRequest(req("https://api.lessnoise.ch.evil.example/v1")))
        XCTAssertNil(URLSessionTransport.urlRequest(req("https://wenigerlaerm.ch/")))
    }

    func testRequestCarriesOnlyTheMinimalHeadersAndNoCookies() async throws {
        let t = URLSessionTransport(protocolClasses: [StubProtocol.self])
        let c = try client(t)
        let p = LocationNoise.Point(e: 2_683_300, n: 1_247_900, sigmaM: 50)
        _ = try await c.postMinutes("dev-1", "tok", [Payloads.pointJson(p)])
        _ = try await c.postMinutes("dev-1", "tok", [Payloads.pointJson(p)])
        XCTAssertEqual(StubProtocol.seen.count, 2)
        let r = StubProtocol.seen[1]
        XCTAssertEqual(r.url?.absoluteString, "https://api.lessnoise.ch/v1/devices/dev-1/minutes")
        XCTAssertEqual(r.value(forHTTPHeaderField: "User-Agent"), "LessNoise")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Encoding"), "gzip")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Accept-Language"), "de")
        XCTAssertNil(r.value(forHTTPHeaderField: "Cookie"), "the Set-Cookie of the first answer is never sent back")
    }

    func testRedirectIsNotFollowed() async throws {
        StubProtocol.redirectTo = URL(string: "https://evil.example/steal")
        let t = URLSessionTransport(protocolClasses: [StubProtocol.self])
        let r = try await t.send(try client(t).buildRequest("POST", "/v1/devices", token: nil, body: Payloads.register("0.1.0", 1)))
        XCTAssertEqual(r.status, 302)
        XCTAssertEqual(StubProtocol.seen.map { $0.url?.host }, ["api.lessnoise.ch"])
    }

    func testRefusedHostNeverReachesTheNetwork() async throws {
        let t = URLSessionTransport(protocolClasses: [StubProtocol.self])
        do {
            _ = try await t.send(UploadRequest(method: "GET", url: "https://evil.example/", path: "/", headers: [], json: nil, body: nil))
            XCTFail("sent")
        } catch let f as UploadFailure {
            XCTAssertEqual(f, .noConnection("host refused"))
        }
        XCTAssertTrue(StubProtocol.seen.isEmpty)
    }
}

final class StoresTests: XCTestCase {
    func testKeychainRoundTripThisDeviceOnly() throws {
        let k = KeychainStore(service: "ch.lessnoise.app.tests")
        k.wipe()
        // An unsigned simulator build may have no Keychain access (errSecMissingEntitlement).
        try XCTSkipUnless(k.setSecret("token", "abc"), "Keychain not available in this (unsigned) build")
        XCTAssertEqual(k.secret("token"), "abc")
        XCTAssertTrue(k.setSecret("token", "def"))
        XCTAssertEqual(k.secret("token"), "def")
        k.setSecret("token", nil)
        XCTAssertNil(k.secret("token"))
        k.setSecret("a", "1")
        k.wipe()
        XCTAssertNil(k.secret("a"))
    }

    func testProtectedFileStoreForPendingReports() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("queue-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = ProtectedFileStore(directory: dir)
        XCTAssertNil(f.secret("reports.pending"))
        XCTAssertTrue(f.setSecret("reports.pending", "[1]"))
        XCTAssertEqual(f.secret("reports.pending"), "[1]")
        XCTAssertTrue(f.setSecret("reports.pending", nil))
        XCTAssertNil(f.secret("reports.pending"))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), [])
        XCTAssertEqual(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testReportsGoToTheFileAndTokensToTheKeychain() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("route-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let files = ProtectedFileStore(directory: dir)
        let r = RoutingSecretStore(keychain: KeychainStore(service: "ch.lessnoise.app.tests2"), files: files)
        r.setSecret("reports.pending", "[]")
        XCTAssertEqual(files.secret("reports.pending"), "[]")
        r.setSecret("reports.pending", nil)
    }

    func testDeviceInfo() {
        XCTAssertFalse(DeviceInfo.model.isEmpty)
        XCTAssertFalse(DeviceInfo.version.isEmpty)
    }
}

final class BundleTests: XCTestCase {
    func testInfoPlistPromises() throws {
        let info = Bundle.main.infoDictionary ?? [:]
        XCTAssertEqual(info["UIBackgroundModes"] as? [String], ["audio"], "audio only: no location in the background")
        XCTAssertNotNil(info["NSMicrophoneUsageDescription"])
        XCTAssertNotNil(info["NSLocationWhenInUseUsageDescription"])
        XCTAssertNil(info["NSLocationAlwaysAndWhenInUseUsageDescription"], "never «Always»")
        XCTAssertNil(info["NSLocationAlwaysUsageDescription"])
        XCTAssertEqual(info["BGTaskSchedulerPermittedIdentifiers"] as? [String], [BackgroundUploads.identifier])
        XCTAssertEqual(info["CFBundleDisplayName"] as? String, "Weniger Lärm")
        XCTAssertEqual(Bundle.main.bundleIdentifier, "ch.lessnoise.app")
        XCTAssertNil(info["NSAppTransportSecurity"], "no ATS exceptions")
    }

    @MainActor
    func testCategoriesAreBundledFromTheAndroidAssets() throws {
        let m = try XCTUnwrap(AppModel.loadMapper())
        XCTAssertEqual(m.labels.count, 521)
        XCTAssertEqual(m.nameDe("loud_vehicle"), "Töff & Poser")
        XCTAssertNil(ClassifierFactory.make(mapper: m), "decision 5 pending: no classifier yet")
    }
}

final class WebKitTests: XCTestCase {
    func testContentRuleListCompilesInWebKit() async throws {
        let store = try XCTUnwrap(WKContentRuleListStore(url: FileManager.default.temporaryDirectory.appendingPathComponent("rules-\(UUID().uuidString)")))
        let list = try await store.compileContentRuleList(forIdentifier: "test", encodedContentRuleList: MapPolicy.contentRules())
        XCTAssertNotNil(list)
    }

    @MainActor
    func testMapWebViewIsLockedDown() throws {
        let c = MapWebController()
        let w = c.makeWebView()
        XCTAssertFalse(w.configuration.websiteDataStore.isPersistent)
        XCTAssertFalse(w.configuration.preferences.javaScriptCanOpenWindowsAutomatically)
        XCTAssertTrue(w.configuration.userContentController.userScripts.contains { $0.source.contains("geolocation") })
        // WebKit sees the async delegate methods under their completion-handler selectors.
        XCTAssertTrue(c.responds(to: NSSelectorFromString("webView:decidePolicyForNavigationAction:decisionHandler:")))
        XCTAssertTrue(c.responds(to: NSSelectorFromString("webView:decidePolicyForNavigationResponse:decisionHandler:")))
        XCTAssertTrue(c.responds(to: NSSelectorFromString("webView:requestMediaCapturePermissionForOrigin:initiatedByFrame:type:decisionHandler:")))
        XCTAssertTrue(c.responds(to: NSSelectorFromString("webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:")))
        XCTAssertTrue(w.navigationDelegate === c)
    }
}

final class NotificationTests: XCTestCase {
    func testSehrLautContentAndItsTap() {
        let e = LiveEvent(startEpochMs: 1_792_092_600_000, endEpochMs: 1_792_092_604_000, lafMaxDb: 84.4, category: "loud_vehicle", wind: false, calibrated: false)
        let c = LocalNotifications.content(e)
        XCTAssertEqual(c.title, "Sehr laut: Töff & Poser, 84 dB")
        XCTAssertEqual(c.body, "Antippen zum Melden")
        XCTAssertEqual(c.interruptionLevel, .timeSensitive)
        XCTAssertEqual(LoudAlertWatcher.preset(c.userInfo), ReportPreset(epochMs: 1_792_092_600_000, suggested: [.MOTORRAD, .POSER]))
    }
}
