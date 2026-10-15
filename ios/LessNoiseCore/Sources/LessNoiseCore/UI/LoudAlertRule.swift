import Foundation

/** A detected event as the live screen and the alert see it (LiveEvent in MeasurementService.kt). */
public struct LiveEvent: Equatable {
    public let startEpochMs: Int
    public let endEpochMs: Int
    public let lafMaxDb: Double
    /** Category id («loud_vehicle», «road_traffic» …), «unclassified», «wind», or nil (classifier off). */
    public let category: String?
    /** Wind on the microphone: not counted, not shared, never an alert. */
    public let wind: Bool
    public let calibrated: Bool
    public init(startEpochMs: Int, endEpochMs: Int, lafMaxDb: Double, category: String?, wind: Bool, calibrated: Bool) {
        self.startEpochMs = startEpochMs; self.endEpochMs = endEpochMs; self.lafMaxDb = lafMaxDb
        self.category = category; self.wind = wind; self.calibrated = calibrated
    }
}

/** A «Laut! Melden» to start from the notification: the event's time and the suggested categories. */
public struct ReportPreset: Equatable {
    public let epochMs: Int
    public let suggested: [ReportCategory]
}

/** The category buttons with [suggested] first, in their own order; the rest as always. */
public func reportCategoryOrder(_ suggested: [ReportCategory]) -> [ReportCategory] {
    suggested.filter { ReportCategory.allCases.contains($0) } + ReportCategory.allCases.filter { !suggested.contains($0) }
}

/**
 * «Unterwegs» and the «Sehr laut» notification (alert/LoudAlertRule.kt, map editions only). On iOS
 * the notification is a local UNNotification, posted while the app measures (ios/DESIGN.md).
 */
public enum LoudAlertRule {
    /** «Sehr laut»: LAFmax from this level, the map's loud threshold (server aggregate.py DEFAULT_LOUD_EVENT_DB). */
    public static let THRESHOLD_DB = 75.0
    /** «Töff & Poser» and «Strassenverkehr» (categories.json ids). */
    public static let CATEGORIES: Set<String> = ["loud_vehicle", "road_traffic"]
    /** At most one notification per … */
    public static let MIN_GAP_MS = 2 * 60_000
    /** The notification's text. */
    public static let TEXT = "Antippen zum Melden"
    /** The channel's name (Android); on iOS the notification category's name. */
    public static let CHANNEL_NAME = "Sehr laut"

    /** Whether [e] alerts now: «Unterwegs» on, measuring, not wind, one of [CATEGORIES], ≥ 75 dB, last alert ≥ 2 min ago. */
    public static func shouldAlert(_ e: LiveEvent, unterwegs: Bool, running: Bool, lastAlertAtMs: Int?, nowMs: Int) -> Bool {
        unterwegs && running && !e.wind && e.category.map { CATEGORIES.contains($0) } == true && e.lafMaxDb >= THRESHOLD_DB &&
            (lastAlertAtMs == nil || nowMs - lastAlertAtMs! >= MIN_GAP_MS)
    }

    /** «Töff & Poser» / «Strassenverkehr» (the names of categories.json). */
    public static func categoryLabel(_ category: String?) -> String {
        switch category {
        case "loud_vehicle"?: return "Töff & Poser"
        case "road_traffic"?: return "Strassenverkehr"
        default: return "Lärm"
        }
    }

    /** «Sehr laut: Töff & Poser, 84 dB». */
    public static func title(_ e: LiveEvent) -> String { "Sehr laut: \(categoryLabel(e.category)), \(JavaMath.roundToInt(e.lafMaxDb)) dB" }

    /** The report categories that fit the detected sound. */
    public static func suggested(_ category: String?) -> [ReportCategory] {
        switch category {
        case "loud_vehicle"?: return [.MOTORRAD, .POSER]
        case "road_traffic"?: return [.AUTO, .LASTWAGEN]
        default: return []
        }
    }
}

/** What the notification's tap carries, and how it becomes a prefilled «Laut! Melden» (LoudAlertIntent). */
public enum LoudAlertIntent {
    public static let ACTION = "ch.lessnoise.app.action.REPORT_LOUD"
    public static let EXTRA_EVENT_MS = "ch.lessnoise.app.extra.EVENT_MS"
    public static let EXTRA_CATEGORY = "ch.lessnoise.app.extra.CATEGORY"

    public static func preset(_ action: String?, _ eventMs: Int, _ category: String?) -> ReportPreset? {
        if action != ACTION || eventMs <= 0 { return nil }
        return ReportPreset(epochMs: eventMs, suggested: LoudAlertRule.suggested(category))
    }
}
