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
 * ui/LoudAlerts.reportTap (src/karte); the Offline and Labor builds never have one.
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

    /** A second's result at [nowMs]: whether to post the level text now (never in report mode). */
    @Synchronized
    fun levelDue(nowMs: Long): Boolean {
        if (reportMode || nowMs - lastLevelMs < everyMs) return false
        lastLevelMs = nowMs
        return true
    }
}
