import Foundation

/** One HTTP request of the upload, fully built (method, path, headers, gzip body). */
public struct UploadRequest: Equatable {
    public let method: String
    /** Full URL: base + path. */
    public let url: String
    public let path: String
    public let headers: [(String, String)]
    /** The JSON text before compression (nil without body). */
    public let json: String?
    /** The gzip-compressed body that goes on the wire (nil without body). */
    public let body: [UInt8]?

    public static func == (a: UploadRequest, b: UploadRequest) -> Bool {
        a.method == b.method && a.url == b.url && a.path == b.path && a.json == b.json && a.body == b.body &&
            a.headers.map { $0.0 + ":" + $0.1 } == b.headers.map { $0.0 + ":" + $0.1 }
    }
}

/** The server's answer as the transport saw it. */
public struct UploadResponse: Equatable {
    public let status: Int
    public let body: String
    public let retryAfter: String?
    public init(status: Int, body: String, retryAfter: String? = nil) { self.status = status; self.body = body; self.retryAfter = retryAfter }
}

/**
 * The network: URLSession in the app (no redirects followed, no cookies, no cache, ephemeral
 * configuration; ios/DESIGN.md «Upload»), a fake in the tests. Throws [UploadFailure.noConnection]
 * when there is no answer at all.
 */
public protocol UploadTransport: AnyObject {
    func send(_ request: UploadRequest) async throws -> UploadResponse
}

/** A record the server refused for good: [index] into the batch, [reasons] as JSON text. */
public struct UploadRejected: Equatable {
    public let index: Int
    public let start: String?
    public let reasons: String
    public init(index: Int, start: String?, reasons: String) { self.index = index; self.start = start; self.reasons = reasons }
}

/** One upload's answer. */
public struct UploadResult: Equatable {
    public let accepted: Int
    public let duplicates: Int
    public let rejected: [UploadRejected]
}

/** Failures, split by what the caller should do (UploadClient.Failure in UploadClient.kt). */
public enum UploadFailure: Error, Equatable {
    /** 401: unknown device or wrong token. Do not retry. */
    case unauthorized(String)
    /** 429: retry later, at the earliest after [retryAfterS] seconds if the server said so. */
    case rateLimited(retryAfterS: Int?, String)
    /** Other 4xx: the request itself is wrong; retrying will not help. */
    case rejected(status: Int, String)
    /** 5xx, an unexpected status or an unreadable answer: retry later. */
    case server(status: Int, String)
    /** No answer (no network, timeout, TLS failure). */
    case noConnection(String)
}

/**
 * THE network code of the app (UploadClient.kt): one host, the calls of server/DESIGN.md §4, JSON
 * bodies always gzip-compressed, the device token as `Authorization: Bearer …`, a fixed User-Agent
 * without device details. Request building and answer parsing are here; sending is the transport's.
 */
public final class UploadClient {
    public static let MAX_MINUTES = 1440
    public static let MAX_EVENTS = 2000
    public static let MAX_REPORTS = 50
    /** The production host (DESIGN.md §4). */
    public static let DEFAULT_BASE = "https://api.lessnoise.ch"

    public struct Registration: Equatable {
        public let deviceId: String
        public let token: String
    }

    public enum ConfigError: Error { case notHttps(String) }

    private let base: String
    private let transport: UploadTransport
    private let userAgent: String
    /** The host every request goes to (for the settings screen). */
    public let host: String

    public init(baseUrl: String, transport: UploadTransport, userAgent: String = "LessNoise") throws {
        var b = baseUrl
        while b.hasSuffix("/") { b.removeLast() }
        guard let comps = URLComponents(string: b), let scheme = comps.scheme, let host = comps.host else { throw ConfigError.notHttps(baseUrl) }
        let loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        // HTTPS only; plain HTTP is accepted for a loopback test server and nothing else.
        guard scheme == "https" || (scheme == "http" && loopback) else { throw ConfigError.notHttps(baseUrl) }
        guard comps.path.isEmpty && comps.query == nil else { throw ConfigError.notHttps(baseUrl) }
        base = b
        self.transport = transport
        self.userAgent = userAgent
        self.host = host
    }

    /** `POST /v1/devices` (§4.1). No token yet. */
    public func register(appVersion: String, appBuild: Int) async throws -> Registration {
        let r = try await send("POST", "/v1/devices", token: nil, body: Payloads.register(appVersion, appBuild), expect: [201, 200])
        let o = try parseObject(r)
        guard let id = o["device_id"]?.stringValue ?? o["device_id"].map({ $0.description }), !id.trimmingCharacters(in: .whitespaces).isEmpty,
              let token = o["token"]?.stringValue ?? o["token"].map({ $0.description }), !token.trimmingCharacters(in: .whitespaces).isEmpty
        else { throw UploadFailure.server(status: r.status, "registration answer without device_id/token") }
        return Registration(deviceId: id, token: token)
    }

    /** `PUT /v1/devices/{id}/site` (§4.2). */
    public func putSite(_ deviceId: String, _ token: String, _ site: JSONValue) async throws {
        _ = try await send("PUT", "/v1/devices/\(UploadClient.seg(deviceId))/site", token: token, body: site, expect: [204, 200])
    }

