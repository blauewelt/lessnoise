package ch.lessnoise.app.alert

import ch.lessnoise.app.report.ReportNow
import ch.lessnoise.app.ui.ReportPreset

/**
 * «Melden aus der Mitteilung», both editions with the map (src/karte; 0.8.4–0.9.4.5 Labor·Karte only, in
 * src/laborKarte). During every run the measurement notification has a «Melden» button next to «Stopp» that
 * opens «Laut! Melden» at the tap's time (0.6.9 Labor·Karte; 0.9.4.4 the Online-Version), and its text stays
 * the level text. While the run is «unterwegs» — started with «Unterwegs messen», or a window run while the
 * motion detector says MOVING — a tap on the notification itself opens «Laut! Melden» too (0.6.8; an earlier
 * owner decision), so that a vehicle seen in the street can be reported without waiting for the «Sehr laut»
 * detection; otherwise the tap opens the app on «Messen» (MeasurementNotification.ACTION_SHOW_MEASURE).
 * Pure, so that ReportTapTest checks it on the JVM; ui/LoudAlerts.kt builds the PendingIntent.
 *
 * Distinct from the «Sehr laut» tap ([LoudAlertIntent]): its own action and request code, no event time and no
 * category in the intent. The report's time is the moment the app receives the tap ([preset]), not the
 * notification's creation; no category is suggested.
 *
 * Until 0.9.4.5 a build flag (true in Labor·Karte only) chose between this rule (Labor·Karte) and 0.6.8's (a fixed
 * text «Antippen zum Melden» and no button while «unterwegs»), which no edition used after 0.8.3; both are gone.
 */
object ReportTap {
    /** 0.8.4: the action of the shortcut and the widget (ReportNow, src/karte), the same report. */
    const val ACTION = ReportNow.ACTION

    /**
     * PendingIntent request code. MeasurementService uses 0 (open the app) and 1 (Stopp), LoudAlertNotifier 2
     * («Sehr laut»), the widget 4 (ReportNow.WIDGET_REQUEST).
     */
    const val REQUEST = ReportNow.NOTIFICATION_REQUEST

    /** The label of the notification's button: «Melden», next to «Stopp». */
    const val BUTTON = ReportNow.NOTIFICATION_BUTTON

    /**
     * What the measurement notification does in a state: the «Melden» [button] (always, during a run) and
     * whether the tap on the notification opens the report too ([contentTap]); both use the same intent
     * ([action], [requestCode]). The text is always the level text.
     */
    data class Tap(
        val action: String,
        val requestCode: Int,
        val contentTap: Boolean,
        val button: Boolean = true,
    )

    /**
     * [running] a measurement runs; [unterwegs] it was started with «Unterwegs messen»; [moving] a window run is
     * «unterwegs» by the motion sensor (LiveState.moving). Null without a run.
     */
    fun forState(running: Boolean, unterwegs: Boolean, moving: Boolean): Tap? {
        if (!running) return null
        return Tap(ACTION, REQUEST, contentTap = LoudAlertRule.unterwegsNow(unterwegs, moving))
    }

    /** The report to start when MainActivity receives [action] at [tapMs]: the tap's time, nothing preselected; null for any other intent. */
    fun preset(action: String?, tapMs: Long): ReportPreset? = ReportNow.preset(action, tapMs)
}
