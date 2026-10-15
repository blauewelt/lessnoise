import Foundation

/** The texts of «Laut! Melden» (LocationFlows.kt; the same strings as the Android app). */
public enum ReportTexts {
    /**
     * The short consent before the first report while «Teilen» is off: what is sent, never audio,
     * that the point is public (server/DESIGN.md §2 rule 10), and how to delete.
     */
    public static let CONSENT_TEXT =
        "Gesendet werden Zeit, genauer Standort und Art des Lärms, nie Audio. " +
        "Der Punkt erscheint auf der Lärmkarte; wer oft vom gleichen Ort meldet, macht ihn sichtbar. " +
        "Dafür wird «Teilen» eingeschaltet; löschen kannst du alles mit «Vom Server löschen»."
    public static let CONSENT_TITLE = "Meldungen senden?"
    public static let CONSENT_YES = "Ja, einschalten"

    /** Rule 10's risk, on the confirmation of EVERY report (0.6.3). */
    public static let SPOT_WARNING = "Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar."

    public static let CATEGORY_TITLE = "Was war laut?"
    public static let FAILED_TITLE = "Kein genauer Standort"
    public static let CONFIRM_TITLE = "Meldung"
    public static let QUEUED = "Meldung wird gesendet."
    public static let BUTTON = "Laut! Melden"

    /** «Meldung: Töff, 16:05, Standort ± 12 m – senden?» */
    public static func confirmText(_ category: ReportCategory, _ tapMs: Int, _ accuracyM: Int, _ zone: TimeZone) -> String {
        "Meldung: \(category.label), \(Fmt.hm(tapMs, zone)), Standort ± \(accuracyM) m – senden?"
    }

    /** The text of the «Meldung» dialog: what is sent, then [SPOT_WARNING]. */
    public static func confirmDialogText(_ category: ReportCategory, _ tapMs: Int, _ accuracyM: Int, _ zone: TimeZone) -> String {
        confirmText(category, tapMs, accuracyM, zone) + "\n" + SPOT_WARNING
    }

    /** «Meldung: Töff» while locating. */
    public static func locatingTitle(_ c: ReportCategory) -> String { "Meldung: \(c.label)" }

    /** «Erkannt um 16:05» (from the «Sehr laut» notification). */
    public static func detectedAt(_ hm: String) -> String { "Erkannt um \(hm)" }
}

/**
 * «Laut! Melden» (LocationFlows.kt): consent (only while «Teilen» is off) → «Was war laut?» → ONE
 * precise fix (≤ 50 m) → «Meldung: …, Standort ± n m – senden?» with the rule-10 warning → queued
 * and uploaded soon. The time is the tap on the button (or, from the «Sehr laut» notification,
 * the event's time), to the second. The exact coordinates exist only in the queued report, until
 * the server has it.
 */
public final class ReportFlow {
    public enum Step: Equatable {
        case consent
        case category
        case locating(ReportCategory)
        case failed(ReportCategory, LocationPolicy.Outcome)
        case confirm(ReportCategory, lat: Double, lon: Double, accuracyM: Double, cell: String)
    }

    private let uploads: UploadCoordinator
    private let reports: ReportStore
    private let locator: Locator
    private let clock: AppClock
    private let zone: () -> TimeZone

    private let _step = Locked<Step?>(nil)
    private let lock = NSLock()
    private var tapMs = 0
    private var suggestedCategories: [ReportCategory] = []
    private var task: Task<Void, Never>?
    public let changes = Observers()

    public init(uploads: UploadCoordinator, reports: ReportStore, locator: Locator, clock: AppClock = SystemClock(), zone: @escaping () -> TimeZone = { .current }) {
        self.uploads = uploads; self.reports = reports; self.locator = locator; self.clock = clock; self.zone = zone
    }

    public var step: Step? { _step.get }
    public var timeMs: Int { lock.lock(); defer { lock.unlock() }; return tapMs }
    public var suggested: [ReportCategory] { lock.lock(); defer { lock.unlock() }; return suggestedCategories }

    private func set(_ s: Step?) { _step.set(s); changes.notify() }

    /** The button (now) or the notification's [preset] (the event's time and the categories to suggest). */
    public func begin(_ preset: ReportPreset? = nil) {
        lock.lock()
        tapMs = preset?.epochMs ?? clock.nowMs()
        suggestedCategories = preset?.suggested ?? []
        lock.unlock()
        // Skipped when sharing is already on (the person said «Ja, auf die Karte» or switched it on).
        set(uploads.store.state.enabled ? .category : .consent)
    }

    /** «Ja, einschalten»: the same yes as «Ja, auf die Karte». */
    public func consentYes() {
        uploads.enable()
        set(.category)
    }

    public func cancel() {
        lock.lock(); task?.cancel(); task = nil; lock.unlock()
        set(nil)
    }

    /** The category buttons in their order (suggested first). */
    public var categoryOrder: [ReportCategory] { reportCategoryOrder(suggested) }

    public func choose(_ c: ReportCategory) {
        switch locator.authorization {
        case .precise: locate(c)
        case .approximate: set(.failed(c, .approximateOnly))
        case .denied: set(.failed(c, .permissionDenied))
        case .notDetermined:
            locator.requestAuthorization { [weak self] in
                guard let self = self else { return }
                switch self.locator.authorization {
                case .precise: self.locate(c)
                case .approximate: self.set(.failed(c, .approximateOnly))
                default: self.set(.failed(c, .permissionDenied))
                }
            }
        }
    }

    private func locate(_ c: ReportCategory) {
        set(.locating(c))
        let t = Task { [weak self] in
            guard let self = self else { return }
            let r = await self.locator.oneFix(timeoutMs: LocationPolicy.TIMEOUT_MS, goodEnoughM: LocationPolicy.GOOD_ENOUGH_M)
            if Task.isCancelled { return }
            switch LocationPolicy.reportOutcome(r, precise: self.locator.authorization == .precise) {
            case let .fix(lat, lon, acc, cell): self.set(.confirm(c, lat: lat, lon: lon, accuracyM: acc, cell: cell))
            case let .problem(o): self.set(.failed(c, o))
            }
        }
        lock.lock(); task?.cancel(); task = t; lock.unlock()
    }

    /** The confirmation text of the current step, if it is the confirmation. */
    public var confirmText: String? {
        guard case let .confirm(c, _, _, acc, _) = step else { return nil }
        return ReportTexts.confirmDialogText(c, timeMs, Swift.max(1, Int(acc.rounded(.up))), zone())
    }

    /** «Senden»: queued (the only place with exact coordinates) and uploaded soon. True if queued. */
    @discardableResult
    public func send() -> Bool {
        guard case let .confirm(c, lat, lon, acc, cell) = step else { return false }
        let t = timeMs
        reports.update {
            ReportQueue.add($0, PendingReport(epochMs: t, timeIso: PendingReport.isoSeconds(t, zone()), lat: lat, lon: lon,
                                              accuracyM: acc, category: c.id, cell: cell))
        }
        uploads.uploadSoon(delayMs: 5_000)
        set(nil)
        return true
    }
}
