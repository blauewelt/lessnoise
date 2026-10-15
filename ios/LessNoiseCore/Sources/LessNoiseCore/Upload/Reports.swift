import Foundation

/** «Laut! Melden»: what was loud (upload/Reports.kt). Ids are the server's; labels the buttons. */
public enum ReportCategory: String, CaseIterable, Equatable {
    case MOTORRAD = "motorrad"
    case AUTO = "auto"
    case LASTWAGEN = "lastwagen"
    case POSER = "poser"
    case ANDERE = "andere"

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .MOTORRAD: return "Töff"
        case .AUTO: return "Auto"
        case .LASTWAGEN: return "Lastwagen"
        case .POSER: return "Poser"
        case .ANDERE: return "anderes"
        }
    }

    public static func fromId(_ id: String) -> ReportCategory? { ReportCategory(rawValue: id) }
}

/**
 * A report waiting to be sent. The ONLY place where the app keeps exact coordinates, and only
 * until the server acknowledged the report: then [toSent] drops them (PRIVACY.md).
 */
public struct PendingReport: Equatable, Hashable {
    /** Time of the tap on «Laut! Melden», epoch ms (sent with second precision). */
    public let epochMs: Int
    /** The same time as ISO-8601 with the phone's offset, second precision. */
    public let timeIso: String
    public let lat: Double
    public let lon: Double
    public let accuracyM: Double
    public let category: String
    /** The 100 m cell of the place, kept after sending (the coordinates are not). */
    public let cell: String?

    public init(epochMs: Int, timeIso: String, lat: Double, lon: Double, accuracyM: Double, category: String, cell: String?) {
        self.epochMs = epochMs; self.timeIso = timeIso; self.lat = lat; self.lon = lon
        self.accuracyM = accuracyM; self.category = category; self.cell = cell
    }

    public func toSent() -> SentReport { SentReport(epochMs: epochMs, category: category, cell: cell) }

    /** ISO-8601 with offset, second precision (`yyyy-MM-dd'T'HH:mm:ssxxx`: "+00:00", never "Z"). */
    public static func isoSeconds(_ epochMs: Int, _ zone: TimeZone) -> String {
        Iso.formatNumericOffset(epochMs - JavaMath.floorMod(epochMs, 1000), zone)
    }
}

/** What remains on the phone of a sent report: category, time and cell. No coordinates. */
public struct SentReport: Equatable {
    public let epochMs: Int
    public let category: String
    public let cell: String?
}

public struct ReportSnapshot: Equatable {
    public static let SENT_KEEP = 500
    public var pending: [PendingReport] = []
    /** Newest last, at most [SENT_KEEP]. */
    public var sent: [SentReport] = []
    public init(pending: [PendingReport] = [], sent: [SentReport] = []) { self.pending = pending; self.sent = sent }
}

/** Queue operations shared by the UI and the uploader (ReportQueue in Reports.kt), as pure transforms. */
public enum ReportQueue {
    public static func add(_ s: ReportSnapshot, _ r: PendingReport) -> ReportSnapshot {
        var n = s
        n.pending.append(r)
        return n
    }

    /**
     * The server answered for [batch]: accepted, duplicate and rejected reports all leave the queue
     * (with their coordinates); only the accepted and duplicate ones go to the sent list.
     */
    public static func acknowledge(_ s: ReportSnapshot, _ batch: [PendingReport], rejected: Set<Int> = []) -> ReportSnapshot {
        let done = Set(batch)
        let kept = batch.enumerated().filter { !rejected.contains($0.offset) }.map { $0.element.toSent() }
        var n = s
        n.pending = s.pending.filter { !done.contains($0) }
        n.sent = Array((s.sent + kept).suffix(ReportSnapshot.SENT_KEEP))
        return n
    }

    /** «Daten auf dem Server löschen» also drops reports that were not sent yet. */
    public static func dropPending(_ s: ReportSnapshot) -> ReportSnapshot {
        var n = s
        n.pending = []
        return n
    }
}
