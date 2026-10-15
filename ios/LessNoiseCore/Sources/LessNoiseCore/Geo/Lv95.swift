import Foundation

/**
 * WGS84 ↔ Swiss LV95 (CH1903+) and the hectare cell ids of server/DESIGN.md §3 (geo/Lv95.kt, itself
 * a 1:1 port of docs/map/lv95.js: same constants, same order of operations). swisstopo's
 * approximate formulas, accuracy about 1 m inside Switzerland. The app snaps and shifts the
 * contributor's point on the phone; true coordinates never leave it (DESIGN.md §2.2).
 */
public enum Lv95 {
    /** LV95 easting/northing in metres. */
    public struct En: Equatable {
        public let e: Double
        public let n: Double
        public init(_ e: Double, _ n: Double) { self.e = e; self.n = n }
    }

    /** WGS84 latitude/longitude in decimal degrees. */
    public struct LatLon: Equatable {
        public let lat: Double
        public let lon: Double
        public init(_ lat: Double, _ lon: Double) { self.lat = lat; self.lon = lon }
    }

    /** WGS84 latitude/longitude in decimal degrees → LV95 in metres. */
    public static func wgs84ToLv95(_ lat: Double, _ lon: Double) -> En {
        // auxiliary values: differences to Bern in units of 10 000"
        let p = (lat * 3600 - 169028.66) / 10000
        let l = (lon * 3600 - 26782.5) / 10000
        // Same left-to-right evaluation as the Kotlin/JS expressions.
        var e = 2600072.37
        e = e + 211455.93 * l
        e = e - 10938.51 * l * p
        e = e - 0.36 * l * p * p
        e = e - 44.54 * l * l * l
        var n = 1200147.07
        n = n + 308807.95 * p
        n = n + 3745.25 * l * l
        n = n + 76.63 * p * p
        n = n - 194.56 * l * l * p
        n = n + 119.79 * p * p * p
        return En(e, n)
    }

    /** LV95 easting/northing in metres → WGS84 in decimal degrees. */
    public static func lv95ToWgs84(_ e: Double, _ n: Double) -> LatLon {
        // auxiliary values: differences to Bern in units of 1000 km
        let y = (e - 2600000) / 1000000
        let x = (n - 1200000) / 1000000
        var lon = 2.6779094
        lon = lon + 4.728982 * y
        lon = lon + 0.791484 * y * x
        lon = lon + 0.1306 * y * x * x
        lon = lon - 0.0436 * y * y * y
        var lat = 16.9023892
        lat = lat + 3.238272 * x
        lat = lat - 0.270978 * y * y
        lat = lat - 0.002528 * x * x
        lat = lat - 0.0447 * y * y * x
        lat = lat - 0.0140 * x * x * x
        // result is in units of 10 000"; * 100 / 36 gives degrees
        return LatLon(lat * 100 / 36, lon * 100 / 36)
    }

    /** "h26824_12473" → south-west corner (2 682 400, 1 247 300), or nil if malformed. */
    public static func parseCell(_ cellId: String) -> En? {
        let u = Array(cellId.utf8)
        // ^h(\d{5})_(\d{5})$
        guard u.count == 12, u[0] == UInt8(ascii: "h"), u[6] == UInt8(ascii: "_") else { return nil }
        func digits(_ r: Range<Int>) -> Int? {
            var v = 0
            for i in r {
                guard u[i] >= 0x30 && u[i] <= 0x39 else { return nil }
                v = v * 10 + Int(u[i] - 0x30)
            }
            return v
        }
        guard let e = digits(1..<6), let n = digits(7..<12) else { return nil }
        return En(Double(e) * 100.0, Double(n) * 100.0)
    }

    /** True for a well-formed hectare id (`h` + 5 digits + `_` + 5 digits). */
    public static func isCellId(_ cellId: String) -> Bool { parseCell(cellId) != nil }

    /** LV95 point → id of the hectare that contains it. */
    public static func lv95ToCell(_ e: Double, _ n: Double) -> String {
        "h\(JavaMath.toLong(floor(e / 100)))_\(JavaMath.toLong(floor(n / 100)))"
    }

    /** WGS84 point → id of the hectare that contains it. */
    public static func cellId(_ lat: Double, _ lon: Double) -> String {
        let p = wgs84ToLv95(lat, lon)
        return lv95ToCell(p.e, p.n)
    }

    /** The four corners of a hectare in WGS84, SW, SE, NE, NW (the website's order); nil on a malformed id. */
    public static func cellCorners(_ cellId: String) -> [LatLon]? {
        guard let sw = parseCell(cellId) else { return nil }
        return [(sw.e, sw.n), (sw.e + 100, sw.n), (sw.e + 100, sw.n + 100), (sw.e, sw.n + 100)].map { lv95ToWgs84($0.0, $0.1) }
    }

    /** Centre of a hectare in WGS84; nil on a malformed id. */
    public static func cellCenter(_ cellId: String) -> LatLon? {
        guard let sw = parseCell(cellId) else { return nil }
        return lv95ToWgs84(sw.e + 50, sw.n + 50)
    }

    /** Rough box around Switzerland in LV95 (the server's CELL_E_RANGE/CELL_N_RANGE). */
    public static func isInServerRange(_ cellId: String) -> Bool {
        guard let sw = parseCell(cellId) else { return false }
        let e = JavaMath.toInt(sw.e / 100)
        let n = JavaMath.toInt(sw.n / 100)
        return e >= 24800 && e < 28400 && n >= 10700 && n < 13000
    }
}
