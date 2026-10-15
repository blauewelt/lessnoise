import Foundation

/**
 * Calibration model on iOS. Android keeps one active calibration per (phone model, audio source):
 * UNPROCESSED and VOICE_RECOGNITION are calibrated separately, because the two inputs differ in
 * gain. iOS has exactly one input path for this app (AVAudioSession category `.record`, mode
 * `.measurement`: no AGC, no noise suppression), so there is one calibration per phone, stored and
 * sent under its own source value [AudioSource.IOS_MEASUREMENT] = "IOS" (owner's decision,
 * 2026-10-15). THE SERVER MUST ACCEPT "IOS" BEFORE THE APP IS RELEASED: today
 * server/stadtlaerm_server/schemas.py accepts only `UNPROCESSED | VOICE_RECOGNITION` (site and
 * minutes) and would refuse every record of an iPhone (ios/DESIGN.md «Before an iOS release»).
 * The offset model itself is unchanged: level = 10·log10(mean square) + offset, default
 * [Acoustics.DEFAULT_CALIBRATION_OFFSET_DB].
 */
public enum AudioSource {
    public static let UNPROCESSED = "UNPROCESSED"
    public static let VOICE_RECOGNITION = "VOICE_RECOGNITION"
    /** The one source of the iOS app: the single place where the value is defined. */
    public static let IOS_MEASUREMENT = "IOS"

    /** «unbearbeitet» / «Sprachmodus» (AudioSourceSelector.labelDe); iOS: «iPhone (Messmodus)». */
    public static func labelDe(_ name: String) -> String {
        switch name {
        case UNPROCESSED: return "unbearbeitet"
        case VOICE_RECOGNITION: return "Sprachmodus"
        case IOS_MEASUREMENT: return "iPhone (Messmodus)"
        default: return name
        }
    }
}

/**
 * Re-evaluating stored measurements with a newer calibration («nachträglich kalibriert»,
 * calibration/Recalibration.kt). Always relative to the ORIGINAL values: A→B→C equals A→C, and
 * repeating a re-evaluation changes nothing.
 */
public enum Recalibration {
    /** [MinuteRecord.recalibratedFromId] of records measured without a calibration (default offset). */
    public static let FROM_DEFAULT = "default"

    /** The calibration to re-evaluate with. Always a real calibration (data becomes «calibrated»). */
    public struct Target: Equatable {
        public let calibrationId: Int
        public let offsetDb: Double
        public let audioSource: String
        public init(calibrationId: Int, offsetDb: Double, audioSource: String) {
            self.calibrationId = calibrationId; self.offsetDb = offsetDb; self.audioSource = audioSource
        }
    }

    public static func idText(_ calibrationId: Int?) -> String { calibrationId.map { String($0) } ?? FROM_DEFAULT }

    /** Records that a re-evaluation with [target] changes: same audio source, without or with another calibration. */
    public static func inScope(_ audioSource: String, _ calibrationId: Int?, _ target: Target) -> Bool {
        audioSource == target.audioSource && calibrationId != target.calibrationId
    }

    /** The minute re-evaluated with [target]. */
    public static func recalibrate(_ m: MinuteRecord, _ target: Target) -> MinuteRecord {
        let first = m.original == nil || m.recalibratedFromId == nil || m.recalibrationOffsetDb == nil
        let original = first ? m.levels : m.original!
        let fromId = first ? idText(m.calibrationId) : m.recalibratedFromId!
        let fromOffset = first ? m.calibrationOffsetDb : m.recalibrationOffsetDb!
        let restore = fromId == String(target.calibrationId)
        let l = restore ? original : original.shifted(target.offsetDb - fromOffset)
        // The local floor has no orig_ column: undo the shift applied so far, then apply the new one.
        let floorOriginal = m.localFloorDb - (m.calibrationOffsetDb - fromOffset)
        let floor = restore ? floorOriginal : floorOriginal + (target.offsetDb - fromOffset)
        var r = m
        r.laeqDb = l.laeqDb; r.lafMaxDb = l.lafMaxDb; r.lafMinDb = l.lafMinDb; r.l1Db = l.l1Db
        r.l10Db = l.l10Db; r.l50Db = l.l50Db; r.l90Db = l.l90Db; r.localFloorDb = floor
        r.calibrationId = target.calibrationId; r.calibrationOffsetDb = target.offsetDb; r.calibrated = true
        // Back on the calibration it was measured with: the record is original again.
        r.original = restore ? nil : original
        r.recalibratedFromId = restore ? nil : fromId
        r.recalibrationOffsetDb = restore ? nil : fromOffset
        return r
    }

