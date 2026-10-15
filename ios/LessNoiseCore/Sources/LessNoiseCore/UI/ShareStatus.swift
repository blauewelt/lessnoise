import Foundation

/** Where the fix for the running measurement stands (ui/ShareStatus.kt). */
public enum LocateState: Equatable {
    /** Nothing to do. */
    case idle
    /** A fix is wanted, but the app was not in the foreground: it is tried when the app is opened again. */
    case pending
    /** The one fix is running (only while the app is open). */
    case locating
    /** No usable fix; [outcome] says why. */
    case failed(LocationPolicy.Outcome)
}

/** What a tap on the status line does. */
public enum ShareAction: String, Equatable {
    case NONE, TURN_ON, LOCATION_OPTIONS, OPEN_MAP, OPEN_SHARE
}

/** One line: its text, what a tap does, and whether it reports a problem (shown in the error colour). */
public struct ShareStatus: Equatable {
    public let text: String
    public let action: ShareAction
    public let problem: Bool
    public init(_ text: String, _ action: ShareAction, problem: Bool = false) { self.text = text; self.action = action; self.problem = problem }
}

/**
 * The status line for the current state (`shareStatus` in ShareStatus.kt). nil: nothing to say
 * (no measurement runs and «Auf die Lärmkarte?» has not been asked yet).
 */
public func shareStatus(
    _ s: UploadSnapshot, running: Bool, measurementStartMs: Int, hasPoint: Bool, locate: LocateState, online: Bool,
    hm: (Int) -> String
) -> ShareStatus? {
    let on = s.enabled
    if !running {
        if !on && s.shareDecision == .UNDECIDED { return nil }
        if !on { return ShareStatus(ShareTexts.STATUS_OFF, .TURN_ON) }
        if s.authFailed { return ShareStatus(ShareTexts.STATUS_AUTH, .OPEN_SHARE, problem: true) }
        if let t = s.lastSuccessAtMs { return ShareStatus(ShareTexts.statusSent(hm(t)), .OPEN_MAP) }
        return ShareStatus(ShareTexts.STATUS_ON_IDLE, .NONE)
    }
    if !on { return ShareStatus(ShareTexts.STATUS_OFF, .TURN_ON) }
    if !hasPoint {
        switch locate {
        case .failed: return ShareStatus(ShareTexts.STATUS_NO_POINT, .LOCATION_OPTIONS, problem: true)
        case .locating, .pending: return ShareStatus(ShareTexts.STATUS_LOCATING, .NONE)
        // Sharing is on but nobody asked for a fix: offer it.
        case .idle: return ShareStatus(ShareTexts.STATUS_NO_POINT, .LOCATION_OPTIONS, problem: true)
        }
    }
    let error = s.lastError
    if s.authFailed { return ShareStatus(ShareTexts.STATUS_AUTH, .OPEN_SHARE, problem: true) }
    if !online || error == UploadRules.NO_CONNECTION_TEXT { return ShareStatus(ShareTexts.STATUS_NO_INTERNET, .NONE, problem: true) }
    if error == UploadRules.RETRY_TEXT { return ShareStatus(ShareTexts.STATUS_SERVER_BUSY, .NONE, problem: true) }
    if error != nil { return ShareStatus(ShareTexts.STATUS_REJECTED, .NONE, problem: true) }
    // Acknowledged: the markers moved into this measurement only after its point was attached.
    if let t = s.lastSuccessAtMs, s.minutesSentUpToMs >= measurementStartMs {
        return ShareStatus(ShareTexts.statusSent(hm(t)), .OPEN_MAP)
    }
    return ShareStatus(ShareTexts.STATUS_FIRST_VALUES, .NONE)
}

/** The banner above the map: its text and what a tap does. */
public struct MapBanner: Equatable {
    public let text: String
    public let action: ShareAction
    public init(_ text: String, _ action: ShareAction) { self.text = text; self.action = action }
}

/** How long after the last acknowledged upload the banner «Gesendet HH:MM …» stays. */
public let BANNER_SENT_MS = 2 * 60_000

/** The banner above the map while this phone's values are not visible there yet (`mapBanner` in ShareStatus.kt). */
public func mapBanner(
    _ s: UploadSnapshot, running: Bool, measurementStartMs: Int, hasPoint: Bool, hasAnyPoint: Bool, locate: LocateState,
    online: Bool, nowMs: Int, hm: (Int) -> String
) -> MapBanner? {
    var action = shareStatus(s, running: running, measurementStartMs: measurementStartMs, hasPoint: hasPoint, locate: locate, online: online, hm: hm)?.action ?? .TURN_ON
    if action == .OPEN_MAP { action = .NONE }
    if !s.enabled { return MapBanner(ShareTexts.BANNER_NOT_SHARING, action) }
    if !hasPoint && !hasAnyPoint { return MapBanner(ShareTexts.BANNER_NO_POINT, action) }
    let ackAt: Int?
    if running && hasPoint {
        ackAt = s.minutesSentUpToMs >= measurementStartMs ? s.lastSuccessAtMs : nil
    } else {
        ackAt = s.lastSuccessAtMs
    }
    guard let a = ackAt else { return MapBanner(ShareTexts.BANNER_FIRST_VALUES, action) }
    if nowMs - a < BANNER_SENT_MS { return MapBanner(ShareTexts.bannerSent(hm(a)), action) }
    return nil
}
