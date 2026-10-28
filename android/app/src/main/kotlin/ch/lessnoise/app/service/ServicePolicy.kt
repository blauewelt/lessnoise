package ch.lessnoise.app.service

import android.app.PendingIntent
import java.util.Locale

/**
 * What the measurement service does when the app's task is swiped away (Service.onTaskRemoved).
 * Pure logic, unit-tested on the JVM.
 */
object TaskRemovedPolicy {
    enum class Action {
        /** Default: the measurement continues as a foreground service; the notification shows it. */
        KEEP_RUNNING,
        /** Stop exactly like «Stopp» (only if [onTaskRemoved] is told to; since 0.6.1 the app never is). */
        STOP,
    }

    /**
     * @param stopOnTaskRemoved stop when swiped away; fixed false since 0.6.1 (AppSettings.stopOnTaskRemoved)
     * @param running a measurement is running
     * @param starting a measurement is starting (model loading); stopping cancels the start
     */
    fun onTaskRemoved(stopOnTaskRemoved: Boolean, running: Boolean, starting: Boolean): Action =
        if (stopOnTaskRemoved && (running || starting)) Action.STOP else Action.KEEP_RUNNING
}

/** Texts of the foreground notification shown while measuring. */
object MeasurementNotification {
    const val TITLE = "Mikrofon aktiv – Messung läuft"
    const val STARTING = "Messung startet …"
    const val NO_PERMISSION = "Mikrofon-Berechtigung fehlt"
    const val SILENCED = "Mikrofon stumm (z. B. Anruf) – zählt nicht"

    /**
     * 0.7.6, editions with the map: the text of the «Sehr laut» alert while it replaces this
     * notification ([NotificationCadence.alertUntil]); the same words as the alert rule's TEXT in
     * src/karte (LoudAlertTest checks that they agree).
     */
    const val ALERT_TEXT = "Antippen zum Melden"

    /** 0.7.6: the label of the alert's report action, next to «Stopp» (the same as the report-now button in src/karte). */
    const val ALERT_BUTTON = "Melden"

    /**
     * 0.9.4.4 (owner: «the main UI flow is to report someone»): the action of the notification's own tap in
     * every edition. MainActivity then shows the tab «Messen» (where the editions with reports have the big
     * «Laut! Melden»), not the tab last open. Labor·Karte's tap while «unterwegs» still opens the report
     * itself (0.6.8).
     */
    const val ACTION_SHOW_MEASURE = "ch.lessnoise.app.action.SHOW_MEASURE"

    /** 0.9.4.4: whether an intent with [action] asks MainActivity for «Messen». */
    fun showsMeasure(action: String?): Boolean = action == ACTION_SHOW_MEASURE

    /**
     * Content text: the running 1-min LAeq as «Letzte Minute»; [note] is appended (Labor build).
     * [calibrated] is no longer shown (0.6.1: «Richtwerte» is said once, under Einstellungen › Datenschutz).
     */
    fun text(laeqDb: Double, @Suppress("UNUSED_PARAMETER") calibrated: Boolean, silenced: Boolean, note: String? = null): String {
        val base = if (silenced) SILENCED else String.format(Locale.GERMANY, "Letzte Minute: %.1f dB(A)", laeqDb)
        return if (note.isNullOrEmpty()) base else "$base · $note"
    }
}

/**
 * 0.6.8, editions with the map: the measurement notification while the run is «unterwegs» — its
 * [text] («Antippen zum Melden») and the [intent] its tap fires («Laut! Melden»). Built by
 * ui/LoudAlerts.reportTap — since 0.8.4 the text and content tap only in Labor·Karte (src/laborKarte);
 * 0.9.4.4: the Online-Version (src/nounterwegs → ReportNow.notificationTap) has the «Melden» [button] during
 * every run, no text and no content tap; the Offline and the Labor builds never have one.
 * 0.6.9 (Labor·Karte): [text] null keeps the level text (posted every 2 s as usual), [intent] null
 * keeps the usual tap (open the app), [button] adds an action [buttonLabel] («Melden») next to «Stopp».
 */
