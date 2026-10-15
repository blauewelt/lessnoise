import Foundation

/**
 * The placement step without a map: the contributor types or pastes where the phone is, and the
 * app turns it into an LV95 point on the phone (upload/CellInput.kt). Accepted, in this order: a
 * hectare id (`h26833_12479`), LV95 `2'683'304, 1'247'925` (E, N, either order), LV03
 * `683'304, 247'925`, WGS84 decimal degrees `47.3769, 8.5417` (either order, decimal commas
 * allowed). Nothing typed here is stored or sent; the caller moves the point by the location
 * noise and keeps only the shifted point.
 */
public enum CellInput {
    public enum Parsed: Equatable {
        /** [cell]: the hectare of the true point; [e]/[n]: the true LV95 point in metres. */
        case ok(cell: String, how: String, e: Double, n: Double)
        case error(String)
    }

    public static func parse(_ text: String) -> Parsed {
        let t = Payloads.kotlinTrim(text)
        if t.isEmpty { return .error("Bitte Koordinaten eingeben.") }
        if Lv95.isCellId(t.lowercased()) {
            let sw = Lv95.parseCell(t.lowercased())!
            return check(sw.e + 50, sw.n + 50, "Zelle")
        }
        // Swiss thousands separators (2'683'304) and spaces inside numbers are removed first.
        let cleaned = removeDigitSeparators(t)
        let nums = numbers(cleaned)
        if nums.count != 2 { return .error("Zwei Zahlen, z. B. «47.3769, 8.5417».") }
        let a = nums[0], b = nums[1]
        if (2_400_000.0...2_900_000.0).contains(a) && (1_000_000.0...1_400_000.0).contains(b) { return check(a, b, "LV95") }
        if (2_400_000.0...2_900_000.0).contains(b) && (1_000_000.0...1_400_000.0).contains(a) { return check(b, a, "LV95") }
        // LV03 y, x: add the LV95 false origin
        if (400_000.0...900_000.0).contains(a) && (0.0...400_000.0).contains(b) { return check(a + 2_000_000, b + 1_000_000, "LV03") }
        if isWgs84Switzerland(a, b) { return fromLatLon(a, b) }
        if isWgs84Switzerland(b, a) { return fromLatLon(b, a) }
        return .error("Diese Koordinaten liegen nicht in der Schweiz.")
    }

    /**
     * WGS84 → the LV95 point and the hectare that contains it, computed on the phone. Used for
     * pasted WGS84 coordinates and for the location fixes. Stores nothing.
     */
    public static func fromLatLon(_ lat: Double, _ lon: Double, how: String = "WGS84") -> Parsed {
        if isWgs84Switzerland(lat, lon) {
            let p = Lv95.wgs84ToLv95(lat, lon)
            return check(p.e, p.n, how)
        }
        return .error("Dieser Standort liegt nicht in der Schweiz.")
    }

    private static func isWgs84Switzerland(_ lat: Double, _ lon: Double) -> Bool {
        lat >= 45.0 && lat <= 48.5 && lon >= 5.0 && lon <= 11.0
    }

    private static func check(_ e: Double, _ n: Double, _ how: String) -> Parsed {
        let cell = Lv95.lv95ToCell(e, n)
        return Lv95.isInServerRange(cell) ? .ok(cell: cell, how: how, e: e, n: n) : .error("Dieser Ort liegt nicht in der Schweiz.")
    }

    /** `(?<=\d)['’ʼ  ](?=\d)` → "". */
    private static func removeDigitSeparators(_ s: String) -> String {
        let seps: Set<Unicode.Scalar> = ["'", "\u{2019}", "\u{02BC}", "\u{2009}", "\u{202F}"]
        let u = Array(s.unicodeScalars)
        var out = String.UnicodeScalarView()
        for i in u.indices {
            if seps.contains(u[i]), i > 0, i + 1 < u.count, isAsciiDigit(u[i - 1]), isAsciiDigit(u[i + 1]) { continue }
            out.append(u[i])
        }
        return String(out)
    }

    @inline(__always) private static func isAsciiDigit(_ u: Unicode.Scalar) -> Bool { u.value >= 0x30 && u.value <= 0x39 }

    /** All matches of `-?\d+(?:[.,]\d+)?`, with ',' read as '.'. */
    private static func numbers(_ s: String) -> [Double] {
        let u = Array(s.unicodeScalars)
        var out: [Double] = []
        var i = 0
        while i < u.count {
            var j = i
            var text = ""
            if u[j] == "-" && j + 1 < u.count && isAsciiDigit(u[j + 1]) { text = "-"; j += 1 }
            if j < u.count && isAsciiDigit(u[j]) {
                while j < u.count && isAsciiDigit(u[j]) { text.unicodeScalars.append(u[j]); j += 1 }
                if j + 1 < u.count && (u[j] == "." || u[j] == ",") && isAsciiDigit(u[j + 1]) {
                    text += "."
                    j += 1
                    while j < u.count && isAsciiDigit(u[j]) { text.unicodeScalars.append(u[j]); j += 1 }
                }
                out.append(Double(text)!)
                i = j
            } else {
                i += 1
            }
        }
        return out
    }
}
