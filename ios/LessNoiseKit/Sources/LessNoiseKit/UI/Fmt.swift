import Foundation

/**
 * Formatting for the screens (Fmt in Common.kt and Windows in the chart module): decimal comma
 * everywhere («45,1 dB(A)»), 24-hour times, German weekday abbreviations. Pure arithmetic on the
 * [TimeZone] (no DateFormatter), so that the tests give the same text on every platform.
 */
public enum Fmt {
    private static let WEEKDAYS = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]

    /** «45,1»; «–» for nil/NaN/∞. */
    public static func db(_ v: Double?, _ decimals: Int = 1) -> String {
        guard let v = v, !v.isNaN, !v.isInfinite else { return "–" }
        return JavaFormat.fixed(v, decimals).replacingOccurrences(of: ".", with: ",")
    }

    /** ChartFmt.comma: the same as [db]. */
    public static func comma(_ v: Double, _ decimals: Int) -> String { db(v, decimals) }

    struct Local { let y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, weekday: Int }

    static func local(_ ms: Int, _ zone: TimeZone) -> Local {
        let offset = Iso.offsetSeconds(ms, zone) * 1000
        let localMs = ms + offset
        let days = JavaMath.floorDiv(localMs, 86_400_000)
        let msOfDay = localMs - days * 86_400_000
        let (y, mo, d) = civil(days)
        // 1970-01-01 was a Thursday (index 3 with Monday = 0).
        let wd = JavaMath.floorMod(days + 3, 7)
        return Local(y: y, mo: mo, d: d, h: msOfDay / 3_600_000, mi: (msOfDay / 60_000) % 60, s: (msOfDay / 1000) % 60, weekday: wd)
    }

    static func civil(_ days: Int) -> (Int, Int, Int) {
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        var y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        if m <= 2 { y += 1 }
        return (y, m, d)
    }

    static func two(_ v: Int) -> String { v < 10 ? "0\(v)" : "\(v)" }

    /** «14:05». */
    public static func hm(_ ms: Int, _ zone: TimeZone = .current) -> String {
        let l = local(ms, zone)
        return two(l.h) + ":" + two(l.mi)
    }

    /** «14:05:09». */
    public static func time(_ ms: Int, _ zone: TimeZone = .current) -> String {
        let l = local(ms, zone)
        return two(l.h) + ":" + two(l.mi) + ":" + two(l.s)
    }

    /** «15.10.2026 21:30». */
    public static func dateTime(_ ms: Int, _ zone: TimeZone = .current) -> String {
        let l = local(ms, zone)
        return two(l.d) + "." + two(l.mo) + "." + String(l.y) + " " + two(l.h) + ":" + two(l.mi)
    }

    /** «Sa». */
    public static func weekday(_ ms: Int, _ zone: TimeZone = .current) -> String { WEEKDAYS[local(ms, zone).weekday] }

    /** «Sa 10.10. 22:14». */
    public static func weekdayDateTime(_ ms: Int, _ zone: TimeZone = .current) -> String {
        let l = local(ms, zone)
        return "\(WEEKDAYS[l.weekday]) \(l.d).\(l.mo). " + two(l.h) + ":" + two(l.mi)
    }

    /** «3 h 05 min», «45 min». */
    public static func hoursMinutes(_ ms: Int) -> String {
        let min = Swift.max(ms / 60_000, 0)
        return min < 60 ? "\(min) min" : "\(min / 60) h " + two(min % 60) + " min"
    }

    /** «12,5 s», «3 min 05 s», «2 h 04 min». */
    public static func duration(_ seconds: Double) -> String {
        if seconds < 60 { return db(seconds, 1) + " s" }
        if seconds < 3600 { return "\(Int(seconds / 60)) min " + two(Int(seconds.truncatingRemainder(dividingBy: 60))) + " s" }
        return "\(Int(seconds / 3600)) h " + two(Int(seconds.truncatingRemainder(dividingBy: 3600) / 60)) + " min"
    }

    /** «85 %». */
    public static func percent(_ v: Double) -> String { "\(JavaMath.round(v * 100)) %" }

    /** «Sa 10.10. 14:05–16:30», across dates «Sa 10.10. 22:14 – So 11.10. 06:30» (Windows.label). */
    public static func spanLabel(_ startMs: Int, _ endMs: Int, _ zone: TimeZone = .current) -> String {
        let a = local(startMs, zone)
        let b = local(endMs, zone)
        let first = "\(WEEKDAYS[a.weekday]) \(a.d).\(a.mo). " + two(a.h) + ":" + two(a.mi)
        if a.y == b.y && a.mo == b.mo && a.d == b.d { return first + "–" + two(b.h) + ":" + two(b.mi) }
        return first + " – \(WEEKDAYS[b.weekday]) \(b.d).\(b.mo). " + two(b.h) + ":" + two(b.mi)
    }

    /** The title of a measurement card: «läuft seit Sa 10.10. 22:14» or its span label. */
    public static func measurementTitle(_ m: Measurement, _ zone: TimeZone = .current) -> String {
        m.running ? "läuft seit \(weekdayDateTime(m.span.startMs, zone))" : spanLabel(m.span.startMs, m.span.endMs, zone)
    }

    /** «Lärmspitzen pro Stunde»: one decimal below 10, else none; «–» without data. */
    public static func eventsPerHour(_ v: Double) -> String { v.isNaN ? "–" : comma(v, v < 10 ? 1 : 0) }

    /** Parses «45,5» or «45.5» (the calibration fields). */
    public static func parseDb(_ s: String) -> Double? {
        let t = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard !t.isEmpty, let v = Double(t), v.isFinite else { return nil }
        return v
    }
}

/** Display names and colours of the categories (categoryName / categoryColor in Common.kt). */
public enum Categories {
    /** German name of a category id: the mapper's name, «Wind», «–» for none. */
    public static func name(_ id: String?, _ mapper: CategoryMapper?) -> String {
        switch id {
        case nil, "n/a"?: return "–"
        case WindRule.CATEGORY?: return WindRule.NAME_DE
        case let i?: return mapper?.nameDe(i) ?? (i == CategoryMapper.UNCLASSIFIED ? "Sonstiges" : i)
        }
    }

    /** RGB (0xRRGGBB) of a category (the Android palette). */
    public static func rgb(_ id: String?) -> Int {
        switch id {
        case "loud_vehicle"?: return 0xD1495B
        case "road_traffic"?: return 0xEDAE49
        case "rail_tram"?: return 0x00798C
        case "aircraft"?: return 0x6C5B7B
        case "construction"?: return 0x8D6A3F
        case "voices"?: return 0x3E8E41
        case "music"?: return 0x30638E
        case CategoryMapper.UNCLASSIFIED?: return 0x9E9E9E
        case WindRule.CATEGORY?: return 0xB0BEC5
        default: return 0xBDBDBD
        }
    }

    /** The mapper from the app's categories.json and yamnet_labels.txt (the same files as Android's assets). */
    public static func mapper(categoriesJson: String, labelsText: String) -> CategoryMapper? {
        try? CategoryMapper.fromJson(categoriesJson, labels: CategoryMapper.parseLabels(labelsText))
    }
}