    /** `POST /v1/devices/{id}/minutes` (§4.3), at most 1440 records. */
    public func postMinutes(_ deviceId: String, _ token: String, _ minutes: [JSONValue]) async throws -> UploadResult {
        precondition(minutes.count <= UploadClient.MAX_MINUTES)
        return try parseResult(try await send("POST", "/v1/devices/\(UploadClient.seg(deviceId))/minutes", token: token, body: .array(minutes), expect: [200]))
    }

    /** `POST /v1/devices/{id}/events` (§4.4), at most 2000 records. */
    public func postEvents(_ deviceId: String, _ token: String, _ events: [JSONValue]) async throws -> UploadResult {
        precondition(events.count <= UploadClient.MAX_EVENTS)
        return try parseResult(try await send("POST", "/v1/devices/\(UploadClient.seg(deviceId))/events", token: token, body: .array(events), expect: [200]))
    }

    /** `POST /v1/devices/{id}/reports` («Laut! Melden»), at most 50 reports. */
    public func postReports(_ deviceId: String, _ token: String, _ reports: [JSONValue]) async throws -> UploadResult {
        precondition(reports.count <= UploadClient.MAX_REPORTS)
        return try parseResult(try await send("POST", "/v1/devices/\(UploadClient.seg(deviceId))/reports", token: token, body: .array(reports), expect: [200, 201]))
    }

    /** `DELETE /v1/devices/{id}` (§4.5). Returns only after the server answered 204 (rows gone). */
    public func deleteDevice(_ deviceId: String, _ token: String) async throws {
        _ = try await send("DELETE", "/v1/devices/\(UploadClient.seg(deviceId))", token: token, body: nil, expect: [204, 200])
    }

    // ---- HTTP -------------------------------------------------------------------------------

    /** `URLEncoder.encode(s, "UTF-8").replace("+", "%20")`. */
    static func seg(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            switch b {
            case UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"):
                out.unicodeScalars.append(Unicode.Scalar(b))
            case UInt8(ascii: " "): out += "%20"
            default: out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    /** The request as it goes on the wire (exposed for the transport tests). */
    public func buildRequest(_ method: String, _ path: String, token: String?, body: JSONValue?) -> UploadRequest {
        var headers: [(String, String)] = [("User-Agent", userAgent), ("Accept", "application/json")]
        if let t = token { headers.append(("Authorization", "Bearer \(t)")) }
        var json: String? = nil
        var bytes: [UInt8]? = nil
        if let b = body {
            json = b.description
            bytes = Gzip.compress(Array(json!.utf8))
            headers.append(("Content-Type", "application/json"))
            headers.append(("Content-Encoding", "gzip"))
        }
        return UploadRequest(method: method, url: base + path, path: path, headers: headers, json: json, body: bytes)
    }

    private func send(_ method: String, _ path: String, token: String?, body: JSONValue?, expect: Set<Int>) async throws -> UploadResponse {
        let r = try await transport.send(buildRequest(method, path, token: token, body: body))
        if expect.contains(r.status) { return r }
        throw UploadClient.classify(r)
    }

    /** The failure for an unexpected status (UploadClient.kt `send`). */
    public static func classify(_ r: UploadResponse) -> UploadFailure {
        let d = detail(r.body)
        switch r.status {
        case 401: return .unauthorized("401 \(d)")
        case 429: return .rateLimited(retryAfterS: r.retryAfter.flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }, "429 \(d)")
        case 400...499: return .rejected(status: r.status, "\(r.status) \(d)")
        default: return .server(status: r.status, "\(r.status) \(d)")
        }
    }

    /** The server's `{"detail": …}` (as JSON text), shortened. Never contains our data. */
    static func detail(_ text: String) -> String {
        let d = (try? JSONValue.parse(text))?.member("detail")?.description
        return String((d ?? text).prefixUtf16(200))
    }

    private func parseObject(_ r: UploadResponse) throws -> JSONObject {
        guard let o = (try? JSONValue.parse(r.body))?.objectValue else { throw UploadFailure.server(status: r.status, "answer is not a JSON object") }
        return o
    }

    /** `{accepted, duplicates, rejected: [{index, start, reasons}]}` (missing counts → 0). */
    public static func parseResult(_ r: UploadResponse) throws -> UploadResult {
        guard let o = (try? JSONValue.parse(r.body))?.objectValue else { throw UploadFailure.server(status: r.status, "answer is not a JSON object") }
        var rejected: [UploadRejected] = []
        if let arr = o["rejected"]?.arrayValue {
            for el in arr {
                guard let x = el.objectValue, let idx = x["index"]?.intValue else {
                    throw UploadFailure.server(status: r.status, "unexpected upload answer")
                }
                let start: String?
                switch x["start"] {
                case nil, .null?: start = nil
                case let .string(s)?: start = s
                case let v?: start = v.description
                }
                rejected.append(UploadRejected(index: idx, start: start, reasons: String((x["reasons"]?.description ?? "").prefixUtf16(300))))
            }
        }
        return UploadResult(accepted: o["accepted"]?.intValue ?? 0, duplicates: o["duplicates"]?.intValue ?? 0, rejected: rejected)
    }

    private func parseResult(_ r: UploadResponse) throws -> UploadResult { try UploadClient.parseResult(r) }
}
