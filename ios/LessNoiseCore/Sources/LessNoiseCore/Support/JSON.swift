import Foundation

/**
 * A JSON value with ordered object keys, printed exactly as kotlinx.serialization prints a
 * `JsonElement` (compact, keys in insertion order, numbers like the JVM's `toString()`), so that a
 * request body built here is byte-identical to the Android app's (golden-tested).
 */
public indirect enum JSONValue: Equatable, CustomStringConvertible {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object(JSONObject)

    public var description: String {
        var out = ""
        write(to: &out)
        return out
    }

    public func write(to out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .double(let d): out += JavaFormat.doubleToString(d)
        case .string(let s): JSONValue.quote(s, into: &out)
        case .array(let a):
            out += "["
            for (i, v) in a.enumerated() {
                if i > 0 { out += "," }
                v.write(to: &out)
            }
            out += "]"
        case .object(let o): o.write(to: &out)
        }
    }

    /** kotlinx `printQuoted`: escapes `"`, `\` and control characters; everything else as is (UTF-8). */
    static func quote(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if u.value < 0x20 {
                    out += String(format: "\\u%04x", u.value)
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        out += "\""
    }

    // Accessors (nil when the type does not match).
    public var objectValue: JSONObject? { if case .object(let o) = self { return o }; return nil }
    public var arrayValue: [JSONValue]? { if case .array(let a) = self { return a }; return nil }
    public var stringValue: String? { if case .string(let s) = self { return s }; return nil }
    public var boolValue: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var isNull: Bool { if case .null = self { return true }; return false }
    /** Numbers (int or double) as Double. */
    public var doubleValue: Double? {
        switch self {
        case .int(let i): return Double(i)
        case .double(let d): return d
        default: return nil
        }
    }
    public var intValue: Int? {
        switch self {
        case .int(let i): return i
        case .double(let d): return d.rounded() == d && d.magnitude < 9e15 ? Int(d) : nil
        default: return nil
        }
    }
    /** The member [key] of an object (nil if this is not an object or has no such key). */
    public func member(_ key: String) -> JSONValue? { objectValue?[key] }
    /** The element [index] of an array (nil if out of range or not an array). */
    public func element(_ index: Int) -> JSONValue? {
        guard let a = arrayValue, index >= 0, index < a.count else { return nil }
        return a[index]
    }
}

/** A JSON object that keeps its keys in insertion order (kotlinx's `buildJsonObject`). */
public struct JSONObject: Equatable {
    public private(set) var keys: [String] = []
    private var values: [String: JSONValue] = [:]

    public init() {}

    public init(_ pairs: [(String, JSONValue)]) {
        for (k, v) in pairs { self[k] = v }
    }

    public subscript(key: String) -> JSONValue? {
        get { values[key] }
        set {
            if let v = newValue {
                if values[key] == nil { keys.append(key) }
                values[key] = v
            } else if values.removeValue(forKey: key) != nil {
                keys.removeAll { $0 == key }
            }
        }
    }

    public var count: Int { keys.count }
    public var isEmpty: Bool { keys.isEmpty }
    public var entries: [(key: String, value: JSONValue)] { keys.map { ($0, values[$0]!) } }

    /** `put(key, value)`: a new key goes to the end, an existing one keeps its place. */
    public mutating func put(_ key: String, _ value: JSONValue) { self[key] = value }

    public func write(to out: inout String) {
        out += "{"
        for (i, k) in keys.enumerated() {
            if i > 0 { out += "," }
            JSONValue.quote(k, into: &out)
            out += ":"
            values[k]!.write(to: &out)
        }
        out += "}"
    }

    public static func == (a: JSONObject, b: JSONObject) -> Bool { a.keys == b.keys && a.values == b.values }
}

public enum JSONParseError: Error, Equatable {
    case unexpected(Int)
}

extension JSONValue {
    /**
     * A strict parser (RFC 8259). Numbers without fraction or exponent become `.int` when they fit,
     * all others `.double` (parsed by Swift's correctly rounded `Double(_:)`).
     */
    public static func parse(_ text: String) throws -> JSONValue {
        var p = Parser(Array(text.utf8))
        p.skipWs()
        let v = try p.value()
        p.skipWs()
        guard p.i == p.b.count else { throw JSONParseError.unexpected(p.i) }
        return v
    }

    private struct Parser {
        let b: [UInt8]
        var i = 0
        init(_ b: [UInt8]) { self.b = b }

        mutating func skipWs() {
            while i < b.count, b[i] == 0x20 || b[i] == 0x0A || b[i] == 0x0D || b[i] == 0x09 { i += 1 }
        }

        mutating func expect(_ s: String) throws {
            for c in s.utf8 {
                guard i < b.count, b[i] == c else { throw JSONParseError.unexpected(i) }
                i += 1
            }
        }

