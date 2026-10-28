package ch.lessnoise.app.report

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ch.lessnoise.app.service.NotificationTap
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
 * The «Melden» button of the measurement notification uses the same action: Labor·Karte since 0.6.9
 * (alert/ReportTap.kt, src/laborKarte), the Online-Version since 0.9.4.4 ([notificationTap], through the
 * Online-Version's stand-in facade in src/nounterwegs). No «Sehr laut» code is involved: this file has none.
 */
object ReportNow {
    const val ACTION = "ch.lessnoise.app.action.REPORT_NOW"

    /**
     * PendingIntent request code of the widget. MeasurementService uses 0 (open the app) and 1 (Stopp);
     * Labor·Karte's «Sehr laut» tap 2, the notification's «Melden» 3 ([NOTIFICATION_REQUEST]).
     */
    const val WIDGET_REQUEST = 4

    /**
     * 0.9.4.4: PendingIntent request code of the measurement notification's «Melden» (Labor·Karte's
     * ReportTap.REQUEST since 0.6.9; the same PendingIntent in both editions with the map).
     */
    const val NOTIFICATION_REQUEST = 3

    /** 0.9.4.4: the label of that button, next to «Stopp». */
    const val NOTIFICATION_BUTTON = "Melden"

    /**
     * 0.9.4.4 (owner: «the main UI flow is to report someone»), the Online-Version: during every run the
     * measurement notification carries «Melden», which opens the report dialog directly (the time of the
     * tap, nothing preselected). The level text stays and a tap on the notification itself opens the app on
     * «Messen» (MeasurementNotification.ACTION_SHOW_MEASURE). Null without a run.
     */
    fun notificationTap(context: Context, running: Boolean): NotificationTap? {
        if (!notificationButton(running)) return null
        return NotificationTap(text = null, intent = null, button = notificationPendingIntent(context), buttonLabel = NOTIFICATION_BUTTON)
    }

    /** 0.9.4.4: whether the notification has «Melden»: during every run (pure, for the tests). */
    fun notificationButton(running: Boolean): Boolean = running

    fun notificationPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, NOTIFICATION_REQUEST, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
