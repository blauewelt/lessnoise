import Foundation
import LessNoiseKit
import Network

/**
 * THE network code of the upload on iOS (ios/DESIGN.md §5): the requests UploadClient builds, sent
 * with an ephemeral URLSession (no cookies, no cache, no credential storage), to one host only
 * (https://api.lessnoise.ch; anything else is refused before it leaves the phone), redirects
 * refused (a redirect could point elsewhere), App Transport Security's default (TLS 1.2+). The
 * headers are UploadClient's (User-Agent «LessNoise», no device details) plus a fixed
 * «Accept-Language: de» so that the phone's language settings are not sent either.
 */
final class URLSessionTransport: NSObject, UploadTransport, URLSessionTaskDelegate {
    static let allowedHost = "api.lessnoise.ch"

    private let protocolClasses: [AnyClass]?
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.httpCookieAcceptPolicy = .never
        c.httpShouldSetCookies = false
        c.httpCookieStorage = nil
        c.urlCache = nil
        c.urlCredentialStorage = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = 30
        c.timeoutIntervalForResource = 90
        c.waitsForConnectivity = false
        c.tlsMinimumSupportedProtocolVersion = .TLSv12
        if let p = protocolClasses { c.protocolClasses = p }
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    /** [protocolClasses]: tests put a URLProtocol stub here; the app passes nil. */
    init(protocolClasses: [AnyClass]? = nil) {
        self.protocolClasses = protocolClasses
        super.init()
    }

    /** The URLRequest for [r], or nil if it does not go to the one allowed host over https. */
    static func urlRequest(_ r: UploadRequest) -> URLRequest? {
        guard let url = URL(string: r.url), url.scheme == "https", url.host == allowedHost, url.port == nil, url.user == nil else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = r.method
        req.httpShouldHandleCookies = false
        for (k, v) in r.headers { req.setValue(v, forHTTPHeaderField: k) }
        req.setValue("de", forHTTPHeaderField: "Accept-Language")
        if let b = r.body { req.httpBody = Data(b) }
        return req
    }

    func send(_ r: UploadRequest) async throws -> UploadResponse {
        guard let req = URLSessionTransport.urlRequest(r) else { throw UploadFailure.noConnection("host refused") }
        let result: (Data, URLResponse)
        do {
            result = try await session.data(for: req)
        } catch {
            throw UploadFailure.noConnection((error as NSError).localizedDescription)
        }
        let (data, response) = result
        guard let http = response as? HTTPURLResponse else { throw UploadFailure.noConnection("no HTTP answer") }
        return UploadResponse(status: http.statusCode, body: String(decoding: data, as: UTF8.self), retryAfter: http.value(forHTTPHeaderField: "Retry-After"))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

/** Online / expensive (cellular, hotspot, Low Data Mode) from NWPathMonitor. */
final class NetworkMonitor: NetworkStatus {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "ch.lessnoise.network")
    private let state = Locked<(online: Bool, expensive: Bool)>((true, false))
    let changes = Observers()

    init() {
        monitor.pathUpdateHandler = { [weak self] p in
            guard let self = self else { return }
            let n = (online: p.status == .satisfied, expensive: p.isExpensive || p.isConstrained)
            let old = self.state.get
            self.state.set(n)
            if old != n { self.changes.notify() }
        }
        monitor.start(queue: queue)
    }

    var online: Bool { state.get.online }
    var expensive: Bool { state.get.expensive }
}