class NotificationTap(
    val text: String?,
    val intent: PendingIntent?,
    val button: PendingIntent? = null,
    val buttonLabel: String = "",
)

/**
 * When the measurement notification is (re-)posted (0.6.8; pure, ServicePolicyTest). Normally the
 * level text every [everyMs] (as before). While the run is «unterwegs» (report mode) the text is fixed,
 * so nothing is posted per second; a change of mode posts at once ([modeChanged]) and makes the next
 * level text due immediately. Thread-safe (capture thread and the service's state collector).
 *
 * 0.7.6 (editions with the map): the «Sehr laut» alert replaces the notification's content for a
 * window ([alertUntil], MeasurementService.LOUD_ALERT_WINDOW_MS = 30 s) instead of posting a second
 * notification. While the window is open nothing else is posted ([levelDue] false, [alertOpen] true
 * for the tap watcher); the first [levelDue] after it is true at once, also in report mode, so the
 * service restores the normal notification (with the tap state in force at that moment).
 */
class NotificationCadence(private val everyMs: Long = 2000) {
    private var reportMode = false
    private var lastLevelMs = Long.MIN_VALUE / 2

    /** Whether the run is in report mode now. */
    @get:Synchronized val inReportMode: Boolean get() = reportMode

    /** The mode is now [report]: true if it changed, i.e. the notification must be re-posted now. */
    @Synchronized
    fun modeChanged(report: Boolean): Boolean {
        if (report == reportMode) return false
        reportMode = report
        lastLevelMs = Long.MIN_VALUE / 2
        return true
    }

    private var tapKey = Pair(false, false)

    /**
     * 0.6.9: the notification's tap now opens the report ([contentTap]) and/or it has the «Melden»
     * button ([button]): true if that changed, i.e. the notification must be re-posted now. In the
     * Online-Version a change here always comes with a change of [modeChanged] (0.6.8 behaviour).
     */
    @Synchronized
    fun tapChanged(contentTap: Boolean, button: Boolean): Boolean {
        val k = Pair(contentTap, button)
        if (k == tapKey) return false
        tapKey = k
        return true
    }

    /** End of the open alert window (epoch ms); [Long.MIN_VALUE]: none. */
    private var alertUntilMs = Long.MIN_VALUE
    /** An alert was posted and the normal notification has not been restored yet. */
    private var restoreAfterAlert = false

    /**
     * 0.7.6: an alert replaces the notification now ([nowMs]) until [untilMs]. True if the window is
     * open, i.e. the service must post the alert now (once); a later alert extends the window.
     */
    @Synchronized
    fun alertUntil(nowMs: Long, untilMs: Long): Boolean {
        if (untilMs <= nowMs) return false
        alertUntilMs = untilMs
        restoreAfterAlert = true
        return true
    }

    /** Whether an alert window is open at [nowMs]: then nothing may overwrite the alert. */
    @Synchronized
    fun alertOpen(nowMs: Long): Boolean = nowMs < alertUntilMs

    /** The measurement stopped: no window, nothing to restore. */
    @Synchronized
    fun endAlert() {
        alertUntilMs = Long.MIN_VALUE
        restoreAfterAlert = false
    }

    /**
     * A second's result at [nowMs]: whether to post the notification now. Never while an alert window
     * is open; once right after it (also in report mode: the service then posts the tap's fixed text
     * or the level text); otherwise the level text every [everyMs], never in report mode.
     */
    @Synchronized
    fun levelDue(nowMs: Long): Boolean {
        if (nowMs < alertUntilMs) return false
        if (restoreAfterAlert) {
            restoreAfterAlert = false
            lastLevelMs = nowMs
            return true
        }
        if (reportMode || nowMs - lastLevelMs < everyMs) return false
        lastLevelMs = nowMs
        return true
    }
}
