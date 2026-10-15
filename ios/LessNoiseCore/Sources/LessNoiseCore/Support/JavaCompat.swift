import Foundation

// The Kotlin code relies on a few JVM semantics that Swift does not share by default. They are
// reproduced here exactly, so that the port prints and rounds the same numbers:
//
// - `Math.round` / `roundToInt` / `roundToLong`: round half up (towards +∞), not away from zero;
// - `Double.toInt()` / `toLong()`: truncation, saturating, NaN → 0 (Swift's Int(_:) traps);
// - `String.format(Locale.ROOT, "%.Nf", v)`: HALF_UP on the shortest decimal representation of
//   v (so 0.15 → "0.2" and 7.25 → "7.3", where C's printf gives "0.1" and "7.2");
// - `Double.toString()` (JDK 19+: shortest representation; plain for 1e-3 ≤ |v| < 1e7, else
//   "1.0E-5"), which is what kotlinx.serialization prints for a JSON number;
// - `Math.floorDiv` / `floorMod`.
//
// Swift's Int is 64-bit on every platform this package supports, so it stands for Kotlin's Long.

public enum JavaMath {
    /** `Math.round(double)`: half up, saturating, NaN → 0. */
    public static func round(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        if x >= 9.223372036854775807e18 { return Int.max }
        if x <= -9.223372036854775808e18 { return Int.min }
        let f = x.rounded(.down)
        let r = (x - f) >= 0.5 ? f + 1 : f
        return Int(r)
    }

    /** Kotlin `Double.roundToInt()`: like [round], saturating at the 32-bit range. */
    public static func roundToInt(_ x: Double) -> Int {
        precondition(!x.isNaN, "Cannot round NaN value.")
        if x > Double(Int32.max) { return Int(Int32.max) }
        if x < Double(Int32.min) { return Int(Int32.min) }
        return round(x)
    }

    /** Kotlin `Double.toInt()` (truncation towards zero, saturating at 32 bit, NaN → 0). */
    public static func toInt(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        if x >= Double(Int32.max) { return Int(Int32.max) }
        if x <= Double(Int32.min) { return Int(Int32.min) }
        return Int(x.rounded(.towardZero))
    }

    /** Kotlin `Double.toLong()` (truncation towards zero, saturating, NaN → 0). */
    public static func toLong(_ x: Double) -> Int {
        if x.isNaN { return 0 }
        if x >= 9.223372036854775807e18 { return Int.max }
        if x <= -9.223372036854775808e18 { return Int.min }
        return Int(x.rounded(.towardZero))
    }

    public static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    public static func floorMod(_ a: Int, _ b: Int) -> Int {
        a - floorDiv(a, b) * b
    }
}

/** The decimal digits of the shortest representation of a finite, non-negative double. */
struct ShortestDecimal {
    /** Significant digits without leading zeros ("0" for zero). */
    let digits: [UInt8]
    /** Value = 0.d1d2d3… × 10^pointPos, i.e. the decimal point sits after `pointPos` digits. */
    let pointPos: Int

    init(_ v: Double) {
        precondition(v.isFinite && v >= 0)
        if v == 0 { digits = [0]; pointPos = 1; return }
        // Swift's description is the shortest representation that round-trips (like the JDK ≥ 19).
        let s = v.description
        var mantissa = Substring(s)
        var exp = 0
        if let e = s.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            mantissa = s[s.startIndex..<e]
            exp = Int(s[s.index(after: e)...])!
        }
        var intPart = mantissa
        var fracPart: Substring = ""
        if let dot = mantissa.firstIndex(of: ".") {
            intPart = mantissa[mantissa.startIndex..<dot]
            fracPart = mantissa[mantissa.index(after: dot)...]
        }
        var all = Array((intPart + fracPart).utf8).map { $0 - 48 }
        var point = intPart.count + exp
        while all.count > 1 && all.first == 0 { all.removeFirst(); point -= 1 }
        while all.count > 1 && all.last == 0 { all.removeLast() }
        digits = all
        pointPos = point
    }
}

public enum JavaFormat {
    /** `String.format(Locale.ROOT, "%.<decimals>f", v)`. */
    public static func fixed(_ v: Double, _ decimals: Int) -> String {
        if v.isNaN { return "NaN" }
        if v.isInfinite { return v < 0 ? "-Infinity" : "Infinity" }
        let negative = v.sign == .minus
        let d = ShortestDecimal(v.magnitude)
        // Digits of the integer part and `decimals` fraction digits, rounded half up.
        let keep = d.pointPos + decimals // number of digits kept (counted from the first significant one)
        var kept: [UInt8]
        var point = d.pointPos
        if keep < 0 {
            kept = [0]
            point = 1
        } else {
            kept = Array(d.digits.prefix(keep))
            while kept.count < keep { kept.append(0) }
            let roundUp = keep < d.digits.count && d.digits[keep] >= 5
            if roundUp {
                var i = kept.count - 1
                var carry = true
                while carry && i >= 0 {
                    if kept[i] == 9 { kept[i] = 0; i -= 1 } else { kept[i] += 1; carry = false }
                }
                if carry { kept.insert(1, at: 0); point += 1 }
            }
            if kept.isEmpty { kept = [0]; point = 1 }
        }
        // Integer digits: kept[0 ..< point] (left-padded with "0" when point ≤ 0).
        var intDigits = ""
        var fracDigits = ""
        if point <= 0 {
            intDigits = "0"
            fracDigits = String(repeating: "0", count: min(-point, decimals))
            for x in kept where fracDigits.count < decimals { fracDigits.append(Character(UnicodeScalar(x + 48))) }
        } else {
            for (i, x) in kept.enumerated() {
                if i < point { intDigits.append(Character(UnicodeScalar(x + 48))) } else { fracDigits.append(Character(UnicodeScalar(x + 48))) }
            }
            while intDigits.count < point { intDigits.append("0") }
            // Leading zeros of the integer part (only possible as a single "0").
            while intDigits.count > 1 && intDigits.first == "0" { intDigits.removeFirst() }
        }
        while fracDigits.count < decimals { fracDigits.append("0") }
        if fracDigits.count > decimals { fracDigits = String(fracDigits.prefix(decimals)) }
        let body = decimals > 0 ? intDigits + "." + fracDigits : intDigits
        return negative ? "-" + body : body
    }

    /** `Double.toString()` of the JDK ≥ 19 (what kotlinx.serialization prints for a JSON number). */
    public static func doubleToString(_ v: Double) -> String {
        if v.isNaN { return "NaN" }
        if v.isInfinite { return v < 0 ? "-Infinity" : "Infinity" }
        let sign = v.sign == .minus ? "-" : ""
        let a = v.magnitude
        if a == 0 { return sign + "0.0" }
        let d = ShortestDecimal(a)
        let digits = d.digits.map { String($0) }.joined()
        if a >= 1e-3 && a < 1e7 {
            if d.pointPos <= 0 {
                return sign + "0." + String(repeating: "0", count: -d.pointPos) + digits
            }
            if d.pointPos >= digits.count {
                return sign + digits + String(repeating: "0", count: d.pointPos - digits.count) + ".0"
            }
            let i = digits.index(digits.startIndex, offsetBy: d.pointPos)
            return sign + String(digits[..<i]) + "." + String(digits[i...])
        }
        let first = String(digits.prefix(1))
        let rest = digits.count > 1 ? String(digits.dropFirst()) : "0"
        return sign + first + "." + rest + "E" + String(d.pointPos - 1)
    }
}
