import Foundation
@testable import LessNoiseCore

/**
 * The contribution server as the Kotlin tests' FakeServer (android/app/src/test/…/upload/FakeServer.kt)
 * behaves, in memory: register → 201 {device_id, token}; site → 204; minutes/events/reports →
 * upsert by start (time for reports) with `refuseStarts` rejected; delete → 204; 401 for a wrong
 * token; `failNext` injects one status for (method, path suffix).
 */
final class FakeTransport: UploadTransport {
    struct Recorded { let method: String; let path: String; let json: String? }

    var requests: [Recorded] = []
    var refuseStarts: Set<String> = []
    var failNext: [(String, String, Int)] = []
    var reportsAvailable = true
    private var tokens: [String: String] = [:]
    private var stores: [String: [String: [String: JSONValue]]] = [:] // device → kind → key → record
    private var nextId = 1

    func send(_ request: UploadRequest) async throws -> UploadResponse {
        // The body as the server sees it: gunzip of what goes on the wire must equal the JSON text.
        if let body = request.body {
            let inflated = try inflateStoredOrNil(body)
            if let i = inflated { precondition(String(decoding: i, as: UTF8.self) == request.json, "gzip body differs from the JSON") }
        }
        requests.append(Recorded(method: request.method, path: request.path, json: request.json))
        if let i = failNext.firstIndex(where: { $0.0 == request.method && request.path.hasSuffix($0.1) }) {
            let f = failNext.remove(at: i)
            return UploadResponse(status: f.2, body: #"{"detail":"injected"}"#, retryAfter: f.2 == 429 ? "120" : nil)
        }
        if request.method == "POST" && request.path == "/v1/devices" {
            let id = "dev-\(nextId)", token = "tok-\(nextId)"
            nextId += 1
            tokens[id] = token
            return UploadResponse(status: 201, body: JSONValue.object(JSONObject([("device_id", .string(id)), ("token", .string(token))])).description)
        }
        let parts = request.path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        // "", "v1", "devices", id, [kind]
        guard parts.count >= 4, parts[1] == "v1", parts[2] == "devices" else { return UploadResponse(status: 404, body: #"{"detail":"not found"}"#) }
        let id = parts[3]
        let kind = parts.count > 4 ? parts[4] : ""
        let auth = request.headers.first { $0.0 == "Authorization" }?.1 ?? ""
        guard let t = tokens[id], auth == "Bearer \(t)" else { return UploadResponse(status: 401, body: #"{"detail":"invalid device or token"}"#) }
        let body = try request.json.map { try JSONValue.parse($0) }
        switch (request.method, kind) {
        case ("PUT", "site"): return UploadResponse(status: 204, body: "")
        case ("POST", "minutes"): return UploadResponse(status: 200, body: upsert(id, "minutes", body!.arr, 1440, key: { $0["start"].str }))
        case ("POST", "events"): return UploadResponse(status: 200, body: upsert(id, "events", body!.arr, 2000, key: { $0["start"].str }))
        case ("POST", "reports"):
            if !reportsAvailable { return UploadResponse(status: 404, body: #"{"detail":"not found"}"#) }
            return UploadResponse(status: 200, body: upsert(id, "reports", body!.arr, 50, key: { o in
                ["time", "category", "lat", "lon"].map { k -> String in
                    let v = o[k]
                    if case .string(let s) = v { return s }
                    return v.description
                }.joined(separator: "|")
            }))
        case ("DELETE", ""):
            tokens[id] = nil; stores[id] = nil
            return UploadResponse(status: 204, body: "")
        default:
            return UploadResponse(status: 405, body: #"{"detail":"method"}"#)
        }
    }

    private func upsert(_ id: String, _ kind: String, _ arr: [JSONValue], _ limit: Int, key: (JSONValue) -> String) -> String {
        if arr.count > limit { return #"{"detail":"at most \#(limit) records per request"}"# }
        var accepted = 0, duplicates = 0
        var rejected: [JSONValue] = []
        for (i, o) in arr.enumerated() {
            let start = key(o)
            let time = o.member("time")?.stringValue
            if refuseStarts.contains(start) || (time.map { refuseStarts.contains($0) } ?? false) {
                rejected.append(.object(JSONObject([
                    ("index", .int(i)), ("start", .string(start)),
                    ("reasons", .array([.object(JSONObject([("field", .string("laeq_db")), ("reason", .string("out of range"))]))])),
                ])))
            } else {
                if stores[id, default: [:]][kind, default: [:]].updateValue(o, forKey: start) == nil { accepted += 1 } else { duplicates += 1 }
            }
        }
        return JSONValue.object(JSONObject([("accepted", .int(accepted)), ("duplicates", .int(duplicates)), ("rejected", .array(rejected))])).description
    }
}

/** Inflates a gzip member made of stored deflate blocks (the Linux path of Gzip); nil for compressed blocks. */
func inflateStoredOrNil(_ gz: [UInt8]) throws -> [UInt8]? {
    precondition(gz.count >= 18 && gz[0] == 0x1f && gz[1] == 0x8b && gz[2] == 8)
    var p = 10
    var out: [UInt8] = []
    while true {
        let header = gz[p]
        if header & 0b110 != 0 { return nil } // not a stored block (Apple's Compression output)
        let len = Int(gz[p + 1]) | Int(gz[p + 2]) << 8
        let nlen = Int(gz[p + 3]) | Int(gz[p + 4]) << 8
        precondition(len == (~nlen & 0xffff), "LEN/NLEN")
        out += gz[(p + 5)..<(p + 5 + len)]
        p += 5 + len
        if header & 1 == 1 { break }
    }
    let crc = UInt32(gz[p]) | UInt32(gz[p + 1]) << 8 | UInt32(gz[p + 2]) << 16 | UInt32(gz[p + 3]) << 24
    let size = UInt32(gz[p + 4]) | UInt32(gz[p + 5]) << 8 | UInt32(gz[p + 6]) << 16 | UInt32(gz[p + 7]) << 24
    precondition(crc == Gzip.crc32(out) && size == UInt32(out.count) && p + 8 == gz.count, "gzip trailer")
    return out
}
