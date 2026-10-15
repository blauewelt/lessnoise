import Foundation

/**
 * How the phone is placed (server/DESIGN.md §4.2), optional and remembered for the next
 * measurements (SiteSettings in upload/UploadState.kt).
 */
public struct SiteSettings: Equatable {
    public static let PLACEMENT_OPEN_WINDOW = "open_window"
    public static let PLACEMENT_BALCONY = "balcony"
    public static let PLACEMENT_BEHIND_GLASS = "behind_glass"
    public static let PLACEMENT_OTHER = "other"
    public static let PLACEMENTS = [PLACEMENT_OPEN_WINDOW, PLACEMENT_BALCONY, PLACEMENT_BEHIND_GLASS, PLACEMENT_OTHER]
    public static let NOTE_MAX = 200

    /** open_window | balcony | behind_glass | other */
    public var placement: String
    /** 0 = ground floor; nil = not given. */
    public var floor: Int?
    /** Microphone faces the street (true) or the courtyard (false); nil = not given. */
    public var streetFacing: Bool?
    /** Free text ≤ 200 characters, never published. */
    public var note: String

    public init(placement: String = PLACEMENT_OPEN_WINDOW, floor: Int? = nil, streetFacing: Bool? = nil, note: String = "") {
        self.placement = placement; self.floor = floor; self.streetFacing = streetFacing; self.note = note
    }
}

/** A record the server refused for good; it is not sent again. */
public struct RefusedRecord: Equatable {
    public static let MINUTE = "minute"
    public static let EVENT = "event"
    public let kind: String
    public let startEpochMs: Int
    public let reason: String
    public init(kind: String, startEpochMs: Int, reason: String) { self.kind = kind; self.startEpochMs = startEpochMs; self.reason = reason }
}

/** The answer to «Auf die Lärmkarte?»: asked once, at the first «Starten». */
public enum ShareDecision: String, Equatable {
    case UNDECIDED = "undecided"
    case YES = "yes"
    case NO = "no"

    public var stored: String { rawValue }
    public static func fromStored(_ s: String?) -> ShareDecision? { s.flatMap { ShareDecision(rawValue: $0) } }
}

/**
 * Everything the upload remembers (UploadSnapshot in UploadState.kt). Immutable; on iOS the app
 * persists it (non-secret fields in UserDefaults, the token and the noise secret in the Keychain,
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, never synced; ios/DESIGN.md «Storage»).
 */
public struct UploadSnapshot: Equatable {
    public static let REFUSED_KEEP = 2000

    public var enabled: Bool = false
    public var shareDecision: ShareDecision = .UNDECIDED
    public var wifiOnly: Bool = false
    public var explanationSeen: Bool = false
    public var site: SiteSettings = SiteSettings()
    public var deviceId: String? = nil
    public var token: String? = nil
    /** Nothing that started before this is ever sent (set at opt-in and with «Neu starten»). */
    public var sendFloorMs: Int = 0
    /** Start of the last minute the server acknowledged; the next upload sends newer ones. */
    public var minutesSentUpToMs: Int = 0
    public var eventsSentUpToMs: Int = 0
    /** The site JSON last accepted by the server; a different one is sent again. */
    public var lastSiteJson: String? = nil
    public var lastSuccessAtMs: Int? = nil
    /** Short German description of the last problem; nil when fine. */
    public var lastError: String? = nil
    /** The server did not accept the token (401): uploading stops until «Neu starten». */
    public var authFailed: Bool = false
    public var refused: [RefusedRecord] = []
    public var refusedTotal: Int = 0
    /** «Standort verschieben»: σ in metres, one of LocationNoise.SIGMAS_M. */
    public var sigmaM: Int = LocationNoise.DEFAULT_SIGMA_M
    /** The location-noise secret (32 bytes, Base64); never sent. */
    public var noiseSecret: String? = nil

    public init() {}

    public var registered: Bool { deviceId != nil && token != nil }
}
