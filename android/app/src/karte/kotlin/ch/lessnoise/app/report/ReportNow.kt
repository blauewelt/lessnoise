package ch.lessnoise.app.report

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ch.lessnoise.app.ui.MainActivity
import ch.lessnoise.app.ui.ReportPreset

/**
 * 0.8.4, editions with the map (src/karte: Online-Version and Labor·Karte): «Laut! Melden» straight
 * from the home screen — the app shortcut (res/xml/shortcuts.xml of src/public and src/laborKarte,
 * a long press on the app icon) and the widget ([ReportWidget]). Both open MainActivity with
 * [ACTION]; MainActivity takes it in onCreate (cold start) and onNewIntent (app already open) through
 * LoudAlerts.presetFrom and opens the report dialog with the time of the tap and nothing preselected.
 * Neither sends anything by itself: a tap only opens the dialog, as the button on «Messen» does.
 *
 * Labor·Karte's «Melden» on the measurement notification (alert/ReportTap.kt, src/laborKarte) uses the
 * same action.
 */
object ReportNow {
    const val ACTION = "ch.lessnoise.app.action.REPORT_NOW"

    /**
     * PendingIntent request code of the widget. MeasurementService uses 0 (open the app) and 1 (Stopp);
     * Labor·Karte's «Sehr laut» tap 2 and ReportTap 3 (the notification's «Melden»).
     */
    const val WIDGET_REQUEST = 4

    /** The report to start when MainActivity receives [action] at [tapMs]: the tap's time, nothing preselected; null for any other intent. */
    fun preset(action: String?, tapMs: Long): ReportPreset? =
        if (action != ACTION || tapMs <= 0) null else ReportPreset(tapMs, emptyList())

    /** The intent of the widget: no extras (no time, no category); single-top, so an open app gets it in onNewIntent. */
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, WIDGET_REQUEST, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