        mutating func value() throws -> JSONValue {
            guard i < b.count else { throw JSONParseError.unexpected(i) }
            switch b[i] {
            case UInt8(ascii: "{"):
                i += 1
                var o = JSONObject()
                skipWs()
                if i < b.count, b[i] == UInt8(ascii: "}") { i += 1; return .object(o) }
                while true {
                    skipWs()
                    guard i < b.count, b[i] == UInt8(ascii: "\"") else { throw JSONParseError.unexpected(i) }
                    let k = try string()
                    skipWs()
                    try expect(":")
                    skipWs()
                    o[k] = try value()
                    skipWs()
                    guard i < b.count else { throw JSONParseError.unexpected(i) }
                    if b[i] == UInt8(ascii: ",") { i += 1; continue }
                    if b[i] == UInt8(ascii: "}") { i += 1; return .object(o) }
                    throw JSONParseError.unexpected(i)
                }
            case UInt8(ascii: "["):
                i += 1
                var a: [JSONValue] = []
                skipWs()
                if i < b.count, b[i] == UInt8(ascii: "]") { i += 1; return .array(a) }
                while true {
                    skipWs()
                    a.append(try value())
                    skipWs()
                    guard i < b.count else { throw JSONParseError.unexpected(i) }
                    if b[i] == UInt8(ascii: ",") { i += 1; continue }
                    if b[i] == UInt8(ascii: "]") { i += 1; return .array(a) }
                    throw JSONParseError.unexpected(i)
                }
            case UInt8(ascii: "\""): return .string(try string())
            case UInt8(ascii: "t"): try expect("true"); return .bool(true)
            case UInt8(ascii: "f"): try expect("false"); return .bool(false)
            case UInt8(ascii: "n"): try expect("null"); return .null
            default: return try number()
            }
        }

        mutating func number() throws -> JSONValue {
            let start = i
            var isDouble = false
            if i < b.count, b[i] == UInt8(ascii: "-") { i += 1 }
            while i < b.count {
                let c = b[i]
                if c >= 0x30 && c <= 0x39 { i += 1; continue }
                if c == UInt8(ascii: ".") || c == UInt8(ascii: "e") || c == UInt8(ascii: "E") || c == UInt8(ascii: "+") || c == UInt8(ascii: "-") {
                    isDouble = true; i += 1; continue
                }
                break
            }
            guard i > start, let s = String(bytes: b[start..<i], encoding: .utf8) else { throw JSONParseError.unexpected(start) }
            if !isDouble, let v = Int(s) { return .int(v) }
            guard let d = Double(s) else { throw JSONParseError.unexpected(start) }
            return .double(d)
        }

        mutating func string() throws -> String {
            i += 1 // opening quote
            var bytes: [UInt8] = []
            while i < b.count {
                let c = b[i]
                if c == UInt8(ascii: "\"") {
                    i += 1
                    return String(decoding: bytes, as: UTF8.self)
                }
                if c == UInt8(ascii: "\\") {
                    i += 1
                    guard i < b.count else { break }
                    let e = b[i]
                    i += 1
                    switch e {
                    case UInt8(ascii: "\""): bytes.append(0x22)
                    case UInt8(ascii: "\\"): bytes.append(0x5C)
                    case UInt8(ascii: "/"): bytes.append(0x2F)
                    case UInt8(ascii: "b"): bytes.append(0x08)
                    case UInt8(ascii: "f"): bytes.append(0x0C)
                    case UInt8(ascii: "n"): bytes.append(0x0A)
                    case UInt8(ascii: "r"): bytes.append(0x0D)
                    case UInt8(ascii: "t"): bytes.append(0x09)
                    case UInt8(ascii: "u"):
                        var code = try hex4()
                        if code >= 0xD800 && code < 0xDC00, i + 1 < b.count, b[i] == UInt8(ascii: "\\"), b[i + 1] == UInt8(ascii: "u") {
                            i += 2
                            let low = try hex4()
                            code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)
                        }
                        guard let scalar = Unicode.Scalar(code) else { throw JSONParseError.unexpected(i) }
                        bytes.append(contentsOf: Array(String(Character(scalar)).utf8))
                    default: throw JSONParseError.unexpected(i)
                    }
                    continue
                }
                bytes.append(c)
                i += 1
            }
            throw JSONParseError.unexpected(i)
        }

        mutating func hex4() throws -> UInt32 {
            guard i + 4 <= b.count, let s = String(bytes: b[i..<(i + 4)], encoding: .utf8), let v = UInt32(s, radix: 16) else {
                throw JSONParseError.unexpected(i)
            }
            i += 4
            return v
        }
    }
}
