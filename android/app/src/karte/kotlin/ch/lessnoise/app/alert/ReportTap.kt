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

    /** The label of the notification's button (0.6.9, Labor·Karte): «Melden», next to «Stopp». */
    const val BUTTON = "Melden"

    /**
     * What the measurement notification shows and does in a state. [text]: the fixed text, or null to
     * keep the level text (0.6.9, Labor·Karte). [contentTap]: the tap on the notification opens the
     * report; else it opens the app. [button]: a «Melden» action opens the report (0.6.9, Labor·Karte).
     * Both use the same intent ([action], [requestCode]).
     */
    data class Tap(
        val text: String?,
        val action: String,
        val requestCode: Int,
        val contentTap: Boolean = true,
        val button: Boolean = false,
    )

    /**
     * [running] a measurement runs; [unterwegs] it was started with «Unterwegs»; [moving] the run is
     * «unterwegs» by the motion sensor (or, 0.6.9 Labor·Karte, by the position) (LiveState.moving,
     * «Messen» runs only). [labor] = BuildConfig.LABOR_UNTERWEGS (0.6.9): the «Melden» button in every
     * state of a run, the level text kept, the content tap opening the report only while «unterwegs».
     * Without [labor] (the Online-Version) exactly as 0.6.8.
     */
    fun forState(running: Boolean, unterwegs: Boolean, moving: Boolean, labor: Boolean = false): Tap? {
        if (!running) return null
        val now = LoudAlertRule.unterwegsNow(unterwegs, moving)
        if (labor) return Tap(null, ACTION, REQUEST, contentTap = now, button = true)
        return if (now) Tap(TEXT, ACTION, REQUEST) else null
    }

    /** The report to start when MainActivity receives [action] at [tapMs]: the tap's time, nothing preselected; null for any other intent. */
    fun preset(action: String?, tapMs: Long): ReportPreset? =
        if (action != ACTION || tapMs <= 0) null else ReportPreset(tapMs, emptyList())
}