    /** The event re-evaluated with [target]; [currentOffsetDb] is used only the first time. */
    public static func recalibrate(_ e: NoiseEvent, _ currentOffsetDb: Double, _ target: Target) -> NoiseEvent {
        let first = e.original == nil || e.recalibratedFromId == nil || e.recalibrationOffsetDb == nil
        let original = first ? e.levels : e.original!
        let fromId = first ? idText(e.calibrationId) : e.recalibratedFromId!
        let fromOffset = first ? currentOffsetDb : e.recalibrationOffsetDb!
        let restore = fromId == String(target.calibrationId)
        let l = restore ? original : original.shifted(target.offsetDb - fromOffset)
        var r = e
        r.lafMaxDb = l.lafMaxDb; r.selDb = l.selDb; r.backgroundDb = l.backgroundDb
        // The local floor is a level too: LAFmax − excess (the excess does not depend on the offset).
        if var f = e.features { f.localFloorDb = l.lafMaxDb - f.excessDb; r.features = f }
        r.calibrationId = target.calibrationId; r.calibrated = true
        r.original = restore ? nil : original
        r.recalibratedFromId = restore ? nil : fromId
        r.recalibrationOffsetDb = restore ? nil : fromOffset
        return r
    }
}

/** The event floor («Mindestpegel») follows the calibration (EventFloor in Recalibration.kt). */
public enum EventFloor {
    public struct Adjustment: Equatable {
        public let previousOffsetDb: Double
        public let newOffsetDb: Double
        public let oldFloorDb: Double
        /** Old floor + delta, rounded to 0.5 dB, before clamping to the setting's range. */
        public let unclampedFloorDb: Double
        public let newFloorDb: Double
        public var deltaDb: Double { newOffsetDb - previousOffsetDb }
        public var clamped: Bool { newFloorDb != unclampedFloorDb }
        public var changed: Bool { newFloorDb != oldFloorDb }
    }

    /** Rounds to the nearest 0.5 dB (ties upwards). */
    public static func roundToHalf(_ v: Double) -> Double { Double(JavaMath.round(v * 2)) / 2.0 }

    public static func adjust(_ floorDb: Double, _ previousOffsetDb: Double, _ newOffsetDb: Double, _ minDb: Double, _ maxDb: Double) -> Adjustment {
        let unclamped = roundToHalf(floorDb + (newOffsetDb - previousOffsetDb))
        return Adjustment(
            previousOffsetDb: previousOffsetDb, newOffsetDb: newOffsetDb, oldFloorDb: floorDb,
            unclampedFloorDb: unclamped, newFloorDb: Swift.min(Swift.max(unclamped, minDb), maxDb)
        )
    }

    /** "+9,6", "−3,2" (minus sign), "±0,0" — decimal comma, as everywhere in the app. */
    public static func signed(_ deltaDb: Double, decimals: Int = 1) -> String {
        // Locale.GERMANY formats like Locale.ROOT with a decimal comma (no grouping for %.nf).
        let text = JavaFormat.fixed(abs(deltaDb), decimals).replacingOccurrences(of: ".", with: ",")
        if text.drop(while: { $0 == "0" || $0 == "," }).isEmpty { return "±" + text }
        return deltaDb > 0 ? "+" + text : "−" + text
    }
}
