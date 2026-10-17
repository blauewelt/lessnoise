package ch.lessnoise.app.alert

import ch.lessnoise.app.ui.ReportPreset

/**
 * «Melden aus der Mitteilung» (0.6.8, editions with the map only; src/karte): while the running
 * measurement is «unterwegs» — started with «Unterwegs», or a «Messen» run while the motion detector
 * says MOVING — a tap on the ongoing «Messung läuft» notification opens «Laut! Melden» directly, so
 * that a poser seen in the street can be reported without waiting for the «Sehr laut» detection.
 * Pure, so that ReportTapTest checks it on the JVM; ui/LoudAlerts.kt builds the PendingIntent.
 *
 * Distinct from the «Sehr laut» tap ([LoudAlertIntent]): its own action and request code, no event
 * time and no category in the intent. The report's time is the moment the app receives the tap
 * ([preset]), not the notification's creation; no category is suggested.
 */
object ReportTap {
    const val ACTION = "ch.lessnoise.app.action.REPORT_NOW"

    /**
     * PendingIntent request code. MeasurementService uses 0 (open the app) and 1 (Stopp),
     * LoudAlertNotifier 2 («Sehr laut»).
     */
    const val REQUEST = 3

    /** The notification's text while it opens «Laut! Melden» (the same words as the «Sehr laut» notification). */
    const val TEXT = LoudAlertRule.TEXT

    /** What the measurement notification shows and does in a state: the report tap, or null (as before 0.6.8). */
    data class Tap(val text: String, val action: String, val requestCode: Int)

    /**
     * [running] a measurement runs; [unterwegs] it was started with «Unterwegs»; [moving] the motion
     * detector says the phone is being carried (LiveState.moving, «Messen» runs only).
     */
    fun forState(running: Boolean, unterwegs: Boolean, moving: Boolean): Tap? =
        if (running && LoudAlertRule.unterwegsNow(unterwegs, moving)) Tap(TEXT, ACTION, REQUEST) else null

    /** The report to start when MainActivity receives [action] at [tapMs]: the tap's time, nothing preselected; null for any other intent. */
    fun preset(action: String?, tapMs: Long): ReportPreset? =
        if (action != ACTION || tapMs <= 0) null else ReportPreset(tapMs, emptyList())
}
